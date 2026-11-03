package io.github.cobylinweiqi.deskly.probe;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标桌面与候选符号表。
 *
 * <p>这里列出的类名<strong>全部只是探测优先级</strong>，不作为 hook 依据。真实结构必须由
 * {@code DexLister} + {@code ApkInspector} 在设备上读出来——ROM 每升级一次，硬编码的路径就有一半是错的。
 */
public final class TargetRegistry {

    /** 已知桌面包名 → 展示名，仅用于界面提示用户该勾哪个。 */
    public static final Map<String, String> KNOWN_LAUNCHERS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("com.miui.home", "小米桌面 / HyperOS");
        m.put("com.huawei.android.launcher", "华为桌面 / HarmonyOS");
        m.put("com.android.launcher3", "AOSP Launcher3");
        m.put("com.google.android.apps.nexuslauncher", "Pixel Launcher");
        m.put("com.oppo.launcher", "OPPO 桌面");
        m.put("com.coloros.launcher", "ColorOS 桌面");
        m.put("com.bbk.launcher2", "vivo 桌面");
        m.put("com.sec.android.app.launcher", "三星 One UI 桌面");
        KNOWN_LAUNCHERS = Collections.unmodifiableMap(m);
    }

    /**
     * 小米桌面候选类，按"最可能是数据模型"的顺序排。探测时逐个试加载，
     * 存在的就 dump 方法签名。
     */
    public static final String[] MIUI_CANDIDATES = {
            "com.miui.home.launcher.FolderInfo",
            "com.miui.home.launcher.ItemInfo",
            "com.miui.home.launcher.ShortcutInfo",
            "com.miui.home.launcher.Launcher",
            "com.miui.home.launcher.Workspace",
            "com.miui.home.launcher.LauncherModel",
            "com.miui.home.launcher.LauncherProvider",
            "com.miui.home.launcher.InstallShortcutReceiver",
            "com.miui.home.launcher.Utilities",
            "com.miui.home.launcher.allapps.AllAppsContainerView",
            "com.miui.home.launcher.allapps.LauncherAllAppsContainerView",
    };

    /** 从全量类名里筛"值得细看"的类用的关键字。 */
    public static final String[] INTEREST_KEYWORDS = {
            "FolderInfo", "Workspace", "ItemInfo", "ShortcutInfo", "LauncherModel",
            "InstallShortcut", "AllApps", "LauncherProvider", "Folder",
    };

    private TargetRegistry() {
    }

    public static boolean isSupportedLauncher(String pkg) {
        if (pkg == null) {
            return false;
        }
        if (KNOWN_LAUNCHERS.containsKey(pkg)) {
            return true;
        }
        // 兜底：名字里带 launcher 的也算，方便用户在 LSPosed 里把陌生桌面加进作用域
        String lower = pkg.toLowerCase();
        return lower.endsWith(".launcher") || lower.contains(".launcher.") || lower.endsWith(".home");
    }

    public static String displayName(String pkg) {
        String n = KNOWN_LAUNCHERS.get(pkg);
        return n != null ? n : pkg;
    }
}
