package com.shengjian.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** A touch-through overlay whose animation uses the video clock, including at altered speeds. */
final class AnimeDanmakuView extends View {
    enum Mode { SCROLL, TOP, BOTTOM }

    static final class Comment {
        final long positionMs;
        final String text;
        final Mode mode;
        final int color;

        Comment(long positionMs, String text, Mode mode, int color) {
            this.positionMs = positionMs;
            this.text = text;
            this.mode = mode == null ? Mode.SCROLL : mode;
            this.color = Color.alpha(color) == 0 ? (color | 0xff000000) : color;
        }
    }

    private static final int MAX_COMMENTS = 100_000;
    private static final int MAX_TEXT_LENGTH = 240;
    private static final int MAX_LANES = 18;
    private static final int MAX_REPLAY = 5_000;
    private static final long FIXED_MS = 4_000L;
    private static final long SAMPLE_MS = 32L;
    private static final long IDLE_FRAME_MS = 320L;
    private static final long BACKWARD_JITTER_MS = 65L;
    private static final long JUMP_MS = 1_250L;
    static final int DEFAULT_AREA = 75;
    static final int DEFAULT_TEXT_SIZE = 17;
    static final int DEFAULT_OPACITY = 82;

    private final TextPaint ink = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect clip = new Rect();
    private final Rect videoBounds = new Rect();
    private final long[] laneFreeAt = new long[MAX_LANES];
    private final List<Comment> comments = new ArrayList<>();
    private final List<Glyph> active = new ArrayList<>(MAX_LANES * 2);
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frame = this::updateFrame;
    private final Runnable idle = this::endIdle;
    private final PlaybackTime timeline = new PlaybackTime();
    private LongSupplier positionClock = () -> -1L;
    private BooleanSupplier playingClock = () -> false;
    private DoubleSupplier playbackRate = () -> 1d;
    private boolean framePending, idlePending, playing;
    private long rawPosition = -1, nextSampleNanos;
    private int cursor, lanes, fixedLanes;
    private int scrollArea = DEFAULT_AREA, textSize = DEFAULT_TEXT_SIZE, opacity = DEFAULT_OPACITY;
    private long drawnAt = -1L;
    private float laneHeight, baseline, bottomInset, gap, strokeWidth, pixelsPerMs;

    AnimeDanmakuView(Context context) {
        super(context);
        ink.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        ink.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setClickable(false);
        setFocusable(false);
        configureLayout();
    }

    void setPlaybackClock(LongSupplier positionMillis, BooleanSupplier isPlaying) {
        positionClock = positionMillis == null ? () -> -1L : positionMillis;
        playingClock = isPlaying == null ? () -> false : isPlaying;
        resetPlayback();
    }

    void setPlaybackRate(DoubleSupplier rate) {
        playbackRate = rate;
        syncPlayback();
    }

    void setComments(List<Comment> replacement) {
        comments.clear();
        if (replacement != null) {
            long previous = -1L;
            boolean ordered = true;
            for (Comment comment : replacement) {
                if (comment == null || comment.positionMs < 0 || comment.text == null) continue;
                String text = cleanText(comment.text);
                if (text.isEmpty()) continue;
                if (previous > comment.positionMs) ordered = false;
                previous = comment.positionMs;
                comments.add(text == comment.text ? comment : new Comment(comment.positionMs, text, comment.mode, comment.color));
                if (comments.size() == MAX_COMMENTS) break;
            }
            if (!ordered) comments.sort(Comparator.comparingLong(value -> value.positionMs));
        }
        resetPlayback();
    }

    void setAppearance(int area, int size, int alpha) {
        int safeArea = Math.max(25, Math.min(100, area));
        int safeSize = Math.max(12, Math.min(26, size));
        int safeAlpha = Math.max(10, Math.min(100, alpha));
        if (scrollArea == safeArea && textSize == safeSize && opacity == safeAlpha) return;
        scrollArea = safeArea;
        textSize = safeSize;
        opacity = safeAlpha;
        configureLayout();
    }

    /** Call after play/pause and when the playback clock first becomes available. */
    void syncPlayback() {
        nextSampleNanos = 0;
        wake();
    }

