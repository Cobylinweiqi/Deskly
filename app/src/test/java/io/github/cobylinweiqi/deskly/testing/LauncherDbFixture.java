package io.github.cobylinweiqi.deskly.testing;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

/**
 * 合成一份 launcher.db，用来在 JVM 上验证「换一个 ROM 会不会读错/写错」。
 *
 * <p>为什么能在 Mac 上跑：Deskly 与 ROM 的耦合其实只有数据库这一层，不需要真机、
 * 不需要模拟器、更不需要真正装一个 ColorOS。Robolectric 提供真实的
 * {@link SQLiteDatabase}（底层是 native sqlite），所以这里造出来的库与真机同构。
 *
 * <p>三套 profile 故意只在<b>枚举值 / 屏号起点 / 屏表</b>上不同，其余完全一致——
 * 这样任何差异都能定位到"代码把某个 ROM 的约定写死了"，而不是别的原因。
 */
public final class LauncherDbFixture {

    /** 一个 ROM 对 favorites.itemType 的约定，以及屏表的结构。 */
    public static final class Profile {
        public final String name;
        public final int typeApp;
        public final int typeShortcut;
        public final int typeFolder;
        public final int typeWidget;
        /** MIUI 用 screenOrder，原生 Launcher3 用 screenRank。 */
        public final String screensColumn;
        /** favorites.screen 里第一屏的编号：MIUI 是 1，Launcher3 常见 0。 */
        public final int screenBase;
        /** 屏表名：screens / workspaceScreens。 */
        public final String screensTable;
        /** favorites 上是否有「NOT NULL 且无 DEFAULT」的厂商私有列。 */
        public final boolean strictColumns;

        Profile(String name, int typeApp, int typeShortcut, int typeFolder,
                int typeWidget, String screensColumn) {
            this(name, typeApp, typeShortcut, typeFolder, typeWidget,
                    screensColumn, 1, "screens", false);
        }

        Profile(String name, int typeApp, int typeShortcut, int typeFolder,
                int typeWidget, String screensColumn, int screenBase, String screensTable) {
            this(name, typeApp, typeShortcut, typeFolder, typeWidget,
                    screensColumn, screenBase, screensTable, false);
        }

