package io.github.cobylinweiqi.deskly.core;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/**
 * 把一份自洽的数据库文件整体灌进桌面正在用的那个库。
 *
 * <p><b>这里藏着整件事唯一真正的难点，而且它不报错。</b>最初的写法是
 * {@code am force-stop → sleep 1 → rm wal → cat 副本 > 真库}，命令全部返回 0、
 * 回执也写着"已写入"，但桌面看起来毫无变化。原因是那一秒：
 *
 * <ol>
 *   <li>force-stop 把桌面杀了，<b>但系统把它当 HOME，几百毫秒后就会重新拉起</b>；</li>
 *   <li>{@code sleep 1} 正好让新进程在这个窗口里完成启动、<b>打开的还是旧库</b>；</li>
 *   <li>我们再 {@code cat} 覆盖，改的是同一个 inode，可新进程早把布局读进内存了，
 *       它不会因为文件变了就重读；</li>
 *   <li>桌面此后随便写点什么，就把内存里的旧布局刷回库，我们的改动连痕迹都不剩。</li>
 * </ol>
 *
 * <p>所以正确顺序是：<b>杀 → 等它真的没了 → 写 → 再杀一次</b>。第二次杀是关键——
 * 此时库里已经是新数据，系统再把桌面拉起来，它读到的就是新布局。
 *
 * <p><b>比"杀了没有"更准的一条判据：进程是不是比库还老。</b>光看 pidof 空过一次不算数，
 * 系统可能在我们往下执行的两条命令之间就把它拉起来了。这里改用
 * {@code /proc/<pid>} 的创建时间（≈ 进程启动时刻）与写入时刻比较：
 *
 * <ul>
 *   <li>进程启动时间 <b>早于</b>写入 → 它读的是旧库，内存里的旧布局迟早把库盖回去，必须再杀；</li>
 *   <li>进程启动时间 <b>晚于</b>写入 → 它打开的是新库，改动已经生效，收工。</li>
 * </ul>
 *
 * <p>这个判据不看库文件的 mtime，因为"旧进程把内存刷回库"这件事本身就会让 mtime 变新，
 * 用它做基准会把最坏的情况误判成正常。
 */
public final class DbInject {

    public static final class Result {
        public boolean ok;
        /** 完整回执，含 gone=[] / proc=[]，失败时用它定位。 */
        public String log = "";
        /** 写入瞬间桌面进程是否已经退出。false 意味着这次很可能被覆盖回去。 */
        public boolean processGone;
        /** 写完的字节数是否与源文件一致（有 sha256sum 时用摘要比，没有时退化成比大小）。 */
        public boolean bytesOk;
        /** 收工时桌面进程是否仍早于写入时刻——true 表示改动大概率被它盖回去。 */
        public boolean staleProcess;
        /** 解析出来的核对字段，直接进回执，不让用户对着"成功"发呆。 */
        public long wroteSize = -1;
        public long wroteAt = -1;
        public String procLine = "";
        /** 动手前抓到的、桌面进程真正打开着的布局库；抓不到为空。 */
        public String livePath = "";
        /** 我们要写的库是不是桌面正在读的那一个。false 表示写错了文件，桌面永远不会变。 */
        public boolean targetIsLive = true;

        public String brief() {
            if (!ok) {
                return log;
            }
            if (!targetIsLive) {
                return "已写入，但写的不是桌面正在读的库"
                        + "（桌面打开：" + (livePath.isEmpty() ? "未抓到" : livePath) + "）";
            }
            if (!bytesOk) {
                return "已写入，但内容核对不一致（库里 " + wroteSize + " B）";
            }
            if (staleProcess) {
                return "已写入，但桌面进程比库还旧（" + procLine + "），改动可能被它盖回去";
            }
            return "已写入并重启桌面";
        }
    }

    private DbInject() {
    }

