package com.shengjian.player;

import static com.shengjian.player.PlayerViews.BG;
import static com.shengjian.player.PlayerViews.FG;
import static com.shengjian.player.PlayerViews.LINE;
import static com.shengjian.player.PlayerViews.MUTED;
import static com.shengjian.player.PlayerViews.PINK;
import static com.shengjian.player.PlayerViews.SURFACE;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.os.Bundle;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** A chapter reader whose saved position is an offset in the unmodified chapter text. */
final class NovelReadingView extends FrameLayout {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BLOCK_LENGTH = 6_000;
    private static final int SCROLL = 0;
    private static final int PAGES = 1;
    private static final int TOP_MARGIN = 16;
    private static final int BOTTOM_MARGIN = 20;
    private static final String MODE = "novel_page_mode";
    private static final String THEME = "novel_paper";
    private static final String FONT = "novel_font";
    private static final String SIZE = "novel_font_size";
    private static final String SPACING = "novel_line_spacing";
    private static final String MARGIN = "novel_margin";
    private static final String LETTERS = "novel_character_spacing";
    private static final String TAP = "novel_tap_turn";
    private static final String VOLUME = "novel_volume_turn";
    private static final int PINK_WASH = 0xfff9dce9;
    private static final int[] BACKGROUNDS = {0xff211721, 0xfff6eddb, 0xffe1eee2, 0xfffffafc};
    private static final int[] FOREGROUNDS = {0xffebdce8, 0xff40352b, 0xff263c31, 0xff2d1d2b};

