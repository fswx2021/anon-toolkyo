package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class ReaderDomVerification {
    private ReaderDomVerification() {}

    static void verify(Path catalog) throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + host.getAddress().getPort();
        host.createContext("/search", exchange -> reply(exchange, "<section class='results'>"
                + "<a href='/ad'>广告</a><a href='/book/1'>甲</a><a href='/book/2'>乙</a>"
                + "<a href='/footer'>尾部</a></section>"));
        host.createContext("/book/1", exchange -> reply(exchange, "<nav class='toc'>"
                + "<a href='/read/1'>第一章</a><a href='/read/2'>第二章</a>"
                + "<a href='/read/3'>第三章</a><a href='/read/4'>第四章</a></nav>"));
        host.createContext("/read/4", exchange -> reply(exchange, "<div class='content'>"
                + "<p>广告</p><p>正文第一段</p><p>正文第二段</p></div>"));
        host.createContext("/script-data", exchange -> reply(exchange,
                "<script>var book_list=[{\"id\":17,\"title\":\"脚本提取\"}];</script>"));
        host.createContext("/script-dom", exchange -> reply(exchange,
                "<table class='grid'><tr><th>标题</th></tr><tr><td><a href='/book/1'>唯一一行</a>"
                        + "</td></tr></table>"));
        host.createContext("/terminal-step", exchange -> reply(exchange, "<a href='/outside'>无关链接</a>"
                + "<ul class='toplist'><li><span class='s1'><a href='/terminal-toc'>斗罗大陆</a></span></li>"
                + "<li><span class='s1'><a href='/book/2'>斗破苍穹</a></span></li></ul>"));
        host.createContext("/terminal-toc", exchange -> reply(exchange,
                "<table><tbody><tr><th>目录</th></tr><tr><td><a href='/read/4'>第一回</a></td></tr></tbody></table>"));
        host.createContext("/relative-xpath", exchange -> reply(exchange, "<a href='/outside'><p>无关作品</p></a>"
                + "<div class='hot_sale'><a href='/book/1'><p>斗罗大陆</p><p>作者</p></a></div>"));
        host.createContext("/rule-variables", exchange -> reply(exchange,
                "{\"items\":[{\"id\":17,\"slug\":\"first\",\"title\":\"甲\",\"author\":\"作者id\"},"
                        + "{\"id\":29,\"slug\":\"second\",\"title\":\"乙\",\"author\":\"作者id\"}]}"));
        host.createContext("/bare-template", exchange -> {
            JSONObject body = new JSONObject(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            Object page = body.opt("page_index");
            boolean correct = "POST".equals(exchange.getRequestMethod()) && page instanceof Number
                    && ((Number) page).intValue() == 3 && "page=2".equals(body.opt("label"))
                    && "测试\"{{page}}".equals(body.opt("keyword"));
            reply(exchange, correct ? "{\"items\":[{\"title\":\"参数类型正确\",\"url\":\"/book/1\"}]}"
                    : "{\"items\":[]}");
        });
        host.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            verifyLegadoElementLists(store, base);
            JSONObject definition = new JSONObject().put("bookSourceUrl", base)
                    .put("bookSourceName", "阅读 DOM 索引验证")
                    .put("searchUrl", "/search?kw={{key}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "class.results@tag.a[!0,-1]")
                            .put("name", "ownText").put("bookUrl", "@css:a[href]@href"))
                    .put("ruleToc", new JSONObject().put("chapterList", "class.toc@[-1:0:2]")
                            .put("chapterName", "text").put("chapterUrl", "href"))
                    .put("ruleContent", new JSONObject().put("content", "class.content@tag.p[1:-1]@text"));
            ReaderSources.Source source = store.importText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderEngine.Book> books = ReaderEngine.search(source, "验证", 1);
            if (!books.stream().map(book -> book.title).toList().equals(List.of("甲", "乙"))) {
                throw new AssertionError("Legado DOM bracket exclusion and ordinary CSS attribute");
            }
            List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(books.get(0));
            if (!chapters.stream().map(chapter -> chapter.title).toList().equals(List.of("第四章", "第二章"))) {
                throw new AssertionError("Legado reverse stepped range on children");
            }
            if (!ReaderEngine.open(chapters.get(0)).text.equals("正文第一段\n\n正文第二段")) {
                throw new AssertionError("Legado content inclusive range and negative index");
            }
            JSONObject domScript = new JSONObject().put("bookSourceUrl", base + "/dom-settings")
                    .put("bookSourceName", "HTML 列表传给脚本").put("searchUrl", "/script-dom")
                    .put("ruleSearch", new JSONObject().put("bookList", ".grid tr!0<js>"
                            + "result.toArray().map(row => ({name:row.select('a').text(),"
                            + " path:row.select('a').attr('href')}));</js>")
                            .put("name", "name").put("bookUrl", "path@js:book.origin.replace('/dom-settings','')+result"));
            ReaderSources.Source htmlScript = store.importText(domScript.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            books = ReaderEngine.search(htmlScript, "验证", 1);
            if (books.size() != 1 || !books.get(0).title.equals("唯一一行")
                    || !books.get(0).url.equals(base + "/book/1")) {
                throw new AssertionError("Legado DOM script must receive a collection even for one selected row");
            }
            JSONObject jsonScript = new JSONObject().put("bookSourceUrl", base + "/json-settings")
                    .put("bookSourceName", "脚本传给 JSON 路径").put("searchUrl", "/script-data")
                    .put("ruleSearch", new JSONObject().put("bookList",
                            "<js>result.match(/var book_list=([\\s\\S]+?);/)[1];</js>\n$.[*]")
                            .put("name", "$.title").put("bookUrl", base + "/book/{{$.id}},"
                                    + new JSONObject().put("headers", new JSONObject().put("Referer", "{{baseUrl}}"))));
            ReaderSources.Source embedded = store.importText(jsonScript.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            books = ReaderEngine.search(embedded, "验证", 1);
            if (books.size() != 1 || !books.get(0).title.equals("脚本提取")
                    || !books.get(0).url.startsWith(base + "/book/17,")
                    || !books.get(0).url.contains(base + "/script-data")) {
                throw new AssertionError("Legado JavaScript to JSONPath or result link template failed");
            }
            JSONObject variables = new JSONObject().put("bookSourceUrl", base + "/variable-source")
                    .put("bookSourceName", "变量与脚本字段读取")
                    .put("searchUrl", "@js:java.put('root','book'); '/rule-variables';")
                    .put("ruleSearch", new JSONObject().put("bookList", "$.items[*]")
                            .put("name", "$.title@put:{bid:$.id}@js: if(result==='甲') java.put('row','甲'); result;")
                            .put("intro", "@get:{row}").put("author", "$.author##id##@get:{bid}")
                            .put("bookUrl", "/@get:{root}/@get:{bid}/{{java.getString('$.slug')}}"));
            ReaderSources.Source variableSource = store.importText(variables.toString(), ReaderSources.NOVEL)
                    .sources.get(0);
            books = ReaderEngine.search(variableSource, "变量", 1);
            if (books.size() != 2 || !books.get(0).url.equals(base + "/book/17/first")
                    || !books.get(1).url.equals(base + "/book/29/second")
                    || !books.get(0).summary.equals("甲") || !books.get(1).summary.isEmpty()
                    || !books.get(0).author.equals("作者17") || !books.get(1).author.equals("作者29")) {
                throw new AssertionError("Legado variables and script field reads must follow the current result row");
            }
            JSONObject bareTemplate = new JSONObject().put("bookSourceUrl", base + "/bare-template-source")
                    .put("bookSourceName", "省略引号的 JSON 请求模板")
                    .put("searchUrl", "/bare-template,{\"method\":\"POST\",\"body\":{"
                            + "\"keyword\":{{key}},\"page_index\":{{page + 1}},\"label\":\"page={{page}}\"}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "$.items[*]")
                            .put("name", "$.title").put("bookUrl", "$.url"));
            ReaderSources.Source bare = store.importText(bareTemplate.toString(), ReaderSources.NOVEL).sources.get(0);
            books = ReaderEngine.search(bare, "测试\"{{page}}", 2);
            if (books.size() != 1 || !books.get(0).title.equals("参数类型正确")) {
                throw new AssertionError("bare Legado JSON body templates must preserve types and escape the keyword once");
            }
        } finally {
            host.stop(0);
        }
        System.out.println("PASS Legado DOM rules, row variables, scripts and typed JSON request templates");
    }

    private static void verifyLegadoElementLists(ReaderSources store, String base) throws Exception {
        JSONObject list = new JSONObject().put("bookSourceUrl", base + "/terminal-step-origin")
                .put("bookSourceName", "Legado 裸元素步骤").put("searchUrl", "/terminal-step")
                .put("ruleSearch", new JSONObject().put("bookList", "class.toplist@li")
                        .put("name", "class.s1@a@text").put("bookUrl", "class.s1@a@href"))
                .put("ruleToc", new JSONObject().put("chapterList", "tbody@tr!0")
                        .put("chapterName", "td.0@a@text").put("chapterUrl", "td.0@a@href"));
        ReaderSources.Source source = store.importText(list.toString(), ReaderSources.NOVEL).sources.get(0);
        List<ReaderEngine.Book> results = ReaderEngine.search(source, "斗罗大陆", 1);
        if (results.size() != 2 || !results.get(0).title.equals("斗罗大陆")
                || !results.get(0).url.equals(base + "/terminal-toc")) {
            throw new AssertionError("bare terminal HTML steps select elements for a result list");
        }
        List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(results.get(0));
        if (chapters.size() != 1 || !chapters.get(0).title.equals("第一回")) {
            throw new AssertionError("bare terminal HTML step and exclusion select chapter rows");
        }
        list.getJSONObject("ruleSearch").put("bookList", "<js>result</js>class.toplist@li");
        source = store.importText(list.toString(), ReaderSources.NOVEL).sources.get(0);
        if (ReaderEngine.search(source, "斗罗大陆", 1).size() != 2) {
            throw new AssertionError("node selection must remain active after a JavaScript stage");
        }
        JSONObject xpath = new JSONObject().put("bookSourceUrl", base + "/relative-xpath-origin")
                .put("bookSourceName", "Legado 条目 XPath").put("searchUrl", "/relative-xpath")
                .put("ruleSearch", new JSONObject().put("bookList", "//div[@class='hot_sale']")
                        .put("name", "/a/p[1]/text()").put("bookUrl", "/a/@href"));
        ReaderSources.Source nested = store.importText(xpath.toString(), ReaderSources.NOVEL).sources.get(0);
        results = ReaderEngine.search(nested, "斗罗大陆", 1);
        if (results.size() != 1 || !results.get(0).title.equals("斗罗大陆")
                || !results.get(0).url.equals(base + "/book/1")) {
            throw new AssertionError("XPath child paths must remain relative to the selected result");
        }
        System.out.println("PASS Legado terminal list steps, chapter rows and row-relative XPath");
    }

    static void verifySessions(Path catalog) throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + host.getAddress().getPort();
        host.createContext("/start", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Set-Cookie", "reader_fixture=ok; Path=/; HttpOnly");
                exchange.getResponseHeaders().set("Location", "/search");
                exchange.sendResponseHeaders(302, -1);
            }
        });
        host.createContext("/search", exchange -> withSession(exchange, "<a href='/book'>原书</a>"));
        host.createContext("/book", exchange -> withSession(exchange, "<h1>请求状态已沿用</h1>"));
        host.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            JSONObject definition = new JSONObject().put("bookSourceUrl", base)
                    .put("bookSourceName", "会话测试").put("searchUrl", "/start?kw={{key}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "tag.a")
                            .put("name", "text").put("bookUrl", "href"))
                    .put("ruleBookInfo", new JSONObject().put("name", "tag.h1@text"));
            ReaderSources.Source source = store.importText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderEngine.Book> books = ReaderEngine.search(source, "会话", 1);
            if (!ReaderEngine.details(books.get(0)).title.equals("请求状态已沿用")) {
                throw new AssertionError("search redirects and details need the same source session");
            }
            definition.put("bookSourceUrl", base + "/other").put("bookSourceName", "另一来源")
                    .put("searchUrl", "/search?kw={{key}}");
            ReaderSources.Source other = store.importText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
            try {
                ReaderEngine.search(other, "会话", 1);
                throw new AssertionError("different sources must retain separate cookies");
            } catch (IOException expected) {
                if (!expected.getMessage().contains("403")) throw expected;
            }
        } finally {
            host.stop(0);
        }
        System.out.println("PASS source session across redirects and details without cross-source cookies");
    }

    private static void withSession(HttpExchange exchange, String html) throws IOException {
        if ("reader_fixture=ok".equals(exchange.getRequestHeaders().getFirst("Cookie"))) {
            reply(exchange, html);
        } else {
            try (exchange) {
                exchange.sendResponseHeaders(403, -1);
            }
        }
    }

    private static void reply(HttpExchange exchange, String html) throws IOException {
        try (exchange) {
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }
}