    /**
     * @param pkg    桌面包名，用来 force-stop / pidof
     * @param dbPath 设备上真实的库路径
     * @param src    app 私有目录里那份改好的、已 checkpoint 的单文件库
     */
    public static Result write(String pkg, String dbPath, File src, int timeoutSec) {
        Result r = new Result();
        if (src == null || !src.exists() || src.length() <= 0) {
            r.log = "工作副本不存在或为空";
            return r;
        }
        final long srcLen = src.length();
        final String srcSha = sha256(src);

        // 动手之前先问一句"桌面现在读的是哪个文件"。写错库的典型症状就是全都成功、
        // 桌面毫无变化，而且任何摘要校验都查不出来——因为文件确实被改了，只是没人读它
        String live = "";
        try {
            live = LiveDb.scan(pkg).layoutDb();
        } catch (Throwable ignored) {
        }
        if (live != null && !live.isEmpty()) {
            r.livePath = live;
            r.targetIsLive = live.equals(dbPath);
        }

        String q = Shell.q(pkg);
        String d = Shell.q(dbPath);
        String w = Shell.q(dbPath + "-wal");
        String s = Shell.q(dbPath + "-shm");

        StringBuilder c = new StringBuilder();
        // 1) 先杀一次
        c.append("am force-stop ").append(q).append(" 2>/dev/null");
        // 2) 等它真的没了（最多 4 秒）。桌面是 HOME，杀完会被系统重新拉起，
        //    但通常有几百毫秒空窗，够我们把文件换掉
        c.append(" ; n=0 ; while [ $n -lt 40 ]; do")
                .append(" L=$(pidof ").append(q).append(" 2>/dev/null); [ -z \"$L\" ] && break")
                .append(" ; sleep 0.1; n=$((n+1)); done");
        c.append(" ; GONE=$(pidof ").append(q).append(" 2>/dev/null)");
        // 3) 换文件。-wal/-shm 必须先删：桌面留下的旧 WAL 与我们的新主库对不上，
        //    SQLite 打开时会拿 WAL 里的旧页覆盖掉刚写进去的内容
        c.append(" ; rm -f ").append(w).append(' ').append(s);
        c.append(" ; cat ").append(Shell.q(src)).append(" > ").append(d);
        c.append(" ; sync");
        // 4) 就地取证：写入时刻、落盘大小、摘要
        c.append(" ; WROTE=$(date +%s)");
        c.append(" ; SZ=$(stat -c '%s' ").append(d).append(" 2>/dev/null)");
        c.append(" ; SH=$(sha256sum ").append(d).append(" 2>/dev/null | cut -d' ' -f1)");
        // 5) 反复杀，直到「活着的桌面进程都是在写入之后才启动的」。
        //    这里才是关键：不是杀一次就算，而是杀到它读的那份是新库为止
        c.append(" ; i=0 ; while [ $i -lt 5 ]; do")
                .append(" LIVE=$(pidof ").append(q).append(" 2>/dev/null); [ -z \"$LIVE\" ] && break")
                .append(" ; STALE=0")
                .append(" ; for p in $LIVE; do")
                .append(" st=$(stat -c '%Y' /proc/$p 2>/dev/null)")
                .append(" ; [ -z \"$st\" ] && continue ; [ $st -lt $WROTE ] && STALE=1")
                .append(" ; done")
                .append(" ; [ $STALE -eq 0 ] && break")
                .append(" ; am force-stop ").append(q).append(" 2>/dev/null")
                .append(" ; sleep 0.5 ; i=$((i+1)); done");
        // 6) 收工前的现场：还剩哪些 pid、各自何时启动
        c.append(" ; FIN=$(pidof ").append(q).append(" 2>/dev/null)");
        c.append(" ; PT='' ; for p in $FIN; do PT=\"$PT $p:$(stat -c '%Y' /proc/$p 2>/dev/null)\"; done");
        c.append(" ; echo \"INJECT_OK gone=[$GONE] size=$SZ wrote=$WROTE proc=[$PT] sha=$SH\"");
        // 7) 按一次 Home：桌面立刻回到前台，用户不用自己找
        c.append(" ; sleep 1 ; input keyevent 3 >/dev/null 2>&1");

        Shell.Out o = Shell.su(c.toString(), timeoutSec);
        r.log = o.text.trim();
        r.ok = r.log.contains("INJECT_OK");

        int a = r.log.indexOf("gone=[");
        if (a >= 0) {
            int b = r.log.indexOf(']', a);
            if (b > a) {
                r.processGone = r.log.substring(a + 6, b).trim().isEmpty();
            }
        }

        long size = fieldNum(r.log, "size=");
        long wrote = fieldNum(r.log, "wrote=");
        r.wroteSize = size;
        r.wroteAt = wrote;

        // 落盘核对：sha 优先，取不到就退化成比大小。两者都取不到时不判失败——
        // 老设备可能既没有 sha256sum 也没有能用的 stat，硬判失败会让人无从下手
        String sha = fieldText(r.log, "sha=");
        if (!sha.isEmpty() && srcSha != null) {
            r.bytesOk = sha.equalsIgnoreCase(srcSha);
        } else if (size > 0) {
            r.bytesOk = size == srcLen;
        } else {
            r.bytesOk = true;
        }

        // 收工时进程是否仍早于写入
        int pa = r.log.indexOf("proc=[");
        if (pa >= 0) {
            int pb = r.log.indexOf(']', pa);
            if (pb > pa) {
                String inside = r.log.substring(pa + 6, pb).trim();
                r.procLine = inside;
                for (String one : inside.split("\\s+")) {
                    int colon = one.indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    long start = parse(one.substring(colon + 1), -1);
                    if (start > 0 && wrote > 0 && start < wrote) {
                        r.staleProcess = true;
                    }
                }
            }
        }

        if (!r.ok && r.log.isEmpty()) {
            r.log = "exit=" + o.code;
        }
        return r;
    }

    /** 从 "key=value" 里取值（到空白为止）。 */
    private static String fieldText(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return "";
        }
        int from = i + key.length();
        int to = from;
        while (to < s.length() && !Character.isWhitespace(s.charAt(to))) {
            to++;
        }
        String v = s.substring(from, to).trim();
        return "null".equals(v) ? "" : v;
    }

    private static long fieldNum(String s, String key) {
        return parse(fieldText(s, key), -1);
    }

    private static long parse(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private static String sha256(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
