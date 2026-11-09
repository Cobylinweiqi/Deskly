package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
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
import java.util.TreeMap;

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
 * <p><b>0.16.5：不重叠是硬约束，不是"尽量"。</b>{@link LayoutPlan} 只决定"第几屏、
 * 第几个候选位"，最后落到 {@code cellX/cellY} 之前还要过一遍 {@link #placeBuckets}：
 * 按桌面实际留下的小组件、旧文件夹占位、4×6 网格边界逐个避让，一个屏放不下就往后开新屏。
 * 写库层还会再校验一次，发现重叠直接中止，绝不肯把一份会叠在一起的布局灌进真库。
 *
 * <p>不参与整理的东西：热区图标（底部固定栏）、小组件与组件（itemType=19 等）。
 * 前者是刻意固定的入口，后者塞不进文件夹——它们原地不动，所以「第一屏留空」实际是
 * 「第一屏只留小组件，不摆应用图标」，这一点在界面上要如实说，不能让用户以为整屏会被清空。
 */
public final class DeskPlan {

    /** 一个占格矩形。屏号统一用内存里的 1 起编号，写库时才由 LauncherProfile 换算。 */
    public static final class Rect {
        public final int screen;
        public final int x;
        public final int y;
        public final int w;
        public final int h;

        public Rect(int screen, int x, int y, int w, int h) {
            this.screen = screen;
            this.x = x;
            this.y = y;
            this.w = Math.max(1, w);
            this.h = Math.max(1, h);
        }

        /** 两个矩形有没有共用任何一格。 */
        public boolean intersects(Rect o) {
            return o != null && screen == o.screen
                    && x <= o.x + o.w - 1 && x + w - 1 >= o.x
                    && y <= o.y + o.h - 1 && y + h - 1 >= o.y;
        }

        /** 有没有越出 4×6 网格。越界的坐标桌面会自己挪，挪完就可能和别人叠在一起。 */
        public boolean inGrid() {
            return x >= 0 && y >= 0
                    && x + w <= LayoutPlan.COLUMNS
                    && y + h <= LayoutPlan.ROWS;
        }
    }

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
     * 屏号 → 这一屏还能用的文件夹候选位（默认全能用）。
     *
     * <p>桌面上的小组件会整块占住格子。真机第 1 屏摆了一个 4×2 的日历，把 (0,0) 和 (2,0)
     * 两个位置压掉了。这里记的是<b>具体哪几个位不能用</b>，而不是"这一屏少放两个"——
     * 只减数量的话，文件夹照样会被放进前四个候选位，正好撞上日历。
     */
    public final Map<Integer, int[]> slots = new LinkedHashMap<>();
    /** 参与整理的应用图标总数。 */
    public int total;
    /** 落进「其它」的图标数：0.16.0 起在确认框里如实报出来，方便对比词典优化效果。 */
    public int unknownCount;
    /**
     * 布局库里实际读到的行数。0.16.1：和 {@link #total} 一起报出来——
     * 「读到 155 行、只有 6 个图标参与整理」一眼就能判定是 isApp() 判据丢了图标，
     * 而不是分类不准或写回没生效，省掉一轮 root 诊断。
     */
    public int readRows;
    /** 未参与整理的条目数（文件夹本体 / 热区 / 小组件 / 锁定屏 / 认不出的类型）。 */
    public int skippedRows;
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

    /**
     * 0.16.5：删不掉、又没被本次分组复用的旧文件夹。
     *
     * <p>它们里面有非应用成员（小米「工具」文件夹里那个 itemType=1 的「查找」入口），
     * 写库层会保护它们不删。旧版让它们留在原来的 screen/cellX/cellY 上，而新方案恰恰
     * 是从每屏左上角开始排——于是新旧两个文件夹占同一格，「有些小米文件夹堆在一起」
     * 有一部分就是这么来的。现在整体挪到所有新文件夹之后的最后一屏，内容一点不丢。
     */
    public final Set<Integer> strayFolderIds = new LinkedHashSet<>();

