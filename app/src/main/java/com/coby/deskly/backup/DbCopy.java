package com.coby.deskly.backup;

import android.content.Context;

import com.coby.deskly.core.Shell;

import java.io.File;

/**
 * 把桌面正在用的库拷一份到 app 私有目录，供只读分析。
 *
 * <p>分析永远读副本，绝不碰真库——这是整套设计里唯一不能让步的一条：分析发生在用户
 * 犹豫、反复切方案的时候，这时候去开真库，等于让一个纯读的操作有机会把桌面弄坏。
 *
 * <p>-wal 必须一起拷。桌面此刻可能有大几百 KB 的改动还没 checkpoint，只拷主库会读到
 * 一份"几分钟前"的布局，据此给出的一切建议都是错的。拷完之后 SQLite 打开时会自己把
 * WAL 应用上去。
 */
public final class DbCopy {

    public static String lastError = null;

    private DbCopy() {
    }

    /** 成功返回副本文件，失败返回 null（原因见 {@link #lastError}）。用完必须 {@link #delete}。 */
    public static File pull(Context ctx, String pkg) {
        lastError = null;
        LauncherDb db = LauncherDb.discover(pkg);
        if (db == null) {
            lastError = LauncherDb.lastError;
            return null;
        }
        File dir = new File(ctx.getCacheDir(), "probe");
        if (!dir.exists() && !dir.mkdirs()) {
            lastError = "无法创建临时目录";
            return null;
        }
        File copy = new File(dir, "layout.db");
        delete(copy);

        int uid = android.os.Process.myUid();
        String wal = copy.getAbsolutePath() + "-wal";
        String cmd = "cp -f " + Shell.q(db.path) + " " + Shell.q(copy)
                + " ; cp -f " + Shell.q(db.walPath()) + " " + Shell.q(wal) + " 2>/dev/null"
                + " ; chown " + uid + ":" + uid + " " + Shell.q(copy) + " " + Shell.q(wal)
                + " 2>/dev/null"
                + " ; chmod 600 " + Shell.q(copy) + " " + Shell.q(wal) + " 2>/dev/null"
                + " ; echo PULLED";
        Shell.Out o = Shell.su(cmd, 60);
        if (!o.text.contains("PULLED") || !copy.exists() || copy.length() <= 0) {
            delete(copy);
            lastError = "拷贝失败：" + o.text.trim();
            return null;
        }
        return copy;
    }

    /** 副本连同 -wal/-shm 一起删。留下残片会让下一次在旧数据上叠加，读出串味的布局。 */
    public static void delete(File f) {
        if (f == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(f.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(f.getAbsolutePath() + "-shm").delete();
    }
}
