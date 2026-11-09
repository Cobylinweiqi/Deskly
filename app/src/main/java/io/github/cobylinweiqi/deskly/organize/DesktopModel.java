package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面布局的内存模型：从<b>快照副本</b>里读出来，不接触设备上正在用的那份库。
 *
 * <p>分成三类：
 * <ul>
 *   <li>{@link Folder}——一级文件夹，自带成员列表；具体 itemType 由
 *       {@link LauncherProfile} 按库识别（小米 21 / Launcher3 系 2）；</li>
 *   <li>{@link Item} 散落图标——直接摆在桌面或热区上的应用/快捷方式/组件；</li>
 *   <li>热区图标——container=-101，通常不该动，但要知道它们占着位置。</li>
 * </ul>
 *
 * <p>这层只读不写。所有修改都由 {@code LayoutWriter} 在另一份副本上完成，
 * 这样"看"和"改"永远作用在不同的文件上，分析阶段不可能把库改坏。
 */
public final class DesktopModel {

    public static final int CONTAINER_DESKTOP = -100;
    public static final int CONTAINER_HOTSEAT = -101;

    /** 小米 / HyperOS 的 favorites.itemType 取值。Launcher3 系见 {@link LauncherProfile}。 */
    public static final int TYPE_APP = 0;
    public static final int TYPE_SHORTCUT = 14;
    public static final int TYPE_GROUP = 19;
    public static final int TYPE_FOLDER = 21;
    /**
     * HyperOS 4 的「小文件夹」（1×1）。真机日志里同一条桌面会把两种文件夹分别读成
     * {@code ItemType.userFolder(2)} 与 {@code ItemType.folder_2X2_4(21)}，
     * 前者按 {@code (0,0,1-1)}、后者按 {@code (0,2,2-2)} 摆放。
     * 也就是说文件夹尺寸编码在 itemType 上，光改 spanX/spanY 桌面不认。
     */
    public static final int TYPE_FOLDER_SMALL = 2;

    /** 小米文件夹打开后是 4 列。用来给新成员算建议行号。 */
    public static final int FOLDER_COLUMNS = 4;

    /** 这份库识别出来的方言。上游排版、下游写库都用它，避免各猜各的。 */
    public LauncherProfile profile;

    public static class Item {
        public int id;
        public int container;
        public int screen;
        public int cellX;
        public int cellY;
        public int spanX = 1;
        public int spanY = 1;
        public int itemType;
        /** 库里存的标题，可能是 {@code com.miui.home:string/xxx} 这种资源引用。 */
        public String rawTitle = "";
        /** 解析后的可读名字。 */
        public String title = "";
        /** 形如 {@code com.hexin.plat.android}，取不到则空串。 */
        public String pkg = "";
        /** 完整组件名 {@code pkg/cls}。 */
        public String component = "";
        /** 库里这一行有没有 intent。跨 ROM 判断「这是应用图标」比 itemType 可靠。 */
        public boolean hasIntent;
        /**
         * 由 {@link DesktopModel#read} 填入。手工构造的 Item 允许为 null，
         * 此时退回小米语义（也就是 0.14.1 的行为），保证老调用方一个都不用改。
         */
        public LauncherProfile profile;

        public boolean isFolder() {
            return profile == null ? itemType == TYPE_FOLDER : profile.isFolder(itemType);
        }

        public boolean isApp() {
            if (profile == null) {
                return itemType == TYPE_APP || itemType == TYPE_SHORTCUT;
            }
            if (profile.isApp(itemType)) {
                return true;
            }
            // Launcher3 系兜底：厂商用了私有 itemType 时，只要有 intent 且不是
            // 文件夹 / 组件，就当它是可整理的应用图标。
            if (!profile.isMiui() && hasIntent && !isFolder()
                    && itemType != 4 && itemType != TYPE_GROUP) {
                return true;
            }
            // 0.16.1 通用兜底：LauncherProfile 探测应用枚举时明确排除了 itemType=4
            // 和 19（AOSP 里 4 是 APPWIDGET），但厂商私有枚举里 4 完全可能就是应用图标。
            // 这类行一旦被判成非应用就会进 stayPut 留在原屏——用户看到的正是
            // 「很多应用没进文件夹」。所以这里不再看 itemType 具体数值，改看结构事实：
            // 有 component 包名、占 1×1 格、不是文件夹/分组、不在热区。
            // 真正的小组件一般没有 component 且占多格，进不来。
            return looksLikeAppIcon();
        }

