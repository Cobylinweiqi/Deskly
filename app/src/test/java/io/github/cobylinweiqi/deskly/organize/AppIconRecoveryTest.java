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

import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 0.16.1「很多应用没进文件夹」的回归保护。
 *
 * <p>{@link LauncherProfile} 探测应用枚举时明确排除了 itemType=4 与 19（AOSP 里
 * 4 是 APPWIDGET）。但厂商私有枚举里 4 完全可能就是应用图标——这些行会被判成
 * 非应用、进 stayPut 留在原屏，桌面看起来就是「整理完图标还散着」。
 * 现在按结构事实（有 component、1×1 格、非文件夹/分组/热区）兜底，
 * 同时必须证明真正的小组件没被一起收进文件夹。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class AppIconRecoveryTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void privateItemType4WithComponentIsAnApp() throws Exception {
        File dbFile = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        SQLiteDatabase w = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            w.execSQL("INSERT INTO favorites "
                    + "(_id,title,intent,container,screen,cellX,cellY,spanX,spanY,itemType) VALUES "
                    + "(900,'私有枚举应用',"
                    + "'#Intent;component=com.example.private4/.MainActivity;end',"
                    + "-100,0,3,5,1,1,4)");
        } finally {
            w.close();
        }

        DesktopModel m = DesktopModel.read(dbFile, ctx, "com.android.launcher");
        DesktopModel.Item found = byId(m, 900);
        assertNotNull("应读到插入的私有枚举行", found);
        assertTrue("探测排除的 itemType=4，只要有 component + 1×1 就该参与整理",
                found.isApp());
        assertEquals("包名要从 component 里取出来", "com.example.private4", found.pkg);
    }

    /** 真我 / ColorOS 的 2×2 日历组件不能被当成应用收进文件夹。 */
    @Test
    public void realmeWidgetStaysPut() throws Exception {
        File dbFile = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        DesktopModel m = DesktopModel.read(dbFile, ctx, "com.android.launcher");
        DesktopModel.Item widget = byTitle(m, "日历");
        assertNotNull(widget);
        assertFalse("2×2 且无 intent 的组件不能进文件夹", widget.isApp());
    }

    /** 小米分支同样不能被兜底带坏：组件是 itemType=19、无 intent、2×2。 */
    @Test
    public void miuiWidgetStaysPut() throws Exception {
        File dbFile = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        DesktopModel m = DesktopModel.read(dbFile, ctx, "com.miui.home");
        assertTrue("方言仍是小米", m.profile.isMiui());
        DesktopModel.Item widget = byTitle(m, "日历");
        assertNotNull(widget);
        assertFalse("小米组件不能进文件夹", widget.isApp());
    }

    /** 确认框要能报出「库里 X 行 / Y 个参与整理 / Z 个原地保留」。 */
    @Test
    public void planReportsReadAndSkippedRows() throws Exception {
        File dbFile = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        DesktopModel m = DesktopModel.read(dbFile, ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION, false, null);
        assertTrue("应当有图标参与整理", p.total > 0);
        assertEquals(m.totalItems, p.readRows);
        assertEquals(Math.max(0, m.totalItems - p.total), p.skippedRows);
        assertTrue("方案里的文件夹成员数应等于参与整理的图标数",
                p.memberCount() == p.total);
    }

    private static DesktopModel.Item byId(DesktopModel m, int id) {
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.id == id) {
                return i;
            }
        }
        return null;
    }

    private static DesktopModel.Item byTitle(DesktopModel m, String title) {
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (title.equals(i.title)) {
                return i;
            }
        }
        return null;
    }
}
