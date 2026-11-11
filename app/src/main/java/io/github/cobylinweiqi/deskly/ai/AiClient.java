package io.github.cobylinweiqi.deskly.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * OpenAI 兼容接口（{@code POST …/chat/completions}）的最小客户端。
 *
 * <p>只用 {@link HttpURLConnection} 和平台自带的 org.json：这个 APK 只有一百来 KB，
 * 为了一个 POST 引 OkHttp 不值得。不做流式、不做重试——调用方一批一批发，
 * 哪一批失败就把原因原样报给用户。
 *
 * <p>刻意不发 {@code max_tokens} / {@code response_format}：各家兼容层对这两个字段
 * 的支持最不一致（推理模型要 {@code max_completion_tokens}，不少本地服务不认
 * {@code json_object}），不发反而哪家都能跑；JSON 由 {@link AiClassifier} 宽松解析。
 */
public final class AiClient {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private AiClient() {
    }

    /** 接口报错、返回体不是预期形状等「请求发出去了但没成」的情况。 */
    public static final class AiException extends IOException {
        public AiException(String msg) {
            super(msg);
        }
    }

    /**
     * 把用户填的地址补成完整的 chat/completions 端点。
     *
     * <p>用户可能填 {@code https://api.openai.com/v1}、带尾斜杠、或者直接贴了完整端点；
     * 没写协议的按 https 处理。不替用户猜 {@code /v1}——各家路径不同
     * （有的是 {@code /api/v3}、{@code /compatible-mode/v1}），猜错比不猜更难排查。
     */
    public static String endpoint(String base) {
        String s = base == null ? "" : base.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (!s.startsWith("http://") && !s.startsWith("https://")) {
            s = "https://" + s;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith("/chat/completions")) {
            return s;
        }
        return s + "/chat/completions";
    }

    /** 发一轮对话，返回助手回复的文本。 */
    public static String chat(AiConfig cfg, String system, String user) throws IOException {
        String url = endpoint(cfg.baseUrl);
        if (url.isEmpty()) {
            throw new AiException("没有填写接口地址");
        }
        String body;
        try {
            JSONArray messages = new JSONArray();
            if (system != null && !system.isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", system));
            }
            messages.put(new JSONObject().put("role", "user").put("content", user));
            body = new JSONObject()
                    .put("model", cfg.model.trim())
                    .put("messages", messages)
                    .put("stream", false)
                    .toString();
        } catch (Exception e) {
            throw new AiException("构造请求失败：" + e);
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            String key = cfg.apiKey == null ? "" : cfg.apiKey.trim();
            if (!key.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + key);
            }
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream());
            if (code < 200 || code >= 300) {
                throw new AiException("接口返回 HTTP " + code + "：" + errorMessage(text));
            }
            return content(text);
        } finally {
            conn.disconnect();
        }
    }

    /** 从返回体里取 {@code choices[0].message.content}。 */
    static String content(String responseBody) throws AiException {
        try {
            JSONObject o = new JSONObject(responseBody);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                throw new AiException("返回里没有 choices：" + clip(responseBody));
            }
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) {
                throw new AiException("返回里没有 message：" + clip(responseBody));
            }
            Object c = msg.opt("content");
            if (c instanceof JSONArray) {
                // 少数兼容层把内容拆成 [{type:"text",text:"…"}]
                StringBuilder sb = new StringBuilder();
                JSONArray parts = (JSONArray) c;
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject p = parts.optJSONObject(i);
                    if (p != null) {
                        sb.append(p.optString("text", ""));
                    }
                }
                return sb.toString();
            }
            if (c == null || c == JSONObject.NULL) {
                return "";
            }
            return String.valueOf(c);
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("返回不是预期的 JSON：" + clip(responseBody));
        }
    }

    /** 报错体通常是 {@code {"error":{"message":"…"}}}，取不到就截一段原文。 */
    static String errorMessage(String body) {
        try {
            JSONObject o = new JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof JSONObject) {
                String m = ((JSONObject) err).optString("message", "");
                if (!m.isEmpty()) {
                    return clip(m);
                }
            } else if (err instanceof String && !((String) err).isEmpty()) {
                return clip((String) err);
            }
            String m = o.optString("message", "");
            if (!m.isEmpty()) {
                return clip(m);
            }
        } catch (Exception ignored) {
        }
        return clip(body);
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream is = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
