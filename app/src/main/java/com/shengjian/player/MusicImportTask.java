package com.shengjian.player;

import java.io.InterruptedIOException;
import java.net.HttpURLConnection;

final class MusicImportTask {
    record Progress(String label, int item, int totalItems, long bytes, long totalBytes) {}
    private static final ThreadLocal<MusicImportTask> RUNNING = new ThreadLocal<>();

    private volatile boolean cancelled;
    private volatile Progress progress;
    private HttpURLConnection connection;
    private long lastUpdate;

    MusicImportTask(String label) { progress = new Progress(label, 0, 0, 0, 0); }

    static MusicImportTask current() { return RUNNING.get(); }
    void enter() { RUNNING.set(this); }
    void exit() { RUNNING.remove(); }

    boolean cancelled() { return cancelled; }
    Progress progress() { return progress; }
    void cancel() {
        HttpURLConnection active;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            active = connection;
        }
        if (active != null) {
            Thread closer = new Thread(active::disconnect, "music-import-cancel");
            closer.setDaemon(true);
            closer.start();
        }
    }

    synchronized void watch(HttpURLConnection request) throws InterruptedIOException {
        check();
        connection = request;
    }

    synchronized void release(HttpURLConnection request) {
        if (connection == request) connection = null;
    }

    void check() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
    }

    void begin(String label, int item, int totalItems) {
        progress = new Progress(label, item, totalItems, 0, 0);
        lastUpdate = 0;
    }

    void transferred(long bytes, long total) {
        long now = System.nanoTime();
        if (lastUpdate != 0 && now - lastUpdate < 120_000_000 && bytes != total) return;
        Progress prior = progress;
        progress = new Progress(prior.label, prior.item, prior.totalItems, bytes, total);
        lastUpdate = now;
    }
}
