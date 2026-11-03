package io.github.cobylinweiqi.deskly.backup;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.cobylinweiqi.deskly.core.DbInject;
import io.github.cobylinweiqi.deskly.organize.DeskPlan;
import io.github.cobylinweiqi.deskly.organize.DesktopModel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把「按方案整理后的整张桌面」写进库。
 *
 * <p>这是真正重建布局的那一层：新建文件夹、把所有成员挪进去、删掉旧的空文件夹、
 * 补齐屏表。与 {@link LayoutWriter}（只改单条 container）共用同一套"在副本上改、
 * checkpoint、整体灌库"的路径，区别只是改动的规模。
 *
 * <p><b>三条来自真机数据的硬约束</b>（不要凭 Launcher3 的印象改）：
 *
 * <ol>
 *   <li>文件夹成员的 {@code screen} 必须是 <b>-1</b>，不是 0、也不是父文件夹所在屏。
 *       真库 147 条成员无一例外。</li>
 *   <li>成员在文件夹里的位置由 <b>{@code cellX = 组内序号}、{@code cellY = 序号/4}</b>
 *       表达。真机上 16 个成员的文件夹正好是 cellX 0..15、cellY 0..3，说明它不是网格坐标
 *       而是"第几个"的编码，行号只是被顺手写成 4 个一行的结果。</li>
 *   <li>文件夹本体在桌面是 {@code spanX=spanY=2}，一屏 4×6 只能放 6 个。</li>
 * </ol>
 *
 * <p>写库之后的生效问题不在这一层，全部交给 {@link DbInject}——那里才是"看起来成功、
 * 其实没生效"的根源。
 */
public final class DeskWriter {

    public static final class Result {
        public boolean ok;
        /**
         * 真库文件是否已经被我们覆盖过（不管后续核对成不成立）。
         *
         * <p>界面靠它区分两种"失败"：<b>没写</b>（桌面原样，可以放心重试）与
         * <b>写了但核对没过</b>（桌面已经变了，不能再拿旧预览去写第二遍）。
         */
        public boolean wrote;
        public String msg = "";
        public String snapshotId = "";
        /** 新建 + 复用的文件夹数。 */
        public int folderCount;
        public int moved;
        public int removedFolders;
        /** 写完回读真库，确认已经落在新文件夹里的图标数；回读本身失败为 -1。 */
        public int verified = -1;
        /** 回读结论，直接拼进成功文案。 */
        public String verdict = "";
    }

    private DeskWriter() {
    }

