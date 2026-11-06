package io.github.cobylinweiqi.deskly.organize;

import android.content.pm.ApplicationInfo;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 功能分类的回归保护。
 *
 * <p>0.16.0 的词典是<b>纯增量</b>的：旧 EXACT / HINTS 的顺序一个字没动，新规则只在
 * 旧规则全不中时接手。所以最重要的断言不是「新词能命中」，而是「小米那一百多个
 * 精确包名的分组结果一个都没变」。
 */
public class AppClassifierTest {

    private static AppEntry app(String label, String pkg) {
        return new AppEntry(label, pkg, "", false, null);
    }

    private static String group(String label, String pkg) {
        return AppClassifier.groupByFunction(app(label, pkg));
    }

    /** 反射遍历整张精确表；加新词典时只要有一条被带偏，这里立刻红。 */
    @Test
    @SuppressWarnings("unchecked")
    public void exactTableNeverRegresses() throws Exception {
        Field f = AppClassifier.class.getDeclaredField("EXACT");
        f.setAccessible(true);
        Map<String, String[]> exact = (Map<String, String[]>) f.get(null);
        int n = 0;
        for (Map.Entry<String, String[]> e : exact.entrySet()) {
            for (String pkg : e.getValue()) {
                assertEquals("精确包名 " + pkg + " 的组别变了", e.getKey(), group("", pkg));
                n++;
            }
        }
        assertTrue("精确表应当覆盖 100 条以上，实际只有 " + n, n > 100);
    }

    /** 小米 0.14.1 真机上的典型应用，逐条钉死。 */
    @Test
    public void miuiTypicalAppsKeepTheirGroup() {
        assertEquals("社交", group("微信", "com.tencent.mm"));
        assertEquals("金融", group("支付宝", "com.eg.android.alipaygphone"));
        assertEquals("购物", group("淘宝", "com.taobao.taobao"));
        assertEquals("影音", group("哔哩哔哩", "tv.danmaku.bili"));
        assertEquals("游戏", group("王者荣耀", "com.tencent.tmgp.sgame"));
        assertEquals("出行", group("高德地图", "com.autonavi.minimap"));
        assertEquals("工具", group("设置", "com.android.settings"));
        assertEquals("开发·Root", group("Magisk", "com.topjohnwu.magisk"));
    }

    /** 真我 / ColorOS 预装：厂商前缀不在词典里，靠功能后缀的整段匹配认出来。 */
    @Test
    public void oemPackagesGetRealGroups() {
        assertEquals("工具", group("", "com.oplus.deskclock"));
        assertEquals("工具", group("", "com.heytap.wallpaper"));
        assertEquals("工具", group("", "com.coloros.memo"));
        assertEquals("生活", group("", "com.coloros.pedometer"));
        assertEquals("资讯", group("", "com.nearme.novel"));
        assertEquals("金融", group("", "com.realme.creditcard"));
        assertEquals("社交", group("", "com.oplus.messaging"));
        assertEquals("摄影", group("", "com.oplus.camera"));
    }

    /** 桌面库 title 常常只是中文应用名，甚至没有包名：这一层要能独立生效。 */
    @Test
    public void chineseLabelsGetRealGroups() {
        assertEquals("工具", group("应用商店", ""));
        assertEquals("工具", group("手机管家", ""));
        assertEquals("工具", group("备忘录", ""));
        assertEquals("金融", group("钱包", ""));
        assertEquals("生活", group("健身", ""));
        assertEquals("摄影", group("图库", ""));
        assertEquals("资讯", group("漫画", ""));
    }

    /** title 为空时，从 PackageManager 补出的应用名要能接上。 */
    @Test
    public void pmLabelFillsEmptyDbTitle() {
        AppEntry e = app("", "com.oplus.unknownapp");
        e.pmLabel = "相机";
        assertEquals("摄影", AppClassifier.groupByFunction(e));

        AppEntry e2 = app("", "com.oplus.unknownapp2");
        e2.pmLabel = "天气";
        assertEquals("生活", AppClassifier.groupByFunction(e2));
    }

    /** 系统声明的 category 是硬证据，词典全不中时用它。 */
    @Test
    public void applicationInfoCategoryIsUsedAsFallback() {
        AppEntry game = app("", "com.some.cold.xyz");
        game.category = ApplicationInfo.CATEGORY_GAME;
        assertEquals("游戏", AppClassifier.groupByFunction(game));

        AppEntry maps = app("", "com.some.cold.zyx");
        maps.category = ApplicationInfo.CATEGORY_MAPS;
        assertEquals("出行", AppClassifier.groupByFunction(maps));
    }

