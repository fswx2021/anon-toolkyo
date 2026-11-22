package com.shengjian.player;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Discovers published rule collections, then commits new sources together. */
final class ReaderAggregation {
    private static final String YCK = "https://www.yckceo.com/";
    private static final String AOAOSTAR = "https://legado.aoaostar.com/sources/";
    private static final String LUANMUC =
            "https://raw.githubusercontent.com/luanmuc/legado-book-sources/main/books.json";
    private static final String YCK_RECENT_NOVELS = YCK + "yuedu/shuyuans/content/id/1333.html";
    private static final String XIU2 = AOAOSTAR + "71e56d4f.json";
    private static final String AOAOSTAR_ALL = AOAOSTAR + "b778fe6b.json";
    private static final ReentrantLock RUN_LOCK = new ReentrantLock();

    static final class Publisher {
        final String name;
        final String[] feeds;
        final String directory;
        final int pages;
        final int collections;
        final boolean singles;

        Publisher(String name, String... feeds) {
            this(name, "", 0, 0, false, feeds);
        }

        private Publisher(String name, String directory, int pages, int collections, boolean singles,
                          String... feeds) {
            this.name = name;
            this.feeds = feeds;
            this.directory = directory;
            this.pages = pages;
            this.collections = collections;
            this.singles = singles;
        }
    }

    static final class Report {
        final String name;
        final String detail;
        final boolean success;

        Report(String name, String detail, boolean success) {
            this.name = name;
            this.detail = detail;
            this.success = success;
        }
    }

    static final class Result {
        final ReaderSources.ImportResult changes;
        final List<Report> reports;
        final int newlyEnabled;
        final boolean limited;

        Result(ReaderSources.AggregateChanges result, List<Report> reports) {
            changes = result.changes;
            newlyEnabled = result.newlyEnabled;
            limited = result.limited;
            this.reports = Collections.unmodifiableList(new ArrayList<>(reports));
        }
    }

    private static List<Publisher> publishers(int kind) {
        if (kind == ReaderSources.COMIC) {
            return Arrays.asList(
                    new Publisher("异次元图源合集", "yiciyuan/tuyuans", 1, 10, false,
                            YCK + "yiciyuan/tuyuans/content/id/60.html"),
                    new Publisher("异次元单图源目录", "yiciyuan/tuyuan", 10, 0, true),
                    new Publisher("阅读源仓库 · 漫画", YCK_RECENT_NOVELS),
                    new Publisher("LuAnMuC · 漫画", LUANMUC),
                    new Publisher("AOAOSTAR · 漫画", AOAOSTAR_ALL));
        }
        return Arrays.asList(
                new Publisher("AOAOSTAR · 精选", XIU2, AOAOSTAR + "e3e5d620.json",
                        AOAOSTAR + "4dc410d1.json"),
                new Publisher("阅读源仓库 · 近期合集", "yuedu/shuyuans", 1, 5, false,
                        YCK_RECENT_NOVELS),
                new Publisher("LuAnMuC", LUANMUC),
                new Publisher("AOAOSTAR · 全量", AOAOSTAR_ALL),
                new Publisher("cjj200011222",
                        "https://raw.githubusercontent.com/cjj200011222/legado-booksource/master/all.json"),
                new Publisher("speauty",
                        "https://raw.githubusercontent.com/speauty/legado.sources/main/subscriptions/active.json"));
    }

    static Result run(ReaderSources sources, int kind, Consumer<String> progress) throws IOException {
        return run(sources, kind, progress, publishers(kind));
    }

    static Result run(ReaderSources sources, int kind, Consumer<String> progress,
                      List<Publisher> publishers) throws IOException {
        if (kind != ReaderSources.COMIC && kind != ReaderSources.NOVEL) throw new IOException("未知书源类型");
        Consumer<String> status = progress == null ? ignored -> {} : progress;
        if (!RUN_LOCK.tryLock()) {
            status.accept("等待另一项聚合完成…");
            try {
                RUN_LOCK.lockInterruptibly();
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new IOException("聚合已取消", cancelled);
            }
        }
        try {
            return collect(sources, kind, status, publishers);
        } finally {
            RUN_LOCK.unlock();
        }
    }

    private static Result collect(ReaderSources sources, int kind, Consumer<String> status,
                                  List<Publisher> publishers) throws IOException {
        Collector collector = new Collector(sources.list(kind));
        List<Report> reports = new ArrayList<>();
        for (int i = 0; i < publishers.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("聚合已取消");
            Publisher publisher = publishers.get(i);
            String label = (i + 1) + "/" + publishers.size() + " · " + publisher.name;
            status.accept("正在读取 " + label);
            reports.add(collectPublisher(sources, kind, publisher, collector, status, label));
        }
        status.accept("正在合并并保存");
        return new Result(sources.addMissing(new ArrayList<>(collector.candidates.values()),
                kind, collector.skipped), reports);
    }

