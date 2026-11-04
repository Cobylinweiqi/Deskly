package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「整理全部图标」的落地方案：把所有应用图标按当前预设重新分组，并算出每个文件夹
 * 落在第几屏第几格、每个成员在文件夹里的第几个。
 *
 * <p><b>与「归位」的区别，也是这次改动的由来。</b>归位只在<b>已经摆好的</b>桌面里做微调——
 * 把散落的塞进合适的文件夹、把明显放错的挪走，剩下的照旧。它的问题不是错，而是太小：
 * 桌面上 155 个图标里 146 个压根不会有任何行出现，于是「整理」这个功能被呈现成了
 * 「处理 5 个散落图标」。这里换的是方向——<b>整张桌面按方案重排</b>，一次动全部。
 *
 * <p>排布完全复用 {@link LayoutPlan}：界面预览说第 3 屏，写进去就必须是第 3 屏，
 * 两套算法各算一遍迟早会对不上。
 *
 * <p>不参与整理的东西：热区图标（底部固定栏）、小组件与组件（itemType=19 等）。
 * 前者是刻意固定的入口，后者塞不进文件夹——它们原地不动，所以「第一屏留空」实际是
 * 「第一屏只留小组件，不摆应用图标」，这一点在界面上要如实说，不能让用户以为整屏会被清空。
 */
public final class DeskPlan {

    /** 一个即将存在的文件夹。 */
    public static final class Bucket {
        public final String group;
        /** 组内成员，顺序即文件夹内的排列顺序。 */
        public final List<DesktopModel.Item> members = new ArrayList<>();
        /** 1 起算的屏号。 */
        public int screen;
        public int cellX;
        public int cellY;

        Bucket(String group) {
            this.group = group;
        }

        public int size() {
            return members.size();
        }
    }

    public final List<Bucket> buckets = new ArrayList<>();
    /** 原地不动的条目：小组件、组件、认不出的类型。 */
    public final List<DesktopModel.Item> stayPut = new ArrayList<>();
    /**
     * 组名 → 现成的同名文件夹 id。
     *
     * <p>复用而不是删了重建，是因为小米的「工具」「游戏」文件夹标题存的是资源引用
     * （{@code com.miui.home:string/...}），自己重建一个同名文件夹能显示，但那两个
     * 是系统自动分类用的，留着原id更稳。反正它们的外观完全一样。
     */
    public final Map<String, Integer> reusable = new LinkedHashMap<>();
    /**
     * 屏号 → 这一屏还能用的文件夹候选位（默认 6 个全能用）。
     *
     * <p>桌面上的小组件会整块占住格子。真机第 1 屏摆了一个 4×2 的日历，把 (0,0) 和 (2,0)
     * 两个位置压掉了。这里记的是<b>具体哪几个位不能用</b>，而不是"这一屏少放两个"——
     * 只减数量的话，文件夹照样会被放进前四个候选位，正好撞上日历。
     */
    public final Map<Integer, int[]> slots = new LinkedHashMap<>();
    /** 参与整理的应用图标总数。 */
    public int total;
    /** 整理之后桌面要用到几屏。 */
    public int screenCount;
    /** 当前桌面已有的文件夹数量，用于在回执里说清"18 个旧文件夹 → 9 个新文件夹"。 */
    public int oldFolderCount;

    // ───────────────────────── 锁定屏（0.14） ─────────────────────────

    /** 被锁定的原屏号（升序，只含当前桌面真实存在的）。 */
    public final List<Integer> lockedScreens = new ArrayList<>();

    /**
     * 需要只改 screen 的条目：itemId → 新屏号。
     *
     * <p>锁定屏里的文件夹本体、应用、组件都走这张表——它们的内容、格位一律不动，
     * 只因为中间未锁定的屏被清空而整体前移。被保留的未锁定屏小组件也复用这张表。
     */
    public final Map<Integer, Integer> frozenScreens = new LinkedHashMap<>();

    /** 未锁定屏上的小组件：原屏会被重排掉，必须显式安置（保留则挪到最后一屏）。 */
    public final List<DesktopModel.Item> orphanWidgets = new ArrayList<>();

    /** 选择"随屏清空"时，这些小组件要从库里删掉。 */
    public final Set<Integer> dropIds = new LinkedHashSet<>();

    /** 锁定屏上原地保留的文件夹 id，写入时绝不能删。 */
    public final Set<Integer> keptFolderIds = new HashSet<>();

    /** 未锁定屏的小组件是否保留（true=挪到最后一屏，false=删除）。 */
    public boolean keepOrphanWidgets = true;

    /** 锁定屏数量。新文件夹从 {@code lockedCount + 1} 屏开始。 */
    public int lockedCount;

    /** 新文件夹的起始屏号（与 LayoutPlan 保持一致，用于回执与预览）。 */
    public int startScreen = 1;

