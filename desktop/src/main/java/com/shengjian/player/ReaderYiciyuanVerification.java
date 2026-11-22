package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

final class ReaderYiciyuanVerification {
    private ReaderYiciyuanVerification() {}

    static void verify(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        String image = "/art,{\"headers\":{\"Referer\":" + JSONObject.quote(base + "/read") + "}}";
        String markup = "<img src=\"" + image + "\">";
        String rawScript = "@js:\n" + JSONObject.quote(markup);
        String escaped = JSONObject.quote(rawScript);
        JSONObject normal = new JSONObject().put("bookSourceName", "旧异次元图源")
                .put("bookSourceType", "漫画").put("bookSourceUrl", base)
                .put("ruleSearchUrl", "/search?q=searchKey")
                .put("ruleSearchList", "tag.a").put("ruleSearchName", "text").put("ruleSearchNoteUrl", "href")
                .put("ruleChapterList", "tag.a").put("ruleChapterName", "text").put("ruleContentUrl", "href")
                .put("ruleBookContent", escaped.substring(1, escaped.length() - 1));
        JSONObject encrypted = new JSONObject(normal.toString()).put("bookSourceName", "异次元加密图源")
                .put("bookSourceUrl", base + "/encrypted").put("ruleChapterList", ":_H006_fixture");
        server.createContext("/feed", exchange -> reply(exchange, 200,
                new JSONArray().put(normal).put(encrypted).toString(), "application/json"));
        server.createContext("/search", exchange -> reply(exchange, 200,
                "<a href='/book'>漫画验证</a>", "text/html"));
        server.createContext("/book", exchange -> reply(exchange, 200,
                "<a href='/read'>第一话</a>", "text/html"));
        server.createContext("/read", exchange -> reply(exchange, 200, "", "text/html"));
        server.createContext("/art", exchange -> {
            boolean allowed = (base + "/read").equals(exchange.getRequestHeaders().getFirst("Referer"));
            reply(exchange, allowed ? 200 : 403, "fixture", "image/png");
        });
        server.createContext("/catalog", exchange -> {
            boolean expectedEntry = "keyword=验证".equals(exchange.getRequestURI().getQuery());
            reply(exchange, expectedEntry ? 200 : 400,
                    "<article class='entry' id='1012' title='目录漫画'><h2>测试</h2></article>", "text/html");
        });
        server.createContext("/series/1012", exchange -> reply(exchange, 200,
                "<a class='chapter' title='第一话' onclick=\"open('/read')\">开始阅读</a>", "text/html"));
        server.createContext("/json-search", exchange -> reply(exchange, 200, """
                {"data":{"list":[{"title":"漫画一","id":25},{"title":"漫画二","comic_id":40}]}}
                """, "application/json"));
        server.createContext("/copy-search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean valid = ("keyword=验证&offset=00".equals(query) || "keyword=验证&offset=20".equals(query))
                    && "1".equals(exchange.getRequestHeaders().getFirst("platform"));
            reply(exchange, valid ? 200 : 400, """
                    {"results":{"list":[{"comic":{"name":"分页样本","path_word":"public-slug"}}]}}
                    """, "application/json");
        });
        server.createContext("/copy-computed", exchange -> {
            boolean valid = "keyword=验证样本&category=2&offset=12".equals(exchange.getRequestURI().getQuery())
                    && (base + "/from").equals(exchange.getRequestHeaders().getFirst("Referer"))
                    && "fixture-same-origin".equals(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, valid ? 200 : 400, "<a href='/book'>计算地址</a>", "text/html");
        });
        server.createContext("/copy-cross", exchange -> {
            boolean safe = "bridge=undefined".equals(exchange.getRequestURI().getQuery())
                    && exchange.getRequestHeaders().getFirst("Cookie") == null
                    && exchange.getRequestHeaders().getFirst("Authorization") == null
                    && "public".equals(exchange.getRequestHeaders().getFirst("X-Mode"));
            reply(exchange, safe ? 200 : 400, "<a href='/book'>跨站测试</a>", "text/html");
        });
        server.createContext("/legacy-header-search", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            boolean valid;
            if ("keyword=验证&mode=cookie".equals(query)) {
                valid = "articlevisited=1".equals(exchange.getRequestHeaders().getFirst("Cookie"));
            } else {
                valid = "keyword=验证&mode=copy".equals(query) && hasCurrentCopyVersion(exchange);
            }
            reply(exchange, valid ? 200 : 400, "<a href='/legacy-header-book'>漫画验证</a>", "text/html");
        });
        server.createContext("/legacy-header-book", exchange -> {
            boolean valid;
            if (exchange.getRequestHeaders().getFirst("version") != null) {
                valid = hasCurrentCopyVersion(exchange)
                        && "application/json, text/html".equals(exchange.getRequestHeaders().getFirst("Accept"))
                        && "zh-CN".equals(exchange.getRequestHeaders().getFirst("Accept-Language"));
            } else {
                valid = "fixture-only".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                        && "6.21".equals(exchange.getRequestHeaders().getFirst("appversion"))
                        && "sample".equals(exchange.getRequestHeaders().getFirst("channel"))
                        && "1234".equals(exchange.getRequestHeaders().getFirst("buildnumber"));
            }
            reply(exchange, valid ? 200 : 400, "<a href='/read'>第一话</a>", "text/html");
        });
        server.createContext("/zaimanhua-search", exchange -> {
            String body = switch (exchange.getRequestURI().getQuery()) {
                case "keyword=空列表" -> """
                        {"errno":0,"errmsg":"","data":{"list":[],"page":1,"size":20,"total":0}}
                        """;
                case "keyword=新版列表" -> """
                        {"errno":0,"errmsg":"","data":{"list":[{"id":25,"title":"新版列表"}],"page":1,"size":20,"total":1}}
                        """;
                case "keyword=旧版列表" -> """
                        {"errno":0,"errmsg":"","data":[{"id":40,"title":"旧版列表"}]}
                        """;
                default -> "{}";
            };
            reply(exchange, 200, body, "application/json");
        });
        server.createContext("/dongman-search", exchange -> reply(exchange, 200, """
                <div class='card_wrap'><ul><li><a href='/dongman-book'><p class='subj'>公开漫画</p></a></li></ul></div>
                """, "text/html"));
        server.createContext("/dongman-book", exchange -> {
            boolean first = exchange.getRequestURI().getQuery() == null;
            reply(exchange, 200, first ? """
                    <ul id='_listUl'><li><a href='/later/viewer'><span class='subj'>后话</span></a></li></ul>
                    <div class='paginate'><a href='#'><span class='on'>1</span></a><a href='?page=2'>2</a></div>
                    """ : """
                    <ul id='_listUl'><li><a href='/earlier/viewer'><span class='subj'>前话</span></a></li></ul>
                    <div class='paginate'><a href='/dongman-book'>1</a><a href='#'><span class='on'>2</span></a></div>
                    """, "text/html");
        });
        server.createContext("/earlier/viewer", exchange -> reply(exchange, 200, """
                <img data-url='/unrelated'>
                <div id='_imageList'><img data-url='/art' src='/placeholder'></div>
                """, "text/html"));
        server.createContext("/honkai-catalog", exchange -> reply(exchange, 200,
                "<div class='container' id='1012'><p class='container-title'>公开漫画</p></div>", "text/html"));
        server.createContext("/book/1012", exchange -> reply(exchange, 200, "<h1>公开漫画</h1>", "text/html"));
        server.createContext("/book/1012/get_chapter", exchange -> reply(exchange, 200, """
                [{"bookid":1012,"chapterid":2,"title":"后话"},{"bookid":1012,"chapterid":1,"title":"前话"}]
                """, "application/json"));
        server.createContext("/book/1012/1", exchange -> reply(exchange, 200,
                "<img data-original='/unrelated'><img class='comic_img' data-original='/art'>", "text/html"));
        server.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            ReaderAggregation.Result merged = ReaderAggregation.run(store, ReaderSources.COMIC, null,
                    List.of(new ReaderAggregation.Publisher("图源验证", base + "/feed")));
            if (merged.changes.added != 1 || merged.changes.skipped != 1
                    || !merged.reports.get(0).detail.contains("加密")) {
                throw new AssertionError("automatic import should identify known encrypted directory rules");
            }
            ReaderSources.Source manual = store.importText(encrypted.toString(), ReaderSources.COMIC).sources.get(0);
            if (YiciyuanRules.readingIssue(manual.definition).isEmpty()) {
                throw new AssertionError("manual imports keep the encrypted rule available for inspection");
            }
            ReaderSources.Source source = merged.changes.sources.get(0);
            ReaderEngine.Book book = ReaderEngine.search(source, "验证", 1).get(0);
            ReaderEngine.Page page = ReaderEngine.open(ReaderEngine.chapters(book).get(0));
            if (page.images.size() != 1 || !ReaderEngine.imageAddress(page.images.get(0)).equals(base + "/art")) {
                throw new AssertionError("escaped script and embedded image request options");
            }
            String reply = new String(ReaderSources.fetchImage(ReaderEngine.imageAddress(page.images.get(0)),
                    ReaderEngine.imageRequestHeaders(page.images.get(0))), StandardCharsets.UTF_8);
            if (!reply.equals("fixture")) throw new AssertionError("image referer must be sent on the actual request");
            verifyScopedRules(store, normal, base);
            verifyCopySearchDialect(store, normal, base);
            verifyComputedCopyAddresses(store, normal, base);
            verifyLegacyRequestHeaders(store, normal, base);
            verifyPublicDongman(store, base, catalog.resolveSibling("legacy-dongman.json"));
            verifyPublicHonkai(store, base);
            verifyPublicZaimanhua(store, normal, base, catalog.resolveSibling("legacy-zaimanhua.json"));
        } finally {
            server.stop(0);
        }
        System.out.println("PASS legacy Yiciyuan scripts, actual image Referer and encrypted aggregate detection");
    }

    private static void verifyScopedRules(ReaderSources store, JSONObject template, String base) throws Exception {
        JSONObject html = new JSONObject(template.toString()).put("bookSourceName", "异次元目录").put("bookSourceUrl", base + "/scoped")
                .put("ruleSearchUrl", "/catalog{,_error}?keyword=searchKey").put("ruleSearchList", "class.entry")
                .put("ruleSearchName", "title").put("ruleSearchNoteUrl", "id@js:'/series/' + result")
                .put("ruleChapterList", "class.chapter").put("ruleChapterName", "title")
                .put("ruleContentUrl", "onclick@js:result.slice(6, -2)");
        ReaderSources.Source htmlSource = store.importText(html.toString(), ReaderSources.COMIC).sources.get(0);
        List<ReaderEngine.Book> books = ReaderEngine.search(htmlSource, "验证", 1);
        if (books.size() != 1 || !books.get(0).title.equals("目录漫画") || !books.get(0).url.equals(base + "/series/1012")) {
            throw new AssertionError("Yiciyuan rows must read bare title and id attributes before the link script");
        }
        List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(books.get(0));
        if (chapters.size() != 1 || !chapters.get(0).title.equals("第一话") || !chapters.get(0).url.equals(base + "/read")) {
            throw new AssertionError("Yiciyuan chapter must read bare title and onclick attributes in its scope");
        }
        JSONObject json = new JSONObject(template.toString()).put("bookSourceName", "异次元接口").put("bookSourceUrl", base + "/json")
                .put("ruleSearchUrl", "/json-search?keyword=searchKey").put("ruleSearchList", "$.data.list||$.data")
                .put("ruleSearchName", "$.title").put("ruleSearchNoteUrl", "$.id||$.comic_id<js>'/series/' + result</js>");
        ReaderSources.Source jsonSource = store.importText(json.toString(), ReaderSources.COMIC).sources.get(0);
        List<ReaderEngine.Book> jsonBooks = ReaderEngine.search(jsonSource, "验证", 1);
        if (jsonBooks.size() != 2 || !jsonBooks.get(0).url.equals(base + "/series/25")
                || !jsonBooks.get(1).url.equals(base + "/series/40")) {
            throw new AssertionError("Yiciyuan JSON alternatives must both feed the shared URL script");
        }
        System.out.println("PASS Yiciyuan scoped HTML search and chapters, JS link conversion and JSON field alternatives");
    }

    private static void verifyCopySearchDialect(ReaderSources store, JSONObject template, String base) throws Exception {
        JSONObject legacy = new JSONObject(template.toString()).put("bookSourceName", "页码与变量验证")
                .put("bookSourceUrl", base + "/copy-dialect")
                .put("ruleSearchUrl", "/copy-search?keyword=searchKey&offset=searchPage-10@Header:{platform:1}")
                .put("ruleSearchList", "results.list[*]")
                .put("ruleSearchName", "comic.name@js:result + java.get('slug')")
                .put("ruleSearchNoteUrl", "comic.path_word||path_word@js:java.put('slug', result); '/copy/' + result")
                .put("ruleSearchAuthor", "@js:java.get('slug')")
                .put("ruleSearchIntroduce", "@js:typeof java.ajax");
        ReaderSources.Source source = store.importText(legacy.toString(), ReaderSources.COMIC).sources.get(0);
        for (int page : List.of(1, 3)) {
            List<ReaderEngine.Book> matches = ReaderEngine.search(source, "验证", page);
            if (matches.size() != 1 || !matches.get(0).title.equals("分页样本")
                    || !matches.get(0).url.equals(base + "/copy/public-slug")
                    || !matches.get(0).author.equals("public-slug") || !matches.get(0).summary.equals("undefined")) {
                throw new AssertionError("Yiciyuan request pagination and page-local script variables");
            }
        }
        System.out.println("PASS original Yiciyuan page substitution and response-local variables without network access");
    }

    private static void verifyComputedCopyAddresses(ReaderSources store, JSONObject template, String base) throws Exception {
        JSONObject computed = new JSONObject(template.toString()).put("bookSourceName", "动态地址").put("bookSourceUrl", base + "/computed")
                .put("ruleSearchUrl", """
                        {{
                            var title = searchKey.replace(/@.*/, '').replace(/\\//g, '');
                            var category = searchKey.replace(/.*@|.*$/, '');
                            '/copy-computed?keyword=' + encodeURIComponent(title) + '&category=' + category
                                + '&offset=' + 12 * (searchPage - 1) + '@Header:'
                                + JSON.stringify({Referer: baseUrl.replace('/computed', '/from'), Authorization: 'fixture-same-origin'});
                        }}
                        """);
        ReaderSources.Source sameOrigin = store.importText(computed.toString(), ReaderSources.COMIC).sources.get(0);
        List<ReaderEngine.Book> same = ReaderEngine.search(sameOrigin, "验证/样本@2", 2);
        if (same.size() != 1 || !same.get(0).title.equals("计算地址")) {
            throw new AssertionError("Yiciyuan whole address expression must preserve regex escapes and generate request headers");
        }
        JSONObject crossOrigin = new JSONObject(template.toString()).put("bookSourceName", "动态跨站地址")
                .put("bookSourceUrl", base.replace("127.0.0.1", "localhost") + "/cross")
                .put("header", new JSONObject().put("Cookie", "fixture-local-only"))
                .put("ruleSearchUrl", "<js>" + JSONObject.quote(base + "/copy-cross?bridge=")
                        + " + (typeof java) + '@Header:' + JSON.stringify({Authorization:'fixture-local-only','X-Mode':'public'});</js>");
        ReaderSources.Source external = store.importText(crossOrigin.toString(), ReaderSources.COMIC).sources.get(0);
        List<ReaderEngine.Book> cross = ReaderEngine.search(external, "验证", 1);
        if (cross.size() != 1 || !cross.get(0).title.equals("跨站测试")) {
            throw new AssertionError("computed Yiciyuan routes must strip inherited and generated credentials on another origin");
        }
        crossOrigin.put("ruleSearchUrl", "<js>" + JSONObject.quote(base + "/copy-cross?bridge=")
                + " + (typeof java) + '@Header:{Authorization=fixture-local-only,X-Mode=public}';</js>");
        ReaderSources.Source legacyExternal = store.importText(crossOrigin.toString(), ReaderSources.COMIC).sources.get(0);
        if (ReaderEngine.search(legacyExternal, "验证", 1).size() != 1) {
            throw new AssertionError("generated legacy headers must obey the same origin-bound credential checks");
        }
        System.out.println("PASS Yiciyuan computed address templates, generated headers and origin-bound credentials");
    }

    private static boolean hasCurrentCopyVersion(HttpExchange exchange) {
        LocalDate today = LocalDate.now();
        String version = today.getYear() + "." + today.getMonthValue() + "." + today.getDayOfMonth();
        return "1".equals(exchange.getRequestHeaders().getFirst("platform"))
                && version.equals(exchange.getRequestHeaders().getFirst("version"));
    }

    private static void verifyLegacyRequestHeaders(ReaderSources store, JSONObject template, String base) throws Exception {
        JSONObject cookie = new JSONObject(template.toString()).put("bookSourceName", "旧式 Cookie 图源")
                .put("bookSourceUrl", base + "/legacy-cookie")
                .put("ruleSearchUrl", "/legacy-header-search?keyword=searchKey&mode=cookie@Header:{Cookie=articlevisited=1}");
        ReaderSources.Source cookieSource = store.importText(cookie.toString(), ReaderSources.COMIC).sources.get(0);
        if (ReaderEngine.search(cookieSource, "验证", 1).size() != 1) {
            throw new AssertionError("legacy Cookie value must keep its equals sign on the actual request");
        }

        String version = "platform:1,\"version\":\"{{y = new Date();a = y.getFullYear() + \".\" + (y.getMonth() + 1) + \".\" + y.getDate();}}\"";
        JSONObject copy = new JSONObject(template.toString()).put("bookSourceName", "旧式日期头图源")
                .put("bookSourceUrl", base + "/legacy-copy")
                .put("ruleSearchUrl", "/legacy-header-search?keyword=searchKey&mode=copy@Header:{" + version + "}")
                .put("ruleSearchNoteUrl", "href@Header:{" + version
                        + ",\"Accept\":\"application/json, text/html\",\"Accept-Language\":\"zh-CN\"}");
        ReaderSources.Source copySource = store.importText(copy.toString(), ReaderSources.COMIC).sources.get(0);
        ReaderEngine.Book copyBook = ReaderEngine.search(copySource, "验证", 1).get(0);
        if (ReaderEngine.chapters(copyBook).size() != 1) {
            throw new AssertionError("the legacy version template must become a local date in both request phases");
        }

        JSONObject mixed = new JSONObject(template.toString()).put("bookSourceName", "混合请求头图源")
                .put("bookSourceUrl", base + "/legacy-mixed")
                .put("ruleSearchNoteUrl", "href@Header:{Authorization:\"fixture-only\",appversion:\"6.21\","
                        + "channel=\"sample\",buildnumber=\"1234\",host=\"viewer.invalid\"}");
        ReaderSources.Source mixedSource = store.importText(mixed.toString(), ReaderSources.COMIC).sources.get(0);
        ReaderEngine.Book mixedBook = ReaderEngine.search(mixedSource, "验证", 1).get(0);
        if (ReaderEngine.chapters(mixedBook).size() != 1
                || !"viewer.invalid".equals(ReaderEngine.imageRequestHeaders(mixedBook.url).get("host"))) {
            throw new AssertionError("mixed header separators must preserve the literal values on a result link");
        }

        JSONObject invalid = new JSONObject(cookie.toString()).put("bookSourceName", "非法动态请求头图源")
                .put("bookSourceUrl", base + "/legacy-invalid")
                .put("ruleSearchUrl", "/legacy-header-search?keyword=searchKey&mode=cookie@Header:{Cookie=read('name')}");
        ReaderSources.Source invalidSource = store.importText(invalid.toString(), ReaderSources.COMIC).sources.get(0);
        try {
            ReaderEngine.search(invalidSource, "验证", 1);
            throw new AssertionError("legacy headers must reject function expressions");
        } catch (IOException malformed) {
            if (!(malformed.getCause() instanceof JSONException)) throw malformed;
        }
        System.out.println("PASS Yiciyuan legacy Cookie, literal date and mixed headers on real local requests");
    }

    private static void verifyPublicDongman(ReaderSources store, String base, Path oldCatalog) throws Exception {
        String origin = "https://www.dongmanmanhua.cn";
        JSONObject original = new JSONObject().put("bookSourceName", "咚漫").put("bookSourceType", "漫画")
                .put("bookSourceUrl", origin).put("ruleSearchUrl", origin + "/search?keyword=searchKey")
                .put("ruleSearchList", "@js:var doc=org.jsoup.Jsoup.parse(result); doc.select('.card_wrap>ul li')")
                .put("ruleSearchName", "class.subj@text").put("ruleSearchNoteUrl", "tag.a@href")
                .put("ruleChapterList", ":_H006_fixture").put("ruleBookContent", ":_H006_fixture");
        JSONObject compatible = YiciyuanRules.normalize(original);
        if (!YiciyuanRules.readingIssue(compatible).isEmpty()
                || !"thisPage".equals(compatible.getJSONObject("imageHeaders").getString("Referer"))) {
            throw new AssertionError("legacy official Dongman signature must get public web rules and the viewer Referer");
        }
        JSONObject differentHost = YiciyuanRules.normalize(new JSONObject(original.toString())
                .put("bookSourceUrl", "https://example.invalid"));
        if (YiciyuanRules.readingIssue(differentHost).isEmpty()) {
            throw new AssertionError("special rules must not apply to an unrelated host");
        }
        JSONObject personalized = YiciyuanRules.normalize(new JSONObject(original.toString())
                .put("ruleChapterList", "@css:.custom a").put("ruleBookContent", "@css:img@src"));
        if (!"@css:.custom a".equals(personalized.getJSONObject("ruleToc").getString("chapterList"))) {
            throw new AssertionError("healthy custom rules must not be changed");
        }
        JSONObject legacy = new JSONObject(compatible.toString())
                .put("ruleToc", new JSONObject().put("chapterList", ":_H006_fixture"))
                .put("ruleContent", new JSONObject().put("content", ":_H006_fixture"));
        legacy.remove("imageHeaders");
        Files.writeString(oldCatalog, new JSONArray().put(new JSONObject().put("kind", ReaderSources.COMIC)
                .put("enabled", true).put("definition", legacy)).toString(), StandardCharsets.UTF_8);
        ReaderSources.Source restored = new ReaderSources(oldCatalog.toFile(), null).list(ReaderSources.COMIC).get(0);
        if (!restored.id.equals(ReaderSources.COMIC + ":" + origin) || !restored.enabled
                || !YiciyuanRules.readingIssue(restored.definition).isEmpty()) {
            throw new AssertionError("legacy catalogue reload must keep the same ID and enable the public rules");
        }
        compatible.put("bookSourceUrl", base + "/dongman").put("searchUrl", "/dongman-search?keyword=searchKey");
        ReaderSources.Source source = store.importText(compatible.toString(), ReaderSources.COMIC).sources.get(0);
        ReaderEngine.Book book = ReaderEngine.search(source, "公开漫画", 1).get(0);
        List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(book);
        if (chapters.size() != 2 || !"前话".equals(chapters.get(0).title) || !"后话".equals(chapters.get(1).title)) {
            throw new AssertionError("official Dongman directory pagination and chronology");
        }
        ReaderEngine.Page page = ReaderEngine.open(chapters.get(0));
        if (!page.images.equals(List.of(base + "/art"))) {
            throw new AssertionError("official Dongman page selectors must take only real viewer image addresses");
        }
        System.out.println("PASS official Dongman source upgrade, search, two directory pages and public viewer images");
    }

    private static void verifyPublicHonkai(ReaderSources store, String base) throws Exception {
        String origin = "https://comic.bh3.com";
        JSONObject original = new JSONObject().put("bookSourceName", "崩坏3漫画").put("bookSourceType", "漫画")
                .put("bookSourceUrl", origin).put("ruleSearchUrl", origin + "/book{,_error.html}")
                .put("ruleSearchList", "class.container").put("ruleSearchName", "class.container-title@text")
                .put("ruleSearchNoteUrl", "id@js:\"" + origin + "/book/\"+result")
                .put("ruleChapterList", ":_H006_fixture").put("ruleBookContent", ":_H006_fixture");
        JSONObject compatible = YiciyuanRules.normalize(original);
        if (!YiciyuanRules.readingIssue(compatible).isEmpty()) {
            throw new AssertionError("legacy official Honkai signature must get the public catalogue rules");
        }
        compatible.put("bookSourceUrl", base + "/honkai").put("searchUrl", "/honkai-catalog");
        compatible.getJSONObject("ruleSearch").put("bookUrl", "id@js:'/book/' + result");
        ReaderSources.Source source = store.importText(compatible.toString(), ReaderSources.COMIC).sources.get(0);
        ReaderEngine.Book book = ReaderEngine.search(source, "公开漫画", 1).get(0);
        List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(book);
        if (chapters.size() != 2 || !"前话".equals(chapters.get(0).title)
                || !chapters.get(0).url.equals(base + "/book/1012/1")) {
            throw new AssertionError("official Honkai directory must use the full JSON catalogue and reverse it");
        }
        if (!ReaderEngine.open(chapters.get(0)).images.equals(List.of(base + "/art"))) {
            throw new AssertionError("official Honkai viewer must extract only comic images");
        }
        System.out.println("PASS official Honkai source upgrade, search, full catalogue API and public viewer images");
    }

    private static void verifyPublicZaimanhua(ReaderSources store, JSONObject template, String base, Path oldCatalog)
            throws Exception {
        String origin = "https://v4api.zaimanhua.com";
        String oldList = "$.data.list||$.data";
        String correctedList = "$.data.list||$.data[0:]";
        JSONObject original = new JSONObject(template.toString()).put("bookSourceName", "再漫画")
                .put("bookSourceUrl", origin)
                .put("ruleSearchUrl", origin + "/app/v1/search/index?keyword=searchKey&source=0&page=searchPage&size=20")
                .put("ruleSearchList", oldList).put("ruleSearchName", "$.title")
                .put("ruleSearchNoteUrl", "$.id@js:'/series/' + result");
        JSONObject compatible = YiciyuanRules.normalize(original);
        if (!correctedList.equals(compatible.getJSONObject("ruleSearch").getString("bookList"))) {
            throw new AssertionError("known Zaimanhua source must select only actual arrays from its public search API");
        }
        String customList = "$.data.custom||$.data";
        JSONObject custom = YiciyuanRules.normalize(new JSONObject(original.toString()).put("ruleSearchList", customList));
        JSONObject differentHost = YiciyuanRules.normalize(new JSONObject(original.toString())
                .put("bookSourceUrl", "https://example.invalid"));
        if (!customList.equals(custom.getJSONObject("ruleSearch").getString("bookList"))
                || !oldList.equals(differentHost.getJSONObject("ruleSearch").getString("bookList"))) {
            throw new AssertionError("the Zaimanhua fix must be scoped to the original source and list rule");
        }
        JSONObject legacy = new JSONObject(compatible.toString());
        legacy.getJSONObject("ruleSearch").put("bookList", oldList);
        Files.writeString(oldCatalog, new JSONArray().put(new JSONObject().put("kind", ReaderSources.COMIC)
                .put("enabled", true).put("definition", legacy)).toString(), StandardCharsets.UTF_8);
        ReaderSources.Source restored = new ReaderSources(oldCatalog.toFile(), null).list(ReaderSources.COMIC).get(0);
        if (!restored.id.equals(ReaderSources.COMIC + ":" + origin)
                || !correctedList.equals(restored.definition.getJSONObject("ruleSearch").getString("bookList"))) {
            throw new AssertionError("saved Zaimanhua sources must pick up the new list rule without changing ID");
        }
        compatible.put("bookSourceUrl", base + "/zaimanhua").put("searchUrl", "/zaimanhua-search?keyword=searchKey");
        ReaderSources.Source source = store.importText(compatible.toString(), ReaderSources.COMIC).sources.get(0);
        if (!ReaderEngine.search(source, "空列表", 1).isEmpty()) {
            throw new AssertionError("an empty paginated array is a valid no-result response");
        }
        List<ReaderEngine.Book> paginated = ReaderEngine.search(source, "新版列表", 1);
        List<ReaderEngine.Book> direct = ReaderEngine.search(source, "旧版列表", 1);
        if (paginated.size() != 1 || !paginated.get(0).url.equals(base + "/series/25")
                || direct.size() != 1 || !direct.get(0).url.equals(base + "/series/40")) {
            throw new AssertionError("the Zaimanhua rule must support both paginated and direct result arrays");
        }
        System.out.println("PASS official Zaimanhua source upgrade, empty search and both result array formats");
    }

    private static void reply(HttpExchange exchange, int status, String text, String type) throws IOException {
        try (exchange) {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
