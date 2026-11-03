package com.coby.deskly.organize;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 主题词典：把"一个应用是干什么的"翻译成一组可解释的主题标签。
 *
 * <p>为什么不直接用 {@link AppClassifier}：那套模块是给"从零生成方案"用的，
 * 关键词表只有 60 来条、以英文为主，对中文应用几乎全军覆没——实测把同花顺、Tesla、
 * 短信、建行生活、Deskly 这五个全判成了"其它"。而这里的任务不同：
 * 要判断"它该进哪个<b>已经存在</b>的文件夹"，所以主题必须能对上用户自己起的中文名，
 * 并且每个判定都要能说出理由（"同属『证券理财』"），否则用户没法信任这条建议。
 *
 * <p>标签是<b>多值</b>的：一个应用可以既属于"社交社区"又属于"影音视频"。打分时
 * 命中的主题越多、越集中，建议越可信。
 */
public final class Semantic {

    /** 主题名直接用中文，因为最终要原样显示在界面上当理由。 */
    public static final String BANK = "银行";
    public static final String FINANCE = "证券理财";
    public static final String SHOP = "购物";
    public static final String FOOD = "外卖到店";
    public static final String TRAVEL = "出行交通";
    public static final String CAR = "车服务";
    public static final String SOCIAL = "社交社区";
    public static final String VIDEO = "影音视频";
    public static final String MUSIC = "音乐音频";
    public static final String OFFICE = "办公文档";
    public static final String CLOUD = "云盘传输";
    public static final String STUDY = "学习";
    public static final String DEV = "开发Root";
    public static final String BROWSER = "浏览器";
    public static final String CAMERA = "影像摄影";
    public static final String HOME = "智能家居";
    public static final String TOOL = "系统工具";
    public static final String GAME = "游戏";

    /** 主题 → 关键词。中文按子串匹配应用名，英文按子串匹配包名。 */
    private static final String[][] DICT = {
            {BANK, "银行", "信用卡", "储蓄", "储蓄卡", "ccb", "icbc", "abc", "boc", "cmb",
                    "spdb", "citic", "ceb", "psbc", "chinamworld", "ecitic", "citiccard",
                    "cmbchina", "bankcomm", "abchina", "hxb", "cib", "axa"},
            {FINANCE, "证券", "股票", "炒股", "基金", "理财", "同花顺", "东方财富", "涨乐",
                    "云闪付", "数字人民币", "钱包", "hexin", "eastmoney", "galaxy",
                    "unionpay", "mipay", "jiufang", "lufax", "snowball"},
            {SHOP, "购物", "商城", "淘宝", "天猫", "京东", "拼多多", "唯品会", "苏宁",
                    "闲鱼", "什么值得买", "taobao", "tmall", "jingdong", "xunmeng",
                    "vipshop", "suning", "smzdm", "idlefish", "pinduoduo"},
            {FOOD, "外卖", "到店", "美团", "饿了么", "点评", "买菜", "sankuai", "meituan",
                    "dianping", "ele", "atpchina", "waimai"},
            {TRAVEL, "地图", "导航", "打车", "公交", "地铁", "火车", "机票", "酒店", "旅游",
                    "出行", "12306", "autonavi", "baidumap", "didi", "sdu", "mobileticket",
                    "trip", "amap", "ctrip"},
            // 刻意不收单字「车」：支付宝乘车码、乘车类小程序的名字里都带「车」，
            // 一旦放开，连「支付·理财」文件夹都会命中车服务，把 Tesla 判到理财里去。
            {CAR, "车主", "加油", "etc", "特斯拉", "蔚来", "理想", "小鹏", "比亚迪",
                    "tesla", "teslamotors", "nio", "xpeng", "byd", "lixiang"},
            {SOCIAL, "社交", "社区", "聊天", "微博", "知乎", "豆瓣", "贴吧", "小红书",
                    "脉脉", "酷安", "weibo", "zhihu", "douban", "xingin", "coolapk",
                    "sina", "tencent.mm", "mobileqq", "momo", "bilibili"},
            {VIDEO, "影音", "视频", "影视", "直播", "电视", "cctv", "danmaku", "hunantv",
                    "qiyi", "youku", "qqlive", "phoenix", "hpplay", "iqiyi", "mgtv"},
            {MUSIC, "音乐", "音频", "播客", "收音", "netease", "kugou", "ximalaya",
                    "ovelin", "cloudmusic", "qqmusic", "spotify"},
            {OFFICE, "办公", "文档", "笔记", "邮箱", "wps", "office", "microsoft", "ainemo",
                    "hpbr", "doc", "notion", "evernote", "yuque"},
            {CLOUD, "云盘", "传输", "网盘", "快传", "下载", "localsend", "xunlei", "mfcloud",
                    "baiduyun", "aliyun", "onedrive", "dropbox"},
            {STUDY, "学习", "词典", "单词", "题库", "扇贝", "豆包", "考研", "课程",
                    "shanbay", "tiku", "larus", "duolingo", "youdao", "chaoxing"},
            {DEV, "开发", "root", "magisk", "shizuku", "termux", "mt管理器", "lsposed",
                    "模块", "超级用户", "密钥认证", "kernelsu", "ksu", "topjohnwu",
                    "frknkrc44", "songe", "topmiaohan", "gkd", "hma", "shell", "adb"},
            {BROWSER, "浏览器", "browser", "quark", "via", "chrome", "edge", "firefox",
                    "jiguro", "safari", "opera"},
            {CAMERA, "影像", "相机", "拍照", "摄影", "camera", "photo", "mimo", "dengziwl",
                    "lightroom", "snapseed"},
            {HOME, "智能家居", "家居", "家电", "midea", "jmgo", "mi.home", "mihome",
                    "yeelight", "aqara"},
            {TOOL, "工具", "设置", "计算器", "日历", "时钟", "天气", "录音", "文件管理",
                    "联系人", "短信", "电话", "输入法", "商店", "系统", "查找", "助手",
                    "倒数日", "mms", "settings", "calendar", "clover", "custom",
                    "calculator", "weather"},
            {GAME, "游戏", "game", "blizzard", "hypergryph", "aliothmoon", "mihoyo",
                    "supercell", "gonlan", "tencent.tmgp"},
    };

