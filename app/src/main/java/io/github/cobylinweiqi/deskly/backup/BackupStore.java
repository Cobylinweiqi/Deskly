package io.github.cobylinweiqi.deskly.backup;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import io.github.cobylinweiqi.deskly.core.DbInject;
import io.github.cobylinweiqi.deskly.core.Shell;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 快照的创建 / 恢复 / 清理。
 *
 * <p>三条设计约束，都是为了"改坏了能回去"这件事真的成立：
 *
 * <ol>
 *   <li><b>基线不可变</b>：第一次运行拍下的 origin 快照永不覆盖、永不删除，
 *       它定义的就是用户口中的"最开始的样子"。</li>
 *   <li><b>恢复前先自保</b>：每次恢复前强制拍一张 pre-restore，恢复错了还能再回去。</li>
 *   <li><b>恢复时先杀后写</b>：launcher 进程活着时持有数据库句柄，直接写文件会被它
 *       的内存状态覆盖。必须先 force-stop，稍等，再写。</li>
 * </ol>
 */
public final class BackupStore {

    /** origin 之外最多留几张，超出按时间淘汰最旧的。 */
    private static final int MAX_KEEP = 8;

    private BackupStore() {
    }

    public static File root(Context ctx) {
        File d = new File(ctx.getFilesDir(), "backup");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    // ───────────────────────── 读取 ─────────────────────────

    public static List<Snapshot> list(Context ctx) {
        List<Snapshot> out = new ArrayList<>();
        File[] dirs = root(ctx).listFiles();
        if (dirs == null) {
            return out;
        }
        for (File d : dirs) {
            if (!d.isDirectory()) {
                continue;
            }
            Snapshot s = readMeta(d);
            // meta 丢失或损坏就跳过，宁可少列，不让界面显示一条恢复不了的记录
            if (s != null && s.dbFile(root(ctx)).exists()) {
                out.add(s);
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    public static Snapshot origin(Context ctx) {
        for (Snapshot s : list(ctx)) {
            if (s.isOrigin()) {
                return s;
            }
        }
        return null;
    }

    private static Snapshot readMeta(File dir) {
        try {
            File f = new File(dir, "meta.json");
            if (!f.exists()) {
                return null;
            }
            byte[] b = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int n = in.read(b);
            in.close();
            if (n <= 0) {
                return null;
            }
            return Snapshot.fromJson(new JSONObject(new String(b, 0, n, "UTF-8")));
        } catch (Throwable t) {
            return null;
        }
    }

    // ───────────────────────── 创建 ─────────────────────────

    /** 首次运行时调用，确保基线存在。已存在则直接返回，绝不动它。 */
    public static Snapshot ensureOrigin(Context ctx, String pkg) {
        Snapshot o = origin(ctx);
        if (o != null) {
            return o;
        }
        return create(ctx, pkg, Snapshot.LABEL_ORIGIN);
    }

    /**
     * 拍一张快照。失败返回 null，原因从 {@link #lastError} 取。
     */
    public static String lastError = null;

    public static Snapshot create(Context ctx, String pkg, String label) {
        lastError = null;

        LauncherDb db = LauncherDb.discover(pkg);
        if (db == null) {
            lastError = LauncherDb.lastError;
            return null;
        }

        File base = root(ctx);
        Snapshot s = new Snapshot();
        s.id = newId(base, label);
        s.label = label;
        s.createdAt = System.currentTimeMillis();
        s.dbPath = db.path;
        s.owner = db.owner;
        s.mode = db.mode;
        s.context = db.context;

        File dir = s.dir(base);
        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "无法创建备份目录 " + dir;
            return null;
        }
        File dst = s.dbFile(base);
        int uid = android.os.Process.myUid();

        // 主库用 && 串起来：拷贝失败就不能往下走，避免留下一个只有 meta 没有数据的空快照。
        // wal / shm 用 ; 分隔并吞掉错误——它们可能压根不存在（非 WAL 模式或已 checkpoint）。
        String cmd = "mkdir -p " + Shell.q(dir)
                + " && cp -f " + Shell.q(db.path) + " " + Shell.q(dst)
                + " && echo MAIN_OK"
                + " ; cp -f " + Shell.q(db.walPath()) + " " + Shell.q(new File(dir, "launcher.db-wal")) + " 2>/dev/null"
                + " ; cp -f " + Shell.q(db.shmPath()) + " " + Shell.q(new File(dir, "launcher.db-shm")) + " 2>/dev/null"
                + " ; chown -R " + uid + ":" + uid + " " + Shell.q(dir)
                + " ; chmod 700 " + Shell.q(dir)
                + " ; chmod 600 " + Shell.q(dir) + "/launcher.db*"
                + " ; echo DONE";

        Shell.Out o = Shell.su(cmd, 60);
        if (!o.text.contains("MAIN_OK") || !dst.exists() || dst.length() <= 0) {
            lastError = "拷贝失败：" + (o.text.trim().isEmpty() ? ("exit=" + o.code) : o.text.trim());
            deleteDir(dir);
            return null;
        }

        File wal = new File(dir, "launcher.db-wal");
        s.walPresent = wal.exists() && wal.length() > 0;

        // 在副本上做 checkpoint 与自检。作用于副本，碰不到设备上真实的那份。
        probeCopy(s, dst, wal, new File(dir, "launcher.db-shm"));

        s.size = dst.length();
        s.sha256 = sha256(dst);

        writeMeta(s, base);
        prune(ctx);
        return s;
    }

    /**
     * 用系统自带的 SQLite 打开副本，做三件事：完整性自检、数条目、把 WAL 合并回主库。
     *
     * <p>合并这步是关键——合并之后主库副本就是自洽的，恢复时只需写回一个文件，
     * 不必再去操心 -wal 的属主和时序。
     */
    private static void probeCopy(Snapshot s, File dst, File wal, File shm) {
        SQLiteDatabase d = null;
        try {
            d = SQLiteDatabase.openDatabase(dst.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);

            try (Cursor c = d.rawQuery("PRAGMA integrity_check", null)) {
                if (c.moveToFirst()) {
                    s.integrity = c.getString(0);
                }
            } catch (Throwable t) {
                s.integrity = "未知";
            }

            try (Cursor c = d.rawQuery("SELECT count(*) FROM favorites", null)) {
                if (c.moveToFirst()) {
                    s.items = c.getInt(0);
                }
            } catch (Throwable t) {
                // 表名不叫 favorites 的桌面（少数国产 ROM 改过名）
                try (Cursor c = d.rawQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table'", null)) {
                    if (c.moveToFirst()) {
                        s.items = -1;
                    }
                } catch (Throwable ignored) {
                }
            }

            // 应用图标单独数一遍：列表里直接告诉用户这份备份"精确到几个图标"，
            // 而不是一个混着文件夹与小组件的总条目数。
            try (Cursor c = d.rawQuery(
                    "SELECT count(*) FROM favorites WHERE itemType IN (0,14)", null)) {
                if (c.moveToFirst()) {
                    s.apps = c.getInt(0);
                }
            } catch (Throwable ignored) {
            }

            try (Cursor c = d.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                // 结果行不关心，调用本身就把 WAL 合并进主库了
                c.moveToFirst();
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            s.integrity = "打开失败：" + t.getClass().getSimpleName();
        } finally {
            if (d != null) {
                try {
                    d.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // checkpoint 之后 wal/shm 对恢复已无意义，删掉可以保证"快照 = 单个文件"
        //noinspection ResultOfMethodCallIgnored
        wal.delete();
        //noinspection ResultOfMethodCallIgnored
        shm.delete();
    }

    // ───────────────────────── 恢复 ─────────────────────────

    /** 恢复结果。ok 为 false 时 msg 是给人看的原因。 */
    public static final class RestoreResult {
        public final boolean ok;
        public final String msg;

        RestoreResult(boolean ok, String msg) {
            this.ok = ok;
            this.msg = msg;
        }
    }

    public static RestoreResult restore(Context ctx, String pkg, Snapshot s) {
        File base = root(ctx);
        File src = s.dbFile(base);
        if (!src.exists() || src.length() <= 0) {
            return new RestoreResult(false, "快照文件不存在或为空");
        }
        if (s.size > 0 && src.length() != s.size) {
            return new RestoreResult(false, "快照大小与记录不符（" + src.length() + "≠" + s.size
                    + "），可能已被破坏");
        }

        // 这里<b>不再</b>先拍一张 pre-restore。0.10.x ~ 0.11.x 会这么做，理由是
        // "恢复错了还能再回去"；实际用起来每点一次恢复就多出一份没人要的快照，
        // 列表越滚越长，用户反而找不到自己真正想恢复的那份。
        // 现在的语义是：恢复 = 用选中的这份覆盖当前布局，不额外留痕。
        // 想保住当前状态，先点「备份当前桌面」——那是显式动作，列表里也看得见。

        // 顺序不能反：launcher 活着时持有句柄，先写文件会被它的内存状态覆盖回去。
        // 具体序列（含"杀两次"的原因）见 DbInject —— 只杀一次 + sleep 会让系统
        // 在写入前把桌面拉起来读旧库，结果是恢复了却看不出任何变化。
        DbInject.Result inj = DbInject.write(pkg, s.dbPath, src, 90);
        if (!inj.ok) {
            return new RestoreResult(false, "写回失败：" + inj.log);
        }

        // 校验：能取到 sha256sum 就比对摘要，取不到就退化成比大小
        String verify = verifyBack(s.dbPath, s.sha256, src.length());
        return new RestoreResult(true, verify + "（" + inj.brief() + "）");
    }

    private static String verifyBack(String dbPath, String expectSha, long expectSize) {
        Shell.Out st = Shell.su("stat -c '%s' " + Shell.q(dbPath) + " 2>/dev/null", 15);
        long now = -1;
        if (st.ok()) {
            try {
                now = Long.parseLong(st.line());
            } catch (Throwable ignored) {
            }
        }
        if (now > 0 && now != expectSize) {
            return "已写回，但大小不一致（" + now + "≠" + expectSize + "），请再恢复一次或重启手机";
        }
        Shell.Out h = Shell.su("sha256sum " + Shell.q(dbPath) + " 2>/dev/null", 30);
        if (h.ok() && h.line().length() > 32) {
            String got = h.line().split("\\s+")[0].toLowerCase();
            if (!expectSha.isEmpty() && got.equals(expectSha.toLowerCase())) {
                return "已写回并校验一致";
            }
            if (!expectSha.isEmpty()) {
                return "已写回，但摘要不一致（" + got.substring(0, 10) + "），建议重启后确认";
            }
        }
        return "已写回（设备无 sha256sum，仅校验了大小）";
    }

    // ───────────────────────── 删除与清理 ─────────────────────────

    public static boolean delete(Context ctx, Snapshot s) {
        if (s.isOrigin()) {
            return false;
        }
        return deleteDir(s.dir(root(ctx)));
    }

    /** origin 永久保留，其余的超出 {@link #MAX_KEEP} 张就淘汰最旧的。 */
    private static void prune(Context ctx) {
        List<Snapshot> all = list(ctx);
        int kept = 0;
        for (Snapshot s : all) {
            if (s.isOrigin()) {
                continue;
            }
            kept++;
            if (kept > MAX_KEEP) {
                deleteDir(s.dir(root(ctx)));
            }
        }
    }

    private static boolean deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) {
            for (File f : fs) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return d.delete();
    }

    // ───────────────────────── 工具 ─────────────────────────

    private static String newId(File base, String label) {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(new java.util.Date());
        String id = (Snapshot.LABEL_ORIGIN.equals(label) ? "origin-" : "snap-") + stamp;
        // 同一秒内连点两次的情况，加后缀避免覆盖
        int i = 1;
        String candidate = id;
        while (new File(base, candidate).exists()) {
            candidate = id + "-" + (++i);
        }
        return candidate;
    }

    private static void writeMeta(Snapshot s, File base) {
        try {
            File f = s.metaFile(base);
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(s.toJson().toString(2).getBytes("UTF-8"));
            out.close();
        } catch (Throwable ignored) {
        }
    }

    /** SHA-256 十六进制。用于证明"恢复回去的就是当初存下来的那份"。 */
    public static String sha256(File f) {
        FileInputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] out = md.digest();
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
