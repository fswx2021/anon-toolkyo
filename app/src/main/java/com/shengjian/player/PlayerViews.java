package com.shengjian.player;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;

final class PlayerViews {
    static int BG = 0xffffffff;
    static int SURFACE = 0xfff5f5f6;
    static int LINE = 0xffececef;
    static int FG = 0xff202124;
    static int MUTED = 0xff63656d;
    static final int PINK = 0xffbf3d50;
    static final int LAVENDER = PINK;
    static final int MINT = 0xff426359;
    static final int INK = 0xffffffff;
    static final int DARK_MINT = 0xfff1f3f3;
    private static boolean dark;

    private PlayerViews() {}

    static void appearance(boolean night) {
        dark = night;
        BG = night ? 0xff191a1c : 0xffffffff;
        SURFACE = night ? 0xff232427 : 0xfff5f5f6;
        LINE = night ? 0xff323338 : 0xffececef;
        FG = night ? 0xffededf0 : 0xff202124;
        MUTED = night ? 0xffa9aab2 : 0xff63656d;
    }

    static boolean dark() { return dark; }

    static int tone(int light, int night) { return dark ? night : light; }

    static int textTone(int color) {
        if (color == PINK) return tone(color, 0xfff299a6);
        if (color == MINT) return tone(color, 0xff93c4b6);
        return color;
    }

    static int dp(Context context, float amount) {
        return Math.round(context.getResources().getDisplayMetrics().density * amount);
    }

    static GradientDrawable surface(Context context, int color, float radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(darkSurface(color));
        shape.setCornerRadius(dp(context, radius));
        if (border != 0) shape.setStroke(dp(context, 1), border);
        return shape;
    }

    private static int darkSurface(int color) {
        return switch (color) {
            case 0xfff9dce9, 0xffffedf5, 0xfffff4f8, 0xfffce9f1, 0xfffaeced, 0xffffeff1 ->
                    tone(0xffffeff1, 0xff37272d);
            case 0xffdef4eb, 0xffe8ece3, 0xfff1f3f3 -> tone(0xfff1f3f3, 0xff292b2e);
            default -> color;
        };
    }

    static RippleDrawable button(Context context, int color, float radius, int border) {
        return new RippleDrawable(ColorStateList.valueOf(tone(0x14202124, 0x22ededf0)),
                surface(context, color, radius, border), surface(context, FG, radius, 0));
    }

    static final class Icon extends View {
        private static final RectF DISC_ARC = new RectF(6, 6, 18, 18);
        private static final RectF HEADPHONE_ARC = new RectF(4.5f, 4.5f, 19.5f, 19.5f);
        private static final int[] WAVE_HEIGHTS = {3, 7, 5, 2};
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final float size;
        private String name;
        private int color;

