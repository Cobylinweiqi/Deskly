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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 跨 ROM 解析一致性测试。
 *
 * <p>用同一份布局，只换 {@code favorites.itemType}、屏号起点与屏表，看
 * {@link DesktopModel} 能不能读出同样的结构。MIUI 那组是回归保护；另外两组
 * 是 realme UI / ColorOS 的适配验收——之前它们会把文件夹当普通条目、成员退回桌面。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class CrossRomSchemaTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void miui_readsFoldersAppsAndHotseat() throws Exception {
        assertLayout(LauncherDbFixture.MIUI, "com.miui.home");
    }

    @Test
    public void colorOs_readsFoldersAppsAndHotseat() throws Exception {
        assertLayout(LauncherDbFixture.COLOROS, "com.coloros.launcher");
    }

    /** 真我 realme UI：Launcher3 枚举 + 0 起屏号 + workspaceScreens。 */
    @Test
    public void realme_readsFoldersAppsAndHotseat() throws Exception {
        assertLayout(LauncherDbFixture.REALME, "com.oppo.launcher");
    }

    /** 真我系统桌面实测包名 com.android.launcher，不能因为名字陌生就退回 MIUI 方言。 */
    @Test
    public void realmeAndroidLauncher_readsFoldersAppsAndHotseat() throws Exception {
        assertLayout(LauncherDbFixture.REALME, "com.android.launcher");
    }

    /** 小米分支必须保持 0.14.1 的恒等变换，适配代码不能顺手改掉它的枚举。 */
    @Test
    public void miui_stillUsesLegacyEnums() throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        DesktopModel m = DesktopModel.read(db, ctx, "com.miui.home");
        assertTrue("方言", m.profile.isMiui());
        assertEquals("应用枚举", "0,14", m.profile.appTypesIn());
        // 0.16.6：HyperOS 4 的 2=小文件夹、21=2×2 大文件夹，两种都要认
        assertEquals("文件夹枚举", "2,21", m.profile.folderTypesIn());
        assertEquals("首屏编号", 1, m.profile.screenBase);
    }

    /**
     * 0.15.4 真机故障：「能换屏，但一个分类文件夹都没有」。
     * 厂商把应用/文件夹换成私有 itemType 时，按 AOSP 常量死认就会一个图标都分不到组。
     */
    @Test
    public void privateItemTypes_areDetectedByStructure() throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME_STRICT);
        DesktopModel m = DesktopModel.read(db, ctx, "com.android.launcher");
        assertEquals("仍属 Launcher3 系", LauncherProfile.Flavor.LAUNCHER3, m.profile.flavor);
        assertEquals("真实文件夹枚举", "5", m.profile.folderTypesIn());
        assertTrue("应用枚举要含私有值 9：" + m.profile.appTypesIn(),
                m.profile.appTypesIn().contains("9"));
        assertEquals("文件夹数", LauncherDbFixture.FOLDERS, m.folders.size());
        assertEquals("成员数", LauncherDbFixture.FOLDER_MEMBERS, memberCount(m));
        assertEquals("屏数", 3, m.screenCount);
        assertTrue("诊断要有 itemType 直方图", m.profile.diagnostics().contains("itemType"));
        assertTrue("诊断要标出文件夹", m.profile.diagnostics().contains("[文件夹]"));
        System.out.println("[realme STRICT] " + m.profile.diagnostics());
    }

    private void assertLayout(LauncherDbFixture.Profile p, String pkg) throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), p);
        DesktopModel m = DesktopModel.read(db, ctx, pkg);

        assertEquals(p + " 方言", p == LauncherDbFixture.MIUI
                ? LauncherProfile.Flavor.MIUI : LauncherProfile.Flavor.LAUNCHER3,
                m.profile.flavor);
        assertEquals(p + " 文件夹数", LauncherDbFixture.FOLDERS, m.folders.size());
        assertEquals(p + " 文件夹成员数", LauncherDbFixture.FOLDER_MEMBERS, memberCount(m));
        assertEquals(p + " 热区", 4, m.hotseat.size());
        // 屏号在内存里一律 1 起算，0 起的 realme 库也必须读成 3 屏
        assertEquals(p + " 屏数", 3, m.screenCount);

        int looseApps = 0;
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.isApp()) {
                looseApps++;
            }
        }
        assertEquals(p + " 散落应用", LauncherDbFixture.LOOSE_APPS, looseApps);
    }

    /**
     * 0.15.1 真机故障：{@code no such table: favorites}。库能打开但不是标准表名时，
     * 要按列结构找到等价布局表，而不是让上层撞一条看不懂的 SQLiteException。
     */
    @Test
    public void renamedLayoutTable_isDetectedAndRead() throws Exception {
        File db = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        SQLiteDatabase s = SQLiteDatabase.openDatabase(
                db.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            s.execSQL("ALTER TABLE favorites RENAME TO launcher_favorites");
        } finally {
            s.close();
        }
        DesktopModel m = DesktopModel.read(db, ctx, "com.oppo.launcher");
        assertEquals("布局表名", "launcher_favorites", m.profile.table);
        assertEquals("文件夹数", LauncherDbFixture.FOLDERS, m.folders.size());
        assertEquals("文件夹成员数", LauncherDbFixture.FOLDER_MEMBERS, memberCount(m));
        assertEquals("屏数", 3, m.screenCount);
    }

    /** 库里压根没有布局表时，探测必须明确说"没有"，并把实际表清单交给诊断。 */
    @Test
    public void noLayoutTable_isReportedWithDiagnostics() throws Exception {
        File db = new File(tmp.newFolder(), "settings.db");
        SQLiteDatabase s = SQLiteDatabase.openOrCreateDatabase(db, null);
        try {
            s.execSQL("CREATE TABLE settings (_id INTEGER PRIMARY KEY, k TEXT, v TEXT)");
            LauncherProfile p = LauncherProfile.detect(s, "com.oppo.launcher");
            assertTrue("不该把设置表误判成布局表", !p.hasLayoutTable());
            assertTrue("诊断要列出实际表", LauncherProfile.describe(s).contains("settings"));
        } finally {
            s.close();
        }
        try {
            DesktopModel.read(db, ctx, "com.oppo.launcher");
            fail("没有布局表时应当抛出带诊断的异常");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("settings"));
        }
    }

    /** 不是断言，是把三套 ROM 的读库结果打出来，方便对照着改代码。 */
    @Test
    public void dumpAllProfiles() throws Exception {
        LauncherDbFixture.Profile[] profiles = {
                LauncherDbFixture.MIUI, LauncherDbFixture.COLOROS, LauncherDbFixture.REALME};
        for (LauncherDbFixture.Profile p : profiles) {
            File db = LauncherDbFixture.create(tmp.newFolder(), p);
            DesktopModel m = DesktopModel.read(db, ctx,
                    p == LauncherDbFixture.MIUI ? "com.miui.home" : "com.oppo.launcher");
            int looseApps = 0;
            for (DesktopModel.Item i : m.looseOnDesktop) {
                if (i.isApp()) {
                    looseApps++;
                }
            }
            System.out.printf(
                    "[%s] flavor=%s base=%d folders=%d members=%d loose=%d looseApps=%d"
                            + " hotseat=%d screens=%d rows=%d%n",
                    p, m.profile.flavor, m.profile.screenBase, m.folders.size(), memberCount(m),
                    m.looseOnDesktop.size(), looseApps, m.hotseat.size(), m.screenCount,
                    m.totalRows);
        }
    }

    private static int memberCount(DesktopModel m) {
        int n = 0;
        for (DesktopModel.Folder f : m.folders) {
            n += f.members.size();
        }
        return n;
    }
}
