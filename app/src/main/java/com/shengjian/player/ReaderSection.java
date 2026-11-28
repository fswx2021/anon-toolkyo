package com.shengjian.player;

import static com.shengjian.player.PlayerViews.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import javax.net.ssl.SSLException;

final class ReaderSection {
    static final int LOCAL_NOVEL_FILE = 111;
    static final int LOCAL_MANGA_FILE = 112;
    static final int LOCAL_MANGA_DIRECTORY = 113;
    static final int NOVEL_DIAGNOSTICS_EXPORT = 121;
    static final int COMIC_DIAGNOSTICS_EXPORT = 122;
    private static final int M = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int W = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int BERRY = PINK;
    private static final int SOFT_PINK = 0xfffaeced;
    private static final int DEEP_MINT = 0xff176e62;
    private static final int SOFT_MINT = 0xffdef4eb;
    private static final int ON_BERRY = 0xffffffff;
    private static final int READER_BG = 0xff19191c;
    private static final int READER_CHROME = 0xff28282c;
    private static final int READER_TEXT = 0xfffaf9fa;
    private static final int READER_MUTED = 0xffcac7ca;
    private static final int READER_BUTTON = 0xff39393d;
    private static final int READER_ACCENT = 0xffefa6b1;
    private static final long RETRY_SOURCE_TIMEOUT_MILLIS = 20_000;
    private static final long SEARCH_UPDATE_INTERVAL_MILLIS = 80;
    private static final String[] SHELF_FILTERS = {"全部", "收藏", "读过", "本地"};
    private static final String[] SHELF_ORDERS = {"收藏优先", "最近阅读", "按书名", "按作者"};
    private static final String[] DIAGNOSTIC_FILTERS = {"全部状态", "通过", "失败", "未检查", "未匹配／受限"};
    private static final Comparator<ReaderSources.Source> PPCAT_FIRST =
            Comparator.comparing(source -> !source.ppcat());
    private static final Uri MANGA_SOURCE_REPOSITORY =
            Uri.parse("https://www.yckceo.com/yiciyuan/tuyuan/index.html");
    private enum Screen { HOME, SOURCES, CHAPTERS, READER, DOWNLOADS, HISTORY }
    private enum SearchMode {
        SEARCH("搜索"), EXPLORE("发现");

        final String label;

        SearchMode(String label) { this.label = label; }
    }
    private enum SearchPass { INITIAL, CONTINUE, RETRY, MORE }

