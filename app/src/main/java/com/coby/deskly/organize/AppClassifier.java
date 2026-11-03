package com.coby.deskly.organize;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 分类引擎：只做计算，不碰桌面。
 *
 * <p>判定优先级从高到低：精确包名 → 包名关键词 → 应用名关键词 → 系统应用兜底 → 其它。
 * 精确表优先是为了避免"支付宝"因为名字含"支付"被扔进金融组的同时，
 * "闲鱼"因为名字含"鱼"之类莫名其妙的原因跑错组。
 *
 * <p><b>这张表是被真机数据打回来重做的。</b>初版只有几十条通用规则，拿真机 151 个图标
 * 一跑，86 个落进「其它」——整理完的桌面会是"一个 86 个图标的巨型文件夹 + 一堆三四个的小组"，
 * 比用户自己分的还乱。功能没错，是词典太薄：国内主流 App 和小米系统应用几乎全在词典外。
 * 现在的表按真机上出现的包名逐条补齐，同类 ROM 上装的应用也大多能被覆盖。
 *
 * <p>「开发·Root」单独成组不是给所有人准备的，而是这类工具聚在一起才找得到——
 * Magisk / Shizuku / Termux / MT管理器 分散在"工具"里时，翻文件夹比翻桌面还慢。
 */
public final class AppClassifier {

    public static final String GROUP_OTHER = "其它";

    /** 组名 → 精确包名（全小写）。命中即锁定，不再往后走。 */
    private static final Map<String, String[]> EXACT = new LinkedHashMap<>();

    /** 组名 → 包名/应用名关键词（小写）。 */
    private static final Map<String, String[]> HINTS = new LinkedHashMap<>();

    /** 组的展示顺序。 */
    private static final String[] ORDER = {
            "社交", "影音", "游戏", "购物", "金融", "出行", "生活",
            "办公学习", "工具", "开发·Root", "摄影", "资讯", "系统", GROUP_OTHER,
    };

    /** 颜色方案的组顺序，兜底组"黑白灰"必须排最后（classify 里按末位取 fallback）。 */
    private static final String[] COLOR_ORDER = {
            "红", "橙", "黄", "绿", "青", "蓝", "靛", "紫", "粉", "黑白灰",
    };

