package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline checks against the same source store and rule engine used by the Android app. */
public final class ReaderVerification {
    private ReaderVerification() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--android-regex")) {
            printAndroidRegex();
            return;
        }
        Path working = Files.createTempDirectory("shengjian-reader-verify-");
        try {
            Path catalog = working.resolve("sources.json");
            ReaderSources store = new ReaderSources(catalog.toFile(), ReaderVerification::resource);
            String novel = sample("novel-source.json");
            String comic = sample("comic-source.json");
            String mixed = new JSONArray().put(new JSONObject(novel)).put(new JSONObject(comic)).put(21).toString();

            ReaderSources.ImportPlan plan = store.prepareText(mixed, ReaderSources.ALL);
            require(plan.added == 2 && plan.skipped == 1 && !plan.diagnostics.isEmpty(), "mixed import preview");
            require(!Files.exists(catalog), "preview must not write");
            require(store.apply(plan).added == 2, "mixed import commit");
            ReaderSources.Source novelSource = store.list(ReaderSources.NOVEL).get(0);
            ReaderSources.Source comicSource = store.list(ReaderSources.COMIC).get(0);
            store.setEnabled(novelSource, false);
            require(store.importStream(new ByteArrayInputStream(backup(novel)), ReaderSources.ALL).updated == 1,
                    "Legado backup ZIP");
            require(!store.list(ReaderSources.NOVEL).get(0).enabled, "reimport preserves source toggle");
            require(store.prepareStream(new ByteArrayInputStream(gzip(novel)), ReaderSources.ALL).updated == 1,
                    "GZIP source");
            require(store.prepareText(compressed(comic), ReaderSources.ALL).updated == 1,
                    "Yiciyuan compressed collection");
            require(new ReaderSources(catalog.toFile(), ReaderVerification::resource).list(ReaderSources.COMIC).size() == 1,
                    "source reload");
            System.out.println("PASS preview, selected-kind detection, ZIP/GZIP/collection, merge and reload");

            List<ReaderEngine.Chapter> prose = readSample(novelSource);
            require(prose.size() == 2 && ReaderEngine.open(prose.get(0)).text.contains("唱针")
                    && ReaderEngine.open(prose.get(1)).text.contains("电话亭"), "Legado novel preview");
            List<ReaderEngine.Chapter> manga = readSample(comicSource);
            ReaderEngine.Page artwork = ReaderEngine.open(manga.get(0));
            require(artwork.images.size() == 1, "Yiciyuan script extracts image");
            byte[] picture = ReaderSources.fetchImage(ReaderEngine.imageAddress(artwork.images.get(0)), comicSource.headers());
            require(ImageIO.read(new ByteArrayInputStream(picture)) != null, "desktop WebP preview");
            System.out.println("PASS novel chapters, Yiciyuan JavaScript chapter and desktop WebP");

            Path offline = working.resolve("offline");
            verifyOffline(offline, prose, manga.get(0), artwork, picture);
            ReaderLocalVerification.verify(working.resolve("local-library"));
            verifyHttp(store, offline);
            ReaderRegexVerification.verify(working.resolve("regex-rules.json"));
            ReaderDomVerification.verify(working.resolve("dom-selectors.json"));
            ReaderDomVerification.verifySessions(working.resolve("source-sessions.json"));
            ReaderUrlVerification.verify(working.resolve("request-rules.json"));
            ReaderYiciyuanVerification.verify(working.resolve("manga-legacy-rules.json"));
            verifyPpcat(working.resolve("ppcat-rules.json"));
            verifyAggregation(working.resolve("aggregated.json"));
            verifySourceDeduplication(working.resolve("unique-names.json"), working.resolve("legacy-aliases.json"));
            verifySearchQueue();
            ReaderSearchVerification.verify(working.resolve("slow-search.json"));
            ReaderSearchVerification.verifyFailures(working.resolve("search-failures.json"));
            ReaderSearchVerification.verifyDefaultTimeoutRecovery(working.resolve("default-timeout-recovery.json"));
            ReaderSearchVerification.verifyParallelSources(working.resolve("search-health.json"));
            ReaderSearchVerification.verifyRelevance();
            ReaderSearchFlowVerification.verify(working.resolve("search-flow.json"));
            ReaderDiagnosticsVerification.verify(working.resolve("source-diagnostics.json"));
            AnimeVerification.verify(working.resolve("anime-sources.json"));
            verifyMusicImportRules(working.resolve("embedded-lyrics.mp3"));
            MusicVerification.verify();
            BackupVerification.verify(working.resolve("backup"));
            AnimeScheduleVerification.verify();
            AnimeDanmakuVerification.verify();
            for (String argument : args) {
                ReaderSources.ImportPlan external = store.prepareFile(Path.of(argument).toFile(), ReaderSources.ALL);
                require(!external.sources.isEmpty(), "external source has no importable entries");
                System.out.printf("PASS external import preview: %d accepted, %d skipped%n",
                        external.sources.size(), external.skipped);
            }
        } finally {
            try (var files = Files.walk(working)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void verifyPpcat(Path catalog) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ppcat/search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            require(exchange.getRequestMethod().equals("POST") && "ppcat".equals(exchange.getRequestHeaders().getFirst("X-Rule")),
                    "PPCat request modifiers");
            require("雨\"天".equals(new JSONObject(body).getString("keyword"))
                    && new JSONObject(body).getInt("offset") == 1, "PPCat JSON template");
            reply(exchange, "{\"data\":{\"items\":[{\"title\":\"雨天\",\"url\":\"/ppcat/book\"}]}}", "application/json");
        });
        server.createContext("/ppcat/book", exchange -> reply(exchange,
                "<h1>雨天 完整版</h1><a class='catalog' href='/ppcat/toc'>目录</a>", "text/html"));
        server.createContext("/ppcat/toc", exchange -> reply(exchange,
                "{\"data\":{\"items\":[{\"caption\":\"第一话\",\"url\":\"/ppcat/episode\"}]}}", "application/json"));
        server.createContext("/ppcat/episode", exchange -> reply(exchange,
                "<img src='/images/001.png'><img src='/images/002.png'>", "text/html"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JSONObject export = new JSONObject().put("format", "ppcat").put("bookSourceName", "皮皮喵规则测试")
                    .put("bookSourceUrl", base + "/ppcat/").put("bookSourceType", "漫画")
                    .put("titleSuffix", " 漫画")
                    .put("ruleSearchUrl", "/ppcat/search@{\"keyword\":\"{{key}}\",\"offset\":{{page-1}}}"
                            + "@Header:{\"X-Rule\":\"ppcat\"}@PostJson")
                    .put("ruleSearchInit", "@js:JSON.parse(resResult).data")
                    .put("ruleSearchList", "$.items.*")
                    .put("ruleSearchName", "$.title@js:result + getrule().titleSuffix")
                    .put("ruleSearchNoteUrl", "$.url")
                    .put("ruleBookName", "h1@text")
                    .put("ruleChapterUrl", "a.catalog@href")
                    .put("ruleChapterInit", "$.data")
                    .put("ruleChapterList", "$.items.*").put("ruleChapterName", "$.caption")
                    .put("ruleContentUrl", "$.url").put("ruleBookContent", "img@src");
            JSONObject sourceBackup = new JSONObject().put("rules", new JSONArray().put(export));
            ReaderSources store = new ReaderSources(catalog.toFile(), ReaderVerification::resource);
            ReaderSources.Source source = store.importText(sourceBackup.toString(), ReaderSources.COMIC).sources.get(0);
            require(source.ppcatModern(), "PPCat format selection");
            List<ReaderEngine.Book> books = ReaderEngine.search(source, "雨\"天", 2);
            require(books.size() == 1 && "雨天 漫画".equals(books.get(0).title), "PPCat search and script scope");
            ReaderEngine.Book book = ReaderEngine.details(books.get(0));
            require("雨天 完整版".equals(book.title), "PPCat HTML detail rules");
            List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(book);
            require(chapters.size() == 1 && "第一话".equals(chapters.get(0).title), "PPCat TOC preprocessing");
            require(ReaderEngine.open(chapters.get(0)).images.equals(List.of(base + "/images/001.png", base + "/images/002.png")),
                    "PPCat image rules");
            System.out.println("PASS PPCat export, JSON POST, scripts, detail, chapters and images");
        } finally {
            server.stop(0);
        }
    }

    private static void printAndroidRegex() throws ReflectiveOperationException {
        for (Class<?> type : List.of(ReaderEngine.class, ReaderRuleSupport.class, ReaderSources.class, ReaderSourceDiagnostics.class,
                ReaderRepositoryIndex.class, ReaderWorkMatcher.class, ReaderLocalBooks.class,
                YiciyuanRules.class, Lyrics.class, LinkResolver.class, AnimeSource.class, AnimeEngine.class,
                AnimeDanmaku.class)) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() != Pattern.class || !Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Pattern pattern = (Pattern) field.get(null);
                String encoded = Base64.getEncoder().encodeToString(pattern.pattern().getBytes(StandardCharsets.UTF_8));
                System.out.println(type.getSimpleName() + "." + field.getName() + "\t" + pattern.flags() + "\t" + encoded);
            }
        }
    }

    private static void verifyMusicImportRules(Path taggedAudio) throws Exception {
        LinkResolver.Result link = LinkResolver.resolve("分享音频 https://audio.example.invalid/demo.m4a?ref=1。");
        require(link.url.equals("https://audio.example.invalid/demo.m4a?ref=1")
                && link.title.equals("demo") && link.extension.equals("m4a"), "media URL in shared text");

        String embedded = "[00:01.00]内嵌歌词";
        byte[] text = embedded.getBytes(StandardCharsets.UTF_8);
        int payloadSize = 5 + text.length;
        ByteBuffer tag = ByteBuffer.allocate(20 + payloadSize);
        // The short fixture's tag and frame sizes each fit in one ID3v2.4 syncsafe byte.
        tag.put(new byte[]{'I', 'D', '3', 4, 0, 0}).putInt(10 + payloadSize);
        tag.put(new byte[]{'U', 'S', 'L', 'T'}).putInt(payloadSize).putShort((short) 0);
        tag.put(new byte[]{3, 'z', 'h', 'o', 0}).put(text);
        Files.write(taggedAudio, tag.array());
        require(Lyrics.embeddedMp3(taggedAudio.toFile()).equals(embedded), "embedded lyric on imported file");

        List<Lyrics.Line> captions = Lyrics.parse("1\n00:00:01,500 --> 00:00:02,750\n{\\an8}<b>字幕测试</b>\n");
        require(captions.size() == 1 && captions.get(0).text.equals("字幕测试")
                && captions.get(0).timeMs == 1500 && captions.get(0).endMs == 2750,
                "caption braces and HTML markup");
        System.out.println("PASS shared media URL, embedded lyrics and caption markup");
    }

    private static void verifySearchQueue() throws InterruptedException {
        try (ReaderSearchQueue queue = new ReaderSearchQueue(8)) {
            List<Integer> targets = IntStream.range(0, 1600).boxed().toList();
            CountDownLatch oldWorkers = new CountDownLatch(8);
            CountDownLatch hold = new CountDownLatch(1);
            AtomicInteger oldStarts = new AtomicInteger();
            AtomicInteger oldResults = new AtomicInteger();
            ReaderSearchQueue.Run old = queue.start(targets, source -> {
                oldStarts.incrementAndGet();
                oldWorkers.countDown();
                hold.await();
                return source;
            }, (source, result, error) -> oldResults.incrementAndGet());
            require(oldWorkers.await(5, TimeUnit.SECONDS), "all enabled search workers enter");
            old.cancel();

            CountDownLatch finished = new CountDownLatch(targets.size());
            AtomicInteger failures = new AtomicInteger();
            queue.start(targets, source -> {
                if (source % 16 == 0) throw new IOException("fixture source failed");
                return source;
            }, (source, result, error) -> {
                if (error != null) failures.incrementAndGet();
                finished.countDown();
            });
            require(finished.await(5, TimeUnit.SECONDS) && failures.get() == 100,
                    "full search reports all requested targets including failures");
            require(oldStarts.get() == 8 && oldResults.get() == 0,
                    "stopping drops queued targets and hides canceled callbacks");
        }
        System.out.println("PASS bounded search, cancellation, full progress and per-source errors");
    }

    private static void verifyOffline(Path directory, List<ReaderEngine.Chapter> prose,
                                      ReaderEngine.Chapter manga, ReaderEngine.Page artwork, byte[] picture)
            throws IOException {
        ReaderEngine.Chapter novel = prose.get(0);
        ReaderCache cache = new ReaderCache(directory.toFile());
        cache.storeCatalogue(novel.book, prose);
        cache.download(novel, null, null);

        String firstImage = artwork.images.get(0);
        ReaderEngine.Page incomplete = new ReaderEngine.Page("", List.of(firstImage, "missing"), artwork.url);
        try {
            cache.download(manga, incomplete, (image, referer) -> {
                if (image.equals("missing")) throw new IOException("fixture download failed");
                return picture;
            });
            throw new AssertionError("failed image must abort the chapter download");
        } catch (IOException expected) {
            require(!cache.isDownloaded(manga), "failed image must leave no incomplete chapter");
        }
        cache.download(manga, artwork, (image, referer) -> picture);

        ReaderCache reopened = new ReaderCache(directory.toFile());
        List<ReaderEngine.Chapter> restored = reopened.loadCatalogue(novel.book);
        require(restored != null && restored.size() == 2 && restored.get(0).url.equals(novel.url)
                && restored.get(0).nextChapterUrl.equals(prose.get(1).url), "offline catalogue resumes chapter");
        ReaderEngine.Page downloadedProse = reopened.loadChapter(restored.get(0));
        require(downloadedProse != null && downloadedProse.text.contains("唱针"), "offline novel after recreation");
        List<ReaderEngine.Chapter> restoredManga = reopened.loadCatalogue(manga.book);
        require(restoredManga != null && restoredManga.size() == 1 && restoredManga.get(0).url.equals(manga.url),
                "downloaded catalogue fallback");
        ReaderEngine.Page downloadedManga = reopened.loadChapter(restoredManga.get(0));
        require(downloadedManga != null && downloadedManga.images.equals(artwork.images), "offline manga manifest");
        byte[] offlineImage = reopened.readImage(manga, manga.book.source, firstImage, "unavailable", Map.of());
        require(Arrays.equals(offlineImage, picture), "offline image must not need a request");
        System.out.println("PASS offline chapter rollback, catalogue recovery and saved novel and manga after recreation");
    }

    private static void verifyHttp(ReaderSources store, Path offline) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        JSONObject source = new JSONObject()
                .put("bookSourceName", "本地验证")
                .put("bookSourceUrl", base)
                .put("bookSourceType", 0)
                .put("searchUrl", "/search?keyword={{key}}")
                .put("ruleSearch", new JSONObject().put("bookList", "$.items[?(@.available == true)]")
                        .put("name", "$.title").put("bookUrl", "$.url"))
                .put("ruleBookInfo", new JSONObject().put("tocUrl", "@css:#contents@href"))
                .put("ruleToc", new JSONObject().put("chapterList", "@css:ol a").put("chapterName", "text")
                        .put("chapterUrl", "href"))
                .put("ruleContent", new JSONObject()
                        .put("content", "@js:org.jsoup.Jsoup.parse(result).select('main p').toArray().map(p=>p.text())"));
        server.createContext("/source", exchange -> reply(exchange, source.toString(), "application/json"));
        server.createContext("/search", exchange -> {
            boolean query = "keyword=测试".equals(exchange.getRequestURI().getQuery());
            reply(exchange, query ? "{\"items\":[{\"available\":true,\"title\":\"一起验证\",\"url\":\"/book\"},"
                    + "{\"available\":false,\"title\":\"隐藏条目\",\"url\":\"/other\"}]}" : "{\"items\":[]}",
                    "application/json");
        });
        server.createContext("/book", exchange -> reply(exchange, "<a id='contents' href='/toc'>目录</a>", "text/html"));
        server.createContext("/toc", exchange -> reply(exchange, "<ol><li><a href='/read'>第一章</a></li></ol>", "text/html"));
        server.createContext("/read", exchange -> reply(exchange, "<main><p>已读取章节正文。</p></main>", "text/html"));
        AtomicInteger imageRequests = new AtomicInteger();
        server.createContext("/image", exchange -> {
            imageRequests.incrementAndGet();
            reply(exchange, exchange.getRequestHeaders().getFirst("X-Reader-Variant"), "image/png");
        });
        server.start();
        try {
            ReaderSources.ImportPlan fromUrl = store.prepareUrl(base + "/source", ReaderSources.ALL);
            require(fromUrl.added == 1, "HTTP source download");
            ReaderSources.Source imported = store.apply(fromUrl).sources.get(0);
            List<ReaderEngine.Book> result = ReaderEngine.search(imported, "测试", 1);
            require(result.size() == 1 && result.get(0).title.equals("一起验证"), "encoded search and JSONPath filter");
            ReaderEngine.Book details = ReaderEngine.details(result.get(0));
            require(details.tocUrl.equals(base + "/toc"), "book details");
            ReaderEngine.Chapter chapter = ReaderEngine.chapters(details).get(0);
            require(ReaderEngine.open(chapter).text.equals("已读取章节正文。"), "Legado JavaScript content");
            ReaderCache cache = new ReaderCache(offline.toFile());
            String image = base + "/image";
            byte[] first = cache.readImage(null, imported, image, image, Map.of("X-Reader-Variant", "one"));
            byte[] repeated = cache.readImage(null, imported, image, image, Map.of("x-reader-variant", "one"));
            byte[] alternate = cache.readImage(null, imported, image, image, Map.of("X-Reader-Variant", "two"));
            require(Arrays.equals(first, repeated) && !Arrays.equals(first, alternate) && imageRequests.get() == 2,
                    "image disk reuse keeps effective request headers separate");
            System.out.println("PASS HTTP import, encoded search, JSONPath filter, details, TOC and chapter");
            System.out.println("PASS image disk reuse and request-header isolation");
        } finally {
            server.stop(0);
        }
    }

    private static void verifyAggregation(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        JSONObject custom = catalogueSource("手动配置", base + "/custom", ReaderSources.NOVEL)
                .put("searchUrl", "/my-search?key={{key}}").put("enabled", false);
        JSONObject remoteCustom = catalogueSource("合集中的同一来源", base + "/custom", ReaderSources.NOVEL);
        JSONObject novel = catalogueSource("自动新增小说源", base + "/novel", ReaderSources.NOVEL)
                .put("enabled", false);
        JSONObject sameNovel = catalogueSource("合集中的重复小说源", base + "/novel", ReaderSources.NOVEL);
        JSONObject nameDuplicate = catalogueSource(" 自动新增小说源 ", base + "/other-address", ReaderSources.NOVEL);
        JSONObject existingName = catalogueSource("手动配置", base + "/other-custom", ReaderSources.NOVEL);
        JSONObject manga = catalogueSource("自动新增漫画源", base + "/manga", ReaderSources.COMIC);
        JSONArray primary = new JSONArray().put(remoteCustom).put(novel).put(manga);
        int novelCount = 12;
        for (int i = 1; i < novelCount; i++) {
            primary.put(catalogueSource("新增小说源 " + i, base + "/novel/" + i, ReaderSources.NOVEL));
        }
        server.createContext("/first.json", exchange -> reply(exchange, primary.toString(), "application/json"));
        server.createContext("/duplicate.json", exchange -> reply(exchange,
                new JSONArray().put(sameNovel).put(nameDuplicate).put(existingName).toString(), "application/json"));
        server.createContext("/unavailable.json", exchange -> {
            try (exchange) {
                exchange.sendResponseHeaders(503, -1);
            }
        });
        server.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            ReaderSources.Source saved = store.importText(custom.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderAggregation.Publisher> publishers = List.of(
                    new ReaderAggregation.Publisher("本地合集", base + "/first.json"),
                    new ReaderAggregation.Publisher("本地重复合集", base + "/duplicate.json"),
                    new ReaderAggregation.Publisher("暂时不可用", base + "/unavailable.json"));
            ReaderAggregation.Result result = ReaderAggregation.run(store, ReaderSources.NOVEL, text -> {}, publishers);
            require(result.changes.added == novelCount && result.changes.updated == 0
                    && result.newlyEnabled == novelCount && store.list(ReaderSources.COMIC).isEmpty()
                    && result.changes.sources.stream().allMatch(source -> source.enabled),
                    "novel aggregation filters kind, deduplicates, and enables every new source");
            require(result.reports.stream().anyMatch(report -> report.name.equals("本地合集") && report.success)
                    && result.reports.stream().anyMatch(report -> report.name.equals("暂时不可用") && !report.success
                    && !report.detail.isBlank()), "one publisher failure preserves successful imports");
            ReaderAggregation.Result comics = ReaderAggregation.run(store, ReaderSources.COMIC, null, publishers);
            require(comics.changes.added == 1 && comics.newlyEnabled == 1 && !comics.limited,
                    "manga aggregation selects the other kind and enables the new source");
            ReaderSources.Source toggled = store.setEnabled(result.changes.sources.get(0), false);
            ReaderAggregation.Result repeated = ReaderAggregation.run(store, ReaderSources.NOVEL, null, publishers);
            require(repeated.changes.added == 0 && repeated.newlyEnabled == 0 && !store.find(toggled.id).enabled,
                    "later aggregation preserves a source manually turned off");
            ReaderSources reloaded = new ReaderSources(catalog.toFile(), null);
            ReaderSources.Source restored = reloaded.find(saved.id);
            require(restored != null && !restored.enabled && restored.name.equals("手动配置")
                    && restored.searchUrl.equals("/my-search?key={{key}}"), "preserve disabled custom definition");
            require(reloaded.list(ReaderSources.NOVEL).size() == novelCount + 1
                    && reloaded.list(ReaderSources.COMIC).size() == 1, "aggregated catalog persists and reloads");
            ReaderSources.Source comic = reloaded.setEnabled(comics.changes.sources.get(0), false);
            require(reloaded.setAllEnabled(ReaderSources.NOVEL, true) == 2
                    && reloaded.setAllEnabled(ReaderSources.NOVEL, true) == 0,
                    "batch enable returns changed sources and is idempotent");
            ReaderSources afterBulk = new ReaderSources(catalog.toFile(), null);
            require(afterBulk.list(ReaderSources.NOVEL).stream().allMatch(source -> source.enabled)
                    && !afterBulk.find(comic.id).enabled, "batch enables only its category and persists in one update");
            require(afterBulk.setAllEnabled(ReaderSources.NOVEL, false) == novelCount + 1
                    && afterBulk.list(ReaderSources.NOVEL).stream().noneMatch(source -> source.enabled),
                    "batch disable applies the inverse explicitly");
            System.out.println("PASS aggregate deduplication, full activation, preserved toggles, partial failure and batch activation");
        } finally {
            server.stop(0);
        }
    }

    private static void verifySourceDeduplication(Path catalog, Path legacyCatalog) throws Exception {
        ReaderSources store = new ReaderSources(catalog.toFile(), null);
        String address = "https://reader-dedup.invalid";
        JSONObject canonical = catalogueSource("  Ｗｅｂ　Ｔｅｓｔ  ", address, ReaderSources.NOVEL);
        JSONObject sameName = catalogueSource("web  test", address + "/alternative", ReaderSources.NOVEL);
        JSONObject sameAddress = catalogueSource("另一名称", "HTTPS://READER-DEDUP.INVALID:443/", ReaderSources.NOVEL);
        JSONObject otherKind = catalogueSource("web test", address, ReaderSources.COMIC);
        ReaderSources.ImportResult applied = store.importText(new JSONArray().put(canonical).put(sameName)
                .put(sameAddress).put(otherKind).toString(), ReaderSources.ALL);
        require(applied.added == 2 && applied.skipped == 2, "deduplicate Unicode names and URLs per kind");
        String canonicalId = store.list(ReaderSources.NOVEL).get(0).id;
        canonical.put("bookSourceName", "Web Test").put("searchUrl", "/revised?key={{key}}");
        ReaderSources.ImportResult revised = store.importText(canonical.toString(), ReaderSources.NOVEL);
        require(revised.updated == 1 && store.find(canonicalId).searchUrl.startsWith("/revised?"),
                "manual exact-address updates remain allowed");
        ReaderSources.ImportPlan pending = store.prepareText(
                catalogueSource("争用名字", address + "/candidate", ReaderSources.NOVEL).toString(),
                ReaderSources.NOVEL);
        store.importText(catalogueSource("争用名字", address + "/winner", ReaderSources.NOVEL).toString(),
                ReaderSources.NOVEL);
        require(store.apply(pending).added == 0, "apply rechecks duplicate names against the latest catalogue");

        JSONObject first = catalogueSource("旧书源", address + "/first", ReaderSources.NOVEL)
                .put("searchUrl", "/old-search?key={{key}}");
        JSONObject preferred = catalogueSource("旧书源", address + "/preferred", ReaderSources.NOVEL);
        JSONObject alias = catalogueSource("旧书源", address + "/alias", ReaderSources.NOVEL);
        JSONArray legacy = new JSONArray().put(legacySourceRecord(first, false))
                .put(legacySourceRecord(preferred, true)).put(legacySourceRecord(alias, true));
        Files.writeString(legacyCatalog, legacy.toString(), StandardCharsets.UTF_8);
        ReaderSources oldStore = new ReaderSources(legacyCatalog.toFile(), null);
        String firstId = "0:" + address + "/first";
        String preferredId = "0:" + address + "/preferred";
        String aliasId = "0:" + address + "/alias";
        require(oldStore.list(ReaderSources.NOVEL).size() == 1
                && oldStore.list(ReaderSources.NOVEL).get(0).id.equals(preferredId)
                && oldStore.find(firstId).searchUrl.equals("/old-search?key={{key}}"),
                "legacy duplicates show one enabled representative and preserve exact old IDs");
        oldStore.setEnabled(oldStore.find(preferredId), false);
        ReaderSources reopened = new ReaderSources(legacyCatalog.toFile(), null);
        require(reopened.list(ReaderSources.NOVEL).size() == 1
                && reopened.list(ReaderSources.NOVEL).get(0).id.equals(preferredId)
                && !reopened.list(ReaderSources.NOVEL).get(0).enabled
                && reopened.find(aliasId).enabled, "disabled representative remains stable across reload");
        require(reopened.setAllEnabled(ReaderSources.NOVEL, true) == 1 && !reopened.find(firstId).enabled,
                "batch activation changes only the visible representative and leaves saved aliases untouched");
        System.out.println("PASS normalized source names, exact address updates, stable legacy aliases and saved source IDs");
    }

    private static JSONObject legacySourceRecord(JSONObject definition, boolean enabled) throws Exception {
        return new JSONObject().put("kind", ReaderSources.NOVEL).put("enabled", enabled)
                .put("definition", definition);
    }

    private static JSONObject catalogueSource(String name, String url, int kind) throws Exception {
        return new JSONObject().put("bookSourceName", name).put("bookSourceUrl", url)
                .put("bookSourceType", kind).put("searchUrl", "/search?key={{key}}")
                .put("ruleSearch", new JSONObject().put("bookList", ".results a")
                        .put("name", "text").put("bookUrl", "href"))
                .put("ruleToc", new JSONObject().put("chapterList", ".chapters a")
                        .put("chapterName", "text").put("chapterUrl", "href"))
                .put("ruleContent", new JSONObject().put("content", kind == ReaderSources.COMIC
                        ? "main img@src" : "main p@text"));
    }

    private static List<ReaderEngine.Chapter> readSample(ReaderSources.Source source) throws IOException {
        ReaderEngine.Explore discovery = ReaderEngine.exploreEntries(source).get(0);
        ReaderEngine.Book book = ReaderEngine.explore(source, discovery.url, 1).get(0);
        return ReaderEngine.chapters(ReaderEngine.details(book));
    }

    private static void reply(HttpExchange exchange, String text, String type) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static InputStream resource(String relative) throws IOException {
        InputStream input = ReaderVerification.class.getResourceAsStream("/" + relative);
        if (input == null) throw new IOException("Missing bundled resource: " + relative);
        return input;
    }

    private static String sample(String file) throws IOException {
        try (InputStream input = resource("reader-samples/" + file)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] backup(String source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("backup/bookSource.json"));
            zip.write(source.getBytes(StandardCharsets.UTF_16));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("backup/bookshelf.json"));
            zip.write("[]".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static byte[] gzip(String source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(source.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String compressed(String source) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(bytes)) {
            zlib.write(source.getBytes(StandardCharsets.UTF_8));
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static void require(boolean condition, String stage) {
        if (!condition) throw new AssertionError(stage);
    }
}
