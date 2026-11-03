package com.coby.deskly.backup;

import com.coby.deskly.core.Shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 定位目标桌面的布局数据库。
 *
 * <p>不预设路径：MIUI 有 {@code /data/data} 与 {@code /data/user_de/0} 两条真实路径，
 * 不同版本还不一样，其它厂商的名字更是五花八门。所以这里把所有候选目录<b>一次列全</b>，
 * 再按规则排序挑选，顺带把属主与 SELinux 上下文记下来——恢复时要用它核对"原文件没被换掉"。
 *
 * <p><b>两条真机踩出来的规则：</b>
 *
 * <ol>
 *   <li><b>两个目录都要看，不能"第一个非空就收工"。</b>HyperOS 上
 *       {@code /data/data/com.miui.home/databases} 里只有 {@code onetrack_cache.db}，
 *       而真正的布局库在 {@code /data/user_de/0/com.miui.home/databases/launcher4x6.db}。
 *       旧逻辑一看到第一个目录非空就不再往第二个看，于是永远找不到 launcher 系文件。</li>
 *   <li><b>{@code launcher*.db} 前缀优先。</b>同目录里还有 {@code app_category_new.db}、
 *       {@code onetrack_cache.db} 等十来个库，而 {@code ls -1} 是字母序，早先"认不出名字
 *       就取第一个 .db"会把<b>分类库</b>当成<b>桌面布局库</b>——备份了一份没用的文件、
 *       恢复时还写错了地方，而界面上一切正常。</li>
 * </ol>
 */
public final class LauncherDb {

    /** 数据库文件名候选，按优先级排列。 */
    private static final String[] NAME_PREFERENCE = {
            "launcher.db", "launcher4x6.db", "launcher4x5.db", "launcher5x5.db",
            "launcher6x5.db", "launcher4x7.db", "launcher5x6.db",
    };

    /**
     * 已知的"不是布局库"的名字前缀。它们和 launcher 放在同一目录，体积有时还更大，
     * 一旦被误选，备份看起来成功、恢复却毫无效果——比直接失败更糟。
     */
    private static final String[] NOT_LAYOUT = {
            "app_category", "app_status", "onetrack", "one_track", "universal_ot",
            "miui_widget", "drawer", "download_install",
    };

    public final String pkg;
    /** 主库绝对路径。 */
    public final String path;
    public final long size;
    public final String owner;
    public final String mode;
    public final String context;

    private LauncherDb(String pkg, String path, long size, String owner, String mode, String context) {
        this.pkg = pkg;
        this.path = path;
        this.size = size;
        this.owner = owner;
        this.mode = mode;
        this.context = context;
    }

    public String walPath() {
        return path + "-wal";
    }

    public String shmPath() {
        return path + "-shm";
    }

    /**
     * 探测失败返回 null，并可用 {@link #lastError} 说明原因。
     */
    public static String lastError = null;

    public static LauncherDb discover(String pkg) {
        lastError = null;
        String main = "/data/data/" + pkg + "/databases";
        String de = "/data/user_de/0/" + pkg + "/databases";
        String[] dirs = {main, de};

        // 一条命令列完两个目录：每多一次 su 往返，就可能多弹一次授权框，代价远大于收益。
        // @@ 行是目录分隔标记，避免两个目录的条目混在一起、分不清哪个文件在哪。
        StringBuilder cmd = new StringBuilder("for d in");
        for (String d : dirs) {
            cmd.append(' ').append(Shell.q(d));
        }
        cmd.append("; do echo \"@@$d\"; ls -1 \"$d\" 2>/dev/null; done");

        Shell.Out o = Shell.su(cmd.toString());
        Map<String, List<String>> listing = new LinkedHashMap<>();
        String cur = null;
        for (String line : o.text.split("\n")) {
            String t = line.trim();
            if (t.startsWith("@@")) {
                cur = t.substring(2);
                listing.put(cur, new ArrayList<>());
            } else if (!t.isEmpty() && cur != null) {
                listing.get(cur).add(t);
            }
        }

        boolean anyListing = false;
        for (List<String> v : listing.values()) {
            if (!v.isEmpty()) {
                anyListing = true;
                break;
            }
        }
        if (!anyListing) {
            String raw = o.text.trim();
            lastError = "读不到 " + main + " 或 " + de
                    + (raw.isEmpty() ? "（目录不存在，或 root 不可用）" : "（" + firstLine(raw) + "）");
            return null;
        }

        // 第一优先：桌面进程此刻真正打开着的那个库。
        // 名字规则再完善也是猜——HyperOS 会为不同网格同时留着 launcher4x6.db / launcher5x6.db，
        // 猜错就是"写入全成功、桌面没变化"。进程的 fd 不会骗人。
        String live = liveLayoutDb(pkg, listing);
        String full;
        if (live != null) {
            full = live;
        } else {
            String[] hit = pickAll(listing);
            if (hit == null) {
                lastError = "目录里没有找到 launcher 系数据库：" + describe(listing);
                return null;
            }
            full = hit[0] + "/" + hit[1];
        }

        // toybox 的 stat 支持 -c，格式串用 | 分隔，避免文件名含空格时解析错位
        Shell.Out st = Shell.su("stat -c '%s|%u|%g|%a' " + Shell.q(full) + " 2>/dev/null");
        long size = -1;
        String owner = "?";
        String mode = "?";
        if (st.ok()) {
            String[] p = st.line().split("\\|");
            if (p.length >= 4) {
                size = parseLong(p[0], -1);
                owner = p[1] + ":" + p[2];
                mode = p[3];
            }
        }

        // SELinux 上下文只在诊断报告里有意义，取不到不算失败
        String ctx = "";
        Shell.Out z = Shell.su("ls -Zd " + Shell.q(full) + " 2>/dev/null");
        if (z.ok() && z.text.contains(":")) {
            String first = z.line();
            int sp = first.indexOf(' ');
            ctx = sp > 0 ? first.substring(0, sp) : "";
        }

        return new LauncherDb(pkg, full, size, owner, mode, ctx);
    }

