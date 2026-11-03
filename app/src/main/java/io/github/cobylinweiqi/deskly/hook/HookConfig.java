package io.github.cobylinweiqi.deskly.hook;

import android.content.SharedPreferences;

import io.github.cobylinweiqi.deskly.Boot;
import io.github.cobylinweiqi.deskly.core.L;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/** 注入侧读取配置。任何一步不可用都退回"关闭"这一安全默认值。 */
public final class HookConfig {

    public static final String SP_NAME = "deskly";
    public static final String KEY_AUTO = "auto_organize";
    public static final String KEY_VERBOSE = "verbose_probe";

    private HookConfig() {
    }

    public static boolean remoteAvailable(XposedModule m) {
        try {
            return (m.getFrameworkProperties() & XposedInterface.PROP_CAP_REMOTE) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean autoOrganize(XposedModule m) {
        return getBool(m, KEY_AUTO, false);
    }

    public static boolean verboseProbe(XposedModule m) {
        return getBool(m, KEY_VERBOSE, true);
    }

    private static boolean getBool(XposedModule m, String key, boolean def) {
        if (!remoteAvailable(m)) {
            return def;
        }
        try {
            SharedPreferences sp = m.getRemotePreferences(SP_NAME);
            if (sp == null) {
                return def;
            }
            return sp.getBoolean(key, def);
        } catch (Throwable t) {
            L.e(Boot.TAG, "read remote prefs failed: " + key, t);
            return def;
        }
    }
}
