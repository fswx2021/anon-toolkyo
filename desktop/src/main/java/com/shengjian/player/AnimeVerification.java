package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class AnimeVerification {
    private AnimeVerification() {}

    static void verify(Path path) throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer otherOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + site.getAddress().getPort();
        String keyword = "海边 电台&光";
        AtomicBoolean legacySearch = new AtomicBoolean(), apiSearch = new AtomicBoolean();
        AtomicBoolean crossedOrigin = new AtomicBoolean();
        otherOrigin.createContext("/post", exchange -> {
            crossedOrigin.set(true);
            reply(exchange, "text/html", "<html></html>");
        });
        otherOrigin.start();
        site.createContext("/redirect-post", exchange -> {
            exchange.getResponseHeaders().set("Location", "http://127.0.0.1:" + otherOrigin.getAddress().getPort() + "/post");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        site.createContext("/dynamic/search", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            legacySearch.set(exchange.getRequestMethod().equals("POST") && exchange.getRequestURI().getQuery() == null
                    && body.startsWith("fixed=1&wd=") && decode(body.substring("fixed=1&wd=".length())).equals(keyword)
                    && "LocalAnime/1".equals(exchange.getRequestHeaders().getFirst("User-Agent")));
            reply(exchange, "text/html", "<ul><li class='card'><h3><a href='../show/a'>海边电台</a></h3>"
                    + "<img data-src='../cover.jpg'></li><li class='card'><h3><a href='../show/b'>另一个作品</a></h3></li>"
                    + "<li class='card'><h3><a href='javascript:alert(1)'>坏地址</a></h3></li></ul>");
        });
        site.createContext("/show/", exchange -> {
            exchange.getResponseHeaders().set("Location", "/series/current/index.html");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        site.createContext("/series/current/index.html", exchange -> reply(exchange, "text/html", "<ol class='show-episode-list' data-name='主线路'>"
                + "<li><a href='first.mp4'>第1集 早晨</a></li><li><a href='?episode=2'>第2集</a></li></ol>"
                + "<ol class='episode-list'><li><a href='//127.0.0.1:" + site.getAddress().getPort()
                + "/other.m3u8?view=1'>备用</a></li></ol>"));
        site.createContext("/api/search", exchange -> {
            JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiSearch.set(exchange.getRequestMethod().equals("POST") && "page=2".equals(exchange.getRequestURI().getQuery())
                    && keyword.equals(body.optString("keyword")) && body.optInt("limit") == 3);
            reply(exchange, "application/json", "{\"data\":{\"items\":[{\"id\":\"work/7\",\"name\":\"海边电台\"}]}}");
        });
        site.createContext("/api/detail", exchange -> {
            require(exchange.getRequestURI().getQuery().equals("id=work/7"), "raw ID substituted as a query value");
            reply(exchange, "application/json", "{\"data\":{\"slug\":\"show 9\",\"roads\":[{\"name\":\"线路甲\","
                    + "\"episodes\":[{\"name\":\"第1集\"},{\"name\":\"第2集\"}]}]}}");
        });
        site.createContext("/api/delimited", exchange -> reply(exchange, "application/json",
                "{\"list\":[{\"names\":\"线路1$$$线路2\",\"works\":\"第1集$/test/file.m3u8#第2集$/test/two.mp4$$$正片$/more\"}]}"));
        site.createContext("/protected", exchange -> {
            exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        JSONObject xpath = xpath(base, "XPath 源");
        JSONObject fromRepo = xpath(base, "repo-example");
        site.createContext("/rules/index.json", exchange -> reply(exchange, "application/json",
                "[{\"name\":\"repo-example\",\"version\":\"1.0\"},{\"name\":\"missing\",\"version\":\"1.0\"}]"));
        site.createContext("/rules/repo-example.json", exchange -> reply(exchange, "application/json", fromRepo.toString()));
        site.start();
        try {
            AnimeSource.Store store = new AnimeSource.Store(path.toFile());
            require(store.importText(new JSONArray().put(xpath).put(new JSONObject()).toString()).skipped == 1,
                    "multi-rule import skips a broken row");
            AnimeSource source = store.list().get(0);
            List<AnimeEngine.Show> found = AnimeEngine.search(source, keyword);
            require(legacySearch.get() && found.size() == 2 && found.get(0).title.equals("海边电台")
                    && found.get(1).title.equals("另一个作品") && found.get(0).cover.equals(base + "/cover.jpg"),
                    "XPath search scopes each row, resolves relative links and uses Kazumi form POST");
            List<AnimeEngine.Road> roads = AnimeEngine.episodes(found.get(0));
            AnimeEngine.Episode first = roads.get(0).episodes.get(0);
            require(roads.size() == 2 && roads.get(0).name.equals("主线路") && roads.get(0).episodes.size() == 2
                    && first.url.equals(base + "/series/current/first.mp4") && AnimeEngine.isDirectMedia(first.url)
                    && roads.get(0).episodes.get(1).url.equals(base + "/series/current/index.html?episode=2")
                    && AnimeEngine.isDirectMedia(roads.get(1).episodes.get(0).url)
                    && AnimeEngine.playbackHeaders(first).get("User-Agent").equals("LocalAnime/1"),
                    "XPath attribute wildcard, multiple roads, redirect-relative episodes and playback headers");

            JSONObject api = api(base, "API 源");
            String link = "kazumi://" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(api.toString().getBytes(StandardCharsets.UTF_8));
            require(store.importText(link).added == 1, "Kazumi share URL import");
            AnimeEngine.Show apiShow = AnimeEngine.search(store.list().get(1), keyword).get(0);
            require(apiSearch.get() && apiShow.sourceValue.equals("work/7"), "JSON API typed POST and query override");
            List<AnimeEngine.Road> apiRoads = AnimeEngine.episodes(apiShow);
            String episodeUrl = apiRoads.get(0).episodes.get(1).url;
            require(episodeUrl.startsWith(base + "/episode/show%209?") && episodeUrl.contains("p=1") && episodeUrl.contains("r=1"),
                    "nested API routes and zero/one based template variables");

            JSONObject delimited = api(base, "分隔源").put("chapterApiConfig", new JSONObject().put("format", "delimited")
                    .put("request", new JSONObject().put("url", base + "/api/delimited"))
                    .put("roadNamesPath", "$.list[0]['names']").put("roadEpisodesPath", "$.list[0].works"));
            store.importText(delimited.toString());
            AnimeEngine.Show splitShow = AnimeEngine.search(store.list().get(2), keyword).get(0);
            List<AnimeEngine.Road> splitRoads = AnimeEngine.episodes(splitShow);
            require(splitRoads.size() == 2 && splitRoads.get(0).episodes.get(0).url.equals(base + "/test/file.m3u8"),
                    "delimited API selects quoted fields and keeps direct media");

            JSONObject mixed = api(base, "混合源").put("chapterMode", "xpath")
                    .put("chapterRoads", "css:ol.episode-list").put("chapterResult", "css:li > a");
            mixed.getJSONObject("searchApiConfig").put("sourceTemplate", base + "/show/GV@source");
            store.importText(mixed.toString());
            AnimeEngine.Show hybrid = AnimeEngine.search(store.list().get(3), keyword).get(0);
            require(hybrid.url.equals(base + "/show/GVwork%2F7") && AnimeEngine.episodes(hybrid).size() == 1,
                    "API to HTML mix handles sourceTemplate immediately following a prefix and CSS");

            AnimeSource.ImportResult repository = store.importUrl(base + "/rules/index.json");
            require(repository.added == 1 && repository.skipped == 1 && store.find("repo-example") != null,
                    "repository imports working entries even when a sibling fails");
            store.setEnabled(source.id, false);
            require(store.importText(xpath.put("version", "2.0").toString()).updated == 1, "matching source can update");
            AnimeSource restored = new AnimeSource.Store(path.toFile()).find(source.id);
            require(restored != null && !restored.enabled && restored.version.equals("2.0"), "source toggle survives update and reload");

            JSONObject blocked = xpath(base, "受限源").put("searchURL", base + "/protected?wd=@keyword")
                    .put("antiCrawlerConfig", new JSONObject().put("enabled", true));
            store.importText(blocked.toString());
            try {
                AnimeEngine.search(store.find("受限源"), keyword);
                throw new AssertionError("verification challenge must surface");
            } catch (AnimeEngine.Failure failure) {
                require(failure.stage == AnimeEngine.Stage.SEARCH && failure.kind == AnimeEngine.Kind.VERIFICATION,
                        "typed challenge diagnostic");
            }
            store.importText(xpath(base, "重定向源").put("searchURL", base + "/redirect-post?wd=@keyword").toString());
            try {
                AnimeEngine.search(store.find("重定向源"), keyword);
                throw new AssertionError("a cross-origin 307 may not forward the anime POST body");
            } catch (AnimeEngine.Failure failure) {
                require(failure.kind == AnimeEngine.Kind.NETWORK && !crossedOrigin.get(),
                        "cross-origin POST rejected before connecting to recipient");
            }
            System.out.println("PASS Kazumi source storage, repository/share import, XPath, POST, mixed API and episodes");
        } finally { site.stop(0); otherOrigin.stop(0); }
        verifyAggregation(path.resolveSibling("anime-repository-sources.json"));
        verifyArchiveAndMirror(path.resolveSibling("anime-official-sources.json"));
        verifyXPathUnion(path.resolveSibling("anime-xpath-union.json"));
    }

    private static void verifyXPathUnion(Path path) throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + site.getAddress().getPort();
        site.createContext("/fixture", exchange -> reply(exchange, "text/html", """
                <section class='anthology'>
                  <div class='line_button_ban' data-name='预告'></div>
                  <a class='circuit_switch_ban' href='/episode/trailer'>预告</a>
                  <div class='line_button1' data-name='线路一'></div>
                  <a class='circuit_switch1' href='/episode/1'>第一集</a>
                  <a class='circuit_switch1' href='/episode/2'>第二集</a>
                  <div class='line_button2' data-name='线路二'></div>
                  <a class='circuit_switch2' href='/backup/1'>备用第一集</a>
                  <div class='line_button3' data-name='线路三'></div>
                  <a class='circuit_switch3' href='/archive/1'>归档第一集</a>
                </section>
                """));
        site.start();
        try {
            AnimeSource.Store store = new AnimeSource.Store(path.toFile());
            JSONObject rule = xpath(base, "ezdmw-xpath-union")
                    .put("chapterRoads", "//section[@class='anthology'][1]/div[contains(@class,'line_button')]")
                    .put("chapterResult", "/self::*[@class='line_button_ban']/following-sibling::a[@class='circuit_switch_ban']"
                            + " | /self::*[@class='line_button1']/following-sibling::a[@class='circuit_switch1']"
                            + " | /self::*[@class='line_button3']/following-sibling::a[@class='circuit_switch3']"
                            + " | /self::*[@class='line_button2']/following-sibling::a[@class='circuit_switch2']");
            store.importText(rule.toString());
            AnimeSource source = store.find("ezdmw-xpath-union");
            List<AnimeEngine.Road> roads = AnimeEngine.episodes(new AnimeEngine.Show(source, "测试动画",
                    base + "/fixture", "", ""));
            require(roads.size() == 4 && roads.get(0).episodes.get(0).url.equals(base + "/episode/trailer")
                    && roads.get(1).episodes.size() == 2 && roads.get(1).episodes.get(1).url.equals(base + "/episode/2")
                    && roads.get(2).episodes.get(0).url.equals(base + "/backup/1")
                    && roads.get(3).episodes.get(0).url.equals(base + "/archive/1"),
                    "all four published ezdmw XPath union branches must evaluate relative to their selected road");
            System.out.println("PASS Kazumi ezdmw relative XPath union across four episode roads");
        } finally { site.stop(0); }
    }

    private static void verifyAggregation(Path path) throws Exception {
        HttpServer repo = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService requestWorkers = Executors.newFixedThreadPool(4);
        repo.setExecutor(requestWorkers);
        String base = "http://127.0.0.1:" + repo.getAddress().getPort();
        AtomicInteger version = new AtomicInteger(1);
        AtomicInteger managedRequests = new AtomicInteger();
        CountDownLatch parallel = new CountDownLatch(2);
        AtomicInteger active = new AtomicInteger(), peak = new AtomicInteger();
        String index = "[{\"name\":\"managed\"},{\"name\":\"manual\"},{\"name\":\"missing\"},"
                + "{\"name\":\"broken\"},{\"name\":\"MANAGED\"},{\"name\":\"../escape\"}]";
        repo.createContext("/rules/index.json", exchange -> reply(exchange, "application/json", index));
        repo.createContext("/rules/managed.json", exchange -> {
            managedRequests.incrementAndGet();
            meetOtherRequest(parallel, active, peak);
            reply(exchange, "application/json", xpath(base, "managed").put("version", version.get() + ".0").toString());
        });
        repo.createContext("/rules/manual.json", exchange -> {
            meetOtherRequest(parallel, active, peak);
            reply(exchange, "application/json", xpath(base, "manual").toString());
        });
        repo.createContext("/rules/broken.json", exchange -> reply(exchange, "text/plain", "{\"name\":"));
        repo.createContext("/second/index.json", exchange -> reply(exchange, "application/json", "[{\"name\":\"managed\"}]"));
        repo.createContext("/second/managed.json", exchange -> reply(exchange, "application/json",
                xpath(base, "managed").put("version", "4.0").toString()));
        repo.start();
        try {
            AnimeSource.Store store = new AnimeSource.Store(path.toFile());
            store.importText(xpath(base, "manual").put("version", "自定义").put("enabled", false).toString());
            List<String> progress = new ArrayList<>();
            AnimeSource.AggregationResult first = store.aggregateRepository(base + "/rules/", progress::add);
            require(first.listed == 6 && first.fetched == 2 && first.failed == 2 && first.skippedEntries == 2
                    && first.imported.added == 1 && first.imported.updated == 0 && first.imported.skipped == 1
                    && first.diagnostics.stream().anyMatch(line -> line.contains("HTTP 404"))
                    && first.diagnostics.stream().anyMatch(line -> line.contains("JSON 无法解析")),
                    "repository counts distinguish read errors, duplicate metadata and manual collisions · " + first.diagnostics);
            require(peak.get() >= 2 && progress.stream().anyMatch(value -> value.contains("4 / 4"))
                    && managedRequests.get() == 1, "parallel fetch and completion progress without duplicate requests");
            require("自定义".equals(store.find("manual").version) && !store.find("manual").enabled
                    && store.find("managed").enabled, "preserve manual rule and activate newly added source");
            store.setEnabled("managed", false);

            AnimeSource.Store reopened = new AnimeSource.Store(path.toFile());
            version.set(2);
            require(reopened.aggregateRepository(base + "/rules/index.json", null).imported.updated == 1
                    && !reopened.find("managed").enabled && "2.0".equals(reopened.find("managed").version),
                    "document and directory variants retain provenance and the enabled flag across restart");
            version.set(1);
            require(reopened.aggregateRepository(base + "/rules/", null).imported.updated == 0
                    && "2.0".equals(reopened.find("managed").version), "stale repository does not downgrade installed rule");
            require(reopened.aggregateRepository(base + "/second/", null).imported.updated == 0
                    && "2.0".equals(reopened.find("managed").version), "unrelated repository does not replace source");
            reopened.importText(xpath(base, "managed").put("version", "2.0").toString());
            version.set(3);
            require(reopened.aggregateRepository(base + "/rules", null).imported.updated == 0
                    && "2.0".equals(reopened.find("managed").version) && !reopened.find("managed").enabled,
                    "explicit manual import takes ownership without changing the toggle");
            System.out.println("PASS Kazumi concurrent repository aggregation, partial failures, saved ownership and toggles");
        } finally { repo.stop(0); requestWorkers.shutdownNow(); }
    }

    private static void meetOtherRequest(CountDownLatch arrivals, AtomicInteger active, AtomicInteger peak) {
        peak.accumulateAndGet(active.incrementAndGet(), Math::max);
        arrivals.countDown();
        try { arrivals.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        finally { active.decrementAndGet(); }
    }

    private static void verifyArchiveAndMirror(Path path) throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService requestWorkers = Executors.newFixedThreadPool(4);
        host.setExecutor(requestWorkers);
        String base = "http://127.0.0.1:" + host.getAddress().getPort();
        AtomicInteger directIndexes = new AtomicInteger(), mirrorIndexes = new AtomicInteger();
        AtomicInteger unlistedRequests = new AtomicInteger();
        AtomicBoolean malformedIndex = new AtomicBoolean();
        AtomicReference<byte[]> archive = new AtomicReference<>(repositoryArchive(base, false));
        host.createContext("/official.zip", exchange -> {
            byte[] bytes = archive.get();
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, bytes.length);
            try (exchange) { exchange.getResponseBody().write(bytes); }
        });
        host.createContext("/primary/index.json", exchange -> {
            directIndexes.incrementAndGet();
            reply(exchange, "application/json", malformedIndex.get() ? "{}" : "[{\"name\":\"bundled\",\"version\":\"2\"},"
                    + "{\"name\":\"recovered\",\"version\":\"3\"},{\"name\":\"stale\",\"version\":\"2\"}]");
        });
        host.createContext("/primary/bundled.json", exchange -> reply(exchange, "application/json",
                xpath(base, "bundled").put("version", "2").toString()));
        host.createContext("/mirror/index.json", exchange -> {
            mirrorIndexes.incrementAndGet();
            reply(exchange, "application/json", "[{\"name\":\"unlisted-mirror\",\"version\":\"1\"}]");
        });
        host.createContext("/mirror/recovered.json", exchange -> reply(exchange, "application/json",
                xpath(base, "recovered").put("version", "3").toString()));
        host.createContext("/mirror/stale.json", exchange -> reply(exchange, "application/json",
                xpath(base, "stale").put("version", "1").toString()));
        host.createContext("/mirror/unlisted-mirror.json", exchange -> {
            unlistedRequests.incrementAndGet();
            reply(exchange, "application/json", xpath(base, "unlisted-mirror").put("version", "1").toString());
        });
        host.start();
        try {
            AnimeSource.Store store = new AnimeSource.Store(path.toFile());
            URI primary = URI.create(base + "/primary/index.json"), mirror = URI.create(base + "/mirror/index.json");
            URI zip = URI.create(base + "/official.zip");
            String scope = "kazumi-official";
            AnimeSource.AggregationResult bundled = store.aggregateKnownRepository(primary, mirror, zip, scope, null);
            require(bundled.listed == 1 && bundled.fetched == 1 && bundled.failed == 0 && bundled.imported.added == 1
                    && store.find("bundled") != null && store.find("retired") == null
                    && directIndexes.get() == 0 && mirrorIndexes.get() == 0,
                    "official ZIP reads only its embedded published list and needs no API file requests");
            store.setEnabled("bundled", false);

            archive.set(repositoryArchive(base, true));
            AnimeSource.AggregationResult recovered = store.aggregateKnownRepository(primary, mirror, zip, scope, null);
            require(recovered.listed == 3 && recovered.fetched == 2 && recovered.failed == 1
                    && recovered.imported.updated == 1 && recovered.imported.added == 1
                    && "2".equals(store.find("bundled").version) && !store.find("bundled").enabled
                    && store.find("recovered") != null && store.find("stale") == null
                    && recovered.diagnostics.stream().anyMatch(value -> value.contains("版本早于发布目录"))
                    && directIndexes.get() == 1 && mirrorIndexes.get() == 0 && unlistedRequests.get() == 0,
                    "bound unlisted decompression, then fill only indexed failures from the mirror at adequate versions");

            malformedIndex.set(true);
            AnimeSource.AggregationResult fallback = store.aggregateKnownRepository(primary, mirror, zip, scope, null);
            require(fallback.listed == 1 && fallback.fetched == 1 && fallback.failed == 0
                    && mirrorIndexes.get() == 1 && unlistedRequests.get() == 1 && store.find("unlisted-mirror") != null,
                    "fall back to whole verified mirror catalogue only when direct metadata is malformed");
            System.out.println("PASS Kazumi official ZIP index, archive budget, pinned mirror retries and full metadata fallback");
        } finally { host.stop(0); requestWorkers.shutdownNow(); }
    }

    private static byte[] repositoryArchive(String base, boolean oversizedUnlisted) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zipText(zip, "KazumiRules-main/bundled.json", xpath(base, "bundled").put("version", "1").toString());
            zipText(zip, "KazumiRules-main/retired.json", xpath(base, "retired").toString());
            zipText(zip, "KazumiRules-main/index.json", "[{\"name\":\"bundled\",\"version\":\"1\"}]");
            zipText(zip, "KazumiRules-main/subdir/index.json", "[{\"name\":\"retired\"}]");
            if (oversizedUnlisted) {
                zip.putNextEntry(new ZipEntry("KazumiRules-main/other.bin"));
                byte[] block = new byte[8192];
                for (int i = 0; i < 1025; i++) zip.write(block);
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void zipText(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static JSONObject xpath(String base, String name) {
        return new JSONObject().put("api", "5").put("type", "anime").put("name", name).put("version", "1.0")
                .put("baseURL", base).put("userAgent", "LocalAnime/1").put("usePost", true)
                .put("searchURL", "/dynamic/search?fixed=1&wd=@keyword").put("searchList", "//li[@class='card']")
                .put("searchName", "//h3/a/text()").put("searchResult", "//h3/a/@href")
                .put("chapterRoads", "//ol[@class*='episode-list']").put("chapterResult", "//li/a");
    }

    private static JSONObject api(String base, String name) {
        return new JSONObject().put("api", "9").put("name", name).put("baseURL", base)
                .put("searchMode", "api").put("chapterMode", "api")
                .put("searchApiConfig", new JSONObject().put("request", new JSONObject().put("method", "POST")
                        .put("url", base + "/api/search?page=1").put("query", new JSONObject().put("page", 2))
                        .put("bodyType", "json").put("body", new JSONObject().put("keyword", "@keyword").put("limit", 3)))
                        .put("listPath", "$.data.items[*]").put("namePath", "$.name").put("sourcePath", "$.id"))
                .put("chapterApiConfig", new JSONObject().put("request", new JSONObject().put("url", base + "/api/detail")
                                .put("query", new JSONObject().put("id", "@source")))
                        .put("episodeUrlPath", "").put("variables", new JSONObject().put("slug", "$.data.slug"))
                        .put("episodePage", new JSONObject().put("url", base + "/episode/@slug")
                                .put("query", new JSONObject().put("p", "@episodeIndex").put("r", "@roadNumber"))));
    }

    private static String decode(String value) { return URLDecoder.decode(value, StandardCharsets.UTF_8); }

    private static void reply(HttpExchange exchange, String mime, String data) throws IOException {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", mime + "; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (exchange) { exchange.getResponseBody().write(bytes); }
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