        /** 有明确 component 的 1×1 图标行；文件夹 / 分组 / 热区 / 多格组件一律不算。 */
        private boolean looksLikeAppIcon() {
            if (!hasIntent || pkg == null || pkg.isEmpty() || isFolder()) {
                return false;
            }
            if (itemType == TYPE_GROUP) {
                return false;
            }
            return spanX <= 1 && spanY <= 1 && container != CONTAINER_HOTSEAT;
        }
    }

    public static final class Folder extends Item {
        public final List<Item> members = new ArrayList<>();

        /** 成员里最大的序号，插入新成员时接在它后面。 */
        public int maxCellX() {
            int m = -1;
            for (Item i : members) {
                if (i.cellX > m) {
                    m = i.cellX;
                }
            }
            return m;
        }

        public int maxCellY() {
            int m = 0;
            for (Item i : members) {
                if (i.cellY > m) {
                    m = i.cellY;
                }
            }
            return m;
        }

        /** 已经占掉的格位，{@code cellX|cellY}。手动拖拽会让序号不连续，所以必须按格位算。 */
        public java.util.Set<String> occupiedCells() {
            java.util.Set<String> s = new java.util.HashSet<>();
            for (Item i : members) {
                s.add(i.cellX + "|" + i.cellY);
            }
            return s;
        }
    }

    public final List<Folder> folders = new ArrayList<>();
    /** 桌面（-100）上不属于任何文件夹的可见条目。 */
    public final List<Item> looseOnDesktop = new ArrayList<>();
    /** 热区（-101）上的条目。 */
    public final List<Item> hotseat = new ArrayList<>();
    public int totalRows;
    public int screenCount;
    /** favorites 表里的总行数，用来在界面上核对"读到的确实是这个库"。 */
    public int totalItems;
    /**
     * 桌面上文件夹占几格（1 或 2）。排屏、写回、预览共用这一份，见 {@link #detectFolderSpan}。
     *
     * <p>读库刚出来时可能是 0（没有可信样本）；界面会用 {@link PlanConfig#effectiveFolderSpan(int)}
     * 把它替换成最终生效值后再传给 {@link DeskPlan}。手工构造的 model 默认 1。
     */
    public int folderSpan = 1;

    public Folder folderById(int id) {
        for (Folder f : folders) {
            if (f.id == id) {
                return f;
            }
        }
        return null;
    }

    /** 真正"可以归位"的散落图标：桌面上的应用/快捷方式。组件类不动。 */
    public List<Item> relocatable() {
        List<Item> out = new ArrayList<>();
        for (Item i : looseOnDesktop) {
            if (i.isApp()) {
                out.add(i);
            }
        }
        return out;
    }

    public String brief() {
        return screenCount + " 屏 · " + folders.size() + " 个文件夹 · "
                + looseOnDesktop.size() + " 个散落条目 · " + hotseat.size() + " 个热区图标";
    }

    // ───────────────────────── 读取 ─────────────────────────

