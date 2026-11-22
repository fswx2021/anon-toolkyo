package com.shengjian.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.OverScroller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Displays a chapter in a scrollable strip or as individual pages. */
final class MangaReadingView extends View {
    enum Mode {
        VERTICAL("纵向长条"), LTR("从左向右翻页"), RTL("从右向左翻页");

        final String label;

        Mode(String label) { this.label = label; }
    }

    interface ImageResult { void onResult(Bitmap bitmap); }

    interface ImageLoader {
        void load(String image, int desiredWidth, int desiredHeight, ImageResult callback);
        void prefetch(String image, int desiredWidth, int desiredHeight);
    }

    interface Callback {
        void onPosition(int page, int offset);
        void onTapCenter();
        default void onChapterBoundary(int delta) { }
        default boolean hasAdjacentChapter(int delta) { return false; }
    }

    private static final float ONE_PAGE = 1_000_000f;
    private static final float MAX_ZOOM = 4f;
    private static final float DOUBLE_TAP_ZOOM = 2.4f;
    private static final float PLACEHOLDER_RATIO = 1.45f;
    private static final int PREFETCH_DISTANCE = 2;
    private static final int BACKGROUND = 0xff18181b;
    private static final int PLACEHOLDER = 0xff28282c;
    private static final int STATUS_SURFACE = 0xff3a3a3f;
    private static final int STATUS_TEXT = 0xfff9f7f8;
    private static final int LOADING_INDICATOR = 0xffc9c7cb;
    private static final int RETRY_INDICATOR = 0xffefa6b1;

    private final List<String> images;
    private final ImageLoader loader;
    private final Callback callback;
    private final PageState[] pages;
    private final float[] pageTops;
    private final boolean[] prefetched;
    private final OverScroller scroller;
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint statusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final float gap;
    private final Runnable updateImages = this::loadVisibleImages;
    private Mode mode;
    private int currentPage;
    private int pendingOffset;
    private int pendingPage = -1;
    private int pendingPagedOffset;
    private int lastStripPage = -1;
    private int lastStripOffset;
    private int reportedPage = -1;
    private int reportedOffset = Integer.MIN_VALUE;
    private boolean reportedSpread;
    private int nextRequest;
    private int retainedStart;
    private int retainedEnd = -1;
    private int retainedAnchor = -1;
    private float pageHeight;
    private float top;
    private float left;
    private float pagedTop;
    private float scrollShift;
    private float zoom = 1f;
    private float drag;
    private float downX;
    private float downY;
    private float lastFocusX;
    private float lastFocusY;
    private boolean downAtStart;
    private float forwardOverscroll;
    private boolean moved;
    private boolean pinched;
    private boolean stoppedFling;
    private boolean disposed;
    private boolean fitWidth;
    private boolean cropWhiteBorders;
    private boolean doublePage;

    MangaReadingView(Context context, List<String> images, Mode mode, int page, int offset,
                     ImageLoader loader, Callback callback) {
        super(context);
        this.images = new ArrayList<>(images);
        this.mode = mode;
        this.loader = loader;
        this.callback = callback;
        pages = new PageState[images.size()];
        for (int i = 0; i < pages.length; i++) pages[i] = new PageState();
        pageTops = new float[images.size() + 1];
        prefetched = new boolean[images.size()];
        gap = dp(3);
        scroller = new OverScroller(context);
        gestures = new GestureDetector(context, new Gestures());
        gestures.setIsLongpressEnabled(false);
        pinch = new ScaleGestureDetector(context, new PinchGesture());
        pinch.setQuickScaleEnabled(false);
        statusPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        statusPaint.setTextAlign(Paint.Align.LEFT);
        setBackgroundColor(BACKGROUND);
        setFocusable(true);
        setClickable(true);
        currentPage = clampPage(page);
        lastStripPage = currentPage;
        lastStripOffset = offset;
        pendingPagedOffset = Math.max(0, offset);
    }

    Mode mode() { return mode; }

    boolean fitsWidth() { return fitWidth; }

    boolean cropsWhiteBorders() { return cropWhiteBorders; }

    boolean showsDoublePage() { return doublePage; }

