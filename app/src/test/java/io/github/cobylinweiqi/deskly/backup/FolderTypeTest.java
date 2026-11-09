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
import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.6：小米 HyperOS 4 的文件夹尺寸编码在 {@code itemType} 上。
 *
 * <p>真机日志（MiuiHome 8.01.02.8602）里同一个桌面同时给出两种类型：
 * <pre>
 * type=userFolder(2)      (0,0,1-1):工具   ← 1×1 小文件夹
 * type=folder_2X2_4(21)   (0,2,2-2):购物   ← 2×2 四宫格大文件夹
 * </pre>
 * 0.16.5 及以前写库永远用探测到的 21，用户选「小 1×1」只改了 span，桌面照旧画 2×2，
 * 1 格步长的坐标立刻叠在一起。这里的断言只盯本方案写出的那些文件夹 id：
 * 库里可能还留着删不掉的旧文件夹（成员里有非应用条目），它的尺寸不归这次整理管。
 * <ol>
 *   <li>1×1 → itemType=2、span=1；</li>
 *   <li>2×2 → itemType=21、span=2；</li>
 *   <li>复用的旧 id 也必须切成新尺寸；</li>
 *   <li>写完再读，2 也必须被认成文件夹（否则下次整理会把它们当"其它条目"留在原地）。</li>
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class FolderTypeTest {

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

    /** 逐个检查本次写出的文件夹行：type 与 span 必须是这一档。 */
    private static void assertRows(File db, List<Integer> ids, int type, int span) {
        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertEquals("应当写出文件夹", true, !ids.isEmpty());
            for (int id : ids) {
                try (Cursor c = check.rawQuery(
                        "SELECT itemType,spanX,spanY,container FROM favorites WHERE _id=?",
                        new String[]{String.valueOf(id)})) {
                    assertTrue("文件夹 " + id + " 不存在", c.moveToFirst());
                    assertEquals("itemType（" + span + "×" + span + "）", type, c.getInt(0));
                    assertEquals("spanX", span, c.getInt(1));
                    assertEquals("spanY", span, c.getInt(2));
                    assertEquals("container", -100, c.getInt(3));
                }
            }
            try (Cursor c = check.rawQuery(
                    "SELECT count(*) FROM favorites WHERE container=-100 AND itemType=?",
                    new String[]{String.valueOf(type)})) {
                assertTrue(c.moveToFirst());
                assertTrue("该档文件夹数应不少于方案数", c.getInt(0) >= ids.size());
            }
        } finally {
            check.close();
        }
    }

    @Test
    public void smallPlan_writesUserFolderType() throws Exception {
        File db = db();
        DesktopModel before = DesktopModel.read(db, ctx, "com.miui.home");
        DeskPlan plan = DeskPlan.build(ctx, before, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 1);
        assertEquals(1, plan.folderSpan);

        List<Integer> ids = new ArrayList<>();
        DeskWriter.rebuild(db, plan, new DeskWriter.Result(), ids);
        assertEquals("写出的文件夹数", plan.buckets.size(), ids.size());
        assertRows(db, ids, DesktopModel.TYPE_FOLDER_SMALL, 1);

        // 回读：2 也必须被认成文件夹，且成员仍然挂在文件夹下
        DesktopModel after = DesktopModel.read(db, ctx, "com.miui.home");
        assertTrue("方言不能被小文件夹带偏", after.profile.isMiui());
        assertEquals("回读文件夹数（含删不掉的旧文件夹）",
                plan.buckets.size() + plan.strayFolderIds.size(), after.folders.size());
        int members = 0;
        for (DesktopModel.Folder f : after.folders) {
            members += f.members.size();
        }
        assertTrue("成员不能丢", members >= plan.total);
    }

    @Test
    public void bigPlan_writesBigFolderType() throws Exception {
        File db = db();
        DesktopModel before = DesktopModel.read(db, ctx, "com.miui.home");
        DeskPlan plan = DeskPlan.build(ctx, before, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);
        assertEquals(2, plan.folderSpan);

        List<Integer> ids = new ArrayList<>();
        DeskWriter.rebuild(db, plan, new DeskWriter.Result(), ids);
        assertEquals("写出的文件夹数", plan.buckets.size(), ids.size());
        assertRows(db, ids, DesktopModel.TYPE_FOLDER, 2);
    }

    /**
     * 复用的旧文件夹同样要改 type。真机上 12 个分类文件夹的 id 会被复用，
     * 旧版只 update 坐标：旧 id 依旧是 21，用户选 1×1 后桌面还是画成 2×2。
     */
    @Test
    public void reusedFolder_alsoSwitchesType() throws Exception {
        // 先按 2×2 写一遍，让库里全是 21
        File db = db();
        DesktopModel first = DesktopModel.read(db, ctx, "com.miui.home");
        DeskPlan big = DeskPlan.build(ctx, first, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 2);
        List<Integer> bigIds = new ArrayList<>();
        DeskWriter.rebuild(db, big, new DeskWriter.Result(), bigIds);
        assertRows(db, bigIds, DesktopModel.TYPE_FOLDER, 2);

        // 再按 1×1 写一遍：标题相同，会走复用分支（同一批 id）
        DesktopModel second = DesktopModel.read(db, ctx, "com.miui.home");
        DeskPlan small = DeskPlan.build(ctx, second, AppClassifier.Scheme.FUNCTION,
                false, null, null, true, 1);
        List<Integer> smallIds = new ArrayList<>();
        DeskWriter.rebuild(db, small, new DeskWriter.Result(), smallIds);

        assertEquals("复用的文件夹数", small.buckets.size(), smallIds.size());
        assertTrue("应当复用上一轮的 id", smallIds.containsAll(bigIds)
                && bigIds.containsAll(smallIds));
        assertRows(db, smallIds, DesktopModel.TYPE_FOLDER_SMALL, 1);

        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = check.rawQuery(
                "SELECT count(*) FROM favorites WHERE container=-100 AND itemType=21"
                        + " AND spanX=2", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("复用后不该残留 2×2 的本次文件夹", 0, c.getInt(0));
        } finally {
            check.close();
        }
    }
}
