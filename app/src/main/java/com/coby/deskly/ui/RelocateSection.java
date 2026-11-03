package com.coby.deskly.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.coby.deskly.R;
import com.coby.deskly.backup.BackupStore;
import com.coby.deskly.backup.LauncherDb;
import com.coby.deskly.backup.LayoutWriter;
import com.coby.deskly.core.Shell;
import com.coby.deskly.organize.DesktopModel;
import com.coby.deskly.organize.FolderMatcher;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「散落图标归位」区块。
 *
 * <p>这是第一个真的会写桌面的功能，所以界面上刻意不做"一键搞定"：每条建议都摆出
 * 目标文件夹和<b>理由</b>，允许逐条改目标或取消。批量自动整理看着爽，但用户的桌面
 * 分类是长期积累的结果，机器猜错一次的代价远大于多点几下。
 *
 * <p>读和分析阶段完全不碰真库：先把库连同 WAL 拷到 app 私有目录读副本，读完即删。
 * 只有点「应用改动」才会走 {@link LayoutWriter} 写回，而它内部会先拍快照。
 */
public final class RelocateSection {

    private final Activity act;
    private final String pkg;
    private final TextView tvStatus;
    private final LinearLayout listBox;
    private final Button btnAnalyze;
    private final Button btnApply;

    private volatile boolean busy = false;
    /** 当前分析得到、且需要用户动手的行，null 表示还没分析过。 */
    private List<Row> rows;
    /** 位置已经合适的图标，默认折叠，展开才逐行渲染（160 多行全渲染会卡）。 */
    private List<FolderMatcher.Suggestion> quiet;
    private boolean quietShown = false;
    /** 展开区容器，每次重新分析都重建，避免旧行残留。 */
    private LinearLayout quietAnchor;
    private DesktopModel model;

    private static final class Row {
        DesktopModel.Item item;
        DesktopModel.Folder target;
        FolderMatcher.Suggestion sug;
        CheckBox cb;
        TextView tvReason;
        TextView tvTarget;
    }

    public RelocateSection(Activity act, String pkg, TextView tvStatus, LinearLayout listBox,
                           Button btnAnalyze, Button btnApply) {
        this.act = act;
        this.pkg = pkg;
        this.tvStatus = tvStatus;
        this.listBox = listBox;
        this.btnAnalyze = btnAnalyze;
        this.btnApply = btnApply;
        btnAnalyze.setOnClickListener(v -> analyze());
        btnApply.setOnClickListener(v -> confirmApply());
    }

    // ───────────────────────── 分析 ─────────────────────────