    void setDoublePage(boolean enabled) {
        if (doublePage == enabled) return;
        int currentOffset = offset();
        boolean wasSpread = spread();
        doublePage = enabled;
        if (wasSpread != spread()) {
            resetPagedPresentation(currentOffset);
            changed();
        }
    }

    private void resetPagedPresentation(int offset) {
        stopFling();
        drag = left = pagedTop = 0;
        zoom = 1f;
        pendingPagedOffset = Math.max(0, offset);
        applyPagedOffset();
    }

    private boolean spread() { return spread(getWidth(), getHeight()); }

    private boolean spread(int width, int height) {
        return MangaSpread.use(doublePage, mode != Mode.VERTICAL, width, height, dp(600));
    }

    private boolean pagedFitWidth() { return fitWidth && !spread(); }

    void setCropWhiteBorders(boolean enabled) {
        if (cropWhiteBorders == enabled) return;
        int anchor = page();
        int offset = offset();
        PageState anchorPage = pages.length == 0 ? null : pages[anchor];
        float originalY = anchorPage == null ? 0 : originalFraction(anchorPage, offset);
        cropWhiteBorders = enabled;
        stopFling();
        if (getWidth() <= 0 || pages.length == 0) return;
        for (PageState page : pages) {
            if (enabled && page.bitmap != null && !page.cropMeasured) measureCrop(page);
            page.ratio = pageRatio(page);
        }
        if (offset > 0) offset = croppedOffset(anchorPage, originalY);
        rebuildStrip(getWidth());
        if (mode == Mode.VERTICAL) placeStrip(anchor, offset);
        else if (pagedFitWidth()) {
            pendingPagedOffset = Math.max(0, offset);
            applyPagedOffset();
            pagedTop = clamp(pagedTop, 0, maxPagedTop());
        }
        changed();
    }

    void setFitWidth(boolean fitWidth) {
        if (this.fitWidth == fitWidth) return;
        this.fitWidth = fitWidth;
        pagedTop = 0;
        if (!fitWidth) pendingPagedOffset = 0;
        else applyPagedOffset();
        changed();
    }

    int page() {
        if (mode != Mode.VERTICAL || getWidth() == 0) return currentPage;
        return pendingPage >= 0 ? pendingPage : pageAt(top);
    }

    int offset() {
        if (pages.length == 0) return 0;
        if (mode != Mode.VERTICAL) {
            if (!pagedFitWidth()) return pendingPagedOffset;
            if (pendingPagedOffset > 0) return pendingPagedOffset;
            float pixels = getWidth() * pages[currentPage].ratio;
            return pixels <= 0 ? 0 : Math.round(clamp(pagedTop / pixels, 0, 1) * ONE_PAGE);
        }
        if (getWidth() == 0) return lastStripOffset;
        if (pendingPage >= 0) return pendingOffset;
        int index = pageAt(top);
        return Math.round(clamp((top - pageTops[index]) / heightOf(index), 0, 1) * ONE_PAGE);
    }

    void restorePosition(int page, int offset) {
        stopFling();
        currentPage = clampPage(page);
        drag = 0;
        left = pagedTop = 0;
        zoom = 1f;
        pendingPage = -1;
        lastStripPage = currentPage;
        lastStripOffset = offset;
        if (mode == Mode.VERTICAL && getWidth() > 0 && pages.length > 0) placeStrip(currentPage, offset);
        pendingPagedOffset = fitWidth && mode != Mode.VERTICAL ? Math.max(0, offset) : 0;
        applyPagedOffset();
        changed();
    }

    void goToPage(int page) { restorePosition(page, 0); }

    void setMode(Mode mode) {
        if (this.mode == mode) return;
        int page = page();
        if (this.mode == Mode.VERTICAL) {
            lastStripPage = page;
            lastStripOffset = offset();
        }
        int offset = mode == Mode.VERTICAL || this.mode == Mode.VERTICAL
                ? (page == lastStripPage ? lastStripOffset : 0) : offset();
        this.mode = mode;
        restorePosition(page, offset);
    }

    void dispose() {
        disposed = true;
        stopFling();
        removeCallbacks(updateImages);
        for (PageState page : pages) page.bitmap = null;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleImages();
    }

