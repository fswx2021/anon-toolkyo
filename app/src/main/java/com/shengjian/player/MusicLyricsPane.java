package com.shengjian.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;

import java.util.Collections;
import java.util.List;
import java.util.function.LongConsumer;

import static com.shengjian.player.PlayerViews.*;

/** A user can browse ahead while playback continues highlighting the current line. */
final class MusicLyricsPane extends LinearLayout {
    private final ListView list;
    private final BaseAdapter adapter;
    private final TextView message, follow, importButton;
    private List<Lyrics.Line> lines = Collections.emptyList();
    private int active = -1;
    private boolean browsing;

    MusicLyricsPane(Context context, LongConsumer jump, Runnable addLyrics) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        message = label(15);
        addView(message);
        importButton = action("添加歌词文件", addLyrics);
        addView(importButton);
        list = new ListView(context) {
            @Override public boolean onGenericMotionEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) browse();
                return super.onGenericMotionEvent(event);
            }
            @Override public boolean onKeyDown(int key, KeyEvent event) {
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || key == KeyEvent.KEYCODE_PAGE_UP || key == KeyEvent.KEYCODE_PAGE_DOWN) browse();
                return super.onKeyDown(key, event);
            }
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) browse();
                return super.performAccessibilityAction(action, args);
            }
        };
        list.setDivider(null);
        list.setClipToPadding(false);
        list.setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
        list.setVerticalScrollBarEnabled(false);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView list, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) browse();
            }
            @Override public void onScroll(AbsListView list, int first, int visible, int total) { }
        });
        adapter = new BaseAdapter() {
            public int getCount() { return lines.size(); }
            public Lyrics.Line getItem(int position) { return lines.get(position); }
            public long getItemId(int position) { return position; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return getItem(position).timeMs >= 0; }
            public View getView(int position, View recycled, ViewGroup parent) {
                TextView line = recycled instanceof TextView view ? view : label(18);
                line.setMinHeight(dp(context, 48));
                line.setPadding(dp(context, 8), dp(context, 7), dp(context, 8), dp(context, 7));
                line.setText(getItem(position).text);
                styleLine(line, position == active);
                return line;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, index, id) -> {
            long time = lines.get(index).timeMs;
            if (time < 0) return;
            jump.accept(time);
            setPosition(time);
            followPlayback();
        });
        addView(list, new LayoutParams(-1, 0, 1));
        follow = action("回到当前歌词", this::followPlayback);
        addView(follow);
        empty("先从曲库选择一首音频", false);
    }

    private TextView label(float sp) {
        TextView view = new TextView(getContext());
        view.setGravity(Gravity.CENTER);
        view.setTextSize(sp);
        view.setTextColor(MUTED);
        return view;
    }

    private TextView action(String text, Runnable callback) {
        TextView view = label(14);
        view.setText(text);
        view.setTextColor(textTone(PINK));
        view.setMinHeight(dp(getContext(), 48));
        view.setPadding(dp(getContext(), 14), 0, dp(getContext(), 14), 0);
        view.setBackground(button(getContext(), Color.TRANSPARENT, 8, 0));
        view.setOnClickListener(v -> callback.run());
        view.setFocusable(true);
        return view;
    }

    void empty(String text, boolean canImport) {
        lines = Collections.emptyList();
        active = -1;
        browsing = false;
        adapter.notifyDataSetChanged();
        message.setText(text);
        message.setVisibility(VISIBLE);
        importButton.setVisibility(canImport ? VISIBLE : GONE);
        follow.setVisibility(GONE);
        list.setVisibility(GONE);
    }

    void showLines(List<Lyrics.Line> result, long position) {
        lines = result;
        active = Lyrics.activeIndex(lines, position);
        browsing = false;
        message.setVisibility(GONE);
        importButton.setVisibility(GONE);
        follow.setVisibility(GONE);
        list.setVisibility(VISIBLE);
        adapter.notifyDataSetChanged();
        list.setSelectionFromTop(Math.max(active, 0), 0);
        list.post(() -> center(false));
    }

    void setPosition(long position) {
        int next = Lyrics.activeIndex(lines, position);
        if (active == next) return;
        int previous = active;
        active = next;
        updateVisible(previous);
        updateVisible(active);
        center(true);
    }

    private void updateVisible(int index) {
        if (index < list.getFirstVisiblePosition() || index > list.getLastVisiblePosition()) return;
        View row = list.getChildAt(index - list.getFirstVisiblePosition());
        if (row instanceof TextView view) styleLine(view, index == active);
    }

    private void styleLine(TextView line, boolean selected) {
        line.setTextColor(selected ? textTone(PINK) : MUTED);
        line.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
        line.setActivated(selected);
    }

    private void browse() {
        if (browsing || lines.isEmpty() || lines.get(0).timeMs < 0) return;
        browsing = true;
        follow.setVisibility(VISIBLE);
    }

    void followPlayback() {
        browsing = false;
        follow.setVisibility(GONE);
        list.post(() -> center(false));
    }

    private void center(boolean animate) {
        if (browsing || active < 0 || !isShown() || list.getHeight() <= 0) return;
        int target = Math.max(0, (list.getHeight() - dp(getContext(), 48)) / 2 - list.getPaddingTop());
        if (animate && Math.abs(active - list.getFirstVisiblePosition()) < 12)
            list.smoothScrollToPositionFromTop(active, target, 170);
        else list.setSelectionFromTop(active, target);
    }
}
