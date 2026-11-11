package io.github.cobylinweiqi.deskly.ui;

import android.app.Activity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.github.cobylinweiqi.deskly.R;
import io.github.cobylinweiqi.deskly.ai.AiClassifier;
import io.github.cobylinweiqi.deskly.ai.AiClient;
import io.github.cobylinweiqi.deskly.ai.AiConfig;
import io.github.cobylinweiqi.deskly.organize.AppClassifier;

/**
 * AI 辅助分类的界面：配置页一张卡（接口 / Key / 模型 / 开关），整理页一颗按钮。
 *
 * <p>分两处是按「做一次」和「每次做」分的：接口填一次就不动了，放配置页并默认折叠；
 * 「AI 精确分类」是整理流程里的一步，放在分析按钮下面。
 *
 * <p>AI 只影响「按功能」方案里哪个应用进哪组。排屏、写库、快照一概不碰——
 * 跑完只是把结果交给 {@link AppClassifier}，再让 {@link DesktopSection} 按新分组重算预览。
 */
public final class AiSection {

    private final Activity act;
    private final DesktopSection desktop;

    private final EditText etBase;
    private final EditText etKey;
    private final EditText etModel;
    private final CheckBox cbEnabled;
    private final Button btnTest;
    private final Button btnClear;
    private final TextView tvStatus;
    private final TextView tvHint;

    private final Button btnRun;
    private final TextView tvRun;

    private volatile boolean busy;

    public AiSection(Activity act, DesktopSection desktop) {
        this.act = act;
        this.desktop = desktop;

        etBase = act.findViewById(R.id.etAiBase);
        etKey = act.findViewById(R.id.etAiKey);
        etModel = act.findViewById(R.id.etAiModel);
        cbEnabled = act.findViewById(R.id.cbAiEnabled);
        btnTest = act.findViewById(R.id.btnAiTest);
        btnClear = act.findViewById(R.id.btnAiClear);
        tvStatus = act.findViewById(R.id.tvAiStatus);
        tvHint = act.findViewById(R.id.tvAiHint);
        btnRun = act.findViewById(R.id.btnAiClassify);
        tvRun = act.findViewById(R.id.tvAiRun);

        AiConfig c = AiConfig.load(act);
        etBase.setText(c.baseUrl);
        etKey.setText(c.apiKey);
        etModel.setText(c.model);
        cbEnabled.setChecked(c.enabled);

        // 上次的 AI 结果一启动就生效，不用等用户再点一次
        AiConfig.apply(act);
        renderHint();

        Fold.bind(act.findViewById(R.id.rowAiHead), act.findViewById(R.id.boxAi),
                (TextView) act.findViewById(R.id.tvAiToggle), tvHint);

        cbEnabled.setOnCheckedChangeListener((v, on) -> {
            AiConfig cfg = readForm();
            cfg.save(act);
            AiConfig.apply(act);
            renderHint();
            desktop.rebuild();
        });
        btnTest.setOnClickListener(v -> test());
        btnClear.setOnClickListener(v -> clear());
        btnRun.setOnClickListener(v -> run());
    }

    private AiConfig readForm() {
        AiConfig c = AiConfig.load(act);
        c.baseUrl = etBase.getText().toString().trim();
        c.apiKey = etKey.getText().toString().trim();
        c.model = etModel.getText().toString().trim();
        c.enabled = cbEnabled.isChecked();
        return c;
    }

    /** 折叠时露的那一行：配没配、存了多少条、开没开。 */
    private void renderHint() {
        AiConfig c = AiConfig.load(act);
        int saved = AiConfig.loadGroups(act).size();
        String s;
        if (!c.ready()) {
            s = "未配置 · 填入 OpenAI 兼容接口即可使用";
        } else {
            s = c.model + (saved > 0 ? " · 已分类 " + saved + " 个应用" : " · 还没跑过分类")
                    + (c.enabled ? "" : " · 已停用");
        }
        tvHint.setText(s);
    }

    // ───────────────────────── 测试连接 ─────────────────────────

    private void test() {
        if (busy) {
            return;
        }
        final AiConfig cfg = readForm();
        cfg.save(act);
        renderHint();
        if (!cfg.ready()) {
            tvStatus.setText("接口地址和模型都要填。");
            return;
        }
        busy = true;
        btnTest.setEnabled(false);
        tvStatus.setText("已保存，正在请求 " + AiClient.endpoint(cfg.baseUrl) + " …");
        new Thread(() -> {
            String msg;
            try {
                String reply = AiClient.chat(cfg, null, "这是一次连接测试，请只回复：ok");
                String t = reply == null ? "" : reply.trim().replace('\n', ' ');
                if (t.length() > 80) {
                    t = t.substring(0, 80) + "…";
                }
                msg = "✓ 连接正常，模型回复：" + (t.isEmpty() ? "（空）" : t);
            } catch (Throwable e) {
                msg = "✗ 连接失败：" + (e.getMessage() == null ? e.toString() : e.getMessage());
            }
            final String fMsg = msg;
            act.runOnUiThread(() -> {
                busy = false;
                if (act.isFinishing() || act.isDestroyed()) {
                    return;
                }
                btnTest.setEnabled(true);
                tvStatus.setText(fMsg);
            });
        }, "deskly-ai-test").start();
    }

