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
 * 「读 → 排 → 写」全链路，在 JVM 上对着合成库跑。
 *
 * <p>{@link DeskWriter#apply} 依赖 su 拉真库、拍快照、灌库，本地跑不了；
 * 但真正决定布局对错的是 {@link DeskWriter#rebuild}——它只改一个库文件。
 * 把这一段单独拉出来测，就能在没有真机的情况下发现"换 ROM 写坏桌面"。
 *
 * <p>除了回读结构，还会直接查库里新建文件夹的 itemType 与 screen：
 * realme UI 的屏号从 0 起，写错一格的结果是所有图标跑到隔壁屏，
 * 而"文件夹数 / 成员数"这类断言对此完全无感。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DeskWriterRebuildTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void miui_rebuild_putsEveryAppIntoFolders() throws Exception {
        assertRebuild(LauncherDbFixture.MIUI, "com.miui.home");
    }

    @Test
    public void colorOs_rebuild_putsEveryAppIntoFolders() throws Exception {
        assertRebuild(LauncherDbFixture.COLOROS, "com.coloros.launcher");
    }

    @Test
    public void realme_rebuild_putsEveryAppIntoFolders() throws Exception {
        assertRebuild(LauncherDbFixture.REALME, "com.oppo.launcher");
    }

    /** 真我系统桌面实测包名：写库链路也必须走 Launcher3 方言。 */
    @Test
    public void realmeAndroidLauncher_rebuild_putsEveryAppIntoFolders() throws Exception {
        assertRebuild(LauncherDbFixture.REALME, "com.android.launcher");
    }

    /**
     * 0.15.4：厂商私有 itemType + 无默认值的 NOT NULL 列。
     * 手工拼字段会 insert 失败，或插入后缺列被桌面丢弃；模板复制必须把这些列带上。
     */
    @Test
    public void realmeStrict_rebuildCreatesFoldersWithPrivateColumns() throws Exception {
        File db = assertRebuild(LauncherDbFixture.REALME_STRICT, "com.android.launcher");
        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor c = check.rawQuery(
                    "SELECT count(*) FROM favorites WHERE itemType=5 AND container=-100"
                            + " AND scene='normal' AND folder_style=1"
                            + " AND intent IS NULL", null)) {
                assertTrue(c.moveToFirst());
                assertTrue("新建文件夹行必须带着厂商私有列（模板复制生效），实际 "
                        + c.getInt(0), c.getInt(0) > 0);
            }
            try (Cursor c = check.rawQuery(
                    "SELECT count(*) FROM favorites WHERE itemType=5 AND container=-100"
                            + " AND (scene IS NULL OR folder_style IS NULL)", null)) {
                assertTrue(c.moveToFirst());
                assertEquals("不该有缺私有列的文件夹行", 0, c.getInt(0));
            }
        } finally {
            check.close();
        }
    }

    /** 0.15.1：写库 SQL 也得跟着动态表名走，不能只在读库那一侧自适应。 */
    @Test
    public void renamedLayoutTable_rebuildWritesIntoThatTable() throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        SQLiteDatabase s = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            s.execSQL("ALTER TABLE favorites RENAME TO launcher_favorites");
        } finally {
            s.close();
        }

        DesktopModel before = DesktopModel.read(db, ctx, "com.oppo.launcher");
        assertEquals("布局表名", "launcher_favorites", before.profile.table);
        DeskPlan plan = DeskPlan.build(ctx, before, AppClassifier.Scheme.FUNCTION, false, null);
        DeskWriter.Result r = new DeskWriter.Result();
        DeskWriter.rebuild(db, plan, r, new ArrayList<Integer>());
        assertEquals("写库报告的移动数", plan.total, r.moved);

        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = check.rawQuery(
                "SELECT count(*) FROM launcher_favorites WHERE container>0"
                        + " AND itemType IN (" + plan.profile.appTypesIn() + ")", null)) {
            assertTrue(c.moveToFirst());
            assertEquals("成员应写进改名后的表", plan.total, c.getInt(0));
        } finally {
            check.close();
        }
    }

    private File assertRebuild(LauncherDbFixture.Profile profile, String pkg) throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), profile);

        DesktopModel before = DesktopModel.read(db, ctx, pkg);
        DeskPlan plan = DeskPlan.build(ctx, before, AppClassifier.Scheme.FUNCTION, false, null);
        assertEquals("参与整理的应用数", LauncherDbFixture.MOVABLE_TOTAL, plan.total);
        assertTrue("应当排出了分组", !plan.buckets.isEmpty());

        DeskWriter.Result r = new DeskWriter.Result();
        List<Integer> folderIds = new ArrayList<>();
        DeskWriter.rebuild(db, plan, r, folderIds);

        assertEquals("写库时报告的移动数", plan.total, r.moved);
        assertEquals("新建/复用的文件夹 id 数", plan.buckets.size(), folderIds.size());

        DesktopModel after = DesktopModel.read(db, ctx, pkg);
        int inFolders = 0;
        for (DesktopModel.Folder f : after.folders) {
            for (DesktopModel.Item i : f.members) {
                if (i.isApp()) {
                    inFolders++;
                }
            }
        }
        assertEquals("回读：落进文件夹的应用数", plan.total, inFolders);
        assertEquals("回读：文件夹总数", plan.buckets.size(), after.folders.size());
        assertEquals("回读：热区不应被动过", 4, after.hotseat.size());

        // 直接查库：新建文件夹用的是本 ROM 的 itemType，屏号也按本 ROM 的起点写。
        SQLiteDatabase check = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            for (int i = 0; i < folderIds.size(); i++) {
                int expectedScreen = plan.buckets.get(i).screen - 1 + profile.screenBase;
                try (Cursor c = check.rawQuery(
                        "SELECT itemType,screen FROM favorites WHERE _id=?",
                        new String[]{String.valueOf(folderIds.get(i))})) {
                    assertTrue(profile + " 文件夹应存在", c.moveToFirst());
                    assertEquals(profile + " 文件夹 itemType", profile.typeFolder, c.getInt(0));
                    assertEquals(profile + " 文件夹屏号", expectedScreen, c.getInt(1));
                }
            }
            // 屏表必须补齐到整理后的总屏数（realme 用的是 workspaceScreens）
            try (Cursor c = check.rawQuery(
                    "SELECT count(*) FROM " + profile.screensTable, null)) {
                assertTrue(c.moveToFirst());
                assertTrue(profile + " 屏表应补齐到 " + plan.screenCount + " 屏",
                        c.getInt(0) >= plan.screenCount);
            }
        } finally {
            check.close();
        }
        return db;
    }
}
