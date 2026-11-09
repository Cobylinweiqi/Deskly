package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.cobylinweiqi.deskly.organize.AppClassifier.Scheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整理方案的配置：选哪个预设 + 第一屏是否留空 + 每个分组钉在第几屏 + 锁定哪几屏。
 *
 * <p>落到 SharedPreferences 而不是只留内存字段：这些是用户对"我的桌面长什么样"的决定，
 * 关掉 App 就丢会很别扭。以后写入桌面时，这个文件就是执行依据。
 *
 * <p>钉屏的键就是分组名（「金融」/「红」），两套方案的分组名天然不重名，
 * 所以切换方案不会串味，也不需要按方案分桶存。
 *
 * <p><b>锁定屏是 0.14 加的。</b>语义是"这几屏我自己的，别动"：锁定屏内的应用、
 * 文件夹、小组件全部原地保留，整理时只重排其余屏。屏号会按锁定顺序压缩——
 * 锁定 1、3 屏时，原第 3 屏会变成新第 2 屏。详见 {@link DeskPlan}。
 *
 * <p><b>文件夹大小是 0.16.4 加的。</b>桌面把文件夹当 1 格小图标还是 2×2 大文件夹，
 * 取决于桌面版本与「大文件夹」开关，光靠读库猜不准——猜成 1 而桌面按 2×2 渲染时，
 * 相邻文件夹会互相压住（酷安网友那台就是这样）。所以给一个可手动覆盖的三态开关，
 * 并把「上次写回后桌面真实怎么排」记下来，供自动模式优先采用，避免一次猜错就永久固化。
 */
public final class PlanConfig {

    private static final String STORE = "deskly_plan";
    private static final String K_SCHEME = "preset";
    private static final String K_FIRST_BLANK = "first_screen_blank";
    private static final String K_PINS = "screen_pins";
    private static final String K_LOCKED = "locked_screens";
    private static final String K_KEEP_WIDGETS = "keep_orphan_widgets";
    private static final String K_SPAN_MODE = "folder_span_mode";
    private static final String K_SPAN_VERIFIED = "folder_span_verified";

    /** 自动：按桌面库证据 + 上次写回校验值判断。 */
    public static final int SPAN_AUTO = 0;
    /** 手动指定：小文件夹，占 1×1 格。 */
    public static final int SPAN_SMALL = 1;
    /** 手动指定：大文件夹，占 2×2 格。 */
    public static final int SPAN_BIG = 2;

    /** 默认方案一（按颜色），与界面上的默认选中项一致。 */
    public Scheme scheme = Scheme.COLOR;

    /** 第一屏留空，所有文件夹从第二屏开始。与锁定第 1 屏互斥，锁定时自动忽略。 */
    public boolean firstScreenBlank;

    /** 分组名 → 屏号（从 1 起）。没有条目表示"按顺序自动排"。 */
    public final Map<String, Integer> pins = new LinkedHashMap<>();

    /** 锁定的屏号（原屏号，从 1 起）。这些屏的内容不参与整理，屏号按顺序压缩。 */
    public final Set<Integer> lockedScreens = new LinkedHashSet<>();

    /**
     * 未锁定屏上的小组件（非应用图标）整理后是否保留。
     * true=移到新布局的最后一屏（默认，绝不丢东西）；false=跟随该屏一起清空。
     */
    public boolean keepOrphanWidgets = true;

    /** 文件夹大小开关，取值 {@link #SPAN_AUTO} / {@link #SPAN_SMALL} / {@link #SPAN_BIG}。 */
    public int folderSpanMode = SPAN_AUTO;

    /**
     * 上次写回后从真库坐标反推出来的桌面真实占位（1 或 2），0 表示还没有可靠记录。
     * 只在自动模式下采用；用户手动指定时仍以手动为准，但记录会一直保留，
     * 这样切回「自动」时能直接用实测值，不用再猜一次。
     */
    public int verifiedFolderSpan;

