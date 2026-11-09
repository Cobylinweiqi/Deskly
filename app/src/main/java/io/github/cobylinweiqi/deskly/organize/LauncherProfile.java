package io.github.cobylinweiqi.deskly.organize;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 桌面数据库的「方言」：同一张布局表，不同 ROM 对表名、itemType、屏号起点、
 * 屏表结构的约定并不一样。
 *
 * <p><b>为什么不写死一套枚举。</b>小米 / HyperOS 把文件夹记作 21、快捷方式记作 14、
 * 组件记作 19，第一屏是 1；realme UI / ColorOS 这一支来自 AOSP Launcher3，
 * 文件夹是 2、快捷方式是 1、组件是 4，第一屏可能是 0。拿小米的常量去读真我的库，
 * 文件夹会被当成普通条目、成员被退回桌面；拿去写库更糟——会往表里插出
 * 桌面根本不认识的文件夹类型。
 *
 * <p><b>0.15.4：枚举也由库自己说了算。</b>真机上出现过「屏表改动生效、图标能换屏，
 * 但一个分类文件夹都没出现」——按 {@code {0,1,6}} 死认应用图标时，只要厂商用了私有
 * itemType，参与分组的图标就是 0 个；另一方面，手工拼出来的文件夹行若缺了厂商私有列，
 * 桌面加载时会静默丢弃。所以这里改成两条跨 ROM 通用的结构判据：
 *
 * <ul>
 *   <li><b>文件夹</b>＝有别的行把 {@code container} 指向它的 {@code _id}（真的有成员）。
 *       比「itemType 等于 21 还是 2」可靠，也不会被某个孤立的特殊行带偏；</li>
 *   <li><b>应用图标</b>＝{@code intent} 非空、且自己不是文件夹 / 组件。
 *       Launcher 写快捷方式时一定会存 intent，这一条对所有 AOSP 派生桌面都成立。</li>
 * </ul>
 *
 * 探测到的真实 itemType 会直接用于读写。小米库里探测结果恰好是 {@code {0,14} / {21}}，
 * 与 0.14.1 的常量逐字相同，所以小米分支仍是恒等变换。
 *
 * <p><b>0.15.1：连表名也不写死。</b>真机上出现过
 * {@code no such table: favorites}——库文件能打开，但里面根本没有这张表。
 * 探测先看 {@code sqlite_master}，找得到 {@code favorites} 就用它，找不到则找一张
 * 具备布局表核心列（_id / container / screen / itemType / intent）的等价表。
 */
public final class LauncherProfile {

    public enum Flavor {
        /** 小米 / HyperOS / MIUI。 */
        MIUI,
        /** AOSP Launcher3 及其派生：realme UI、ColorOS、OxygenOS、Pixel。 */
        LAUNCHER3
    }

    public final Flavor flavor;
    private final int[] appTypes;
    private final int[] folderTypes;
    /** 文件里的第一屏编号：MIUI 是 1，Launcher3 常见 0。 */
    public final int screenBase;
    /** 屏表名：{@code screens} / {@code workspaceScreens}；空串表示库里没有屏表。 */
    public final String screensTable;
    /** 屏表里的排序列：{@code screenOrder} / {@code screenRank}；空串表示没有。 */
    public final String screensRankColumn;
    /**
     * 屏表里按排序列升序取出的 {@code _id} 列表。
     *
     * <p>0.16.3：Launcher3 系（真我 / ColorOS）的 {@code favorites.screen} 存的是
     * <b>屏表的 _id</b>，不是「第几屏」这个序号。厂商改过表名、_id 不连续或起点不是 0 时，
     * 靠 {@code screenBase} 做加减法算出来的屏号桌面一律不认，表现就是「方案说 2 屏、
     * 桌面上全挤在 1 屏」。这里直接拿桌面自己登记的 id 来映射，空数组表示没探到屏表。
     */
    private final int[] screenIds;
    /** 文件夹成员是否用小米的「cellX=序号、cellY=序号/4」编码。 */
    public final boolean miuiFolderCells;
    /** 实际承载桌面图标的表名。空串＝这份库不是桌面布局库。 */
    public final String table;
    /** 一行可直接抄给开发者的诊断，含 itemType 直方图。 */
    private final String diagnostics;

