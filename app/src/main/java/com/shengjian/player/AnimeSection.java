package com.shengjian.player;

import static com.shengjian.player.PlayerViews.BG;
import static com.shengjian.player.PlayerViews.FG;
import static com.shengjian.player.PlayerViews.LINE;
import static com.shengjian.player.PlayerViews.MUTED;
import static com.shengjian.player.PlayerViews.PINK;
import static com.shengjian.player.PlayerViews.SURFACE;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class AnimeSection {
    private static final int FILE_REQUEST = 131;
    private static final int EXPORT_RULES = 133;
    private static final int M = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int W = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long POPULAR_CACHE_AGE_MILLIS = 30 * 60 * 1_000L;
    private static final String BUNDLED_POPULAR = "anime-popular-2026-11-21.json";
    private static final String BUNDLED_POPULAR_LABEL = "Bangumi 近期注目 · 2026-11-21 收录";

    private enum Screen { HOME, SEARCH, SOURCES, POPULAR, SUBJECT, RANKING, TRACKING, HISTORY, EPISODES }
    private enum Catalog { LIVE, CACHE, BUNDLED }

    private static final class SearchState {
        final String keyword;
        final AnimeSchedule.Entry calendarEntry;
        final Map<String, AnimeEngine.Show> matches = new LinkedHashMap<>();
        final Map<AnimeSource, String> failures = new LinkedHashMap<>();
        final LinkedHashSet<AnimeSource> pending = new LinkedHashSet<>();
        int total;
        int complete;
        boolean stopped;

        SearchState(String keyword, AnimeSchedule.Entry calendarEntry) {
            this.keyword = keyword;
            this.calendarEntry = calendarEntry;
        }
    }

    private final MainActivity host;
    private boolean legacyMode;
    private TextView recentWatching;
    private TextView ruleShortcut;
    private EditText dashboardSearch;
    private ScrollView dashboardScroll;
    private int restoreScrollY;
    private String dashboardQuery = "";
    private final FrameLayout root;
    private final AnimeSource.Store sources;
    private final AnimeLibrary library;
    private final AtomicFile popularCache;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReaderSearchQueue queue = new ReaderSearchQueue(10);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataWorker = Executors.newSingleThreadExecutor();
    private final AnimeCoverCache artwork;
    private final List<AlertDialog> dialogs = new ArrayList<>();

    private Screen screen = Screen.HOME;
    private Screen searchBack = Screen.HOME;
    private Screen sourceBack = Screen.HOME;
    private Screen detailBack = Screen.HOME;
    private Screen subjectBack = Screen.HOME;
    private AnimeLibrary.WatchStatus trackingFilter;
    private String keyword = "";
    private LinearLayout body;
    private TextView searchStatus;
    private ProgressBar searchProgress;
    private TextView searchControl;
    private TextView searchDetails;
    private AnimeList searchList;
    private TextView searchEmpty;
    private SearchState search;
    private ReaderSearchQueue.Run running;
    private boolean searchUpdatePending;
    private int searchEpoch;
    private int detailEpoch;
    private Future<?> detailTask;
    private boolean importing;
    private boolean aggregating;
    private String aggregationStatus = "";
    private AnimeSource.AggregationResult aggregationResult;
    private TextView aggregationLabel;
    private List<AnimeDiscovery.Entry> ranking;
    private boolean rankingLoading;
    private String rankingError = "";
    private final List<AnimeDiscovery.Entry> popular = new ArrayList<>();
    private AnimeDiscovery.Entry selectedSubject;
    private LinearLayout homePopular;
    private PopularGrid popularGrid;
    private GridView popularView;
    private TextView popularStatus;
    private TextView popularAction;
    private int popularOffset;
    private int popularPosition;
    private boolean popularCacheChecked;
    private boolean popularLoading;
    private boolean popularHasMore;
    private Catalog popularCatalog = Catalog.LIVE;
    private boolean popularRetryMore;
    private String popularError = "";
    private boolean destroyed;
    private AnimeEngine.Show show;
    private List<AnimeEngine.Road> roads = List.of();
    private int roadIndex;
    private String detailError = "";

    AnimeSection(MainActivity host) {
        this.host = host;
        sources = new AnimeSource.Store(new File(host.getFilesDir(), "anime-sources.json"));
        library = new AnimeLibrary(host);
        popularCache = new AtomicFile(new File(host.getCacheDir(), "bangumi-popular-v1.json"));
        artwork = new AnimeCoverCache(host, main);
        root = new FrameLayout(host);
        showHome();
    }

    View view() { return root; }

    void saveState(Bundle state) {
        state.putBoolean("anime_archive", legacyMode);
        if (legacyMode) return;
        state.putString("anime_query", dashboardSearch == null ? dashboardQuery : dashboardSearch.getText().toString());
        state.putInt("anime_scroll", dashboardScroll == null || restoreScrollY > 0 ? restoreScrollY : dashboardScroll.getScrollY());
        state.putBoolean("anime_ranking", screen == Screen.RANKING);
    }

    void restoreState(Bundle state) {
        if (state.getBoolean("anime_archive")) { openLegacy(); return; }
        dashboardQuery = state.getString("anime_query", "");
        restoreScrollY = state.getInt("anime_scroll", 0);
        if (dashboardSearch != null) dashboardSearch.setText(dashboardQuery);
        restoreDashboardScroll();
        if (state.getBoolean("anime_ranking")) openRanking();
    }

    static boolean isRequest(int request) { return request == FILE_REQUEST || request == EXPORT_RULES; }

    void onSelected() {
        if (screen == Screen.HOME) {
            if (!popularCacheChecked) fetchPopular(false);
            if (!legacyMode) refreshDashboard();
        }
        else if (screen == Screen.HISTORY || screen == Screen.TRACKING) showCollection(screen);
        else if (screen == Screen.EPISODES) renderEpisodes();
    }

    boolean onBackPressed() {
        switch (screen) {
            case HOME:
                if (!legacyMode) return false;
                leaveLegacy();
                break;
            case SEARCH: backTo(searchBack); break;
            case SOURCES: backTo(sourceBack); break;
            case SUBJECT: backTo(subjectBack); break;
            case EPISODES: backTo(detailBack); break;
            default: showHome(); break;
        }
        return true;
    }

    void destroy() {
        destroyed = true;
        PageMotion.reset(root);
        cancelSearch();
        queue.close();
        io.shutdownNow();
        metadataWorker.shutdownNow();
        artwork.close();
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        main.removeCallbacksAndMessages(null);
    }

    void onActivityResult(int request, int result, Intent data) {
        if (!isRequest(request) || result != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri document = data.getData();
        if (!"content".equals(document.getScheme())) { toast("请使用系统文件选择器"); return; }
        if (request == EXPORT_RULES) {
            exportRules(document);
            return;
        }
        importRules(() -> {
            try (InputStream input = host.getContentResolver().openInputStream(document)) {
                if (input == null) throw new IOException("无法读取动画源文件");
                return sources.importStream(input);
            }
        });
    }

    void importLink(String raw) {
        if (raw == null || raw.trim().isEmpty()) { askImport(true); return; }
        importRules(() -> sources.importUrl(raw.trim()));
    }

    void searchTitle(String title) {
        searchTitle(title, null);
    }

    private void searchTitle(String title, AnimeSchedule.Entry calendarEntry) {
        String value = title == null ? "" : title.trim();
        if (value.isEmpty()) { toast("请输入动画名称"); return; }
        if (value.length() > 160) { toast("动画名称最多 160 个字符"); return; }
        keyword = value;
        searchBack = switch (screen) {
            case RANKING, SUBJECT -> screen;
            default -> Screen.HOME;
        };
        cancelSearch();
        search = new SearchState(value, calendarEntry);
        ArrayList<AnimeSource> enabled = new ArrayList<>();
        for (AnimeSource source : sources.list()) if (source.enabled) enabled.add(source);
        showSearch();
        runSearch(enabled);
    }

    private void showHome() {
        reset(Screen.HOME);
        if (!legacyMode) {
            showAnimeDashboard();
            return;
        }
        List<AnimeSource> installed = sources.list();
        int enabled = (int) installed.stream().filter(source -> source.enabled).count();
        LinearLayout heading = row();
        TextView name = label("旧版动画资料", 24, FG, true);
        markHeading(name);
        heading.addView(name, new LinearLayout.LayoutParams(0, W, 1));
        TextView manage = action("动画源", false, this::openSources);
        manage.setTextColor(PlayerViews.textTone(PINK));
        manage.setContentDescription(host.getString(R.string.reader_source_count, enabled, "动画源") + "，管理动画源");
        heading.addView(manage, params(W, dp(48)));
        add(heading, 3);
        add(searchBox(), 4);

        LinearLayout actions = row();
        TextView rank = action("排行", false, this::openRanking);
        rank.setContentDescription("动画排行");
        addEqual(actions, rank);
        TextView calendar = action("日历", false,
                () -> AnimeCalendar.show(host, entry -> searchTitle(entry.title, entry)));
        calendar.setContentDescription("放送日历");
        addEqual(actions, calendar);
        addEqual(actions, action("追番", false, () -> showCollection(Screen.TRACKING)));
        TextView history = action("记录", false, () -> showCollection(Screen.HISTORY));
        history.setContentDescription("观看记录");
        addEqual(actions, history);
        addEqual(actions, action("下载", false, this::showDownloads));
        add(actions, 4);
        addRule();
        ScrollView scroll = new ScrollView(host);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout shelves = column();
        scroll.addView(shelves, new ScrollView.LayoutParams(M, W));
        body.addView(scroll, new LinearLayout.LayoutParams(M, 0, 1));
        homePopular = column();
        shelves.addView(homePopular, params(M, W));
        updateHomePopular();

        List<AnimeLibrary.Item> watched = library.list(AnimeLibrary.HISTORY);
        if (enabled == 0) {
            View sourceState = installed.isEmpty() ? emptyState(R.drawable.mygo_create, "还没有动画源", "",
                    "一键聚合", this::aggregateSources) : emptyState("动画源已全部停用", "",
                    "管理动画源", this::openSources);
            shelves.addView(sourceState, withTopGap(14));
            shelves.addView(action("试播", false, this::launchDemo), params(W, dp(44)));
        }
        if (!watched.isEmpty()) {
            TextView section = label("继续观看", 17, FG, true);
            markHeading(section);
            shelves.addView(section, withTopGap(16));
            for (int i = 0; i < Math.min(watched.size(), 4); i++) {
                RowView cell = new RowView();
                cell.bind(savedRow(watched.get(i), Screen.HISTORY));
                shelves.addView(cell, params(M, W));
            }
        }
    }

    boolean isLegacy() { return legacyMode; }

    void openLegacy() {
        rememberDashboard();
        legacyMode = true;
        showHome();
    }

    void leaveLegacy() {
        if (!legacyMode) return;
        cancelSearch();
        stopDetails();
        legacyMode = false;
        keyword = "";
        showHome();
    }

    private void showAnimeDashboard() {
        add(SectionControls.heading(host, "动画",
                SectionControls.action(host, "以图搜番", false, () -> host.openAnimeFeature("imageSearch", "")),
                SectionControls.action(host, "动画源", true, () -> host.openAnimeFeature("rules", ""))), 0);
        add(searchBox(), 6);

        LinearLayout shortcuts = row();
        addEqual(shortcuts, action("排行", false, this::openRanking));
        addEqual(shortcuts, featureAction("日历", "timeline"));
        addEqual(shortcuts, featureAction("追番", "collect"));
        TextView history = featureAction("历史", "history");
        history.setContentDescription("动画历史记录");
        addEqual(shortcuts, history);
        addEqual(shortcuts, featureAction("下载", "downloads"));
        add(shortcuts, 4);
        addRule();

        ScrollView scroll = new ScrollView(host);
        dashboardScroll = scroll;
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout shelves = column();
        scroll.addView(shelves, new ScrollView.LayoutParams(M, W));
        body.addView(scroll, new LinearLayout.LayoutParams(M, 0, 1));
        recentWatching = featureAction("观看记录", "history");
        recentWatching.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        shelves.addView(recentWatching, params(M, dp(44)));
        ruleShortcut = featureAction("从规则仓库添加动画源　›", "catalog");
        ruleShortcut.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        ruleShortcut.setTextColor(PlayerViews.textTone(PINK));
        shelves.addView(ruleShortcut, params(M, dp(44)));
        refreshDashboard();
        homePopular = column();
        shelves.addView(homePopular, params(M, W));
        updateHomePopular();
        LinearLayout tools = row();
        addEqual(tools, featureAction("规则仓库", "catalog"));
        addEqual(tools, featureAction("同步", "sync"));
        addEqual(tools, featureAction("动画设置", "settings"));
        shelves.addView(tools, withTopGap(12));
        restoreDashboardScroll();
    }

    private void rememberDashboard() {
        if (dashboardSearch != null) dashboardQuery = dashboardSearch.getText().toString();
        if (dashboardScroll != null && restoreScrollY == 0) restoreScrollY = dashboardScroll.getScrollY();
    }

    private void restoreDashboardScroll() {
        ScrollView view = dashboardScroll;
        if (view == null || restoreScrollY == 0 || popular.isEmpty()) return;
        view.post(() -> {
            if (view != dashboardScroll || restoreScrollY == 0) return;
            view.scrollTo(0, restoreScrollY);
            restoreScrollY = 0;
        });
    }

    private TextView featureAction(String text, String feature) {
        return action(text, false, () -> host.openAnimeFeature(feature, ""));
    }

    private void refreshDashboard() {
        if (recentWatching == null) return;
        android.content.SharedPreferences summary = host.getSharedPreferences("anime_feature_summary", Activity.MODE_PRIVATE);
        ruleShortcut.setVisibility(summary.getInt("ruleCount", 0) == 0 ? View.VISIBLE : View.GONE);
        String title = summary.getString("latestTitle", "");
        recentWatching.setVisibility(title.isEmpty() ? View.GONE : View.VISIBLE);
        if (!title.isEmpty()) {
            String episode = summary.getString("latestEpisode", "");
            recentWatching.setText("上次看到　" + title + (episode.isEmpty() ? "" : " · " + episode));
            recentWatching.setSingleLine();
            recentWatching.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
    }

    private void updateHomePopular() {
        if (homePopular == null) return;
        homePopular.removeAllViews();
        LinearLayout heading = row();
        TextView caption = label("热门番剧", 18, FG, true);
        markHeading(caption);
        heading.addView(caption, new LinearLayout.LayoutParams(0, W, 1));
        if (!legacyMode) heading.addView(featureAction("查看全部", "popular"), params(W, dp(44)));
        else if (!popular.isEmpty()) heading.addView(action("查看全部", false, this::showPopular), params(W, dp(44)));
        homePopular.addView(heading, withTopGap(8));
        if (popular.isEmpty()) {
            if (popularError.isEmpty()) {
                TextView status = label("正在加载热门番剧…", 13, MUTED, false);
                status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                homePopular.addView(status, withTopGap(9));
            } else {
                homePopular.addView(emptyState("暂时无法获取热门番剧", popularError,
                        "重试", () -> fetchPopular(false)), withTopGap(6));
            }
            return;
        }
        if (popularCatalog == Catalog.BUNDLED) {
            homePopular.addView(label(BUNDLED_POPULAR_LABEL, 12, MUTED, false), withTopGap(3));
        } else if (popularCatalog == Catalog.CACHE && !popularError.isEmpty()) {
            homePopular.addView(label("已显示上次加载的热门番剧", 12, MUTED, false), withTopGap(3));
        }
        int columns = popularColumns();
        int preview = Math.min(popular.size(), columns * 2);
        for (int start = 0; start < preview; start += columns) {
            LinearLayout shelf = row();
            shelf.setGravity(Gravity.TOP);
            for (int cell = 0; cell < columns; cell++) {
                int position = start + cell;
                View card;
                if (position < preview) {
                    PopularCard item = new PopularCard();
                    item.bind(popular.get(position));
                    card = item;
                } else card = new View(host);
                addEqual(shelf, card);
            }
            homePopular.addView(shelf, withTopGap(8));
        }
        restoreDashboardScroll();
    }

    private View searchBox() {
        EditText field = input("搜索动画名称", false);
        if (!legacyMode && screen == Screen.HOME) {
            dashboardSearch = field;
            field.setText(dashboardQuery);
        } else field.setText(keyword);
        field.setSelectAllOnFocus(true);
        return SectionControls.search(field, () -> {
            host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(field.getWindowToken(), 0);
            String query = field.getText().toString().trim();
            if (legacyMode) searchTitle(query);
            else host.openAnimeFeature("search", query);
        });
    }

    private void showSearch() {
        if (search == null) { showHome(); return; }
        reset(Screen.SEARCH);
        LinearLayout searchHeader = header("动画搜索", () -> backTo(searchBack));
        searchHeader.addView(action("动画源", false, this::openSources), params(W, dp(44)));
        add(searchHeader, 0);
        add(searchBox(), 4);
        LinearLayout progressPanel = column();
        searchStatus = label("", 13, MUTED, false);
        searchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        progressPanel.addView(searchStatus, params(M, W));
        searchProgress = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        searchProgress.setProgressTintList(ColorStateList.valueOf(PlayerViews.textTone(PINK)));
        searchProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        searchProgress.setContentDescription("动画源查询进度");
        LinearLayout.LayoutParams progressSpace = params(M, dp(3));
        progressSpace.topMargin = dp(5);
        progressPanel.addView(searchProgress, progressSpace);
        LinearLayout controls = row();
        searchControl = action("停止", false, this::searchControl);
        searchDetails = action("来源详情", false, this::showSearchFailures);
        controls.addView(searchControl, params(W, dp(44)));
        controls.addView(searchDetails, params(W, dp(44)));
        progressPanel.addView(controls, params(M, W));
        add(progressPanel, 10);
        addRule();
        searchEmpty = label("", 14, MUTED, false);
        add(searchEmpty, 10);
        ListView list = listView();
        searchList = new AnimeList();
        list.setAdapter(searchList);
        body.addView(list, new LinearLayout.LayoutParams(M, 0, 1));
        updateSearch();
    }

    private void runSearch(List<AnimeSource> attempts) {
        if (search == null || attempts.isEmpty()) { updateSearch(); return; }
        SearchState state = search;
        state.stopped = false;
        state.total = attempts.size();
        state.complete = 0;
        state.pending.clear();
        state.pending.addAll(attempts);
        LinkedHashSet<String> retrying = new LinkedHashSet<>();
        for (AnimeSource source : attempts) retrying.add(source.id);
        state.failures.keySet().removeIf(source -> retrying.contains(source.id));
        final int generation = ++searchEpoch;
        running = queue.start(attempts, AnimeEngine::route, source -> AnimeEngine.search(source, state.keyword), source -> {},
                (source, result, error) -> main.post(() -> {
                    if (destroyed || generation != searchEpoch || state != search) return;
                    state.pending.remove(source);
                    state.complete++;
                    if (error != null) state.failures.put(source, problem(error));
                    else if (result != null) for (AnimeEngine.Show item : result) {
                        state.matches.putIfAbsent(item.source.id + '\u001f' + AnimeLibrary.workAddress(item.url, item.sourceValue), item);
                    }
                    if (state.pending.isEmpty()) running = null;
                    scheduleSearchUpdate();
                }), ReaderSearchQueue.DEFAULT_TIMEOUT_MILLIS);
        updateSearch();
    }

    private final Runnable publishSearch = () -> {
        searchUpdatePending = false;
        updateSearch();
    };

    private void scheduleSearchUpdate() {
        if (searchUpdatePending) return;
        searchUpdatePending = true;
        main.postDelayed(publishSearch, 90);
    }

    private void searchControl() {
        if (search == null) return;
        if (running != null) {
            cancelSearch();
            search.stopped = true;
            updateSearch();
        } else if (!search.pending.isEmpty() || !search.failures.isEmpty()) {
            LinkedHashMap<String, AnimeSource> retry = new LinkedHashMap<>();
            for (AnimeSource source : search.pending) retry.put(source.id, source);
            for (AnimeSource source : search.failures.keySet()) retry.put(source.id, source);
            List<AnimeSource> enabled = new ArrayList<>();
            for (AnimeSource old : retry.values()) {
                AnimeSource current = sources.find(old.id);
                if (current != null && current.enabled) enabled.add(current);
            }
            if (enabled.isEmpty()) { toast("没有仍启用的可重试来源"); return; }
            runSearch(enabled);
        } else openSources();
    }

    private void cancelSearch() {
        searchEpoch++;
        if (running == null) return;
        running.cancel();
        running = null;
    }

    private void updateSearch() {
        if (destroyed || screen != Screen.SEARCH || search == null || searchList == null) return;
        String progress = search.total == 0 ? "没有启用的动画源"
                : (running != null ? "查询中" : search.stopped ? "已停止" : "查询完成") + " · "
                + search.complete + " / " + search.total + " 个来源 · " + search.matches.size() + " 条结果";
        if (!search.failures.isEmpty()) progress += " · " + search.failures.size() + " 个失败";
        searchStatus.setText(progress);
        searchProgress.setMax(Math.max(1, search.total));
        searchProgress.setProgress(search.complete);
        searchProgress.setVisibility(search.total == 0 ? View.GONE : View.VISIBLE);
        searchControl.setText(running != null ? "停止" : !search.pending.isEmpty() ? "继续搜索"
                : !search.failures.isEmpty() ? "重试失败" : "管理动画源");
        searchDetails.setVisibility(search.failures.isEmpty() ? View.GONE : View.VISIBLE);
        searchEmpty.setVisibility(search.matches.isEmpty() ? View.VISIBLE : View.GONE);
        searchEmpty.setText(search.total == 0 ? "请先启用动画源" : running != null
                ? "等待结果…" : search.stopped ? "未找到结果" : "没有找到匹配的动画");
        String query = comparable(search.keyword);
        List<AnimeEngine.Show> matches = new ArrayList<>(search.matches.values());
        matches.sort(Comparator.comparingInt((AnimeEngine.Show item) -> matchScore(item.title, query)).reversed()
                .thenComparing(item -> item.title).thenComparing(item -> item.source.name));
        List<Row> cells = new ArrayList<>();
        for (AnimeEngine.Show item : matches) {
            AnimeEngine.Show selected = withCalendarEntry(item, search.calendarEntry);
            cells.add(new Row(item.title, item.source.name, item.cover, () -> openEpisodes(selected, Screen.SEARCH), null));
        }
        searchList.submit(cells);
    }

    private static AnimeEngine.Show withCalendarEntry(AnimeEngine.Show item, AnimeSchedule.Entry entry) {
        if (entry == null || entry.id <= 0 || item.bangumiId > 0) return item;
        if (!AnimeComments.sameTitle(item.title, entry.title) && !AnimeComments.sameTitle(item.title, entry.originalTitle)) {
            return item;
        }
        return new AnimeEngine.Show(item.source, item.title, item.url, item.cover, item.sourceValue, entry.id);
    }

    private void showSearchFailures() {
        if (search == null) return;
        ArrayList<String> details = new ArrayList<>();
        for (Map.Entry<AnimeSource, String> failure : search.failures.entrySet()) {
            details.add(failure.getKey().name + " · " + failure.getValue());
        }
        dialog(new AlertDialog.Builder(host).setTitle("失败来源")
                .setItems(details.toArray(new String[0]), null).setNegativeButton("关闭", null));
    }

    private static int matchScore(String title, String query) {
        String candidate = comparable(title);
        if (candidate.equals(query)) return 3;
        if (candidate.startsWith(query)) return 2;
        return candidate.contains(query) ? 1 : 0;
    }

    private static String comparable(String value) {
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private void fetchPopular(boolean more) {
        if (destroyed || popularLoading || (more && !popularHasMore)) return;
        if (!more) {
            artwork.allowRetry();
            if (popularGrid != null) popularGrid.notifyDataSetChanged();
        }
        boolean checkCache = !popularCacheChecked;
        int offset = more ? popularOffset : 0;
        popularCacheChecked = true;
        popularLoading = true;
        popularRetryMore = more;
        popularError = "";
        updatePopularUi();
        metadataWorker.execute(() -> {
            if (checkCache) {
                AnimeDiscovery.Page cached = readPopularCache();
                if (cached != null) {
                    long age = System.currentTimeMillis() - popularCache.getBaseFile().lastModified();
                    if (age >= 0 && age < POPULAR_CACHE_AGE_MILLIS) {
                        main.post(() -> finishPopular(cached, 0, Catalog.CACHE, ""));
                        return;
                    }
                    main.post(() -> {
                        if (destroyed) return;
                        acceptPopular(cached, 0, Catalog.CACHE);
                        updatePopularUi();
                    });
                } else {
                    AnimeDiscovery.Page bundled = readBundledPopular();
                    if (bundled != null) main.post(() -> {
                        if (destroyed) return;
                        acceptPopular(bundled, 0, Catalog.BUNDLED);
                        updatePopularUi();
                    });
                }
            }
            try {
                AnimeDiscovery.Snapshot result = AnimeDiscovery.fetchPopular(offset);
                if (offset == 0) writePopularCache(result.json());
                main.post(() -> finishPopular(result.page(), offset, Catalog.LIVE, ""));
            } catch (IOException | IllegalArgumentException failure) {
                main.post(() -> finishPopular(null, offset, Catalog.LIVE, problem(failure)));
            }
        });
    }

    private AnimeDiscovery.Page readBundledPopular() {
        try (InputStream input = host.getAssets().open(BUNDLED_POPULAR)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4_096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > AnimeDiscovery.MAX_JSON_LENGTH) return null;
                bytes.write(buffer, 0, count);
            }
            return AnimeDiscovery.parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return null;
        }
    }

    private AnimeDiscovery.Page readPopularCache() {
        if (popularCache.getBaseFile().length() > AnimeDiscovery.MAX_JSON_LENGTH * 4L) return null;
        try {
            AnimeDiscovery.Page cached = AnimeDiscovery.parse(new String(popularCache.readFully(), StandardCharsets.UTF_8));
            return cached.entries().isEmpty() ? null : cached;
        } catch (IOException ignored) {
            return null;
        }
    }

    private void writePopularCache(String json) {
        FileOutputStream output = null;
        try {
            output = popularCache.startWrite();
            output.write(json.getBytes(StandardCharsets.UTF_8));
            popularCache.finishWrite(output);
        } catch (IOException failure) {
            popularCache.failWrite(output);
        }
    }

    private void finishPopular(AnimeDiscovery.Page page, int offset, Catalog catalog, String error) {
        if (destroyed) return;
        if (page != null) acceptPopular(page, offset, catalog);
        popularLoading = false;
        popularError = error;
        updatePopularUi();
    }

    private void acceptPopular(AnimeDiscovery.Page page, int offset, Catalog catalog) {
        if (offset == 0) popular.clear();
        LinkedHashSet<Long> subjectIds = new LinkedHashSet<>();
        for (AnimeDiscovery.Entry entry : popular) subjectIds.add(entry.subject().id);
        int previousCount = popular.size();
        for (AnimeDiscovery.Entry entry : page.entries()) {
            if (subjectIds.add(entry.subject().id)) popular.add(entry);
        }
        popularOffset = AnimeDiscovery.nextOffset(offset);
        popularHasMore = page.hasMore() && popular.size() > previousCount;
        if (offset == 0) popularCatalog = catalog;
    }

    private void updatePopularUi() {
        if (screen == Screen.HOME) updateHomePopular();
        if (screen != Screen.POPULAR || popularGrid == null) return;
        popularGrid.submit(popular);
        boolean empty = popular.isEmpty();
        String message = popularLoading ? "正在加载热门番剧…" : popularError;
        if (empty && message.isEmpty()) message = "暂无热门番剧";
        if (popularCatalog == Catalog.BUNDLED) {
            message = BUNDLED_POPULAR_LABEL + (message.isEmpty() ? "" : "\n" + message);
        }
        popularStatus.setText(message);
        popularStatus.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        popularAction.setVisibility(popularLoading || (!popularHasMore && popularError.isEmpty())
                ? View.GONE : View.VISIBLE);
        popularAction.setText(popularError.isEmpty() ? "加载更多" : "重试");
    }

    private void showPopular() {
        reset(Screen.POPULAR);
        LinearLayout bar = header("热门番剧", this::showHome);
        TextView refresh = action("刷新", false, () -> fetchPopular(false));
        refresh.setContentDescription("刷新热门番剧");
        bar.addView(refresh, params(W, dp(44)));
        add(bar, 0);
        GridView grid = new GridView(host);
        popularView = grid;
        grid.setHorizontalSpacing(dp(8));
        grid.setVerticalSpacing(dp(10));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setNumColumns(popularColumns());
        grid.setPadding(0, dp(6), 0, dp(6));
        grid.setClipToPadding(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        popularGrid = new PopularGrid();
        grid.setAdapter(popularGrid);
        body.addView(grid, new LinearLayout.LayoutParams(M, 0, 1));
        popularStatus = label("", 13, MUTED, false);
        popularStatus.setGravity(Gravity.CENTER);
        popularStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(popularStatus, 3);
        popularAction = action("加载更多", false,
                () -> fetchPopular(popularError.isEmpty() ? popularHasMore : popularRetryMore));
        body.addView(popularAction, params(M, dp(44)));
        updatePopularUi();
        grid.setSelection(popularPosition);
    }

    private int popularColumns() {
        int width = host.getResources().getConfiguration().screenWidthDp;
        return width > 840 ? 6 : width > 600 ? 5 : 3;
    }

    private void openSubject(AnimeDiscovery.Entry entry) {
        if (!legacyMode) {
            AnimeSchedule.Entry subject = entry.subject();
            if (subject.id > 0) host.openAnimeFeature("subject", String.valueOf(subject.id), entry.previewJson());
            else host.openAnimeFeature("search", subject.title);
            return;
        }
        subjectBack = screen == Screen.POPULAR ? Screen.POPULAR : Screen.HOME;
        if (popularView != null) popularPosition = popularView.getFirstVisiblePosition();
        selectedSubject = entry;
        showSubject();
    }

    private void showSubject() {
        if (selectedSubject == null) { showHome(); return; }
        reset(Screen.SUBJECT);
        add(header("番剧详情", () -> backTo(subjectBack)), 0);
        AnimeDiscovery.Entry entry = selectedSubject;
        AnimeSchedule.Entry subject = entry.subject();
        LinearLayout hero = row();
        hero.setGravity(Gravity.TOP);
        ImageView cover = artwork(dp(96), dp(148));
        connectArtwork(cover, entry.cover());
        hero.addView(cover);
        LinearLayout titles = column();
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(label(subject.title, 20, FG, true));
        if (!subject.originalTitle.isEmpty()) titles.addView(label(subject.originalTitle, 13, MUTED, false), withTopGap(4));
        if (!subject.airing.isEmpty()) titles.addView(label(subject.airing, 13, MUTED, false), withTopGap(5));
        if (entry.score() > 0) titles.addView(label(String.format(Locale.ROOT, "Bangumi %.1f 分", entry.score()),
                13, MUTED, false), withTopGap(5));
        hero.addView(titles, new LinearLayout.LayoutParams(0, W, 1));
        add(hero, 10);
        body.addView(action("查找播放源", true, () -> searchTitle(subject.title, subject)), params(M, dp(46)));
        if (!subject.originalTitle.isEmpty() && !AnimeComments.sameTitle(subject.title, subject.originalTitle)) {
            body.addView(action("按原名查找", false, () -> searchTitle(subject.originalTitle, subject)), params(M, dp(44)));
        }
        if (!entry.summary().isEmpty()) {
            TextView summary = label(entry.summary(), 14, FG, false);
            summary.setLineSpacing(dp(4), 1f);
            ScrollView scroll = new ScrollView(host);
            scroll.addView(summary, new ScrollView.LayoutParams(M, W));
            LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(M, 0, 1);
            space.topMargin = dp(12);
            body.addView(scroll, space);
        }
    }

    private void openRanking() {
        if (!legacyMode) rememberDashboard();
        if (ranking == null && !rankingLoading && rankingError.isEmpty()) fetchRanking();
        showRanking();
    }

    private void fetchRanking() {
        rankingLoading = true;
        rankingError = "";
        metadataWorker.execute(() -> {
            try {
                List<AnimeDiscovery.Entry> entries = AnimeDiscovery.fetch();
                main.post(() -> finishRanking(entries, ""));
            } catch (IOException | IllegalArgumentException failure) {
                main.post(() -> finishRanking(null, problem(failure)));
            }
        });
    }

    private void finishRanking(List<AnimeDiscovery.Entry> entries, String error) {
        if (destroyed) return;
        ranking = entries;
        rankingError = error;
        rankingLoading = false;
        if (screen == Screen.RANKING) showRanking();
    }

    private void showRanking() {
        reset(Screen.RANKING);
        LinearLayout bar = header("动画排行", this::showHome);
        bar.addView(label("Bangumi", 12, MUTED, false), params(W, W));
        add(bar, 0);
        addRule();
        if (rankingLoading) {
            add(emptyState("正在读取排行…", "", null, null), 12);
            return;
        }
        if (ranking == null || ranking.isEmpty()) {
            add(emptyState("暂时无法获取排行", rankingError, "重试", () -> {
                fetchRanking();
                showRanking();
            }), 12);
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (AnimeDiscovery.Entry entry : ranking) {
            AnimeSchedule.Entry subject = entry.subject();
            String info = entry.rank() > 0 ? "第 " + entry.rank() + " 名" : "";
            if (entry.score() > 0) info += (info.isEmpty() ? "" : " · ") + String.format(Locale.ROOT, "%.1f 分", entry.score());
            rows.add(new Row(subject.title, info, entry.cover(), () -> {
                if (legacyMode) searchTitle(subject.title, subject);
                else openSubject(entry);
            }, null));
        }
        addList(rows);
    }

    private void showCollection(Screen destination) {
        reset(destination);
        boolean history = destination == Screen.HISTORY;
        LinearLayout bar = header(history ? "观看记录" : "追番", this::showHome);
        String group = history ? AnimeLibrary.HISTORY : AnimeLibrary.FAVORITES;
        List<AnimeLibrary.Item> saved = library.list(group);
        if (!history) {
            String filterLabel = trackingFilter == null ? "全部" : trackingFilter.label;
            TextView filter = action(filterLabel + " ▾", false, this::chooseTrackingFilter);
            filter.setContentDescription("筛选追番 · " + filterLabel);
            bar.addView(filter, params(W, dp(44)));
            if (trackingFilter != null) saved.removeIf(item -> item.trackingStatus() != trackingFilter);
        } else if (!saved.isEmpty()) {
            bar.addView(action("清空", false, () -> dialog(new AlertDialog.Builder(host).setTitle("清空观看记录？")
                    .setMessage("将从此列表移除观看记录。已保存的播放进度仍可用于续播。")
                    .setNegativeButton("取消", null).setPositiveButton("清空记录", (window, which) -> {
                        library.clearHistory();
                        showCollection(destination);
                    }))), params(dp(66), dp(44)));
        }
        add(bar, 0);
        if (saved.isEmpty()) {
            if (history) {
                add(emptyState("暂无观看记录", "", "返回动画", this::showHome), 8);
            } else if (trackingFilter == null) {
                add(emptyState(R.drawable.mygo_love, "暂无追番", "进入作品选集即可设置追番状态。",
                        "返回动画", this::showHome), 8);
            } else {
                add(emptyState("暂无「" + trackingFilter.label + "」的作品", "", "查看全部", () -> {
                    trackingFilter = null;
                    showCollection(Screen.TRACKING);
                }), 8);
            }
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (AnimeLibrary.Item item : saved) rows.add(savedRow(item, destination));
        addList(rows);
    }

    private void chooseTrackingFilter() {
        AnimeLibrary.WatchStatus[] statuses = AnimeLibrary.WatchStatus.values();
        String[] options = new String[statuses.length + 1];
        options[0] = "全部";
        for (int i = 0; i < statuses.length; i++) options[i + 1] = statuses[i].label;
        dialog(new AlertDialog.Builder(host).setTitle("筛选追番")
                .setSingleChoiceItems(options, trackingFilter == null ? 0 : trackingFilter.ordinal() + 1, (window, which) -> {
                    trackingFilter = which == 0 ? null : statuses[which - 1];
                    window.dismiss();
                    showCollection(Screen.TRACKING);
                })
                .setNegativeButton("关闭", null));
    }

    private void chooseTrackingStatus(AnimeLibrary.Item item, Runnable refresh) {
        AnimeLibrary.WatchStatus current = library.watchStatus(item);
        AnimeLibrary.WatchStatus[] statuses = AnimeLibrary.WatchStatus.values();
        String[] options = new String[statuses.length + (current == null ? 0 : 1)];
        for (int i = 0; i < statuses.length; i++) options[i] = statuses[i].label;
        if (current != null) options[statuses.length] = "移除追番";
        dialog(new AlertDialog.Builder(host).setTitle("追番状态")
                .setSingleChoiceItems(options, current == null ? -1 : current.ordinal(), (window, which) -> {
                    window.dismiss();
                    if (which < statuses.length && statuses[which] == current) return;
                    library.setWatchStatus(item, which == statuses.length ? null : statuses[which]);
                    refresh.run();
                })
                .setNegativeButton("取消", null));
    }

    private Row savedRow(AnimeLibrary.Item item, Screen origin) {
        String info = item.episode.isEmpty() ? item.sourceName : item.episode + " · " + item.sourceName;
        boolean tracking = origin == Screen.TRACKING;
        if (tracking) info = item.trackingStatus().label + " · " + info;
        Runnable manage = tracking ? () -> chooseTrackingStatus(item, () -> showCollection(origin))
                : () -> dialog(new AlertDialog.Builder(host).setTitle("移除「" + item.title + "」？")
                        .setNegativeButton("取消", null).setPositiveButton("移除记录", (window, which) -> {
                            library.remove(AnimeLibrary.HISTORY, item);
                            if (screen == Screen.HOME) showHome();
                            else showCollection(origin);
                        }));
        return new Row(item.title, info, item.cover, () -> {
            AnimeSource source = sources.find(item.sourceId);
            if (source == null) {
                dialog(new AlertDialog.Builder(host).setTitle("原动画源已移除")
                        .setMessage("重新导入原来源后可继续，也可按片名搜索其他来源。")
                        .setNegativeButton("取消", null).setPositiveButton("搜索片名", (window, which) -> searchTitle(item.title)));
                return;
            }
            openEpisodes(new AnimeEngine.Show(source, item.title, item.url, item.cover, item.sourceValue, item.bangumiId),
                    screen == Screen.HOME ? Screen.HOME : origin);
        }, manage);
    }

    private void openEpisodes(AnimeEngine.Show selected, Screen previous) {
        stopDetails();
        show = selected;
        detailBack = previous;
        detailError = "";
        roadIndex = -1;
        roads = List.of();
        renderEpisodes();
        int epoch = detailEpoch;
        detailTask = metadataWorker.submit(() -> {
            try {
                List<AnimeEngine.Road> available = AnimeEngine.episodes(selected);
                main.post(() -> {
                    if (destroyed || epoch != detailEpoch || screen != Screen.EPISODES) return;
                    roads = available;
                    roadIndex = preferredRoad(available);
                    renderEpisodes();
                });
            } catch (Exception | LinkageError failure) {
                main.post(() -> {
                    if (destroyed || epoch != detailEpoch || screen != Screen.EPISODES) return;
                    detailError = problem(failure);
                    renderEpisodes();
                });
            }
        });
    }

    private int preferredRoad(List<AnimeEngine.Road> available) {
        for (int index = 0; index < available.size(); index++) {
            if (AnimePlaybackHistory.mostRecentIndex(host, available.get(index).episodes) >= 0) return index;
        }
        for (int index = 0; index < available.size(); index++) {
            List<AnimeEngine.Episode> episodes = available.get(index).episodes;
            int episode = AnimePlaybackActivity.resumeIndex(host, episodes);
            if (episode >= 0 && episode < episodes.size()
                    && AnimePlaybackActivity.savedPosition(host, episodes.get(episode)) > 0) return index;
        }
        return 0;
    }

    private void renderEpisodes() {
        if (show == null) { showHome(); return; }
        reset(Screen.EPISODES);
        add(header("选集", () -> backTo(detailBack)), 0);
        LinearLayout hero = row();
        hero.setPadding(0, dp(6), 0, dp(6));
        ImageView cover = artwork(dp(60), dp(80));
        connectArtwork(cover, show.cover);
        hero.addView(cover);
        LinearLayout info = column();
        info.setPadding(dp(12), 0, 0, 0);
        TextView name = label(show.title, 19, FG, true);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        markHeading(name);
        info.addView(name, params(M, W));
        info.addView(label(show.source.name, 13, MUTED, false));
        hero.addView(info, new LinearLayout.LayoutParams(0, W, 1));
        add(hero, 6);
        LinearLayout actions = row();
        AnimeLibrary.Item tracked = new AnimeLibrary.Item(show, "");
        AnimeLibrary.WatchStatus status = library.watchStatus(tracked);
        addEqual(actions, action(status == null ? "追番" : "追番 · " + status.label, false,
                () -> chooseTrackingStatus(tracked, this::renderEpisodes)));
        if (roads.isEmpty()) {
            boolean loading = roadIndex < 0 && detailError.isEmpty();
            if (!loading) addEqual(actions, action("重试选集", false, () -> openEpisodes(show, detailBack)));
            add(actions, 8);
            add(emptyState(loading ? "正在读取分集…" : "选集读取失败", loading ? ""
                    : detailError.isEmpty() ? "此来源没有提供可选的分集。" : detailError, null, null), 8);
            return;
        }
        int selectedRoad = Math.min(Math.max(0, roadIndex), roads.size() - 1);
        AnimeEngine.Road road = roads.get(selectedRoad);
        List<AnimeEngine.Episode> episodes = road.episodes;
        int resume = AnimePlaybackActivity.resumeIndex(host, episodes);
        if (resume < 0 || resume >= episodes.size()) resume = 0;
        if (!episodes.isEmpty()) {
            int selected = resume;
            long position = AnimePlaybackActivity.savedPosition(host, episodes.get(selected));
            addEqual(actions, action(position > 0 ? "继续播放" : "开始播放", true, () -> launch(selectedRoad, selected)));
        }
        add(actions, 8);
        LinearLayout chooser = row();
        TextView roadName = label((road.name.isEmpty() ? "默认线路" : road.name) + " · " + episodes.size() + " 集", 15, FG, true);
        chooser.addView(roadName, new LinearLayout.LayoutParams(0, W, 1));
        if (roads.size() > 1) chooser.addView(action("切换线路", false, this::chooseRoad), params(dp(96), dp(44)));
        add(chooser, 8);
        addRule();
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < episodes.size(); i++) {
            AnimeEngine.Episode episode = episodes.get(i);
            int index = i;
            long position = AnimePlaybackActivity.savedPosition(host, episode);
            rows.add(new Row(episode.title, position > 0 ? "继续 · " + time(position) : "播放", "",
                    () -> launch(selectedRoad, index), null));
        }
        addList(rows);
    }

    private void chooseRoad() {
        List<AnimeEngine.Road> options = roads;
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) {
            AnimeEngine.Road road = options.get(i);
            labels[i] = (road.name.isEmpty() ? "线路 " + (i + 1) : road.name) + " · " + road.episodes.size() + " 集";
        }
        dialog(new AlertDialog.Builder(host).setTitle("切换播放线路").setSingleChoiceItems(labels, roadIndex, (window, which) -> {
            if (screen != Screen.EPISODES || roads != options) return;
            roadIndex = which;
            window.dismiss();
            renderEpisodes();
        }).setNegativeButton("关闭", null));
    }

    private static String time(long millis) {
        long seconds = millis / 1000;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private void launch(int selectedRoad, int selected) {
        if (selectedRoad < 0 || selectedRoad >= roads.size()) return;
        List<AnimeEngine.Episode> episodes = roads.get(selectedRoad).episodes;
        if (episodes.isEmpty() || selected < 0 || selected >= episodes.size()) return;
        host.pauseMusicForAnime();
        try {
            AnimePlaybackActivity.launch(host, roads, selectedRoad, selected);
            library.record(episodes.get(selected));
        } catch (ActivityNotFoundException missing) { toast("无法启动动画播放器"); }
    }

    private void launchDemo() {
        host.pauseMusicForAnime();
        try { AnimePlaybackActivity.launchDemo(host); }
        catch (ActivityNotFoundException missing) { toast("无法启动动画播放器"); }
    }

    private void showDownloads() {
        try { host.startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
        catch (ActivityNotFoundException missing) { toast("请在系统文件管理器中查看下载"); }
    }

    private void stopDetails() {
        detailEpoch++;
        if (detailTask != null) {
            detailTask.cancel(true);
            detailTask = null;
        }
    }

    private void backTo(Screen destination) {
        if (screen == Screen.EPISODES) stopDetails();
        switch (destination) {
            case SEARCH: showSearch(); break;
            case POPULAR: showPopular(); break;
            case SUBJECT: showSubject(); break;
            case RANKING: openRanking(); break;
            case TRACKING: case HISTORY: showCollection(destination); break;
            default: showHome(); break;
        }
    }

    private void openSources() {
        if (screen != Screen.SOURCES) sourceBack = screen == Screen.SEARCH ? Screen.SEARCH : Screen.HOME;
        showSources();
    }

    private void showSources() {
        reset(Screen.SOURCES);
        add(header("动画源管理", () -> backTo(sourceBack)), 0);
        add(aggregationCard(), 8);
        LinearLayout buttons = row();
        addEqual(buttons, action("选择文件", false, this::pickFile));
        addEqual(buttons, action("粘贴规则", false, () -> askImport(false)));
        addEqual(buttons, action("从链接", false, () -> askImport(true)));
        add(buttons, 8);
        addRule();
        if (!sources.loadIssue().isEmpty()) add(label(sources.loadIssue(), 14, FG, true), 9);
        if (importing && !aggregating) {
            TextView busy = label("正在导入动画源…", 13, PINK, true);
            busy.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            add(busy, 8);
        }
        List<AnimeSource> installed = sources.list();
        if (installed.isEmpty()) {
            add(emptyState("还没有动画源", "", null, null), 8);
            return;
        }
        int enabled = (int) installed.stream().filter(source -> source.enabled).count();
        LinearLayout countRow = row();
        countRow.addView(label(host.getString(R.string.reader_source_count, enabled, "动画源"), 13, MUTED, false),
                new LinearLayout.LayoutParams(0, W, 1));
        countRow.addView(action("导出全部规则", false, this::chooseExportFile), params(W, dp(44)));
        add(countRow, 8);
        ListView list = listView();
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return installed.size(); }
            @Override public AnimeSource getItem(int position) { return installed.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View previous, ViewGroup parent) {
                AnimeSource source = getItem(position);
                LinearLayout item = row();
                item.setPadding(0, dp(6), 0, dp(6));
                LinearLayout description = column();
                TextView name = label(source.name, 16, FG, true);
                description.addView(name);
                TextView site = label(Uri.parse(source.baseUrl).getHost(), 12, MUTED, false);
                site.setMaxLines(1);
                site.setEllipsize(TextUtils.TruncateAt.END);
                description.addView(site);
                item.addView(description, new LinearLayout.LayoutParams(0, W, 1));
                Switch toggle = new Switch(host);
                toggle.setMinHeight(dp(46));
                toggle.setContentDescription("启用 " + source.name);
                toggle.setThumbTintList(new ColorStateList(
                        new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                        new int[]{PlayerViews.textTone(PINK), MUTED}));
                toggle.setTrackTintList(ColorStateList.valueOf(LINE));
                toggle.setChecked(source.enabled);
                toggle.setOnCheckedChangeListener((view, enabled) -> alterSource(() -> sources.setEnabled(source.id, enabled)));
                item.addView(toggle, params(W, dp(46)));
                item.addView(action("管理", false, () -> sourceMenu(source)), params(dp(63), dp(45)));
                return item;
            }
        });
        body.addView(list, new LinearLayout.LayoutParams(M, 0, 1));
    }

    private View aggregationCard() {
        LinearLayout panel = column();
        LinearLayout controls = row();
        controls.addView(label("Kazumi 规则仓库", 16, FG, true), new LinearLayout.LayoutParams(0, W, 1));
        TextView aggregate = action(aggregating ? "聚合中…" : "一键聚合", true, this::aggregateSources);
        aggregate.setEnabled(!importing);
        controls.addView(aggregate, params(W, dp(44)));
        panel.addView(controls, params(M, W));
        if (!aggregationStatus.isEmpty()) {
            aggregationLabel = label(aggregationStatus, 13, MUTED, false);
            aggregationLabel.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            aggregationLabel.setPadding(0, dp(4), 0, dp(3));
            panel.addView(aggregationLabel, params(M, W));
        }
        if (aggregationResult != null && !aggregationResult.diagnostics.isEmpty()) {
            TextView details = action("查看详情", false, () -> dialog(new AlertDialog.Builder(host)
                    .setTitle("Kazumi 聚合详情")
                    .setMessage(aggregationStatus + "\n\n" + TextUtils.join("\n", aggregationResult.diagnostics))
                    .setPositiveButton("关闭", null)));
            panel.addView(details, params(W, dp(44)));
        }
        return panel;
    }

    private void aggregateSources() {
        if (destroyed) return;
        if (importing) {
            openSources();
            return;
        }
        importing = true;
        aggregating = true;
        aggregationResult = null;
        aggregationStatus = "正在读取 Kazumi 规则仓库…";
        openSources();
        io.execute(() -> {
            AnimeSource.AggregationResult result = null;
            String failure = "";
            try {
                result = sources.aggregateOfficial(progress -> main.post(() -> {
                    if (destroyed) return;
                    aggregationStatus = progress;
                    if (aggregationLabel != null) aggregationLabel.setText(progress);
                }));
            } catch (IOException | RuntimeException error) {
                failure = problem(error);
            }
            AnimeSource.AggregationResult completed = result;
            String summary = completed == null
                    ? "聚合失败 · " + failure
                    : "新增 " + completed.imported.added + " · 更新 " + completed.imported.updated
                        + " · 已保留 " + completed.imported.skipped
                        + " · 跳过 " + completed.skippedEntries + " · 失败 " + completed.failed;
            main.post(() -> {
                if (destroyed) return;
                importing = false;
                aggregating = false;
                aggregationResult = completed;
                aggregationStatus = summary;
                if (screen == Screen.SOURCES) showSources();
                else {
                    if (screen == Screen.HOME) showHome();
                    toast(summary);
                }
            });
        });
    }

    private void sourceMenu(AnimeSource source) {
        dialog(new AlertDialog.Builder(host).setTitle(source.name).setMessage(source.baseUrl)
                .setNeutralButton("移除", (window, which) -> dialog(new AlertDialog.Builder(host)
                        .setTitle("移除该动画源？").setMessage("下次可重新导入来源，从追番或观看记录继续。")
                        .setNegativeButton("取消", null).setPositiveButton("移除来源", (confirm, action) ->
                                alterSource(() -> sources.remove(source.id)))))
                .setNegativeButton("关闭", null));
    }

    private interface Change { void run() throws IOException; }
    private interface Import { AnimeSource.ImportResult run() throws IOException; }

    private void alterSource(Change operation) {
        io.execute(() -> {
            String failure;
            try { operation.run(); failure = ""; }
            catch (IOException | RuntimeException error) { failure = problem(error); }
            String message = failure;
            main.post(() -> {
                if (destroyed) return;
                if (!message.isEmpty()) toast(message);
                if (screen == Screen.SOURCES) showSources();
            });
        });
    }

    private void pickFile() {
        if (importing) return;
        Intent file = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { host.startActivityForResult(file, FILE_REQUEST); }
        catch (ActivityNotFoundException missing) { toast("此设备没有可用的文件选择器"); }
    }

    private void chooseExportFile() {
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "anime-rules.json");
        try { host.startActivityForResult(picker, EXPORT_RULES); }
        catch (ActivityNotFoundException missing) { toast("此设备没有可用的文件选择器"); }
    }

    private void exportRules(Uri document) {
        io.execute(() -> {
            String status;
            try {
                byte[] payload = sources.exportRules();
                try (OutputStream output = host.getContentResolver().openOutputStream(document, "wt")) {
                    if (output == null) throw new IOException("无法写入所选文件");
                    output.write(payload);
                }
                status = "动画规则已导出";
            } catch (IOException | RuntimeException failure) {
                status = "导出失败 · " + problem(failure);
            }
            String message = status;
            main.post(() -> { if (!destroyed) toast(message); });
        });
    }

    private void askImport(boolean link) {
        if (importing) { toast("请等当前导入完成"); return; }
        EditText field = input(link ? "粘贴分享链接、规则链接或索引链接" : "粘贴 Kazumi JSON 规则", true);
        field.setMinLines(link ? 2 : 5);
        LinearLayout space = column();
        space.setPadding(dp(18), dp(5), dp(18), 0);
        space.addView(field, params(M, W));
        AlertDialog window = dialog(new AlertDialog.Builder(host).setTitle(link ? "从链接导入" : "粘贴动画源")
                .setView(space).setPositiveButton("导入", null).setNegativeButton("取消", null));
        window.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) { field.setError("请输入要导入的内容"); return; }
            window.dismiss();
            if (link) importLink(value);
            else importRules(() -> sources.importText(value));
        });
    }

    private void importRules(Import request) {
        if (importing || destroyed) { toast("请等当前导入完成"); return; }
        importing = true;
        openSources();
        io.execute(() -> {
            AnimeSource.ImportResult result = null;
            String error = "";
            try { result = request.run(); }
            catch (IOException | RuntimeException failure) { error = problem(failure); }
            AnimeSource.ImportResult imported = result;
            String message = error;
            main.post(() -> {
                if (destroyed) return;
                importing = false;
                if (screen == Screen.SOURCES) showSources();
                if (imported == null) {
                    dialog(new AlertDialog.Builder(host).setTitle("导入失败").setMessage(message)
                            .setPositiveButton("关闭", null));
                } else {
                    String summary = "新增 " + imported.added + " · 更新 " + imported.updated + " · 跳过 " + imported.skipped;
                    if (!imported.diagnostics.isEmpty()) summary += "\n\n" + TextUtils.join("\n", imported.diagnostics);
                    dialog(new AlertDialog.Builder(host).setTitle("动画源已处理").setMessage(summary)
                            .setPositiveButton("完成", null));
                }
            });
        });
    }

    private static String problem(Throwable error) {
        if (error instanceof SocketTimeoutException) return "连接超时";
        if (error instanceof UnknownHostException) return "无法解析网站地址";
        if (error instanceof LinkageError) return "当前设备无法执行该规则";
        String detail = error.getMessage();
        if (detail == null || detail.isEmpty()) return "没有取得数据";
        detail = detail.replaceAll("https?://[^\\s]+", "网站地址");
        return detail.substring(0, Math.min(160, detail.length()));
    }

    private void reset(Screen destination) {
        boolean changed = screen != destination;
        boolean back = destination == Screen.HOME || switch (screen) {
            case SEARCH -> destination == searchBack;
            case SOURCES -> destination == sourceBack;
            case SUBJECT -> destination == subjectBack;
            case EPISODES -> destination == detailBack;
            default -> false;
        };
        screen = destination;
        aggregationLabel = null;
        root.removeAllViews();
        root.setBackgroundColor(BG);
        body = column();
        body.setPadding(0, dp(3), 0, dp(5));
        root.addView(body, new FrameLayout.LayoutParams(M, M));
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        if (destination != Screen.SEARCH) searchList = null;
        homePopular = null;
        recentWatching = null;
        ruleShortcut = null;
        dashboardSearch = null;
        dashboardScroll = null;
        popularView = null;
        popularGrid = null;
        popularStatus = null;
        popularAction = null;
        if (changed) PageMotion.enter(root, back);
    }

    private LinearLayout header(String title, Runnable back) {
        LinearLayout bar = row();
        FrameLayout control = new FrameLayout(host);
        control.setContentDescription("返回");
        control.setFocusable(true);
        control.setBackground(PlayerViews.button(host, Color.TRANSPARENT, 4, 0));
        control.addView(new PlayerViews.Icon(host, "back_left", FG, 21), new FrameLayout.LayoutParams(M, M));
        control.setOnClickListener(view -> back.run());
        bar.addView(control, params(dp(44), dp(44)));
        TextView caption = label(title, 20, FG, true);
        caption.setPadding(dp(4), 0, dp(4), 0);
        markHeading(caption);
        bar.addView(caption, new LinearLayout.LayoutParams(0, W, 1));
        return bar;
    }

    private View emptyState(String title, String hint, String button, Runnable click) {
        return emptyState(0, title, hint, button, click);
    }

    private View emptyState(int emote, String title, String hint, String button, Runnable click) {
        LinearLayout panel = column();
        panel.setPadding(0, dp(3), 0, dp(3));
        TextView heading = label(title, 17, FG, true);
        markHeading(heading);
        if (emote == 0) {
            panel.addView(heading);
        } else {
            LinearLayout headingRow = row();
            LinearLayout.LayoutParams imageSpace = params(dp(34), dp(34));
            imageSpace.setMarginEnd(dp(8));
            headingRow.addView(MyGoEmotes.icon(host, emote, 34), imageSpace);
            headingRow.addView(heading, new LinearLayout.LayoutParams(0, W, 1));
            panel.addView(headingRow, params(M, W));
        }
        if (!hint.isEmpty()) {
            TextView note = label(hint, 13, MUTED, false);
            note.setPadding(0, dp(5), 0, 0);
            panel.addView(note);
        }
        if (button != null) {
            LinearLayout.LayoutParams buttonSpace = params(W, dp(44));
            buttonSpace.topMargin = dp(9);
            panel.addView(action(button, true, click), buttonSpace);
        }
        return panel;
    }

    private void addList(List<Row> rows) {
        ListView view = listView();
        AnimeList entries = new AnimeList();
        view.setAdapter(entries);
        entries.submit(rows);
        body.addView(view, new LinearLayout.LayoutParams(M, 0, 1));
    }

    private static final class Row {
        final String title, subtitle, cover;
        final Runnable open, more;

        Row(String title, String subtitle, String cover, Runnable open, Runnable more) {
            this.title = title;
            this.subtitle = subtitle;
            this.cover = cover;
            this.open = open;
            this.more = more;
        }
    }

    private final class AnimeList extends BaseAdapter {
        private List<Row> rows = List.of();

        void submit(List<Row> values) { rows = values; notifyDataSetChanged(); }
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            RowView item = recycled instanceof RowView ? (RowView) recycled : new RowView();
            item.bind(getItem(position));
            return item;
        }
    }

    private final class PopularGrid extends BaseAdapter {
        private List<AnimeDiscovery.Entry> entries = List.of();

        void submit(List<AnimeDiscovery.Entry> entries) {
            if (this.entries.equals(entries)) return;
            this.entries = List.copyOf(entries);
            notifyDataSetChanged();
        }

        @Override public int getCount() { return entries.size(); }
        @Override public AnimeDiscovery.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return getItem(position).subject().id; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            PopularCard item = recycled instanceof PopularCard card ? card : new PopularCard();
            item.bind(getItem(position));
            return item;
        }
    }

    private final class PopularCard extends LinearLayout {
        private final ImageView cover;
        private final TextView title;

        PopularCard() {
            super(host);
            setOrientation(VERTICAL);
            setFocusable(true);
            setBackground(PlayerViews.button(host, Color.TRANSPARENT, 6, 0));
            cover = new ImageView(host) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) {
                    int width = View.MeasureSpec.getSize(widthSpec);
                    setMeasuredDimension(width, Math.round(width / .65f));
                }
            };
            configureArtwork(cover);
            addView(cover, params(M, W));
            title = label("", 13, FG, true);
            title.setLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(2), dp(4), dp(2), 0);
            addView(title, params(M, W));
        }

        void bind(AnimeDiscovery.Entry entry) {
            title.setText(entry.subject().title);
            connectArtwork(cover, entry.cover());
            setOnClickListener(view -> openSubject(entry));
        }
    }

    private final class RowView extends LinearLayout {
        private final TextView title, subtitle, more;
        private final ImageView image;
        private final LinearLayout words;

        RowView() {
            super(host);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(6), 0, dp(6));
            setBackground(PlayerViews.button(host, Color.TRANSPARENT, 0, 0));
            setFocusable(true);
            image = artwork(dp(44), dp(58));
            addView(image);
            words = column();
            title = label("", 15, FG, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(title);
            subtitle = label("", 12, MUTED, false);
            subtitle.setPadding(0, dp(1), 0, 0);
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(subtitle);
            addView(words, new LinearLayout.LayoutParams(0, W, 1));
            more = action("⋯", false, () -> {});
            more.setContentDescription("管理记录");
            addView(more, params(dp(48), dp(48)));
        }

        void bind(Row row) {
            title.setText(row.title);
            subtitle.setText(row.subtitle);
            image.setVisibility(row.cover.isEmpty() ? View.GONE : View.VISIBLE);
            words.setPadding(row.cover.isEmpty() ? 0 : dp(11), 0, dp(5), 0);
            connectArtwork(image, row.cover);
            setMinimumHeight(dp(row.cover.isEmpty() ? 53 : 70));
            setOnClickListener(view -> row.open.run());
            more.setVisibility(row.more == null ? View.GONE : View.VISIBLE);
            more.setOnClickListener(row.more == null ? null : view -> row.more.run());
        }
    }

    private ListView listView() {
        ListView list = new ListView(host);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        return list;
    }

    private ImageView artwork(int width, int height) {
        ImageView view = new ImageView(host);
        view.setLayoutParams(params(width, height));
        configureArtwork(view);
        return view;
    }

    private void configureArtwork(ImageView view) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setBackground(PlayerViews.surface(host, SURFACE, 5, 0));
        view.setClipToOutline(true);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void connectArtwork(ImageView image, String url) { artwork.bind(image, url); }

    private AlertDialog dialog(AlertDialog.Builder builder) {
        AlertDialog window = builder.create();
        dialogs.add(window);
        window.setOnDismissListener(ignored -> dialogs.remove(window));
        window.show();
        if (window.getWindow() != null) window.getWindow().setBackgroundDrawable(PlayerViews.surface(host, SURFACE, 10, LINE));
        for (int code : new int[]{AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_POSITIVE}) {
            TextView button = window.getButton(code);
            if (button != null) button.setTextColor(PlayerViews.textTone(PINK));
        }
        return window;
    }

    private TextView action(String title, boolean primary, Runnable run) {
        TextView view = label(title, 13, primary ? Color.WHITE : FG, true);
        view.setMinHeight(dp(44));
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(primary ? 13 : 8), 0, dp(primary ? 13 : 8), 0);
        view.setBackground(PlayerViews.button(host, primary ? PINK : Color.TRANSPARENT, 7, 0));
        view.setFocusable(true);
        view.setOnClickListener(ignored -> run.run());
        return view;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(host);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(PlayerViews.textTone(color));
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private EditText input(String hint, boolean multiline) {
        EditText field = new EditText(host);
        field.setHint(hint);
        field.setTextSize(14);
        field.setTextColor(FG);
        field.setHintTextColor(MUTED);
        field.setSingleLine(!multiline);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        field.setBackgroundColor(Color.TRANSPARENT);
        field.setPadding(dp(4), 0, dp(4), 0);
        return field;
    }

    private void add(View view, int top) {
        body.addView(view, withTopGap(top));
    }

    private LinearLayout.LayoutParams withTopGap(int top) {
        LinearLayout.LayoutParams params = params(M, W);
        params.topMargin = dp(top);
        return params;
    }

    private void addRule() {
        View rule = new View(host);
        rule.setBackgroundColor(LINE);
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        body.addView(rule, params(M, dp(1)));
    }

    private void addEqual(LinearLayout group, View view) {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(0, W, 1);
        if (group.getChildCount() > 0) space.setMarginStart(dp(6));
        group.addView(view, space);
    }

    private static LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private static void markHeading(View view) { if (Build.VERSION.SDK_INT >= 28) view.setAccessibilityHeading(true); }
    private int dp(float units) { return PlayerViews.dp(host, units); }
    private void toast(String message) { if (!destroyed) Toast.makeText(host, message, Toast.LENGTH_SHORT).show(); }
}
