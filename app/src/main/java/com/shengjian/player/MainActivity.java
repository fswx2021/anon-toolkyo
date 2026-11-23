package com.shengjian.player;

import static com.shengjian.player.PlayerViews.*;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.Lifecycle;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.LruCache;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends ComponentActivity {
    private static final int FILES = 101;
    private static final int LYRIC = 102;
    private static final int COMIC_SOURCE = 103;
    private static final int NOVEL_SOURCE = 104;
    private static final int BACKUP_SAVE = 115;
    private static final int BACKUP_RESTORE = 116;
    private static final int UPDATE_INSTALL_PERMISSION = 117;
    private static final int MUSIC = 0;
    private static final int COMICS = 1;
    private static final int NOVELS = 2;
    private static final int ANIME = 3;
    private static final int ALL_TRACKS = 0;
    private static final int LOCAL_TRACKS = 1;
    private static final int LINK_TRACKS = 2;
    private static final int FAVORITE_TRACKS = 3;
    private static final int RECENT_TRACKS = 4;
    private static final int BERRY = PINK;
    private static final int BLUSH = 0xffffeff1;
    private static final int SEAFOAM = 0xfff1f3f3;
    private static final int TEAL = MINT;
    private static final int ON_ACCENT = Color.WHITE;
    private static final String[] SECTION_NAMES = {"音乐", "漫画", "小说", "动画"};
    private static final int M = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int W = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final ExecutorService IMPORTS = Executors.newSingleThreadExecutor();
    private static volatile MusicImportTask activeImport;
    private static ReaderCache readerCache;
    private final ExecutorService images = Executors.newFixedThreadPool(2);
    private final AppUpdates appUpdates = new AppUpdates(
            BuildConfig.UPDATE_GITHUB_REPOSITORY, BuildConfig.UPDATE_MIRROR_BASE);
    private final ExecutorService updateRequests = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> artworkCache = new LruCache<>(20);
    private final List<Track> visible = new ArrayList<>();
    private final ExecutorService lyricReader = Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> lyricLoad;
    private int lyricGeneration;
    private final FrameLayout[] sectionPages = new FrameLayout[SECTION_NAMES.length];
    private final ReaderSection[] readers = new ReaderSection[SECTION_NAMES.length];
    private List<Track> tracks = Collections.emptyList();
    private final Map<String, Track> tracksById = new LinkedHashMap<>();
    private Library library;
    private AnimeSection anime;
    private SharedPreferences appSettings;
    private AppUpdates.Release availableAppUpdate;
    private AppUpdates.Release pendingStartupUpdate;
    private AppUpdateInstaller updateInstaller;
    private UpdateDownload updateDownload;
    private File pendingUpdateInstall, installAfterPermission;
    private Sheet updateSheet;
    private boolean checkedOnLaunch, checkingAppUpdate, showUpdateCheckResult;
    private int promptedUpdateVersion;
    private boolean backupContent;
    private static volatile boolean backupBusy;
    private static boolean loadedApplication;
    private SharedPreferences settings;
    private SharedPreferences musicSettings;
    private MusicCollections collections;
    private MusicOnlineSection musicOnline;
    private PlaybackService service;
    private boolean bound, working, inPlayer, inLyrics, seeking;
    private int filter;
    private String seekTrackId = "";
    private int section = MUSIC;
    private long savedAt, checkedAt;
    private String query = "", targetId = "", shownId = "", lyricId = "", lastError = "";
    private String playlistId = "", recentSnapshot = "", recentStatsSnapshot = "";
    private Map<String, MusicCollections.HistoryStats> recentStats = Collections.emptyMap();
    private FrameLayout root;
    private AnonHome chrome;
    private LinearLayout appSettingsPage;
    private LinearLayout openSourcePage, acknowledgementsPage, licensePage;
    private String openLicenseAsset;
    private FrameLayout miniCard;
    private LinearLayout miniControls;
    private View sectionBar, homeHeader;
    private ProgressBar miniProgress;
    private boolean keyboardVisible;
    private boolean hidingReaderBars;
    private int readerVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
    private LinearLayout home, player, empty, busy;
    private TextView busyText, emptyTitle, emptyHint, miniTitle, miniMeta, title, meta;
    private ImageView emptyEmote;
    private TextView importCancel;
    private ProgressBar importProgress;
    private MusicDownloadProgress musicDownloadProgress;
    private TextView elapsed, length, status, coverTab, lyricsTab, effectsButton, speedButton, timerButton;
    private TextView playbackErrorAction;
    private EditText search;
    private ImageView miniCover, cover;
    private SquareCover coverMount;
    private FrameLayout trackOptions;
    private FrameLayout downloadTrack;
    private FrameLayout playerStage;
    private GradientDrawable coverStageBackground, lyricStageBackground;
    private MusicLyricsPane lyricPane;
    private SeekBar seek;
    private Icon miniPlay, play, shuffle, repeat;
    private ListView list, openQueueList;
    private Songs songs;
    private AudioEffectsDialog effectsDialog;
    private boolean effectsOn;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            sync();
            main.postDelayed(this, 220);
        }
    };
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((PlaybackService.LocalBinder) binder).getService();
            if (service.currentTrack() == null) restoreMusicQueue();
            sync();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service = null; }
    };

    @Override public void onCreate(Bundle state) {
        Intent launchIntent = getIntent();
        boolean launcherStart = state == null && launchIntent != null
                && Intent.ACTION_MAIN.equals(launchIntent.getAction()) && !isAnimeDownloadNotification(launchIntent);
        String restoreMessage = loadedApplication ? "" : AppBackup.restoreAtStartup(this);
        loadedApplication = true;
        appSettings = getSharedPreferences("app_settings", MODE_PRIVATE);
        boolean systemDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int appearance = appSettings.getInt("appearance", 0);
        PlayerViews.appearance(appearance == 2 || appearance == 0 && systemDark);
        if (PlayerViews.dark()) setTheme(R.style.PlayerDarkTheme);
        super.onCreate(state);
        updateInstaller = new AppUpdateInstaller(this);
        LaunchMotion.useCustomSplash(this);
        library = Library.get(this);
        if (readerCache == null) readerCache = new ReaderCache(new File(getFilesDir(), "reader-offline"));
        tracks = library.tracks();
        indexTracks();
        settings = getSharedPreferences("player_ui", MODE_PRIVATE);
        musicSettings = getSharedPreferences(MusicCollections.PREFS, MODE_PRIVATE);
        collections = new MusicCollections(musicSettings.getString(MusicCollections.STATE, ""));
        readMusicHistory();
        if (state != null) {
            backupContent = state.getBoolean("backup_content");
            targetId = state.getString("target", "");
            filter = state.getInt("music_filter", ALL_TRACKS);
            playlistId = state.getString("music_playlist", "");
            String pendingInstall = state.getString("pending_update_install");
            if (pendingInstall != null) pendingUpdateInstall = new File(pendingInstall);
            String permissionInstall = state.getString("update_after_permission");
            if (permissionInstall != null) installAfterPermission = new File(permissionInstall);
            promptedUpdateVersion = state.getInt("prompted_update_version");
        }
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right,
                        Math.max(safe.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            if (view.getHeight() > 0) {
                updatePlayerWidth();
            }
            if (miniCard != null) {
                if (Build.VERSION.SDK_INT >= 30) keyboardVisible = insets.isVisible(WindowInsets.Type.ime());
                updateHomeChrome();
            }
            return insets;
        });
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        setContentView(root);
        buildHome(state != null ? state.getInt("section", MUSIC)
                : launcherStart ? appSettings.getInt("start_section", MUSIC) : MUSIC);
        buildPlayer();
        filter();
        bound = bindService(new Intent(this, PlaybackService.class), connection, BIND_AUTO_CREATE);
        if (state == null) handleIntent(getIntent());
        else {
            if (section == ANIME) anime.restoreState(state);
            if (state.getBoolean("player")) showPlayer(state.getBoolean("lyrics"));
            else if (readers[section] != null) readers[section].restoreState(state);
            if (state.getBoolean("app_settings_open")) {
                showAppSettings();
                if (state.getBoolean("open_source_page")) showOpenSourceNotices();
                if (state.getBoolean("acknowledgements_open")) showAcknowledgements();
                String licenseAsset = state.getString("open_license");
                OpenSourceNotices.Document license = acknowledgementsPage == null
                        && OpenSourceNotices.PROJECT_LICENSE.asset().equals(licenseAsset)
                        ? OpenSourceNotices.PROJECT_LICENSE : OpenSourceNotices.findDocument(licenseAsset);
                if (license != null && license != OpenSourceNotices.PROJECT_LICENSE) showAcknowledgements();
                if (license != null) showLicenseDocument(license);
            }
        }
        Runnable afterLaunch = () -> {
            if (isFinishing() || isDestroyed()) return;
            if (!restoreMessage.isEmpty()) toast(restoreMessage);
            if (launcherStart && section != ANIME && !appSettings.getBoolean("guide_seen", false) && tracks.isEmpty()
                    && AndroidReaderSources.get(this).list(ReaderSources.NOVEL).isEmpty()
                    && AndroidReaderSources.get(this).list(ReaderSources.COMIC).isEmpty()) {
                showGuide();
            }
            if (launcherStart) checkAppUpdate(false);
        };
        if (launcherStart) LaunchMotion.show(root, afterLaunch);
        else afterLaunch.run();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("target", targetId);
        state.putInt("music_filter", filter);
        state.putString("music_playlist", playlistId);
        state.putBoolean("player", inPlayer);
        state.putBoolean("lyrics", inLyrics);
        state.putInt("section", section);
        if (section == ANIME) anime.saveState(state);
        state.putBoolean("backup_content", backupContent);
        state.putBoolean("app_settings_open", appSettingsPage != null);
        state.putBoolean("open_source_page", openSourcePage != null);
        state.putBoolean("acknowledgements_open", acknowledgementsPage != null);
        state.putString("open_license", openLicenseAsset);
        if (pendingUpdateInstall != null) state.putString("pending_update_install", pendingUpdateInstall.getPath());
        if (installAfterPermission != null) state.putString("update_after_permission", installAfterPermission.getPath());
        state.putInt("prompted_update_version", promptedUpdateVersion);
        if (!inPlayer && readers[section] != null) readers[section].saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onStart() {
        super.onStart();
        refreshIfChanged();
        main.post(tick);
    }
    @Override protected void onResume() {
        super.onResume();
        if (section == ANIME && anime != null) anime.onSelected();
        main.post(this::presentPendingAppUpdate);
    }
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) presentPendingAppUpdate();
    }
    @Override protected void onStop() {
        savePosition();
        for (ReaderSection reader : readers) if (reader != null) reader.pause();
        main.removeCallbacks(tick);
        super.onStop();
    }
    @Override protected void onDestroy() {
        if (effectsDialog != null) effectsDialog.dismiss();
        if (musicDownloadProgress != null) musicDownloadProgress.sheet.dialog.dismiss();
        if (updateDownload != null) updateDownload.transfer.cancel();
        if (updateSheet != null) updateSheet.dialog.dismiss();
        updateRequests.shutdownNow();
        if (musicOnline != null) musicOnline.close();
        for (ReaderSection reader : readers) if (reader != null) reader.destroy();
        if (anime != null) anime.destroy();
        if (bound) unbindService(connection);
        images.shutdownNow();
        lyricReader.shutdownNow();
        super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!inPlayer && appSettingsPage == null && readers[section] != null
                && readers[section].handleVolumeKey(keyCode)) {
            readerVolumeKey = keyCode;
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == readerVolumeKey && readerVolumeKey != KeyEvent.KEYCODE_UNKNOWN) {
            readerVolumeKey = KeyEvent.KEYCODE_UNKNOWN;
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (licensePage != null) closeLicenseDocument();
        else if (acknowledgementsPage != null) closeAcknowledgements();
        else if (openSourcePage != null) closeOpenSourceNotices();
        else if (appSettingsPage != null || inPlayer) showHome();
        else if (section == MUSIC && musicOnline.onBackPressed()) return;
        else if (search.getVisibility() == View.VISIBLE) toggleSearch(false);
        else if (section == MUSIC && filter == RECENT_TRACKS && playlistId.isEmpty()) setMusicFilter(ALL_TRACKS);
        else if (readers[section] != null && readers[section].onBackPressed()) return;
        else if (section == ANIME && anime.onBackPressed()) return;
        else if (section != MUSIC) selectSection(MUSIC);
        else super.onBackPressed();
    }

    private void buildHome(int initial) {
        home = column();
        pad(home, 16, 3, 16, 6);
        FrameLayout.LayoutParams homeLayout = new FrameLayout.LayoutParams(M, M, Gravity.CENTER);
        root.addView(home, homeLayout);
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            ReaderSection reader = readers[section];
            updateHomeWidth(reader != null && reader.isReading());
            updatePlayerWidth();
        });

        chrome = new AnonHome(this);
        homeHeader = chrome.header(this::showAppSettings);
        add(home, homeHeader, W, 0);

        FrameLayout pages = new FrameLayout(this);
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            sectionPages[i] = new FrameLayout(this);
            pages.addView(sectionPages[i], new FrameLayout.LayoutParams(M, M));
        }
        home.addView(pages, new LinearLayout.LayoutParams(M, 0, 1));
        buildMusicPage();
        buildReaderSection(COMICS, COMIC_SOURCE, "reader-samples/comic-source.json");
        buildReaderSection(NOVELS, NOVEL_SOURCE, "reader-samples/novel-source.json");
        sectionBar = chrome.navigation(destination -> {
            if (destination == ANIME && anime != null) anime.leaveLegacy();
            selectSection(destination);
        });
        add(home, sectionBar, W, 2);
        selectSection(initial);
    }

    private void updateHomeChrome() {
        if (miniCard == null || sectionBar == null) return;
        ReaderSection reader = readers[section];
        boolean reading = reader != null && reader.isReading();
        boolean homeActive = !inPlayer && appSettingsPage == null;
        homeHeader.setVisibility(reading ? View.GONE : View.VISIBLE);
        home.setKeepScreenOn(homeActive && reader != null && reader.keepAwake());
        updateReaderBars(homeActive && reader != null && reader.immersive());
        int inset = reading ? 0 : dp(16);
        if (home.getPaddingLeft() != inset) {
            home.setPadding(inset, reading ? 0 : dp(3), inset, reading ? 0 : dp(6));
        }
        updateHomeWidth(reading);
        boolean visible = !reading && !keyboardVisible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        miniCard.setVisibility(homeActive && section == MUSIC && visible && current() != null
                ? View.VISIBLE : View.GONE);
        sectionBar.setVisibility(visibility);
        if (homeActive && !reading && pendingStartupUpdate != null) presentPendingAppUpdate();
    }

    private void updateHomeWidth(boolean reading) {
        int innerWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        if (innerWidth <= 0) return;
        int contentWidth = Math.min(innerWidth, dp(640));
        updatePageWidth(home, reading ? innerWidth : contentWidth);
        if (appSettingsPage != null) updatePageWidth(appSettingsPage, contentWidth);
        if (openSourcePage != null) updatePageWidth(openSourcePage, contentWidth);
        if (acknowledgementsPage != null) updatePageWidth(acknowledgementsPage, contentWidth);
        if (licensePage != null) updatePageWidth(licensePage, contentWidth);
    }

    private void updatePlayerWidth() {
        if (player == null) return;
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int height = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (width <= 0) return;
        int maximum = dp(width >= dp(480) && width > height ? 920 : 630);
        updatePageWidth(player, Math.min(width, maximum));
    }

    private void updatePageWidth(View page, int width) {
        ViewGroup.LayoutParams params = page.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            page.setLayoutParams(params);
        }
    }

    @SuppressWarnings("deprecation")
    private void updateReaderBars(boolean hide) {
        if (hide == hidingReaderBars) return;
        hidingReaderBars = hide;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (hide) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            int chrome = PlayerViews.dark() ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (!PlayerViews.dark() && Build.VERSION.SDK_INT >= 27) chrome |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            if (hide) chrome |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().getDecorView().setSystemUiVisibility(chrome);
        }
    }

    private void buildMusicPage() {
        LinearLayout musicTab = column();
        sectionPages[MUSIC].addView(musicTab, new FrameLayout.LayoutParams(M, M));
        FrameLayout musicPages = new FrameLayout(this);
        musicTab.addView(musicPages, new LinearLayout.LayoutParams(M, 0, 1));
        musicOnline = new MusicOnlineSection(this, this::playMusicOnline, this::downloadMusicOnline,
                musicPages, this::artwork);
        LinearLayout music = column();
        musicPages.addView(music, new FrameLayout.LayoutParams(M, M));
        miniCard = buildMiniPlayer();
        add(musicTab, miniCard, 62, 5);
        add(music, SectionControls.heading(this, "音乐",
                SectionControls.action(this, "音源", true, musicOnline::showSources)), W, 0);
        add(music, musicOnline.searchBox(), W, 6);
        search = field("搜索曲名、作者或来源");
        search.setBackground(surface(this, SURFACE, 7, 0));
        search.setSingleLine();
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            getSystemService(InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            return true;
        });
        search.setVisibility(View.GONE);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                filter();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        add(music, chrome.library(() -> toggleSearch(search.getVisibility() != View.VISIBLE),
                this::setMusicFilter, this::playlistsDialog, this::musicFilterMenu, this::musicImportMenu,
                this::confirmClearMusicHistory), W, 6);
        add(music, search, W, 5);
        list = new ListView(this);
        list.setDivider(new ColorDrawable(LINE));
        list.setDividerHeight(dp(1));
        list.setHeaderDividersEnabled(false);
        list.setFooterDividersEnabled(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        pad(list, 0, 0, 0, 4);
        music.addView(list, new LinearLayout.LayoutParams(M, 0, 1));
        LinearLayout shelf = column();
        list.addHeaderView(shelf, null, false);

        busy = column();
        LinearLayout statusRow = row();
        busyText = text("", 12, MUTED, false);
        statusRow.addView(busyText, weight(0, W));
        importCancel = action("取消导入", false, () -> {
            MusicImportTask task = activeImport;
            if (task != null) task.cancel();
            syncImportProgress();
        });
        statusRow.addView(importCancel, size(W, 44));
        busy.addView(statusRow, size(M, W));
        importProgress = musicImportProgressBar();
        busy.addView(importProgress, size(M, 5));
        busy.setVisibility(View.GONE);
        add(shelf, busy, W, 4);


        LinearLayout footer = column();
        empty = row();
        pad(empty, 14, 15, 13, 15);
        LinearLayout words = column();
        emptyTitle = text("", 16, FG, true);
        emptyHint = text("", 13, MUTED, false);
        words.addView(emptyTitle);
        add(words, emptyHint, W, 5);
        empty.addView(words, weight(0, W));
        emptyEmote = MyGoEmotes.icon(this, R.drawable.mygo_create, 40);
        LinearLayout.LayoutParams emotePosition = size(40, 40);
        emotePosition.setMarginStart(dp(12));
        empty.addView(emptyEmote, emotePosition);
        add(footer, empty, W, 4);
        list.addFooterView(footer, null, false);
        songs = new Songs();
        list.setAdapter(songs);
    }

    private void musicImportMenu() {
        Sheet chooser = sheet("导入音乐", "note");
        add(chooser.body, action("导入文件", true, () -> { chooser.dialog.dismiss(); pick(null); }), 48, 0);
        add(chooser.body, action("导入链接", false, () -> { chooser.dialog.dismiss(); linkDialog(""); }), 48, 6);
        add(chooser.body, action("音源管理", false, () -> { chooser.dialog.dismiss(); musicOnline.showSources(); }), 48, 6);
        chooser.show();
    }

    private void musicFilterMenu() {
        String[] options = {"本地文件", "链接导入", "最近播放"};
        int[] values = {LOCAL_TRACKS, LINK_TRACKS, RECENT_TRACKS};
        new android.app.AlertDialog.Builder(this).setTitle("筛选曲库")
                .setItems(options, (dialog, item) -> setMusicFilter(values[item]))
                .setNegativeButton("关闭", null).show();
    }

    private void setMusicFilter(int value) {
        boolean changingHistory = value == RECENT_TRACKS || (playlistId.isEmpty() && filter == RECENT_TRACKS);
        if (changingHistory && search.getVisibility() == View.VISIBLE) toggleSearch(false);
        playlistId = "";
        filter = value;
        if (value == RECENT_TRACKS) readMusicHistory();
        filter();
        if (changingHistory) list.setSelection(0);
    }

    private void confirmClearMusicHistory() {
        new android.app.AlertDialog.Builder(this).setTitle("清空音乐历史记录？")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    MusicCollections.clearRecent(musicSettings);
                    refreshMusicHistory();
                })
                .show();
    }

    private void removeMusicHistoryItem(String id) {
        MusicCollections.removeRecent(musicSettings, id);
        refreshMusicHistory();
    }

    private void refreshMusicHistory() {
        if (readMusicHistory() && playlistId.isEmpty() && filter == RECENT_TRACKS) filter();
    }

    private boolean readMusicHistory() {
        String recent = musicSettings.getString(MusicCollections.RECENT, "[]");
        String stats = musicSettings.getString(MusicCollections.RECENT_STATS, "{}");
        if (recentSnapshot.equals(recent) && recentStatsSnapshot.equals(stats)) return false;
        recentSnapshot = recent;
        recentStatsSnapshot = stats;
        recentStats = MusicCollections.readRecentStats(stats);
        return true;
    }

    private FrameLayout buildMiniPlayer() {
        FrameLayout card = new FrameLayout(this);
        card.setBackground(surface(this, SURFACE, 7, 0));
        card.setClipToOutline(true);
        LinearLayout mini = row();
        miniControls = mini;
        pad(mini, 8, 4, 5, 6);
        mini.setBackground(button(this, Color.TRANSPARENT, 9, 0));
        mini.setFocusable(true);
        mini.setContentDescription("展开播放页");
        mini.setOnClickListener(v -> showPlayer(false));
        miniCover = art(6);
        mini.addView(miniCover, size(44, 44));
        artwork(miniCover, "");
        LinearLayout info = column();
        pad(info, 10, 0, 3, 0);
        miniTitle = text("未在播放", 15, FG, true);
        miniMeta = text("从曲库选择一首音频", 12, MUTED, false);
        oneLine(miniTitle);
        oneLine(miniMeta);
        info.addView(miniTitle);
        add(info, miniMeta, W, 3);
        mini.addView(info, weight(0, W));
        FrameLayout miniButton = icon("play", "播放", 44, 20, BERRY, ON_ACCENT, this::toggle);
        miniPlay = (Icon) miniButton.getChildAt(0);
        mini.addView(miniButton);
        mini.addView(icon("next", "下一首", 44, 19, Color.TRANSPARENT, BERRY,
                () -> { if (service != null) service.next(); }));
        card.addView(mini, new FrameLayout.LayoutParams(M, M));
        miniProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        miniProgress.setMax(1000);
        miniProgress.setProgressTintList(ColorStateList.valueOf(BERRY));
        miniProgress.setProgressBackgroundTintList(ColorStateList.valueOf(BLUSH));
        miniProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        miniProgress.setVisibility(View.INVISIBLE);
        FrameLayout.LayoutParams progressLayout = new FrameLayout.LayoutParams(M, dp(2), Gravity.BOTTOM);
        progressLayout.leftMargin = dp(13);
        progressLayout.rightMargin = dp(13);
        progressLayout.bottomMargin = dp(2);
        card.addView(miniProgress, progressLayout);
        return card;
    }

    private void buildReaderSection(int destination, int requestCode, String example) {
        ReaderSection reader = new ReaderSection(this, destination == COMICS, requestCode, readerCache,
                this::updateHomeChrome);
        reader.setSampleSource(example);
        readers[destination] = reader;
        sectionPages[destination].addView(reader.view(), new FrameLayout.LayoutParams(M, M));
    }

    private void selectSection(int destination) {
        if (destination < 0 || destination >= SECTION_NAMES.length) destination = MUSIC;
        if (destination == ANIME && anime == null) {
            anime = new AnimeSection(this);
            sectionPages[ANIME].addView(anime.view(), new FrameLayout.LayoutParams(M, M));
        }
        boolean switching = section != destination;
        boolean back = destination < section;
        if (switching) {
            if (section == MUSIC) miniCard.setVisibility(View.GONE);
            View outgoing = sectionPages[section];
            PageMotion.prepareExit((ViewGroup) outgoing.getParent(), outgoing, back);
            if (readers[section] != null) readers[section].pause();
        }
        if (section == MUSIC && destination != MUSIC) musicOnline.onLeaveSection();
        if (destination != MUSIC && search.getVisibility() == View.VISIBLE) toggleSearch(false);
        section = destination;
        chrome.updateSection(destination);
        for (int i = 0; i < SECTION_NAMES.length; i++) {
            sectionPages[i].setVisibility(i == destination ? View.VISIBLE : View.GONE);
        }
        if (readers[section] != null) readers[section].onSelected();
        if (section == ANIME) anime.onSelected();
        updateHomeChrome();
        if (switching) PageMotion.enterTab(sectionPages[section], back);
    }

    private void buildPlayer() {
        player = column();
        pad(player, 16, 4, 16, 8);
        root.addView(player, new FrameLayout.LayoutParams(M, M, Gravity.CENTER));
        player.setVisibility(View.GONE);

        LinearLayout top = row();
        FrameLayout back = icon("back", "收起播放页", 48, 22, SURFACE, BERRY, this::showHome);
        back.setBackground(button(this, SURFACE, 8, 0));
        top.addView(back);
        TextView topLabel = text("正在播放", 18, FG, true);
        if (Build.VERSION.SDK_INT >= 28) topLabel.setAccessibilityHeading(true);
        oneLine(topLabel);
        LinearLayout.LayoutParams titleSpace = weight(0, W);
        titleSpace.setMarginStart(dp(10));
        top.addView(topLabel, titleSpace);
        effectsButton = action("音效", false, this::openEffects);
        effectsButton.setBackground(button(this, SURFACE, 8, 0));
        effectsButton.setFocusable(true);
        LinearLayout.LayoutParams effectSpace = size(60, 48);
        effectSpace.setMarginEnd(dp(6));
        top.addView(effectsButton, effectSpace);
        FrameLayout queue = icon("queue", "播放队列", 48, 21, SURFACE, BERRY, this::queueDialog);
        queue.setBackground(button(this, SURFACE, 8, 0));
        top.addView(queue);
        add(player, top, 54, 0);

        LinearLayout visual = column();
        LinearLayout tabs = row();
        tabs.setGravity(Gravity.CENTER);
        pad(tabs, 3, 3, 3, 3);
        tabs.setBackground(surface(this, SURFACE, 8, 0));
        coverTab = action("封面", false, () -> playerTab(false));
        lyricsTab = action("歌词", false, () -> playerTab(true));
        tabs.addView(coverTab, weight(0, M));
        tabs.addView(lyricsTab, weight(0, M));
        LinearLayout.LayoutParams switchSpace = size(170, 54);
        switchSpace.gravity = Gravity.CENTER_HORIZONTAL;
        switchSpace.bottomMargin = dp(8);
        visual.addView(tabs, switchSpace);

        playerStage = new FrameLayout(this);
        coverStageBackground = surface(this, SURFACE, 8, LINE);
        lyricStageBackground = surface(this, SURFACE, 10, LINE);
        playerStage.setClipToOutline(true);
        cover = art(20);
        cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        artwork(cover, "");
        coverMount = new SquareCover();
        playerStage.addView(coverMount, new FrameLayout.LayoutParams(M, M));

        lyricPane = new MusicLyricsPane(this, time -> {
            Track track = current();
            if (track != null && track.id.equals(shownId) && service != null) service.seekTo(time);
        }, () -> { Track track = current(); if (track != null) pick(track); });
        playerStage.addView(lyricPane, new FrameLayout.LayoutParams(M, M));
        visual.addView(playerStage, new LinearLayout.LayoutParams(M, 0, 1));
        add(player, new PlaybackLayout(visual, buildPlaybackDock()), M, 5);
        playerTab(false);
    }

    private LinearLayout buildPlaybackDock() {
        LinearLayout dock = column();
        pad(dock, 15, 11, 15, 7);
        dock.setBackground(surface(this, SURFACE, 10, 0));
        LinearLayout labels = row();
        LinearLayout info = column();
        title = text("未在播放", 23, FG, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        meta = text("从曲库选择一首音频", 12, MUTED, false);
        oneLine(meta);
        info.addView(title);
        add(info, meta, W, 5);
        labels.addView(info, weight(0, W));
        downloadTrack = icon("download", "下载曲目到本地", 48, 21, Color.TRANSPARENT, BERRY, () -> {
            Track track = current();
            if (track != null) downloadMusicOnline(track);
        });
        downloadTrack.setVisibility(View.GONE);
        labels.addView(downloadTrack);
        trackOptions = icon("more", "曲目选项", 48, 21, BLUSH, BERRY, () -> {
            Track current = current();
            if (current != null) trackDialog(current);
        });
        trackOptions.setVisibility(View.GONE);
        labels.addView(trackOptions);
        add(dock, labels, W, 0);

        seek = new SeekBar(this) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) seekTrackId = "";
                return super.onTouchEvent(event);
            }
        };
        seek.setProgressTintList(ColorStateList.valueOf(BERRY));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(BLUSH));
        seek.setThumbTintList(ColorStateList.valueOf(BERRY));
        seek.setContentDescription("播放进度");
        pad(seek, 5, 0, 5, 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (!user || service == null) return;
                long target = value;
                elapsed.setText(stamp(target));
                if (!seeking) service.seekTo(target);
                if (inLyrics) lyricPane.setPosition(target);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {
                seeking = true;
                seekTrackId = shownId;
            }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                PlaybackService.Track current = service == null ? null : service.currentTrack();
                if (current != null && current.id.equals(seekTrackId)) service.seekTo(bar.getProgress());
                seeking = false;
                seekTrackId = "";
            }
        });
        add(dock, seek, 48, 0);
        LinearLayout times = row();
        elapsed = text("0:00", 12, MUTED, false);
        length = text("0:00", 12, MUTED, false);
        elapsed.setTypeface(Typeface.MONOSPACE);
        length.setTypeface(Typeface.MONOSPACE);
        status = text("", 12, TEAL, false);
        status.setGravity(Gravity.CENTER);
        oneLine(status);
        times.addView(elapsed);
        times.addView(status, weight(0, W));
        times.addView(length);
        add(dock, times, 20, 0);
        playbackErrorAction = action("播放遇到问题　查看原因 ›", false, this::showPlaybackError);
        playbackErrorAction.setTextColor(textTone(BERRY));
        playbackErrorAction.setVisibility(View.GONE);
        add(dock, playbackErrorAction, 48, 0);

        LinearLayout controls = row();
        int[] sizes = {48, 48, 58, 48, 48};
        String[] names = {"shuffle", "prev", "play", "next", "repeat"};
        String[] descriptions = {"随机播放", "上一首", "播放", "下一首", "循环模式"};
        Runnable[] callbacks = {
                () -> { if (service != null) service.setShuffle(!service.isShuffle()); },
                () -> { if (service != null) service.previous(); },
                this::toggle,
                () -> { if (service != null) service.next(); },
                this::changeRepeat
        };
        for (int i = 0; i < sizes.length; i++) {
            boolean primary = i == 2;
            FrameLayout control = icon(names[i], descriptions[i], sizes[i], primary ? 29 : 22,
                    primary ? BERRY : Color.TRANSPARENT, primary ? ON_ACCENT : MUTED, callbacks[i]);
            if (primary) {
                control.setBackground(button(this, BERRY, 21, 0));
                LinearLayout.LayoutParams position = size(sizes[i], sizes[i]);
                position.setMarginStart(dp(1));
                position.setMarginEnd(dp(1));
                controls.addView(control, position);
            } else {
                controls.addView(control, weight(0, sizes[i]));
            }
            if (i == 0) shuffle = (Icon) control.getChildAt(0);
            if (i == 2) play = (Icon) control.getChildAt(0);
            if (i == 4) repeat = (Icon) control.getChildAt(0);
        }
        add(dock, controls, 61, 3);
        LinearLayout options = row();
        speedButton = action("倍速 · 1×", false, this::chooseSpeed);
        timerButton = action("定时停止", false, this::chooseSleepTimer);
        options.addView(speedButton, weight(0, 48));
        options.addView(timerButton, weight(0, 48));
        add(dock, options, 48, 0);
        return dock;
    }

    private final class SquareCover extends FrameLayout {
        private final FrameLayout mount;

        SquareCover() {
            super(MainActivity.this);
            Icon largeStar = new Icon(MainActivity.this, "sparkle", BERRY, 17);
            largeStar.setAlpha(0.82f);
            FrameLayout.LayoutParams upper = new FrameLayout.LayoutParams(dp(33), dp(33),
                    Gravity.TOP | Gravity.END);
            upper.setMarginEnd(dp(5));
            upper.topMargin = dp(5);
            addView(largeStar, upper);
            Icon smallStar = new Icon(MainActivity.this, "sparkle", TEAL, 13);
            smallStar.setAlpha(0.78f);
            FrameLayout.LayoutParams lower = new FrameLayout.LayoutParams(dp(30), dp(30),
                    Gravity.BOTTOM | Gravity.START);
            lower.setMarginStart(dp(6));
            lower.bottomMargin = dp(4);
            addView(smallStar, lower);

            mount = new FrameLayout(MainActivity.this);
            pad(mount, 6, 6, 6, 6);
            mount.setBackground(surface(MainActivity.this, SURFACE, 8, 0));
            mount.setElevation(dp(3));
            mount.addView(cover, new FrameLayout.LayoutParams(M, M));
            addView(mount, new FrameLayout.LayoutParams(M, M, Gravity.CENTER));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int area = Math.min(View.MeasureSpec.getSize(widthSpec), View.MeasureSpec.getSize(heightSpec));
            int side = Math.max(0, Math.min(dp(390), area - dp(area >= dp(200) ? 38 : 16)));
            mount.getLayoutParams().width = side;
            mount.getLayoutParams().height = side;
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private final class PlaybackLayout extends LinearLayout {
        private final View visual;
        private final ScrollView dockPane;
        private boolean sideBySide;

        PlaybackLayout(View visual, View dock) {
            super(MainActivity.this);
            this.visual = visual;
            dockPane = new ScrollView(MainActivity.this);
            dockPane.setVerticalScrollBarEnabled(false);
            dockPane.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
            dockPane.addView(dock, new FrameLayout.LayoutParams(M, W));
            setOrientation(VERTICAL);
            addView(visual, new LinearLayout.LayoutParams(M, 0, 1));
            LinearLayout.LayoutParams bottom = size(M, W);
            bottom.topMargin = dp(11);
            addView(dockPane, bottom);
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = View.MeasureSpec.getSize(widthSpec);
            int height = View.MeasureSpec.getSize(heightSpec);
            boolean wide = width >= dp(480) && width > height;
            if (sideBySide != wide) {
                sideBySide = wide;
                setOrientation(wide ? HORIZONTAL : VERTICAL);
                visual.setLayoutParams(wide ? new LinearLayout.LayoutParams(0, M, 1)
                        : new LinearLayout.LayoutParams(M, 0, 1));
                LinearLayout.LayoutParams position = wide ? size(288, W) : size(M, W);
                if (wide) {
                    position.gravity = Gravity.CENTER_VERTICAL;
                    position.setMarginStart(dp(14));
                } else {
                    position.topMargin = dp(11);
                }
                dockPane.setLayoutParams(position);
            }
            super.onMeasure(widthSpec, heightSpec);
        }
    }

    private void filter() {
        visible.clear();
        MusicCollections.Playlist active = collections.playlist(playlistId);
        List<Track> shelf = active != null ? tracksForIds(active.trackIds())
                : filter == RECENT_TRACKS ? tracksForIds(MusicCollections.readIds(recentSnapshot)) : tracks;
        for (Track track : shelf) {
            boolean link = !track.sourceUrl.isEmpty() || track.isOnline();
            if (active == null && ((filter == LOCAL_TRACKS && !track.isLocal()) || (filter == LINK_TRACKS && !link)
                    || (filter == FAVORITE_TRACKS && !collections.isFavorite(track.id)))) continue;
            String searchable = (track.title + " " + track.artist + " " + track.source + " " + track.fileName)
                    .toLowerCase(Locale.ROOT);
            if (searchable.contains(query)) visible.add(track);
        }
        chrome.updateLibrary(filter, active == null ? "" : active.name,
                active == null && filter == RECENT_TRACKS && !shelf.isEmpty());
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        emptyTitle.setText(!query.isEmpty() ? "没有搜索结果" : active != null ? "歌单还没有曲目"
                : filter == FAVORITE_TRACKS ? "还没有收藏" : filter == RECENT_TRACKS ? "还没有播放记录" : "这里还没有曲目");
        emptyHint.setText(!query.isEmpty() ? "试试曲名、作者或来源"
                : active != null ? "从曲目选项添加歌曲，或打开歌单选曲"
                : filter == FAVORITE_TRACKS ? "可从曲目选项收藏歌曲"
                : filter == RECENT_TRACKS ? "播放过的歌曲会显示在这里"
                : tracks.isEmpty() ? "选择音视频文件，或粘贴媒体链接" : "切换到「全部」查看已导入的曲目");
        if (visible.isEmpty()) {
            int emote = !query.isEmpty() ? R.drawable.mygo_look
                    : active != null || filter == ALL_TRACKS ? R.drawable.mygo_create
                    : filter == FAVORITE_TRACKS ? R.drawable.mygo_love : 0;
            emptyEmote.setVisibility(emote == 0 ? View.GONE : View.VISIBLE);
            emptyEmote.setImageResource(emote);
        }
        songs.notifyDataSetChanged();
    }

    private void toggleSearch(boolean show) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        chrome.updateSearch(show);
        if (show) {
            list.setSelection(0);
            search.setVisibility(View.VISIBLE);
            search.requestFocus();
            search.post(() -> keyboard.showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
        } else {
            keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.setText("");
            search.clearFocus();
            search.setVisibility(View.GONE);
        }
    }

    private void showHome() {
        View outgoing = inPlayer ? player : licensePage != null ? licensePage
                : acknowledgementsPage != null ? acknowledgementsPage
                : openSourcePage != null ? openSourcePage : appSettingsPage;
        if (outgoing != null) PageMotion.prepareExit(root, outgoing, true);
        removeAppSettings();
        inPlayer = false;
        player.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        updateHomeChrome();
        if (outgoing != null) PageMotion.enter(home, true);
    }

    private void showPlayer(boolean lyrics) {
        boolean opening = !inPlayer;
        if (opening) PageMotion.prepareExit(root, appSettingsPage != null ? appSettingsPage : home, false);
        removeAppSettings();
        if (search.getVisibility() == View.VISIBLE) toggleSearch(false);
        inPlayer = true;
        home.setVisibility(View.GONE);
        player.setVisibility(View.VISIBLE);
        updateHomeChrome();
        playerTab(lyrics, !opening);
        sync();
        if (opening) PageMotion.enter(player, false);
    }

    private void playerTab(boolean lyrics) {
        playerTab(lyrics, true);
    }

    private void playerTab(boolean lyrics, boolean animate) {
        boolean switching = inLyrics != lyrics;
        if (switching) PageMotion.reset(inLyrics ? lyricPane : coverMount);
        inLyrics = lyrics;
        playerStage.setBackground(lyrics ? lyricStageBackground : coverStageBackground);
        coverMount.setVisibility(lyrics ? View.GONE : View.VISIBLE);
        lyricPane.setVisibility(lyrics ? View.VISIBLE : View.GONE);
        selectTab(coverTab, !lyrics);
        selectTab(lyricsTab, lyrics);
        if (lyrics && inPlayer) syncLyrics(current(), service == null ? 0L : Math.max(0, service.position()));
        if (switching && lyrics) lyricPane.followPlayback();
        if (animate && switching) PageMotion.fade(lyrics ? lyricPane : coverMount);
    }

    private void selectTab(TextView tab, boolean selected) {
        tab.setSelected(selected);
        tab.setTextColor(textTone(selected ? BERRY : MUTED));
        tab.setBackground(button(this, selected ? BLUSH : Color.TRANSPARENT, 6, 0));
        tab.setFocusable(true);
    }

    private void sync() {
        syncImportProgress();
        long now = SystemClock.elapsedRealtime();
        if (now - checkedAt >= 1000) {
            checkedAt = now;
            if (!working) refreshIfChanged();
        }
        Track track = current();
        refreshMusicHistory();
        String id = track == null ? "" : track.id;
        if (!shownId.equals(id)) {
            shownId = id;
            songs.notifyDataSetChanged();
            if (openQueueList != null) openQueueList.invalidateViews();
            showTrackMetadata(track);
            updateHomeChrome();
        }
        boolean playing = service != null && service.isPlayRequested();
        playIcon(miniPlay, playing);
        playIcon(play, playing);
        long position = service == null ? 0 : Math.max(0, service.position());
        long duration = service == null ? 0 : Math.max(0, service.duration());
        int progress = duration <= 0 ? 0 : (int) Math.min(1000, position * 1000 / duration);
        if (miniProgress.getVisibility() != (duration > 0 ? View.VISIBLE : View.INVISIBLE)) {
            miniProgress.setVisibility(duration > 0 ? View.VISIBLE : View.INVISIBLE);
        }
        miniProgress.setProgress(progress);
        if (!seeking) {
            seek.setEnabled(duration > 0);
            int maximum = (int) Math.min(Integer.MAX_VALUE, duration);
            if (seek.getMax() != maximum) {
                seek.setMax(maximum);
                seek.setKeyProgressIncrement(Math.max(1, Math.min(5_000, maximum / 20)));
            }
            seek.setProgress((int) Math.min(maximum, position));
            setChangedText(elapsed, stamp(position));
        }
        setChangedText(length, duration > 0 ? stamp(duration) : "—:—");
        if (inPlayer && Build.VERSION.SDK_INT >= 30 && seek.isEnabled()) {
            String state = elapsed.getText() + " / " + length.getText();
            if (!TextUtils.equals(seek.getStateDescription(), state)) seek.setStateDescription(state);
        }
        String error = service == null ? "" : service.getError();
        boolean buffering = service != null && service.isBuffering();
        setChangedText(status, error.isEmpty() && buffering ? "正在缓冲" : "");
        playbackErrorAction.setVisibility(error.isEmpty() ? View.GONE : View.VISIBLE);
        if (!inPlayer && !error.isEmpty() && !error.equals(lastError)) toast(error);
        lastError = error;
        if (service != null) {
            shuffle.show("shuffle", service.isShuffle() ? TEAL : MUTED);
            ((View) shuffle.getParent()).setSelected(service.isShuffle());
            int mode = service.getRepeatMode();
            repeat.show(mode == PlaybackService.REPEAT_ONE ? "one" : "repeat",
                    mode == PlaybackService.REPEAT_OFF ? MUTED : TEAL);
            ((View) repeat.getParent()).setSelected(mode != PlaybackService.REPEAT_OFF);
            View repeatControl = (View) repeat.getParent();
            String repeatDescription = "循环模式，" + (mode == PlaybackService.REPEAT_ONE ? "单曲循环"
                    : mode == PlaybackService.REPEAT_ALL ? "列表循环" : "顺序播放");
            if (!TextUtils.equals(repeatControl.getContentDescription(), repeatDescription))
                repeatControl.setContentDescription(repeatDescription);
            if (inPlayer) {
                setChangedText(speedButton, "倍速 · " + speedLabel(service.speed()));
                long remaining = service.sleepRemaining();
                setChangedText(timerButton, remaining > 0 ? "定时 · " + (remaining + 59_999) / 60_000 + " 分钟" : "定时停止");
                timerButton.setSelected(remaining > 0);
            }
        }
        speedButton.setEnabled(service != null);
        timerButton.setEnabled(service != null);
        syncEffects();
        if (inPlayer && inLyrics) syncLyrics(track, seeking && id.equals(seekTrackId) ? seek.getProgress() : position);
        if (now - savedAt >= 3000) {
            savePosition();
            savedAt = now;
        }
    }

    private static void setChangedText(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }

    private void showPlaybackError() {
        if (service == null || service.getError().isEmpty()) return;
        Track track = current();
        Sheet sheet = sheet("播放遇到问题", "headphones");
        add(sheet.body, text(service.getError(), 15, FG, false), W, 12);
        if (track != null) {
            Uri origin = musicOrigin(track);
            if (origin != null) add(sheet.body, action("在来源网站打开", false, () -> {
                sheet.dialog.dismiss();
                openBrowser(origin);
            }), 48, 8);
            add(sheet.body, action("重试播放", true, () -> {
                sheet.dialog.dismiss();
                if (service != null && current() != null && track.id.equals(current().id)) {
                    service.play();
                    sync();
                }
            }), 48, 8);
        }
        add(sheet.body, action("关闭", false, sheet.dialog::dismiss), 48, 8);
        sheet.show();
    }

    private void showTrackMetadata(Track track) {
        miniTitle.setText(track == null ? "未在播放" : track.title);
        miniMeta.setText(track == null ? "从曲库选择一首音频" : detail(track));
        miniControls.setContentDescription(track == null ? "展开播放页，未在播放" : "展开播放页，" + track.title);
        title.setText(miniTitle.getText());
        meta.setText(miniMeta.getText());
        trackOptions.setVisibility(track == null ? View.GONE : View.VISIBLE);
        downloadTrack.setVisibility(track != null && MusicOnlineTrack.isReference(track.url)
                ? View.VISIBLE : View.GONE);
        String url = track == null ? "" : track.artwork;
        if (!url.equals(miniCover.getTag())) artwork(miniCover, url);
        if (!url.equals(cover.getTag())) artwork(cover, url);
    }

    private void syncLyrics(Track track, long position) {
        String revision = track == null ? "-" : track.id + ":" + track.lyricsFile.lastModified();
        if (lyricId.equals(revision)) {
            lyricPane.setPosition(position);
            return;
        }
        lyricId = revision;
        int generation = ++lyricGeneration;
        if (lyricLoad != null) lyricLoad.cancel(true);
        if (track == null) {
            lyricPane.empty("先从曲库选择一首音频", false);
            return;
        }
        lyricPane.empty("正在读取歌词…", false);
        lyricLoad = lyricReader.submit(() -> {
            try {
                List<Lyrics.Line> result = library.lyrics(track);
                main.post(() -> {
                    if (isDestroyed() || generation != lyricGeneration || !lyricId.equals(revision)) return;
                    if (result.isEmpty()) lyricPane.empty("这首曲目还没有歌词", true);
                    else lyricPane.showLines(result, seeking ? seek.getProgress() : service == null ? 0 : service.position());
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> {
                    if (!isDestroyed() && generation == lyricGeneration && lyricId.equals(revision))
                        lyricPane.empty("无法读取这份歌词，可重新选择文件", true);
                });
            }
        });
    }

    private void playIcon(Icon icon, boolean playing) {
        icon.show(playing ? "pause" : "play", ON_ACCENT);
        View parent = (View) icon.getParent();
        String description = playing ? "暂停" : "播放";
        if (!description.contentEquals(parent.getContentDescription())) parent.setContentDescription(description);
    }
    private void toggle() {
        if (service == null) toast("播放器正在启动");
        else if (service.currentTrack() != null) {
            service.toggle();
            sync();
        } else if (!visible.isEmpty()) startTrack(visible.get(0));
        else if (!tracks.isEmpty()) toast("当前曲库视图没有可播放的曲目");
        else toast("先导入一首音频");
    }

    private void openEffects() {
        if (service == null) {
            toast("播放器正在启动");
            return;
        }
        if (effectsDialog != null && effectsDialog.isShowing()) return;
        effectsDialog = new AudioEffectsDialog(this, service.audioSettings(),
                settings -> {
                    if (service == null) return;
                    service.setAudioEffects(settings);
                    syncEffects();
                }, this::openSoundSettings);
        effectsDialog.setStatus(service.audioEffectMode(), service.spatialEffectMode());
        effectsDialog.show();
    }

    private void openSoundSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            toast("请在手机设置里打开「声音与振动」");
        }
    }

    private void syncEffects() {
        AudioEffects.Mode mode = service == null ? AudioEffects.Mode.PENDING : service.audioEffectMode();
        AudioEffects.SpatialMode spatial = service == null
                ? AudioEffects.SpatialMode.OFF : service.spatialEffectMode();
        boolean active = mode == AudioEffects.Mode.DYNAMICS || mode == AudioEffects.Mode.EQUALIZER
                || spatial == AudioEffects.SpatialMode.ACTIVE;
        if (effectsOn != active) {
            effectsOn = active;
            effectsButton.setTextColor(textTone(active ? TEAL : FG));
            effectsButton.setBackground(button(this, active ? SEAFOAM : SURFACE, 8, 0));
            effectsButton.setContentDescription(active ? "音效，已启用" : "音效");
        }
        if (effectsDialog != null) {
            if (effectsDialog.isShowing()) effectsDialog.setStatus(mode, spatial);
            else effectsDialog = null;
        }
    }

    private void changeRepeat() {
        if (service == null) return;
        int mode = (service.getRepeatMode() + 1) % 3;
        service.setRepeatMode(mode);
        toast(mode == PlaybackService.REPEAT_ONE ? "单曲循环"
                : mode == PlaybackService.REPEAT_ALL ? "列表循环" : "顺序播放");
        sync();
    }


    private static String speedLabel(float value) {
        return (value == 1f ? "1" : Float.toString(value)) + "×";
    }

    private void chooseSpeed() {
        if (service == null) return;
        float[] values = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
        String[] labels = new String[values.length];
        int selected = 2;
        for (int i = 0; i < values.length; i++) {
            labels[i] = speedLabel(values[i]);
            if (values[i] == service.speed()) selected = i;
        }
        new android.app.AlertDialog.Builder(this).setTitle("播放速度")
                .setSingleChoiceItems(labels, selected, (dialog, choice) -> {
                    if (service != null && !service.setSpeed(values[choice])) toast("当前音轨不支持此倍速");
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseSleepTimer() {
        int[] values = {0, 15, 30, 45, 60, 90};
        String[] labels = {"关闭定时", "15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟"};
        new android.app.AlertDialog.Builder(this).setTitle("定时停止")
                .setItems(labels, (dialog, choice) -> {
                    if (service == null) return;
                    service.sleepAfter(values[choice]);
                    toast(choice == 0 ? "定时已关闭" : "将在 " + labels[choice] + "后暂停");
                }).setNegativeButton("取消", null).show();
    }

    private List<PlaybackService.Track> queue(List<Track> items) {
        List<PlaybackService.Track> queue = new ArrayList<>(items.size());
        for (Track track : items) queue.add(playbackTrack(track));
        return queue;
    }

    private PlaybackService.Track playbackTrack(Track track) {
        return new PlaybackService.Track(track.id, track.url, track.title, track.artist, track.artwork,
                track.source, track.referer);
    }

    private void restoreMusicQueue() {
        boolean saved = musicSettings.contains(MusicCollections.QUEUE);
        List<Track> stored = saved ? tracksForIds(MusicCollections.readIds(
                musicSettings.getString(MusicCollections.QUEUE, "[]"))) : tracks;
        List<Track> playable = new ArrayList<>();
        for (Track track : stored) if (track.available()) playable.add(track);
        String selected = saved ? musicSettings.getString(MusicCollections.CURRENT, "") : settings.getString("last", "");
        long position = saved ? musicSettings.getLong(MusicCollections.POSITION, 0) : settings.getLong("time", 0);
        for (int i = 0; i < playable.size(); i++) {
            if (!playable.get(i).id.equals(selected)) continue;
            service.restoreQueue(queue(playable), i, position);
            return;
        }
        if (saved && !playable.isEmpty()) service.restoreQueue(queue(playable), 0, 0);
    }

    private Track current() {
        return service == null || service.currentTrack() == null ? null : byId(service.currentTrack().id);
    }

    private void indexTracks() {
        tracksById.clear();
        for (Track track : tracks) tracksById.put(track.id, track);
    }

    private Track byId(String id) { return tracksById.get(id); }

    private List<Track> tracksForIds(List<String> ids) {
        List<Track> entries = new ArrayList<>(ids.size());
        for (String id : ids) {
            Track track = tracksById.get(id);
            if (track != null) entries.add(track);
        }
        return entries;
    }

    private boolean startTrack(Track track) {
        if (service == null) { toast("播放器正在启动"); return false; }
        if (!track.available()) { toast("此曲目缺少音频文件，可从完整备份恢复"); return false; }
        PlaybackService.Track playing = service.currentTrack();
        if (filter == RECENT_TRACKS && playlistId.isEmpty() && playing != null && playing.id.equals(track.id)) {
            if (!service.isPlayRequested()) service.play();
            sync();
            return true;
        }
        List<Track> playable = new ArrayList<>();
        int selected = -1;
        for (Track candidate : visible) {
            if (!candidate.available()) continue;
            if (candidate.id.equals(track.id)) selected = playable.size();
            playable.add(candidate);
        }
        if (selected < 0) return false;
        service.setQueue(queue(playable), selected);
        savePosition();
        sync();
        return true;
    }

    private void playMusicOnline(MusicOnlineEngine.Song song) {
        if (service == null) { toast("播放器正在启动，请稍后再试"); return; }
        if (musicBusy()) { toast("曲库正在更新，请稍后再试"); return; }
        try {
            MusicOnlineEngine.Config config = MusicOnlineEngine.NAVIDROME.equals(song.provider)
                    ? MusicOnlineAccounts.get(this) : null;
            Track track = library.addOnline(song, config);
            if (byId(track.id) == null) applyTracks(library.tracks());
            service.setQueue(Collections.singletonList(playbackTrack(track)), 0);
            savePosition();
            showPlayer(false);
        } catch (IOException error) {
            toast(error.getMessage());
        }
    }

    private void downloadMusicOnline(MusicOnlineEngine.Song song) {
        startMusicDownload(song.title, task -> {
            MusicOnlineEngine.Config config = MusicOnlineEngine.NAVIDROME.equals(song.provider)
                    ? MusicOnlineAccounts.get(this) : null;
            library.downloadOnline(song, config, task);
        });
    }

    private void downloadMusicOnline(Track track) {
        if (!MusicOnlineTrack.isReference(track.url)) return;
        startMusicDownload(track.title, task -> {
            MusicOnlineEngine.Config config = MusicOnlineTrack.parse(track.url).isNavidrome()
                    ? MusicOnlineAccounts.get(this) : null;
            library.downloadOnline(track, config, task);
        });
    }

    private interface MusicDownload {
        void save(MusicImportTask task) throws IOException;
    }

    private void startMusicDownload(String title, MusicDownload download) {
        if (musicBusy()) { toast("有音乐导入或下载正在进行"); return; }
        MusicImportTask task = beginImport("正在下载…");
        musicDownloadProgress = new MusicDownloadProgress(task, title);
        syncImportProgress();
        musicDownloadProgress.sheet.show();
        IMPORTS.execute(() -> {
            task.enter();
            try {
                download.save(task);
                main.post(() -> {
                    if (isDestroyed()) return;
                    refresh();
                    toast("已保存到本地曲库");
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (task.cancelled()) toast("下载已取消");
                    else {
                        if (musicDownloadProgress != null && musicDownloadProgress.task == task)
                            musicDownloadProgress.sheet.dialog.dismiss();
                        notice("下载失败", importReason(error));
                    }
                });
            } finally {
                task.exit();
                endImport(task);
            }
        });
    }

    private void playQueued(PlaybackService.Track track) {
        if (service == null) return;
        service.playQueued(track.id);
        savePosition();
        sync();
    }

    private void savePosition() {
        if (service == null || service.currentTrack() == null) return;
        settings.edit().putString("last", service.currentTrack().id)
                .putLong("time", Math.max(0, service.position())).apply();
    }

    private void refresh() {
        applyTracks(library.tracks());
        sync();
    }

    private void refreshIfChanged() {
        List<Track> committed = library.tracks();
        if (!tracks.equals(committed)) applyTracks(committed);
    }

    private void applyTracks(List<Track> committed) {
        tracks = committed;
        indexTracks();
        if (collections.prune(tracksById.keySet())) saveCollections();
        if (service != null) service.refreshQueue(queue(tracks));
        showTrackMetadata(current());
        lyricId = "";
        filter();
    }

    private final class Songs extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Track getItem(int index) { return visible.get(index); }
        @Override public long getItemId(int index) { return index; }

        @Override public View getView(int index, View recycled, ViewGroup parent) {
            SongRow song = recycled == null ? new SongRow() : (SongRow) recycled.getTag();
            song.bind(getItem(index));
            return song.item;
        }
    }

    private final class SongRow {
        final LinearLayout item = row();
        final ImageView thumb = art(6);
        final TextView name = text("", 15, FG, true);
        final TextView summary = text("", 12, MUTED, false);
        final TextView history = text("", 12, MUTED, false);
        final TextView time = text("", 12, MUTED, false);
        Track track;

        SongRow() {
            item.setTag(this);
            item.setLayoutParams(new AbsListView.LayoutParams(M, W));
            item.setMinimumHeight(dp(57));
            pad(item, 0, 5, 0, 5);
            item.setOnClickListener(v -> {
                if (startTrack(track)) showPlayer(false);
            });
            item.setOnLongClickListener(v -> { trackDialog(track); return true; });
            item.addView(thumb, size(40, 40));
            LinearLayout info = column();
            pad(info, 10, 0, 4, 0);
            oneLine(name);
            oneLine(summary);
            info.addView(name);
            add(info, summary, W, 3);
            history.setMaxLines(2);
            add(info, history, W, 3);
            item.addView(info, weight(0, W));
            oneLine(time);
            item.addView(time);
            item.addView(icon("more", "曲目选项", 44, 21, Color.TRANSPARENT, BERRY, () -> trackDialog(track)));
        }

        void bind(Track next) {
            track = next;
            artwork(thumb, track.artwork);
            boolean selected = track.id.equals(shownId);
            item.setSelected(selected);
            item.setBackground(button(MainActivity.this, selected ? SURFACE : Color.TRANSPARENT, 4, 0));
            name.setText(track.title);
            name.setTextColor(textTone(selected ? BERRY : FG));
            summary.setText(detail(track));
            boolean showHistory = playlistId.isEmpty() && filter == RECENT_TRACKS;
            history.setVisibility(showHistory ? View.VISIBLE : View.GONE);
            if (showHistory) {
                MusicCollections.HistoryStats stats = recentStats.get(track.id);
                history.setText(HistoryDisplay.summary(MainActivity.this, "播放",
                        stats == null ? 0 : stats.playCount(), stats == null ? 0 : stats.lastPlayedAt(),
                        stats != null && stats.previousCountUnknown()));
            }
            time.setVisibility(track.durationMs > 0 ? View.VISIBLE : View.GONE);
            if (track.durationMs > 0) time.setText(stamp(track.durationMs));
        }
    }

    private void pick(Track target) {
        if (musicBusy()) { toast("正在导入，请稍候"); return; }
        targetId = target == null ? "" : target.id;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, target == null)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try { startActivityForResult(pick, target == null ? FILES : LYRIC); }
        catch (ActivityNotFoundException error) { toast("此设备没有可用的文件选择器"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == UPDATE_INSTALL_PERMISSION) {
            File apk = installAfterPermission;
            installAfterPermission = null;
            if (apk != null && updateInstaller.canInstall()) {
                pendingUpdateInstall = apk;
                main.post(this::presentPendingAppUpdate);
            } else if (apk != null) {
                toast("更新已下载。允许安装后，可以从检查更新继续。");
            }
            return;
        }
        if (request == MusicOnlineSection.REQUEST_IMPORT_MUSIC_SOURCE) {
            if (result == RESULT_OK && data != null && data.getData() != null) musicOnline.handleImportFile(data.getData());
            return;
        }
        if (request == BACKUP_SAVE || request == BACKUP_RESTORE) {
            if (result == RESULT_OK && data != null && data.getData() != null) backupResult(request, data.getData());
            return;
        }
        if (AnimeSection.isRequest(request)) {
            if (anime == null) selectSection(ANIME);
            if (!anime.isLegacy()) anime.openLegacy();
            anime.onActivityResult(request, result, data);
            return;
        }
        if (ReaderSection.isLocalRequest(request)) {
            readers[request == ReaderSection.LOCAL_NOVEL_FILE ? NOVELS : COMICS].onActivityResult(request, result, data);
            return;
        }
        if (ReaderSection.isDiagnosticExportRequest(request)) {
            readers[request == ReaderSection.NOVEL_DIAGNOSTICS_EXPORT ? NOVELS : COMICS].onActivityResult(request, result, data);
            return;
        }
        if (request == COMIC_SOURCE || request == NOVEL_SOURCE) {
            ReaderSection reader = readers[request == COMIC_SOURCE ? COMICS : NOVELS];
            reader.onActivityResult(request, result, data);
            return;
        }
        if (result != RESULT_OK || data == null || (request != FILES && request != LYRIC)) return;
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        if (data.getData() != null) files.add(data.getData());
        addClip(files, data.getClipData());
        Track target = request == LYRIC ? byId(targetId) : null;
        if (request == LYRIC && target == null) { toast("找不到要添加歌词的曲目"); return; }
        importFiles(new ArrayList<>(files), target,
                (data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0);
    }

    private void showAppSettings() {
        if (appSettingsPage != null || inPlayer) return;

        LinearLayout content = column();
        LinearLayout page = settingsPage("设置", content, this::showHome);

        LinearLayout general = settingsGroup(content, "常规");
        String[] looks = {"跟随系统", "浅色", "深色"};
        addSettingsChoice(general, "外观", "appearance", looks, true);
        addSettingsChoice(general, "默认启动页", "start_section", SECTION_NAMES, false);

        LinearLayout data = settingsGroup(content, "存储与数据");
        addSettingsRow(data, "下载与存储", null, this::showStorage);
        addSettingsRow(data, "备份与恢复", null, this::showBackups);
        addSettingsRow(data, "旧版动画资料", text("原有追番、观看进度和动画源", 13, MUTED, false), () -> {
            showHome();
            selectSection(ANIME);
            anime.openLegacy();
        });

        LinearLayout help = settingsGroup(content, "帮助");
        addSettingsRow(help, "快速上手", null, this::showGuide);
        addSettingsRow(help, "关于与数据", text("版本 " + BuildConfig.VERSION_NAME, 13, MUTED, false),
                this::showAboutAndData);
        if (appUpdates.configured()) {
            addSettingsChoice(help, "启动时检查更新", "startup_update_check",
                    new String[]{"开启", "关闭"}, false);
        }
        addSettingsRow(help, "开源声明", null, this::showOpenSourceNotices);

        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        appSettingsPage = page;
        PageMotion.prepareExit(root, home, false);
        home.setVisibility(View.GONE);
        root.addView(page, new FrameLayout.LayoutParams(M, M, Gravity.CENTER));
        updateHomeChrome();
        PageMotion.enter(page, false);
    }

    private void showAboutAndData() {
        Sheet about = sheet(getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME, "note");
        add(about.body, text("导入的文件、书架和阅读进度保存在本机。在线搜索与播放会连接所选来源。"
                + "启用 Bangumi 或 WebDAV 同步后，应用会与所选服务交换同步数据。", 14, MUTED, false), W, 8);
        String label = availableAppUpdate == null ? "检查更新" : "查看 " + availableAppUpdate.versionName() + " 更新";
        add(about.body, action(label, true, () -> {
            about.dialog.dismiss();
            if (availableAppUpdate != null) showAppUpdate(availableAppUpdate);
            else checkAppUpdate(true);
        }), 48, 10);
        add(about.body, action("关闭", false, about.dialog::dismiss), 48, 6);
        about.show();
    }

    private void checkAppUpdate(boolean manual) {
        if (!appUpdates.configured()) {
            if (manual) toast("当前安装包未设置更新仓库");
            return;
        }
        if (!manual) {
            if (appSettings.getInt("startup_update_check", 0) != 0 || checkedOnLaunch || checkingAppUpdate) return;
            checkedOnLaunch = true;
        } else {
            showUpdateCheckResult = true;
            toast("正在检查更新…");
            if (checkingAppUpdate) return;
        }
        checkingAppUpdate = true;
        updateRequests.execute(() -> {
            try {
                AppUpdates.Release release = appUpdates.latest();
                main.post(() -> finishAppUpdateCheck(release, null));
            } catch (IOException unavailable) {
                main.post(() -> finishAppUpdateCheck(null, unavailable));
            }
        });
    }

    private void finishAppUpdateCheck(AppUpdates.Release release, IOException failure) {
        if (isFinishing() || isDestroyed()) return;
        checkingAppUpdate = false;
        boolean manual = showUpdateCheckResult;
        showUpdateCheckResult = false;
        boolean foreground = getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        if (failure != null) {
            if (manual && foreground) {
                String message = (failure instanceof java.net.SocketTimeoutException)
                        ? "连接更新服务超时，请稍后再试" : failure.getMessage();
                toast(TextUtils.isEmpty(message) || failure instanceof java.net.UnknownHostException
                        ? "无法连接更新服务，请检查网络" : message);
            }
            return;
        }
        availableAppUpdate = release != null && release.newerThan(BuildConfig.VERSION_CODE) ? release : null;
        if (availableAppUpdate == null) {
            pendingStartupUpdate = null;
            if (manual && foreground) toast(release == null ? "更新渠道暂无正式版本" : "已是最新版本");
            return;
        }
        if (manual && foreground && updateDownload == null && installAfterPermission == null) {
            showAppUpdate(availableAppUpdate);
        } else if (availableAppUpdate.versionCode() > promptedUpdateVersion) {
            pendingStartupUpdate = availableAppUpdate;
            presentPendingAppUpdate();
        }
    }

    private void presentPendingAppUpdate() {
        if (!hasWindowFocus() || isFinishing() || isDestroyed()
                || !getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)
                || updateSheet != null || updateDownload != null || installAfterPermission != null) return;
        if (pendingUpdateInstall != null) {
            File apk = pendingUpdateInstall;
            pendingUpdateInstall = null;
            requestUpdateInstallation(apk);
            return;
        }
        ReaderSection reader = readers[section];
        if (pendingStartupUpdate != null && !inPlayer && appSettingsPage == null && !keyboardVisible
                && (reader == null || !reader.isReading()) && appSettings.getInt("startup_update_check", 0) == 0) {
            showAppUpdate(pendingStartupUpdate);
        }
    }

    private void showAppUpdate(AppUpdates.Release update) {
        showAppUpdate(update, "");
    }

    private void showAppUpdate(AppUpdates.Release update, String downloadError) {
        promptedUpdateVersion = Math.max(promptedUpdateVersion, update.versionCode());
        pendingStartupUpdate = null;
        Sheet sheet = updateSheet("更新到 " + update.versionName());
        if (!update.notes().isEmpty()) {
            ScrollView scroll = new ScrollView(this);
            TextView notes = text(update.notes(), 14, FG, false);
            pad(notes, 0, 3, 0, 3);
            scroll.addView(notes, new ScrollView.LayoutParams(M, W));
            add(sheet.body, scroll, update.notes().length() > 180 ? 200 : W, 8);
        }
        if (!downloadError.isEmpty()) {
            add(sheet.body, text(downloadError, 13, MUTED, false), W, 8);
        }
        String downloadLabel = downloadError.isEmpty() ? "应用内下载并安装" : "重试下载并安装";
        add(sheet.body, action(downloadLabel, true, () -> startAppUpdateDownload(update)), 48, 12);
        add(sheet.body, action("去网站下载", false, () -> {
            sheet.dialog.dismiss();
            openBrowser(Uri.parse(update.websiteUrl().toString()));
        }), 48, 6);
        add(sheet.body, action("稍后", false, sheet.dialog::dismiss), 48, 6);
        sheet.show();
    }

    private Sheet updateSheet(String heading) {
        if (updateSheet != null) updateSheet.dialog.dismiss();
        Sheet sheet = sheet(heading, "download");
        updateSheet = sheet;
        sheet.dialog.setOnDismissListener(dialog -> {
            if (updateSheet == sheet) updateSheet = null;
        });
        return sheet;
    }

    private void startAppUpdateDownload(AppUpdates.Release release) {
        if (updateDownload != null) return;
        UpdateDownload download = new UpdateDownload(release);
        updateDownload = download;
        download.sheet.show();
        updateRequests.execute(() -> {
            try {
                File apk = updateInstaller.download(release, download.transfer, download::progress);
                main.post(() -> finishUpdateDownload(download, apk, null));
            } catch (IOException failure) {
                main.post(() -> finishUpdateDownload(download, null, failure));
            }
        });
    }

    private void finishUpdateDownload(UpdateDownload download, File apk, IOException failure) {
        if (isFinishing() || isDestroyed() || updateDownload != download) return;
        updateDownload = null;
        download.sheet.dialog.dismiss();
        if (updateSheet == download.sheet) updateSheet = null;
        boolean foreground = getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED);
        if (download.cancelled) {
            if (apk != null) updateInstaller.discard(apk);
            if (foreground) toast("已取消下载");
        } else if (failure != null) {
            if (foreground) {
                String message = failure.getMessage();
                showAppUpdate(download.release, TextUtils.isEmpty(message) ? "更新下载失败，请重试。" : message);
            }
        } else {
            pendingUpdateInstall = apk;
            main.post(this::presentPendingAppUpdate);
        }
    }

    private void requestUpdateInstallation(File apk) {
        if (updateInstaller.canInstall()) {
            try {
                startActivity(updateInstaller.installIntent(apk));
            } catch (IOException | ActivityNotFoundException | SecurityException unavailable) {
                installUnavailable("无法打开系统安装程序");
            }
            return;
        }
        Sheet permission = updateSheet("允许安装更新");
        add(permission.body, text("更新已下载。请在系统设置中允许此应用安装，然后返回确认安装。", 14, MUTED, false), W, 8);
        add(permission.body, action("去系统设置", true, () -> {
            permission.dialog.dismiss();
            try {
                installAfterPermission = apk;
                startActivityForResult(updateInstaller.installationPermissionIntent(), UPDATE_INSTALL_PERMISSION);
            } catch (ActivityNotFoundException | SecurityException unavailable) {
                installAfterPermission = null;
                installUnavailable("无法打开安装权限设置");
            }
        }), 48, 12);
        add(permission.body, action("稍后", false, permission.dialog::dismiss), 48, 6);
        permission.show();
    }

    private void installUnavailable(String message) {
        if (availableAppUpdate != null) showAppUpdate(availableAppUpdate, message);
        else notice("安装更新", message);
    }

    private LinearLayout settingsPage(String title, LinearLayout content, Runnable back) {
        LinearLayout page = column();
        page.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 28) page.setAccessibilityPaneTitle(title);
        LinearLayout header = row();
        pad(header, 12, 0, 20, 0);
        header.setMinimumHeight(dp(56));
        header.addView(icon("back_left", "返回", 48, 22, Color.TRANSPARENT, FG, back));
        TextView heading = text(title, 22, FG, true);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        header.addView(heading, weight(0, W));
        add(page, header, W, 0);
        View divider = new View(this);
        divider.setBackgroundColor(LINE);
        add(page, divider, 1, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, weight(M, 0));
        pad(content, 20, 4, 20, 24);
        scroll.addView(content, new ScrollView.LayoutParams(M, W));
        return page;
    }

    private void showOpenSourceNotices() {
        if (appSettingsPage == null || openSourcePage != null) return;
        LinearLayout content = column();
        add(content, text("anon toolkyo 由 fswx2021 维护。项目代码采用 GNU GPL 第 3 版（仅限第 3 版）开源。",
                14, MUTED, false), W, 14);
        LinearLayout project = settingsGroup(content, "项目链接与许可");
        addSettingsRow(project, "项目网站", text("github.com/fswx2021/anon-toolkyo", 13, MUTED, false),
                () -> openBrowser(Uri.parse(OpenSourceNotices.PROJECT_SITE)));
        addSettingsRow(project, "版本发布与下载", null,
                () -> openBrowser(Uri.parse(OpenSourceNotices.PROJECT_SITE + "/releases")));
        addSettingsRow(project, "查看项目许可证", null, () -> showLicenseDocument(OpenSourceNotices.PROJECT_LICENSE));
        LinearLayout credits = column();
        credits.setBackground(surface(this, SURFACE, 12, 0));
        credits.setClipToOutline(true);
        add(content, credits, W, 18);
        addSettingsRow(credits, "致谢", text("第三方项目和许可证", 13, MUTED, false), this::showAcknowledgements);
        openSourcePage = settingsPage("开源声明", content, this::closeOpenSourceNotices);
        pushSettingsPage(appSettingsPage, openSourcePage);
    }

    private void showAcknowledgements() {
        if (openSourcePage == null || acknowledgementsPage != null) return;
        LinearLayout content = column();
        add(content, text("感谢以下项目的作者和贡献者。第三方组件适用各自的许可证。",
                14, MUTED, false), W, 14);
        for (OpenSourceNotices.Component component : OpenSourceNotices.COMPONENTS) {
            LinearLayout group = settingsGroup(content, component.name());
            addSettingsRow(group, "上游源码", text(component.credit(), 13, MUTED, false),
                    () -> openBrowser(Uri.parse(component.source())));
            for (OpenSourceNotices.Document document : component.documents()) {
                addSettingsRow(group, document.title(), null, () -> showLicenseDocument(document));
            }
        }
        LinearLayout framework = settingsGroup(content, "Flutter");
        addSettingsRow(framework, "Flutter 及其依赖的许可证", null, () -> openAnimeFeature("licenses", ""));
        LinearLayout artwork = settingsGroup(content, "数据与图片");
        addSettingsRow(artwork, "Bangumi 番组计划", text("联网番剧资料与封面", 13, MUTED, false),
                () -> openBrowser(Uri.parse("https://bangumi.tv/")));
        acknowledgementsPage = settingsPage("致谢", content, this::closeAcknowledgements);
        pushSettingsPage(openSourcePage, acknowledgementsPage);
    }

    private void showLicenseDocument(OpenSourceNotices.Document document) {
        if (openSourcePage == null || licensePage != null) return;
        String fullText;
        try (InputStreamReader input = new InputStreamReader(getAssets().open(document.asset()), StandardCharsets.UTF_8)) {
            StringBuilder contents = new StringBuilder();
            char[] buffer = new char[4096];
            int count;
            while ((count = input.read(buffer)) != -1) contents.append(buffer, 0, count);
            fullText = contents.toString();
        } catch (IOException unavailable) {
            toast("无法读取许可证");
            return;
        }
        LinearLayout content = column();
        TextView body = text(fullText, 14, FG, false);
        body.setTextIsSelectable(true);
        add(content, body, W, 14);
        openLicenseAsset = document.asset();
        licensePage = settingsPage(document.title(), content, this::closeLicenseDocument);
        pushSettingsPage(licenseParent(), licensePage);
    }

    private void pushSettingsPage(View parent, View page) {
        PageMotion.prepareExit(root, parent, false);
        parent.setVisibility(View.GONE);
        root.addView(page, new FrameLayout.LayoutParams(M, M, Gravity.CENTER));
        updateHomeChrome();
        PageMotion.enter(page, false);
    }

    private void closeLicenseDocument() {
        root.removeView(licensePage);
        licensePage = null;
        openLicenseAsset = null;
        LinearLayout parent = licenseParent();
        parent.setVisibility(View.VISIBLE);
        PageMotion.enter(parent, true);
    }

    private LinearLayout licenseParent() {
        return acknowledgementsPage != null ? acknowledgementsPage : openSourcePage;
    }

    private void closeAcknowledgements() {
        root.removeView(acknowledgementsPage);
        acknowledgementsPage = null;
        openSourcePage.setVisibility(View.VISIBLE);
        PageMotion.enter(openSourcePage, true);
    }

    private void closeOpenSourceNotices() {
        root.removeView(openSourcePage);
        openSourcePage = null;
        appSettingsPage.setVisibility(View.VISIBLE);
        PageMotion.enter(appSettingsPage, true);
    }

    private LinearLayout settingsGroup(LinearLayout parent, String title) {
        TextView heading = text(title, 13, MUTED, true);
        pad(heading, 4, 0, 4, 0);
        if (Build.VERSION.SDK_INT >= 28) heading.setAccessibilityHeading(true);
        add(parent, heading, W, 18);
        LinearLayout group = column();
        group.setBackground(surface(this, SURFACE, 12, 0));
        group.setClipToOutline(true);
        add(parent, group, W, 7);
        return group;
    }

    private void addSettingsChoice(LinearLayout group, String title, String key, String[] options, boolean refreshTheme) {
        int selected = Math.max(0, Math.min(options.length - 1, appSettings.getInt(key, 0)));
        TextView value = text(options[selected], 13, MUTED, false);
        addSettingsRow(group, title, value, () -> {
            int current = Math.max(0, Math.min(options.length - 1, appSettings.getInt(key, 0)));
            new android.app.AlertDialog.Builder(this).setTitle(title)
                    .setSingleChoiceItems(options, current, (dialog, which) -> {
                        dialog.dismiss();
                        if (which == current) return;
                        appSettings.edit().putInt(key, which).apply();
                        value.setText(options[which]);
                        if (refreshTheme) recreate();
                    }).setNegativeButton("取消", null).show();
        });
    }

    private void addSettingsRow(LinearLayout group, String title, TextView value, Runnable open) {
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(LINE);
            LinearLayout.LayoutParams rule = size(M, 1);
            rule.setMarginStart(dp(16));
            group.addView(divider, rule);
        }

        LinearLayout entry = row();
        entry.setMinimumHeight(dp(52));
        entry.setBackground(button(this, Color.TRANSPARENT, 0, 0));
        entry.setFocusable(true);
        entry.setOnClickListener(v -> open.run());
        pad(entry, 16, 10, 12, 10);
        LinearLayout labels = column();
        add(labels, text(title, 16, FG, false), W, 0);
        if (value != null) add(labels, value, W, 4);
        entry.addView(labels, weight(0, W));
        entry.addView(new Icon(this, "chevron", MUTED, 18), size(24, 24));
        add(group, entry, W, 0);
    }

    private void removeAppSettings() {
        if (appSettingsPage == null) return;
        if (licensePage != null) root.removeView(licensePage);
        if (acknowledgementsPage != null) root.removeView(acknowledgementsPage);
        if (openSourcePage != null) root.removeView(openSourcePage);
        licensePage = null;
        acknowledgementsPage = null;
        openSourcePage = null;
        openLicenseAsset = null;
        root.removeView(appSettingsPage);
        appSettingsPage = null;
    }

    private void showGuide() {
        appSettings.edit().putBoolean("guide_seen", true).apply();
        new android.app.AlertDialog.Builder(this).setTitle("快速上手")
                .setMessage("音乐支持导入设备文件、公开 B 站链接和媒体直链。漫画与小说可导入本地作品，或按来源搜索。动画支持导入 Kazumi 规则，查看放送日历、选集播放和续看。\n\n设置中的「下载与存储」可管理离线章节，「备份与恢复」用于换机。")
                .setPositiveButton("知道了", null).show();
    }

    private void showStorage() {
        Sheet sheet = sheet("下载与存储", "file");
        TextView usage = text("正在统计应用空间…", 14, MUTED, false);
        add(sheet.body, usage, W, 6);
        IMPORTS.execute(() -> {
            long[] bytes = readerCache.storageBytes();
            long music = folderBytes(new File(getFilesDir(), "library"));
            long books = folderBytes(new File(getFilesDir(), "reader-local"));
            main.post(() -> {
                if (isDestroyed()) return;
                usage.setText("音乐曲库 " + storageSize(music) + "\n本地作品 " + storageSize(books)
                        + "\n离线章节 " + storageSize(bytes[0]) + "\n图片与目录缓存 " + storageSize(bytes[1] + bytes[2]));
            });
        });
        for (int destination : new int[]{COMICS, NOVELS}) {
            add(sheet.body, action(SECTION_NAMES[destination] + "下载", false, () -> {
                sheet.dialog.dismiss();
                selectSection(destination);
                readers[destination].showDownloads();
                showHome();
            }), 48, 8);
        }
        add(sheet.body, action("动画下载", false, () -> {
            sheet.dialog.dismiss();
            openAnimeLink(Uri.parse("kazumi://downloads"));
        }), 48, 8);
        add(sheet.body, action("清理临时图片和目录缓存", false, () -> {
            sheet.dialog.dismiss();
            IMPORTS.execute(() -> {
                try { readerCache.clearTransient(); main.post(() -> toast("已清理临时缓存")); }
                catch (IOException failure) { main.post(() -> toast("临时缓存清理失败")); }
            });
        }), 48, 8);
        sheet.show();
    }

    private long folderBytes(File directory) {
        File[] entries = directory.listFiles();
        if (entries == null) return directory.isFile() ? directory.length() : 0L;
        long total = 0;
        for (File entry : entries) if (!java.nio.file.Files.isSymbolicLink(entry.toPath())) total += folderBytes(entry);
        return total;
    }

    private String storageSize(long bytes) {
        return android.text.format.Formatter.formatFileSize(this, bytes);
    }

    private void showBackups() {
        Sheet sheet = sheet("备份与恢复", "file");
        add(sheet.body, text("备份包含音乐、漫画、小说和旧版动画资料，完整备份还包含本地音乐、作品和离线章节。新版动画的收藏和观看记录可在动画设置中同步到 WebDAV。恢复时会用备份里的索引和设置替换已有记录。", 14, MUTED, false), W, 6);
        add(sheet.body, action("导出完整备份", true, () -> { sheet.dialog.dismiss(); openBackupPicker(true, true); }), 48, 12);
        add(sheet.body, action("仅导出资料与设置", false, () -> { sheet.dialog.dismiss(); openBackupPicker(true, false); }), 48, 8);
        if (AppBackup.hasPending(this)) {
            add(sheet.body, action("取消待恢复的备份", false, () -> {
                sheet.dialog.dismiss();
                IMPORTS.execute(() -> {
                    try { AppBackup.cancel(this); main.post(() -> toast("已取消恢复")); }
                    catch (IOException failure) { main.post(() -> toast("取消恢复失败")); }
                });
            }), 48, 8);
        } else {
            add(sheet.body, action("从备份恢复", false, () -> {
                sheet.dialog.dismiss();
                new android.app.AlertDialog.Builder(this).setTitle("从备份恢复")
                        .setMessage("恢复会替换备份中包含的曲库索引、书源和设置。选中文件后先验证备份，再重新打开应用应用恢复。")
                        .setPositiveButton("选择文件", (dialog, which) -> openBackupPicker(false, false))
                        .setNegativeButton("取消", null).show();
            }), 48, 8);
        }
        sheet.show();
    }

    private void openBackupPicker(boolean write, boolean content) {
        if (musicBusy() || backupBusy) { toast("请等当前任务完成"); return; }
        backupContent = content;
        Intent picker = new Intent(write ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(write ? "application/zip" : "*/*");
        if (write) {
            String filePrefix = getString(R.string.app_name).replace(' ', '-');
            picker.putExtra(Intent.EXTRA_TITLE, filePrefix + "-" + java.time.LocalDate.now() + ".zip");
        }
        try { startActivityForResult(picker, write ? BACKUP_SAVE : BACKUP_RESTORE); }
        catch (ActivityNotFoundException missing) { toast("无法打开系统文件选择器"); }
    }

    private void backupResult(int request, Uri file) {
        if (backupBusy) return;
        backupBusy = true;
        boolean content = backupContent;
        toast(request == BACKUP_SAVE ? "正在导出备份…" : "正在验证备份…");
        IMPORTS.execute(() -> {
            try {
                if (request == BACKUP_SAVE) AppBackup.export(this, file, content);
                else AppBackup.stage(this, file);
                main.post(() -> {
                    backupBusy = false;
                    if (isDestroyed()) return;
                    if (request == BACKUP_SAVE) toast("备份已导出");
                    else new android.app.AlertDialog.Builder(this).setTitle("备份验证完成")
                            .setMessage("重新打开应用后完成恢复。")
                            .setPositiveButton("退出应用", (dialog, which) -> {
                                if (service != null) service.pause();
                                stopService(new Intent(this, PlaybackService.class));
                                finishAndRemoveTask();
                                main.postDelayed(() -> android.os.Process.killProcess(android.os.Process.myPid()), 300);
                            }).setNegativeButton("稍后", null).show();
                });
            } catch (IOException | RuntimeException failure) {
                main.post(() -> { backupBusy = false; if (!isDestroyed()) notice("备份操作失败", failure.getMessage()); });
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (isAnimeDownloadNotification(intent)) {
            openAnimeLink(Uri.parse("kazumi://downloads"));
            return;
        }
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null
                && "kazumi".equalsIgnoreCase(intent.getData().getScheme())) {
            openAnimeLink(intent.getData());
            return;
        }
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            showPlayer(false);
            return;
        }
        if (!Intent.ACTION_SEND.equals(intent.getAction()) && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) return;
        LinkedHashSet<Uri> files = new LinkedHashSet<>();
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Object item = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (item instanceof Uri) files.add((Uri) item);
        } else {
            ArrayList<?> items = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (items != null) for (Object item : items) if (item instanceof Uri) files.add((Uri) item);
        }
        addClip(files, intent.getClipData());
        if (!files.isEmpty()) importFiles(new ArrayList<>(files), null, false);
        else {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && text.toString().trim().regionMatches(true, 0, "kazumi://", 0, 9)) {
                openAnimeLink(Uri.parse(text.toString().trim()));
            } else if (text != null && text.length() > 0) linkDialog(text.toString());
            else toast("没有收到可导入的文件或链接");
        }
    }

    private static boolean isAnimeDownloadNotification(Intent intent) {
        return Intent.ACTION_MAIN.equals(intent.getAction())
                && "/anon-anime-downloads".equals(intent.getStringExtra("route"));
    }

    private void openAnimeLink(Uri link) {
        if (inPlayer || appSettingsPage != null) showHome();
        selectSection(ANIME);
        anime.leaveLegacy();
        String authority = link.getHost();
        if ("downloads".equalsIgnoreCase(authority)) openAnimeFeature("downloads", "");
        else if ("search".equalsIgnoreCase(authority)) openAnimeFeature("search", link.getQueryParameter("query"));
        else if ("bangumi".equalsIgnoreCase(authority)) {
            String id = link.getLastPathSegment();
            if (id == null) id = link.getQueryParameter("id");
            if (id != null && id.matches("[1-9][0-9]{0,9}")) openAnimeFeature("subject", id);
            else toast("动画链接缺少有效的条目编号");
        } else if ("import".equalsIgnoreCase(authority)) {
            String rule = link.getQueryParameter("rule");
            if (rule == null || rule.isBlank()) toast("动画链接缺少规则");
            else openAnimeFeature("import", rule);
        } else openAnimeFeature("import", link.toString());
    }

    void openAnimeFeature(String feature, String value) {
        openAnimeFeature(feature, value, "");
    }

    void openAnimeFeature(String feature, String value, String subjectPreview) {
        Intent target = new Intent().setClassName(this, "com.shengjian.player.AnimeFeatureActivity")
                .putExtra("feature", feature).putExtra("value", value == null ? "" : value)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!subjectPreview.isEmpty()) target.putExtra("subjectPreview", subjectPreview);
        try {
            startActivity(target);
        } catch (ActivityNotFoundException missingModule) {
            notice("无法打开动画功能", "当前安装包缺少这个页面。请安装完整版本后重试。");
        }
    }

    void pauseMusicForAnime() {
        if (service != null) service.pause();
    }

    private void addClip(Set<Uri> files, ClipData clip) {
        if (clip == null) return;
        for (int i = 0; i < clip.getItemCount(); i++) {
            Uri uri = clip.getItemAt(i).getUri();
            if (uri != null) files.add(uri);
        }
    }

    private void importFiles(List<Uri> files, Track target, boolean persistable) {
        if (files.isEmpty()) return;
        if (musicBusy()) { toast("正在导入，请稍后再试"); return; }
        files.removeIf(uri -> !"content".equalsIgnoreCase(uri.getScheme()));
        if (files.isEmpty()) { toast("请通过系统文件选择器导入"); return; }
        List<Uri> grants = new ArrayList<>();
        if (persistable) for (Uri uri : files) {
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                grants.add(uri);
            } catch (SecurityException ignored) { }
        }
        if (target == null) {
            showHome();
            selectSection(MUSIC);
        }
        MusicImportTask task = beginImport(target == null ? "正在导入文件" : "正在读取歌词");
        IMPORTS.execute(() -> {
            try {
                Library.ImportResult result = library.importFiles(files, target, task);
                main.post(() -> {
                    if (isDestroyed()) return;
                    refresh();
                    String summary = result.tracks.size() + " 首已导入";
                    if (result.lyrics > 0) summary += " · " + result.lyrics + " 份歌词";
                    if (result.duplicates > 0) summary += " · " + result.duplicates + " 首已在曲库";
                    if (task.cancelled()) toast("导入已取消 · " + summary);
                    else if (!result.errors.isEmpty()) {
                        notice(result.tracks.isEmpty() && result.lyrics == 0 ? "导入遇到问题" : summary,
                                TextUtils.join("\n", result.errors));
                    } else toast(target != null && result.lyrics > 0 ? "歌词已添加" : summary);
                });
            } catch (Exception | LinkageError error) {
                importFailure(task, "文件导入失败", error);
            } finally {
                for (Uri uri : grants) {
                    try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                    catch (SecurityException ignored) { }
                }
                endImport(task);
            }
        });
    }

    private void importLinks(String shared) {
        if (musicBusy()) { toast("正在导入，请稍后再试"); return; }
        showHome();
        selectSection(MUSIC);
        MusicImportTask task = beginImport("正在解析链接");
        IMPORTS.execute(() -> {
            task.enter();
            try {
                List<String> links = LinkResolver.importInputs(shared);
                if (links.size() == 1) {
                    java.net.URI address = LinkResolver.importAddress(links.get(0));
                    LinkResolver.VideoCollection collection = LinkResolver.videoCollection(address);
                    task.check();
                    if (collection != null) {
                        showImportSelection(task, () -> chooseCollectionVideos(collection));
                        return;
                    }
                    LinkResolver.VideoParts video = LinkResolver.videoParts(address);
                    task.check();
                    if (video != null && video.entries.size() > 1 && !isDestroyed()) {
                        showImportSelection(task, () -> chooseVideoParts(video));
                        return;
                    }
                    LinkImport only = video == null ? () -> LinkResolver.resolve(address) : () -> video.resolve(video.selected);
                    runLinkBatch(task, Collections.singletonList(only));
                    return;
                }
                List<LinkImport> requests = new ArrayList<>();
                for (String link : links) requests.add(() -> LinkResolver.resolve(link));
                runLinkBatch(task, requests);
            } catch (Exception | LinkageError error) {
                importFailure(task, "链接导入失败", error);
            } finally {
                task.exit();
                endImport(task);
            }
        });
    }

    private interface LinkImport { LinkResolver.Result resolve() throws Exception; }

    private void showImportSelection(MusicImportTask task, Runnable show) {
        main.post(() -> {
            if (activeImport == task) activeImport = null;
            if (isDestroyed()) return;
            syncImportProgress();
            if (task.cancelled()) toast("导入已取消");
            else show.run();
        });
    }

    private void runLinkBatch(MusicImportTask task, List<LinkImport> requests) {
        int added = 0, existing = 0;
        List<String> errors = new ArrayList<>();
        for (int i = 0; i < requests.size() && !task.cancelled(); i++) {
            try {
                task.begin("正在解析链接", i + 1, requests.size());
                LinkResolver.Result link = requests.get(i).resolve();
                task.check();
                if (library.findBySourceUrl(link.sourceUrl) != null) {
                    existing++;
                    continue;
                }
                task.begin("正在导入链接", i + 1, requests.size());
                library.addLink(link, task);
                added++;
            } catch (Exception | LinkageError error) {
                if (!task.cancelled()) errors.add("第 " + (i + 1) + " 项 · " + importReason(error));
            }
        }
        String summary = added + " 首已导入" + (existing > 0 ? " · " + existing + " 首已在曲库" : "");
        main.post(() -> {
            if (isDestroyed()) return;
            if (!task.cancelled()) {
                playlistId = "";
                filter = ALL_TRACKS;
            }
            refresh();
            if (task.cancelled()) toast("导入已取消 · " + summary);
            else if (!errors.isEmpty()) notice("导入结果 · " + summary, TextUtils.join("\n", errors));
            else toast(summary);
        });
    }

    private void chooseVideoParts(LinkResolver.VideoParts video) {
        List<String> titles = new ArrayList<>();
        List<LinkImport> options = new ArrayList<>();
        for (LinkResolver.VideoPart part : video.entries) {
            titles.add("P" + part.number() + " · " + part.name());
            options.add(() -> video.resolve(part.number()));
        }
        int preselected = -1;
        for (int i = 0; i < video.entries.size(); i++) if (video.entries.get(i).number() == video.selected) preselected = i;
        chooseImportItems(video.title, titles, options, preselected, "请选择要导入的分 P");
    }

    private void chooseCollectionVideos(LinkResolver.VideoCollection collection) {
        String title = collection.limited() ? collection.title() + "（显示前 300 项）" : collection.title();
        List<String> titles = new ArrayList<>();
        List<LinkImport> options = new ArrayList<>();
        for (LinkResolver.CollectionVideo video : collection.entries()) {
            titles.add(video.title());
            options.add(() -> LinkResolver.resolve(video.videoUrl()));
        }
        chooseImportItems(title, titles, options, -1, "请选择要导入的视频");
    }

    private void chooseImportItems(String title, List<String> titles, List<LinkImport> options,
                                   int preselected, String empty) {
        boolean[] checked = new boolean[titles.size()];
        if (preselected >= 0) checked[preselected] = true;
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(title)
                .setMultiChoiceItems(titles.toArray(new String[0]), checked, (view, index, selected) -> checked[index] = selected)
                .setNegativeButton("取消", null)
                .setNeutralButton("全选", null)
                .setPositiveButton("导入所选", null).create();
        dialog.show();
        dialog.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            Arrays.fill(checked, true);
            for (int i = 0; i < checked.length; i++) dialog.getListView().setItemChecked(i, true);
        });
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (musicBusy()) return;
            List<LinkImport> requests = new ArrayList<>();
            for (int i = 0; i < checked.length; i++) {
                if (checked[i]) requests.add(options.get(i));
            }
            if (requests.isEmpty()) { toast(empty); return; }
            dialog.dismiss();
            MusicImportTask task = beginImport("正在导入所选内容");
            IMPORTS.execute(() -> {
                task.enter();
                try { runLinkBatch(task, requests); }
                finally {
                    task.exit();
                    endImport(task);
                }
            });
        });
    }

    private boolean musicBusy() { return working || activeImport != null; }

    private MusicImportTask beginImport(String label) {
        MusicImportTask task = new MusicImportTask(label);
        activeImport = task;
        syncImportProgress();
        return task;
    }

    private void endImport(MusicImportTask task) {
        main.post(() -> {
            if (activeImport == task) activeImport = null;
            if (!isDestroyed()) syncImportProgress();
        });
    }

    private void importFailure(MusicImportTask task, String heading, Throwable error) {
        if (!task.cancelled()) {
            failure(heading, error);
            return;
        }
        main.post(() -> {
            if (!isDestroyed()) {
                refresh();
                toast("导入已取消");
            }
        });
    }

    private void syncImportProgress() {
        if (busy == null) return;
        MusicImportTask task = activeImport;
        MusicDownloadProgress download = musicDownloadProgress;
        if (download != null && download.task != task) {
            download.sheet.dialog.dismiss();
            musicDownloadProgress = null;
            download = null;
        }
        busy.setVisibility(task != null || working ? View.VISIBLE : View.GONE);
        importCancel.setVisibility(task == null ? View.GONE : View.VISIBLE);
        importCancel.setEnabled(task != null && !task.cancelled());
        if (task == null) return;
        MusicImportTask.Progress progress = task.progress();
        String label = task.cancelled() ? "正在取消导入…" : progress.label();
        if (!task.cancelled() && progress.totalItems() > 1) {
            label += " " + progress.item() + " / " + progress.totalItems();
        }
        if (!task.cancelled() && progress.bytes() > 0) {
            label += " · " + android.text.format.Formatter.formatShortFileSize(this, progress.bytes());
        }
        if (!label.contentEquals(busyText.getText())) busyText.setText(label);
        updateImportProgress(importProgress, progress, task.cancelled());
        if (download != null) download.update(progress, label);
    }

    private void updateImportProgress(ProgressBar bar, MusicImportTask.Progress progress, boolean cancelled) {
        long total = progress.totalBytes();
        boolean determinate = !cancelled && total > 0;
        if (bar.isIndeterminate() == determinate) bar.setIndeterminate(!determinate);
        if (determinate) {
            bar.setMax(1000);
            bar.setProgress((int) Math.min(1000, progress.bytes() / (double) total * 1000));
        }
    }

    private ProgressBar musicImportProgressBar() {
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setIndeterminateTintList(ColorStateList.valueOf(BERRY));
        bar.setProgressTintList(ColorStateList.valueOf(BERRY));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(BLUSH));
        return bar;
    }

    private void failure(String heading, Throwable error) {
        main.post(() -> {
            if (isDestroyed()) return;
            setBusy(false, "");
            notice(heading, importReason(error));
        });
    }

    private String importReason(Throwable error) {
        if (error instanceof java.net.SocketTimeoutException) return "网络连接超时，请稍后重试。";
        if (error instanceof LinkageError) return "导入组件无法加载，请更新应用后重试。";
        String reason = error.getMessage();
        return reason == null || reason.isEmpty() ? "请检查文件或链接后重试。" : reason;
    }

    private void setBusy(boolean active, String message) {
        working = active;
        busyText.setText(message);
        syncImportProgress();
    }

    private void linkDialog(String shared) {
        if (musicBusy()) { toast("正在导入，请稍后再试"); return; }
        Sheet sheet = sheet("导入链接", "link");
        TextView hint = text("支持 B 站公开视频、合集、音视频直链和 HLS。选曲导入时请单独粘贴合集链接。", 14, MUTED, false);
        add(sheet.body, hint, W, 8);
        EditText address = field("粘贴链接或分享文本");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        address.setGravity(Gravity.TOP | Gravity.START);
        address.setText(shared);
        add(sheet.body, address, 96, 12);
        LinearLayout buttons = row();
        TextView paste = action("从剪贴板粘贴", false, () -> {
            ClipboardManager clipboard = getSystemService(ClipboardManager.class);
            ClipData data = clipboard.getPrimaryClip();
            if (data != null && data.getItemCount() > 0) {
                address.setText(data.getItemAt(0).coerceToText(this));
                address.setSelection(address.length());
            } else toast("剪贴板里没有链接");
        });
        LinearLayout.LayoutParams left = weight(0, 48);
        left.rightMargin = dp(8);
        buttons.addView(paste, left);
        buttons.addView(action("开始导入", true, () -> {
            String value = address.getText().toString().trim();
            if (value.isEmpty()) { address.setError("请输入链接"); return; }
            sheet.dialog.dismiss();
            importLinks(value);
        }), weight(0, 48));
        add(sheet.body, buttons, 48, 12);
        sheet.show();
    }

    private void trackDialog(Track track) {
        Sheet sheet = sheet(track.title, "disc");
        TextView details = text(detail(track) + (track.durationMs > 0 ? " · " + stamp(track.durationMs) : ""),
                13, MUTED, false);
        add(sheet.body, details, W, 6);
        boolean favorite = collections.isFavorite(track.id);
        add(sheet.body, action(favorite ? "取消收藏" : "收藏曲目", true, () -> {
            sheet.dialog.dismiss();
            boolean selected = collections.toggleFavorite(track.id);
            saveCollections();
            filter();
            toast(selected ? "已收藏" : "已取消收藏");
        }), 46, 12);
        add(sheet.body, action("添加到歌单", false, () -> {
            sheet.dialog.dismiss();
            addTrackToPlaylist(track);
        }), 46, 7);
        if (MusicOnlineTrack.isReference(track.url)) {
            add(sheet.body, action("下载到本地", false, () -> {
                sheet.dialog.dismiss();
                downloadMusicOnline(track);
            }), 46, 7);
        }
        MusicCollections.Playlist active = collections.playlist(playlistId);
        if (active == null && filter == RECENT_TRACKS
                && MusicCollections.readIds(recentSnapshot).contains(track.id)) {
            add(sheet.body, action("移出历史记录", false, () -> {
                sheet.dialog.dismiss();
                removeMusicHistoryItem(track.id);
            }), 46, 7);
        }
        if (active != null && active.trackIds().contains(track.id)) {
            add(sheet.body, action("从当前歌单移除", false, () -> {
                sheet.dialog.dismiss();
                collections.remove(active, track.id);
                saveCollections();
                filter();
            }), 46, 7);
        }
        if (service != null && current() != track && track.available()) {
            add(sheet.body, action("下一首播放", false, () -> {
                sheet.dialog.dismiss();
                if (service != null) service.playNext(playbackTrack(track));
                sync();
            }), 46, 7);
        }
        add(sheet.body, action("修改曲目信息", false, () -> {
            sheet.dialog.dismiss();
            editTrackMetadata(track);
        }), 46, 7);
        add(sheet.body, action(track.hasLyrics() ? "更换歌词文件" : "添加歌词文件", true, () -> {
            sheet.dialog.dismiss();
            pick(track);
        }), 48, 14);
        if (!track.sourceUrl.isEmpty()) {
            Uri origin = musicOrigin(track);
            if (origin != null) add(sheet.body, action("在来源网站打开", false, () -> {
                sheet.dialog.dismiss();
                openBrowser(origin);
            }), 48, 8);
            add(sheet.body, action("复制原始链接", false, () -> {
                getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("链接", track.sourceUrl));
                sheet.dialog.dismiss();
            }), 48, 8);
        }
        add(sheet.body, action("从曲库移除", false, () -> {
            sheet.dialog.dismiss();
            confirmRemove(track);
        }), 48, 8);
        sheet.show();
    }

    private static Uri musicOrigin(Track track) {
        Uri origin = Uri.parse(track.sourceUrl);
        return MusicOnlineTrack.isReference(track.url) && "https".equalsIgnoreCase(origin.getScheme())
                && origin.getHost() != null && origin.getUserInfo() == null ? origin : null;
    }

    private void openBrowser(Uri origin) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, origin).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException unavailable) {
            toast("没有可用的浏览器");
        }
    }

    private void editTrackMetadata(Track track) {
        Sheet sheet = sheet("修改曲目信息", "disc");
        EditText name = field("曲名");
        name.setSingleLine(true);
        name.setText(track.title);
        add(sheet.body, name, 48, 8);
        EditText artist = field("作者 / 歌手");
        artist.setSingleLine(true);
        artist.setText(track.artist);
        add(sheet.body, artist, 48, 7);
        add(sheet.body, action("保存", true, () -> {
            if (musicBusy()) { toast("曲库正在更新，请稍后再试"); return; }
            String updatedName = name.getText().toString().trim();
            String updatedArtist = artist.getText().toString().trim();
            if (updatedName.isEmpty()) { name.setError("请输入曲名"); return; }
            sheet.dialog.dismiss();
            setBusy(true, "正在更新曲目信息…");
            IMPORTS.execute(() -> {
                try {
                    library.editMetadata(track, updatedName, updatedArtist);
                    main.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        setBusy(false, "");
                        refresh();
                        toast("曲目信息已更新");
                    });
                } catch (IOException error) {
                    failure("曲目信息更新失败", error);
                }
            });
        }), 46, 9);
        sheet.show();
    }

    private void saveCollections() {
        musicSettings.edit().putString(MusicCollections.STATE, collections.serialize()).apply();
    }

    private void playlistsDialog() {
        Sheet sheet = sheet("歌单", "disc");
        add(sheet.body, action("新建歌单", true, () -> {
            sheet.dialog.dismiss();
            editPlaylistName(null, null);
        }), 46, 10);
        MusicCollections.Playlist active = collections.playlist(playlistId);
        if (active != null) {
            add(sheet.body, action("管理「" + active.name + "」", false, () -> {
                sheet.dialog.dismiss();
                managePlaylist(active);
            }), 46, 7);
        }
        for (MusicCollections.Playlist playlist : collections.playlists()) {
            TextView entry = action(playlist.name + "  ·  " + tracksForIds(playlist.trackIds()).size() + " 首",
                    false, () -> {
                        sheet.dialog.dismiss();
                        playlistId = playlist.id;
                        filter();
                        list.setSelection(0);
                    });
            oneLine(entry);
            add(sheet.body, entry, 46, 7);
        }
        sheet.show();
    }

    private void managePlaylist(MusicCollections.Playlist playlist) {
        Sheet sheet = sheet(playlist.name, "disc");
        add(sheet.body, action("选曲添加", true, () -> {
            sheet.dialog.dismiss();
            choosePlaylistTracks(playlist);
        }), 46, 10);
        add(sheet.body, action("重命名", false, () -> {
            sheet.dialog.dismiss();
            editPlaylistName(playlist, null);
        }), 46, 7);
        add(sheet.body, action("删除歌单", false, () -> {
            sheet.dialog.dismiss();
            Sheet confirm = sheet("删除歌单？", "disc");
            add(confirm.body, text("曲目仍保留在曲库中。", 14, MUTED, false), W, 8);
            add(confirm.body, action("删除歌单", true, () -> {
                collections.delete(playlist);
                if (playlistId.equals(playlist.id)) playlistId = "";
                saveCollections();
                filter();
                confirm.dialog.dismiss();
            }), 46, 10);
            add(confirm.body, action("取消", false, confirm.dialog::dismiss), 46, 7);
            confirm.show();
        }), 46, 7);
        sheet.show();
    }

    private void editPlaylistName(MusicCollections.Playlist playlist, Track addTrack) {
        Sheet sheet = sheet(playlist == null ? "新建歌单" : "重命名歌单", "disc");
        EditText name = field("歌单名称");
        name.setSingleLine(true);
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        if (playlist != null) name.setText(playlist.name);
        add(sheet.body, name, 50, 9);
        add(sheet.body, action("保存", true, () -> {
            String value = name.getText().toString().trim();
            if (value.isEmpty()) { name.setError("请输入歌单名称"); return; }
            MusicCollections.Playlist chosen = playlist;
            if (chosen == null) chosen = collections.create(value);
            else collections.rename(chosen, value);
            if (addTrack != null) collections.add(chosen, addTrack.id);
            playlistId = chosen.id;
            saveCollections();
            filter();
            sheet.dialog.dismiss();
            if (playlist == null && addTrack == null) managePlaylist(chosen);
        }), 46, 10);
        sheet.show();
    }

    private void addTrackToPlaylist(Track track) {
        if (collections.playlists().isEmpty()) {
            editPlaylistName(null, track);
            return;
        }
        Sheet sheet = sheet("添加到歌单", "disc");
        add(sheet.body, action("新建歌单并添加", true, () -> {
            sheet.dialog.dismiss();
            editPlaylistName(null, track);
        }), 46, 10);
        for (MusicCollections.Playlist playlist : collections.playlists()) {
            boolean exists = playlist.trackIds().contains(track.id);
            TextView entry = action(exists ? playlist.name + " · 已添加" : playlist.name, false, () -> {
                if (!collections.add(playlist, track.id)) return;
                saveCollections();
                filter();
                sheet.dialog.dismiss();
                toast("已添加到「" + playlist.name + "」");
            });
            oneLine(entry);
            entry.setEnabled(!exists);
            add(sheet.body, entry, 46, 7);
        }
        sheet.show();
    }

    private void choosePlaylistTracks(MusicCollections.Playlist playlist) {
        List<Track> candidates = new ArrayList<>();
        Set<String> members = new HashSet<>(playlist.trackIds());
        for (Track track : tracks) if (!members.contains(track.id)) candidates.add(track);
        if (candidates.isEmpty()) {
            toast("曲库没有可以添加的曲目");
            return;
        }
        String[] names = new String[candidates.size()];
        for (int i = 0; i < names.length; i++) {
            Track track = candidates.get(i);
            names[i] = track.artist.isEmpty() ? track.title : track.title + " · " + track.artist;
        }
        boolean[] selected = new boolean[names.length];
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle("添加曲目")
                .setMultiChoiceItems(names, selected, (view, index, checked) -> selected[index] = checked)
                .setNegativeButton("取消", null).setPositiveButton("添加", (view, action) -> {
                    for (int i = 0; i < selected.length; i++) {
                        if (selected[i]) collections.add(playlist, candidates.get(i).id);
                    }
                    saveCollections();
                    filter();
                }).create();
        dialog.show();
    }

    private void confirmRemove(Track track) {
        if (musicBusy()) { toast("正在导入，请稍后再试"); return; }
        Sheet sheet = sheet("移除这首曲目？", "disc");
        add(sheet.body, text("将从曲库移除「" + track.title + "」及其歌词。", 14, MUTED, false), W, 8);
        add(sheet.body, action("移除", true, () -> {
            sheet.dialog.dismiss();
            setBusy(true, "正在移除曲目…");
            IMPORTS.execute(() -> {
                try {
                    library.remove(track);
                    main.post(() -> {
                        if (isDestroyed()) {
                            return;
                        }
                        setBusy(false, "");
                        if (track.id.equals(settings.getString("last", ""))) {
                            settings.edit().remove("last").remove("time").apply();
                        }
                        refresh();
                        toast("已从曲库移除");
                    });
                } catch (Exception error) { failure("移除失败", error); }
            });
        }), 48, 14);
        add(sheet.body, action("取消", false, sheet.dialog::dismiss), 48, 8);
        sheet.show();
    }

    private void queueDialog() { queueDialog(shownId); }

    private void queueDialog(String focusId) {
        if (isFinishing() || isDestroyed()) return;
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setBackground(surface(this, BG, 14, LINE));
        pad(panel, 16, 6, 16, 10);
        LinearLayout heading = row();
        TextView caption = text("播放队列", 20, FG, true);
        if (Build.VERSION.SDK_INT >= 28) caption.setAccessibilityHeading(true);
        heading.addView(caption, weight(0, W));
        heading.addView(action("完成", false, dialog::dismiss), size(54, 48));
        add(panel, heading, 52, 0);
        List<PlaybackService.Track> queue = service == null ? Collections.emptyList()
                : new ArrayList<>(service.playbackOrder());
        if (queue.isEmpty()) {
            add(panel, text("从曲库选择歌曲即可开始播放", 14, MUTED, false), 48, 4);
        } else {
            ListView entries = new ListView(this);
            openQueueList = entries;
            entries.setDividerHeight(0);
            entries.setAdapter(new BaseAdapter() {
                public int getCount() { return queue.size(); }
                public PlaybackService.Track getItem(int i) { return queue.get(i); }
                public long getItemId(int i) { return i; }
                public View getView(int i, View recycled, ViewGroup parent) {
                    PlaybackService.Track track = getItem(i);
                    boolean selected = track.id.equals(shownId);
                    LinearLayout entry = row();
                    entry.setLayoutParams(new AbsListView.LayoutParams(M, W));
                    entry.setMinimumHeight(dp(54));
                    entry.setBackground(button(MainActivity.this, selected ? BLUSH : Color.TRANSPARENT, 8, 0));
                    LinearLayout label = column();
                    pad(label, 10, 6, 6, 6);
                    TextView name = text(track.title, 15, selected ? BERRY : FG, true);
                    oneLine(name);
                    label.addView(name);
                    String artist = track.artist.isEmpty() ? track.source : track.artist;
                    String subtitle = selected && !artist.isEmpty() ? "当前曲目 · " + artist : selected ? "当前曲目" : artist;
                    if (!subtitle.isEmpty()) {
                        TextView secondary = text(subtitle, 12, MUTED, false);
                        oneLine(secondary);
                        label.addView(secondary);
                    }
                    label.setMinimumHeight(dp(48));
                    label.setGravity(Gravity.CENTER_VERTICAL);
                    label.setFocusable(true);
                    label.setOnClickListener(v -> { playQueued(track); dialog.dismiss(); });
                    entry.addView(label, weight(0, W));
                    entry.addView(icon("more", track.title + "，队列选项", 48, 20, Color.TRANSPARENT, BERRY, () -> {
                        dialog.dismiss();
                        queueTrackDialog(track);
                    }));
                    return entry;
                }
            });
            int listHeight = Math.min(dp(480), Math.max(dp(54), root.getHeight() * 2 / 3 - dp(68)));
            panel.addView(entries, new LinearLayout.LayoutParams(M, Math.min(listHeight, queue.size() * dp(60))));
            int selected = 0;
            for (int i = 0; i < queue.size(); i++) {
                String id = queue.get(i).id;
                if (id.equals(focusId)) { selected = i; break; }
                if (id.equals(shownId)) selected = i;
            }
            entries.setSelectionFromTop(selected, 0);
            dialog.setOnDismissListener(ignored -> { if (openQueueList == entries) openQueueList = null; });
        }
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        window.setDimAmount(.36f);
        window.setLayout(Math.min(dp(640), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()), W);
        PageMotion.dialog(window);
        dialog.show();
    }

    private void queueTrackDialog(PlaybackService.Track track) {
        if (service == null) return;
        List<PlaybackService.Track> ordered = service.playbackOrder();
        int position = -1;
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).id.equals(track.id)) position = i;
        if (position < 0) { queueDialog(); return; }
        Sheet sheet = sheet(track.title, "queue");
        if (position > 0) queueAction(sheet, track.id, "向上移动", () -> service.moveQueued(track.id, -1));
        if (position + 1 < ordered.size()) queueAction(sheet, track.id, "向下移动", () -> service.moveQueued(track.id, 1));
        PlaybackService.Track current = service.currentTrack();
        if (current == null || !current.id.equals(track.id)) {
            queueAction(sheet, track.id, "下一首播放", () -> service.playNext(track));
        }
        queueAction(sheet, track.id, "从队列移除", () -> service.removeQueued(track.id));
        add(sheet.body, action("返回队列", false, () -> {
            sheet.dialog.dismiss();
            queueDialog(track.id);
        }), 46, 8);
        sheet.show();
    }

    private void queueAction(Sheet sheet, String focusId, String title, Runnable change) {
        add(sheet.body, action(title, false, () -> {
            sheet.dialog.dismiss();
            if (service != null) change.run();
            sync();
            queueDialog(focusId);
        }), 46, 8);
    }

    private void notice(String heading, String message) {
        Sheet sheet = sheet(heading, "note");
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        TextView copy = text(message, 14, MUTED, false);
        pad(copy, 0, 6, 0, 6);
        scroll.addView(copy, new ScrollView.LayoutParams(M, W));
        if (message.length() > 180) add(sheet.body, scroll, 200, 7);
        else add(sheet.body, scroll, W, 7);
        add(sheet.body, action("知道了", true, sheet.dialog::dismiss), 48, 12);
        sheet.show();
    }

    private Sheet sheet(String heading, String symbol) { return new Sheet(heading, symbol); }

    private final class UpdateDownload {
        final AppUpdates.Release release;
        final AppUpdateInstaller.Transfer transfer = new AppUpdateInstaller.Transfer();
        final Sheet sheet;
        final ProgressBar bar;
        final TextView status;
        boolean cancelled;
        private long lastProgressAt;

        UpdateDownload(AppUpdates.Release release) {
            this.release = release;
            sheet = updateSheet("下载 " + release.versionName());
            sheet.dialog.setCancelable(false);
            status = text("正在准备下载…", 14, MUTED, false);
            add(sheet.body, status, W, 8);
            bar = musicImportProgressBar();
            bar.setIndeterminate(true);
            bar.setMax(1000);
            bar.setContentDescription("更新下载进度");
            add(sheet.body, bar, 5, 8);
            add(sheet.body, action("取消下载", false, () -> {
                if (cancelled) return;
                cancelled = true;
                status.setText("正在取消下载…");
                transfer.cancel();
            }), 48, 14);
        }

        void progress(long bytes, long total) {
            long now = SystemClock.elapsedRealtime();
            if (lastProgressAt != 0 && now - lastProgressAt < 120 && bytes != total) return;
            lastProgressAt = now;
            main.post(() -> {
                if (isDestroyed() || updateDownload != this || cancelled) return;
                String received = Formatter.formatShortFileSize(MainActivity.this, bytes);
                if (total <= 0) {
                    bar.setIndeterminate(true);
                    status.setText("已下载 " + received);
                    return;
                }
                bar.setIndeterminate(false);
                bar.setProgress((int) Math.min(1000, bytes * 1000.0 / total));
                status.setText(received + " / " + Formatter.formatShortFileSize(MainActivity.this, total));
            });
        }
    }

    private final class MusicDownloadProgress {
        final MusicImportTask task;
        final Sheet sheet;
        final TextView message, cancel;
        final ProgressBar bar;

        MusicDownloadProgress(MusicImportTask task, String title) {
            this.task = task;
            sheet = sheet("下载到本地", "download");
            TextView song = text(title, 15, FG, true);
            song.setMaxLines(2);
            song.setEllipsize(TextUtils.TruncateAt.END);
            add(sheet.body, song, W, 6);
            message = text("", 13, MUTED, false);
            add(sheet.body, message, W, 6);
            bar = musicImportProgressBar();
            add(sheet.body, bar, 5, 8);
            LinearLayout buttons = row();
            cancel = action("取消下载", false, () -> {
                task.cancel();
                syncImportProgress();
            });
            buttons.addView(cancel, weight(0, M));
            LinearLayout.LayoutParams continueSpace = weight(0, M);
            continueSpace.setMarginStart(dp(8));
            buttons.addView(action("收起", false, sheet.dialog::dismiss), continueSpace);
            add(sheet.body, buttons, 48, 14);
        }

        void update(MusicImportTask.Progress progress, String label) {
            if (task.cancelled()) label = "正在取消下载…";
            if (!label.contentEquals(message.getText())) message.setText(label);
            cancel.setEnabled(!task.cancelled());
            updateImportProgress(bar, progress, task.cancelled());
        }
    }

    private final class Sheet {
        final Dialog dialog;
        final LinearLayout body;

        Sheet(String heading, String symbol) {
            dialog = new Dialog(MainActivity.this);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            ScrollView scroll = new ScrollView(MainActivity.this);
            scroll.setVerticalScrollBarEnabled(false);
            scroll.setBackground(surface(MainActivity.this, BG, 14, LINE));
            scroll.setClipToOutline(true);
            body = column();
            body.setFocusableInTouchMode(true);
            pad(body, 17, 16, 17, 17);
            LinearLayout header = row();
            pad(header, 0, 0, 0, 5);
            header.addView(icon(symbol, null, 37, 20, BLUSH, BERRY, null));
            TextView label = text(heading, 20, FG, true);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
            LinearLayout.LayoutParams headingSpace = weight(0, W);
            headingSpace.setMarginStart(dp(9));
            header.addView(label, headingSpace);
            body.addView(header, size(M, W));
            scroll.addView(body, new ScrollView.LayoutParams(M, W));
            dialog.setContentView(scroll);
        }

        void show() {
            if (isFinishing() || isDestroyed()) return;
            PageMotion.dialog(dialog.getWindow());
            dialog.show();
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.38f);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            int safeWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
            if (safeWidth <= 0) safeWidth = getResources().getDisplayMetrics().widthPixels;
            window.setLayout(Math.max(1, Math.min(dp(438), safeWidth - dp(24))), W);
        }
    }

    private String detail(Track track) {
        List<String> values = new ArrayList<>();
        if (!track.artist.isEmpty()) values.add(track.artist);
        if (!track.available()) values.add("缺少文件");
        if (!track.source.isEmpty()) values.add(track.source);
        if (!track.format.isEmpty()) values.add(track.format.toUpperCase(Locale.ROOT));
        return TextUtils.join(" · ", values);
    }

    private void artwork(ImageView view, String url) {
        view.setTag(url);
        Bitmap cached = url.isEmpty() ? null : artworkCache.get(url);
        if (cached != null) {
            applyArtwork(view, cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pad(view, 2, 2, 2, 2);
        view.setImageResource(view == cover ? R.drawable.anon_mascot : R.drawable.ic_track_placeholder);
        if (url.isEmpty() || images.isShutdown()) return;
        images.execute(() -> {
            Uri uri = Uri.parse(url);
            Bitmap result = null;
            HttpURLConnection request = null;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 2;
            try {
                if ("file".equals(uri.getScheme())) {
                    result = BitmapFactory.decodeFile(uri.getPath(), options);
                } else if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) {
                    request = (HttpURLConnection) new URL(url).openConnection();
                    request.setConnectTimeout(6000);
                    request.setReadTimeout(7000);
                    try (InputStream input = request.getInputStream()) {
                        result = BitmapFactory.decodeStream(input, null, options);
                    }
                }
            } catch (Exception ignored) {
                // The default cover stays visible when artwork is unavailable.
            } finally {
                if (request != null) request.disconnect();
            }
            Bitmap image = result;
            if (image != null) main.post(() -> {
                if (isDestroyed()) return;
                artworkCache.put(url, image);
                if (url.equals(view.getTag())) applyArtwork(view, image);
            });
        });
    }

    private void applyArtwork(ImageView view, Bitmap image) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        pad(view, 0, 0, 0, 0);
        view.setImageBitmap(image);
    }

    private ImageView art(int radius) {
        ImageView image = new ImageView(this);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        image.setBackground(surface(this, SURFACE, radius, 0));
        image.setClipToOutline(true);
        return image;
    }

    private FrameLayout icon(String symbol, String description, int size, int glyph, int bg, int fg, Runnable click) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(size(size, size));
        frame.addView(new Icon(this, symbol, fg, glyph), new FrameLayout.LayoutParams(M, M));
        if (click == null) frame.setBackground(surface(this, bg, size / 2f, 0));
        else {
            frame.setBackground(button(this, bg, size / 2f, 0));
            frame.setContentDescription(description);
            frame.setFocusable(true);
            frame.setOnClickListener(v -> click.run());
        }
        return frame;
    }

    private TextView action(String label, boolean primary, Runnable callback) {
        TextView button = text(label, 14, primary ? ON_ACCENT : BERRY, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(button(this, primary ? BERRY : BLUSH, 15, 0));
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private EditText field(String hint) {
        EditText input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setHint(hint);
        input.setBackground(surface(this, BG, 15, LINE));
        pad(input, 12, 10, 12, 10);
        return input;
    }

    private TextView text(CharSequence value, int size, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(textTone(color));
        label.setIncludeFontPadding(false);
        if (bold) label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return label;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private void add(LinearLayout parent, View child, int height, int gap) {
        LinearLayout.LayoutParams p = size(M, height);
        p.topMargin = dp(gap);
        parent.addView(child, p);
    }

    private void oneLine(TextView label) {
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
    }

    private void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams weight(int width, int height) {
        LinearLayout.LayoutParams size = size(width, height);
        size.weight = 1;
        return size;
    }

    private int dp(float units) { return PlayerViews.dp(this, units); }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

    private String stamp(long milliseconds) {
        long seconds = Math.max(0, milliseconds / 1000);
        return seconds >= 3600
                ? String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }
}
