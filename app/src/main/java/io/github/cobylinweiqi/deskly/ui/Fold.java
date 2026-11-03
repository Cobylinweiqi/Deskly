package io.github.cobylinweiqi.deskly.ui;

import android.view.View;
import android.widget.TextView;

/**
 * 一张卡片折叠起来。
 *
 * <p>配置页的几张卡（运行引导 / 备份与恢复 / 关于）篇幅都很长，平铺时一屏放不下，
 * 想操作下面那张必须先划过一整张——而这些事本来互不相干。收起后只留标题与一行摘要，
 * 谁要干什么一眼能挑，点到哪张才展开哪张。
 *
 * <p>摘要行与正文互斥：收起时露摘要（"5 步接入 · root 已就绪"／"4 份备份 · 最近 16:05"／
 * "v0.12.3 · Coby"），展开时让位给正文，避免同一件事说两遍。
 *
 * <p>和首页「精细归位」的折叠用同一套语义：点整行标题切换、文案在「展开 ⌄ / 收起 ⌃」
 * 之间翻转。没有动画——这两个区块展开后的高度差很大，加动画反而让人觉得界面在抖。
 */
public final class Fold {

    public static final String OPEN = "展开 ⌄";
    public static final String CLOSE = "收起 ⌃";

    private Fold() {
    }

    /**
     * @param head   整行标题（含箭头），点击区域就是它
     * @param body   正文容器，初始应为 {@code gone}
     * @param toggle 右侧「展开 / 收起」文案，可为 null
     * @param hint   收起时显示的摘要行，可为 null
     */
    public static void bind(View head, final View body, final TextView toggle, final View hint) {
        if (head == null || body == null) {
            return;
        }
        head.setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            if (hint != null) {
                hint.setVisibility(show ? View.GONE : View.VISIBLE);
            }
            if (toggle != null) {
                toggle.setText(show ? CLOSE : OPEN);
            }
        });
    }
}
