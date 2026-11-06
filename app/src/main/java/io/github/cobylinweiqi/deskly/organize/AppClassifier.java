package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 分类引擎：只做计算，不碰桌面。
 *
 * <p>判定优先级从高到低：精确包名 → 包名/应用名关键词 → 中文应用名 → 包名英文段
 * → 系统声明的 category → 系统应用兜底 → 其它。
 * 精确表优先是为了避免"支付宝"因为名字含"支付"被扔进金融组的同时，
 * "闲鱼"因为名字含"鱼"之类莫名其妙的原因跑错组。
 *
 * <p><b>这张表是被真机数据打回来重做的。</b>初版只有几十条通用规则，拿真机 151 个图标
 * 一跑，86 个落进「其它」——整理完的桌面会是"一个 86 个图标的巨型文件夹 + 一堆三四个的小组"，
 * 比用户自己分的还乱。功能没错，是词典太薄：国内主流 App 和小米系统应用几乎全在词典外。
 * 现在的表按真机上出现的包名逐条补齐，同类 ROM 上装的应用也大多能被覆盖。
 *
 * <p><b>0.16.0：兼容 Launcher3 系（真我 / ColorOS）后，词典又不够用了。</b>那批预装
 * 包名是 {@code com.coloros.*} / {@code com.oplus.*} / {@code com.heytap.*} /
 * {@code com.nearme.*} / {@code com.realme.*}，几乎全不在按小米真机补的精确表里；
 * 而且桌面库里的 title 有时是空、资源引用甚至包名本身。于是补三条<b>纯增量</b>规则：
 * 从 PackageManager 拿应用名、按中文应用名判、按包名英文段判，最后再读系统声明的
 * {@code ApplicationInfo.category}。<b>旧的 EXACT / HINTS 及其判定顺序一个字没动</b>——
 * 新规则只在旧规则全不中时才接手，小米 0.14.1 的分组结果不受影响。
 *
 * <p>「开发·Root」单独成组不是给所有人准备的，而是这类工具聚在一起才找得到——
 * Magisk / Shizuku / Termux / MT管理器 分散在"工具"里时，翻文件夹比翻桌面还慢。
 */
public final class AppClassifier {

    public static final String GROUP_OTHER = "其它";
    public static final String GROUP_SYSTEM = "系统";

    /** 组名 → 精确包名（全小写）。命中即锁定，不再往后走。 */
    private static final Map<String, String[]> EXACT = new LinkedHashMap<>();

    /** 组名 → 包名/应用名关键词（小写）。0.14.1 的原有规则，保持原样不动。 */
    private static final Map<String, String[]> HINTS = new LinkedHashMap<>();

    /**
     * 中文应用名关键词 → 组名。只匹配应用名（含从 PM 补出的名字），不匹配包名。
     * 子串匹配：应用名「中国建设银行」里含「银行」正是我们想要的。
     */
    private static final Map<String, String> LABEL_HINTS = new LinkedHashMap<>();

    /**
     * 包名英文段 → 组名。<b>整段匹配</b>，不是子串：{@code com.unionpay} 里含 {@code nio}，
     * 按子串会把云闪付判成「蔚来」；{@code camerapro} 也不该被 {@code camera} 拖走。
     * 真我 / ColorOS 预装靠这一层兜住——厂商前缀千变万化，功能后缀却是稳定的。
     */
    private static final Map<String, String> SEGMENT_GROUP = new LinkedHashMap<>();

    /**
     * 应用名（小写）精确 → 组名，<b>排在 HINTS 之前</b>。
     *
     * <p>0.16.1：0.14.1 的关键词表里有 {@code store} / {@code market}，
     * 于是「LSP Store」「Aurora Store」「华为应用市场」这些<b>应用商店 / 模块仓库</b>
     * 会被「购物」抢先命中；「realme商城」同理，它是系统应用商店而不是电商。
     * 这类名字没法规律化，只能点名。旧的 EXACT 包名表仍然最优先，
     * 所以小米上已由包名锁定的分组一个都不会变。
     */
    private static final Map<String, String> LABEL_EXACT = new LinkedHashMap<>();

