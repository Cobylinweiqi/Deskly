package com.coby.deskly.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.coby.deskly.R;
import com.coby.deskly.core.Shell;
import com.coby.deskly.probe.TargetRegistry;

/**
 * 「运行引导」卡片：给刚装好的人一条走得通的路。
 *
 * <p>这个模块装完并不会立刻生效——LSPosed 要启用、作用域要勾桌面、root 要授权、
 * 桌面进程要重启。这四件事以前散落在两个 tab 的说明文字里，缺哪一步都不会有明确报错，
 * 用户只能看到"点了没反应"。这里把它们摊成一条清单，每一条都带一个能直接点的动作。
 *
 * <p><b>能查的就查，查不到的就直说。</b>root 状态、LSPosed 是否安装、电池优化是否关闭，
 * 都能真实读到；而"模块有没有在 LSPosed 里被启用、作用域勾没勾"，框架没有给模块 App
 * 留下任何查询接口——所以那一步只给操作路径，不假装打勾。宁可显示"只能自己确认"，
 * 也不要给一个绿点让人以为已经好了。
 *
 * <p><b>0.12.1 起序号不再变绿勾。</b>整列步骤编号始终是数字，只有真出错（root 没授权）
 * 才显示 "!"。理由和上面那条一样：作用域那一步永远查不到，如果其余步骤都打勾，
 * 这一格空着就会被读成"这步有问题"，而实际上它只是无从验证。
 *
 * <p><b>0.12.3 起收起时的摘要也不再报"就绪 N"。</b>五步里有两步本来就无法自动确认，
 * 算出来的分子分母都不成立；摘要只留 root 这一个能准确读出、且真的卡住可用性的判断。
 */
public final class GuideSection {

    private static final String LSPOSED_PKG = "org.lsposed.manager";
    private static final String MAGISK_PKG = "com.topjohnwu.magisk";

    private enum State {OK, WARN, NEUTRAL}

    private final Activity act;
    private final String pkg;
    private final LinearLayout stepsBox;
    private final TextView tvFacts;
    private final TextView tvHint;
    private final Button btnRecheck;
    /** 「重新检测」实际由备份区块的探测驱动：同一个 root 探测只该跑一次。 */
    private final Runnable onRecheck;

    private Shell.Probe probe;
    private boolean restarting;
    /** LSPosed 模块目录是否存在。null = 还没查过（没有 root 时查不了）。 */
    private Boolean lspModule;

    public GuideSection(Activity act, String pkg, View root, TextView tvHint, Runnable onRecheck) {
        this.act = act;
        this.pkg = pkg;
        this.onRecheck = onRecheck;
        this.stepsBox = root.findViewById(R.id.guideSteps);
        this.tvFacts = root.findViewById(R.id.tvGuideFacts);
        this.tvHint = tvHint;
        this.btnRecheck = root.findViewById(R.id.btnGuideRecheck);
        btnRecheck.setOnClickListener(v -> {
            render();
            onRecheck.run();
            toast("正在重新检测…");
        });
        render();
    }

    /** 备份区块探完 root 后回调进来，让第三条跟着变。 */
    public void onProbe(Shell.Probe p) {
        this.probe = p;
        if (p != null && p.ok()) {
            detectLspModule();
        }
        render();
    }

    /**
     * 查 LSPosed 模块是否真的在。
     *
     * <p>只查 {@code org.lsposed.manager} 不够：这个管理器可以把自己隐藏起来，
     * 真机上就出现过"模块明明在工作、pm 却查不到管理器"的情况。有 root 时直接看
     * {@code /data/adb/modules} 里有没有名字带 lsposed 的目录，这个更难骗过。
     */
    private void detectLspModule() {
        if (lspModule != null) {
            return;
        }
        new Thread(() -> {
            Shell.Out o = Shell.su("ls /data/adb/modules 2>/dev/null", 15);
            boolean found = o.ok() && o.text.toLowerCase().contains("lsposed");
            act.runOnUiThread(() -> {
                lspModule = found;
                if (!act.isFinishing() && !act.isDestroyed()) {
                    render();
                }
            });
        }, "deskly-lsp-check").start();
    }

    // ───────────────────────── 渲染 ─────────────────────────