    private static final ExecutorService MIGRATIONS = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "novel-position-migration");
        worker.setDaemon(true);
        return worker;
    });

    private final String chapter;
    private final NovelTextTools.LiteralText text;
    private final int[] blockStarts;
    private final SharedPreferences settings;
    private final Runnable previousChapter;
    private final Runnable nextChapter;
    private final Runnable positionChanged;
    private final Runnable toggleControls;
    private final int touchSlop;
    private final AccessibilityNodeInfo.AccessibilityAction toolsAction =
            new AccessibilityNodeInfo.AccessibilityAction(R.id.action_reader_tools, "显示或收起阅读工具");
    private final AccessibilityNodeInfo.AccessibilityAction previousAction =
            new AccessibilityNodeInfo.AccessibilityAction(R.id.action_reader_previous_page, "上一页");
    private final AccessibilityNodeInfo.AccessibilityAction nextAction =
            new AccessibilityNodeInfo.AccessibilityAction(R.id.action_reader_next_page, "下一页");
    private final AccessibilityDelegate readingActions = new AccessibilityDelegate() {
        @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.addAction(toolsAction);
            info.addAction(previousAction);
            info.addAction(nextAction);
        }

        @Override public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
            if (action == toolsAction.getId()) return NovelReadingView.this.performClick();
            if (action == previousAction.getId()) return turn(-1);
            if (action == nextAction.getId()) return turn(1);
            return super.performAccessibilityAction(host, action, arguments);
        }
    };
    private Integer legacyScroll;
    private Future<?> migration;
    private Future<?> findTask;
    private int migrationVersion;
    private int findVersion, highlightRevision, hitStart = -1, hitEnd;
    private String findTerm = "";
    private NovelSpeech speech;
    private int anchor;
    private int mode;
    private int theme;
    private int font;
    private int size;
    private int lineSpacing;
    private int margin;
    private int letterSpacing;
    private boolean tapToTurn;
    private boolean volumeToTurn;

    private TextView pageView;
    private ChapterList scroll;
    private PageBlock pageBlock;
    private AlertDialog settingsDialog;
    private Runnable pendingSettingsUpdate;
    private float downX, downY;
    private float chapterEndY = Float.NaN;
    private long downTime;
    private boolean fingerDown, moved, swiping, chapterSwipeAllowed;

    NovelReadingView(Context context, NovelTextTools.LiteralText chapterText, int savedOffset, Integer legacyScrollPx,
                     Runnable previousChapter, Runnable nextChapter, Runnable positionChanged,
                     Runnable toggleControls) {
        super(context);
        text = chapterText;
        chapter = text.display;
        blockStarts = splitBlocks(chapter);
        settings = context.getSharedPreferences("reader_display", Context.MODE_PRIVATE);
        this.previousChapter = previousChapter;
        this.nextChapter = nextChapter;
        this.positionChanged = positionChanged;
        this.toggleControls = toggleControls;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        legacyScroll = legacyScrollPx != null && legacyScrollPx > 0 ? legacyScrollPx : null;
        anchor = characterOffset(text.displayOffset(savedOffset));
        readSettings();
        rebuild();
    }

    int length() { return text.original.length(); }

    int position() { return text.originalOffset(anchor); }

    boolean isPositionReady() { return legacyScroll == null; }

    void jumpTo(int characterOffset) {
        jumpToDisplay(text.displayOffset(characterOffset));
    }

    private void jumpToDisplay(int characterOffset) {
        boolean resolvesLegacy = legacyScroll != null;
        legacyScroll = null;
        cancelMigration();
        setAnchor(characterOffset, resolvesLegacy);
        if (mode == PAGES) showAnchoredPage();
        else if (scroll != null) scroll.restore();
    }

    void findText(String term, int direction, IntConsumer result) {
        cancelFind();
        if (term.isEmpty()) {
            result.accept(-1);
            return;
        }
        int start = findTerm.equals(term) && hitStart >= 0 ? hitStart + direction : anchor;
        int generation = findVersion;
        findTask = MIGRATIONS.submit(() -> {
            int hit = NovelTextTools.find(chapter, term, start, direction);
            if (Thread.currentThread().isInterrupted()) return;
            post(() -> {
                if (!isAttachedToWindow() || generation != findVersion) return;
                findTask = null;
                findTerm = term;
                setFindHighlight(hit, hit + term.length());
                if (hit >= 0) jumpToDisplay(hit);
                result.accept(hit < 0 ? hit : text.originalOffset(hit));
            });
        });
    }

    void clearFind() {
        cancelFind();
        findTerm = "";
        setFindHighlight(-1, 0);
    }

    private void cancelFind() {
        findVersion++;
        if (findTask != null) findTask.cancel(true);
        findTask = null;
    }

    private void setFindHighlight(int start, int end) {
        if (start == hitStart && end == hitEnd) return;
        hitStart = start;
        hitEnd = end;
        highlightRevision++;
        if (scroll != null) scroll.invalidateViews();
        else showAnchoredPage();
    }

    boolean speaking() { return speech != null && speech.active(); }

    void startSpeech() {
        if (!isPositionReady()) return;
        if (speech == null) speech = new NovelSpeech(getContext(), chapter, this::jumpToDisplay,
                message -> Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show());
        speech.start(anchor);
    }

    void stopSpeech() {
        if (speech != null) speech.stop();
    }

    boolean handleVolumeKey(int keyCode) {
        if (!volumeToTurn) return false;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) turn(-1);
        else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) turn(1);
        else return false;
        return true;
    }

    boolean turn(int direction) {
        if (direction == 0 || !isPositionReady()) return false;
        int step = direction < 0 ? -1 : 1;
        if (mode == SCROLL) {
            if (scroll == null || scroll.getHeight() <= 0) return false;
            if (!scroll.canScrollVertically(step)) return turnChapter(step);
            int viewport = scroll.getHeight() - scroll.getPaddingTop() - scroll.getPaddingBottom();
            scroll.smoothScrollBy(step * Math.max(dp(32), viewport - dp(28)), 175);
            return true;
        }
        if (pageBlock == null) return false;
        int page = pageBlock.indexOf(anchor);
        int target = page + step;
        if (target >= 0 && target < pageBlock.starts.length) {
            setAnchor(pageBlock.starts[target], false);
        } else {
            int block = pageBlock.block + step;
            if (block < 0 || block >= blockCount()) return turnChapter(step);
            int boundary = step > 0 ? blockStarts[block] : blockStarts[block + 1] - 1;
            PageBlock adjacent = measureBlock(block);
            if (adjacent == null) return false;
            pageBlock = adjacent;
            setAnchor(adjacent.starts[adjacent.indexOf(boundary)], false);
        }
        showAnchoredPage();
        return true;
    }

    private boolean turnChapter(int step) {
        Runnable callback = step < 0 ? previousChapter : nextChapter;
        if (callback == null) return false;
        callback.run();
        return true;
    }

    private void rebuild() {
        setBackgroundColor(BACKGROUNDS[theme]);
        removeAllViews();
        pageBlock = null;
        scroll = null;
        pageView = null;
        if (mode == SCROLL) {
            scroll = new ChapterList();
            addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));
        } else {
            pageView = bodyText();
            pageView.setPadding(dp(margin), dp(TOP_MARGIN), dp(margin), dp(BOTTOM_MARGIN));
            addView(pageView, new FrameLayout.LayoutParams(MATCH, MATCH));
            showAnchoredPage();
        }
        migrateIfNeeded();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        pageBlock = null;
        if (legacyScroll != null && width != oldWidth) cancelMigration();
        migrateIfNeeded();
        if (mode == PAGES) showAnchoredPage();
        else if (scroll != null) scroll.restore();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        migrateIfNeeded();
    }

    @Override protected void onDetachedFromWindow() {
        cancelMigration();
        cancelFind();
        stopSpeech();
        if (pendingSettingsUpdate != null) {
            removeCallbacks(pendingSettingsUpdate);
            pendingSettingsUpdate = null;
        }
        if (settingsDialog != null) {
            settingsDialog.setOnDismissListener(null);
            settingsDialog.dismiss();
            settingsDialog = null;
        }
        super.onDetachedFromWindow();
    }

    private TextView bodyText() {
        TextView view = new TextView(getContext());
        view.setTextColor(FOREGROUNDS[theme]);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        view.setTypeface(Typeface.create(font == 1 ? "serif" : "sans-serif", Typeface.NORMAL));
        view.setLetterSpacing(letterSpacing / 100f);
        view.setLineSpacing(dp(5), lineSpacing / 100f);
        view.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        view.setTextDirection(TEXT_DIRECTION_FIRST_STRONG_LTR);
        view.setGravity(Gravity.TOP | Gravity.START);
        view.setIncludeFontPadding(false);
        if (Build.VERSION.SDK_INT >= 28) view.setFallbackLineSpacing(true);
        view.setTextIsSelectable(true);
        view.setAccessibilityDelegate(readingActions);
        view.setPadding(dp(margin), 0, dp(margin), 0);
        return view;
    }

    private TextView bindTextRow(int block, TextView recycled) {
        TextView row = recycled == null ? bodyText() : recycled;
        if (row.getCurrentTextColor() != FOREGROUNDS[theme]) row.setTextColor(FOREGROUNDS[theme]);
        long binding = ((long) highlightRevision << 32) | block;
        if (!Long.valueOf(binding).equals(row.getTag())) {
            row.setTag(binding);
            row.setText(chapterText(blockStarts[block], displayEnd(block)));
        }
        if (row.getLayoutParams() == null) row.setLayoutParams(new AbsListView.LayoutParams(MATCH, WRAP));
        return row;
    }

    private Layout measureLines(int block, int width) {
        if (width <= 2 * dp(margin)) return null;
        TextView measuring = bindTextRow(block, null);
        measuring.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return measuring.getLayout();
    }

    private PageBlock measureBlock(int block) {
        int available = getHeight() - dp(TOP_MARGIN) - dp(BOTTOM_MARGIN);
        if (available <= 0) return null;
        Layout layout = measureLines(block, getWidth());
        if (layout == null) return null;
        int first = blockStarts[block];
        int last = displayEnd(block);
        List<Integer> offsets = new ArrayList<>();
        offsets.add(first);
        int pageTop = layout.getLineTop(0);
        for (int line = 1; line < layout.getLineCount(); line++) {
            int absolute = first + layout.getLineStart(line);
            // A final newline can produce an empty layout line with no characters left to show.
            if (absolute >= last) break;
            if (layout.getLineBottom(line) - pageTop > available) {
                offsets.add(absolute);
                pageTop = layout.getLineTop(line);
            }
        }
        int[] starts = offsets.stream().mapToInt(Integer::intValue).toArray();
        return new PageBlock(block, starts);
    }

    private void showAnchoredPage() {
        if (pageView == null || !isPositionReady()) return;
        int block = blockAt(anchor);
        if (pageBlock == null || pageBlock.block != block) pageBlock = measureBlock(block);
        if (pageBlock == null) return;
        int index = pageBlock.indexOf(anchor);
        int start = pageBlock.starts[index];
        int end = index + 1 < pageBlock.starts.length ? pageBlock.starts[index + 1] : displayEnd(block);
        if (end > start && chapter.charAt(end - 1) == '\n') end--;
        pageView.setText(chapterText(start, Math.max(start, end)));
    }

    private CharSequence chapterText(int from, int to) {
        String text = chapter.substring(from, to);
        int begin = Math.max(from, hitStart), end = Math.min(to, hitEnd);
        if (hitStart < 0 || begin >= end) return text;
        SpannableString marked = new SpannableString(text);
        marked.setSpan(new BackgroundColorSpan(0xffffd784), begin - from, end - from,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        marked.setSpan(new ForegroundColorSpan(0xff402328), begin - from, end - from,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return marked;
    }

    private void setAnchor(int position, boolean notifyEvenIfUnchanged) {
        int safe = characterOffset(position);
        if (safe == anchor && !notifyEvenIfUnchanged) return;
        anchor = safe;
        if (positionChanged != null) positionChanged.run();
    }

    private int characterOffset(int position) {
        int safe = Math.max(0, Math.min(position, chapter.length() - 1));
        if (safe > 0 && Character.isLowSurrogate(chapter.charAt(safe))
                && Character.isHighSurrogate(chapter.charAt(safe - 1))) return safe - 1;
        return safe;
    }

    private int blockAt(int position) {
        int index = Arrays.binarySearch(blockStarts, position);
        return Math.min(blockCount() - 1, index >= 0 ? index : -2 - index);
    }

    private int blockCount() { return blockStarts.length - 1; }

    private int displayEnd(int block) {
        int end = blockStarts[block + 1];
        return block + 1 < blockCount() && chapter.charAt(end - 1) == '\n' ? end - 1 : end;
    }

    static int[] splitBlocks(String chapter) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        int start = 0;
        while (chapter.length() - start > BLOCK_LENGTH) {
            int stop = start + BLOCK_LENGTH;
            int line = chapter.lastIndexOf('\n', stop - 1);
            if (line >= stop - 1_000) stop = line + 1;
            else if (Character.isHighSurrogate(chapter.charAt(stop - 1))
                    && Character.isLowSurrogate(chapter.charAt(stop))) stop--;
            starts.add(stop);
            start = stop;
        }
        starts.add(chapter.length());
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }

    private static final class PageBlock {
        final int block;
        final int[] starts;

        PageBlock(int block, int[] starts) {
            this.block = block;
            this.starts = starts;
        }

        int indexOf(int offset) {
            int index = Arrays.binarySearch(starts, offset);
            return index >= 0 ? index : Math.max(0, -2 - index);
        }
    }

    private final class ChapterList extends ListView {
        private boolean restoring = true;

        ChapterList() {
            super(NovelReadingView.this.getContext());
            setBackgroundColor(android.graphics.Color.TRANSPARENT);
            setPadding(0, dp(TOP_MARGIN), 0, dp(BOTTOM_MARGIN));
            setDivider(null);
            setDividerHeight(0);
            setSelector(android.R.color.transparent);
            setItemsCanFocus(true);
            setVerticalScrollBarEnabled(false);
            setAdapter(new BaseAdapter() {
                @Override public int getCount() { return blockCount(); }
                @Override public Integer getItem(int position) { return blockStarts[position]; }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View recycled, ViewGroup parent) {
                    return bindTextRow(position, recycled instanceof TextView ? (TextView) recycled : null);
                }
            });
            setOnScrollListener(new OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) { }

                @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (!restoring && isPositionReady() && visible > 0) saveScrollAnchor(first);
                }
            });
        }

        void restore() {
            restoring = true;
            requestLayout();
        }

        @Override protected void layoutChildren() {
            if (restoring && isPositionReady() && getWidth() > 0 && getHeight() > 0) {
                int block = blockAt(anchor);
                Layout layout = measureLines(block, getWidth());
                if (layout != null) {
                    int offset = Math.min(anchor - blockStarts[block], displayEnd(block) - blockStarts[block]);
                    setSelectionFromTop(block, -layout.getLineTop(layout.getLineForOffset(offset)));
                }
            }
            super.layoutChildren();
            restoring = false;
        }

        private void saveScrollAnchor(int first) {
            if (!(getChildAt(0) instanceof TextView row) || row.getLayout() == null) return;
            Layout layout = row.getLayout();
            int top = Math.max(0, getPaddingTop() - row.getTop());
            setAnchor(blockStarts[first] + layout.getLineStart(layout.getLineForVertical(top)), false);
        }
    }

    private void migrateIfNeeded() {
        if (legacyScroll == null || migration != null || getWidth() <= dp(44) || !isAttachedToWindow()) return;
        int pixels = legacyScroll;
        int width = getWidth() - dp(44);
        int verticalPadding = dp(10);
        float legacySize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                settings.getInt("text_size", 19), getResources().getDisplayMetrics());
        int extraSpacing = dp(5);
        int version = ++migrationVersion;
        migration = MIGRATIONS.submit(() -> {
            int converted = 0;
            try {
                converted = convertLegacyOffset(pixels - verticalPadding, width, legacySize, extraSpacing);
            } catch (RuntimeException error) {
                android.util.Log.w("NovelReadingView", "Legacy position conversion failed", error);
            }
            int offset = converted;
            post(() -> {
                if (version == migrationVersion && legacyScroll != null) jumpTo(offset);
            });
        });
    }

    private int convertLegacyOffset(int pixels, int width, float textSize, int extraSpacing) {
        if (pixels <= 0) return 0;
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTextSize(textSize);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        String original = text.original;
        int[] blocks = original == chapter ? blockStarts : splitBlocks(original);
        for (int block = 0; block < blocks.length - 1; block++) {
            if (Thread.currentThread().isInterrupted()) return 0;
            int from = blocks[block], end = blocks[block + 1];
            if (block + 1 < blocks.length - 1 && original.charAt(end - 1) == '\n') end--;
            StaticLayout measured = StaticLayout.Builder.obtain(original, from, end, paint, width)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
                    .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
                    .setLineSpacing(extraSpacing, 1.25f)
                    .build();
            if (pixels < measured.getHeight()) return measured.getLineStart(measured.getLineForVertical(pixels));
            pixels -= measured.getHeight();
        }
        return original.length() - 1;
    }

    private void cancelMigration() {
        migrationVersion++;
        if (migration != null) migration.cancel(true);
        migration = null;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = event.getX();
            downY = event.getY();
            downTime = event.getEventTime();
            fingerDown = true;
            moved = false;
            swiping = false;
            super.dispatchTouchEvent(event);
            chapterSwipeAllowed = true;
            chapterEndY = atChapterScrollEnd() ? downY : Float.NaN;
            return true;
        }
        if (!fingerDown) return super.dispatchTouchEvent(event);
        float deltaX = event.getX() - downX;
        float deltaY = event.getY() - downY;
        long elapsed = event.getEventTime() - downTime;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            fingerDown = false;
            if (swiping) return true;
            return super.dispatchTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (!moved && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                moved = true;
                chapterSwipeAllowed = elapsed < ViewConfiguration.getLongPressTimeout()
                        && -deltaY > Math.abs(deltaX) * 1.4f;
            }
            if (!swiping && mode == PAGES && Math.abs(deltaX) > touchSlop * 2
                    && Math.abs(deltaX) > Math.abs(deltaY) * 1.4f
                    && elapsed < ViewConfiguration.getLongPressTimeout()) {
                cancelChildTouch(event);
                swiping = true;
            }
            if (swiping) return true;
            if (mode == SCROLL) {
                boolean handled = super.dispatchTouchEvent(event);
                if (!chapterSwipeAllowed || !atChapterScrollEnd()) chapterEndY = Float.NaN;
                else if (Float.isNaN(chapterEndY) || event.getY() > chapterEndY) chapterEndY = event.getY();
                return handled;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            fingerDown = false;
            if (swiping) {
                if (Math.abs(deltaX) >= dp(40) && Math.abs(deltaX) > Math.abs(deltaY)) turn(deltaX < 0 ? 1 : -1);
                return true;
            }
            if (chapterSwipeAllowed && moved && -deltaY > Math.abs(deltaX) * 1.4f
                    && chapterEndY - event.getY() >= dp(28) && atChapterScrollEnd()) {
                cancelChildTouch(event);
                return turnChapter(1);
            }
            if (!moved && elapsed < ViewConfiguration.getLongPressTimeout()) {
                float ratio = event.getX() / Math.max(1, getWidth());
                boolean middle = ratio >= 0.32f && ratio <= 0.68f;
                if (middle || tapToTurn) {
                    cancelChildTouch(event);
                    if (middle) performClick();
                    else turn(ratio < 0.32f ? -1 : 1);
                    return true;
                }
            }
        }
        return super.dispatchTouchEvent(event);
    }

    private boolean atChapterScrollEnd() {
        return scroll != null && isPositionReady() && scroll.getChildCount() > 0
                && scroll.getLastVisiblePosition() == blockCount() - 1 && !scroll.canScrollVertically(1);
    }

    private void cancelChildTouch(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
    }

    @Override public boolean performClick() {
        super.performClick();
        if (toggleControls != null) toggleControls.run();
        else showSettings();
        return true;
    }

    void showSettings() {
        if (settingsDialog != null) return;
        LinearLayout panel = new LinearLayout(getContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(3), dp(16), dp(8));
        LinearLayout appearance = settingsCard(panel);
        addChoices(appearance, "翻页方式", new String[]{"上下滚动", "左右翻页"}, mode, false,
                value -> setLayoutSetting(MODE, value));
        addChoices(appearance, "背景", new String[]{"夜读", "米白", "护眼", "白纸"}, theme, true,
                value -> setLayoutSetting(THEME, value));
        addChoices(appearance, "字体", new String[]{"系统默认", "衬线体"}, font, false,
                value -> setLayoutSetting(FONT, value));
        LinearLayout typography = settingsCard(panel);
        addSlider(typography, "字号", 20, size - 14, value -> (value + 14) + " sp",
                value -> debounceSetting(SIZE, value + 14));
        addSlider(typography, "行距", 18, (lineSpacing - 100) / 5, value -> (100 + value * 5) + "%",
                value -> debounceSetting(SPACING, 100 + value * 5));
        addSlider(typography, "边距", 26, margin - 10, value -> (value + 10) + " dp",
                value -> debounceSetting(MARGIN, value + 10));
        addSlider(typography, "字距", 10, letterSpacing, value -> value + "%",
                value -> debounceSetting(LETTERS, value));
        LinearLayout controls = settingsCard(panel);
        addSwitch(controls, "点击两侧翻页", tapToTurn, value -> {
            tapToTurn = value;
            settings.edit().putBoolean(TAP, value).apply();
        });
        addSwitch(controls, "音量键翻页", volumeToTurn, value -> {
            volumeToTurn = value;
            settings.edit().putBoolean(VOLUME, value).apply();
        });

        ScrollView settingsScroll = new ScrollView(getContext());
        settingsScroll.setClipToPadding(false);
        settingsScroll.addView(panel, new ScrollView.LayoutParams(MATCH, WRAP));
        settingsDialog = new AlertDialog.Builder(getContext()).setCustomTitle(settingsTitle())
                .setView(settingsScroll).setPositiveButton("完成", null).create();
        settingsDialog.setOnDismissListener(dialog -> {
            flushSettingsUpdate();
            settingsDialog = null;
        });
        settingsDialog.show();
        ReaderSection.placeReadingSheet(settingsDialog);
        Window window = settingsDialog.getWindow();
        if (window != null) window.setBackgroundDrawable(PlayerViews.surface(getContext(), SURFACE, 24, LINE));
        settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(PINK);
    }

    private LinearLayout settingsTitle() {
        LinearLayout header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(17), dp(15), dp(17), dp(11));
        PlayerViews.Icon icon = new PlayerViews.Icon(getContext(), "book", PINK, 22);
        icon.setBackground(PlayerViews.surface(getContext(), PINK_WASH, 13, 0));
        header.addView(icon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = settingsLabel("阅读设置");
        title.setPadding(dp(11), 0, 0, 0);
        title.setTextSize(21);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        return header;
    }

    private LinearLayout settingsCard(LinearLayout panel) {
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(10), dp(3), dp(10), dp(9));
        card.setBackground(PlayerViews.surface(getContext(), BG, 16, LINE));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(MATCH, WRAP);
        if (panel.getChildCount() > 0) layout.topMargin = dp(8);
        panel.addView(card, layout);
        return card;
    }

    private void readSettings() {
        mode = readInt(MODE, SCROLL, SCROLL, PAGES);
        theme = readInt(THEME, 0, 0, BACKGROUNDS.length - 1);
        font = readInt(FONT, 0, 0, 1);
        size = readInt(SIZE, settings.getInt("text_size", 19), 14, 34);
        lineSpacing = readInt(SPACING, 125, 100, 190);
        margin = readInt(MARGIN, 22, 10, 36);
        letterSpacing = readInt(LETTERS, 0, 0, 10);
        tapToTurn = settings.getBoolean(TAP, true);
        volumeToTurn = settings.getBoolean(VOLUME, false);
    }

    private int readInt(String key, int fallback, int minimum, int maximum) {
        return Math.max(minimum, Math.min(settings.getInt(key, fallback), maximum));
    }

    private void setLayoutSetting(String key, int value) {
        settings.edit().putInt(key, value).apply();
        int oldTheme = theme;
        readSettings();
        if (THEME.equals(key)) {
            if (oldTheme != theme) {
                setBackgroundColor(BACKGROUNDS[theme]);
                if (pageView != null) pageView.setTextColor(FOREGROUNDS[theme]);
                if (scroll != null) {
                    for (int i = 0; i < scroll.getChildCount(); i++) {
                        if (scroll.getChildAt(i) instanceof TextView row) row.setTextColor(FOREGROUNDS[theme]);
                    }
                }
            }
            return;
        }
        rebuild();
    }

    private void debounceSetting(String key, int value) {
        if (pendingSettingsUpdate != null) removeCallbacks(pendingSettingsUpdate);
        // Persist every control immediately so a move to a second slider does not discard a value.
        settings.edit().putInt(key, value).apply();
        pendingSettingsUpdate = () -> {
            pendingSettingsUpdate = null;
            readSettings();
            rebuild();
        };
        postDelayed(pendingSettingsUpdate, 140);
    }

    private void flushSettingsUpdate() {
        if (pendingSettingsUpdate == null) return;
        Runnable update = pendingSettingsUpdate;
        removeCallbacks(update);
        update.run();
    }

    private void addChoices(LinearLayout panel, String title, String[] labels, int current,
                            boolean paperChoices, IntConsumer change) {
        panel.addView(settingsLabel(title), new LinearLayout.LayoutParams(MATCH, WRAP));
        LinearLayout row = new LinearLayout(getContext());
        List<TextView> choices = new ArrayList<>();
        for (int i = 0; i < labels.length; i++) {
            int position = i;
            TextView choice = settingsLabel(labels[i]);
            choice.setPadding(dp(2), dp(5), dp(2), dp(5));
            choice.setMinimumHeight(dp(paperChoices ? 49 : 44));
            choice.setGravity(Gravity.CENTER);
            choice.setFocusable(true);
            if (paperChoices) {
                GradientDrawable paper = PlayerViews.surface(getContext(), BACKGROUNDS[i], 7, FOREGROUNDS[i]);
                paper.setBounds(0, 0, dp(14), dp(14));
                choice.setCompoundDrawablesRelative(null, paper, null, null);
                choice.setCompoundDrawablePadding(dp(3));
            }
            choice.setOnClickListener(view -> {
                for (int j = 0; j < choices.size(); j++) styleChoice(choices.get(j), j == position);
                flushSettingsUpdate();
                change.accept(position);
            });
            styleChoice(choice, position == current);
            choices.add(choice);
            LinearLayout.LayoutParams cell = new LinearLayout.LayoutParams(0, WRAP, 1);
            if (i > 0) cell.setMarginStart(dp(5));
            row.addView(choice, cell);
        }
        panel.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private void styleChoice(TextView choice, boolean selected) {
        choice.setBackground(PlayerViews.button(getContext(), selected ? PINK_WASH : SURFACE, 12,
                selected ? PINK : LINE));
        choice.setTextColor(selected ? PINK : MUTED);
        choice.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        choice.setSelected(selected);
    }

    private void addSlider(LinearLayout panel, String title, int maximum, int current,
                           IntFunction<String> format, IntConsumer change) {
        LinearLayout caption = new LinearLayout(getContext());
        caption.setGravity(Gravity.CENTER_VERTICAL);
        caption.setPadding(0, dp(5), 0, 0);
        caption.addView(settingsLabel(title), new LinearLayout.LayoutParams(0, WRAP, 1));
        TextView value = settingsLabel(format.apply(current));
        value.setPadding(dp(8), dp(3), dp(8), dp(3));
        value.setTextSize(12);
        value.setTextColor(PINK);
        value.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
        value.setBackground(PlayerViews.surface(getContext(), PINK_WASH, 10, 0));
        caption.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        panel.addView(caption, new LinearLayout.LayoutParams(MATCH, WRAP));
        SeekBar bar = new SeekBar(getContext());
        bar.setMax(maximum);
        bar.setProgress(current);
        bar.setContentDescription(title);
        bar.setProgressTintList(ColorStateList.valueOf(PINK));
        bar.setThumbTintList(ColorStateList.valueOf(PINK));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                value.setText(format.apply(progress));
                if (fromUser) change.accept(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { flushSettingsUpdate(); }
        });
        panel.addView(bar, new LinearLayout.LayoutParams(MATCH, dp(42)));
    }

    private void addSwitch(LinearLayout panel, String title, boolean checked, java.util.function.Consumer<Boolean> change) {
        Switch toggle = new Switch(getContext());
        toggle.setText(title);
        toggle.setTextColor(FG);
        toggle.setTextSize(14);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener((button, value) -> change.accept(value));
        toggle.setMinHeight(dp(46));
        panel.addView(toggle, new LinearLayout.LayoutParams(MATCH, WRAP));
    }

    private TextView settingsLabel(String value) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextSize(14);
        label.setTextColor(FG);
        label.setPadding(0, dp(8), 0, dp(5));
        return label;
    }

    private int dp(float amount) { return PlayerViews.dp(getContext(), amount); }
}
