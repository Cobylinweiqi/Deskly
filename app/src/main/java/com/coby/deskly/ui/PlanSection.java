package com.coby.deskly.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.coby.deskly.R;
import com.coby.deskly.organize.AppClassifier.Scheme;
import com.coby.deskly.organize.AppEntry;
import com.coby.deskly.organize.IconPalette;
import com.coby.deskly.organize.LayoutPlan;
import com.coby.deskly.organize.PlanConfig;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 「整理方案」区块：两张并排的方案卡 + 第一屏留空 + 分屏布局预览。
 *
 * <p>卡片从"整行文字"改成"并排两张"的原因：两个方案是二选一的关系，上下两行看起来
 * 像列表项，用户得先读文字才知道能点；并排卡片配上各自的视觉预览（五彩点 vs 分类格），
 * 一眼就能分清，选中态也有地方放确认标记。
 *
 * <p>状态全在 {@link PlanConfig} 里，并且立刻落盘。这里只管把它画出来、把用户的点击写回去，
 * 不参与分类计算——重算由 {@link DesktopSection} 决定要不要做（没有分析过时点方案不该触发一次全量计算）。
 */
public final class PlanSection {

    private final Activity act;
    private final LinearLayout presetBox;
    private final CheckBox cbFirstBlank;
    private final TextView tvPlanSummary;
    private final LinearLayout screenBox;

    private final PlanConfig cfg;
    private final Map<Scheme, Row> rows = new EnumMap<>(Scheme.class);
    private final Map<Scheme, Integer> groupCounts = new EnumMap<>(Scheme.class);

    private Consumer<Scheme> presetListener;
    /** 排布变了之后通知上层重画分组行（行上的屏号要跟着变）。 */
    private Runnable planChanged;
    private Map<String, List<AppEntry>> lastGroups;
    private LayoutPlan lastPlan;
    /** 屏号 → 这一屏还能用的文件夹候选位。桌面上的小组件会占格子，不计入就会和预览打架。 */
    private Map<Integer, int[]> lastSlots;

    /** 一张方案卡用到的那几个 View，选中态切换时挨个改。 */
    private static final class Row {
        View root;
        TextView title;
        TextView desc;
        TextView count;
        TextView check;
    }

    public PlanSection(Activity act, LinearLayout presetBox, CheckBox cbFirstBlank,
                       TextView tvPlanSummary, LinearLayout screenBox) {
        this.act = act;
        this.presetBox = presetBox;
        this.cbFirstBlank = cbFirstBlank;
        this.tvPlanSummary = tvPlanSummary;
        this.screenBox = screenBox;
        this.cfg = PlanConfig.load(act);

        buildPresets();

        // 先落初始值再挂监听：setChecked 会立刻触发一次回调，白白把默认值当用户操作存一遍
        cbFirstBlank.setChecked(cfg.firstScreenBlank);
        cbFirstBlank.setOnCheckedChangeListener((btn, checked) -> {
            cfg.firstScreenBlank = checked;
            // 勾上留空后，钉在第 1 屏的组必然排不进去，留着那条记录只会让屏号标签
            // 显示"已钉"却和实际位置不符，索性一并清掉
            if (checked) {
                cfg.pins.values().removeIf(n -> n != null && n == 1);
            }
            cfg.save(act);
            // 自己重绘：勾选只影响排布，不动分类结果，没必要让上层重新分组
            paintPlan();
            if (planChanged != null) {
                planChanged.run();
            }
        });

        updatePresets();
    }

    public void setPresetListener(Consumer<Scheme> l) {
        this.presetListener = l;
    }

    public void setPlanChangedListener(Runnable r) {
        this.planChanged = r;
    }

    public Scheme scheme() {
        return cfg.scheme;
    }

    /** 第一屏是否留空。选屏号的对话框要据此把"第 1 屏"从选项里去掉。 */
    public boolean firstScreenBlank() {
        return cfg.firstScreenBlank;
    }

    // ───────────────────────── 钉屏 ─────────────────────────

    /** 该组落在第几屏，0 表示没排上。 */
    public int screenOf(String group) {
        return lastPlan == null ? 0 : lastPlan.numberOf(group);
    }

    /** 当前最大屏号，用来给"调到第几屏"的选项封顶。 */
    public int maxScreen() {
        return lastPlan == null ? 0 : lastPlan.maxNumber();
    }

    /** 该组是否被手动钉过屏（自动排布时为 false）。 */
    public boolean isPinned(String group) {
        return cfg.pinOf(group) > 0;
    }

    /** 该组被钉到第几屏；0 表示自动。用来把"钉了但没排到那一屏"显式露出来。 */
    public int pinOf(String group) {
        return cfg.pinOf(group);
    }

    /** 组名 → 指定屏号。整理时要拿这份钉屏表去算真实落点，不能只让预览用。 */
    public java.util.Map<String, Integer> pins() {
        return cfg.pins;
    }