    private LauncherProfile(Flavor flavor, int[] appTypes, int[] folderTypes,
                            int screenBase, String screensTable, String screensRankColumn,
                            int[] screenIds, String table, String diagnostics) {
        this.flavor = flavor;
        this.appTypes = appTypes;
        this.folderTypes = folderTypes;
        this.screenBase = screenBase;
        this.screensTable = screensTable;
        this.screensRankColumn = screensRankColumn;
        this.screenIds = screenIds == null ? new int[0] : screenIds;
        this.miuiFolderCells = flavor == Flavor.MIUI;
        this.table = table;
        this.diagnostics = diagnostics;
    }

    /** 这份库里是否真的找到了可用的布局表。 */
    public boolean hasLayoutTable() {
        return table != null && !table.isEmpty();
    }

    /** 供拼 SQL 用；没探到表时仍返回 favorites，让调用方拿到明确的 SQLite 报错。 */
    public String sqlTable() {
        return hasLayoutTable() ? table : "favorites";
    }

    public boolean isApp(int itemType) {
        for (int t : appTypes) {
            if (t == itemType) {
                return true;
            }
        }
        return false;
    }

    public boolean isFolder(int itemType) {
        for (int t : folderTypes) {
            if (t == itemType) {
                return true;
            }
        }
        return false;
    }

    /** 新建文件夹时写进 itemType 的值：优先用这台桌面库里真实存在的文件夹枚举。 */
    public int folderType() {
        return folderTypes[0];
    }

    /**
     * 0.16.6：按要写的尺寸给出正确的文件夹 itemType。
     *
     * <p><b>真机证据。</b>MiuiHome RELEASE-8.01.02.8602（HyperOS 4）的日志里同时出现过：
     * <pre>
     * type=userFolder(2)      (0,0,1-1):工具      ← 1×1 小文件夹
     * type=folder_2X2_4(21)   (0,2,2-2):购物      ← 2×2 四宫格大文件夹
     * </pre>
     * 同一个桌面按 itemType 决定画多大，{@code spanX/spanY} 只是跟着写。
     * 旧版写库永远用 {@code folderType()}（从旧库探测到的 21），于是界面上选了
     * 「小 1×1」也只是把 span 写成 1，桌面照旧画成 2×2 —— 1 格步长的坐标立刻叠在一起。
     *
     * <p>Launcher3 系（真我 / ColorOS）里 2 就是唯一的文件夹枚举，尺寸仍由 span 决定。
     */
    public int folderTypeFor(int span) {
        if (isMiui()) {
            return span >= 2 ? DesktopModel.TYPE_FOLDER : DesktopModel.TYPE_FOLDER_SMALL;
        }
        return folderTypes.length > 0 ? folderTypes[0] : DesktopModel.TYPE_FOLDER_SMALL;
    }

    /** 拼 SQL 的 IN 列表，如 {@code 0,14} / {@code 0,1,6}。 */
    public String appTypesIn() {
        return join(appTypes);
    }

    /** 拼 SQL 的 IN 列表，如 {@code 21} / {@code 2}。 */
    public String folderTypesIn() {
        return join(folderTypes);
    }

    /**
     * 把内部统一的 1 起屏号换成库里真实的屏号。MIUI 下是恒等（0.14.1 既有约定）。
     *
     * <p>非小米且探到屏表时，直接返回该屏在屏表里的 {@code _id}；超出已有屏数的新屏
     * 按「最大 id 之后顺延」生成，{@code DeskWriter.syncScreens} 会把这些 id 补进屏表。
     */
    public int toDbScreen(int oneBased) {
        if (isMiui() || screenIds.length == 0) {
            return oneBased - 1 + screenBase;
        }
        if (oneBased >= 1 && oneBased <= screenIds.length) {
            return screenIds[oneBased - 1];
        }
        return screenIds[screenIds.length - 1] + (oneBased - screenIds.length);
    }

    /** 把库里的屏号换回内部统一的 1 起屏号。MIUI 下是恒等。 */
    public int fromDbScreen(int raw) {
        if (isMiui() || screenIds.length == 0) {
            return raw - screenBase + 1;
        }
        for (int i = 0; i < screenIds.length; i++) {
            if (screenIds[i] == raw) {
                return i + 1;
            }
        }
        return raw - screenBase + 1;
    }

    /** 屏表里现存的 id（按排序列升序），供写入层补屏与诊断使用。 */
    public int[] screenIds() {
        return screenIds.clone();
    }

    public boolean isMiui() {
        return flavor == Flavor.MIUI;
    }

