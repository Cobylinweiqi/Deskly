package io.github.cobylinweiqi.deskly.ai;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 让模型按「应用名 + 包名」把应用归进 Deskly 的功能组。
 *
 * <p>类别<b>固定</b>为内置的那一套，模型只回答"哪个应用进哪组"。不让它自己起组名：
 * 组名同时是桌面文件夹名，也是 {@code AppClassifier.isBuiltinGroup} 识别「上次写回产物」
 * 的依据；自由发挥的话，两次运行的组名对不上，文件夹复用和排屏都会乱。
 *
 * <p>分批发送：一次塞两百个应用，便宜模型容易漏项或把 JSON 写断；每批几十个，
 * 某一批失败也只丢这一批。
 */
public final class AiClassifier {

    /** 每批应用数。 */
    public static final int BATCH = 40;

    private AiClassifier() {
    }

    /** 要分类的一个应用。 */
    public static final class App {
        public final String pkg;
        public final String label;

        public App(String pkg, String label) {
            this.pkg = pkg == null ? "" : pkg;
            this.label = label == null ? "" : label;
        }
    }

    public interface Progress {
        /** @param done 已完成的应用数 */
        void onBatch(int done, int total);
    }

    /** 一次运行的结果：能拿到多少算多少，出错的批次把原因留在 {@link #error}。 */
    public static final class Result {
        public final Map<String, String> groups = new LinkedHashMap<>();
        /** 发出去的应用数。 */
        public int asked;
        /** 第一处失败的原因；全部成功为 null。 */
        public String error;
    }

    public static Result classify(AiConfig cfg, List<App> apps, List<String> groups,
                                  Progress progress) {
        Result r = new Result();
        r.asked = apps.size();
        String system = systemPrompt(groups);
        for (int from = 0; from < apps.size(); from += BATCH) {
            List<App> batch = apps.subList(from, Math.min(apps.size(), from + BATCH));
            try {
                String reply = AiClient.chat(cfg, system, userPrompt(batch));
                Map<String, String> got = parseReply(reply, batch, groups);
                if (got.isEmpty()) {
                    r.error = "模型的回复里没有可用的分类结果：" + clip(reply);
                    break;
                }
                r.groups.putAll(got);
            } catch (IOException e) {
                r.error = e.getMessage() == null ? e.toString() : e.getMessage();
                break;
            } catch (Throwable t) {
                r.error = t.toString();
                break;
            }
            if (progress != null) {
                progress.onBatch(Math.min(apps.size(), from + BATCH), apps.size());
            }
        }
        return r;
    }

    // ───────────────────────── 提示词 ─────────────────────────

