package com.coby.deskly.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.coby.deskly.R;
import com.coby.deskly.backup.BackupStore;
import com.coby.deskly.backup.LauncherDb;
import com.coby.deskly.backup.Snapshot;
import com.coby.deskly.core.Shell;
import com.coby.deskly.organize.DesktopModel;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「备份与恢复」区块的控制器。
 *
 * <p>只做编排：所有 root 操作都在后台线程，所有界面更新都回主线程。
 * 这里刻意不缓存"root 是否可用"——用户可以中途去 Magisk 里授权，重新检测必须真实重跑。
 *
 * <p><b>一条硬规则：按钮永远可以点。</b>以前「备份当前桌面」在没拿到 root 时是
 * {@code setEnabled(false)}，用户点上去毫无反应、也没有任何解释，只能以为应用坏了。
 * 现在按钮始终可点，点了必定有回应：要么开始干活并改文案，要么当场说清为什么不行。
 *
 * <p>第二条硬规则：后台线程里的任何异常都必须把 {@code busy} 复位。否则一次未捕获的
 * 异常就会让 {@code busy} 永远为 true，之后所有点击都被静默丢弃——症状和按钮被禁用一模一样。
 *
 * <p><b>0.12.0 起，备份明细精确到每一个图标。</b>快照本来就是整库拷贝，信息一直都在，
 * 只是以前只露一行「12 项」——用户没法判断这份备份到底是不是自己想要的。现在点开一份
 * 快照就能看到：每个文件夹在第几屏、里面装了哪些应用（带图标）。读取走的是快照自己的
 * 那份库副本，不需要 root，也不会碰设备上正在用的库。
 *
 * <p>监听器一律用 lambda：minSdk 26 下 D8 走 invoke-custom，不会为每个监听器生成
 * 一个匿名内部类，dex 里能少一批 class_def。
 */
public final class BackupSection {

    private static final String LABEL_BACKUP = "备份当前桌面";
    private static final String LABEL_PROBING = "检测中…";
    private static final String LABEL_BACKING = "备份中…";
    private static final String LABEL_RESTORING = "恢复中…";
    private static final String TOGGLE_OPEN = "展开明细 ⌄";
    private static final String TOGGLE_CLOSE = "收起明细 ⌃";

    /** 用户主动点按钮触发的探测：短超时，别让人盯着不动的界面等一分半。 */
    private static final int PROBE_TIMEOUT_SEC = 20;

    private final Activity act;
    private final String pkg;

    private final TextView tvStatus;
    private final TextView tvHead;
    /** 卡片收起时的摘要行：有几份备份、最近一份是什么时候。 */
    private final TextView tvHint;
    private final LinearLayout listBox;
    private final Button btnCreate;

    private volatile boolean busy = false;
    /** 上一次探测是否拿到 root。onResume 靠它判断要不要自动重查。 */
    private volatile boolean lastOk = false;
    private volatile long lastAttemptAt = 0;
    private java.util.function.Consumer<Shell.Probe> probeListener;
    private List<Snapshot> snapshots;
    /** 状态行的原始颜色，回执用别的颜色写完要能改回来。 */
    private final int defaultColor;
    /** 上次探测失败时的一句话原因，恢复按钮点了不响应时用它解释。 */
    private volatile String rootProbeHint;

    public BackupSection(Activity act, String pkg, TextView tvStatus, TextView tvHead,
                         TextView tvHint, LinearLayout listBox, Button btnCreate) {
        this.act = act;
        this.pkg = pkg;
        this.tvStatus = tvStatus;
        this.tvHead = tvHead;
        this.tvHint = tvHint;
        this.listBox = listBox;
        this.btnCreate = btnCreate;
        this.defaultColor = tvStatus.getCurrentTextColor();
        btnCreate.setText(LABEL_BACKUP);
        btnCreate.setOnClickListener(v -> createSnapshot());
    }

    // ───────────────────────── 检测 / 刷新 ─────────────────────────

    public void refresh() {
        refresh(null, false);
    }