    public int memberCount() {
        int n = 0;
        for (Bucket b : buckets) {
            n += b.members.size();
        }
        return n;
    }

    /**
     * @param firstScreenBlank 第一屏不放应用图标
     * @param pins             组名 → 指定屏号，可为 null
     */
    public static DeskPlan build(Context ctx, DesktopModel model, AppClassifier.Scheme scheme,
                                 boolean firstScreenBlank, Map<String, Integer> pins) {
        return build(ctx, model, scheme, firstScreenBlank, pins, null, true);
    }

    /**
     * @param lockedScreens 锁定的<b>原屏号</b>，可为 null。这些屏内的应用、文件夹、组件
     *                      全部冻结，不参与分组；屏号按锁定顺序压缩成 1..k。
     * @param keepOrphanWidgets 未锁定屏上的小组件：true=挪到最后一屏保留，false=随屏清空。
     */
    public static DeskPlan build(Context ctx, DesktopModel model, AppClassifier.Scheme scheme,
                                 boolean firstScreenBlank, Map<String, Integer> pins,
                                 Set<Integer> lockedScreens, boolean keepOrphanWidgets) {
        DeskPlan plan = new DeskPlan();
        plan.oldFolderCount = model.folders.size();
        plan.keepOrphanWidgets = keepOrphanWidgets;

        // 1) 锁定屏：只认当前桌面真实存在的屏号；升序后映射到新号 1..k。
        List<Integer> locks = new ArrayList<>();
        if (lockedScreens != null) {
            for (int s : lockedScreens) {
                if (s >= 1 && s <= model.screenCount && !locks.contains(s)) {
                    locks.add(s);
                }
            }
        }
        Collections.sort(locks);
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        for (int i = 0; i < locks.size(); i++) {
            remap.put(locks.get(i), i + 1);
        }
        plan.lockedScreens.addAll(locks);
        plan.lockedCount = locks.size();
        plan.startScreen = Math.max(firstScreenBlank ? 2 : 1, plan.lockedCount + 1);
        boolean hasLock = !locks.isEmpty();
        // 现有文件夹的名字能对上新组名的，复用它的 _id。
        //
        // 但标题是资源引用的（com.miui.home:string/app_category_games 这种）一律不复用：
        // 那是小米「自动分类」生成的文件夹，什么时候重排内容由系统说了算——把用户整理进去的
        // 图标交给它保管，等于让这次整理随时可能被悄悄回滚。宁可删掉重建一个同名普通文件夹。
        for (DesktopModel.Folder f : model.folders) {
            // 锁定屏里的文件夹不参与复用：它的 id 要留着托住原来的内容
            if (remap.containsKey(f.screen)) {
                continue;
            }
            if (f.title == null || f.title.isEmpty()) {
                continue;
            }
            boolean resourceTitle = f.rawTitle != null
                    && (f.rawTitle.indexOf(':') >= 0 || f.rawTitle.indexOf('/') >= 0);
            // 里面有不进整理的东西（小米「工具」文件夹里那个 itemType=1 的「查找」）就不能删，
            // 不删就必须复用，否则桌面上会多出一个空壳文件夹，和新文件夹并排站
            boolean hasExtra = false;
            for (DesktopModel.Item m : f.members) {
                if (!m.isApp()) {
                    hasExtra = true;
                    break;
                }
            }
            if (resourceTitle && !hasExtra) {
                continue;
            }
            if (!plan.reusable.containsKey(f.title)) {
                plan.reusable.put(f.title, f.id);
            }
        }

        // 3) 收集要整理的应用：只收未锁定屏的；锁定屏整块冻结。
        //    热区不看——它天然是刻意放的；组件也不能进文件夹。
        List<DesktopModel.Item> apps = new ArrayList<>();
        for (DesktopModel.Folder f : model.folders) {
            if (remap.containsKey(f.screen)) {
                // 整个文件夹（连成员）原地保留，只把屏号前移
                plan.frozenScreens.put(f.id, remap.get(f.screen));
                plan.keptFolderIds.add(f.id);
                continue;
            }
            for (DesktopModel.Item m : f.members) {
                if (m.isApp()) {
                    apps.add(m);
                } else {
                    plan.stayPut.add(m);
                }
            }
        }
        for (DesktopModel.Item i : model.looseOnDesktop) {
            if (remap.containsKey(i.screen)) {
                // 锁定屏上的应用/快捷方式/组件都冻结，只改屏号
                plan.frozenScreens.put(i.id, remap.get(i.screen));
                continue;
            }
            if (i.isApp()) {
                apps.add(i);
            } else if (!i.isFolder()) {
                // 文件夹本体（itemType=21）绝对不能算进 stayPut。
                //
                // 0.11.1 及以前它被算了进去，后果很隐蔽：stayPut 是用来算"哪些格位被
                // 小组件压住了"的，而每个旧文件夹都有自己的 screen/cellX/cellY，
                // 于是它把自己现在占的那个 2×2 洞标成"已被占用"——可这些文件夹正是
                // 这次要挪走/删掉的东西。新布局只能塞进旧布局剩下的缝里，
                // 每整理一次就摊得更散、屏数越排越多。
                //
                // 真机复现：整理前第 2 屏的 4 个文件夹占着候选位 2~5，于是第 2 屏
                // "只剩"0、1 两个位，只能放 2 个文件夹；第 4 屏的文件夹占 0~2，
                // 新文件夹就被排到 3、4、5——用户看到的就是"我钉的屏它不认"。
                //
                // 普通组件：没有锁定时按老行为留在原屏并扣格位；有锁定时它所在的屏
                // 会被重排掉，必须显式安置，否则会和压缩后的锁定屏抢同一个屏号。
                if (hasLock) {
                    plan.orphanWidgets.add(i);
                } else {
                    plan.stayPut.add(i);
                }
            }
        }
        plan.total = apps.size();
        if (apps.isEmpty()) {
            // 没有可整理的图标：锁定屏照样压缩屏号，小组件按策略安置
            plan.screenCount = settle(plan, plan.lockedCount);
            return plan;
        }

        // 2) 分组。功能方案只看包名与名字；颜色方案要真的去取图标主色。
        PackageManager pm = ctx.getPackageManager();
        Map<String, List<DesktopModel.Item>> grouped = new LinkedHashMap<>();
        for (DesktopModel.Item it : apps) {
            String g = scheme == AppClassifier.Scheme.COLOR
                    ? groupByColor(pm, it)
                    : groupByFunction(pm, it);
            List<DesktopModel.Item> list = grouped.get(g);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(g, list);
            }
            list.add(it);
        }