    /**
     * 从数据库副本读出布局。只开 READONLY，连误写的可能性都掐掉。
     */
    public static DesktopModel read(File copy, Context ctx, String launcherPkg) throws Exception {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                copy.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            return read(db, ctx, launcherPkg);
        } finally {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static DesktopModel read(SQLiteDatabase db, Context ctx, String launcherPkg) {
        DesktopModel m = new DesktopModel();
        m.profile = LauncherProfile.detect(db, launcherPkg);
        if (!m.profile.hasLayoutTable()) {
            throw new IllegalStateException("这份库不是桌面布局库（没有 favorites 或等价表）；表清单："
                    + LauncherProfile.describe(db));
        }
        Map<Integer, Folder> byId = new LinkedHashMap<>();

        String sql = "SELECT _id,title,intent,container,screen,cellX,cellY,spanX,spanY,itemType"
                + " FROM " + m.profile.sqlTable();
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Item it = new Item();
                it.profile = m.profile;
                it.id = c.getInt(0);
                it.rawTitle = c.getString(1) == null ? "" : c.getString(1);
                String intent = c.getString(2);
                it.container = c.getInt(3);
                // 屏号在内存里一律 1 起算，写回时再由 DeskWriter 反算回库的约定。
                // 小米 screenBase=1，这一步是恒等变换；Launcher3 系为 0 时才真正平移。
                int rawScreen = c.getInt(4);
                // 0.16.3：真我私有库里桌面行的 container 未必是 -100（状态行会显示
                // 「0 个散在桌面上」），只按「非热区、非文件夹成员」判断；屏号一律交给
                // 方言映射，Launcher3 系现在按屏表 id 反查，不再靠 screenBase 做加减法。
                it.screen = it.container <= 0 && it.container != CONTAINER_HOTSEAT
                        && rawScreen >= 0
                        ? m.profile.fromDbScreen(rawScreen) : rawScreen;
                it.cellX = c.getInt(5);
                it.cellY = c.getInt(6);
                it.spanX = c.isNull(7) ? 1 : c.getInt(7);
                it.spanY = c.isNull(8) ? 1 : c.getInt(8);
                it.itemType = c.getInt(9);
                it.hasIntent = intent != null && !intent.isEmpty();
                parseComponent(it, intent);
                it.title = resolveTitle(ctx, launcherPkg, it.rawTitle);
                m.totalItems++;

                if (it.container > 0) {
                    // 文件夹成员，先挂起来，等文件夹本体出现后再分组
                    Folder owner = byId.get(it.container);
                    if (owner != null) {
                        owner.members.add(it);
                    } else {
                        m.pendingMembers.add(it);
                    }
                } else if (it.container == CONTAINER_HOTSEAT) {
                    m.hotseat.add(it);
                } else {
                    m.looseOnDesktop.add(it);
                }

                if (it.isFolder() && it.container < 0) {
                    Folder f = new Folder();
                    f.profile = m.profile;
                    f.id = it.id;
                    f.rawTitle = it.rawTitle;
                    f.title = it.title;
                    f.container = it.container;
                    f.screen = it.screen;
                    f.cellX = it.cellX;
                    f.cellY = it.cellY;
                    f.spanX = it.spanX;
                    f.spanY = it.spanY;
                    f.itemType = it.itemType;
                    byId.put(f.id, f);
                    m.folders.add(f);
                    // 文件夹可能出现在成员之后（_id 顺序不保证），补挂已到的成员
                    List<Item> mine = new ArrayList<>();
                    for (Item p : m.pendingMembers) {
                        if (p.container == f.id) {
                            mine.add(p);
                        }
                    }
                    f.members.addAll(mine);
                    m.pendingMembers.removeAll(mine);
                }
            }
        }

        // 兜底：成员先于文件夹出现且文件夹根本不存在（库损坏的极端情况），别让它们凭空消失
        for (Item p : m.pendingMembers) {
            m.looseOnDesktop.add(p);
        }

        // 文件夹里成员也可能先于文件夹被读到，上面已补挂；这里再补一次防止漏
        for (Folder f : m.folders) {
            if (f.members.isEmpty()) {
                for (Item it : m.looseOnDesktop) {
                    if (it.container == f.id) {
                        f.members.add(it);
                    }
                }
            }
        }

