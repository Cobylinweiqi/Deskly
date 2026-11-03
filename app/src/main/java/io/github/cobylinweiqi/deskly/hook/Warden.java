package io.github.cobylinweiqi.deskly.hook;

import io.github.cobylinweiqi.deskly.Boot;
import io.github.cobylinweiqi.deskly.core.L;
import io.github.cobylinweiqi.deskly.probe.RuntimeProbe;
import io.github.cobylinweiqi.deskly.probe.TargetRegistry;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 进程侧守门人：决定当前进程要不要接管。
 *
 * <p>LSPosed 会把模块注入作用域内包名下<strong>所有</strong>进程，且注入后会收到比作用域更广的
 * 回调，所以这里自己按包名过滤，不依赖框架保证。
 */
public final class Warden {

    private static volatile boolean handled;

    private Warden() {
    }

    public static void onPackageReady(XposedModule module, PackageReadyParam param) {
        final String pkg;
        try {
            pkg = param.getPackageName();
        } catch (Throwable t) {
            return;
        }
        if (!TargetRegistry.isSupportedLauncher(pkg) || handled) {
            return;
        }
        handled = true;

        L.i(Boot.TAG, "接管桌面进程: " + pkg + " (" + TargetRegistry.displayName(pkg) + ")");

        ClassLoader cl;
        try {
            cl = param.getClassLoader();
        } catch (Throwable t) {
            L.e(Boot.TAG, "拿不到 classloader", t);
            return;
        }

        RuntimeProbe.Outcome outcome = null;
        try {
            outcome = RuntimeProbe.run(module, pkg, cl);
        } catch (Throwable t) {
            L.e(Boot.TAG, "runtime probe 失败", t);
        }

        try {
            Organizer.get().attach(module, pkg, cl, outcome);
        } catch (Throwable t) {
            L.e(Boot.TAG, "organizer attach 失败", t);
        }
    }
}
