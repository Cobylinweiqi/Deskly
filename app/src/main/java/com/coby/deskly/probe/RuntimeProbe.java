package com.coby.deskly.probe;

import com.coby.deskly.Boot;
import com.coby.deskly.core.L;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * 注入进程内的运行时探针。
 *
 * <p>与 {@link ApkInspector} 的分工：那边是"离线读文件"，这边是"在真实 classloader 里确认"。
 * 有些类在离线反射时会因系统私有依赖失败，但在目标进程内部是完好的——两边结果互相印证，
 * 才能确定 hook 点到底叫什么。
 *
 * <p>输出全部走框架日志（LSPosed 管理器 → 日志），因为注入进程没有写模块目录的权限。
 */
public final class RuntimeProbe {

    private static final int MAX_METHODS_LOGGED = 10;

    private RuntimeProbe() {
    }

    public static Outcome run(XposedModule module, String pkg, ClassLoader cl) {
        Outcome o = new Outcome(pkg);
        L.i(Boot.TAG, "── runtime probe · " + pkg + " · " + cl.getClass().getName() + " ──");

        for (String name : TargetRegistry.MIUI_CANDIDATES) {
            Class<?> c = tryLoad(name, cl);
            if (c == null) {
                o.missing.add(name);
                continue;
            }
            o.found.add(name);
            dumpBrief(c);
        }

        L.i(Boot.TAG, "runtime probe done: found=" + o.found.size()
                + " missing=" + o.missing.size());
        return o;
    }

    private static void dumpBrief(Class<?> c) {
        String sup;
        try {
            sup = c.getSuperclass() == null ? "无" : c.getSuperclass().getName();
        } catch (Throwable t) {
            sup = "?";
        }
        L.i(Boot.TAG, "  [存在] " + c.getName() + "  ← " + sup);

        try {
            List<String> writes = new ArrayList<>();
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic() || m.isBridge()) {
                    continue;
                }
                String n = m.getName().toLowerCase(Locale.US);
                if (!(n.contains("add") || n.contains("move") || n.contains("insert")
                        || n.contains("create") || n.contains("folder") || n.contains("remove"))) {
                    continue;
                }
                if (writes.size() >= MAX_METHODS_LOGGED) {
                    break;
                }
                writes.add("      " + render(m));
            }
            for (String w : writes) {
                L.i(Boot.TAG, w);
            }
        } catch (Throwable t) {
            L.i(Boot.TAG, "      [方法读取失败] " + t.getClass().getSimpleName());
        }

        try {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic()) {
                    continue;
                }
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                L.i(Boot.TAG, "      字段 " + f.getType().getSimpleName() + " " + f.getName());
            }
        } catch (Throwable ignored) {
        }
    }

    private static String render(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getReturnType().getSimpleName()).append(' ').append(m.getName()).append('(');
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ps[i].getSimpleName());
        }
        sb.append(')');
        if (!Modifier.isPublic(m.getModifiers())) {
            sb.append(" [non-public]");
        }
        return sb.toString();
    }

    private static Class<?> tryLoad(String name, ClassLoader cl) {
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 探测结果。 */
    public static final class Outcome {
        public final String pkg;
        public final Set<String> found = new LinkedHashSet<>();
        public final Set<String> missing = new LinkedHashSet<>();

        Outcome(String pkg) {
            this.pkg = pkg;
        }

        public boolean has(String className) {
            return found.contains(className);
        }

        /** 数据模型类是否齐全——齐全才谈得上"能改桌面"。 */
        public boolean structureReady() {
            return !found.isEmpty();
        }

        public String summary() {
            if (found.isEmpty()) {
                return "未识别到任何桌面结构类";
            }
            return "识别到 " + found.size() + " 个结构类";
        }
    }
}