    /**
     * 桌面进程打开着的那个布局库；抓不到返回 null。
     *
     * <p>只接受落在候选目录里的路径——{@code /proc/<pid>/fd} 也可能有别的包的同名库被映射进来，
     * 那不属于我们要改的目标。
     */
    private static String liveLayoutDb(String pkg, Map<String, List<String>> listing) {
        try {
            String live = com.coby.deskly.core.LiveDb.scan(pkg).layoutDb();
            if (live == null) {
                return null;
            }
            for (String dir : listing.keySet()) {
                if (live.startsWith(dir + "/")) {
                    return live;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 跨目录挑选库文件。
     *
     * <p>三级优先级：已知名字 → 任意 {@code launcher*.db} → 任意 .db（先剔掉已知的非布局库）。
     * 同一级出现多个候选时，用<b>一条</b> stat 把体积一次取回，选最大的——正在使用的库通常最胖。
     *
     * @return {@code {目录, 文件名}}，没得挑返回 null
     */
    private static String[] pickAll(Map<String, List<String>> byDir) {
        // 第一级：名字完全匹配的，按 NAME_PREFERENCE 顺序
        for (String pref : NAME_PREFERENCE) {
            for (Map.Entry<String, List<String>> e : byDir.entrySet()) {
                if (e.getValue().contains(pref)) {
                    return new String[]{e.getKey(), pref};
                }
            }
        }

        // 第二级：任意 launcher* 前缀（launcher4x6.db / launcher5x6.db / 未来新名字）
        List<String[]> launchers = new ArrayList<>();
        collect(byDir, launchers, false);
        if (!launchers.isEmpty()) {
            return biggestOf(launchers);
        }

        // 第三级：连 launcher 系都没有，才退回任意 .db，但要先剔掉已知的非布局库
        List<String[]> rest = new ArrayList<>();
        collect(byDir, rest, true);
        return rest.isEmpty() ? null : biggestOf(rest);
    }

    /** 收集候选；{@code skipKnownBad} 为 true 时过滤掉非布局库。 */
    private static void collect(Map<String, List<String>> byDir, List<String[]> out,
                                boolean skipKnownBad) {
        for (Map.Entry<String, List<String>> e : byDir.entrySet()) {
            for (String n : e.getValue()) {
                // -wal / -shm / -journal 不以 .db 结尾，天然被排除
                if (!n.endsWith(".db")) {
                    continue;
                }
                if (skipKnownBad) {
                    if (isKnownNonLayout(n) || n.startsWith("launcher")) {
                        continue;
                    }
                } else if (!n.startsWith("launcher")) {
                    continue;
                }
                out.add(new String[]{e.getKey(), n});
            }
        }
    }

    static boolean isKnownNonLayout(String name) {
        String n = name.toLowerCase(Locale.US);
        for (String bad : NOT_LAYOUT) {
            if (n.startsWith(bad)) {
                return true;
            }
        }
        return false;
    }

    /** 多个候选取最大。取不到体积就退回第一个，保证总有返回值。 */
    private static String[] biggestOf(List<String[]> cands) {
        if (cands.size() == 1) {
            return cands.get(0);
        }
        StringBuilder cmd = new StringBuilder("stat -c '%s %n'");
        for (String[] c : cands) {
            cmd.append(' ').append(Shell.q(c[0] + "/" + c[1]));
        }
        cmd.append(" 2>/dev/null");
        Shell.Out st = Shell.su(cmd.toString());
        if (!st.ok()) {
            return cands.get(0);
        }
        String bestPath = null;
        long bestSize = -1;
        for (String line : st.text.split("\n")) {
            String t = line.trim();
            int sp = t.indexOf(' ');
            if (sp <= 0) {
                continue;
            }
            long sz = parseLong(t.substring(0, sp), -1);
            String full = t.substring(sp + 1).trim();
            if (sz > bestSize) {
                bestSize = sz;
                bestPath = full;
            }
        }
        if (bestPath == null) {
            return cands.get(0);
        }
        int slash = bestPath.lastIndexOf('/');
        if (slash <= 0) {
            return cands.get(0);
        }
        return new String[]{bestPath.substring(0, slash), bestPath.substring(slash + 1)};
    }

    private static String describe(Map<String, List<String>> byDir) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : byDir.entrySet()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(e.getKey()).append(" → ")
                    .append(e.getValue().isEmpty() ? "空" : e.getValue().size() + " 项");
        }
        return sb.toString();
    }

    private static String firstLine(String s) {
        for (String line : s.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                return t.length() > 80 ? t.substring(0, 80) + "…" : t;
            }
        }
        return s;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    /** 给界面用的一行摘要。 */
    public String summary() {
        return path + "\n" + human(size) + " · 属主 " + owner + " · " + mode
                + (context.isEmpty() ? "" : "\n" + context);
    }

    public static String human(long bytes) {
        if (bytes < 0) {
            return "未知大小";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
