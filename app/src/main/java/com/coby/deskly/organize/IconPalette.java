package com.coby.deskly.organize;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图标主色提取 + 颜色分桶。
 *
 * <p>难点不在"取平均色"，在于绝大多数 App 图标是白底 + 彩色 logo，直接平均会得到一片灰。
 * 所以采样时按 HSV 做三重剔除：近白、近黑、低饱和，再按饱和度加权——饱和度越高的像素
 * 越可能是 logo 本体而不是底色。
 */
public final class IconPalette {

    private static final int SAMPLE = 48;

    /** 组名 → 该组的代表色，用于预览时的色条。 */
    public static final Map<String, Integer> GROUP_COLORS;

    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("红", 0xFFEF4444);
        m.put("橙", 0xFFF97316);
        m.put("黄", 0xFFEAB308);
        m.put("绿", 0xFF22C55E);
        m.put("青", 0xFF06B6D4);
        m.put("蓝", 0xFF3B82F6);
        m.put("靛", 0xFF4F46E5);
        m.put("紫", 0xFF9333EA);
        m.put("粉", 0xFFEC4899);
        m.put("黑白灰", 0xFF94A3B8);
        GROUP_COLORS = m;
    }

    private IconPalette() {
    }

    /** 提取图标主色；失败或纯灰图标返回 0。 */
    public static int dominantColor(Drawable icon) {
        if (icon == null) {
            return 0;
        }
        Bitmap bmp = null;
        try {
            bmp = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            icon.setBounds(0, 0, SAMPLE, SAMPLE);
            icon.draw(canvas);

            double sr = 0, sg = 0, sb = 0, sw = 0;
            int valid = 0;
            float[] hsv = new float[3];

            for (int y = 0; y < SAMPLE; y++) {
                for (int x = 0; x < SAMPLE; x++) {
                    int px = bmp.getPixel(x, y);
                    int a = (px >>> 24) & 0xFF;
                    if (a < 170) {
                        continue;
                    }
                    int r = (px >> 16) & 0xFF;
                    int g = (px >> 8) & 0xFF;
                    int b = px & 0xFF;
                    Color.RGBToHSV(r, g, b, hsv);
                    float s = hsv[1];
                    float v = hsv[2];

                    if (v > 0.94f && s < 0.10f) {
                        continue; // 近白底
                    }
                    if (v < 0.10f) {
                        continue; // 近黑
                    }
                    if (s < 0.16f) {
                        continue; // 灰
                    }

                    // 饱和度平方加权：让 logo 的饱和部分压过浅色描边
                    double w = s * s;
                    sr += r * w;
                    sg += g * w;
                    sb += b * w;
                    sw += w;
                    valid++;
                }
            }

            if (valid < 12 || sw <= 0) {
                return 0; // 没有足够的彩色像素，交给黑白灰
            }
            int r = (int) Math.round(sr / sw);
            int g = (int) Math.round(sg / sw);
            int b = (int) Math.round(sb / sw);
            return Color.rgb(clamp(r), clamp(g), clamp(b));
        } catch (Throwable t) {
            return 0;
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    /** 主色 → 颜色组名。 */
    public static String groupOf(int color) {
        if (color == 0) {
            return "黑白灰";
        }
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        float h = hsv[0];
        float s = hsv[1];
        float v = hsv[2];

        if (s < 0.16f || v < 0.12f) {
            return "黑白灰";
        }
        if (h < 15 || h >= 345) {
            return "红";
        }
        if (h < 45) {
            return "橙";
        }
        if (h < 66) {
            return "黄";
        }
        if (h < 160) {
            return "绿";
        }
        if (h < 195) {
            return "青";
        }
        if (h < 250) {
            return "蓝";
        }
        if (h < 285) {
            return "靛";
        }
        if (h < 320) {
            return "紫";
        }
        return "粉";
    }

    public static int colorOfGroup(String group) {
        Integer c = GROUP_COLORS.get(group);
        return c == null ? 0xFF94A3B8 : c;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
