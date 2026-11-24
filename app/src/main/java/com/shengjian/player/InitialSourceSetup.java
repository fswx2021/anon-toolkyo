package com.shengjian.player;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.AndroidViewModel;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Keeps the one-time source setup and its progress across Activity recreation. */
public final class InitialSourceSetup extends AndroidViewModel {
    enum Module {
        MUSIC("音乐"), COMICS("漫画"), NOVELS("小说"), ANIME("动画");

        final String label;
        Module(String label) { this.label = label; }
    }

    private record Status(String message, boolean working, boolean retry) {}

    final MusicSourceCatalog.Store music;
    final AnimeSource.Store anime;
    private final File files;
    private final ReaderSources readers;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newFixedThreadPool(Module.values().length);
    private final EnumMap<Module, Status> status = new EnumMap<>(Module.class);
    private Runnable listener;
    private boolean cleared;

    public InitialSourceSetup(Application app) {
        super(app);
        files = app.getFilesDir();
        music = new MusicSourceCatalog.Store(new File(files, "music-sources.json"));
        anime = new AnimeSource.Store(new File(files, "anime-sources.json"));
        readers = AndroidReaderSources.get(app);
    }

    boolean sourcesAreInitial() {
        return !new File(files, "music-sources.json").exists()
                && !new File(files, "reader-sources.json").exists()
                && !new File(files, "anime-sources.json").exists()
                && getApplication().getSharedPreferences("anime_feature_summary", 0).getInt("ruleCount", 0) == 0
                && readers.list(ReaderSources.COMIC).isEmpty()
                && readers.list(ReaderSources.NOVEL).isEmpty()
                && anime.list().isEmpty();
    }

    void listen(Runnable callback) { listener = callback; }

    boolean started() { return !status.isEmpty(); }

    boolean working() { return status.values().stream().anyMatch(Status::working); }

    boolean needsRetry() { return status.values().stream().anyMatch(Status::retry); }

    void prepare() {
        if (!working() && !needsRetry()) status.clear();
    }

    String message(Module module) {
        Status value = status.get(module);
        return value == null ? "待聚合" : value.message();
    }

    void start() {
        if (cleared || working()) return;
        for (Module module : Module.values()) {
            Status previous = status.get(module);
            if (previous == null || previous.retry()) status.put(module, new Status("正在读取来源…", true, false));
        }
        for (Module module : Module.values()) {
            if (status.get(module).working()) workers.execute(() -> run(module));
        }
        changed();
    }

    private void run(Module module) {
        Status result;
        try {
            result = switch (module) {
                case MUSIC -> loadMusic();
                case COMICS -> loadReader(module, ReaderSources.COMIC);
                case NOVELS -> loadReader(module, ReaderSources.NOVEL);
                case ANIME -> loadAnime();
            };
        } catch (IOException | RuntimeException error) {
            result = new Status("聚合未完成，可重试", false, true);
        }
        publish(module, result);
    }

    private Status loadMusic() throws IOException {
        MusicSourceCatalog.AggregationResult result = music.aggregate();
        boolean navidromeConfigured;
        try {
            MusicOnlineEngine.validateConfig(MusicOnlineAccounts.get(getApplication()));
            navidromeConfigured = true;
        } catch (IOException missingConfig) { navidromeConfigured = false; }
        return summary(music.enableSearchable(navidromeConfigured), result.failed(), "个仓库未读到");
    }

    private Status loadReader(Module module, int kind) throws IOException {
        ReaderAggregation.Result result = ReaderAggregation.run(readers, kind, progress -> progress(module, progress));
        readers.setAllEnabled(kind, true);
        int failures = (int) result.reports.stream().filter(report -> !report.success).count();
        return summary(readers.list(kind).size(), failures, "个列表未读到");
    }

    private Status loadAnime() throws IOException {
        AnimeSource.AggregationResult result = anime.aggregateAndEnableOfficial(progress -> progress(Module.ANIME, progress));
        return summary(anime.list().size(), result.failed, "条规则未读到");
    }

    private static Status summary(int enabled, int failures, String failureUnit) {
        if (enabled == 0 && failures == 0) return new Status("未找到可启用的来源", false, true);
        String message = "已启用 " + enabled + " 个";
        if (failures > 0) message += " · " + failures + " " + failureUnit;
        return new Status(message, false, failures > 0);
    }

    private void progress(Module module, String message) {
        publish(module, new Status(message, true, false));
    }

    private void publish(Module module, Status value) {
        main.post(() -> {
            if (cleared) return;
            status.put(module, value);
            changed();
        });
    }

    private void changed() { if (listener != null) listener.run(); }

    @Override protected void onCleared() {
        cleared = true;
        listener = null;
        workers.shutdownNow();
        main.removeCallbacksAndMessages(null);
    }
}