    /** 未锁定屏的小组件是否保留（true=挪到最后一屏，false=删除）。 */
    public boolean keepOrphanWidgets = true;

    /** 锁定屏数量。新文件夹从 {@code lockedCount + 1} 屏开始。 */
    public int lockedCount;

    /** 新文件夹的起始屏号（与 LayoutPlan 保持一致，用于回执与预览）。 */
    public int startScreen = 1;

    /**
     * 这份方案依据的桌面方言。写库时要靠它生成正确的 itemType / 屏号，
     * 由 {@code build} 从 {@link DesktopModel#profile} 带下来，不接受另猜一次。
     */
    public LauncherProfile profile;

    /** 文件夹占几格，由读库时从现有文件夹坐标探测。排屏与写库必须共用它。 */
    public int folderSpan = 2;

    // ───────────────────────── 不重叠校验 ─────────────────────────

    /**
     * 屏号 → 桌面上会一直占着格的矩形（小组件等）。
     * 预览的可用候选位、最终排位、写库前的校验都读这一份，保证三方说的是同一件事。
     */
    public final Map<Integer, List<Rect>> obstacles = new LinkedHashMap<>();

    /** 最终排位里重叠的文件夹对数；正常必须为 0。 */
    public int overlapCount;

    /** 为了避开小组件 / 旧文件夹 / 边界而挪过位置的文件夹数，只用于回执说明。 */
    public int adjustedCount;

