package com.shengjian.player;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;

/** Draws the app's brief launch wordmark over the already initialized home screen. */
final class LaunchMotion extends View {
    private static final long DURATION_MS = 680;
    private static final float[] DOT_POINTS = {.34f, .7f, .94f};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path wave = new Path();
    private final Path visibleWave = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final float[] dotCenters = new float[DOT_POINTS.length * 2];
    private final float density;
    private final int background = PlayerViews.BG;
    private final int foreground = PlayerViews.FG;
    private final int rose = PlayerViews.tone(0xffd37489, 0xffeca1ae);
    private final int mint = PlayerViews.tone(0xff6d9a8b, 0xff8ec7b5);
    private final String title;
    private Runnable onFinished;
    private ValueAnimator motion;
    private Shader waveColor;
    private float progress;

    static void useCustomSplash(Activity host) {
        if (Build.VERSION.SDK_INT >= 31) {
            host.getSplashScreen().setOnExitAnimationListener(splash -> splash.remove());
        }
    }

    static void show(FrameLayout parent, Runnable onFinished) {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            onFinished.run();
            return;
        }
        parent.addView(new LaunchMotion(parent.getContext(), onFinished),
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private LaunchMotion(Context context, Runnable onFinished) {
        super(context);
        this.onFinished = onFinished;
        density = getResources().getDisplayMetrics().density;
        title = context.getString(R.string.app_name);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(2.4f));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        motion = ValueAnimator.ofFloat(0f, 1f);
        motion.setDuration(DURATION_MS);
        motion.setInterpolator(new LinearInterpolator());
        motion.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            invalidate();
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                Runnable finished = onFinished;
                onFinished = null;
                if (getParent() instanceof ViewGroup parent) parent.removeView(LaunchMotion.this);
                if (finished != null) finished.run();
            }
        });
        motion.start();
    }

    @Override protected void onDetachedFromWindow() {
        if (motion != null) {
            motion.removeAllListeners();
            motion.removeAllUpdateListeners();
            motion.cancel();
            motion = null;
        }
        onFinished = null;
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (width == 0 || height == 0) return;
        float cx = width / 2f;
        float cy = height / 2f + dp(21);
        float span = Math.min(dp(124), width * .32f);
        wave.reset();
        wave.moveTo(cx - span / 2, cy);
        wave.cubicTo(cx - span * .28f, cy + dp(10), cx - span * .12f, cy - dp(14), cx + span * .09f, cy - dp(4));
        wave.cubicTo(cx + span * .27f, cy + dp(4), cx + span * .38f, cy + dp(6), cx + span / 2, cy - dp(3));
        measure.setPath(wave, false);
        float[] point = new float[2];
        for (int i = 0; i < DOT_POINTS.length; i++) {
            measure.getPosTan(measure.getLength() * DOT_POINTS[i], point, null);
            dotCenters[i * 2] = point[0];
            dotCenters[i * 2 + 1] = point[1] - dp(i == 1 ? 9 : 5);
        }
        waveColor = new LinearGradient(cx - span / 2, cy, cx + span / 2, cy,
                rose, mint, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cover = 1f - ease(interval(.53f, 1f));
        canvas.drawColor(withAlpha(background, cover));
        if (waveColor == null) return;

        float trace = ease(interval(0f, .50f));
        float accentOpacity = 1f - ease(interval(.57f, .92f));
        visibleWave.rewind();
        measure.getSegment(0f, measure.getLength() * trace, visibleWave, true);
        paint.setShader(waveColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAlpha(Math.round(255 * accentOpacity));
        canvas.drawPath(visibleWave, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < DOT_POINTS.length; i++) {
            float dot = ease(interval(.14f + i * .08f, .38f + i * .08f));
            paint.setColor(withAlpha(i == 0 ? rose : mint, dot * accentOpacity));
            canvas.drawCircle(dotCenters[2 * i], dotCenters[2 * i + 1] + dp(3) * (1f - dot), dp(1.6f), paint);
        }
        float reveal = ease(interval(.10f, .53f));
        paint.setColor(withAlpha(foreground, reveal * (1f - ease(interval(.61f, .96f)))));
        paint.setTextSize(dp(26));
        float baseline = getHeight() / 2f - dp(5) + dp(6) * (1f - reveal);
        canvas.drawText(title, getWidth() / 2f, baseline, paint);
    }

    private float interval(float start, float end) {
        return Math.max(0f, Math.min(1f, (progress - start) / (end - start)));
    }

    private static float ease(float value) {
        return value * value * (3f - 2f * value);
    }

    private static int withAlpha(int color, float alpha) {
        return (color & 0x00ffffff) | (Math.round(Color.alpha(color) * alpha) << 24);
    }

    private float dp(float value) { return density * value; }
}
