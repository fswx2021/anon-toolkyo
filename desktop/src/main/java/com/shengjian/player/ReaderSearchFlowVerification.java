package com.shengjian.player;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.json.JSONObject;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.UnaryOperator;

final class ReaderSearchFlowVerification {
    private ReaderSearchFlowVerification() {}

    static void verify(Path store) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String address = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/one", exchange -> reply(exchange, "甲"));
        server.createContext("/two", exchange -> reply(exchange, "乙"));
        server.createContext("/search", exchange -> reply(exchange,
                exchange.getRequestURI().getRawQuery().startsWith("page=2&") ? "乙" : "甲"));
        server.start();
        try {
            ReaderSources sources = new ReaderSources(store.toFile(), null);
            JSONObject rules = new JSONObject().put("bookList", "$.items[*]").put("name", "$.title")
                    .put("author", "$.author").put("bookUrl", "$.url");
            String explore = "/<one,two>";
            JSONObject first = new JSONObject().put("bookSourceUrl", address).put("bookSourceName", "甲来源")
                    .put("searchUrl", "/search?page={{page}}&q={{key}}")
                    .put("exploreUrl", "分类::" + explore).put("ruleSearch", rules);
            JSONObject second = new JSONObject(first.toString()).put("bookSourceUrl", address + "/alt")
                    .put("bookSourceName", "乙来源").put("searchUrl", "/one?q={{key}}");
            ReaderSources.Source a = sources.importText(first.toString(), ReaderSources.NOVEL).sources.get(0);
            ReaderSources.Source b = sources.importText(second.toString(), ReaderSources.NOVEL).sources.get(0);
            require(ReaderEngine.canPaginate(a, a.searchUrl) && ReaderEngine.canPaginate(a, explore)
                    && !ReaderEngine.canPaginate(b, b.searchUrl), "only page-aware sources are offered more pages");
            require(ReaderEngine.search(a, "测试", 1).get(0).title.equals("甲")
                    && ReaderEngine.search(a, "测试", 2).get(0).title.equals("乙")
                    && ReaderEngine.explore(a, explore, 2).get(0).title.equals("乙"), "actual search and discovery page 2");
            verifyGroupingAndChapters(a, b);
            System.out.println("PASS search and discovery pagination, work identity and alternate chapter matching");
            verifyNovelText();
        } finally {
            server.stop(0);
        }
    }

    private static void verifyNovelText() {
        String text = "海边🌷 Café。再见，CAFÉ。";
        int first = text.indexOf("Café"), last = text.indexOf("CAFÉ");
        require(NovelTextTools.find(text, "café", first + 1, 1) == last
                && NovelTextTools.find(text, "café", last + 1, 1) == first
                && NovelTextTools.find(text, "café", first - 1, -1) == last
                && NovelTextTools.find(text, "missing", last, -1) < 0,
                "chapter find preserves UTF-16 offsets and wraps in both directions");
        require(NovelTextTools.speechEnd("甲乙🌷丙丁", 0, 3) == 2
                && NovelTextTools.speechEnd("晨光进屋。现在是早晨", 0, 7) == 5,
                "speech chunk splits at sentence or full character boundary");
        var rules = NovelTextTools.literalRules("广告[1]=>\n花朵=>🌷\n🌷=>别名");
        String raw = "广告[1]海边花朵再见";
        var cleaned = NovelTextTools.literalText(raw, rules);
        int farewell = raw.indexOf("再见");
        require(cleaned.display.equals("海边🌷再见") && cleaned.original.equals(raw)
                && cleaned.originalOffset(0) == raw.indexOf("海边")
                && cleaned.originalOffset(cleaned.display.indexOf("再见")) == farewell
                && cleaned.displayOffset(farewell) == cleaned.display.indexOf("再见"),
                "literal cleanup does not chain edits and maps saved original offsets");
        var blank = NovelTextTools.literalText("正文", NovelTextTools.literalRules("正文=>"));
        require(blank.skipped && blank.display.equals("正文"), "cleanup keeps the original if the result is empty");
        System.out.println("PASS chapter text find offsets/wrap and speech chunk boundaries");
    }

    private static void verifyGroupingAndChapters(ReaderSources.Source a, ReaderSources.Source b) {
        ReaderWorkMatcher matcher = new ReaderWorkMatcher(UnaryOperator.identity());
        ReaderEngine.Book original = book(a, "《海边电台》", "作者：  林海", "/work/one");
        ReaderEngine.Book same = book(b, "海边电台", "林海", "/work/now");
        ReaderEngine.Book unknown = book(b, "海边电台", "未知", "/work/other");
        ReaderEngine.Book other = book(b, "海边电台", "海林", "/work/different");
        ReaderEngine.Book edition = book(b, "海边电台（典藏版）", "林海", "/work/edition");
        require(matcher.searchTitleKey(original).equals(matcher.searchTitleKey(same))
                && matcher.searchTitleKey(same).equals(matcher.searchTitleKey(unknown))
                && matcher.searchTitleKey(same).equals(matcher.searchTitleKey(other))
                && !matcher.searchTitleKey(same).equals(matcher.searchTitleKey(edition)), "title grouping with edition qualifiers");
        require(matcher.discoveryKey(original).equals(matcher.discoveryKey(same))
                && !matcher.discoveryKey(same).equals(matcher.discoveryKey(other))
                && !matcher.discoveryKey(same).equals(matcher.discoveryKey(unknown)), "discovery grouping keeps the author");
        require(matcher.alternate(original, same) && matcher.verifiedAuthor(original, same)
                && matcher.alternate(original, unknown) && !matcher.verifiedAuthor(original, unknown)
                && !matcher.alternate(original, other) && !matcher.alternate(original, edition),
                "verified author and manual confirmation for unknown author");
        ReaderEngine.Chapter earlier = chapter(same, "第十章　电台", "1");
        ReaderEngine.Chapter newer = chapter(same, "第十二章：灯亮了", "2");
        ReaderEngine.Chapter different = chapter(same, "第12章 新的一天", "3");
        ReaderWorkMatcher.Match exact = matcher.chapter("第十二章 灯亮了", List.of(earlier, different, newer));
        ReaderWorkMatcher.Match number = matcher.chapter("第12章 灯火", List.of(earlier, newer));
        require(exact.type == ReaderWorkMatcher.ChapterMatch.EXACT && exact.index == 2
                && number.type == ReaderWorkMatcher.ChapterMatch.NUMBER && number.index == 1,
                "match normalized chapter name or unique chapter number");
        require(matcher.chapter("第12章 灯火", List.of(different, newer)).index < 0
                && matcher.chapter("第12章 灯火", List.of(chapter(same, "第二卷 第12章 灯火", "4"))).index < 0
                && matcher.chapter("第12章（上）", List.of(chapter(same, "第12章（下）", "5"))).index < 0,
                "refuse ambiguous numbers, volumes and alternate parts");
    }

    private static ReaderEngine.Book book(ReaderSources.Source source, String title, String author, String link) {
        return new ReaderEngine.Book(source, title, author, "", "", source.url + link, "");
    }

    private static ReaderEngine.Chapter chapter(ReaderEngine.Book book, String title, String id) {
        return new ReaderEngine.Chapter(book, title, book.url + "/" + id);
    }

    private static void reply(HttpExchange exchange, String title) throws IOException {
        byte[] body = ("{\"items\":[{\"title\":\"" + title + "\",\"author\":\"林海\",\"url\":\"/book/" + title
                + "\"}]}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, body.length);
        try (exchange) { exchange.getResponseBody().write(body); }
    }

    private static void require(boolean result, String description) {
        if (!result) throw new AssertionError(description);
    }
}
