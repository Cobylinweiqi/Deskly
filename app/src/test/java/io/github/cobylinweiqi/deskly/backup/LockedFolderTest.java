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
import java.util.Collections;

import io.github.cobylinweiqi.deskly.organize.AppClassifier;
import io.github.cobylinweiqi.deskly.organize.DeskPlan;
import io.github.cobylinweiqi.deskly.organize.DesktopModel;
import io.github.cobylinweiqi.deskly.testing.LauncherDbFixture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「锁定屏上有文件夹时整理必然失败」的回归保护。
 *
 * <p>真机现象（HyperOS 3，锁定第 1、2 屏，第 2 屏有系统自带的「工具」文件夹，17 个成员）：
 * <pre>在副本上重建布局失败：IllegalStateException: 回读成员数 129 ≠ 计划 112</pre>
 * 锁定屏上的文件夹连成员原地保留，成员不计入 {@code plan.total}；
 * 而回读校验数的是全库所有文件夹里的应用，两边差的正好是保留文件夹的成员数。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class LockedFolderTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
    }

    /** fixture 第 1 屏有一个带 3 个应用成员的文件夹：锁定第 1 屏后重建不能中止。 */
    @Test
    public void lockedScreenFolderMembersAreNotCountedOnReadBack() throws Exception {
        File f = LauncherDbFixture.create(tmp.newFolder(), LauncherDbFixture.MIUI);
        final int lockedFolderId;
        final int lockedMembers;
        SQLiteDatabase before = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            try (Cursor c = before.rawQuery("SELECT _id FROM favorites WHERE itemType=21"
                    + " AND container=-100 AND screen=1", null)) {
                assertTrue("fixture 第 1 屏应当有文件夹", c.moveToFirst());
                lockedFolderId = c.getInt(0);
            }
            lockedMembers = count(before, "container=" + lockedFolderId);
            assertTrue("锁定屏的文件夹里应当有成员", lockedMembers > 0);
        } finally {
            before.close();
        }

        DesktopModel m = DesktopModel.read(f, ctx, "com.miui.home");
        DeskPlan p = DeskPlan.build(ctx, m, AppClassifier.Scheme.FUNCTION,
                false, null, Collections.singleton(1), true, 2);
        assertTrue("锁定屏的文件夹要原地保留", p.keptFolderIds.contains(lockedFolderId));
        assertTrue("未锁定屏上应当还有图标参与整理", p.total > 0);

        DeskWriter.Result r = new DeskWriter.Result();
        // 修复前这里抛「回读成员数 ≠ 计划」
        DeskWriter.rebuild(f, p, r, new ArrayList<Integer>());
        assertEquals("成员写入数", p.total, r.moved);

        SQLiteDatabase after = SQLiteDatabase.openDatabase(
                f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
        try {
            assertEquals("锁定屏文件夹的成员一个都不能动",
                    lockedMembers, count(after, "container=" + lockedFolderId));
            assertEquals("锁定屏文件夹本体必须还在",
                    1, count(after, "_id=" + lockedFolderId + " AND container=-100"));
            assertFalse("新文件夹不能复用锁定屏的文件夹",
                    p.reusable.containsValue(lockedFolderId));
        } finally {
            after.close();
        }
    }

    private static int count(SQLiteDatabase db, String where) {
        try (Cursor c = db.rawQuery("SELECT count(*) FROM favorites WHERE " + where, null)) {
            c.moveToFirst();
            return c.getInt(0);
        }
    }
}
