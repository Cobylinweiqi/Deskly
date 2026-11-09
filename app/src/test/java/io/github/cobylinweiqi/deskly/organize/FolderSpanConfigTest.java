package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.4「有些小米手机分类后文件夹堆在一起」的回归保护。
 *
 * <p>根因不是分类，是占位：桌面原本没有文件夹时旧版默认按 1 格排，而那台桌面
 * 渲染成 2×2 大文件夹，相邻方块压掉一半；更糟的是第一次写错之后，下次「重新分析」
 * 读到的是我们自己写的 1 格密排坐标，又判成 1，永久固化。
 *
 * <p>这里钉死四件事：无样本保守按 2、脚本产物不算证据、手动开关优先级最高、
 * 上次写回校验值优先于一次可疑的探测。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderSpanConfigTest {

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    // ───────────────────────── 配置优先级 ─────────────────────────

    @Test
    public void autoUsesDetectedValue() {
        PlanConfig c = new PlanConfig();
        assertEquals(1, c.effectiveFolderSpan(1));
        assertEquals(2, c.effectiveFolderSpan(2));
        assertEquals("坐标探测", c.spanSource(1));
    }

    /** 没有可信样本时按 2×2 排：宁可稀疏多分一屏，也不要让方块互相压住。 */
    @Test
    public void noSampleFallsBackToBigFolder() {
        PlanConfig c = new PlanConfig();
        assertEquals(2, c.effectiveFolderSpan(0));
        assertEquals(2, c.effectiveFolderSpan(-1));
        assertEquals("无样本·保守默认", c.spanSource(0));
    }

    /** 上次写回后桌面真实按 2 格排，比这次偶然读到的 1 格密排更可信。 */
    @Test
    public void verifiedSpanBeatsDetection() {
        PlanConfig c = new PlanConfig();
        c.recordVerifiedSpan(2);
        assertEquals(2, c.effectiveFolderSpan(1));
        assertEquals("上次写回校验", c.spanSource(1));

        PlanConfig d = new PlanConfig();
        d.recordVerifiedSpan(1);
        assertEquals(1, d.effectiveFolderSpan(2));
    }

    /** 手动指定压过一切自动判断，用户看到的屏数必须立刻变。 */
    @Test
    public void manualModeOverridesEverything() {
        PlanConfig small = new PlanConfig();
        small.folderSpanMode = PlanConfig.SPAN_SMALL;
        small.recordVerifiedSpan(2);
        assertEquals(1, small.effectiveFolderSpan(2));
        assertEquals("手动指定", small.spanSource(2));

        PlanConfig big = new PlanConfig();
        big.folderSpanMode = PlanConfig.SPAN_BIG;
        big.recordVerifiedSpan(1);
        assertEquals(2, big.effectiveFolderSpan(1));
        assertEquals("手动指定", big.spanSource(1));
    }

    @Test
    public void recordVerifiedSpanRejectsGarbage() {
        PlanConfig c = new PlanConfig();
        c.recordVerifiedSpan(0);
        c.recordVerifiedSpan(3);
        c.recordVerifiedSpan(-1);
        assertEquals(0, c.verifiedFolderSpan);
        c.recordVerifiedSpan(2);
        assertEquals(2, c.verifiedFolderSpan);
    }

    @Test
    public void configRoundTripKeepsSpanSettings() {
        PlanConfig c = new PlanConfig();
        c.folderSpanMode = PlanConfig.SPAN_BIG;
        c.recordVerifiedSpan(2);
        c.save(ctx);
        PlanConfig back = PlanConfig.load(ctx);
        assertEquals(PlanConfig.SPAN_BIG, back.folderSpanMode);
        assertEquals(2, back.verifiedFolderSpan);

        // 坏数据不能把整个配置读崩
        ctx.getSharedPreferences("deskly_plan", Context.MODE_PRIVATE).edit()
                .putInt("folder_span_mode", 99)
                .putInt("folder_span_verified", 7)
                .commit();
        PlanConfig fallback = PlanConfig.load(ctx);
        assertEquals(PlanConfig.SPAN_AUTO, fallback.folderSpanMode);
        assertEquals(0, fallback.verifiedFolderSpan);
    }

    // ───────────────────────── 探测层 ─────────────────────────

    @Test
    public void emptyLibraryIsUnknown() {
        assertEquals(0, DesktopModel.detectFolderSpan(new ArrayList<>()));
    }

    /** 只有一个小文件夹时，坐标间距和 span 都不足以证明「桌面按 1 格排」。 */
    @Test
    public void singleSmallFolderIsUnknown() {
        List<DesktopModel.Folder> fs = new ArrayList<>();
        fs.add(folder("工具", 0, 0, 1));
        assertEquals(0, DesktopModel.detectFolderSpan(fs));
    }

    /** 单个明确写成 2×2 的文件夹仍可作为大文件夹证据（小米旧路径不回归）。 */
    @Test
    public void singleBigFolderStillDetectsTwo() {
        List<DesktopModel.Folder> fs = new ArrayList<>();
        fs.add(folder("文件夹A", 0, 0, 2));
        assertEquals(2, DesktopModel.detectFolderSpan(fs));
    }

    /**
     * 上次写回的产物：整批内置组名 + 1×1 + 相邻坐标。旧版会据此判 1 并永久固化，
     * 现在必须返回 0 交给上层用校验值 / 保守默认。
     */
    @Test
    public void syntheticDenseLayoutIsNotEvidence() {
        String[] names = {"社交", "影音", "游戏", "购物", "金融", "工具"};
        List<DesktopModel.Folder> fs = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            fs.add(folder(names[i], i, 0, 1));
        }
        assertEquals(0, DesktopModel.detectFolderSpan(fs));
    }

    /** 用户自己摆的连续小文件夹仍是 1 格网格的有效证据（0.16.2 行为不回归）。 */
    @Test
    public void naturalDenseLayoutStillDetectsOne() {
        List<DesktopModel.Folder> fs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            fs.add(folder("我的文件夹" + i, i, 0, 1));
        }
        assertEquals(1, DesktopModel.detectFolderSpan(fs));
    }

    /** 2×2 且分散在不同行：span 众数生效，判 2。 */
    @Test
    public void naturalBigFoldersStillDetectTwo() {
        List<DesktopModel.Folder> fs = new ArrayList<>();
        fs.add(folder("文件夹A", 0, 0, 2));
        fs.add(folder("文件夹B", 2, 2, 2));
        assertEquals(2, DesktopModel.detectFolderSpan(fs));
    }

    /** 内置组名但按 2×2 写回的布局不该被误判成脚本产物：span 是硬证据。 */
    @Test
    public void builtinNamesWithBigSpansStillDetectTwo() {
        List<DesktopModel.Folder> fs = new ArrayList<>();
        fs.add(folder("社交", 0, 0, 2));
        fs.add(folder("影音", 2, 2, 2));
        assertEquals(2, DesktopModel.detectFolderSpan(fs));
    }

    // ───────────────────────── 排屏结果 ─────────────────────────

    /** 手动切到 2×2 后，坐标步长与每屏容量必须一起变。 */
    @Test
    public void manualBigChangesCoordinatesAndCapacity() {
        assertEquals(24, LayoutPlan.slotsForSpan(1));
        assertEquals(1, LayoutPlan.cellX(1, 1));
        assertEquals(6, LayoutPlan.slotsForSpan(2));
        assertEquals(2, LayoutPlan.cellX(1, 2));
        assertEquals(2, LayoutPlan.cellY(2, 2));
        assertTrue(LayoutPlan.build(new java.util.LinkedHashMap<>(), false).folderSpan > 0);
    }

    private static DesktopModel.Folder folder(String title, int cellX, int cellY, int span) {
        DesktopModel.Folder f = new DesktopModel.Folder();
        f.title = title;
        f.container = DesktopModel.CONTAINER_DESKTOP;
        f.screen = 1;
        f.cellX = cellX;
        f.cellY = cellY;
        f.spanX = span;
        f.spanY = span;
        return f;
    }
}