    /** 给界面用的一行说明，别让用户对着"未识别"猜。 */
    public String label() {
        return isMiui() ? "小米 / HyperOS" : "Launcher3 系（真我 / ColorOS / OPPO）";
    }

    /**
     * 可直接复制给开发者的诊断。真机排查「写了但没生效」时，这一行比截图有用：
     * 它同时给出布局表名、方言、屏表、首屏编号、每个 itemType 的行数与 intent 情况。
     */
    public String diagnostics() {
        return diagnostics;
    }

    // ───────────────────────── 探测 ─────────────────────────

    /** 一行布局记录的精简投影，避免反复查库。 */
    private static final class Row {
        int id;
        int itemType;
        int container;
        boolean hasIntent;
    }

    /**
     * 从数据库副本里识别方言。所有查询都只读；任何异常都退回"按包名猜"，
     * 绝不让探测本身把一次整理搞崩。
     */
    public static LauncherProfile detect(SQLiteDatabase db, String pkg) {
        String table = detectTable(db);
        String src = table.isEmpty() ? "favorites" : table;

        List<Row> rows = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT _id,itemType,intent,container FROM " + src, null)) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.id = c.getInt(0);
                r.itemType = c.getInt(1);
                r.hasIntent = !c.isNull(2) && !c.getString(2).isEmpty();
                r.container = c.getInt(3);
                rows.add(r);
            }
        } catch (Throwable ignored) {
            // 没有布局表 / 列名不同：退回包名
        }

        // 被别的行当作 container 的 _id = 真正装了东西的文件夹。这是跨 ROM 的结构事实，
        // 比任何 itemType 常量都可靠：文件夹可以不叫 2、也不叫 21，但一定有成员指向它。
        Set<Integer> parentIds = new HashSet<>();
        for (Row r : rows) {
            if (r.container > 0) {
                parentIds.add(r.container);
            }
        }
        Map<Integer, Integer> allTypes = new TreeMap<>();
        Map<Integer, Integer> intentTypes = new TreeMap<>();
        Map<Integer, Integer> realFolderTypes = new TreeMap<>();
        for (Row r : rows) {
            bump(allTypes, r.itemType);
            if (r.hasIntent) {
                bump(intentTypes, r.itemType);
            }
            if (parentIds.contains(r.id)) {
                bump(realFolderTypes, r.itemType);
            }
        }

        Flavor pkgFlavor = byPackage(pkg);
        Flavor flavor;
        if (realFolderTypes.containsKey(21)) {
            flavor = Flavor.MIUI;
        } else if (realFolderTypes.containsKey(2)) {
            // 0.16.6：小米桌面把文件夹全切成小文件夹（2）之后，库里就只剩 2 了。
            // 此时不能照旧判成 Launcher3 —— 那会把屏号起点、成员编码一并切错，
            // 下一次整理直接写到另一个方言上。包名认得是小米就仍按小米处理。
            flavor = pkgFlavor == Flavor.MIUI ? Flavor.MIUI : Flavor.LAUNCHER3;
        } else {
            // 库里一个文件夹都没有（新桌面 / 用户全删了）：不再拿"整表出现过 21"
            // 去猜——厂商的特殊条目很容易撞上这个数字。包名兜底反而更准：
            // com.miui.home→MIUI，com.android.launcher / oppo / coloros→LAUNCHER3。
            flavor = pkgFlavor;
        }

        // 小米的 1 起屏号是 0.14.1 真机验证过的既有约定，这里固定不探测，
        // 免得某台机器恰好有个空屏就把小米的布局整体平移一格。
        ScreenInfo si = detectScreens(db, src);
        int base = flavor == Flavor.MIUI ? 1 : detectScreenBase(db, src, si);

        int[] folders;
        if (flavor == Flavor.MIUI) {
            // 两种尺寸都是文件夹：读库、删旧、复用都要同时认 2 与 21。
            folders = new int[]{DesktopModel.TYPE_FOLDER_SMALL, DesktopModel.TYPE_FOLDER};
        } else if (!realFolderTypes.isEmpty()) {
            folders = toArray(realFolderTypes.keySet());
        } else {
            folders = new int[]{2};
        }

        // 应用枚举：标准值打底，再把「有 intent、不是文件夹、不是组件」的私有值并进来。
        // 小米的 19 / 21 行没有 intent，不会被并进来，结果仍是 {0,14}——恒等变换。
        Set<Integer> apps = new TreeSet<>();
        if (flavor == Flavor.MIUI) {
            apps.add(DesktopModel.TYPE_APP);
            apps.add(DesktopModel.TYPE_SHORTCUT);
        } else {
            apps.add(0);
            apps.add(1);
            apps.add(6);
        }
        Set<Integer> folderSet = new HashSet<>();
        for (int t : folders) {
            folderSet.add(t);
        }
        for (Row r : rows) {
            if (r.hasIntent && !parentIds.contains(r.id)
                    && !folderSet.contains(r.itemType)
                    && r.itemType != 4 && r.itemType != DesktopModel.TYPE_GROUP) {
                apps.add(r.itemType);
            }
        }
        int[] appArray = toArray(apps);

        String diag = buildDiagnostics(db, table, flavor, si, base, allTypes,
                intentTypes, realFolderTypes, appArray, folders);
        return new LauncherProfile(flavor, appArray, folders, base,
                si.table, si.rank, si.ids, table, diag);
    }

    private static String buildDiagnostics(SQLiteDatabase db, String table, Flavor flavor,
                                           ScreenInfo si, int base,
                                           Map<Integer, Integer> all,
                                           Map<Integer, Integer> intent,
                                           Map<Integer, Integer> folders,
                                           int[] apps, int[] folderTypes) {
        StringBuilder sb = new StringBuilder();
        sb.append("布局表 ").append(table.isEmpty() ? "（未找到）" : table)
                .append(" · ").append(flavor == Flavor.MIUI
                        ? "小米 / HyperOS" : "Launcher3 系（真我 / ColorOS / OPPO）")
                .append(" · 屏表 ").append(si.table.isEmpty()
                        ? "（无）" : si.table + "(" + (si.rank.isEmpty() ? "无排序列" : si.rank) + ")")
                .append(" · 首屏 ").append(base);
        sb.append("\nitemType：");
        for (Map.Entry<Integer, Integer> e : all.entrySet()) {
            int t = e.getKey();
            sb.append(' ').append(t).append('=').append(e.getValue())
                    .append("(有intent ").append(count(intent, t)).append(')');
            if (folders.containsKey(t)) {
                sb.append("[文件夹]");
            }
        }
        sb.append("\n应用枚举 ").append(join(apps))
                .append(" · 文件夹枚举 ").append(join(folderTypes));
        // 0.16.3：真我「只显示 1 屏」的排查只需要这三行，一次回传就能定死，
        // 不必再让朋友导库或反复截图。
        sb.append("\n屏表行 ").append(si.rows.isEmpty() ? "（无）" : si.rows);
        sb.append("\n布局表 screen 分布 ")
                .append(screenDistribution(db, table.isEmpty() ? "favorites" : table));
        sb.append("\n全表 ").append(describe(db));
        return sb.toString();
    }

    /** 桌面上（container<=0、排除热区）每个 screen 值各有多少行。 */
    private static String screenDistribution(SQLiteDatabase db, String table) {
        StringBuilder sb = new StringBuilder();
        try (Cursor c = db.rawQuery("SELECT screen,count(*) FROM " + table
                + " WHERE container<=0 AND container<>-101 GROUP BY screen ORDER BY screen", null)) {
            while (c.moveToNext()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(c.getInt(0)).append('=').append(c.getInt(1));
            }
        } catch (Throwable t) {
            return "（读不到）";
        }
        return sb.length() == 0 ? "（无桌面行）" : sb.toString();
    }

    private static int count(Map<Integer, Integer> m, int key) {
        Integer v = m.get(key);
        return v == null ? 0 : v;
    }

    private static void bump(Map<Integer, Integer> m, int key) {
        m.put(key, count(m, key) + 1);
    }

    private static int[] toArray(Set<Integer> set) {
        int[] out = new int[set.size()];
        int i = 0;
        for (int v : set) {
            out[i++] = v;
        }
        return out;
    }

    private static Flavor byPackage(String pkg) {
        if (pkg == null) {
            return Flavor.MIUI;
        }
        String p = pkg.toLowerCase(Locale.US);
        if (p.contains("miui") || p.contains("xiaomi")) {
            return Flavor.MIUI;
        }
        if (p.contains("oppo") || p.contains("coloros") || p.contains("realme")
                || p.contains("oplus") || p.contains("launcher3") || p.contains("nexus")
                // 真我 realme UI 的「系统桌面」实测包名，不含任何厂商字样，
                // 明确写出来免得日后被误归进小米那一族。
                || p.startsWith("com.android.launcher")) {
            return Flavor.LAUNCHER3;
        }
        // 走到这里说明是没登记的第三方 / 新系统桌面。它更可能沿用 AOSP 的
        // Launcher3 约定而不是小米私有枚举；一旦库里出现文件夹，上面的结构探测
        // 会直接给出答案，包名兜底只在"桌面还是空的"这一次起作用。
        return Flavor.LAUNCHER3;
    }

    /** 屏表探测结果。ids 一律按排序列升序，ids[0] 就是桌面上的第一屏。 */
    private static final class ScreenInfo {
        final String table;
        final String rank;
        final int[] ids;
        /** 形如 {@code 0/0 1/1}，斜杠后是排序列的值，方便一眼看出 id 与顺序是否对齐。 */
        final String rows;

        ScreenInfo(String table, String rank, int[] ids, String rows) {
            this.table = table;
            this.rank = rank;
            this.ids = ids;
            this.rows = rows;
        }

        static ScreenInfo empty() {
            return new ScreenInfo("", "", new int[0], "");
        }
    }

    /**
     * 首屏编号。0.16.3 之前只查 {@code container=-100}，真我私有库里桌面行的
     * container 不是 -100（状态行会显示「0 个散在桌面上」），查询落在 NULL 上退回 1，
     * 于是新屏被写成桌面不认的 screen=1/2，全部归位回已有的一屏。
     *
     * <p>优先用屏表自己的 id；没有屏表时取「桌面行（非热区、非文件夹成员）」的最小
     * screen；再兜底到 AOSP Launcher3 的 0。
     */
    private static int detectScreenBase(SQLiteDatabase db, String src, ScreenInfo si) {
        if (si.ids.length > 0) {
            return si.ids[0];
        }
        try (Cursor c = db.rawQuery(
                "SELECT min(screen) FROM " + src
                        + " WHERE screen>=0 AND container<=0 AND container<>-101", null)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * 先按固定名认，认不出再<b>按数据反查</b>：布局表用到的每个 screen 值都必须能在
     * 这张小表里找到同值的 {@code _id}——这正是 Launcher3「screen 引用屏表 id」的约束。
     * 厂商把屏表改叫 {@code desk_screens} 之类也不会漏掉，不用为每台机器补一张名单。
     */
    private static ScreenInfo detectScreens(SQLiteDatabase db, String layoutTable) {
        for (String t : new String[]{"screens", "workspaceScreens"}) {
            if (tableExists(db, t)) {
                return readScreenInfo(db, t, pickRank(columns(db, t)));
            }
        }
        Set<Integer> used = usedScreens(db, layoutTable);
        if (used.isEmpty()) {
            return ScreenInfo.empty();
        }
        // 两轮：先只认带明确排序列的小表；再放宽到「行数恰好等于已用屏数」的无排序列
        // 小表。带 intent / itemType / title 的一律跳过，免得把设置表、图标缓存表当屏表。
        for (int pass = 0; pass < 2; pass++) {
            for (String t : tables(db)) {
                if (t.equalsIgnoreCase(layoutTable)) {
                    continue;
                }
                Set<String> cols = columns(db, t);
                if (!cols.contains("_id") || cols.size() > 12
                        || cols.contains("intent") || cols.contains("itemType")
                        || cols.contains("title")) {
                    continue;
                }
                String rank = pickRank(cols);
                if (pass == 0 ? rank.isEmpty() : !rank.isEmpty()) {
                    continue;
                }
                int n = rowCount(db, t);
                if (n <= 0 || n > used.size() + 4 || (pass == 1 && n != used.size())) {
                    continue;
                }
                ScreenInfo si = readScreenInfo(db, t, rank);
                if (si.ids.length == 0) {
                    continue;
                }
                boolean covers = true;
                for (int s : used) {
                    boolean found = false;
                    for (int id : si.ids) {
                        if (id == s) {
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        covers = false;
                        break;
                    }
                }
                if (covers) {
                    return si;
                }
            }
        }
        return ScreenInfo.empty();
    }

    private static ScreenInfo readScreenInfo(SQLiteDatabase db, String t, String rank) {
        String order = rank.isEmpty() ? "_id" : rank;
        StringBuilder rows = new StringBuilder();
        List<Integer> ids = new ArrayList<>();
        try (Cursor c = db.rawQuery("SELECT _id" + (rank.isEmpty() ? "" : "," + rank)
                + " FROM " + t + " ORDER BY " + order + " ASC, _id ASC", null)) {
            while (c.moveToNext()) {
                int id = c.getInt(0);
                ids.add(id);
                if (rows.length() > 0) {
                    rows.append(' ');
                }
                rows.append(id);
                if (!rank.isEmpty()) {
                    rows.append('/').append(c.getInt(1));
                }
            }
        } catch (Throwable ignored) {
            return ScreenInfo.empty();
        }
        int[] a = new int[ids.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = ids.get(i);
        }
        return new ScreenInfo(t, rank, a, rows.toString());
    }

    /** 只认固定候选，避免把任意列名拼进 SQL；私有屏表没有排序列也照样能用。 */
    private static String pickRank(Set<String> cols) {
        String[] candidates = {"screenOrder", "screenRank", "rank", "sortIndex",
                "screenIndex", "position", "orderIndex"};
        for (String k : candidates) {
            if (cols.contains(k)) {
                return k;
            }
        }
        return "";
    }

    /** 布局表里桌面行（排除热区与文件夹成员）用到的 screen 值。 */
    private static Set<Integer> usedScreens(SQLiteDatabase db, String layoutTable) {
        Set<Integer> out = new TreeSet<>();
        try (Cursor c = db.rawQuery("SELECT DISTINCT screen FROM " + layoutTable
                + " WHERE screen>=0 AND container<=0 AND container<>-101", null)) {
            while (c.moveToNext()) {
                out.add(c.getInt(0));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static int rowCount(SQLiteDatabase db, String table) {
        try (Cursor c = db.rawQuery("SELECT count(*) FROM " + table, null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /**
     * 找布局表。先认标准名 {@code favorites}，再按"名字像 + 列齐"找等价表，
     * 最后按"列齐"兜底。表名只允许字母数字下划线，避免把奇怪的标识符拼进 SQL。
     */
    private static String detectTable(SQLiteDatabase db) {
        List<String> tables = tables(db);
        for (String t : tables) {
            if ("favorites".equalsIgnoreCase(t)) {
                return t;
            }
        }
        for (String t : tables) {
            String low = t.toLowerCase(Locale.US);
            if (low.contains("favorite") || low.contains("workspace") || low.contains("item")) {
                if (looksLikeLayout(db, t)) {
                    return t;
                }
            }
        }
        for (String t : tables) {
            if (looksLikeLayout(db, t)) {
                return t;
            }
        }
        return "";
    }

    /** 桌面布局表最核心的几列；少一列都没法做归位。 */
    private static boolean looksLikeLayout(SQLiteDatabase db, String table) {
        Set<String> c = columns(db, table);
        return c.contains("_id") && c.contains("container") && c.contains("screen")
                && c.contains("itemType") && c.contains("intent");
    }

    /** 库里所有用户表（按名字排序），诊断与探测共用。 */
    public static List<String> tables(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        try (Cursor c = db.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
                        + " AND name NOT LIKE 'android_%' ORDER BY name", null)) {
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && n.matches("[A-Za-z0-9_]+")) {
                    out.add(n);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 诊断用：一行列出库里所有表和每表的行数（行数取不到就只给表名）。 */
    public static String describe(SQLiteDatabase db) {
        List<String> ts = tables(db);
        if (ts.isEmpty()) {
            return "（没有任何表，可能是空库或 WAL 未拷贝）";
        }
        StringBuilder sb = new StringBuilder();
        for (String t : ts) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(t);
            try (Cursor c = db.rawQuery("SELECT count(*) FROM " + t, null)) {
                if (c.moveToFirst()) {
                    sb.append('(').append(c.getInt(0)).append(')');
                }
            } catch (Throwable ignored) {
            }
        }
        return sb.toString();
    }

    /** 诊断用：某张表的列名清单，排查「布局表选对了没有」。 */
    public static String describeColumns(SQLiteDatabase db, String table) {
        List<String> cols = new ArrayList<>(columns(db, table));
        java.util.Collections.sort(cols);
        return cols.toString();
    }

    private static boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
                new String[]{name})) {
            return c.moveToFirst();
        } catch (Throwable t) {
            return false;
        }
    }

    public static Set<String> columns(SQLiteDatabase db, String table) {
        Set<String> out = new HashSet<>();
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                out.add(c.getString(1));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String join(int[] values) {
        StringBuilder sb = new StringBuilder();
        for (int v : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    private LauncherProfile() {
        throw new AssertionError();
    }
}
