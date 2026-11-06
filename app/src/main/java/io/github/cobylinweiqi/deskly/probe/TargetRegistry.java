package io.github.cobylinweiqi.deskly.probe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * 写进 {@code META-INF/xposed/scope.list} 的推荐作用域，顺序与文件一致。
     *
     * <p>刻意<b>不等于</b> {@link #KNOWN_LAUNCHERS}：白名单是「认得出这个桌面」，
     * 华为 / vivo / 三星之类的库结构还没适配，列进推荐等于开空头支票。
     * 这里只放 MIUI 与 Launcher3 两族（含真我 {@code com.android.launcher}）。
     * {@code staticScope=false}，其它桌面用户仍可在 LSPosed 里手动加作用域。
     */
    public static final List<String> RECOMMENDED_SCOPE;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("com.miui.home", "小米桌面 / HyperOS");
        // 真我 realme UI 的「系统桌面」实测包名是 com.android.launcher（不是 com.oppo.launcher）。
        // 同系其它版本还会用 com.oppo.launcher / com.coloros.launcher 等，全部列进作用域；
        // LSPosed 只会把其中「本机确实装了」的那几个显示成推荐应用。
        m.put("com.android.launcher", "真我 / realme UI 系统桌面");
        m.put("com.oppo.launcher", "真我 / OPPO 桌面（ColorOS）");
        m.put("com.coloros.launcher", "ColorOS 桌面");
        m.put("com.realme.launcher", "realme UI 桌面");
        m.put("com.oplus.launcher", "ColorOS 桌面（OPlus）");
        m.put("com.huawei.android.launcher", "华为桌面 / HarmonyOS");
        m.put("com.android.launcher3", "AOSP Launcher3");
        m.put("com.google.android.apps.nexuslauncher", "Pixel Launcher");
        m.put("com.bbk.launcher2", "vivo 桌面");
        m.put("com.sec.android.app.launcher", "三星 One UI 桌面");
        KNOWN_LAUNCHERS = Collections.unmodifiableMap(m);

        List<String> scope = new ArrayList<>();
        scope.add("com.miui.home");
        scope.add("com.android.launcher");
        scope.add("com.oppo.launcher");
        scope.add("com.coloros.launcher");
        scope.add("com.realme.launcher");
        scope.add("com.oplus.launcher");
        scope.add("com.android.launcher3");
        RECOMMENDED_SCOPE = Collections.unmodifiableList(scope);
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