    private void render() {
        if (act.isFinishing() || act.isDestroyed()) {
            return;
        }
        stepsBox.removeAllViews();

        boolean lspMgr = installed(LSPOSED_PKG);
        boolean lspOn = lspMgr || Boolean.TRUE.equals(lspModule);
        String lspBody;
        if (lspMgr) {
            lspBody = "已检测到 LSPosed 管理器。";
        } else if (Boolean.TRUE.equals(lspModule)) {
            lspBody = "已检测到 LSPosed 模块（管理器把自己隐藏了，pm 查不到，属正常）。";
        } else if (probe != null && probe.ok()) {
            lspBody = "没找到 LSPosed：管理器查不到，/data/adb/modules 里也没有 lsposed 目录。先装 LSPosed。";
        } else {
            lspBody = "还没拿到 root，查不了模块目录。先用下面第 3 步把 root 打开。";
        }
        addStep(1, "装好 LSPosed", lspBody,
                lspOn ? State.OK : State.NEUTRAL, "打开", v -> openPkg(LSPOSED_PKG, "LSPosed"));

        addStep(2, "启用模块，勾选作用域",
                "LSPosed → 模块 → Deskly 打开开关；作用域里勾选「" + launcherName()
                        + "」。作用域决定模块能进哪个进程——不勾，模块在桌面里等于没加载。"
                        + "整理与恢复本身走 root 写库，不依赖 hook，但模块要算装完整就得勾上。"
                        + "这一步框架没提供查询接口，只能自己确认。",
                State.NEUTRAL, "打开 LSPosed", v -> openPkg(LSPOSED_PKG, "LSPosed"));

        Shell.Probe p = probe;
        String rootBody;
        State rootState;
        if (p == null) {
            rootBody = "还没检测。";
            rootState = State.NEUTRAL;
        } else if (p.ok()) {
            rootBody = "已获得 root，备份、恢复与写入桌面都能用。";
            rootState = State.OK;
        } else {
            rootBody = p.headline() + "。" + firstLine(p.advice());
            rootState = State.WARN;
        }
        addStep(3, "授予 root", rootBody, rootState,
                p != null && p.ok() ? null : "去授权", v -> openPkg(MAGISK_PKG, "Magisk"));

        addStep(4, "重启桌面，让模块注入",
                "模块只在桌面进程启动时加载。改完 LSPosed、或更新了 Deskly，都要重启一次桌面——"
                        + "点右边按钮会停掉桌面再拉起来，屏幕会闪一下。",
                State.NEUTRAL, "重启桌面", v -> confirmRestart());

        boolean ignoring = ignoringBattery();
        addStep(5, "关闭电池优化（可选）",
                ignoring ? "已关闭，Deskly 不会被系统在后台冻住。"
                        : "系统可能在后台冻住 Deskly，导致自动检测失败。",
                ignoring ? State.OK : State.NEUTRAL,
                ignoring ? null : "去设置", v -> openBatterySettings());

        StringBuilder facts = new StringBuilder();
        facts.append("桌面：").append(launcherName());
        String ver = versionOf(pkg);
        if (!ver.isEmpty()) {
            facts.append(" ").append(ver);
        }
        facts.append(installed(pkg) ? "（已安装）" : "（未安装）");
        String lspFact = lspMgr ? "管理器已安装"
                : Boolean.TRUE.equals(lspModule) ? "模块已安装（管理器隐藏）" : "未检测到";
        facts.append("\nLSPosed：").append(lspFact);
        facts.append(" · 电池优化：").append(ignoring ? "已关闭" : "仍开启");
        tvFacts.setText(facts.toString());
        updateHint();
    }

    /**
     * 收起时的摘要行：不展开也知道这张卡值不值得点开。
     *
     * <p><b>这里刻意不报"就绪 N/5"。</b>五步里第 2 步（作用域）框架根本查不到，
     * 第 4 步（重启桌面）也不是可查询的状态，能算出来的"就绪数"永远不是 5——
     * 分母假的、分子也不全，读起来像"你还有 2 步没做"，实际上那两步根本没法自动确认。
     * 所以只留一个真正影响可用性的判断：root 到手了没有——它是唯一会让整理彻底不可用的一步，
     * 也是唯一能准确读出来的。
     */
    private void updateHint() {
        if (tvHint == null) {
            return;
        }
        if (probe == null) {
            tvHint.setText("5 步接入 · 正在检测 root…");
        } else if (probe.ok()) {
            tvHint.setText("5 步接入 · root 已就绪");
        } else {
            tvHint.setText("5 步接入 · root 未授权，展开看怎么拿");
        }
    }

