package io.github.cobylinweiqi.deskly.organize;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.2「预览说 2、3 屏，桌面却全在第一屏」的回归保护。
 *
 * <p>旧版把文件夹一律当 2×2（一屏 6 个），但 HyperOS / 真我桌面把文件夹当
 * 普通图标排（4 列 × 6 行，一屏 24 个）。13 个分类文件夹于是被摊到 3 屏，
 * 而桌面一屏就装下了。现在占位从库里现有文件夹的坐标探测，
 * 排屏容量、写回 span、预览文案共用同一份值。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderSpanTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void oneCellGridHas24Slots() {
        assertEquals(24, LayoutPlan.slotsForSpan(1));
        assertEquals(6, LayoutPlan.slotsForSpan(2));
        assertEquals(0, LayoutPlan.cellX(0, 1));
        assertEquals(3, LayoutPlan.cellX(3, 1));
        assertEquals(1, LayoutPlan.cellY(4, 1));
        assertEquals(2, LayoutPlan.cellX(1, 2));
        assertEquals(2, LayoutPlan.cellY(2, 2));
    }

    /** 13 个分类文件夹在 1 格网格里就是 1 屏；旧 2×2 假设会摊成 3 屏。 */
    @Test
    public void thirteenGroupsFitOneScreenOnOneCellGrid() {
        LayoutPlan one = LayoutPlan.build(groups(13), false, null, null, 0, 1);
        assertEquals(13, one.folderCount);
        assertEquals(1, one.folderSpan);
        assertEquals(1, one.maxNumber());
        assertEquals(24, one.screens.get(0).capacity);

        LayoutPlan two = LayoutPlan.build(groups(13), false, null, null, 0, 2);
        assertEquals(13, two.folderCount);
        assertEquals(3, two.maxNumber());
    }

    /** 直接调用老签名仍按 2×2，既有行为不回归。 */
    @Test
    public void legacyBuildKeepsTwoByTwo() {
        LayoutPlan p = LayoutPlan.build(groups(13), false, null, null, 0);
        assertEquals(3, p.maxNumber());
        assertEquals(2, p.folderSpan);
    }

    /** fixture 的文件夹是 2×2 且分散在不同行，探测结果应保持 2。 */
    @Test
    public void fixtureDetectsTwoByTwo() throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        DesktopModel m = DesktopModel.read(db, ctx, "com.miui.home");
        assertEquals(2, m.folderSpan);
    }

    /** 桌面把文件夹按 4 列连续排（真机截图）→ 探测为 1 格。 */
    @Test
    public void denseFolderRowDetectsOneCell() throws Exception {
        DesktopModel m = DesktopModel.read(denseDb(), ctx, "com.android.launcher");
        assertEquals(1, m.folderSpan);
    }

    /** 探测值要一路带到方案里，写库时用的是它而不是 prof.isMiui()?2:1。 */
    @Test
    public void planCarriesDetectedSpan() throws Exception {
        DesktopModel m = DesktopModel.read(denseDb(), ctx, "com.android.launcher");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION, false, null);
        assertEquals(1, p.folderSpan);
        assertTrue("应当有参与整理的图标", p.total > 0);
        assertTrue("1 格网格下 1 屏就够", p.screenCount <= 1);
    }

    private File denseDb() throws Exception {
        File f = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        SQLiteDatabase w = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            for (int i = 0; i < 4; i++) {
                w.execSQL("INSERT INTO favorites "
                        + "(_id,title,container,screen,cellX,cellY,spanX,spanY,itemType) VALUES "
                        + "(" + (800 + i) + ",'连续文件夹" + i + "',-100,0,"
                        + i + ",0,1,1,2)");
            }
        } finally {
            w.close();
        }
        return f;
    }

    private static Map<String, List<AppEntry>> groups(int n) {
        Map<String, List<AppEntry>> g = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            List<AppEntry> list = new ArrayList<>();
            list.add(new AppEntry("应用" + i, "com.example.a" + i, "", false, null));
            g.put("组" + i, list);
        }
        return g;
    }
}
