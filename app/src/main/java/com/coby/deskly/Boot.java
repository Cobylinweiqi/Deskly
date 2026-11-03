package com.coby.deskly;

import android.util.Log;

import io.github.libxposed.api.XposedModule;

/**
 * 模块入口。由 META-INF/xposed/java_init.list 声明，LSPosed 注入目标进程后实例化。
 *
 * <p>这一层只做三件事：绑定日志通道、判断当前进程是不是要接管的桌面、把后续工作交给
 * {@code Warden}。所有异常一律吞掉——launcher 崩溃的代价是用户整个桌面起不来。
 */
public class Boot extends XposedModule {

    public static final String TAG = "Deskly";

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        try {
            com.coby.deskly.core.L.bind(this);
            com.coby.deskly.core.L.i(TAG, "module loaded in process=" + param.getProcessName()
                    + " api=" + getApiVersion()
                    + " framework=" + getFrameworkName() + " " + getFrameworkVersion());
        } catch (Throwable t) {
            safeLog("onModuleLoaded failed", t);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            com.coby.deskly.hook.Warden.onPackageReady(this, param);
        } catch (Throwable t) {
            safeLog("onPackageReady failed for " + safeName(param), t);
        }
    }

    private void safeLog(String msg, Throwable t) {
        try {
            log(Log.ERROR, TAG, msg + ": " + t);
        } catch (Throwable ignored) {
            // 连日志都写不了就只能放弃，绝不能把异常抛回框架
        }
    }

    private static String safeName(PackageReadyParam p) {
        try {
            return p.getPackageName();
        } catch (Throwable t) {
            return "?";
        }
    }
}