    public static Result apply(Context ctx, String pkg, DeskPlan plan) {
        Result r = new Result();
        if (plan == null || plan.buckets.isEmpty()) {
            r.msg = "没有整理出任何分组";
            return r;
        }

        // 1) 回滚点。它同时是后面工作副本的来源（快照内部已把 WAL 合并掉）
        Snapshot pre = BackupStore.create(ctx, pkg, Snapshot.LABEL_PRE_APPLY);
        if (pre == null) {
            r.msg = "改动前快照失败，已中止：" + BackupStore.lastError;
            return r;
        }
        r.snapshotId = pre.id;

        File base = BackupStore.root(ctx);
        File src = pre.dbFile(base);
        File work = new File(base, "work-desk-" + pre.id + ".db");
        try {
            copy(src, work);
        } catch (Throwable t) {
            r.msg = "无法准备工作副本：" + t;
            return r;
        }

        // 2) 只在副本上重建
        List<Integer> folderIds = new ArrayList<>();
        try {
            rebuild(work, plan, r, folderIds);
        } catch (Throwable t) {
            r.msg = "在副本上重建布局失败：" + t + "\n桌面未被改动，可用快照 " + pre.id + " 回滚";
            clean(work);
            return r;
        }
        if (r.moved != plan.total) {
            r.msg = "校验不一致：计划移动 " + plan.total + " 个，实际落入文件夹 "
                    + r.moved + " 个。桌面未被改动。";
            clean(work);
            return r;
        }

        // 3) 让副本自洽后整体灌进真库
        if (!checkpoint(work)) {
            r.msg = "副本合并 WAL 失败，已中止（真库未被改动）";
            clean(work);
            return r;
        }
        DbInject.Result inj = DbInject.write(pkg, pre.dbPath, work, 150);
        // wroteAt/wroteSize 是灌库那一刻就地取的值：非 0 就说明 cat 真的执行过。
        // 后面不管怎么判，界面都得知道"桌面已经被改过了"。
        r.wrote = inj.wroteAt > 0 && inj.wroteSize > 0;
        if (!inj.ok) {
            r.msg = "写回真库失败：" + inj.log + "\n回滚点：" + pre.id;
            clean(work);
            return r;
        }
        clean(work);

        if (!inj.targetIsLive) {
            // 写进去的是另一个文件，桌面读不到——这是真失败，不能算成功
            r.msg = "写入了 " + pre.dbPath + "，但桌面此刻打开的是 " + inj.livePath
                    + "，布局不会变。回滚点：" + pre.id;
            return r;
        }

        // 4) 到此收工，<b>不再对着真库重算摘要</b>。
        //
        //    这里原先有一道"写完之后再 sha256sum 真库，和副本比"的校验，0.11.0 在真机上
        //    就是被它坑的：灌库的最后一步会 input keyevent 3 把桌面拉回来，Launcher 启动
        //    时自己开库、合并 WAL、补齐自己的字段，几秒后摘要必然与当初写进去的不同。
        //    于是"桌面明明已经按方案整理好了"，界面却弹「整理失败」并劝用户拿快照回滚——
        //    用户照做就等于把刚整理好的桌面亲手撤销。
        //
        //    真正的写入核对在 DbInject 内部完成：cat 完立刻 stat + sha256sum，
        //    那个时刻桌面还没开始读库，比的就是该比的东西。结论通过 inj.brief()
        //    作为"附注"拼进成功文案（已写入但内容核对不一致 / 桌面进程比库旧），
        //    不再让它决定成败。

        // 5) 回读真库，数一遍"到底有多少图标落在了我们建的文件夹里"，顺便核对屏号与格位。
        StringBuilder moved = new StringBuilder();
        StringBuilder placed = new StringBuilder();
        r.verified = verifyBack(ctx, pkg, plan, folderIds, moved, placed);
        if (r.verified < 0) {
            r.verdict = "回读失败（" + DbCopy.lastError + "），以写入时的核对为准";
        } else if (r.verified == plan.total) {
            r.verdict = "回读 " + r.verified + "/" + plan.total + " 个图标已在新文件夹里";
        } else {
            r.verdict = "⚠ 回读只有 " + r.verified + "/" + plan.total
                    + " 个图标在新文件夹里——桌面可能把它又改了，先看一眼再决定要不要回滚";
        }
        if (moved.length() > 0) {
            r.verdict += "\n⚠ 屏号和计划不一致：" + moved;
        }
        if (r.verified >= 0) {
            // 位置能对上就是"每屏从左上角密排"的直接证据：格位是计划算的，
            // 库里也是这个格位，说明桌面没有再把它挪开。
            r.verdict += placed.length() == 0
                    ? "\n位置与计划一致（每屏从左上角开始排）"
                    : "\n⚠ 格位和计划不一致：" + placed;
        }

        r.ok = true;
        r.msg = "已整理 " + r.moved + " 个图标 · " + r.folderCount + " 个文件夹"
                + (r.removedFolders > 0 ? " · 清掉 " + r.removedFolders + " 个旧文件夹" : "")
                + " · " + inj.brief() + "\n" + r.verdict;
        return r;
    }

    // ───────────────────────── 重建 ─────────────────────────

    private static void rebuild(File work, DeskPlan plan, Result r, List<Integer> folderIds)
            throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                work.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            Set<String> cols = columns(db, "favorites");

