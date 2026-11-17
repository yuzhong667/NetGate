package com.d.netgate;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 浅色视觉规范 + 通用控件。
 *
 * 设计取向：白卡片浮在浅灰底上，圆角 19～26，阴影交给系统绘制。
 */
public final class Ui {

    public static final int BG = 0xFFF2F2F7;
    public static final int CARD = 0xFFFFFFFF;
    public static final int INK = 0xFF0E0E12;
    public static final int TEXT = 0xFF1C1C1E;
    public static final int SUB = 0xFF8A8A8F;
    public static final int SUB2 = 0xFFA8A8B2;

    public static final int BLUE = 0xFF0A84FF;
    public static final int BLUE_DEEP = 0xFF0673E3;
    public static final int GREEN = 0xFF34C759;
    public static final int GREEN_DEEP = 0xFF1E9E43;
    public static final int RED = 0xFFFF3B30;
    public static final int RED_DEEP = 0xFFE8332A;
    public static final int AMBER = 0xFFFF9F0A;
    public static final int AMBER_DEEP = 0xFFC77C00;
    public static final int PURPLE = 0xFF6B48D6;

    public static final int LINE = 0x143C3C43;

    /** 弹窗里的浅灰按钮底色。 */
    public static final int FILL = 0xFFF0F0F4;
    public static final int FILL_PRESSED = 0xFFE4E4EA;

    private Ui() {
    }

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static int sp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                c.getResources().getDisplayMetrics()));
    }

    /** 纯色圆角。 */
    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 竖向渐变圆角。 */
    public static GradientDrawable grad(int top, int bottom, float radiusDp, Context c) {
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    /** 带描边的圆角。 */
    public static GradientDrawable stroke(int color, float radiusDp, float strokeDp,
                                          int strokeColor, Context c) {
        GradientDrawable g = round(color, radiusDp, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    /** 竖向渐变 + 描边。 */
    public static GradientDrawable gradStroke(int top, int bottom, float radiusDp,
                                              float strokeDp, int strokeColor, Context c) {
        GradientDrawable g = grad(top, bottom, radiusDp, c);
        g.setStroke(dp(c, strokeDp), strokeColor);
        return g;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    /**
     * 带柔和外投影的白卡片。
     *
     * 之前用 software layer + Paint.setShadowLayer 手绘，问题是 Canvas 被裁在
     * 自己的 bounds 里，只有圆角外那一小块阴影留下来 —— 于是每个角都挂着一片
     * 灰楔子，比真的投影还显眼。现在改用系统 elevation 投影：轮廓取自背景的
     * 圆角矩形，阴影由 RenderThread 画在 bounds 之外，四角自然是干净的。
     * 强度用 depth() 控制，颜色 alpha 越低越轻。
     */
    public static class ShadowCard extends LinearLayout {

        private float radiusDp = 19f;
        private float depthDp = 2.5f;
        private int shadowColor = 0x1A000000;
        private int bgColor = CARD;

        public ShadowCard(Context c) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(round(bgColor, radiusDp, c));
            setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            setClipToOutline(false);
            applyDepth();
        }

        public ShadowCard radius(float dp) {
            radiusDp = dp;
            setBackground(round(bgColor, radiusDp, getContext()));
            invalidateOutline();
            return this;
        }

        /** 投影强度：depthDp 越大影子越散，color 的 alpha 决定深浅。 */
        public ShadowCard depth(float dp, int color) {
            depthDp = dp;
            shadowColor = color;
            applyDepth();
            return this;
        }

        /** 卡片背景统一走这里，避免每处自己 setBackground 忘了圆角。 */
        public ShadowCard bg(int color) {
            bgColor = color;
            setBackground(round(color, radiusDp, getContext()));
            invalidateOutline();
            return this;
        }

        private void applyDepth() {
            if (depthDp <= 0f) {
                setElevation(0f);
                return;
            }
            setElevation(dp(getContext(), depthDp));
            if (Build.VERSION.SDK_INT >= 28) {
                // alpha 直接就是阴影浓度；环境光再淡一点，避免四周发灰
                int amb = (shadowColor & 0x00FFFFFF)
                        | (((Color.alpha(shadowColor) * 55 / 100) & 0xFF) << 24);
                setOutlineSpotShadowColor(shadowColor);
                setOutlineAmbientShadowColor(amb);
            }
        }
    }

    // ------------------------------------------------------------ 弹窗统一风格

    /** 弹窗统一走这里：白底、圆角、蓝/灰文字按钮。 */
    public static AlertDialog show(AlertDialog.Builder b, Context c) {
        AlertDialog d = b.show();
        polish(d, c);
        return d;
    }

    public static void polish(AlertDialog d, Context c) {
        if (d == null) return;
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setBackgroundDrawable(round(0xFFFFFFFF, 22, c));
                w.setDimAmount(0.32f);
            }
        } catch (Throwable ignored) {
        }
        try {
            TextView title = d.findViewById(android.R.id.title);
            if (title != null) {
                title.setTextColor(INK);
                title.setTextSize(17);
                title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
                title.setLetterSpacing(0f);
            }
        } catch (Throwable ignored) {
        }
        styleButton(d, android.R.id.button1, BLUE, true, c);
        styleButton(d, android.R.id.button2, SUB, false, c);
        styleButton(d, android.R.id.button3, SUB, false, c);
    }

    private static void styleButton(AlertDialog d, int id, int color, boolean bold, Context c) {
        try {
            Button b = d.findViewById(id);
            if (b == null) return;
            b.setTextColor(color);
            b.setTextSize(15);
            b.setAllCaps(false);
            b.setLetterSpacing(0f);
            b.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            b.setMinWidth(0);
            b.setMinimumWidth(0);
            b.setPadding(dp(c, 14), b.getPaddingTop(), dp(c, 14), b.getPaddingBottom());
        } catch (Throwable ignored) {
        }
    }

    /** EditText 在弹窗里的统一外观。 */
    public static void field(TextView et, Context c) {
        et.setBackground(stroke(0xFFF7F7FA, 11, 0.5f, 0x1F000000, c));
        et.setTextColor(INK);
        et.setHintTextColor(SUB2);
        et.setPadding(dp(c, 13), dp(c, 10), dp(c, 13), dp(c, 10));
    }
}