    static String systemPrompt(List<String> groups) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是手机应用分类助手。根据应用名和包名判断每个应用的主要用途，")
                .append("把它归入下面的类别之一。\n\n类别（只能使用这些名字，一字不差）：\n");
        for (String g : groups) {
            sb.append("- ").append(g);
            String d = describe(g);
            if (!d.isEmpty()) {
                sb.append("：").append(d);
            }
            sb.append('\n');
        }
        sb.append("\n规则：\n")
                .append("1. 按应用的主要用途归类，不要被名字里的个别字眼带偏；")
                .append("认识这个应用就按你知道的实际功能判断。\n")
                .append("2. 每个应用必须且只能归入一个类别，不要遗漏。\n")
                .append("3. 实在无法判断的才用「其它」。\n")
                .append("4. 只输出一个 JSON 对象，键是包名，值是类别名，")
                .append("例如 {\"com.tencent.mm\":\"社交\"}。不要输出解释、不要用代码块。");
        return sb.toString();
    }

    /** 给模型的类别说明。新增内置组时这里没写也不会坏，只是少一句提示。 */
    private static String describe(String group) {
        switch (group) {
            case "社交":
                return "聊天、社区、论坛、招聘社交、企业通讯";
            case "影音":
                return "视频、音乐、直播、短视频、播客、电台、听书";
            case "游戏":
                return "游戏本体，以及游戏社区、加速器、游戏助手";
            case "购物":
                return "电商、二手交易、比价导购、品牌商城";
            case "金融":
                return "银行、支付、钱包、证券基金、保险、税务、运营商与水电缴费";
            case "出行":
                return "地图导航、打车、公交地铁、火车机票、车主与车企应用、充电加油";
            case "生活":
                return "外卖、本地生活、天气、健康运动、智能家居、快递、房产";
            case "办公学习":
                return "文档、邮箱、笔记、会议、翻译词典、课程与题库";
            case "工具":
                return "系统自带工具、浏览器、文件管理、网盘、输入法、应用商店、下载传输、AI 助手";
            case "开发·Root":
                return "root 与模块管理、终端、抓包调试、代理 VPN、各类玩机工具";
            case "摄影":
                return "相机、相册、修图、扫描、相机与无人机配套应用";
            case "资讯":
                return "新闻、阅读、小说、漫画、攻略资讯";
            case "系统":
                return "认不出具体用途的系统预装组件";
            case "其它":
                return "以上都不合适时使用";
            default:
                return "";
        }
    }

    static String userPrompt(List<App> batch) {
        StringBuilder sb = new StringBuilder("请分类下面的应用（每行：包名 | 应用名）：\n");
        for (App a : batch) {
            sb.append(a.pkg).append(" | ")
                    .append(a.label.isEmpty() ? "（无名称）" : a.label.replace('\n', ' '))
                    .append('\n');
        }
        return sb.toString();
    }

    // ───────────────────────── 解析 ─────────────────────────

    /**
     * 从模型回复里取出「包名 → 组名」。
     *
     * <p>宽松到什么程度：允许前后有解释文字、代码块围栏、推理模型的
     * {@code <think>…</think>}；允许外面多包一层（{@code {"result":{…}}}）；
     * 组名允许标点和空白有出入（「开发/Root」「开发 Root」都认成「开发·Root」）。
     * <br>不宽松的地方：只收这一批问过的包名、只收内置组名——模型编出来的包名或
     * 自创的类别一律丢弃，宁可这个应用回到内置规则。
     */
    static Map<String, String> parseReply(String reply, List<App> batch, List<String> groups) {
        Map<String, String> out = new LinkedHashMap<>();
        JSONObject o = firstObject(reply);
        if (o == null) {
            return out;
        }
        // 外面多包了一层：唯一的键、值又是对象
        if (o.length() == 1) {
            Object inner = o.opt(o.keys().next());
            if (inner instanceof JSONObject) {
                o = (JSONObject) inner;
            }
        }
        Map<String, String> asked = new LinkedHashMap<>();
        for (App a : batch) {
            asked.put(a.pkg.toLowerCase(java.util.Locale.US), a.pkg);
        }
        Map<String, String> canon = new LinkedHashMap<>();
        for (String g : groups) {
            canon.put(squash(g), g);
        }
        for (Iterator<String> it = o.keys(); it.hasNext(); ) {
            String k = it.next();
            String pkg = asked.get(k.trim().toLowerCase(java.util.Locale.US));
            if (pkg == null) {
                continue;
            }
            Object v = o.opt(k);
            if (!(v instanceof String)) {
                continue;
            }
            String g = canon.get(squash((String) v));
            if (g != null) {
                out.put(pkg, g);
            }
        }
        return out;
    }

    /** 回复里第一个左花括号到最后一个右花括号之间的 JSON 对象；解析不了返回 null。 */
    private static JSONObject firstObject(String reply) {
        if (reply == null) {
            return null;
        }
        String s = reply.replaceAll("(?s)<think>.*?</think>", "");
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            return null;
        }
        try {
            return new JSONObject(s.substring(a, b + 1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 只留字母、数字和汉字，用来比较组名。 */
    private static String squash(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static String clip(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim().replace('\n', ' ');
        return t.length() > 200 ? t.substring(0, 200) + "…" : t;
    }

    /** 调用方用它把「桌面上的条目」去重成要问的应用表。 */
    public static List<App> dedupe(List<App> apps) {
        Map<String, App> seen = new LinkedHashMap<>();
        for (App a : apps) {
            if (!a.pkg.isEmpty() && !seen.containsKey(a.pkg)) {
                seen.put(a.pkg, a);
            }
        }
        return new ArrayList<>(seen.values());
    }
}