            // 旧文件夹的 id 集合：只用来判断"哪些要从库里删掉"。
            // 名字匹配不在这里做——库里的 title 可能是 com.miui.home:string/xxx 这种
            // 资源引用，只有 DesktopModel 读过之后才知道它叫「工具」。
            Set<Integer> oldIds = new LinkedHashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT _id FROM favorites WHERE itemType=21 AND container=-100", null)) {
                while (c.moveToNext()) {
                    oldIds.add(c.getInt(0));
                }
            }

            // 有非应用成员的旧文件夹不能删：那些成员（组件之类）不进新文件夹，删了会成孤儿
            Set<Integer> protectedFolders = new HashSet<>();
            try (Cursor c = db.rawQuery(
                    "SELECT DISTINCT container FROM favorites WHERE container>0"
                            + " AND itemType NOT IN (0,14)", null)) {
                while (c.moveToNext()) {
                    protectedFolders.add(c.getInt(0));
                }
            }

            db.beginTransaction();
            try {
                Set<Integer> kept = new HashSet<>();
                for (DeskPlan.Bucket b : plan.buckets) {
                    Integer id = plan.reusable.get(b.group);
                    if (id != null && !oldIds.contains(id)) {
                        id = null;
                    }
                    if (id != null && kept.contains(id)) {
                        id = null;
                    }
                    boolean reused = id != null;
                    if (reused) {
                        // 复用：只改它自己在桌面上的位置
                        ContentValues v = new ContentValues();
                        v.put("container", DesktopModel.CONTAINER_DESKTOP);
                        v.put("screen", b.screen);
                        v.put("cellX", b.cellX);
                        v.put("cellY", b.cellY);
                        v.put("spanX", 2);
                        v.put("spanY", 2);
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        db.update("favorites", v, "_id=?", new String[]{String.valueOf(id)});
                        kept.add(id);
                    } else {
                        ContentValues v = new ContentValues();
                        v.put("title", b.group);
                        v.put("intent", (String) null);
                        v.put("container", DesktopModel.CONTAINER_DESKTOP);
                        v.put("screen", b.screen);
                        v.put("cellX", b.cellX);
                        v.put("cellY", b.cellY);
                        v.put("spanX", 2);
                        v.put("spanY", 2);
                        v.put("itemType", DesktopModel.TYPE_FOLDER);
                        putIf(v, cols, "appWidgetId", -1);
                        putIf(v, cols, "itemFlags", 0);
                        putIf(v, cols, "profileId", 0);
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        putIf(v, cols, "originWidgetId", -1);
                        long newId = db.insert("favorites", null, v);
                        if (newId <= 0) {
                            throw new IllegalStateException("新建文件夹「" + b.group + "」失败");
                        }
                        kept.add((int) newId);
                        id = (int) newId;
                    }
                    // 记下来，写完之后回读真库要按这些 container 数成员
                    folderIds.add(id);

                    // 组内成员：cellX = 序号，cellY = 序号/4（真机编码方式，见类注释）
                    int idx = 0;
                    for (DesktopModel.Item it : b.members) {
                        ContentValues v = new ContentValues();
                        v.put("container", id);
                        v.put("screen", -1);
                        v.put("cellX", idx);
                        v.put("cellY", idx / DesktopModel.FOLDER_COLUMNS);
                        putIf(v, cols, "extendContainer", -1);
                        putIf(v, cols, "isOnlySupportExtend", 0);
                        int n = db.update("favorites", v, "_id=?",
                                new String[]{String.valueOf(it.id)});
                        if (n != 1) {
                            throw new IllegalStateException(
                                    "「" + it.title + "」写入文件夹失败（影响 " + n + " 行）");
                        }
                        r.moved++;
                        idx++;
                    }

                    // 复用的旧文件夹里可能还留着非应用成员——真机的「工具」文件夹里就有一个
                    // itemType=1 的「查找」入口。它们的序号必须接在新成员后面，
                    // 否则会和某个应用抢同一个格子，Launcher 上表现为图标重叠或丢失。
                    if (reused) {
                        for (int leftover : leftovers(db, id, b)) {
                            ContentValues v = new ContentValues();
                            v.put("cellX", idx);
                            v.put("cellY", idx / DesktopModel.FOLDER_COLUMNS);
                            db.update("favorites", v, "_id=?",
                                    new String[]{String.valueOf(leftover)});
                            idx++;
                        }
                    }
                    r.folderCount++;
                }

                // 没被复用的旧文件夹删掉；被保护的不碰
                int removed = 0;
                for (int id : oldIds) {
                    if (kept.contains(id) || protectedFolders.contains(id)) {
                        continue;
                    }
                    removed += db.delete("favorites", "_id=?", new String[]{String.valueOf(id)});
                }
                r.removedFolders = removed;

                syncScreens(db, plan.screenCount);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }

            // 逐条回读：UPDATE 返回 1 不等于真的落到了该在的地方
            try (Cursor c = db.rawQuery(
                    "SELECT count(*) FROM favorites WHERE container>0 AND itemType IN (0,14)", null)) {
                if (c.moveToFirst()) {
                    int total = c.getInt(0);
                    if (total != plan.total) {
                        throw new IllegalStateException(
                                "回读成员数 " + total + " ≠ 计划 " + plan.total);
                    }
                }
            }
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 某个文件夹里不属于本次整理的非应用成员（小米的「查找」这类系统入口）。 */
    private static List<Integer> leftovers(SQLiteDatabase db, int folderId, DeskPlan.Bucket b) {
        Set<Integer> mine = new HashSet<>();
        for (DesktopModel.Item it : b.members) {
            mine.add(it.id);
        }
        List<Integer> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT _id FROM favorites WHERE container=? AND itemType NOT IN (0,14)"
                        + " ORDER BY cellX",
                new String[]{String.valueOf(folderId)})) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                if (!mine.contains(id)) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    /**
     * 补齐屏表，并清掉彻底空掉的屏。
     *
     * <p>屏表不补，写进去的第 7 屏在桌面里就不存在；空屏不清，整理后从 6 屏缩到 3 屏
     * 会剩下 3 个滑得过去却什么都没有的空白页。
     */
    private static void syncScreens(SQLiteDatabase db, int want) {
        if (!tableExists(db, "screens")) {
            return;
        }
        Set<String> screenCols = columns(db, "screens");
        for (int i = 1; i <= want; i++) {
            try (Cursor c = db.rawQuery("SELECT 1 FROM screens WHERE _id=?",
                    new String[]{String.valueOf(i)})) {
                if (c.moveToFirst()) {
                    continue;
                }
            }
            ContentValues v = new ContentValues();
            v.put("_id", i);
            putIf(v, screenCols, "screenOrder", i - 1);
            putIf(v, screenCols, "screenType", 0);
            db.insert("screens", null, v);
        }

        // 屏号 > want 且一条 favorites 都没有的屏，删掉；第 1 屏保留（可能是刻意留空的）
        List<Integer> empty = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT s._id FROM screens s WHERE s._id>? AND s._id>1"
                        + " AND NOT EXISTS (SELECT 1 FROM favorites f WHERE f.screen=s._id)",
                new String[]{String.valueOf(want)})) {
            while (c.moveToNext()) {
                empty.add(c.getInt(0));
            }
        }
        for (int id : empty) {
            db.delete("screens", "_id=?", new String[]{String.valueOf(id)});
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{name})) {
            return c.moveToFirst();
        }
    }

    private static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> out = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                out.add(c.getString(1));
            }
        }
        return out;
    }

    /** 不同 ROM 的 favorites 列不完全一样，写之前先确认这一列存在，免得整条 SQL 崩掉。 */
    private static void putIf(ContentValues v, Set<String> cols, String key, int value) {
        if (cols.contains(key)) {
            v.put(key, value);
        }
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

    /**
     * 写完回读真库，数一遍落在我们新建 / 复用的那些文件夹里的成员数。
     *
     * <p>为什么先等 900ms：灌库最后一步会把桌面拉回前台，它得先起来才会打开库，
     * 立刻 pull 有可能抓到它启动前的那个瞬间。
     *
     * <p>为什么数 container 而不是比字节：桌面启动后会自己改库（补字段、合并 WAL、
     * 还原它自己的东西），字节必然不同；但"成员在哪个 container"就是布局本身，
     * 这个对不上才算真的没成功。
     */
    private static int verifyBack(Context ctx, String pkg, DeskPlan plan,
                                  List<Integer> folderIds, StringBuilder moved,
                                  StringBuilder placed) {
        if (folderIds.isEmpty()) {
            return -1;
        }
        try {
            Thread.sleep(900);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        File back = DbCopy.pull(ctx, pkg);
        if (back == null) {
            return -1;
        }
        StringBuilder in = new StringBuilder();
        for (int id : folderIds) {
            if (in.length() > 0) {
                in.append(',');
            }
            in.append(id);
        }
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(back.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            try {
                int members = -1;
                try (Cursor c = db.rawQuery("SELECT count(*) FROM favorites"
                        + " WHERE itemType IN (0,14) AND container IN (" + in + ")", null)) {
                    if (c.moveToFirst()) {
                        members = c.getInt(0);
                    }
                }
                // 顺带核对"文件夹是不是还在我写的那个屏上、那个格位上"。
                // 用户报的"钉到第 6 屏却出现在第 2 屏"、"不是从左上角开始排"，
                // 靠这一条就能把责任分清：是桌面把位置改了，还是我们压根没写对。
                int shown = 0;
                int posShown = 0;
                for (int i = 0; i < folderIds.size() && i < plan.buckets.size(); i++) {
                    DeskPlan.Bucket b = plan.buckets.get(i);
                    int as = -999;
                    int ax = -999;
                    int ay = -999;
                    try (Cursor c = db.rawQuery(
                            "SELECT screen,cellX,cellY FROM favorites WHERE _id=?",
                            new String[]{String.valueOf(folderIds.get(i))})) {
                        if (c.moveToFirst()) {
                            as = c.getInt(0);
                            ax = c.getInt(1);
                            ay = c.getInt(2);
                        }
                    }
                    if (as != b.screen && shown++ < 3) {
                        if (moved.length() > 0) {
                            moved.append('、');
                        }
                        moved.append(b.group).append(" 计划第").append(b.screen)
                                .append("屏→库里第").append(as).append("屏");
                    }
                    if ((as != b.screen || ax != b.cellX || ay != b.cellY)
                            && posShown++ < 3) {
                        if (placed.length() > 0) {
                            placed.append('、');
                        }
                        placed.append(b.group).append(" 计划(").append(b.screen).append("屏 ")
                                .append(b.cellX).append(',').append(b.cellY).append(")→库里(")
                                .append(as).append("屏 ").append(ax).append(',').append(ay)
                                .append(')');
                    }
                }
                return members;
            } finally {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return -1;
        } finally {
            DbCopy.delete(back);
        }
    }

    private static void clean(File work) {
        //noinspection ResultOfMethodCallIgnored
        work.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(work.getAbsolutePath() + "-shm").delete();
    }
}