    static {
        EXACT.put("社交", new String[]{
                "com.tencent.mm", "com.tencent.mobileqq", "com.sina.weibo", "com.xingin.xhs",
                "com.zhihu.android", "com.douban.frodo", "com.taou.maimai", "com.baidu.tieba",
                "com.hpbr.bosszhipin", "com.tencent.wework", "com.alibaba.android.rimet",
                "com.xiaomi.vipaccount", "com.tencent.workbuddy.app",
                "org.telegram.messenger", "com.whatsapp", "com.instagram.android",
        });
        EXACT.put("影音", new String[]{
                "tv.danmaku.bili", "com.example.piliplus", "com.ss.android.ugc.aweme",
                "com.ximalaya.ting.android", "com.netease.cloudmusic", "com.miui.player",
                "com.kugou.android", "com.tencent.qqlive", "com.qiyi.video", "com.youku.phone",
                "com.hunantv.imgo.activity", "com.cmcc.cmvideo", "com.cctv.yangshipin.app.androidp",
                "air.tv.douyu.android", "com.duowan.kiwi", "com.sankuai.movie", "cn.damai",
                "com.phoenix.read", "com.example.hikerview", "com.roitium.bbplayer",
                "com.ainemo.android.ccbbank", "com.google.android.youtube",
                "com.netflix.mediaclient", "com.smile.gifmaker",
        });
        EXACT.put("游戏", new String[]{
                "com.hypergryph.endfield", "com.hypergryph.skland",
                "com.blizzard.wtcg.hearthstone", "com.netease.hearthstone",
                "com.gonlan.iplaymtg", "wild.cats", "com.netease.gl", "com.max.xiaoheihe",
                "com.sinyee.babybus.recommendapp", "com.jmgo.funcontrol",
                "com.aliothmoon.maafw.maaend", "com.mihoyo.yuanshen", "com.mihoyo.hkrpg",
                "com.tencent.tmgp.sgame", "com.tencent.tmgp.pubgmhd",
        });
        EXACT.put("购物", new String[]{
                "com.taobao.taobao", "com.tmall.wireless", "com.jingdong.app.mall",
                "com.xunmeng.pinduoduo", "com.taobao.idlefish", "com.coolapk.market",
                "com.smzdm.client.android", "com.dji.store", "com.ss.android.ugc.lifeservices",
                "com.mxj.wylcjbxyx", "com.zxbyrk.xszi.xszc", "com.achievo.vipshop",
                "com.suning.mobile.ebuy", "com.amazon.mshop.android.shopping",
        });
        EXACT.put("金融", new String[]{
                // 支付 / 钱包
                "com.eg.android.alipaygphone", "com.unionpay", "cn.gov.pbc.dcep",
                "com.mipay.wallet",
                // 银行
                "com.chinamworld.main", "com.chinamworld.bocmbci", "com.icbc",
                "com.android.bankabc", "cmb.pb", "com.cmbchina.ccd.pluto.cmbactivity",
                "cn.com.spdb.mobilebank.per", "com.spdbccc.app", "com.webank.wemoney",
                "com.zhongan.ibank", "com.citiccard.mobilebank", "com.ecitic.bank.mobile",
                "com.axa.hk.emma", "com.ccb.ecpmobile.ecp", "com.ccb.longjilife",
                // 券商 / 理财 / 保险 / 缴费
                "com.eastmoney.android.fund", "com.galaxy.stock", "com.hexin.plat.android",
                "cn.gov.tax.its", "com.paic.yl.health", "com.pa.sales.client",
                "com.pingan.carowner", "com.sgcc.wsgw.cn",
                "com.greenpoint.android.mc10086.activity", "com.ai.obc.cbn.app",
        });
        EXACT.put("出行", new String[]{
                "com.autonavi.minimap", "com.baidu.baidumap", "com.google.android.apps.maps",
                "com.sdu.didi.psnger", "com.mobileticket", "com.trip.pocket",
                "com.teslamotors.tesla",
        });
        EXACT.put("生活", new String[]{
                "com.sankuai.meituan", "com.sankuai.meituan.takeoutnew", "com.dianping.v1",
                "me.ele", "cn.atpchina.mobileapp", "com.xiachufang", "com.anjuke.android.app",
                "com.miui.weather2", "com.xiaomi.smarthome", "com.huawei.smarthome",
                "com.midea.ai.appliances", "com.xiaomi.mico", "com.huawei.smartaudio",
        });
        EXACT.put("办公学习", new String[]{
                "cn.wps.moffice_eng", "com.google.android.gm", "com.microsoft.office.word",
                "com.youdao.dict", "com.shanbay.sentence", "com.tiku.app", "com.miui.notes",
                "com.microsoft.rdc.androidx", "com.microsoft.teams", "com.google.android.apps.docs",
                "com.duolingo", "com.youdao.note", "com.evernote", "com.tencent.androidqqmail",
        });
        EXACT.put("工具", new String[]{
                "com.miui.calculator", "com.android.fileexplorer", "com.android.settings",
                "com.android.soundrecorder", "com.android.deskclock", "com.android.calendar",
                "com.android.mms", "com.android.contacts",
                "com.android.providers.downloads.ui", "com.android.browser", "com.quark.browser",
                "com.android.chrome", "com.microsoft.emmx", "com.xiaomi.market",
                "com.miui.voiceassistproxy", "com.baidu.netdisk", "com.mfcloudcalculate.networkdisk",
                "com.chinamobile.mcloud", "com.xunlei.downloadprovider",
                "com.jd.cloud.download", "com.wirelessalien.zipxtract",
                "org.localsend.localsend_app", "com.hpplay.happycast", "com.clover.daysmatter",
                "com.tencent.wetype", "com.android.vending", "com.larus.nova", "com.ovelin.guitartuna",
        });
        EXACT.put("开发·Root", new String[]{
                "com.topjohnwu.magisk", "moe.shizuku.privileged.api", "com.termux", "bin.mt.plus",
                "li.songe.gkd", "org.frknkrc44.hma_oss", "io.github.a13e300.ksuwebui",
                "com.shizuku.uninstaller", "io.github.vvb2060.keyattestation",
                "io.github.wisyh.zenneko", "com.coby.deskly", "com.custom.zktimehelp",
                "com.wrongchao.v2vpn", "com.github.metacubex.clash.meta",
                "com.jiguro.bettervia", "com.topmiaohan.sulist",
        });
        EXACT.put("摄影", new String[]{
                "com.miui.gallery", "dji.mimo", "com.dengziwl.bk", "com.xiaomi.scanner",
        });

        HINTS.put("社交", new String[]{"chat", "social", "message", "messenger", "weibo", "qzone", "论坛"});
        HINTS.put("影音", new String[]{"video", "music", "player", "movie", "audio", "media", "live", "cast", "影视", "视频", "音乐", "直播", "短剧"});
        HINTS.put("游戏", new String[]{"game", "tmgp", "mihoyo", "supercell", "pubg", "kingdom", "游戏"});
        HINTS.put("购物", new String[]{"shop", "mall", "store", "market", "taobao", "buy", "商城", "购物", "优惠"});
        HINTS.put("金融", new String[]{"bank", "pay", "wallet", "finance", "stock", "fund", "保险", "银行", "证券", "基金", "信用卡"});
        HINTS.put("出行", new String[]{"map", "navi", "taxi", "travel", "train", "flight", "地铁", "公交", "地图", "出行"});
        HINTS.put("生活", new String[]{"food", "weather", "health", "fitness", "home", "recipe", "外卖", "天气", "买菜"});
        HINTS.put("办公学习", new String[]{"office", "doc", "note", "mail", "study", "learn", "edu", "字典", "笔记", "单词", "题库"});
        HINTS.put("工具", new String[]{"tool", "calc", "file", "clean", "zip", "scanner", "settings", "browser", "download", "网盘", "文件", "下载", "浏览器", "计算器"});
        HINTS.put("开发·Root", new String[]{"magisk", "shizuku", "termux", "xposed", "root", "adb", "vpn", "proxy", "clash", "shell", "终端"});
        HINTS.put("摄影", new String[]{"camera", "photo", "gallery", "image", "filter", "相机", "相册", "修图"});
        HINTS.put("资讯", new String[]{"news", "reader", "article", "头条", "新闻", "资讯"});
    }

