package io.github.cobylinweiqi.deskly.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.cobylinweiqi.deskly.core.DbInject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

/**
 * 把布局改动写进真实桌面库。
 *
 * <p>整个流程刻意做成"在副本上改，改完再整体替换"，而不是对着真库执行 UPDATE：
 *
 * <ol>
 *   <li>先拍一张 pre-apply 快照（同时也是回滚点）；</li>
 *   <li>把快照复制成工作副本，<b>只在副本上</b>跑 UPDATE；</li>
 *   <li>副本上 checkpoint(TRUNCATE)，让它变成自洽的单文件；</li>
 *   <li>force-stop 桌面 → 删 -wal/-shm → {@code cat 副本 > 真库} → sync；</li>
 *   <li>回读真库，逐条确认改动是否真的落在了目标文件夹里。</li>
 * </ol>
 *
 * <p><b>不要用"真库与副本的 sha256 是否相等"来判定成败。</b>写完之后桌面会被系统重新
 * 拉起，它自己开库、合并 WAL、补字段，摘要必然变化；那条校验只会把已经生效的改动报成
 * 失败，并诱导用户回滚。
 *
 * <p>为什么不直接在真库上 UPDATE：桌面进程持有它的句柄，SQLite 的文件锁在 root 下
 * 虽然能抢到，但桌面内存里的模型不会知道数据变了，下一次它自己写回就会把我们的改动冲掉，
 * 而且中间态可能让 WAL 与主库对不上，轻则布局错乱重则桌面反复崩溃。
 * 整体替换 + 先杀后写，是唯一没有中间态的路径。
 */
public final class LayoutWriter {

    /** 一条"把这个图标挪进那个文件夹"的指令。 */
    public static final class Change {
        public final int itemId;
        public final int folderId;
        public final int cellX;
        public final int cellY;
        /** 仅用于日志与回执。 */
        public String title = "";

        public Change(int itemId, int folderId, int cellX, int cellY) {
            this.itemId = itemId;
            this.folderId = folderId;
            this.cellX = cellX;
            this.cellY = cellY;
        }
    }

    public static final class Result {
        public boolean ok;
        public String msg = "";
        /** 改动前自动存的快照 id，出错时告诉用户拿它回滚。 */
        public String snapshotId = "";
        public int applied;
        /** 真库文件是否已经被覆盖过——用来区分"没写"与"写了但回读不符"。 */
        public boolean wrote;
        public String sha = "";
        /** 写完之后回读真库的结果：这几条改动是否真的留在了库里。 */
        public String verdict = "";
        /** 回读确认生效的条数；-1 表示没回读成功。 */
        public int verified = -1;
        /** 回读逐条确认全部生效。false 表示桌面就算重启也不会看到变化。 */
        public boolean effective;
    }

    private LayoutWriter() {
    }

    public static Result apply(Context ctx, String pkg, List<Change> changes) {
        Result r = new Result();
        if (changes == null || changes.isEmpty()) {
            r.msg = "没有要应用的改动";
            return r;
        }

        // 1) 回滚点。这一步同时把 WAL 合并进副本，后面的工作副本才是完整状态。
        Snapshot pre = BackupStore.create(ctx, pkg, Snapshot.LABEL_PRE_APPLY);
        if (pre == null) {
            r.msg = "改动前快照失败，已中止：" + BackupStore.lastError;
            return r;
        }
        r.snapshotId = pre.id;

        File base = BackupStore.root(ctx);
        File src = pre.dbFile(base);
        File work = new File(base, "work-" + pre.id + ".db");
        try {
            copy(src, work);
        } catch (Throwable t) {
            r.msg = "无法准备工作副本：" + t;
            return r;
        }

        // 2) 只在副本上改
        int applied;
        try {
            applied = rewrite(work, changes);
        } catch (Throwable t) {
            r.msg = "在副本上执行改动失败：" + t;
            clean(work);
            return r;
        }
        if (applied != changes.size()) {
            r.msg = "副本校验不一致：计划 " + changes.size() + " 项，实际生效 " + applied + " 项";
            clean(work);
            return r;
        }

        // 3) 让副本自洽，然后写回真库
        boolean synced = checkpoint(work);
        if (!synced) {
            r.msg = "副本合并 WAL 失败，已中止（真库未被改动）";
            clean(work);
            return r;
        }
        DbInject.Result inj = DbInject.write(pkg, pre.dbPath, work, 120);
        r.wrote = inj.wroteAt > 0 && inj.wroteSize > 0;
        if (!inj.ok) {
            r.msg = "写回真库失败：" + inj.log + "\n回滚点：" + pre.id;
            clean(work);
            return r;
        }
        r.applied = applied;
        r.ok = true;

        // 4) 不再比对真库的 sha256——理由与 DeskWriter 里那一段相同：写完之后桌面会被
        //    系统拉起来，Launcher 自己开库、合并 WAL、补自己的字段，几秒后摘要必然与
        //    我们写进去的不同。拿它当失败判据，就会把"已经生效"报成"整理失败"，
        //    还劝用户拿快照回滚。改动到底有没有落地，交给下一步回读来判断。

        // 5) 再回读一次真库，逐条核对改动是否留在库里。
        //    这一步是"点了没反应"的分水岭：字节落盘了却没生效，和根本没落盘，
        //    修法完全不同——不分开就只能瞎猜。
        r.verified = verifyBack(ctx, pkg, changes);
        if (r.verified < 0) {
            r.verdict = "回读失败：" + DbCopy.lastError;
        } else if (r.verified == changes.size()) {
            r.verdict = "回读 " + r.verified + "/" + changes.size() + " 条已在目标文件夹";
            r.effective = true;
        } else {
            r.verdict = "⚠ 回读只有 " + r.verified + "/" + changes.size()
                    + " 条生效——改动没留在库里";
        }
        r.msg = "已写入 " + applied + " 项 · " + inj.brief() + "\n" + r.verdict;
        clean(work);
        return r;
    }