        Profile(String name, int typeApp, int typeShortcut, int typeFolder,
                int typeWidget, String screensColumn, int screenBase, String screensTable,
                boolean strictColumns) {
            this.name = name;
            this.typeApp = typeApp;
            this.typeShortcut = typeShortcut;
            this.typeFolder = typeFolder;
            this.typeWidget = typeWidget;
            this.screensColumn = screensColumn;
            this.screenBase = screenBase;
            this.screensTable = screensTable;
            this.strictColumns = strictColumns;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /** 小米 / HyperOS：文件夹 21、快捷方式 14、组件 19，见 DesktopModel 里的常量。 */
    public static final Profile MIUI =
            new Profile("MIUI", 0, 14, 21, 19, "screenOrder");

    /**
     * 原生 Launcher3 及绝大多数派生桌面（ColorOS / OxygenOS / Pixel）：
     * 文件夹 2、快捷方式 1、组件 4。这几组数字来自 AOSP
     * {@code LauncherProvider.Favorites} 的 ITEM_TYPE_* 常量。
     */
    public static final Profile COLOROS =
            new Profile("ColorOS/Launcher3", 0, 1, 2, 4, "screenRank");

    /**
     * 真我 realme UI：同样是 Launcher3 枚举，但屏号从 <b>0</b> 起、屏表叫
     * {@code workspaceScreens}。这条 profile 专门盯"屏号平移"和"屏表不叫 screens"
     * 两件事——它们不会让解析失败，只会让图标落到隔壁屏，最难在真机上发现。
     */
    public static final Profile REALME =
            new Profile("realme UI", 0, 1, 2, 4, "screenRank", 0, "workspaceScreens");

    /**
     * 模拟厂商改造过的桌面：应用 9、文件夹 5（都不是 AOSP 常量），另外有
     * {@code scene TEXT NOT NULL} / {@code folder_style INTEGER NOT NULL} 两列无默认值。
     * 专测「按枚举死认」和「手工拼字段 insert」这两种会静默失败的做法。
     */
    public static final Profile REALME_STRICT =
            new Profile("realme 私有枚举+必需列", 9, 9, 5, 4,
                    "screenRank", 0, "workspaceScreens", true);

    /** 固定布局：14 个散落应用 + 2 个文件夹（共 5 个成员）+ 1 个 2×2 组件。 */
    public static final int LOOSE_APPS = 14;
    public static final int FOLDER_MEMBERS = 5;
    public static final int FOLDERS = 2;
    public static final int MOVABLE_TOTAL = LOOSE_APPS + FOLDER_MEMBERS;

    private LauncherDbFixture() {
    }

    /**
     * 在 {@code dir} 下生成 {@code launcher.db}，返回文件。
     *
     * <p>屏幕布局（4 列 × 6 行），屏号按 profile 的 screenBase 平移：
     * <pre>
     * 第 1 屏：8 个应用 + 一个 2×2 组件 + 一个 2×2 文件夹（3 个成员）
     * 第 2 屏：4 个应用 + 一个 2×2 文件夹（2 个成员）
     * 第 3 屏：2 个应用
     * 热区  ：4 个应用（container=-101，不该被整理）
     * </pre>
     */
    public static File create(File dir, Profile profile) {
        File f = new File(dir, "launcher.db");
        if (f.exists() && !f.delete()) {
            throw new IllegalStateException("无法删除旧 fixture: " + f);
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null);
        try {
            String extraCols = profile.strictColumns
                    ? ",scene TEXT NOT NULL,folder_style INTEGER NOT NULL" : "";
            db.execSQL("CREATE TABLE favorites ("
                    + "_id INTEGER PRIMARY KEY,"
                    + "title TEXT,"
                    + "intent TEXT,"
                    + "container INTEGER NOT NULL DEFAULT -1,"
                    + "screen INTEGER NOT NULL DEFAULT -1,"
                    + "cellX INTEGER NOT NULL DEFAULT -1,"
                    + "cellY INTEGER NOT NULL DEFAULT -1,"
                    + "spanX INTEGER NOT NULL DEFAULT 1,"
                    + "spanY INTEGER NOT NULL DEFAULT 1,"
                    + "itemType INTEGER NOT NULL DEFAULT 0,"
                    + "appWidgetId INTEGER NOT NULL DEFAULT -1,"
                    + "profileId INTEGER NOT NULL DEFAULT 0,"
                    + "rank INTEGER NOT NULL DEFAULT 0"
                    + extraCols
                    + ")");
            db.execSQL("CREATE TABLE " + profile.screensTable + " ("
                    + "_id INTEGER PRIMARY KEY,"
                    + profile.screensColumn + " INTEGER NOT NULL DEFAULT 0"
                    + ")");

            final int s1 = profile.screenBase;
            final int s2 = s1 + 1;
            final int s3 = s1 + 2;
            int id = 1;
            // 第 1 屏：8 个应用
            int[][] screen1 = {{0, 0}, {1, 0}, {0, 1}, {1, 1}, {0, 2}, {1, 2}, {2, 2}, {3, 2}};
            for (int[] cell : screen1) {
                id = app(db, profile, id, s1, cell[0], cell[1]);
            }
            // 第 1 屏：2×2 组件，压住 (2,0)(3,0)(2,1)(3,1)
            widget(db, profile, id++, s1, 2, 0);

            // 第 1 屏：文件夹 100 + 3 个成员
            int folder1 = id + 100;
            folder(db, profile, folder1, s1, 0, 3);
            for (int i = 0; i < 3; i++) {
                member(db, profile, profile.typeApp, id++, folder1, i, 0);
            }

            // 第 2 屏：4 个应用
            for (int i = 0; i < 4; i++) {
                id = app(db, profile, id, s2, i, 0);
            }
            // 第 2 屏：文件夹 200 + 2 个成员
            int folder2 = id + 100;
            folder(db, profile, folder2, s2, 0, 1);
            for (int i = 0; i < 2; i++) {
                member(db, profile, profile.typeApp, id++, folder2, i, 0);
            }

            // 第 3 屏：2 个应用
            id = app(db, profile, id, s3, 0, 0);
            id = app(db, profile, id, s3, 1, 0);

            // 热区
            for (int i = 0; i < 4; i++) {
                ContentValues v = base(profile, profile.typeApp, id++, -101, i, 0);
                v.put("title", "热区" + (i + 1));
                v.put("intent", intent("hotseat" + (i + 1)));
                db.insert("favorites", null, v);
            }

            // 0.16.3：Launcher3 系 favorites.screen 引用屏表的 _id，两者必须一致。
            // 旧 fixture 把所有 profile 的屏表 _id 都写成 0,1,2，而 COLOROS 的布局行
            // screen 是 1,2,3 —— 数据自相矛盾，按屏表 id 映射屏号后就会被读成 4 屏。
            for (int i = 0; i <= 2; i++) {
                ContentValues v = new ContentValues();
                v.put("_id", profile.screenBase + i);
                v.put(profile.screensColumn, i);
                db.insert(profile.screensTable, null, v);
            }
        } finally {
            db.close();
        }
        return f;
    }

    // ───────────────────────── 行构造 ─────────────────────────

    private static ContentValues base(Profile p, int type, int id, int container, int x, int y) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("container", container);
        v.put("screen", container < 0 ? container : -1);
        v.put("cellX", x);
        v.put("cellY", y);
        v.put("spanX", 1);
        v.put("spanY", 1);
        v.put("itemType", type);
        if (p.strictColumns) {
            v.put("scene", "normal");
            v.put("folder_style", 1);
        }
        return v;
    }

    private static int app(SQLiteDatabase db, Profile p, int id, int screen, int x, int y) {
        ContentValues v = base(p, p.typeApp, id, -100, x, y);
        v.put("screen", screen);
        v.put("title", "App" + id);
        v.put("intent", intent("app" + id));
        db.insert("favorites", null, v);
        return id + 1;
    }

    private static void widget(SQLiteDatabase db, Profile p, int id, int screen, int x, int y) {
        ContentValues v = base(p, p.typeWidget, id, -100, x, y);
        v.put("screen", screen);
        v.put("spanX", 2);
        v.put("spanY", 2);
        v.put("title", "日历");
        v.put("appWidgetId", 1);
        db.insert("favorites", null, v);
    }

    private static void folder(SQLiteDatabase db, Profile p, int id, int screen, int x, int y) {
        ContentValues v = base(p, p.typeFolder, id, -100, x, y);
        v.put("screen", screen);
        v.put("spanX", 2);
        v.put("spanY", 2);
        v.put("title", "文件夹" + id);
        db.insert("favorites", null, v);
    }

    private static void member(SQLiteDatabase db, Profile p, int type, int id, int folderId,
                               int x, int y) {
        ContentValues v = base(p, type, id, folderId, x, y);
        v.put("screen", 0);
        v.put("title", "成员" + id);
        v.put("intent", intent("member" + id));
        db.insert("favorites", null, v);
    }

    private static String intent(String name) {
        return "#Intent;action=android.intent.action.MAIN;"
                + "category=android.intent.category.LAUNCHER;launchFlags=0x10200000;"
                + "component=com.example." + name + "/.MainActivity;end";
    }
}