    private AppClassifier() {
    }

    /** 桌面能点开的应用（不含纯后台服务）。 */
    public static List<AppEntry> loadDesktopVisible(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = pm.queryIntentActivities(main, 0);
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        List<AppEntry> out = new ArrayList<>(ris.size());
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) {
                continue;
            }
            ApplicationInfo ai = ri.activityInfo.applicationInfo;
            String label;
            try {
                label = ai.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = ai.packageName;
            }
            Drawable icon = null;
            try {
                icon = ai.loadIcon(pm);
            } catch (Throwable ignored) {
            }
            boolean sys = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            out.add(new AppEntry(label, ai.packageName, ri.activityInfo.name, sys, icon));
        }
        return out;
    }

    /**
     * 只取"能启动的应用"的包名集合，不加载图标与名字。
     *
     * <p>用来核一个数：本机装了多少可启动应用、其中几个不在桌面上。
     * 走 {@link #loadDesktopVisible} 会把每个应用的图标都 rasterize 一遍，
     * 几百个图标白算一遍只为数个数，不值得。
     */
    public static java.util.Set<String> launcherPackages(Context ctx) {
        java.util.Set<String> out = new java.util.HashSet<>();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris;
        try {
            ris = ctx.getPackageManager().queryIntentActivities(main, 0);
        } catch (Throwable t) {
            return out;
        }
        for (ResolveInfo ri : ris) {
            if (ri.activityInfo == null || ri.activityInfo.applicationInfo == null) {
                continue;
            }
            String p = ri.activityInfo.applicationInfo.packageName;
            if (p != null && !p.isEmpty()) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * 预设的整理方案。枚举声明顺序即界面上的「方案一 / 方案二」，
     * 所以 COLOR 必须写在 FUNCTION 前面——界面的序号由这里推导，不另存一份，
     * 免得哪天调整顺序时两处对不上。
     */
    public enum Scheme {
        /** 方案一：按图标主色 → 红 / 橙 / 黄 / 绿 / 青 / 蓝 / 靛 / 紫 / 粉 / 黑白灰。 */
        COLOR("按颜色", "提取图标主色分桶，同色系进同一个文件夹"),
        /** 方案二：按应用功能 → 社交 / 影音 / 游戏 / 购物 / 金融 … */
        FUNCTION("按功能", "按用途归类，冷门 App 也有明确去处");

        /** 方案名，如「按颜色」。 */
        public final String title;
        /** 一句话说明，显示在方案卡片第二行。 */
        public final String desc;

        Scheme(String title, String desc) {
            this.title = title;
            this.desc = desc;
        }

        /** 界面标题，如「方案一 · 按颜色」。 */
        public String heading() {
            return indexName() + " · " + title;
        }

        public String indexName() {
            return this == COLOR ? "方案一" : "方案二";
        }
    }

    public static Map<String, List<AppEntry>> classify(List<AppEntry> apps) {
        return classify(apps, Scheme.FUNCTION);
    }

    public static Map<String, List<AppEntry>> classify(List<AppEntry> apps, Scheme scheme) {
        String[] order = scheme == Scheme.COLOR ? COLOR_ORDER : ORDER;
        Map<String, List<AppEntry>> buckets = new LinkedHashMap<>();
        for (String g : order) {
            buckets.put(g, new ArrayList<>());
        }
        for (AppEntry e : apps) {
            String g = scheme == Scheme.COLOR ? groupByColor(e) : groupByFunction(e);
            List<AppEntry> list = buckets.get(g);
            if (list == null) {
                list = buckets.get(order[order.length - 1]);
            }
            list.add(e);
        }
        // 组内按名称排序，空组丢弃
        Map<String, List<AppEntry>> out = new LinkedHashMap<>();
        for (String g : order) {
            List<AppEntry> list = buckets.get(g);
            if (list == null || list.isEmpty()) {
                continue;
            }
            Collections.sort(list, (a, b) -> a.label.compareToIgnoreCase(b.label));
            out.put(g, list);
        }
        return out;
    }

    /** 颜色方案的兜底组必须放在最后一项。 */
    public static String groupByColor(AppEntry e) {
        return IconPalette.groupOf(e.dominantColor());
    }

    public static boolean isColorGroup(String name) {
        return IconPalette.GROUP_COLORS.containsKey(name);
    }

    public static String groupByFunction(AppEntry e) {
        String pkg = e.pkg == null ? "" : e.pkg.toLowerCase(Locale.US);
        String label = e.label == null ? "" : e.label.toLowerCase(Locale.US);

        String hit = firstExactHit(pkg);
        if (!hit.isEmpty()) {
            return hit;
        }
        for (Map.Entry<String, String[]> en : HINTS.entrySet()) {
            for (String h : en.getValue()) {
                if (pkg.contains(h) || label.contains(h)) {
                    return en.getKey();
                }
            }
        }
        if (e.system) {
            return "系统";
        }
        return GROUP_OTHER;
    }

    /**
     * 精确表里第一个命中的组名，没命中返回空串。
     *
     * <p>拆出来是因为要先拿到组名才能判断"命中了没有"——原写法遍历整个表再 return，
     * 想复用得再走一遍，而这张表现在有一百多条。
     */
    private static String firstExactHit(String pkg) {
        if (pkg.isEmpty()) {
            return "";
        }
        for (Map.Entry<String, String[]> en : EXACT.entrySet()) {
            for (String p : en.getValue()) {
                if (pkg.equals(p)) {
                    return en.getKey();
                }
            }
        }
        return "";
    }
}