    private Semantic() {
    }

    /** 命中的主题，按词典顺序，天然去重。 */
    public static List<String> topicsOf(String name, String pkg) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        String p = pkg == null ? "" : pkg.toLowerCase(Locale.US);
        java.util.Set<String> segs = segmentsOf(p);
        // 应用名也要切词："QQ"、"WPS Office" 里的缩写是独立词，不能只认包名
        segs.addAll(segmentsOf(n));
        List<String> out = new ArrayList<>();
        for (String[] row : DICT) {
            for (int i = 1; i < row.length; i++) {
                String kw = row[i];
                if (hits(n, p, segs, kw)) {
                    out.add(row[0]);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * 关键词命中判断。
     *
     * <p>中文词按子串匹配（"银行" 命中 "中国建设银行"，符合直觉）；
     * <b>短英文词必须精确等于包名的某一段</b>。这条限制不是洁癖：
     * {@code com.unionpay} 里恰好含 {@code nio}（u-nio-npay），按子串匹配会把云闪付判成
     * 车服务里的"蔚来"，结果 Tesla 的归位建议会多出一个"支付·理财"的次选。
     * 同理 {@code abc} / {@code boc} / {@code cmb} / {@code etc} 都有这个风险。
     */
    private static boolean hits(String name, String pkg, java.util.Set<String> segs, String kw) {
        if (isShortAscii(kw)) {
            return segs.contains(kw);
        }
        return name.contains(kw) || pkg.contains(kw);
    }

    /** 纯 ASCII 且不超过 4 个字符，视为缩写词，只做整段匹配。 */
    private static boolean isShortAscii(String s) {
        if (s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static java.util.Set<String> segmentsOf(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s.isEmpty()) {
            return out;
        }
        out.add(s);
        for (String seg : s.split("[^a-z0-9]+")) {
            if (!seg.isEmpty()) {
                out.add(seg);
            }
        }
        return out;
    }

    /**
     * 缓存"某个包是不是模块"。分析范围扩大到全部图标之后，认不出类别的应用从几个变成几十个，
     * 每次分析都重新解一遍 APK 是白费力气。
     */
    private static final java.util.Map<String, Boolean> XPOSED_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 一个 APK 是不是 LSPosed / Xposed 模块。
     *
     * <p>模块类应用（Magisk、Shizuku、各种模块管理器）没有任何能写进词典的语义关键词，
     * 却恰好是"开发Root"文件夹里最该待着的东西。与其往词典里一条条加包名（永远加不全），
     * 不如认它们的共同结构：模块 APK 里一定带着 {@code META-INF/xposed/} 目录。
     *
     * <p>只在"一个主题都没命中"时才调用，避免为每个应用都开一次 zip。
     */
    public static boolean isXposedModule(Context ctx, String pkg) {
        Boolean hit = XPOSED_CACHE.get(pkg);
        if (hit != null) {
            return hit;
        }
        boolean v = scanXposed(ctx, pkg);
        XPOSED_CACHE.put(pkg, v);
        return v;
    }

    private static boolean scanXposed(Context ctx, String pkg) {
        ZipFile zf = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (ai.sourceDir == null) {
                return false;
            }
            zf = new ZipFile(ai.sourceDir);
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (n.startsWith("META-INF/xposed/")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (zf != null) {
                try {
                    zf.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return false;
    }

    /** 给界面用的一句话：{@code 证券理财、购物}。 */
    public static String describe(List<String> topics) {
        if (topics.isEmpty()) {
            return "无匹配主题";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < topics.size(); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(topics.get(i));
        }
        return sb.toString();
    }
}