    /** 数一遍矩形两两相交的对数，公开给写库层与单测做硬校验。 */
    public static int countOverlaps(List<Rect> rects) {
        int n = 0;
        for (int i = 0; i < rects.size(); i++) {
            for (int j = i + 1; j < rects.size(); j++) {
                if (rects.get(i).intersects(rects.get(j))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 给状态行 / 确认框用的一句话。 */
    public String placementNote() {
        if (overlapCount > 0) {
            return "⚠ 检出 " + overlapCount + " 对文件夹位置重叠，请重新分析";
        }
        String s = "已校验：文件夹之间、与小组件之间均不重叠";
        return adjustedCount > 0 ? s + "（自动避让 " + adjustedCount + " 处）" : s;
    }

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
        return build(ctx, model, scheme, firstScreenBlank, pins, lockedScreens,
                keepOrphanWidgets, 0);
    }

    /**
     * 0.16.5：调用方<b>显式</b>把生效占位传进来。
     *
     * <p>以前只有预览这条路径会调 {@code model.folderSpan = plan.folderSpan()}；真正写库的
     * {@code DesktopSection#doApply} 会为了防用户刚拖过图标而重读一次桌面，那份 fresh model
     * 带着的是<b>探测原始值</b>，于是用户在界面上选的「小 1×1 / 大 2×2」在写进去时被悄悄丢掉：
     * 预览按 2×2 排、写入按 1×1 密排，桌面按 2×2 渲染就是一堆方块互相压住。
     * 现在把生效值作为参数传进来，谁都不能假装没看见。
     *
     * @param effectiveFolderSpan 1 / 2 时以它为准；0 表示沿用 model 的探测值（老调用方）。
     */
    public static DeskPlan build(Context ctx, DesktopModel model, AppClassifier.Scheme scheme,
                                 boolean firstScreenBlank, Map<String, Integer> pins,
                                 Set<Integer> lockedScreens, boolean keepOrphanWidgets,
                                 int effectiveFolderSpan) {
        DeskPlan plan = new DeskPlan();
        plan.profile = model.profile;
        plan.folderSpan = effectiveFolderSpan == 1 || effectiveFolderSpan == 2
                ? effectiveFolderSpan
                : (model.folderSpan > 0 ? model.folderSpan : 2);
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
        plan.readRows = model.totalItems;
        plan.skippedRows = Math.max(0, model.totalItems - apps.size());
        if (apps.isEmpty()) {
            // 没有可整理的图标：锁定屏照样压缩屏号，小组件按策略安置，
            // 删不掉的旧文件夹也整体挪到最后一屏，别留在中间屏占位
            collectStrayFolders(plan, model, remap, Collections.emptySet());
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
            if (scheme == AppClassifier.Scheme.FUNCTION && AppClassifier.isUnknown(g)) {
                plan.unknownCount++;
            }
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

        // 哪些旧文件夹这次真的会被复用（标题命中了新组名），其余删不掉的搬到最后一屏
        Set<Integer> reusedIds = new HashSet<>();
        for (String g : grouped.keySet()) {
            Integer id = plan.reusable.get(g);
            if (id != null) {
                reusedIds.add(id);
            }
        }
        collectStrayFolders(plan, model, remap, reusedIds);

        // 3) 排屏：转成 LayoutPlan 认识的形状，直接用同一份算法。
        //    先把小组件挡掉的格位算出来——不传这个，第 1 屏就会排出 6 个文件夹，
        //    和那个 4×2 的日历叠在一起。
        collectObstacles(plan);
        plan.slots.putAll(freeSlots(plan.obstacles, plan.folderSpan));
        Map<String, List<AppEntry>> forPlan = new LinkedHashMap<>();
        for (Map.Entry<String, List<DesktopModel.Item>> e : grouped.entrySet()) {
            List<AppEntry> fake = new ArrayList<>(e.getValue().size());
            for (DesktopModel.Item it : e.getValue()) {
                fake.add(new AppEntry(it.title, it.pkg, "", false, null));
            }
            forPlan.put(e.getKey(), fake);
        }
        LayoutPlan layout = LayoutPlan.build(
                forPlan, firstScreenBlank, pins, plan.slots, plan.lockedCount, plan.folderSpan);

        // 4) 屏幕内序号 → 格位。然后交给 placeBuckets 做最终的避让与不重叠校验。
        Map<String, Bucket> byGroup = new LinkedHashMap<>();
        for (Map.Entry<String, List<DesktopModel.Item>> e : grouped.entrySet()) {
            Bucket b = new Bucket(e.getKey());
            b.members.addAll(e.getValue());
            byGroup.put(e.getKey(), b);
            plan.buckets.add(b);
        }
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
                b.cellX = LayoutPlan.cellX(slot, plan.folderSpan);
                b.cellY = LayoutPlan.cellY(slot, plan.folderSpan);
                idx++;
            }
        }
        // 没排上的（理论上不会有）统一交给 placeBuckets 从起始屏开始找空位，
        // 旧版在这里给它们全塞 maxScreen+1 屏的 (0,0)，多个组就会叠成一摞。
        for (Bucket b : plan.buckets) {
            if (b.screen <= 0) {
                b.screen = plan.startScreen;
            }
        }
        int highest = placeBuckets(plan);
        plan.screenCount = settle(plan, highest);
        return plan;
    }

    // ───────────────────────── 不重叠排位 ─────────────────────────

    /** 某屏第 slot 个候选位对应的文件夹矩形。 */
    private static Rect rectAt(int screen, int slot, int span) {
        return new Rect(screen, LayoutPlan.cellX(slot, span), LayoutPlan.cellY(slot, span),
                span, span);
    }

    private static boolean intersectsAny(Rect r, List<Rect> list) {
        if (list == null) {
            return false;
        }
        for (Rect o : list) {
            if (r.intersects(o)) {
                return true;
            }
        }
        return false;
    }

    /** 收集桌面上不会被移走、会一直占格的条目（小组件 / 组件）。 */
    private static void collectObstacles(DeskPlan plan) {
        for (DesktopModel.Item it : plan.stayPut) {
            // 文件夹内成员的 screen 是 -1，不在任何屏上；文件夹本体不进 stayPut（见上文）
            if (it.screen <= 0 || it.isFolder()) {
                continue;
            }
            List<Rect> list = plan.obstacles.get(it.screen);
            if (list == null) {
                list = new ArrayList<>();
                plan.obstacles.put(it.screen, list);
            }
            list.add(new Rect(it.screen, it.cellX, it.cellY, it.spanX, it.spanY));
        }
    }

    /**
     * 算每屏还剩下哪些文件夹位：小组件按它自己的 span 盖住候选格，被盖住的位置直接剔掉。
     *
     * <p>候选位就是 Launcher 上那 6 个 2×2 的位置，(0,0) (2,0) (0,2) (2,2) (0,4) (2,4)。
     * 一个 4×2 的日历压掉上面两个，剩下 {0,2,3,4,5}——注意是"剩下这几个具体位置"，
     * 不是"还剩五个"，两者写进库里的结果完全不同。空数组也要保留：整屏被占满时
     * 容量必须是 0，不能被 LayoutPlan 当成"没传，用默认 6 个"。
     */
    private static Map<Integer, int[]> freeSlots(Map<Integer, List<Rect>> obstacles, int span) {
        int total = LayoutPlan.slotsForSpan(span);
        Map<Integer, int[]> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Rect>> e : obstacles.entrySet()) {
            int screen = e.getKey();
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                Rect r = rectAt(screen, i, span);
                if (!r.inGrid() || intersectsAny(r, e.getValue())) {
                    continue;
                }
                free.add(i);
            }
            int[] a = new int[free.size()];
            for (int k = 0; k < a.length; k++) {
                a[k] = free.get(k);
            }
            out.put(screen, a);
        }
        return out;
    }

