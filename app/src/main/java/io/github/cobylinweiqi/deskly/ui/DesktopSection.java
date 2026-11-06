package io.github.cobylinweiqi.deskly.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import io.github.cobylinweiqi.deskly.R;
import io.github.cobylinweiqi.deskly.backup.DbCopy;
import io.github.cobylinweiqi.deskly.backup.DeskWriter;
import io.github.cobylinweiqi.deskly.core.Shell;
import io.github.cobylinweiqi.deskly.organize.AppClassifier;
import io.github.cobylinweiqi.deskly.organize.AppEntry;
import io.github.cobylinweiqi.deskly.organize.DeskPlan;
import io.github.cobylinweiqi.deskly.organize.DesktopModel;
import io.github.cobylinweiqi.deskly.organize.IconPalette;
import io.github.cobylinweiqi.deskly.organize.LayoutPlan;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 页一的主链路：<b>分析 → 选方案 → 预览 → 写入</b>。
 *
 * <p>范围是<b>桌面上的全部应用图标</b>：文件夹里的、散在桌面上的都算，整批按所选方案重新分桶，
 * 再按屏位规划重排。不是"只处理散落的那几个"——真机 155 个图标里 146 个已经在文件夹里，
 * 按散落处理会让功能看起来只影响 5 个图标，而"整理桌面"的诉求根本没被满足。
 *
 * <p>界面按三步排：第一张卡只有"分析"一个动作，出结果后才有数字与方案卡；
 * 写入按钮从页面底部挪到固定的 bottom bar，分析出结果就浮出来——用户在预览里滚到哪都能点，
 * 不用先划到底。
 *
 * <p>底部固定栏（热区）与小组件不进文件夹，原地不动。这一点在页脚里如实写了，
 * 免得用户以为"整理完桌面就空了"。
 */
public final class DesktopSection {

    /** 一组图标折叠时显示几个，以及每行排几个。半屏宽下 7 个 28dp 图标刚好不换行。 */
    private static final int ICONS_PER_ROW = 7;

    private final Activity act;
    private final String pkg;
    private final PlanSection plan;

    private final TextView tvStatus;
    private final TextView tvNote;
    private final View cardStats;
    private final TextView statApps;
    private final TextView statFolders;
    private final TextView statScreens;
    private final TextView tvSummary;
    private final View cardGroups;
    private final LinearLayout groupBox;
    private final Button btnLoad;
    private final View applyBar;
    private final Button btnApply;
    private final TextView tvApplyHint;

    private volatile boolean busy;
    /** 读到的桌面模型，null 表示还没读过。 */
    private DesktopModel model;
    /** 当前方案算出来的整理计划，null 表示还没算。 */
    private DeskPlan desk;
    /** 计划是按哪个方案算的，切方案后要重算。 */
    private AppClassifier.Scheme deskScheme;
    /** 包名 → 颜色组名。颜色方案要 rasterize 图标，切回同一方案不必重算一遍。 */
    private final Map<String, String> colorGroupCache = new HashMap<>();
    /** 首次进入的呼吸提示，点过一次分析就停。 */
    private ValueAnimator idlePulse;

    public DesktopSection(Activity act, String pkg, View root, PlanSection plan) {
        this.act = act;
        this.pkg = pkg;
        this.plan = plan;

        this.tvStatus = root.findViewById(R.id.tvDeskStatus);
        this.tvNote = root.findViewById(R.id.tvDeskNote);
        this.cardStats = root.findViewById(R.id.cardStats);
        this.statApps = root.findViewById(R.id.tvStatApps);
        this.statFolders = root.findViewById(R.id.tvStatFolders);
        this.statScreens = root.findViewById(R.id.tvStatScreens);
        this.tvSummary = root.findViewById(R.id.tvGroupSummary);
        this.cardGroups = root.findViewById(R.id.cardGroups);
        this.groupBox = root.findViewById(R.id.groupContainer);
        this.btnLoad = root.findViewById(R.id.btnDeskLoad);
        this.applyBar = root.findViewById(R.id.applyBar);
        this.btnApply = root.findViewById(R.id.btnDeskApply);
        this.tvApplyHint = root.findViewById(R.id.tvApplyHint);

        btnLoad.setOnClickListener(v -> load());
        btnApply.setOnClickListener(v -> confirmApply());
        applyBar.setVisibility(View.GONE);
        startIdlePulse();
    }

