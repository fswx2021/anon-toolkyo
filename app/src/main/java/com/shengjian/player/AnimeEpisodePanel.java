package com.shengjian.player;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntConsumer;

/** Title and episode selection below the portrait player. */
final class AnimeEpisodePanel extends LinearLayout {
    private static final int VISIBLE_EPISODES = 32;
    private static final int CONTENT_INSET = 16;
    private static final int CHIP_GAP = 8;

    private final TextView title;
    private final TextView description;
    private final LinearLayout chooser;
    private final TextView allEpisodes;
    private final HorizontalScrollView scroll;
    private final LinearLayout chips;
    private final IntConsumer select;
    private int chipWidth;

    AnimeEpisodePanel(Context context, Runnable picker, IntConsumer onSelect) {
        super(context);
        select = onSelect;
        setOrientation(VERTICAL);
        setBackgroundColor(PlayerViews.BG);
        setPadding(0, dp(13), 0, dp(16));

        title = label(19, PlayerViews.FG, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(dp(CONTENT_INSET), 0, dp(CONTENT_INSET), 0);
        markHeading(title);
        addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        description = label(13, PlayerViews.MUTED, false);
        description.setMaxLines(2);
        description.setEllipsize(TextUtils.TruncateAt.END);
        description.setPadding(dp(CONTENT_INSET), dp(5), dp(CONTENT_INSET), dp(13));
        addView(description, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        chooser = new LinearLayout(context);
        chooser.setOrientation(VERTICAL);
        addView(chooser, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        View rule = new View(context);
        rule.setBackgroundColor(PlayerViews.LINE);
        LayoutParams ruleSpace = new LayoutParams(LayoutParams.MATCH_PARENT, dp(1));
        ruleSpace.setMargins(dp(CONTENT_INSET), 0, dp(CONTENT_INSET), 0);
        chooser.addView(rule, ruleSpace);

        LinearLayout heading = new LinearLayout(context);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(CONTENT_INSET), dp(2), dp(12), dp(2));
        TextView label = label(15, PlayerViews.FG, true);
        label.setText("选集");
        markHeading(label);
        heading.addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        allEpisodes = label(13, PlayerViews.MUTED, false);
        allEpisodes.setGravity(Gravity.CENTER);
        allEpisodes.setMinHeight(dp(48));
        allEpisodes.setPadding(dp(8), 0, dp(5), 0);
        allEpisodes.setFocusable(true);
        allEpisodes.setBackground(PlayerViews.button(context, 0, 8, 0));
        allEpisodes.setOnClickListener(view -> picker.run());
        heading.addView(allEpisodes, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        chooser.addView(heading, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setPadding(dp(CONTENT_INSET), 0, dp(CONTENT_INSET), dp(2));
        chips = new LinearLayout(context);
        scroll.addView(chips, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        chooser.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        chipWidth = dp(76);
        scroll.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int available = right - left - scroll.getPaddingLeft() - scroll.getPaddingRight();
            if (available <= 0) return;
            int columns = Math.max(2, available / dp(78));
            int width = Math.max(dp(54), (available - (columns - 1) * dp(CHIP_GAP)) / columns);
            if (width == chipWidth) return;
            chipWidth = width;
            for (int i = 0; i < chips.getChildCount(); i++) {
                View chip = chips.getChildAt(i);
                chip.getLayoutParams().width = chipWidth;
            }
            chips.requestLayout();
        });
    }

    void bind(List<AnimeEngine.Episode> episodes, int current, boolean demo) {
        boolean hasEpisode = !demo && episodes != null && current >= 0 && current < episodes.size();
        chooser.setVisibility(hasEpisode ? VISIBLE : GONE);
        chips.removeAllViews();
        if (!hasEpisode) {
            title.setText(demo ? "本地示例" : "");
            description.setText(demo ? getContext().getString(R.string.anime_demo_description,
                    getContext().getString(R.string.app_name)) : "");
            return;
        }

        AnimeEngine.Episode episode = episodes.get(current);
        title.setText(episode.show.title);
        description.setText(episode.title + " · " + episode.show.source.name);
        allEpisodes.setText("全部 " + episodes.size() + " 集  ›");
        allEpisodes.setContentDescription("查看全部 " + episodes.size() + " 集");

        int first = Math.max(0, Math.min(current - VISIBLE_EPISODES / 2, episodes.size() - VISIBLE_EPISODES));
        int end = Math.min(episodes.size(), first + VISIBLE_EPISODES);
        for (int i = first; i < end; i++) {
            int destination = i;
            TextView chip = chip(episodes.get(i).title, i == current);
            LayoutParams margin = new LayoutParams(chipWidth, dp(48));
            if (i > first) margin.setMarginStart(dp(CHIP_GAP));
            chips.addView(chip, margin);
            chip.setOnClickListener(view -> {
                if (destination != current) select.accept(destination);
            });
            if (i == current) {
                chip.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        scroll.post(() -> centerCurrent(view)));
            }
        }
    }

    private TextView chip(String name, boolean current) {
        TextView chip = label(14, current ? PlayerViews.textTone(PlayerViews.PINK) : PlayerViews.FG, current);
        chip.setText(name);
        chip.setContentDescription(current ? name + "，正在播放的分集" : name);
        chip.setGravity(Gravity.CENTER);
        chip.setSelected(current);
        chip.setFocusable(true);
        chip.setPadding(dp(5), dp(3), dp(5), dp(3));
        chip.setMaxLines(2);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setBackground(PlayerViews.button(getContext(), current ? 0xffffeff1 : PlayerViews.SURFACE, 8, 0));
        return chip;
    }

    private void centerCurrent(View chip) {
        if (chip.getParent() != chips || scroll.getWidth() == 0) return;
        int midpoint = chips.getLeft() + chip.getLeft() + chip.getWidth() / 2;
        scroll.scrollTo(midpoint - scroll.getWidth() / 2, 0);
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void markHeading(View heading) {
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    }

    private int dp(float value) { return PlayerViews.dp(getContext(), value); }
}
