package io.github.cobylinweiqi.deskly.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * su 通道。备份与归位是这套框架里仅有的需要 root 的部分，所以这里把它收成一个薄壳，
 * 所有调用都走同一条路径，便于统一超时与失败处理。
 *
 * <p>刻意不使用 {@code su -c "cmd1 && cmd2"} 之外的语法糖：Magisk / KernelSU / APatch
 * 三家的 su 都认 {@code -c}，但对 {@code --} 和其它长参数支持不一致。
 *
 * <p><b>为什么要有 {@link Probe}：</b>以前这里只有 {@code hasRoot()} 一个布尔值，
 * 于是"没授权""su 不存在""Magisk 守护进程挂了""等授权等到超时"四种情况在界面上
 * 长得一模一样，全都显示"未获得 root"。真机上就吃过这个亏——用户明明在 Magisk 里
 * 点了允许，界面还让他去授权，因为真正坏掉的是 magiskd，跟授权毫无关系。
 */
public final class Shell {

    /** 命令结果。stdout 与 stderr 合并，因为我们只关心"成不成 + 说了什么"。 */
    public static final class Out {
        public final int code;
        public final String text;

        Out(int code, String text) {
            this.code = code;
            this.text = text == null ? "" : text;
        }

        public boolean ok() {
            return code == 0;
        }

        /** 取第一行非空输出，用于 id / stat 这类单值命令。 */
        public String line() {
            for (String s : text.split("\n")) {
                String t = s.trim();
                if (!t.isEmpty()) {
                    return t;
                }
            }
            return "";
        }
    }

    /**
     * root 探测结果。不再是"能/不能"，而是"为什么不能"。
     */
    public static final class Probe {
        /** 拿到了 root。 */
        public static final int OK = 0;
        /** su 在，但没被允许（Magisk 里未授权 / 被拒）。 */
        public static final int DENIED = 1;
        /** su 在，但它连不上 magiskd，一启动就崩。跟授权无关，重启才能恢复。 */
        public static final int DAEMON_DOWN = 2;
        /** 设备上没有 su 可执行文件。 */
        public static final int NO_SU = 3;
        /** 等了很久没有回应——通常是授权框还没人点。 */
        public static final int TIMEOUT = 4;
        /** 其它意外。 */
        public static final int ERROR = 5;

        public final int kind;
        /** 原始输出，用于「详情」里摊给用户看，不让诊断停在猜测上。 */
        public final String raw;

        Probe(int kind, String raw) {
            this.kind = kind;
            this.raw = raw == null ? "" : raw;
        }

        public boolean ok() {
            return kind == OK;
        }

        /** 一句话说清问题。 */
        public String headline() {
            switch (kind) {
                case OK:
                    return "已获得 root";
                case DENIED:
                    return "su 可用，但 Deskly 还没拿到授权";
                case DAEMON_DOWN:
                    return "Magisk 守护进程没有运行（su 一启动就崩）";
                case NO_SU:
                    return "这台设备上没有 su";
                case TIMEOUT:
                    return "等待授权超时";
                default:
                    return "root 状态未知";
            }
        }

        /** 该怎么办。每一条都必须是用户真能执行的动作。 */
        public String advice() {
            switch (kind) {
                case OK:
                    return "";
                case DENIED:
                    return "到「超级用户列表」里手动授权：\n"
                            + "打开 Magisk → 底部「超级用户」→ 找到 Deskly，打开右侧开关。\n"
                            + "（KernelSU / APatch 同理，在「超级用户」页里授权。）\n"
                            + "列表里如果暂时没有 Deskly，说明它还没成功发起过 su 请求——"
                            + "回到本页点「重试」，触发一次申请后它就会出现在列表里。\n"
                            + "授权后无需手动刷新，回到本页会自动重查。";
                case DAEMON_DOWN:
                    return "这不是授权问题——授权记录还在，坏的是 Magisk 自己的后台服务。\n"
                            + "重启手机即可恢复（守护进程只在开机时启动，应用没法把它拉起来）。\n"
                            + "重启后所有需要 root 的应用都会同时恢复正常。";
                case NO_SU:
                    return "这台设备没有 root，或用的是不被支持的 su 实现。\n"
                            + "分类与预览功能不需要 root，仍可正常使用。";
                case TIMEOUT:
                    return "授权框可能被系统折叠成了通知、被「白名单类」模块静默拦掉，或屏幕已熄灭。\n"
                            + "最稳的办法是不等弹窗，直接去「超级用户列表」授权：\n"
                            + "打开 Magisk → 底部「超级用户」→ 找到 Deskly 打开开关。\n"
                            + "列表里没有 Deskly 就先回本页重试一次，让它发起请求后再去授权。";
                default:
                    return "把下面这行发给我，我按它继续查。";
            }
        }

        /** 一行动作，用在空间紧的告警条上——长解释留给「诊断 · 备份」里的正文。 */
        public String shortAction() {
            switch (kind) {
                case DENIED:
                    return "到超级用户列表授权";
                case DAEMON_DOWN:
                    return "重启手机可恢复";
                case NO_SU:
                    return "分类与预览仍可用";
                case TIMEOUT:
                    return "到超级用户列表授权";
                default:
                    return "";
            }
        }

        /** 一行签名，既给用户看也给日志用。 */
        public String signature() {
            String first = "";
            for (String s : raw.split("\n")) {
                if (!s.trim().isEmpty()) {
                    first = s.trim();
                    break;
                }
            }
            if (first.isEmpty()) {
                return "kind=" + kind;
            }
            // Magisk 的 Rust CLI 崩之前打的是 stderr 原文，长度不可控，截一下
            if (first.length() > 120) {
                first = first.substring(0, 120) + "…";
            }
            return "kind=" + kind + " · " + first;
        }