        int maxScreen = 0;
        int maxCellY = 0;
        for (Item it : m.looseOnDesktop) {
            if (it.screen > maxScreen) {
                maxScreen = it.screen;
            }
            if (it.cellY > maxCellY) {
                maxCellY = it.cellY;
            }
        }
        m.screenCount = maxScreen;
        m.totalRows = maxCellY + 1;
        m.folderSpan = detectFolderSpan(m.folders);
        return m;
    }

    /**
     * 从库里现有文件夹的坐标推断「一个文件夹占几格」。
     *
     * <p><b>不能只看 spanX。</b>小米 / HyperOS 的库里文件夹行常写着 {@code spanX=2}，
     * 但桌面把文件夹当普通图标排（4 列 × 6 行，一屏 24 个）。按 2×2 算就会把
     * 13 个分类文件夹摊到 3 屏，而桌面实际一屏就装下了——预览说第 2、3 屏、
     * 桌面上却全挤在第 1 屏，就是这么来的。坐标是桌面自己写回来的，最可信：
     * 同一屏同一行出现相邻 cellX（差 1），或一行超过 2 个文件夹，就是 1 格网格。
     *
     * <p><b>0.16.4：两种此前会出事的情形，现在一律返回 0（证据不足）</b>
     * <ol>
     *   <li>桌面原本一个文件夹都没有。旧版直接默认 1；若这台机器按 2×2 大文件夹渲染，
     *       写进去的相邻坐标会让方块互相压住（「有些小米文件夹堆在一起」就是这么来的）。
     *       现在交给 {@link PlanConfig#effectiveFolderSpan(int)} 保守按 2 处理。</li>
     *   <li>整批文件夹是<b>我们上次写回的产物</b>：内置组名 + 严格 1 格密排。旧版读到
     *       自己写的相邻坐标又判成 1，一次猜错就永久固化。现在这类布局不作为证据，
     *       改由「上次写回校验值」定夺。</li>
     * </ol>
     *
     * @return 1 / 2，或 0 表示没有可信样本
     */
    static int detectFolderSpan(List<Folder> folders) {
        int big = 0;
        int small = 0;
        int topLevel = 0;
        boolean allBuiltin = true;
        Map<String, List<Integer>> byRow = new LinkedHashMap<>();
        for (Folder f : folders) {
            if (f.container >= 0) {
                continue;
            }
            topLevel++;
            if (!AppClassifier.isBuiltinGroup(f.title)) {
                allBuiltin = false;
            }
            // 0.16.6：先认 itemType。HyperOS 4 用 2=小文件夹、21=2×2 大文件夹，
            // 尺寸写在这里而不是 span 上；只按 span 判会把「小文件夹」当成坐标异常。
            if (f.itemType == TYPE_FOLDER) {
                big++;
            } else if (f.itemType == TYPE_FOLDER_SMALL) {
                small++;
            } else if (Math.max(f.spanX, f.spanY) >= 2) {
                big++;
            } else {
                small++;
            }
            String key = f.screen + "/" + f.cellY;
            List<Integer> xs = byRow.get(key);
            if (xs == null) {
                xs = new ArrayList<>();
                byRow.put(key, xs);
            }
            xs.add(f.cellX);
        }
        if (topLevel == 0 || looksSynthetic(folders, allBuiltin, byRow)) {
            return 0;
        }
        // 只有一个小文件夹时，既没有坐标间距也没有 span 证据，别急着判 1 格网格
        if (topLevel < 2) {
            return big > small ? 2 : 0;
        }
        for (List<Integer> xs : byRow.values()) {
            if (xs.size() > 2) {
                return 1;
            }
            if (xs.size() < 2) {
                continue;
            }
            java.util.Collections.sort(xs);
            if (xs.get(1) - xs.get(0) == 1) {
                return 1;
            }
        }
        return big > small ? 2 : 1;
    }

    /**
     * 是不是「上次按 1 格步长从左上角密排写出来的」布局。
     *
     * <p>三个条件同时成立才认定：整批都是 Deskly 内置组名、每个文件夹都写成 1×1、
     * 且同屏同行出现相邻 cellX。用户手工摆的文件夹极少同时满足这三条；而按 2×2
     * 写回的布局因为 span=2 也不会命中，所以小米 0.14.1 的既有探测不受影响。
     */
    private static boolean looksSynthetic(List<Folder> folders, boolean allBuiltin,
                                          Map<String, List<Integer>> byRow) {
        if (!allBuiltin || folders.isEmpty()) {
            return false;
        }
        boolean adjacent = false;
        for (List<Integer> xs : byRow.values()) {
            if (xs.size() < 2) {
                continue;
            }
            java.util.Collections.sort(xs);
            for (int i = 1; i < xs.size(); i++) {
                if (xs.get(i) - xs.get(i - 1) == 1) {
                    adjacent = true;
                }
            }
        }
        if (!adjacent) {
            return false;
        }
        for (Folder f : folders) {
            if (f.container >= 0) {
                continue;
            }
            if (Math.max(f.spanX, f.spanY) >= 2) {
                return false;
            }
        }
        return true;
    }

    /** 读的过程中用于暂存"成员先到、文件夹后到"的情况。 */
    private final List<Item> pendingMembers = new ArrayList<>();

    /**
     * 从 Intent 串里抠出组件名与包名。
     *
     * <p>不用 {@code Intent.parseUri}：库里的串是 Launcher 自己序列化的 {@code #Intent;...;end}
     * 形式，且部分记录含自定义 extra，parseUri 遇到不认识的 extra 会抛异常。正则更耐操。
     *
     * <p><b>0.16.0：包名不只从 {@code component=} 取。</b>真我 / ColorOS 的布局库里
     * 出现过 {@code ComponentInfo{pkg/cls}}、{@code package=} / {@code S.package=} extra、
     * 甚至直接一个 {@code pkg/cls} 裸串。旧写法只认第一种，取不到 pkg 时分类只能看到
     * 一个空包名，整批预装图标就都落进「其它」。
     */
    private static final java.util.regex.Pattern P_COMPONENT =
            java.util.regex.Pattern.compile("(?:^|;)(?:[A-Za-z]\\.)?component=([^;]+)");
    private static final java.util.regex.Pattern P_PACKAGE =
            java.util.regex.Pattern.compile("(?:^|;)(?:[A-Za-z]\\.)?package=([^;]+)");
    private static final java.util.regex.Pattern P_COMPONENT_INFO =
            java.util.regex.Pattern.compile("ComponentInfo\\{([^}/]+)/");
    private static final java.util.regex.Pattern P_COMPONENT_INFO_FULL =
            java.util.regex.Pattern.compile("ComponentInfo\\{([^}]+)\\}");

    /** 从任意一种 intent 写法里取出包名；取不到返回空串。 */
    public static String packageOf(String intent) {
        if (intent == null || intent.isEmpty()) {
            return "";
        }
        String[] candidates = {
                first(P_COMPONENT, intent),
                first(P_COMPONENT_INFO, intent),
                first(P_PACKAGE, intent),
                intent,
        };
        for (String c : candidates) {
            String p = packagePart(c);
            if (!p.isEmpty()) {
                return p;
            }
        }
        return "";
    }

    private static String first(java.util.regex.Pattern p, String s) {
        java.util.regex.Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : "";
    }

    /** 取 {@code pkg/cls} 的 pkg 段，并校验它长得像包名，免得把 {@code end} 之类当包名。 */
    private static String packagePart(String comp) {
        if (comp == null || comp.isEmpty()) {
            return "";
        }
        String s = comp.trim();
        int brace = s.indexOf('{');
        if (brace >= 0) {
            s = s.substring(brace + 1);
        }
        int slash = s.indexOf('/');
        String p = (slash > 0 ? s.substring(0, slash) : s).trim();
        return p.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+") ? p : "";
    }

    private static void parseComponent(Item it, String intent) {
        if (intent == null || intent.isEmpty()) {
            return;
        }
        String comp = first(P_COMPONENT, intent);
        if (comp.isEmpty()) {
            comp = first(P_COMPONENT_INFO_FULL, intent);
        }
        it.component = comp;
        it.pkg = packageOf(intent);
    }

    /**
     * 把 {@code com.miui.home:string/default_folder_title_tools} 解成「工具」。
     *
     * <p>小米自动生成的文件夹（工具、游戏）标题存的是资源引用而不是字面量，
     * 直接显示会是 {@code com.miui.home:string/app_category_games} 这种鬼东西，
     * 归位判断时关键词匹配也会全部失效。取不到资源就退回最后一段名字，至少可读。
     */
    static String resolveTitle(Context ctx, String launcherPkg, String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        int colon = raw.indexOf(':');
        if (colon <= 0 || !raw.contains("/")) {
            return raw;
        }
        String pkg = raw.substring(0, colon);
        String rest = raw.substring(colon + 1);
        int slash = rest.indexOf('/');
        String type = slash > 0 ? rest.substring(0, slash) : "string";
        String name = slash > 0 ? rest.substring(slash + 1) : rest;
        try {
            Resources r = ctx.getPackageManager().getResourcesForApplication(pkg);
            int id = r.getIdentifier(name, type, pkg);
            if (id != 0) {
                return r.getString(id);
            }
        } catch (Throwable ignored) {
        }
        // 退化：去掉 app_category_ / default_folder_title_ 这类前缀，把下划线换成空格
        String fallback = name.replaceFirst("^(app_category_|default_folder_title_|folder_title_)", "")
                .replace('_', ' ');
        return fallback.isEmpty() ? raw : fallback;
    }
}