    /** Rebuild immediately around the new position after a completed seek or clock change. */
    void resetPlayback() {
        drawnAt = -1L;
        cursor = 0;
        active.clear();
        Arrays.fill(laneFreeAt, Long.MIN_VALUE);
        timeline.clear();
        rawPosition = -1L;
        nextSampleNanos = 0;
        wake();
        invalidate();
    }

    /** Video bounds in overlay coordinates; defaults to the complete overlay. */
    void setVideoBounds(int left, int top, int right, int bottom) {
        if (right <= left || bottom <= top) {
            clearVideoBounds();
            return;
        }
        if (left == videoBounds.left && top == videoBounds.top
                && right == videoBounds.right && bottom == videoBounds.bottom) return;
        videoBounds.set(left, top, right, bottom);
        configureLayout();
    }

    void clearVideoBounds() {
        if (videoBounds.isEmpty()) return;
        videoBounds.setEmpty();
        configureLayout();
    }

    @Override public void setEnabled(boolean enabled) {
        if (isEnabled() == enabled) return;
        super.setEnabled(enabled);
        resetPlayback();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        wake();
    }

    @Override protected void onDetachedFromWindow() {
        stopFrames();
        super.onDetachedFromWindow();
    }

    @Override public void onVisibilityAggregated(boolean visible) {
        super.onVisibilityAggregated(visible);
        if (visible) wake();
        else stopFrames();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        configureLayout();
    }