    private static Report collectPublisher(ReaderSources sources, int kind, Publisher publisher,
                                           Collector collector, Consumer<String> status, String label)
            throws IOException {
        Batch batch = new Batch();
        Set<String> feeds = new HashSet<>();
        if (!publisher.directory.isEmpty()) {
            try {
                ReaderRepositoryIndex.Result index = ReaderRepositoryIndex.scan(
                        YCK + publisher.directory + "/index.html", publisher.directory, publisher.pages);
                batch.pages = index.pages;
                batch.partial = index.truncated;
                if (publisher.singles) {
                    collectSingles(sources, kind, index.entries, collector, batch, status, label);
                } else {
                    for (int i = 0; i < index.entries.size() && i < publisher.collections; i++) {
                        String feed = index.entries.get(i).detailUrl;
                        if (feeds.add(feed)) collectFeed(sources, kind, collector, batch, feed);
                    }
                }
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                batch.partial = true;
                batch.error = friendlyFailure(failure);
            }
        }
        for (String feed : publisher.feeds) {
            if (feeds.add(feed)) collectFeed(sources, kind, collector, batch, feed);
        }
        return batch.report(publisher.name);
    }

    private static void collectFeed(ReaderSources sources, int kind, Collector collector, Batch batch,
                                    String feed) throws IOException {
        try {
            batch.record(sources.prepareAggregateUrl(feed, kind), collector);
        } catch (IOException | RuntimeException failure) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("聚合已取消", failure);
            batch.failed++;
            batch.error = friendlyFailure(failure);
        }
    }

    private static void collectSingles(ReaderSources sources, int kind,
                                       List<ReaderRepositoryIndex.Entry> entries, Collector collector,
                                       Batch batch, Consumer<String> status, String label) throws IOException {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (ReaderRepositoryIndex.Entry entry : entries) {
            String key = entry.address.isEmpty() ? entry.detailUrl : ReaderSources.aggregationKey(kind, entry.address);
            if (collector.keys.contains(key)) {
                batch.known++;
                continue;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry.detailUrl);
        }
        collector.skipped += batch.known;
        if (groups.isEmpty()) return;
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<ReaderSources.ImportPlan>> jobs = new ArrayList<>();
            AtomicInteger done = new AtomicInteger();
            for (List<String> alternatives : groups.values()) {
                jobs.add(() -> {
                    try {
                        return firstCompatible(sources, kind, alternatives);
                    } finally {
                        int count = done.incrementAndGet();
                        if (!Thread.currentThread().isInterrupted() && (count % 12 == 0 || count == groups.size())) {
                            status.accept("正在读取 " + label + " · " + count + "/" + groups.size());
                        }
                    }
                });
            }
            for (Future<ReaderSources.ImportPlan> future : workers.invokeAll(jobs, 90, TimeUnit.SECONDS)) {
                if (future.isCancelled()) {
                    batch.partial = true;
                    continue;
                }
                try {
                    batch.record(future.get(), collector);
                } catch (ExecutionException failure) {
                    batch.failed++;
                    batch.error = friendlyFailure(failure.getCause());
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IOException("聚合已取消", cancelled);
        } finally {
            workers.shutdownNow();
        }
    }

    private static ReaderSources.ImportPlan firstCompatible(ReaderSources sources, int kind,
                                                            List<String> alternatives) throws IOException {
        ReaderSources.ImportPlan last = null;
        IOException failed = null;
        for (String link : alternatives) {
            try {
                last = sources.prepareAggregateUrl(link, kind);
                if (!last.sources.isEmpty()) return last;
            } catch (IOException failure) {
                if (Thread.currentThread().isInterrupted()) throw failure;
                failed = failure;
            }
        }
        if (last != null) return last;
        throw failed == null ? new IOException("没有可读取的图源") : failed;
    }

    private static String friendlyFailure(Throwable error) {
        String message = error == null ? null : error.getMessage();
        if (message == null || message.isEmpty()) return "仓库暂时无法读取";
        if (message.matches("^[\\x00-\\x7f]+$")) return "仓库连接失败或响应无效";
        return message.length() > 52 ? message.substring(0, 52) + "…" : message;
    }

    private static final class Collector {
        final Set<String> keys = new HashSet<>();
        final Map<String, ReaderSources.Source> candidates = new LinkedHashMap<>();
        int skipped;

        Collector(List<ReaderSources.Source> existing) {
            for (ReaderSources.Source source : existing) keys.add(ReaderSources.aggregationKey(source.kind, source.url));
        }

        void add(ReaderSources.ImportPlan plan) {
            skipped += plan.skipped;
            for (ReaderSources.Source source : plan.sources) {
                String key = ReaderSources.aggregationKey(source.kind, source.url);
                if (keys.add(key)) candidates.put(key, source);
                else skipped++;
            }
        }
    }

    private static final class Batch {
        int pages, okay, known, failed, accepted, incompatible, encrypted;
        boolean partial;
        String error = "";

        void record(ReaderSources.ImportPlan plan, Collector collector) {
            okay++;
            accepted += plan.sources.size();
            incompatible += plan.skipped;
            encrypted += plan.encrypted;
            collector.add(plan);
        }

        Report report(String name) {
            boolean success = okay > 0 || known > 0;
            if (!success) return new Report(name, error.isEmpty() ? "暂无可读取的条目" : error, false);
            String detail = (pages > 0 ? "已扫描 " + pages + " 页 · " : "") + "识别 " + accepted + " 条";
            if (known > 0) detail += " · 已收录 " + known;
            if (incompatible > 0) detail += " · 跳过 " + incompatible;
            if (encrypted > 0) detail += "（其中规则加密 " + encrypted + " 条）";
            if (failed > 0) detail += " · " + failed + " 项读取失败";
            if (partial) detail += " · 部分目录暂未读取";
            return new Report(name, detail, true);
        }
    }
}