    /**
     * 写完之后重新拉一份真库，逐条确认改动的条目是否已经落在目标文件夹里。
     *
     * @return 确认生效的条数；回读本身失败返回 -1
     *
     * <p>必须等一下再读：桌面刚被重新拉起，它的 fd 要过几百毫秒才建立起来，
     * 立刻 pull 有可能抓到它启动前的那个瞬间。
     */
    private static int verifyBack(Context ctx, String pkg, List<Change> changes) {
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        File back = DbCopy.pull(ctx, pkg);
        if (back == null) {
            return -1;
        }
        int hit = 0;
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(back.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            try {
                for (Change c : changes) {
                    try (Cursor cur = db.rawQuery(
                            "SELECT container FROM favorites WHERE _id=?",
                            new String[]{String.valueOf(c.itemId)})) {
                        if (cur.moveToFirst() && cur.getInt(0) == c.folderId) {
                            hit++;
                        }
                    }
                }
            } finally {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            DbCopy.delete(back);
            return -1;
        }
        DbCopy.delete(back);
        return hit;
    }

    /**
     * 在副本上执行改动，返回真正改动的行数。
     *
     * <p>用事务包起来：任何一条失败就整体回滚，不留下"改了一半"的库。
     */
    private static int rewrite(File work, List<Change> changes) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                work.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        int affected = 0;
        try {
            db.beginTransaction();
            try {
                for (Change c : changes) {
                    ContentValues v = new ContentValues();
                    v.put("container", c.folderId);
                    // 文件夹成员的 screen 恒为 -1，这是 Launcher3 的约定
                    v.put("screen", -1);
                    v.put("cellX", c.cellX);
                    v.put("cellY", c.cellY);
                    v.put("extendContainer", -1);
                    v.put("isOnlySupportExtend", 0);
                    int n = db.update("favorites", v, "_id=?",
                            new String[]{String.valueOf(c.itemId)});
                    if (n == 1) {
                        affected++;
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // 逐条读回确认，避免 update 返回 1 但其实没落地
            for (Change c : changes) {
                int container = -999;
                try (Cursor cur = db.rawQuery(
                        "SELECT container FROM favorites WHERE _id=?",
                        new String[]{String.valueOf(c.itemId)})) {
                    if (cur.moveToFirst()) {
                        container = cur.getInt(0);
                    }
                }
                if (container != c.folderId) {
                    throw new IllegalStateException("条目 " + c.itemId + " 未落入目标文件夹");
                }
            }
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
        return affected;
    }

    private static boolean checkpoint(File work) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(work.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READWRITE);
            try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                c.moveToFirst();
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void copy(File from, File to) throws Exception {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.getFD().sync();
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
            try {
                out.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 工作副本既已写入真库就没用了，留着只会占空间、还会让快照列表混乱。 */
    private static void clean(File work) {
        //noinspection ResultOfMethodCallIgnored
        work.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-shm").delete();
    }
}
