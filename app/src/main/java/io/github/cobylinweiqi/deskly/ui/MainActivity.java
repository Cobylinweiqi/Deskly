package io.github.cobylinweiqi.deskly.ui;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import io.github.cobylinweiqi.deskly.R;
import io.github.cobylinweiqi.deskly.core.Shell;

import java.util.ArrayList;
import java.util.List;

/**
 * 模块设置页。
 *
 * <p>两页：<b>整理</b>与<b>配置</b>。
 *
 * <p><b>整理页是一条直线：分析 → 选方案 → 预览并写入。</b>
 * 第一张卡只有一个动作（分析桌面上的全部应用图标），出结果后才有三个数字
 * （应用图标 / 现有文件夹 / 屏数）与方案卡；方案做成并排两张，可点选；
 * 写入按钮从页面流里挪到了固定底栏，滚动预览时始终可点。
 *
 * <p>「精细归位」降到页尾并默认折叠：它是对整理结果的逐条微调，
 * 和分析入口是两种不同量级的事，不该同时摆在第一屏抢注意力。
 *
 * <p><b>配置页在 0.12.0 重做。</b>原来这里堆着「运行状态 / 诊断 / 备份 / 探针报告」四块，
 * 其中运行状态只是一行版本号、诊断要手动扫一遍才出报告——对刚装好的用户，
 * 真正的问题是"我该开哪些权限、模块到底有没有生效"，而不是"有多少个候选类"。
 * 现在只留两张卡：<b>运行引导</b>（LSPosed / 作用域 / root / 重启桌面，能查的查、
 * 查不到的直说）与<b>备份与恢复</b>（精确到每个图标，恢复不再额外备份）；
 * 0.12.3 又在页尾补了<b>关于</b>（版本 / 作者 / 反馈入口 / 免责声明 / 捐赠）。
 * 三张卡都默认收起（见 {@link Fold}）：不收起时一屏放不下，想点下面那张得先划过一整张。
 *
 * <p>这里不假装能"查询模块是否已激活"——LSPosed 没有给模块 App 提供这种查询接口。
 * 能确定的事实（桌面是否安装、root 能不能用）就直说，不能确定的就告诉用户去哪检查。
 */
public class MainActivity extends Activity {

    private static final String DEFAULT_TARGET = "com.miui.home";

    private TextView tvMainWarn;
    private TextView tvFooter;

    private TextView tabOrganize;
    private TextView tabConfig;
    private View pageOrganize;
    private View pageConfig;

    private PlanSection plan;
    private GuideSection guide;
    private BackupSection backup;
    private AboutSection about;
    private DesktopSection desktop;
    private RelocateSection relocate;

    /** 小米桌面是否安装。与 root 状态分开存，因为两条告警要能同时显示。 */
    private boolean launcherInstalled = false;
    /** 最近一次 root 探测结果，null 表示还没探过。 */
    private Shell.Probe rootProbe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvMainWarn = findViewById(R.id.tvMainWarn);
        tvFooter = findViewById(R.id.tvFooter);

        // 版本号从 PackageManager 读，不写死在布局里：升版本只改 build.gradle.kts 一处，
        // 界面上也不会出现"装的是新的、显示的是旧的"这种自相矛盾。
        String ver = versionName();
        ((TextView) findViewById(R.id.tvBannerVersion)).setText(ver.isEmpty() ? "" : "v" + ver);

        tabOrganize = findViewById(R.id.tabOrganize);
        tabConfig = findViewById(R.id.tabConfig);
        pageOrganize = findViewById(R.id.pageOrganize);
        pageConfig = findViewById(R.id.pageConfig);

        launcherInstalled = installed(DEFAULT_TARGET);

        plan = new PlanSection(this,
                findViewById(R.id.presetContainer),
                findViewById(R.id.cbFirstBlank),
                findViewById(R.id.tvPlanSummary),
                findViewById(R.id.screenContainer));

        desktop = new DesktopSection(this, DEFAULT_TARGET, pageOrganize, plan);

        // 换方案 / 改钉屏 / 勾留空 都要重算分组与屏位——预览和写入依据同一份结果
        plan.setPresetListener(s -> desktop.rebuild());
        plan.setPlanChangedListener(desktop::rebuild);

        wireActions();
        renderFooter();
        selectTab(true);

        // 运行引导的「重新检测」复用备份区块的探测：root 探测只该有一个入口，
        // 否则一次授权会弹出两个 su 请求，看起来像应用在乱弹框。
        guide = new GuideSection(this, DEFAULT_TARGET, pageConfig,
                (TextView) findViewById(R.id.tvGuideHint),
                () -> {
                    if (backup != null) {
                        backup.refresh();
                    }
                });

        backup = new BackupSection(this, DEFAULT_TARGET,
                (TextView) findViewById(R.id.tvBackupStatus),
                (TextView) findViewById(R.id.tvBackupHead),
                (TextView) findViewById(R.id.tvBackupHint),
                (LinearLayout) findViewById(R.id.backupList),
                (findViewById(R.id.btnBackup)));
        // 探测结果要回流：首页告警条与运行引导第三条都靠它
        backup.setOnProbeListener(p -> {
            rootProbe = p;
            if (guide != null) {
                guide.onProbe(p);
            }
            if (!isFinishing() && !isDestroyed()) {
                refreshWarn();
            }
        });
        backup.refresh();