    /**
     * 刷新检测结果，并可带回一条操作回执。
     *
     * <p>回执是后加的重点：以前备份完只在屏幕底部弹一个 Toast，几秒就没了，
     * 列表也没有任何变化提示——用户会怀疑到底存没存。现在回执写在状态行最前面、带颜色，
     * 直到下一次操作才被替换掉。
     *
     * @param flash   操作回执，null 表示普通刷新
     * @param flashOk true 用成功色，false 用警示色
     */
    public void refresh(String flash, boolean flashOk) {
        if (busy) {
            return;
        }
        busy = true;
        lastAttemptAt = SystemClock.elapsedRealtime();
        // 按钮不禁用，只换文案：禁用之后点击连事件都不触发，用户看到的就是"没反应"
        btnCreate.setText(flash == null ? LABEL_PROBING : LABEL_BACKUP);
        tvStatus.setTextColor(defaultColor);
        tvStatus.setText("正在检测 root 与桌面数据库…");
        if (tvHint != null && snapshots == null) {
            // 首次检测才改摘要，之后每次刷新都改会让收起的那行闪一下
            tvHint.setText("正在检测…");
        }

        new Thread(() -> {
            boolean ready = false;
            List<Snapshot> list;
            String text;

            try {
                Shell.Probe probe = Shell.probe(PROBE_TIMEOUT_SEC);
                lastOk = probe.ok();
                rootProbeHint = probe.ok() ? null : probe.headline();
                final Shell.Probe p = probe;

                StringBuilder sb = new StringBuilder();
                if (!probe.ok()) {
                    // 关键：不再把所有失败都说成"未获得 root"。授权失败、守护进程崩了、
                    // su 不存在、等授权超时，是四种完全不同的事，给的动作也不一样。
                    sb.append(probe.report());
                    if (probe.kind != Shell.Probe.NO_SU) {
                        sb.append("\n\n不依赖 root 的部分仍可用：分类、预览、分组调整。");
                    }
                } else {
                    LauncherDb db = LauncherDb.discover(pkg);
                    if (db == null) {
                        sb.append("已获得 root，但没找到桌面数据库。\n")
                                .append(LauncherDb.lastError == null ? "" : LauncherDb.lastError);
                    } else {
                        // 基线只在这里补：已经存在就一个字节都不动
                        Snapshot origin = BackupStore.origin(act);
                        if (origin == null) {
                            tvStatus.post(() -> tvStatus.setText("首次运行，正在保存初始方案…"));
                            origin = BackupStore.ensureOrigin(act, pkg);
                        }
                        ready = true;
                        sb.append(db.path).append("\n")
                                .append(LauncherDb.human(db.size))
                                .append(" · 属主 ").append(db.owner)
                                .append(" · ").append(db.mode);
                        if (origin != null) {
                            sb.append("\n初始方案已保存 · ").append(origin.timeText());
                        } else {
                            sb.append("\n初始方案保存失败：")
                                    .append(BackupStore.lastError == null ? "未知原因" : BackupStore.lastError);
                        }
                    }
                }

                // 快照放在应用私有目录里，列出来不需要 root。所以即使当下没有 root，
                // 也要把"你有哪些备份"摆给用户看——能不能恢复是另一件事，但不该假装没有备份。
                list = BackupStore.list(act);
                text = sb.toString();

                final boolean canWrite = ready;
                final List<Snapshot> fList = list;
                act.runOnUiThread(() -> {
                    busy = false;
                    if (act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    btnCreate.setText(LABEL_BACKUP);
                    if (flash != null && !flash.isEmpty()) {
                        tvStatus.setText(flash + "\n\n" + text);
                        tvStatus.setTextColor(act.getColor(flashOk ? R.color.ok : R.color.warn));
                    } else {
                        tvStatus.setText(text);
                        tvStatus.setTextColor(defaultColor);
                    }
                    snapshots = fList;
                    renderList(canWrite);
                    if (probeListener != null) {
                        probeListener.accept(p);
                    }
                });
            } catch (Throwable t) {
                // 没有这段的话，一次异常就会让 busy 永远为 true，之后每次点击都被静默丢弃
                final String why = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
                final List<Snapshot> fallback = BackupStore.list(act);
                act.runOnUiThread(() -> {
                    busy = false;
                    if (act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    btnCreate.setText(LABEL_BACKUP);
                    tvStatus.setTextColor(act.getColor(R.color.warn));
                    tvStatus.setText("✗ 检测过程出错 · " + why
                            + "\n\n到「运行引导」里点「重新检测」再试一次。");
                    snapshots = fallback;
                    renderList(false);
                });
            }
        }, "deskly-backup-probe").start();
    }

    /**
     * 回到前台时自动重查一次，但只在"上次没拿到 root"时才查。
     *
     * <p>用户去 Magisk 点「允许」再切回来，是本功能最常见的路径。以前回到应用还停在
     * "未获得 root"，因为检测只在 onCreate 跑过一次——用户明明授权了，界面却说没有。
     *
     * <p>节流 8 秒：避免来回切页面时反复触发 su（授权方式是"每次询问"的话会连环弹框）。
     */
    public void recheckIfDegraded() {
        if (busy || lastOk) {
            return;
        }
        if (SystemClock.elapsedRealtime() - lastAttemptAt < 8000) {
            return;
        }
        refresh();
    }

    /** 当前 root 是否可用，给上层决定要不要提示。 */
    public boolean rootOk() {
        return lastOk;
    }

    /** 每次探测完都回调一次，让上层（整理页的告警条、运行引导）能跟着变。 */
    public void setOnProbeListener(java.util.function.Consumer<Shell.Probe> l) {
        this.probeListener = l;
    }

    // ───────────────────────── 创建 ─────────────────────────

    /**
     * 点「备份当前桌面」。
     *
     * <p>三条分支，但共同点是<b>点下去立刻有反馈</b>：
     * <ol>
     *   <li>正在忙 → 立刻提示"请稍候"</li>
     *   <li>已知有 root → 直接备份</li>
     *   <li>已知没 root → 先重探一次（用户可能刚在 Magisk 里授权），成功就接着备份，
     *       失败就把原因摊开说清楚。不能因为"上次没 root"就拒绝——那正是最常见的场景。</li>
     * </ol>
     */
    private void createSnapshot() {
        if (busy) {
            toast("正在忙，请稍候…");
            return;
        }
        if (lastOk) {
            doBackup();
        } else {
            probeThenBackup();
        }
    }

    /** 先探一次 root，成功则直接备份，失败则把原因说清楚。 */
    private void probeThenBackup() {
        busy = true;
        btnCreate.setText(LABEL_PROBING);
        tvStatus.setTextColor(defaultColor);
        tvStatus.setText("正在重新检测 root…（第一次会弹授权框，点「允许」即可）");

        new Thread(() -> {
            Shell.Probe probe;
            try {
                probe = Shell.probe(PROBE_TIMEOUT_SEC);
            } catch (Throwable t) {
                final String why = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
                act.runOnUiThread(() -> {
                    busy = false;
                    if (act.isFinishing() || act.isDestroyed()) {
                        return;
                    }
                    btnCreate.setText(LABEL_BACKUP);
                    tvStatus.setTextColor(act.getColor(R.color.warn));
                    tvStatus.setText("✗ 检测 root 出错 · " + why);
                });
                return;
            }

            lastOk = probe.ok();
            rootProbeHint = probe.ok() ? null : probe.headline();
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnCreate.setText(LABEL_BACKUP);
                if (probeListener != null) {
                    probeListener.accept(probe);
                }
                if (probe.ok()) {
                    // 刚授权成功：接着把备份做完，别让用户再点一次
                    doBackup();
                } else {
                    tvStatus.setTextColor(act.getColor(R.color.warn));
                    tvStatus.setText("✗ 没能备份 · " + probe.headline() + "\n\n" + probe.advice());
                    showDialog("还不能备份", probe.report());
                }
            });
        }, "deskly-backup-precheck").start();
    }

