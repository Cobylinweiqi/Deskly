package io.github.cobylinweiqi.deskly.organize;

import android.content.ContentValues;
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
import static org.junit.Assert.assertNotNull;

/**
 * intent 包名提取的多格式验收。
 *
 * <p>0.16.0 之前只认 {@code component=}。真我 / ColorOS 的布局库里还有
 * {@code ComponentInfo{...}}、{@code package=} extra、裸 {@code pkg/cls} 等写法；
 * 取不到包名时功能分类只剩一个空字符串，整批预装图标会落进「其它」。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class DesktopModelPackageTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void parsesStandardComponentIntent() {
        assertEquals("com.tencent.mm", DesktopModel.packageOf(
                "#Intent;action=android.intent.action.MAIN;"
                        + "category=android.intent.category.LAUNCHER;"
                        + "launchFlags=0x10200000;component=com.tencent.mm/.ui.LauncherUI;end"));
        assertEquals("com.foo.bar", DesktopModel.packageOf(
                "#Intent;component=com.foo.bar/com.foo.bar.MainActivity;end"));
    }

    @Test
    public void parsesOemAlternateForms() {
        assertEquals("com.coloros.calculator", DesktopModel.packageOf(
                "ComponentInfo{com.coloros.calculator/com.coloros.calculator.Main}"));
        assertEquals("com.oplus.camera", DesktopModel.packageOf(
                "#Intent;action=android.intent.action.MAIN;package=com.oplus.camera;end"));
        assertEquals("com.heytap.music", DesktopModel.packageOf(
                "#Intent;S.package=com.heytap.music;end"));
        assertEquals("com.nearme.gamecenter", DesktopModel.packageOf(
                "com.nearme.gamecenter/.GameCenterActivity"));
    }

    @Test
    public void garbageNeverBecomesAPackage() {
        assertEquals("", DesktopModel.packageOf(null));
        assertEquals("", DesktopModel.packageOf(""));
        assertEquals("", DesktopModel.packageOf("#Intent;action=android.intent.action.MAIN;end"));
        assertEquals("", DesktopModel.packageOf("end"));
    }

    /** 端到端：库里写 package= 形式时，读出来的图标必须带着正确的包名进入分类。 */
    @Test
    public void packageOnlyIntentFlowsIntoClassifier() throws Exception {
        File dbFile = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.REALME);
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
                dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
        try {
            ContentValues v = new ContentValues();
            v.put("title", "");
            v.put("intent", "#Intent;action=android.intent.action.MAIN;"
                    + "package=com.oplus.deskclock;end");
            db.update("favorites", v, "_id=?", new String[]{"1"});
        } finally {
            db.close();
        }

        DesktopModel model = DesktopModel.read(dbFile, ctx, "com.android.launcher");
        DesktopModel.Item item = null;
        for (DesktopModel.Item i : model.looseOnDesktop) {
            if (i.id == 1) {
                item = i;
                break;
            }
        }
        assertNotNull("id=1 的桌面图标应当被读到", item);
        assertEquals("com.oplus.deskclock", item.pkg);
        assertEquals("工具", AppClassifier.groupByFunction(
                new AppEntry(item.title, item.pkg, "", false, null)));
    }
}