    // ───────────────────────── 首次进入的引导 ─────────────────────────

    /**
     * 还没分析过时，让"分析"这颗按钮缓慢呼吸一下。
     *
     * <p>第一版这里什么都没有，结果首屏最显眼的是三张白卡和一堆灰字，
     * 用户不知道该点哪。呼吸幅度压到 0.88，是提示不是闪烁；点过一次就永久停掉——
     * 已经知道要做什么的人，不需要再看第二次。
     */
    private void startIdlePulse() {
        if (idlePulse != null || model != null) {
            return;
        }
        idlePulse = ValueAnimator.ofFloat(1f, 0.88f);
        idlePulse.setDuration(2400);
        idlePulse.setRepeatMode(ValueAnimator.REVERSE);
        idlePulse.setRepeatCount(ValueAnimator.INFINITE);
        idlePulse.addUpdateListener(a -> btnLoad.setAlpha((Float) a.getAnimatedValue()));
        idlePulse.start();
    }

    private void stopIdlePulse() {
        if (idlePulse != null) {
            idlePulse.cancel();
            idlePulse = null;
        }
        btnLoad.setAlpha(1f);
    }

    // ───────────────────────── 分析 ─────────────────────────

    private void load() {
        if (busy) {
            return;
        }
        busy = true;
        // 重新分析要重新问一次应用名 / category：期间用户可能装了新 App
        AppClassifier.clearPmCache();
        stopIdlePulse();
        btnLoad.setEnabled(false);
        btnLoad.setText("分析中…");
        applyBar.setVisibility(View.GONE);
        cardGroups.setVisibility(View.GONE);
        tvStatus.setText("正在读取桌面布局…");

        new Thread(() -> {
            String err = null;
            DesktopModel m = null;
            File copy = null;
            int installed = 0;
            int notOnDesk = 0;
            try {
                Shell.Probe probe = Shell.probe();
                if (!probe.ok()) {
                    err = probe.report();
                } else {
                    copy = DbCopy.pull(act, pkg);
                    if (copy == null) {
                        err = DbCopy.lastError == null ? "读不到桌面数据库" : DbCopy.lastError;
                    } else {
                        m = DesktopModel.read(copy, act, pkg);
                        // 顺带核一个数：本机还有多少可启动应用不在这张桌面上。
                        // 它们本次不会被整理（写库只挪已有条目），与其让用户自己发现，不如直说。
                        Set<String> launchable = AppClassifier.launcherPackages(act);
                        installed = launchable.size();
                        Set<String> onDesk = new HashSet<>();
                        collectPkgs(m, onDesk);
                        for (String p : launchable) {
                            if (!onDesk.contains(p)) {
                                notOnDesk++;
                            }
                        }
                        // 换了一份桌面数据，按包名缓存的颜色组可能已经过期（装了/卸了应用）
                        colorGroupCache.clear();
                    }
                }
            } catch (Throwable t) {
                err = "分析出错：" + t;
            } finally {
                DbCopy.delete(copy);
            }

            final String fErr = err;
            final DesktopModel fModel = m;
            final int fInstalled = installed;
            final int fMissing = notOnDesk;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnLoad.setEnabled(true);
                btnLoad.setText("重新分析");
                if (fErr != null) {
                    tvStatus.setText(fErr);
                    cardStats.setVisibility(View.GONE);
                    return;
                }
                model = fModel;
                // 屏数变了，锁定 Chip 与越界的锁定记录要一起收敛
                plan.setScreenCount(fModel.screenCount);
                plan.setFolderSpan(fModel.folderSpan);
                renderOverview(fModel, fInstalled, fMissing);
                rebuild();
            });
        }, "deskly-desk-load").start();
    }

    private void collectPkgs(DesktopModel m, Set<String> out) {
        for (DesktopModel.Folder f : m.folders) {
            for (DesktopModel.Item i : f.members) {
                if (i.pkg != null && !i.pkg.isEmpty()) {
                    out.add(i.pkg);
                }
            }
        }
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.pkg != null && !i.pkg.isEmpty()) {
                out.add(i.pkg);
            }
        }
    }

    /** 第一次分析出结果：填三个数字 + 一句人话总结 + 一条"哪些没被覆盖"的提醒。 */
    private void renderOverview(DesktopModel m, int installed, int missing) {
        int apps = 0;
        for (DesktopModel.Folder f : m.folders) {
            for (DesktopModel.Item i : f.members) {
                if (i.isApp()) {
                    apps++;
                }
            }
        }
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.isApp()) {
                apps++;
            }
        }

        statApps.setText(String.valueOf(apps));
        statFolders.setText(String.valueOf(m.folders.size()));
        statScreens.setText(String.valueOf(m.screenCount));
        cardStats.setVisibility(View.VISIBLE);

        int hotseat = m.hotseat.size();
        Set<Integer> locks = plan.lockedScreens();
        int lockedApps = 0;
        for (DesktopModel.Folder f : m.folders) {
            if (locks.contains(f.screen)) {
                for (DesktopModel.Item i : f.members) {
                    if (i.isApp()) {
                        lockedApps++;
                    }
                }
            }
        }
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (locks.contains(i.screen) && i.isApp()) {
                lockedApps++;
            }
        }
        String lockNote = locks.isEmpty() ? ""
                : " · 锁定 " + locks.size() + " 屏（跳过 " + lockedApps + " 个图标）";
        tvStatus.setText("桌面已读取：库里共 " + m.totalItems + " 行，其中 "
                + countLoose(m) + " 个散在桌面上、"
                + hotseat + " 个在底部固定栏（不动）" + lockNote
                + "。下面两个方案都算好了，选中即预览。"
                + "\n文件夹占 " + m.folderSpan + "×" + m.folderSpan + " 格 · 每屏可放 "
                + LayoutPlan.slotsForSpan(m.folderSpan) + " 个"
                + "\n诊断：" + (m.profile == null ? "无" : m.profile.diagnostics()));

        if (missing > 0) {
            tvNote.setText("本机 " + installed + " 个可启动应用里有 " + missing
                    + " 个不在桌面 —— 本次只重排桌面上已有的图标，不会把它们加进来。");
            tvNote.setVisibility(View.VISIBLE);
        } else {
            tvNote.setVisibility(View.GONE);
        }
    }

    private int countLoose(DesktopModel m) {
        int n = 0;
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.isApp()) {
                n++;
            }
        }
        return n;
    }

    // ───────────────────────── 重新分组 ─────────────────────────

    /**
     * 方案 / 钉屏 / 留空 任何一项变了都要重算。颜色方案要 rasterize 一百多个图标，
     * 必须放后台；算的时候用户可能又切了方案，所以回来先比对方案是否仍是当前那个。
     */
    public void rebuild() {
        if (model == null || busy) {
            return;
        }
        final AppClassifier.Scheme scheme = plan.scheme();
        final boolean blank = plan.firstScreenBlank();
        final Map<String, Integer> pins = new LinkedHashMap<>(plan.pins());
        final java.util.Set<Integer> locks = new java.util.HashSet<>(plan.lockedScreens());
        final boolean keepWidgets = plan.keepOrphanWidgets();
        final DesktopModel m = model;

        busy = true;
        applyBar.setVisibility(View.GONE);
        tvSummary.setText("正在分组…");

        new Thread(() -> {
            DeskPlan p = null;
            String err = null;
            Map<AppClassifier.Scheme, Integer> counts = new EnumMap<>(AppClassifier.Scheme.class);
            try {
                p = DeskPlan.build(act, m, scheme, blank, pins, locks, keepWidgets);
                // 两个方案的组数一起算：方案卡上要能直接比较，切过去才知道差几个文件夹太迟了
                for (AppClassifier.Scheme s : AppClassifier.Scheme.values()) {
                    counts.put(s, countGroups(m, s));
                }
            } catch (Throwable t) {
                err = "分组出错：" + t;
            }
            final DeskPlan fPlan = p;
            final String fErr = err;
            final Map<AppClassifier.Scheme, Integer> fCounts = counts;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (fErr != null) {
                    tvSummary.setText(fErr);
                    cardGroups.setVisibility(View.VISIBLE);
                    return;
                }
                if (scheme != plan.scheme()) {
                    // 算的过程中换了方案，这批结果已经作废，按新方案再来一次
                    rebuild();
                    return;
                }
                desk = fPlan;
                deskScheme = scheme;
                for (Map.Entry<AppClassifier.Scheme, Integer> e : fCounts.entrySet()) {
                    plan.noteGroups(e.getKey(), e.getValue());
                }
                // 屏位预览用同一份分组和同一份"每屏容量"，界面上说第几屏，写进去就是第几屏
                plan.renderPlan(entryGroups(fPlan), fPlan.slots);
                renderBuckets(fPlan);
            });
        }, "deskly-desk-group").start();
    }

    /**
     * 只数分组、不排屏：给方案卡上的"N 个文件夹"用。
     * 颜色方案按包名缓存主色结果，切方案来回点不会反复 rasterize。
     */
    private int countGroups(DesktopModel m, AppClassifier.Scheme scheme) {
        Set<String> groups = new HashSet<>();
        Set<Integer> locks = plan.lockedScreens();
        for (DesktopModel.Folder f : m.folders) {
            // 锁定屏里的图标不参与整理，数组时也不能算进来
            if (locks.contains(f.screen)) {
                continue;
            }
            for (DesktopModel.Item i : f.members) {
                if (i.isApp()) {
                    groups.add(groupOf(i, scheme));
                }
            }
        }
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (locks.contains(i.screen)) {
                continue;
            }
            if (i.isApp()) {
                groups.add(groupOf(i, scheme));
            }
        }
        return groups.size();
    }

    private String groupOf(DesktopModel.Item i, AppClassifier.Scheme scheme) {
        if (scheme != AppClassifier.Scheme.COLOR) {
            return AppClassifier.groupByFunction(act.getPackageManager(), i.title, i.pkg);
        }
        String key = i.pkg == null ? "" : i.pkg;
        String hit = colorGroupCache.get(key);
        if (hit != null) {
            return hit;
        }
        Drawable d = null;
        if (!key.isEmpty()) {
            try {
                d = act.getPackageManager().getApplicationIcon(key);
            } catch (Throwable ignored) {
            }
        }
        String g = IconPalette.groupOf(IconPalette.dominantColor(d));
        colorGroupCache.put(key, g);
        return g;
    }

    /** 屏位预览只要组名与数量，转成 LayoutPlan 认识的形状即可。 */
    private Map<String, List<AppEntry>> entryGroups(DeskPlan p) {
        Map<String, List<AppEntry>> m = new LinkedHashMap<>();
        for (DeskPlan.Bucket b : p.buckets) {
            List<AppEntry> list = new ArrayList<>(b.members.size());
            for (DesktopModel.Item it : b.members) {
                list.add(new AppEntry(it.title, it.pkg, "", false, null));
            }
            m.put(b.group, list);
        }
        return m;
    }

    private void renderBuckets(DeskPlan p) {
        groupBox.removeAllViews();
        cardGroups.setVisibility(View.VISIBLE);

        if (p.buckets.isEmpty()) {
            tvSummary.setText(p.lockedCount > 0
                    ? "锁定屏之外的屏里没有可整理的图标"
                    : "桌面上没有可整理的应用图标");
            applyBar.setVisibility(View.GONE);
            groupBox.addView(note("读到的都是小组件或组件，它们不进文件夹。"));
            return;
        }

        tvSummary.setText("整理后 " + p.buckets.size() + " 个文件夹 · 共 " + p.screenCount
                + " 屏" + (p.lockedCount > 0 ? "（锁定 " + p.lockedCount + " 屏）" : "")
                + " · 每屏最多 " + LayoutPlan.slotsForSpan(p.folderSpan) + " 个"
                + "（文件夹占 " + p.folderSpan + "×" + p.folderSpan + "）");
        for (DeskPlan.Bucket b : p.buckets) {
            groupBox.addView(buildBucket(b));
        }

        // 兜底组太大的时候提醒一句：这是词典没覆盖到，不是用户的问题
        for (DeskPlan.Bucket b : p.buckets) {
            if (AppClassifier.GROUP_OTHER.equals(b.group) && b.size() >= 10) {
                groupBox.addView(note("「" + b.group + "」里是两套规则都没命中的 "
                        + b.size() + " 个应用。可以先按功能整理，再把这一组手工拆开。"));
                break;
            }
        }

        btnApply.setEnabled(true);
        btnApply.setText("应用方案 · 整理 " + p.total + " 个图标");
        tvApplyHint.setText("按「" + (deskScheme == null ? "当前方案" : deskScheme.title)
                + "」写入 " + p.buckets.size() + " 个文件夹 · 写入前自动备份");
        applyBar.setVisibility(View.VISIBLE);
    }

    /** 一个分组的卡片：色条 + 名称 + 数量 + 屏号，下面是图标缩略。 */
    private View buildBucket(final DeskPlan.Bucket b) {
        LinearLayout block = new LinearLayout(act);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setBackgroundResource(R.drawable.bg_stat);
        block.setPadding(dp(11), dp(10), dp(11), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        block.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(act);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(3), dp(15));
        dotLp.rightMargin = dp(9);
        dot.setLayoutParams(dotLp);
        dot.setBackgroundColor(isColorGroup(b.group)
                ? IconPalette.colorOfGroup(b.group) : colorFor(b.group));
        head.addView(dot);

        TextView title = new TextView(act);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tp);
        title.setText(b.group);
        title.setTextColor(act.getColor(R.color.text_primary));
        title.setTextSize(14);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(title);

        TextView count = new TextView(act);
        count.setText(b.size() + " 个");
        count.setTextColor(act.getColor(R.color.text_tertiary));
        count.setTextSize(11);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.rightMargin = dp(8);
        count.setLayoutParams(cp);
        head.addView(count);

        head.addView(buildScreenChip(b));
        block.addView(head);

        final LinearLayout icons = new LinearLayout(act);
        icons.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(8);
        icons.setLayoutParams(ip);
        block.addView(icons);

        final TextView toggle = new TextView(act);
        toggle.setTextSize(11);
        toggle.setTextColor(act.getColor(R.color.accent));
        LinearLayout.LayoutParams tgp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tgp.topMargin = dp(7);
        toggle.setLayoutParams(tgp);
        block.addView(toggle);

        final boolean[] expanded = {false};
        final Runnable paint = () -> {
            icons.removeAllViews();
            paintIcons(icons, b, expanded[0]);
            if (b.size() <= ICONS_PER_ROW) {
                toggle.setVisibility(View.GONE);
            } else {
                toggle.setVisibility(View.VISIBLE);
                toggle.setText(expanded[0]
                        ? "收起 ⌃"
                        : "展开全部 " + b.size() + " 个 ⌄");
            }
        };
        paint.run();
        toggle.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            paint.run();
        });
        return block;
    }

    private void paintIcons(LinearLayout box, DeskPlan.Bucket b, boolean all) {
        int limit = all ? b.size() : Math.min(b.size(), ICONS_PER_ROW);
        LinearLayout row = null;
        for (int i = 0; i < limit; i++) {
            if (i % ICONS_PER_ROW == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) {
                    rp.topMargin = dp(6);
                }
                row.setLayoutParams(rp);
                box.addView(row);
            }
            ImageView iv = new ImageView(act);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
            ilp.rightMargin = dp(6);
            iv.setLayoutParams(ilp);
            Drawable d = iconOf(b.members.get(i).pkg);
            if (d != null) {
                iv.setImageDrawable(d);
            }
            row.addView(iv);
        }
        // 折叠时把"还有多少个没显示"标出来，而不是无声截断
        if (!all && b.size() > limit) {
            TextView rest = new TextView(act);
            rest.setText("+" + (b.size() - limit));
            rest.setTextSize(11);
            rest.setTextColor(act.getColor(R.color.text_tertiary));
            rest.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(rest);
        }
    }

    private View buildScreenChip(final DeskPlan.Bucket b) {
        boolean pinned = plan.isPinned(b.group);
        TextView chip = new TextView(act);
        // 钉了第 6 屏却排到了第 3 屏时必须写出来。只显示实际屏号会让人以为钉屏没生效，
        // 只显示钉的屏号又是骗人——两个都给，一眼能看出差异在哪。
        int pin = pinned ? plan.pinOf(b.group) : 0;
        String label = b.screen > 0 ? "第 " + b.screen + " 屏" : "未排";
        if (pin > 0 && pin != b.screen) {
            label += "（钉 " + pin + "）";
        }
        chip.setText(label);
        chip.setTextSize(11);
        chip.setTypeface(null, Typeface.BOLD);
        chip.setPadding(dp(9), dp(4), dp(9), dp(4));
        chip.setBackgroundResource(pinned ? R.drawable.bg_pill_accent : R.drawable.bg_pill_idle);
        chip.setTextColor(act.getColor(pinned ? R.color.accent : R.color.text_secondary));
        chip.setOnClickListener(v -> pickScreen(b.group));
        return chip;
    }

    /** 改屏号：改的是配置，改完整份重算——钉屏会挤动别的组，不是只挪这一行。 */
    private void pickScreen(final String group) {
        if (desk == null) {
            return;
        }
        final int max = Math.max(plan.maxScreen(), 1) + 1;
        final List<String> items = new ArrayList<>();
        items.add("自动（按顺序排）");
        for (int i = 1; i <= max; i++) {
            if (i == 1 && plan.firstScreenBlank()) {
                continue;
            }
            // 锁定屏不放新文件夹，钉上去也不会生效
            if (plan.isLocked(i)) {
                continue;
            }
            items.add("第 " + i + " 屏");
        }
        int checked = 0;
        if (plan.isPinned(group)) {
            int idx = items.indexOf("第 " + plan.screenOf(group) + " 屏");
            checked = idx >= 0 ? idx : 0;
        }
        new AlertDialog.Builder(act)
                .setTitle(group + " 放第几屏")
                .setSingleChoiceItems(items.toArray(new String[0]), checked, (d, which) -> {
                    int screen = which == 0 ? 0
                            : Integer.parseInt(items.get(which).replaceAll("[^0-9]", ""));
                    plan.setPin(group, screen);
                    d.dismiss();
                    rebuild();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ───────────────────────── 应用 ─────────────────────────

    private void confirmApply() {
        if (desk == null || desk.buckets.isEmpty() || busy) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("按「").append(deskScheme == null ? "当前方案" : deskScheme.title).append("」重排桌面：\n");
        int shown = 0;
        for (DeskPlan.Bucket b : desk.buckets) {
            if (shown++ >= 12) {
                sb.append("… 共 ").append(desk.buckets.size()).append(" 个文件夹\n");
                break;
            }
            sb.append("· ").append(b.group).append(" ").append(b.size())
                    .append(" 个 → 第 ").append(b.screen).append(" 屏\n");
        }
        sb.append("\n这会改动整张桌面（").append(desk.total).append(" 个图标")
                .append(desk.lockedCount > 0
                        ? "，跳过锁定的 " + desk.lockedCount + " 屏" : "")
                .append("）。动手前会自动拍一张「整理前」快照，随时能退回去。");
        if (desk.readRows > 0) {
            sb.append("\n\n桌面库读到 ").append(desk.readRows).append(" 行，其中 ")
                    .append(desk.total).append(" 个图标参与整理，其余 ")
                    .append(desk.skippedRows)
                    .append(" 行原地保留（热区 / 小组件 / 文件夹本体 / 锁定屏）。");
            if (desk.skippedRows > desk.total) {
                sb.append("\n注意：未参与整理的条目比图标还多。如果桌面上明显不止 ")
                        .append(desk.total)
                        .append(" 个应用，说明大量图标被判成了「非应用」，请把这几行数字发我。");
            }
        }
        if (desk.unknownCount > 0) {
            sb.append("\n\n还有 ").append(desk.unknownCount)
                    .append(" 个应用没认出类别，会集中在「其它」；认不出的系统预装已归到「系统」。");
        }

        new AlertDialog.Builder(act)
                .setTitle("整理 " + desk.total + " 个图标？")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("开始整理", (d, w) -> doApply())
                .show();
    }

    private void doApply() {
        if (busy) {
            return;
        }
        busy = true;
        btnApply.setEnabled(false);
        btnLoad.setEnabled(false);
        tvStatus.setText("正在确认 root 授权…");
        tvApplyHint.setText("没授权的话，去「超级用户列表」里给 Deskly 打开开关");

        final AppClassifier.Scheme scheme = plan.scheme();
        final boolean blank = plan.firstScreenBlank();
        final Map<String, Integer> pins = new LinkedHashMap<>(plan.pins());
        final java.util.Set<Integer> locks = new java.util.HashSet<>(plan.lockedScreens());
        final boolean keepWidgets = plan.keepOrphanWidgets();

        new Thread(() -> {
            String err = null;
            DeskWriter.Result res = null;
            File copy = null;
            try {
                // 写入前先确认 root。这一步以前不存在，代价很直接：没授权时
                // DbCopy.pull 只返回 null，界面给的是「读取最新布局失败」——
                // 既没说清是缺授权，也没告诉用户该去哪点，于是大家只能猜。
                // 这里把「超级用户列表」这条唯一有效的路径摆到对话框里。
                Shell.Probe probe = Shell.probe(25);
                if (!probe.ok()) {
                    act.runOnUiThread(() -> {
                        busy = false;
                        if (act.isFinishing() || act.isDestroyed()) {
                            return;
                        }
                        btnApply.setEnabled(true);
                        btnLoad.setEnabled(true);
                        tvStatus.setText(probe.headline());
                        tvApplyHint.setText("还没有 root 授权，桌面未被改动");
                        applyBar.setVisibility(View.VISIBLE);
                        showRootDialog(probe);
                    });
                    return;
                }
                act.runOnUiThread(() -> {
                    if (!act.isFinishing() && !act.isDestroyed()) {
                        tvStatus.setText("正在写入…（桌面会闪一下，随后自动回到桌面）");
                        tvApplyHint.setText("正在写入，别退出 App…");
                    }
                });
                // 重读一次再算：用户可能刚在桌面上拖过图标，按旧模型写就是错的
                copy = DbCopy.pull(act, pkg);
                if (copy == null) {
                    err = "读取最新布局失败，已中止（桌面未被改动）";
                } else {
                    DesktopModel fresh = DesktopModel.read(copy, act, pkg);
                    DeskPlan p = DeskPlan.build(act, fresh, scheme, blank, pins, locks, keepWidgets);
                    if (p.buckets.isEmpty()) {
                        err = "桌面上没有可整理的应用图标";
                    } else {
                        res = DeskWriter.apply(act, pkg, p);
                    }
                }
            } catch (Throwable t) {
                err = "整理出错：" + t;
            } finally {
                DbCopy.delete(copy);
            }

            final String fErr = err;
            final DeskWriter.Result fRes = res;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnApply.setEnabled(true);
                btnLoad.setEnabled(true);
                if (fErr != null) {
                    tvStatus.setText(fErr);
                    tvApplyHint.setText("没有写入，桌面未被改动");
                    applyBar.setVisibility(View.VISIBLE);
                    dialog("没能整理", fErr);
                    return;
                }
                if (fRes == null) {
                    tvStatus.setText("未知结果");
                    return;
                }
                if (fRes.ok) {
                    tvStatus.setText("完成。" + fRes.msg + "\n整理前快照：" + fRes.snapshotId
                            + "\n桌面已重排，可以再点一次「重新分析」看新结果。");
                    toast(fRes.msg);
                    clearAppliedState();
                } else if (fRes.wrote) {
                    // 已经写进真库了，只是核对没过。桌面此刻多半已经是新样子——
                    // 这时若还留着旧预览，用户很容易再点一次写入，把刚整理好的再改一遍。
                    tvStatus.setText(fRes.msg);
                    clearAppliedState();
                    dialog("已写入，但有对不上的地方", fRes.msg
                            + "\n\n先看一眼桌面：已经是新的就不用管；不是的话用快照 "
                            + fRes.snapshotId + " 回滚。");
                } else {
                    tvStatus.setText(fRes.msg);
                    applyBar.setVisibility(View.VISIBLE);
                    dialog("整理失败", fRes.msg
                            + (fRes.snapshotId.isEmpty() ? "" : "\n\n可用快照 " + fRes.snapshotId + " 回滚。"));
                }
            });
        }, "deskly-desk-apply").start();
    }

    /**
     * 桌面已经被写过之后，旧的模型与预览全部作废。
     *
     * <p>不清掉的后果很具体：界面上还摆着"整理前"的分组与方案，用户以为没生效，
     * 再点一次写入——那份计划算的是旧桌面，第二次写只会把刚整理好的结果搞乱。
     */
    private void clearAppliedState() {
        model = null;
        desk = null;
        deskScheme = null;
        groupBox.removeAllViews();
        cardGroups.setVisibility(View.GONE);
        cardStats.setVisibility(View.GONE);
        tvSummary.setText("");
        applyBar.setVisibility(View.GONE);
        tvNote.setVisibility(View.GONE);
        plan.renderPlan(null);
        btnLoad.setText("重新分析");
    }

    // ───────────────────────── 工具 ─────────────────────────

    private boolean isColorGroup(String name) {
        return IconPalette.GROUP_COLORS.containsKey(name);
    }

    private Drawable iconOf(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            return act.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private View note(String text) {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setLineSpacing(dp(2), 1f);
        tv.setTextColor(act.getColor(R.color.text_secondary));
        return tv;
    }

    private int colorFor(String group) {
        switch (group) {
            case "社交":
                return 0xFF3B82F6;
            case "影音":
                return 0xFFEF4444;
            case "游戏":
                return 0xFF8B5CF6;
            case "购物":
                return 0xFFF59E0B;
            case "金融":
                return 0xFF10B981;
            case "出行":
                return 0xFF06B6D4;
            case "办公学习":
                return 0xFF6366F1;
            case "工具":
                return 0xFF64748B;
            case "生活":
                return 0xFFEC4899;
            case "摄影":
                return 0xFF14B8A6;
            case "资讯":
                return 0xFF0EA5E9;
            case "系统":
                return 0xFF94A3B8;
            default:
                return 0xFF9CA3AF;
        }
    }

    private void dialog(String title, String msg) {
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    /**
     * 应用方案时没拿到 root 的专用提示。
     *
     * <p>为什么单独做一个对话框、而不是复用 {@link #dialog}：整理这条路唯一的解法是
     * <b>去「超级用户列表」里授权</b>，用户需要从弹窗直接跳过去；只有一个"知道了"
     * 等于让他自己回忆 Magisk 在哪。
     *
     * <p>三个按钮：知道了 / 打开 Magisk / 重试。「重试」会重新走一遍 doApply，
     * 所以用户在 Magisk 里点完允许切回来，按一下就能接着整理，不用重新分析。
     */
    private void showRootDialog(Shell.Probe probe) {
        if (act.isFinishing() || act.isDestroyed()) {
            return;
        }
        AlertDialog d = new AlertDialog.Builder(act)
                .setTitle("需要 root 授权")
                .setMessage("应用方案会改写桌面的数据库，必须先拿到 root。\n\n"
                        + probe.report())
                .setNegativeButton("知道了", null)
                .setNeutralButton("打开 Magisk", (x, w) -> openMagisk())
                .setPositiveButton("重试", (x, w) -> doApply())
                .create();
        d.show();
        // 重试是这里的主路径，把它做成实心按钮，别让人在三个灰字里找。
        d.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF4F46E5);
    }

    /** 直接拉起 Magisk 管理器；找不到就提示手动打开，不静默失败。 */
    private void openMagisk() {
        try {
            Intent i = act.getPackageManager().getLaunchIntentForPackage("com.topjohnwu.magisk");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("没找到 Magisk，请从桌面手动打开，进入「超级用户」页给 Deskly 授权");
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