    @Override protected void onDetachedFromWindow() {
        stopFling();
        removeCallbacks(updateImages);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (pages.length == 0 || width <= 0) return;
        int page = oldWidth > 0 && mode == Mode.VERTICAL
                ? (pendingPage >= 0 ? pendingPage : pageAt(top)) : currentPage;
        int offset = oldWidth > 0 && mode == Mode.VERTICAL
                ? (pendingPage >= 0 ? pendingOffset : fractionAt(top, page)) : lastStripOffset;
        if (mode != Mode.VERTICAL && fitWidth && oldWidth > 0 && !spread(oldWidth, oldHeight) && pendingPagedOffset == 0) {
            pendingPagedOffset = Math.round(clamp(pagedTop / (oldWidth * pages[currentPage].ratio), 0, 1) * ONE_PAGE);
        }
        rebuildStrip(width);
        stopFling();
        left = pagedTop = drag = 0;
        zoom = 1f;
        if (mode == Mode.VERTICAL) placeStrip(page, offset);
        else applyPagedOffset();
        Arrays.fill(prefetched, false);
        changed();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pages.length == 0 || getWidth() <= 0) return;
        canvas.save();
        canvas.scale(zoom, zoom);
        canvas.translate(-left, mode == Mode.VERTICAL ? -top : -pagedTop);
        if (mode == Mode.VERTICAL) {
            int first = pageAt(top);
            int last = pageAt(top + visibleHeight());
            for (int page = first; page <= last; page++) {
                bounds.set(0, pageTops[page], getWidth(), pageTops[page] + heightOf(page));
                drawImage(canvas, page, bounds);
            }
        } else {
            drawSpread(canvas, currentPage, drag);
            if (drag != 0) {
                int neighbor = MangaSpread.adjacent(currentPage, deltaForSwipe(drag), spread());
                if (neighbor >= 0 && neighbor < pages.length) {
                    drawSpread(canvas, neighbor, drag - Math.copySign(getWidth(), drag));
                }
            }
        }
        canvas.restore();
    }

    private void drawSpread(Canvas canvas, int page, float shift) {
        int first = MangaSpread.first(page, spread());
        int last = MangaSpread.last(page, pages.length, spread());
        if (first == last) {
            drawPaged(canvas, first, shift, getWidth(), pagedFitWidth());
            return;
        }
        float half = (getWidth() - gap) / 2;
        boolean rtl = mode == Mode.RTL;
        drawPaged(canvas, rtl ? last : first, shift, half, false);
        drawPaged(canvas, rtl ? first : last, shift + half + gap, half, false);
    }

    private void drawPaged(Canvas canvas, int page, float shift, float slotWidth, boolean fitWidth) {
        PageState state = pages[page];
        Bitmap bitmap = state.bitmap;
        if (bitmap == null) {
            bounds.set(shift, 0, shift + slotWidth, getHeight());
        } else {
            float width = fitWidth ? slotWidth : Math.min(slotWidth, getHeight() / state.ratio);
            float height = width * state.ratio;
            float x = shift + (slotWidth - width) / 2f;
            float y = Math.max(0, (getHeight() - height) / 2f);
            bounds.set(x, y, x + width, y + height);
        }
        drawImage(canvas, page, bounds);
    }

    private void drawImage(Canvas canvas, int page, RectF target) {
        PageState state = pages[page];
        if (state.retryBounds != null) state.retryBounds.setEmpty();
        if (state.bitmap != null) {
            canvas.drawBitmap(state.bitmap, cropWhiteBorders ? state.crop : null, target, paint);
            return;
        }
        paint.setColor(PLACEHOLDER);
        canvas.drawRect(target, paint);
        float scrollY = mode == Mode.VERTICAL ? top : pagedTop;
        float showTop = Math.max(scrollY, target.top);
        float showBottom = Math.min(scrollY + visibleHeight(), target.bottom);
        float showLeft = Math.max(left, target.left);
        float showRight = Math.min(left + getWidth() / zoom, target.right);
        float badgeHeight = dp(38) / zoom;
        if (showBottom - showTop < badgeHeight + dp(8) / zoom) return;
        String message = state.failed ? "图片加载失败，点按重试" : "正在加载第 " + (page + 1) + " 页";
        statusPaint.setTextSize(dp(14) / zoom);
        float padding = dp(12) / zoom;
        float badgeWidth = statusPaint.measureText(message) + dp(14) / zoom + 2 * padding;
        if (showRight - showLeft < badgeWidth + dp(8) / zoom) return;
        float centerY = (showTop + showBottom) / 2f;
        float badgeLeft = (showLeft + showRight - badgeWidth) / 2f;
        if (state.failed) {
            if (state.retryBounds == null) state.retryBounds = new RectF();
            float hitRadius = dp(24) / zoom;
            state.retryBounds.set(badgeLeft, centerY - hitRadius, badgeLeft + badgeWidth, centerY + hitRadius);
        }
        statusPaint.setColor(STATUS_SURFACE);
        canvas.drawRoundRect(badgeLeft, centerY - badgeHeight / 2f, badgeLeft + badgeWidth,
                centerY + badgeHeight / 2f, badgeHeight / 2f, badgeHeight / 2f, statusPaint);
        statusPaint.setColor(state.failed ? RETRY_INDICATOR : LOADING_INDICATOR);
        canvas.drawCircle(badgeLeft + padding + dp(3) / zoom, centerY, dp(3) / zoom, statusPaint);
        statusPaint.setColor(STATUS_TEXT);
        canvas.drawText(message, badgeLeft + padding + dp(14) / zoom,
                centerY - (statusPaint.ascent() + statusPaint.descent()) / 2f, statusPaint);
    }

    private void scheduleImages() {
        if (disposed || getWidth() == 0 || !isAttachedToWindow()) return;
        removeCallbacks(updateImages);
        post(updateImages);
    }

    private void loadVisibleImages() {
        if (disposed || pages.length == 0 || getWidth() <= 0) return;
        boolean spread = spread();
        int first = mode == Mode.VERTICAL ? pageAt(top) : MangaSpread.first(currentPage, spread);
        int last = mode == Mode.VERTICAL ? pageAt(top + visibleHeight()) : MangaSpread.last(currentPage, pages.length, spread);
        if (mode != Mode.VERTICAL && drag != 0f) {
            int neighbor = MangaSpread.adjacent(currentPage, deltaForSwipe(drag), spread);
            if (neighbor >= 0 && neighbor < pages.length) {
                first = Math.min(first, neighbor);
                last = Math.max(last, MangaSpread.last(neighbor, pages.length, spread));
            }
        }
        int keepFirst = Math.max(0, first - PREFETCH_DISTANCE);
        int keepLast = Math.min(pages.length - 1, last + PREFETCH_DISTANCE);
        for (int page = retainedStart; page <= retainedEnd; page++) {
            if (page != pendingPage && (page < keepFirst || page > keepLast)) releasePage(page);
        }
        if (retainedAnchor >= 0 && retainedAnchor != pendingPage
                && (retainedAnchor < keepFirst || retainedAnchor > keepLast)) releasePage(retainedAnchor);
        retainedStart = keepFirst;
        retainedEnd = keepLast;
        retainedAnchor = pendingPage >= 0 && (pendingPage < keepFirst || pendingPage > keepLast)
                ? pendingPage : -1;
        for (int page = first; page <= last; page++) request(page);
        if (pendingPage >= 0 && (pendingPage < first || pendingPage > last)) request(pendingPage);
        int width = desired(getWidth(), 1.4f);
        int height = desired(getHeight(), 1.4f);
        for (int distance = 1; distance <= PREFETCH_DISTANCE; distance++) {
            prefetch(last + distance, width, height);
            prefetch(first - distance, width, height);
        }
    }

    private void prefetch(int page, int width, int height) {
        if (page < 0 || page >= pages.length || prefetched[page]) return;
        prefetched[page] = true;
        if (pages[page].bitmap == null && pages[page].ticket == 0) {
            loader.prefetch(images.get(page), width, height);
        }
    }

    private void request(int index) {
        PageState page = pages[index];
        if (page.ticket != 0 || page.failed) return;
        float sampling = zoom > 1.7f && !pinch.isInProgress() ? 2.4f : 1.4f;
        int width = desired(getWidth(), sampling);
        int height = desired(getHeight(), sampling);
        if (page.bitmap != null && page.loadedWidth >= width && page.loadedHeight >= height) return;
        int ticket = ++nextRequest;
        page.ticket = ticket;
        loader.load(images.get(index), width, height, bitmap -> {
            if (disposed || page.ticket != ticket) return;
            page.ticket = 0;
            if (bitmap == null || bitmap.isRecycled()) {
                page.failed = page.bitmap == null;
                page.loadedWidth = width;
                page.loadedHeight = height;
                invalidate();
                return;
            }
            page.bitmap = bitmap;
            page.fullRatio = (float) bitmap.getHeight() / bitmap.getWidth();
            page.crop = null;
            page.cropMeasured = false;
            if (cropWhiteBorders) measureCrop(page);
            page.loadedWidth = width;
            page.loadedHeight = height;
            updateRatio(index, pageRatio(page));
            changed();
        });
    }

    private void releasePage(int index) {
        PageState page = pages[index];
        page.bitmap = null;
        page.failed = false;
        page.ticket = 0;
    }

    private float originalFraction(PageState page, int offset) {
        return cropWhiteBorders && page.crop != null
                ? page.cropTop + (page.cropBottom - page.cropTop) * offset / ONE_PAGE : offset / ONE_PAGE;
    }

    private int croppedOffset(PageState page, float original) {
        float relative = cropWhiteBorders && page.crop != null
                ? (original - page.cropTop) / (page.cropBottom - page.cropTop) : original;
        return Math.round(clamp(relative, 0f, 1f) * ONE_PAGE);
    }

    private static void measureCrop(PageState page) {
        Bitmap original = page.bitmap;
        page.cropMeasured = true;
        try {
            float scale = Math.min(1f, 128f / Math.max(original.getWidth(), original.getHeight()));
            int width = Math.max(1, Math.round(original.getWidth() * scale));
            int height = Math.max(1, Math.round(original.getHeight() * scale));
            Bitmap sample = Bitmap.createScaledBitmap(original, width, height, true);
            int[] pixels = new int[width * height];
            try { sample.getPixels(pixels, 0, width, 0, 0, width, height); }
            finally { if (sample != original) sample.recycle(); }
            int[] bounds = MangaWhiteBorders.find(pixels, width, height);
            if (bounds == null) return;
            page.crop = new Rect(bounds[0] * original.getWidth() / width, bounds[1] * original.getHeight() / height,
                    (int) Math.ceil(bounds[2] * (double) original.getWidth() / width),
                    (int) Math.ceil(bounds[3] * (double) original.getHeight() / height));
            page.croppedRatio = (float) page.crop.height() / page.crop.width();
            page.cropTop = (float) page.crop.top / original.getHeight();
            page.cropBottom = (float) page.crop.bottom / original.getHeight();
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            // Unsupported bitmap storage preserves the full page.
        }
    }

    private float pageRatio(PageState page) {
        return cropWhiteBorders && page.crop != null ? page.croppedRatio : page.fullRatio;
    }

    private void updateRatio(int index, float ratio) {
        PageState page = pages[index];
        boolean needsRestore = pendingPage == index;
        page.dimensionsKnown = true;
        if (Math.abs(page.ratio - ratio) < 0.0001f && !needsRestore) {
            if (index == currentPage) applyPagedOffset();
            return;
        }
        int anchor = mode == Mode.VERTICAL ? page() : currentPage;
        int offset = mode == Mode.VERTICAL ? offset() : 0;
        page.ratio = ratio;
        rebuildStrip(getWidth());
        if (mode == Mode.VERTICAL) {
            float previous = top;
            placeStrip(anchor, offset);
            if (!scroller.isFinished()) scrollShift += top - previous;
        } else if (index == currentPage) applyPagedOffset();
    }

    private void applyPagedOffset() {
        if (mode == Mode.VERTICAL || !pagedFitWidth() || pendingPagedOffset == 0 || getWidth() <= 0
                || pages.length == 0 || !pages[currentPage].dimensionsKnown) return;
        pagedTop = clamp(getWidth() * pages[currentPage].ratio * pendingPagedOffset / ONE_PAGE, 0, maxPagedTop());
        pendingPagedOffset = 0;
    }

    private void rebuildStrip(int width) {
        float position = 0;
        for (int page = 0; page < pages.length; page++) {
            pageTops[page] = position;
            position += Math.max(dp(16), width * pages[page].ratio) + gap;
        }
        pageTops[pages.length] = position;
        pageHeight = Math.max(0, position - gap);
    }

    private float heightOf(int index) { return Math.max(1, pageTops[index + 1] - pageTops[index] - gap); }

    private void placeStrip(int page, int offset) {
        float height = heightOf(page);
        float pixels = offset < 0 ? -(float) offset : height * offset / ONE_PAGE;
        top = clamp(pageTops[page] + clamp(pixels, 0, height - 0.01f), 0, maxTop());
        boolean waitingForAnchorImage = !pages[page].dimensionsKnown
                && (offset != 0 || pageAt(top) != page);
        pendingPage = waitingForAnchorImage ? page : -1;
        pendingOffset = offset;
    }

    private int pageAt(float position) {
        int lower = 0, upper = pages.length;
        while (lower < upper) {
            int middle = (lower + upper) >>> 1;
            if (pageTops[middle] <= position) lower = middle + 1;
            else upper = middle;
        }
        return clampPage(lower - 1);
    }

    private int fractionAt(float position, int page) {
        return Math.round(clamp((position - pageTops[page]) / heightOf(page), 0, 1) * ONE_PAGE);
    }

    private void changed() {
        if (disposed) return;
        invalidate();
        if (getWidth() <= 0 || pages.length == 0) return;
        scheduleImages();
        int page = page();
        int offset = offset();
        if (page != reportedPage || reportedSpread != spread()) {
            reportedSpread = spread();
            updateDescription(page);
        }
        if (page == reportedPage && offset == reportedOffset) return;
        reportedPage = page;
        reportedOffset = offset;
        callback.onPosition(page, offset);
    }

    private void updateDescription(int page) {
        int first = MangaSpread.first(page, spread()) + 1;
        int last = MangaSpread.last(page, pages.length, spread()) + 1;
        String description = "漫画第 " + (first == last ? Integer.toString(first) : first + " 至 " + last)
                + " 页，共 " + pages.length + " 页";
        if (!description.contentEquals(getContentDescription() == null ? "" : getContentDescription())) {
            setContentDescription(description);
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (disposed || pages.length == 0) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stoppedFling = !scroller.isFinished();
            stopFling();
            downX = event.getX();
            downY = event.getY();
            downAtStart = atBoundary(-1);
            forwardOverscroll = 0;
            pinched = moved = false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        pinch.onTouchEvent(event);
        gestures.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && moved && !pinched && zoom == 1f) finishDrag(event);
            drag = 0;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            changed();
        }
        return true;
    }

    private void finishDrag(MotionEvent event) {
        float dx = event.getX() - downX;
        float dy = event.getY() - downY;
        if (mode != Mode.VERTICAL) {
            if (Math.abs(dx) > Math.max(dp(45), getWidth() * 0.16f) && Math.abs(dx) > Math.abs(dy)) {
                turn(deltaForSwipe(dx));
            }
        } else if (Math.abs(dy) > Math.abs(dx)) {
            if (dy < 0 && forwardOverscroll >= dp(32) && atBoundary(1)) callback.onChapterBoundary(1);
            else if (!stoppedFling && downAtStart && atBoundary(-1)
                    && dy > Math.max(dp(65), getHeight() * 0.16f)) callback.onChapterBoundary(-1);
        }
    }

    private boolean atBoundary(int delta) {
        if (mode != Mode.VERTICAL) {
            int candidate = MangaSpread.adjacent(currentPage, delta, spread());
            return candidate < 0 || candidate >= pages.length;
        }
        return delta < 0 ? top <= 0.5f : top >= maxTop() - 0.5f;
    }

    private int deltaForSwipe(float distance) {
        return (distance > 0 ? -1 : 1) * (mode == Mode.RTL ? -1 : 1);
    }

    private void turn(int delta) {
        if (pages.length == 0 || disposed) return;
        if (mode == Mode.VERTICAL) {
            if (atBoundary(delta)) {
                callback.onChapterBoundary(delta);
            } else {
                pendingPage = -1;
                stopFling();
                float destination = clamp(top + visibleHeight() * 0.85f * delta, 0, maxTop());
                scroller.startScroll(0, Math.round(top), 0, Math.round(destination - top), 230);
                postInvalidateOnAnimation();
            }
            return;
        }
        int target = MangaSpread.adjacent(currentPage, delta, spread());
        if (target >= 0 && target < pages.length) goToPage(target);
        else callback.onChapterBoundary(delta);
    }

    private void stopFling() {
        if (!scroller.isFinished()) scroller.forceFinished(true);
        scrollShift = 0;
    }

    @Override public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            top = clamp(scroller.getCurrY() + scrollShift, 0, maxTop());
            changed();
            postInvalidateOnAnimation();
        }
    }

    private void zoomAt(float newZoom, float previousX, float previousY, float x, float y) {
        float oldZoom = zoom;
        float horizontal = left + previousX / oldZoom;
        float vertical = (mode == Mode.VERTICAL ? top : pagedTop) + previousY / oldZoom;
        zoom = clamp(newZoom, 1, MAX_ZOOM);
        left = clamp(horizontal - x / zoom, 0, maxLeft());
        if (mode == Mode.VERTICAL) {
            pendingPage = -1;
            top = clamp(vertical - y / zoom, 0, maxTop());
        } else {
            pagedTop = clamp(vertical - y / zoom, 0, maxPagedTop());
        }
        changed();
    }

    private class PinchGesture extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
            pinched = true;
            drag = 0;
            lastFocusX = detector.getFocusX();
            lastFocusY = detector.getFocusY();
            stopFling();
            return true;
        }

        @Override public boolean onScale(ScaleGestureDetector detector) {
            float x = detector.getFocusX();
            float y = detector.getFocusY();
            zoomAt(zoom * detector.getScaleFactor(), lastFocusX, lastFocusY, x, y);
            lastFocusX = x;
            lastFocusY = y;
            return true;
        }

        @Override public void onScaleEnd(ScaleGestureDetector detector) { scheduleImages(); }
    }

    private class Gestures extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent event) { return true; }

        @Override public boolean onScroll(MotionEvent first, MotionEvent last, float dx, float dy) {
            if (pinched || pinch.isInProgress()) return true;
            moved = true;
            if (mode == Mode.VERTICAL) {
                pendingPage = -1;
                float requestedTop = top + dy / zoom;
                top = clamp(requestedTop, 0, maxTop());
                if (dy < 0 || zoom > 1f) forwardOverscroll = 0;
                else forwardOverscroll += requestedTop - top;
                left = clamp(left + dx / zoom, 0, maxLeft());
            } else if (zoom > 1f) {
                left = clamp(left + dx / zoom, 0, maxLeft());
                pagedTop = clamp(pagedTop + dy / zoom, 0, maxPagedTop());
            } else {
                float horizontal = last.getX() - downX;
                float vertical = last.getY() - downY;
                if (Math.abs(horizontal) > Math.abs(vertical)) {
                    float limit = getWidth() * (atBoundary(deltaForSwipe(horizontal)) ? 0.3f : 1f);
                    drag = clamp(horizontal, -limit, limit);
                } else if (pagedFitWidth()) {
                    drag = 0;
                    pagedTop = clamp(pagedTop + dy, 0, maxPagedTop());
                }
            }
            changed();
            return true;
        }

        @Override public boolean onFling(MotionEvent first, MotionEvent last, float xSpeed, float ySpeed) {
            if (mode != Mode.VERTICAL || pinched || zoom > 1f) return false;
            if (Math.abs(ySpeed) <= Math.abs(xSpeed) || Math.abs(ySpeed) < dp(220)) return false;
            if (atBoundary(ySpeed < 0 ? 1 : -1)) return false;
            stopFling();
            scroller.fling(0, Math.round(top), 0, Math.round(-ySpeed), 0, 0, 0, Math.round(maxTop()));
            postInvalidateOnAnimation();
            return true;
        }

        @Override public boolean onDoubleTap(MotionEvent tap) {
            if (stoppedFling) return true;
            float x = tap.getX(), y = tap.getY();
            zoomAt(zoom > 1.05f ? 1f : DOUBLE_TAP_ZOOM, x, y, x, y);
            return true;
        }

        @Override public boolean onSingleTapConfirmed(MotionEvent tap) {
            if (disposed || stoppedFling || moved || pinched) return true;
            int hit = mode == Mode.VERTICAL ? pageAt(top + tap.getY() / zoom) : pagedHit(tap.getX());
            RectF target = pages[hit].retryBounds;
            float imageX = left + tap.getX() / zoom;
            float imageY = (mode == Mode.VERTICAL ? top : pagedTop) + tap.getY() / zoom;
            if (target != null && target.contains(imageX, imageY) && retryPage(hit)) return true;
            if (zoom > 1f) return performClick();
            float axis = mode == Mode.VERTICAL ? tap.getY() / getHeight() : tap.getX() / getWidth();
            if (axis >= 0.28f && axis <= 0.72f) return performClick();
            int section = axis < 0.28f ? -1 : 1;
            turn(mode == Mode.RTL ? -section : section);
            return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!disposed) callback.onTapCenter();
        return true;
    }

    private int pagedHit(float x) {
        return MangaSpread.pageAt(MangaSpread.first(currentPage, spread()), MangaSpread.last(currentPage, pages.length, spread()),
                mode == Mode.RTL, x / zoom + left, getWidth());
    }

    boolean hasFailedImage() {
        if (disposed || pages.length == 0) return false;
        for (int i = visibleFirst(); i <= visibleLast(); i++) if (pages[i].failed) return true;
        return false;
    }

    boolean retryVisiblePage() {
        if (disposed || pages.length == 0) return false;
        for (int i = visibleFirst(); i <= visibleLast(); i++) if (retryPage(i)) return true;
        return false;
    }

    private int visibleFirst() {
        return mode == Mode.VERTICAL ? pageAt(top) : MangaSpread.first(currentPage, spread());
    }

    private int visibleLast() {
        return mode == Mode.VERTICAL ? pageAt(top + visibleHeight()) : MangaSpread.last(currentPage, pages.length, spread());
    }

    private boolean retryPage(int page) {
        if (!pages[page].failed) return false;
        pages[page].failed = false;
        request(page);
        invalidate();
        return true;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        int direction;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: direction = mode == Mode.RTL ? 1 : -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: direction = mode == Mode.RTL ? -1 : 1; break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_PAGE_UP: direction = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_PAGE_DOWN: direction = 1; break;
            default: return super.onKeyDown(keyCode, event);
        }
        if (!atBoundary(direction) || event.getRepeatCount() == 0) turn(direction);
        return true;
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (hasFailedImage()) {
            info.setContentDescription(getContentDescription() + "，图片加载失败");
            info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_reader_retry_image, "重试加载图片"));
        }
        boolean back = canTurn(-1);
        boolean forward = canTurn(1);
        info.setScrollable(back || forward);
        if (back) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (forward) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == R.id.action_reader_retry_image && !disposed && pages.length > 0) return retryVisiblePage();
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            int direction = action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1;
            if (!canTurn(direction)) return false;
            turn(direction);
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private boolean canTurn(int direction) {
        return !disposed && pages.length > 0 && (!atBoundary(direction) || callback.hasAdjacentChapter(direction));
    }

    private int clampPage(int page) { return Math.max(0, Math.min(pages.length - 1, page)); }
    private float visibleHeight() { return getHeight() / zoom; }
    private float maxTop() {
        float lastPageStart = pages.length > 0 ? pageTops[pages.length - 1] : 0;
        return Math.max(lastPageStart, pageHeight - visibleHeight());
    }
    private float maxLeft() { return Math.max(0, getWidth() - getWidth() / zoom); }
    private float maxPagedTop() {
        float content = pagedFitWidth() && pages.length > 0 && pages[currentPage].dimensionsKnown
                ? Math.max(getHeight(), getWidth() * pages[currentPage].ratio) : getHeight();
        return Math.max(0, content - visibleHeight());
    }
    private int desired(int dimension, float scale) { return Math.max(1, Math.round(dimension * scale)); }
    private float dp(float value) { return PlayerViews.dp(getContext(), value); }

    private static float clamp(float value, float lower, float upper) {
        return Math.max(lower, Math.min(upper, value));
    }

    private static final class PageState {
        Bitmap bitmap;
        float ratio = PLACEHOLDER_RATIO;
        float fullRatio = PLACEHOLDER_RATIO;
        float croppedRatio;
        float cropTop, cropBottom;
        Rect crop;
        RectF retryBounds;
        int ticket;
        int loadedWidth;
        int loadedHeight;
        boolean failed;
        boolean dimensionsKnown;
        boolean cropMeasured;
    }
}
