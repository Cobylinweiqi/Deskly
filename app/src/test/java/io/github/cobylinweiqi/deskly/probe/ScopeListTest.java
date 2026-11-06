package io.github.cobylinweiqi.deskly.probe;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * LSPosed「推荐应用」的声明回归保护。
 *
 * <p>框架侧的确切行为（{@code ModuleUtil.InstalledModule} 读
 * {@code META-INF/xposed/scope.list}，{@code ScopeAdapter.refresh()} 再用
 * {@code scopeList.contains(packageName)} 去过滤 {@code AppHelper.getAppList()}）：
 * <b>只显示本机已安装的包，未安装的静默忽略</b>。
 * 0.15.1 在真我上「推荐应用一片空白」就是这个原因——真我系统桌面是
 * {@code com.android.launcher}，而当时的 scope.list 六行里没有它。
 *
 * <p>还有一条不能踩：现代 libxposed 模块<b>不能</b>改用 Manifest 的
 * {@code xposedscope} 元数据兜底。LSPosed 判断新旧只看
 * {@code xposedminversion}，一旦出现 legacy 元数据就去找
 * {@code assets/xposed_init}，模块会静默不加载。所以推荐作用域只能由这份
 * scope.list 提供。
 */
public class ScopeListTest {

    /** 文件与代码清单双向对齐；新增已适配的桌面时两边必须一起改。 */
    @Test
    public void scopeListMatchesDeclaredRecommendations() throws Exception {
        List<String> lines = readScopeList();
        assertFalse("scope.list 不能为空", lines.isEmpty());
        assertTrue("真我系统桌面必须出现在推荐作用域里",
                lines.contains("com.android.launcher"));
        assertEquals("scope.list 必须与 TargetRegistry.RECOMMENDED_SCOPE 逐行一致",
                TargetRegistry.RECOMMENDED_SCOPE, lines);
        for (String pkg : lines) {
            assertTrue("推荐了却认不出的桌面：" + pkg,
                    TargetRegistry.isSupportedLauncher(pkg));
        }
        // 未适配的库结构不进推荐列表，别给用户「勾上就能用」的错觉
        assertFalse("华为桌面尚未适配，不该出现在推荐作用域",
                lines.contains("com.huawei.android.launcher"));
    }

    /** LSPosed 用 reader.lines() 逐行读，不 trim、不吃注释、不留空行。 */
    @Test
    public void scopeListLinesAreExactPackageNames() throws Exception {
        List<String> lines = readScopeList();
        for (String line : lines) {
            assertTrue("scope.list 每行必须是纯包名，不能带空格 / 注释 / BOM：" + line,
                    line.matches("[a-zA-Z][a-zA-Z0-9_.]+"));
        }
        assertEquals("别写重复行", new HashSet<>(lines).size(), lines.size());
    }

    @Test
    public void realmeLauncherIsRecognized() {
        assertTrue(TargetRegistry.isSupportedLauncher("com.android.launcher"));
        assertTrue(TargetRegistry.displayName("com.android.launcher").contains("真我"));
    }

    /**
     * 优先从 unit test 的 classpath 读（即真正会被打进 APK 的那份 Java 资源），
     * 取不到再从源码树读，保证换工作目录也不会假绿。
     */
    private List<String> readScopeList() throws Exception {
        InputStream in = ScopeListTest.class.getClassLoader()
                .getResourceAsStream("META-INF/xposed/scope.list");
        if (in == null) {
            File f = new File("src/main/resources/META-INF/xposed/scope.list");
            if (!f.exists()) {
                f = new File("app/src/main/resources/META-INF/xposed/scope.list");
            }
            assertTrue("找不到 scope.list（工作目录 "
                    + new File(".").getAbsolutePath() + "）", f.exists());
            in = new FileInputStream(f);
        }
        List<String> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty()) {
                    // 故意不 trim：LSPosed 也不 trim，多一个空格就是一个匹配不上的包名
                    out.add(line);
                }
            }
        }
        return out;
    }
}
