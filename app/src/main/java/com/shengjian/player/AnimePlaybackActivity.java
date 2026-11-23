package com.shengjian.player;

import android.app.Activity;
import android.app.DownloadManager;
import android.app.PictureInPictureParams;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.Rational;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.widget.VideoView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AnimePlaybackActivity extends Activity {
    private static final String TAG = "AnimePlayback";
    private static final int M = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int W = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int PANEL = 0xff202124;
    private static final int STROKE = 0xff45464c;
    private static final int TITLE = 0xfff5f5f8;
    private static final int SUBTITLE = 0xffc1c2c9;
    private static final int ACCENT = 0xfff299a6;
    private static final int INK = 0xff272126;
    private static final int WIDE_PLAYER_WIDTH = 600;
    private static final float HOLD_SPEED = 2f;
    private static final String EXTRA_SESSION = "anime_session";
    private static final String EXTRA_INDEX = "anime_index";
    private static final String EXTRA_ROAD = "anime_road";
    private static final String EXTRA_DEMO = "anime_demo";
    private static final int EXPORT_FRAME = 208;
    private static final int MAX_SESSIONS = 8;
    private static final MediaPlayer.TrackInfo[] NO_TRACKS = new MediaPlayer.TrackInfo[0];
    private static final Map<String, List<AnimeEngine.Road>> SESSIONS = new LinkedHashMap<>();

    public static void launch(Activity host, List<AnimeEngine.Road> roads, int road, int index) {
        if (road < 0 || road >= roads.size() || index < 0 || index >= roads.get(road).episodes.size()) return;
        String token = UUID.randomUUID().toString();
        synchronized (SESSIONS) {
            if (SESSIONS.size() >= MAX_SESSIONS) SESSIONS.remove(SESSIONS.keySet().iterator().next());
            SESSIONS.put(token, List.copyOf(roads));
        }
        host.startActivity(new Intent(host, AnimePlaybackActivity.class).putExtra(EXTRA_SESSION, token)
                .putExtra(EXTRA_ROAD, road).putExtra(EXTRA_INDEX, index));
    }

    public static void launchDemo(Activity host) {
        host.startActivity(new Intent(host, AnimePlaybackActivity.class).putExtra(EXTRA_DEMO, true));
    }

    static long savedPosition(Context context, AnimeEngine.Episode episode) {
        return AnimePlaybackHistory.position(context, episode);
    }

    static int resumeIndex(Context context, List<AnimeEngine.Episode> episodes) {
        return AnimePlaybackHistory.resumeIndex(context, episodes);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updatePlayback();
            main.postDelayed(this, 350);
        }
    };
    private final Runnable timeout = this::timedOut;
    private final Runnable hideControls = this::hideImmersiveControls;
    private final Runnable sleepTimer = () -> {
        updatePlayback();
        persist();
        finish();
    };

    private List<AnimeEngine.Episode> episodes;
    private List<AnimeEngine.Road> roads;
    private String token;
    private int index, roadIndex, generation;
    private long position, persistAt;
    private long seekRequestedAt;
    private int duration;
    private boolean ready, seeking, fullscreen, pip, sniffing, website, inactive;
    private boolean shouldPlay = true, nextOnEnd = true, finished;
    private boolean resumePreparedSeek;
    private boolean demo;
    private boolean autoResume;
    private boolean privateViewing;
    private boolean recordedWatch;
    private boolean preferMpv;
    private boolean canSwitchEngine;
    private boolean pipSupported, compactFullscreen;
    private int skipSeconds, videoFit, anime4k;
    private int appliedVideoFit = -1, videoSourceWidth, videoSourceHeight;
    private int chromeInsetLeft, chromeInsetTop, chromeInsetRight, chromeInsetBottom;
    private long sleepAt;
    private String playbackAddress = "";
    private String playbackReferer = "";
    private String resolvedUserAgent = "";
    private Map<String, String> playbackHeaders = Collections.emptyMap();
    private SharedPreferences playbackOptions;
    private float speed = 1f;
    private float appliedSpeed = 1f;
    private File demoFile;
    private ExecutorService mediaWork;
    private Bitmap pendingFrame;
    private FrameLayout root, stage, overlay, webFullscreen, chrome, headerDock, controlsDock;
    private LinearLayout header, controls, timeline, tools, transport, sideControls, loadingBox, errors;
    private ScrollView episodeDetails;
    private AnimeEpisodePanel episodePanel;
    private AnimeCommentsPanel commentsPanel;
    private AnimePlaybackGestures gestures;
    private ProgressBar restingProgress;
    private TextView gestureFeedback, episodePicker;
    private PlayerViews.Icon playGlyph, dockPlayGlyph, lockGlyph, fullscreenGlyph, webFullscreenGlyph;
    private GradientDrawable portraitHeaderBackground, portraitControlsBackground;
    private View topScrim, bottomScrim;
    private AnimePlayerOverflow playbackOverflow;
    private boolean chromeVisible = true, previouslyPlaying, controlsLocked, temporaryBoost;
    private int openPanels;
    private long scrubPosition = -1;
    private ProgressBar spinner;
    private TextView title, positionLabel, fullscreenTime, elapsedTime, totalTime, speedButton, quickForward;
    private View playButton, dockPlay, next, fullscreenPip, lockButton, screenshotButton;
    private View danmakuSettings, upscalingButton, aspectButton, controlsSpacer, fullButton, webPageFullButton, moreButton;
    private TextView websiteButton, status, loadingMessage;
    private View skipBack, skipForward;
    private TextView skipBackNumber, skipForwardNumber;
    private ToggleButton danmakuToggle, webPageDanmakuToggle, webDanmakuToggle;
    private SeekBar seek;
    private AnimeVideoView video;
    private AnimeMpvPlayer mpv;
    private MediaPlayer media;
    private AnimeWebPlayer web;
    private AnimeDanmakuControls danmaku;
    private long webSampleAt;
    private boolean webPlaying;
    private double webRate = 1;
    private WebChromeClient.CustomViewCallback webFullCallback;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        demo = getIntent().getBooleanExtra(EXTRA_DEMO, false);
        token = getIntent().getStringExtra(EXTRA_SESSION);
        synchronized (SESSIONS) { roads = SESSIONS.get(token); }
        if (!demo && roads != null && !roads.isEmpty()) {
            roadIndex = saved == null ? getIntent().getIntExtra(EXTRA_ROAD, 0) : saved.getInt(EXTRA_ROAD);
            roadIndex = Math.max(0, Math.min(roadIndex, roads.size() - 1));
            episodes = roads.get(roadIndex).episodes;
        }
        if (!demo && (episodes == null || episodes.isEmpty())) {
            Toast.makeText(this, "播放已结束，请从番剧列表重新打开", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        index = saved == null ? getIntent().getIntExtra(EXTRA_INDEX, 0) : saved.getInt(EXTRA_INDEX, 0);
        index = demo ? 0 : Math.max(0, Math.min(index, episodes.size() - 1));
        playbackOptions = getSharedPreferences(AnimePlaybackHistory.PREFS, Context.MODE_PRIVATE);
        nextOnEnd = playbackOptions.getBoolean("autoplay_next", true);
        autoResume = playbackOptions.getBoolean("auto_resume", true);
        privateViewing = playbackOptions.getBoolean("private_viewing", false);
        recordedWatch = saved != null && saved.getBoolean("recorded_watch");
        preferMpv = playbackOptions.getBoolean("prefer_mpv", false);
        anime4k = Math.max(0, Math.min(2, playbackOptions.getInt("anime4k", 0)));
        skipSeconds = Math.max(5, Math.min(60, playbackOptions.getInt("skip_seconds", 10)));
        videoFit = AnimeVideoSizing.normalize(playbackOptions.getInt("video_fit", AnimeVideoSizing.AUTO));
        speed = Math.max(.5f, Math.min(3f, playbackOptions.getFloat("speed", 1f)));
        if (!Float.isFinite(speed)) speed = 1f;
        pipSupported = getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        buildScreen();
        if (demo) {
            title.setText("本地示例");
            episodePanel.bind(Collections.emptyList(), 0, true);
            openDemo();
        } else startEpisode(index, false);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt(EXTRA_INDEX, index);
        out.putInt(EXTRA_ROAD, roadIndex);
        out.putBoolean("recorded_watch", recordedWatch);
        persist();
        super.onSaveInstanceState(out);
    }

    private void buildScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        LinearLayout stack = column();
        stack.setBackgroundColor(PlayerViews.BG);
        root.addView(stack, new FrameLayout.LayoutParams(M, M));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        stack.addView(stage, size(M, 216));
        episodePanel = new AnimeEpisodePanel(this, this::pickEpisode, destination -> {
            if (!demo && destination != index) startEpisode(destination, true);
        });
        episodeDetails = new ScrollView(this);
        episodeDetails.setFillViewport(false);
        episodeDetails.setClipToPadding(false);
        episodeDetails.setPadding(0, 0, 0, dp(16));
        episodeDetails.setBackgroundColor(PlayerViews.BG);
        FrameLayout centeredDetails = new FrameLayout(this);
        LinearLayout details = column();
        centeredDetails.addView(details, new FrameLayout.LayoutParams(M, W, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        centeredDetails.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            int width = Math.min(right - left, dp(680));
            ViewGroup.LayoutParams params = details.getLayoutParams();
            if (width > 0 && params.width != width) {
                params.width = width;
                details.setLayoutParams(params);
            }
        });
        episodeDetails.addView(centeredDetails, new ScrollView.LayoutParams(M, W));
        details.addView(episodePanel, size(M, W));
        commentsPanel = new AnimeCommentsPanel(this);
        commentsPanel.bind(null);
        details.addView(commentsPanel, size(M, W));
        stack.addView(episodeDetails, new LinearLayout.LayoutParams(M, 0, 1));

        danmaku = new AnimeDanmakuControls(this, this::danmakuPosition, this::danmakuPlaying);
        danmaku.view.setPlaybackRate(() -> website ? webRate : appliedSpeed);
        danmaku.mount(stage, 0);
        gestures = new AnimePlaybackGestures(this, new AnimePlaybackGestures.Callbacks() {
            @Override public boolean canAdjust() { return ready && playerSurface() != null && !pip && !controlsLocked; }
            @Override public boolean canAdjustLevel() { return fullscreen && canAdjust(); }
            @Override public boolean canStartTemporaryBoost() {
                return fullscreen && canAdjust() && nativePlaying() && speed < HOLD_SPEED;
            }
            @Override public boolean startTemporaryBoost() { return beginBoost(); }
            @Override public void stopTemporaryBoost() { endBoost(); }
            @Override public void toggleControls() { toggleChrome(); }
            @Override public void togglePlayback() {
                if (!nativePlaying()) showChrome();
                toggle();
            }
            @Override public long positionMs() { return position; }
            @Override public long durationMs() { return canSeek() ? duration : 0; }
            @Override public void scrub(long target, boolean finished) {
                if (!canSeek()) return;
                if (finished) {
                    scrubPosition = -1;
                    seeking = false;
                    seekTo(target);
                } else {
                    main.removeCallbacks(hideControls);
                    scrubPosition = target;
                    seeking = true;
                    updateControls();
                }
            }
            @Override public void feedback(String label) {
                gestureFeedback.setText(label);
                gestureFeedback.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
                if (label.isEmpty()) {
                    if (scrubPosition >= 0) {
                        scrubPosition = -1;
                        seeking = false;
                        updateControls();
                    }
                    scheduleImmersiveControls();
                }
            }
        });
        stage.addView(gestures, new FrameLayout.LayoutParams(M, M));
        buildLoadingLayer();
        buildVideoChrome();
        webFullscreen = new FrameLayout(this);
        webFullscreen.setBackgroundColor(Color.BLACK);
        webFullscreen.setVisibility(View.GONE);
        root.addView(webFullscreen, new FrameLayout.LayoutParams(M, M));
        root.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> sizeVideoStage());
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            applyInsets(insets);
            return insets;
        });
        setContentView(root);
        applyBars();
    }

    private void buildLoadingLayer() {
        overlay = new FrameLayout(this);
        stage.addView(overlay, new FrameLayout.LayoutParams(M, M));
        loadingBox = column();
        loadingBox.setGravity(Gravity.CENTER);
        pad(loadingBox, 14, 9, 14, 9);
        loadingBox.setBackground(PlayerViews.surface(this, 0xe01d1d24, 16, STROKE));
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        loadingBox.addView(spinner, size(24, 24));
        loadingMessage = text("", 14, TITLE);
        loadingMessage.setGravity(Gravity.CENTER);
        pad(loadingMessage, 3, 5, 3, 4);
        loadingBox.addView(loadingMessage, size(M, W));
        errors = row();
        errors.setGravity(Gravity.CENTER);
        errors.addView(action("重试", v -> retry()), size(70, 44));
        websiteButton = action("网页播放", v -> openWebsite());
        errors.addView(websiteButton, size(92, 44));
        loadingBox.addView(errors, size(M, W));
        overlay.addView(loadingBox, new FrameLayout.LayoutParams(dp(246), W, Gravity.CENTER));
    }

    private void buildVideoChrome() {
        restingProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        restingProgress.setMax(1000);
        restingProgress.setProgressTintList(ColorStateList.valueOf(ACCENT));
        restingProgress.setProgressBackgroundTintList(ColorStateList.valueOf(0x38ffffff));
        restingProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stage.addView(restingProgress, new FrameLayout.LayoutParams(M, dp(2), Gravity.BOTTOM));

        chrome = new FrameLayout(this);
        stage.addView(chrome, new FrameLayout.LayoutParams(M, M));
        topScrim = addFullscreenScrim(true);
        bottomScrim = addFullscreenScrim(false);
        header = row();
        pad(header, 7, 2, 7, 0);
        portraitHeaderBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xbd000000, Color.TRANSPARENT});
        header.setBackground(portraitHeaderBackground);
        header.addView(iconAction("back_left", "返回", this::leavePlayer), size(44, 44));
        title = text("", 15, TITLE);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
        oneLine(title);
        pad(title, 5, 0, 5, 0);
        header.addView(title, weighted(W));
        webPageDanmakuToggle = createDanmakuToggle();
        header.addView(webPageDanmakuToggle, size(44, 44));
        webFullscreenGlyph = new PlayerViews.Icon(this, "fullscreen", Color.WHITE, 22);
        webPageFullButton = iconAction(webFullscreenGlyph, "全屏", () -> setFull(!fullscreen, true));
        header.addView(webPageFullButton, size(44, 44));
        quickForward = chromeAction("", () -> jump(skipSeconds));
        quickForward.setOnLongClickListener(view -> { chooseSkipInterval(); return true; });
        header.addView(quickForward, size(48, 48));
        fullscreenPip = iconAction("pip", "画中画", this::enterPip);
        header.addView(fullscreenPip, size(48, 48));
        moreButton = iconAction("more_vertical", "播放选项", this::showPlaybackMenu);
        StateListDrawable moreBackground = new StateListDrawable();
        moreBackground.addState(new int[]{android.R.attr.state_selected},
                PlayerViews.button(this, 0x594c4d55, 11, 0x36ffffff));
        moreBackground.addState(new int[0], PlayerViews.button(this, Color.TRANSPARENT, 11, 0));
        moreButton.setBackground(new InsetDrawable(moreBackground, dp(4)));
        header.addView(moreButton, size(44, 44));
        headerDock = new FrameLayout(this);
        headerDock.addView(header, new FrameLayout.LayoutParams(M, dp(49), Gravity.CENTER_HORIZONTAL));
        chrome.addView(headerDock, new FrameLayout.LayoutParams(M, W, Gravity.TOP));

        transport = row();
        transport.setGravity(Gravity.CENTER);
        pad(transport, 2, 0, 2, 0);
        transport.setBackground(PlayerViews.surface(this, 0x9e111216, 24, 0x29ffffff));
        skipBackNumber = skipNumber();
        skipForwardNumber = skipNumber();
        skipBack = skipAction("seek_back", skipBackNumber, () -> jump(-skipSeconds));
        skipForward = skipAction("seek_forward", skipForwardNumber, () -> jump(skipSeconds));
        refreshSkipButtons();
        transport.addView(skipBack, size(48, 48));
        playGlyph = new PlayerViews.Icon(this, "play", TITLE, 23);
        playButton = iconAction(playGlyph, "播放", this::toggle);
        transport.addView(playButton, size(48, 48));
        transport.addView(skipForward, size(48, 48));
        chrome.addView(transport, new FrameLayout.LayoutParams(W, W, Gravity.CENTER));

        controls = column();
        pad(controls, 12, 0, 12, 3);
        portraitControlsBackground = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.TRANSPARENT, 0xa8000000, 0xdd000000});
        status = text("", 12, SUBTITLE);
        status.setGravity(Gravity.END);
        oneLine(status);
        controls.addView(status, size(M, W));
        timeline = row();
        fullscreenTime = timelineLabel();
        pad(fullscreenTime, 4, 0, 4, 0);
        fullscreenTime.setShadowLayer(dp(2), 0, dp(1), 0xcc000000);
        controls.addView(fullscreenTime, size(W, W));
        elapsedTime = timelineLabel();
        totalTime = timelineLabel();
        timeline.addView(elapsedTime, size(W, W));
        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setSplitTrack(false);
        seek.setProgressTintList(ColorStateList.valueOf(ACCENT));
        seek.setSecondaryProgressTintList(ColorStateList.valueOf(0x80ffffff));
        seek.setProgressBackgroundTintList(ColorStateList.valueOf(0x62ffffff));
        seek.setThumbTintList(ColorStateList.valueOf(ACCENT));
        seek.setPadding(dp(5), 0, dp(5), 0);
        seek.setContentDescription("视频进度");
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser) {
                    long target = duration * (long) value / 1000;
                    updateTimelineLabels(target);
                    if (!seeking && canSeek()) seekTo(target);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                main.removeCallbacks(hideControls);
                seeking = true;
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                seeking = false;
                if (canSeek()) seekTo(duration * (long) bar.getProgress() / 1000);
                scheduleImmersiveControls();
            }
        });
        timeline.addView(seek, weighted(36));
        timeline.addView(totalTime, size(W, W));
        controls.addView(timeline, size(M, W));

        tools = row();
        dockPlayGlyph = new PlayerViews.Icon(this, "play", Color.WHITE, 25);
        dockPlay = iconAction(dockPlayGlyph, "播放", this::toggle);
        tools.addView(dockPlay, size(48, 48));
        next = iconAction("next", "下一集", () -> startEpisode(index + 1, true));
        tools.addView(next, size(44, 44));
        positionLabel = timelineLabel();
        pad(positionLabel, 4, 0, 3, 0);
        tools.addView(positionLabel, weighted(W));
        danmakuToggle = createDanmakuToggle();
        tools.addView(danmakuToggle, size(44, 44));
        danmakuSettings = iconAction("settings", "弹幕设置", danmaku::menu);
        tools.addView(danmakuSettings, size(44, 44));
        controlsSpacer = new View(this);
        tools.addView(controlsSpacer, weighted(1));
        danmaku.setOnVisibilityChanged(this::updateDanmakuToggles);
        upscalingButton = chromeAction("超分辨率", this::chooseUpscaling);
        tools.addView(upscalingButton, size(68, 44));
        speedButton = chromeAction(speedLabel(speed), this::chooseSpeed);
        tools.addView(speedButton, size(48, 44));
        aspectButton = iconAction("aspect", "画面比例", this::chooseVideoFit);
        tools.addView(aspectButton, size(44, 44));
        episodePicker = chromeAction("选集", this::pickEpisode);
        tools.addView(episodePicker, size(46, 44));
        fullscreenGlyph = new PlayerViews.Icon(this, "fullscreen", Color.WHITE, 22);
        fullButton = iconAction(fullscreenGlyph, "全屏", () -> setFull(!fullscreen, true));
        tools.addView(fullButton, size(44, 44));
        controls.addView(tools, size(M, W));
        controlsDock = new FrameLayout(this);
        controls.setBackground(portraitControlsBackground);
        controlsDock.addView(controls, new FrameLayout.LayoutParams(M, W, Gravity.CENTER_HORIZONTAL));
        chrome.addView(controlsDock, new FrameLayout.LayoutParams(M, W, Gravity.BOTTOM));

        sideControls = column();
        screenshotButton = iconAction("camera", "截图", this::captureFrame);
        lockGlyph = new PlayerViews.Icon(this, "unlock", Color.WHITE, 21);
        lockButton = iconAction(lockGlyph, "锁定播放控件", this::toggleControlsLock);
        sideControls.addView(screenshotButton, size(48, 48));
        sideControls.addView(lockButton, size(48, 48));
        chrome.addView(sideControls, new FrameLayout.LayoutParams(W, W, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        gestureFeedback = text("", 14, TITLE);
        gestureFeedback.setGravity(Gravity.CENTER);
        pad(gestureFeedback, 17, 9, 17, 9);
        gestureFeedback.setBackground(PlayerViews.surface(this, 0xd51d1d24, 16, 0));
        gestureFeedback.setVisibility(View.GONE);
        gestureFeedback.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams feedbackPosition = new FrameLayout.LayoutParams(W, W, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        feedbackPosition.topMargin = dp(51);
        stage.addView(gestureFeedback, feedbackPosition);
        stage.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> layoutPlaybackControls());
        controlsDock.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> positionTransport());
        header.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> positionTransport());
    }

    private void layoutPlaybackControls() {
        int stageWidth = stage.getWidth(), stageHeight = stage.getHeight();
        applyVideoFit(stageWidth, stageHeight);
        float fontScale = getResources().getConfiguration().fontScale;
        AnimeHudGeometry.Edges edges = AnimeHudGeometry.edges(fullscreen, stageWidth, stageHeight,
                chromeInsetLeft, chromeInsetTop, chromeInsetRight, chromeInsetBottom,
                dp(fullscreen ? 4 : 7), dp(fullscreen ? 10 : 12), dp(90), dp(WIDE_PLAYER_WIDTH),
                Math.round(dp(WIDE_PLAYER_WIDTH) * fontScale));
        setPixelPadding(header, edges.headerLeft(), fullscreen ? 0 : dp(2), edges.headerRight(), 0);
        setPixelPadding(controls, edges.transportLeft(), 0, edges.transportRight(), fullscreen ? 0 : dp(3));
        setPixelPadding(headerDock, 0, edges.top(), 0, 0);
        setPixelPadding(controlsDock, 0, 0, 0, edges.bottom());
        scrimHeight(topScrim, dp(50) + edges.top());
        scrimHeight(bottomScrim, dp(100) + edges.bottom());
        FrameLayout.LayoutParams rail = (FrameLayout.LayoutParams) sideControls.getLayoutParams();
        if (rail.rightMargin != edges.railRight()) {
            rail.rightMargin = edges.railRight();
            sideControls.setLayoutParams(rail);
        }
        boolean wide = fullscreen && !edges.compact();
        compactFullscreen = fullscreen && edges.compact();
        placeTimeline(compactFullscreen);
        fullscreenTime.setVisibility(wide && !edges.sideLabels() ? View.VISIBLE : View.GONE);
        positionLabel.setVisibility(!fullscreen || compactFullscreen ? View.VISIBLE : View.GONE);
        elapsedTime.setVisibility(edges.sideLabels() ? View.VISIBLE : View.GONE);
        totalTime.setVisibility(edges.sideLabels() ? View.VISIBLE : View.GONE);
        controlsSpacer.setVisibility(wide ? View.VISIBLE : View.GONE);
        aspectButton.setVisibility(wide ? View.VISIBLE : View.GONE);
        upscalingButton.setVisibility(wide && AnimeMpvPlayer.supported() ? View.VISIBLE : View.GONE);
        speedButton.setVisibility(!compactFullscreen ? View.VISIBLE : View.GONE);
        next.setVisibility(!demo && !compactFullscreen ? View.VISIBLE : View.GONE);
        episodePicker.setVisibility(!demo && wide ? View.VISIBLE : View.GONE);
        danmakuToggle.setVisibility(!compactFullscreen ? View.VISIBLE : View.GONE);
        refreshDanmakuActions(wide);
        updateChromeVisibility();
    }

    private void placeTimeline(boolean compact) {
        LinearLayout seekRow = compact ? tools : timeline;
        moveTimelineControl(seek, seekRow, compact ? 2 : 1, weighted(compact ? 44 : fullscreen ? 26 : 36));
        timeline.setVisibility(compact ? View.GONE : View.VISIBLE);
        moveTimelineControl(positionLabel, tools, compact ? 3 : 2, fullscreen ? size(W, W) : weighted(W));
    }

    private static void moveTimelineControl(View view, LinearLayout row, int index, LinearLayout.LayoutParams size) {
        if (view.getParent() != row || row.indexOfChild(view) != index) {
            if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
            row.addView(view, Math.min(index, row.getChildCount()), size);
        } else {
            LinearLayout.LayoutParams current = (LinearLayout.LayoutParams) view.getLayoutParams();
            if (current.width != size.width || current.height != size.height || current.weight != size.weight) {
                view.setLayoutParams(size);
            }
        }
    }

    private View addFullscreenScrim(boolean top) {
        View scrim = new View(this);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                top ? new int[]{0x73000000, Color.TRANSPARENT} : new int[]{Color.TRANSPARENT, 0x73000000}));
        scrim.setVisibility(View.GONE);
        chrome.addView(scrim, new FrameLayout.LayoutParams(M, dp(top ? 50 : 100), top ? Gravity.TOP : Gravity.BOTTOM));
        return scrim;
    }

    private static void scrimHeight(View scrim, int height) {
        ViewGroup.LayoutParams params = scrim.getLayoutParams();
        if (params.height == height) return;
        params.height = height;
        scrim.setLayoutParams(params);
    }

    private static void setPixelPadding(View view, int left, int top, int right, int bottom) {
        if (view.getPaddingLeft() != left || view.getPaddingTop() != top
                || view.getPaddingRight() != right || view.getPaddingBottom() != bottom) {
            view.setPadding(left, top, right, bottom);
        }
    }

    private void positionTransport() {
        float top = headerDock.getHeight() + dp(4);
        float bottom = stage.getHeight() - controlsDock.getHeight() - dp(4);
        boolean fits = stage.getHeight() == 0 || bottom - top >= dp(48);
        boolean center = !fullscreen && fits;
        transport.setVisibility(center && !website && ready && playerSurface() != null ? View.VISIBLE : View.GONE);
        dockPlay.setVisibility(center ? View.GONE : View.VISIBLE);
        if (center) transport.setTranslationY((top + bottom - stage.getHeight()) / 2f);
    }

    private TextView timelineLabel() {
        TextView label = text("0:00 / 0:00", 12, TITLE);
        label.setFontFeatureSettings("tnum");
        label.setIncludeFontPadding(false);
        oneLine(label);
        return label;
    }

    private void leavePlayer() {
        if (controlsLocked) toggleControlsLock();
        else if (fullscreen) setFull(false, true);
        else finish();
    }

    private FrameLayout iconAction(String name, String label, Runnable command) {
        return iconAction(new PlayerViews.Icon(this, name, Color.WHITE, 22), label, command);
    }

    private FrameLayout iconAction(PlayerViews.Icon icon, String label, Runnable command) {
        FrameLayout target = new FrameLayout(this);
        target.setContentDescription(label);
        target.setFocusable(true);
        target.setBackground(PlayerViews.button(this, Color.TRANSPARENT, 22, 0));
        target.addView(icon, new FrameLayout.LayoutParams(M, M));
        target.setOnClickListener(view -> { showChrome(); command.run(); scheduleImmersiveControls(); });
        return target;
    }

    private TextView chromeAction(String value, Runnable command) {
        TextView button = text(value, 13, TITLE);
        button.setGravity(Gravity.CENTER);
        button.setFocusable(true);
        button.setBackground(PlayerViews.button(this, Color.TRANSPARENT, 8, 0));
        button.setOnClickListener(view -> { showChrome(); command.run(); scheduleImmersiveControls(); });
        return button;
    }

    private TextView skipNumber() {
        TextView label = text("", 9, TITLE);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 9);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setFontFeatureSettings("tnum");
        label.setIncludeFontPadding(false);
        label.setGravity(Gravity.CENTER);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return label;
    }

    private FrameLayout skipAction(String glyph, TextView number, Runnable command) {
        FrameLayout button = iconAction(new PlayerViews.Icon(this, glyph, TITLE, 28), "", command);
        button.addView(number, new FrameLayout.LayoutParams(W, W, Gravity.CENTER));
        return button;
    }

    private void startEpisode(int destination, boolean keepPrior) {
        startEpisode(destination, keepPrior, -1);
    }

    private void startEpisode(int destination, boolean keepPrior, long resumeAt) {
        if (destination < 0 || destination >= episodes.size()) return;
        if (keepPrior) persist();
        index = destination;
        position = resumeAt >= 0 ? resumeAt : autoResume && !privateViewing ? savedPosition(this, episode()) : 0;
        shouldPlay = true;
        finished = false;
        persist();
        if (!privateViewing) {
            new AnimeLibrary(this).record(episode(), !recordedWatch);
            recordedWatch = true;
        }
        refreshPlayerTitle();
        episodePanel.bind(episodes, index, false);
        commentsPanel.bind(episode());
        if (keepPrior) episodeDetails.scrollTo(0, 0);
        showChrome();
        danmaku.bind(episode());
        openEpisode();
    }

    private AnimeEngine.Episode episode() { return episodes.get(index); }

    private void openDemo() {
        if (demoFile != null && demoFile.isFile()) {
            openNative(Uri.fromFile(demoFile).toString(), "");
            return;
        }
        closePlayback();
        int epoch = generation;
        showLoading("正在准备本地示例…");
        mediaWork().execute(() -> {
            File temporary = null;
            try (InputStream input = getAssets().open("anime-demo/sample.mp4")) {
                temporary = File.createTempFile("anon-anime-", ".mp4", getCacheDir());
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    long total = 0;
                    byte[] bytes = new byte[32_768];
                    for (int count; (count = input.read(bytes)) != -1;) {
                        total += count;
                        if (total > 12L * 1024 * 1024 || Thread.currentThread().isInterrupted()) {
                            throw new IOException("Invalid demo size or cancelled");
                        }
                        output.write(bytes, 0, count);
                    }
                }
                File prepared = temporary;
                main.post(() -> {
                    if (isFinishing() || isDestroyed() || generation != epoch) prepared.delete();
                    else {
                        demoFile = prepared;
                        openNative(Uri.fromFile(demoFile).toString(), "");
                    }
                });
            } catch (IOException failed) {
                if (temporary != null) temporary.delete();
                main.post(() -> {
                    if (generation == epoch && !isDestroyed()) showFailure("无法打开本地示例视频");
                });
            }
        });
    }

    private void openEpisode() {
        playbackAddress = "";
        playbackReferer = "";
        resolvedUserAgent = "";
        playbackHeaders = Collections.emptyMap();
        String address = episode().url;
        if (!AnimeWebPlayer.webAddress(address)) {
            showFailure("播放地址无效或指向设备内部网络");
        } else if (AnimeEngine.isDirectMedia(address)) {
            openNative(address, "");
        } else {
            openWeb(true);
        }
    }

    private void openNative(String address, String observedReferer) {
        boolean mpvAvailable = AnimeMpvPlayer.supported();
        openNative(address, observedReferer, preferMpv && mpvAvailable, mpvAvailable);
    }

    private void openNative(String address, String observedReferer, boolean useMpv, boolean switchEngine) {
        closePlayback();
        if (!demo && !AnimeWebPlayer.webAddress(address)) { showFailure("未找到有效视频地址"); return; }
        int epoch = generation;
        Map<String, String> headers = demo ? new LinkedHashMap<>() : new LinkedHashMap<>(AnimeEngine.playbackHeaders(episode()));
        headers.keySet().removeIf(key -> !key.equalsIgnoreCase("Referer") && !key.equalsIgnoreCase("User-Agent"));
        if (!demo) {
            String ruleReferer = episode().show.source.referer;
            String referer = AnimeWebPlayer.webAddress(ruleReferer) ? ruleReferer : observedReferer;
            if (AnimeWebPlayer.webAddress(referer)) {
                headers.put("Referer", Uri.parse(referer).buildUpon().clearQuery().fragment(null).toString());
            }
            if (!resolvedUserAgent.isEmpty()) headers.putIfAbsent("User-Agent", resolvedUserAgent);
        }
        playbackAddress = address;
        playbackReferer = observedReferer;
        playbackHeaders = headers;
        canSwitchEngine = switchEngine;
        if (useMpv) {
            openMpv(address, headers, epoch);
            return;
        }
        showLoading("正在载入视频…");
        AnimeVideoView player = new AnimeVideoView(this);
        video = player;
        applyVideoFit(stage.getWidth(), stage.getHeight());
        player.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
        player.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN);
        player.setOnPreparedListener(controller -> afterPlayerCallback(epoch, () -> nativePrepared(player, controller)));
        player.setOnCompletionListener(controller -> afterPlayerCallback(epoch, this::completeEpisode));
        player.setOnErrorListener((controller, what, extra) -> {
            afterPlayerCallback(epoch, () -> nativeFailed(false, extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                    || extra == MediaPlayer.MEDIA_ERROR_MALFORMED ? "播放器无法解码当前视频，请换一条线路"
                    : "视频读取失败，请重试或换一条线路"));
            return true;
        });
        player.setOnInfoListener((controller, what, extra) -> {
            if (generation == epoch && ready) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) status.setText("正在缓冲…");
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) status.setText("");
            }
            return true;
        });
        try {
            player.setVideoURI(Uri.parse(address), headers);
            stage.addView(player, 0, videoSurfaceLayout(stage.getWidth(), stage.getHeight()));
            main.postDelayed(timeout, 25_000);
        } catch (RuntimeException failed) {
            Log.w(TAG, "Could not initialize video view", failed);
            nativeFailed(false, "系统播放器无法打开此地址");
        }
        updateControls();
        applyBars();
    }

    private void openMpv(String address, Map<String, String> headers, int epoch) {
        showLoading("正在启动 mpv…");
        try {
            List<File> shaders = Anime4kShaders.files(this, anime4k);
            mpv = new AnimeMpvPlayer(this, new AnimeMpvPlayer.Listener() {
                @Override public void onReady() {
                    afterPlayerCallback(epoch, () -> {
                        if (mpv == null) return;
                        main.removeCallbacks(timeout);
                        ready = true;
                        duration = (int) Math.max(0, Math.min(Integer.MAX_VALUE, mpv.duration()));
                        showContent();
                        if (shouldPlay && !inactive) startNative();
                        scheduleImmersiveControls();
                        updatePlayback();
                    });
                }

                @Override public void onCompletion() {
                    afterPlayerCallback(epoch, AnimePlaybackActivity.this::completeEpisode);
                }

                @Override public void onError(String message) {
                    afterPlayerCallback(epoch, () -> nativeFailed(true, "mpv 无法播放当前视频，请换一条线路"));
                }
            });
            mpv.setShaders(shaders);
            applyVideoFit(stage.getWidth(), stage.getHeight());
            mpv.speed(speed);
            appliedSpeed = speed;
            stage.addView(mpv.view(), 0, videoSurfaceLayout(stage.getWidth(), stage.getHeight()));
            mpv.load(address, headers, position);
            main.postDelayed(timeout, 25_000);
        } catch (IOException | RuntimeException | LinkageError unavailable) {
            Log.w(TAG, "Optional mpv engine unavailable", unavailable);
            nativeFailed(true, "mpv 无法启动，请重试");
            return;
        }
        updateControls();
        applyBars();
    }

    private void nativeFailed(boolean wasMpv, String message) {
        if (!canSwitchEngine) {
            showFailure(message);
            return;
        }
        openNative(playbackAddress, playbackReferer, !wasMpv, false);
    }

    private void afterPlayerCallback(int epoch, Runnable change) {
        // VideoView and WebView may continue using their player after the app listener returns.
        main.post(() -> {
            if (generation == epoch && !isFinishing() && !isDestroyed()) change.run();
        });
    }

    private void nativePrepared(VideoView player, MediaPlayer controller) {
        if (player != video || controller == null || !player.getHolder().getSurface().isValid()) return;
        main.removeCallbacks(timeout);
        media = controller;
        try {
            video.dimensions(controller);
            duration = Math.max(0, player.getDuration());
            controller.setOnSeekCompleteListener(ignored -> {
                if (media == controller) resumePreparedSeek = false;
            });
        } catch (IllegalStateException failed) {
            Log.w(TAG, "Player lost preparation state", failed);
            showFailure("视频初始化失败，请重试或换一集");
            return;
        }
        ready = true;
        if (position > 0 && (duration == 0 || position < duration - 2000L)) {
            resumePreparedSeek = true;
            seekRequestedAt = SystemClock.elapsedRealtime();
            if (!seekNative(position)) return;
        } else position = 0;
        showContent();
        if (shouldPlay && !inactive && !startNative()) return;
        scheduleImmersiveControls();
        updatePlayback();
    }

    private void openWebsite() { openWeb(false); }

    private void timedOut() {
        if (mpv != null || video != null) nativeFailed(mpv != null, "视频加载超时，请重试或换一条线路");
        else if (web != null && sniffing) showFailure("未识别到可播放的视频流，请重试或换一条线路");
        else showFailure("播放页面加载超时，请重试或换一条线路");
    }

    private void openWeb(boolean findMedia) {
        closePlayback();
        if (!AnimeWebPlayer.webAddress(episode().url)) {
            showFailure("播放页面地址无效");
            return;
        }
        int epoch = generation;
        sniffing = findMedia;
        website = !findMedia;
        showLoading(findMedia ? "正在识别视频地址…" : "正在打开网页播放器…");
        try {
            web = new AnimeWebPlayer(this, main, new AnimeWebPlayer.Callback() {
                @Override public void media(String url, String referer) {
                    afterPlayerCallback(epoch, () -> {
                        String agent = web.view().getSettings().getUserAgentString();
                        resolvedUserAgent = agent == null ? "" : agent;
                        openNative(url, referer);
                    });
                }

                @Override public void pageReady() {
                    if (generation != epoch || findMedia) return;
                    main.removeCallbacks(timeout);
                    showContent();
                    status.setText("使用网页内的播放控件");
                }

                @Override public void failed(String reason) {
                    afterPlayerCallback(epoch, () -> showFailure(reason));
                }

                @Override public void position(long millis, boolean ended, boolean playing, double rate) {
                    if (generation != epoch) return;
                    position = millis;
                    webSampleAt = SystemClock.elapsedRealtime();
                    webPlaying = playing;
                    webRate = Double.isFinite(rate) && rate > 0 ? rate : 1;
                    ready = true;
                    danmaku.view.syncPlayback();
                    if (ended) afterPlayerCallback(epoch, AnimePlaybackActivity.this::completeEpisode);
                }

                @Override public void fullscreen(View content, WebChromeClient.CustomViewCallback callback) {
                    if (generation == epoch) openWebFullscreen(content, callback);
                    else callback.onCustomViewHidden();
                }

                @Override public void exitFullscreen() {
                    if (generation == epoch) closeWebFullscreen();
                }
            }, findMedia, position, episode().show.source.useLegacyParser);
            if (findMedia) {
                web.view().setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                web.view().setFocusable(false);
                overlay.setBackgroundColor(Color.BLACK);
                overlay.setClickable(true);
            }
            stage.addView(web.view(), 0, new FrameLayout.LayoutParams(M, M));
            web.load(episode().url, AnimeEngine.playbackHeaders(episode()));
            main.postDelayed(timeout, findMedia ? 20_000 : 25_000);
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not initialize web video view", unavailable);
            showFailure("系统网页播放器不可用，请检查 Android WebView");
        }
        updateControls();
    }

    private void showLoading(String message) {
        showChrome();
        status.setText("");
        loadingBox.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        errors.setVisibility(View.GONE);
        loadingMessage.setText(message);
    }

    private void showContent() { overlay.setVisibility(View.GONE); }

    private void showFailure(String message) {
        persist();
        closePlayback();
        showChrome();
        overlay.setVisibility(View.VISIBLE);
        loadingBox.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        errors.setVisibility(View.VISIBLE);
        boolean webFallback = !demo && AnimeWebPlayer.webAddress(episode().url) && !AnimeEngine.isDirectMedia(episode().url);
        websiteButton.setVisibility(webFallback ? View.VISIBLE : View.GONE);
        loadingMessage.setText(message);
        updateControls();
    }

    private void retry() {
        shouldPlay = true;
        if (demo) openDemo();
        else openEpisode();
    }

    private void closePlayback() {
        dismissPlaybackOverflow();
        generation++;
        main.removeCallbacks(timeout);
        main.removeCallbacks(hideControls);
        closeWebFullscreen();
        gestures.reset();
        if (web != null) {
            AnimeWebPlayer previousWeb = web;
            web = null;
            try {
                previousWeb.close();
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Could not release web video view", unavailable);
                if (previousWeb.view().getParent() == stage) stage.removeView(previousWeb.view());
            }
        }
        if (video != null) {
            VideoView previousVideo = video;
            video = null;
            previousVideo.setOnPreparedListener(null);
            previousVideo.setOnCompletionListener(null);
            previousVideo.setOnErrorListener((controller, what, extra) -> true);
            previousVideo.setOnInfoListener(null);
            try {
                // suspend() releases VideoView's player in all states, including prepare and error.
                previousVideo.suspend();
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Could not release native video view", unavailable);
            }
            if (previousVideo.getParent() == stage) stage.removeView(previousVideo);
        }
        if (mpv != null) {
            AnimeMpvPlayer previousMpv = mpv;
            mpv = null;
            previousMpv.release();
            if (previousMpv.view().getParent() == stage) stage.removeView(previousMpv.view());
        }
        media = null;
        appliedVideoFit = -1;
        videoSourceWidth = 0;
        videoSourceHeight = 0;
        webSampleAt = 0;
        webPlaying = false;
        danmaku.view.clearVideoBounds();
        danmaku.view.resetPlayback();
        duration = 0;
        appliedSpeed = 1f;
        ready = false;
        seeking = false;
        scrubPosition = -1;
        previouslyPlaying = false;
        resumePreparedSeek = false;
        website = false;
        sniffing = false;
        overlay.setBackgroundColor(Color.TRANSPARENT);
        overlay.setClickable(false);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private boolean startNative() {
        if (!ready || playerSurface() == null) return false;
        if (mpv != null) {
            mpv.play();
            return true;
        }
        try {
            video.start();
        } catch (IllegalStateException failed) {
            Log.w(TAG, "Could not start native playback", failed);
            showFailure("系统播放器无法开始播放，请重试");
            return false;
        }
        if (speed != appliedSpeed && !applySpeed(speed)) {
            speed = 1f;
            Toast.makeText(this, "当前视频不支持所选倍速", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    private boolean seekNative(long millis) {
        try {
            if (mpv != null) mpv.seek(millis);
            else video.seekTo((int) Math.min(Integer.MAX_VALUE, Math.max(0, millis)));
            danmaku.view.resetPlayback();
            return true;
        } catch (IllegalStateException failed) {
            Log.w(TAG, "Could not seek native video", failed);
            showFailure("视频跳转失败，请重试");
            return false;
        }
    }

    private void seekTo(long target) {
        if (!ready || playerSurface() == null || duration <= 0) return;
        position = Math.max(0, Math.min(duration - 1L, target));
        finished = false;
        resumePreparedSeek = true;
        seekRequestedAt = SystemClock.elapsedRealtime();
        if (!seekNative(position)) return;
        persist();
        updateControls();
        scheduleImmersiveControls();
    }

    private void jump(int seconds) {
        long current = resumePreparedSeek ? position : danmakuPosition();
        seekTo((current >= 0 ? current : position) + seconds * 1000L);
    }

    private boolean beginBoost() {
        if (!fullscreen || controlsLocked || !nativePlaying() || speed >= HOLD_SPEED || !applySpeed(HOLD_SPEED)) {
            return false;
        }
        temporaryBoost = true;
        main.removeCallbacks(hideControls);
        gestureFeedback.setText("2× 播放");
        gestureFeedback.setVisibility(View.VISIBLE);
        updateChromeVisibility();
        return true;
    }

    private void endBoost() {
        if (!temporaryBoost) return;
        temporaryBoost = false;
        if (mpv != null || shouldPlay && !finished && !inactive && nativePlaying()) applySpeed(speed);
        gestureFeedback.setVisibility(View.GONE);
        updateChromeVisibility();
        scheduleImmersiveControls();
    }

    private void toggle() {
        if (website) {
            Toast.makeText(this, "请使用网页内的播放按钮", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ready || playerSurface() == null) return;
        if (finished && mpv != null) {
            shouldPlay = true;
            finished = false;
            openNative(playbackAddress, playbackReferer);
            return;
        }
        if (nativePlaying()) {
            shouldPlay = false;
            pauseNative();
            persist();
        } else {
            shouldPlay = true;
            finished = false;
            startNative();
        }
        updatePlayback();
        scheduleImmersiveControls();
    }

    private void completeEpisode() {
        if (finished) return;
        finished = true;
        position = 0;
        if (!demo && !privateViewing) AnimePlaybackHistory.save(this, episode(), 0);
        if (!demo && nextOnEnd && index + 1 < episodes.size() && !inactive) startEpisode(index + 1, false);
        else {
            shouldPlay = false;
            status.setText("本集播放完毕");
            showChrome();
            updateControls();
        }
    }

    private void pickEpisode() {
        if (demo) return;
        AnimePlayerPanel picker = new AnimePlayerPanel(this);
        picker.setTitle("选集 · " + roadName(roadIndex)).setSingleChoiceItems(episodeLabels(episodes), index, (dialog, item) -> {
            dialog.dismiss();
            if (item != index) startEpisode(item, true);
        }).setNeutralButton(nextOnEnd ? "关闭自动连播" : "开启自动连播", (dialog, button) -> {
            nextOnEnd = !nextOnEnd;
            playbackOptions.edit().putBoolean("autoplay_next", nextOnEnd).apply();
        }).setNegativeButton("关闭", null);
        if (roads.size() > 1) picker.setPositiveButton("切换线路", (dialog, button) -> pickRoad());
        picker.show();
    }

    private static String[] episodeLabels(List<AnimeEngine.Episode> options) {
        String[] labels = new String[options.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).title;
        return labels;
    }

    private String roadName(int road) {
        String name = roads.get(road).name;
        return name.isEmpty() ? "线路 " + (road + 1) : name;
    }

    private void pickRoad() {
        String[] labels = new String[roads.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = roadName(i) + " · " + roads.get(i).episodes.size() + " 集";
        new AnimePlayerPanel(this).setTitle("切换播放线路")
                .setSingleChoiceItems(labels, roadIndex, (dialog, selected) -> {
                    dialog.dismiss();
                    if (roadIndex == selected) return;
                    List<AnimeEngine.Episode> options = roads.get(selected).episodes;
                    int match = sameEpisode(episode(), options);
                    if (match >= 0) {
                        switchRoad(selected, match, position);
                    } else {
                        new AnimePlayerPanel(this).setTitle("选择「" + roadName(selected) + "」的分集")
                                .setItems(episodeLabels(options), (window, item) -> switchRoad(selected, item, -1))
                                .setNegativeButton("取消", null).show();
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static int sameEpisode(AnimeEngine.Episode current, List<AnimeEngine.Episode> options) {
        int number = AnimeDanmaku.episodeNumber(current.sourceTitle);
        int match = -1;
        for (int i = 0; i < options.size(); i++) {
            AnimeEngine.Episode candidate = options.get(i);
            boolean sameNumber = number > 0 && number == AnimeDanmaku.episodeNumber(candidate.sourceTitle);
            if (!sameNumber && !AnimeComments.sameTitle(current.sourceTitle, candidate.sourceTitle)) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    private void switchRoad(int road, int destination, long resumeAt) {
        persist();
        roadIndex = road;
        episodes = roads.get(road).episodes;
        startEpisode(destination, false, resumeAt);
    }

    private void showPlaybackMenu() {
        if (dismissPlaybackOverflow()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (fullscreen) {
            labels.add("播放设置");
            actions.add(this::showPlaybackSettings);
            labels.add("弹幕设置");
            actions.add(danmaku::menu);
        }
        if (!demo) {
            labels.add("选集");
            actions.add(this::pickEpisode);
            if (index > 0) {
                labels.add("上一集");
                actions.add(() -> startEpisode(index - 1, true));
            }
            if (index + 1 < episodes.size()) {
                labels.add("下一集");
                actions.add(() -> startEpisode(index + 1, true));
            }
        }
        if (!fullscreen) {
            labels.add("弹幕设置");
            actions.add(danmaku::menu);
        }
        if (fullscreen && canSeek()) {
            labels.add("快退 " + skipSeconds + " 秒");
            actions.add(() -> jump(-skipSeconds));
            labels.add("快进 " + skipSeconds + " 秒");
            actions.add(() -> jump(skipSeconds));
        }
        if (fullscreen && ready && playerSurface() != null) {
            labels.add("播放倍速");
            actions.add(this::chooseSpeed);
        }
        if (website) {
            labels.add("返回内置播放");
            actions.add(this::retry);
        }
        if (hasSubtitles()) {
            labels.add("字幕");
            actions.add(this::chooseSubtitle);
        }
        if (playerSurface() != null && ready && pipSupported) {
            labels.add("画中画");
            actions.add(this::enterPip);
        }
        if (!fullscreen) {
            labels.add("播放设置");
            actions.add(this::showPlaybackSettings);
        }
        if (AnimeMpvPlayer.supported()) {
            labels.add("播放内核 · " + (preferMpv ? "mpv" : "系统解码"));
            actions.add(this::chooseEngine);
            labels.add("Anime4K 超分辨率" + (anime4k == 0 ? "" : anime4k == 1 ? " · 效率" : " · 画质"));
            actions.add(this::chooseUpscaling);
        }
        labels.add("快进与快退 · " + skipSeconds + " 秒");
        actions.add(this::chooseSkipInterval);
        if (playerSurface() != null) {
            labels.add("画面比例");
            actions.add(this::chooseVideoFit);
        }
        labels.add("定时关闭" + (sleepAt == 0 ? "" : " · 剩余 " + timestamp(sleepAt - SystemClock.elapsedRealtime())));
        actions.add(this::chooseSleepTimer);
        if (playerSurface() != null && ready) {
            labels.add("保存视频截图");
            actions.add(this::captureFrame);
            labels.add("视频详情");
            actions.add(this::showVideoDetails);
        }
        if (!demo && AnimeWebPlayer.webAddress(playbackAddress)) {
            labels.add("用外部播放器打开");
            actions.add(this::openExternalPlayer);
            if (downloadExtension() != null) {
                labels.add("下载本集视频");
                actions.add(this::enqueueDownload);
            }
        }
        if (fullscreen) playbackOverflow = AnimePlayerOverflow.show(this, stage, moreButton, labels, actions);
        else new AnimePlayerPanel(this).setTitle("播放选项")
                    .setItems(labels.toArray(new String[0]), (dialog, item) -> actions.get(item).run())
                    .setNegativeButton("关闭", null).show();
    }

    private boolean dismissPlaybackOverflow() {
        AnimePlayerOverflow popup = playbackOverflow;
        playbackOverflow = null;
        if (popup == null || !popup.isShowing()) return false;
        popup.dismiss();
        return true;
    }

    private void showPlaybackSettings() {
        String[] flags = {"自动续播上次进度", "自动播放下一集", "隐身观看"};
        boolean[] enabled = {autoResume, nextOnEnd, privateViewing};
        new AnimePlayerPanel(this).setTitle("播放设置")
                .setMultiChoiceItems(flags, enabled, (dialog, item, checked) -> enabled[item] = checked)
                .setPositiveButton("保存", (dialog, button) -> {
                    autoResume = enabled[0];
                    nextOnEnd = enabled[1];
                    privateViewing = enabled[2];
                    playbackOptions.edit().putBoolean("auto_resume", autoResume)
                            .putBoolean("autoplay_next", nextOnEnd)
                            .putBoolean("private_viewing", privateViewing).apply();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseEngine() {
        new AnimePlayerPanel(this).setTitle("播放内核")
                .setSingleChoiceItems(new String[]{"Android 系统解码", "mpv"}, preferMpv ? 1 : 0, (dialog, choice) -> {
                    dialog.dismiss();
                    boolean useMpv = choice == 1;
                    if (preferMpv == useMpv && (mpv != null) == useMpv) return;
                    preferMpv = useMpv;
                    if (!useMpv) anime4k = 0;
                    playbackOptions.edit().putBoolean("prefer_mpv", preferMpv).putInt("anime4k", anime4k).apply();
                    restartResolvedMedia();
                }).setNegativeButton("取消", null).show();
    }

    private void chooseUpscaling() {
        new AnimePlayerPanel(this).setTitle("Anime4K 超分辨率")
                .setSingleChoiceItems(new String[]{"关闭", "效率 · 启用 mpv", "画质 · 启用 mpv"}, anime4k, (dialog, choice) -> {
                    dialog.dismiss();
                    if (anime4k == choice && (choice == 0 || mpv != null)) return;
                    anime4k = choice;
                    if (choice > 0) preferMpv = true;
                    playbackOptions.edit().putInt("anime4k", anime4k).putBoolean("prefer_mpv", preferMpv).apply();
                    restartResolvedMedia();
                }).setNegativeButton("取消", null).show();
    }

    private void restartResolvedMedia() {
        if (!playbackAddress.isEmpty() && !website && !sniffing) {
            updatePlayback();
            persist();
            openNative(playbackAddress, playbackReferer);
        }
    }

    private void chooseVideoFit() {
        String[] fits = {"自动 · 原比例", "裁切填充", "拉伸填充", "4:3"};
        int[] modes = {AnimeVideoSizing.AUTO, AnimeVideoSizing.COVER, AnimeVideoSizing.STRETCH, AnimeVideoSizing.RATIO_4_3};
        int checked = 0;
        for (int i = 1; i < modes.length; i++) if (videoFit == modes[i]) checked = i;
        int selected = checked;
        if (fullscreen) {
            List<String> labels = new ArrayList<>();
            List<Runnable> actions = new ArrayList<>();
            for (int i = 0; i < modes.length; i++) {
                int mode = modes[i];
                labels.add((i == selected ? "✓  " : "    ") + fits[i]);
                actions.add(() -> setVideoFit(mode));
            }
            playbackOverflow = AnimePlayerOverflow.show(this, stage,
                    aspectButton.isShown() ? aspectButton : moreButton, labels, actions);
            if (playbackOverflow != null) return;
        }
        new AnimePlayerPanel(this).setTitle("画面比例")
                .setSingleChoiceItems(fits, selected, (dialog, option) -> {
                    setVideoFit(modes[option]);
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void setVideoFit(int mode) {
        videoFit = mode;
        playbackOptions.edit().putInt("video_fit", videoFit).apply();
        layoutPlaybackControls();
    }

    private void chooseSkipInterval() {
        int[] intervals = {5, 10, 15, 30, 60};
        String[] items = {"5 秒", "10 秒", "15 秒", "30 秒", "60 秒"};
        int selected = -1;
        for (int i = 0; i < intervals.length; i++) if (intervals[i] == skipSeconds) selected = i;
        new AnimePlayerPanel(this).setTitle("快进与快退")
                .setSingleChoiceItems(items, selected, (dialog, which) -> {
                    skipSeconds = intervals[which];
                    playbackOptions.edit().putInt("skip_seconds", skipSeconds).apply();
                    refreshSkipButtons();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    private void refreshSkipButtons() {
        String seconds = Integer.toString(skipSeconds);
        skipBackNumber.setText(seconds);
        skipBack.setContentDescription("快退" + skipSeconds + "秒");
        skipForwardNumber.setText(seconds);
        skipForward.setContentDescription("快进" + skipSeconds + "秒");
        quickForward.setText("+" + skipSeconds + "s");
        quickForward.setContentDescription("快进" + skipSeconds + "秒，长按调整间隔");
    }

    private void chooseSleepTimer() {
        int[] minutes = {0, 15, 30, 45, 60, 90};
        String[] items = {"关闭定时", "15 分钟", "30 分钟", "45 分钟", "60 分钟", "90 分钟"};
        new AnimePlayerPanel(this).setTitle("定时关闭播放")
                .setItems(items, (dialog, selected) -> {
                    main.removeCallbacks(sleepTimer);
                    long millis = minutes[selected] * 60_000L;
                    sleepAt = millis == 0 ? 0 : SystemClock.elapsedRealtime() + millis;
                    if (millis != 0) main.postDelayed(sleepTimer, millis);
                }).setNegativeButton("取消", null).show();
    }

    private void openExternalPlayer() {
        if (!AnimeWebPlayer.webAddress(playbackAddress)) return;
        Uri address = Uri.parse(playbackAddress);
        Intent launch = new Intent(Intent.ACTION_VIEW).setDataAndType(address, "video/*");
        try {
            startActivity(Intent.createChooser(launch, "选择外部播放器"));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, "设备上没有可接收此视频地址的播放器", Toast.LENGTH_SHORT).show();
        }
    }

    private String downloadExtension() {
        if (demo || !AnimeWebPlayer.webAddress(playbackAddress)) return null;
        String path = Uri.parse(playbackAddress).getPath();
        if (path == null) return null;
        path = path.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".mp4", ".webm", ".mkv", ".mov", ".m4v", ".flv"}) {
            if (path.endsWith(extension)) return extension;
        }
        return null;
    }

    private void enqueueDownload() {
        String extension = downloadExtension();
        if (extension == null) return;
        String item = episode().show.title + " " + episode().title;
        String filename = item.replaceAll("[\\\\/:*?\\x22<>|\\p{Cntrl}]", "_").trim();
        int count = filename.codePointCount(0, filename.length());
        if (count > 50) filename = filename.substring(0, filename.offsetByCodePoints(0, 50)).trim();
        if (filename.isEmpty() || filename.startsWith(".")) filename = "ANON " + filename;
        filename += "-" + System.currentTimeMillis() + extension;
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(playbackAddress))
                    .setTitle(item)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            for (Map.Entry<String, String> header : playbackHeaders.entrySet()) {
                request.addRequestHeader(header.getKey(), header.getValue());
            }
            if (Build.VERSION.SDK_INT >= 29) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
            }
            DownloadManager manager = getSystemService(DownloadManager.class);
            if (manager == null) throw new IllegalStateException("DownloadManager is unavailable");
            manager.enqueue(request);
            Toast.makeText(this, "已加入系统下载", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | IllegalStateException | SecurityException unavailable) {
            Toast.makeText(this, "无法创建下载任务", Toast.LENGTH_SHORT).show();
        }
    }

    private void showVideoDetails() {
        SurfaceView surface = playerSurface();
        if (surface == null || !ready) return;
        try {
            int width = mpv != null ? mpv.width() : media == null ? 0 : media.getVideoWidth();
            int height = mpv != null ? mpv.height() : media == null ? 0 : media.getVideoHeight();
            String dimensions = width > 0 && height > 0 ? "分辨率 " + width + " × " + height + "\n" : "";
            new AnimePlayerPanel(this).setTitle("视频详情").setMessage(dimensions
                            + "时长 " + timestamp(duration)
                            + "\n播放器 " + (mpv == null ? "Android 系统解码" : "mpv")
                            + (mpv == null || anime4k == 0 ? "" : "\nAnime4K " + (anime4k == 1 ? "效率" : "画质")))
                    .setPositiveButton("关闭", null).show();
        } catch (IllegalStateException closed) {
            Toast.makeText(this, "当前视频详情不可用", Toast.LENGTH_SHORT).show();
        }
    }

    private ExecutorService mediaWork() {
        if (mediaWork == null) mediaWork = Executors.newSingleThreadExecutor();
        return mediaWork;
    }

    private void captureFrame() {
        SurfaceView surface = playerSurface();
        if (surface == null || !ready || pendingFrame != null) return;
        Rect crop = mpv == null ? null : mpvContentArea(surface);
        int width = crop == null ? media.getVideoWidth() : crop.width();
        int height = crop == null ? media.getVideoHeight() : crop.height();
        if (width <= 0 || height <= 0) {
            Toast.makeText(this, "视频画面尚未就绪", Toast.LENGTH_SHORT).show();
            return;
        }
        double scale = Math.min(1, 2560.0 / Math.max(width, height));
        Bitmap frame = Bitmap.createBitmap(Math.max(1, (int) (width * scale)),
                Math.max(1, (int) (height * scale)), Bitmap.Config.ARGB_8888);
        try {
            PixelCopy.request(surface, crop, frame, result -> {
                if (isFinishing() || isDestroyed() || result != PixelCopy.SUCCESS || pendingFrame != null) {
                    frame.recycle();
                    if (result != PixelCopy.SUCCESS && !isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, "当前视频画面无法截图", Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                pendingFrame = frame;
                Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png")
                        .putExtra(Intent.EXTRA_TITLE, "ANON-" + System.currentTimeMillis() + ".png");
                try {
                    startActivityForResult(destination, EXPORT_FRAME);
                } catch (ActivityNotFoundException unavailable) {
                    pendingFrame = null;
                    frame.recycle();
                    Toast.makeText(this, "系统文件选择器不可用", Toast.LENGTH_SHORT).show();
                }
            }, main);
        } catch (IllegalArgumentException unavailable) {
            frame.recycle();
            Toast.makeText(this, "当前视频画面无法截图", Toast.LENGTH_SHORT).show();
        }
    }

    private void exportFrame(Bitmap frame, Uri destination) {
        mediaWork().execute(() -> {
            boolean complete;
            try (OutputStream output = getContentResolver().openOutputStream(destination)) {
                complete = output != null && frame.compress(Bitmap.CompressFormat.PNG, 100, output);
            } catch (IOException | SecurityException failed) {
                complete = false;
            } finally {
                frame.recycle();
            }
            String message = complete ? "视频截图已保存" : "截图保存失败";
            main.post(() -> { if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); });
        });
    }

    private void chooseSpeed() {
        if (website) { Toast.makeText(this, "网页播放请使用站点的倍速选项", Toast.LENGTH_SHORT).show(); return; }
        float[] rates = {.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f};
        String[] items = new String[rates.length];
        int selected = 2;
        for (int i = 0; i < rates.length; i++) {
            items[i] = speedLabel(rates[i]);
            if (rates[i] == speed) selected = i;
        }
        new AnimePlayerPanel(this).setTitle("播放倍速").setSingleChoiceItems(items, selected, (dialog, item) -> {
            if (!nativePlaying() || applySpeed(rates[item])) {
                speed = rates[item];
                playbackOptions.edit().putFloat("speed", speed).apply();
            }
            else Toast.makeText(this, "当前视频不支持此倍速", Toast.LENGTH_SHORT).show();
            speedButton.setText(speedLabel(speed));
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private boolean applySpeed(float value) {
        if (mpv != null) {
            mpv.speed(value);
            appliedSpeed = value;
            return true;
        }
        if (media == null) return false;
        try {
            media.setPlaybackParams(media.getPlaybackParams().setSpeed(value).setPitch(1f));
            appliedSpeed = value;
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return false;
        }
    }

    private boolean hasSubtitles() {
        for (MediaPlayer.TrackInfo track : mediaTracks(media)) {
            if (track != null && track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) return true;
        }
        return false;
    }

    private MediaPlayer.TrackInfo[] mediaTracks(MediaPlayer player) {
        if (player == null) return NO_TRACKS;
        try {
            MediaPlayer.TrackInfo[] tracks = player.getTrackInfo();
            return tracks == null ? NO_TRACKS : tracks;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Could not inspect native subtitle tracks", unavailable);
            return NO_TRACKS;
        }
    }

    private int selectedSubtitle(MediaPlayer player) {
        try {
            return player.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE);
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Could not read selected subtitle", unavailable);
            return -1;
        }
    }

    private void chooseSubtitle() {
        MediaPlayer player = media;
        if (player == null) return;
        MediaPlayer.TrackInfo[] tracks = mediaTracks(player);
        if (tracks.length == 0) return;
        List<Integer> indexes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("关闭字幕");
        indexes.add(-1);
        int active = selectedSubtitle(player);
        for (int i = 0; i < tracks.length; i++) {
            if (tracks[i] == null || tracks[i].getTrackType() != MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE) continue;
            indexes.add(i);
            String language = tracks[i].getLanguage();
            names.add(TextUtils.isEmpty(language) || "und".equals(language) ? "轨道 " + (indexes.size() - 1) : language);
        }
        new AnimePlayerPanel(this).setTitle("字幕轨道")
                .setSingleChoiceItems(names.toArray(new String[0]), indexes.indexOf(active), (dialog, item) -> {
                    dialog.dismiss();
                    if (media != player) return;
                    try {
                        if (indexes.get(item) >= 0) player.selectTrack(indexes.get(item));
                        else if (active >= 0) player.deselectTrack(active);
                    } catch (RuntimeException ignored) {
                        Toast.makeText(this, "当前字幕轨道无法切换", Toast.LENGTH_SHORT).show();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private boolean danmakuPlaying() {
        if (inactive || !ready || finished || seeking || resumePreparedSeek) return false;
        return website ? webPlaying && SystemClock.elapsedRealtime() - webSampleAt < 1000 : nativePlaying();
    }

    private long danmakuPosition() {
        if (!ready || seeking || resumePreparedSeek) return -1;
        if (website) {
            if (webSampleAt == 0) return -1;
            long elapsed = Math.min(450, SystemClock.elapsedRealtime() - webSampleAt);
            return webPlaying && !inactive ? position + (long) (elapsed * webRate) : position;
        }
        if (mpv != null) return finished ? position : mpv.position();
        try {
            return video == null ? -1 : finished ? position : video.getCurrentPosition();
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private void updatePlayback() {
        if (!demo && episodes == null) return;
        long now = SystemClock.elapsedRealtime();
        if (resumePreparedSeek && now - seekRequestedAt >= 3000) resumePreparedSeek = false;
        if (mpv != null && ready && !finished && !seeking) {
            long length = mpv.duration();
            long current = mpv.position();
            if (length > 0) duration = (int) Math.min(Integer.MAX_VALUE, length);
            if (resumePreparedSeek && Math.abs(current - position) < 600) resumePreparedSeek = false;
            if (!resumePreparedSeek && current >= 0) position = current;
        } else if (video != null && ready && !finished && !seeking && !resumePreparedSeek) {
            try {
                int length = video.getDuration();
                int current = video.getCurrentPosition();
                // An invisible VideoView can lose its surface and report -1/0.
                if (length > 0) duration = length;
                if (length >= 0 || current > 0) position = Math.max(0, current);
            } catch (IllegalStateException unavailable) {
                Log.d(TAG, "Native timeline temporarily unavailable", unavailable);
            }
        } else if (web != null && website) {
            web.samplePosition();
        }
        refreshVideoDimensions();
        SurfaceView surface = playerSurface();
        if (surface != null && ready && surface.getWidth() > 0 && surface.getHeight() > 0) {
            Rect content = mpv == null ? null : mpvContentArea(surface);
            int left = content == null ? surface.getLeft() : surface.getLeft() + content.left;
            int top = content == null ? surface.getTop() : surface.getTop() + content.top;
            int right = content == null ? surface.getRight() : surface.getLeft() + content.right;
            int bottom = content == null ? surface.getBottom() : surface.getTop() + content.bottom;
            danmaku.view.setVideoBounds(Math.max(0, left), Math.max(0, top),
                    Math.min(stage.getWidth(), right), Math.min(stage.getHeight(), bottom));
        }
        danmaku.view.syncPlayback();
        if (ready && !finished && now - persistAt >= 2500) {
            persistAt = now;
            persist();
        }
        updateControls();
    }

    private boolean canSeek() {
        return ready && playerSurface() != null && duration > 0 && !(finished && mpv != null);
    }

    private void updateTimelineLabels(long millis) {
        String elapsed = timestamp(millis);
        String total = timestamp(duration);
        String text = elapsed + " / " + total;
        if (!TextUtils.equals(positionLabel.getText(), text)) positionLabel.setText(text);
        if (!TextUtils.equals(fullscreenTime.getText(), text)) fullscreenTime.setText(text);
        if (!TextUtils.equals(elapsedTime.getText(), elapsed)) elapsedTime.setText(elapsed);
        if (!TextUtils.equals(totalTime.getText(), total)) totalTime.setText(total);
    }

    private void updateControls() {
        if (controls == null) return;
        boolean playing = nativePlaying();
        boolean nativeReady = playerSurface() != null && ready;
        boolean keepScreenOn = !inactive && (playing || website);
        if (keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        boolean hasNext = !demo && index + 1 < episodes.size();
        next.setEnabled(hasNext);
        next.setAlpha(hasNext ? 1f : .44f);
        dockPlay.setEnabled(nativeReady);
        boolean seekable = canSeek();
        skipBack.setEnabled(seekable);
        skipForward.setEnabled(seekable);
        skipBack.setAlpha(seekable ? 1f : .46f);
        skipForward.setAlpha(seekable ? 1f : .46f);
        playButton.setContentDescription(playing ? "暂停" : "播放");
        playGlyph.show(playing ? "pause" : "play", TITLE);
        dockPlay.setContentDescription(playing ? "暂停" : "播放");
        dockPlayGlyph.show(playing ? "pause" : "play", Color.WHITE);
        String speedText = speedLabel(speed);
        if (!TextUtils.equals(speedButton.getText(), speedText)) speedButton.setText(speedText);
        speedButton.setEnabled(nativeReady);
        seek.setEnabled(seekable);
        if (!seeking || scrubPosition >= 0) {
            long shown = scrubPosition >= 0 ? scrubPosition : position;
            int progress = duration > 0 ? (int) Math.min(1000, 1000 * shown / duration) : 0;
            seek.setProgress(progress);
            restingProgress.setProgress(progress);
            updateTimelineLabels(shown);
        }
        int cached = nativeReady && video != null ? Math.min(100, Math.max(0, video.getBufferPercentage())) : 0;
        seek.setSecondaryProgress(cached * 10);
        status.setVisibility(status.length() == 0 ? View.GONE : View.VISIBLE);
        if (playing && !previouslyPlaying) scheduleImmersiveControls();
        else if (!playing && previouslyPlaying && !shouldPlay) showChrome();
        previouslyPlaying = playing;
        updateChromeVisibility();
    }

    private void persist() {
        if (!demo && !privateViewing && episodes != null && !episodes.isEmpty() && !finished) {
            AnimePlaybackHistory.save(this, episode(), position);
        }
    }

    private void setFull(boolean enabled, boolean rotate) {
        if (fullscreen == enabled) return;
        dismissPlaybackOverflow();
        gestures.reset();
        controlsLocked = false;
        fullscreen = enabled;
        refreshLock();
        showChrome();
        applyBars();
        scheduleImmersiveControls();
        if (rotate) setRequestedOrientation(enabled ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    boolean playerPanelFullscreen() { return fullscreen; }

    void playerPanelOpened() {
        openPanels++;
        main.removeCallbacks(hideControls);
    }

    void playerPanelClosed() {
        openPanels = Math.max(0, openPanels - 1);
        if (!isFinishing() && !isDestroyed()) scheduleImmersiveControls();
    }

    private void toggleControlsLock() {
        if (!fullscreen) return;
        gestures.reset();
        controlsLocked = !controlsLocked;
        refreshLock();
        showChrome();
        scheduleImmersiveControls();
    }

    private void refreshLock() {
        lockGlyph.show(controlsLocked ? "lock" : "unlock", Color.WHITE);
        lockButton.setContentDescription(controlsLocked ? "解锁播放控件" : "锁定播放控件");
    }

    private void refreshPlayerTitle() {
        if (demo) title.setText("本地示例");
        else {
            AnimeEngine.Episode current = episode();
            title.setText(fullscreen ? current.show.title + " · " + current.title : current.show.title);
        }
    }

    @SuppressWarnings("deprecation")
    private void applyBars() {
        boolean hidden = fullscreen || pip;
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams settings = getWindow().getAttributes();
            int cutoutMode = hidden ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            if (settings.layoutInDisplayCutoutMode != cutoutMode) {
                settings.layoutInDisplayCutoutMode = cutoutMode;
                getWindow().setAttributes(settings);
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (hidden) controller.hide(WindowInsets.Type.systemBars());
                else controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(hidden ? View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY : 0);
        }
        episodeDetails.setVisibility(hidden ? View.GONE : View.VISIBLE);
        commentsPanel.setActive(!hidden && !inactive);
        String glyph = fullscreen ? "exit_fullscreen" : "fullscreen";
        String fullscreenLabel = fullscreen ? "退出全屏" : "全屏";
        fullscreenGlyph.show(glyph, Color.WHITE);
        webFullscreenGlyph.show(glyph, Color.WHITE);
        fullButton.setContentDescription(fullscreenLabel);
        webPageFullButton.setContentDescription(fullscreenLabel);
        refreshPlayerTitle();
        header.setBackground(fullscreen ? null : portraitHeaderBackground);
        controls.setBackground(fullscreen ? null : portraitControlsBackground);
        updateDanmakuToggles();
        sizeVideoStage();
        layoutPlaybackControls();
        updateChromeVisibility();
        root.requestApplyInsets();
    }

    private void applyInsets(WindowInsets insets) {
        int left = 0, top = 0, right = 0, bottom = 0;
        int touchLeft = 0, touchTop = 0, touchRight = 0, touchBottom = 0;
        if (!pip && Build.VERSION.SDK_INT >= 30) {
            int cutout = WindowInsets.Type.displayCutout();
            if (fullscreen) {
                android.graphics.Insets safe = insets.getInsets(cutout | WindowInsets.Type.systemBars()
                        | WindowInsets.Type.tappableElement());
                touchLeft = safe.left;
                // The immersive header starts at the window edge. Only the physical display
                // cutout moves it down; transient status bars do not reserve a permanent gap.
                touchTop = insets.getInsets(cutout).top;
                touchRight = safe.right;
                touchBottom = safe.bottom;
            } else {
                android.graphics.Insets safe = insets.getInsets(cutout | WindowInsets.Type.systemBars());
                left = safe.left;
                top = safe.top;
                right = safe.right;
                bottom = safe.bottom;
            }
        } else if (!pip && fullscreen) {
            touchLeft = insets.getSystemWindowInsetLeft();
            touchRight = insets.getSystemWindowInsetRight();
            touchBottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 29) {
                android.graphics.Insets tappable = insets.getTappableElementInsets();
                touchLeft = Math.max(touchLeft, tappable.left);
                touchRight = Math.max(touchRight, tappable.right);
                touchBottom = Math.max(touchBottom, tappable.bottom);
            }
            if (Build.VERSION.SDK_INT >= 28) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                if (cutout != null) {
                    touchLeft = Math.max(touchLeft, cutout.getSafeInsetLeft());
                    touchTop = cutout.getSafeInsetTop();
                    touchRight = Math.max(touchRight, cutout.getSafeInsetRight());
                    touchBottom = Math.max(touchBottom, cutout.getSafeInsetBottom());
                }
            }
        }
        root.setPadding(left, top, right, bottom);
        padVideoChrome(touchLeft, touchTop, touchRight, touchBottom);
        sizeVideoStage();
    }

    private void padVideoChrome(int left, int top, int right, int bottom) {
        chromeInsetLeft = left;
        chromeInsetTop = top;
        chromeInsetRight = right;
        chromeInsetBottom = bottom;
        ViewGroup.LayoutParams headerSize = header.getLayoutParams();
        int headerHeight = dp(fullscreen ? 48 : 49);
        if (headerSize.height != headerHeight) {
            headerSize.height = headerHeight;
            header.setLayoutParams(headerSize);
        }
        layoutPlaybackControls();
        FrameLayout.LayoutParams feedbackSize = (FrameLayout.LayoutParams) gestureFeedback.getLayoutParams();
        if (feedbackSize.topMargin != dp(52) + top) {
            feedbackSize.topMargin = dp(52) + top;
            gestureFeedback.setLayoutParams(feedbackSize);
        }
        webFullscreen.setPadding(left, top, right, bottom);
    }

    private void sizeVideoStage() {
        int width = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int height = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (width <= 0 || height <= 0) return;
        int portraitHeight = Math.min(Math.max(dp(180), width * 9 / 16), height * 2 / 3);
        int desired = fullscreen || pip ? M : portraitHeight;
        ViewGroup.LayoutParams current = stage.getLayoutParams();
        if (current.height != desired) {
            current.height = desired;
            stage.setLayoutParams(current);
        }
    }

    private void toggleChrome() {
        if ((!ready && !controlsLocked) || pip || website) return;
        if (chromeVisible) {
            chromeVisible = false;
            main.removeCallbacks(hideControls);
            updateChromeVisibility();
        } else {
            showChrome();
            scheduleImmersiveControls();
        }
    }

    private void showChrome() {
        chromeVisible = true;
        updateChromeVisibility();
    }

    private void updateChromeVisibility() {
        boolean available = !pip && webFullCallback == null;
        boolean nativeFullscreen = fullscreen && !website && ready;
        chrome.setVisibility(available && !temporaryBoost && (chromeVisible || website) ? View.VISIBLE : View.GONE);
        header.setVisibility(controlsLocked ? View.GONE : View.VISIBLE);
        topScrim.setVisibility(fullscreen && !controlsLocked ? View.VISIBLE : View.GONE);
        bottomScrim.setVisibility(nativeFullscreen && !controlsLocked ? View.VISIBLE : View.GONE);
        controlsDock.setVisibility(!website && ready && !controlsLocked ? View.VISIBLE : View.GONE);
        positionTransport();
        sideControls.setVisibility(nativeFullscreen && !compactFullscreen || fullscreen && controlsLocked
                ? View.VISIBLE : View.GONE);
        screenshotButton.setVisibility(!controlsLocked ? View.VISIBLE : View.GONE);
        fullscreenPip.setVisibility(nativeFullscreen && pipSupported ? View.VISIBLE : View.GONE);
        quickForward.setVisibility(nativeFullscreen && canSeek() ? View.VISIBLE : View.GONE);
        webPageDanmakuToggle.setVisibility(website || nativeFullscreen && compactFullscreen ? View.VISIBLE : View.GONE);
        webPageFullButton.setVisibility(website ? View.VISIBLE : View.GONE);
        gestures.setVisibility(available && !website && (ready || controlsLocked) ? View.VISIBLE : View.GONE);
        restingProgress.setVisibility(available && !website && !fullscreen && !chromeVisible && ready && duration > 0
                ? View.VISIBLE : View.GONE);
    }

    private void scheduleImmersiveControls() {
        main.removeCallbacks(hideControls);
        if (chromeVisible && !pip && !website && !inactive && !temporaryBoost && !seeking
                && openPanels == 0 && (controlsLocked || nativePlaying())) {
            main.postDelayed(hideControls, fullscreen ? 4_000 : 3_000);
        }
    }

    private void hideImmersiveControls() {
        if ((nativePlaying() || controlsLocked) && !pip && !seeking && openPanels == 0 && !chrome.hasFocus()) {
            chromeVisible = false;
            updateChromeVisibility();
        }
    }

    private void openWebFullscreen(View content, WebChromeClient.CustomViewCallback callback) {
        if (webFullCallback != null || content == null || content.getParent() != null) {
            releaseWebFullscreen(callback);
            return;
        }
        dismissPlaybackOverflow();
        try {
            webFullscreen.addView(content, new FrameLayout.LayoutParams(M, M));
        } catch (RuntimeException unavailable) {
            Log.w(TAG, "Could not attach fullscreen web video", unavailable);
            releaseWebFullscreen(callback);
            return;
        }
        webFullCallback = callback;
        danmaku.mount(webFullscreen, 1);
        webDanmakuToggle = createDanmakuToggle();
        FrameLayout.LayoutParams togglePosition = new FrameLayout.LayoutParams(
                dp(48), dp(48), Gravity.TOP | Gravity.END);
        togglePosition.topMargin = dp(8);
        togglePosition.setMarginEnd(dp(8));
        webFullscreen.addView(webDanmakuToggle, togglePosition);
        webFullscreen.setVisibility(View.VISIBLE);
        setFull(true, true);
        applyBars();
    }

    private void closeWebFullscreen() {
        if (webFullCallback == null) return;
        WebChromeClient.CustomViewCallback callback = webFullCallback;
        webFullCallback = null;
        danmaku.mount(stage, stage.indexOfChild(gestures));
        webFullscreen.removeAllViews();
        webDanmakuToggle = null;
        webFullscreen.setVisibility(View.GONE);
        releaseWebFullscreen(callback);
        setFull(false, true);
    }

    private void releaseWebFullscreen(WebChromeClient.CustomViewCallback callback) {
        try {
            callback.onCustomViewHidden();
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Web video fullscreen already closed", unavailable);
        }
    }

    private boolean nativePlaying() {
        if (mpv != null) return ready && mpv.isPlaying();
        try {
            return video != null && ready && video.isPlaying();
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    private void pauseNative() {
        if (mpv != null) {
            mpv.pause();
            return;
        }
        if (video == null) return;
        try {
            video.pause();
        } catch (IllegalStateException unavailable) {
            Log.d(TAG, "Native player already stopped", unavailable);
        }
    }

    private SurfaceView playerSurface() {
        return mpv == null ? video : mpv.view();
    }

    private void refreshVideoDimensions() {
        int width = mpv != null ? mpv.width() : video == null ? 0 : video.sourceWidth();
        int height = mpv != null ? mpv.height() : video == null ? 0 : video.sourceHeight();
        if (width == videoSourceWidth && height == videoSourceHeight) return;
        videoSourceWidth = width;
        videoSourceHeight = height;
        layoutPlaybackControls();
    }

    private FrameLayout.LayoutParams videoSurfaceLayout(int viewportWidth, int viewportHeight) {
        int width = M, height = M;
        if (videoFit == AnimeVideoSizing.RATIO_4_3 && viewportWidth > 0 && viewportHeight > 0) {
            AnimeVideoSizing.Frame frame = AnimeVideoSizing.fitFrame(viewportWidth, viewportHeight, 4, 3);
            width = frame.width();
            height = frame.height();
        }
        return new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
    }

    private void applyVideoFit(int viewportWidth, int viewportHeight) {
        SurfaceView surface = playerSurface();
        if (surface == null) return;
        ViewGroup.LayoutParams layout = surface.getLayoutParams();
        if (layout != null) {
            FrameLayout.LayoutParams target = videoSurfaceLayout(viewportWidth, viewportHeight);
            if (layout.width != target.width || layout.height != target.height) surface.setLayoutParams(target);
        }
        int effective = AnimeVideoSizing.surfaceFit(videoFit);
        if (appliedVideoFit == effective) return;
        appliedVideoFit = effective;
        if (mpv != null) mpv.fit(effective);
        else video.fit(effective);
    }

    private Rect contentFrame(int width, int height, int sourceWidth, int sourceHeight) {
        if (appliedVideoFit != AnimeVideoSizing.FIT) return new Rect(0, 0, width, height);
        AnimeVideoSizing.Frame frame = AnimeVideoSizing.fitFrame(width, height, sourceWidth, sourceHeight);
        return new Rect(frame.left(), frame.top(), frame.right(), frame.bottom());
    }

    private Rect mpvContentArea(SurfaceView surface) {
        return contentFrame(surface.getWidth(), surface.getHeight(), mpv.width(), mpv.height());
    }

    private void enterPip() {
        SurfaceView surface = playerSurface();
        if (surface == null || !ready
                || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return;
        try {
            Rect area = mpv != null ? mpvContentArea(surface) : new Rect(0, 0, surface.getWidth(), surface.getHeight());
            int width = area.width(), height = area.height();
            if (appliedVideoFit == AnimeVideoSizing.FIT && videoSourceWidth > 0 && videoSourceHeight > 0) {
                width = videoSourceWidth;
                height = videoSourceHeight;
            }
            float ratio = height > 0 ? (float) width / height : 0;
            Rational aspect = ratio >= 1 / 2.39f && ratio <= 2.39f
                    ? new Rational(width, height) : new Rational(16, 9);
            PictureInPictureParams params = new PictureInPictureParams.Builder().setAspectRatio(aspect).build();
            if (!enterPictureInPictureMode(params)) {
                Toast.makeText(this, "设备未允许画中画", Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            Toast.makeText(this, "设备未允许画中画", Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean active, Configuration config) {
        super.onPictureInPictureModeChanged(active, config);
        pip = active;
        if (root != null) {
            dismissPlaybackOverflow();
            gestures.reset();
            showChrome();
            applyBars();
            if (!pip) scheduleImmersiveControls();
        }
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (root != null) {
            dismissPlaybackOverflow();
            applyBars();
        }
    }

    @Override protected void onStart() {
        super.onStart();
        if (root == null) return;
        if (sleepAt > 0) {
            main.removeCallbacks(sleepTimer);
            long remaining = sleepAt - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                sleepTimer.run();
                return;
            }
            main.postDelayed(sleepTimer, remaining);
        }
        inactive = false;
        commentsPanel.setActive(!fullscreen && !pip);
        if (web != null) web.resume();
        if (playerSurface() != null && ready && shouldPlay && !finished) startNative();
        scheduleImmersiveControls();
        main.removeCallbacks(tick);
        main.post(tick);
    }

    @Override protected void onStop() {
        dismissPlaybackOverflow();
        if (!pip && root != null) {
            inactive = true;
            commentsPanel.setActive(false);
            gestures.reset();
            main.removeCallbacks(hideControls);
            updatePlayback();
            persist();
            if (nativePlaying()) pauseNative();
            if (web != null) web.pause();
            main.removeCallbacks(tick);
        }
        super.onStop();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (danmaku != null && request == AnimeDanmakuControls.OPEN_XML && result == RESULT_OK) {
            danmaku.acceptDocument(data);
        }
        if (request == EXPORT_FRAME) {
            Bitmap frame = pendingFrame;
            pendingFrame = null;
            if (frame == null) return;
            if (result == RESULT_OK && data != null && data.getData() != null) exportFrame(frame, data.getData());
            else frame.recycle();
        }
    }

    @Override protected void onDestroy() {
        dismissPlaybackOverflow();
        if (root != null) {
            persist();
            closePlayback();
        }
        if (danmaku != null) danmaku.close();
        if (commentsPanel != null) commentsPanel.close();
        if (mediaWork != null) mediaWork.shutdown();
        if (pendingFrame != null) pendingFrame.recycle();
        if (demoFile != null) demoFile.delete();
        main.removeCallbacksAndMessages(null);
        if (isFinishing()) synchronized (SESSIONS) { SESSIONS.remove(token); }
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (dismissPlaybackOverflow()) return;
        if (webFullCallback != null) closeWebFullscreen();
        else if (controlsLocked) toggleControlsLock();
        else if (fullscreen) setFull(false, true);
        else super.onBackPressed();
    }

    private ToggleButton createDanmakuToggle() {
        ToggleButton button = new ToggleButton(this);
        button.setTextOn("弹");
        button.setTextOff("弹");
        button.setContentDescription("弹幕");
        button.setTextSize(16);
        button.setGravity(Gravity.CENTER);
        button.setOnClickListener(v -> {
            danmaku.toggleVisibility();
            scheduleImmersiveControls();
        });
        styleDanmakuToggle(button, danmaku.isVisible());
        return button;
    }

    private void updateDanmakuToggles() {
        boolean visible = danmaku.isVisible();
        styleDanmakuToggle(danmakuToggle, visible);
        styleDanmakuToggle(webPageDanmakuToggle, visible);
        if (webDanmakuToggle != null) styleDanmakuToggle(webDanmakuToggle, visible);
        refreshDanmakuActions(fullscreen && !compactFullscreen);
    }

    private void refreshDanmakuActions(boolean wideFullscreen) {
        danmakuSettings.setVisibility(wideFullscreen && danmaku.isVisible() ? View.VISIBLE : View.GONE);
    }

    private void styleDanmakuToggle(ToggleButton button, boolean visible) {
        button.setChecked(visible);
        boolean floating = fullscreen && button != webDanmakuToggle;
        button.setTextColor(floating ? (visible ? ACCENT : 0xffdedfe4) : (visible ? INK : SUBTITLE));
        button.setContentDescription(visible ? "关闭弹幕" : "打开弹幕");
        button.setBackground(new InsetDrawable(
                PlayerViews.button(this, floating ? Color.TRANSPARENT : visible ? ACCENT : PANEL, 9,
                        floating ? 0 : visible ? 0 : STROKE),
                dp(6), dp(8), dp(6), dp(8)));
        button.setPadding(0, 0, 0, 0);
    }

    private TextView action(String value, View.OnClickListener click) {
        TextView button = text(value, 13, TITLE);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(44));
        button.setBackground(PlayerViews.button(this, PANEL, 12, 0));
        button.setOnClickListener(click);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void oneLine(TextView button) {
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
    }

    private void pad(View view, int left, int top, int right, int bottom) {
        view.setPadding(dp(left), dp(top), dp(right), dp(bottom));
    }

    private LinearLayout.LayoutParams size(int width, int height) {
        return new LinearLayout.LayoutParams(width < 0 ? width : dp(width), height < 0 ? height : dp(height));
    }

    private LinearLayout.LayoutParams weighted(int height) {
        return new LinearLayout.LayoutParams(0, height < 0 ? height : dp(height), 1);
    }

    private int dp(int value) { return PlayerViews.dp(this, value); }

    private static String speedLabel(float value) { return (value == 1f ? "1" : Float.toString(value)) + "×"; }

    private static String timestamp(long value) {
        long seconds = Math.max(0, value) / 1000;
        return seconds < 3600 ? String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
                : String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }
}
