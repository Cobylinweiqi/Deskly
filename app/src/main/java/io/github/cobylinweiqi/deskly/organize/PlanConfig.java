package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.content.SharedPreferences;

import io.github.cobylinweiqi.deskly.organize.AppClassifier.Scheme;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 整理方案的配置：选哪个预设 + 第一屏是否留空 + 每个分组钉在第几屏。
 *
 * <p>落到 SharedPreferences 而不是只留内存字段：这些是用户对"我的桌面长什么样"的决定，
 * 关掉 App 就丢会很别扭。以后写入桌面时，这个文件就是执行依据。
 *
 * <p>钉屏的键就是分组名（「金融」/「红」），两套方案的分组名天然不重名，
 * 所以切换方案不会串味，也不需要按方案分桶存。
 */
public final class PlanConfig {

    private static final String STORE = "deskly_plan";
    private static final String K_SCHEME = "preset";
    private static final String K_FIRST_BLANK = "first_screen_blank";
    private static final String K_PINS = "screen_pins";

    /** 默认方案一（按颜色），与界面上的默认选中项一致。 */
    public Scheme scheme = Scheme.COLOR;

    /** 第一屏留空，所有文件夹从第二屏开始。 */
    public boolean firstScreenBlank;

    /** 分组名 → 屏号（从 1 起）。没有条目表示"按顺序自动排"。 */
    public final Map<String, Integer> pins = new LinkedHashMap<>();

    public static PlanConfig load(Context ctx) {
        PlanConfig c = new PlanConfig();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(STORE, Context.MODE_PRIVATE);
            String name = sp.getString(K_SCHEME, Scheme.COLOR.name());
            c.scheme = Scheme.FUNCTION.name().equals(name) ? Scheme.FUNCTION : Scheme.COLOR;
            c.firstScreenBlank = sp.getBoolean(K_FIRST_BLANK, false);
            c.parsePins(sp.getString(K_PINS, ""));
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
                    .apply();
        } catch (Throwable ignored) {
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

    // ───────────────────────── 序列化 ─────────────────────────
    // 用 "名字=屏号" 按行拼，不用 JSON：只有一张十来条的短表，引一个解析器不划算。
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
}