    private void analyze() {
        if (busy) {
            return;
        }
        busy = true;
        btnAnalyze.setEnabled(false);
        btnAnalyze.setText("分析中…");
        btnApply.setVisibility(View.GONE);
        tvStatus.setText("正在读取桌面布局…");

        new Thread(() -> {
            String err = null;
            DesktopModel m = null;
            List<FolderMatcher.Suggestion> sugs = null;
            File copy = null;
            try {
                Shell.Probe probe = Shell.probe();
                if (!probe.ok()) {
                    // 原因原样透出来：没授权 / 守护进程崩了 / 没有 su，处理方式完全不同
                    err = probe.report();
                } else {
                    copy = pullCopy();
                    if (copy == null) {
                        err = "无法读取桌面数据库：" + (LauncherDb.lastError == null
                                ? "未知原因" : LauncherDb.lastError);
                    } else {
                        m = DesktopModel.read(copy, act, pkg);
                        FolderMatcher matcher = new FolderMatcher(act, m);
                        sugs = matcher.suggestAll(m);
                    }
                }
            } catch (Throwable t) {
                err = "分析出错：" + t;
            } finally {
                if (copy != null) {
                    deleteWithSidecars(copy);
                }
            }

            final String fErr = err;
            final DesktopModel fModel = m;
            final List<FolderMatcher.Suggestion> fSugs = sugs;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnAnalyze.setEnabled(true);
                btnAnalyze.setText("重新分析");
                if (fErr != null) {
                    tvStatus.setText(fErr);
                    return;
                }
                model = fModel;
                render(fSugs);
            });
        }, "deskly-relocate-analyze").start();
    }

    /**
     * 把真库连同 -wal 拷到私有目录。
     *
     * <p>-wal 必须带上：桌面此刻可能有大几百 KB 的改动还没 checkpoint，
     * 只拷主库会读到一份"几分钟前"的布局，据此算出来的建议全是错的。
     * 拷完之后用 SQLite 打开时它自己会把 WAL 应用上去，不需要我们额外做什么。
     */
    private File pullCopy() {
        LauncherDb db = LauncherDb.discover(pkg);
        if (db == null) {
            return null;
        }
        File dir = new File(act.getCacheDir(), "probe");
        if (!dir.exists() && !dir.mkdirs()) {
            LauncherDb.lastError = "无法创建临时目录";
            return null;
        }
        File copy = new File(dir, "layout.db");
        int uid = android.os.Process.myUid();
        String cmd = "cp -f " + Shell.q(db.path) + " " + Shell.q(copy)
                + " ; cp -f " + Shell.q(db.walPath()) + " " + Shell.q(copy.getAbsolutePath() + "-wal")
                + " 2>/dev/null"
                + " ; chown " + uid + ":" + uid + " " + Shell.q(copy) + " " + Shell.q(copy.getAbsolutePath() + "-wal")
                + " 2>/dev/null"
                + " ; chmod 600 " + Shell.q(copy) + " " + Shell.q(copy.getAbsolutePath() + "-wal")
                + " 2>/dev/null"
                + " ; echo PULLED";
        Shell.Out o = Shell.su(cmd, 60);
        if (!o.text.contains("PULLED") || !copy.exists() || copy.length() <= 0) {
            deleteWithSidecars(copy);
            LauncherDb.lastError = "拷贝失败：" + o.text.trim();
            return null;
        }
        return copy;
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private void render(List<FolderMatcher.Suggestion> sugs) {
        listBox.removeAllViews();
        rows = new ArrayList<>();
        quiet = new ArrayList<>();
        quietShown = false;
        quietAnchor = null;

        int loose = 0;
        int move = 0;
        int unknown = 0;
        int hotseat = 0;
        for (FolderMatcher.Suggestion s : sugs) {
            switch (s.status) {
                case FolderMatcher.Suggestion.STATUS_LOOSE:
                    loose++;
                    break;
                case FolderMatcher.Suggestion.STATUS_MOVE:
                    move++;
                    break;
                case FolderMatcher.Suggestion.STATUS_UNKNOWN:
                    unknown++;
                    break;
                case FolderMatcher.Suggestion.STATUS_HOTSEAT:
                    hotseat++;
                    break;
                default:
                    break;
            }
            if (s.actionable()) {
                Row r = new Row();
                r.item = s.item;
                r.sug = s;
                // 散落且确定 → 默认勾上；建议挪动 / 没把握 → 默认不勾，
                // 让"机器改了你的桌面"永远需要你亲手点一次
                r.target = (s.status == FolderMatcher.Suggestion.STATUS_LOOSE
                        && s.hasTarget() && !s.shaky) ? s.target : null;
                rows.add(r);
            } else {
                quiet.add(s);
            }
        }

        int inPlace = quiet.size() - hotseat;
        StringBuilder sb = new StringBuilder();
        sb.append("共分析 ").append(sugs.size()).append(" 个图标")
                .append("（").append(model.folders.size()).append(" 个文件夹）\n")
                .append(inPlace).append(" 个位置合适");
        if (hotseat > 0) {
            sb.append("，").append(hotseat).append(" 个在热区");
        }
        sb.append("，").append(loose).append(" 个散落");
        if (move > 0) {
            sb.append("，").append(move).append(" 个建议挪动");
        }
        if (unknown > 0) {
            sb.append("，").append(unknown).append(" 个认不出类别");
        }
        tvStatus.setText(sb.toString());

        if (rows.isEmpty()) {
            btnApply.setVisibility(View.GONE);
            listBox.addView(sectionNote("桌面已经归好类了，没有需要处理的图标。"));
            listBox.addView(buildQuietToggle());
            if (!quiet.isEmpty()) {
                renderQuiet();
            }
            return;
        }

        listBox.addView(sectionHeader("需要处理 · " + rows.size() + " 个"));
        for (Row r : rows) {
            listBox.addView(buildRow(r));
        }
        if (!quiet.isEmpty()) {
            listBox.addView(buildQuietToggle());
        }

        btnApply.setVisibility(View.VISIBLE);
        updateApplyButton();
    }

    /** 分区标题。用一条细线 + 小字，跟可操作的行走开层次。 */
    private View sectionHeader(String text) {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextSize(11);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        tv.setLetterSpacing(0.04f);
        tv.setTextColor(act.getColor(R.color.text_tertiary));
        return tv;
    }

    private View sectionNote(String text) {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(act.getColor(R.color.text_secondary));
        return tv;
    }

    /**
     * "另外 N 个位置合适 · 展开看" 这一行。
     *
     * <p>默认折叠不是为了省事，是必须的：真机上有 160 多个图标，逐行建 ViewGroup
     * 会让展开一次卡顿几百毫秒，而这一堆恰恰是用户不需要动手的部分。
     */
    private View buildQuietToggle() {
        TextView tv = new TextView(act);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        tv.setLayoutParams(lp);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        tv.setBackgroundResource(R.drawable.bg_pill_idle);
        tv.setTextSize(12);
        tv.setTextColor(act.getColor(R.color.text_secondary));
        tv.setText(quietShown
                ? "收起已归好的 " + quiet.size() + " 个"
                : "另外 " + quiet.size() + " 个位置合适 · 点开查看");
        tv.setOnClickListener(v -> {
            quietShown = !quietShown;
            tv.setText(quietShown
                    ? "收起已归好的 " + quiet.size() + " 个"
                    : "另外 " + quiet.size() + " 个位置合适 · 点开查看");
            renderQuiet();
        });
        return tv;
    }

    /** 展开后逐行渲染"已归好"的图标：只读，没有勾选框。 */
    private void renderQuiet() {
        if (quietAnchor == null) {
            quietAnchor = new LinearLayout(act);
            quietAnchor.setOrientation(LinearLayout.VERTICAL);
            // 收起状态下也先把容器挂上去：这样它在「展开」按钮下方，位置不会跳
            listBox.addView(quietAnchor);
        }
        quietAnchor.removeAllViews();
        if (!quietShown) {
            return;
        }
        for (FolderMatcher.Suggestion s : quiet) {
            quietAnchor.addView(buildQuietRow(s));
        }
    }

    private View buildQuietRow(FolderMatcher.Suggestion s) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(act);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(26), dp(26)));
        Drawable d = loadIcon(s.item.pkg);
        if (d != null) {
            icon.setImageDrawable(d);
        }
        row.addView(icon);

        TextView name = new TextView(act);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        np.leftMargin = dp(10);
        name.setLayoutParams(np);
        name.setText(s.item.title.isEmpty() ? s.item.pkg : s.item.title);
        name.setTextSize(12);
        name.setTextColor(act.getColor(R.color.text_secondary));
        row.addView(name);

        TextView where = new TextView(act);
        where.setTextSize(11);
        where.setTextColor(act.getColor(R.color.text_tertiary));
        where.setText(s.status == FolderMatcher.Suggestion.STATUS_HOTSEAT
                ? "热区" : (s.current == null ? "—" : "已在「" + s.current.title + "」"));
        row.addView(where);
        return row;
    }

    private View buildRow(final Row r) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(act);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(34), dp(34));
        ip.topMargin = dp(2);
        icon.setLayoutParams(ip);
        Drawable d = loadIcon(r.item.pkg);
        if (d != null) {
            icon.setImageDrawable(d);
        }
        row.addView(icon);

        LinearLayout col = new LinearLayout(act);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        cp.leftMargin = dp(11);
        col.setLayoutParams(cp);

        TextView name = new TextView(act);
        name.setText(r.item.title.isEmpty() ? r.item.pkg : r.item.title);
        name.setTextColor(act.getColor(R.color.text_primary));
        name.setTextSize(14);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        col.addView(name);

        TextView target = new TextView(act);
        target.setTextSize(12);
        target.setPadding(0, dp(4), 0, dp(2));
        col.addView(target);
        r.tvTarget = target;

        TextView reason = new TextView(act);
        reason.setTextSize(11);
        reason.setTextColor(act.getColor(R.color.text_tertiary));
        reason.setLineSpacing(dp(2), 1f);
        col.addView(reason);
        r.tvReason = reason;

        col.setOnClickListener(v -> pickFolder(r));
        row.addView(col);

        CheckBox cb = new CheckBox(act);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(6);
        cb.setLayoutParams(bp);
        cb.setButtonTintList(android.content.res.ColorStateList.valueOf(
                act.getColor(R.color.accent)));
        cb.setChecked(r.target != null);
        cb.setOnCheckedChangeListener((b, checked) -> {
            if (checked && r.target == null) {
                // 勾上了却没有目标，让它去选，而不是静默失效
                b.setChecked(false);
                pickFolder(r);
                return;
            }
            updateApplyButton();
        });
        row.addView(cb);
        r.cb = cb;

        refreshRowText(r);
        return row;
    }

    private void refreshRowText(Row r) {
        // 已在文件夹里的图标，理由前面先交代它现在在哪——不然"建议进银行"会让人莫名其妙
        String now = (r.sug.status == FolderMatcher.Suggestion.STATUS_MOVE && r.sug.current != null)
                ? "现在在「" + r.sug.current.title + "」 · " : "";
        if (r.target == null) {
            r.tvTarget.setText("→ 不移动");
            r.tvTarget.setTextColor(act.getColor(R.color.text_tertiary));
            r.tvReason.setText(now + r.sug.reason
                    + (r.sug.shaky ? "（不太确定，可点这里手动选）" : ""));
        } else {
            r.tvTarget.setText("→ " + r.target.title);
            r.tvTarget.setTextColor(act.getColor(R.color.accent));
            r.tvReason.setText(now + r.sug.reason
                    + (r.sug.shaky ? "（不太确定）" : ""));
        }
    }

    private void pickFolder(final Row r) {
        if (model == null) {
            return;
        }
        final List<DesktopModel.Folder> opts = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        labels.add("不移动");
        for (DesktopModel.Folder f : model.folders) {
            opts.add(f);
            labels.add(f.title + "（" + f.members.size() + " 项 · 第" + f.screen + "屏）");
        }
        new AlertDialog.Builder(act)
                .setTitle(r.item.title.isEmpty() ? r.item.pkg : r.item.title)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    r.target = which == 0 ? null : opts.get(which - 1);
                    r.cb.setChecked(r.target != null);
                    refreshRowText(r);
                    updateApplyButton();
                })
                .show();
    }

    private void updateApplyButton() {
        int n = selectedCount();
        if (n == 0) {
            btnApply.setText("没有选中任何改动");
            btnApply.setEnabled(false);
        } else {
            btnApply.setText("应用 " + n + " 项改动");
            btnApply.setEnabled(true);
        }
    }

    private int selectedCount() {
        if (rows == null) {
            return 0;
        }
        int n = 0;
        for (Row r : rows) {
            if (r.cb.isChecked() && r.target != null) {
                n++;
            }
        }
        return n;
    }

    // ───────────────────────── 应用 ─────────────────────────

    private void confirmApply() {
        final List<Row> chosen = new ArrayList<>();
        if (rows != null) {
            for (Row r : rows) {
                if (r.cb.isChecked() && r.target != null) {
                    chosen.add(r);
                }
            }
        }
        if (chosen.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Row r : chosen) {
            sb.append("· ").append(r.item.title).append(" → ").append(r.target.title).append('\n');
        }
        sb.append("\n会先自动拍一张「整理前」快照，随时能退回去。\n")
                .append("写入时桌面进程会被停止（屏幕会闪一下），按 Home 键即可看到新布局。");

        new AlertDialog.Builder(act)
                .setTitle("确认应用 " + chosen.size() + " 项改动？")
                .setMessage(sb.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("应用", (d, w) -> doApply(chosen))
                .show();
    }

    private void doApply(final List<Row> chosen) {
        if (busy) {
            return;
        }
        busy = true;
        btnApply.setEnabled(false);
        btnAnalyze.setEnabled(false);
        tvStatus.setText("正在写入…（桌面会短暂重启）");

        new Thread(() -> {
            String err = null;
            LayoutWriter.Result res = null;
            File copy = null;
            try {
                // 应用之前重读一次：用户可能刚在桌面上拖过图标，这时候按旧坐标写就是错的
                copy = pullCopy();
                if (copy == null) {
                    err = "读取最新布局失败，已中止（桌面未被改动）";
                } else {
                    DesktopModel fresh = DesktopModel.read(copy, act, pkg);
                    List<LayoutWriter.Change> changes = new ArrayList<>();
                    Map<Integer, Integer> nextCell = new HashMap<>();
                    for (Row r : chosen) {
                        DesktopModel.Item now = findById(fresh, r.item.id);
                        if (now == null) {
                            err = "「" + r.item.title + "」已经不在桌面上了（可能刚被移动或卸载），"
                                    + "请重新分析后再试。桌面未被改动。";
                            break;
                        }
                        // 散落的应当还在桌面；已在文件夹的应当还在原文件夹。
                        // 位置变了说明用户刚拖过，按旧结论写就是错的
                        int expect = r.sug.current == null
                                ? DesktopModel.CONTAINER_DESKTOP : r.sug.current.id;
                        if (now.container != expect) {
                            err = "「" + r.item.title + "」的位置已经变了（可能刚在桌面上拖过），"
                                    + "请重新分析后再试。桌面未被改动。";
                            break;
                        }
                        DesktopModel.Folder f = fresh.folderById(r.target.id);
                        if (f == null) {
                            err = "目标文件夹「" + r.target.title + "」已不存在，请重新分析。";
                            break;
                        }
                        Integer cached = nextCell.get(f.id);
                        int base = cached != null ? cached : firstFreeCell(f);
                        int cx = base;
                        // 同一文件夹里可能一次进去好几个，依次往后排，不能都算成同一个位置
                        nextCell.put(f.id, nextSlot(f, cx));
                        LayoutWriter.Change c = new LayoutWriter.Change(
                                r.item.id, f.id, cx, cx / DesktopModel.FOLDER_COLUMNS);
                        c.title = r.item.title;
                        changes.add(c);
                    }
                    if (err == null) {
                        if (changes.isEmpty()) {
                            err = "没有可写入的改动";
                        } else {
                            res = LayoutWriter.apply(act, pkg, changes);
                        }
                    }
                }
            } catch (Throwable t) {
                err = "写入出错：" + t;
            } finally {
                if (copy != null) {
                    deleteWithSidecars(copy);
                }
            }

            final String fErr = err;
            final LayoutWriter.Result fRes = res;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnAnalyze.setEnabled(true);
                if (fErr != null) {
                    tvStatus.setText(fErr);
                    showDialog("没能写入", fErr);
                } else if (fRes == null) {
                    tvStatus.setText("未知结果");
                } else if (fRes.ok && fRes.effective) {
                    tvStatus.setText("完成。" + fRes.msg
                            + "\n整理前快照：" + fRes.snapshotId);
                    toast(fRes.msg);
                    rows = null;
                    quiet = null;
                    quietAnchor = null;
                    quietShown = false;
                    listBox.removeAllViews();
                    btnApply.setVisibility(View.GONE);
                    btnAnalyze.setText("重新分析");
                } else if (fRes.ok) {
                    // 字节写进去了、但回读看不到：这是"点了没反应"的真正现场，
                    // 绝不能显示成功。把复核结果原样摊出来，并让人能直接重试或回滚
                    tvStatus.setText("写入未生效 · " + fRes.msg
                            + "\n整理前快照：" + fRes.snapshotId);
                    btnApply.setEnabled(true);
                    showDialog("写入未生效",
                            fRes.msg + "\n\n桌面重启后也不会看到变化。\n"
                                    + "可用快照 " + fRes.snapshotId + " 回滚。");
                } else {
                    tvStatus.setText(fRes.msg);
                    btnApply.setEnabled(true);
                    showDialog("写入失败", fRes.msg
                            + (fRes.snapshotId.isEmpty() ? "" : "\n\n可用快照 " + fRes.snapshotId + " 回滚。"));
                }
            });
        }, "deskly-relocate-apply").start();
    }

    /**
     * 目标文件夹里第一个没被占的格位。
     *
     * <p>不能简单用 max(cellX)+1：用户手动拖过图标，序号会出现空洞，
     * 也可能出现 cellX 与 cellY 对不上的记录（真机上"个人所得税"就是 cellX=7 而 cellY=2）。
     * 所以按"序号递增、跳过已占格位"来走，宁可往后多排一格也不覆盖别人的位置。
     */
    private static int firstFreeCell(DesktopModel.Folder f) {
        int idx = f.maxCellX() + 1;
        java.util.Set<String> taken = f.occupiedCells();
        while (taken.contains(idx + "|" + (idx / DesktopModel.FOLDER_COLUMNS))) {
            idx++;
        }
        return idx;
    }

    /** 下一个待用序号，同样跳过已占格位。 */
    private static int nextSlot(DesktopModel.Folder f, int from) {
        int idx = from + 1;
        java.util.Set<String> taken = f.occupiedCells();
        while (taken.contains(idx + "|" + (idx / DesktopModel.FOLDER_COLUMNS))) {
            idx++;
        }
        return idx;
    }

    /** 在整份布局里按 _id 找条目：散落的、文件夹里的、热区的都算（现在分析覆盖了全部）。 */
    private static DesktopModel.Item findById(DesktopModel m, int id) {
        for (DesktopModel.Item i : m.looseOnDesktop) {
            if (i.id == id) {
                return i;
            }
        }
        for (DesktopModel.Folder f : m.folders) {
            for (DesktopModel.Item i : f.members) {
                if (i.id == id) {
                    return i;
                }
            }
        }
        for (DesktopModel.Item i : m.hotseat) {
            if (i.id == id) {
                return i;
            }
        }
        return null;
    }

    // ───────────────────────── 工具 ─────────────────────────

    private Drawable loadIcon(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return null;
        }
        try {
            return act.getPackageManager().getApplicationIcon(pkg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void deleteWithSidecars(File f) {
        if (f == null) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        //noinspection ResultOfMethodCallIgnored
        new File(f.getAbsolutePath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(f.getAbsolutePath() + "-shm").delete();
    }

    private void showDialog(String title, String msg) {
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
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