    /** 认不出的预装进「系统」，不再堆进一个巨大的「其它」。 */
    @Test
    public void unknownSystemAppsGoToSystemGroup() {
        AppEntry e = new AppEntry("", "com.oem.internal.service", "", true, null);
        assertEquals("系统", AppClassifier.groupByFunction(e));
    }

    /** 冷门第三方不硬凑，诚实落「其它」，界面会报出这个数。 */
    @Test
    public void coldThirdPartyStaysUnknown() {
        String g = group("某个冷门应用", "com.coby.tinyxyz");
        assertEquals("其它", g);
        assertTrue(AppClassifier.isUnknown(g));
        assertFalse(AppClassifier.isUnknown("工具"));
    }

    /** 英文段必须整段匹配：memopro 不能被 memo 拖进「工具」。 */
    @Test
    public void segmentHintsRequireWholeSegment() {
        assertEquals("工具", group("", "com.oplus.memo"));
        assertEquals("其它", group("", "com.oplus.memopro"));
    }

    /** 空数据不能炸，且落到兜底组。 */
    @Test
    public void emptyInputIsSafe() {
        assertEquals("其它", AppClassifier.groupByFunction(new AppEntry(null, null, "", false, null)));
        assertEquals("其它", group("", ""));
    }

    /** 分桶结果里不能有空组，组顺序仍按 ORDER。 */
    @Test
    public void classifyDropsEmptyBuckets() {
        java.util.List<AppEntry> apps = new java.util.ArrayList<>();
        apps.add(app("微信", "com.tencent.mm"));
        apps.add(app("", "com.oplus.deskclock"));
        Map<String, java.util.List<AppEntry>> buckets = AppClassifier.classify(apps);
        assertFalse(buckets.isEmpty());
        for (Map.Entry<String, java.util.List<AppEntry>> e : buckets.entrySet()) {
            assertFalse("出现空组 " + e.getKey(), e.getValue().isEmpty());
        }
        assertEquals(2, buckets.size());
        assertTrue(buckets.containsKey("社交"));
        assertTrue(buckets.containsKey("工具"));
    }

    /** 0.16.1：真机桌面截图里的冷门应用，不该再整批落「其它」。 */
    @Test
    public void screenshotColdAppsGetRealGroups() {
        assertEquals("金融", group("个人所得税", ""));
        assertEquals("金融", group("指纹支付", ""));
        assertEquals("金融", group("中国工商银行", ""));
        assertEquals("金融", group("网上国网", ""));
        assertEquals("出行", group("捷途汽车", ""));
        assertEquals("出行", group("高德默认路线", ""));
        assertEquals("影音", group("红果免费短剧", ""));
        assertEquals("影音", group("西瓜视频", ""));
        assertEquals("游戏", group("王者营地", ""));
        assertEquals("游戏", group("无畏契约", ""));
        assertEquals("游戏", group("三角洲行动", ""));
        assertEquals("游戏", group("游民星空", ""));
        assertEquals("摄影", group("摄影现场助手", ""));
        assertEquals("工具", group("手机搬家", ""));
        assertEquals("工具", group("剪贴板过滤器", ""));
        assertEquals("开发·Root", group("Maple检测", ""));
        assertEquals("开发·Root", group("JamesDSP", ""));
        assertEquals("生活", group("秒送骑士版", ""));
    }

    /** store / market 关键词不能把应用商店、LSPosed 模块仓库判成购物。 */
    @Test
    public void labelExactBeatsStoreKeyword() {
        assertEquals("开发·Root", group("LSP Store", "com.some.lspstore"));
        assertEquals("工具", group("Aurora Store", "com.aurora.store"));
        assertEquals("工具", group("华为应用市场", "com.huawei.appmarket"));
        assertEquals("工具", group("主题商店", "com.oplus.themestore"));
        assertEquals("工具", group("应用商店", ""));
    }

    /** 截图里那批主流 App 本来就在精确表里：没进文件夹不是词典的锅。 */
    @Test
    public void screenshotMainstreamAppsAreInExactTable() {
        assertEquals("购物", group("京东", "com.jingdong.app.mall"));
        assertEquals("购物", group("拼多多", "com.xunmeng.pinduoduo"));
        assertEquals("购物", group("唯品会", "com.achievo.vipshop"));
        assertEquals("购物", group("闲鱼", "com.taobao.idlefish"));
        assertEquals("影音", group("抖音", "com.ss.android.ugc.aweme"));
        assertEquals("影音", group("爱奇艺", "com.qiyi.video"));
        assertEquals("影音", group("快手", "com.smile.gifmaker"));
        assertEquals("影音", group("网易云音乐", "com.netease.cloudmusic"));
        assertEquals("金融", group("中国银行", "com.chinamworld.main"));
        assertEquals("金融", group("云闪付", "com.unionpay"));
        assertEquals("出行", group("铁路12306", "com.mobileticket"));
    }
}
