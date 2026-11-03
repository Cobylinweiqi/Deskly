package com.coby.deskly.core;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 全局日志出口。注入进程里拿不到模块自己的 Context，所以统一走框架的 log 通道。
 */
public final class L {

    private static volatile XposedModule module;

    private L() {
    }

    public static void bind(XposedModule m) {
        module = m;
    }

    public static void i(String tag, String msg) {
        d(Log.INFO, tag, msg);
    }

    public static void w(String tag, String msg) {
        d(Log.WARN, tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        XposedModule m = module;
        if (m == null) {
            return;
        }
        try {
            m.log(Log.ERROR, tag, msg, t);
        } catch (Throwable ignored) {
        }
    }

    private static void d(int level, String tag, String msg) {
        XposedModule m = module;
        if (m == null) {
            return;
        }
        try {
            m.log(level, tag, msg);
        } catch (Throwable ignored) {
        }
    }
}
