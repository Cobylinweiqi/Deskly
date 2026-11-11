package io.github.cobylinweiqi.deskly.ai;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.cobylinweiqi.deskly.organize.AppClassifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 对着一个本机假 OpenAI 服务跑完整链路：请求长什么样、分批、报错怎么透出来。
 * 不访问任何外部地址。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AiClientHttpTest {

    private ServerSocket server;
    private Thread acceptor;
    private AiConfig cfg;
    private final List<JSONObject> requests = new ArrayList<>();
    private final List<String> auth = new ArrayList<>();
    private final AtomicInteger failFrom = new AtomicInteger(Integer.MAX_VALUE);

    @Before
    public void setUp() throws Exception {
        // 用裸 ServerSocket：Android 的编译类路径里没有 JDK 的 com.sun.net.httpserver
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    serve(s);
                } catch (Exception ignored) {
                    // 关服务时 accept 会抛，正常退出
                }
            }
        }, "fake-openai");
        acceptor.setDaemon(true);
        acceptor.start();
        Context ctx = ApplicationProvider.getApplicationContext();
        cfg = AiConfig.load(ctx);
        cfg.baseUrl = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
        cfg.apiKey = "sk-test";
        cfg.model = "fake-model";
    }

    @After
    public void tearDown() throws Exception {
        server.close();
    }

    /** 读一个 HTTP 请求（请求行 + 头 + 按 Content-Length 读体），回一个 JSON。 */
    private void serve(Socket s) throws Exception {
        InputStream in = s.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int c = in.read();
            if (c < 0) {
                return;
            }
            head.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
        int len = 0;
        String authorization = null;
        for (String l : lines) {
            String low = l.toLowerCase(java.util.Locale.US);
            if (low.startsWith("content-length:")) {
                len = Integer.parseInt(l.substring(15).trim());
            } else if (low.startsWith("authorization:")) {
                authorization = l.substring(14).trim();
            }
        }
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, len - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        boolean rightPath = lines[0].startsWith("POST /v1/chat/completions ");
        String[] reply = respond(new String(body, StandardCharsets.UTF_8), authorization);
        byte[] out = reply[1].getBytes(StandardCharsets.UTF_8);
        String status = rightPath ? reply[0] : "404 Not Found";
        OutputStream os = s.getOutputStream();
        os.write(("HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + out.length + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        os.write(out);
        os.flush();
    }

    /** 假模型：把每个包名都归到「工具」；到第 failFrom 个请求起返回 401。 */
    private String[] respond(String body, String authorization) throws Exception {
        int n;
        synchronized (requests) {
            requests.add(new JSONObject(body));
            auth.add(authorization);
            n = requests.size();
        }
        if (n >= failFrom.get()) {
            return new String[]{"401 Unauthorized", new JSONObject().put("error",
                    new JSONObject().put("message", "Incorrect API key")).toString()};
        }
        JSONArray msgs = requests.get(n - 1).getJSONArray("messages");
        String user = msgs.getJSONObject(msgs.length() - 1).getString("content");
        JSONObject map = new JSONObject();
        for (String line : user.split("\n")) {
            int bar = line.indexOf(" | ");
            if (bar > 0) {
                map.put(line.substring(0, bar), "工具");
            }
        }
        return new String[]{"200 OK", new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject()
                        .put("role", "assistant")
                        .put("content", "```json\n" + map + "\n```")))).toString()};
    }

    private static List<AiClassifier.App> apps(int n) {
        List<AiClassifier.App> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new AiClassifier.App("com.example.app" + i, "应用" + i));
        }
        return out;
    }

    @Test
    public void requestIsOpenAiShaped() throws Exception {
        String reply = AiClient.chat(cfg, "系统提示", "你好");
        assertTrue(reply.contains("{"));
        JSONObject req = requests.get(0);
        assertEquals("fake-model", req.getString("model"));
        assertEquals(false, req.getBoolean("stream"));
        JSONArray msgs = req.getJSONArray("messages");
        assertEquals("system", msgs.getJSONObject(0).getString("role"));
        assertEquals("系统提示", msgs.getJSONObject(0).getString("content"));
        assertEquals("user", msgs.getJSONObject(1).getString("role"));
        assertEquals("你好", msgs.getJSONObject(1).getString("content"));
        assertEquals("Bearer sk-test", auth.get(0));
    }

    @Test
    public void emptyKeySendsNoAuthorizationHeader() throws Exception {
        cfg.apiKey = "";
        AiClient.chat(cfg, null, "ping");
        assertNull(auth.get(0));
        assertEquals("没有 system 时只发一条 user", 1,
                requests.get(0).getJSONArray("messages").length());
    }

    @Test
    public void classifiesInBatchesAndReportsProgress() {
        int n = AiClassifier.BATCH * 2 + 5;
        final List<Integer> ticks = new ArrayList<>();
        AiClassifier.Result r = AiClassifier.classify(cfg, apps(n),
                AppClassifier.functionGroups(), (done, total) -> ticks.add(done));
        assertNull(r.error);
        assertEquals(n, r.asked);
        assertEquals(n, r.groups.size());
        assertEquals("工具", r.groups.get("com.example.app0"));
        assertEquals("应当分 3 批", 3, requests.size());
        assertEquals(Integer.valueOf(n), ticks.get(ticks.size() - 1));
    }

    @Test
    public void failureKeepsEarlierBatchesAndSurfacesServerMessage() {
        failFrom.set(2);
        int n = AiClassifier.BATCH + 10;
        AiClassifier.Result r = AiClassifier.classify(cfg, apps(n),
                AppClassifier.functionGroups(), null);
        assertEquals("第一批的结果要保留", AiClassifier.BATCH, r.groups.size());
        assertNotNull(r.error);
        assertTrue(r.error, r.error.contains("401"));
        assertTrue(r.error, r.error.contains("Incorrect API key"));
    }
}
