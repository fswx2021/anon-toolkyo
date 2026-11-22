package com.shengjian.player;

import android.app.Activity;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.Locale;

/** Touch surface under the on-screen playback controls. */
final class AnimePlaybackGestures extends View {
    interface Callbacks {
        boolean canAdjust();
        default boolean canAdjustLevel() { return canAdjust(); }
        default boolean canStartTemporaryBoost() { return false; }
        default boolean startTemporaryBoost() { return false; }
        default void stopTemporaryBoost() {}
        void toggleControls();
        void togglePlayback();
        long positionMs();
        long durationMs();
        void scrub(long positionMs, boolean finished);
        void feedback(String label);
    }

    private enum Drag { NONE, SEEK, BRIGHTNESS, VOLUME, IGNORE }

    private final Activity host;
    private final Callbacks controls;
    private final AudioManager audio;
    private final GestureDetector detector;
    private Drag drag = Drag.NONE;
    private boolean tracking, boosting;
    private long duration, initialPosition, seekPosition;
    private float initialBrightness;
    private int maxVolume, initialVolume;

    AnimePlaybackGestures(Activity host, Callbacks controls) {
        super(host);
        this.host = host;
        this.controls = controls;
        audio = host.getSystemService(AudioManager.class);
        setClickable(true);
        setFocusable(true);
        setContentDescription("播放画面，轻触显示或隐藏控件");
        detector = new GestureDetector(host, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event) { return true; }

            @Override public boolean onSingleTapConfirmed(MotionEvent event) { return performClick(); }

            @Override public boolean onDoubleTap(MotionEvent event) {
                if (isShown() && controls.canAdjust()) controls.togglePlayback();
                return true;
            }

            @Override public void onLongPress(MotionEvent event) {
                if (!tracking || boosting || drag != Drag.NONE || !isShown()
                        || !controls.canAdjust() || !controls.canStartTemporaryBoost()) return;
                boosting = controls.startTemporaryBoost();
                if (boosting && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            }

            @Override public boolean onScroll(MotionEvent start, MotionEvent current, float dx, float dy) {
                if (boosting) return true;
                if (start == null || !controls.canAdjust()) return false;
                float horizontal = current.getX() - start.getX();
                float vertical = start.getY() - current.getY();
                if (drag == Drag.NONE) beginDrag(start.getX(), horizontal, vertical);
                switch (drag) {
                    case SEEK -> previewSeek(horizontal);
                    case BRIGHTNESS -> changeBrightness(vertical);
                    case VOLUME -> changeVolume(vertical);
                    case NONE, IGNORE -> { return false; }
                }
                return true;
            }
        });
        detector.setIsLongpressEnabled(false);
    }

    @Override public boolean performClick() {
        super.performClick();
        if (isShown()) controls.toggleControls();
        return true;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                tracking = true;
                detector.setIsLongpressEnabled(controls.canAdjust() && controls.canStartTemporaryBoost());
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                reset();
                return true;
            }
            default -> { }
        }
        if (!tracking) return false;
        detector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            tracking = false;
            finishBoost();
            finishDrag(false);
        }
        return true;
    }

    void reset() {
        tracking = false;
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try {
            detector.onTouchEvent(cancel);
        } finally {
            cancel.recycle();
        }
        finishBoost();
        finishDrag(true);
    }

    @Override protected void onDetachedFromWindow() {
        reset();
        super.onDetachedFromWindow();
    }

    private void beginDrag(float startX, float horizontal, float vertical) {
        drag = Drag.IGNORE;
        if (Math.abs(horizontal) >= Math.abs(vertical)) {
            duration = controls.durationMs();
            if (duration > 0) {
                initialPosition = Math.min(duration, Math.max(0, controls.positionMs()));
                drag = Drag.SEEK;
            }
        } else if (controls.canAdjustLevel()) {
            if (startX < getWidth() / 2f) {
                initialBrightness = currentBrightness();
                drag = Drag.BRIGHTNESS;
            } else if (audio != null && !audio.isVolumeFixed()) {
                maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                initialVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (maxVolume > 0) drag = Drag.VOLUME;
            }
        }
        if (drag != Drag.IGNORE && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    }

    private void previewSeek(float distance) {
        long range = Math.min(duration, Math.max(15_000L, Math.min(300_000L, duration / 5)));
        long offset = Math.round((double) distance / Math.max(1, getWidth()) * range);
        seekPosition = Math.max(0, Math.min(duration, initialPosition + offset));
        controls.scrub(seekPosition, false);
        controls.feedback(formatTime(seekPosition) + " / " + formatTime(duration));
    }

    private float currentBrightness() {
        float brightness = host.getWindow().getAttributes().screenBrightness;
        if (brightness >= 0) return brightness;
        int systemBrightness = Settings.System.getInt(host.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
        return systemBrightness / 255f;
    }

    private void changeBrightness(float distance) {
        if (!controls.canAdjustLevel()) return;
        float level = Math.max(.03f, Math.min(1f, initialBrightness + distance / Math.max(1, getHeight())));
        WindowManager.LayoutParams params = host.getWindow().getAttributes();
        if (Math.abs(params.screenBrightness - level) >= .01f) {
            params.screenBrightness = level;
            host.getWindow().setAttributes(params);
        }
        controls.feedback("亮度 " + Math.round(level * 100) + "%");
    }

    private void changeVolume(float distance) {
        if (!controls.canAdjustLevel()) return;
        int level = Math.max(0, Math.min(maxVolume, initialVolume + Math.round(distance * maxVolume / Math.max(1, getHeight()))));
        try {
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != level) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
            }
            controls.feedback("音量 " + Math.round(100f * audio.getStreamVolume(AudioManager.STREAM_MUSIC) / maxVolume) + "%");
        } catch (SecurityException denied) {
            controls.feedback("系统限制了音量调节");
        }
    }

    private void finishDrag(boolean cancelled) {
        Drag completed = drag;
        drag = Drag.NONE;
        if (completed == Drag.NONE || completed == Drag.IGNORE) return;
        if (completed == Drag.SEEK && !cancelled && controls.canAdjust()) controls.scrub(seekPosition, true);
        controls.feedback("");
    }

    private void finishBoost() {
        if (!boosting) return;
        boosting = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        controls.stopTemporaryBoost();
    }

    private static String formatTime(long milliseconds) {
        long seconds = milliseconds / 1000L;
        return seconds >= 3600L ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600L, seconds / 60L % 60L, seconds % 60L)
                : String.format(Locale.ROOT, "%02d:%02d", seconds / 60L, seconds % 60L);
    }
}