    /** 一行步骤：编号圆点 + 标题与说明 + 右侧动作。 */
    private void addStep(int no, String title, String body, State state,
                         String actionText, View.OnClickListener action) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(14);
        row.setLayoutParams(rp);

        TextView dot = new TextView(act);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        dot.setLayoutParams(dotLp);
        dot.setGravity(Gravity.CENTER);
        dot.setTextSize(11);
        dot.setTypeface(null, android.graphics.Typeface.BOLD);
        // 序号就是序号：就绪也照样显示数字，只有真出问题时才换成 "!"。
        // 以前 OK 会变成绿色 ✓，看起来像"这一步已经过验证"，可第 2 步（作用域）永远查不到，
        // 于是同一列里一半打勾一半不打，反而让不打勾的那半步看起来像出了问题。
        if (state == State.WARN) {
            dot.setText("!");
            dot.setTextColor(act.getColor(R.color.warn));
            dot.setBackgroundResource(R.drawable.bg_pill_warn);
        } else {
            dot.setText(String.valueOf(no));
            dot.setTextColor(act.getColor(R.color.text_secondary));
            dot.setBackgroundResource(R.drawable.bg_pill_idle);
        }
        row.addView(dot);

        LinearLayout mid = new LinearLayout(act);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        mp.leftMargin = dp(10);
        mid.setLayoutParams(mp);
        row.addView(mid);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextSize(13);
        t.setTextColor(act.getColor(R.color.text_primary));
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        mid.addView(t);

        TextView b = new TextView(act);
        b.setText(body);
        b.setTextSize(11.5f);
        b.setTextColor(act.getColor(R.color.text_secondary));
        b.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(3);
        b.setLayoutParams(bp);
        mid.addView(b);

        if (actionText != null) {
            TextView pill = new TextView(act);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pp.leftMargin = dp(8);
            pill.setLayoutParams(pp);
            pill.setText(actionText);
            pill.setTextSize(11);
            pill.setTypeface(null, android.graphics.Typeface.BOLD);
            pill.setGravity(Gravity.CENTER);
            pill.setPadding(dp(11), dp(6), dp(11), dp(6));
            pill.setBackgroundResource(R.drawable.bg_pill_accent);
            pill.setTextColor(act.getColor(R.color.accent));
            pill.setOnClickListener(action);
            row.addView(pill);
        }
        stepsBox.addView(row);
    }

    // ───────────────────────── 动作 ─────────────────────────

    private void confirmRestart() {
        new AlertDialog.Builder(act)
                .setTitle("重启桌面？")
                .setMessage("会停掉 " + launcherName() + " 再把它拉起来，屏幕会闪一下。\n\n"
                        + "正在别的应用里操作不会丢失，但桌面上的临时状态（比如刚拖到一半的图标）会回到库里的样子。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重启", (d, w) -> doRestart())
                .show();
    }

    private void doRestart() {
        if (restarting) {
            return;
        }
        restarting = true;
        toast("正在重启桌面…");
        new Thread(() -> {
            Shell.Out o = Shell.su("am force-stop " + pkg + "; sleep 1; input keyevent 3", 30);
            act.runOnUiThread(() -> {
                restarting = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                if (o.ok()) {
                    toast("桌面已重启，模块会重新注入");
                    render();
                    onRecheck.run();
                } else {
                    String why = o.text.trim().isEmpty() ? ("exit=" + o.code) : firstLine(o.text);
                    toast("重启失败：" + why);
                }
            });
        }, "deskly-restart-launcher").start();
    }

    private void openPkg(String target, String name) {
        try {
            Intent i = act.getPackageManager().getLaunchIntentForPackage(target);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
                return;
            }
        } catch (Throwable ignored) {
        }
        toast("没找到 " + name + "，请从桌面或应用列表里手动打开");
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + act.getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                act.startActivity(i);
            } catch (Throwable t2) {
                toast("打不开系统设置，请手动到「电池 → 应用耗电管理」里放开 Deskly");
            }
        }
    }

    // ───────────────────────── 事实探测 ─────────────────────────

    private boolean installed(String target) {
        try {
            act.getPackageManager().getPackageInfo(target, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private String versionOf(String target) {
        try {
            return act.getPackageManager().getPackageInfo(target, 0).versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private boolean ignoringBattery() {
        try {
            PowerManager pm = (PowerManager) act.getSystemService(Activity.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(act.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private String launcherName() {
        String known = TargetRegistry.KNOWN_LAUNCHERS.get(pkg);
        return known == null ? pkg : known;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_SHORT).show();
    }
}
