package com.coby.deskly.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 桌面进程此刻<b>真正打开着</b>哪些文件。
 *
 * <p>这是整套流程里唯一的"事实来源"。之前一直靠猜：猜库在 {@code /data/data} 还是
 * {@code /data/user_de/0}，猜 {@code launcher4x6.db} 还是 {@code launcher5x6.db}。
 * 猜错的表现极其恶劣——写入全部返回 0、摘要校验也一致，可桌面毫无变化，因为改的是
 * 一个桌面<b>根本没在读</b>的文件。
 *
 * <p>换成读 {@code /proc/<pid>/fd} 之后这件事就不用猜了：桌面打开的是哪个 .db，
 * 我们就写哪个。同一个进程还顺手给出启动时间，用来判断"它到底有没有重新加载"。
 */
public final class LiveDb {

    /** 桌面进程的 pid。空表示当前没有进程在跑。 */
    public final List<Integer> pids = new ArrayList<>();
    /** 进程打开的文件，按出现顺序去重（含 -wal / -shm）。 */
    public final Set<String> openFiles = new LinkedHashSet<>();
    public String raw = "";

    public boolean alive() {
        return !pids.isEmpty();
    }

    /** 只保留主库（-wal / -shm 是同名后缀，不能当成目标写）。 */
    public List<String> openDbs() {
        List<String> out = new ArrayList<>();
        for (String f : openFiles) {
            if (f.endsWith(".db")) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * 桌面正在读的那个布局库。取不到返回 null。
     *
     * <p>只认 {@code launcher} 前缀：同目录下的 {@code app_category_new.db}、
     * {@code onetrack_cache.db} 也是被打开的，写到它们上面只会更糟。
     */
    public String layoutDb() {
        for (String f : openDbs()) {
            int slash = f.lastIndexOf('/');
            String name = slash >= 0 ? f.substring(slash + 1) : f;
            if (name.startsWith("launcher")) {
                return f;
            }
        }
        return null;
    }

    /**
     * 扫一次。命令只有一条，避免每多一次 su 往返就多一次授权框的风险。
     *
     * <p>{@code pidof} 在某些 ROM 上不存在，所以退回到 {@code ps -A} 再筛一次。
     */
    public static LiveDb scan(String pkg) {
        LiveDb r = new LiveDb();
        String cmd = "PL=$(pidof " + Shell.q(pkg) + " 2>/dev/null)"
                + " ; if [ -z \"$PL\" ]; then"
                + " PL=$(ps -A -o PID,NAME 2>/dev/null | awk '$2 ~ /" + pkg + "/ {print $1}' | tr '\\n' ' ') ; fi"
                + " ; echo \"PIDS:$PL\""
                + " ; for p in $PL; do"
                + "   for f in /proc/$p/fd/*; do"
                + "     t=$(readlink \"$f\" 2>/dev/null)"
                + "     case \"$t\" in *.db|*.db-wal|*.db-shm) echo \"FD:$p|$t\" ;; esac"
                + "   done"
                + " ; done";
        Shell.Out o = Shell.su(cmd, 20);
        r.raw = o.text.trim();
        for (String line : r.raw.split("\n")) {
            String t = line.trim();
            if (t.startsWith("PIDS:")) {
                for (String p : t.substring(5).trim().split("\\s+")) {
                    try {
                        int pid = Integer.parseInt(p.trim());
                        if (!r.pids.contains(pid)) {
                            r.pids.add(pid);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            } else if (t.startsWith("FD:")) {
                int bar = t.indexOf('|');
                if (bar > 3) {
                    String path = t.substring(bar + 1).trim();
                    if (path.startsWith("/")) {
                        r.openFiles.add(path);
                    }
                }
            }
        }
        return r;
    }

    /** 给诊断用的一行摘要。 */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(alive() ? "桌面进程 pid " + join() : "桌面进程未运行");
        String db = layoutDb();
        sb.append("\n当前打开的布局库：").append(db == null ? "（没抓到，可能还没启动完）" : db);
        return sb.toString();
    }

    private String join() {
        StringBuilder sb = new StringBuilder();
        for (int p : pids) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(p);
        }
        return sb.toString();
    }
}