        // 「关于」没有异步探测，构造完就能用：版本号读一次，两个入口挂上点击
        about = new AboutSection(this, pageConfig);

        // 归位区块依赖"改坏了能退回去"，归位写入前会自己拍快照，不等用户切 tab
        relocate = new RelocateSection(this, DEFAULT_TARGET,
                (TextView) findViewById(R.id.tvRelocateStatus),
                (LinearLayout) findViewById(R.id.relocateList),
                (findViewById(R.id.btnRelocate)),
                (findViewById(R.id.btnRelocateApply)));

        // 精细归位默认折叠：它是对整理结果的微调，展开才占位置，
        // 否则一进首页就会被两张"都能改桌面"的卡同时抢注意力
        View relocateHead = findViewById(R.id.rowRelocateHead);
        View relocateBox = findViewById(R.id.boxRelocate);
        TextView relocateToggle = findViewById(R.id.tvRelocateToggle);
        relocateHead.setOnClickListener(v -> {
            boolean show = relocateBox.getVisibility() != View.VISIBLE;
            relocateBox.setVisibility(show ? View.VISIBLE : View.GONE);
            relocateToggle.setText(show ? "收起 ⌃" : "展开 ⌄");
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 最常见的一条路：用户去 Magisk 点了「允许」再切回来。以前回来仍停在"未获得 root"，
        // 因为检测只在 onCreate 跑过。这里自动补一次，省得他们去另一个 tab 找「重新检测」。
        if (backup != null) {
            backup.recheckIfDegraded();
        }
    }

    // ───────────────────────── Tab ─────────────────────────

    private void wireActions() {
        tabOrganize.setOnClickListener(v -> selectTab(true));
        tabConfig.setOnClickListener(v -> selectTab(false));

        // 配置页三张卡默认收起：收起后一屏就能看全"运行引导 / 备份与恢复 / 关于"，
        // 要点哪件再展开哪件。折叠时露一行摘要，不用展开也知道状态。
        Fold.bind(findViewById(R.id.rowGuideHead), findViewById(R.id.boxGuide),
                (TextView) findViewById(R.id.tvGuideToggle),
                findViewById(R.id.tvGuideHint));
        Fold.bind(findViewById(R.id.rowBackupHead), findViewById(R.id.boxBackup),
                (TextView) findViewById(R.id.tvBackupToggle),
                findViewById(R.id.tvBackupHint));
        Fold.bind(findViewById(R.id.rowAboutHead), findViewById(R.id.boxAbout),
                (TextView) findViewById(R.id.tvAboutToggle),
                findViewById(R.id.tvAboutHint));
    }

    private void selectTab(boolean organize) {
        pageOrganize.setVisibility(organize ? View.VISIBLE : View.GONE);
        pageConfig.setVisibility(organize ? View.GONE : View.VISIBLE);
        paintTab(tabOrganize, organize);
        paintTab(tabConfig, !organize);
    }

    /** 选中的 tab 给白底滑块 + 深色粗体，未选中的退成灰字，避免两个都像按钮。 */
    private void paintTab(TextView tv, boolean on) {
        tv.setBackgroundResource(on ? R.drawable.bg_tab_on : 0);
        tv.setTextColor(getColor(on ? R.color.text_primary : R.color.text_secondary));
        tv.setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    // ───────────────────────── 状态 ─────────────────────────

    /**
     * 首页顶部告警条。两条事实独立判断、可以同时出现：<b>桌面是否在</b>与<b>root 能不能用</b>。
     *
     * <p>root 这条是后加的。之前 root 坏掉时整理页一个字都不说，用户点「分析」才会看到
     * 一句含糊的"需要 root"，然后陷进"我明明授权了"的困惑里。真实原因
     * （没授权 / Magisk 守护进程挂了 / 没有 su）现在就摆在首页。
     */
    private void refreshWarn() {
        List<String> warns = new ArrayList<>();
        if (!launcherInstalled) {
            warns.add("没找到小米桌面。整理依赖它的数据库结构，其它 ROM 需自行验证。");
        }
        if (rootProbe != null && !rootProbe.ok()) {
            String act = rootProbe.shortAction();
            warns.add(rootProbe.headline() + (act.isEmpty() ? "" : " · " + act));
        }
        if (warns.isEmpty()) {
            tvMainWarn.setVisibility(View.GONE);
            return;
        }
        tvMainWarn.setText(TextUtils.join("\n", warns));
        tvMainWarn.setVisibility(View.VISIBLE);
    }

    private void renderFooter() {
        tvFooter.setText("· 只用 root 读写，不使用无障碍服务\n"
                + "· 整理前自动拍快照；恢复是直接用选中的快照覆盖，不再额外备份\n"
                + "· 写完后桌面会自动重启一次，布局即刻生效");
    }

    private String versionName() {
        try {
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return v == null ? "" : v;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean installed(String target) {
        try {
            getPackageManager().getPackageInfo(target, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