    private final Activity host;
    private final boolean manga;
    private final int kind;
    private final int pickerRequestCode;
    private final Runnable screenChanged;
    private final String sectionName;
    private final ReaderSources sources;
    private final ReaderProgress progress;
    private final ReaderCache offline;
    private final ReaderLocalBooks localBooks;
    private final SharedPreferences readerSettings;
    private final SharedPreferences searchSettings;
    private final SharedPreferences downloadSettings;
    private final ReaderSourceHealth searchHealth;
    private final ReaderSourceDiagnostics sourceDiagnostics;
    private final String searchHealthKey;
    private final FrameLayout root, pageHost;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newFixedThreadPool(4);
    private final ReaderSearchQueue searches = ReaderSearchQueue.forSources();
    private final ReaderSearchQueue alternateSearches = new ReaderSearchQueue(6);
    private final ReaderSearchQueue sourceChecks = new ReaderSearchQueue(4, 18_000);
    private final ReaderWorkMatcher workMatcher = new ReaderWorkMatcher(ReaderSection::foldSearchChinese);
    private final ExecutorService navigation = Executors.newSingleThreadExecutor();
    private final ExecutorService imports = Executors.newSingleThreadExecutor();
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private final Map<String, DownloadTask> downloadJobs = new LinkedHashMap<>();
    private final List<AlertDialog> dialogs = new ArrayList<>();
    private ResultSourcePicker resultSourcePicker;
    private final LruCache<String, Bitmap> imageCache = new LruCache<>(18 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private final Map<String, List<Consumer<Bitmap>>> imageRequests = new HashMap<>();
    private final Runnable saveSoon = this::savePosition;
    private final Runnable updateSearchTime = this::tickSearchTime;
    private final Runnable saveSearchHealth = this::persistSearchHealth;

    private Screen screen = Screen.HOME;
    private int viewEpoch;
    private boolean destroyed, editingSources, aggregatingSources;
    private String sampleAsset = "", query = "";
    private SearchState searchState;
    private SearchFeed searchFeed;
    private boolean searchHealthDirty;
    private String sourceFilter = "", aggregationMessage = "";
    private int diagnosticFilter;
    private ReaderAggregation.Result aggregationResult;
    private Future<?> pageTask;
    private LinearLayout scene, feed;
    private View shelfUnderline;
    private TextView shelfShortcut;
    private int shelfFilter, shelfOrder, shelfColumns = 3, displayedShelfColumns = 1;
    private boolean shelfGrid;
    private String shelfGroup = "";
    private EditText search, sourceFilterInput;
    private ListView feedList, sourceList;
    private TextView aggregationStatus;
    private MangaReadingView mangaReader;
    private NovelReadingView novelReader;
    private int cleanupRevision;
    private LinearLayout novelFind;
    private LinearLayout readerHeader, readerTools;
    private TextView undoSeek;
    private ReaderProgress.Position seekOrigin;
    private TextView sourceCount, readingStatus;
    private SeekBar readerSeek;
    private TextView downloadStatus;
    private ReaderEngine.Book book;
    private List<ReaderEngine.Chapter> chapters = Collections.emptyList();
    private boolean downloadedChaptersOnly;
    private ReaderEngine.Page loadedPage;
    private int chapterIndex = -1;
    private boolean controlsVisible, seekingReader, savePending, returnToHistory;
    private String historyVisitBook = "";

    ReaderSection(Activity host, boolean manga, int pickerRequestCode, ReaderCache offline,
                  Runnable onScreenChanged) {
        this.host = host;
        this.manga = manga;
        this.pickerRequestCode = pickerRequestCode;
        this.offline = offline;
        localBooks = new ReaderLocalBooks(new File(host.getFilesDir(), "reader-local"));
        screenChanged = onScreenChanged;
        kind = manga ? ReaderSources.COMIC : ReaderSources.NOVEL;
        sectionName = manga ? "漫画" : "小说";
        sources = AndroidReaderSources.get(host);
        progress = new ReaderProgress(host, manga);
        readerSettings = host.getSharedPreferences("reader_display", Context.MODE_PRIVATE);
        shelfFilter = shelfSetting("filter", SHELF_FILTERS.length);
        shelfOrder = shelfSetting("order", SHELF_ORDERS.length);
        shelfGroup = readerSettings.getString(shelfSettingKey("group"), "");
        shelfGrid = readerSettings.getBoolean(shelfSettingKey("grid"), true);
        searchSettings = host.getSharedPreferences("reader_source_health", Context.MODE_PRIVATE);
        downloadSettings = host.getSharedPreferences("reader_download_jobs", Context.MODE_PRIVATE);
        searchHealthKey = manga ? "manga" : "novel";
        searchHealth = new ReaderSourceHealth(searchSettings.getString(searchHealthKey, ""));
        sourceDiagnostics = new ReaderSourceDiagnostics(searchSettings.getString("checks_" + searchHealthKey, ""));
        restoreDownloadTasks();
        root = new FrameLayout(host) {
            @Override public void onVisibilityAggregated(boolean visible) {
                super.onVisibilityAggregated(visible);
                if (visible) publishSearch();
            }
        };
        pageHost = new FrameLayout(host);
        root.addView(pageHost, new FrameLayout.LayoutParams(M, M));
        showHome();
    }

    View view() { return root; }

    boolean isReading() { return screen == Screen.READER; }

    boolean immersive() { return isReading() && !controlsVisible && readerSettings.getBoolean("immersive", true); }

    boolean keepAwake() { return isReading() && readerSettings.getBoolean("keep_awake", true); }

    boolean handleVolumeKey(int keyCode) { return novelReader != null && novelReader.handleVolumeKey(keyCode); }

    void pause() {
        savePosition();
        if (novelReader != null) novelReader.stopSpeech();
        PageMotion.reset(pageHost);
        if (feed != null) PageMotion.reset(feed);
        persistSearchHealth();
    }

    void saveState(Bundle state) {
        if (screen == Screen.HISTORY || returnToHistory && screen == Screen.CHAPTERS) {
            state.putBoolean("reader_history", true);
            return;
        }
        if (book == null || !isReading() || chapterIndex < 0) return;
        savePosition();
        state.putString("reader_book", ReaderProgress.key(book));
        state.putString("reader_source", book.source.id);
        state.putString("reader_title", book.title);
        state.putString("reader_author", book.author);
        state.putString("reader_url", book.url);
        state.putString("reader_toc", book.tocUrl);
        state.putString("reader_chapter", chapters.get(chapterIndex).url);
        state.putBoolean("reader_controls", controlsVisible);
        state.putBoolean("reader_from_history", returnToHistory);
        state.putBoolean("reader_visit_recorded", ReaderProgress.key(book).equals(historyVisitBook));
    }

    void restoreState(Bundle state) {
        if (state.getBoolean("reader_history")) {
            showHistory();
            return;
        }
        String key = state.getString("reader_book", "");
        if (key.isEmpty()) return;
        ReaderProgress.Entry entry = progress.get(key);
        ReaderSources.Source source = savedSource(entry == null
                ? state.getString("reader_source", "") : entry.sourceId);
        if (source == null) return;
        controlsVisible = state.getBoolean("reader_controls", true);
        returnToHistory = state.getBoolean("reader_from_history");
        book = entry == null ? new ReaderEngine.Book(source, state.getString("reader_title", ""),
                state.getString("reader_author", ""), "", "", state.getString("reader_url", ""),
                state.getString("reader_toc", "")) : entry.toBook(source);
        String chapter = state.getString("reader_chapter", "");
        showContents(true, chapter);
        if (state.getBoolean("reader_visit_recorded",
                entry != null && entry.historyTime > 0 && chapter.equals(entry.chapterUrl))) {
            historyVisitBook = key;
        }
    }

    void onSelected() {
        if (screen == Screen.SOURCES) showSources();
        else if (screen == Screen.HOME) {
            updateSourceCount();
            renderFeed();
        }
    }

    void setSampleSource(String assetPath) {
        sampleAsset = assetPath;
        if (screen == Screen.HOME) renderFeed();
    }

    boolean onBackPressed() {
        hideKeyboard();
        switch (screen) {
            case READER:
                if (novelFind != null) {
                    closeNovelFind();
                    return true;
                }
                savePosition();
                controlsVisible = false;
                showContents(false);
                return true;
            case CHAPTERS:
                leaveCatalogue();
                return true;
            case SOURCES:
            case DOWNLOADS:
            case HISTORY:
                showHome();
                return true;
            case HOME:
                if (searchState == null) return false;
                showShelf();
                return true;
        }
        return false;
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (isDiagnosticExportRequest(requestCode)) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                writeDiagnosticsReport(data.getData());
            }
            return;
        }
        if (isLocalRequest(requestCode)) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                importLocal(data.getData(), requestCode == LOCAL_MANGA_DIRECTORY);
            }
            return;
        }
        if (requestCode != pickerRequestCode || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        previewSources(() -> AndroidReaderSources.prepareFile(host, uri, ReaderSources.ALL));
    }

    static boolean isLocalRequest(int requestCode) {
        return requestCode == LOCAL_NOVEL_FILE || requestCode == LOCAL_MANGA_FILE
                || requestCode == LOCAL_MANGA_DIRECTORY;
    }

    static boolean isDiagnosticExportRequest(int requestCode) {
        return requestCode == NOVEL_DIAGNOSTICS_EXPORT || requestCode == COMIC_DIAGNOSTICS_EXPORT;
    }

    void destroy() {
        savePosition();
        PageMotion.reset(pageHost);
        if (feed != null) PageMotion.reset(feed);
        if (mangaReader != null) mangaReader.dispose();
        clearSearch();
        persistSearchHealth();
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        imports.shutdown();
        searches.close();
        alternateSearches.close();
        sourceChecks.close();
        network.shutdownNow();
        navigation.shutdownNow();
        images.shutdownNow();
        downloads.shutdownNow();
        imageCache.evictAll();
        imageRequests.clear();
    }

    private void reset(Screen destination) {
        if (resultSourcePicker != null) resultSourcePicker.dialog.dismiss();
        Screen previous = screen;
        boolean wasReading = isReading();
        screen = destination;
        viewEpoch++;
        if (pageTask != null) { pageTask.cancel(true); pageTask = null; }
        main.removeCallbacks(saveSoon);
        savePending = false;
        if (mangaReader != null) mangaReader.dispose();
        mangaReader = null;
        novelReader = null;
        novelFind = null;
        loadedPage = null;
        if (destination != Screen.READER) historyVisitBook = "";
        seekingReader = false;
        seekOrigin = null;
        undoSeek = null;
        sourceFilterInput = null;
        aggregationStatus = null;
        if (previous == Screen.HOME && feed != null) PageMotion.reset(feed);
        pageHost.removeAllViews();
        root.setBackgroundColor(isReading() ? READER_BG : BG);
        scene = column();
        pageHost.addView(scene, new FrameLayout.LayoutParams(M, M));
        if (wasReading != isReading() && screenChanged != null) screenChanged.run();
        if (previous != destination) {
            boolean returning = destination == Screen.HOME || wasReading && destination == Screen.CHAPTERS
                    || destination == Screen.HISTORY && previous == Screen.CHAPTERS;
            PageMotion.enter(pageHost, returning);
        }
    }

    private void showHome() {
        returnToHistory = false;
        reset(Screen.HOME);
        scene.addView(buildHomeHeading(), lp(M, W));

        search = input(manga ? "搜索漫画或作者" : "搜索书名或作者", false);
        search.setText(query);
        LinearLayout searchBar = SectionControls.search(search, this::beginSearch);
        LinearLayout.LayoutParams barParams = lp(M, W);
        barParams.topMargin = dp(6);
        scene.addView(searchBar, barParams);

        LinearLayout shortcuts = row();
        FrameLayout shelfTab = new FrameLayout(host);
        shelfShortcut = homeAction("书架", true, this::showShelf);
        shelfTab.addView(shelfShortcut, new FrameLayout.LayoutParams(M, M));
        shelfUnderline = new View(host);
        shelfUnderline.setBackgroundColor(textTone(BERRY));
        shelfTab.addView(shelfUnderline, new FrameLayout.LayoutParams(dp(24), dp(2), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        shortcuts.addView(shelfTab, weight(48));
        shortcuts.addView(homeAction("发现", false, this::chooseExplore), weight(48));
        TextView local = homeAction("本地", false, this::chooseLocal);
        local.setContentDescription("导入本地" + sectionName);
        shortcuts.addView(local, weight(48));
        TextView download = homeAction("下载", false, this::showDownloads);
        download.setContentDescription("离线下载");
        shortcuts.addView(download, weight(48));
        TextView history = homeAction("历史", false, this::showHistory);
        history.setContentDescription(sectionName + "历史记录");
        shortcuts.addView(history, weight(48));
        scene.addView(shortcuts, lp(M, 48));
        scene.addView(homeRule(), new LinearLayout.LayoutParams(M, dp(1)));

        feed = column();
        scene.addView(feed, new LinearLayout.LayoutParams(M, 0, 1));
        feed.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int columns = Math.max(2, Math.min(5, (right - left) / dp(110)));
            if (right <= left || columns == shelfColumns) return;
            shelfColumns = columns;
            if (shelfGrid && searchState == null && feedList != null) view.post(() -> {
                if (!destroyed && view == feed && shelfGrid && searchState == null
                        && displayedShelfColumns != shelfColumns) renderFeed();
            });
        });
        scene.setFocusableInTouchMode(true);
        scene.requestFocus();
        renderFeed();
    }

    private View buildHomeHeading() {
        sourceCount = SectionControls.action(host, "源", true, this::showSources);
        updateSourceCount();
        return SectionControls.heading(host, sectionName, sourceCount);
    }

    private TextView homeAction(String label, boolean accented, Runnable click) {
        TextView control = text(label, 14, accented ? BERRY : MUTED, accented);
        control.setSingleLine();
        control.setEllipsize(TextUtils.TruncateAt.END);
        control.setGravity(Gravity.CENTER);
        control.setBackground(button(host, android.graphics.Color.TRANSPARENT, 8, 0));
        control.setFocusable(true);
        control.setOnClickListener(view -> click.run());
        return control;
    }

    private View homeRule() {
        View rule = new View(host);
        rule.setBackgroundColor(LINE);
        rule.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return rule;
    }

    private void showShelf() {
        boolean wasShowingResults = searchState != null;
        hideKeyboard();
        if (search != null) search.setText("");
        query = "";
        clearSearch();
        renderFeed();
        if (wasShowingResults) PageMotion.enter(feed, true);
    }

    private void updateSourceCount() {
        if (sourceCount != null) {
            sourceCount.setContentDescription("管理源 · "
                    + host.getString(R.string.reader_source_count, enabledSources().size(), "源"));
        }
    }

    private void renderFeed() {
        if (screen != Screen.HOME || feed == null) return;
        boolean onShelf = searchState == null;
        shelfShortcut.setSelected(onShelf);
        shelfShortcut.setTextColor(textTone(onShelf ? BERRY : MUTED));
        shelfUnderline.setVisibility(onShelf ? View.VISIBLE : View.INVISIBLE);
        if (searchState != null) {
            if (searchFeed == null || searchFeed.state != searchState || searchFeed.parent != feed) {
                SearchFeed previous = searchFeed != null && searchFeed.state == searchState ? searchFeed : null;
                feed.removeAllViews();
                feedList = null;
                searchFeed = new SearchFeed(feed, searchState, previous);
            }
            searchFeed.refresh();
            scheduleSearchTime();
            return;
        }
        searchFeed = null;
        int priorIndex = feedList == null ? 0 : feedList.getFirstVisiblePosition();
        int priorHeaders = feedList == null ? 0 : feedList.getHeaderViewsCount();
        int priorBook = Math.max(0, priorIndex - priorHeaders) * displayedShelfColumns;
        View first = feedList == null ? null : feedList.getChildAt(0);
        int priorTop = first == null ? 0 : first.getTop();
        feed.removeAllViews();
        feedList = null;
        if (!shelfGroup.isEmpty() && !progress.groups().containsKey(shelfGroup)) resetShelfGroup("");
        List<ReaderProgress.Entry> entries = progress.shelf();
        List<Cell> cells = shelfCells(entries);
        if (cells.isEmpty()) {
            feed.addView(shelfHeading(), lp(M, W));
            if (shelfFilter != 0 || !shelfGroup.isEmpty()) {
                feed.addView(emptyShelf(null, "这一栏没有作品", "", "显示全部",
                        () -> { resetShelfGroup(""); updateShelfSetting("filter", 0); }), lp(M, W));
                return;
            }
            boolean hasSources = !enabledSources().isEmpty();
            LinearLayout emptyBody = column();
            emptyBody.addView(emptyShelf(MyGoEmotes.icon(host, R.drawable.mygo_peek, 34),
                    "书架还没有作品", hasSources ? "" : "添加源即可搜索。",
                    hasSources ? "搜索" : "添加源",
                    hasSources ? this::focusSearch : this::showSources), lp(M, W));
            if (!sampleAsset.isEmpty() && !hasSources) attachTrySample(emptyBody);
            feed.addView(scroll(emptyBody), new LinearLayout.LayoutParams(M, 0, 1));
            return;
        }
        displayedShelfColumns = shelfGrid ? shelfColumns : 1;
        feedList = shelfGrid ? newList() : paperList();
        if (shelfGrid) feedList.setPadding(0, 0, 0, dp(6));
        View recent = resumeEntry(entries);
        if (recent != null) feedList.addHeaderView(recent, null, false);
        feedList.addHeaderView(shelfHeading(), null, false);
        if (shelfGrid) {
            bindShelfGrid(feedList, cells, displayedShelfColumns);
        } else {
            bindList(feedList, cells, true, true, true);
            feedList.setOnItemLongClickListener((parent, child, position, id) -> {
                Cell cell = (Cell) parent.getItemAtPosition(position);
                if (cell == null || cell.onLongClick == null) return false;
                cell.onLongClick.run();
                return true;
            });
        }
        feed.addView(feedList, new LinearLayout.LayoutParams(M, 0, 1));
        if (priorIndex >= priorHeaders && priorHeaders != 0) {
            priorIndex = feedList.getHeaderViewsCount() + priorBook / displayedShelfColumns;
        }
        if (priorIndex < feedList.getCount()) feedList.setSelectionFromTop(priorIndex, priorTop);
    }

    private void bindShelfGrid(ListView list, List<Cell> books, int columns) {
        list.setItemsCanFocus(true);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return (books.size() + columns - 1) / columns; }
            @Override public Cell getItem(int position) { return books.get(position * columns); }
            @Override public long getItemId(int position) { return position; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout tiles = recycled instanceof LinearLayout ? (LinearLayout) recycled : row();
                if (tiles.getChildCount() == 0) {
                    tiles.setGravity(Gravity.TOP);
                    tiles.setPadding(0, dp(4), 0, dp(9));
                    for (int i = 0; i < columns; i++) {
                        LinearLayout.LayoutParams layout = weight(W);
                        if (i < columns - 1) layout.setMarginEnd(dp(12));
                        tiles.addView(new ShelfTile(), layout);
                    }
                }
                int first = position * columns;
                for (int i = 0; i < columns; i++) {
                    ShelfTile tile = (ShelfTile) tiles.getChildAt(i);
                    tile.bind(first + i < books.size() ? books.get(first + i) : null);
                }
                return tiles;
            }
        });
    }

    private View resumeEntry(List<ReaderProgress.Entry> entries) {
        for (ReaderProgress.Entry entry : entries) {
            if (entry.chapterUrl.isEmpty()) continue;
            ReaderSources.Source source = savedSource(entry.sourceId);
            if (source == null) continue;
            LinearLayout section = column();
            section.setPadding(0, dp(10), 0, dp(2));
            TextView heading = text("继续阅读", 13, MUTED, true);
            markHeading(heading);
            section.addView(heading, lp(M, W));
            Runnable resume = () -> selectBook(entry.toBook(source), entry.chapterUrl);
            CellView item = new CellView(true);
            item.bind(new Cell(entry.title, entry.chapterTitle, "", entry.cover, source, resume));
            item.setBackground(button(host, android.graphics.Color.TRANSPARENT, 8, 0));
            item.setFocusable(true);
            item.setOnClickListener(view -> resume.run());
            section.addView(item, lp(M, W));
            section.addView(homeRule(), new LinearLayout.LayoutParams(M, dp(1)));
            return section;
        }
        return null;
    }

    private void showHistory() {
        hideKeyboard();
        returnToHistory = false;
        reset(Screen.HISTORY);
        List<ReaderProgress.Entry> entries = progress.history();
        LinearLayout header = topBar("历史记录", sectionName, this::showHome);
        if (!entries.isEmpty()) {
            header.addView(action("清空", false, () -> showDialog(new AlertDialog.Builder(host)
                    .setTitle("清空" + sectionName + "历史？")
                    .setMessage("书架、书签、离线内容和阅读进度会保留。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("清空历史", (dialog, which) -> {
                        progress.clearHistory();
                        showHistory();
                    }))), lp(W, 44));
        }
        scene.addView(header, lp(M, W));
        if (entries.isEmpty()) {
            scene.addView(emptyCard("暂无阅读历史", "打开章节后，阅读记录会显示在这里。", "返回书架", this::showHome), lp(M, W));
            return;
        }

        scene.addView(note("点条目继续阅读，长按可移除。"), lp(M, W));
        List<Cell> cells = new ArrayList<>();
        for (ReaderProgress.Entry entry : entries) {
            ReaderSources.Source source = savedSource(entry.sourceId);
            String subtitle = HistoryDisplay.summary(host, "阅读", entry.historyCount, entry.historyTime,
                    entry.historyCountPartial) + "\n" + entry.chapterTitle;
            cells.add(new Cell(entry.title, subtitle, null, entry.cover, source, () -> {
                if (source == null) {
                    toast("请重新导入此作品的源");
                    return;
                }
                selectBook(entry.toBook(source), entry.chapterUrl, true);
            }, () -> showDialog(new AlertDialog.Builder(host).setTitle(entry.title)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除记录", (dialog, which) -> {
                        progress.hideHistory(entry);
                        showHistory();
                    })), true));
        }
        ListView list = newList();
        bindList(list, cells);
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            cells.get(position).onLongClick.run();
            return true;
        });
        scene.addView(list, new LinearLayout.LayoutParams(M, 0, 1));
    }

    private View emptyShelf(ImageView emote, String title, String detail, String cta, Runnable click) {
        LinearLayout content = column();
        content.setPadding(0, dp(14), 0, dp(7));
        LinearLayout headingRow = row();
        headingRow.setGravity(Gravity.CENTER_VERTICAL);
        if (emote != null) {
            LinearLayout.LayoutParams imageSpace = lp(34, 34);
            imageSpace.setMarginEnd(dp(8));
            headingRow.addView(emote, imageSpace);
        }
        TextView heading = text(title, 15, MUTED, false);
        markHeading(heading);
        headingRow.addView(heading, weight(W));
        content.addView(headingRow, lp(M, W));
        if (!detail.isEmpty()) {
            TextView message = text(detail, 13, MUTED, false);
            message.setPadding(0, dp(5), 0, 0);
            content.addView(message, lp(M, W));
        }
        TextView action = homeAction(cta, true, click);
        action.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        action.setMinWidth(dp(48));
        content.addView(action, lp(W, 48));
        return content;
    }

    private TextView feedHeading(String title) {
        TextView feedLabel = text(title, 18, FG, true);
        feedLabel.setPadding(dp(16), dp(9), dp(16), dp(6));
        feedLabel.setMaxLines(1);
        feedLabel.setEllipsize(TextUtils.TruncateAt.END);
        markHeading(feedLabel);
        return feedLabel;
    }

    private static final class SearchNotice {
        final ReaderSources.Source source;
        final String reason;
        final int page;

        SearchNotice(ReaderSources.Source source, String reason) {
            this(source, reason, 1);
        }

        SearchNotice(ReaderSources.Source source, String reason, int page) {
            this.source = source;
            this.reason = reason;
            this.page = page;
        }
    }

    private static final class SearchOutcome {
        final ReaderSources.Source source;
        final List<ReaderEngine.Book> books;
        final String[] groupKeys;
        final int[] scores;
        final Exception error;

        SearchOutcome(ReaderSources.Source source, List<ReaderEngine.Book> books, Exception error,
                      ReaderSearchRanking ranking, ReaderWorkMatcher matcher) {
            this.source = source;
            this.books = books == null ? Collections.emptyList() : books;
            this.error = error;
            int count = matcher == null ? 0 : this.books.size();
            groupKeys = new String[count];
            scores = new int[count];
            for (int i = 0; i < scores.length && !Thread.currentThread().isInterrupted(); i++) {
                ReaderEngine.Book book = this.books.get(i);
                groupKeys[i] = ranking == null ? matcher.discoveryKey(book) : matcher.searchTitleKey(book);
                scores[i] = ranking == null ? 1 : ranking.score(book.title, book.author);
            }
        }
    }

    private static final class SearchBatch {
        final int inFlight;
        final List<SearchOutcome> outcomes;

        SearchBatch(int inFlight, List<SearchOutcome> outcomes) {
            this.inFlight = inFlight;
            this.outcomes = outcomes;
        }
    }

    private static final class SearchAttempt {
        final int total;
        final SearchPass pass;
        final Map<String, Integer> pages;
        final long startedAt = SystemClock.elapsedRealtime();
        private final Set<String> inFlight = new HashSet<>();
        private List<SearchOutcome> outcomes = new ArrayList<>();
        private boolean accepting = true, deliveryQueued;
        int completed, active;
        long finishedAt;
        boolean stopped;
        ReaderSearchQueue.Run run;

        SearchAttempt(Map<String, Integer> pages, SearchPass pass) {
            this.pages = pages;
            this.total = pages.size();
            this.pass = pass;
        }

        boolean running() { return !stopped && completed < total; }

        synchronized boolean began(ReaderSources.Source source) {
            if (!accepting) return false;
            inFlight.add(source.id);
            return requestDelivery();
        }

        synchronized boolean finished(SearchOutcome outcome) {
            if (!accepting) return false;
            inFlight.remove(outcome.source.id);
            outcomes.add(outcome);
            return requestDelivery();
        }

        private boolean requestDelivery() {
            if (deliveryQueued) return false;
            deliveryQueued = true;
            return true;
        }

        synchronized SearchBatch drain(boolean close) {
            if (close) {
                accepting = false;
                inFlight.clear();
            }
            SearchBatch batch = new SearchBatch(inFlight.size(), outcomes);
            outcomes = new ArrayList<>();
            deliveryQueued = false;
            return batch;
        }
    }

    private static final class SearchState {
        final String title;
        final SearchMode mode;
        final int total;
        final SearchAction action;
        final String discoveryRequest;
        final ReaderSearchRanking ranking;
        final List<SearchNotice> excluded;
        final Map<String, SearchNotice> deferred;
        final Map<String, Integer> unfinished = new LinkedHashMap<>();
        final Map<String, Integer> morePages = new LinkedHashMap<>();
        final Map<String, SearchNotice> failures = new LinkedHashMap<>();
        final Map<String, Set<String>> resultUrls = new HashMap<>();
        final Map<String, SearchResultGroup> results = new LinkedHashMap<>();
        final Map<String, Boolean> sourceMatches = new HashMap<>();
        int withMatches, withoutMatches, revision;
        SearchAttempt attempt;

        SearchState(String title, SearchMode mode, List<ReaderSources.Source> initial, SearchAction action,
                    List<SearchNotice> excluded, Map<String, SearchNotice> deferred, ReaderSearchRanking ranking,
                    String discoveryRequest) {
            this.title = title;
            this.mode = mode;
            total = initial.size() + deferred.size();
            this.action = action;
            this.discoveryRequest = discoveryRequest;
            this.ranking = ranking;
            this.excluded = excluded;
            this.deferred = new LinkedHashMap<>(deferred);
            for (ReaderSources.Source source : initial) unfinished.put(source.id, 1);
        }

        boolean canContinue() { return !unfinished.isEmpty() || !deferred.isEmpty(); }

        String request(ReaderSources.Source source) {
            return discoveryRequest == null ? source.searchUrl : discoveryRequest;
        }

        void mark(ReaderSources.Source source, boolean matched) {
            Boolean previous = sourceMatches.get(source.id);
            if (previous == null) {
                sourceMatches.put(source.id, matched);
                if (matched) withMatches++;
                else withoutMatches++;
            } else if (!previous && matched) {
                sourceMatches.put(source.id, true);
                withMatches++;
                withoutMatches--;
            }
        }
    }

    private static final class SearchResultGroup {
        final List<ReaderEngine.Book> editions = new ArrayList<>();
        final Set<String> sourceIds = new HashSet<>();
        int score = -1;
        ReaderEngine.Book featured;
        Cell cell;

        void add(ReaderEngine.Book book, int score) {
            editions.add(book);
            sourceIds.add(book.source.id);
            if (score > this.score || (score == this.score && featured.cover.isEmpty() && !book.cover.isEmpty())) {
                this.score = score;
                featured = book;
            }
            cell = null;
        }
    }

    private final class SearchFeed {
        final LinearLayout parent;
        final SearchState state;
        final ListView results;
        private final List<Cell> displayed = new ArrayList<>();
        private final List<SearchResultGroup> displayedGroups = new ArrayList<>();
        private final BaseAdapter adapter;
        private final TextView phase, counts, activity, details, resume, retry, stop, more;
        private final LinearLayout nextActions;
        private final ProgressBar bar;
        private final View empty;
        private final ImageView emptySearchEmote;
        private int revision = -1;

        SearchFeed(LinearLayout parent, SearchState state, SearchFeed previous) {
            this.parent = parent;
            this.state = state;
            parent.addView(feedHeading(state.title), lp(M, W));
            LinearLayout status = column();
            status.setPadding(0, dp(1), 0, dp(8));
            LinearLayout headline = row();
            headline.setGravity(Gravity.CENTER_VERTICAL);
            headline.setMinimumHeight(dp(42));
            phase = text("", 15, BERRY, true);
            headline.addView(phase, weight(W));
            details = homeAction("来源详情", false, () -> showSearchDetails(state));
            details.setMinWidth(dp(64));
            headline.addView(details, lp(W, 42));
            stop = homeAction("停止", true, ReaderSection.this::stopSearch);
            stop.setMinWidth(dp(48));
            stop.setContentDescription("停止" + state.mode.label);
            LinearLayout.LayoutParams stopSpace = lp(W, 42);
            stopSpace.setMarginStart(dp(5));
            headline.addView(stop, stopSpace);
            status.addView(headline, lp(M, W));

            bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(false);
            bar.setProgressTintList(ColorStateList.valueOf(BERRY));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(SOFT_PINK));
            bar.setContentDescription("来源处理进度");
            status.addView(bar, lp(M, 7));

            counts = text("", 12, MUTED, false);
            counts.setPadding(0, dp(6), 0, 0);
            counts.setMaxLines(3);
            status.addView(counts, lp(M, W));
            activity = text("", 12, MUTED, false);
            activity.setPadding(0, dp(3), 0, 0);
            activity.setMaxLines(2);
            status.addView(activity, lp(M, W));

            nextActions = row();
            nextActions.setPadding(0, dp(7), 0, 0);
            resume = homeAction("继续" + state.mode.label, true, () -> continueSearch(state));
            addEqual(nextActions, resume, false);
            retry = homeAction("重试失败", false, () -> retryFailed(state));
            addEqual(nextActions, retry, true);
            more = homeAction("加载更多", false, () -> resumeSearch(state, SearchPass.MORE));
            addEqual(nextActions, more, true);
            status.addView(nextActions, lp(M, W));
            parent.addView(status, lp(M, W));
            parent.addView(homeRule(), new LinearLayout.LayoutParams(M, dp(1)));

            emptySearchEmote = state.total == 0 ? null : MyGoEmotes.icon(host, R.drawable.mygo_look, 34);
            empty = state.total == 0
                    ? emptyShelf(null, "没有可搜索的源", "请启用带搜索入口的源。",
                            "管理源", ReaderSection.this::showSources)
                    : emptyShelf(emptySearchEmote, "尚无结果", "", "更换关键词",
                            ReaderSection.this::focusSearch);
            parent.addView(empty, lp(M, W));
            results = paperList();
            bindList(results, displayed, false, true);
            adapter = (BaseAdapter) results.getAdapter();
            parent.addView(results, new LinearLayout.LayoutParams(M, 0, 1));
            refreshResults(previous == null ? this : previous);
        }

        private void refreshResults(SearchFeed oldFeed) {
            if (revision == state.revision) return;
            revision = state.revision;
            int index = oldFeed.results.getFirstVisiblePosition();
            View first = oldFeed.results.getChildAt(0);
            int offset = first == null ? 0 : first.getTop() - oldFeed.results.getPaddingTop();
            SearchResultGroup anchor = (index > 0 || offset < 0) && index < oldFeed.displayedGroups.size()
                    ? oldFeed.displayedGroups.get(index) : null;
            displayed.clear();
            displayedGroups.clear();
            displayedGroups.addAll(state.results.values());
            displayedGroups.sort(Comparator.comparingInt((SearchResultGroup group) -> group.score).reversed());
            for (SearchResultGroup group : displayedGroups) {
                if (group.cell == null) {
                    ReaderEngine.Book work = group.featured;
                    String badge = group.sourceIds.size() + " 个来源";
                    Runnable select = () -> showResultSources(state, group);
                    group.cell = state.mode == SearchMode.SEARCH
                            ? new Cell(work.title, "", badge, work.cover, work.source, select)
                            : bookCell(work, group.sourceIds.size() > 1 ? badge
                                    : group.editions.size() > 1 ? group.editions.size() + " 个条目" : null, select);
                }
                displayed.add(group.cell);
            }
            adapter.notifyDataSetChanged();
            int position = anchor == null ? 0 : displayedGroups.indexOf(anchor);
            results.setSelectionFromTop(Math.max(0, position), anchor != null && position >= 0 ? offset : 0);
        }

        void refresh() {
            refreshResults(this);
            SearchAttempt attempt = state.attempt;
            boolean running = attempt.running();
            String label = attempt.pass == SearchPass.RETRY ? "重试"
                    : attempt.pass == SearchPass.CONTINUE ? "继续" + state.mode.label
                    : attempt.pass == SearchPass.MORE ? "加载更多"
                    : !state.deferred.isEmpty() ? "优先搜索" : state.mode.label;
            String status = running ? label + "中" : attempt.stopped ? "已停止" : label + "完成";
            if (attempt.total == 0) status = state.deferred.isEmpty() ? "无可用来源" : "来源已暂缓";
            phase.setText(attempt.total == 0 ? status : status + "  " + attempt.completed + " / " + attempt.total);
            int progressColor = !running && !attempt.stopped && state.withMatches > 0 ? DEEP_MINT : BERRY;
            if (phase.getCurrentTextColor() != progressColor) {
                phase.setTextColor(textTone(progressColor));
                bar.setProgressTintList(ColorStateList.valueOf(progressColor));
            }
            bar.setMax(Math.max(1, attempt.total));
            bar.setProgress(attempt.completed);
            bar.setVisibility(attempt.total > 0 ? View.VISIBLE : View.GONE);
            stop.setVisibility(running ? View.VISIBLE : View.GONE);
            boolean hasFailures = !state.failures.isEmpty();
            details.setVisibility(hasFailures || !state.deferred.isEmpty() || !state.excluded.isEmpty()
                    ? View.VISIBLE : View.GONE);
            boolean canContinue = state.canContinue();
            boolean canLoadMore = !state.morePages.isEmpty();
            nextActions.setVisibility(!running && (canContinue || hasFailures || canLoadMore) ? View.VISIBLE : View.GONE);
            resume.setVisibility(!running && canContinue ? View.VISIBLE : View.GONE);
            retry.setVisibility(!running && hasFailures ? View.VISIBLE : View.GONE);
            more.setVisibility(!running && canLoadMore ? View.VISIBLE : View.GONE);
            String skipped = "";
            if (!state.deferred.isEmpty()) skipped = "暂缓 " + state.deferred.size();
            if (!state.excluded.isEmpty()) skipped = join(skipped, "未参与 " + state.excluded.size());
            counts.setVisibility(attempt.total > 0 || !skipped.isEmpty() ? View.VISIBLE : View.GONE);
            counts.setText(attempt.total == 0 ? skipped : (state.ranking == null ? "有结果 " : "有匹配 ")
                    + state.withMatches + " · 无匹配 " + state.withoutMatches + " · 失败 "
                    + state.failures.size() + " · 作品 " + state.results.size()
                    + (skipped.isEmpty() ? "" : "\n" + skipped));
            activity.setVisibility(attempt.total > 0 ? View.VISIBLE : View.GONE);
            refreshActivity();
            if (emptySearchEmote != null) {
                emptySearchEmote.setVisibility(!attempt.stopped && state.withoutMatches > 0 ? View.VISIBLE : View.GONE);
            }
            empty.setVisibility(!running && displayed.isEmpty() ? View.VISIBLE : View.GONE);
            results.setVisibility(displayed.isEmpty() ? View.GONE : View.VISIBLE);
        }

        void refreshActivity() {
            SearchAttempt attempt = state.attempt;
            if (attempt.total == 0) return;
            long end = attempt.running() ? SystemClock.elapsedRealtime() : attempt.finishedAt;
            long seconds = Math.max(0, end - attempt.startedAt) / 1_000;
            String duration = seconds < 60 ? seconds + " 秒"
                    : (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
            String timing = "用时 " + duration;
            int remaining = attempt.total - attempt.completed;
            if (attempt.running()) {
                activity.setText("查询中 " + attempt.active + " / " + searches.capacity() + " · 排队 "
                        + Math.max(0, remaining - attempt.active) + " · " + timing);
            } else {
                if (attempt.stopped && attempt.pass == SearchPass.RETRY) timing += " · 未重试 " + remaining;
                if (!state.unfinished.isEmpty()) timing += " · 未处理 " + state.unfinished.size();
                activity.setText(timing);
            }
        }
    }

    private List<Cell> shelfCells(List<ReaderProgress.Entry> entries) {
        List<Cell> cells = new ArrayList<>();
        entries = new ArrayList<>(entries);
        if (shelfOrder == 0) {
            entries.sort(Comparator.comparing(entry -> !entry.favorite));
        } else if (shelfOrder >= 2) {
            java.text.Collator collator = java.text.Collator.getInstance(Locale.SIMPLIFIED_CHINESE);
            Comparator<ReaderProgress.Entry> byTitle = Comparator.comparing(entry -> entry.title, collator);
            if (shelfOrder == 2) entries.sort(byTitle);
            else entries.sort(Comparator.<ReaderProgress.Entry, Boolean>comparing(entry -> entry.author.isEmpty())
                    .thenComparing(entry -> entry.author, collator).thenComparing(byTitle));
        }
        for (ReaderProgress.Entry entry : entries) {
            if (!shelfGroup.isEmpty() && !shelfGroup.equals(entry.group)) continue;
            if (shelfFilter == 1 && !entry.favorite) continue;
            if (shelfFilter == 2 && entry.chapterUrl.isEmpty()) continue;
            if (shelfFilter == 3 && !ReaderLocalBooks.isSource(entry.sourceId, kind)) continue;
            cells.add(shelfCell(entry, entry.favorite));
        }
        return cells;
    }

    private LinearLayout shelfHeading() {
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(4), 0, 0);
        TextView heading = text("书架", 19, FG, true);
        heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        markHeading(heading);
        header.addView(heading, weight(W));
        TextView layout = homeAction(shelfGrid ? "列表" : "网格", false, () -> {
            shelfGrid = !shelfGrid;
            readerSettings.edit().putBoolean(shelfSettingKey("grid"), shelfGrid).apply();
            renderFeed();
        });
        layout.setContentDescription(shelfGrid ? "切换到列表视图" : "切换到网格视图");
        header.addView(layout, lp(52, 48));
        String groupName = progress.groups().getOrDefault(shelfGroup, "全部分组");
        TextView listSettings = homeAction("管理", false, () ->
                showDialog(new AlertDialog.Builder(host).setTitle("管理书架")
                        .setItems(new String[]{"筛选 · " + SHELF_FILTERS[shelfFilter],
                                "排序 · " + SHELF_ORDERS[shelfOrder], groupName, "整理分组", "检查更新", "已移出书架"},
                                (dialog, which) -> {
                                    switch (which) {
                                        case 0: chooseShelfSetting("书架筛选", "filter", SHELF_FILTERS, shelfFilter); break;
                                        case 1: chooseShelfSetting("书架排序", "order", SHELF_ORDERS, shelfOrder); break;
                                        case 2: chooseShelfGroup(); break;
                                        case 3: showGroupManagement(); break;
                                        case 4: checkShelfUpdates(); break;
                                        case 5: showRemovedShelfEntries(); break;
                                    }
                                }).setNegativeButton("关闭", null)));
        listSettings.setContentDescription("管理书架");
        header.addView(listSettings, lp(56, 48));
        return header;
    }

    private void chooseShelfGroup() {
        List<Map.Entry<String, String>> groups = new ArrayList<>(progress.groups().entrySet());
        String[] labels = new String[groups.size() + 1];
        labels[0] = "全部分组";
        int current = 0;
        for (int i = 0; i < groups.size(); i++) {
            labels[i + 1] = groups.get(i).getValue();
            if (groups.get(i).getKey().equals(shelfGroup)) current = i + 1;
        }
        showDialog(new AlertDialog.Builder(host).setTitle("书架分组")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    dialog.dismiss();
                    resetShelfGroup(which == 0 ? "" : groups.get(which - 1).getKey());
                    renderFeed();
                }).setNegativeButton("关闭", null));
    }

    private void resetShelfGroup(String id) {
        shelfGroup = id;
        readerSettings.edit().putString(shelfSettingKey("group"), id).apply();
        feedList = null;
    }

    private void showGroupManagement() {
        showDialog(new AlertDialog.Builder(host).setTitle("整理书架分组")
                .setItems(new String[]{"新建分组", "重命名或删除分组", "给作品分组"}, (dialog, which) -> {
                    if (which == 0) editGroupName(null, "", () -> renderFeed());
                    if (which == 1) chooseGroupToEdit();
                    if (which == 2) {
                        List<ReaderProgress.Entry> entries = progress.shelf();
                        if (entries.isEmpty()) { toast("书架还没有作品"); return; }
                        String[] labels = new String[entries.size()];
                        for (int i = 0; i < labels.length; i++) {
                            ReaderProgress.Entry entry = entries.get(i);
                            ReaderSources.Source source = savedSource(entry.sourceId);
                            labels[i] = join(entry.title, source == null ? entry.author : source.name);
                        }
                        showDialog(new AlertDialog.Builder(host).setTitle("选择作品")
                                .setItems(labels, (picker, index) -> assignGroup(entries.get(index)))
                                .setNegativeButton("关闭", null));
                    }
                }).setNegativeButton("关闭", null));
    }

    private void chooseGroupToEdit() {
        List<Map.Entry<String, String>> groups = new ArrayList<>(progress.groups().entrySet());
        if (groups.isEmpty()) { toast("还没有自定义分组"); return; }
        String[] names = new String[groups.size()];
        for (int i = 0; i < names.length; i++) names[i] = groups.get(i).getValue();
        showDialog(new AlertDialog.Builder(host).setTitle("选择分组")
                .setItems(names, (dialog, index) -> {
                    Map.Entry<String, String> group = groups.get(index);
                    showDialog(new AlertDialog.Builder(host).setTitle(group.getValue())
                            .setNeutralButton("删除", (action, which) -> confirmGroupDeletion(group))
                            .setPositiveButton("重命名", (action, which) ->
                                    editGroupName(group.getKey(), group.getValue(), this::renderFeed))
                            .setNegativeButton("取消", null));
                }).setNegativeButton("关闭", null));
    }

    private void confirmGroupDeletion(Map.Entry<String, String> group) {
        showDialog(new AlertDialog.Builder(host).setTitle("删除「" + group.getValue() + "」？")
                .setMessage("该组的作品会移到未分组，阅读进度会保留。")
                .setPositiveButton("删除分组", (dialog, which) -> {
                    progress.deleteGroup(group.getKey());
                    if (shelfGroup.equals(group.getKey())) resetShelfGroup("");
                    renderFeed();
                }).setNegativeButton("取消", null));
    }

    private void editGroupName(String id, String oldName, Runnable saved) {
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        EditText name = input("分组名称", false);
        name.setText(oldName);
        panel.addView(name, lp(M, 52));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle(id == null ? "新建分组" : "重命名分组")
                .setView(panel).setPositiveButton("保存", null).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                progress.saveGroup(id, name.getText().toString());
                dialog.dismiss();
                saved.run();
            } catch (IllegalArgumentException invalid) { name.setError(invalid.getMessage()); }
        });
    }

    private void assignGroup(ReaderProgress.Entry entry) {
        List<Map.Entry<String, String>> groups = new ArrayList<>(progress.groups().entrySet());
        String[] labels = new String[groups.size() + 1];
        labels[0] = "不分组";
        int current = 0;
        for (int i = 0; i < groups.size(); i++) {
            labels[i + 1] = groups.get(i).getValue();
            if (groups.get(i).getKey().equals(entry.group)) current = i + 1;
        }
        showDialog(new AlertDialog.Builder(host).setTitle("选择「" + entry.title + "」的分组")
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    progress.setGroup(entry, which == 0 ? "" : groups.get(which - 1).getKey());
                    dialog.dismiss();
                    renderFeed();
                }).setNeutralButton("新建分组", (dialog, which) -> editGroupName(null, "", () -> assignGroup(entry)))
                .setNegativeButton("取消", null));
    }

    private void chooseShelfSetting(String title, String key, String[] options, int current) {
        showDialog(new AlertDialog.Builder(host).setTitle(title)
                .setSingleChoiceItems(options, current, (dialog, value) -> {
                    dialog.dismiss();
                    updateShelfSetting(key, value);
                }).setNegativeButton("关闭", null));
    }

    private int shelfSetting(String key, int max) {
        int index = readerSettings.getInt(shelfSettingKey(key), 0);
        return index >= 0 && index < max ? index : 0;
    }

    private String shelfSettingKey(String key) {
        return "shelf_" + (manga ? "manga_" : "novel_") + key;
    }

    private static final class CheckedCatalogue {
        final String previousLatest;
        final List<ReaderEngine.Chapter> chapters;

        CheckedCatalogue(String previousLatest, List<ReaderEngine.Chapter> chapters) {
            this.previousLatest = previousLatest;
            this.chapters = chapters;
        }
    }

    private void checkShelfUpdates() {
        List<ReaderEngine.Book> targets = new ArrayList<>();
        for (ReaderProgress.Entry entry : progress.shelf()) {
            if (!entry.favorite || ReaderLocalBooks.isSource(entry.sourceId, kind)) continue;
            ReaderSources.Source source = sources.find(entry.sourceId);
            if (source != null && source.enabled) targets.add(entry.toBook(source));
        }
        if (targets.isEmpty()) { toast("没有可检查的在线收藏"); return; }
        int total = targets.size();
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        TextView status = text("正在检查 0 / " + total, 14, FG, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(status, lp(M, W));
        ProgressBar bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(total);
        bar.setProgressTintList(ColorStateList.valueOf(BERRY));
        panel.addView(bar, lp(M, 14));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("检查在线收藏更新")
                .setView(panel).setNegativeButton("停止", null));
        int[] counts = new int[3]; // completed, updated, failed
        ReaderSearchQueue.Run run = sourceChecks.start(targets, selected -> ReaderEngine.searchRoute(selected.source,
                firstNonempty(selected.tocUrl, selected.url)), selected -> {
                    List<ReaderEngine.Chapter> cached = offline.loadCatalogue(selected);
                    String last = cached == null || cached.isEmpty() ? "" : cached.get(cached.size() - 1).url;
                    List<ReaderEngine.Chapter> fresh = ReaderEngine.chapters(selected);
                    ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
                    if (budget != null) budget.check();
                    offline.storeCatalogue(selected, fresh);
                    return new CheckedCatalogue(last, fresh);
                }, selected -> {}, (selected, catalogue, error) -> main.post(() -> {
                    if (destroyed) return;
                    counts[0]++;
                    if (error != null || catalogue == null) counts[2]++;
                    else {
                        ReaderSources.Source source = sources.find(selected.source.id);
                        if (source != null && source.enabled && source.diagnosticRevision == selected.source.diagnosticRevision
                                && progress.recordCatalogue(selected, catalogue.previousLatest, catalogue.chapters)) counts[1]++;
                    }
                    if (!dialog.isShowing()) return;
                    status.setText((counts[0] == total ? "检查完成 " : "正在检查 ") + counts[0] + " / " + total
                            + "\n有更新 " + counts[1] + " · 失败 " + counts[2]);
                    bar.setProgress(counts[0]);
                    if (counts[0] == total) dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
                }), 14_000);
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            run.cancel();
            main.post(() -> { if (!destroyed && screen == Screen.HOME && searchState == null) renderFeed(); });
        });
    }

    private void updateShelfSetting(String key, int value) {
        readerSettings.edit().putInt(shelfSettingKey(key), value).apply();
        if (key.equals("filter")) shelfFilter = value;
        else shelfOrder = value;
        feedList = null;
        renderFeed();
    }

    private Cell shelfCell(ReaderProgress.Entry entry, boolean favorite) {
        ReaderSources.Source original = sources.find(entry.sourceId);
        ReaderSources.Source source = original == null ? savedSource(entry.sourceId) : original;
        boolean local = ReaderLocalBooks.isSource(entry.sourceId, kind);
        String subtitle = !entry.chapterTitle.isEmpty() ? "读到 " + entry.chapterTitle
                : firstNonempty(entry.author, local ? "本地文件" : original == null ? "来源已移除" : source.name);
        if (!entry.latestTitle.isEmpty()) subtitle = join("最新 " + entry.latestTitle, subtitle);
        String badge = local ? "本地" : entry.unread > 0 ? "未读 " + entry.unread
                : entry.newChapters > 0 ? "更新 " + entry.newChapters : favorite ? "已收藏"
                : entry.chapterUrl.isEmpty() ? "在书架" : "最近读过";
        return new Cell(entry.title, subtitle, badge, original == null ? "" : entry.cover,
                source, () -> {
                    if (source == null) {
                        toast("请重新导入此作品的源");
                        return;
                    }
                    selectBook(entry.toBook(source));
                }, () -> showShelfActions(entry));
    }

    private void showShelfActions(ReaderProgress.Entry entry) {
        showDialog(new AlertDialog.Builder(host).setTitle(entry.title)
                .setItems(new String[]{"移动到分组", "从书架移除"}, (dialog, index) -> {
                    if (index == 0) assignGroup(entry);
                    else confirmShelfRemoval(entry);
                }).setNegativeButton("取消", null));
    }

    private void confirmShelfRemoval(ReaderProgress.Entry entry) {
        showDialog(new AlertDialog.Builder(host).setTitle("从书架移除「" + entry.title + "」？")
                .setMessage("本地书籍、已下载章节、阅读进度和书签会保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    progress.setOnShelf(entry, false);
                    renderFeed();
                    toast("已从书架移除");
                }));
    }

    private void showRemovedShelfEntries() {
        List<ReaderProgress.Entry> removed = progress.entries();
        removed.removeIf(entry -> !entry.hiddenOnShelf);
        if (removed.isEmpty()) { toast("没有已移出的作品"); return; }
        String[] titles = removed.stream().map(entry -> entry.title).toArray(String[]::new);
        showDialog(new AlertDialog.Builder(host).setTitle("选择要放回书架的作品")
                .setItems(titles, (dialog, index) -> {
                    progress.setOnShelf(removed.get(index), true);
                    renderFeed();
                    toast("已放回书架");
                }).setNegativeButton("关闭", null));
    }

    private ReaderSources.Source savedSource(String id) {
        ReaderSources.Source source = sources.find(id);
        if (source != null) return source;
        if (id.startsWith(kind + ":") && id.length() > 2) {
            return ReaderSources.offlineSource(kind, id, ReaderLocalBooks.isSource(id, kind) ? "本地文件" : "离线来源");
        }
        return null;
    }

    private boolean localBook(ReaderEngine.Book selected) {
        return ReaderLocalBooks.isSource(selected.source.id, kind);
    }

    private Cell bookCell(ReaderEngine.Book item, String badge, Runnable click) {
        String details = join(item.author, item.source.name);
        return new Cell(item.title, details, badge, item.cover, item.source, click);
    }

    private void showResultSources(SearchState state, SearchResultGroup group) {
        if (destroyed || screen != Screen.HOME || state != searchState || group.editions.isEmpty()) return;
        if (state.mode == SearchMode.EXPLORE && group.editions.size() == 1) {
            selectBook(group.featured);
            return;
        }
        if (resultSourcePicker != null) resultSourcePicker.dialog.dismiss();
        resultSourcePicker = new ResultSourcePicker(state, group);
    }

    private final class ResultSourcePicker {
        final SearchState state;
        final SearchResultGroup group;
        final AlertDialog dialog;
        private final List<Cell> cells = new ArrayList<>();
        private final Map<String, Integer> perSource = new HashMap<>();
        private final ListView list;
        private final TextView status;

        ResultSourcePicker(SearchState state, SearchResultGroup group) {
            this.state = state;
            this.group = group;
            int minimumRows = state.attempt.running() ? 3 : 1;
            LinearLayout panel = column();
            panel.setPadding(dp(18), 0, dp(18), dp(4));
            status = text("", 13, MUTED, false);
            status.setPadding(0, dp(2), 0, dp(6));
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            panel.addView(status, lp(M, W));
            list = paperList();
            list.setContentDescription("选择来源");
            list.setPadding(0, 0, 0, 0);
            int rows = Math.max(minimumRows, group.editions.size());
            panel.addView(list, lp(M, Math.min(chapterPickerHeight(), rows * 60)));
            bindList(list, cells, true, true);
            dialog = showDialog(new AlertDialog.Builder(host).setTitle(group.featured.title)
                    .setView(panel).setNegativeButton("关闭", null));
            dialog.setOnDismissListener(ignored -> {
                dialogs.remove(dialog);
                if (resultSourcePicker == this) resultSourcePicker = null;
            });
            placeReadingSheet(dialog);
            refresh();
        }

        void refresh() {
            if (destroyed || state != searchState || !dialog.isShowing()) return;
            String label = group.sourceIds.size() + " 个来源" + (state.attempt.running() ? " · 搜索中" : "");
            if (!TextUtils.equals(status.getText(), label)) status.setText(label);
            if (cells.size() == group.editions.size()) return;

            List<ReaderEngine.Book> arrivals = group.editions.subList(cells.size(), group.editions.size());
            if (manga && cells.isEmpty()) {
                arrivals = new ArrayList<>(arrivals);
                arrivals.sort(Comparator.comparing(edition -> edition.source, PPCAT_FIRST));
            }
            // Append arrivals so an item the user is about to tap keeps its position and identity.
            for (ReaderEngine.Book edition : arrivals) {
                int number = perSource.merge(edition.source.id, 1, Integer::sum);
                String author = TextUtils.isEmpty(edition.author) || edition.author.isBlank() ? "作者未知" : edition.author;
                String detail = number == 1 ? author : join(author, "同源条目 " + number);
                cells.add(new Cell(edition.source.name, detail, null, "", null, () -> {
                    if (!dialog.isShowing() || destroyed || screen != Screen.HOME || searchState != state) return;
                    dialog.dismiss();
                    selectBook(edition);
                }));
            }
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        }
    }

    private void beginSearch() {
        query = ReaderSearchRanking.requestKeyword(search.getText().toString());
        if (query.isEmpty()) {
            search.setError("请输入书名或作者");
            return;
        }
        hideKeyboard();
        List<ReaderSources.Source> targets = new ArrayList<>();
        List<SearchNotice> excluded = new ArrayList<>();
        Map<String, SearchNotice> deferred = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (ReaderSources.Source source : enabledSources()) {
            String issue = searchEligibility(source);
            if (!issue.isEmpty()) {
                excluded.add(new SearchNotice(source, issue));
            } else if (searchHealth.deferred(source, now)) {
                deferred.put(source.id, new SearchNotice(source, "近期多次失败，已暂缓，可选择继续搜索。"));
            } else {
                targets.add(source);
            }
        }
        prioritizeSearchTargets(targets, now);
        String keyword = query;
        showResults("“" + keyword + "”", SearchMode.SEARCH, targets,
                (source, page) -> ReaderEngine.search(source, keyword, page), excluded, deferred,
                new ReaderSearchRanking(keyword, ReaderSection::foldSearchChinese), null);
        PageMotion.enter(feed, false);
    }

    private static String foldSearchChinese(String value) {
        if (Build.VERSION.SDK_INT >= 29 && value.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
            return SearchChinese.toSimplified(value);
        }
        return value;
    }

    @android.annotation.TargetApi(29)
    private static final class SearchChinese {
        private static final ThreadLocal<android.icu.text.Transliterator> CONVERTER = ThreadLocal.withInitial(() -> {
            try {
                return android.icu.text.Transliterator.getInstance("Traditional-Simplified");
            } catch (IllegalArgumentException unavailable) {
                return null;
            }
        });

        static String toSimplified(String text) {
            android.icu.text.Transliterator converter = CONVERTER.get();
            return converter == null ? text : converter.transliterate(text);
        }
    }

    private void prioritizeSearchTargets(List<ReaderSources.Source> targets, long now) {
        if (targets.size() <= 1) return;
        Map<String, Integer> priorities = new HashMap<>();
        for (ReaderSources.Source source : targets) priorities.put(source.id, searchHealth.priority(source, now));
        Comparator<ReaderSources.Source> byHealth =
                Comparator.<ReaderSources.Source>comparingInt(source -> priorities.get(source.id)).reversed();
        targets.sort(manga ? PPCAT_FIRST.thenComparing(byHealth) : byHealth);
    }

    private static String searchEligibility(ReaderSources.Source source) {
        if (source.searchUrl.isEmpty()) return "此来源没有提供搜索入口";
        return YiciyuanRules.readingIssue(source.definition);
    }

    private void chooseExplore() {
        hideKeyboard();
        List<ReaderSources.Source> options = new ArrayList<>();
        List<ReaderEngine.Explore> choices = new ArrayList<>();
        String entryError = "";
        for (ReaderSources.Source source : enabledSources()) {
            if (!YiciyuanRules.readingIssue(source.definition).isEmpty()) continue;
            try {
                for (ReaderEngine.Explore explore : ReaderEngine.exploreEntries(source)) {
                    options.add(source);
                    choices.add(explore);
                }
            } catch (IOException error) {
                if (entryError.isEmpty()) entryError = problem(error);
            }
        }
        if (choices.isEmpty()) {
            toast(firstNonempty(entryError, "已启用的源没有发现入口"));
            return;
        }
        String[] names = new String[choices.size()];
        for (int i = 0; i < names.length; i++) names[i] = join(options.get(i).name, choices.get(i).title);
        showDialog(new AlertDialog.Builder(host).setTitle("发现" + sectionName)
                .setItems(names, (dialog, which) -> showExplore(options.get(which), choices.get(which)))
                .setNegativeButton("取消", null));
    }

    private void showExplore(ReaderSources.Source source, ReaderEngine.Explore explore) {
        boolean withinHome = screen == Screen.HOME;
        if (!withinHome) showHome();
        showResults(firstNonempty(explore.title, "发现"), SearchMode.EXPLORE, Collections.singletonList(source),
                (item, page) -> ReaderEngine.explore(item, explore.url, page), Collections.emptyList(),
                Collections.emptyMap(), null, explore.url);
        if (withinHome) PageMotion.enter(feed, false);
    }

    private List<ReaderSources.Source> enabledSources() {
        List<ReaderSources.Source> enabled = new ArrayList<>();
        for (ReaderSources.Source source : sources.list(kind)) if (source.enabled) enabled.add(source);
        return enabled;
    }

    private interface SearchAction {
        List<ReaderEngine.Book> run(ReaderSources.Source source, int page) throws Exception;
    }

    private void showResults(String title, SearchMode mode, List<ReaderSources.Source> targets, SearchAction action,
                             List<SearchNotice> excluded, Map<String, SearchNotice> deferred,
                              ReaderSearchRanking ranking, String discoveryRequest) {
        clearSearch();
        SearchState state = new SearchState(title, mode, targets, action, excluded, deferred, ranking, discoveryRequest);
        searchState = state;
        startSearchAttempt(state, targets, SearchPass.INITIAL);
    }

    private void startSearchAttempt(SearchState state, List<ReaderSources.Source> targets, SearchPass pass) {
        Map<String, Integer> pages = new HashMap<>();
        for (ReaderSources.Source source : targets) pages.put(source.id, state.unfinished.get(source.id));
        SearchAttempt attempt = new SearchAttempt(pages, pass);
        state.attempt = attempt;
        renderFeed();
        if (targets.isEmpty()) return;
        Runnable deliver = () -> completeSearchBatch(state, attempt);
        attempt.run = searches.start(targets, source -> ReaderEngine.searchRoute(source, state.request(source)),
                source -> state.action.run(source, attempt.pages.get(source.id)), source -> {
            if (attempt.began(source)) main.postDelayed(deliver, SEARCH_UPDATE_INTERVAL_MILLIS);
        }, (source, books, error) -> {
            SearchOutcome outcome = new SearchOutcome(source, books, error, state.ranking, workMatcher);
            if (attempt.finished(outcome)) main.postDelayed(deliver, SEARCH_UPDATE_INTERVAL_MILLIS);
        }, pass == SearchPass.RETRY ? RETRY_SOURCE_TIMEOUT_MILLIS : ReaderSearchQueue.DEFAULT_TIMEOUT_MILLIS);
    }

    private void retryFailed(SearchState state) {
        resumeSearch(state, SearchPass.RETRY);
    }

    private void continueSearch(SearchState state) {
        resumeSearch(state, SearchPass.CONTINUE);
    }

    private void resumeSearch(SearchState state, SearchPass pass) {
        if (destroyed || state != searchState || state.attempt.running()) return;
        List<ReaderSources.Source> targets = new ArrayList<>();
        for (ReaderSources.Source source : enabledSources()) {
            if (state.mode == SearchMode.SEARCH && !searchEligibility(source).isEmpty()) continue;
            String id = source.id;
            Integer page;
            if (pass == SearchPass.MORE) {
                page = state.morePages.remove(id);
            } else if (pass == SearchPass.RETRY) {
                SearchNotice failure = state.failures.get(id);
                page = failure == null ? null : failure.page;
            } else {
                page = state.unfinished.get(id);
                if (page == null && state.deferred.containsKey(id)) page = 1;
            }
            if (page == null) continue;
            if (pass == SearchPass.CONTINUE) {
                state.deferred.remove(id);
            }
            state.unfinished.put(id, page);
            targets.add(source);
        }
        if (targets.isEmpty()) {
            toast(pass == SearchPass.RETRY ? "没有可重试的失败来源，请检查来源设置"
                    : pass == SearchPass.MORE ? "没有还能提供更多结果的启用来源"
                    : "没有可继续搜索的来源，请检查来源设置");
            return;
        }
        prioritizeSearchTargets(targets, System.currentTimeMillis());
        startSearchAttempt(state, targets, pass);
    }

    private void showSearchDetails(SearchState state) {
        if (destroyed || searchState != state) return;
        List<SearchNotice> notices = new ArrayList<>(state.failures.values());
        int failed = notices.size();
        notices.addAll(state.deferred.values());
        int deferredEnd = notices.size();
        notices.addAll(state.excluded);
        if (notices.isEmpty()) return;
        ListView rows = newList();
        rows.setPadding(dp(20), 0, dp(20), 0);
        rows.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return notices.size(); }
            @Override public SearchNotice getItem(int position) { return notices.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return false; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                LinearLayout row = (LinearLayout) recycled;
                if (row == null) {
                    row = column();
                    row.setPadding(0, dp(9), 0, dp(9));
                    TextView name = text("", 15, FG, true);
                    name.setMaxLines(2);
                    name.setEllipsize(TextUtils.TruncateAt.END);
                    row.addView(name, lp(M, W));
                    TextView reason = text("", 13, MUTED, false);
                    reason.setPadding(0, dp(4), 0, 0);
                    row.addView(reason, lp(M, W));
                }
                SearchNotice notice = getItem(position);
                TextView name = (TextView) row.getChildAt(0);
                name.setText(notice.source.name.equals(notice.source.url)
                        ? firstNonempty(Uri.parse(notice.source.url).getHost(), "未命名来源")
                        : notice.source.name);
                TextView reason = (TextView) row.getChildAt(1);
                String label = position < failed ? "失败" : position < deferredEnd ? "暂缓" : "未参与";
                reason.setText(label + (notice.page > 1 ? " · 第 " + notice.page + " 页" : "") + " · " + notice.reason);
                return row;
            }
        });
        showDialog(new AlertDialog.Builder(host)
                .setTitle("失败 " + failed + " · 暂缓 " + state.deferred.size() + " · 未参与 " + state.excluded.size())
                .setView(rows).setPositiveButton("关闭", null));
    }

    private static String searchProblem(Exception error) {
        Throwable cause = error;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof ReaderSources.SiteFailure) return cause.getMessage();
            if (cause instanceof ReaderSources.RuleFailure) return cause.getMessage();
            if (cause instanceof ReaderSources.HttpFailure) {
                int status = ((ReaderSources.HttpFailure) cause).status;
                if (status == 401 || status == 403) {
                    return "网站拒绝访问（HTTP " + status + "），请检查来源的登录或访问限制。";
                }
                if (status == 404 || status == 410) {
                    return "网站找不到搜索入口（HTTP " + status + "），请更新来源。";
                }
                if (status == 429) return "网站限制请求频率（HTTP 429），请稍后重试。";
                if (status >= 500) return "网站暂时无法处理请求（HTTP " + status + "），请稍后重试。";
                return "网站请求失败（HTTP " + status + "）。";
            }
            if (cause instanceof SocketTimeoutException) return "网站响应超时，可以重试。";
            if (cause instanceof UnknownHostException) return "无法解析网站域名，请检查网络和来源。";
            if (cause instanceof ConnectException || cause instanceof NoRouteToHostException) {
                return "无法连接网站，请检查网络和来源。";
            }
            if (cause instanceof SSLException) return "HTTPS 安全连接失败，请检查网站证书和系统时间。";
            if (cause instanceof SocketException) return "网站连接中断，可以重试。";
            if (cause instanceof LinkageError) return "阅读组件初始化失败，请更新应用后重试。";
            if (cause instanceof StackOverflowError) return "来源规则嵌套过深，无法完成解析。";
        }
        String message = error.getMessage();
        if (message != null) {
            switch (message) {
                case "列表规则命中了条目，但没能提取作品名称和链接":
                    return "来源返回了列表，但书名或链接提取规则与页面不匹配。";
                case "来源请求参数无法解析":
                case "来源脚本的辅助请求参数无法解析":
                case "源的请求头规则格式有误":
                    return "来源的请求参数格式有误，请检查地址或请求头规则。";
                case "来源脚本的辅助请求须访问来源自身站点":
                case "来源脚本的有效地址跳转到其他站点":
                case "来源脚本的请求返回了跨站跳转":
                    return "来源脚本尝试访问其他站点，该辅助请求受到限制。";
                default:
                    break;
            }
            if (message.contains("脚本") || message.contains("加密") || message.contains("WebView")) {
                return "来源需要可用的脚本、解密或浏览器规则，请检查来源。";
            }
            if (message.contains("超时")) return "来源未能在时限内完成，可以重试。";
            if (message.contains("规则") || message.contains("正则") || message.contains("模板")) {
                return "来源规则解析失败，请在来源管理中检查。";
            }
        }
        return "读取网站失败，请检查网络或在来源管理中检查该来源。";
    }

    private void completeSearchBatch(SearchState state, SearchAttempt attempt) {
        if (destroyed || searchState != state || state.attempt != attempt || !attempt.running()) return;
        applySearchBatch(state, attempt, attempt.drain(false));
        if (!attempt.running()) attempt.finishedAt = SystemClock.elapsedRealtime();
        publishSearch();
    }

    private void applySearchBatch(SearchState state, SearchAttempt attempt, SearchBatch batch) {
        attempt.active = batch.inFlight;
        attempt.completed += batch.outcomes.size();
        long now = System.currentTimeMillis();
        for (SearchOutcome outcome : batch.outcomes) {
            ReaderSources.Source source = outcome.source;
            int page = attempt.pages.get(source.id);
            boolean found = false;
            for (int score : outcome.scores) if (score > 0) {
                found = true;
                break;
            }
            state.unfinished.remove(source.id);
            if (page == 1 && state.mode == SearchMode.SEARCH && searchHealth.record(source, found, outcome.error, now)
                    && !searchHealthDirty) {
                searchHealthDirty = true;
                main.postDelayed(saveSearchHealth, 2500);
            }
            if (outcome.error != null) {
                state.failures.put(source.id, new SearchNotice(source, searchProblem(outcome.error), page));
                continue;
            }
            state.failures.remove(source.id);
            state.mark(source, found);
            boolean hasNewResults = false;
            for (int i = 0; i < outcome.books.size(); i++) {
                String groupKey = outcome.groupKeys[i];
                if (groupKey == null) break;
                ReaderEngine.Book book = outcome.books.get(i);
                Set<String> seen = state.resultUrls.computeIfAbsent(book.source.id, id -> new HashSet<>());
                if (!seen.add(book.url)) continue;
                state.results.computeIfAbsent(groupKey, ignored -> new SearchResultGroup())
                        .add(book, outcome.scores[i]);
                state.revision++;
                hasNewResults = true;
            }
            if (hasNewResults && page < Integer.MAX_VALUE && ReaderEngine.canPaginate(source, state.request(source))) {
                state.morePages.put(source.id, page + 1);
            }
        }
    }

    private void persistSearchHealth() {
        main.removeCallbacks(saveSearchHealth);
        if (!searchHealthDirty) return;
        searchHealthDirty = false;
        searchSettings.edit().putString(searchHealthKey, searchHealth.serialize()).apply();
    }

    private void publishSearch() {
        if (searchState != null && screen == Screen.HOME && root.isShown()) {
            renderFeed();
            if (resultSourcePicker != null) resultSourcePicker.refresh();
        }
    }

    private void scheduleSearchTime() {
        main.removeCallbacks(updateSearchTime);
        if (!destroyed && screen == Screen.HOME && root.isShown()
                && searchState != null && searchState.attempt.running()) {
            main.postDelayed(updateSearchTime, 1000);
        }
    }

    private void tickSearchTime() {
        if (destroyed || screen != Screen.HOME || !root.isShown()
                || searchFeed == null || searchFeed.state != searchState || !searchState.attempt.running()) return;
        searchFeed.refreshActivity();
        scheduleSearchTime();
    }

    private void stopSearch() {
        SearchState state = searchState;
        if (state == null || !state.attempt.running()) return;
        SearchAttempt attempt = state.attempt;
        SearchBatch completed = attempt.drain(true);
        if (attempt.run != null) attempt.run.cancel();
        applySearchBatch(state, attempt, completed);
        attempt.stopped = attempt.completed < attempt.total;
        attempt.finishedAt = SystemClock.elapsedRealtime();
        publishSearch();
    }

    private void clearSearch() {
        if (resultSourcePicker != null) resultSourcePicker.dialog.dismiss();
        if (searchState != null) {
            SearchAttempt attempt = searchState.attempt;
            SearchBatch completed = attempt.drain(true);
            if (attempt.run != null) attempt.run.cancel();
            applySearchBatch(searchState, attempt, completed);
        }
        searchState = null;
        searchFeed = null;
        main.removeCallbacks(updateSearchTime);
    }

    private void showAlternateSources() {
        ReaderEngine.Book original = book;
        if (original == null) return;
        Map<String, ReaderEngine.Book> candidates = new LinkedHashMap<>();
        Set<String> knownSources = new HashSet<>();
        List<ReaderSources.Source> available = enabledSources();
        Set<String> enabledIds = new HashSet<>();
        for (ReaderSources.Source source : available) enabledIds.add(source.id);
        if (searchState != null) {
            for (SearchResultGroup group : searchState.results.values()) {
                for (ReaderEngine.Book candidate : group.editions) {
                    if (!enabledIds.contains(candidate.source.id) || !workMatcher.alternate(original, candidate)) continue;
                    candidates.put(ReaderProgress.key(candidate), candidate);
                    if (workMatcher.verifiedAuthor(original, candidate)) knownSources.add(candidate.source.id);
                }
            }
        }
        List<ReaderSources.Source> targets = new ArrayList<>();
        Map<String, Integer> pages = new HashMap<>();
        for (ReaderSources.Source source : available) {
            if (source.id.equals(original.source.id) || knownSources.contains(source.id)
                    || !searchEligibility(source).isEmpty()) continue;
            targets.add(source);
            pages.put(source.id, 1);
        }
        prioritizeSearchTargets(targets, System.currentTimeMillis());
        LinearLayout panel = column();
        panel.setPadding(dp(18), 0, dp(18), dp(10));
        TextView status = text("", 13, MUTED, false);
        status.setPadding(0, dp(4), 0, dp(7));
        panel.addView(status, lp(M, W));
        ListView list = newList();
        list.setPadding(0, 0, 0, 0);
        panel.addView(list, lp(M, chapterPickerHeight()));
        List<Cell> cells = new ArrayList<>();
        bindList(list, cells, false);
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("换源 · " + original.title)
                .setView(panel).setNegativeButton("关闭", null));
        SearchAttempt attempt = new SearchAttempt(pages, SearchPass.INITIAL);
        Runnable refresh = () -> {
            if (destroyed || !dialog.isShowing() || book != original) return;
            SearchBatch batch = attempt.drain(false);
            attempt.completed += batch.outcomes.size();
            boolean changed = cells.isEmpty();
            for (SearchOutcome outcome : batch.outcomes) {
                for (ReaderEngine.Book candidate : outcome.books) {
                    if (candidates.putIfAbsent(ReaderProgress.key(candidate), candidate) == null) changed = true;
                }
            }
            String count = candidates.isEmpty() ? "没有找到可选来源" : "可选条目 " + candidates.size();
            status.setText(attempt.running() ? "正在查找 " + attempt.completed + " / " + attempt.total
                    + (candidates.isEmpty() ? "" : " · " + count) : count);
            if (!changed) return;
            List<ReaderEngine.Book> sorted = new ArrayList<>(candidates.values());
            sorted.sort(Comparator.<ReaderEngine.Book, Boolean>comparing(item -> !workMatcher.verifiedAuthor(original, item))
                    .thenComparing(item -> item.source.name, String.CASE_INSENSITIVE_ORDER));
            cells.clear();
            for (ReaderEngine.Book candidate : sorted) {
                String badge = workMatcher.verifiedAuthor(original, candidate) ? null : "作者待核对";
                cells.add(bookCell(candidate, badge, () -> {
                    dialog.dismiss();
                    prepareSourceChange(original, candidate);
                }));
            }
            list.setVisibility(cells.isEmpty() ? View.GONE : View.VISIBLE);
            ((BaseAdapter) list.getAdapter()).notifyDataSetChanged();
        };
        refresh.run();
        if (targets.isEmpty()) return;
        String keyword = ReaderSearchRanking.requestKeyword(original.title);
        attempt.run = alternateSearches.start(targets, ReaderEngine::searchRoute, source -> {
            List<ReaderEngine.Book> matches = new ArrayList<>();
            for (ReaderEngine.Book candidate : ReaderEngine.search(source, keyword, 1)) {
                if (workMatcher.alternate(original, candidate)) matches.add(candidate);
            }
            return matches;
        }, source -> {}, (source, matches, error) -> {
            if (attempt.finished(new SearchOutcome(source, matches, error, null, null))) main.postDelayed(refresh, 100);
        }, ReaderSearchQueue.DEFAULT_TIMEOUT_MILLIS);
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            attempt.drain(true);
            attempt.run.cancel();
            main.removeCallbacks(refresh);
        });
    }

    private void prepareSourceChange(ReaderEngine.Book original, ReaderEngine.Book candidate) {
        if (book != original) return;
        savePosition();
        ReaderProgress.Entry old = progress.get(original);
        int epoch = viewEpoch;
        Screen current = screen;
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle(candidate.source.name)
                .setMessage("正在读取新来源的章节目录…").setNegativeButton("取消", null));
        Future<?> task = network.submit(() -> {
            try {
                ReaderEngine.Book resolved = candidate;
                List<ReaderEngine.Chapter> catalogue = offline.loadCatalogue(candidate);
                if (catalogue == null || catalogue.isEmpty()) {
                    try {
                        resolved = ReaderEngine.details(candidate);
                    } catch (IOException unavailable) {
                        if (Thread.currentThread().isInterrupted()) return;
                    }
                    if (!workMatcher.alternate(original, resolved)) {
                        throw new IOException("新来源的作品名称或作者与当前作品不同。");
                    }
                    catalogue = ReaderEngine.chapters(resolved);
                    offline.storeCatalogue(resolved, catalogue);
                }
                ReaderEngine.Book replacement = resolved;
                List<ReaderEngine.Chapter> entries = catalogue;
                ReaderWorkMatcher.Match match = workMatcher.chapter(old == null ? "" : old.chapterTitle, entries);
                main.post(() -> {
                    if (!dialog.isShowing() || !isCurrent(epoch, current) || book != original) return;
                    dialog.dismiss();
                    confirmSourceChange(original, replacement, entries, old, match);
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!dialog.isShowing() || !isCurrent(epoch, current) || book != original) return;
                    dialog.setMessage("读取目录失败 · " + problem(error));
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
                });
            }
        });
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            task.cancel(true);
        });
    }

    private void confirmSourceChange(ReaderEngine.Book original, ReaderEngine.Book replacement,
                                     List<ReaderEngine.Chapter> entries, ReaderProgress.Entry old,
                                     ReaderWorkMatcher.Match match) {
        boolean authorVerified = workMatcher.verifiedAuthor(original, replacement);
        boolean hasPosition = old != null && !old.chapterUrl.isEmpty();
        if (authorVerified && (!hasPosition || match.type == ReaderWorkMatcher.ChapterMatch.EXACT)) {
            applySourceChange(original, replacement, entries, old, match.index);
            return;
        }
        String reason = authorVerified ? "" : "部分来源未提供作者，请核对是否为同一作品。\n";
        String message = !hasPosition ? reason + "切换到「" + replacement.source.name + "」？"
                : match.index < 0 ? reason + "无法定位「" + old.chapterTitle + "」，请选择新来源的章节。"
                : reason + "当前 · " + old.chapterTitle + "\n新来源 · " + entries.get(match.index).title
                + "\n切换后将从新章节开头继续。";
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("确认换源").setMessage(message)
                .setNegativeButton("取消", null);
        if (hasPosition && match.index < 0) {
            dialog.setPositiveButton("选择章节", (ignored, button) ->
                    pickSourceChapter(original, replacement, entries, old, -1));
        } else {
            dialog.setPositiveButton(!hasPosition ? "切换" : "继续阅读", (ignored, button) ->
                    applySourceChange(original, replacement, entries, old, match.index));
            if (hasPosition) {
                dialog.setNeutralButton("选择章节", (ignored, button) ->
                        pickSourceChapter(original, replacement, entries, old, match.index));
            }
        }
        showDialog(dialog);
    }

    private void pickSourceChapter(ReaderEngine.Book original, ReaderEngine.Book replacement,
                                   List<ReaderEngine.Chapter> entries, ReaderProgress.Entry old, int suggested) {
        showChapterPicker("选择新来源章节", entries, suggested, index ->
                applySourceChange(original, replacement, entries, old, index));
    }

    private void applySourceChange(ReaderEngine.Book original, ReaderEngine.Book replacement,
                                   List<ReaderEngine.Chapter> entries, ReaderProgress.Entry old, int index) {
        if (book != original) return;
        savePosition();
        if (old != null && old.favorite) progress.favorite(replacement, true);
        progress.copyGroup(old, replacement);
        book = replacement;
        chapters = entries;
        downloadedChaptersOnly = false;
        chapterIndex = -1;
        showContents(false);
        if (index >= 0) openChapter(index, false);
        toast("已切换到「" + replacement.source.name + "」");
    }

    private void showSources() {
        boolean preserveScroll = sourceList != null && screen == Screen.SOURCES;
        int scrollTo = preserveScroll ? sourceList.getFirstVisiblePosition() : 0;
        View first = preserveScroll ? sourceList.getChildAt(0) : null;
        int scrollOffset = first == null ? 0 : first.getTop() - sourceList.getPaddingTop();
        boolean restoreFilterFocus = sourceFilterInput != null && sourceFilterInput.hasFocus();
        int filterCursor = restoreFilterFocus ? sourceFilterInput.getSelectionStart() : -1;
        List<ReaderSources.Source> catalog = sources.list(kind);
        if (manga) catalog.sort(PPCAT_FIRST);
        int active = 0;
        for (ReaderSources.Source source : catalog) if (source.enabled) active++;
        reset(Screen.SOURCES);
        LinearLayout header = topBar("源管理", "已启用 " + active + " / " + catalog.size(), this::showHome);
        scene.addView(header, lp(M, W));
        scene.addView(aggregationPanel(), inset(0, 8));

        SourceCatalogAdapter rows = new SourceCatalogAdapter(catalog);
        scene.addView(sourceFilterBar(rows, active), lp(M, W));
        LinearLayout content = column();
        content.setPadding(0, dp(8), 0, dp(6));
        TextView importHeading = text("导入源", 17, FG, true);
        markHeading(importHeading);
        content.addView(importHeading, lp(M, W));
        LinearLayout operation = row();
        addImportTile(operation, "file", "选择文件", this::chooseFile);
        addImportTile(operation, "link", "从链接", () -> askImport(true));
        addImportTile(operation, "queue", "粘贴规则", () -> askImport(false));
        LinearLayout.LayoutParams ops = lp(M, W);
        ops.topMargin = dp(7);
        content.addView(operation, ops);
        if (manga) {
            LinearLayout.LayoutParams repository = lp(M, W);
            repository.topMargin = dp(10);
            content.addView(mangaRepositoryCard(), repository);
        }
        if (!sampleAsset.isEmpty()) attachTrySample(content);
        if (editingSources) {
            String message = aggregatingSources ? "源变更将于聚合结束后处理…" : "正在更新源…";
            content.addView(loading(message), lp(M, W));
        } else if (catalog.isEmpty() && !aggregatingSources) {
            content.addView(note("目前没有已导入的源"), lp(M, W));
        }
        sourceList = newList();
        sourceList.addHeaderView(content, null, false);
        sourceList.setAdapter(rows);
        scene.addView(sourceList, new LinearLayout.LayoutParams(M, 0, 1));
        sourceList.setSelectionFromTop(Math.min(scrollTo, rows.getCount()), scrollOffset);
        if (restoreFilterFocus && root.isShown()) {
            sourceFilterInput.requestFocus();
            sourceFilterInput.setSelection(Math.max(0, Math.min(filterCursor, sourceFilter.length())));
        }
    }

    private View sourceFilterBar(SourceCatalogAdapter rows, int active) {
        LinearLayout bar = column();
        bar.setPadding(dp(16), 0, dp(16), dp(3));
        LinearLayout controls = row();
        LinearLayout filterBox = row();
        filterBox.setGravity(Gravity.CENTER_VERTICAL);
        filterBox.setPadding(dp(8), 0, 0, 0);
        filterBox.setBackground(surface(host, SURFACE, 13, LINE));
        filterBox.addView(new Icon(host, "search", BERRY, 17), lp(26, M));
        EditText field = input("按名称或网址筛选", false);
        field.setPadding(dp(5), 0, dp(8), 0);
        field.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setText(sourceFilter);
        filterBox.addView(field, weight(M));
        controls.addView(filterBox, weight(46));
        if (!rows.catalog.isEmpty()) {
            boolean enable = active < rows.catalog.size();
            TextView toggleAll = action(enable ? "全部启用" : "全部停用", false, () -> setAllSourcesEnabled(enable));
            toggleAll.setEnabled(!editingSources);
            LinearLayout.LayoutParams params = lp(W, 46);
            params.setMarginStart(dp(8));
            controls.addView(toggleAll, params);
        }
        bar.addView(controls, lp(M, W));
        if (!rows.catalog.isEmpty()) {
            LinearLayout checks = row();
            addEqual(checks, action("批量检查", true, () -> askBatchCheck(rows.visible)), false);
            addEqual(checks, action(DIAGNOSTIC_FILTERS[diagnosticFilter], false, () ->
                    showDialog(new AlertDialog.Builder(host).setTitle("按检查状态筛选")
                            .setSingleChoiceItems(DIAGNOSTIC_FILTERS, diagnosticFilter, (dialog, which) -> {
                                diagnosticFilter = which;
                                dialog.dismiss();
                                showSources();
                            }).setNegativeButton("关闭", null))), true);
            addEqual(checks, action("检查报告", false, this::showDiagnosticsReport), true);
            LinearLayout.LayoutParams checkSize = lp(M, W);
            checkSize.topMargin = dp(5);
            bar.addView(checks, checkSize);
        }
        TextView filterStatus = text("", 12, MUTED, false);
        filterStatus.setPadding(0, dp(5), 0, 0);
        bar.addView(filterStatus, lp(M, W));
        updateFilterStatus(filterStatus, rows);
        sourceFilterInput = field;
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                if (sourceFilterInput != field) return;
                sourceFilter = value.toString();
                rows.filter(sourceFilter);
                updateFilterStatus(filterStatus, rows);
                if (sourceList != null) {
                    sourceList.setSelection(!sourceFilter.trim().isEmpty() && rows.getCount() > 0 ? 1 : 0);
                }
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        return bar;
    }

    private void setAllSourcesEnabled(boolean enabled) {
        hideKeyboard();
        editSources(() -> {
            sources.setAllEnabled(kind, enabled);
            return null;
        }, "已" + (enabled ? "启用" : "停用") + "全部源");
    }

    private void updateFilterStatus(TextView filterStatus, SourceCatalogAdapter rows) {
        filterStatus.setVisibility(sourceFilter.trim().isEmpty() && diagnosticFilter == 0 ? View.GONE : View.VISIBLE);
        int count = rows.getCount();
        filterStatus.setText(count == 0 ? "没有匹配的来源" : "找到 " + count + " 个来源");
    }

    private final class SourceCatalogAdapter extends BaseAdapter {
        final List<ReaderSources.Source> catalog;
        private List<ReaderSources.Source> visible;
        private String[] normalizedNamesAndUrls;

        SourceCatalogAdapter(List<ReaderSources.Source> catalog) {
            this.catalog = catalog;
            filter(sourceFilter);
        }

        void filter(String query) {
            String key = query.trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty() && diagnosticFilter == 0) visible = catalog;
            else {
                if (!key.isEmpty() && normalizedNamesAndUrls == null) {
                    normalizedNamesAndUrls = new String[catalog.size()];
                    for (int i = 0; i < catalog.size(); i++) {
                        ReaderSources.Source source = catalog.get(i);
                        normalizedNamesAndUrls[i] = (source.name + '\n' + source.url).toLowerCase(Locale.ROOT);
                    }
                }
                List<ReaderSources.Source> matches = new ArrayList<>();
                for (int i = 0; i < catalog.size(); i++) {
                    ReaderSources.Source source = catalog.get(i);
                    if ((key.isEmpty() || normalizedNamesAndUrls[i].contains(key)) && showByCheckStatus(source)) {
                        matches.add(source);
                    }
                }
                visible = matches;
            }
            notifyDataSetChanged();
        }

        @Override public int getCount() { return visible.size(); }
        @Override public ReaderSources.Source getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            SourceRow view = recycled instanceof SourceRow ? (SourceRow) recycled : new SourceRow();
            view.bind(getItem(position));
            return view;
        }
    }

    private boolean showByCheckStatus(ReaderSources.Source source) {
        if (diagnosticFilter == 0) return true;
        ReaderSourceDiagnostics.Record result = sourceDiagnostics.get(source);
        if (result == null) return diagnosticFilter == 3;
        switch (result.status) {
            case PASSED: return diagnosticFilter == 1;
            case FAILED: return diagnosticFilter == 2;
            default: return diagnosticFilter == 4;
        }
    }

    private View aggregationPanel() {
        LinearLayout box = column();
        box.setPadding(dp(11), dp(7), dp(9), dp(7));
        box.setBackground(surface(host, SOFT_PINK, 18, 0));
        LinearLayout headline = row();
        headline.setGravity(Gravity.CENTER_VERTICAL);
        if (aggregatingSources) {
            ProgressBar spinner = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
            spinner.setIndeterminateTintList(ColorStateList.valueOf(BERRY));
            headline.addView(spinner, lp(24, 24));
        } else {
            headline.addView(new Icon(host, "sparkle", BERRY, 20), lp(24, 24));
        }
        TextView title = text("公开来源", 16, FG, true);
        title.setPadding(dp(7), 0, dp(4), 0);
        headline.addView(title, weight(W));
        TextView aggregate = action(aggregatingSources ? "聚合中…" : "一键聚合", true, this::aggregateSources);
        aggregate.setEnabled(!aggregatingSources && !editingSources);
        headline.addView(aggregate, lp(W, 44));
        box.addView(headline, lp(M, W));

        LinearLayout status = row();
        status.setGravity(Gravity.CENTER_VERTICAL);
        String message = firstNonempty(aggregationMessage, "从公开列表导入源");
        aggregationStatus = text(message, 13, MUTED, false);
        aggregationStatus.setPadding(dp(5), dp(1), dp(4), dp(3));
        status.addView(aggregationStatus, weight(W));
        if (aggregationResult != null) {
            status.addView(action("查看详情", false, this::showAggregationDetails), lp(W, 44));
        }
        box.addView(status, lp(M, W));
        return box;
    }

    private void addImportTile(LinearLayout parent, String icon, String title, Runnable open) {
        LinearLayout tile = column();
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(6), dp(4), dp(6));
        tile.setMinimumHeight(dp(58));
        tile.setBackground(button(host, SURFACE, 15, LINE));
        tile.addView(new Icon(host, icon, BERRY, 19), lp(24, 24));
        TextView label = text(title, 13, FG, true);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(label, lp(M, W));
        tile.setContentDescription(title);
        tile.setFocusable(true);
        tile.setOnClickListener(view -> open.run());
        LinearLayout.LayoutParams params = weight(W);
        if (parent.getChildCount() > 0) params.setMarginStart(dp(7));
        parent.addView(tile, params);
    }

    private void aggregateSources() {
        if (destroyed || aggregatingSources || editingSources) return;
        hideKeyboard();
        aggregatingSources = true;
        aggregationResult = null;
        aggregationMessage = "正在读取公开来源列表…";
        refreshSourceScreen();
        imports.submit(() -> {
            try {
                ReaderAggregation.Result result = ReaderAggregation.run(sources, kind,
                        message -> main.post(() -> updateAggregationProgress(message)));
                main.post(() -> finishAggregation(result, ""));
            } catch (Exception error) {
                main.post(() -> finishAggregation(null, problem(error)));
            }
        });
    }

    private void updateAggregationProgress(String message) {
        if (destroyed || !aggregatingSources || TextUtils.isEmpty(message)) return;
        aggregationMessage = message;
        if (aggregationStatus != null) aggregationStatus.setText(message);
    }

    private void finishAggregation(ReaderAggregation.Result result, String error) {
        if (destroyed) return;
        aggregatingSources = false;
        aggregationResult = result;
        aggregationMessage = result == null ? "聚合失败 · " + error : aggregationSummary(result);
        if (screen == Screen.SOURCES) showSources();
        else if (screen == Screen.HOME) {
            updateSourceCount();
            renderFeed();
            if (root.isShown()) toast(aggregationMessage);
        }
    }

    private String aggregationSummary(ReaderAggregation.Result result) {
        int failures = 0;
        for (ReaderAggregation.Report report : result.reports) if (!report.success) failures++;
        StringBuilder message = new StringBuilder();
        if (result.changes.added == 0 && failures == result.reports.size() && failures > 0) {
            message.append("公开来源列表读取失败");
        } else {
            message.append("新增 ").append(result.changes.added).append(" 个");
            if (result.newlyEnabled > 0) message.append(" · 启用 ").append(result.newlyEnabled).append(" 个");
            if (failures > 0) message.append(" · ").append(failures).append(" 个列表失败");
        }
        if (result.limited) message.append(" · 已达来源上限");
        return message.toString();
    }

    private void showAggregationDetails() {
        ReaderAggregation.Result result = aggregationResult;
        if (result == null) return;
        StringBuilder message = new StringBuilder(importSummary(result.changes.added,
                result.changes.updated, result.changes.skipped));
        if (result.newlyEnabled > 0) message.append("\n已启用 ").append(result.newlyEnabled).append(" 个新来源");
        if (result.limited) message.append("\n已达来源数量上限，其余已跳过。");
        for (ReaderAggregation.Report report : result.reports) {
            message.append("\n\n").append(report.name).append(report.success ? " · 已读取" : " · 读取失败");
            if (!TextUtils.isEmpty(report.detail)) message.append("\n").append(report.detail);
        }
        if (!result.changes.diagnostics.isEmpty()) {
            message.append("\n\n").append(TextUtils.join("\n", result.changes.diagnostics));
        }
        showDialog(new AlertDialog.Builder(host).setTitle("聚合结果").setMessage(message.toString())
                .setPositiveButton("完成", null));
    }

    private View mangaRepositoryCard() {
        LinearLayout card = column();
        card.setPadding(dp(12), dp(8), dp(12), dp(10));
        card.setBackground(surface(host, SOFT_MINT, 15, 0));
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("源仓库", 15, DEEP_MINT, true), weight(W));
        heading.addView(action("浏览源仓库", false, this::openMangaRepository), lp(W, 48));
        card.addView(heading, lp(M, W));
        card.addView(text("可从链接或文件导入源，也可一键聚合公开源。", 13, MUTED, false), lp(M, W));
        return card;
    }

    private void openMangaRepository() {
        Intent browser = new Intent(Intent.ACTION_VIEW, MANGA_SOURCE_REPOSITORY)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        try {
            host.startActivity(browser);
        } catch (ActivityNotFoundException unavailable) {
            toast("此设备没有可用的浏览器");
        }
    }

    private final class SourceRow extends LinearLayout {
        final TextView name, issue;
        final Switch enabled;
        final View more;
        ReaderSources.Source source;

        SourceRow() {
            super(host);
            setOrientation(VERTICAL);
            setPadding(0, dp(6), 0, 0);
            LinearLayout block = row();
            block.setGravity(Gravity.CENTER_VERTICAL);
            block.setBackground(surface(host, SURFACE, 16, LINE));
            block.setPadding(dp(13), dp(6), dp(6), dp(6));
            LinearLayout labels = column();
            name = text("", 15, FG, true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name, lp(M, W));
            issue = text("", 12, BERRY, false);
            issue.setMaxLines(2);
            issue.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(issue, lp(M, W));
            block.addView(labels, weight(W));
            enabled = new Switch(host);
            int[][] toggleStates = {{-android.R.attr.state_enabled}, {android.R.attr.state_checked}, {}};
            enabled.setThumbTintList(new ColorStateList(toggleStates, new int[]{LINE, DEEP_MINT, BERRY}));
            enabled.setTrackTintList(new ColorStateList(toggleStates, new int[]{BG, SOFT_MINT, SOFT_PINK}));
            block.addView(enabled, lp(51, 45));
            more = iconButton("more", "管理来源", () -> editSource(source));
            block.addView(more, lp(42, 45));
            addView(block, lp(M, W));
        }

        void bind(ReaderSources.Source source) {
            this.source = source;
            String label = firstNonempty(source.name, source.url);
            String warning = YiciyuanRules.readingIssue(source.definition);
            ReaderSourceDiagnostics.Record diagnosis = sourceDiagnostics.get(source);
            if (diagnosis != null) warning = join(diagnosis.label(), warning);
            name.setText(label);
            issue.setText(warning);
            issue.setTextColor(diagnosis != null && diagnosis.status == ReaderSourceDiagnostics.Status.PASSED
                    ? DEEP_MINT : BERRY);
            issue.setVisibility(warning.isEmpty() ? GONE : VISIBLE);
            enabled.setOnCheckedChangeListener(null);
            enabled.setChecked(source.enabled);
            enabled.setEnabled(!editingSources);
            enabled.setContentDescription(source.name + "启用状态");
            enabled.setOnCheckedChangeListener((button, checked) -> editSources(() -> {
                sources.setEnabled(source, checked);
                return null;
            }, ""));
            more.setContentDescription("管理 " + source.name);
            more.setEnabled(!editingSources);
        }
    }

    private void editSource(ReaderSources.Source source) {
        ReaderSourceDiagnostics.Record checked = sourceDiagnostics.get(source);
        String details = source.url + "\n" + ReaderSourceDiagnostics.reference(source) + "\n"
                + (checked == null ? "未检查" : checked.label() + " · "
                + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(checked.time)));
        showDialog(new AlertDialog.Builder(host).setTitle(source.name).setMessage(details)
                .setNegativeButton("关闭", null)
                .setNeutralButton("移除", (dialog, which) -> confirmRemove(source))
                .setPositiveButton("检查来源", (dialog, which) -> askSourceCheck(source)));
    }

    private void askBatchCheck(List<ReaderSources.Source> visible) {
        List<ReaderSources.Source> selected = new ArrayList<>();
        for (ReaderSources.Source source : visible) if (source.enabled) selected.add(source);
        if (selected.isEmpty()) {
            toast("当前筛选中没有启用的来源");
            return;
        }
        hideKeyboard();
        EditText keyword = input("输入已收录作品的完整书名", false);
        keyword.setText(query);
        keyword.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        panel.addView(text("检查当前筛选中已启用的 " + selected.size()
                + " 个来源。匹配书名后才会检查目录和首章。", 14, MUTED, false), lp(M, W));
        panel.addView(keyword, lp(M, 52));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("批量检查来源")
                .setView(panel).setPositiveButton("开始检查", null).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = ReaderSearchRanking.requestKeyword(keyword.getText().toString());
            if (value.isEmpty()) {
                keyword.setError("请输入完整书名");
                return;
            }
            dialog.dismiss();
            runBatchCheck(selected, value);
        });
    }

    private static final class DiagnosticBatch {
        private final List<ReaderSourceDiagnostics.Record> pending = new ArrayList<>();
        final Map<String, ReaderSourceDiagnostics.Stage> stages = new ConcurrentHashMap<>();
        boolean accepting = true, deliveryPosted;
        boolean stopped;
        int done, failed, passed;
        long lastPersisted = SystemClock.elapsedRealtime();
        ReaderSearchQueue.Run run;

        synchronized boolean offer(ReaderSourceDiagnostics.Record record) {
            if (!accepting) return false;
            pending.add(record);
            if (deliveryPosted) return false;
            deliveryPosted = true;
            return true;
        }

        synchronized List<ReaderSourceDiagnostics.Record> drain(boolean close) {
            if (close) accepting = false;
            List<ReaderSourceDiagnostics.Record> ready = new ArrayList<>(pending);
            pending.clear();
            deliveryPosted = false;
            return ready;
        }
    }

    private void runBatchCheck(List<ReaderSources.Source> selected, String keyword) {
        DiagnosticBatch batch = new DiagnosticBatch();
        ReaderSearchRanking ranking = new ReaderSearchRanking(keyword, ReaderSection::foldSearchChinese);
        LinearLayout panel = column();
        panel.setPadding(dp(20), dp(3), dp(20), dp(8));
        TextView result = text("正在检查 0 / " + selected.size(), 14, FG, false);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        panel.addView(result, lp(M, W));
        ProgressBar bar = new ProgressBar(host, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(selected.size());
        bar.setProgressTintList(ColorStateList.valueOf(BERRY));
        panel.addView(bar, lp(M, 14));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("批量检查来源")
                .setView(panel).setNegativeButton("停止", null).setPositiveButton("查看报告", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        Runnable refresh = () -> {
            if (destroyed || !dialog.isShowing()) return;
            applyDiagnosticBatch(batch, false);
            bar.setProgress(batch.done);
            result.setText((batch.stopped ? "已停止 " : batch.done == selected.size() ? "检查完成 " : "正在检查 ") + batch.done + " / "
                    + selected.size() + "\n通过 " + batch.passed + " · 失败 " + batch.failed
                    + " · 未匹配或无法检查 " + (batch.done - batch.passed - batch.failed));
            if (batch.stopped || batch.done == selected.size()) {
                persistDiagnostics();
                if (screen == Screen.SOURCES) showSources();
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
            } else if (batch.done > 0 && SystemClock.elapsedRealtime() - batch.lastPersisted >= 15_000) {
                persistDiagnostics();
                batch.lastPersisted = SystemClock.elapsedRealtime();
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(batch.done > 0 && (batch.stopped || batch.done == selected.size()));
        };
        batch.run = sourceChecks.start(selected, ReaderEngine::searchRoute,
                source -> inspectSource(source, keyword, ranking, stage -> batch.stages.put(source.id, stage), ignored -> {}),
                source -> {}, (source, record, failure) -> {
                    ReaderSourceDiagnostics.Stage stage = batch.stages.remove(source.id);
                    ReaderSourceDiagnostics.Record outcome = failure == null && record != null ? record
                            : new ReaderSourceDiagnostics.Record(source,
                            stage == null ? ReaderSourceDiagnostics.Stage.SEARCH : stage,
                            ReaderSourceDiagnostics.Status.FAILED, ReaderSourceDiagnostics.reason(failure));
                    if (batch.offer(outcome)) main.postDelayed(refresh, 140);
                }, 18_000);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            dialog.dismiss();
            showDiagnosticsReport();
        });
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
            if (batch.stopped || batch.done == selected.size()) {
                dialog.dismiss();
                return;
            }
            batch.run.cancel();
            main.removeCallbacks(refresh);
            applyDiagnosticBatch(batch, true);
            batch.stopped = batch.done < selected.size();
            refresh.run();
        });
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            batch.run.cancel();
            main.removeCallbacks(refresh);
            applyDiagnosticBatch(batch, true);
            persistDiagnostics();
            if (!destroyed && screen == Screen.SOURCES) showSources();
        });
    }

    private void applyDiagnosticBatch(DiagnosticBatch batch, boolean close) {
        for (ReaderSourceDiagnostics.Record record : batch.drain(close)) {
            sourceDiagnostics.put(record);
            batch.done++;
            if (record.status == ReaderSourceDiagnostics.Status.PASSED) batch.passed++;
            if (record.status == ReaderSourceDiagnostics.Status.FAILED) batch.failed++;
        }
    }

    private void persistDiagnostics() {
        searchSettings.edit().putString("checks_" + searchHealthKey, sourceDiagnostics.serialize()).apply();
    }

    private void showDiagnosticsReport() {
        List<ReaderSources.Source> all = sources.list(kind);
        int[] count = sourceDiagnostics.counts(all);
        int tested = 0;
        StringBuilder summary = new StringBuilder("源检查报告\n");
        for (ReaderSourceDiagnostics.Status status : ReaderSourceDiagnostics.Status.values()) {
            int value = count[status.ordinal()];
            tested += value;
            summary.append("\n").append(status.label).append(" ").append(value);
        }
        summary.append("\n未检查 ").append(all.size() - tested);
        LinearLayout options = column();
        options.setPadding(dp(20), 0, dp(20), dp(6));
        options.addView(text(summary.toString(), 14, FG, false), lp(M, W));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("检查报告").setView(options)
                .setNegativeButton("关闭", null));
        options.addView(action("保存完整 TSV", true, () -> { dialog.dismiss(); chooseDiagnosticsDestination(); }), lp(M, 44));
        options.addView(action("复制摘要", false, () -> {
            ClipboardManager clipboard = host.getSystemService(ClipboardManager.class);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("检查报告", summary.toString()));
                toast("已复制检查摘要");
            }
        }), lp(M, 44));
        options.addView(action("选择要停用的失败来源", false, () -> { dialog.dismiss(); selectFailedSources(all); }), lp(M, 44));
    }

    private void chooseDiagnosticsDestination() {
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/tab-separated-values")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "源检查报告.tsv");
        try {
            host.startActivityForResult(picker, manga ? COMIC_DIAGNOSTICS_EXPORT : NOVEL_DIAGNOSTICS_EXPORT);
        } catch (ActivityNotFoundException unavailable) {
            toast("此设备没有可用的文件保存工具");
        }
    }

    private void writeDiagnosticsReport(Uri destination) {
        if (destroyed) return;
        List<ReaderSources.Source> catalog = sources.list(kind);
        String snapshot = sourceDiagnostics.serialize();
        imports.submit(() -> {
            try (OutputStream output = host.getContentResolver().openOutputStream(destination, "wt")) {
                if (output == null) throw new IOException("无法打开所选文件");
                try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                    writer.write('\ufeff');
                    new ReaderSourceDiagnostics(snapshot).writeReport(writer, catalog);
                }
                main.post(() -> toast("检查报告已保存"));
            } catch (IOException | SecurityException error) {
                main.post(() -> toast("无法保存检查报告，请重试"));
            }
        });
    }

    private void selectFailedSources(List<ReaderSources.Source> catalog) {
        List<ReaderSources.Source> candidates = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (ReaderSources.Source source : catalog) {
            ReaderSourceDiagnostics.Record record = sourceDiagnostics.get(source);
            if (source.enabled && record != null && record.status == ReaderSourceDiagnostics.Status.FAILED) {
                candidates.add(source);
                labels.add(source.name + " · " + record.label());
            }
        }
        if (candidates.isEmpty()) {
            toast("没有已启用且检查失败的来源");
            return;
        }
        boolean[] selected = new boolean[candidates.size()];
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("勾选要停用的来源")
                .setMultiChoiceItems(labels.toArray(new String[0]), selected, (list, index, value) -> selected[index] = value)
                .setPositiveButton("下一步", null).setNegativeButton("取消", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            Map<String, Integer> revisions = new LinkedHashMap<>();
            for (int i = 0; i < selected.length; i++) {
                if (selected[i]) {
                    ReaderSources.Source source = candidates.get(i);
                    revisions.put(source.id, source.diagnosticRevision);
                }
            }
            if (revisions.isEmpty()) { toast("请先勾选来源"); return; }
            dialog.dismiss();
            showDialog(new AlertDialog.Builder(host).setTitle("停用 " + revisions.size() + " 个来源？")
                    .setMessage("停用后可在来源列表重新启用。")
                    .setNegativeButton("取消", null).setPositiveButton("确认停用", (confirm, which) -> editSources(() -> {
                        int disabled = sources.disableChecked(kind, revisions);
                        main.post(() -> toast("实际停用 " + disabled + " 个来源"));
                        return null;
                    }, "")));
        });
    }

    private void askSourceCheck(ReaderSources.Source source) {
        if (source.searchUrl.isEmpty()) {
            showSourceCheck(source, "");
            return;
        }
        EditText keyword = input("输入已有的" + sectionName + "名称", false);
        keyword.setSingleLine(true);
        keyword.setText(query);
        LinearLayout field = column();
        field.setPadding(dp(20), 0, dp(20), 0);
        field.addView(keyword, lp(M, 52));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("检查来源")
                .setView(field).setNegativeButton("取消", null).setPositiveButton("开始", null));
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = keyword.getText().toString().trim();
            if (value.isEmpty()) {
                keyword.setError("请输入书名");
                return;
            }
            dialog.dismiss();
            showSourceCheck(source, value);
        });
    }

    private void showSourceCheck(ReaderSources.Source source, String keyword) {
        TextView output = text("", 14, FG, false);
        output.setTextIsSelectable(true);
        LinearLayout report = column();
        report.setPadding(dp(20), dp(5), dp(20), dp(12));
        View pending = loading("正在检查来源…");
        report.addView(pending, lp(M, W));
        report.addView(output, lp(M, W));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(report, new ScrollView.LayoutParams(M, W));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle(source.name)
                .setView(scroll).setNegativeButton("取消", null));
        StringBuilder log = new StringBuilder();
        Consumer<String> emit = message -> main.post(() -> {
            if (destroyed || !dialog.isShowing()) return;
            if (log.length() > 0) log.append('\n');
            log.append(message);
            output.setText(log.toString());
        });
        Future<?> task = network.submit(() -> {
            ReaderSourceDiagnostics.Record record = inspectSource(source, keyword, null, ignored -> {}, emit);
            if (Thread.currentThread().isInterrupted()) return;
            main.post(() -> {
                if (destroyed || !dialog.isShowing()) return;
                sourceDiagnostics.put(record);
                persistDiagnostics();
                if (screen == Screen.SOURCES) showSources();
                pending.setVisibility(View.GONE);
                dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setText("关闭");
            });
        });
        dialog.setOnDismissListener(ignored -> {
            dialogs.remove(dialog);
            task.cancel(true);
        });
    }

    private ReaderSourceDiagnostics.Record inspectSource(ReaderSources.Source source, String keyword,
            ReaderSearchRanking exactTitle, Consumer<ReaderSourceDiagnostics.Stage> steps, Consumer<String> emit) {
        ReaderSourceDiagnostics.Stage stage = source.searchUrl.isEmpty() ? ReaderSourceDiagnostics.Stage.DISCOVERY
                : ReaderSourceDiagnostics.Stage.SEARCH;
        try {
            String issue = YiciyuanRules.readingIssue(source.definition);
            if (!issue.isEmpty()) {
                emit.accept(issue);
                return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.UNSUPPORTED, "");
            }
            steps.accept(stage);
            emit.accept("正在" + stage.label + "…");
            List<ReaderEngine.Book> books;
            if (source.searchUrl.isEmpty()) {
                if (exactTitle != null) {
                    return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.NO_ENTRY, "");
                }
                List<ReaderEngine.Explore> options = ReaderEngine.exploreEntries(source);
                if (options.isEmpty()) {
                    emit.accept("没有可用的搜索或发现入口");
                    return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.NO_ENTRY, "");
                }
                books = ReaderEngine.explore(source, options.get(0).url, 1);
            } else {
                books = ReaderEngine.search(source, keyword, 1);
            }
            emit.accept(stage.label + "返回 " + books.size() + " 部作品");
            ReaderEngine.Book book = null;
            for (ReaderEngine.Book candidate : books) {
                if (exactTitle == null || exactTitle.exactTitle(candidate.title)) { book = candidate; break; }
            }
            if (book == null) {
                return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.NO_MATCH, "");
            }
            stage = ReaderSourceDiagnostics.Stage.CATALOGUE;
            steps.accept(stage);
            emit.accept("正在检查《" + book.title + "》的详情和目录…");
            try {
                book = ReaderEngine.details(book);
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
                if (budget != null) budget.check();
                emit.accept("详情读取失败 · " + ReaderSourceDiagnostics.reasonLabel(ReaderSourceDiagnostics.reason(unavailable)));
            }
            List<ReaderEngine.Chapter> entries = ReaderEngine.chapters(book);
            emit.accept("目录返回 " + entries.size() + " 章");
            ReaderEngine.Chapter chapter = entries.get(0);
            emit.accept("正在读取「" + chapter.title + "」…");
            stage = ReaderSourceDiagnostics.Stage.CONTENT;
            steps.accept(stage);
            ReaderEngine.Page page = ReaderEngine.open(chapter);
            if (source.kind == ReaderSources.COMIC) {
                emit.accept("解析到 " + page.images.size() + " 张图片");
                stage = ReaderSourceDiagnostics.Stage.IMAGE;
                steps.accept(stage);
                String imageRule = page.images.get(0);
                String imageUrl = ReaderEngine.imageAddress(imageRule);
                byte[] image = ReaderSources.fetchImage(imageUrl, imageHeaders(source, page.url, imageUrl,
                        ReaderEngine.imageRequestHeaders(imageRule), true));
                Bitmap bitmap = decodeImage(image, 120, 180);
                if (bitmap == null) {
                    emit.accept("首图失败 · 图片无法解码");
                    return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.FAILED, "DECODE");
                }
                bitmap.recycle();
                emit.accept("首图下载和解码成功");
            } else {
                emit.accept("正文读取成功，共 " + page.text.length() + " 字符");
            }
            return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.PASSED, "");
        } catch (IOException | RuntimeException | StackOverflowError | LinkageError error) {
            String reason = ReaderSourceDiagnostics.reason(error);
            if (!Thread.currentThread().isInterrupted()) {
                emit.accept(stage.label + "失败 · " + ReaderSourceDiagnostics.reasonLabel(reason));
            }
            return new ReaderSourceDiagnostics.Record(source, stage, ReaderSourceDiagnostics.Status.FAILED, reason);
        }
    }

    private void confirmRemove(ReaderSources.Source source) {
        showDialog(new AlertDialog.Builder(host).setTitle("移除此源？")
                .setMessage("已下载的章节仍可离线阅读。在线章节需要重新导入该来源，或为作品换源。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (confirm, action) -> editSources(() -> {
                    sources.remove(source);
                    return null;
                }, "已移除")));
    }

    private void chooseLocal() {
        if (!manga) {
            openLocalPicker(LOCAL_NOVEL_FILE);
            return;
        }
        showDialog(new AlertDialog.Builder(host).setTitle("导入本地漫画")
                .setItems(new String[]{"CBZ 或 ZIP 文件", "图片目录"},
                        (dialog, index) -> openLocalPicker(index == 0 ? LOCAL_MANGA_FILE : LOCAL_MANGA_DIRECTORY))
                .setNegativeButton("取消", null));
    }

    private void openLocalPicker(int request) {
        Intent picker = request == LOCAL_MANGA_DIRECTORY ? new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                : new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { host.startActivityForResult(picker, request); }
        catch (ActivityNotFoundException unavailable) { toast("此设备没有可用的文件选择器"); }
    }

    private void importLocal(Uri uri, boolean directory) {
        if (!"content".equals(uri.getScheme())) { toast("请从系统文件选择器选择本地内容"); return; }
        toast("正在导入本地" + sectionName + "…");
        int epoch = viewEpoch;
        imports.submit(() -> {
            try {
                ReaderEngine.Book imported = directory ? importLocalDirectory(uri)
                        : localBooks.importFile(localDisplayName(uri), kind, () -> host.getContentResolver().openInputStream(uri));
                progress.favorite(imported, true);
                main.post(() -> {
                    if (destroyed) return;
                    if (isCurrent(epoch, Screen.HOME)) selectBook(imported);
                    else toast("已加入本地书架");
                });
            } catch (IOException | RuntimeException invalid) {
                main.post(() -> { if (!destroyed) toast("本地导入失败 · " + problem(invalid)); });
            }
        });
    }

    private String localDisplayName(Uri uri) {
        try (Cursor cursor = host.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0);
        }
        return firstNonempty(uri.getLastPathSegment(), "本地文件");
    }

    private ReaderEngine.Book importLocalDirectory(Uri tree) throws IOException {
        String id = DocumentsContract.getTreeDocumentId(tree);
        Uri current = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
        List<ReaderLocalBooks.ImageFile> pages = new ArrayList<>();
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = host.getContentResolver().query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取图片目录");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))
                        || !ReaderLocalBooks.supportsImage(name)) continue;
                if (pages.size() >= 5_000) throw new IOException("图片目录包含过多页面");
                Uri image = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                pages.add(new ReaderLocalBooks.ImageFile(name, () -> host.getContentResolver().openInputStream(image)));
            }
        }
        return localBooks.importImages(localDisplayName(current), pages);
    }

    private void chooseFile() {
        if (editingSources) return;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            host.startActivityForResult(picker, pickerRequestCode);
        } catch (ActivityNotFoundException error) {
            toast("此设备没有可用的文件选择器");
        }
    }

    private void askImport(boolean url) {
        if (editingSources) return;
        String hint = url
                ? "粘贴源链接、仓库地址或合集详情页链接"
                : "粘贴完整的源规则";
        EditText field = input(hint, !url);
        if (url) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        else field.setMinLines(4);
        LinearLayout box = column();
        box.setPadding(dp(21), dp(8), dp(21), dp(3));
        box.addView(field, lp(M, W));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host)
                .setTitle(url ? "从链接导入" : "粘贴规则")
                .setView(box).setNegativeButton("取消", null).setPositiveButton("预览", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            String value = field.getText().toString().trim();
            if (value.isEmpty()) { field.setError("请输入内容"); return; }
            dialog.dismiss();
            previewSources(() -> url ? sources.prepareUrl(value, ReaderSources.ALL)
                    : sources.prepareText(value, ReaderSources.ALL));
        });
    }

    private void attachTrySample(LinearLayout parent) {
        LinearLayout.LayoutParams params = lp(W, 42);
        params.gravity = Gravity.CENTER_HORIZONTAL;
        params.topMargin = dp(8);
        TextView trial = action("试读示例", false, this::trySample);
        trial.setEnabled(!editingSources);
        parent.addView(trial, params);
    }

    private void trySample() {
        toast("正在载入示例…");
        editSources(() -> {
            try (InputStream input = host.getAssets().open(sampleAsset)) {
                return sources.importStream(input, kind);
            }
        }, "sample");
    }

    private interface PlanOperation { ReaderSources.ImportPlan run() throws Exception; }
    private interface SourceOperation { ReaderSources.ImportResult run() throws Exception; }

    private void previewSources(PlanOperation operation) {
        if (editingSources || destroyed) return;
        editingSources = true;
        refreshSourceScreen();
        imports.submit(() -> {
            try {
                ReaderSources.ImportPlan plan = operation.run();
                main.post(() -> showImportPlan(plan));
            } catch (Exception error) {
                main.post(() -> finishSourceEdit(null, "", problem(error)));
            }
        });
    }

    private void showImportPlan(ReaderSources.ImportPlan plan) {
        if (destroyed) return;
        editingSources = false;
        refreshSourceScreen();
        int comics = 0;
        int novels = 0;
        for (ReaderSources.Source source : plan.sources) {
            if (source.kind == ReaderSources.COMIC) comics++;
            else novels++;
        }
        StringBuilder report = new StringBuilder();
        report.append("漫画源 ").append(comics).append(" 个 · 小说源 ").append(novels).append(" 个\n")
                .append(importSummary(plan.added, plan.updated, plan.skipped));
        int count = Math.min(8, plan.sources.size());
        for (int i = 0; i < count; i++) {
            ReaderSources.Source source = plan.sources.get(i);
            report.append("\n").append(source.kind == ReaderSources.COMIC ? "漫画  " : "小说  ")
                    .append(source.name);
        }
        if (count < plan.sources.size()) {
            report.append("\n另有 ").append(plan.sources.size() - count).append(" 个来源");
        }
        if (!plan.diagnostics.isEmpty()) {
            report.append("\n\n").append(TextUtils.join("\n", plan.diagnostics));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(host)
                .setTitle(plan.sources.isEmpty() ? "没有可导入的来源" : "导入预览")
                .setMessage(report.toString())
                .setNegativeButton(plan.sources.isEmpty() ? "关闭" : "取消", null);
        if (!plan.sources.isEmpty()) {
            dialog.setPositiveButton("确认导入", (view, which) -> editSources(() -> sources.apply(plan), ""));
        }
        showDialog(dialog);
    }

    private String importSummary(int added, int updated, int skipped) {
        return "新增 " + added + " 个，更新 " + updated + " 个，跳过 " + skipped + " 个";
    }

    private void editSources(SourceOperation operation, String completion) {
        if (editingSources || destroyed) return;
        editingSources = true;
        refreshSourceScreen();
        imports.submit(() -> {
            try {
                ReaderSources.ImportResult result = operation.run();
                main.post(() -> finishSourceEdit(result, completion, ""));
            } catch (Exception error) {
                main.post(() -> finishSourceEdit(null, "", problem(error)));
            }
        });
    }

    private void finishSourceEdit(ReaderSources.ImportResult result, String completion, String error) {
        if (destroyed) return;
        editingSources = false;
        refreshSourceScreen();
        if (!error.isEmpty()) { toast(error); return; }
        if (result == null) {
            if (!completion.isEmpty()) toast(completion);
            return;
        }
        if ("sample".equals(completion) && !result.sources.isEmpty()) {
            ReaderSources.Source source = result.sources.get(0);
            try {
                List<ReaderEngine.Explore> options = ReaderEngine.exploreEntries(source);
                if (!options.isEmpty()) {
                    showExplore(source, options.get(0));
                    return;
                }
            } catch (IOException exploreError) {
                toast(problem(exploreError));
            }
        }
        toast(importSummary(result.added, result.updated, result.skipped));
    }

    private void refreshSourceScreen() {
        if (screen == Screen.SOURCES) showSources();
        else if (screen == Screen.HOME) {
            if (search != null) query = search.getText().toString();
            showHome();
        }
    }

    private void selectBook(ReaderEngine.Book selected) {
        selectBook(selected, "");
    }

    private void selectBook(ReaderEngine.Book selected, String reopenChapter) {
        selectBook(selected, reopenChapter, false);
    }

    private void selectBook(ReaderEngine.Book selected, String reopenChapter, boolean fromHistory) {
        hideKeyboard();
        returnToHistory = fromHistory;
        book = selected;
        chapters = Collections.emptyList();
        downloadedChaptersOnly = false;
        chapterIndex = -1;
        controlsVisible = false;
        showContents(true, reopenChapter);
    }

    private void leaveCatalogue() {
        if (returnToHistory) showHistory();
        else showHome();
    }

    private void showContents(boolean fetch) {
        showContents(fetch, "", false);
    }

    private void showContents(boolean fetch, String reopenChapter) {
        showContents(fetch, reopenChapter, false);
    }

    private void showContents(boolean fetch, String reopenChapter, boolean refresh) {
        reset(Screen.CHAPTERS);
        LinearLayout header = topBar("作品目录", "", this::leaveCatalogue);
        if (!localBook(book)) header.addView(action("刷新", false, () -> showContents(true, "", true)), lp(W, 44));
        scene.addView(header, lp(M, W));
        renderContents(fetch, "");
        if (!fetch) return;
        int epoch = viewEpoch;
        ReaderEngine.Book selected = book;
        boolean resumeHistory = returnToHistory && !refresh && !reopenChapter.isEmpty();
        pageTask = navigation.submit(() -> {
            List<ReaderEngine.Chapter> cached = localBook(selected) ? null : offline.loadCatalogue(selected);
            try {
                if (localBook(selected)) {
                    List<ReaderEngine.Chapter> chapters = localBooks.chapters(selected);
                    main.post(() -> useCatalogue(epoch, selected, chapters, reopenChapter, ""));
                    return;
                }
                if (sources.find(selected.source.id) == null) {
                    List<ReaderEngine.Chapter> saved = offline.downloadedCatalogue(selected);
                    if (saved == null || saved.isEmpty()) {
                        main.post(() -> {
                            if (isCurrent(epoch, Screen.CHAPTERS)) renderContents(false, "来源已移除，也没有可用的离线章节");
                        });
                    } else {
                        main.post(() -> useCatalogue(epoch, selected, saved, reopenChapter, "来源已移除，现显示已下载章节", resumeHistory));
                    }
                    return;
                }
                if (!refresh && cached != null && !cached.isEmpty() && (!resumeHistory
                        || cached.stream().anyMatch(chapter -> chapter.url.equals(reopenChapter)))) {
                    main.post(() -> useCatalogue(epoch, selected, cached, reopenChapter, ""));
                    return;
                }
                if (resumeHistory) {
                    List<ReaderEngine.Chapter> saved = offline.downloadedCatalogue(selected);
                    if (saved != null && saved.stream().anyMatch(chapter -> chapter.url.equals(reopenChapter))) {
                        main.post(() -> useCatalogue(epoch, selected, saved, reopenChapter, "", true));
                        return;
                    }
                }
                ReaderEngine.Book details;
                try {
                    details = ReaderEngine.details(selected);
                } catch (IOException unavailable) {
                    details = selected;
                }
                ReaderEngine.Book resolved = details;
                List<ReaderEngine.Chapter> loaded = ReaderEngine.chapters(resolved);
                if (!loaded.isEmpty()) offline.storeCatalogue(resolved, loaded);
                if (loaded.isEmpty() && cached != null && !cached.isEmpty()) {
                    main.post(() -> useCatalogue(epoch, selected, cached, reopenChapter, "未获取到新目录，已显示缓存。"));
                } else {
                    main.post(() -> useCatalogue(epoch, resolved, loaded, reopenChapter, ""));
                }
            } catch (Exception error) {
                main.post(() -> {
                    if (!isCurrent(epoch, Screen.CHAPTERS)) return;
                    if (cached != null && !cached.isEmpty()) {
                        useCatalogue(epoch, selected, cached, reopenChapter, "刷新失败，已显示缓存目录。");
                    } else renderContents(false, problem(error));
                });
            }
        });
    }

    private void useCatalogue(int epoch, ReaderEngine.Book resolved, List<ReaderEngine.Chapter> loaded,
                              String reopenChapter, String message) {
        useCatalogue(epoch, resolved, loaded, reopenChapter, message, false);
    }

    private void useCatalogue(int epoch, ReaderEngine.Book resolved, List<ReaderEngine.Chapter> loaded,
                              String reopenChapter, String message, boolean onlyDownloaded) {
        if (!isCurrent(epoch, Screen.CHAPTERS)) return;
        book = resolved;
        chapters = loaded;
        downloadedChaptersOnly = onlyDownloaded;
        int reopen = reopenChapter.isEmpty() ? -1 : chapterAt(reopenChapter);
        if (reopen >= 0) openChapter(reopen, true);
        else {
            historyVisitBook = "";
            if (!message.isEmpty()) toast(message);
            renderContents(false, "");
        }
    }

    private void renderContents(boolean busy, String error) {
        if (scene.getChildCount() > 1) scene.removeViews(1, scene.getChildCount() - 1);
        List<Cell> cells = new ArrayList<>();
        ReaderProgress.Entry old = progress.get(book);
        int lastRead = previousChapter(old);
        for (int i = 0; i < chapters.size(); i++) {
            int index = i;
            cells.add(new Cell(chapters.get(i).title, "", i == lastRead ? "读到这里" : null,
                    "", null, () -> openChapter(index, true)));
        }
        ListView list = newList();
        list.addHeaderView(bookOverview(old, lastRead, busy, error), null, false);
        bindList(list, cells);
        scene.addView(list, new LinearLayout.LayoutParams(M, 0, 1));
    }

    private LinearLayout bookOverview(ReaderProgress.Entry old, int lastRead, boolean busy, String error) {
        LinearLayout container = column();
        container.setPadding(0, dp(4), 0, dp(6));
        LinearLayout panel = column();
        panel.setBackground(surface(host, 0xffffedf5, 21, LINE));
        panel.setPadding(dp(14), dp(14), dp(14), dp(13));
        LinearLayout summary = row();
        summary.setGravity(Gravity.CENTER_VERTICAL);
        ImageSlot cover = new ImageSlot(false);
        cover.bind(book.cover, book.source, book.url);
        summary.addView(cover, lp(70, 94));
        LinearLayout title = column();
        title.setPadding(dp(13), 0, 0, 0);
        TextView name = text(book.title, 23, FG, true);
        markHeading(name);
        name.setMaxLines(3);
        name.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(name, lp(M, W));
        TextView author = text(join(book.author, book.source.name), 13, BERRY, false);
        author.setPadding(0, dp(5), 0, 0);
        title.addView(author, lp(M, W));
        summary.addView(title, weight(W));
        panel.addView(summary, lp(M, W));

        if (!TextUtils.isEmpty(book.summary)) {
            TextView abstractText = text(book.summary, 14, MUTED, false);
            abstractText.setPadding(0, dp(12), 0, dp(3));
            abstractText.setMaxLines(4);
            abstractText.setEllipsize(TextUtils.TruncateAt.END);
            abstractText.setOnClickListener(view -> {
                boolean expand = abstractText.getMaxLines() == 4;
                abstractText.setMaxLines(expand ? Integer.MAX_VALUE : 4);
            });
            panel.addView(abstractText, lp(M, W));
        }
        LinearLayout actions = row();
        actions.setPadding(0, dp(12), 0, 0);
        TextView read = action(lastRead >= 0 ? "继续阅读" : "开始阅读", true,
                () -> openChapter(Math.max(lastRead, 0), true));
        read.setEnabled(!chapters.isEmpty());
        addEqual(actions, read, false);
        boolean favored = old != null && old.favorite;
        String favoriteLabel = favored ? "取消收藏" : old != null && old.hiddenOnShelf ? "放回书架" : "收藏";
        addEqual(actions, action(favoriteLabel, false, () -> {
            ReaderProgress.Entry entry = progress.get(book);
            progress.favorite(book, entry == null || !entry.favorite);
            TextView button = (TextView) actions.getChildAt(1);
            ReaderProgress.Entry updated = progress.get(book);
            button.setText(updated != null && updated.favorite ? "取消收藏" : "收藏");
        }), true);
        if (!localBook(book)) {
            addEqual(actions, action("换源", false, this::showAlternateSources), true);
        }
        panel.addView(actions, lp(M, W));
        if (localBook(book)) panel.addView(action("删除导入的副本", false, this::removeLocalBook), lp(M, 44));
        if (!chapters.isEmpty()) {
            LinearLayout tools = row();
            tools.setPadding(0, dp(7), 0, 0);
            addEqual(tools, action("查找章节", false, this::showChapterPicker), false);
            addEqual(tools, action("书签", false, this::showBookmarks), true);
            if (!localBook(book)) addEqual(tools, action("离线章节", false, this::showDownloadedChapters), true);
            panel.addView(tools, lp(M, W));
            if (!localBook(book) && sources.find(book.source.id) != null) {
                panel.addView(action("批量下载章节", false, this::chooseChapterDownloads), lp(M, 44));
            }
        }
        container.addView(panel, lp(M, W));
        if (busy) container.addView(loading("正在读取目录…"), lp(M, W));
        else if (!error.isEmpty() || chapters.isEmpty()) {
            container.addView(note(firstNonempty(error, "没有读取到章节")), lp(M, W));
            container.addView(action("重新读取", false, () -> showContents(true)), lp(W, 40));
        } else {
            TextView directory = text("全部章节 · " + chapters.size(), 16, FG, true);
            directory.setPadding(0, dp(16), 0, dp(6));
            markHeading(directory);
            container.addView(directory, lp(M, W));
        }
        return container;
    }

    private int previousChapter(ReaderProgress.Entry old) {
        return old == null ? -1 : chapterAt(old.chapterUrl);
    }

    private int chapterAt(String chapterUrl) {
        if (chapterUrl.isEmpty()) return -1;
        for (int i = 0; i < chapters.size(); i++) {
            if (chapterUrl.equals(chapters.get(i).url)) return i;
        }
        return -1;
    }

    private void showChapterPicker() {
        int current = isReading() ? chapterIndex : previousChapter(progress.get(book));
        showChapterPicker("章节目录", chapters, current, selected -> {
            if (!isReading() || selected != chapterIndex) openChapter(selected, true);
        });
    }

    private int chapterPickerHeight() {
        return Math.min(360, (int) (host.getResources().getDisplayMetrics().heightPixels
                / host.getResources().getDisplayMetrics().density * 0.43f));
    }

    private void showChapterPicker(String title, List<ReaderEngine.Chapter> chapters, int current,
                                   IntConsumer onSelect) {
        LinearLayout chooser = column();
        chooser.setPadding(dp(18), 0, dp(18), dp(8));
        EditText filter = input("输入章节名或编号", false);
        filter.setImeOptions(EditorInfo.IME_ACTION_DONE);
        chooser.addView(filter, lp(M, 50));
        ListView list = newList();
        list.setPadding(0, 0, 0, 0);
        chooser.addView(list, lp(M, chapterPickerHeight()));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle(title)
                .setView(chooser).setNegativeButton("关闭", null));
        placeReadingSheet(dialog);
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        Runnable refresh = () -> {
            String keyword = filter.getText().toString().trim().toLowerCase(Locale.ROOT);
            List<Cell> proposals = new ArrayList<>();
            for (int index = 0; index < chapters.size(); index++) {
                ReaderEngine.Chapter chapter = chapters.get(index);
                if (!chapter.title.toLowerCase(Locale.ROOT).contains(keyword)
                        && !Integer.toString(index + 1).equals(keyword)) continue;
                int selected = index;
                proposals.add(new Cell(chapter.title, "", index == current ? "当前" : null,
                        "", null, () -> {
                            dialog.dismiss();
                             onSelect.accept(selected);
                        }));
            }
            bindList(list, proposals);
            if (keyword.isEmpty() && current >= 0) list.setSelection(Math.max(0, current - 1));
        };
        filter.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        refresh.run();
    }

    private void addBookmark() {
        ReaderProgress.Position position = readingPosition();
        if (position == null) return;
        String label = position.format == ReaderProgress.TEXT_OFFSET ? readingPercent(position.offset)
                : "第 " + (position.page + 1) + " 页";
        boolean added = progress.addBookmark(chapters.get(chapterIndex), position, label);
        toast(added ? "已添加书签" : "此处已有书签");
    }

    private void showBookmarks() {
        List<ReaderProgress.Bookmark> saved = progress.bookmarks(book);
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("书签")
                .setNegativeButton("关闭", null);
        if (saved.isEmpty()) dialog.setMessage("阅读时可在右上角菜单添加书签。");
        else {
            String[] labels = new String[saved.size()];
            for (int i = 0; i < saved.size(); i++) labels[i] = join(saved.get(i).title, saved.get(i).label);
            dialog.setItems(labels, (ignored, index) -> manageBookmark(saved.get(index)));
        }
        showDialog(dialog);
    }

    private void manageBookmark(ReaderProgress.Bookmark bookmark) {
        int index = chapterAt(bookmark.chapterUrl);
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle(bookmark.title)
                .setMessage(index >= 0 ? bookmark.label : "当前目录中找不到此章节。")
                .setNeutralButton("删除书签", (ignored, button) -> {
                    progress.removeBookmark(book, bookmark);
                    showBookmarks();
                }).setNegativeButton("返回", (ignored, button) -> showBookmarks());
        if (index >= 0) dialog.setPositiveButton("跳转", (ignored, button) -> {
            if (!isReading() || index != chapterIndex || loadedPage == null) {
                openChapter(index, bookmark.position);
            } else if (mangaReader != null) {
                mangaReader.restorePosition(bookmark.position.page, mangaOffset(bookmark.position));
            } else if (novelReader != null) {
                novelReader.jumpTo(bookmark.position.offset);
            }
        });
        showDialog(dialog);
    }

    private void openChapter(int index, boolean resume) {
        if (index < 0 || index >= chapters.size()) return;
        ReaderProgress.Entry exit = resume ? progress.get(book) : null;
        openChapter(index, exit != null && chapters.get(index).url.equals(exit.chapterUrl) ? exit.position : null);
    }

    private void openChapter(int index, ReaderProgress.Position position) {
        openChapter(index, position, false);
    }

    private void openChapter(int index, ReaderProgress.Position position, boolean enterAtEnd) {
        int previous = isReading() ? chapterIndex : -1;
        savePosition();
        hideKeyboard();
        reset(Screen.READER);
        chapterIndex = index;
        ReaderEngine.Chapter chapter = chapters.get(index);
        readerHeader = topBar(book.title, chapter.title, this::onBackPressed);
        readerHeader.setBackgroundColor(READER_CHROME);
        TextView settings = action("设置", false, this::showReadingSettings);
        LinearLayout.LayoutParams settingsSize = lp(W, 44);
        settingsSize.setMarginEnd(dp(6));
        readerHeader.addView(settings, settingsSize);
        View more = iconButton("more", "阅读菜单", this::showReadingMenu);
        readerHeader.addView(more, lp(44, 44));
        FrameLayout viewport = new FrameLayout(host);
        scene.addView(viewport, new LinearLayout.LayoutParams(M, 0, 1));
        FrameLayout body = new FrameLayout(host);
        body.addView(loading("正在载入章节…"), new FrameLayout.LayoutParams(M, W, Gravity.CENTER));
        viewport.addView(body, new FrameLayout.LayoutParams(M, M));
        readerHeader.setClickable(true);
        viewport.addView(readerHeader, new FrameLayout.LayoutParams(M, W, Gravity.TOP));
        readerTools = chapterControls(index);
        readerTools.setClickable(true);
        viewport.addView(readerTools, new FrameLayout.LayoutParams(M, W, Gravity.BOTTOM));
        updateReaderControls();
        updateReadingMeter();
        if (previous >= 0 && previous != index) PageMotion.enter(pageHost, index < previous);

        int epoch = viewEpoch;
        List<NovelTextTools.LiteralRule> cleanup = activeCleanup(chapter.book);
        pageTask = navigation.submit(() -> {
            try {
                ReaderEngine.Page local = localBook(chapter.book) ? localBooks.open(chapter) : offline.loadChapter(chapter);
                if (local == null && sources.find(chapter.book.source.id) == null) throw new IOException("此章节未下载，来源已移除");
                ReaderEngine.Page page = local == null ? ReaderEngine.open(chapter) : local;
                NovelTextTools.LiteralText novelText = manga && !page.images.isEmpty() ? null
                        : NovelTextTools.literalText(page.text, cleanup);
                main.post(() -> {
                    if (!isCurrent(epoch, Screen.READER)) return;
                    if ((!manga || page.images.isEmpty()) && TextUtils.isEmpty(page.text)) {
                        showChapterError(body, index, position, enterAtEnd, "此章节没有可阅读的内容");
                        return;
                    }
                    loadedPage = page;
                    body.removeAllViews();
                    boolean comicPages = manga && !page.images.isEmpty();
                    ReaderProgress.Position start = position;
                    if (enterAtEnd) {
                        start = comicPages
                                ? new ReaderProgress.Position(page.images.size() - 1, 1_000_000, ReaderProgress.IMAGE_FRACTION)
                                : new ReaderProgress.Position(0, Math.max(0, page.text.length() - 1), ReaderProgress.TEXT_OFFSET);
                    }
                    if (comicPages) showManga(body, chapter, page, start, epoch);
                    else showNovel(body, novelText, start, epoch);
                    String visit = ReaderProgress.key(chapter.book);
                    if (!visit.equals(historyVisitBook)) {
                        ReaderProgress.Position initial = readingPosition();
                        if (initial == null) initial = start == null
                                ? new ReaderProgress.Position(0, 0, comicPages
                                        ? ReaderProgress.IMAGE_FRACTION : ReaderProgress.TEXT_OFFSET) : start;
                        progress.mark(chapter, initial, chapters, chapterIndex, true);
                        historyVisitBook = visit;
                    }
                    int span = mangaReader != null ? page.images.size() : novelReader.length();
                    readerSeek.setMax(Math.max(1, span - 1));
                    readerSeek.setEnabled(span > 1);
                    updateReaderControls();
                    if (!readerSettings.getBoolean("seen_reading_gesture", false)) {
                        readerSettings.edit().putBoolean("seen_reading_gesture", true).apply();
                        toast("轻点画面中央，可打开目录和阅读设置");
                    }
                    positionChanged();
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (isCurrent(epoch, Screen.READER)) showChapterError(body, index, position, enterAtEnd, problem(error));
                });
            }
        });
    }

    private LinearLayout chapterControls(int index) {
        LinearLayout panel = column();
        panel.setBackgroundColor(READER_CHROME);
        panel.setPadding(dp(12), 0, dp(12), dp(9));
        LinearLayout progressRow = row();
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        readingStatus = text("", 12, READER_MUTED, false);
        readingStatus.setSingleLine();
        readingStatus.setPadding(dp(6), 0, 0, 0);
        progressRow.addView(readingStatus, weight(W));
        undoSeek = action("撤销跳转", false, this::undoReaderSeek);
        undoSeek.setVisibility(View.GONE);
        progressRow.addView(undoSeek, lp(W, 44));
        panel.addView(progressRow, lp(M, W));
        readerSeek = new SeekBar(host) {
            @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
                if (getScaleX() < 0) {
                    if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) keyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
                    else if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) keyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT;
                }
                return super.onKeyDown(keyCode, event);
            }
        };
        readerSeek.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        if (manga && mangaMode() == MangaReadingView.Mode.RTL) readerSeek.setScaleX(-1f);
        readerSeek.setContentDescription(manga ? "跳至漫画页" : "调整本章进度");
        readerSeek.setProgressTintList(ColorStateList.valueOf(READER_ACCENT));
        readerSeek.setThumbTintList(ColorStateList.valueOf(READER_ACCENT));
        readerSeek.setProgressBackgroundTintList(ColorStateList.valueOf(0xff67666a));
        readerSeek.setEnabled(false);
        readerSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onStartTrackingTouch(SeekBar bar) { seekingReader = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekingReader = false;
                seekWithUndo(bar.getProgress());
            }
            @Override public void onProgressChanged(SeekBar bar, int position, boolean user) {
                if (!user) return;
                if (seekingReader) updateReadingLabel(position);
                else seekWithUndo(position);
            }
        });
        panel.addView(readerSeek, lp(M, 44));
        LinearLayout bar = row();
        TextView previous = action("上一章", false, () -> moveChapter(-1));
        previous.setEnabled(index > 0);
        boolean hasNext = adjacentChapter(1) >= 0;
        TextView next = action("下一章", hasNext, () -> moveChapter(1));
        next.setEnabled(hasNext);
        addEqual(bar, previous, false);
        addEqual(bar, action("目录", false, this::showChapterPicker), true);
        addEqual(bar, next, true);
        panel.addView(bar, lp(M, W));
        return panel;
    }

    private void seekTo(int offset) {
        if (mangaReader != null) mangaReader.goToPage(offset);
        else if (novelReader != null) novelReader.jumpTo(offset);
        updateReadingMeter();
    }

    private void seekWithUndo(int offset) {
        ReaderProgress.Position current = readingPosition();
        if (current == null || offset == (mangaReader == null ? current.offset : current.page)) return;
        if (seekOrigin == null) seekOrigin = current;
        undoSeek.setVisibility(View.VISIBLE);
        seekTo(offset);
    }

    private void undoReaderSeek() {
        if (seekOrigin == null) return;
        if (mangaReader != null) mangaReader.restorePosition(seekOrigin.page, mangaOffset(seekOrigin));
        else if (novelReader != null) novelReader.jumpTo(seekOrigin.offset);
        seekOrigin = null;
        undoSeek.setVisibility(View.GONE);
        updateReadingMeter();
    }

    private int adjacentChapter(int direction) {
        if (chapterIndex < 0 || chapterIndex >= chapters.size()) return -1;
        if (direction > 0 && downloadedChaptersOnly) {
            int next = chapterAt(chapters.get(chapterIndex).nextChapterUrl);
            return next == chapterIndex ? -1 : next;
        }
        int target = chapterIndex + direction;
        return target >= 0 && target < chapters.size() ? target : -1;
    }

    private void moveChapter(int direction) {
        int target = adjacentChapter(direction);
        if (target >= 0) openChapter(target, false);
        else if (direction < 0) toast("已是第一章");
        else toast(downloadedChaptersOnly ? "离线列表中没有下一章" : "已是最后一章");
    }

    private void turnAcrossChapter(int direction) {
        if (direction < 0 && adjacentChapter(-1) >= 0) openChapter(chapterIndex - 1, null, true);
        else moveChapter(direction);
    }

    private void showChapterError(FrameLayout body, int index, ReaderProgress.Position position, boolean enterAtEnd, String message) {
        body.removeAllViews();
        body.addView(emptyCard("章节读取失败", message, "重试", () -> openChapter(index, position, enterAtEnd)),
                new FrameLayout.LayoutParams(M, W, Gravity.CENTER));
        controlsVisible = true;
        updateReaderControls();
    }

    private void showNovel(FrameLayout frame, NovelTextTools.LiteralText content, ReaderProgress.Position position, int epoch) {
        int offset = position != null && position.format == ReaderProgress.TEXT_OFFSET ? position.offset : 0;
        Integer legacy = position != null && position.format == ReaderProgress.PIXELS && !manga
                ? Math.max(0, position.offset) : null;
        novelReader = new NovelReadingView(host, content, offset, legacy,
                () -> { if (isCurrent(epoch, Screen.READER)) turnAcrossChapter(-1); },
                () -> { if (isCurrent(epoch, Screen.READER)) turnAcrossChapter(1); },
                () -> { if (isCurrent(epoch, Screen.READER)) positionChanged(); },
                () -> { if (isCurrent(epoch, Screen.READER)) toggleReaderControls(); });
        frame.addView(novelReader, new FrameLayout.LayoutParams(M, M));
        if (content.skipped) toast("清理结果为空或超过限制，已显示原文");
    }

    private void showManga(FrameLayout frame, ReaderEngine.Chapter chapter, ReaderEngine.Page page,
                           ReaderProgress.Position position, int epoch) {
        int index = position == null ? 0 : position.page;
        int offset = position == null ? 0 : mangaOffset(position);
        mangaReader = new MangaReadingView(host, page.images, mangaMode(), index, offset,
                new MangaReadingView.ImageLoader() {
                    @Override public void load(String image, int width, int height, MangaReadingView.ImageResult result) {
                        queuePageImage(chapter, page.url, image, width, height, bitmap -> {
                            if (isCurrent(epoch, Screen.READER)) result.onResult(bitmap);
                        });
                    }
                    @Override public void prefetch(String image, int width, int height) {
                        queuePageImage(chapter, page.url, image, width, height, null);
                    }
                }, new MangaReadingView.Callback() {
                    @Override public void onPosition(int page, int offset) {
                        if (isCurrent(epoch, Screen.READER)) positionChanged();
                    }
                    @Override public void onTapCenter() {
                        if (isCurrent(epoch, Screen.READER)) toggleReaderControls();
                    }
                    @Override public boolean hasAdjacentChapter(int direction) {
                        return isCurrent(epoch, Screen.READER) && adjacentChapter(direction) >= 0;
                    }
                    @Override public void onChapterBoundary(int direction) {
                        if (isCurrent(epoch, Screen.READER)) turnAcrossChapter(direction);
                    }
                });
        mangaReader.setFitWidth(readerSettings.getBoolean(mangaFitKey(), false));
        mangaReader.setCropWhiteBorders(readerSettings.getBoolean(mangaCropKey(), false));
        mangaReader.setDoublePage(readerSettings.getBoolean(mangaSpreadKey(), false));
        frame.addView(mangaReader, new FrameLayout.LayoutParams(M, M));
    }

    private static int mangaOffset(ReaderProgress.Position position) {
        if (position.format == ReaderProgress.PIXELS) return -Math.max(0, position.offset);
        return position.format == ReaderProgress.IMAGE_FRACTION ? position.offset : 0;
    }

    private String modeKey() { return "manga_mode_" + ReaderProgress.key(book); }

    private String mangaFitKey() { return "manga_fit_width_" + ReaderProgress.key(book); }

    private String mangaCropKey() { return "manga_crop_white_" + ReaderProgress.key(book); }

    private String mangaSpreadKey() { return "manga_spread_" + ReaderProgress.key(book); }

    private MangaReadingView.Mode mangaMode() {
        try {
            return MangaReadingView.Mode.valueOf(readerSettings.getString(modeKey(), "VERTICAL"));
        } catch (IllegalArgumentException ignored) {
            return MangaReadingView.Mode.VERTICAL;
        }
    }

    private void showReadingSettings() {
        if (novelReader != null) { novelReader.showSettings(); return; }
        MangaReadingView reader = mangaReader;
        if (reader == null) return;
        LinearLayout options = column();
        options.setPadding(dp(22), dp(4), dp(22), dp(8));
        android.widget.RadioGroup directions = new android.widget.RadioGroup(host);
        for (MangaReadingView.Mode mode : MangaReadingView.Mode.values()) {
            android.widget.RadioButton choice = new android.widget.RadioButton(host);
            choice.setText(mode.label);
            choice.setTextColor(FG);
            choice.setTag(mode);
            directions.addView(choice, lp(M, 48));
            if (reader.mode() == mode) directions.check(choice.getId());
        }
        options.addView(directions, lp(M, W));
        Switch fit = mangaOption(options, "单页适应宽度", mangaFitKey(), reader.fitsWidth(), reader::setFitWidth);
        Switch spread = mangaOption(options, "横向宽屏并排两页", mangaSpreadKey(), reader.showsDoublePage(), reader::setDoublePage);
        mangaOption(options, "裁切页边空白", mangaCropKey(), reader.cropsWhiteBorders(), reader::setCropWhiteBorders);
        Runnable refresh = () -> {
            boolean paged = reader.mode() != MangaReadingView.Mode.VERTICAL;
            fit.setEnabled(paged);
            spread.setEnabled(paged);
        };
        directions.setOnCheckedChangeListener((group, id) -> {
            View selected = group.findViewById(id);
            if (selected == null) return;
            MangaReadingView.Mode mode = (MangaReadingView.Mode) selected.getTag();
            readerSettings.edit().putString(modeKey(), mode.name()).apply();
            reader.setMode(mode);
            readerSeek.setScaleX(mode == MangaReadingView.Mode.RTL ? -1f : 1f);
            refresh.run();
        });
        refresh.run();
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("漫画阅读设置")
                .setView(scroll(options)).setPositiveButton("完成", null));
        placeReadingSheet(dialog);
    }

    private Switch mangaOption(LinearLayout panel, String label, String key, boolean checked,
                               java.util.function.Consumer<Boolean> apply) {
        Switch option = new Switch(host);
        option.setText(label);
        option.setChecked(checked);
        option.setOnCheckedChangeListener((view, enabled) -> {
            readerSettings.edit().putBoolean(key, enabled).apply();
            apply.accept(enabled);
        });
        panel.addView(option, lp(M, 48));
        return option;
    }

    static void placeReadingSheet(AlertDialog dialog) {
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        Context context = dialog.getContext();
        int width = Math.min(context.getResources().getDisplayMetrics().widthPixels, PlayerViews.dp(context, 600));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setLayout(width, W);
    }

    private void showNovelFind() {
        NovelReadingView reader = novelReader;
        if (reader == null || novelFind != null) return;
        reader.stopSpeech();
        novelFind = column();
        novelFind.setBackgroundColor(READER_CHROME);
        novelFind.setPadding(dp(10), dp(3), dp(10), dp(6));
        LinearLayout tools = row();
        tools.setGravity(Gravity.CENTER_VERTICAL);
        EditText keyword = input("查找正文", false);
        keyword.setTextColor(READER_TEXT);
        keyword.setHintTextColor(READER_MUTED);
        keyword.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(160)});
        keyword.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        tools.addView(keyword, weight(46));
        TextView feedback = text("", 12, READER_MUTED, false);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        feedback.setPadding(dp(3), dp(4), 0, 0);
        feedback.setVisibility(View.GONE);
        IntConsumer find = direction -> {
            String query = keyword.getText().toString().trim();
            if (query.isEmpty()) {
                keyword.setError("请输入关键词");
                return;
            }
            hideKeyboard();
            feedback.setVisibility(View.VISIBLE);
            feedback.setText("正在查找…");
            reader.findText(query, direction, position -> {
                if (novelReader == reader && novelFind != null) {
                    feedback.setText(position < 0 ? "本章没有匹配文字" : "已定位 · " + readingPercent(position));
                }
            });
        };
        addNovelFindAction(tools, "上一处", () -> find.accept(-1));
        addNovelFindAction(tools, "下一处", () -> find.accept(1));
        addNovelFindAction(tools, "关闭", this::closeNovelFind);
        keyword.setOnEditorActionListener((field, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            find.accept(1);
            return true;
        });
        keyword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                reader.clearFind();
                feedback.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable value) {}
        });
        novelFind.addView(tools, lp(M, W));
        novelFind.addView(feedback, lp(M, W));
        scene.addView(novelFind, 0, lp(M, W));
        updateReaderControls();
        keyword.requestFocus();
        keyword.post(() -> {
            if (keyword.isAttachedToWindow()) host.getSystemService(InputMethodManager.class)
                    .showSoftInput(keyword, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void addNovelFindAction(LinearLayout container, String label, Runnable click) {
        LinearLayout.LayoutParams size = lp(W, 44);
        size.setMarginStart(dp(4));
        container.addView(action(label, false, click), size);
    }

    private void closeNovelFind() {
        if (novelFind == null) return;
        hideKeyboard();
        if (novelReader != null) novelReader.clearFind();
        scene.removeView(novelFind);
        novelFind = null;
        updateReaderControls();
    }

    private String cleanupKey(ReaderEngine.Book selected) {
        return "novel_cleanup_" + ReaderProgress.key(selected);
    }

    private List<NovelTextTools.LiteralRule> activeCleanup(ReaderEngine.Book selected) {
        if (manga) return Collections.emptyList();
        String key = cleanupKey(selected);
        if (!readerSettings.getBoolean(key + "_enabled", false)) return Collections.emptyList();
        try {
            return NovelTextTools.literalRules(readerSettings.getString(key, ""));
        } catch (IllegalArgumentException invalid) {
            return Collections.emptyList();
        }
    }

    private void showNovelCleanup() {
        if (manga || novelReader == null || book == null) return;
        String key = cleanupKey(book);
        LinearLayout panel = column();
        panel.setPadding(dp(20), 0, dp(20), dp(8));
        Switch enabled = new Switch(host);
        enabled.setText("为这本书启用");
        enabled.setChecked(readerSettings.getBoolean(key + "_enabled", false));
        panel.addView(enabled, lp(M, 44));
        panel.addView(text("一行一条，按原文字面匹配。例：广告文字 =>\n替换内容留空即删除。", 13, MUTED, false),
                lp(M, W));
        EditText rules = input("原文 => 替换内容", true);
        rules.setMinLines(3);
        rules.setMaxLines(6);
        rules.setGravity(Gravity.TOP | Gravity.START);
        rules.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(8_000)});
        rules.setText(readerSettings.getString(key, ""));
        panel.addView(rules, lp(M, W));
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("正文清理")
                .setView(panel).setNegativeButton("取消", null).setPositiveButton("保存", null));
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = rules.getText().toString();
            List<NovelTextTools.LiteralRule> parsed;
            try {
                parsed = NovelTextTools.literalRules(value);
                if (enabled.isChecked() && parsed.isEmpty()) throw new IllegalArgumentException("请至少填写一条有效规则");
            } catch (IllegalArgumentException invalid) {
                rules.setError(invalid.getMessage());
                return;
            }
            readerSettings.edit().putString(key, value).putBoolean(key + "_enabled", enabled.isChecked()).apply();
            dialog.dismiss();
            redrawNovel(enabled.isChecked() ? parsed : Collections.emptyList());
        });
    }

    private void redrawNovel(List<NovelTextTools.LiteralRule> rules) {
        NovelReadingView previous = novelReader;
        ReaderEngine.Page page = loadedPage;
        if (previous == null || page == null || !(previous.getParent() instanceof FrameLayout)) return;
        FrameLayout frame = (FrameLayout) previous.getParent();
        int epoch = viewEpoch;
        int revision = ++cleanupRevision;
        if (pageTask != null) pageTask.cancel(true);
        pageTask = navigation.submit(() -> {
            NovelTextTools.LiteralText processed = NovelTextTools.literalText(page.text, rules);
            if (Thread.currentThread().isInterrupted()) return;
            main.post(() -> {
                if (!isCurrent(epoch, Screen.READER) || novelReader != previous || revision != cleanupRevision) return;
                ReaderProgress.Position current = readingPosition();
                if (current == null) {
                    ReaderProgress.Entry saved = progress.get(book);
                    if (saved != null && chapters.get(chapterIndex).url.equals(saved.chapterUrl)) current = saved.position;
                }
                closeNovelFind();
                frame.removeView(previous);
                showNovel(frame, processed, current, epoch);
                positionChanged();
            });
        });
    }

    private void showReadingMenu(View anchor) {
        PopupMenu menu = new PopupMenu(host, anchor);
        if (mangaReader != null && mangaReader.hasFailedImage()) {
            menu.getMenu().add("重试加载图片").setOnMenuItemClickListener(item ->
                    mangaReader != null && mangaReader.retryVisiblePage());
        }
        menu.getMenu().add("添加书签").setEnabled(readingPosition() != null)
                .setOnMenuItemClickListener(item -> { addBookmark(); return true; });
        menu.getMenu().add("查看书签").setOnMenuItemClickListener(item -> { showBookmarks(); return true; });
        if (novelReader != null) {
            menu.getMenu().add("查找正文").setOnMenuItemClickListener(item -> { showNovelFind(); return true; });
            if (!manga) menu.getMenu().add("正文清理").setOnMenuItemClickListener(item -> { showNovelCleanup(); return true; });
            menu.getMenu().add(novelReader.speaking() ? "停止朗读" : "从当前位置朗读")
                    .setEnabled(novelReader.isPositionReady())
                    .setOnMenuItemClickListener(item -> {
                        if (novelReader == null) return false;
                        if (novelReader.speaking()) novelReader.stopSpeech();
                        else {
                            closeNovelFind();
                            novelReader.startSpeech();
                        }
                        return true;
                    });
        }
        if (!localBook(book)) {
            menu.getMenu().add("换源").setOnMenuItemClickListener(item -> { showAlternateSources(); return true; });
        }
        if (!localBook(book)) menu.getMenu().add("离线缓存").setEnabled(loadedPage != null)
                .setOnMenuItemClickListener(item -> { showOffline(); return true; });
        menu.getMenu().add("阅读显示").setOnMenuItemClickListener(item -> { showReaderDisplay(); return true; });
        menu.show();
    }

    private void showReaderDisplay() {
        String[] labels = {"收起控件时全屏", "阅读时保持亮屏"};
        String[] keys = {"immersive", "keep_awake"};
        boolean[] values = {readerSettings.getBoolean(keys[0], true), readerSettings.getBoolean(keys[1], true)};
        showDialog(new AlertDialog.Builder(host).setTitle("阅读显示")
                .setMultiChoiceItems(labels, values, (dialog, index, checked) -> {
                    readerSettings.edit().putBoolean(keys[index], checked).apply();
                    if (screenChanged != null) screenChanged.run();
                }).setPositiveButton("完成", null));
    }

    private void showOffline() {
        ReaderEngine.Chapter chapter = chapters.get(chapterIndex);
        ReaderEngine.Page current = loadedPage;
        String job = downloadKey(chapter);
        DownloadTask queued = downloadJobs.get(job);
        if (queued != null && queued.active()) { toast(queued.status); return; }
        int epoch = viewEpoch;
        network.submit(() -> {
            boolean stored = offline.isDownloaded(chapter);
            main.post(() -> {
                if (!isCurrent(epoch, Screen.READER)) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("离线缓存")
                        .setNegativeButton("关闭", null);
                if (stored) {
                    dialog.setMessage("本章已缓存，可离线阅读。")
                            .setPositiveButton("删除缓存", (ignored, button) -> removeOffline(chapter));
                } else {
                    String content = current.images.isEmpty() ? "正文" : current.images.size() + " 张图片";
                    dialog.setMessage("将本章" + content + "保存在设备上。")
                            .setPositiveButton("缓存本章", (ignored, button) -> downloadChapter(chapter, current));
                }
                showDialog(dialog);
            });
        });
    }

    private void showDownloadedChapters() {
        int epoch = viewEpoch;
        ReaderEngine.Book selected = book;
        network.submit(() -> {
            List<ReaderEngine.Chapter> entries = offline.downloadedCatalogue(selected);
            main.post(() -> {
                if (!isCurrent(epoch, Screen.CHAPTERS)) return;
                AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("离线章节")
                        .setNegativeButton("关闭", null);
                if (entries == null || entries.isEmpty()) dialog.setMessage("在阅读菜单中可缓存当前章节。");
                else {
                    String[] labels = new String[entries.size()];
                    for (int i = 0; i < labels.length; i++) labels[i] = entries.get(i).title;
                    dialog.setItems(labels, (ignored, index) -> manageOfflineChapter(entries.get(index)));
                }
                showDialog(dialog);
            });
        });
    }

    private void chooseChapterDownloads() {
        List<ReaderEngine.Chapter> available = new ArrayList<>(chapters);
        if (available.isEmpty()) return;
        String[] labels = new String[available.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = available.get(i).title;
        AlertDialog dialog = showDialog(new AlertDialog.Builder(host).setTitle("选择要下载的章节")
                .setMultiChoiceItems(labels, null, null)
                .setNeutralButton("全选", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("下载所选", null));
        ListView list = dialog.getListView();
        TextView selectAll = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        TextView submit = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Runnable updateSelection = () -> {
            int count = list.getCheckedItemCount();
            selectAll.setText(count == available.size() ? "取消全选" : "全选");
            submit.setText(count == 0 ? "下载所选" : "下载 " + count + " 章");
            submit.setEnabled(count > 0);
        };
        list.setOnItemClickListener((parent, row, position, id) -> updateSelection.run());
        selectAll.setOnClickListener(view -> {
            boolean select = list.getCheckedItemCount() < available.size();
            for (int i = 0; i < available.size(); i++) list.setItemChecked(i, select);
            updateSelection.run();
        });
        submit.setOnClickListener(view -> {
            if (list.getCheckedItemCount() == 0) return;
            for (int i = 0; i < available.size(); i++) {
                if (list.isItemChecked(i)) enqueueDownload(available.get(i), null);
            }
            persistDownloadTasks();
            dialog.dismiss();
            showDownloads();
        });
        updateSelection.run();
    }

    private void removeLocalBook() {
        ReaderEngine.Book selected = book;
        int epoch = viewEpoch;
        showDialog(new AlertDialog.Builder(host).setTitle("删除导入的副本？")
                .setMessage("将删除应用内的副本、进度和书签。原始文件会保留。")
                .setNegativeButton("取消", null).setPositiveButton("移除", (dialog, which) -> imports.submit(() -> {
                    try {
                        localBooks.remove(selected);
                        progress.remove(selected);
                        main.post(() -> {
                            if (isCurrent(epoch, Screen.CHAPTERS)) showHome();
                        });
                    } catch (IOException error) {
                        main.post(() -> { if (!destroyed) toast(problem(error)); });
                    }
                })));
    }

    private void manageOfflineChapter(ReaderEngine.Chapter chapter) {
        showDialog(new AlertDialog.Builder(host).setTitle(chapter.title)
                .setNeutralButton("删除缓存", (dialog, button) -> removeOffline(chapter))
                .setNegativeButton("返回", (dialog, button) -> showDownloadedChapters())
                .setPositiveButton("阅读", (dialog, button) -> {
                    int index = chapterAt(chapter.url);
                    if (index < 0) {
                        List<ReaderEngine.Chapter> available = new ArrayList<>(chapters);
                        index = available.size();
                        available.add(chapter);
                        chapters = available;
                    }
                    openChapter(index, true);
                }));
    }

    private void downloadChapter(ReaderEngine.Chapter chapter, ReaderEngine.Page page) {
        if (!enqueueDownload(chapter, page)) return;
        persistDownloadTasks();
        refreshDownloadStatus();
        if (screen != Screen.DOWNLOADS) toast("已加入下载队列");
    }

    private boolean enqueueDownload(ReaderEngine.Chapter chapter, ReaderEngine.Page page) {
        String job = downloadKey(chapter);
        DownloadTask previous = downloadJobs.get(job);
        if (previous != null && previous.active()) return false;
        if (page == null && sources.find(chapter.book.source.id) == null) {
            toast("来源已移除，重新导入来源后再重试");
            return false;
        }
        DownloadTask task = new DownloadTask(chapter);
        downloadJobs.put(job, task);
        task.work = downloads.submit(() -> {
            try {
                main.post(() -> updateDownload(task, "读取章节…"));
                offline.download(chapter, page, (image, referer) -> {
                    byte[] bytes = readImage(chapter, chapter.book.source, referer, image);
                    BitmapFactory.Options check = new BitmapFactory.Options();
                    check.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.length, check);
                    if (check.outWidth <= 0 || check.outHeight <= 0) throw new IOException("章节含无法解码的图片");
                    return bytes;
                }, (complete, total) -> {
                    if (total > 0 && (complete % 4 == 0 || complete == total)) {
                        main.post(() -> updateDownload(task, "已缓存 " + complete + " / " + total + " 张"));
                    }
                });
                main.post(() -> finishDownload(task, null));
            } catch (IOException | RuntimeException error) {
                main.post(() -> finishDownload(task, "缓存失败 · " + problem(error)));
            }
        });
        return true;
    }

    private void removeOffline(ReaderEngine.Chapter chapter) {
        downloads.submit(() -> {
            try {
                offline.removeDownload(chapter);
                main.post(() -> {
                    if (destroyed) return;
                    if (screen == Screen.DOWNLOADS) showDownloads();
                    else toast("已删除本章离线缓存");
                });
            } catch (IOException error) {
                main.post(() -> { if (!destroyed) toast("无法删除本章缓存"); });
            }
        });
    }

    private static final class DownloadTask {
        final ReaderEngine.Chapter chapter;
        String status = "排队中";
        Future<?> work;
        boolean done;

        DownloadTask(ReaderEngine.Chapter chapter) { this.chapter = chapter; }
        boolean active() { return !done; }
    }

    private void updateDownload(DownloadTask task, String message) {
        if (destroyed || !task.active() || downloadJobs.get(downloadKey(task.chapter)) != task) return;
        task.status = message;
        refreshDownloadStatus();
    }

    private void finishDownload(DownloadTask task, String error) {
        String key = downloadKey(task.chapter);
        if (destroyed || !task.active() || downloadJobs.get(key) != task) return;
        task.done = true;
        if (error == null) downloadJobs.remove(key);
        else task.status = error;
        persistDownloadTasks();
        if (screen == Screen.DOWNLOADS) showDownloads();
        else toast(error == null ? "本章已下载" : error);
    }

    private void stopDownload(DownloadTask task) {
        task.done = true;
        task.status = "已取消";
        if (task.work != null) task.work.cancel(true);
        persistDownloadTasks();
        refreshDownloadStatus();
    }

    private void persistDownloadTasks() {
        JSONArray saved = new JSONArray();
        for (DownloadTask task : downloadJobs.values()) {
            ReaderEngine.Chapter chapter = task.chapter;
            try {
                saved.put(new JSONObject().put("source", chapter.book.source.id).put("book", chapter.book.url)
                        .put("name", chapter.book.title).put("chapter", chapter.url).put("title", chapter.title)
                        .put("state", task.active() ? "下载已中断，可重试" : task.status));
            } catch (JSONException invalid) { continue; }
            if (saved.length() == 150) break;
        }
        downloadSettings.edit().putString(searchHealthKey, saved.toString()).apply();
    }

    private void restoreDownloadTasks() {
        try {
            JSONArray saved = new JSONArray(downloadSettings.getString(searchHealthKey, "[]"));
            for (int i = 0; i < Math.min(150, saved.length()); i++) {
                JSONObject row = saved.optJSONObject(i);
                if (row == null) continue;
                ReaderSources.Source source = savedSource(row.optString("source"));
                if (source == null || row.optString("book").isEmpty() || row.optString("chapter").isEmpty()) continue;
                ReaderEngine.Book book = new ReaderEngine.Book(source, row.optString("name"), "", "", "",
                        row.optString("book"), "");
                DownloadTask task = new DownloadTask(new ReaderEngine.Chapter(book, row.optString("title"), row.optString("chapter")));
                task.status = row.optString("state", "下载已中断，可重试");
                task.done = true;
                downloadJobs.put(downloadKey(task.chapter), task);
            }
        } catch (JSONException ignored) {
            // The offline chapter archive is independent from the retry list.
        }
    }

    private void refreshDownloadStatus() {
        if (screen != Screen.DOWNLOADS || downloadStatus == null) return;
        StringBuilder status = new StringBuilder();
        for (DownloadTask task : downloadJobs.values()) {
            if (status.length() > 0) status.append('\n');
            status.append(task.chapter.book.title).append(" · ").append(task.chapter.title)
                    .append(" · ").append(task.status);
            if (status.length() > 420) break;
        }
        downloadStatus.setText(status);
        downloadStatus.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
    }

    void showDownloads() {
        savePosition();
        returnToHistory = false;
        reset(Screen.DOWNLOADS);
        int epoch = viewEpoch;
        LinearLayout header = topBar(sectionName + "离线下载", "", this::showHome);
        header.addView(action("任务", false, this::showDownloadTasks), lp(W, 44));
        scene.addView(header, lp(M, W));
        downloadStatus = text("", 13, MUTED, false);
        scene.addView(downloadStatus, inset(5, 2));
        refreshDownloadStatus();
        TextView loading = text("正在读取已下载章节…", 13, MUTED, false);
        scene.addView(loading, inset(5, 2));
        pageTask = navigation.submit(() -> {
            List<ReaderEngine.Book> legacy = new ArrayList<>();
            for (ReaderProgress.Entry entry : progress.entries()) {
                ReaderSources.Source source = savedSource(entry.sourceId);
                if (source != null && !ReaderLocalBooks.isSource(source.id, kind)) legacy.add(entry.toBook(source));
            }
            List<ReaderCache.SavedDownload> rows = offline.savedDownloads(legacy);
            rows.removeIf(saved -> saved.chapter.book.source.kind != kind);
            main.post(() -> {
                if (!isCurrent(epoch, Screen.DOWNLOADS)) return;
                scene.removeView(loading);
                boolean changed = false;
                for (ReaderCache.SavedDownload item : rows) {
                    String key = downloadKey(item.chapter);
                    DownloadTask task = downloadJobs.get(key);
                    if (task != null && !task.active()) {
                        downloadJobs.remove(key);
                        changed = true;
                    }
                }
                if (changed) persistDownloadTasks();
                refreshDownloadStatus();
                renderDownloads(rows);
            });
        });
    }

    private void renderDownloads(List<ReaderCache.SavedDownload> rows) {
        if (rows.isEmpty()) {
            scene.addView(emptyCard("还没有离线章节", "打开作品目录可批量下载，阅读时也可缓存当前章节。",
                    "返回书架", this::showHome), inset(8, 0));
            return;
        }
        long bytes = 0;
        for (ReaderCache.SavedDownload item : rows) bytes += item.bytes;
        LinearLayout commands = row();
        TextView info = text(rows.size() + " 章 · " + android.text.format.Formatter.formatFileSize(host, bytes),
                13, MUTED, false);
        commands.addView(info, weight(W));
        commands.addView(action("批量删除", false, () -> chooseSavedDownloads(rows)), lp(W, 44));
        scene.addView(commands, inset(5, 2));
        List<Cell> entries = new ArrayList<>();
        for (ReaderCache.SavedDownload item : rows) {
            ReaderEngine.Chapter chapter = item.chapter;
            entries.add(new Cell(chapter.title, chapter.book.title + " · "
                    + android.text.format.Formatter.formatFileSize(host, item.bytes), "离线", "", null,
                    () -> openSavedDownload(chapter, rows)));
        }
        scene.addView(list(entries), new LinearLayout.LayoutParams(M, 0, 1));
    }

    private void openSavedDownload(ReaderEngine.Chapter selected, List<ReaderCache.SavedDownload> rows) {
        ReaderEngine.Book identity = selected.book;
        ReaderSources.Source active = sources.find(identity.source.id);
        book = active == null ? identity : new ReaderEngine.Book(active, identity.title, identity.author, identity.summary,
                identity.cover, identity.url, identity.tocUrl);
        List<ReaderEngine.Chapter> saved = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            ReaderEngine.Chapter chapter = rows.get(i).chapter;
            if (!chapter.book.source.id.equals(identity.source.id) || !chapter.book.url.equals(identity.url)) continue;
            ReaderEngine.Chapter reopened = new ReaderEngine.Chapter(book, chapter.title, chapter.url);
            reopened.nextChapterUrl = chapter.nextChapterUrl;
            saved.add(reopened);
        }
        chapters = saved;
        downloadedChaptersOnly = true;
        int index = chapterAt(selected.url);
        if (index >= 0) openChapter(index, true);
    }

    private void chooseSavedDownloads(List<ReaderCache.SavedDownload> rows) {
        if (rows.isEmpty()) return;
        String[] labels = new String[rows.size()];
        boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < labels.length; i++) {
            ReaderEngine.Chapter chapter = rows.get(i).chapter;
            labels[i] = chapter.book.title + " · " + chapter.title;
        }
        showDialog(new AlertDialog.Builder(host).setTitle("选择要删除的离线章节")
                .setMultiChoiceItems(labels, checked, (dialog, index, on) -> checked[index] = on)
                .setNegativeButton("取消", null).setPositiveButton("删除所选", (dialog, which) -> {
                    List<ReaderEngine.Chapter> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) chosen.add(rows.get(i).chapter);
                    }
                    if (chosen.isEmpty()) return;
                    downloads.submit(() -> {
                        int removed = 0;
                        for (ReaderEngine.Chapter chapter : chosen) {
                            try { offline.removeDownload(chapter); removed++; }
                            catch (IOException ignored) { }
                        }
                        int count = removed;
                        main.post(() -> {
                            if (destroyed) return;
                            toast("已删除 " + count + " / " + chosen.size() + " 章");
                            if (screen == Screen.DOWNLOADS) showDownloads();
                        });
                    });
                }));
    }

    private void showDownloadTasks() {
        List<DownloadTask> tasks = new ArrayList<>(downloadJobs.values());
        String[] labels = new String[tasks.size()];
        for (int i = 0; i < labels.length; i++) {
            DownloadTask task = tasks.get(i);
            labels[i] = task.chapter.title + " · " + task.status;
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("下载任务").setNegativeButton("关闭", null);
        if (labels.length == 0) dialog.setMessage("目前没有进行中或失败的下载。");
        else dialog.setItems(labels, (ignored, index) -> {
            DownloadTask task = tasks.get(index);
            boolean active = task.active();
            showDialog(new AlertDialog.Builder(host).setTitle(task.chapter.title).setMessage(task.status)
                    .setNegativeButton("返回", (previous, button) -> showDownloadTasks())
                    .setNeutralButton(active ? null : "移除记录", (previous, button) -> {
                        downloadJobs.remove(downloadKey(task.chapter));
                        persistDownloadTasks();
                        refreshDownloadStatus();
                    })
                    .setPositiveButton(active ? "取消下载" : "重试下载", (update, button) -> {
                        if (active && task.active()) stopDownload(task);
                        else if (!active) downloadChapter(task.chapter, null);
                    }));
        });
        showDialog(dialog);
    }

    private static String downloadKey(ReaderEngine.Chapter chapter) {
        return ReaderProgress.key(chapter.book) + '\u001f' + chapter.url;
    }

    private void toggleReaderControls() {
        if (novelFind != null) { closeNovelFind(); return; }
        controlsVisible = !controlsVisible;
        updateReaderControls();
    }

    private void updateReaderControls() {
        int visible = controlsVisible && novelFind == null ? View.VISIBLE : View.GONE;
        readerHeader.setVisibility(loadedPage == null ? View.VISIBLE : visible);
        readerTools.setVisibility(visible);
        if (screenChanged != null) screenChanged.run();
    }

    private String readingPercent(int offset) {
        return Math.min(100, Math.round(100f * offset / Math.max(1, novelReader.length() - 1))) + "%";
    }

    private void updateReadingLabel(int position) {
        String chapter = (chapterIndex + 1) + " / " + chapters.size() + " 章";
        if (mangaReader != null) chapter = join(chapter, (position + 1) + " / " + loadedPage.images.size() + " 页");
        else if (novelReader != null) chapter = join(chapter, readingPercent(position));
        if (TextUtils.equals(readingStatus.getText(), chapter)) return;
        readingStatus.setText(chapter);
        if (Build.VERSION.SDK_INT >= 30) readerSeek.setStateDescription(chapter);
    }

    private void updateReadingMeter() {
        if (seekingReader || readingStatus == null) return;
        int position = mangaReader != null ? mangaReader.page() : novelReader != null ? novelReader.position() : 0;
        readerSeek.setProgress(position);
        updateReadingLabel(position);
    }

    private void positionChanged() {
        updateReadingMeter();
        if (savePending || readingPosition() == null) return;
        savePending = true;
        main.postDelayed(saveSoon, 400);
    }

    private ReaderProgress.Position readingPosition() {
        if (!isReading() || loadedPage == null) return null;
        if (mangaReader != null) {
            int offset = mangaReader.offset();
            return offset < 0 ? new ReaderProgress.Position(mangaReader.page(), -offset, ReaderProgress.PIXELS)
                    : new ReaderProgress.Position(mangaReader.page(), offset, ReaderProgress.IMAGE_FRACTION);
        }
        if (novelReader != null && novelReader.isPositionReady()) {
            return new ReaderProgress.Position(0, novelReader.position(), ReaderProgress.TEXT_OFFSET);
        }
        return null;
    }

    private void savePosition() {
        main.removeCallbacks(saveSoon);
        savePending = false;
        ReaderProgress.Position position = readingPosition();
        if (position != null && chapterIndex >= 0) {
            progress.mark(chapters.get(chapterIndex), position, chapters, chapterIndex);
        }
    }

    private boolean isCurrent(int epoch, Screen expected) {
        return !destroyed && viewEpoch == epoch && screen == expected;
    }

    private static final class Cell {
        final String title, subtitle, badge, artwork;
        final ReaderSources.Source source;
        final Runnable onClick, onLongClick;
        final boolean wrapSubtitle;

        Cell(String title, String subtitle, String badge, String artwork,
             ReaderSources.Source source, Runnable onClick) {
            this(title, subtitle, badge, artwork, source, onClick, null);
        }

        Cell(String title, String subtitle, String badge, String artwork,
             ReaderSources.Source source, Runnable onClick, Runnable onLongClick) {
            this(title, subtitle, badge, artwork, source, onClick, onLongClick, false);
        }

        Cell(String title, String subtitle, String badge, String artwork,
             ReaderSources.Source source, Runnable onClick, Runnable onLongClick, boolean wrapSubtitle) {
            this.title = title;
            this.subtitle = subtitle;
            this.badge = badge;
            this.artwork = artwork;
            this.source = source;
            this.onClick = onClick;
            this.onLongClick = onLongClick;
            this.wrapSubtitle = wrapSubtitle;
        }
    }

    private final class CellView extends LinearLayout {
        private final TextView title, subtitle, badge;
        private final ImageSlot cover;
        private final View shelfMenu;
        private Cell bound;

        CellView(boolean paper) { this(paper, false); }

        CellView(boolean paper, boolean shelfControls) {
            super(host);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(paper ? 0 : dp(12), dp(8), paper ? 0 : dp(8), dp(8));
            setMinimumHeight(dp(54));
            if (!paper) setBackground(surface(host, SURFACE, 10, LINE));
            cover = new ImageSlot(paper);
            addView(cover, lp(paper ? 44 : 41, paper ? 44 : 55));
            LinearLayout labels = column();
            LinearLayout.LayoutParams labelParams = weight(W);
            labelParams.leftMargin = dp(10);
            addView(labels, labelParams);
            title = text("", 15, FG, true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(title, lp(M, W));
            subtitle = text("", 12, MUTED, false);
            subtitle.setPadding(0, dp(4), 0, 0);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(subtitle, lp(M, W));
            badge = text("", 11, paper ? MUTED : DEEP_MINT, !paper);
            badge.setPadding(dp(6), dp(5), dp(5), dp(5));
            if (!paper) badge.setBackground(surface(host, SOFT_MINT, 8, 0));
            addView(badge, lp(W, W));
            if (shelfControls) {
                shelfMenu = iconButton("more", "书架操作", () -> bound.onLongClick.run());
                shelfMenu.setFocusable(false);
                addView(shelfMenu, lp(44, 44));
            } else {
                shelfMenu = null;
                addView(new Icon(host, "chevron", MUTED, 14), lp(22, 30));
            }
        }

        void bind(Cell cell) {
            if (bound == cell) return;
            if (bound == null || bound.wrapSubtitle != cell.wrapSubtitle) {
                subtitle.setSingleLine(!cell.wrapSubtitle);
                if (cell.wrapSubtitle) subtitle.setMaxLines(3);
            }
            bound = cell;
            if (shelfMenu != null) shelfMenu.setContentDescription("管理「" + cell.title + "」");
            title.setText(cell.title);
            subtitle.setVisibility(TextUtils.isEmpty(cell.subtitle) ? GONE : VISIBLE);
            subtitle.setText(cell.subtitle);
            badge.setVisibility(TextUtils.isEmpty(cell.badge) ? GONE : VISIBLE);
            badge.setText(cell.badge);
            cover.setVisibility(cell.source == null ? GONE : VISIBLE);
            if (cell.source != null) cover.bind(cell.artwork, cell.source, cell.source.url);
        }
    }

    private final class ImageSlot extends FrameLayout {
        private final ImageView picture;
        private final View placeholder;
        private final boolean portrait;
        private String key = "";

        ImageSlot(boolean paper) { this(paper, false); }

        ImageSlot(boolean paper, boolean portrait) {
            super(host);
            this.portrait = portrait;
            setBackground(surface(host, paper ? SURFACE : SOFT_PINK, 5, 0));
            setClipToOutline(true);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            picture = new ImageView(host);
            picture.setScaleType(ImageView.ScaleType.CENTER_CROP);
            addView(picture, new FrameLayout.LayoutParams(M, M));
            placeholder = new Icon(host, manga ? "panels" : "book", textTone(BERRY), 20);
            placeholder.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(placeholder, new FrameLayout.LayoutParams(M, M));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            if (portrait && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY) {
                heightSpec = MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthSpec) * 4 / 3, MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        void bind(String image, ReaderSources.Source source, String referer) {
            key = source == null ? "" : imageKey(source, referer, image);
            String binding = key;
            picture.setImageDrawable(null);
            placeholder.setVisibility(VISIBLE);
            if (source == null || TextUtils.isEmpty(image)) return;
            WeakReference<ImageSlot> target = new WeakReference<>(this);
            queueImage(binding, null, source, referer, image, 240, 320, bitmap -> {
                ImageSlot slot = target.get();
                if (slot != null && slot.key.equals(binding) && bitmap != null) {
                    slot.picture.setImageBitmap(bitmap);
                    slot.placeholder.setVisibility(GONE);
                }
            });
        }
    }

    private final class ShelfTile extends LinearLayout {
        private final ImageSlot cover;
        private final View shelfMenu;
        private final TextView title, progressLabel;
        private Cell cell;

        ShelfTile() {
            super(host);
            setOrientation(VERTICAL);
            setFocusable(true);
            setBackground(button(host, android.graphics.Color.TRANSPARENT, 6, 0));
            cover = new ImageSlot(true, true);
            FrameLayout artwork = new FrameLayout(host);
            artwork.addView(cover, new FrameLayout.LayoutParams(M, W));
            FrameLayout touchTarget = new FrameLayout(host);
            Icon dots = new Icon(host, "more", 0xffffffff, 18);
            dots.setBackground(button(host, 0xbf252126, 8, 0));
            dots.setDuplicateParentStateEnabled(true);
            touchTarget.addView(dots, new FrameLayout.LayoutParams(dp(24), dp(24),
                    Gravity.TOP | Gravity.END));
            touchTarget.setFocusable(true);
            touchTarget.setOnClickListener(view -> {
                if (cell != null) cell.onLongClick.run();
            });
            shelfMenu = touchTarget;
            FrameLayout.LayoutParams menuPlacement = new FrameLayout.LayoutParams(dp(44), dp(44),
                    Gravity.TOP | Gravity.END);
            menuPlacement.topMargin = dp(4);
            menuPlacement.setMarginEnd(dp(4));
            artwork.addView(shelfMenu, menuPlacement);
            addView(artwork, lp(M, W));
            title = text("", 14, FG, true);
            title.setPadding(0, dp(5), 0, 0);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            addView(title, lp(M, W));
            progressLabel = text("", 11, MUTED, false);
            progressLabel.setSingleLine(true);
            progressLabel.setEllipsize(TextUtils.TruncateAt.END);
            addView(progressLabel, lp(M, W));
            setOnClickListener(view -> { if (cell != null) cell.onClick.run(); });
            setOnLongClickListener(view -> {
                if (cell == null || cell.onLongClick == null) return false;
                cell.onLongClick.run();
                return true;
            });
        }

        void bind(Cell next) {
            if (cell != null && cell == next) return;
            cell = next;
            setVisibility(next == null ? INVISIBLE : VISIBLE);
            if (next == null) {
                cover.bind("", null, "");
                title.setText("");
                progressLabel.setText("");
                return;
            }
            title.setText(next.title);
            shelfMenu.setContentDescription("管理「" + next.title + "」");
            progressLabel.setText(next.subtitle);
            cover.bind(next.artwork, next.source, next.source == null ? "" : next.source.url);
        }
    }

    private static String imageKey(ReaderSources.Source source, String referer, String image) {
        return source.id + '\u001f' + referer + '\u001f' + image;
    }

    private void queuePageImage(ReaderEngine.Chapter chapter, String referer, String image,
                                int width, int height, Consumer<Bitmap> target) {
        if (target == null && imageRequests.size() >= 6) return;
        int boxWidth = Math.max(720, Math.min(2160, width));
        int boxHeight = Math.max(3600, Math.min(8192, height));
        String key = "page:" + chapter.url + '\u001f' + imageKey(chapter.book.source, referer, image)
                + '\u001f' + boxWidth + '\u001f' + boxHeight;
        queueImage(key, chapter, chapter.book.source, referer, image, boxWidth, boxHeight, target);
    }

    private void queueImage(String key, ReaderEngine.Chapter chapter, ReaderSources.Source source,
                            String referer, String image, int width, int height, Consumer<Bitmap> target) {
        Bitmap cached = imageCache.get(key);
        if (cached != null) {
            if (target != null) main.post(() -> { if (!destroyed) target.accept(cached); });
            return;
        }
        List<Consumer<Bitmap>> pending = imageRequests.get(key);
        if (pending != null) {
            if (target != null) pending.add(target);
            return;
        }
        List<Consumer<Bitmap>> targets = new ArrayList<>();
        if (target != null) targets.add(target);
        imageRequests.put(key, targets);
        images.submit(() -> {
            Bitmap bitmap = null;
            try {
                String url = ReaderEngine.imageAddress(image);
                Map<String, String> headers = imageHeaders(source, referer, url,
                        ReaderEngine.imageRequestHeaders(image), chapter != null);
                byte[] bytes = chapter != null && localBook(chapter.book) ? localBooks.image(chapter, image)
                        : offline.readImage(chapter, source, image, url, headers);
                bitmap = decodeImage(bytes, width, height);
                if (bitmap == null) offline.removeTransientImage(source, url, headers);
            } catch (IOException | RuntimeException ignored) {
                // A failed page can be retried inside the viewer.
            }
            Bitmap result = bitmap;
            main.post(() -> {
                imageRequests.remove(key);
                if (destroyed) return;
                if (result != null) imageCache.put(key, result);
                for (Consumer<Bitmap> item : targets) item.accept(result);
            });
        });
    }

    private byte[] readImage(ReaderEngine.Chapter chapter, ReaderSources.Source source,
                             String referer, String image) throws IOException {
        String url = ReaderEngine.imageAddress(image);
        Map<String, String> headers = imageHeaders(source, referer, url,
                ReaderEngine.imageRequestHeaders(image), chapter != null);
        return offline.readImage(chapter, source, image, url, headers);
    }

    private static Map<String, String> imageHeaders(ReaderSources.Source source, String referer,
                                                   String imageUrl, Map<String, String> imageOptions,
                                                   boolean chapterImage) {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        boolean sameSource = ReaderEngine.sameOrigin(source.url, imageUrl);
        addImageHeaders(headers, source.headers(), sameSource);
        JSONObject chapterHeaders = chapterImage ? source.definition.optJSONObject("imageHeaders") : null;
        if (chapterHeaders != null) {
            Map<String, String> requested = new LinkedHashMap<>();
            Iterator<String> keys = chapterHeaders.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                Object value = chapterHeaders.opt(name);
                if (value instanceof String || value instanceof Number) requested.put(name, value.toString());
            }
            addImageHeaders(headers, requested, sameSource);
        }
        addImageHeaders(headers, imageOptions, sameSource);
        if (!TextUtils.isEmpty(referer)) {
            Uri pageAddress = Uri.parse(referer);
            String configuredReferer = headers.get("Referer");
            if ("thisPage".equalsIgnoreCase(configuredReferer)) {
                headers.put("Referer", pageAddress.buildUpon().fragment(null).toString());
            } else if (configuredReferer == null) {
                String defaultReferer = ReaderEngine.sameOrigin(referer, imageUrl) ? referer
                        : pageAddress.buildUpon().encodedPath("/").clearQuery().fragment(null).toString();
                headers.put("Referer", defaultReferer);
            }
        }
        return headers;
    }

    private static void addImageHeaders(Map<String, String> headers, Map<String, String> requested,
                                        boolean sameSource) {
        for (Map.Entry<String, String> header : requested.entrySet()) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (sameSource || name.equals("user-agent") || name.equals("referer")
                    || name.equals("accept") || name.equals("accept-language")) {
                headers.put(header.getKey(), header.getValue());
            }
        }
    }

    private static Bitmap decodeImage(byte[] bytes, int maxWidth, int maxHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inSampleSize = 1;
        long pixels = (long) options.outWidth * options.outHeight;
        while (options.outWidth / options.inSampleSize > maxWidth * 1.6
                || options.outHeight / options.inSampleSize > maxHeight
                || pixels / ((long) options.inSampleSize * options.inSampleSize) > 3_000_000) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private ListView list(List<Cell> cells) {
        ListView list = newList();
        bindList(list, cells);
        return list;
    }

    private ListView newList() {
        ListView list = new ListView(host);
        list.setDivider(null);
        list.setSelector(button(host, android.graphics.Color.TRANSPARENT, 14, 0));
        list.setDrawSelectorOnTop(true);
        list.setPadding(dp(16), 0, dp(16), dp(8));
        list.setClipToPadding(false);
        return list;
    }

    private ListView paperList() {
        ListView list = newList();
        list.setPadding(0, 0, 0, dp(6));
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setHeaderDividersEnabled(false);
        return list;
    }

    private void bindList(ListView list, List<Cell> cells) {
        bindList(list, cells, true);
    }

    private void bindList(ListView list, List<Cell> cells, boolean stableIds) {
        bindList(list, cells, stableIds, false);
    }

    private void bindList(ListView list, List<Cell> cells, boolean stableIds, boolean paper) {
        bindList(list, cells, stableIds, paper, false);
    }

    private void bindList(ListView list, List<Cell> cells, boolean stableIds, boolean paper, boolean shelfControls) {
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return cells.size(); }
            @Override public Cell getItem(int position) { return cells.get(position); }
            @Override public long getItemId(int position) { return position; }
            @Override public boolean hasStableIds() { return stableIds; }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                FrameLayout outer;
                CellView view;
                if (recycled instanceof FrameLayout && ((FrameLayout) recycled).getChildAt(0) instanceof CellView) {
                    outer = (FrameLayout) recycled;
                    view = (CellView) outer.getChildAt(0);
                } else {
                    outer = new FrameLayout(host);
                    if (!paper) outer.setPadding(0, dp(3), 0, dp(4));
                    view = new CellView(paper, shelfControls);
                    outer.addView(view, new FrameLayout.LayoutParams(M, W));
                }
                view.bind(getItem(position));
                return outer;
            }
        });
        list.setOnItemClickListener((parent, child, position, id) -> {
            Object selected = parent.getItemAtPosition(position);
            if (selected instanceof Cell) ((Cell) selected).onClick.run();
        });
    }

    private LinearLayout topBar(String title, String subtitle, Runnable back) {
        LinearLayout bar = row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), dp(7), dp(16), dp(8));
        bar.addView(iconButton("chevron", "返回", back), lp(44, 44));
        LinearLayout labels = column();
        labels.setPadding(dp(11), 0, dp(10), 0);
        TextView heading = text(title, 20, isReading() ? READER_TEXT : FG, true);
        heading.setSingleLine(true);
        heading.setEllipsize(TextUtils.TruncateAt.END);
        markHeading(heading);
        labels.addView(heading, lp(M, W));
        if (!subtitle.isEmpty()) {
            TextView subheading = text(subtitle, 12, isReading() ? READER_MUTED : MUTED, false);
            subheading.setSingleLine(true);
            subheading.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(subheading, lp(M, W));
        }
        bar.addView(labels, weight(W));
        return bar;
    }

    private View iconButton(String name, String description, Runnable click) {
        return iconButton(name, description, view -> click.run());
    }

    private View iconButton(String name, String description, View.OnClickListener click) {
        FrameLayout button = new FrameLayout(host);
        boolean insideReader = isReading();
        button.setBackground(button(host, insideReader ? READER_BUTTON : SOFT_PINK, 13, 0));
        Icon icon = new Icon(host, name, insideReader ? SOFT_PINK : BERRY, 18);
        if ("返回".equals(description)) icon.setScaleX(-1);
        button.addView(icon, new FrameLayout.LayoutParams(M, M));
        button.setContentDescription(description);
        button.setFocusable(true);
        button.setOnClickListener(click);
        return button;
    }

    private View emptyCard(String title, String detail, String cta, Runnable click) {
        LinearLayout margin = column();
        margin.setPadding(dp(16), dp(6), dp(16), dp(5));
        LinearLayout panel = row();
        panel.setPadding(dp(13), dp(13), dp(13), dp(12));
        panel.setBackground(surface(host, SURFACE, 19, LINE));
        Icon symbol = new Icon(host, manga ? "panels" : "book", BERRY, 22);
        symbol.setBackground(surface(host, SOFT_PINK, 12, 0));
        panel.addView(symbol, lp(44, 44));
        LinearLayout body = column();
        body.setPadding(dp(11), 0, 0, 0);
        TextView heading = text(title, 17, FG, true);
        body.addView(heading, lp(M, W));
        TextView message = text(detail, 13, MUTED, false);
        message.setPadding(0, dp(4), 0, 0);
        message.setMaxLines(5);
        message.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(message, lp(M, W));
        LinearLayout.LayoutParams params = lp(W, 44);
        params.topMargin = dp(7);
        body.addView(actionButton(cta, true, false, click), params);
        panel.addView(body, weight(W));
        margin.addView(panel, lp(M, W));
        return margin;
    }

    private View loading(String label) {
        LinearLayout block = row();
        block.setPadding(dp(20), dp(12), dp(20), dp(12));
        block.setGravity(Gravity.CENTER);
        ProgressBar progress = new ProgressBar(host, null, android.R.attr.progressBarStyleSmall);
        progress.setIndeterminateTintList(ColorStateList.valueOf(isReading() ? SOFT_PINK : BERRY));
        block.addView(progress, lp(19, 19));
        TextView message = text(label, 13, isReading() ? READER_MUTED : MUTED, false);
        message.setPadding(dp(8), 0, 0, 0);
        block.addView(message, lp(W, W));
        return block;
    }

    private View note(String label) {
        TextView note = text(label, 13, MUTED, false);
        note.setPadding(dp(20), dp(7), dp(20), dp(7));
        note.setMaxLines(5);
        return note;
    }

    private EditText input(String hint, boolean multiline) {
        EditText field = new EditText(host);
        field.setTextSize(15);
        field.setHint(hint);
        field.setTextColor(FG);
        field.setHintTextColor(MUTED);
        field.setSingleLine(!multiline);
        if (multiline) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        return field;
    }

    private TextView action(String label, boolean primary, Runnable click) {
        return actionButton(label, primary, isReading(), click);
    }

    private TextView actionButton(String label, boolean primary, boolean reader, Runnable click) {
        int color = primary ? ON_BERRY : reader ? READER_TEXT : BERRY;
        int fill = primary ? BERRY : reader ? READER_BUTTON : SURFACE;
        TextView action = text(label, 13, color, true);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(10), 0, dp(10), 0);
        action.setMaxLines(1);
        action.setEllipsize(TextUtils.TruncateAt.END);
        action.setBackground(button(host, fill, 13, primary || reader ? 0 : LINE));
        action.setTextColor(new ColorStateList(new int[][]{
                new int[]{-android.R.attr.state_enabled}, new int[]{}
        }, new int[]{reader ? 0xffc7a9bb : primary ? 0xffffe0ee : MUTED, color}));
        action.setFocusable(true);
        action.setOnClickListener(view -> click.run());
        return action;
    }

    private TextView text(String label, float size, int color, boolean emphasized) {
        TextView view = new TextView(host);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(textTone(color));
        if (emphasized) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private static void markHeading(View heading) {
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
    }

    private AlertDialog showDialog(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(surface(host, SURFACE, 22, LINE));
        }
        for (int which : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEUTRAL,
                AlertDialog.BUTTON_NEGATIVE}) {
            TextView control = dialog.getButton(which);
            if (control != null) control.setTextColor(textTone(which == AlertDialog.BUTTON_POSITIVE ? BERRY : MUTED));
        }
        return dialog;
    }

    private ScrollView scroll(View content) {
        ScrollView scroll = new ScrollView(host);
        scroll.addView(content, new ScrollView.LayoutParams(M, W));
        return scroll;
    }

    private void focusSearch() {
        search.requestFocus();
        search.selectAll();
        host.getSystemService(InputMethodManager.class).showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        View focused = host.getCurrentFocus();
        if (focused instanceof EditText) {
            host.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(focused.getWindowToken(), 0);
        }
    }

    private void toast(String message) {
        if (!destroyed) Toast.makeText(host, message, Toast.LENGTH_SHORT).show();
    }

    private static String problem(Exception error) {
        String message = error.getMessage();
        if (TextUtils.isEmpty(message)) return "读取失败，请检查来源后重试。";
        // Transport exceptions can include full URLs and implementation details.
        if (message.matches("^[\\x00-\\x7f]+$")) return "连接或读取失败，请检查网络和来源后重试。";
        return message.length() > 100 ? message.substring(0, 100) + "…" : message;
    }

    private static String firstNonempty(String first, String second) {
        return TextUtils.isEmpty(first) ? second : first;
    }

    private static String join(String first, String second) {
        if (TextUtils.isEmpty(first)) return second;
        if (TextUtils.isEmpty(second)) return first;
        return first + " · " + second;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(host);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private void addEqual(LinearLayout row, View view, boolean spaced) {
        LinearLayout.LayoutParams params = weight(42);
        if (spaced) params.leftMargin = dp(7);
        row.addView(view, params);
    }

    private LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams inset(int top, int bottom) {
        LinearLayout.LayoutParams params = lp(M, W);
        params.setMargins(dp(16), dp(top), dp(16), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weight(int height) {
        return new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), 1);
    }

    private int dp(float amount) { return PlayerViews.dp(host, amount); }
}
