package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.shengjian.player.PlayerViews.BG;
import static com.shengjian.player.PlayerViews.FG;
import static com.shengjian.player.PlayerViews.LINE;
import static com.shengjian.player.PlayerViews.MUTED;
import static com.shengjian.player.PlayerViews.PINK;
import static com.shengjian.player.PlayerViews.SURFACE;

final class MusicOnlineSection implements AutoCloseable {
    static final int REQUEST_IMPORT_MUSIC_SOURCE = 509;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MAX_QUERY = 160;

    private final Activity host;
    private final Consumer<MusicOnlineEngine.Song> onPlay;
    private final Consumer<MusicOnlineEngine.Song> onDownload;
    private final BiConsumer<ImageView, String> loadArtwork;
    private final FrameLayout page;
    private final MusicSourceCatalog.Store catalog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReaderSearchQueue searches = new ReaderSearchQueue(4, 16_000);
    private final ExecutorService imports = Executors.newSingleThreadExecutor(worker -> {
        Thread thread = new Thread(worker, "music-source-import");
        thread.setDaemon(true);
        return thread;
    });
    private final List<AlertDialog> prompts = new ArrayList<>();

    private LinearLayout searchScreen, sourceScreen;
    private EditText homeKeywords, keywords;
    private TextView searchStatus, stopButton, detailsButton, retryButton, moreButton;
    private ProgressBar searchProgress;
    private ListView songList;
    private Rows<Match> songRows;
    private TextView sourceStatus, aggregateButton;
    private TextView[] importButtons;
    private Rows<MusicSourceCatalog.Source> sourceRows;
    private ReaderSearchQueue.Run activeSearch;
    private SearchState state;
    private int searchGeneration;
    private boolean importing, closed;
    private String sourceNotice = "";
    private final Runnable publishSearch = this::renderSearch;

    MusicOnlineSection(Activity host, Consumer<MusicOnlineEngine.Song> onPlay,
                       Consumer<MusicOnlineEngine.Song> onDownload, FrameLayout page,
                       BiConsumer<ImageView, String> loadArtwork, MusicSourceCatalog.Store catalog) {
        this.host = host;
        this.onPlay = onPlay;
        this.onDownload = onDownload;
        this.page = page;
        this.loadArtwork = loadArtwork;
        this.catalog = catalog;
    }

    View searchBox() {
        homeKeywords = queryInput(state == null ? "" : state.keyword);
        return queryBox(homeKeywords, this::searchFromHome);
    }

    private void searchFromHome(EditText input) {
        String keyword = readQuery(input);
        if (keyword.isEmpty()) return;
        dismissKeyboard(input);
        showSearch();
        startSearch(keyword);
    }

    private void showSearch() {
        if (closed || searchScreen != null) return;
        LinearLayout content = screen();
        searchScreen = content;
        LinearLayout header = header("在线搜歌", this::closeSearch);
        TextView manage = action("音源", false, this::showSources);
        manage.setTextColor(PlayerViews.textTone(PINK));
        header.addView(manage, params(WRAP, dp(44)));
        content.addView(header, params(MATCH, WRAP));

        keywords = queryInput(state == null ? "" : state.keyword);
        LinearLayout queryBar = queryBox(keywords, input -> {
            String keyword = readQuery(input);
            if (keyword.isEmpty()) return;
            dismissKeyboard(input);
            startSearch(keyword);
        });
        add(content, queryBar, 6);

        searchStatus = label("", 13, MUTED, false);
        searchStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(content, searchStatus, 12);
        searchProgress = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        searchProgress.setProgressTintList(ColorStateList.valueOf(PlayerViews.textTone(PINK)));
        searchProgress.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
        searchProgress.setContentDescription("音乐源查询进度");
        LinearLayout.LayoutParams progressSpace = params(MATCH, dp(4));
        progressSpace.topMargin = dp(6);
        content.addView(searchProgress, progressSpace);

        LinearLayout options = row();
        stopButton = action("停止", false, () -> { cancelSearch(); renderSearch(); });
        detailsButton = action("来源详情", false, this::showFailures);
        retryButton = action("重试", false, this::retrySearch);
        moreButton = action("加载更多", false, this::loadMore);
        for (TextView option : new TextView[]{stopButton, detailsButton, retryButton, moreButton}) {
            options.addView(option, params(WRAP, dp(44)));
        }
        content.addView(options, params(MATCH, WRAP));
        rule(content);

        songRows = new Rows<>((match, recycled) -> {
            SongRow row = recycled != null && recycled.getTag() instanceof SongRow existing ? existing : new SongRow();
            row.bind(match);
            return row.item;
        });
        songList = list(songRows);
        content.addView(songList, new LinearLayout.LayoutParams(MATCH, 0, 1));
        present(content);
        renderSearch();
    }