    /** 组的展示顺序。 */
    private static final String[] ORDER = {
            "社交", "影音", "游戏", "购物", "金融", "出行", "生活",
            "办公学习", "工具", "开发·Root", "摄影", "资讯", GROUP_SYSTEM, GROUP_OTHER,
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
                "io.github.wisyh.zenneko", "io.github.cobylinweiqi.deskly", "com.custom.zktimehelp",
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

        // ── 0.16.0 增量词典 ──────────────────────────────────────────
        // 中文应用名。刻意不收「服务」「中心」「管理」这类过泛的词：
        // 「生活服务」「支付中心」一旦被它们抢先命中，理由就站不住脚。
        labelHint("社交", "微信", "QQ", "微博", "聊天", "社区", "论坛", "贴吧", "小红书",
                "知乎", "脉脉", "即时通讯", "通讯录", "朋友圈", "熟人");
        labelHint("影音", "音乐", "视频", "影视", "播放器", "收音机", "电台", "铃声",
                "直播", "电视", "短剧", "有声", "听书", "点播");
        labelHint("游戏", "游戏", "电竞", "对战平台", "游戏空间");
        labelHint("购物", "商城", "购物", "淘宝", "天猫", "京东", "拼多多", "唯品会",
                "闲鱼", "二手", "优惠", "秒杀", "比价", "海淘");
        labelHint("金融", "银行", "支付", "钱包", "证券", "股票", "基金", "理财", "保险",
                "信用卡", "记账", "缴费", "充值", "数字人民币", "收银");
        labelHint("出行", "地图", "导航", "打车", "公交", "地铁", "火车", "机票", "车票",
                "出行", "车主", "加油", "停车", "骑行", "乘车码", "航班", "订票");
        labelHint("生活", "天气", "外卖", "点餐", "美食", "买菜", "健康", "运动", "健身",
                "计步", "智能家居", "家居", "家电", "遥控", "快递", "家政", "食谱", "睡眠");
        labelHint("办公学习", "办公", "文档", "笔记", "邮箱", "邮件", "翻译", "词典",
                "单词", "学习", "课程", "题库", "会议", "识字", "扫描件", "简历");
        labelHint("工具", "设置", "计算器", "日历", "时钟", "录音", "文件管理", "文件",
                "下载", "浏览器", "输入法", "应用商店", "商店", "主题", "壁纸", "手机管家",
                "安全中心", "清理", "权限", "双开", "分身", "便签", "备忘录", "联系人",
                "短信", "电话", "拨号", "指南针", "手电筒", "压缩包", "云盘", "网盘",
                "互传", "备份", "恢复", "传输助手");
        labelHint("开发·Root", "开发者", "模块", "终端", "抓包", "代理", "调试",
                "超级用户", "密钥认证", "内核", "刷机", "框架");
        labelHint("摄影", "相机", "相册", "图库", "拍照", "修图", "美颜", "扫描仪",
                "证件照", "滤镜");
        labelHint("资讯", "新闻", "资讯", "头条", "阅读", "小说", "漫画", "杂志", "报刊");
        labelHint("系统", "系统更新", "系统服务", "启动器", "查找设备", "恢复模式");

        // 包名英文段。厂商前缀（coloros / oplus / heytap / nearme / realme / oppo）
        // 一律不写进这里——它们只说明"谁做的"，不说明"干什么"。
        seg("摄影", "camera", "cameras", "gallery", "galleries", "photo", "photos",
                "image", "imageeditor", "scanner", "scan", "beauty", "makeup");
        seg("影音", "music", "musics", "video", "videos", "player", "mediaplayer",
                "radio", "fmradio", "ringtone", "theater", "cinema", "karaoke");
        seg("金融", "wallet", "payment", "banking", "bank", "stock", "fund",
                "insurance", "finance", "creditcard");
        seg("出行", "maps", "navigation", "navi", "taxi", "travel", "train",
                "flight", "traffic", "metro", "bus", "ticket", "vehicle", "carowner");
        seg("社交", "messenger", "messaging", "messages", "chat", "social",
                "community", "forum", "moments", "contacts", "mms", "dialer");
        seg("游戏", "games", "gamecenter", "gameassistant", "gamespace", "esports");
        seg("购物", "shopping", "mall", "auction", "resale", "coupon", "idlefish");
        seg("资讯", "news", "reader", "article", "magazine", "comic", "novel", "headline");
        seg("生活", "weather", "health", "fitness", "pedometer", "smarthome",
                "iot", "recipe", "takeout", "delivery", "express", "remote");
        seg("办公学习", "office", "docs", "mail", "email", "dictionary",
                "translate", "study", "learning", "course", "homework", "meeting");
        seg("开发·Root", "magisk", "shizuku", "termux", "xposed", "root",
                "adb", "vpn", "proxy", "clash", "shell", "terminal", "debug", "developer");
        seg("工具", "calculator", "calendar", "deskclock", "clock", "recorder",
                "soundrecorder", "filemanager", "fileexplorer", "compass", "flashlight",
                "torch", "notes", "note", "memo", "settings", "browser", "download",
                "downloads", "market", "appstore", "store", "theme", "themes",
                "wallpaper", "security", "cleaner", "netdisk", "zip", "transfer",
                "inputmethod", "ime", "backup", "clone", "dualapp");
        seg(GROUP_SYSTEM, "systemui", "launcher", "framework", "updater", "ota",
                "systemservice", "fingerprint", "faceservice");

        // ── 0.16.1：按真机桌面截图补的点名与词 ──────────────────────────
        // 这些名字按关键词判会跑偏，只能点名。全部是「旧规则没命中时」才轮到，
        // 且 EXACT 包名表仍排在最前，小米原有分组不受影响。
        labelExact("工具", "aurora store", "华为应用市场", "应用市场", "应用商店",
                "主题商店", "软件商店", "oppo 商店", "游戏中心");
        labelExact("开发·Root", "lsp store", "lsposed manager", "jamesdsp",
                "maple检测", "ota 提取器", "ota提取器");
        labelExact("金融", "指纹支付", "个人所得税", "网上国网", "中国工商银行", "中国移动");
        labelExact("出行", "高德默认路线", "捷途汽车", "铁路12306");
        labelExact("影音", "红果免费短剧", "西瓜视频", "ace短剧标签");
        labelExact("游戏", "王者营地", "无畏契约", "三角洲行动", "高能英雄",
                "使命召唤", "盖世游戏", "游民星空");
        labelExact("摄影", "摄影现场助手", "光影边框app", "doka相机");
        labelExact("生活", "小睡眠", "京东秒送骑士版", "秒送骑士版");

        labelHint("工具", "搬家", "剪贴板", "过滤器", "提取器", "应用市场",
                "电池", "内存", "加速器");
        labelHint("资讯", "游民", "星空", "攻略");
        labelHint("影音", "短剧", "西瓜", "红果", "剧场");
        labelHint("游戏", "王者", "契约", "三角洲", "高能", "营地", "召唤", "精英");
        labelHint("金融", "国网", "工商银行", "个人所得税", "指纹支付");
        labelHint("出行", "汽车", "路线", "12306", "充电");
        labelHint("摄影", "摄影", "边框", "光影");
        labelHint("开发·Root", "检测", "dsp", "lsp", "调试器", "模块仓库");
        labelHint("生活", "骑士", "秒送");
    }