    public static PlanConfig load(Context ctx) {
        PlanConfig c = new PlanConfig();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
            String name = sp.getString(K_SCHEME, Scheme.COLOR.name());
            c.scheme = Scheme.FUNCTION.name().equals(name) ? Scheme.FUNCTION : Scheme.COLOR;
            c.firstScreenBlank = sp.getBoolean(K_FIRST_BLANK, false);
            c.parsePins(sp.getString(K_PINS, ""));
            c.parseLocked(sp.getString(K_LOCKED, ""));
            c.keepOrphanWidgets = sp.getBoolean(K_KEEP_WIDGETS, true);
            int mode = sp.getInt(K_SPAN_MODE, SPAN_AUTO);
            c.folderSpanMode = mode == SPAN_SMALL || mode == SPAN_BIG ? mode : SPAN_AUTO;
            int verified = sp.getInt(K_SPAN_VERIFIED, 0);
            c.verifiedFolderSpan = verified == 1 || verified == 2 ? verified : 0;
        } catch (Throwable ignored) {
            // 读配置失败不该拦着用户用 App，退回默认值就行
        }
        return c;
    }

    public void save(Context ctx) {
        try {
            ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                    .putString(K_SCHEME, scheme.name())
                    .putBoolean(K_FIRST_BLANK, firstScreenBlank)
                    .putString(K_PINS, formatPins())
                    .putString(K_LOCKED, formatLocked())
                    .putBoolean(K_KEEP_WIDGETS, keepOrphanWidgets)
                    .putInt(K_SPAN_MODE, folderSpanMode)
                    .putInt(K_SPAN_VERIFIED, verifiedFolderSpan)
                    .apply();
        } catch (Throwable ignored) {
        }
    }

    // ───────────────────────── 文件夹大小 ─────────────────────────

    /**
     * 本次实际生效的文件夹占位。优先级：手动指定 &gt; 上次写回校验 &gt; 读库探测 &gt; 保守默认。
     *
     * <p><b>证据不足时默认 2 而不是 1</b>，这是刻意选的：按 2 格排而桌面其实是小文件夹，
     * 结果只是图标稀疏、多分几屏，不美观但不坏；按 1 格排而桌面是 2×2 大文件夹，
     * 结果就是相邻方块互相压住，用户得手工拆开。0.16.2 默认 1 正是「有些小米文件夹堆在一起」
     * 的根源——那台机器桌面上原本一个文件夹都没有，探测拿不到样本。
     *
     * @param detected 读库坐标探测结果，1 / 2，0 表示没有可信样本
     */
    public int effectiveFolderSpan(int detected) {
        if (folderSpanMode == SPAN_SMALL) {
            return 1;
        }
        if (folderSpanMode == SPAN_BIG) {
            return 2;
        }
        if (verifiedFolderSpan == 1 || verifiedFolderSpan == 2) {
            return verifiedFolderSpan;
        }
        if (detected == 1 || detected == 2) {
            return detected;
        }
        return 2;
    }

    /** 生效值的来源标签，进状态行与说明文字，方便一眼看出为什么是这个值。 */
    public String spanSource(int detected) {
        if (folderSpanMode == SPAN_SMALL || folderSpanMode == SPAN_BIG) {
            return "手动指定";
        }
        if (verifiedFolderSpan == 1 || verifiedFolderSpan == 2) {
            return "上次写回校验";
        }
        if (detected == 1 || detected == 2) {
            return "坐标探测";
        }
        return "无样本·保守默认";
    }

    /** 只收 1 / 2，别让一次异常回读把缓存污染成别的值。 */
    public void recordVerifiedSpan(int span) {
        if (span == 1 || span == 2) {
            verifiedFolderSpan = span;
        }
    }

    /** 该分组被钉在第几屏，0 表示没钉。 */
    public int pinOf(String group) {
        Integer v = pins.get(group);
        return v == null ? 0 : v;
    }

    /** screen &lt;= 0 表示解除钉屏，交回自动排布。 */
    public void setPin(String group, int screen) {
        if (group == null) {
            return;
        }
        if (screen <= 0) {
            pins.remove(group);
        } else {
            pins.put(group, screen);
        }
    }

    public void clearPins() {
        pins.clear();
    }

    // ───────────────────────── 锁定屏 ─────────────────────────

    public boolean isLocked(int screen) {
        return lockedScreens.contains(screen);
    }

    public void setLocked(int screen, boolean locked) {
        if (screen < 1) {
            return;
        }
        if (locked) {
            lockedScreens.add(screen);
        } else {
            lockedScreens.remove(screen);
        }
    }

    public void toggleLock(int screen) {
        setLocked(screen, !isLocked(screen));
    }

    public int lockedCount() {
        return lockedScreens.size();
    }

    public void clearLocks() {
        lockedScreens.clear();
    }

    /**
     * 桌面屏数变了（整理后变少、或换了一份桌面数据）时，把越界的锁定记录清掉。
     * 不清的话，锁定 5 屏的配置会一直留着，下次整理时把不存在的屏当成锁定屏，
     * 新文件夹的起始屏就会被无端推后。
     */
    public void retainLockedUpTo(int maxScreen) {
        lockedScreens.removeIf(n -> n == null || n < 1 || n > maxScreen);
    }

    /** 锁定屏按升序返回，屏号压缩、预览都按这个顺序。 */
    public List<Integer> sortedLocks() {
        List<Integer> out = new ArrayList<>(lockedScreens);
        Collections.sort(out);
        return out;
    }

    // ───────────────────────── 序列化 ─────────────────────────
    // 用 "名字=屏号" / "1,3" 按行拼，不用 JSON：只有一张十来条的短表，引一个解析器不划算。
    // 分组名里不会有 '=' 和换行，所以不需要转义。

    private String formatPins() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : pins.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private void parsePins(String raw) {
        pins.clear();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String line : raw.split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0 || eq >= line.length() - 1) {
                continue;
            }
            try {
                int n = Integer.parseInt(line.substring(eq + 1).trim());
                if (n > 0) {
                    pins.put(line.substring(0, eq), n);
                }
            } catch (NumberFormatException ignored) {
                // 单条坏了就跳过，不让整份配置失效
            }
        }
    }

    private String formatLocked() {
        StringBuilder sb = new StringBuilder();
        for (int n : sortedLocks()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(n);
        }
        return sb.toString();
    }

    private void parseLocked(String raw) {
        lockedScreens.clear();
        if (raw == null || raw.isEmpty()) {
            return;
        }
        for (String part : raw.split("[,\\s]+")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int n = Integer.parseInt(part.trim());
                if (n > 0) {
                    lockedScreens.add(n);
                }
            } catch (NumberFormatException ignored) {
                // 同 pins：坏一条不影响其余
            }
        }
    }
}
