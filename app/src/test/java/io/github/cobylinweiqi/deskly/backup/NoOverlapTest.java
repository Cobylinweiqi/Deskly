package io.github.cobylinweiqi.deskly.backup;

import android.content.Context;
import android.database.Cursor;
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
import java.util.List;

import io.github.cobylinweiqi.deskly.organize.AppClassifier;
import io.github.cobylinweiqi.deskly.organize.DeskPlan;
import io.github.cobylinweiqi.deskly.organize.DesktopModel;
import io.github.cobylinweiqi.deskly.organize.LayoutPlan;
import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.5「选 1×1 / 2×2，并且不许文件夹重叠」的回归保护。
 *
 * <p>两件真机上出过的事必须钉死：
 * <ol>
 *   <li>界面上选的尺寸在写库路径被丢掉：{@code doApply} 会重读一份 fresh 桌面，
 *       那份带的是<b>探测原始值</b>，预览按 2×2 排、写进去却是 1×1 密排；</li>
 *   <li>删不掉的旧文件夹（里面有非应用成员）留在原坐标，而新文件夹又从每屏左上角
 *       开始排，新旧两个方块占同一格。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class NoOverlapTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    private File db() throws Exception {
        return LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
    }

    /** 把方案里所有文件夹还原成占格矩形，供两两相交校验。 */
    private static List<DeskPlan.Rect> rects(DeskPlan p) {
        List<DeskPlan.Rect> out = new ArrayList<>();
        for (DeskPlan.Bucket b : p.buckets) {
            out.add(new DeskPlan.Rect(b.screen, b.cellX, b.cellY, p.folderSpan, p.folderSpan));
        }
        return out;
    }

    /** fixture 第 1 屏有个 2×2 组件压住 (2,0)..(3,1)。 */
    private static final DeskPlan.Rect CALENDAR = new DeskPlan.Rect(1, 2, 0, 2, 2);

    private static void assertCollisionFree(DeskPlan p) {
        List<DeskPlan.Rect> rs = rects(p);
        assertFalse("应当排出了文件夹", rs.isEmpty());
        assertEquals("方案自校验的重叠对数", 0, p.overlapCount);
        assertEquals("矩形两两相交的对数", 0, DeskPlan.countOverlaps(rs));
        for (DeskPlan.Rect r : rs) {
            assertTrue("文件夹不能越出 4×6 网格：" + r.x + "," + r.y, r.inGrid());
            assertFalse("文件夹不能压住第 1 屏的日历组件", r.intersects(CALENDAR));
        }
    }

    // ───────────────────────── 尺寸选择 ─────────────────────────

    /**
     * 探测说 2×2，但调用方显式传 1 就必须按 1 排。
     * 这是写库路径丢手动值的直接回归点。
     */
    @Test
    public void explicitSpanOverridesDetectedValue() throws Exception {
        DesktopModel m = DesktopModel.read(db(), ctx, "com.miui.home");
        assertEquals(2, m.folderSpan);

        DeskPlan small = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 1);
        assertEquals(1, small.folderSpan);
        assertCollisionFree(small);

        DeskPlan big = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);
        assertEquals(2, big.folderSpan);
        assertCollisionFree(big);

        // 生效尺寸必须真的换掉「这一屏剩哪几个候选位」，而不只是改个显示文案。
        // fixture 第 1 屏有个 2×2 日历：1 格网格有 24 个位、压掉 4 个剩 20；
        // 2×2 网格只有 6 个位、压掉 1 个剩 5。
        assertEquals("1×1 模式的可用位", 20, small.slots.get(1).length);
        assertEquals("2×2 模式的可用位", 5, big.slots.get(1).length);
    }

    /** 老调用方不传尺寸时沿用探测值，行为不回归。 */
    @Test
    public void legacyBuildStillUsesDetectedSpan() throws Exception {
        DesktopModel m = DesktopModel.read(db(), ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION, false, null);
        assertEquals(2, p.folderSpan);
        assertCollisionFree(p);
    }

    // ───────────────────────── 避让与分屏 ─────────────────────────

    /** 第 1 屏被一个 4×6 组件占满时，文件夹必须整体挪到后面的屏，不能硬塞进去。 */
    @Test
    public void fullyBlockedScreenSpillsToNextScreen() throws Exception {
        File f = db();
        SQLiteDatabase w = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            w.execSQL("UPDATE favorites SET cellX=0,cellY=0,spanX=4,spanY=6 "
                    + "WHERE itemType=19 AND container=-100");
        } finally {
            w.close();
        }
        DesktopModel m = DesktopModel.read(f, ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);

        int[] free = p.slots.get(1);
        assertTrue("第 1 屏应当一个可用位都没有", free != null && free.length == 0);
        for (DeskPlan.Bucket b : p.buckets) {
            assertTrue("文件夹不能落在被占满的第 1 屏", b.screen > 1);
        }
        assertEquals(0, p.overlapCount);
        assertEquals(0, DeskPlan.countOverlaps(rects(p)));
        assertTrue("避让后应当多开一屏", p.screenCount >= 2);
    }

    /** 空候选位数组 = 容量 0，不能被 LayoutPlan 偷偷换回默认的 6 个。 */
    @Test
    public void emptySlotsMeanZeroCapacity() {
        java.util.Map<Integer, int[]> slots = new java.util.LinkedHashMap<>();
        slots.put(1, new int[0]);
        LayoutPlan p = LayoutPlan.build(groups(4), false, null, slots, 0, 2);
        LayoutPlan.Screen first = null;
        for (LayoutPlan.Screen s : p.screens) {
            if (s.number == 1) {
                first = s;
            }
        }
        assertTrue("第 1 屏仍应出现在预览里", first != null);
        assertEquals(0, first.capacity);
        assertEquals("四个组都该被挪到后面的屏", 0, first.folders.size());
    }

    // ───────────────────────── 旧文件夹残留 ─────────────────────────

    /**
     * 小米「工具」文件夹里那个 itemType=19 的系统入口会让写库层保护它不删。
     * 旧版把它留在原坐标，新文件夹又从 (0,0) 开始排，两个方块就叠在一起。
     * 现在它必须被整体搬到所有新文件夹之后的最后一屏。
     */
    @Test
    public void protectedOldFolderMovesToTrailingScreen() throws Exception {
        File f = db();
        final int folderId;
        SQLiteDatabase w = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            // fixture 的文件夹 id 是按插入顺序算出来的，别写死
            try (Cursor c = w.rawQuery("SELECT _id FROM favorites WHERE itemType=21"
                    + " AND container=-100 ORDER BY _id LIMIT 1", null)) {
                assertTrue("fixture 里应当有文件夹", c.moveToFirst());
                folderId = c.getInt(0);
            }
            w.execSQL("INSERT INTO favorites "
                    + "(_id,title,container,screen,cellX,cellY,spanX,spanY,itemType) VALUES "
                    + "(999,'查找'," + folderId + ",-1,0,0,1,1,19)");
        } finally {
            w.close();
        }
        DesktopModel m = DesktopModel.read(f, ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);

        assertTrue("旧文件夹应被识别为删不掉", p.strayFolderIds.contains(folderId));
        assertEquals("旧文件夹要挪到最后一屏",
                Integer.valueOf(p.screenCount), p.frozenScreens.get(folderId));
        assertTrue("写库层不能删它", p.keptFolderIds.contains(folderId));
        for (DeskPlan.Bucket b : p.buckets) {
            assertTrue("新文件夹不能落在旧文件夹那一屏", b.screen < p.screenCount);
        }
        assertCollisionFree(p);

        DeskWriter.Result r = new DeskWriter.Result();
        DeskWriter.rebuild(f, p, r, new ArrayList<Integer>());
        assertEquals("成员写入数", p.total, r.moved);

        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = check.rawQuery(
                "SELECT screen,cellX,cellY,spanX,spanY FROM favorites "
                        + "WHERE _id=" + folderId + " AND itemType=21", null)) {
            assertTrue("旧文件夹行必须还在", c.moveToFirst());
            assertEquals("旧文件夹的新屏号", p.screenCount, c.getInt(0));
        } finally {
            check.close();
        }
    }

    // ───────────────────────── 写库后的坐标 ─────────────────────────

    /** 真正写进库之后，回读出来的文件夹坐标也必须两两不相交。 */
    @Test
    public void rebuildWritesCollisionFreeCoordinates() throws Exception {
        File f = db();
        DesktopModel m = DesktopModel.read(f, ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);
        DeskWriter.Result r = new DeskWriter.Result();
        DeskWriter.rebuild(f, p, r, new ArrayList<Integer>());
        assertEquals(p.total, r.moved);
        assertCollisionFree(p);

        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        List<DeskPlan.Rect> back = new ArrayList<>();
        try (Cursor c = check.rawQuery(
                "SELECT screen,cellX,cellY,spanX,spanY FROM favorites "
                        + "WHERE itemType IN (" + p.profile.folderTypesIn() + ")"
                        + " AND container=-100", null)) {
            while (c.moveToNext()) {
                back.add(new DeskPlan.Rect(c.getInt(0), c.getInt(1), c.getInt(2),
                        Math.max(1, c.getInt(3)), Math.max(1, c.getInt(4))));
            }
        } finally {
            check.close();
        }
        assertEquals("库里文件夹行数", p.buckets.size(), back.size());
        assertEquals("库里文件夹重叠对数", 0, DeskPlan.countOverlaps(back));
        for (DeskPlan.Rect rect : back) {
            assertTrue("库里坐标不能越出 4×6 网格", rect.inGrid());
            assertFalse("库里坐标不能压住日历组件", rect.intersects(CALENDAR));
        }
    }

    private static java.util.Map<String, List<io.github.cobylinweiqi.deskly.organize.AppEntry>>
            groups(int n) {
        java.util.Map<String, List<io.github.cobylinweiqi.deskly.organize.AppEntry>> g =
                new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            List<io.github.cobylinweiqi.deskly.organize.AppEntry> list = new ArrayList<>();
            list.add(new io.github.cobylinweiqi.deskly.organize.AppEntry(
                    "应用" + i, "com.example.a" + i, "", false, null));
            g.put("组" + i, list);
        }
        return g;
    }
}