    private void clear() {
        if (busy) {
            return;
        }
        AiConfig.clearGroups(act);
        AiConfig.apply(act);
        renderHint();
        tvStatus.setText("已清除 AI 分类结果，按功能整理回到内置词典。");
        tvRun.setVisibility(View.GONE);
        desktop.rebuild();
    }

    // ───────────────────────── 分类 ─────────────────────────

    private void run() {
        if (busy) {
            return;
        }
        final AiConfig cfg = readForm();
        tvRun.setVisibility(View.VISIBLE);
        if (!cfg.ready()) {
            tvRun.setText("还没配置接口：到「配置 → AI 辅助分类」填写接口地址和模型。");
            return;
        }
        final List<String[]> raw = desktop.appsForAi();
        if (raw.isEmpty()) {
            tvRun.setText("先点上面的按钮分析桌面，再让 AI 分类。");
            return;
        }
        final List<AiClassifier.App> apps = new ArrayList<>(raw.size());
        for (String[] a : raw) {
            apps.add(new AiClassifier.App(a[0], a[1]));
        }
        busy = true;
        btnRun.setEnabled(false);
        btnRun.setText("AI 分类中…");
        tvRun.setText("正在把 " + apps.size() + " 个应用的名称和包名发给 " + cfg.model + " …");

        new Thread(() -> {
            final AiClassifier.Result r = AiClassifier.classify(cfg, apps,
                    AppClassifier.functionGroups(),
                    (done, total) -> act.runOnUiThread(() -> {
                        if (!act.isFinishing() && !act.isDestroyed()) {
                            tvRun.setText("AI 分类中… " + done + " / " + total);
                        }
                    }));
            // 与内置词典比一下，告诉用户 AI 到底改了多少——"跑了但看不出区别"最让人起疑
            int changed = 0;
            int rescued = 0;
            for (AiClassifier.App a : apps) {
                String ai = r.groups.get(a.pkg);
                if (ai == null) {
                    continue;
                }
                String rule = AppClassifier.groupByRules(
                        AppClassifier.fromDesktop(act.getPackageManager(), a.label, a.pkg));
                if (!ai.equals(rule)) {
                    changed++;
                    if (AppClassifier.isUnknown(rule)) {
                        rescued++;
                    }
                }
            }
            final int fChanged = changed;
            final int fRescued = rescued;
            act.runOnUiThread(() -> finish(cfg, r, fChanged, fRescued));
        }, "deskly-ai-classify").start();
    }

    private void finish(AiConfig cfg, AiClassifier.Result r, int changed, int rescued) {
        busy = false;
        if (act.isFinishing() || act.isDestroyed()) {
            return;
        }
        btnRun.setEnabled(true);
        btnRun.setText("AI 精确分类（可选）");

        if (r.groups.isEmpty()) {
            tvRun.setText("✗ AI 分类失败：" + (r.error == null ? "没有拿到结果" : r.error)
                    + "\n分组没有变化。");
            return;
        }
        // 新结果并进已有的：这次没问到（或失败批次里）的应用保留上次的判断
        Map<String, String> all = AiConfig.loadGroups(act);
        all.putAll(r.groups);
        AiConfig.saveGroups(act, all);
        // 用户主动跑了分类，就当他要用：顺手把开关打开
        if (!cfg.enabled) {
            cfg.enabled = true;
            cbEnabled.setChecked(true);
        }
        cfg.save(act);
        AiConfig.apply(act);
        renderHint();

        StringBuilder sb = new StringBuilder();
        sb.append(r.error == null ? "✓ " : "⚠ ")
                .append("AI 已分类 ").append(r.groups.size()).append(" / ").append(r.asked)
                .append(" 个应用，其中 ").append(changed).append(" 个与内置词典不同");
        if (rescued > 0) {
            sb.append("（").append(rescued).append(" 个原本落在「其它」）");
        }
        sb.append("。选「按功能」方案即可看到新分组。");
        if (r.error != null) {
            sb.append("\n中途出错，剩下的仍按内置词典：").append(r.error);
        }
        tvRun.setText(sb.toString());
        desktop.rebuild();
    }
}
