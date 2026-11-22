package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Player discussion panel based on Kazumi 2.4.4's EpisodeCommentsSheet and UserCommentsCard. */
final class AnimeCommentsPanel extends LinearLayout implements AutoCloseable {
    private static final int PAGE_SIZE = 12;
    private static final int REPLY_PREVIEW = 2;
    private static final int REQUEST_BUDGET = 42_000;
    private static final String NOT_MATCHED = "未匹配到 Bangumi 番剧";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AnimeCommentAvatars avatars = new AnimeCommentAvatars(main);
    private final ReaderSearchQueue worker = new ReaderSearchQueue(1, REQUEST_BUDGET);
    private final ReaderSearchQueue catalogueWorker = new ReaderSearchQueue(1, REQUEST_BUDGET);
    private final Map<String, Long> subjects = new LinkedHashMap<>();
    private final Map<EpisodeKey, Result> cache = new LinkedHashMap<>();
    private final Map<Long, List<AnimeComments.EpisodeChoice>> catalogues = new LinkedHashMap<>();
    private final Set<Long> expandedReplies = new HashSet<>();
    private final Activity host;
    private final TextView episodeBadge;
    private final TextView episodeTitle;
    private final TextView episodeOriginal;
    private final TextView count;
    private final TextView summary;
    private final TextView order;
    private final TextView reload;
    private final TextView chooseEpisode;
    private final TextView more;
    private final TextView footer;
    private final ProgressBar progress;
    private final LinearLayout rows;
    private final DateFormat dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM);
    private EpisodeKey playbackKey, key;
    private Result result;
    private List<AnimeComments.Thread> sorted = List.of();
    private ReaderSearchQueue.Run pending, cataloguePending;
    private AlertDialog episodePicker;
    private int generation, catalogueGeneration, shown;
    private long discussionEpisode;
    private boolean active, closed, oldestFirst;
    private final Rect visibleMore = new Rect();
    private final ViewTreeObserver.OnScrollChangedListener scrollListener = this::appendVisiblePage;

    private record EpisodeKey(long subject, String title, int number, AnimeComments.EpisodeChoice choice) {}
    private record Result(long subject, AnimeComments.Discussion discussion) {}
    private record Catalogue(long subject, List<AnimeComments.EpisodeChoice> episodes) {}

    AnimeCommentsPanel(Activity host) {
        super(host);
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(dp(16), dp(7), dp(16), dp(24));
        setBackgroundColor(PlayerViews.BG);

        LinearLayout episodeCard = column();
        episodeCard.setBackground(PlayerViews.surface(host, 0xffffeff1, 20, 0));
        episodeCard.setPadding(dp(16), dp(10), dp(16), dp(12));
        LinearLayout header = row();
        episodeBadge = label("", 13, PlayerViews.textTone(PlayerViews.PINK), true);
        header.addView(episodeBadge, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        chooseEpisode = button("切换分集", this::openEpisodePicker);
        chooseEpisode.setBackground(PlayerViews.button(host, PlayerViews.BG, 14, 0));
        header.addView(chooseEpisode, wrap());
        episodeCard.addView(header, matchWrap());
        episodeTitle = label("", 19, PlayerViews.FG, true);
        episodeTitle.setPadding(0, dp(5), 0, 0);
        if (Build.VERSION.SDK_INT >= 28) episodeTitle.setAccessibilityHeading(true);
        episodeCard.addView(episodeTitle, matchWrap());
        episodeOriginal = label("", 12, PlayerViews.MUTED, false);
        episodeOriginal.setMaxLines(1);
        episodeOriginal.setEllipsize(TextUtils.TruncateAt.END);
        episodeOriginal.setPadding(0, dp(4), 0, 0);
        episodeCard.addView(episodeOriginal, matchWrap());
        addView(episodeCard, matchWrap());

        LinearLayout toolbar = row();
        toolbar.setPadding(0, dp(5), 0, dp(3));
        LinearLayout title = row();
        TextView heading = label("讨论", 16, PlayerViews.FG, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        title.addView(heading, wrap());
        count = label("", 12, PlayerViews.MUTED, true);
        count.setPadding(dp(8), 0, 0, 0);
        title.addView(count, wrap());
        toolbar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        order = button("最新优先", this::toggleOrder);
        order.setContentDescription("评论顺序，最新优先");
        toolbar.addView(order, wrap());
        reload = button("刷新", () -> load(true));
        reload.setContentDescription("刷新本集评论");
        toolbar.addView(reload, wrap());
        addView(toolbar, matchWrap());

        summary = label("", 12, PlayerViews.MUTED, false);
        summary.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        summary.setPadding(0, dp(3), 0, dp(11));
        addView(summary, matchWrap());
        progress = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(PlayerViews.PINK));
        LayoutParams indicator = new LayoutParams(dp(23), dp(23));
        indicator.gravity = Gravity.CENTER_HORIZONTAL;
        indicator.bottomMargin = dp(10);
        addView(progress, indicator);

        rows = column();
        addView(rows, matchWrap());
        more = button("加载更多讨论", this::appendPage);
        addView(more, matchWrap());
        footer = label("", 12, PlayerViews.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(4), 0, dp(8));
        addView(footer, matchWrap());
        setVisibility(GONE);
    }

    void bind(AnimeEngine.Episode value) {
        if (closed) return;
        EpisodeKey next = value == null ? null : new EpisodeKey(value.show.bangumiId,
                value.show.title, AnimeComments.number(value), null);
        if (next != null && next.equals(playbackKey)) return;
        cancelEpisodePicker();
        playbackKey = next;
        selectDiscussion(next);
    }

    private void selectDiscussion(EpisodeKey next) {
        cancel();
        key = next;
        expandedReplies.clear();
        setVisibility(next == null ? GONE : VISIBLE);
        result = cache.get(next);
        rebuild();
        if (active) load(false);
    }

    void setActive(boolean value) {
        if (closed || active == value) return;
        active = value;
        if (active) load(false);
        else {
            cancel();
            cancelEpisodePicker();
        }
    }

    private void load(boolean refresh) {
        if (closed || !active || key == null || pending != null || !refresh && result != null) return;
        if (key.number() <= 0) {
            status("Bangumi 暂无此分集");
            reload.setVisibility(GONE);
            return;
        }
        int token = ++generation;
        EpisodeKey request = key;
        long known = knownSubject(request);
        progress.setVisibility(VISIBLE);
        reload.setEnabled(false);
        status(result == null ? "正在读取 Bangumi 评论…" : "");
        pending = worker.start(List.of(request), job -> {
            long subject = known > 0 ? known : AnimeComments.matchSubject(job.title());
            if (subject > 0 && known == 0) {
                main.post(() -> {
                    if (!closed && generation == token) remember(subjects, job.title(), subject, 24);
                });
            }
            AnimeComments.Discussion discussion = job.choice() == null
                    ? AnimeComments.fetch(subject, job.number()) : AnimeComments.fetch(job.choice());
            return new Result(subject, discussion);
        }, (job, loaded, failure) -> main.post(() -> {
            if (closed || generation != token) return;
            pending = null;
            progress.setVisibility(GONE);
            reload.setEnabled(true);
            if (failure != null) {
                reload.setText("重试");
                reload.setContentDescription("重试本集评论");
                showFailure(failure);
                return;
            }
            if (loaded.subject() > 0) remember(subjects, job.title(), loaded.subject(), 24);
            remember(cache, job, loaded, 6);
            result = loaded;
            rebuild();
        }));
    }

    private long knownSubject(EpisodeKey selected) {
        return selected.subject() > 0 ? selected.subject() : subjects.getOrDefault(selected.title(), 0L);
    }

    private void openEpisodePicker() {
        if (key == null || !active || closed || cataloguePending != null || episodePicker != null) return;
        EpisodeKey request = key;
        long known = knownSubject(request);
        List<AnimeComments.EpisodeChoice> saved = catalogues.get(known);
        if (saved != null) {
            showEpisodePicker(known, saved);
            return;
        }
        int token = ++catalogueGeneration;
        chooseEpisode.setEnabled(false);
        chooseEpisode.setText("加载分集…");
        cataloguePending = catalogueWorker.start(List.of(request), job -> {
            long subject = known > 0 ? known : AnimeComments.matchSubject(job.title());
            return new Catalogue(subject, AnimeComments.catalogue(subject));
        }, (job, loaded, failure) -> main.post(() -> {
            if (closed || catalogueGeneration != token) return;
            cataloguePending = null;
            resetPickerButton();
            if (failure != null) {
                Toast.makeText(host, "Bangumi 分集列表加载失败，可以重试", Toast.LENGTH_SHORT).show();
                return;
            }
            if (loaded.subject() <= 0) {
                Toast.makeText(host, NOT_MATCHED, Toast.LENGTH_SHORT).show();
                return;
            }
            remember(subjects, job.title(), loaded.subject(), 24);
            if (!loaded.episodes().isEmpty()) remember(catalogues, loaded.subject(), loaded.episodes(), 3);
            showEpisodePicker(loaded.subject(), loaded.episodes());
        }));
    }

    private void showEpisodePicker(long subject, List<AnimeComments.EpisodeChoice> episodes) {
        if (key == null || !active || host.isFinishing() || host.isDestroyed()) return;
        if (episodes.isEmpty()) {
            Toast.makeText(host, "Bangumi 暂无分集", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[episodes.size()];
        int selected = -1;
        long chosenId = key.choice() == null ? discussionEpisode : key.choice().id();
        for (int i = 0; i < episodes.size(); i++) {
            AnimeComments.EpisodeChoice choice = episodes.get(i);
            labels[i] = choice.title().isEmpty() ? choice.label() : choice.label() + " · " + choice.title();
            if (chosenId > 0 ? chosenId == choice.id() : key.number() == choice.position()) selected = i;
        }
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle("选择讨论分集")
                .setSingleChoiceItems(labels, selected, (window, index) -> {
                    window.dismiss();
                    AnimeComments.EpisodeChoice choice = episodes.get(index);
                    EpisodeKey next = new EpisodeKey(subject, key.title(), choice.position(), choice);
                    if (!next.equals(key)) selectDiscussion(next);
                }).setNegativeButton("取消", null).create();
        episodePicker = dialog;
        dialog.setOnDismissListener(window -> {
            if (episodePicker == dialog) episodePicker = null;
        });
        dialog.show();
    }

    private void cancelEpisodePicker() {
        catalogueGeneration++;
        if (cataloguePending != null) cataloguePending.cancel();
        cataloguePending = null;
        if (episodePicker != null) episodePicker.dismiss();
        episodePicker = null;
        resetPickerButton();
    }

    private void resetPickerButton() {
        chooseEpisode.setEnabled(true);
        chooseEpisode.setText("切换分集");
    }

    private void toggleOrder() {
        if (sorted.size() < 2) return;
        oldestFirst = !oldestFirst;
        order.setText(oldestFirst ? "最早优先" : "最新优先");
        order.setContentDescription(oldestFirst ? "评论顺序，最早优先" : "评论顺序，最新优先");
        rebuild();
    }

    private void rebuild() {
        rows.removeAllViews();
        shown = 0;
        updateEpisodeHeader();
        boolean supported = key != null && key.number() > 0;
        reload.setVisibility(supported ? VISIBLE : GONE);
        reload.setText("刷新");
        reload.setContentDescription("刷新本集评论");
        order.setEnabled(false);
        count.setText("");
        more.setVisibility(GONE);
        footer.setVisibility(GONE);
        discussionEpisode = 0;
        sorted = List.of();
        if (result == null) {
            status(supported ? "" : "Bangumi 暂无此分集");
            return;
        }
        if (result.subject() == 0) {
            status(NOT_MATCHED);
            return;
        }
        AnimeComments.Discussion discussion = result.discussion();
        if (discussion.id() == 0) {
            status("尚未匹配到 Bangumi 的本集讨论");
            return;
        }
        discussionEpisode = discussion.id();
        List<AnimeComments.Thread> ordered = new ArrayList<>(discussion.threads());
        Comparator<AnimeComments.Thread> byDate = Comparator.comparingLong(thread -> thread.comment().createdAt());
        ordered.sort(oldestFirst ? byDate : byDate.reversed());
        sorted = ordered;
        count.setText(Integer.toString(sorted.size()));
        status(sorted.isEmpty() ? "本集还没有讨论" : "");
        order.setEnabled(sorted.size() > 1);
        appendPage();
    }

    private void updateEpisodeHeader() {
        if (key == null) return;
        AnimeComments.EpisodeChoice selected = key.choice();
        String badge = selected == null ? "EP " + key.number() : selected.label();
        String name = selected == null ? "" : selected.title();
        String original = selected == null ? "" : selected.originalTitle();
        if (result != null && result.discussion().id() > 0) {
            if (!result.discussion().title().isEmpty()) name = result.discussion().title();
            original = result.discussion().originalTitle();
        }
        if (name.isEmpty()) name = key.number() > 0 ? "第 " + key.number() + " 集" : "本集讨论";
        episodeBadge.setText(key.number() > 0 ? badge : "讨论");
        episodeTitle.setText(name);
        episodeOriginal.setText(name.equals(original) ? "" : original);
        episodeOriginal.setVisibility(original.isEmpty() || name.equals(original) ? GONE : VISIBLE);
    }

    private void status(String message) {
        summary.setText(message);
        summary.setVisibility(message.isEmpty() ? GONE : VISIBLE);
    }

    private void showFailure(Throwable failure) {
        if (failure instanceof AnimeComments.DiscussionFailure discussion) {
            discussionEpisode = discussion.episodeId;
        }
        if (!sorted.isEmpty()) {
            status("刷新失败，已保留现有讨论");
            return;
        }
        Throwable cause = failure;
        if (cause instanceof AnimeComments.DiscussionFailure discussion && discussion.getCause() != null) {
            cause = discussion.getCause();
        }
        if (cause instanceof SocketTimeoutException) {
            status("Bangumi 请求超时，可以重试");
        } else if (cause instanceof UnknownHostException) {
            status("无法解析 Bangumi 域名，请检查网络");
        } else if (cause instanceof ReaderSources.HttpFailure http) {
            status(http.status == 429 ? "Bangumi 请求过于频繁，请稍后重试"
                    : "Bangumi 暂时无法访问（HTTP " + http.status + "）");
        } else {
            status("Bangumi 评论加载失败，可以重试");
        }
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }

    @Override protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
        super.onDetachedFromWindow();
    }

    private void appendVisiblePage() {
        if (active && !closed && more.isShown() && more.getGlobalVisibleRect(visibleMore)) appendPage();
    }

    private void appendPage() {
        int end = Math.min(sorted.size(), shown + PAGE_SIZE);
        for (; shown < end; shown++) {
            LayoutParams card = matchWrap();
            card.bottomMargin = dp(10);
            rows.addView(thread(sorted.get(shown)), card);
        }
        more.setVisibility(shown < sorted.size() ? VISIBLE : GONE);
        footer.setText("已显示全部 " + sorted.size() + " 条讨论 · 来自 Bangumi");
        footer.setVisibility(shown > 0 && shown == sorted.size() ? VISIBLE : GONE);
    }

    private View thread(AnimeComments.Thread thread) {
        LinearLayout group = column();
        group.setPadding(dp(13), dp(13), dp(13), dp(13));
        group.setBackground(PlayerViews.surface(getContext(), PlayerViews.SURFACE, 18, 0));
        group.addView(comment(thread.comment(), false), matchWrap());
        List<AnimeComments.Comment> replies = thread.replies();
        if (!replies.isEmpty()) {
            TextView replyCount = label(replies.size() + " 条回复", 12, PlayerViews.textTone(PlayerViews.PINK), true);
            replyCount.setPadding(0, dp(12), 0, dp(6));
            group.addView(replyCount, matchWrap());
            LinearLayout preview = column();
            for (int i = 0; i < Math.min(REPLY_PREVIEW, replies.size()); i++) addReply(preview, replies.get(i));
            group.addView(preview, matchWrap());
        }
        if (replies.size() > REPLY_PREVIEW) {
            long id = thread.comment().id();
            LinearLayout additional = column();
            boolean expanded = expandedReplies.contains(id);
            additional.setVisibility(expanded ? VISIBLE : GONE);
            if (expanded) appendReplies(additional, replies, REPLY_PREVIEW);
            group.addView(additional, matchWrap());
            String expandLabel = "展开其余 " + (replies.size() - REPLY_PREVIEW) + " 条回复";
            TextView expand = button(expandLabel, null);
            expand.setBackground(PlayerViews.button(getContext(), PlayerViews.BG, 11, 0));
            expand.setText(expanded ? "收起回复" : expandLabel);
            expand.setOnClickListener(view -> {
                if (additional.getVisibility() == VISIBLE) {
                    if (id > 0) expandedReplies.remove(id);
                    additional.setVisibility(GONE);
                    expand.setText(expandLabel);
                } else {
                    if (id > 0) expandedReplies.add(id);
                    if (additional.getChildCount() == 0) appendReplies(additional, replies, REPLY_PREVIEW);
                    additional.setVisibility(VISIBLE);
                    expand.setText("收起回复");
                }
            });
            LayoutParams position = matchWrap();
            position.topMargin = dp(5);
            group.addView(expand, position);
        }
        return group;
    }

    private void appendReplies(LinearLayout container, List<AnimeComments.Comment> replies, int start) {
        int end = Math.min(replies.size(), start + PAGE_SIZE);
        for (int i = start; i < end; i++) addReply(container, replies.get(i));
        if (end < replies.size()) {
            TextView next = button("更多回复", () -> {
                container.removeViewAt(container.getChildCount() - 1);
                appendReplies(container, replies, end);
            });
            container.addView(next, matchWrap());
        }
    }

    private void addReply(LinearLayout container, AnimeComments.Comment reply) {
        View card = comment(reply, true);
        card.setPadding(dp(10), dp(9), dp(10), dp(9));
        card.setBackground(PlayerViews.surface(getContext(), PlayerViews.tone(0xffededf0, 0xff333438), 11, 0));
        LayoutParams position = matchWrap();
        position.bottomMargin = dp(3);
        container.addView(card, position);
    }

    private View comment(AnimeComments.Comment comment, boolean reply) {
        LinearLayout item = column();
        LinearLayout byline = row();
        int avatarSize = reply ? 32 : 44;
        LayoutParams picture = new LayoutParams(dp(avatarSize), dp(avatarSize));
        picture.setMarginEnd(dp(10));
        byline.addView(avatar(comment, avatarSize), picture);
        LinearLayout user = column();
        TextView author = label(comment.author(), 14, PlayerViews.FG, true);
        author.setMaxLines(2);
        author.setEllipsize(TextUtils.TruncateAt.END);
        user.addView(author, matchWrap());
        if (comment.createdAt() > 0 && comment.createdAt() < 100_000_000_000L) {
            TextView date = label(dateFormat.format(new Date(comment.createdAt() * 1000)), 11, PlayerViews.MUTED, false);
            user.addView(date, matchWrap());
        }
        byline.addView(user, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        item.addView(byline, matchWrap());
        boolean empty = comment.text().isEmpty() && comment.images().isEmpty();
        if (empty || !comment.html().isBlank() || comment.images().isEmpty()) {
            TextView body = label("", 14, empty ? PlayerViews.MUTED : PlayerViews.FG, false);
            body.setLineSpacing(dp(4), 1f);
            body.setTextIsSelectable(true);
            body.setPadding(0, dp(reply ? 7 : 11), 0, dp(2));
            if (comment.html().isBlank()) body.setText(empty ? "该评论已被删除" : comment.text());
            else {
                Spanned formatted = richBody(comment.html());
                body.setText(formatted);
                if (formatted.getSpans(0, formatted.length(), ClickableSpan.class).length > 0) {
                    body.setMovementMethod(LinkMovementMethod.getInstance());
                }
            }
            item.addView(body, matchWrap());
        }
        for (String url : comment.images()) {
            ImageView image = new ImageView(getContext());
            image.setAdjustViewBounds(true);
            image.setMaxHeight(dp(320));
            image.setScaleType(ImageView.ScaleType.FIT_START);
            image.setContentDescription("评论图片");
            LayoutParams display = matchWrap();
            display.topMargin = dp(5);
            item.addView(image, display);
            avatars.bindMedia(image, url);
        }
        return item;
    }

    private static Spanned richBody(String html) {
        ArrayDeque<Integer> starts = new ArrayDeque<>();
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, null, (opening, tag, output, xml) -> {
            if (!tag.equalsIgnoreCase("anon-spoiler")) return;
            if (opening) {
                starts.push(output.length());
            } else if (!starts.isEmpty()) {
                int start = starts.pop();
                if (start < output.length()) output.setSpan(new Spoiler(), start, output.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        });
    }

    private static final class Spoiler extends ClickableSpan {
        private boolean reveal;

        @Override public void onClick(View view) {
            reveal = !reveal;
            view.invalidate();
        }

        @Override public void updateDrawState(TextPaint paint) {
            int tint = PlayerViews.tone(0xff55565d, 0xff999ba3);
            paint.bgColor = reveal ? 0 : tint;
            paint.setColor(reveal ? PlayerViews.FG : tint);
            paint.setUnderlineText(false);
        }
    }

    private View avatar(AnimeComments.Comment comment, int size) {
        FrameLayout round = new FrameLayout(getContext());
        round.setBackground(PlayerViews.surface(getContext(), PlayerViews.BG, size / 2, 0));
        round.setClipToOutline(true);
        round.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        String name = comment.author();
        String initial = name.substring(0, Character.charCount(name.codePointAt(0)));
        TextView placeholder = label(initial, size <= 32 ? 11 : 13, PlayerViews.MUTED, true);
        placeholder.setGravity(Gravity.CENTER);
        round.addView(placeholder, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        ImageView photo = new ImageView(getContext());
        photo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        round.addView(photo, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        avatars.bind(photo, comment.avatar());
        return round;
    }

    private void cancel() {
        generation++;
        if (pending != null) pending.cancel();
        pending = null;
        progress.setVisibility(GONE);
        reload.setEnabled(true);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancel();
        cancelEpisodePicker();
        worker.close();
        catalogueWorker.close();
        avatars.close();
        main.removeCallbacksAndMessages(null);
    }

    private TextView button(String title, Runnable action) {
        TextView view = label(title, 13, PlayerViews.textTone(PlayerViews.PINK), false);
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(11), 0, dp(11), 0);
        view.setBackground(PlayerViews.button(getContext(), 0, 7, 0));
        view.setFocusable(true);
        if (action != null) view.setOnClickListener(button -> action.run());
        return view;
    }

    private TextView label(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(getContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(getContext());
        column.setOrientation(VERTICAL);
        return column;
    }

    private static <K, V> void remember(Map<K, V> data, K key, V value, int limit) {
        data.remove(key);
        data.put(key, value);
        if (data.size() > limit) data.remove(data.keySet().iterator().next());
    }

    private static LayoutParams matchWrap() { return new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT); }
    private static LayoutParams wrap() { return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT); }
    private int dp(int value) { return PlayerViews.dp(getContext(), value); }
}
