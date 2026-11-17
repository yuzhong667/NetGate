package com.d.netgate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * 24×24 视口的手绘图标。
 *
 * 全部用 Path 直接画，不走 VectorDrawable：
 * 项目没有 aapt 生成 R 的完整流程，代码内绘制反而更省事，
 * 而且描边宽度、圆角、端点样式都能逐像素对齐设计稿。
 */
public class Glyph extends View {

    public static final int CHECK = 1;
    public static final int TRI = 2;
    public static final int SHIELD = 3;
    public static final int SHIELD_CHECK = 4;
    public static final int SWORD = 5;
    public static final int BOLT = 6;
    public static final int CROSS = 7;
    public static final int RADAR = 8;
    public static final int CHEVRON = 9;
    public static final int DOT = 10;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path strokePath = new Path();
    private final Path fillPath = new Path();

    private int type = CHECK;
    private int color = 0xFF1C1C1E;
    private float weight = 2.1f;

    public Glyph(Context c) {
        super(c);
    }

    public Glyph type(int t) {
        this.type = t;
        invalidate();
        return this;
    }

    public Glyph color(int c) {
        this.color = c;
        invalidate();
        return this;
    }

    public Glyph weight(float w) {
        this.weight = w;
        invalidate();
        return this;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        build();

        float size = Math.min(getWidth(), getHeight());
        if (size <= 0) return;
        float s = size / 24f;

        canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(s, s);

        paint.setColor(color);
        paint.setStrokeWidth(weight);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawPath(strokePath, paint);

        if (!fillPath.isEmpty()) {
            paint.setStyle(Paint.Style.FILL);
            canvas.drawPath(fillPath, paint);
        }
        canvas.restore();
    }

    private void build() {
        strokePath.reset();
        fillPath.reset();

        switch (type) {
            case TRI:
                // 三个角都是真正的圆弧，不是靠 stroke-linejoin 磨出来的
                strokePath.moveTo(9.09f, 7.73f);
                strokePath.quadTo(12f, 2.2f, 14.91f, 7.73f);
                strokePath.lineTo(19.29f, 16.07f);
                strokePath.quadTo(22.2f, 21.6f, 15.96f, 21.6f);
                strokePath.lineTo(8.04f, 21.6f);
                strokePath.quadTo(1.8f, 21.6f, 4.71f, 16.07f);
                strokePath.close();
                strokePath.moveTo(12f, 10.2f);
                strokePath.lineTo(12f, 15.2f);
                fillPath.addCircle(12f, 18.4f, 0.95f, Path.Direction.CW);
                break;

            case SHIELD:
                shield(strokePath);
                break;

            case SHIELD_CHECK:
                shield(strokePath);
                strokePath.moveTo(9f, 12.1f);
                strokePath.lineTo(11.2f, 14.3f);
                strokePath.lineTo(15.3f, 10.2f);
                break;

            case SWORD:
                strokePath.moveTo(10.4f, 2.2f);
                strokePath.lineTo(13.6f, 2.2f);
                strokePath.moveTo(12f, 2.2f);
                strokePath.lineTo(12f, 5.8f);
                strokePath.moveTo(6.8f, 5.8f);
                strokePath.lineTo(17.2f, 5.8f);
                strokePath.moveTo(8.8f, 5.8f);
                strokePath.lineTo(15.2f, 5.8f);
                strokePath.lineTo(12f, 19.8f);
                strokePath.close();
                break;

            case BOLT:
                fillPath.moveTo(13.2f, 2f);
                fillPath.lineTo(4.4f, 13.4f);
                fillPath.lineTo(10.9f, 13.4f);
                fillPath.lineTo(9.9f, 22f);
                fillPath.lineTo(19.6f, 10.6f);
                fillPath.lineTo(13f, 10.6f);
                fillPath.close();
                break;

            case CROSS:
                strokePath.moveTo(6.5f, 6.5f);
                strokePath.lineTo(17.5f, 17.5f);
                strokePath.moveTo(17.5f, 6.5f);
                strokePath.lineTo(6.5f, 17.5f);
                break;

            case RADAR:
                strokePath.addCircle(12f, 12f, 3f, Path.Direction.CW);
                spoke(strokePath, 12f, 2f, 12f, 5f);
                spoke(strokePath, 12f, 19f, 12f, 22f);
                spoke(strokePath, 2f, 12f, 5f, 12f);
                spoke(strokePath, 19f, 12f, 22f, 12f);
                spoke(strokePath, 4.9f, 4.9f, 7f, 7f);
                spoke(strokePath, 17f, 17f, 19.1f, 19.1f);
                spoke(strokePath, 19.1f, 4.9f, 17f, 7f);
                spoke(strokePath, 7f, 17f, 4.9f, 19.1f);
                break;

            case CHEVRON:
                strokePath.moveTo(9f, 5f);
                strokePath.lineTo(16f, 12f);
                strokePath.lineTo(9f, 19f);
                break;

            case DOT:
                fillPath.addCircle(12f, 12f, 4.5f, Path.Direction.CW);
                break;

            case CHECK:
            default:
                strokePath.moveTo(4f, 12.5f);
                strokePath.lineTo(9f, 17.5f);
                strokePath.lineTo(20f, 6.5f);
                break;
        }
    }

    private static void shield(Path p) {
        p.moveTo(12f, 2.9f);
        p.lineTo(19.4f, 5.8f);
        p.lineTo(19.4f, 11.6f);
        p.cubicTo(19.4f, 16.0f, 16.5f, 19.3f, 12f, 21.1f);
        p.cubicTo(7.5f, 19.3f, 4.6f, 16.0f, 4.6f, 11.6f);
        p.lineTo(4.6f, 5.8f);
        p.close();
    }

    private static void spoke(Path p, float x1, float y1, float x2, float y2) {
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
    }
}
