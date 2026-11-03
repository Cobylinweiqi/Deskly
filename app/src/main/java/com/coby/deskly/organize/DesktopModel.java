package com.coby.deskly.organize;

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
 *   <li>{@link Folder}——一级文件夹（container&lt;0 且 itemType=21），自带成员列表；</li>
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

    /** favorites.itemType 的取值，Launcher3 与 MIUI 共用。 */
    public static final int TYPE_APP = 0;
    public static final int TYPE_SHORTCUT = 14;
    public static final int TYPE_GROUP = 19;
    public static final int TYPE_FOLDER = 21;

    /** 小米文件夹打开后是 4 列。用来给新成员算建议行号。 */
    public static final int FOLDER_COLUMNS = 4;

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

        public boolean isFolder() {
            return itemType == TYPE_FOLDER;
        }

        public boolean isApp() {
            return itemType == TYPE_APP || itemType == TYPE_SHORTCUT;
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

    public Folder folderById(int id) {
        for (Folder f : folders) {
            if (f.id == id) {
                return f;
            }
        }
        return null;
    }

    /** 真正"可以归位"的散落图标：桌面上的应用/快捷方式。组件类（itemType=19）不动。 */
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
        Map<Integer, Folder> byId = new LinkedHashMap<>();

        String sql = "SELECT _id,title,intent,container,screen,cellX,cellY,spanX,spanY,itemType"
                + " FROM favorites";
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getInt(0);
                it.rawTitle = c.getString(1) == null ? "" : c.getString(1);
                String intent = c.getString(2);
                it.container = c.getInt(3);
                it.screen = c.getInt(4);
                it.cellX = c.getInt(5);
                it.cellY = c.getInt(6);
                it.spanX = c.isNull(7) ? 1 : c.getInt(7);
                it.spanY = c.isNull(8) ? 1 : c.getInt(8);
                it.itemType = c.getInt(9);
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
        return m;
    }

    /** 读的过程中用于暂存"成员先到、文件夹后到"的情况。 */
    private final List<Item> pendingMembers = new ArrayList<>();

    /**
     * 从 Intent 串里抠出组件名。
     *
     * <p>不用 {@code Intent.parseUri}：库里的串是 Launcher 自己序列化的 {@code #Intent;...;end}
     * 形式，且部分记录含自定义 extra，parseUri 遇到不认识的 extra 会抛异常。正则更耐操。
     */
    private static void parseComponent(Item it, String intent) {
        if (intent == null || intent.isEmpty()) {
            return;
        }
        int i = intent.indexOf("component=");
        if (i < 0) {
            return;
        }
        int end = intent.indexOf(';', i);
        String comp = end > 0 ? intent.substring(i + 10, end) : intent.substring(i + 10);
        it.component = comp;
        int slash = comp.indexOf('/');
        it.pkg = slash > 0 ? comp.substring(0, slash) : comp;
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