    /**
     * 删不掉、又没被复用的旧文件夹，整体挪到最后一屏。
     *
     * <p>判据刻意与写库层的 {@code protectedFolders} 保持一致：看成员行的 itemType 是否
     * 落在本 ROM 的应用枚举里（{@link LauncherProfile#isApp(int)}），而不是用带结构兜底的
     * {@code Item.isApp()}。两者一旦不一致，就会出现"我们认为它会被删掉、写库却把它留下"，
     * 于是它继续占着老格子，和新文件夹叠在一起。
     */
    private static void collectStrayFolders(DeskPlan plan, DesktopModel model,
                                            Map<Integer, Integer> remap,
                                            Set<Integer> reusedIds) {
        LauncherProfile prof = model.profile;
        for (DesktopModel.Folder f : model.folders) {
            if (remap.containsKey(f.screen) || reusedIds.contains(f.id)) {
                continue;
            }
            boolean protectedBody = false;
            for (DesktopModel.Item m : f.members) {
                boolean appRow = prof == null
                        ? (m.itemType == DesktopModel.TYPE_APP
                        || m.itemType == DesktopModel.TYPE_SHORTCUT)
                        : prof.isApp(m.itemType);
                if (!appRow) {
                    protectedBody = true;
                    break;
                }
            }
            if (protectedBody) {
                plan.strayFolderIds.add(f.id);
            }
        }
    }

