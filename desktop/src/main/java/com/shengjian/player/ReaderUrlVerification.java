package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Exercises request declarations against a local HTTP server. */
final class ReaderUrlVerification {
    private ReaderUrlVerification() {}

    static void verify(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer differentOrigin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String other = "http://127.0.0.1:" + differentOrigin.getAddress().getPort();
        server.createContext("/lookup", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String raw = exchange.getRequestURI().getRawQuery();
            String query = raw == null ? "" : raw;
            String page = exchange.getRequestHeaders().getFirst("X-Page");
            boolean encoded = query.contains("keyword=%E6%B5%8B%E8%AF%95%20A%26B");
            boolean first = path.equals("/lookup/first") && query.contains("offset=0")
                    && query.contains("tag=ok") && "1".equals(page);
            boolean later = path.equals("/lookup/older") && query.contains("offset=40")
                    && query.contains("tag=ok") && "3".equals(page);
            reply(exchange, encoded && (first || later) ? "已执行脚本地址" : "");
        });
        server.createContext("/post", exchange -> {
            String title = "";
            try {
                JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                if ("POST".equals(exchange.getRequestMethod()) && "引号\"".equals(body.optString("keyword"))
                        && "25".equals(body.optString("offset"))) title = "已处理 JSON 参数";
            } catch (RuntimeException invalid) {
                // A malformed request returns an empty result so the assertion reports the failing stage.
            }
            reply(exchange, title);
        });
        server.createContext("/legacy-multiline-options", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String title = "";
            try {
                JSONObject json = new JSONObject(body);
                if ("POST".equals(exchange.getRequestMethod())
                        && "fixture".equals(exchange.getRequestHeaders().getFirst("X-Format"))
                        && "%E9%AA%8C%E8%AF%81".equals(json.optString("keyword")) && json.optInt("page_num") == 1) {
                    title = "已处理多行请求参数";
                }
            } catch (JSONException invalid) {
                // A malformed body leaves the result empty.
            }
            reply(exchange, title);
        });
        server.createContext("/bridge-token", exchange -> {
            byte[] token = ("fixture".equals(exchange.getRequestHeaders().getFirst("X-Source"))
                    ? "联机令牌" : "").getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, token.length);
                exchange.getResponseBody().write(token);
            }
        });
        server.createContext("/bridge-search", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            reply(exchange, "token=%E8%81%94%E6%9C%BA%E4%BB%A4%E7%89%8C&hash=900150983cd24fb0d6963f7d28e17f72"
                    .equals(raw) ? "脚本书名" : "");
        });
        server.createContext("/bridge-submit", exchange -> {
            try (exchange) {
                String data = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                boolean valid = "POST".equals(exchange.getRequestMethod()) && data.equals("searchkey=测试")
                        && "fixture".equals(exchange.getRequestHeaders().getFirst("X-Source"));
                if (valid) exchange.getResponseHeaders().set("Location", "/bridge-submit-result");
                exchange.sendResponseHeaders(valid ? 302 : 400, -1);
            }
        });
        server.createContext("/bridge-submit-result", exchange -> reply(exchange, "由脚本提交"));
        server.createContext("/bridge-template", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean valid = "POST".equals(exchange.getRequestMethod())
                    && "q=%B2%E2%CA%D4%26&page=2".equals(body)
                    && "page=2".equals(exchange.getRequestURI().getRawQuery())
                    && "2".equals(exchange.getRequestHeaders().getFirst("X-Page"));
            byte[] path = (valid ? "/bridge-submit-result" : "/missing").getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, path.length);
                exchange.getResponseBody().write(path);
            }
        });
        server.createContext("/legacy-link", exchange -> {
            byte[] page = ("<article class='book-list'><a href='/legacy-match'>查看</a>"
                    + "<a class='0' href='/css-match'><h4>索引书名</h4></a></article>").getBytes(StandardCharsets.UTF_8);
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(200, page.length);
                exchange.getResponseBody().write(page);
            }
        });
        differentOrigin.createContext("/rewritten", exchange -> {
            boolean valid = exchange.getRequestHeaders().getFirst("Authorization") == null
                    && "token=hello".equals(exchange.getRequestURI().getRawQuery());
            reply(exchange, valid ? "已改写至其他站点" : "");
        });
        registerEncodingFixtures(server);
        registerSafeLegadoFixtures(server);
        server.start();
        differentOrigin.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            verifyEncoding(store, base);
            verifySafeLegadoHelpers(store, base, other);
            JSONObject script = source("脚本生成地址", base + "/setup",
                    "<js>const route = { value: '/lookup' }; route.value;</js>"
                            + "@result/<first, older>?keyword={{encodeURIComponent(key)}}"
                            + "&offset={{(page - 1) * 20}}&tag={{({text: 'ok'}).text}},"
                            + new JSONObject().put("headers", new JSONObject().put("X-Page", "{{page}}")));
            ReaderSources.Source generated = store.importText(script.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(generated, "测试 A&B", 1), "已执行脚本地址");
            requireBook(ReaderEngine.search(generated, "测试 A&B", 3), "已执行脚本地址");

            JSONObject post = source("发出 JSON 请求", base + "/settings", "/post,"
                    + new JSONObject().put("method", "POST").put("body", new JSONObject()
                    .put("keyword", "{{key}}").put("offset", "{{(page-1)*25}}")));
            ReaderSources.Source posted = store.importText(post.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(posted, "引号\"", 2), "已处理 JSON 参数");

            String multiline = "/legacy-multiline-options,{\n"
                    + "'charset': 'UTF-8',\n'method': 'POST',\n"
                    + "'body': '{\r\n\t\"keyword\": \"{{key}}\",\r\n\t\"page_num\": 1\r\n}',\n"
                    + "'headers': '{\r\n\t\"X-Format\": \"fixture\"\r\n}'\n}";
            JSONObject legacyOptions = source("多行请求参数", base + "/legacy-multiline-settings", multiline);
            ReaderSources.Source multilineSource = store.prepareText(legacyOptions.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            requireBook(ReaderEngine.search(multilineSource, "验证", 1), "已处理多行请求参数");
            legacyOptions.put("searchUrl", multiline.replace("{{key}}", "%E9%AA%8C%E8%AF%81"));
            ReaderSources.Source plainMultiline = store.prepareText(legacyOptions.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            requireBook(ReaderEngine.search(plainMultiline, "验证", 1), "已处理多行请求参数");

            JSONObject rewritten = source("请求参数改写", base + "/settings-rewrite", "/unused,"
                    + new JSONObject().put("js", JSONObject.quote(other + "/rewritten?token=")
                    + " + encodeURIComponent(key)"))
                    .put("jsLib", new JSONObject().put("external", other + "/unneeded.js").toString())
                    .put("header", new JSONObject().put("Authorization", "Bearer localhost-fixture"));
            ReaderSources.Source adjusted = store.importText(rewritten.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(adjusted, "hello", 2), "已改写至其他站点");

            JSONObject scripted = source("脚本库", base + "/bridge-config", "@js: buildAddress(key)")
                    .put("jsLib", "function buildAddress(key) {"
                            + "let token = java.ajax('/bridge-token'); java.put('token', token);"
                            + "return source.getKey().replace('/bridge-config','/bridge-search')"
                            + " + '?token=' + java.encodeURI(token) + '&hash=' + java.md5Encode(key); }"
                            + "function displayName(name) { return name + java.get('token'); }")
                    .put("header", new JSONObject().put("X-Source", "fixture"));
            scripted.getJSONObject("ruleSearch").put("name", "$.title@js: displayName(result)");
            ReaderSources.Source bridge = store.importText(scripted.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(bridge, "abc", 1), "脚本书名联机令牌");

            JSONObject submitted = source("脚本提交", base + "/bridge-post",
                    "@js: java.post('/bridge-submit', 'searchkey=' + key, {'X-Source': 'fixture'}).header('location')");
            ReaderSources.Source redirected = store.importText(submitted.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(redirected, "测试", 1), "由脚本提交");

            String declaration = "/bridge-template?page={{page}},"
                    + new JSONObject().put("charset", "GBK").put("method", "POST")
                    .put("body", "q={{key}}&page={{page}}")
                    .put("headers", new JSONObject().put("X-Page", "{{page}}"));
            JSONObject templates = source("脚本辅助请求模板", base + "/ajax-template",
                    "@js: java.ajax(" + JSONObject.quote(declaration) + ")");
            ReaderSources.Source template = store.importText(templates.toString(), ReaderSources.NOVEL).sources.get(0);
            requireBook(ReaderEngine.search(template, "测试&", 2), "由脚本提交");
            if (!ReaderEngine.mobileSibling("https://www.example.org", "https://m.example.org/path")
                    || ReaderEngine.mobileSibling("https://www.example.org", "https://m.example.org.evil.net/")
                    || ReaderEngine.mobileSibling("https://www.example.org", "http://m.example.org/")
                    || ReaderEngine.mobileSibling("https://www.example.org", "https://m.example.org:9443/")
                    || !ReaderEngine.publicRequestHeader("Referer") || ReaderEngine.publicRequestHeader("Cookie")
                    || ReaderEngine.publicRequestHeader("Authorization") || ReaderEngine.publicRequestHeader("X-Token")) {
                throw new AssertionError("mobile sibling requests must validate origin and omit credentials");
            }

            JSONObject verification = source("需人工验证的脚本", base + "/human-verification",
                    "@js: java.longToast('提示'); java.getVerificationCode('/verify');");
            ReaderSources.Source requiresHuman = store.importText(verification.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            try {
                ReaderEngine.search(requiresHuman, "验证", 1);
                throw new AssertionError("verification scripts must report the required user action");
            } catch (ReaderSources.SiteFailure expected) {
                if (!expected.getMessage().contains("人工验证")) throw expected;
            }
            JSONObject malformed = source("损坏的脚本", base + "/script-syntax", "@js: const = ;");
            ReaderSources.Source invalidScript = store.importText(malformed.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            try {
                ReaderEngine.search(invalidScript, "验证", 1);
                throw new AssertionError("an invalid script must report a deterministic rule failure");
            } catch (ReaderSources.RuleFailure expected) {
                if (!expected.getMessage().contains("语法有误")) throw expected;
            }

            JSONObject legacyLink = source("裸选择器索引", base + "/indexed-rules", "/legacy-link");
            legacyLink.put("ruleSearch", new JSONObject().put("bookList", ".book-list")
                    .put("name", "h4@text").put("bookUrl", "a.0@href"));
            ReaderSources.Source indexed = store.importText(legacyLink.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderEngine.Book> indexedResults = ReaderEngine.search(indexed, "索引", 1);
            requireBook(indexedResults, "索引书名");
            if (!indexedResults.get(0).url.equals(base + "/legacy-match")) {
                throw new AssertionError("Legado numeric selector suffix failed to select the indexed element");
            }
            legacyLink.getJSONObject("ruleSearch").put("bookUrl", "@css:a.0@href");
            ReaderSources.Source explicit = store.importText(legacyLink.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderEngine.Book> explicitResults = ReaderEngine.search(explicit, "索引", 1);
            requireBook(explicitResults, "索引书名");
            if (!explicitResults.get(0).url.equals(base + "/css-match")) {
                throw new AssertionError("explicit CSS with a numeric class must retain CSS meaning");
            }

            JSONObject outside = source("隔离辅助请求", base + "/bridge-isolation",
                    "@js: java.ajax(" + JSONObject.quote(other + "/rewritten") + ")");
            ReaderSources.Source isolated = store.importText(outside.toString(), ReaderSources.NOVEL).sources.get(0);
            try {
                ReaderEngine.search(isolated, "hello", 1);
                throw new AssertionError("auxiliary URL escaped the source origin");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("须访问来源自身站点")) throw expected;
            }

            String pageScript = "document.querySelector('main').textContent";
            JSONObject browserOptions = new JSONObject().put("webView", true).put("webJs", pageScript)
                    .put("headers", new JSONObject().put("X-Reader", "browser"));
            JSONObject webView = source("需要浏览器", base + "/browser", "/render," + browserOptions);
            ReaderSources.Source rendered = store.importText(webView.toString(), ReaderSources.NOVEL).sources.get(0);
            List<Long> waits = new ArrayList<>();
            ReaderSources.setBrowserLoader((url, headers, scriptText, timeout, settle) -> {
                if (!url.equals(base + "/render") || !pageScript.equals(scriptText)
                        || !"browser".equals(headers.get("X-Reader")) || settle >= timeout) {
                    throw new AssertionError("browser route lost URL, options, script, or deadline");
                }
                waits.add(settle);
                return new ReaderSources.Response(url, "{\"items\":[{\"title\":\"浏览器结果\",\"url\":\"/book\"}]}",
                        "application/json");
            });
            try {
                requireBook(ReaderEngine.search(rendered, "hello", 1), "浏览器结果");
                for (Object delay : List.of(-20, 250, "无效", "1500", 2_300)) {
                    browserOptions.put("webViewDelayTime", delay);
                    webView.put("searchUrl", "/render," + browserOptions);
                    ReaderSources.Source delayed = store.prepareText(webView.toString(), ReaderSources.NOVEL)
                            .sources.get(0);
                    requireBook(ReaderEngine.search(delayed, "hello", 1), "浏览器结果");
                }
                if (!waits.equals(List.of(400L, 400L, 400L, 400L, 1500L, 2300L))) {
                    throw new AssertionError("browser settle options were not normalized: " + waits);
                }
                browserOptions.put("webViewDelayTime", Long.MAX_VALUE);
                webView.put("searchUrl", "/render," + browserOptions);
                ReaderSources.Source overLimit = store.prepareText(webView.toString(), ReaderSources.NOVEL)
                        .sources.get(0);
                try {
                    ReaderEngine.search(overLimit, "hello", 1);
                    throw new AssertionError("a wait beyond the browser deadline must not start a WebView");
                } catch (SocketTimeoutException expected) {
                    // No capture callback should be scheduled beyond the total budget.
                }
                JSONObject webPost = source("需要浏览器提交", base + "/browser-post",
                        "/render,{\"webView\":true,\"method\":\"POST\",\"body\":\"x=1\"}");
                ReaderSources.Source needsPost = store.importText(webPost.toString(), ReaderSources.NOVEL).sources.get(0);
                try {
                    ReaderEngine.search(needsPost, "hello", 1);
                    throw new AssertionError("a browser POST must not silently be sent as GET");
                } catch (IOException expected) {
                    if (!expected.getMessage().contains("浏览器 POST")) throw expected;
                }
            } finally {
                ReaderSources.setBrowserLoader(null);
            }
            System.out.println("PASS Legado URL scripts, library scope, auxiliary request, POST and browser settle timing");
        } finally {
            differentOrigin.stop(0);
            server.stop(0);
        }
    }

    private static void registerSafeLegadoFixtures(HttpServer server) {
        server.createContext("/safe-get", exchange -> {
            try (exchange) {
                boolean valid = "GET".equals(exchange.getRequestMethod())
                        && "fixture".equals(exchange.getRequestHeaders().getFirst("X-Reader"));
                if (valid) {
                    exchange.getResponseHeaders().set("Location", "/safe-destination");
                    exchange.getResponseHeaders().set("Set-Cookie", "script_fixture=present; Path=/; HttpOnly");
                }
                exchange.sendResponseHeaders(valid ? 302 : 400, -1);
            }
        });
        server.createContext("/safe-destination", exchange -> reply(exchange, "脚本地址已解析"));
        server.createContext("/safe-cookie", exchange -> {
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            reply(exchange, "script_fixture=present".equals(cookie) ? "原会话仍存在" : "本来源会话已清除");
        });
        server.createContext("/safe-page", exchange -> reply(exchange,
                "page=3".equals(exchange.getRequestURI().getRawQuery()) ? "逗号仍属于翻页规则" : ""));
        server.createContext("/safe-template", exchange -> {
            String data = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            reply(exchange, "POST".equals(exchange.getRequestMethod()) && "kw=fixture".equals(data)
                    ? "模板内引号已保留" : "");
        });
    }

    private static void verifySafeLegadoHelpers(ReaderSources store, String base, String other) throws Exception {
        JSONObject getter = source("脚本读取 GET 响应", base + "/safe-get-source", "@js: "
                + "var next; if(key==='clear') { cookie.removeCookie(source.getKey()); next='/safe-cookie'; } else {"
                + "var r=java.get('/safe-get',JSON.stringify({'X-Reader':'fixture'}));"
                + "source.bookSourceComment='changed';"
                + "next=r.headers('Set-Cookie').length===0 && typeof cookie.getCookie==='undefined'"
                + " && source.bookSourceComment==='来源备注' ? r.headers('Location')[0] : '/missing';} next;")
                .put("bookSourceComment", "来源备注");
        ReaderSources.Source own = store.importText(getter.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(own, "normal", 1), "脚本地址已解析");
        if (!ReaderSources.fetch(own, base + "/safe-cookie", Map.of(), null, "").body.contains("原会话仍存在")) {
            throw new AssertionError("script GET should use the source's separate HTTP session");
        }

        JSONObject follow = source("脚本读取最终地址", base + "/safe-connect-source", "@js: "
                + "java.connect('/safe-get',{'X-Reader':'fixture'}).raw().request().url();");
        ReaderSources.Source separate = store.importText(follow.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(separate, "normal", 1), "脚本地址已解析");
        requireBook(ReaderEngine.search(own, "clear", 1), "本来源会话已清除");
        if (!ReaderSources.fetch(separate, base + "/safe-cookie", Map.of(), null, "").body.contains("原会话仍存在")) {
            throw new AssertionError("clearing a source session must not clear another source's cookies");
        }

        JSONObject foreign = source("拒绝清除其他站点会话", base + "/safe-source-isolation", "@js: "
                + "cookie.removeCookie(" + JSONObject.quote(other + "/other") + "); '/safe-cookie';");
        ReaderSources.Source isolated = store.importText(foreign.toString(), ReaderSources.NOVEL).sources.get(0);
        try {
            ReaderEngine.search(isolated, "normal", 1);
            throw new AssertionError("cookie clearing escaped source origin");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("来源自身站点")) throw expected;
        }
        JSONObject optional = source("脚本处理未授权的会话清除", base + "/safe-optional-clear", "@js: "
                + "try { cookie.removeCookie(" + JSONObject.quote(other + "/other")
                + "); } catch(e) {} '/safe-destination';");
        ReaderSources.Source defensive = store.importText(optional.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(defensive, "normal", 1), "脚本地址已解析");
        JSONObject page = source("逗号翻页", base + "/safe-page-source", "/safe-page?page=<,{{page}}>");
        ReaderSources.Source paged = store.importText(page.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(paged, "normal", 3), "逗号仍属于翻页规则");
        JSONObject quoted = source("嵌套模板引号", base + "/safe-template-source",
                "/safe-template,{\"method\":\"POST\",\"body\":\"kw={{java.put(\"q\",key)}}\"}");
        ReaderSources.Source templated = store.importText(quoted.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(templated, "fixture", 1), "模板内引号已保留");
        System.out.println("PASS bounded script GET/connect, scoped session clear and legacy option boundaries");
    }

    private static JSONObject source(String name, String url, String search) {
        return new JSONObject().put("bookSourceType", ReaderSources.NOVEL)
                .put("bookSourceName", name).put("bookSourceUrl", url).put("searchUrl", search)
                .put("ruleSearch", new JSONObject().put("bookList", "$.items[*]")
                        .put("name", "$.title").put("bookUrl", "$.url"));
    }

    private static void registerEncodingFixtures(HttpServer server) {
        server.createContext("/encoded/search", exchange -> {
            boolean gbkQuery = "q=%B2%E2%CA%D4".equals(exchange.getRequestURI().getRawQuery());
            reply(exchange, gbkQuery ? "异码中文" : "");
        });
        server.createContext("/encoded/utf8", exchange -> replyEncoded(exchange,
                "text/html", "<head><script>let bogus='charset=GBK';</script></head><p>无标签中文</p>"
                        .getBytes(StandardCharsets.UTF_8)));
        server.createContext("/encoded/header", exchange -> replyEncoded(exchange,
                "text/html; charset=ISO-8859-1", "<head><meta charset='UTF-8'></head><p>Édition</p>"
                        .getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/encoded/meta", exchange -> replyEncoded(exchange,
                "text/html", "<head><meta charset='ISO-8859-1'></head><p>Fête</p>"
                        .getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/encoded/http-equiv", exchange -> replyEncoded(exchange,
                "text/html", "<head><meta http-equiv='Content-Type' content='text/html; charset=ISO-8859-1'></head><p>Café</p>"
                        .getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/encoded/bom", exchange -> replyEncoded(exchange,
                "text/plain; charset=UTF-8", "有 BOM 的正文".getBytes(StandardCharsets.UTF_16)));
        server.createContext("/encoded/fallback", exchange -> replyEncoded(exchange,
                "text/plain", "Été".getBytes(StandardCharsets.ISO_8859_1)));
        server.createContext("/encoded/gb", exchange -> replyEncoded(exchange,
                "text/plain", "旧中文正文".getBytes(Charset.forName("GB18030"))));
    }

    private static void verifyEncoding(ReaderSources store, String base) throws IOException {
        JSONObject legado = source("请求 GBK 响应 UTF-8", base + "/legado-encoding",
                "/encoded/search?q={{key}},{\"charset\":\"GBK\"}");
        ReaderSources.Source novel = store.prepareText(legado.toString(), ReaderSources.NOVEL).sources.get(0);
        requireBook(ReaderEngine.search(novel, "测试", 1), "异码中文");

        JSONObject yiciyuan = new JSONObject().put("bookSourceName", "异次元编码验证")
                .put("bookSourceType", "漫画").put("bookSourceUrl", base + "/manga-encoding")
                .put("ruleSearchUrl", "/encoded/search?q=searchKey|char=GBK")
                .put("ruleSearchList", "$.items").put("ruleSearchName", "$.title")
                .put("ruleSearchNoteUrl", "$.url").put("ruleChapterList", "tag.a");
        ReaderSources.Source comic = store.prepareText(yiciyuan.toString(), ReaderSources.COMIC).sources.get(0);
        requireBook(ReaderEngine.search(comic, "测试", 1), "异码中文");

        requireDecoded(base, "utf8", "GBK", "无标签中文");
        requireDecoded(base, "header", "GBK", "Édition");
        requireDecoded(base, "meta", "GBK", "Fête");
        requireDecoded(base, "http-equiv", "GBK", "Café");
        requireDecoded(base, "bom", "GBK", "有 BOM 的正文");
        requireDecoded(base, "fallback", "ISO-8859-1", "Été");
        requireDecoded(base, "gb", "", "旧中文正文");
        System.out.println("PASS Legado and Yiciyuan GBK request encoding, response BOM/header/meta and fallback decoding");
    }

    private static void requireDecoded(String base, String fixture, String fallback, String expected) throws IOException {
        String value = ReaderSources.fetch(base + "/encoded/" + fixture, Map.of(), null, fallback).body;
        if (!value.contains(expected)) throw new AssertionError("incorrect response decode for " + fixture);
    }

    private static void replyEncoded(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static void reply(HttpExchange exchange, String title) throws IOException {
        JSONArray items = new JSONArray();
        if (!title.isEmpty()) items.put(new JSONObject().put("title", title).put("url", "/book"));
        byte[] data = new JSONObject().put("items", items).toString().getBytes(StandardCharsets.UTF_8);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, data.length);
            exchange.getResponseBody().write(data);
        }
    }

    private static void requireBook(List<ReaderEngine.Book> results, String expected) {
        if (results.size() != 1 || !expected.equals(results.get(0).title)) {
            throw new AssertionError(expected + " · results=" + results.size());
        }
    }
}
