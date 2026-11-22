package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

final class ReaderRegexVerification {
    private ReaderRegexVerification() { }

    static void verify(Path catalog) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/search", ex -> html(ex, "<a class='book' href='/book'>样例小说</a>"));
        server.createContext("/book", ex -> html(ex, "<h1>样例小说</h1><a href='/toc'>目录</a>"));
        server.createContext("/toc", ex -> html(ex, """
                <li class="row"><a href="/chapter-1">第一章</a></li>
                <li class="row"><a href="/chapter-2" data-note=" [$末\\尾]">第二章</a></li>
                """));
        server.createContext("/chapter-1", ex -> html(ex, "<main><p>这是用于验证正则目录的样例正文。</p></main>"));
        server.createContext("/chain", ex -> html(ex, """
                entry=无关记录;chapter=/unused!
                <main>entry=第一章;chapter=/chapter-1!entry=第二章;chapter=/chapter-2!</main>
                """));
        server.start();
        try {
            ReaderSources store = new ReaderSources(catalog.toFile(), null);
            JSONObject definition = new JSONObject()
                    .put("bookSourceName", "本地正则样例")
                    .put("bookSourceUrl", base)
                    .put("bookSourceType", 0)
                    .put("searchUrl", "/search?key={{key}}")
                    .put("ruleSearch", new JSONObject().put("bookList", "@css:a.book")
                            .put("name", "text").put("bookUrl", "href"))
                    .put("ruleBookInfo", new JSONObject().put("name", "@css:h1@text")
                            .put("tocUrl", "@css:a@href"))
                    .put("ruleToc", new JSONObject()
                            .put("chapterList", ":<li class=\"row\"><a href=\"([^\"]+)\"(?: data-note=\"([^\"]+)\")?>(.*?)</a></li>")
                            .put("chapterName", "$3$2").put("chapterUrl", "$1<js>result</js>"))
                    .put("ruleContent", new JSONObject().put("content", "@css:main"));
            ReaderSources.Source source = store.prepareText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
            List<ReaderEngine.Book> matches = ReaderEngine.search(source, "样例", 1);
            require(matches.size() == 1, "search");
            ReaderEngine.Book book = ReaderEngine.details(matches.get(0));
            require(book.tocUrl.equals(base + "/toc"), "details");
            List<ReaderEngine.Chapter> chapters = ReaderEngine.chapters(book);
            require(chapters.size() == 2 && chapters.get(0).title.equals("第一章")
                    && chapters.get(1).title.equals("第二章 [$末\\尾]")
                    && chapters.get(1).url.equals(base + "/chapter-2"), "capture substitution and script chaining");
            require(ReaderEngine.open(chapters.get(0)).text.contains("样例正文"), "body");

            definition.getJSONObject("ruleToc")
                    .put("chapterList", ":<main>.*?</main>&&entry=(.*?);chapter=(.*?)!")
                    .put("chapterName", "$1").put("chapterUrl", "$2");
            source = store.prepareText(definition.toString(), ReaderSources.NOVEL).sources.get(0);
            book = new ReaderEngine.Book(source, "样例小说", "", "", "", base + "/book", base + "/chain");
            chapters = ReaderEngine.chapters(book);
            require(chapters.size() == 2 && chapters.get(0).title.equals("第一章"), "multiple regex passes");
        } finally {
            server.stop(0);
        }
        System.out.println("PASS Android Legado regex TOC, capture fields and chapter body");
    }

    private static void require(boolean valid, String stage) {
        if (!valid) throw new AssertionError("Legado regex stage failed: " + stage);
    }

    private static void html(HttpExchange exchange, String text) throws IOException {
        try (exchange) {
            byte[] body = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
