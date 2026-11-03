package com.coby.deskly.backup;

import org.json.JSONObject;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 一次布局快照的元数据。实际数据库文件放在同 id 的目录里，meta.json 与它并列。
 */
public final class Snapshot {

    /** 首次运行时自动拍的基线，用户说的"最开始的方案"就是它。不可删、不可覆盖。 */
    public static final String LABEL_ORIGIN = "origin";
    /** 手动创建。 */
    public static final String LABEL_MANUAL = "manual";
    /** 恢复动作之前自动拍的，防止"恢复错了想再回去"。 */
    public static final String LABEL_PRE_RESTORE = "pre-restore";
    /** 写入布局改动之前自动拍的，也就是"这次整理之前的样子"。 */
    public static final String LABEL_PRE_APPLY = "pre-apply";

    public String id;
    public String label = LABEL_MANUAL;
    public long createdAt;
    public long size = -1;
    public String sha256 = "";
    public int items = -1;
    /** 这份快照里有多少个应用图标（itemType 0/14）。-1 表示旧快照没记过，得现读。 */
    public int apps = -1;
    public String integrity = "";
    public String dbPath = "";
    public String owner = "";
    public String mode = "";
    public String context = "";
    /** 备份时主库有未 checkpoint 的 WAL，说明这是从热库上直接取的。 */
    public boolean walPresent;

    public boolean isOrigin() {
        return LABEL_ORIGIN.equals(label);
    }

    public String labelText() {
        switch (label) {
            case LABEL_ORIGIN:
                return "初始方案";
            case LABEL_PRE_RESTORE:
                return "恢复前自动";
            case LABEL_PRE_APPLY:
                return "整理前自动";
            default:
                return "手动";
        }
    }

    public String timeText() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(createdAt));
    }

    public String sizeText() {
        return LauncherDb.human(size);
    }

    public String itemText() {
        // 应用图标数是用户真正关心的粒度：同样是「12 项」，可能是 12 个文件夹，
        // 也可能是 12 个散落图标，说清楚才不会让人以为备份只存了文件夹。
        if (apps >= 0) {
            return apps + " 个图标";
        }
        return items >= 0 ? (items + " 项") : "条目未知";
    }

    /** 一行摘要，列表里直接显示。 */
    public String summary() {
        return labelText() + " · " + timeText() + "\n"
                + sizeText() + " · " + itemText()
                + (sha256.isEmpty() ? "" : " · " + sha256.substring(0, Math.min(10, sha256.length())));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("label", label);
            o.put("createdAt", createdAt);
            o.put("size", size);
            o.put("sha256", sha256);
            o.put("items", items);
            o.put("apps", apps);
            o.put("integrity", integrity);
            o.put("dbPath", dbPath);
            o.put("owner", owner);
            o.put("mode", mode);
            o.put("context", context);
            o.put("walPresent", walPresent);
        } catch (Throwable ignored) {
        }
        return o;
    }

    public static Snapshot fromJson(JSONObject o) {
        Snapshot s = new Snapshot();
        s.id = o.optString("id", "");
        s.label = o.optString("label", LABEL_MANUAL);
        s.createdAt = o.optLong("createdAt", 0);
        s.size = o.optLong("size", -1);
        s.sha256 = o.optString("sha256", "");
        s.items = o.optInt("items", -1);
        s.apps = o.optInt("apps", -1);
        s.integrity = o.optString("integrity", "");
        s.dbPath = o.optString("dbPath", "");
        s.owner = o.optString("owner", "");
        s.mode = o.optString("mode", "");
        s.context = o.optString("context", "");
        s.walPresent = o.optBoolean("walPresent", false);
        return s;
    }

    // ── 文件布局 ──

    public File dir(File root) {
        return new File(root, id);
    }

    public File dbFile(File root) {
        return new File(dir(root), "launcher.db");
    }

    public File metaFile(File root) {
        return new File(dir(root), "meta.json");
    }
}
