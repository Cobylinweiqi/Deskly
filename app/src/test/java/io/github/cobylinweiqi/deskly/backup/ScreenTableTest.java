package io.github.cobylinweiqi.deskly.backup;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;

import io.github.cobylinweiqi.deskly.organize.LauncherProfile;
import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.3：真我「预览 2 屏、桌面最终只有 1 屏」的回归保护。
 *
 * <p>真机诊断：布局表叫 {@code singledesktopitems}，屏表探测为「（无）」，首屏被判成 1，
 * 桌面行 container 不是 -100。旧代码只认 {@code screens}/{@code workspaceScreens}，
 * 且首屏编号靠 {@code container=-100} 去查——查不到行就退回 1，写回的 screen 值
 * 桌面根本不认。这里把「私有屏表反查 / 屏号按 id 映射 / 模板行补屏」逐条钉死。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class ScreenTableTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String LAYOUT = "singledesktopitems";
    private static final String SCREENS = "desk_screens";
    private static final String PKG = "com.android.launcher";

    /** 造一份真我风格的私有库：桌面行 container=0、screen=0 起、屏表带私有 NOT NULL 列。 */
    private File privateDb(boolean withScreensTable) throws Exception {
        File f = new File(tmp.newFolder(), "launcher.db");
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(f, null);
        try {
            db.execSQL("CREATE TABLE " + LAYOUT + " ("
                    + "_id INTEGER PRIMARY KEY, title TEXT, intent TEXT,"
                    + "container INTEGER NOT NULL DEFAULT -1,"
                    + "screen INTEGER NOT NULL DEFAULT -1,"
                    + "cellX INTEGER NOT NULL DEFAULT 0,"
                    + "cellY INTEGER NOT NULL DEFAULT 0,"
                    + "spanX INTEGER NOT NULL DEFAULT 1,"
                    + "spanY INTEGER NOT NULL DEFAULT 1,"
                    + "itemType INTEGER NOT NULL DEFAULT 0,"
                    + "scene TEXT NOT NULL)");
            if (withScreensTable) {
                db.execSQL("CREATE TABLE " + SCREENS + " ("
                        + "_id INTEGER PRIMARY KEY,"
                        + "screenRank INTEGER NOT NULL,"
                        + "flag TEXT NOT NULL)");
                ContentValues s = new ContentValues();
                s.put("_id", 0);
                s.put("screenRank", 0);
                s.put("flag", "normal");
                db.insert(SCREENS, null, s);
            }

            int id = 1;
            for (int i = 0; i < 3; i++) {
                ContentValues v = row(id++, 0, 0, 0);
                v.put("title", "App" + i);
                v.put("intent", "#Intent;component=com.example.app" + i + "/.Main;end");
                db.insert(LAYOUT, null, v);
            }
            int folderId = 100;
            db.insert(LAYOUT, null, row(folderId, 0, 0, 5));
            ContentValues member = row(id++, folderId, 0, 0);
            member.put("title", "成员");
            member.put("intent", "#Intent;component=com.example.member/.Main;end");
            db.insert(LAYOUT, null, member);
            db.insert(LAYOUT, null, row(id, -101, 0, 0));
        } finally {
            db.close();
        }
        return f;
    }

    private static ContentValues row(int id, int container, int screen, int itemType) {
        ContentValues v = new ContentValues();
        v.put("_id", id);
        v.put("container", container);
        v.put("screen", screen);
        v.put("itemType", itemType);
        v.put("scene", "normal");
        return v;
    }

    private static LauncherProfile detect(File f) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            return LauncherProfile.detect(db, PKG);
        } finally {
            db.close();
        }
    }

    /** 屏表名字不标准时，按「布局表用到的 screen 值都能在它 _id 里找到」反查出来。 */
    @Test
    public void privateScreenTable_isFoundByData() throws Exception {
        File f = privateDb(true);
        LauncherProfile p = detect(f);
        assertEquals("布局表", LAYOUT, p.table);
        assertEquals("私有屏表", SCREENS, p.screensTable);
        assertEquals("排序列", "screenRank", p.screensRankColumn);
        assertEquals("首屏 id", 0, p.screenBase);
        assertEquals("第 1 屏写库值", 0, p.toDbScreen(1));
        assertEquals("新第 2 屏写库值", 1, p.toDbScreen(2));
        assertEquals("库里 1 → 内部第 1 屏", 1, p.fromDbScreen(0));
    }

    /** 没有屏表时也不能退回 1：桌面行 container=0 / screen=0 必须探出首屏 0。 */
    @Test
    public void noScreenTable_baseComesFromRealScreenValues() throws Exception {
        File f = privateDb(false);
        LauncherProfile p = detect(f);
        assertTrue("屏表确实没探到", p.screensTable.isEmpty());
        assertEquals("首屏不能退回 1", 0, p.screenBase);
        assertEquals("第 1 屏写库值", 0, p.toDbScreen(1));
        assertEquals("第 2 屏写库值", 1, p.toDbScreen(2));
    }

    /** 补屏必须拿桌面自己写的行做模板：私有 NOT NULL 列得带上，rank 要接着排。 */
    @Test
    public void syncScreens_usesExistingRowAsTemplate() throws Exception {
        File f = privateDb(true);
        LauncherProfile p = detect(f);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            DeskWriter.syncScreens(db, 2, p);

            try (Cursor c = db.rawQuery("SELECT _id,screenRank,flag FROM " + SCREENS
                    + " ORDER BY _id", null)) {
                assertTrue(c.moveToFirst());
                assertEquals("原有屏不被改动", 0, c.getInt(0));
                assertEquals(0, c.getInt(1));
                assertEquals("normal", c.getString(2));
                assertTrue("新屏应已补上", c.moveToNext());
                assertEquals(1, c.getInt(0));
                assertEquals(1, c.getInt(1));
                assertEquals("私有 NOT NULL 列必须从模板带过来", "normal", c.getString(2));
                assertFalse("不该多插屏", c.moveToNext());
            }
        } finally {
            db.close();
        }
    }

    /** 诊断要一次给全：全表清单、屏表行、screen 分布，别再让朋友反复回传。 */
    @Test
    public void diagnostics_exposeEveryTableNameAndScreenDistribution() throws Exception {
        String diag = detect(privateDb(true)).diagnostics();
        assertTrue("全表清单", diag.contains("全表 " + SCREENS) || diag.contains(SCREENS + "("));
        assertTrue("屏表行", diag.contains("屏表行"));
        assertTrue("screen 分布", diag.contains("screen 分布"));
        assertTrue("布局表 screen 值", diag.contains("0="));
        System.out.println("[private realme] " + diag);
    }

    /** 小米分支必须保持 0.14.1 的恒等变换，屏表映射不能顺手改掉它。 */
    @Test
    public void miui_keepsLegacyScreenMapping() throws Exception {
        File f = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        LauncherProfile p = detect(f);
        assertTrue(p.isMiui());
        assertEquals("首屏", 1, p.screenBase);
        assertEquals("第 1 屏", 1, p.toDbScreen(1));
        assertEquals("第 2 屏", 2, p.toDbScreen(2));
        assertEquals("回读", 1, p.fromDbScreen(1));
    }
}