        // 组内按名字排，否则同一个文件夹里每次顺序都不一样，看着像被打乱了
        for (List<DesktopModel.Item> list : grouped.values()) {
            Collections.sort(list, (a, b) -> {
                String x = a.title == null ? "" : a.title;
                String y = b.title == null ? "" : b.title;
                return x.compareToIgnoreCase(y);
            });
        }

        // 3) 排屏：转成 LayoutPlan 认识的形状，直接用同一份算法。
        //    先把小组件挡掉的格位算出来——不传这个，第 1 屏就会排出 6 个文件夹，
        //    和那个 4×2 的日历叠在一起。
        plan.slots.putAll(slotsOf(plan.stayPut));
        Map<String, List<AppEntry>> forPlan = new LinkedHashMap<>();
        for (Map.Entry<String, List<DesktopModel.Item>> e : grouped.entrySet()) {
            List<AppEntry> fake = new ArrayList<>(e.getValue().size());
            for (DesktopModel.Item it : e.getValue()) {
                fake.add(new AppEntry(it.title, it.pkg, "", false, null));
            }
            forPlan.put(e.getKey(), fake);
        }
        LayoutPlan layout = LayoutPlan.build(
                forPlan, firstScreenBlank, pins, plan.slots, plan.lockedCount);

        // 4) 屏幕内序号 → 2×2 格位。每屏 6 个文件夹，按 2 列 3 行铺。
        Map<String, Bucket> byGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<DesktopModel.Item>> e : grouped.entrySet()) {
            Bucket b = new Bucket(e.getKey());
            b.members.addAll(e.getValue());
            byGroup.put(e.getKey(), b);
            plan.buckets.add(b);
        }
        int maxScreen = 0;
        for (LayoutPlan.Screen s : layout.screens) {
            int idx = 0;
            for (LayoutPlan.Folder lf : s.folders) {
                Bucket b = byGroup.get(lf.group);
                if (b == null) {
                    continue;
                }
                b.screen = s.number;
                // 站哪个候选位由 Screen 说了算：被小组件压掉的那几个位不能进
                int slot = s.slotOf(idx);
                b.cellX = (slot % 2) * LayoutPlan.FOLDER_SPAN;
                b.cellY = (slot / 2) * LayoutPlan.FOLDER_SPAN;
                idx++;
                if (s.number > maxScreen) {
                    maxScreen = s.number;
                }
            }
        }
        // 没排上的（理论上不会有）兜底放在最后，避免它们凭空消失
        for (Bucket b : plan.buckets) {
            if (b.screen <= 0) {
                b.screen = maxScreen + 1;
                b.cellX = 0;
                b.cellY = 0;
                maxScreen = b.screen;
            }
        }
        plan.screenCount = settle(plan, maxScreen);
        return plan;
    }

    /**
     * 收尾：安置未锁定屏上的小组件，算出最终屏数。
     *
     * <p>保留时把它们整体挪到最后一屏之后（保持各自 cellX/cellY，只要原来不重叠，
     * 挪过去也不重叠）；清空时记进 {@link #dropIds} 交给写入层删除。
     *
     * @param maxScreen 新文件夹占到的最大屏号
     * @return 整理后的总屏数
     */
    private static int settle(DeskPlan plan, int maxScreen) {
        int total = Math.max(maxScreen, plan.lockedCount);
        if (plan.orphanWidgets.isEmpty()) {
            return total;
        }
        if (plan.keepOrphanWidgets) {
            int orphanScreen = total + 1;
            for (DesktopModel.Item w : plan.orphanWidgets) {
                plan.frozenScreens.put(w.id, orphanScreen);
            }
            return orphanScreen;
        }
        for (DesktopModel.Item w : plan.orphanWidgets) {
            plan.dropIds.add(w.id);
        }
        return total;
    }

    // ───────────────────────── 分组 ─────────────────────────

    /**
     * 算每屏还剩下哪些文件夹位：小组件按它自己的 span 盖住候选格，被盖住的位置直接剔掉。
     *
     * <p>候选位就是 Launcher 上那 6 个 2×2 的位置，(0,0) (2,0) (0,2) (2,2) (0,4) (2,4)。
     * 一个 4×2 的日历压掉上面两个，剩下 {2,3,4,5}——注意是"剩下这四个具体位置"，
     * 不是"还剩四个"，两者写进库里的结果完全不同。
     */
    private static Map<Integer, int[]> slotsOf(List<DesktopModel.Item> stayPut) {
        int total = LayoutPlan.FOLDERS_PER_SCREEN;
        Map<Integer, java.util.Set<Integer>> blocked = new LinkedHashMap<>();
        for (DesktopModel.Item it : stayPut) {
            // 文件夹内的组件 screen 是 -1，不在任何屏上，不影响桌面的格位
            if (it.screen <= 0) {
                continue;
            }
            // 文件夹本体不是障碍物，是这次要被挪走/删掉的东西。
            // 0.11.1 的调用方已经不再把它塞进 stayPut，这里再挡一道：
            // 一旦有人又把文件夹放进来，每屏的可用位就会被旧布局切碎，
            // 新文件夹只能落在旧布局剩下的缝里——用户看到的就是"不是从左上角开始"。
            if (it.isFolder()) {
                continue;
            }
            int sx = it.cellX;
            int sy = it.cellY;
            int ex = sx + Math.max(1, it.spanX) - 1;
            int ey = sy + Math.max(1, it.spanY) - 1;
            for (int i = 0; i < total; i++) {
                int cx = (i % 2) * LayoutPlan.FOLDER_SPAN;
                int cy = (i / 2) * LayoutPlan.FOLDER_SPAN;
                boolean overlap = cx <= ex && cx + LayoutPlan.FOLDER_SPAN - 1 >= sx
                        && cy <= ey && cy + LayoutPlan.FOLDER_SPAN - 1 >= sy;
                if (overlap) {
                    java.util.Set<Integer> set = blocked.get(it.screen);
                    if (set == null) {
                        set = new java.util.HashSet<>();
                        blocked.put(it.screen, set);
                    }
                    set.add(i);
                }
            }
        }
        Map<Integer, int[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, java.util.Set<Integer>> e : blocked.entrySet()) {
            java.util.Set<Integer> b = e.getValue();
            int[] free = new int[Math.max(0, total - b.size())];
            int k = 0;
            for (int i = 0; i < total; i++) {
                if (!b.contains(i)) {
                    free[k++] = i;
                }
            }
            out.put(e.getKey(), free);
        }
        return out;
    }

    private static String groupByFunction(PackageManager pm, DesktopModel.Item it) {
        boolean sys = isSystem(pm, it.pkg);
        return AppClassifier.groupByFunction(new AppEntry(it.title, it.pkg, "", sys, null));
    }

    /** 取图标 → 算主色 → 分桶。丢了的应用（包名还在、App 已卸）落到黑白灰，不阻塞整理。 */
    private static String groupByColor(PackageManager pm, DesktopModel.Item it) {
        if (it.pkg == null || it.pkg.isEmpty()) {
            return "黑白灰";
        }
        Drawable d = null;
        try {
            d = pm.getApplicationIcon(it.pkg);
        } catch (Throwable ignored) {
        }
        return IconPalette.groupOf(IconPalette.dominantColor(d));
    }

    private static boolean isSystem(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return false;
        }
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 一行摘要，用于确认框与状态行。 */
    public String summary() {
        return "把 " + total + " 个图标整理成 " + buckets.size() + " 个文件夹 · 共 "
                + screenCount + " 屏";
    }
}