    private static void labelExact(String group, String... names) {
        for (String n : names) {
            LABEL_EXACT.put(n.toLowerCase(Locale.US), group);
        }
    }

    private static void labelHint(String group, String... words) {
        for (String w : words) {
            LABEL_HINTS.put(w, group);
        }
    }

    private static void seg(String group, String... words) {
        for (String w : words) {
            SEGMENT_GROUP.put(w, group);
        }
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
            AppEntry e = new AppEntry(label, ai.packageName, ri.activityInfo.name, sys, icon);
            e.pmLabel = label;
            e.category = ai.category;
            out.add(e);
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

    /** 落在「其它」＝这台机器上没能认出类别，界面要如实报数。 */
    public static boolean isUnknown(String group) {
        return GROUP_OTHER.equals(group);
    }

    /**
     * 从桌面库的一条记录构造分类用的入口。
     *
     * <p>桌面库的 title 经常不可靠：真我 / ColorOS 上可能是空串、包名或资源引用，
     * 小米的自动分类文件夹标题则是 {@code com.miui.home:string/xxx}。所以一定再向
     * PackageManager 要一次应用名与 category，交给增量词典兜底。取不到就留空，不影响旧规则。
     */
    /**
     * 进程内缓存。一次「整理」里同一个包名要被问三遍（两个方案的组数预览 + 真正排屏），
     * 每次都走一遍 PackageManager 的 binder 没必要。缓存只在 {@link #clearPmCache()} 时失效，
     * 「重新分析」会调它，因此中途装了新 App 也不会拿到旧名字。
     */
    private static final Map<String, PmInfo> PM_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class PmInfo {
        boolean system;
        String label = "";
        int category = -1;
    }

    public static void clearPmCache() {
        PM_CACHE.clear();
    }

    public static AppEntry fromDesktop(PackageManager pm, String title, String pkg) {
        PmInfo info = null;
        if (pm != null && pkg != null && !pkg.isEmpty()) {
            info = PM_CACHE.get(pkg);
            if (info == null) {
                PmInfo fresh = new PmInfo();
                ApplicationInfo ai = null;
                try {
                    ai = pm.getApplicationInfo(pkg, 0);
                } catch (Throwable ignored) {
                }
                if (ai != null) {
                    fresh.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                    fresh.category = ai.category;
                    try {
                        CharSequence l = pm.getApplicationLabel(ai);
                        if (l != null) {
                            fresh.label = l.toString();
                        }
                    } catch (Throwable ignored) {
                    }
                }
                PM_CACHE.put(pkg, fresh);
                info = fresh;
            }
        }
        AppEntry e = new AppEntry(title, pkg, "", info != null && info.system, null);
        if (info != null) {
            e.pmLabel = info.label;
            e.category = info.category;
        }
        return e;
    }

    /** 分组与方案卡预览共用同一个入口，避免两处判据慢慢分家。 */
    public static String groupByFunction(PackageManager pm, String title, String pkg) {
        return groupByFunction(fromDesktop(pm, title, pkg));
    }

    public static String groupByFunction(AppEntry e) {
        String pkg = lower(e.pkg);
        String label = lower(e.label);
        String hit = firstExactHit(pkg);
        if (!hit.isEmpty()) {
            return hit;
        }

        // ⓪ 应用名点名：抢在 0.14.1 的关键词之前，修「store / market」把应用商店
        //    判成购物、把 LSPosed 模块仓库判成电商的老问题。只认精确名字，不扩散。
        String byName = LABEL_EXACT.get(label.trim());
        if (byName == null) {
            byName = LABEL_EXACT.get(lower(e.pmLabel).trim());
        }
        if (byName != null) {
            return byName;
        }

        // ① 0.14.1 原有关键词：只看原始 label，行为逐字保持，小米分组不受新规则影响。
        for (Map.Entry<String, String[]> en : HINTS.entrySet()) {
            for (String h : en.getValue()) {
                if (pkg.contains(h) || label.contains(h)) {
                    return en.getKey();
                }
            }
        }

        // ② 中文应用名（含 PM 补出的名字）。真我预装大多靠这一层认出来。
        String names = label + " " + lower(e.pmLabel);
        for (Map.Entry<String, String> en : LABEL_HINTS.entrySet()) {
            if (names.contains(en.getKey())) {
                return en.getValue();
            }
        }

        // ③ 包名英文段整段匹配。厂商前缀不参与，只认 calculator / camera / wallet 这类功能后缀。
        Set<String> segments = segments(pkg + " " + names);
        for (Map.Entry<String, String> en : SEGMENT_GROUP.entrySet()) {
            if (segments.contains(en.getKey())) {
                return en.getValue();
            }
        }

        // ④ 系统在 ApplicationInfo 里声明的类别（API 26+）。多数国产预装没声明，有就是硬证据。
        String byCategory = groupByCategory(e.category);
        if (!byCategory.isEmpty()) {
            return byCategory;
        }

        // ⑤ 认不出的预装统一进「系统」，至少比塞进一个几十图标的「其它」有语义。
        if (e.system) {
            return GROUP_SYSTEM;
        }
        return GROUP_OTHER;
    }

    private static String groupByCategory(int category) {
        switch (category) {
            case ApplicationInfo.CATEGORY_GAME:
                return "游戏";
            case ApplicationInfo.CATEGORY_AUDIO:
            case ApplicationInfo.CATEGORY_VIDEO:
                return "影音";
            case ApplicationInfo.CATEGORY_IMAGE:
                return "摄影";
            case ApplicationInfo.CATEGORY_SOCIAL:
                return "社交";
            case ApplicationInfo.CATEGORY_NEWS:
                return "资讯";
            case ApplicationInfo.CATEGORY_MAPS:
                return "出行";
            case ApplicationInfo.CATEGORY_PRODUCTIVITY:
                return "办公学习";
            default:
                return "";
        }
    }

    /** 按非字母数字切段，整串也放进去；短词的误判就是靠这层挡掉的。 */
    private static Set<String> segments(String s) {
        Set<String> out = new HashSet<>();
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

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.US);
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
