package io.github.cobylinweiqi.deskly.ai;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.cobylinweiqi.deskly.organize.AppClassifier;

/**
 * AI 辅助分类的本地存储：接口配置 + 上一次 AI 给出的「包名 → 组名」。
 *
 * <p>全部放在应用私有目录的 SharedPreferences 里。API Key 是明文——这台机器有 root，
 * 任何加密都只是把钥匙放在同一个抽屉里，不如直说，由用户决定填不填。
 *
 * <p>分类结果单独落盘是因为它很贵（一次请求几秒到几十秒、还花钱），而应用的类别
 * 基本不会变：跑一次之后，「重新分析」「换方案」「下次打开」都直接复用。
 */
public final class AiConfig {

    private static final String PREFS = "deskly_ai";
    private static final String K_BASE = "base_url";
    private static final String K_KEY = "api_key";
    private static final String K_MODEL = "model";
    private static final String K_ENABLED = "enabled";
    private static final String K_GROUPS = "groups";

    public String baseUrl = "";
    public String apiKey = "";
    public String model = "";
    /** 整理时是否采用 AI 的分类结果。关掉只是不用，结果还留着。 */
    public boolean enabled = true;

    private AiConfig() {
    }

    public static AiConfig load(Context ctx) {
        SharedPreferences sp = prefs(ctx);
        AiConfig c = new AiConfig();
        c.baseUrl = sp.getString(K_BASE, "");
        c.apiKey = sp.getString(K_KEY, "");
        c.model = sp.getString(K_MODEL, "");
        c.enabled = sp.getBoolean(K_ENABLED, true);
        return c;
    }

    public void save(Context ctx) {
        prefs(ctx).edit()
                .putString(K_BASE, baseUrl == null ? "" : baseUrl.trim())
                .putString(K_KEY, apiKey == null ? "" : apiKey.trim())
                .putString(K_MODEL, model == null ? "" : model.trim())
                .putBoolean(K_ENABLED, enabled)
                .apply();
    }

    /** 地址和模型是必填；Key 可以空（本地 Ollama / LM Studio 不需要）。 */
    public boolean ready() {
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }

    // ───────────────────────── 分类结果 ─────────────────────────

    public static Map<String, String> loadGroups(Context ctx) {
        Map<String, String> out = new LinkedHashMap<>();
        String raw = prefs(ctx).getString(K_GROUPS, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        try {
            JSONObject o = new JSONObject(raw);
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                String v = o.optString(k, "");
                if (!v.isEmpty()) {
                    out.put(k, v);
                }
            }
        } catch (Throwable ignored) {
            // 存坏了就当没有，下次 AI 分类会整份覆盖
        }
        return out;
    }

    public static void saveGroups(Context ctx, Map<String, String> groups) {
        JSONObject o = new JSONObject();
        try {
            for (Map.Entry<String, String> e : groups.entrySet()) {
                o.put(e.getKey(), e.getValue());
            }
        } catch (Throwable ignored) {
        }
        prefs(ctx).edit().putString(K_GROUPS, o.toString()).apply();
    }

    public static void clearGroups(Context ctx) {
        prefs(ctx).edit().remove(K_GROUPS).apply();
    }

    /**
     * 把「开关 + 已存结果」同步给分类引擎。开关或结果变了都要调一次；
     * 启动时也要调，否则上次的 AI 结果要等用户再点一次才生效。
     *
     * @return 当前生效的条数（开关关着时是 0）
     */
    public static int apply(Context ctx) {
        AiConfig c = load(ctx);
        Map<String, String> groups = c.enabled
                ? loadGroups(ctx) : Collections.<String, String>emptyMap();
        AppClassifier.setAiOverrides(groups);
        return groups.size();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
