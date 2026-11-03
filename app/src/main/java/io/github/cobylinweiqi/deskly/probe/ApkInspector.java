package io.github.cobylinweiqi.deskly.probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 静态探针：在本应用进程里读取目标桌面的 APK，列出真实类名并反射其成员。
 *
 * <p>为什么不放在注入进程里做：注入侧只能把结果写进框架日志，用户拿不出来。
 * 这里用 {@code PathClassLoader} 指向目标 APK，{@code Class.forName(name, false, loader)}
 * 只加载不初始化，纯读不执行，副作用为零。少数依赖系统私有 API 的类会解析失败，
 * 逐个 try 掉即可，不影响整体。
 */
public final class ApkInspector {

    private static final int MAX_METHODS_PER_CLASS = 60;
    private static final int MAX_CANDIDATES = 200;
    private static final int MAX_FIELDS = 40;

    /** 疑似"往桌面写数据"的方法名片段，用于在报告里高亮。 */
    private static final String[] WRITE_HINTS = {
            "add", "move", "insert", "create", "remove", "delete", "update",
            "folder", "bind", "commit", "save",
    };

    private ApkInspector() {
    }

    public static Result inspect(Context ctx, String pkg) {
        Result r = new Result();
        r.pkg = pkg;
        r.device = Build.MANUFACTURER + " " + Build.MODEL + " / Android "
                + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")";
        r.generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());