    @Override protected void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);
        configureLayout();
    }

    private void configureLayout() {
        clip.set(0, 0, getWidth(), getHeight());
        if (!videoBounds.isEmpty() && !clip.intersect(videoBounds)) clip.setEmpty();
        final float textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, textSize,
                getResources().getDisplayMetrics());
        ink.setTextSize(Math.min(dp(textSize * 25f / DEFAULT_TEXT_SIZE), textPx));
        Paint.FontMetrics metrics = ink.getFontMetrics();
        strokeWidth = dp(1.5f);
        gap = dp(13);
        laneHeight = metrics.descent - metrics.ascent + dp(5);
        baseline = dp(6) - metrics.ascent;
        bottomInset = dp(6) + metrics.descent;
        // A common velocity keeps neighboring comments on the same lane from catching up.
        pixelsPerMs = Math.max(dp(38), clip.width() / 5.4f) / 1_000f;
        lanes = Math.min(MAX_LANES, Math.max(0, (int) ((clip.height() - dp(12)) / laneHeight)));
        fixedLanes = lanes >= 4 ? Math.max(1, Math.min(3, lanes / 5)) : 0;
        resetPlayback();
    }

    private boolean canDraw() {
        return isEnabled() && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && !comments.isEmpty() && lanes > 0 && !clip.isEmpty();
    }

    private void wake() {
        if (idlePending) {
            removeCallbacks(idle);
            idlePending = false;
        }
        if (canDraw()) requestFrame();
        else stopFrames();
    }

    private void stopFrames() {
        if (framePending) choreographer.removeFrameCallback(frame);
        if (idlePending) removeCallbacks(idle);
        framePending = false;
        idlePending = false;
        nextSampleNanos = 0;
        timeline.clear();
    }

    private void requestFrame() {
        if (framePending) return;
        framePending = true;
        choreographer.postFrameCallback(frame);
    }

    private void endIdle() {
        idlePending = false;
        requestFrame();
    }

    private void scheduleIdle(long delay) {
        idlePending = true;
        postDelayed(idle, delay);
    }

    private void updateFrame(long frameNanos) {
        framePending = false;
        if (!canDraw()) return;
        if (frameNanos >= nextSampleNanos) {
            try {
                rawPosition = positionClock.getAsLong();
                playing = playingClock.getAsBoolean();
                nextSampleNanos = frameNanos + SAMPLE_MS * 1_000_000L;
            } catch (IllegalStateException ignored) {
                // A media player may be released between the layout and this frame.
                timeline.clear();
                return;
            }
        }
        if (rawPosition < 0L) {
            timeline.clear();
            return;
        }
        double rate = playbackRate.getAsDouble();
        if (!Double.isFinite(rate) || rate <= 0) rate = 1;
        long at = timeline.update(rawPosition, playing, rate, frameNanos);
        boolean moved = at != drawnAt;
        boolean changed = false;
        if (drawnAt < 0 || timeline.jumped || drawnAt - at > BACKWARD_JITTER_MS || at - drawnAt > JUMP_MS) {
            rebuild(at);
            changed = true;
        } else if (at > drawnAt) {
            changed = advance(at);
        }
        long nextEvent = cursor < comments.size() ? comments.get(cursor).positionMs : Long.MAX_VALUE;
        boolean scrolling = false;
        for (Glyph item : active) {
            if (item.mode == Mode.SCROLL) scrolling = true;
            nextEvent = Math.min(nextEvent, item.ends);
        }
        if (changed || (moved && scrolling)) invalidate();
        if (!playing) return;
        if (scrolling && moved) {
            requestFrame();
        } else if (nextEvent != Long.MAX_VALUE) {
            long delay = scrolling ? SAMPLE_MS : (long) ((nextEvent - at) / rate);
            scheduleIdle(Math.max(SAMPLE_MS, Math.min(IDLE_FRAME_MS, delay)));
        }
    }

    private void rebuild(long at) {
        active.clear();
        Arrays.fill(laneFreeAt, Long.MIN_VALUE);
        long maxTravel = (long) Math.ceil((clip.width() * 2f + gap) / pixelsPerMs);
        int start = lowerBound(at - maxTravel - FIXED_MS, false);
        int end = lowerBound(at, true);
        cursor = Math.max(start, end - MAX_REPLAY);
        advance(at);
    }

    private boolean advance(long at) {
        drawnAt = at;
        boolean changed = false;
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).ends <= at) {
                active.remove(i);
                changed = true;
            }
        }
        int remaining = active.size();
        while (cursor < comments.size() && comments.get(cursor).positionMs <= at) {
            addGlyph(comments.get(cursor++), at);
        }
        return changed || active.size() != remaining;
    }

    private int lowerBound(long time, boolean afterEqual) {
        int low = 0;
        int high = comments.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            long at = comments.get(middle).positionMs;
            if (at < time || (afterEqual && at == time)) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private void addGlyph(Comment comment, long at) {
        int start;
        int end;
        int direction = 1;
        if (fixedLanes == 0) {
            start = comment.mode == Mode.BOTTOM ? lanes - 1 : 0;
            end = comment.mode == Mode.BOTTOM ? -1
                    : comment.mode == Mode.SCROLL ? Math.max(1, lanes * scrollArea / 100) : lanes;
            direction = comment.mode == Mode.BOTTOM ? -1 : 1;
        } else if (comment.mode == Mode.BOTTOM) {
            start = lanes - 1;
            end = lanes - fixedLanes - 1;
            direction = -1;
        } else if (comment.mode == Mode.TOP) {
            start = 0;
            end = fixedLanes;
        } else {
            start = fixedLanes;
            end = Math.min(lanes - fixedLanes, Math.max(start + 1, lanes * scrollArea / 100));
        }
        int lane = start;
        long begins = comment.positionMs;
        while (lane != end && laneFreeAt[lane] > begins) lane += direction;
        if (lane == end) return;

        float limit = clip.width() * .90f;
        String text = comment.text;
        float width = ink.measureText(text);
        if (width > limit) {
            text = TextUtils.ellipsize(text, ink, limit, TextUtils.TruncateAt.END).toString();
            width = ink.measureText(text);
        }
        if (text.isEmpty()) return;
        boolean moves = comment.mode == Mode.SCROLL;
        long ends = begins + (moves
                ? (long) Math.ceil((clip.width() + width + gap * 2f) / pixelsPerMs) : FIXED_MS);
        laneFreeAt[lane] = moves && fixedLanes > 0
                ? begins + (long) Math.ceil((width + gap * 2f) / pixelsPerMs) : ends;
        if (ends > at) active.add(new Glyph(text, comment, ends, width, lane, opacity));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!isEnabled() || active.isEmpty() || drawnAt < 0) return;
        int saved = canvas.save();
        canvas.clipRect(clip);
        ink.setStrokeWidth(strokeWidth);
        for (int i = 0; i < active.size(); i++) {
            Glyph label = active.get(i);
            float x = label.mode == Mode.SCROLL ? clip.right + gap - (drawnAt - label.begins) * pixelsPerMs
                    : clip.left + (clip.width() - label.width) / 2f;
            if (x - strokeWidth >= clip.right || x + label.width + strokeWidth <= clip.left) continue;
            float y = label.mode == Mode.BOTTOM && fixedLanes > 0
                    ? clip.bottom - bottomInset - (lanes - 1 - label.lane) * laneHeight
                    : clip.top + baseline + label.lane * laneHeight;
            ink.setStyle(Paint.Style.STROKE);
            ink.setColor(label.outline);
            canvas.drawText(label.text, x, y, ink);
            ink.setStyle(Paint.Style.FILL);
            ink.setColor(label.color);
            canvas.drawText(label.text, x, y, ink);
        }
        canvas.restoreToCount(saved);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static String cleanText(String source) {
        if (source.length() <= MAX_TEXT_LENGTH) {
            boolean clean = !source.startsWith(" ") && !source.endsWith(" ") && !source.contains("  ")
                    && (source.isEmpty() || !Character.isHighSurrogate(source.charAt(source.length() - 1)));
            for (int i = 0; clean && i < source.length(); i++) {
                char c = source.charAt(i);
                clean = !Character.isISOControl(c) && (c == ' ' || !Character.isWhitespace(c));
            }
            if (clean) return source;
        }
        int length = Math.min(source.length(), MAX_TEXT_LENGTH);
        if (length > 0 && Character.isHighSurrogate(source.charAt(length - 1))) length--;
        StringBuilder text = new StringBuilder(length);
        boolean space = false;
        for (int i = 0; i < length; i++) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                space = text.length() > 0;
            } else if (!Character.isISOControl(c)) {
                if (space) text.append(' ');
                text.append(c);
                space = false;
            }
        }
        return text.toString();
    }

    /** Interpolates between decoder reports, stopping prediction when the decoder clock stalls. */
    static final class PlaybackTime {
        private static final double PREDICTION_MS = 220;
        private long lastReport = -1, reportNanos, lastFrameNanos;
        private double estimate;
        private boolean running;
        boolean jumped;

        void clear() {
            lastReport = -1;
            estimate = 0;
            running = false;
            jumped = false;
        }

        long update(long reported, boolean playing, double rate, long now) {
            jumped = lastReport >= 0 && (reported - lastReport > JUMP_MS || lastReport - reported > BACKWARD_JITTER_MS);
            if (lastReport < 0 || jumped || playing != running) {
                estimate = lastReport < 0 || jumped ? reported : Math.max(reported, estimate);
                lastReport = reported;
                reportNanos = now;
            } else if (playing) {
                if (reported > lastReport) {
                    lastReport = reported;
                    reportNanos = now;
                }
                double elapsed = Math.max(0, (now - lastFrameNanos) / 1_000_000d) * rate;
                double projected = lastReport + Math.min(PREDICTION_MS, (now - reportNanos) / 1_000_000d) * rate;
                double step = estimate + elapsed;
                double correction = Math.max(-elapsed / 4, Math.min(elapsed / 4, projected - step));
                estimate = Math.max(estimate, Math.min(lastReport + PREDICTION_MS * rate, step + correction));
            } else {
                estimate = Math.max(estimate, reported);
                lastReport = reported;
            }
            running = playing;
            lastFrameNanos = now;
            return Math.round(estimate);
        }
    }

    private static final class Glyph {
        final String text;
        final int color, outline;
        final long begins, ends;
        final float width;
        final int lane;
        final Mode mode;

        Glyph(String text, Comment comment, long ends, float width, int lane, int opacity) {
            this.text = text;
            color = withOpacity(softenColor(comment.color), opacity);
            outline = withOpacity(0xc8191a1e, opacity);
            begins = comment.positionMs;
            this.ends = ends;
            this.width = width;
            this.lane = lane;
            mode = comment.mode;
        }

        private static int softenColor(int color) {
            int red = softenChannel(Color.red(color), 246);
            int green = softenChannel(Color.green(color), 247);
            int blue = softenChannel(Color.blue(color), 249);
            return (color & 0xff000000) | (red << 16) | (green << 8) | blue;
        }

        private static int softenChannel(int channel, int nearWhite) {
            return nearWhite - ((255 - channel) * 43 + 50) / 100;
        }

        private static int withOpacity(int color, int opacity) {
            int alpha = (Color.alpha(color) * opacity + 50) / 100;
            return (color & 0x00ffffff) | (alpha << 24);
        }
    }
}