    private void doBackup() {
        if (busy) {
            toast("正在忙，请稍候…");
            return;
        }
        busy = true;
        btnCreate.setText(LABEL_BACKING);
        tvStatus.setTextColor(defaultColor);
        tvStatus.setText("正在备份当前桌面…");

        new Thread(() -> {
            Snapshot s = null;
            String err = null;
            int total = 0;
            try {
                s = BackupStore.create(act, pkg, Snapshot.LABEL_MANUAL);
                err = BackupStore.lastError;
                total = BackupStore.list(act).size();
            } catch (Throwable t) {
                err = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
            }
            final Snapshot fs = s;
            final String fe = err;
            final int ft = total;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnCreate.setText(LABEL_BACKUP);
                if (fs == null) {
                    String why = fe == null ? "未知原因" : fe;
                    toast("备份失败：" + why);
                    refresh("✗ 备份失败 · " + why, false);
                    return;
                }
                toast("已备份当前桌面 · " + fs.itemText());
                // 回执写在状态行最前面并染色：Toast 一闪就没了，用户会怀疑到底存没存
                refresh("✓ 已备份当前桌面 · " + fs.timeText() + " · " + fs.sizeText()
                        + " · " + fs.itemText() + "（共 " + ft + " 份）", true);
            });
        }, "deskly-backup-create").start();
    }

    // ───────────────────────── 列表 ─────────────────────────

    private void renderList(boolean canRestore) {
        listBox.removeAllViews();
        int n = snapshots == null ? 0 : snapshots.size();
        tvHead.setText(n == 0 ? "还没有备份" : "已有 " + n + " 份备份 · 点一份可看每个图标");
        if (tvHint != null) {
            // 收起时的摘要：不展开也知道有没有备份、最后一份是什么时候
            tvHint.setText(n == 0 ? "还没有备份 · 展开可存一份"
                    : n + " 份备份 · 最近 " + snapshots.get(0).timeText());
        }

        if (n == 0) {
            TextView empty = new TextView(act);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(10);
            empty.setLayoutParams(lp);
            empty.setText(canRestore
                    ? "点上面「备份当前桌面」存一份。每份备份都记下了这一屏上每个图标的位置。"
                    : "恢复需要 root，先按「运行引导」把 root 修好。");
            empty.setTextSize(11);
            empty.setTextColor(act.getColor(R.color.text_tertiary));
            listBox.addView(empty);
            return;
        }
        for (Snapshot s : snapshots) {
            listBox.addView(buildRow(s, canRestore));
        }
    }

    /**
     * 一行 = 一份快照：头部是摘要与动作，下面可展开成逐图标明细。
     *
     * <p>明细按需读取：展开那一刻才去打开这份快照的库副本，读完缓存在这个容器里，
     * 收起再展开不重复解析。读的是应用私有目录里的副本，不需要 root。
     */
    private View buildRow(final Snapshot s, boolean canRestore) {
        LinearLayout wrap = new LinearLayout(act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wp.topMargin = dp(14);
        wrap.setLayoutParams(wp);

        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        wrap.addView(row);

        View bar = new View(act);
        bar.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(34)));
        bar.setBackgroundColor(s.isOrigin() ? act.getColor(R.color.accent)
                : Snapshot.LABEL_PRE_RESTORE.equals(s.label)
                ? act.getColor(R.color.warn) : act.getColor(R.color.text_tertiary));
        row.addView(bar);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = dp(9);
        mid.setLayoutParams(mp);
        row.addView(mid);

        TextView info = new TextView(act);
        info.setText(s.summary());
        info.setTextColor(act.getColor(R.color.text_primary));
        info.setTextSize(12);
        info.setLineSpacing(dp(2), 1f);
        mid.addView(info);

        final LinearLayout detail = new LinearLayout(act);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setVisibility(View.GONE);

        final TextView toggle = new TextView(act);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tp.topMargin = dp(4);
        toggle.setLayoutParams(tp);
        toggle.setText(TOGGLE_OPEN);
        toggle.setTextSize(11);
        toggle.setTextColor(act.getColor(R.color.accent));
        toggle.setPadding(0, dp(4), dp(8), dp(4));
        toggle.setOnClickListener(v -> toggleDetail(s, detail, toggle));
        // 开关放在明细上面：展开之后列表长在它下面，而不是把开关顶到列表末尾去
        mid.addView(toggle);
        mid.addView(detail);

        // 「恢复」做成实心胶囊：它比「删除」重要得多，不该和它长得一样重
        row.addView(buildPill("恢复", canRestore, v -> {
            if (!lastOk) {
                showDialog("现在没法恢复", "恢复要写回系统数据库，必须先有 root。\n\n"
                        + (rootProbeHint == null ? "" : rootProbeHint + "\n\n")
                        + "在「运行引导」里重新检测一次再试。");
                return;
            }
            confirmRestore(s);
        }));
        if (!s.isOrigin()) {
            row.addView(buildAction("删除", R.color.text_tertiary, v -> confirmDelete(s)));
        }
        return wrap;
    }

    /** 带底色的实心按钮；不可用时压暗但仍可点——点了会说清为什么不能用。 */
    private TextView buildPill(String text, boolean enabled, View.OnClickListener l) {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(13), dp(6), dp(13), dp(6));
        tv.setBackgroundResource(R.drawable.bg_pill_accent);
        tv.setTextColor(act.getColor(R.color.accent));
        tv.setAlpha(enabled ? 1f : 0.45f);
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView buildAction(String text, int colorRes, View.OnClickListener l) {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(act.getColor(colorRes));
        // 文字按钮也要给足触摸目标，否则一行里很容易点错到相邻的「删除」
        tv.setPadding(dp(8), dp(8), dp(8), dp(8));
        tv.setOnClickListener(l);
        return tv;
    }

    // ───────────────────────── 每份备份的明细 ─────────────────────────

    private void toggleDetail(final Snapshot s, final LinearLayout box, final TextView toggle) {
        if (box.getVisibility() == View.VISIBLE) {
            box.setVisibility(View.GONE);
            toggle.setText(TOGGLE_OPEN);
            return;
        }
        if (box.getChildCount() > 0) {
            box.setVisibility(View.VISIBLE);
            toggle.setText(TOGGLE_CLOSE);
            return;
        }
        toggle.setText("读取中…");
        new Thread(() -> {
            DesktopModel model = null;
            String err = null;
            Map<String, Drawable> icons = new HashMap<>();
            try {
                File db = s.dbFile(BackupStore.root(act));
                model = DesktopModel.read(db, act, pkg);
                PackageManager pm = act.getPackageManager();
                for (DesktopModel.Folder f : model.folders) {
                    for (DesktopModel.Item it : f.members) {
                        loadIcon(pm, icons, it.pkg);
                    }
                }
                for (DesktopModel.Item it : model.looseOnDesktop) {
                    loadIcon(pm, icons, it.pkg);
                }
                for (DesktopModel.Item it : model.hotseat) {
                    loadIcon(pm, icons, it.pkg);
                }
            } catch (Throwable t) {
                err = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
            }
            final DesktopModel fm = model;
            final String fe = err;
            final Map<String, Drawable> fi = icons;
            act.runOnUiThread(() -> {
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                box.removeAllViews();
                if (fm == null) {
                    TextView bad = new TextView(act);
                    bad.setText("这份快照读不出来：" + fe);
                    bad.setTextSize(11);
                    bad.setTextColor(act.getColor(R.color.warn));
                    bad.setPadding(0, dp(6), 0, 0);
                    box.addView(bad);
                    toggle.setText("明细读不出来");
                } else {
                    renderDetail(fm, fi, box);
                    toggle.setText(TOGGLE_CLOSE);
                }
                box.setVisibility(View.VISIBLE);
            });
        }, "deskly-snapshot-read").start();
    }

    /** 把一份快照摊开：文件夹 → 第几屏 → 里面的每个图标。 */
    private void renderDetail(DesktopModel m, Map<String, Drawable> icons, LinearLayout box) {
        int apps = countApps(m);
        TextView sum = new TextView(act);
        sum.setText("共 " + m.folders.size() + " 个文件夹 · " + apps + " 个图标 · "
                + Math.max(m.screenCount, 1) + " 屏");
        sum.setTextSize(11);
        sum.setTextColor(act.getColor(R.color.text_tertiary));
        sum.setPadding(0, dp(8), 0, 0);
        box.addView(sum);

        // 文件夹按屏号、再按格位排，跟桌面上看到的顺序一致
        List<DesktopModel.Folder> folders = new ArrayList<>(m.folders);
        Collections.sort(folders, (a, b) -> {
            if (a.screen != b.screen) {
                return a.screen - b.screen;
            }
            if (a.cellY != b.cellY) {
                return a.cellY - b.cellY;
            }
            return a.cellX - b.cellX;
        });
        for (DesktopModel.Folder f : folders) {
            String name = f.title == null || f.title.isEmpty() ? "未命名文件夹" : f.title;
            addGroup(box, "「" + name + "」  第 " + Math.max(f.screen, 1) + " 屏 · "
                    + f.members.size() + " 个", f.members, icons);
        }

        // 散落在桌面上的图标按屏归组
        Map<Integer, List<DesktopModel.Item>> byScreen = new java.util.TreeMap<>();
        for (DesktopModel.Item it : m.looseOnDesktop) {
            if (!it.isApp()) {
                continue;
            }
            List<DesktopModel.Item> list = byScreen.get(it.screen);
            if (list == null) {
                list = new ArrayList<>();
                byScreen.put(it.screen, list);
            }
            list.add(it);
        }
        for (Map.Entry<Integer, List<DesktopModel.Item>> e : byScreen.entrySet()) {
            addGroup(box, "散落在桌面 · 第 " + Math.max(e.getKey(), 1) + " 屏 · "
                    + e.getValue().size() + " 个", e.getValue(), icons);
        }

        List<DesktopModel.Item> hot = new ArrayList<>();
        for (DesktopModel.Item it : m.hotseat) {
            if (it.isApp()) {
                hot.add(it);
            }
        }
        if (!hot.isEmpty()) {
            addGroup(box, "底部固定栏 · " + hot.size() + " 个", hot, icons);
        }
    }

    private void addGroup(LinearLayout box, String title, List<DesktopModel.Item> items,
                          Map<String, Drawable> icons) {
        TextView head = new TextView(act);
        head.setText(title);
        head.setTextSize(12);
        head.setTypeface(null, android.graphics.Typeface.BOLD);
        head.setTextColor(act.getColor(R.color.text_primary));
        head.setPadding(0, dp(10), 0, dp(2));
        box.addView(head);

        LinearLayout current = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % 3 == 0) {
                current = new LinearLayout(act);
                current.setOrientation(LinearLayout.HORIZONTAL);
                current.setPadding(0, dp(2), 0, 0);
                box.addView(current);
            }
            current.addView(buildChip(items.get(i), icons, i % 3 == 2));
        }
        // 最后一行不满 3 个时补空位，避免图标被拉宽
        if (current != null) {
            for (int i = items.size() % 3; i > 0 && i < 3; i++) {
                View gap = new View(act);
                gap.setLayoutParams(new LinearLayout.LayoutParams(
                        0, dp(1), 1f));
                current.addView(gap);
            }
        }
    }

    private View buildChip(DesktopModel.Item it, Map<String, Drawable> icons, boolean last) {
        LinearLayout chip = new LinearLayout(act);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (!last) {
            lp.rightMargin = dp(6);
        }
        chip.setLayoutParams(lp);

        ImageView iv = new ImageView(act);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(20), dp(20));
        iv.setLayoutParams(ip);
        Drawable d = it.pkg == null ? null : icons.get(it.pkg);
        if (d != null) {
            iv.setImageDrawable(d);
        } else {
            iv.setBackgroundResource(R.drawable.bg_pill_idle);
        }
        chip.addView(iv);

        TextView tv = new TextView(act);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(5);
        tv.setLayoutParams(tp);
        String name = it.title == null || it.title.isEmpty()
                ? (it.pkg == null || it.pkg.isEmpty() ? "未命名" : it.pkg) : it.title;
        tv.setText(name);
        tv.setTextSize(11);
        tv.setSingleLine(true);
        tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        tv.setTextColor(act.getColor(R.color.text_secondary));
        chip.addView(tv);

        return chip;
    }

    private void loadIcon(PackageManager pm, Map<String, Drawable> out, String pkgName) {
        if (pkgName == null || pkgName.isEmpty() || out.containsKey(pkgName)) {
            return;
        }
        Drawable d = null;
        try {
            d = pm.getApplicationIcon(pkgName);
        } catch (Throwable ignored) {
        }
        out.put(pkgName, d);
    }

    private int countApps(DesktopModel m) {
        int n = 0;
        for (DesktopModel.Folder f : m.folders) {
            for (DesktopModel.Item it : f.members) {
                if (it.isApp()) {
                    n++;
                }
            }
        }
        for (DesktopModel.Item it : m.looseOnDesktop) {
            if (it.isApp()) {
                n++;
            }
        }
        for (DesktopModel.Item it : m.hotseat) {
            if (it.isApp()) {
                n++;
            }
        }
        return n;
    }

    // ───────────────────────── 恢复 / 删除 ─────────────────────────

    private void confirmRestore(final Snapshot s) {
        new AlertDialog.Builder(act)
                .setTitle("恢复到「" + s.timeText() + "」？")
                .setMessage("桌面进程会被停止，当前布局将被这张快照覆盖。\n\n"
                        + "这一步不会先备份当前状态。要留个退路，请先返回列表点「备份当前桌面」。\n\n"
                        + "快照：" + s.itemText() + " · " + s.sizeText()
                        + (s.integrity.isEmpty() ? "" : "\n自检：" + s.integrity))
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复", (DialogInterface d, int w) -> doRestore(s))
                .show();
    }

    private void doRestore(final Snapshot s) {
        if (busy) {
            toast("正在忙，请稍候…");
            return;
        }
        busy = true;
        btnCreate.setText(LABEL_RESTORING);
        tvStatus.setTextColor(defaultColor);
        tvStatus.setText("正在恢复…（直接用这份快照覆盖，不再额外备份）");

        new Thread(() -> {
            BackupStore.RestoreResult r = null;
            String crash = null;
            try {
                r = BackupStore.restore(act, pkg, s);
            } catch (Throwable t) {
                crash = t.getClass().getSimpleName()
                        + (t.getMessage() == null ? "" : "：" + t.getMessage());
            }
            final BackupStore.RestoreResult fr = r;
            final String fc = crash;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnCreate.setText(LABEL_BACKUP);
                if (fr != null && fr.ok) {
                    toast(fr.msg);
                    refresh("✓ 已恢复到「" + s.timeText() + "」", true);
                } else {
                    String msg = fr != null ? fr.msg : ("恢复过程出错：" + fc);
                    showDialog("恢复失败", msg);
                    refresh("✗ 恢复失败 · " + msg, false);
                }
            });
        }, "deskly-backup-restore").start();
    }

    private void confirmDelete(final Snapshot s) {
        new AlertDialog.Builder(act)
                .setTitle("删除这张快照？")
                .setMessage(s.timeText() + " · " + s.sizeText()
                        + "\n\n只删除备份副本，不会动你现在的桌面。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (DialogInterface d, int w) -> {
                    BackupStore.delete(act, s);
                    refresh("✓ 已删除「" + s.timeText() + "」", true);
                })
                .show();
    }

    // ───────────────────────── 工具 ─────────────────────────

    private void showDialog(String title, String msg) {
        if (act.isFinishing() || act.isDestroyed()) {
            return;
        }
        new AlertDialog.Builder(act)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