        PackageManager pm = ctx.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(pkg, 0);
            // 用 versionCode 而非 getLongVersionCode()：后者 API 28 才有，minSdk 是 26
            r.version = pi.versionName + " (code " + pi.versionCode + ")";
            r.apkPath = pi.applicationInfo.sourceDir;
        } catch (Throwable t) {
            r.error = "读取包信息失败: " + t;
            return r;
        }

        List<String> all;
        try {
            all = DexLister.listClassNames(new File(r.apkPath));
        } catch (Throwable t) {
            r.error = "解析 DEX 失败: " + t;
            return r;
        }
        r.totalClasses = all.size();

        // 候选类：先按已知清单命中的，再按关键字扩展
        List<String> candidates = new ArrayList<>();
        for (String c : TargetRegistry.MIUI_CANDIDATES) {
            if (all.contains(c) && !candidates.contains(c)) {
                candidates.add(c);
            }
        }
        for (String c : all) {
            if (candidates.size() >= MAX_CANDIDATES) {
                break;
            }
            if (candidates.contains(c)) {
                continue;
            }
            if (!c.startsWith(pkg)) {
                continue;
            }
            for (String kw : TargetRegistry.INTEREST_KEYWORDS) {
                if (c.endsWith("." + kw) || c.endsWith(kw)) {
                    candidates.add(c);
                    break;
                }
            }
        }
        r.candidates = candidates;

        // 反射 dump（只对候选类，控制耗时）
        ClassLoader loader;
        try {
            loader = new dalvik.system.PathClassLoader(r.apkPath, ClassLoader.getSystemClassLoader());
        } catch (Throwable t) {
            r.error = "构造 ClassLoader 失败: " + t;
            return r;
        }

        StringBuilder sb = new StringBuilder();
        int dumped = 0;
        for (String name : candidates) {
            String one = dumpClass(loader, name, r.pkg);
            if (one != null) {
                sb.append(one);
                dumped++;
            }
        }
        r.dumpedClasses = dumped;
        r.detail = sb.toString();
        return r;
    }

    private static String dumpClass(ClassLoader loader, String name, String pkgPrefix) {
        Class<?> c;
        try {
            c = Class.forName(name, false, loader);
        } catch (Throwable t) {
            // 依赖系统私有 API 的类在这里会失败，只留一行说明
            return "\n── " + name + "  ──\n  [加载失败] " + t.getClass().getSimpleName() + "\n";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n── ").append(name).append(" ──\n");

        String sup = c.getSuperclass() == null ? "无" : c.getSuperclass().getName();
        sb.append("  继承: ").append(sup).append('\n');

        String[] ifaces = null;
        try {
            Class<?>[] is = c.getInterfaces();
            if (is.length > 0) {
                StringBuilder ib = new StringBuilder();
                for (Class<?> i : is) {
                    if (ib.length() > 0) {
                        ib.append(", ");
                    }
                    ib.append(i.getSimpleName());
                }
                ifaces = new String[]{ib.toString()};
            }
        } catch (Throwable ignored) {
        }
        if (ifaces != null) {
            sb.append("  实现: ").append(ifaces[0]).append('\n');
        }

        try {
            Field[] fs = c.getDeclaredFields();
            List<String> fLines = new ArrayList<>();
            for (Field f : fs) {
                if (f.isSynthetic() || fLines.size() >= MAX_FIELDS) {
                    continue;
                }
                String type;
                try {
                    type = simple(f.getType());
                } catch (Throwable t) {
                    type = "?";
                }
                if (Modifier.isStatic(f.getModifiers())) {
                    type = "static " + type;
                }
                fLines.add("    " + type + " " + f.getName());
            }
            if (!fLines.isEmpty()) {
                sb.append("  字段(").append(fLines.size()).append("):\n");
                for (String l : fLines) {
                    sb.append(l).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  字段: [读取失败] ").append(t.getClass().getSimpleName()).append('\n');
        }

        try {
            Method[] ms = c.getDeclaredMethods();
            List<String> normal = new ArrayList<>();
            List<String> likely = new ArrayList<>();
            for (Method m : ms) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                String line;
                try {
                    StringBuilder pb = new StringBuilder();
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) {
                            pb.append(", ");
                        }
                        pb.append(simple(ps[i]));
                    }
                    String mod = Modifier.isStatic(m.getModifiers()) ? "static " : "";
                    String suffix = Modifier.isPublic(m.getModifiers()) ? "" : " [non-public]";
                    line = "    " + mod + simple(m.getReturnType()) + " " + m.getName()
                            + "(" + pb + ")" + suffix;
                } catch (Throwable t) {
                    continue; // 参数类型解析不出来就跳过这一条
                }
                if (isWriteHint(m.getName())) {
                    likely.add(line);
                } else if (normal.size() < MAX_METHODS_PER_CLASS) {
                    normal.add(line);
                }
            }
            if (!likely.isEmpty()) {
                sb.append("  疑似写桌面的方法(").append(likely.size()).append("):\n");
                for (String l : likely) {
                    sb.append(l).append('\n');
                }
            }
            if (!normal.isEmpty()) {
                sb.append("  其它方法(截断至").append(MAX_METHODS_PER_CLASS).append("):\n");
                for (String l : normal) {
                    sb.append(l).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  方法: [读取失败] ").append(t.getClass().getSimpleName()).append('\n');
        }

        return sb.toString();
    }

    private static boolean isWriteHint(String methodName) {
        if (methodName == null) {
            return false;
        }
        String n = methodName.toLowerCase(Locale.US);
        for (String h : WRITE_HINTS) {
            if (n.contains(h)) {
                return true;
            }
        }
        return false;
    }

    private static String simple(Class<?> t) {
        if (t.isArray()) {
            return simple(t.getComponentType()) + "[]";
        }
        String n = t.getName();
        int dot = n.lastIndexOf('.');
        return dot >= 0 ? n.substring(dot + 1) : n;
    }

    /** 一次探测的结果。 */
    public static final class Result {
        public String pkg = "";
        public String version = "?";
        public String apkPath = "";
        public String device = "";
        public String generatedAt = "";
        public String error;
        public int totalClasses;
        public int dumpedClasses;
        public List<String> candidates = new ArrayList<>();
        public String detail = "";

        public String toText() {
            StringBuilder sb = new StringBuilder();
            sb.append("Deskly 探针报告\n");
            sb.append("生成: ").append(generatedAt).append('\n');
            sb.append("设备: ").append(device).append('\n');
            sb.append("目标: ").append(pkg).append("  版本 ").append(version).append('\n');
            sb.append("APK : ").append(apkPath).append('\n');
            sb.append("类总数: ").append(totalClasses).append('\n');
            if (error != null) {
                sb.append("\n!! ").append(error).append('\n');
                return sb.toString();
            }
            sb.append("候选类: ").append(candidates.size())
                    .append(" 个，成功反射 ").append(dumpedClasses).append(" 个\n");
            sb.append("\n── 候选类清单 ──\n");
            for (String c : candidates) {
                sb.append("  ").append(c).append('\n');
            }
            sb.append(detail);
            return sb.toString();
        }
    }
}