        Icon(Context context, String name, int color, float size) {
            super(context);
            this.name = name;
            this.color = textTone(color);
            this.size = size;
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void show(String name, int color) {
            color = textTone(color);
            if (name.equals(this.name) && this.color == color) return;
            this.name = name;
            this.color = color;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float width = dp(getContext(), size);
            canvas.save();
            canvas.translate((getWidth() - width) / 2f, (getHeight() - width) / 2f);
            canvas.scale(width / 24f, width / 24f);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.75f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            switch (name) {
                case "disc":
                    canvas.drawCircle(12, 12, 9.5f, paint);
                    canvas.drawCircle(12, 12, 3, paint);
                    canvas.drawArc(DISC_ARC, 208, 85, false, paint);
                    break;
                case "note":
                    curve(canvas, 8, 17, 8, 6, 19, 3, 19, 14);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawOval(3, 15, 9, 20, paint);
                    canvas.drawOval(14, 12, 20, 17, paint);
                    break;
                case "sparkle":
                    curve(canvas, 12, 3, 14.4f, 9.6f, 21, 12, 14.4f, 14.4f,
                            12, 21, 9.6f, 14.4f, 3, 12, 9.6f, 9.6f, 12, 3);
                    break;
                case "settings":
                    canvas.drawCircle(12, 12, 3, paint);
                    for (int tooth = 0; tooth < 8; tooth++) {
                        canvas.save();
                        canvas.rotate(tooth * 45f, 12f, 12f);
                        canvas.drawLine(12, 3, 12, 5, paint);
                        canvas.restore();
                    }
                    canvas.drawCircle(12, 12, 7, paint);
                    break;
                case "headphones":
                    canvas.drawArc(HEADPHONE_ARC, 180, 180, false, paint);
                    canvas.drawRoundRect(4, 12, 8, 19, 1.5f, 1.5f, paint);
                    canvas.drawRoundRect(16, 12, 20, 19, 1.5f, 1.5f, paint);
                    break;
                case "panels":
                    canvas.drawRoundRect(3.5f, 5, 20.5f, 19, 3, 3, paint);
                    canvas.drawLine(10, 5, 10, 19, paint);
                    canvas.drawLine(13.5f, 10, 17, 10, paint);
                    canvas.drawLine(13.5f, 14, 17, 14, paint);
                    break;
                case "film":
                    canvas.drawRoundRect(3, 5, 21, 19, 3, 3, paint);
                    curve(canvas, 10, 9, 15, 12, 10, 15, 10, 9);
                    canvas.drawLine(6, 5, 6, 19, paint);
                    canvas.drawLine(18, 5, 18, 19, paint);
                    break;
                case "book":
                    curve(canvas, 12, 6, 9, 4.5f, 3.5f, 4.5f, 3.5f, 18.5f, 9, 18.5f, 12, 20,
                            15, 18.5f, 20.5f, 18.5f, 20.5f, 4.5f, 15, 4.5f, 12, 6, 12, 20);
                    break;
                case "grid":
                    canvas.drawRoundRect(4, 4, 10, 10, 1.5f, 1.5f, paint);
                    canvas.drawRoundRect(14, 4, 20, 10, 1.5f, 1.5f, paint);
                    canvas.drawRoundRect(4, 14, 10, 20, 1.5f, 1.5f, paint);
                    canvas.drawRoundRect(14, 14, 20, 20, 1.5f, 1.5f, paint);
                    break;
                case "tray":
                    curve(canvas, 3,14, 7,6, 17,6, 21,14, 21,20, 3,20, 3,14,
                            8,14, 9.5f,16, 14.5f,16, 16,14, 21,14);
                    break;
                case "download":
                    curve(canvas, 12,3, 12,15);
                    curve(canvas, 7.5f,10.5f, 12,15, 16.5f,10.5f);
                    curve(canvas, 4,16, 4,20, 20,20, 20,16);
                    break;
                case "search":
                    canvas.drawCircle(10, 10, 5.5f, paint);
                    curve(canvas, 14,14, 19,19);
                    break;
                case "file":
                    curve(canvas, 5,3, 13,3, 19,9, 19,21, 5,21, 5,3);
                    curve(canvas, 13,3, 13,9, 19,9);
                    curve(canvas, 8,15, 11,18, 15,14);
                    break;
                case "link":
                    curve(canvas, 9,15, 15,9);
                    canvas.save();
                    canvas.rotate(45, 12, 12);
                    canvas.drawRoundRect(8.5f, 2, 15.5f, 11, 3,3,paint);
                    canvas.drawRoundRect(8.5f, 13, 15.5f, 22, 3,3,paint);
                    canvas.restore();
                    break;
                case "arrow":
                    curve(canvas, 5,17, 17,5);
                    curve(canvas, 8,5, 17,5, 17,14);
                    break;
                case "back":
                    curve(canvas, 19,9, 12,16, 5,9);
                    break;
                case "back_left":
                    curve(canvas, 14,5, 7,12, 14,19);
                    break;
                case "fullscreen":
                    curve(canvas, 4,9, 4,4, 9,4);
                    curve(canvas, 15,4, 20,4, 20,9);
                    curve(canvas, 20,15, 20,20, 15,20);
                    curve(canvas, 9,20, 4,20, 4,15);
                    break;
                case "exit_fullscreen":
                    curve(canvas, 9,4, 9,9, 4,9);
                    curve(canvas, 20,9, 15,9, 15,4);
                    curve(canvas, 15,20, 15,15, 20,15);
                    curve(canvas, 4,15, 9,15, 9,20);
                    break;
                case "chevron":
                    curve(canvas, 9,6, 15,12, 9,18);
                    break;
                case "more":
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 6; i <= 18; i += 6) canvas.drawCircle(i, 12, 1.45f, paint);
                    break;
                case "more_vertical":
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 6; i <= 18; i += 6) canvas.drawCircle(12, i, 1.45f, paint);
                    break;
                case "pip":
                    canvas.drawRoundRect(3, 5, 21, 19, 2, 2, paint);
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(11.5f, 10.5f, 18.5f, 16.5f, 1, 1, paint);
                    break;
                case "camera":
                    curve(canvas, 4, 18, 4, 7, 8, 7, 10, 4.5f, 14, 4.5f, 16, 7, 20, 7, 20, 18, 4, 18);
                    canvas.drawCircle(12, 12.5f, 3, paint);
                    break;
                case "aspect":
                    canvas.drawRoundRect(3, 5, 21, 19, 2, 2, paint);
                    curve(canvas, 6.5f, 12, 6.5f, 8.5f, 10, 8.5f);
                    curve(canvas, 14, 15.5f, 17.5f, 15.5f, 17.5f, 12);
                    break;
                case "lock":
                case "unlock":
                    canvas.drawRoundRect(5.5f, 10, 18.5f, 20, 2, 2, paint);
                    canvas.drawArc(8, 3.5f, 16, 13, 180, 180, false, paint);
                    canvas.drawLine(8, 8.25f, 8, 10, paint);
                    if ("lock".equals(name)) canvas.drawLine(16, 8.25f, 16, 10, paint);
                    canvas.drawLine(12, 14, 12, 16, paint);
                    break;
                case "seek_back":
                case "seek_forward":
                    if ("seek_back".equals(name)) canvas.scale(-1, 1, 12, 12);
                    canvas.drawArc(4.8f, 4.8f, 19.2f, 19.2f, 8, 280, false, paint);
                    curve(canvas, 11.5f, 2.6f, 14.25f, 5.15f, 11.5f, 7.6f);
                    break;
                case "play":
                    paint.setStyle(Paint.Style.FILL);
                    curve(canvas, 8,4, 21,12, 8,20, 8,4);
                    break;
                case "pause":
                    paint.setStyle(Paint.Style.FILL);
                    canvas.drawRoundRect(6,4,10,20,1,1,paint);
                    canvas.drawRoundRect(14,4,18,20,1,1,paint);
                    break;
                case "prev":
                    canvas.scale(-1, 1, 12, 12);
                    // Fall through to draw the mirrored next icon.
                case "next":
                    paint.setStyle(Paint.Style.FILL);
                    curve(canvas, 4,5, 16,12, 4,19, 4,5);
                    canvas.drawRoundRect(17,5,19.5f,19,0.8f,0.8f,paint);
                    break;
                case "queue":
                    curve(canvas, 4,6, 20,6);
                    curve(canvas, 4,12, 20,12);
                    curve(canvas, 4,18, 14,18);
                    break;
                case "shuffle":
                    curve(canvas, 3,6, 7,6, 17,18, 21,18);
                    curve(canvas, 3,18, 7,18, 17,6, 21,6);
                    curve(canvas, 18,3, 21,6, 18,9);
                    curve(canvas, 18,15, 21,18, 18,21);
                    break;
                case "one":
                    curve(canvas, 10,11, 12,9, 12,15);
                    // Fall through to draw the enclosing repeat arrows.
                case "repeat":
                    curve(canvas, 4,10, 4,6, 20,6, 17,3);
                    curve(canvas, 20,6, 17,9);
                    curve(canvas, 20,14, 20,18, 4,18, 7,21);
                    curve(canvas, 4,18, 7,15);
                    break;
                case "wave":
                    for (int i = 0; i < WAVE_HEIGHTS.length; i++) {
                        float x = 5 + i * 4;
                        canvas.drawLine(x, 12 - WAVE_HEIGHTS[i], x, 12 + WAVE_HEIGHTS[i], paint);
                    }
                    break;
            }
            canvas.restore();
        }

        private void curve(Canvas canvas, float... coordinates) {
            path.reset();
            path.moveTo(coordinates[0], coordinates[1]);
            for (int i = 2; i < coordinates.length; i += 2) {
                path.lineTo(coordinates[i], coordinates[i+1]);
            }
            canvas.drawPath(path, paint);
        }
    }
}