    /**
     * 最终排位：按屏号顺序把每个文件夹放进一个真正空着的格子。
     *
     * <p>为什么还要再排一遍：{@link LayoutPlan} 只知道"这屏还有几个候选位"，钉屏钉过头、
     * 旧文件夹残留、候选位数组临时对不上，都可能让它给出一个越界或重复的格位。这里以
     * <b>矩形相交</b>为准，逐个试候选位；撞上小组件、撞上已放好的文件夹、越出 4×6 网格
     * 就换下一个位，这一屏没位了就往后开一屏。写出去的坐标因此必然两两不相交。
     *
     * @return 实际用到的最大屏号
     */
    private static int placeBuckets(DeskPlan plan) {
        int span = plan.folderSpan;
        int[] all = LayoutPlan.defaultSlots(span);
        Map<Integer, List<Rect>> used = new LinkedHashMap<>();
        for (Map.Entry<Integer, List<Rect>> e : plan.obstacles.entrySet()) {
            used.put(e.getKey(), new ArrayList<>(e.getValue()));
        }

        TreeMap<Integer, List<Bucket>> byScreen = new TreeMap<>();
        for (Bucket b : plan.buckets) {
            int s = b.screen > 0 ? b.screen : plan.startScreen;
            List<Bucket> list = byScreen.get(s);
            if (list == null) {
                list = new ArrayList<>();
                byScreen.put(s, list);
            }
            list.add(b);
        }

        int highest = 0;
        int adjusted = 0;
        List<Bucket> pending = new ArrayList<>();
        for (Map.Entry<Integer, List<Bucket>> e : byScreen.entrySet()) {
            int screen = Math.max(Math.max(e.getKey(), plan.startScreen), highest + 1);
            pending.addAll(e.getValue());
            int guard = 0;
            while (!pending.isEmpty() && guard++ < 512) {
                int[] slots = plan.slots.containsKey(screen) ? plan.slots.get(screen) : all;
                int idx = 0;
                while (idx < slots.length && !pending.isEmpty()) {
                    Rect r = rectAt(screen, slots[idx++], span);
                    if (!r.inGrid() || intersectsAny(r, used.get(screen))) {
                        continue;
                    }
                    Bucket b = pending.remove(0);
                    if (b.screen != screen || b.cellX != r.x || b.cellY != r.y) {
                        adjusted++;
                    }
                    b.screen = screen;
                    b.cellX = r.x;
                    b.cellY = r.y;
                    List<Rect> list = used.get(screen);
                    if (list == null) {
                        list = new ArrayList<>();
                        used.put(screen, list);
                    }
                    list.add(r);
                }
                if (pending.isEmpty()) {
                    break;
                }
                screen++;
            }
            highest = Math.max(highest, screen);
        }

        plan.adjustedCount = adjusted;
        List<Rect> rects = new ArrayList<>();
        for (Bucket b : plan.buckets) {
            rects.add(new Rect(b.screen, b.cellX, b.cellY, span, span));
        }
        plan.overlapCount = countOverlaps(rects);
        return Math.max(highest, plan.lockedCount);
    }

    /**
     * 收尾：安置未锁定屏上的小组件与删不掉的旧文件夹，算出最终屏数。
     *
     * <p>保留时把它们整体挪到最后一屏之后（保持各自 cellX/cellY，只要原来不重叠，
     * 挪过去也不重叠）；清空时记进 {@link #dropIds} 交给写入层删除。
     * 旧文件夹搬过去后既不会被删，也不再和任何新文件夹抢格子。
     *
     * @param maxScreen 新文件夹占到的最大屏号
     * @return 整理后的总屏数
     */
    private static int settle(DeskPlan plan, int maxScreen) {
        int total = Math.max(maxScreen, plan.lockedCount);
        boolean needTrailing = !plan.strayFolderIds.isEmpty()
                || (plan.keepOrphanWidgets && !plan.orphanWidgets.isEmpty());
        if (!needTrailing) {
            if (!plan.keepOrphanWidgets) {
                for (DesktopModel.Item w : plan.orphanWidgets) {
                    plan.dropIds.add(w.id);
                }
            }
            return total;
        }
        int trailing = total + 1;
        if (plan.keepOrphanWidgets) {
            for (DesktopModel.Item w : plan.orphanWidgets) {
                plan.frozenScreens.put(w.id, trailing);
            }
        } else {
            for (DesktopModel.Item w : plan.orphanWidgets) {
                plan.dropIds.add(w.id);
            }
        }
        for (int id : plan.strayFolderIds) {
            plan.frozenScreens.put(id, trailing);
            plan.keptFolderIds.add(id);
        }
        return trailing;
    }

    // ───────────────────────── 分组 ─────────────────────────

    private static String groupByFunction(PackageManager pm, DesktopModel.Item it) {
        // 与方案卡预览共用 AppClassifier.fromDesktop：应用名、category、系统标志只在一处补齐，
        // 免得预览说 9 个文件夹、实际排出来 11 个。
        return AppClassifier.groupByFunction(pm, it.title, it.pkg);
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

    /** 一行摘要，用于确认框与状态行。 */
    public String summary() {
        String s = "把 " + total + " 个图标整理成 " + buckets.size() + " 个文件夹 · 共 "
                + screenCount + " 屏";
        if (unknownCount > 0) {
            s += " · 其中 " + unknownCount + " 个未识别";
        }
        return s;
    }
}