    void showSources() {
        if (closed || sourceScreen != null) return;
        hideKeyboard();
        LinearLayout content = screen();
        sourceScreen = content;
        LinearLayout header = header("音源管理", this::closeSources);
        header.addView(action("订阅", false, this::showRepositories), params(WRAP, dp(44)));
        content.addView(header, params(MATCH, WRAP));

        LinearLayout actions = row();
        TextView repository = label("MusicFree 插件仓库", 16, FG, true);
        repository.setPadding(0, 0, dp(8), 0);
        actions.addView(repository, new LinearLayout.LayoutParams(0, WRAP, 1));
        aggregateButton = action("一键聚合", true, this::aggregate);
        actions.addView(aggregateButton, params(WRAP, dp(44)));
        add(content, actions, 8);

        LinearLayout importControls = row();
        importButtons = new TextView[]{action("选择文件", false, this::pickFile),
                action("粘贴规则", false, () -> enterSource(false)), action("从链接", false, () -> enterSource(true))};
        for (TextView button : importButtons) importControls.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1));
        add(content, importControls, 8);
        rule(content);

        sourceStatus = label("", 13, MUTED, false);
        sourceStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        sourceStatus.setPadding(0, 0, 0, dp(10));
        add(content, sourceStatus, 10);
        sourceRows = new Rows<>((source, recycled) -> sourceRow(source));
        content.addView(list(sourceRows), new LinearLayout.LayoutParams(MATCH, 0, 1));
        present(content);
        renderSources();
    }

    private String readQuery(EditText input) {
        String value = ReaderSearchRanking.requestKeyword(input.getText().toString());
        if (value.isEmpty()) input.setError("请输入歌名或歌手");
        return value;
    }

    private void startSearch(String keyword) {
        cancelSearch();
        keywords.setText(keyword);
        if (homeKeywords != null) homeKeywords.setText(keyword);
        state = new SearchState(keyword);
        songList.setSelection(0);
        ArrayList<Target> targets = new ArrayList<>();
        MusicOnlineEngine.Config navidrome = null;
        for (MusicSourceCatalog.Source source : catalog.sources()) {
            if (!source.enabled() || !source.searchable()) continue;
            if (source.adapter() == MusicSourceCatalog.Adapter.NAVIDROME) {
                try {
                    navidrome = MusicOnlineAccounts.get(host);
                    MusicOnlineEngine.validateConfig(navidrome);
                } catch (IOException invalid) {
                    state.failures.put(source.id(), source.name() + " · " + errorMessage(invalid, "请检查服务器配置"));
                    continue;
                }
            }
            targets.add(new Target(source, source.adapter() == MusicSourceCatalog.Adapter.NAVIDROME ? navidrome : null, 1));
        }
        runSearch(targets);
    }

    private void loadMore() {
        if (state == null || activeSearch != null) return;
        runSearch(new ArrayList<>(state.nextPages.values()));
    }

    private void retrySearch() {
        if (state == null || activeSearch != null) return;
        ArrayList<Target> targets = new ArrayList<>(state.failedTargets.values());
        targets.addAll(state.pending.values());
        runSearch(targets);
    }

    private void runSearch(List<Target> targets) {
        SearchState current = state;
        current.total = targets.size();
        current.finished = 0;
        current.stopped = false;
        int generation = ++searchGeneration;
        for (Target target : targets) {
            String id = target.source.id();
            current.nextPages.remove(id);
            current.failedTargets.remove(id);
            current.failures.remove(id);
            current.pending.put(id, target);
        }
        if (!targets.isEmpty()) {
            activeSearch = searches.start(targets,
                    target -> MusicOnlineEngine.searchPage(target.source.adapter().provider(), target.config,
                            current.keyword, target.page),
                    (target, results, error) -> main.post(() -> {
                        if (closed || generation != searchGeneration || activeSearch == null) return;
                        current.finished++;
                        current.pending.remove(target.source.id());
                        if (error != null) {
                            current.failures.put(target.source.id(), target.source.name() + " · " + errorMessage(error, "查询失败"));
                            current.failedTargets.put(target.source.id(), target);
                        } else {
                            for (MusicOnlineEngine.Song song : results.songs()) {
                                current.matches.putIfAbsent(song.provider + '\u001f' + song.id,
                                        new Match(song, target.source.name(), current.ranking.score(song.title, song.artist)));
                            }
                            if (results.hasMore() && target.page < 50) {
                                current.nextPages.put(target.source.id(), new Target(target.source, target.config, target.page + 1));
                            }
                        }
                        if (current.finished == current.total) activeSearch = null;
                        main.removeCallbacks(publishSearch);
                        main.postDelayed(publishSearch, 45);
                    }));
        }
        renderSearch();
    }

    private void cancelSearch() {
        main.removeCallbacks(publishSearch);
        if (activeSearch == null) return;
        searchGeneration++;
        activeSearch.cancel();
        activeSearch = null;
        if (state != null) state.stopped = true;
    }

    private void renderSearch() {
        if (searchScreen == null) return;
        boolean busy = activeSearch != null;
        int errors = state == null ? 0 : state.failures.size();
        stopButton.setVisibility(busy ? View.VISIBLE : View.GONE);
        detailsButton.setVisibility(errors > 0 ? View.VISIBLE : View.GONE);
        retryButton.setVisibility(state != null && !busy && (!state.failedTargets.isEmpty() || !state.pending.isEmpty())
                ? View.VISIBLE : View.GONE);
        moreButton.setVisibility(state != null && !busy && !state.nextPages.isEmpty() ? View.VISIBLE : View.GONE);
        searchProgress.setVisibility(state != null && state.total > 0 ? View.VISIBLE : View.GONE);
        if (state == null) {
            searchStatus.setText("输入歌名或歌手，查询已启用的音乐源。");
            songRows.replace(List.of());
            return;
        }
        if (state.total == 0) {
            searchStatus.setText("暂无可搜索的源，请在音源管理中启用或配置。");
        } else {
            searchProgress.setMax(state.total);
            searchProgress.setProgress(state.finished);
            String message = (state.stopped ? "已停止 · " : "") + "已查 " + state.finished + "/" + state.total + " 个源"
                    + " · " + state.matches.size() + " 首" + (errors == 0 ? "" : " · " + errors + " 个失败");
            if (!busy && !state.stopped && state.matches.isEmpty() && errors < state.total) message += "\n没有找到匹配的歌曲";
            searchStatus.setText(message);
        }
        ArrayList<Match> ranked = new ArrayList<>(state.matches.values());
        ranked.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(Match::source, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.song.title, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(match -> match.song.id));
        int first = songList.getFirstVisiblePosition();
        Match anchor = first > 0 && first < songRows.getCount() ? songRows.getItem(first) : null;
        int offset = songList.getChildCount() > 0 ? songList.getChildAt(0).getTop() : 0;
        songRows.replace(ranked);
        if (anchor != null) {
            int position = ranked.indexOf(anchor);
            if (position >= 0 && position != first) songList.setSelectionFromTop(position, offset);
        }
    }

    private void showFailures() {
        if (state == null || state.failures.isEmpty()) return;
        showPrompt(new AlertDialog.Builder(host).setTitle("查询失败的音源")
                .setItems(state.failures.values().toArray(new String[0]), null)
                .setPositiveButton("关闭", null));
    }

    private final class SongRow {
        final LinearLayout item = row();
        final ImageView artwork = new ImageView(host);
        final TextView title = oneLine(label("", 15, FG, true));
        final TextView subtitle = oneLine(label("", 12, MUTED, false));
        final TextView length = label("", 12, MUTED, false);
        MusicOnlineEngine.Song song;
        final TextView download = oneLine(action("下载", false, () -> onDownload.accept(song)));

        SongRow() {
            item.setPadding(0, dp(8), dp(6), dp(8));
            item.setMinimumHeight(dp(62));
            item.setBackground(PlayerViews.button(host, Color.TRANSPARENT, 8, 0));
            item.setTag(this);
            artwork.setBackground(PlayerViews.surface(host, SURFACE, 7, 0));
            artwork.setClipToOutline(true);
            artwork.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams coverSize = params(dp(46), dp(46));
            coverSize.setMarginEnd(dp(10));
            item.addView(artwork, coverSize);
            LinearLayout text = column();
            text.addView(title, params(MATCH, WRAP));
            text.addView(subtitle, params(MATCH, WRAP));
            item.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
            length.setPadding(dp(10), 0, 0, 0);
            item.addView(length, params(WRAP, WRAP));
            download.setPadding(dp(4), 0, dp(4), 0);
            LinearLayout.LayoutParams downloadSize = params(dp(56), dp(48));
            downloadSize.setMarginStart(dp(6));
            item.addView(download, downloadSize);
            item.setFocusable(true);
            item.setOnClickListener(view -> onPlay.accept(song));
        }

        void bind(Match match) {
            song = match.song;
            title.setText(song.title);
            subtitle.setText(song.artist.isEmpty() ? match.source : song.artist + " · " + match.source);
            download.setContentDescription("下载「" + song.title + "」到本地");
            long seconds = song.durationMs / 1000;
            length.setVisibility(seconds > 0 ? View.VISIBLE : View.GONE);
            if (seconds > 0) length.setText(String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60));
            if (!song.coverUrl.equals(artwork.getTag())) loadArtwork.accept(artwork, song.coverUrl);
        }
    }

    private View sourceRow(MusicSourceCatalog.Source source) {
        LinearLayout item = row();
        item.setPadding(0, dp(8), 0, dp(8));
        LinearLayout text = column();
        text.addView(label(source.name(), 15, FG, true), params(MATCH, WRAP));
        String caption = source.capabilities();
        if (source.adapter() == MusicSourceCatalog.Adapter.NAVIDROME) {
            caption += source.enabled() ? " · 已启用" : " · 可配置服务器";
        }
        if (!source.version().isEmpty()) caption += " · v" + source.version();
        text.addView(label(caption, 12, MUTED, false), params(MATCH, WRAP));
        item.addView(text, new LinearLayout.LayoutParams(0, WRAP, 1));
        if (source.adapter() == MusicSourceCatalog.Adapter.NAVIDROME) {
            item.addView(action("配置", false, () -> configureNavidrome(source)), params(WRAP, dp(44)));
        }
        if (source.searchable()) {
            Switch toggle = new Switch(host);
            toggle.setContentDescription("启用" + source.name());
            toggle.setThumbTintList(new ColorStateList(
                    new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{PlayerViews.textTone(PINK), MUTED}));
            toggle.setTrackTintList(ColorStateList.valueOf(LINE));
            toggle.setChecked(source.enabled());
            toggle.setOnCheckedChangeListener((button, checked) -> changeEnabled(source, checked));
            item.addView(toggle, params(WRAP, dp(44)));
        } else if (!source.builtIn()) {
            item.addView(action("移除", false, () -> removeSource(source)), params(WRAP, dp(44)));
        }
        return item;
    }

    private void changeEnabled(MusicSourceCatalog.Source source, boolean checked) {
        try {
            if (checked && source.adapter() == MusicSourceCatalog.Adapter.NAVIDROME) {
                try { MusicOnlineEngine.validateConfig(MusicOnlineAccounts.get(host)); }
                catch (IOException missing) { renderSources(); configureNavidrome(source); return; }
            }
            catalog.setEnabled(source.id(), checked);
            clearSearchResults();
        } catch (IOException failure) { notifyUser("无法保存音源状态"); }
        renderSources();
    }

    private void removeSource(MusicSourceCatalog.Source source) {
        try { catalog.remove(source.id()); }
        catch (IOException failure) { notifyUser("无法移除这个音源"); }
        renderSources();
    }

    private void configureNavidrome(MusicSourceCatalog.Source source) {
        MusicOnlineEngine.Config saved;
        try { saved = MusicOnlineAccounts.get(host); }
        catch (IOException ignored) { saved = new MusicOnlineEngine.Config("", "", ""); }
        LinearLayout fields = column();
        fields.setPadding(dp(20), dp(6), dp(20), dp(4));
        EditText address = field("服务器地址，例如 https://music.example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048);
        EditText username = field("用户名", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 128);
        EditText password = field("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 1024);
        address.setText(saved.baseUrl());
        username.setText(saved.username());
        password.setText(saved.password());
        for (EditText input : new EditText[]{address, username, password}) add(fields, input, 8);
        AlertDialog.Builder builder = new AlertDialog.Builder(host).setTitle("配置 Navidrome")
                .setView(fields).setNegativeButton("取消", null).setPositiveButton("保存并启用", null);
        if (!saved.baseUrl().isEmpty()) builder.setNeutralButton("移除账户", null);
        AlertDialog dialog = showPrompt(builder);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            MusicOnlineEngine.Config config = new MusicOnlineEngine.Config(address.getText().toString().trim(),
                    username.getText().toString().trim(), password.getText().toString());
            try {
                MusicOnlineEngine.validateConfig(config);
                MusicOnlineAccounts.save(host, config);
                clearSearchResults();
                catalog.setEnabled(source.id(), true);
                dialog.dismiss();
                renderSources();
            } catch (IOException failure) { address.setError(errorMessage(failure, "无法保存服务器配置")); }
        });
        TextView remove = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (remove != null && remove.getVisibility() == View.VISIBLE) remove.setOnClickListener(view -> {
            try {
                MusicOnlineAccounts.save(host, new MusicOnlineEngine.Config("", "", ""));
                clearSearchResults();
                catalog.setEnabled(source.id(), false);
                dialog.dismiss();
                renderSources();
            } catch (IOException failure) { notifyUser("无法移除 Navidrome 账户"); }
        });
    }

    private void clearSearchResults() {
        cancelSearch();
        state = null;
        renderSearch();
    }

    private void enterSource(boolean url) {
        if (importing) return;
        EditText input = field(url ? "https://…/plugins.json" : "粘贴清单或插件内容",
                InputType.TYPE_CLASS_TEXT | (url ? InputType.TYPE_TEXT_VARIATION_URI : InputType.TYPE_TEXT_FLAG_MULTI_LINE),
                url ? 4096 : 1_048_576);
        if (!url) { input.setMinLines(5); input.setMaxLines(10); input.setGravity(Gravity.TOP | Gravity.START); }
        LinearLayout holder = column();
        holder.setPadding(dp(18), dp(4), dp(18), dp(4));
        holder.addView(input, params(MATCH, WRAP));
        AlertDialog dialog = showPrompt(new AlertDialog.Builder(host).setTitle(url ? "从链接导入" : "粘贴音乐源")
                .setView(holder).setPositiveButton("导入", null).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { input.setError("请输入音源内容"); return; }
            dialog.dismiss();
            runImport("正在导入音乐源…", () -> catalog.merge(url ? MusicSourceCatalog.fetch(text) : MusicSourceCatalog.parse(text, "")));
        });
    }

    private void pickFile() {
        if (importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { host.startActivityForResult(intent, REQUEST_IMPORT_MUSIC_SOURCE); }
        catch (RuntimeException unavailable) { notifyUser("此设备无法打开文件选择器"); }
    }

    void handleImportFile(Uri uri) {
        if (uri == null || closed) return;
        showSources();
        runImport("正在读取音乐源文件…", () -> {
            try (InputStream input = host.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取所选文件");
                return catalog.merge(MusicSourceCatalog.parse(input, ""));
            }
        });
    }

    private void aggregate() {
        runWork("正在更新 MusicFree 清单和已订阅的仓库…", () -> {
            MusicSourceCatalog.AggregationResult result = catalog.aggregate();
            if (result.failed() == result.repositories()) throw new IOException("所有订阅都更新失败，请检查网络或订阅链接");
            String counts = importSummary(result.imported());
            return result.failed() == 0 ? counts : counts + " · " + result.failed() + " 个仓库失败";
        });
    }

    private void showRepositories() {
        ArrayList<String> addresses = new ArrayList<>(catalog.repositories());
        ArrayList<String> labels = new ArrayList<>();
        labels.add("MusicFree 官方清单");
        for (String address : addresses) {
            Uri uri = Uri.parse(address);
            labels.add(uri.getHost() + (uri.getEncodedPath() == null ? "" : uri.getEncodedPath()));
        }
        showPrompt(new AlertDialog.Builder(host).setTitle("音源订阅")
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (index == 0) { aggregate(); return; }
                    String address = addresses.get(index - 1);
                    showPrompt(new AlertDialog.Builder(host).setTitle("取消订阅")
                            .setMessage("取消后，这个仓库已导入的音源会保留。")
                            .setPositiveButton("取消订阅", (confirm, which) -> {
                                try { catalog.removeRepository(address); }
                                catch (IOException error) { notifyUser("无法取消订阅"); }
                                renderSources();
                            }).setNegativeButton("返回", null));
                }).setPositiveButton("添加订阅", (dialog, which) -> { if (!importing) enterSource(true); })
                .setNegativeButton("关闭", null));
    }

    private static String importSummary(MusicSourceCatalog.ImportResult result) {
        return "新增 " + result.added() + " · 更新 " + result.updated() + " · 已是最新 " + result.unchanged()
                + (result.skipped() == 0 ? "" : " · 跳过 " + result.skipped());
    }

    private interface Import { MusicSourceCatalog.ImportResult run() throws IOException; }
    private interface Work { String run() throws IOException; }

    private void runImport(String pending, Import task) { runWork(pending, () -> importSummary(task.run())); }

    private void runWork(String pending, Work task) {
        if (closed || importing) return;
        importing = true;
        sourceNotice = pending;
        renderSources();
        imports.execute(() -> {
            String summary;
            try { summary = task.run(); }
            catch (IOException | RuntimeException failure) { summary = errorMessage(failure, "音源导入失败"); }
            String result = summary;
            main.post(() -> {
                if (closed) return;
                importing = false;
                sourceNotice = result;
                renderSources();
                if (sourceScreen == null) notifyUser(result);
            });
        });
    }

    void renderSources() {
        if (sourceScreen == null) return;
        List<MusicSourceCatalog.Source> sources = catalog.sources();
        sources.sort(Comparator.comparing(MusicSourceCatalog.Source::searchable).reversed()
                .thenComparing(Comparator.comparing(MusicSourceCatalog.Source::enabled).reversed()));
        long active = sources.stream().filter(source -> source.enabled() && source.searchable()).count();
        String counts = active + " 个搜索源已启用 · " + sources.size() + " 个已收录";
        String status = !sourceNotice.isEmpty() ? sourceNotice : catalog.loadIssue();
        sourceStatus.setText(status.isEmpty() ? counts : counts + "\n" + status);
        aggregateButton.setEnabled(!importing);
        aggregateButton.setAlpha(importing ? .48f : 1f);
        aggregateButton.setText(importing ? "处理中…" : "一键聚合");
        for (TextView button : importButtons) {
            button.setEnabled(!importing);
            button.setAlpha(importing ? .48f : 1f);
        }
        sourceRows.replace(sources);
    }

    private View topPage() {
        return page.getChildAt(page.getChildCount() - 1);
    }

    private void present(LinearLayout content) {
        View previous = topPage();
        PageMotion.prepareExit(page, previous, false);
        if (previous != null) {
            previous.setVisibility(View.GONE);
        }
        page.addView(content, new FrameLayout.LayoutParams(MATCH, MATCH));
        content.requestFocus();
        PageMotion.enter(content, false);
    }

    private void remove(LinearLayout content) {
        PageMotion.prepareExit(page, closed ? null : content, true);
        page.removeView(content);
        View previous = topPage();
        if (previous != null) {
            previous.setVisibility(View.VISIBLE);
            previous.requestFocus();
            if (!closed) PageMotion.enter(previous, true);
        }
    }

    private void closeSources() {
        if (sourceScreen == null) return;
        remove(sourceScreen);
        sourceScreen = null;
        sourceRows = null;
        importButtons = null;
        sourceStatus = null;
        aggregateButton = null;
    }

    private void closeSearch() {
        if (searchScreen == null) return;
        dismissKeyboard(keywords);
        cancelSearch();
        remove(searchScreen);
        searchScreen = null;
        keywords = null;
        songRows = null;
        songList = null;
        searchProgress = null;
        searchStatus = stopButton = detailsButton = moreButton = retryButton = null;
    }

    boolean onBackPressed() {
        if (sourceScreen != null) closeSources();
        else if (searchScreen != null) closeSearch();
        else return false;
        return true;
    }

    void onLeaveSection() {
        View current = topPage();
        if (current != null) PageMotion.reset(current);
        hideKeyboard();
        cancelSearch();
        renderSearch();
    }

    private void hideKeyboard() {
        EditText field = keywords == null ? homeKeywords : keywords;
        if (field != null && field.hasFocus()) dismissKeyboard(field);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelSearch();
        searches.close();
        state = null;
        imports.shutdownNow();
        main.removeCallbacksAndMessages(null);
        for (AlertDialog dialog : new ArrayList<>(prompts)) dialog.dismiss();
        closeSources();
        closeSearch();
    }

    private static String errorMessage(Exception error, String fallback) {
        String message = error.getMessage();
        if (!(error instanceof IOException) || message == null || message.isBlank()
                || message.length() > 180 || message.contains("://") || message.contains("?")) return fallback;
        return message;
    }

    private AlertDialog showPrompt(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        prompts.add(dialog);
        dialog.setOnDismissListener(ignored -> prompts.remove(dialog));
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setBackgroundDrawable(PlayerViews.surface(host, SURFACE, 14, LINE));
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
            TextView button = dialog.getButton(which);
            if (button != null) button.setTextColor(PlayerViews.textTone(PINK));
        }
        return dialog;
    }

    private LinearLayout screen() {
        LinearLayout content = column();
        content.setPadding(0, dp(3), 0, dp(5));
        content.setBackgroundColor(BG);
        content.setFocusableInTouchMode(true);
        return content;
    }

    private EditText queryInput(String initial) {
        EditText input = field("在线搜索歌名或歌手", InputType.TYPE_CLASS_TEXT, MAX_QUERY);
        input.setMinHeight(dp(44));
        input.setPadding(0, 0, dp(5), 0);
        input.setText(initial);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        return input;
    }

    private LinearLayout queryBox(EditText input, Consumer<EditText> search) {
        return SectionControls.search(input, () -> search.accept(input));
    }

    private LinearLayout header(String text, Runnable back) {
        LinearLayout header = row();
        FrameLayout control = new FrameLayout(host);
        control.setContentDescription("返回");
        control.setBackground(PlayerViews.button(host, Color.TRANSPARENT, 4, 0));
        control.setFocusable(true);
        control.addView(new PlayerViews.Icon(host, "back", FG, 21), new FrameLayout.LayoutParams(MATCH, MATCH));
        control.setOnClickListener(view -> back.run());
        header.addView(control, params(dp(44), dp(44)));
        TextView title = label(text, 20, FG, true);
        title.setPadding(dp(4), 0, dp(4), 0);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        return header;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView text = new TextView(host);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(PlayerViews.textTone(color));
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return text;
    }

    private TextView oneLine(TextView view) {
        view.setSingleLine();
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }

    private TextView action(String label, boolean primary, Runnable run) {
        TextView button = label(label, 14, primary ? Color.WHITE : FG, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(44));
        button.setBackground(PlayerViews.button(host, primary ? PINK : Color.TRANSPARENT, 9, 0));
        button.setFocusable(true);
        button.setOnClickListener(view -> run.run());
        return button;
    }

    private EditText field(String hint, int type, int maxLength) {
        EditText field = new EditText(host);
        field.setSingleLine((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) == 0);
        field.setInputType(type);
        field.setTextSize(14);
        field.setTextColor(FG);
        field.setHintTextColor(MUTED);
        field.setHint(hint);
        field.setMinHeight(dp(46));
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLength)});
        field.setBackground(PlayerViews.surface(host, BG, 8, LINE));
        field.setPadding(dp(10), dp(6), dp(10), dp(6));
        return field;
    }

    private ListView list(BaseAdapter adapter) {
        ListView list = new ListView(host);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setItemsCanFocus(true);
        list.setAdapter(adapter);
        return list;
    }

    private void rule(LinearLayout content) {
        View line = new View(host);
        line.setBackgroundColor(LINE);
        content.addView(line, params(MATCH, dp(1)));
    }

    private void add(LinearLayout content, View child, int top) {
        LinearLayout.LayoutParams space = params(MATCH, WRAP);
        space.topMargin = dp(top);
        content.addView(child, space);
    }

    private void notifyUser(String message) { if (!closed) Toast.makeText(host, message, Toast.LENGTH_SHORT).show(); }

    private void dismissKeyboard(EditText input) {
        InputMethodManager ime = (InputMethodManager) host.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private static LinearLayout.LayoutParams params(int width, int height) { return new LinearLayout.LayoutParams(width, height); }
    private int dp(float amount) { return PlayerViews.dp(host, amount); }

    private record Target(MusicSourceCatalog.Source source, MusicOnlineEngine.Config config, int page) {}
    private record Match(MusicOnlineEngine.Song song, String source, int score) {}

    private static final class SearchState {
        final String keyword;
        final ReaderSearchRanking ranking;
        final LinkedHashMap<String, Match> matches = new LinkedHashMap<>();
        final LinkedHashMap<String, Target> nextPages = new LinkedHashMap<>();
        final LinkedHashMap<String, Target> pending = new LinkedHashMap<>();
        final LinkedHashMap<String, Target> failedTargets = new LinkedHashMap<>();
        final LinkedHashMap<String, String> failures = new LinkedHashMap<>();
        int finished, total;
        boolean stopped;

        SearchState(String keyword) {
            this.keyword = keyword;
            ranking = new ReaderSearchRanking(keyword, value -> value);
        }
    }

    private interface RowFactory<T> { View create(T item, View recycled); }

    private static final class Rows<T> extends BaseAdapter {
        private final RowFactory<T> factory;
        private List<T> items = List.of();

        Rows(RowFactory<T> factory) { this.factory = factory; }
        void replace(List<T> updated) { items = updated; notifyDataSetChanged(); }
        @Override public int getCount() { return items.size(); }
        @Override public T getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) { return factory.create(items.get(position), convertView); }
    }
}
