package io.github.cobylinweiqi.deskly.organize;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

/** 桌面上的一个可见入口（不是"已安装应用"——只包含能从桌面点开的）。 */
public final class AppEntry {

    public final String label;
    public final String pkg;
    public final String activity;
    public final boolean system;
    public final Drawable icon;

    /** 图标主色缓存，0 表示还没算过。按颜色分方案时按需填充，避免重复 rasterize。 */
    public int dominant;

    public AppEntry(String label, String pkg, String activity, boolean system, Drawable icon) {
        this.label = label == null ? pkg : label;
        this.pkg = pkg;
        this.activity = activity;
        this.system = system;
        this.icon = icon;
    }

    /** 与桌面数据里 ShortcutInfo 的识别键保持同一形式，后续 adapt 时直接复用。 */
    public ComponentName component() {
        return new ComponentName(pkg, activity);
    }

    public String flatKey() {
        return pkg + "/" + activity;
    }

    /** 延迟算主色，只算一次。 */
    public int dominantColor() {
        if (dominant == 0) {
            dominant = IconPalette.dominantColor(icon);
        }
        return dominant;
    }

    @Override
    public String toString() {
        return label + " (" + pkg + ")";
    }
}