        /** 一次说清：问题 + 怎么办。适合直接丢进多行 TextView。 */
        public String report() {
            String a = advice();
            return a.isEmpty() ? headline() : headline() + "\n\n" + a;
        }
    }

    private Shell() {
    }

    /**
     * 探一次 root，并区分失败原因。首次会弹授权框，所以给足 90 秒等人点确认。
     *
     * <p>用户主动点按钮触发的那次探查用短超时（见 {@link #probe(int)}）：点下去之后
     * 界面上是有"正在检测"提示的，但让人盯着看 90 秒不动仍然是坏体验。
     */
    public static Probe probe() {
        return probe(90);
    }

    public static Probe probe(int timeoutSec) {
        Out o = su("id -u", timeoutSec);

        if (o.code == -2) {
            return new Probe(Probe.TIMEOUT, o.text);
        }
        if (o.code == -1) {
            // 进程根本没起来：多半是找不到 su
            String t = o.text.toLowerCase(Locale.US);
            if (t.contains("no such file") || t.contains("error=2")
                    || t.contains("cannot run program") || t.contains("permission denied")) {
                return new Probe(Probe.NO_SU, o.text);
            }
            return new Probe(Probe.ERROR, o.text);
        }
        if (o.code == 0 && "0".equals(o.line())) {
            return new Probe(Probe.OK, o.text);
        }

        String t = o.text.toLowerCase(Locale.US);
        // magiskd 不在时，su 连不上它的 socket，Rust 侧直接 panic abort（信号 5 → 退出码 133）
        if (t.contains("cannot connect to daemon") || t.contains("connection refused")
                || o.code == 133) {
            return new Probe(Probe.DAEMON_DOWN, o.text);
        }
        return new Probe(Probe.DENIED, o.text);
    }

    /** 只要布尔值的旧调用点用这个；新代码请用 {@link #probe()}，否则又退回"一律未授权"。 */
    public static boolean hasRoot() {
        return probe().ok();
    }

    public static Out su(String cmd) {
        return su(cmd, 30);
    }

    /**
     * 执行 {@code su -c cmd}。读流必须放到独立线程，否则输出超过管道缓冲区会双向卡死。
     */
    public static Out su(String cmd, int timeoutSec) {
        if (cmd != null && nsState() == 1) {
            return exec("nsenter -t 1 -m -- sh -c " + q(cmd), timeoutSec);
        }
        return exec(cmd, timeoutSec);
    }

    /**
     * 是否需要把命令搬进全局挂载命名空间。
     *
     * <p><b>这是本框架最关键的一处修正。</b>Android 11 起每个 App 进程有独立的 mount
     * namespace，别的 App 的数据目录在里面<b>根本没被挂载</b>。Magisk 的 su 默认继承
     * 调用者的 namespace，所以"App 里 su 是 root，却 ls 不到 /data/user_de/0/com.miui.home"
     * 完全可能发生——报的是 No such file or directory，而不是 Permission denied，
     * 特别容易被误判成"没授权"或"路径猜错"。
     *
     * <p>而通过 adb shell 发起的 su 用的是 shell 的全局 namespace，一切正常。这就是
     * "我用命令行一直能读、App 里就是读不到"的全部原因。
     *
     * <p>取值：-1 未探测，1 可用，0 不可用。探测失败若是"还没授权"这类原因，<b>不缓存</b>，
     * 否则一次太早的探测会把后续所有命令永久钉死在错误路径上。
     */
    private static volatile int nsMode = -1;

    private static synchronized int nsState() {
        int cur = nsMode;
        if (cur >= 0) {
            return cur;
        }
        // 60 秒，不是 8 秒。这条命令也是走 su 的：首次调用会弹 Magisk 授权框，
        // 8 秒根本不够人点。而超时被 destroyForcibly 杀掉时，Magisk 会把它记成
        // 「用户拒绝」，之后就再也不弹了——用户看到的现象就是"点应用方案毫无反应，
        // 超级用户列表里也找不到 Deskly"。已授权时它立刻返回，不会真等满 60 秒。
        Out o = exec("nsenter -t 1 -m -- true", 60);
        if (o.code == 0) {
            nsMode = 1;
            return 1;
        }
        String t = o.text.toLowerCase(Locale.US);
        boolean rootProblem = o.code == -2 || t.contains("permission denied")
                || t.contains("cannot connect") || t.contains("not allowed")
                || t.contains("no such file");
        if (rootProblem) {
            // 只是这次没拿到 root，不能据此断定设备没有 nsenter
            return 0;
        }
        nsMode = 0;
        return 0;
    }

    /** 真正的 su 执行。所有命令都从这里出去，便于统一超时与失败处理。 */
    private static Out exec(String cmd, int timeoutSec) {
        Process p = null;
        try {
            p = new ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start();
            final InputStream in = p.getInputStream();
            final ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
            Thread reader = new Thread(() -> {
                byte[] b = new byte[4096];
                try {
                    int n;
                    while ((n = in.read(b)) > 0) {
                        buf.write(b, 0, n);
                    }
                } catch (Throwable ignored) {
                }
            }, "deskly-su-read");
            reader.setDaemon(true);
            reader.start();

            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                reader.join(800);
                return new Out(-2, "命令超时（" + timeoutSec + "s）：" + cmd);
            }
            reader.join(1500);
            return new Out(p.exitValue(), buf.toString("UTF-8"));
        } catch (Throwable t) {
            return new Out(-1, "su 不可用：" + t);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 单引号转义，防止路径里的空格与特殊字符把命令拼坏。 */
    public static String q(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String q(File f) {
        return q(f.getAbsolutePath());
    }
}
