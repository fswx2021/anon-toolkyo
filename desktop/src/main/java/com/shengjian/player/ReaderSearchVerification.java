package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the actual search transport against servers that never finish their response. */
final class ReaderSearchVerification {
    private ReaderSearchVerification() {}

    static void verify(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService connections = Executors.newCachedThreadPool();
        server.setExecutor(connections);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        JSONArray rules = new JSONArray();
        for (int i = 0; i < 3; i++) {
            rules.put(new JSONObject().put("bookSourceType", ReaderSources.NOVEL)
                    .put("bookSourceName", "搜索超时验证 " + i)
                    .put("bookSourceUrl", base + "/rules/" + i)
                    .put("searchUrl", i < 2 ? "/slow?kw={{key}}" : "/fast?kw={{key}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "$.items")
                            .put("name", "$.title").put("bookUrl", "$.url")));
        }
        CountDownLatch slowRequests = new CountDownLatch(2);
        server.createContext("/rules", exchange -> reply(exchange, rules.toString()));
        server.createContext("/slow", exchange -> sendSlowly(exchange, slowRequests));
        server.createContext("/fast", exchange -> reply(exchange,
                "{\"items\":[{\"title\":\"搜索继续运行\",\"url\":\"/book\"}]}"));
        server.start();
        try (ReaderSearchQueue queue = new ReaderSearchQueue(2, 700)) {
            ReaderSources sources = new ReaderSources(catalog.toFile(), null);
            ReaderAggregation.Result merged = ReaderAggregation.run(sources, ReaderSources.NOVEL, null,
                    List.of(new ReaderAggregation.Publisher("测试合集", base + "/rules")));
            if (merged.changes.added != 3) throw new AssertionError("slow-search catalogue import");

            AtomicInteger failed = new AtomicInteger();
            AtomicInteger found = new AtomicInteger();
            CountDownLatch finished = new CountDownLatch(3);
            queue.start(sources.list(ReaderSources.NOVEL), source -> ReaderEngine.search(source, "验证", 1),
                    (source, books, error) -> {
                        if (error != null) failed.incrementAndGet();
                        else found.addAndGet(books.size());
                        finished.countDown();
                    });
            if (!slowRequests.await(3, TimeUnit.SECONDS) || !finished.await(5, TimeUnit.SECONDS)
                    || failed.get() != 2 || found.get() != 1) {
                throw new AssertionError("slow HTTP responses must time out and free the queued healthy source");
            }
        } finally {
            server.stop(0);
            connections.shutdownNow();
        }
        System.out.println("PASS search HTTP deadline, progress and result after slow sources");
    }

