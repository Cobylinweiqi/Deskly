package io.github.cobylinweiqi.deskly.ai;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.cobylinweiqi.deskly.organize.AppClassifier;
import io.github.cobylinweiqi.deskly.organize.AppEntry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AI 辅助分类里不需要联网的那部分：端点拼接、回复解析、覆盖表接入分类引擎。
 *
 * <p>模型的回复是这条链路上最不可控的一环，所以解析要对着真实会出现的脏输出测：
 * 代码块围栏、前后解释、推理模型的 think 段、编造的包名、自创的类别。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AiClassifierTest {

    private static final List<String> GROUPS = AppClassifier.functionGroups();

    private static List<AiClassifier.App> batch(String... pkgs) {
        List<AiClassifier.App> out = new ArrayList<>();
        for (String p : pkgs) {
            out.add(new AiClassifier.App(p, p));
        }
        return out;
    }

    @After
    public void tearDown() {
        AppClassifier.setAiOverrides(null);
    }

    // ───────────────────────── 端点 ─────────────────────────

    @Test
    public void endpointAcceptsTheWaysPeopleTypeIt() {
        String want = "https://api.example.com/v1/chat/completions";
        assertEquals(want, AiClient.endpoint("https://api.example.com/v1"));
        assertEquals(want, AiClient.endpoint(" https://api.example.com/v1/ "));
        assertEquals(want, AiClient.endpoint("api.example.com/v1"));
        assertEquals(want, AiClient.endpoint(want));
        assertEquals("http://192.168.1.2:11434/v1/chat/completions",
                AiClient.endpoint("http://192.168.1.2:11434/v1"));
        assertEquals("", AiClient.endpoint("  "));
    }

    @Test
    public void contentHandlesStringAndPartArray() throws Exception {
        assertEquals("hi", AiClient.content(
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}"));
        assertEquals("ab", AiClient.content(
                "{\"choices\":[{\"message\":{\"content\":"
                        + "[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"text\",\"text\":\"b\"}]}}]}"));
    }

    @Test
    public void errorMessageIsPulledOutOfErrorBody() {
        assertEquals("Incorrect API key",
                AiClient.errorMessage("{\"error\":{\"message\":\"Incorrect API key\",\"code\":401}}"));
        assertEquals("plain text", AiClient.errorMessage("plain text"));
    }

    // ───────────────────────── 解析 ─────────────────────────

    @Test
    public void parsesPlainJson() {
        Map<String, String> m = AiClassifier.parseReply(
                "{\"com.a\":\"社交\",\"com.b\":\"工具\"}", batch("com.a", "com.b"), GROUPS);
        assertEquals("社交", m.get("com.a"));
        assertEquals("工具", m.get("com.b"));
    }

    @Test
    public void toleratesFencesProseAndThinkBlocks() {
        String reply = "<think>com.a 看起来像 {游戏}？不对。</think>\n好的，结果如下：\n"
                + "```json\n{\"com.a\": \"影音\"}\n```\n希望有帮助。";
        Map<String, String> m = AiClassifier.parseReply(reply, batch("com.a"), GROUPS);
        assertEquals("影音", m.get("com.a"));
    }

    @Test
    public void unwrapsSingleKeyWrapper() {
        Map<String, String> m = AiClassifier.parseReply(
                "{\"result\":{\"com.a\":\"金融\"}}", batch("com.a"), GROUPS);
        assertEquals("金融", m.get("com.a"));
    }

    @Test
    public void groupNamesMatchIgnoringPunctuation() {
        Map<String, String> m = AiClassifier.parseReply(
                "{\"com.a\":\"开发/Root\",\"com.b\":\" 办公 学习 \"}",
                batch("com.a", "com.b"), GROUPS);
        assertEquals("开发·Root", m.get("com.a"));
        assertEquals("办公学习", m.get("com.b"));
    }

    @Test
    public void dropsInventedPackagesAndCategories() {
        Map<String, String> m = AiClassifier.parseReply(
                "{\"com.a\":\"效率神器\",\"com.made.up\":\"社交\",\"com.b\":123,\"COM.C\":\"游戏\"}",
                batch("com.a", "com.b", "com.c"), GROUPS);
        assertNull("自创的类别不收", m.get("com.a"));
        assertNull("没问过的包名不收", m.get("com.made.up"));
        assertNull("值不是字符串不收", m.get("com.b"));
        assertEquals("包名大小写不敏感，但写回原样", "游戏", m.get("com.c"));
        assertEquals(1, m.size());
    }

    @Test
    public void garbageYieldsEmptyMap() {
        assertTrue(AiClassifier.parseReply("抱歉，我无法完成。", batch("com.a"), GROUPS).isEmpty());
        assertTrue(AiClassifier.parseReply("{\"com.a\": ", batch("com.a"), GROUPS).isEmpty());
        assertTrue(AiClassifier.parseReply(null, batch("com.a"), GROUPS).isEmpty());
    }

    @Test
    public void promptListsEveryGroupAndEveryApp() {
        String sys = AiClassifier.systemPrompt(GROUPS);
        for (String g : GROUPS) {
            assertTrue("提示词里要有类别 " + g, sys.contains("- " + g));
        }
        String user = AiClassifier.userPrompt(Arrays.asList(
                new AiClassifier.App("com.a", "甲"), new AiClassifier.App("com.b", "")));
        assertTrue(user.contains("com.a | 甲"));
        assertTrue(user.contains("com.b | （无名称）"));
    }

    @Test
    public void dedupeKeepsFirstAndSkipsEmptyPackage() {
        List<AiClassifier.App> d = AiClassifier.dedupe(Arrays.asList(
                new AiClassifier.App("com.a", "一"), new AiClassifier.App("", "空"),
                new AiClassifier.App("com.a", "二")));
        assertEquals(1, d.size());
        assertEquals("一", d.get(0).label);
    }

    // ───────────────────────── 接入分类引擎 ─────────────────────────

    private static AppEntry wechat() {
        return new AppEntry("微信", "com.tencent.mm", "", false, null);
    }

    @Test
    public void overridesWinOverBuiltinRulesAndCanBeTurnedOff() {
        assertEquals("社交", AppClassifier.groupByFunction(wechat()));

        Map<String, String> ai = new HashMap<>();
        ai.put("com.tencent.mm", "工具");
        AppClassifier.setAiOverrides(ai);
        assertEquals("AI 结果优先", "工具", AppClassifier.groupByFunction(wechat()));
        assertEquals("内置规则本身不受影响", "社交", AppClassifier.groupByRules(wechat()));

        AppClassifier.setAiOverrides(Collections.<String, String>emptyMap());
        assertEquals("清空后回到内置规则", "社交", AppClassifier.groupByFunction(wechat()));
    }

    @Test
    public void overridesWithUnknownGroupAreIgnored() {
        Map<String, String> ai = new HashMap<>();
        ai.put("com.tencent.mm", "红");
        ai.put("com.unknown.app", "不存在的组");
        AppClassifier.setAiOverrides(ai);
        assertEquals("颜色组名不是功能组，不能生效", "社交", AppClassifier.groupByFunction(wechat()));
        assertFalse("其它应用照常走规则", "不存在的组".equals(AppClassifier.groupByFunction(
                new AppEntry("某应用", "com.unknown.app", "", false, null))));
    }
}
