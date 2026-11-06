package io.github.cobylinweiqi.deskly.backup;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import io.github.cobylinweiqi.deskly.core.Shell;
import io.github.cobylinweiqi.deskly.organize.LauncherProfile;

import java.io.File;
import java.util.List;

/**
 * 把桌面正在用的库拷一份到 app 私有目录，供只读分析。
 *
 * <p>分析永远读副本，绝不碰真库——这是整套设计里唯一不能让步的一条：分析发生在用户
 * 犹豫、反复切方案的时候，这时候去开真库，等于让一个纯读的操作有机会把桌面弄坏。
 *
 * <p>-wal 必须一起拷。桌面此刻可能有大几百 KB 的改动还没 checkpoint，只拷主库会读到
 * 一份"几分钟前"的布局，据此给出的一切建议都是错的。拷完之后 SQLite 打开时会自己把
 * WAL 应用上去。
 *
 * <p><b>0.15.1：拷完要验收，不能"文件在就算成功"。</b>真我上出现过库能拷、能打开，
 * 却 {@code no such table: favorites} 的情况——那说明按名字挑中的是同目录里另一个
 * 不相干的 .db，或者这份库根本没有布局表。现在逐个试 {@link LauncherDb#candidates}，
 * 用 {@link LauncherProfile#detect} 确认里面真的有布局表，挑中的路径用
 * {@link LauncherDb#select} 钉住，保证后续快照与写回指向同一个文件。
 * 全部失败时把每个候选的表清单写进 {@link #lastError}，用户一张截图就能定位。
 */
public final class DbCopy {

    public static String lastError = null;

    /** 最多验几个候选，避免同目录几十个 .db 时逐个发 su 拖慢分析。 */
    private static final int MAX_TRY = 8;

    private DbCopy() {
    }

    /** 成功返回副本文件，失败返回 null（原因见 {@link #lastError}）。用完必须 {@link #delete}。 */
    public static File pull(Context ctx, String pkg) {
        lastError = null;
        StringBuilder diag = new StringBuilder();
        diag.append("目标桌面 ").append(pkg).append('\n');

        File hit = tryCandidates(ctx, pkg, diag);
        if (hit != null) {
            return hit;
        }
        // 上次钉住的库可能已经不在了（换桌面、系统清理），清掉再完整扫一次
        if (LauncherDb.hasPin(pkg)) {
            LauncherDb.forget(pkg);
            diag.append("（上次选中的库已失效，重新扫描）\n");
            hit = tryCandidates(ctx, pkg, diag);
            if (hit != null) {
                return hit;
            }
        }

        lastError = "没找到可用的桌面布局库（没有一个 .db 含 favorites 或等价布局表）\n"
                + diag;
        return null;
    }

    private static File tryCandidates(Context ctx, String pkg, StringBuilder diag) {
        LauncherDb db = LauncherDb.discover(pkg);
        if (db == null) {
            diag.append("定位失败：").append(LauncherDb.lastError).append('\n');
            return null;
        }
        File dir = new File(ctx.getCacheDir(), "probe");
        if (!dir.exists() && !dir.mkdirs()) {
            diag.append("无法创建临时目录 ").append(dir).append('\n');
            return null;
        }
        File copy = new File(dir, "layout.db");
        List<String> cands = db.candidates;
        int limit = Math.min(cands.size(), MAX_TRY);
        int uid = android.os.Process.myUid();

        for (int i = 0; i < limit; i++) {
            String path = cands.get(i);
            // 换候选前必须把副本及其 -wal / -shm 全删掉，否则上一份 WAL 会叠加到
            // 下一份库上，读出"串味"的表结构，甚至把不相关的表当成布局表。
            delete(copy);
            String wal = copy.getAbsolutePath() + "-wal";
            String cmd = "cp -f " + Shell.q(path) + " " + Shell.q(copy)
                    + " ; cp -f " + Shell.q(path + "-wal") + " " + Shell.q(wal) + " 2>/dev/null"
                    + " ; chown " + uid + ":" + uid + " " + Shell.q(copy) + " " + Shell.q(wal)
                    + " 2>/dev/null"
                    + " ; chmod 600 " + Shell.q(copy) + " " + Shell.q(wal) + " 2>/dev/null"
                    + " ; echo PULLED";
            Shell.Out o = Shell.su(cmd, 60);
            String note = "[" + (i + 1) + "/" + limit + "] " + path + "\n    ";
            if (!o.text.contains("PULLED") || !copy.exists() || copy.length() <= 0) {
                diag.append(note).append("拷贝失败：")
                        .append(abbrev(o.text.trim().isEmpty() ? "exit=" + o.code : o.text.trim()))
                        .append('\n');
                continue;
            }

            SQLiteDatabase s = null;
            try {
                s = SQLiteDatabase.openDatabase(
                        copy.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
                String tables = LauncherProfile.describe(s);
                LauncherProfile prof = LauncherProfile.detect(s, pkg);
                if (prof.hasLayoutTable()) {
                    diag.append(note).append("可用：表 ").append(prof.table)
                            .append(" · ").append(prof.label()).append('\n');
                    LauncherDb.select(pkg, path);
                    return copy;
                }
                diag.append(note).append("不含布局表；表清单：")
                        .append(abbrev(tables)).append('\n');
            } catch (Throwable t) {
                diag.append(note).append("打不开：").append(abbrev(String.valueOf(t))).append('\n');
            } finally {
                if (s != null) {
                    try {
                        s.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        delete(copy);
        return null;
    }

    private static String abbrev(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() > 320 ? t.substring(0, 320) + "…" : t;
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