    static void verifyFailures(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService connections = Executors.newCachedThreadPool();
        server.setExecutor(connections);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        AtomicInteger recover = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger deferred = new AtomicInteger();
        AtomicInteger submitted = new AtomicInteger();
        String works = "{\"items\":[{\"title\":\"作品\",\"url\":\"/book\"}]}";
        server.createContext("/recover", exchange -> {
            if (recover.incrementAndGet() == 1) refuse(exchange, 503, null);
            else reply(exchange, works);
        });
        server.createContext("/limited", exchange -> {
            limited.incrementAndGet();
            refuse(exchange, 429, null);
        });
        server.createContext("/deferred", exchange -> {
            deferred.incrementAndGet();
            refuse(exchange, 503, "5");
        });
        server.createContext("/submit", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) submitted.incrementAndGet();
            refuse(exchange, 503, null);
        });
        server.createContext("/slow-once", exchange -> {
            try {
                Thread.sleep(900);
                reply(exchange, works);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
            }
        });
        server.createContext("/empty", exchange -> reply(exchange, "{\"items\":[]}"));
        server.createContext("/unmatched", exchange -> reply(exchange, "{\"code\":404,\"msg\":\"No results\"}"));
        server.createContext("/json-denied", exchange -> reply(exchange,
                "{\"code\":403,\"message\":\"Forbidden\",\"items\":[]}"));
        server.createContext("/invalid", exchange -> reply(exchange, "{\"items\":[{\"other\":1}]}"));
        server.createContext("/blocked", exchange -> reply(exchange, "<title>无法显示此网页</title>"));
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/book");
            refuse(exchange, 302, null);
        });
        server.createContext("/book", exchange -> reply(exchange, "<h1>作品</h1><a id='toc' href='/toc'>目录</a>"));
        server.start();
        try (ReaderSearchQueue queue = new ReaderSearchQueue(1)) {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            require(search(queue, source(store, base, "/recover"), 4000).size() == 1 && recover.get() == 2,
                    "HTTP 503 retry should recover even with an optional cover error");
            expectStatus(queue, source(store, base, "/limited"), 429);
            expectStatus(queue, source(store, base, "/deferred"), 503);
            expectStatus(queue, source(store, base, "/submit,{\"method\":\"POST\",\"body\":\"q=book\"}"), 503);
            require(limited.get() == 1 && deferred.get() == 1 && submitted.get() == 1,
                    "automatic retries must respect rate limits, Retry-After and POST");
            require(search(queue, source(store, base, "/empty"), 4000).isEmpty(), "valid empty results");
            require(search(queue, source(store, base, "/unmatched"), 4000).isEmpty(),
                    "an API may use 404 to indicate no matching work");
            try {
                search(queue, source(store, base, "/json-denied"), 4000);
                throw new AssertionError("JSON error inside HTTP 200 must report an access failure");
            } catch (ReaderSources.SiteFailure failed) {
                require(failed.getMessage().contains("403"), "JSON error status must be retained");
            }
            try {
                search(queue, source(store, base, "/invalid"), 4000);
                throw new AssertionError("invalid rows must report a rule mismatch");
            } catch (IOException failed) {
                require(failed.getMessage().contains("列表规则命中了条目"), "unexpected invalid-row error");
            }
            try {
                search(queue, source(store, base, "/blocked"), 4000);
                throw new AssertionError("HTTP 200 error page must report an upstream error");
            } catch (ReaderSources.SiteFailure expected) {
                // The upstream HTML did not contain a result list.
            }
            List<ReaderEngine.Book> detail = search(queue, source(store, base, "/redirect"), 4000);
            require(detail.size() == 1 && detail.get(0).tocUrl.equals(base + "/toc"),
                    "search that redirects directly to matching book info");
            ReaderSources.Source slow = source(store, base, "/slow-once");
            try {
                search(queue, slow, 450);
                throw new AssertionError("slow first check should time out");
            } catch (SocketTimeoutException expected) {
                require(search(queue, slow, 4000).size() == 1, "extended manual retry should recover");
            }
        } finally {
            server.stop(0);
            connections.shutdownNow();
        }
        System.out.println("PASS search retry, typed upstream and rule failures, and detail-page fallback");
    }

    static void verifyDefaultTimeoutRecovery(Path catalog) throws Exception {
        require(ReaderSearchQueue.DEFAULT_TIMEOUT_MILLIS == 10_000,
                "ordinary searches must retain the ten-second first budget");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService connections = Executors.newCachedThreadPool();
        server.setExecutor(connections);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String book = "{\"items\":[{\"title\":\"定向补查\",\"url\":\"/book\"}]}";
        AtomicInteger stalledGets = new AtomicInteger();
        AtomicInteger stalledPosts = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/default-stall", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")
                    || !"reader-fixture".equals(exchange.getRequestHeaders().getFirst("X-Test-Search"))) {
                refuse(exchange, 400, null);
            } else if (stalledGets.incrementAndGet() == 1) {
                entered.countDown();
                holdHeaders(exchange, release);
            } else {
                reply(exchange, book);
            }
        });
        server.createContext("/healthy", exchange -> reply(exchange, book));
        server.createContext("/default-post", exchange -> {
            if (!exchange.getRequestMethod().equals("POST")) {
                refuse(exchange, 405, null);
                return;
            }
            exchange.getRequestBody().readAllBytes();
            stalledPosts.incrementAndGet();
            holdHeaders(exchange, release);
        });
        server.start();
        try (ReaderSearchQueue queue = ReaderSearchQueue.forSources()) {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            ReaderSources.Source stalled = source(store, base + "/stall-source",
                    "/default-stall,{\"headers\":{\"X-Test-Search\":\"reader-fixture\"}}");
            ReaderSources.Source healthy = source(store, base + "/healthy-source", "/healthy");
            ReaderSources.Source posted = source(store, base + "/post-source",
                    "/default-post,{\"method\":\"POST\",\"body\":\"q=book\"}");
            CompletableFuture<List<ReaderEngine.Book>> recovered = new CompletableFuture<>();
            CompletableFuture<List<ReaderEngine.Book>> peer = new CompletableFuture<>();
            CompletableFuture<Exception> postFailure = new CompletableFuture<>();
            queue.start(List.of(stalled, healthy, posted), ReaderEngine::searchRoute,
                    input -> ReaderEngine.search(input, "定向补查", 1), input -> {}, (input, books, error) -> {
                        if (input == posted) {
                            postFailure.complete(error);
                        } else {
                            CompletableFuture<List<ReaderEngine.Book>> result = input == stalled ? recovered : peer;
                            if (error == null) result.complete(books);
                            else result.completeExceptionally(error);
                        }
                    }, ReaderSearchQueue.DEFAULT_TIMEOUT_MILLIS);
            require(entered.await(3, TimeUnit.SECONDS), "the first GET must reach the local server");
            require(peer.get(4, TimeUnit.SECONDS).size() == 1 && !recovered.isDone(),
                    "a stalled source must leave its same-site healthy peer responsive");
            List<ReaderEngine.Book> books = recovered.get(16, TimeUnit.SECONDS);
            require(books.size() == 1 && books.get(0).title.equals("定向补查") && stalledGets.get() == 2,
                    "one post-deadline GET retry must recover the result and retain request headers");
            require(postFailure.get(5, TimeUnit.SECONDS) instanceof SocketTimeoutException && stalledPosts.get() == 1,
                    "a stalled search POST must time out without automatic replay");
        } finally {
            release.countDown();
            server.stop(0);
            connections.shutdownNow();
        }
        System.out.println("PASS default deadline GET recovery, healthy peer progress and no POST replay");
    }

    static void verifyParallelSources(Path catalog) throws Exception {
        List<ReaderSearchQueue.Route> routes = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            routes.add(new ReaderSearchQueue.Route("rendered-" + i + ".example", true));
        }
        for (int i = 0; i < 20; i++) routes.add(new ReaderSearchQueue.Route("same.example", false));
        for (int i = routes.size(); i < 2647; i++) {
            routes.add(new ReaderSearchQueue.Route("site-" + i + ".example", false));
        }

        require(ReaderSearchQueue.HTTP_LIMIT == 48 && ReaderSearchQueue.BROWSER_LIMIT == 2,
                "reader search must expose 48 HTTP lanes while keeping the WebView cap");
        AtomicInteger http = new AtomicInteger();
        AtomicInteger browser = new AtomicInteger();
        AtomicInteger sameSite = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        CountDownLatch active = new CountDownLatch(ReaderSearchQueue.HTTP_LIMIT + ReaderSearchQueue.BROWSER_LIMIT);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(routes.size());
        try (ReaderSearchQueue queue = ReaderSearchQueue.forSources()) {
            queue.start(routes, route -> route, route -> {
                AtomicInteger lane = route.browser ? browser : http;
                int limit = route.browser ? ReaderSearchQueue.BROWSER_LIMIT : ReaderSearchQueue.HTTP_LIMIT;
                if (lane.incrementAndGet() > limit) bad.incrementAndGet();
                boolean repeated = route.site.equals("same.example");
                if (repeated && sameSite.incrementAndGet() > 2) bad.incrementAndGet();
                active.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("parallel lane stalled");
                    return true;
                } finally {
                    lane.decrementAndGet();
                    if (repeated) sameSite.decrementAndGet();
                }
            }, route -> {}, (route, found, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(found)) bad.incrementAndGet();
                completed.countDown();
            }, 10_000);
            require(active.await(5, TimeUnit.SECONDS)
                    && http.get() == ReaderSearchQueue.HTTP_LIMIT && browser.get() == ReaderSearchQueue.BROWSER_LIMIT
                    && sameSite.get() == 2, "slow site and browser jobs must leave independent HTTP slots");
            release.countDown();
            require(completed.await(10, TimeUnit.SECONDS) && bad.get() == 0,
                    "every eligible source should finish within bounded concurrent slots");
        } finally {
            release.countDown();
        }
        System.out.println("PASS 2647-source queue, 48 HTTP + 2 browser jobs and two per site");

        ReaderSources store = new ReaderSources(catalog.toFile(), null);
        ReaderSources.Source novel = source(store, "https://www.example.test", "/find");
        ReaderSources.Source updated = source(store, "https://www.example.test", "/lookup");
        ReaderSourceHealth health = new ReaderSourceHealth("");
        long now = TimeUnit.DAYS.toMillis(20);
        IOException gone = new ReaderSources.HttpFailure(404, null);
        health.record(novel, false, gone, now);
        require(!health.deferred(novel, now), "a single HTTP failure still participates");
        health.record(novel, false, gone, now + 1);
        ReaderSourceHealth restored = new ReaderSourceHealth(health.serialize());
        require(restored.deferred(novel, now + 1) && !restored.deferred(updated, now + 1),
                "cooldown survives a reload and resets on a changed rule");
        require(!restored.deferred(novel, now + TimeUnit.MINUTES.toMillis(31)), "cooldown automatically ends");
        restored.record(novel, false, new SocketTimeoutException(), now + 2);
        restored.record(novel, false, new SocketTimeoutException(), now + 3);
        require(!restored.deferred(novel, now + 3), "network timeouts can participate in the next search");
        restored.record(novel, true, null, now + 4);
        require(restored.priority(novel, now + 4) > restored.priority(updated, now + 4),
                "recent successful sources outrank unknown ones");
        verifyMangaHealth(store, now);
        System.out.println("PASS source health persistence, recovery, priority and rule revision reset");
    }

    private static void verifyMangaHealth(ReaderSources store, long now) throws Exception {
        ReaderSources.Source comic = store.importText(new JSONObject()
                .put("bookSourceType", ReaderSources.COMIC).put("bookSourceName", "漫画连接检查")
                .put("bookSourceUrl", "https://comic.example.test").put("searchUrl", "/find")
                .put("ruleSearch", new JSONObject().put("bookList", "$.list")).toString(),
                ReaderSources.COMIC).sources.get(0);
        ReaderSourceHealth health = new ReaderSourceHealth("");
        health.record(comic, false, new UnknownHostException(), now);
        require(!health.deferred(comic, now), "first DNS failure remains eligible");
        health.record(comic, false, new UnknownHostException(), now + 1);
        require(health.deferred(comic, now + 1), "repeated manga DNS failure enters temporary cooldown");
        health.record(comic, false, new IOException(new CertificateExpiredException()), now + 2);
        require(!health.deferred(comic, now + 2), "different failures do not share a streak");
        health.record(comic, false, new IOException(new CertificateExpiredException()), now + 3);
        require(health.deferred(comic, now + 3), "repeated expired certificate enters temporary cooldown");
        health.record(comic, true, null, now + 4);
        require(!health.deferred(comic, now + 4), "manual retry can restore the source immediately");
    }

    static void verifyRelevance() {
        String keyword = ReaderSearchRanking.requestKeyword("　《 神之塔 》　");
        require(keyword.equals("神之塔"), "a copied book title should omit surrounding punctuation on the wire");
        require(ReaderSearchRanking.requestKeyword("Fate/stay night").equals("Fate/stay night"),
                "preserve punctuation internal to a title");
        ReaderSearchRanking manga = new ReaderSearchRanking(keyword, text -> text);
        List<String> arrival = new ArrayList<>(List.of("机器人保镖和大姐姐", "第二次崩坏", "神之塔外传", "《神之塔》"));
        arrival.sort((first, second) -> Integer.compare(manga.score(second, "SIU"), manga.score(first, "SIU")));
        require(arrival.equals(List.of("《神之塔》", "神之塔外传", "机器人保镖和大姐姐", "第二次崩坏")),
                "late exact matches rank first, and unmatched results stay at the bottom of the same list");
        require(new ReaderSearchRanking("镖人", text -> text).score("机器人保镖和大姐姐", "") == 0,
                "upstream recommendation should not count as a keyword match");

        ReaderSearchRanking novel = new ReaderSearchRanking("剑来", text -> text);
        require(novel.score("《剑来》", "烽火戏诸侯") > novel.score("剑来外传", "某人"), "exact novel title");
        ReaderSearchRanking author = new ReaderSearchRanking("烽火戏诸侯", text -> text);
        require(author.score("剑来", "作者：烽火戏诸侯") > author.score("关于烽火戏诸侯", "某人"),
                "exact author should rank above a substring in the title");
        ReaderSearchRanking combined = new ReaderSearchRanking("剑来 烽火戏诸侯", text -> text);
        require(combined.score("剑来", "烽火戏诸侯") > 0 && combined.score("剑来", "某人") == 0,
                "all terms must match a combined title and author search");
        require(new ReaderSearchRanking("IT", text -> text).score("Little Women", "") == 0,
                "a short Latin query should not match inside an unrelated word");
        require(new ReaderSearchRanking("ＡＫＩＲＡ", text -> text).score("Akira", "") > 0, "width and case folding");
        require(new ReaderSearchRanking("进击的巨人", text -> text.replace('進', '进').replace('擊', '击'))
                .exactTitle("進擊的巨人"), "injected traditional and simplified title folding");
        System.out.println("PASS ranked title and author matching, out-of-order results and unrelated recommendations");
    }

    private static ReaderSources.Source source(ReaderSources store, String base, String search) throws IOException {
        return store.prepareText(new JSONObject().put("bookSourceType", ReaderSources.NOVEL)
                .put("bookSourceName", "搜索验证")
                .put("bookSourceUrl", base).put("searchUrl", search)
                .put("ruleSearch", new JSONObject().put("bookList", "$.items").put("name", "$.title")
                        .put("bookUrl", "$.url").put("coverUrl", "@js:missingCover(result)"))
                .put("ruleBookInfo", new JSONObject().put("name", "@css:h1@text")
                        .put("tocUrl", "@css:#toc@href")).toString(), ReaderSources.NOVEL).sources.get(0);
    }

    private static List<ReaderEngine.Book> search(ReaderSearchQueue queue, ReaderSources.Source source,
                                                  long timeout) throws Exception {
        CompletableFuture<List<ReaderEngine.Book>> result = new CompletableFuture<>();
        queue.start(List.of(source), target -> ReaderEngine.search(target, "作品", 1), target -> {},
                (target, books, failure) -> {
                    if (failure == null) result.complete(books);
                    else result.completeExceptionally(failure);
                }, timeout);
        try {
            return result.get(6, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException failed) {
            throw (Exception) failed.getCause();
        }
    }

    private static void expectStatus(ReaderSearchQueue queue, ReaderSources.Source source, int status)
            throws Exception {
        try {
            search(queue, source, 4000);
            throw new AssertionError("expected HTTP " + status);
        } catch (ReaderSources.HttpFailure failed) {
            require(failed.status == status, "unexpected HTTP status");
        }
    }

    private static void refuse(HttpExchange exchange, int status, String retryAfter) throws IOException {
        try (exchange) {
            if (retryAfter != null) exchange.getResponseHeaders().set("Retry-After", retryAfter);
            exchange.sendResponseHeaders(status, -1);
        }
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void holdHeaders(HttpExchange exchange, CountDownLatch release) {
        try (exchange) {
            release.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sendSlowly(HttpExchange exchange, CountDownLatch requests) {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, 0);
            requests.countDown();
            OutputStream out = exchange.getResponseBody();
            while (!Thread.currentThread().isInterrupted()) {
                out.write(' ');
                out.flush();
                Thread.sleep(60);
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (IOException closed) {
            // The timed-out search closed the response stream.
        }
    }

    private static void reply(HttpExchange exchange, String text) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