    /** screen &lt;= 0 表示交回自动。写完立刻重排并落盘。 */
    public void setPin(String group, int screen) {
        cfg.setPin(group, screen);
        cfg.save(act);
        paintPlan();
    }

    public void clearPins() {
        cfg.clearPins();
        cfg.save(act);
        paintPlan();
    }

    // ───────────────────────── 方案卡 ─────────────────────────

    private void buildPresets() {
        presetBox.removeAllViews();
        for (Scheme s : Scheme.values()) {
            Row r = new Row();

            LinearLayout card = new LinearLayout(act);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(13), dp(12), dp(13), dp(12));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (presetBox.getChildCount() > 0) {
                rp.leftMargin = dp(10);
            }
            card.setLayoutParams(rp);

            // 顶部一行：预览条 + 选中标记
            LinearLayout head = new LinearLayout(act);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(buildPreview(s));
            TextView check = new TextView(act);
            LinearLayout.LayoutParams ckp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ckp.leftMargin = dp(6);
            check.setLayoutParams(ckp);
            check.setText("✓");
            check.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            check.setIncludeFontPadding(false);
            check.setTypeface(null, android.graphics.Typeface.BOLD);
            check.setTextColor(Color.WHITE);
            check.setGravity(Gravity.CENTER);
            check.setVisibility(View.INVISIBLE);
            head.addView(check, new LinearLayout.LayoutParams(dp(18), dp(18)));
            card.addView(head);

            TextView title = new TextView(act);
            title.setText(s.title);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            title.setTypeface(null, android.graphics.Typeface.BOLD);
            title.setTextColor(act.getColor(R.color.text_primary));
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tp.topMargin = dp(10);
            title.setLayoutParams(tp);
            card.addView(title);

            TextView desc = new TextView(act);
            desc.setText(shortDesc(s));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            desc.setTextColor(act.getColor(R.color.text_secondary));
            desc.setMinLines(2);
            desc.setMaxLines(2);
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dp1.topMargin = dp(3);
            desc.setLayoutParams(dp1);
            card.addView(desc);

            TextView count = new TextView(act);
            count.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
            count.setTextColor(act.getColor(R.color.text_tertiary));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(8);
            count.setLayoutParams(cp);
            card.addView(count);

            card.setOnClickListener(v -> {
                if (cfg.scheme == s) {
                    return;
                }
                cfg.scheme = s;
                cfg.save(act);
                updatePresets();
                bounce(card);
                if (presetListener != null) {
                    presetListener.accept(s);
                }
            });

            r.root = card;
            r.title = title;
            r.desc = desc;
            r.count = count;
            r.check = check;
            rows.put(s, r);
            presetBox.addView(card);
        }
    }

    /** 方案卡的视觉预览：颜色方案给一串色点，功能方案给几个"文件夹小块"。 */
    private View buildPreview(Scheme s) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(
                0, dp(16), 1f));

        if (s == Scheme.COLOR) {
            String[] dots = {"红", "黄", "绿", "蓝", "紫"};
            for (String name : dots) {
                View dot = new View(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(11), dp(11));
                lp.rightMargin = dp(4);
                dot.setLayoutParams(lp);
                GradientDrawable g = new GradientDrawable();
                g.setShape(GradientDrawable.OVAL);
                g.setColor(IconPalette.colorOfGroup(name));
                dot.setBackground(g);
                box.addView(dot);
            }
            // 剩下几个色系用一条渐隐的细线暗示"还有"，比排满 10 个点干净
            View more = new View(act);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(dp(14), dp(2));
            mp.leftMargin = dp(2);
            more.setLayoutParams(mp);
            GradientDrawable line = new GradientDrawable(
                    GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{0xFF9CA3AF, 0x009CA3AF});
            line.setCornerRadius(dp(1));
            more.setBackground(line);
            box.addView(more);
        } else {
            String[] tiles = {"社交", "影音", "工具", "金融"};
            for (String name : tiles) {
                View tile = new View(act);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(15), dp(11));
                lp.rightMargin = dp(4);
                tile.setLayoutParams(lp);
                GradientDrawable g = new GradientDrawable();
                g.setCornerRadius(dp(3));
                g.setColor(soft(groupColor(name)));
                tile.setBackground(g);
                box.addView(tile);
            }
        }
        return box;
    }

    private void bounce(View v) {
        v.animate().scaleX(1.03f).scaleY(1.03f).setDuration(110)
                .withEndAction(() -> v.animate().scaleX(1f).scaleY(1f).setDuration(110).start())
                .start();
    }

    private void updatePresets() {
        for (Scheme s : Scheme.values()) {
            Row r = rows.get(s);
            if (r == null) {
                continue;
            }
            boolean selected = cfg.scheme == s;
            r.root.setBackgroundResource(selected
                    ? R.drawable.bg_preset_on : R.drawable.bg_preset_off);
            r.title.setTextColor(act.getColor(R.color.text_primary));
            r.desc.setTextColor(act.getColor(selected
                    ? R.color.text_secondary : R.color.text_tertiary));
            r.check.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(act.getColor(R.color.accent));
            r.check.setBackground(badge);

            Integer n = groupCounts.get(s);
            r.count.setText(n == null ? "待分析" : n + " 个文件夹");
            // 未选中项不要用 text_tertiary：#9CA3AF 压在 #F0F1F4 上对比只有 2.4:1，基本看不清
            r.count.setTextColor(act.getColor(selected
                    ? R.color.accent : R.color.text_secondary));
        }
    }

    /** 某个方案算完之后把组数记下来，让用户在切换前就能比较两种方案分几组。 */
    public void noteGroups(Scheme s, int count) {
        groupCounts.put(s, count);
        updatePresets();
    }

    // ───────────────────────── 分屏布局 ─────────────────────────

    public void renderPlan(Map<String, List<AppEntry>> groups) {
        renderPlan(groups, null);
    }

    /**
     * @param slots 屏号 → 该屏还能用的候选位（小组件会占掉具体的格子）。
     *              整理时的真实排布就按这份数据算，预览必须拿到同一份，否则
     *              "界面说第 1 屏 6 个、写进去只有 4 个"这种事一定会发生。
     */
    public void renderPlan(Map<String, List<AppEntry>> groups, Map<Integer, int[]> slots) {
        lastGroups = groups;
        lastSlots = slots;
        paintPlan();
    }

    /**
     * 只重画排布。没有分组数据时给一句明确的"还差什么"，而不是留白——
     * 否则用户勾了「第一屏留空」却什么都看不到，会误以为开关没生效。
     */
    private void paintPlan() {
        if (lastGroups == null) {
            tvPlanSummary.setText(cfg.firstScreenBlank
                    ? "已记录：第一屏留空 · 分析后显示具体排布"
                    : "分析后这里显示每个文件夹落在第几屏");
            screenBox.removeAllViews();
            lastPlan = null;
            return;
        }
        LayoutPlan plan = LayoutPlan.build(lastGroups, cfg.firstScreenBlank, cfg.pins, lastSlots);
        lastPlan = plan;
        tvPlanSummary.setText(plan.summary());
        screenBox.removeAllViews();
        for (LayoutPlan.Screen s : plan.screens) {
            screenBox.addView(buildScreenRow(plan, s));
        }
    }

    private View buildScreenRow(LayoutPlan plan, LayoutPlan.Screen s) {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        box.setLayoutParams(lp);
        box.setBackgroundResource(s.reserved
                ? R.drawable.bg_screen_reserved : R.drawable.bg_screen);
        box.setPadding(dp(11), dp(9), dp(11), dp(9));

        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(act);
        name.setText("第 " + s.number + " 屏");
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(act.getColor(s.reserved ? R.color.accent : R.color.text_primary));
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(name);

        TextView tag = new TextView(act);
        if (s.reserved) {
            tag.setText("留空");
            tag.setTextColor(act.getColor(R.color.accent));
        } else if (s.overflow()) {
            // 钉屏钉过头了：直接把超出量说出来，而不是显示"剩 -1 格"
            tag.setText("超 " + (s.folders.size() - s.capacity) + " 个");
            tag.setTextColor(act.getColor(R.color.warn));
        } else {
            tag.setText(s.folders.size() + " 个 · 剩 " + s.free() + " 格");
            tag.setTextColor(act.getColor(R.color.text_tertiary));
        }
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        head.addView(tag);

        box.addView(head);

        TextView body = new TextView(act);
        body.setText(plan.describe(s));
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        body.setTextColor(act.getColor(s.reserved
                ? R.color.text_tertiary : R.color.text_secondary));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(4);
        body.setLayoutParams(bp);
        box.addView(body);

        return box;
    }

    // ───────────────────────── 文案 ─────────────────────────

    /** 卡片宽度只有半屏，官方 desc 太长会折成四行；这里给一版短的。 */
    private static String shortDesc(Scheme s) {
        return s == Scheme.COLOR ? "同色系收进同一个文件夹" : "按用途归档，冷门 App 也有去处";
    }

    private static int groupColor(String group) {
        switch (group) {
            case "社交":
                return 0xFF3B82F6;
            case "影音":
                return 0xFFEF4444;
            case "工具":
                return 0xFF64748B;
            case "金融":
                return 0xFF10B981;
            default:
                return 0xFF9CA3AF;
        }
    }

    /** 把颜色调淡成卡片底纹，避免四个实心块太吵。 */
    private static int soft(int color) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return Color.rgb(255 - (255 - r) / 4, 255 - (255 - g) / 4, 255 - (255 - b) / 4);
    }

    private int dp(int v) {
        return Math.round(v * act.getResources().getDisplayMetrics().density);
    }
}
