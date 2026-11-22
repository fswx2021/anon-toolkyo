package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;
import org.jsoup.select.Selector;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.shengjian.player.ReaderRuleSupport.splitTop;
import static com.shengjian.player.ReaderRuleSupport.topLevelOffset;

final class ReaderEngine {
    private static final int MAX_TOC_PAGES = 24;
    private static final int MAX_CONTENT_PAGES = 16;
    private static final int MAX_MATCHES = 12000;
    private static final int MAX_REGEX_CAPTURE_CHARS = 4_000_000;
    private static final Pattern REGEX_GROUP_FIELD = Pattern.compile("\\$([1-9][0-9]?)");
    private static final int MAX_PROSE_CHARS = 2_000_000;
    private static final int MAX_IMAGES = 800;
    private static final long SCRIPT_TIME_PER_RESPONSE_NS = 4_000_000_000L;
    private static final Pattern TEMPLATE = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*\\}\\}");
    private static final Pattern LEGADO_GET = Pattern.compile("(?i)@get:\\{([^{}\\r\\n]{1,256})\\}");
    private static final Pattern LEGADO_FIELD = Pattern.compile(LEGADO_GET.pattern() + "|" + TEMPLATE.pattern());
    private static final Pattern PAGE_MATH = Pattern.compile("page(?:\\s*([+-])\\s*(\\d{1,6}))?");
    private static final Pattern PAGE_SCRIPT_ARGUMENT = Pattern.compile("\\b(?:page|searchPage)\\b");
    private static final Pattern LEGADO_PAGE_OPTIONS = Pattern.compile("<([^<>\\r\\n]*,[^<>\\r\\n]*)>");
    private static final Pattern YICYUAN_FIELD = Pattern.compile("(?<!\\{)\\{\\s*([^{}:,]+?)\\s*\\}(?!\\})");
    private static final Pattern YICYUAN_FIELDS = Pattern.compile(TEMPLATE.pattern() + "|" + YICYUAN_FIELD.pattern());
    private static final Pattern YICYUAN_PAGE_OPTION = Pattern.compile("(?<!\\{)\\{([^{}]*,[^{}]*)\\}(?!\\})");
    // The original substitutes literal searchPage-1 before searchPage and keeps any following digits.
    private static final Pattern YICYUAN_TEMPLATE = Pattern.compile(
            TEMPLATE.pattern() + "|(searchKey)|(searchPage)(?:([+-])(1))?");
    private static final Pattern PAGE_OFFSET = Pattern.compile(
            "(?:(\\d{1,6})\\s*\\*\\s*)?\\(?\\s*(?:searchPage|page)(?:\\s*([+-])\\s*(\\d{1,6}))?\\s*\\)?");
    private static final Pattern YICYUAN_POST = Pattern.compile("@(?=[A-Za-z0-9_.%-]+=)");
    private static final Pattern YICYUAN_HEADER = Pattern.compile("(?i)@header:\\s*\\{");
    private static final int MAX_YICYUAN_HEADER_CHARS = 65_536;
    private static final String YICYUAN_HEADER_DATE =
            "{{y = new Date();a = y.getFullYear() + \".\" + (y.getMonth() + 1) + \".\" + y.getDate();}}";
    private static final Pattern YICYUAN_HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Pattern YICYUAN_HEADER_VALUE = Pattern.compile("[!#$%&'*+./:;=?@^_`|~0-9A-Za-z\\t -]+");
    private static final Pattern URL_OPTIONS = Pattern.compile(",\\s*\\{(?!\\{)");
    private static final Pattern LEGADO_STEP = Pattern.compile("(?i)(class|id|tag|text)\\.(.+)");
    private static final Pattern STEP_INDEX = Pattern.compile("^(.*?)([.!])(-?\\d{1,5}(?::-?\\d{1,5})*)$");
    private static final Pattern BRACKET_INDEX = Pattern.compile("^(.*?)\\[\\s*(!?)\\s*([0-9,:\\s-]{1,4096})\\]$");
    private static final Pattern HTML_MARKUP = Pattern.compile("(?s)</?[a-zA-Z][^<>]*>");
    private static final Pattern EXCESS_BREAKS = Pattern.compile("\\n{3,}");
    private static final Pattern AROUND_BREAKS = Pattern.compile("[\\t \\x0b]*\\n[\\t \\x0b]*");

    private ReaderEngine() {}

    static final class Book {
        final ReaderSources.Source source;
        final String title;
        final String author;
        final String summary;
        final String cover;
        final String url;
        final String tocUrl;

        Book(ReaderSources.Source source, String title, String author, String summary,
             String cover, String url, String tocUrl) {
            this.source = source;
            this.title = title;
            this.author = author;
            this.summary = summary;
            this.cover = cover;
            this.url = url;
            this.tocUrl = tocUrl;
        }
    }

    static final class Chapter {
        final Book book;
        final String title;
        final String url;
        String nextChapterUrl = "";

        Chapter(Book book, String title, String url) {
            this.book = book;
            this.title = title;
            this.url = url;
        }
    }

    static final class Page {
        final String text;
        final List<String> images;
        final String url;

        Page(String text, List<String> images, String url) {
            this.text = text;
            this.images = images;
            this.url = url;
        }
    }

    static final class Explore {
        final String title;
        final String url;

        Explore(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private static final class Data {
        final String url;
        final String raw;
        final Object root;
        final boolean yiciyuan;
        private final YiciyuanScript.LegadoScope scripting;
        private final YiciyuanScript.MangaVariables mangaVariables;
        private long scriptTime;
        private int scriptCalls, variableDepth;

        Data(ReaderSources.Source source, ReaderSources.Response response, YiciyuanScript.LegadoScope scripting)
                throws IOException {
            url = response.url;
            raw = response.body;
            yiciyuan = yiciyuan(source);
            this.scripting = scripting;
            mangaVariables = yiciyuan ? new YiciyuanScript.MangaVariables() : null;
            String body = response.body.trim();
            if (body.startsWith("\ufeff")) body = body.substring(1).trim();
            if (body.startsWith("{") || body.startsWith("[")) {
                try {
                    root = new JSONTokener(body).nextValue();
                } catch (JSONException malformed) {
                    throw new IOException("来源返回了无法读取的 JSON", malformed);
                }
            } else {
                root = Jsoup.parse(body, url);
            }
        }

        void beginRuleRow() {
            if (scripting != null) scripting.beginRow();
        }

        Object runScript(String rule, Object input, Object ruleRoot) throws IOException {
            if (++scriptCalls > 3000 || scriptTime >= SCRIPT_TIME_PER_RESPONSE_NS) {
                throw new IOException("网页的来源脚本超过处理限制");
            }
            long started = System.nanoTime();
            long networkBefore = scripting == null ? 0 : scripting.networkTime();
            try {
                if (yiciyuan) return YiciyuanScript.evaluateYiciyuan(rule, input, url, mangaVariables);
                return scripting == null ? YiciyuanScript.evaluate(rule, input, url)
                        : YiciyuanScript.evaluateLegado(rule, input, url, scripting,
                                selector -> scriptField(this, ruleRoot, selector));
            } finally {
                long networkElapsed = scripting == null ? 0 : scripting.networkTime() - networkBefore;
                scriptTime += System.nanoTime() - started - networkElapsed;
            }
        }
    }

    static List<Book> search(ReaderSources.Source source, String query, int page) throws IOException {
        if (source.searchUrl.isEmpty()) throw new IOException("此来源没有提供搜索入口");
        return findBooks(source, source.searchUrl, query, page, section(source, "ruleSearch"));
    }

    static ReaderSearchQueue.Route searchRoute(ReaderSources.Source source) {
        return searchRoute(source, source.searchUrl);
    }

    static ReaderSearchQueue.Route searchRoute(ReaderSources.Source source, String request) {
        String declaration = request.toLowerCase(Locale.ROOT);
        boolean browser = declaration.contains("webview") || declaration.contains("webjs");
        if (browser) {
            Matcher options = requestOptions(request);
            if (options != null) {
                try {
                    String declarationOptions = request.substring(options.end() - 1);
                    browser = usesBrowser(yiciyuan(source) ? new JSONObject(declarationOptions)
                            : parseLegadoOptions(declarationOptions, 1));
                } catch (JSONException dynamicDeclaration) {
                    // The script still has to generate its request options.
                }
            }
        }
        String address = declaration.startsWith("http://") || declaration.startsWith("https://")
                ? request : source.url;
        try {
            URL target = declaration.startsWith("//") ? new URL(new URL(source.url), request)
                    : new URL(address);
            String site = target.getHost().toLowerCase(Locale.ROOT);
            if (site.startsWith("www.")) site = site.substring(4);
            if (site.startsWith("m.")) site = site.substring(2);
            if (!site.isEmpty()) return new ReaderSearchQueue.Route(site, browser);
        } catch (MalformedURLException ignored) {
            // A script or template can supply the actual request address at runtime.
        }
        return new ReaderSearchQueue.Route(source.id, browser);
    }

    static boolean canPaginate(ReaderSources.Source source, String request) {
        if (request.contains("<page>")) return true;
        Matcher template = TEMPLATE.matcher(request);
        while (template.find()) {
            if (PAGE_SCRIPT_ARGUMENT.matcher(template.group(1)).find()) return true;
        }
        if (yiciyuan(source)) {
            return request.contains("searchPage") || YICYUAN_PAGE_OPTION.matcher(request).find();
        }
        int script = scriptOffset(request.toLowerCase(Locale.ROOT));
        if (script >= 0 && PAGE_SCRIPT_ARGUMENT.matcher(request.substring(script)).find()) return true;
        Matcher options = requestOptions(request);
        return LEGADO_PAGE_OPTIONS.matcher(options == null ? request : request.substring(0, options.start())).find();
    }

    private static boolean usesBrowser(JSONObject options) {
        String value = field(options, "webView");
        return !field(options, "webJs").isEmpty() || (!value.isEmpty() && !value.equalsIgnoreCase("false"));
    }

    static List<Explore> exploreEntries(ReaderSources.Source source) throws IOException {
        String declaration = source.exploreUrl.trim();
        if (declaration.isEmpty()) return Collections.emptyList();
        List<Explore> entries = new ArrayList<>();
        if (declaration.startsWith("[")) {
            try {
                JSONArray array = new JSONArray(declaration);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject entry = array.optJSONObject(i);
                    if (entry != null && !field(entry, "url").isEmpty()) {
                        entries.add(new Explore(field(entry, "title", "name"), field(entry, "url")));
                    }
                }
            } catch (JSONException malformed) {
                throw new IOException("发现入口格式无法解析", malformed);
            }
        } else {
            String separatorPattern = yiciyuan(source) ? "[\\r\\n]+|&&" : "[\\r\\n]+";
            for (String line : declaration.split(separatorPattern)) {
                rejectScript(line);
                int separator = line.indexOf("::");
                String title = separator < 0 ? "发现" : line.substring(0, separator).trim();
                String url = separator < 0 ? line.trim() : line.substring(separator + 2).trim();
                if (!url.isEmpty()) entries.add(new Explore(title, url));
            }
        }
        return entries;
    }

    static List<Book> explore(ReaderSources.Source source, String entryUrl, int page) throws IOException {
        JSONObject rules = section(source, "ruleExplore");
        if (rules.length() == 0) rules = section(source, "ruleSearch");
        return findBooks(source, entryUrl, "", page, rules);
    }

    private static List<Book> findBooks(ReaderSources.Source source, String url, String query, int page,
                                        JSONObject rules) throws IOException {
        String list = field(rules, "bookList");
        if (list.isEmpty()) throw new ReaderSources.RuleFailure("此来源缺少书籍列表规则");
        Data data = download(source, source.url, url, query, page, true);
        checkSearchResponse(data);
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        boolean legadoSearch = !data.yiciyuan && !query.trim().isEmpty();
        String detailPattern = legadoSearch ? field(source.definition, "bookUrlPattern") : "";
        if (!detailPattern.isEmpty() && matchesBookUrl(data.url, detailPattern)) {
            Book detail = searchDetail(source, data, "", budget);
            if (detail != null) return Collections.singletonList(detail);
        }
        boolean reverse = !data.yiciyuan && list.startsWith("-");
        if (reverse || !data.yiciyuan && list.startsWith("+")) list = list.substring(1).trim();
        String titleRule = field(rules, "name", "title");
        String urlRule = field(rules, "bookUrl", "url");
        String authorRule = field(rules, "author");
        String summaryRule = field(rules, "intro", "summary");
        String coverRule = field(rules, "coverUrl", "cover");
        String tocRule = field(rules, "tocUrl");
        List<Object> rows = selectRows(data, data.root, list);
        Map<String, Book> books = new LinkedHashMap<>();
        IOException entryFailure = null;
        for (Object row : rows) {
            if (budget != null) budget.check();
            data.beginRuleRow();
            String title;
            String bookUrl;
            try {
                title = first(data, row, titleRule);
                bookUrl = link(data.url, first(data, row, urlRule));
            } catch (IOException failed) {
                checkSearchInterrupted(budget);
                if (entryFailure == null) entryFailure = failed;
                continue;
            }
            if (title.isEmpty() || bookUrl.isEmpty() || books.containsKey(bookUrl)) continue;
            Book book = new Book(source, title, optionalSearchField(data, row, authorRule, budget),
                    optionalSearchField(data, row, summaryRule, budget),
                    link(data.url, optionalSearchField(data, row, coverRule, budget)), bookUrl,
                    link(data.url, optionalSearchField(data, row, tocRule, budget)));
            books.put(bookUrl, book);
        }
        if (books.isEmpty() && !rows.isEmpty()) {
            if (entryFailure != null) throw entryFailure;
            throw new IOException("列表规则命中了条目，但没能提取作品名称和链接");
        }
        if (rows.isEmpty()) {
            checkEmptySearchResponse(data.root);
            if (legadoSearch && detailPattern.isEmpty()) {
                Book detail = searchDetail(source, data, query.trim(), budget);
                if (detail != null) return Collections.singletonList(detail);
            }
        }
        List<Book> result = new ArrayList<>(books.values());
        if (reverse) Collections.reverse(result);
        return result;
    }

    private static boolean matchesBookUrl(String url, String rule) {
        if (rule.length() > 1500 || url.length() > 4096) return false;
        try {
            return Pattern.compile(rule).matcher(new LimitedText(url, 0, url.length(),
                    System.nanoTime() + 120_000_000L)).matches();
        } catch (IllegalArgumentException | RegexTimeout | StackOverflowError invalid) {
            return false;
        }
    }

    private static Book searchDetail(ReaderSources.Source source, Data data, String expectedTitle,
                                     ReaderSearchQueue.RequestBudget budget) throws IOException {
        JSONObject rules = section(source, "ruleBookInfo");
        String titleRule = field(rules, "name", "title");
        if (titleRule.isEmpty()) return null;
        Object root;
        try {
            root = infoRoot(data, rules);
        } catch (IOException failed) {
            checkSearchInterrupted(budget);
            return null;
        }
        String title = optionalSearchField(data, root, titleRule, budget);
        if (title.isEmpty() || (!expectedTitle.isEmpty()
                && !new ReaderSearchRanking(expectedTitle, text -> text).exactTitle(title))) return null;
        return new Book(source, title, optionalSearchField(data, root, field(rules, "author"), budget),
                optionalSearchField(data, root, field(rules, "intro", "summary"), budget),
                link(data.url, optionalSearchField(data, root, field(rules, "coverUrl", "cover"), budget)),
                data.url, link(data.url, optionalSearchField(data, root, field(rules, "tocUrl"), budget)));
    }

    private static void checkSearchResponse(Data data) throws ReaderSources.SiteFailure {
        if (!(data.root instanceof Document)) return;
        Document document = (Document) data.root;
        String title = document.title().trim().toLowerCase(Locale.ROOT);
        if (title.equals("无法显示此网页") || title.equals("网站暂时无法访问")
                || title.equals("service unavailable") || title.equals("bad gateway")) {
            throw new ReaderSources.SiteFailure("来源站点返回了错误页面");
        }
        if (title.startsWith("需要登录 - ")) {
            throw new ReaderSources.SiteFailure("来源站点要求登录后搜索");
        }
        if (title.equals("just a moment...") || title.startsWith("attention required! | cloudflare")
                || document.selectFirst("form#challenge-form, script[src*='/cdn-cgi/challenge-platform/']") != null
                || document.body().children().isEmpty() && document.body().text().isEmpty()
                    && document.selectFirst("head script[src*='/probe.js?']") != null
                    && data.raw.contains("buid")) {
            throw new ReaderSources.SiteFailure("来源站点要求完成访问验证");
        }
    }

    private static void checkEmptySearchResponse(Object root) throws ReaderSources.SiteFailure {
        if (!(root instanceof JSONObject)) return;
        JSONObject json = (JSONObject) root;
        if (!json.has("message") && !json.has("msg") && json.optBoolean("success", true)) return;
        int code = json.optInt("code", json.optInt("status", 0));
        if (code == 401 || code == 403) {
            throw new ReaderSources.SiteFailure("来源接口拒绝访问（代码 " + code + "）");
        }
        if (code == 429) throw new ReaderSources.SiteFailure("来源接口限制请求频率，请稍后重试");
        if (code >= 500 && code <= 599) {
            throw new ReaderSources.SiteFailure("来源接口暂时无法处理请求（代码 " + code + "）");
        }
    }

    private static String optionalSearchField(Data data, Object row, String rule,
                                              ReaderSearchQueue.RequestBudget budget) throws IOException {
        try {
            return first(data, row, rule);
        } catch (IOException failed) {
            checkSearchInterrupted(budget);
            return "";
        }
    }

    private static void checkSearchInterrupted(ReaderSearchQueue.RequestBudget budget) throws IOException {
        if (budget != null) budget.check();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("搜索已取消");
    }

    static Book details(Book book) throws IOException {
        JSONObject rules = section(book.source, "ruleBookInfo");
        if (rules.length() == 0) return book;
        Data data = download(book.source, book.source.url, book.url, "", 1);
        Object root = infoRoot(data, rules);
        return new Book(book.source,
                or(first(data, root, field(rules, "name", "title")), book.title),
                or(first(data, root, field(rules, "author")), book.author),
                or(first(data, root, field(rules, "intro", "summary")), book.summary),
                or(link(data.url, first(data, root, field(rules, "coverUrl", "cover"))), book.cover),
                book.url, or(link(data.url, first(data, root, field(rules, "tocUrl"))), book.tocUrl));
    }

    private static Object infoRoot(Data data, JSONObject rules) throws IOException {
        String init = field(rules, "init");
        if (init.isEmpty()) return data.root;
        List<Object> scopes = select(data, data.root, init);
        return scopes.isEmpty() ? data.root : scopes.get(0);
    }

    static List<Chapter> chapters(Book book) throws IOException {
        ReaderSources.Source source = book.source;
        JSONObject info = section(source, "ruleBookInfo");
        JSONObject toc = section(source, "ruleToc");
        String listRule = field(toc, "chapterList");
        if (yiciyuan(source) && YiciyuanRules.isEncryptedRule(listRule)) {
            throw new IOException("此异次元图源的章节目录规则已加密");
        }
        if (listRule.isEmpty()) throw new IOException("此来源缺少目录规则");
        boolean reverse = listRule.startsWith("-");
        if (reverse || listRule.startsWith("+")) listRule = listRule.substring(1).trim();
        String tocUrl = book.tocUrl;
        Data data = null;
        if (!field(info, "tocUrl").isEmpty() && tocUrl.isEmpty()) {
            data = download(source, source.url, book.url, "", 1);
            tocUrl = link(data.url, first(data, infoRoot(data, info), field(info, "tocUrl")));
            if (!tocUrl.isEmpty() && !plainUrl(tocUrl).equals(data.url)) data = null;
        }
        if (tocUrl.isEmpty()) tocUrl = book.url;

        Set<String> visited = new LinkedHashSet<>();
        Map<String, Chapter> found = new LinkedHashMap<>();
        String nextRule = field(toc, "nextTocUrl");
        String childrenRule = field(toc, "chapterChildList");
        for (int page = 1; page <= MAX_TOC_PAGES && !tocUrl.isEmpty(); page++) {
            if (data == null) data = download(source, source.url, tocUrl, "", page);
            if (!visited.add(data.url)) break;
            for (Object group : selectRows(data, data.root, listRule)) {
                List<Object> rows = childrenRule.isEmpty()
                        ? Collections.singletonList(group) : selectRows(data, group, childrenRule);
                for (Object row : rows) {
                    data.beginRuleRow();
                    String title = first(data, row, field(toc, "chapterName", "title"));
                    if (title.isEmpty() && data.yiciyuan) {
                        title = first(data, row, row instanceof JSONObject ? "$.name|$.title|$.text" : "text");
                    }
                    String chapterUrl = link(data.url, first(data, row, field(toc, "chapterUrl", "url")));
                    if (!title.isEmpty() && !chapterUrl.isEmpty()) {
                        found.putIfAbsent(chapterUrl, new Chapter(book, title, chapterUrl));
                    }
                }
            }
            tocUrl = nextLink(data, nextRule, visited, "");
            data = null;
        }
        if (found.isEmpty()) throw new IOException("目录没有解析到章节，请检查来源规则");
        List<Chapter> result = new ArrayList<>(found.values());
        if (reverse) Collections.reverse(result);
        for (int i = 0; i + 1 < result.size(); i++) {
            result.get(i).nextChapterUrl = result.get(i + 1).url;
        }
        return result;
    }

    static Page open(Chapter chapter) throws IOException {
        ReaderSources.Source source = chapter.book.source;
        JSONObject rules = section(source, "ruleContent");
        String contentRule = field(rules, "content");
        if (contentRule.isEmpty()) throw new IOException("此来源缺少正文或图片规则");
        boolean comic = source.kind == ReaderSources.COMIC;
        if (comic && yiciyuan(source) && YiciyuanRules.isEncryptedRule(contentRule)) {
            throw new IOException("此异次元图源的图片提取规则已加密");
        }
        if (comic && yiciyuan(source) && !field(rules, "imageDecoder").isEmpty()) {
            throw new IOException("此异次元来源需要专用图片解码器，当前无法读取");
        }
        String url = chapter.url;
        String firstUrl = "";
        Set<String> visited = new LinkedHashSet<>();
        Set<String> pictures = new LinkedHashSet<>();
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= MAX_CONTENT_PAGES && !url.isEmpty(); page++) {
            Data data = download(source, source.url, url, "", page);
            if (!visited.add(data.url)) break;
            if (firstUrl.isEmpty()) firstUrl = data.url;
            for (Object part : select(data, data.root, contentRule)) {
                if (comic) {
                    addImages(pictures, part, data.url, data.yiciyuan);
                } else {
                    String paragraph = narrative(part);
                    if (!paragraph.isEmpty()) {
                        if (paragraph.length() > MAX_PROSE_CHARS - text.length()) {
                            throw new IOException("章节正文过长，请检查来源规则是否提取了整页内容");
                        }
                        if (text.length() > 0) text.append("\n\n");
                        text.append(paragraph);
                    }
                }
            }
            url = nextLink(data, field(rules, "nextContentUrl"), visited, chapter.nextChapterUrl);
        }
        String prose = text.toString().trim();
        String replacement = field(rules, "replaceRegex");
        if (!comic && !replacement.isEmpty() && !prose.isEmpty()) {
            prose = replace(prose, replacement.startsWith("##") ? replacement.substring(2) : replacement).trim();
        }
        if (comic && pictures.isEmpty()) throw new IOException("没有解析到漫画图片，请检查来源规则");
        if (!comic && prose.isEmpty()) throw new IOException("没有解析到小说正文，请检查来源规则");
        return new Page(prose, new ArrayList<>(pictures), firstUrl);
    }

    private static Data download(ReaderSources.Source source, String base, String declaration, String query,
                                 int page) throws IOException {
        return download(source, base, declaration, query, page, false);
    }

    private static Data download(ReaderSources.Source source, String base, String declaration, String query,
                                 int page, boolean configuredEntry) throws IOException {
        boolean yiciyuan = yiciyuan(source);
        RequestScript scripting = yiciyuan ? null : new RequestScript(source, base, query, page);
        String mangaScript = yiciyuan ? yiciyuanAddressScript(declaration.trim()) : "";
        boolean computedMangaAddress = !mangaScript.isEmpty();
        String rawUrl;
        if (yiciyuan) {
            if (computedMangaAddress) {
                Object address = YiciyuanScript.evaluateYiciyuanUrl(mangaScript, base, query, page);
                if (!(address instanceof String) || ((String) address).length() > 65_536) {
                    throw new IOException("异次元地址脚本没有返回有效的请求地址");
                }
                rawUrl = normalizeHeaders(((String) address).trim(), base);
            } else {
                rejectScript(declaration);
                rawUrl = normalizeHeaders(declaration.trim(), base);
            }
        } else {
            rawUrl = scriptAddress(declaration.trim(), scripting);
        }
        if (rawUrl.isEmpty()) throw new IOException("来源没有提供有效的网页地址");
        JSONObject options = new JSONObject();
        Matcher match = requestOptions(rawUrl);
        if (match != null) {
            try {
                String declarationOptions = rawUrl.substring(match.end() - 1);
                options = yiciyuan ? new JSONObject(declarationOptions) : parseLegadoOptions(declarationOptions, page);
            } catch (JSONException malformed) {
                throw new IOException("来源请求参数无法解析", malformed);
            }
            rawUrl = rawUrl.substring(0, match.start());
        }
        String legacyBody = null;
        if (yiciyuan) {
            int encoding = rawUrl.lastIndexOf("|char=");
            if (encoding >= 0) {
                try {
                    options.put("charset", rawUrl.substring(encoding + 6));
                } catch (JSONException malformed) {
                    throw new IOException("异次元来源的字符编码无效", malformed);
                }
                rawUrl = rawUrl.substring(0, encoding);
            }
            Matcher legacyPost = YICYUAN_POST.matcher(rawUrl);
            if (legacyPost.find() && field(options, "method").isEmpty()) {
                legacyBody = rawUrl.substring(legacyPost.end());
                rawUrl = rawUrl.substring(0, legacyPost.start());
            }
        }
        String rewrite = field(options, "js");
        String webScript = field(options, "webJs");
        boolean browser = usesBrowser(options);
        if (!rewrite.isEmpty() && yiciyuan) {
            throw new IOException("此异次元来源依赖专用地址脚本");
        }
        String charset = field(options, "charset");
        Charset encoding = StandardCharsets.UTF_8;
        if ("escape".equalsIgnoreCase(charset)) charset = "";
        if (!charset.isEmpty()) {
            try {
                encoding = Charset.forName(charset);
            } catch (IllegalArgumentException unsupported) {
                throw new IOException("不支持的来源字符编码：" + charset, unsupported);
            }
        }
        String keyword = URLEncoder.encode(query, encoding.name());
        String expandedUrl = computedMangaAddress ? rawUrl : expand(rawUrl, keyword, query, base, page, yiciyuan, scripting);
        String url = resolve(base, yiciyuan ? expandedUrl : chooseLegadoPage(expandedUrl, page));
        if (!rewrite.isEmpty() && !url.isEmpty()) {
            String adjusted = scripting.run(rewrite, url);
            if (!adjusted.isEmpty()) url = resolve(url, adjusted);
        }
        if (url.isEmpty()) throw new IOException("来源没有提供有效的网页地址");

        Map<String, String> headers = source.headers();
        boolean crossOrigin = !sameOrigin(source.url, url);
        if (crossOrigin && (!configuredEntry || computedMangaAddress || (scripting != null && scripting.calls > 0))) {
            headers.keySet().removeIf(ReaderEngine::credentialHeader);
        }
        JSONObject extraHeaders = object(options, "headers");
        for (java.util.Iterator<String> keys = extraHeaders.keys(); keys.hasNext(); ) {
            String name = keys.next();
            if (computedMangaAddress && crossOrigin && credentialHeader(name)) continue;
            String value = extraHeaders.optString(name, "");
            headers.put(name, computedMangaAddress ? value : expand(value, query, query, base, page, yiciyuan, scripting));
        }
        String method = field(options, "method").toUpperCase(Locale.ROOT);
        if (!method.isEmpty() && !method.equals("GET") && !method.equals("POST")) {
            throw new ReaderSources.RuleFailure("来源使用了不支持的请求方法");
        }
        Object body = options.opt("body");
        boolean jsonBody = body instanceof JSONObject || body instanceof JSONArray;
        String postBody = null;
        if (legacyBody != null) {
            postBody = computedMangaAddress ? legacyBody : expand(legacyBody, keyword, query, base, page, true, null);
        } else if (method.equals("POST")) {
            String encoded = jsonBody ? jsonStringFragment(query) : keyword;
            String declaredBody = field(options, "body");
            postBody = computedMangaAddress ? declaredBody
                    : expand(declaredBody, encoded, query, base, page, yiciyuan, scripting);
            if (jsonBody && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
                headers.put("Content-Type", "application/json; charset=UTF-8");
            }
        }
        ReaderSources.Response response;
        if (browser) {
            if (postBody != null) throw new ReaderSources.RuleFailure("此来源需要浏览器 POST，当前版本无法执行");
            response = ReaderSources.fetchBrowser(url, headers, webScript, options.optLong("webViewDelayTime", 0));
        } else {
            response = ReaderSources.fetch(source, url, headers, postBody, charset);
        }
        return new Data(source, response, scripting == null ? null : scripting.state);
    }

    private static String yiciyuanAddressScript(String declaration) {
        if (YiciyuanScript.matches(declaration)) return declaration;
        if (!declaration.startsWith("{{") || !declaration.endsWith("}}")
                || templateEnd(declaration, 2) != declaration.length() - 2) return "";
        String expression = declaration.substring(2, declaration.length() - 2).trim();
        if (expression.isEmpty() || expression.equals("key") || expression.equals("keyword")
                || expression.equals("searchKey") || expression.equals("rawKey") || expression.equals("baseUrl")
                || PAGE_OFFSET.matcher(expression).matches()) return "";
        return "@js:" + expression;
    }

    private static boolean credentialHeader(String name) {
        return name.equalsIgnoreCase("Authorization") || name.equalsIgnoreCase("Proxy-Authorization")
                || name.equalsIgnoreCase("Cookie");
    }

    static ReaderSources.Response scriptAjax(ReaderSources.Source source, String base, String declaration,
                                             String query, int page)
            throws IOException {
        return scriptRequest(source, base, declaration, query, page, Collections.emptyMap());
    }

    static ReaderSources.Response scriptConnect(ReaderSources.Source source, String base, String declaration,
                                               String query, int page, Map<String, String> headers)
            throws IOException {
        ReaderSources.Response response = scriptRequest(source, base, declaration, query, page, headers);
        if (!sameOrigin(source.url, response.url) && !mobileSibling(source.url, response.url)) {
            throw new IOException("来源脚本的有效地址跳转到其他站点");
        }
        return response;
    }

    private static ReaderSources.Response scriptRequest(ReaderSources.Source source, String base, String declaration,
                                                       String query, int page, Map<String, String> suppliedHeaders)
            throws IOException {
        if (declaration.length() > 65_536) throw new IOException("来源脚本的辅助请求过长");
        JSONObject options = new JSONObject();
        String address = declaration.trim();
        Matcher suffix = requestOptions(address);
        if (suffix != null) {
            try {
                options = parseLegadoOptions(address.substring(suffix.end() - 1), page);
            } catch (JSONException invalid) {
                throw new IOException("来源脚本的辅助请求参数无法解析", invalid);
            }
            address = address.substring(0, suffix.start());
        }
        if (options.has("js") || options.has("webJs") || options.optBoolean("webView")) {
            throw new IOException("来源脚本的辅助请求不支持再次执行脚本或浏览器操作");
        }
        String charset = field(options, "charset");
        Charset encoding = StandardCharsets.UTF_8;
        if (charset.equalsIgnoreCase("escape")) charset = "";
        if (!charset.isEmpty()) {
            try {
                encoding = Charset.forName(charset);
            } catch (IllegalArgumentException unsupported) {
                throw new IOException("不支持的来源字符编码：" + charset, unsupported);
            }
        }
        String keyword = URLEncoder.encode(query, encoding.name());
        String expanded = expandLegado(address, keyword, query, base, page, null);
        String url = resolve(base, chooseLegadoPage(expanded, page));
        boolean same = sameOrigin(source.url, url);
        if (url.isEmpty() || !same && !mobileSibling(source.url, url)) {
            throw new IOException("来源脚本的辅助请求须访问来源自身站点");
        }
        String method = field(options, "method").toUpperCase(Locale.ROOT);
        if (!method.isEmpty() && !method.equals("GET") && !method.equals("POST")) {
            throw new IOException("来源脚本的辅助请求仅支持 GET 或 POST");
        }
        Map<String, String> headers = same ? source.headers() : new LinkedHashMap<>();
        for (Map.Entry<String, String> extra : suppliedHeaders.entrySet()) {
            if (same || publicRequestHeader(extra.getKey())) headers.put(extra.getKey(), extra.getValue());
        }
        JSONObject custom = object(options, "headers");
        for (java.util.Iterator<String> keys = custom.keys(); keys.hasNext(); ) {
            String name = keys.next();
            if (same || publicRequestHeader(name)) {
                headers.put(name, expandLegado(custom.optString(name, ""), query, query, base, page, null));
            }
        }
        Object payload = options.opt("body");
        boolean jsonBody = payload instanceof JSONObject || payload instanceof JSONArray;
        String body = method.equals("POST") ? expandLegado(options.optString("body", ""),
                jsonBody ? jsonStringFragment(query) : keyword, query, base, page, null) : null;
        if (body != null && jsonBody && headers.keySet().stream().noneMatch("Content-Type"::equalsIgnoreCase)) {
            headers.put("Content-Type", "application/json; charset=UTF-8");
        }
        return ReaderSources.fetch(source, url, headers, body, charset);
    }

    private static Matcher requestOptions(String address) {
        Matcher matcher = URL_OPTIONS.matcher(address);
        for (int offset = 0; offset < address.length(); offset++) {
            if (address.startsWith("{{", offset)) {
                int end = templateEnd(address, offset + 2);
                if (end < 0) return null;
                offset = end + 1;
            } else if (address.charAt(offset) == ',' && matcher.region(offset, address.length()).lookingAt()) {
                return matcher;
            }
        }
        return null;
    }

    static JSONObject parseLegadoOptions(String raw, int page) throws JSONException {
        try {
            return new JSONObject(normalizeLegadoOptions(raw, page));
        } catch (JSONException invalid) {
            String recovered = escapeOptionStrings(raw);
            if (recovered.equals(raw)) throw invalid;
            return new JSONObject(normalizeLegadoOptions(recovered, page));
        }
    }

    private static String escapeOptionStrings(String raw) {
        StringBuilder escaped = new StringBuilder(raw.length());
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char value = raw.charAt(i);
            if (raw.startsWith("{{", i)) {
                int close = templateEnd(raw, i + 2);
                if (close < 0) return raw;
                int end = close + 2;
                while (i < end) {
                    char item = raw.charAt(i++);
                    if (quote != 0 && item == '\\' && i < end) {
                        escaped.append(item).append(raw.charAt(i++));
                    } else {
                        if (quote != 0 && item == quote) escaped.append('\\');
                        appendOptionCharacter(escaped, item, quote);
                    }
                }
                i--;
                continue;
            }
            appendOptionCharacter(escaped, value, quote);
            if (quote != 0 && value == '\\' && i + 1 < raw.length()) escaped.append(raw.charAt(++i));
            else if (quote != 0 && value == quote) quote = 0;
            else if (quote == 0 && (value == '\'' || value == '"')) quote = value;
        }
        return escaped.toString();
    }

    private static void appendOptionCharacter(StringBuilder escaped, char value, char quote) {
        // Older request declarations can embed a formatted JSON document inside a quoted value.
        if (quote != 0 && value == '\n') escaped.append("\\n");
        else if (quote != 0 && value == '\r') escaped.append("\\r");
        else if (quote != 0 && value == '\t') escaped.append("\\t");
        else escaped.append(value);
    }

    static String normalizeLegadoOptions(String options, int page) {
        if (!options.contains("{{")) return options;
        StringBuilder normalized = new StringBuilder(options.length());
        Matcher tokens = TEMPLATE.matcher(options);
        char quote = 0;
        for (int index = 0; index < options.length(); index++) {
            char ch = options.charAt(index);
            if (quote != 0 && ch == '\\' && index + 1 < options.length()) {
                normalized.append(ch).append(options.charAt(++index));
                continue;
            }
            if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (options.startsWith("{{", index) && tokens.region(index, options.length()).lookingAt()) {
                String expression = tokens.group(1).trim();
                if (expression.equals("key") || expression.equals("keyword")) {
                    normalized.append('"').append(tokens.group()).append('"');
                    index = tokens.end() - 1;
                    continue;
                }
                Matcher numeric = PAGE_MATH.matcher(expression);
                if (numeric.matches()) {
                    normalized.append(pageValue(numeric.group(1), numeric.group(2), null, page));
                    index = tokens.end() - 1;
                    continue;
                }
            }
            normalized.append(ch);
        }
        return normalized.toString();
    }

    static boolean mobileSibling(String first, String second) {
        try {
            URL a = new URL(first);
            URL b = new URL(second);
            if (!a.getProtocol().equals("https") || !b.getProtocol().equals("https")
                    || (a.getPort() != -1 && a.getPort() != 443) || (b.getPort() != -1 && b.getPort() != 443)) {
                return false;
            }
            String left = a.getHost().toLowerCase(Locale.ROOT);
            String right = b.getHost().toLowerCase(Locale.ROOT);
            if (left.startsWith("www.")) return right.equals("m." + left.substring(4));
            if (left.startsWith("m.")) return right.equals("www." + left.substring(2));
        } catch (MalformedURLException invalid) {
            return false;
        }
        return false;
    }

    static boolean publicRequestHeader(String name) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "accept": case "accept-language": case "content-type": case "user-agent":
            case "referer": case "origin": case "x-requested-with": return true;
            default: return false;
        }
    }

    static ReaderSources.Response scriptPost(ReaderSources.Source source, String base, String address,
                                             String body, Map<String, String> headers) throws IOException {
        String url = scriptAddress(source, base, address);
        Map<String, String> merged = source.headers();
        merged.putAll(headers);
        return ReaderSources.fetchScriptPost(source, url, merged, body);
    }

    static ReaderSources.Response scriptGet(ReaderSources.Source source, String base, String address,
                                            Map<String, String> headers) throws IOException {
        String url = scriptAddress(source, base, address);
        Map<String, String> merged = source.headers();
        merged.putAll(headers);
        return ReaderSources.fetchScriptGet(source, url, merged);
    }

    static void clearScriptSession(ReaderSources.Source source, String base, String address) throws IOException {
        ReaderSources.clearScriptSession(source, scriptAddress(source, base, address));
    }

    private static String scriptAddress(ReaderSources.Source source, String base, String address) throws IOException {
        String url = resolve(base, address);
        if (url.isEmpty() || !sameOrigin(source.url, url)) {
            throw new IOException("来源脚本的辅助请求须访问来源自身站点");
        }
        return url;
    }

    private static String jsonStringFragment(String raw) {
        String quoted = JSONObject.quote(raw);
        return quoted.substring(1, quoted.length() - 1);
    }

    private static String expand(String declaration, String keyword, String query, String base, int page,
                                 boolean yiciyuan, RequestScript scripting)
            throws IOException {
        if (!yiciyuan) {
            return expandLegado(declaration, keyword, query, base, page, scripting);
        }
        declaration = expandPageOptions(declaration, page);
        Matcher matcher = YICYUAN_TEMPLATE.matcher(declaration);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1);
            String value;
            if (expression == null) {
                value = matcher.group(2) != null ? keyword
                        : pageValue(matcher.group(4), matcher.group(5), null, page);
            } else {
                value = expandExpression(expression.trim(), keyword, query, base, page, true, null);
            }
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString().replace("<page>", Integer.toString(page));
    }

    private static String expandExpression(String expression, String keyword, String rawKey, String base,
                                           int page, boolean yiciyuan, RequestScript scripting) throws IOException {
        if ("key".equals(expression) || "keyword".equals(expression)
                || (yiciyuan && "searchKey".equals(expression))) return keyword;
        if ("rawKey".equals(expression)) return rawKey;
        if ("baseUrl".equals(expression)) return base;
        Matcher math = (yiciyuan ? PAGE_OFFSET : PAGE_MATH).matcher(expression);
        if (math.matches()) {
            return yiciyuan ? pageValue(math.group(2), math.group(3), math.group(1), page)
                    : pageValue(math.group(1), math.group(2), null, page);
        }
        if (scripting != null) {
            return scripting.run(expression, "");
        }
        throw new IOException("此来源的地址模板包含不支持的表达式");
    }

    private static String scriptAddress(String declaration, RequestScript scripting) throws IOException {
        String result = declaration;
        String remaining = declaration;
        while (true) {
            String lower = remaining.toLowerCase(Locale.ROOT);
            int start = scriptOffset(lower);
            if (start < 0) {
                String suffix = remaining.trim();
                if (scripting.calls == 0) return suffix;
                return suffix.isEmpty() ? result : suffix.replace("@result", result);
            }
            String prefix = remaining.substring(0, start).trim();
            if (!prefix.isEmpty()) result = prefix.replace("@result", result);
            int end = remaining.length();
            if (lower.startsWith("<js>", start)) {
                int closing = lower.indexOf("</js>", start + 4);
                if (closing < 0) throw new IOException("来源地址脚本缺少结束标记 </js>");
                end = closing + 5;
            }
            result = scripting.run(remaining.substring(start, end), result);
            remaining = remaining.substring(end);
        }
    }

    private static String expandLegado(String declaration, String keyword, String query, String base, int page,
                                      RequestScript scripting) throws IOException {
        StringBuilder expanded = new StringBuilder();
        int start = 0;
        int opening;
        while ((opening = declaration.indexOf("{{", start)) >= 0) {
            int end = templateEnd(declaration, opening + 2);
            if (end < 0) throw new IOException("来源地址模板缺少结束括号");
            expanded.append(declaration, start, opening);
            String expression = declaration.substring(opening + 2, end).trim();
            expanded.append(expandExpression(expression, keyword, query, base, page, false, scripting));
            start = end + 2;
        }
        expanded.append(declaration, start, declaration.length());
        return expanded.toString().replace("<page>", Integer.toString(page));
    }

    private static String chooseLegadoPage(String address, int page) {
        Matcher choices = LEGADO_PAGE_OPTIONS.matcher(address);
        if (!choices.find()) return address;
        StringBuffer chosen = new StringBuffer();
        do {
            String[] pages = choices.group(1).split(",", -1);
            String value = pages[Math.max(0, Math.min(page - 1, pages.length - 1))].trim();
            choices.appendReplacement(chosen, Matcher.quoteReplacement(value));
        } while (choices.find());
        choices.appendTail(chosen);
        return chosen.toString();
    }

    private static int templateEnd(String value, int start) {
        int braces = 0;
        char quote = 0;
        for (int i = start; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\') i++;
            else if (quote != 0) {
                if (character == quote) quote = 0;
            } else if (character == '"' || character == '\'' || character == '\u0060') quote = character;
            else if (character == '{') braces++;
            else if (character == '}') {
                if (braces > 0) braces--;
                else if (i + 1 < value.length() && value.charAt(i + 1) == '}') return i;
            }
        }
        return -1;
    }

    private static final class RequestScript {
        final String base;
        final String key;
        final int page;
        final YiciyuanScript.LegadoScope state;
        int calls;
        long elapsed;

        RequestScript(ReaderSources.Source source, String base, String key, int page) {
            this.base = base;
            this.key = key;
            this.page = page;
            state = new YiciyuanScript.LegadoScope(source, key, page);
        }

        String run(String rule, String input) throws IOException {
            ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
            if (budget != null) budget.check();
            if (++calls > 24 || elapsed >= SCRIPT_TIME_PER_RESPONSE_NS) {
                throw new IOException("来源地址脚本超过运行限制");
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("来源地址处理已取消");
            long started = System.nanoTime();
            long networkBefore = state.networkTime();
            Object value;
            try {
                value = YiciyuanScript.evaluateUrl(rule, input, base, key, page, state);
            } finally {
                elapsed += System.nanoTime() - started - (state.networkTime() - networkBefore);
            }
            if (budget != null) budget.check();
            if (value == null || value == JSONObject.NULL) return "";
            if (value instanceof Number) {
                double number = ((Number) value).doubleValue();
                if (number == Math.rint(number) && number >= Long.MIN_VALUE && number < Long.MAX_VALUE) {
                    return Long.toString((long) number);
                }
            } else if (!(value instanceof String) && !(value instanceof Boolean)) {
                throw new IOException("来源地址脚本没有返回文字或数字");
            }
            return value.toString();
        }
    }

    private static String expandPageOptions(String declaration, int page) {
        Matcher matcher = YICYUAN_PAGE_OPTION.matcher(declaration);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String[] choices = matcher.group(1).split(",", -1);
            int index = Math.max(0, Math.min(page - 1, choices.length - 1));
            matcher.appendReplacement(result, Matcher.quoteReplacement(choices[index]));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String pageValue(String sign, String amount, String factor, int page) {
        long offset = amount == null ? 0 : Long.parseLong(amount);
        long multiplier = factor == null ? 1 : Long.parseLong(factor);
        return Long.toString((page + ("-".equals(sign) ? -offset : offset)) * multiplier);
    }

    private static String nextLink(Data data, String rule, Set<String> visited, String stopAt)
            throws IOException {
        if (rule.isEmpty()) return "";
        String stopUrl = plainUrl(stopAt);
        for (Object item : select(data, data.root, rule)) {
            String next = link(data.url, scalar(item));
            String plain = plainUrl(next);
            if (!next.isEmpty() && !plain.equals(stopUrl) && !visited.contains(plain)) return next;
        }
        return "";
    }

    private static String plainUrl(String value) {
        Matcher options = URL_OPTIONS.matcher(value);
        String url = options.find() ? value.substring(0, options.start()) : value;
        int fragment = url.indexOf('#');
        return fragment < 0 ? url : url.substring(0, fragment);
    }

    static String imageAddress(String image) {
        return plainUrl(image);
    }

    static Map<String, String> imageRequestHeaders(String image) {
        Matcher suffix = URL_OPTIONS.matcher(image);
        if (!suffix.find()) return Collections.emptyMap();
        try {
            JSONObject options = new JSONObject(image.substring(image.indexOf('{', suffix.start())));
            return headerMap(object(options, "headers"));
        } catch (JSONException | IOException malformed) {
            return Collections.emptyMap();
        }
    }

    private static Map<String, String> headerMap(JSONObject object) {
        Map<String, String> result = new LinkedHashMap<>();
        for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!object.isNull(key)) result.put(key, object.optString(key, ""));
        }
        return result;
    }

    private static String link(String base, String reference) {
        Matcher split = URL_OPTIONS.matcher(reference);
        if (!split.find()) return resolve(base, reference);
        String address = resolve(base, reference.substring(0, split.start()));
        return address.isEmpty() ? "" : address + reference.substring(split.start());
    }

    private static String normalizeHeaders(String declaration, String base) throws IOException {
        Matcher header = YICYUAN_HEADER.matcher(declaration);
        if (!header.find()) return declaration;
        int start = declaration.indexOf('{', header.start());
        try {
            if (declaration.length() - start > MAX_YICYUAN_HEADER_CHARS) throw new JSONException("Header suffix too long");
            String suffix = declaration.substring(start);
            // Some exported headers contain this date template with unescaped inner quotes.
            // Expand the known template as data without handing the header to a script engine.
            String dateTemplate = '"' + YICYUAN_HEADER_DATE + '"';
            if (suffix.contains(dateTemplate)) {
                LocalDate today = LocalDate.now();
                suffix = suffix.replace(dateTemplate,
                        JSONObject.quote(today.getYear() + "." + today.getMonthValue() + "." + today.getDayOfMonth()));
            }
            JSONObject values = yiciyuanHeaderValues(suffix);
            for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
                String key = keys.next();
                if (values.optString(key).equalsIgnoreCase("host")) {
                    URL origin = new URL(base);
                    values.put(key, origin.getProtocol() + "://" + origin.getAuthority() + "/");
                }
            }
            return declaration.substring(0, header.start()).trim() + ","
                    + new JSONObject().put("headers", values);
        } catch (JSONException | MalformedURLException malformed) {
            throw new IOException("异次元来源的请求头规则格式有误", malformed);
        }
    }

    private static JSONObject yiciyuanHeaderValues(String suffix) throws JSONException {
        try {
            JSONTokener parser = new JSONTokener(suffix);
            Object parsed = parser.nextValue();
            if (parsed instanceof JSONObject && parser.nextClean() == 0) return (JSONObject) parsed;
            throw new JSONException("Invalid header suffix");
        } catch (JSONException malformed) {
            // Legacy exports may mix JSON colons with assignments such as Cookie=articlevisited=1.
            // Only accept a flat set of literal fields, keeping later equals signs in the value.
            JSONTokener parser = new JSONTokener(suffix);
            if (parser.nextClean() != '{') throw malformed;
            JSONObject values = new JSONObject();
            boolean assignment = false;
            do {
                String name = yiciyuanHeaderPart(parser, true);
                char separator = parser.nextClean();
                if (!YICYUAN_HEADER_NAME.matcher(name).matches() || (separator != ':' && separator != '=')) {
                    throw malformed;
                }
                assignment |= separator == '=';
                values.put(name, yiciyuanHeaderPart(parser, false));
                separator = parser.nextClean();
                if (separator == '}' && assignment && parser.nextClean() == 0) return values;
                if (separator != ',') throw malformed;
            } while (parser.more());
            throw malformed;
        }
    }

    private static String yiciyuanHeaderPart(JSONTokener parser, boolean name) throws JSONException {
        char next = parser.nextClean();
        if (next == '"' || next == '\'') return parser.nextString(next);
        StringBuilder literal = new StringBuilder();
        while (next != 0) {
            if (next == ',' || next == '}' || (name && (next == ':' || next == '='))) {
                parser.back();
                break;
            }
            literal.append(next);
            next = parser.next();
        }
        String value = literal.toString().trim();
        if (!name && !YICYUAN_HEADER_VALUE.matcher(value).matches()) {
            throw new JSONException("Invalid literal header value");
        }
        return value;
    }

    private static String resolve(String base, String reference) {
        String value = reference.trim();
        if (value.isEmpty() || value.startsWith("#")) return "";
        try {
            URL origin = new URL(base);
            if (value.startsWith("?")) value = origin.getPath() + value;
            URL url = new URL(origin, value.replace(" ", "%20"));
            if (url.getHost().isEmpty() || !(url.getProtocol().equals("http") || url.getProtocol().equals("https"))
                    || url.getUserInfo() != null) return "";
            return plainUrl(url.toExternalForm());
        } catch (MalformedURLException invalid) {
            return "";
        }
    }

    static boolean sameOrigin(String first, String second) {
        try {
            URL a = new URL(first);
            URL b = new URL(second);
            int aPort = a.getPort() < 0 ? a.getDefaultPort() : a.getPort();
            int bPort = b.getPort() < 0 ? b.getDefaultPort() : b.getPort();
            return a.getProtocol().equalsIgnoreCase(b.getProtocol())
                    && a.getHost().equalsIgnoreCase(b.getHost()) && aPort == bPort;
        } catch (MalformedURLException invalid) {
            return false;
        }
    }

    private static JSONObject section(ReaderSources.Source source, String key) throws IOException {
        return object(source.definition, key);
    }

    private static boolean yiciyuan(ReaderSources.Source source) {
        return source.definition.optBoolean("_yiciyuan", false);
    }

    private static JSONObject object(JSONObject owner, String key) throws IOException {
        JSONObject value = owner.optJSONObject(key);
        if (value != null) return value;
        String text = field(owner, key);
        if (text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (JSONException malformed) {
            throw new IOException("来源规则 " + key + " 格式有误", malformed);
        }
    }

    private static String field(JSONObject owner, String... keys) {
        for (String key : keys) {
            String value = owner.optString(key, "").trim();
            if (!value.isEmpty() && !owner.isNull(key)) return value;
        }
        return "";
    }

    private static String or(String preferred, String fallback) {
        return preferred.isEmpty() ? fallback : preferred;
    }

    private static void rejectScript(String rule) throws IOException {
        String lower = rule.toLowerCase(Locale.ROOT);
        if (lower.contains("@js:") || lower.contains("<js>") || lower.contains("</js>")
                || lower.startsWith("js:") || lower.startsWith("javascript:")
                || lower.contains("@get:{") || lower.contains("@put:{")) {
            throw new IOException("此来源依赖脚本或动态规则");
        }
    }

    private static String first(Data data, Object root, String rule) throws IOException {
        return firstValue(select(data, root, rule));
    }

    private static String firstValue(List<Object> matches) {
        for (Object match : matches) {
            String value = scalar(match).trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static List<Object> select(Data data, Object root, String rule) throws IOException {
        return select(data, root, rule, false);
    }

    private static List<Object> selectRows(Data data, Object root, String rule) throws IOException {
        return select(data, root, rule, true);
    }

    private static List<Object> select(Data data, Object root, String rule, boolean rows) throws IOException {
        if (rule.isEmpty()) return Collections.emptyList();
        String headerSuffix = "";
        if (data.yiciyuan) {
            if (YiciyuanRules.isEncryptedRule(rule)) throw new IOException("此异次元来源使用了加密规则");
            rule = YiciyuanRules.restoreEscapedRule(rule);
            Matcher header = YICYUAN_HEADER.matcher(rule);
            if (header.find()) {
                String normalized = normalizeHeaders(rule, data.url);
                rule = rule.substring(0, header.start()).trim();
                headerSuffix = normalized.substring(rule.length());
            }
        }
        return withHeader(selectStages(data, root, rule, 0, rows), headerSuffix);
    }

    private static List<Object> selectStages(Data data, Object root, String rule, int depth, boolean rows)
            throws IOException {
        String lower = rule.toLowerCase(Locale.ROOT);
        int index = scriptOffset(lower);
        if (index < 0) return selectStageInput(data, root, rule, rows);
        if (depth >= 12) throw new IOException("来源脚本串联层数过多");
        int end = rule.length();
        if (lower.startsWith("<js>", index)) {
            int closing = lower.indexOf("</js>", index + 4);
            if (closing < 0) throw new IOException("来源脚本缺少结束标记 </js>");
            end = closing + 5;
        }
        String prefix = rule.substring(0, index).trim();
        String script = rule.substring(index, end);
        String suffix = rule.substring(end).trim();
        List<Object> input = prefix.isEmpty() ? Collections.singletonList(root) : selectStageInput(data, root, prefix, rows);
        if (!data.yiciyuan && rows && !prefix.isEmpty() && input.stream().allMatch(Element.class::isInstance)) {
            Elements elements = new Elements();
            for (Object element : input) elements.add((Element) element);
            input = Collections.singletonList(elements);
        } else if (!data.yiciyuan && input.size() > 1) {
            JSONArray group = new JSONArray();
            for (Object item : input) group.put(scriptInput(data, item));
            input = Collections.singletonList(group);
        }
        List<Object> results = new ArrayList<>();
        for (Object item : input) {
            Object output = data.runScript(script, scriptInput(data, item), root);
            if (output == null || output == JSONObject.NULL) continue;
            if (suffix.isEmpty()) appendValue(results, output);
            else for (Object result : selectStages(data, output, suffix, depth + 1, rows && !data.yiciyuan)) add(results, result);
            if (results.size() >= MAX_MATCHES) break;
        }
        return results;
    }

    private static List<Object> selectStageInput(Data data, Object root, String rule, boolean rows)
            throws IOException {
        if (data.scripting != null) {
            rule = applyRuleAssignments(data, root, rule);
        }
        if (rows && !data.yiciyuan && rule.startsWith(":")) {
            String input = root == data.root ? data.raw
                    : root instanceof Element ? ((Element) root).outerHtml() : scalar(root);
            return regexRows(input, rule.substring(1));
        }
        return selectStatic(data, root, rule, rows && !data.yiciyuan);
    }

    private static String applyRuleAssignments(Data data, Object root, String rule) throws IOException {
        int marker = topLevelOffset(rule.toLowerCase(Locale.ROOT), "@put:");
        if (marker < 0) return rule;
        if (++data.variableDepth > 12) {
            data.variableDepth--;
            throw new IOException("来源变量规则嵌套过深");
        }
        try {
            int assignments = 0;
            do {
                int start = marker + 5;
                int end = assignmentEnd(rule, start);
                if (end < 0) throw new ReaderSources.RuleFailure("来源的变量赋值规则缺少对象边界");
                JSONObject fields;
                try {
                    fields = new JSONObject(rule.substring(start, end + 1));
                } catch (JSONException malformed) {
                    throw new ReaderSources.RuleFailure("来源的变量赋值规则有误");
                }
                for (java.util.Iterator<String> keys = fields.keys(); keys.hasNext(); ) {
                    if (++assignments > 32) throw new IOException("单条来源规则赋值数量过多");
                    String key = keys.next();
                    data.scripting.put(key, first(data, root, fields.optString(key, "")));
                }
                rule = (rule.substring(0, marker) + rule.substring(end + 1)).trim();
                marker = topLevelOffset(rule.toLowerCase(Locale.ROOT), "@put:");
            } while (marker >= 0);
            return rule;
        } finally {
            data.variableDepth--;
        }
    }

    private static int assignmentEnd(String rule, int start) {
        if (start >= rule.length() || rule.charAt(start) != '{') return -1;
        char quote = 0;
        int depth = 0;
        for (int index = start; index < rule.length(); index++) {
            char ch = rule.charAt(index);
            if (ch == '\\') index++;
            else if (quote != 0) {
                if (ch == quote) quote = 0;
            } else if (ch == '\'' || ch == '"') quote = ch;
            else if (ch == '{') depth++;
            else if (ch == '}' && --depth == 0) return index;
        }
        return -1;
    }

    /** Android Legado's colon lists match raw response text, exposing each match's capture groups. */
    private static List<Object> regexRows(String value, String declaration) throws IOException {
        List<String> stages = new ArrayList<>();
        for (String pattern : declaration.split("&&")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty()) stages.add(trimmed);
        }
        if (stages.size() > 12) throw new IOException("来源正则表达式层数过多");
        long deadline = System.nanoTime() + 120_000_000L;
        try {
            for (int stage = 0; stage < stages.size(); stage++) {
                String pattern = stages.get(stage);
                if (pattern.length() > 1500) throw new IOException("来源正则表达式过长");
                Matcher matcher = Pattern.compile(pattern).matcher(new LimitedText(value, 0, value.length(), deadline));
                if (stage + 1 == stages.size()) return captureMatches(value, matcher, deadline);
                StringBuilder narrowed = new StringBuilder();
                while (matcher.find()) {
                    checkRegexTime(deadline);
                    narrowed.append(value, matcher.start(), matcher.end());
                }
                value = narrowed.toString();
                if (value.isEmpty()) break;
            }
        } catch (RegexTimeout | StackOverflowError timeout) {
            throw new IOException("来源正则表达式处理超时", timeout);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("来源列表的正则表达式无效", invalid);
        }
        return Collections.emptyList();
    }

    private static List<Object> captureMatches(String value, Matcher matcher, long deadline) throws IOException {
        List<Object> matches = new ArrayList<>();
        int groupCount = Math.min(matcher.groupCount() + 1, 100);
        int captured = 0;
        while (matches.size() < MAX_MATCHES && matcher.find()) {
            checkRegexTime(deadline);
            List<String> groups = new ArrayList<>(groupCount);
            for (int group = 0; group < groupCount; group++) {
                int start = matcher.start(group);
                if (start < 0) {
                    groups.add("");
                    continue;
                }
                int end = matcher.end(group);
                captured += end - start;
                if (captured > MAX_REGEX_CAPTURE_CHARS) throw new IOException("来源正则表达式提取内容过长");
                groups.add(value.substring(start, end));
            }
            matches.add(new RegexRow(groups));
        }
        return matches;
    }

    private static void checkRegexTime(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) throw new RegexTimeout();
    }

    private static final class RegexRow {
        final List<String> groups;

        RegexRow(List<String> groups) { this.groups = groups; }

        String format(Matcher fields) throws IOException {
            StringBuffer result = new StringBuffer();
            do {
                int index = Integer.parseInt(fields.group(1));
                String value = index < groups.size() ? groups.get(index) : "";
                fields.appendReplacement(result, Matcher.quoteReplacement(value));
                if (result.length() > MAX_REGEX_CAPTURE_CHARS) throw new IOException("来源正则表达式提取内容过长");
            } while (fields.find());
            fields.appendTail(result);
            if (result.length() > MAX_REGEX_CAPTURE_CHARS) throw new IOException("来源正则表达式提取内容过长");
            return result.toString();
        }

        @Override public String toString() { return groups.get(0); }
    }

    private static int scriptOffset(String lower) {
        if (lower.startsWith("js:")) return 0;
        int block = topLevelOffset(lower, "<js>");
        int tail = topLevelOffset(lower, "@js:");
        return block < 0 ? tail : tail < 0 ? block : Math.min(block, tail);
    }

    private static Object scriptInput(Data data, Object value) {
        if (value == data.root) return data.raw;
        if (value instanceof RegexRow) return new JSONArray(((RegexRow) value).groups);
        return value instanceof Element ? ((Element) value).outerHtml() : value;
    }

    private static String scriptField(Data data, Object root, String rule) throws IOException {
        String cleaned = rule.trim();
        if (cleaned.isEmpty()) return "";
        String lower = cleaned.toLowerCase(Locale.ROOT);
        if (cleaned.length() > 4096 || scriptOffset(lower) >= 0 || cleaned.contains("{{")
                || topLevelOffset(lower, "@put:") >= 0) {
            throw new IOException("脚本字段读取只接受静态提取规则");
        }
        return firstValue(selectStatic(data, root, cleaned, false));
    }

    private static List<Object> selectStatic(Data data, Object root, String rule, boolean nodeList) throws IOException {
        String lower = rule.toLowerCase(Locale.ROOT);
        if (lower.startsWith("javascript:") || (data.scripting == null && topLevelOffset(lower, "@get:") >= 0)
                || topLevelOffset(lower, "@put:") >= 0 || topLevelOffset(lower, "<webjs>") >= 0
                || topLevelOffset(lower, "</js>") >= 0) {
            throw new IOException("此来源依赖专用脚本接口、动态变量或 WebView");
        }
        int regex = topLevelOffset(rule, "##");
        boolean singleHash = false;
        if (regex < 0 && data.yiciyuan) {
            regex = legacyReplacementIndex(rule);
            singleHash = regex >= 0;
        }
        String selector = (regex < 0 ? rule : rule.substring(0, regex)).trim();
        String replacement = regex < 0 ? "" : rule.substring(regex + (singleHash ? 1 : 2));
        if (singleHash) {
            List<String> pieces = splitTop(replacement, "#");
            if (pieces.size() > 1) replacement = pieces.get(0) + "##" + pieces.get(1);
        }
        try {
            Matcher fields = root instanceof RegexRow ? REGEX_GROUP_FIELD.matcher(selector) : null;
            List<Object> selected;
            if (data.scripting != null && LEGADO_GET.matcher(selector).find()) {
                selected = Collections.singletonList(expandFieldVariables(data, root, selector));
            } else if (fields != null && fields.find()) {
                selected = Collections.singletonList(((RegexRow) root).format(fields));
            } else {
                selected = selectCombined(data, root, selector, "", 0, nodeList);
            }
            if (replacement.isEmpty()) return selected;
            if (data.scripting != null && LEGADO_GET.matcher(replacement).find()) {
                replacement = expandFieldVariables(data, root, replacement);
            }
            List<Object> changed = new ArrayList<>();
            for (Object item : selected) {
                String value = selector.isEmpty() && item instanceof Element ? ((Element) item).outerHtml() : scalar(item);
                changed.add(replace(value, replacement));
            }
            return changed;
        } catch (IllegalArgumentException | Selector.SelectorParseException invalid) {
            throw new IOException("无法解析来源规则：" + abbreviation(selector), invalid);
        }
    }

    private static String expandFieldVariables(Data data, Object root, String part) throws IOException {
        if (part.length() > 240_000) throw new IOException("来源变量规则过长");
        Matcher token = LEGADO_FIELD.matcher(part);
        StringBuffer result = new StringBuffer();
        while (token.find()) {
            String value = token.group(1) != null ? data.scripting.get(token.group(1).trim())
                    : embeddedField(data, root, token.group(2).trim());
            if (value.length() > MAX_PROSE_CHARS - result.length()) {
                throw new IOException("来源变量规则结果过长");
            }
            token.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        token.appendTail(result);
        if (result.length() > MAX_PROSE_CHARS) throw new IOException("来源变量规则结果过长");
        return result.toString();
    }

    private static List<Object> selectCombined(Data data, Object root, String selector, String inheritedPrefix,
                                               int depth, boolean nodeList) throws IOException {
        if (depth > 12) throw new IOException("组合规则层数过多");
        String prefix = selectorRegion(selector);
        if (!prefix.isEmpty()) inheritedPrefix = prefix;
        String operator = literalAddress(selector, data.yiciyuan) ? "" : firstOperator(selector, data.yiciyuan);
        if (operator.isEmpty()) {
            String rule = prefix.isEmpty() && !selector.isEmpty() && !selector.startsWith("@")
                    && !selector.startsWith("$") && !selector.startsWith("/")
                    ? inheritedPrefix + selector : selector;
            List<Object> selected = new ArrayList<>();
            for (Object item : selectSingle(data, root, rule, true, nodeList)) appendValue(selected, item);
            return selected;
        }
        List<Object> selected = new ArrayList<>();
        List<List<Object>> interleaved = new ArrayList<>();
        for (String branch : splitTop(selector, operator)) {
            String part = branch.trim();
            if (part.isEmpty()) continue;
            List<Object> match = selectCombined(data, root, part, inheritedPrefix, depth + 1, nodeList);
            if (!hasValue(match)) continue;
            if (operator.startsWith("|")) return match;
            if (operator.equals("%%")) interleaved.add(match);
            else for (Object item : match) add(selected, item);
        }
        if (!interleaved.isEmpty()) {
            for (int item = 0; item < interleaved.get(0).size(); item++) {
                for (List<Object> branch : interleaved) {
                    if (item < branch.size()) add(selected, branch.get(item));
                }
            }
        }
        return selected;
    }

    private static String firstOperator(String selector, boolean yiciyuan) {
        String found = "";
        int first = selector.length();
        String[] operators = yiciyuan ? new String[] {"||", "&&", "%%", "|", "&"}
                : new String[] {"||", "&&", "%%"};
        for (String test : operators) {
            int offset = topLevelOffset(selector, test);
            if (offset >= 0 && offset < first) {
                first = offset;
                found = test;
            }
        }
        return found;
    }

    private static boolean literalAddress(String selector, boolean yiciyuan) {
        return selector.startsWith("http://") || selector.startsWith("https://")
                || selector.startsWith("/") && ((yiciyuan ? YICYUAN_FIELDS : TEMPLATE).matcher(selector).find());
    }

    private static String selectorRegion(String selector) {
        String lower = selector.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"@css:", "css:", "@@", "@json:", "json:", "@xpath:", "xpath:"}) {
            if (lower.startsWith(prefix)) return prefix;
        }
        return "";
    }

    private static int legacyReplacementIndex(String rule) {
        List<String> segments = splitTop(rule, "#");
        if (segments.size() == 1) return -1;
        int prefixLength = 0;
        for (int i = 0; i < segments.size() - 1; i++) {
            prefixLength += segments.get(i).length();
            String selector = rule.substring(0, prefixLength).trim();
            String prefix = selectorRegion(selector);
            String expression = selector.substring(prefix.length()).trim();
            if (topLevelOffset(expression, "@") >= 0 || expression.startsWith("$.")
                    || expression.startsWith("$[") || isOutput(expression)
                    || prefix.endsWith("json:") && !expression.isEmpty()) return prefixLength;
            prefixLength++;
        }
        return -1;
    }

    private static List<Object> withHeader(List<Object> items, String suffix) {
        if (suffix.isEmpty()) return items;
        List<Object> values = new ArrayList<>();
        for (Object item : items) {
            String address = scalar(item).trim();
            if (!address.isEmpty()) values.add(address + suffix);
        }
        return values;
    }

    private static boolean hasValue(List<Object> values) {
        for (Object value : values) {
            if (!(value instanceof String) || !((String) value).trim().isEmpty()) return true;
        }
        return false;
    }

    private static List<?> selectSingle(Data data, Object root, String rule, boolean expandTemplates,
                                        boolean nodeList) throws IOException {
        if (rule.isEmpty() || ".".equals(rule)) return Collections.singletonList(root);
        String lower = rule.toLowerCase(Locale.ROOT);
        Matcher template = (data.yiciyuan ? YICYUAN_FIELDS : TEMPLATE).matcher(rule);
        if (expandTemplates && template.find()) {
            StringBuffer resolved = new StringBuffer();
            do {
                String part = template.group(1) == null ? template.group(2) : template.group(1);
                String nested = embeddedField(data, root, part.trim());
                template.appendReplacement(resolved, Matcher.quoteReplacement(nested));
            } while (template.find());
            template.appendTail(resolved);
            String value = resolved.toString();
            return selectorRegion(rule).isEmpty() ? Collections.singletonList(value)
                    : selectSingle(data, root, value, false, nodeList);
        }
        if (rule.startsWith("http://") || rule.startsWith("https://")) return Collections.singletonList(rule);
        if (lower.startsWith("@json:") || lower.startsWith("json:") || rule.startsWith("$")
                || root instanceof JSONObject || root instanceof JSONArray) {
            return json(root, lower.startsWith("@json:") || lower.startsWith("json:")
                    ? rule.substring(rule.indexOf(':') + 1).trim() : rule);
        }
        Element element;
        if (root instanceof Element) element = (Element) root;
        else if (root instanceof String) element = Jsoup.parse((String) root, data.url);
        else return Collections.emptyList();
        if (data.yiciyuan && root == element && root != data.root && !isOutput(rule)
                && YiciyuanRules.isAttributeName(rule) && element.hasAttr(rule)) {
            return Collections.singletonList(element.attr(rule));
        }
        if (lower.startsWith("@xpath:") || lower.startsWith("xpath:")) {
            return xpath(element, rule.substring(rule.indexOf(':') + 1).trim(), !data.yiciyuan);
        }
        if (rule.startsWith("/") || rule.startsWith("./")) return xpath(element, rule, !data.yiciyuan);
        if (rule.startsWith("@@")) return css(element, rule.substring(2), true, nodeList);
        if (lower.startsWith("@css:") || lower.startsWith("css:")) {
            return css(element, rule.substring(rule.indexOf(':') + 1), false, nodeList);
        }
        return css(element, rule, true, nodeList);
    }

    private static String embeddedField(Data data, Object root, String rule) throws IOException {
        if (rule.equals("baseUrl")) return data.url;
        if (rule.equals("result")) return scalar(scriptInput(data, root));
        if (rule.startsWith("result.") || rule.startsWith("result[") || rule.startsWith("baseUrl ")
                || (!data.yiciyuan && !rule.startsWith("@") && !rule.startsWith("$") && !rule.startsWith("/")
                    && rule.indexOf('(') >= 0)) {
            return scalar(data.runScript("@js:" + rule, scriptInput(data, root), root));
        }
        return first(data, root, rule);
    }

    private static List<?> css(Element root, String declaration, boolean legadoSteps, boolean nodeList) {
        List<String> parts = splitTop(declaration, "@");
        String output = "";
        String last = parts.get(parts.size() - 1).trim();
        boolean selectorStep = (nodeList && !isOutput(last)) || (legadoSteps && (LEGADO_STEP.matcher(last).matches()
                || isChildrenRule(last) || BRACKET_INDEX.matcher(last).matches()));
        if (parts.size() > 1 && !selectorStep) output = parts.remove(parts.size() - 1).trim();
        else if (isOutput(parts.get(0))) output = parts.remove(0).trim();
        List<Element> current = new ArrayList<>();
        current.add(root);
        for (String selector : parts) {
            if (selector.trim().isEmpty()) continue;
            List<Element> next = new ArrayList<>();
            for (Element node : current) {
                for (Element child : step(node, selector.trim(), legadoSteps)) {
                    if (next.size() >= MAX_MATCHES) break;
                    next.add(child);
                }
            }
            current = next;
        }
        if (output.isEmpty() || output.equalsIgnoreCase("all")) return current;
        List<String> strings = new ArrayList<>();
        for (Element element : current) strings.add(extract(element, output));
        return strings;
    }

    private static List<Element> step(Element root, String rule, boolean legadoSteps) {
        if (!legadoSteps) return root.select(rule);
        Matcher bracket = BRACKET_INDEX.matcher(rule);
        if (bracket.matches()) {
            String selector = bracket.group(1).trim();
            List<Element> elements = selector.isEmpty() ? root.children() : legacyStep(root, selector);
            return bracketIndexed(elements, bracket.group(3), !bracket.group(2).isEmpty());
        }
        return legacyStep(root, rule);
    }

    private static List<Element> legacyStep(Element root, String rule) {
        Matcher pick = STEP_INDEX.matcher(rule);
        String selector = pick.matches() ? pick.group(1).trim() : rule;
        if (selector.isEmpty() || isChildrenRule(selector)) return indexed(root.children(), pick);
        Matcher simple = LEGADO_STEP.matcher(selector);
        if (!simple.matches()) return indexed(root.select(selector), pick);
        String expression = simple.group(2);
        List<Element> nodes;
        switch (simple.group(1).toLowerCase(Locale.ROOT)) {
            case "class":
                String[] classes = expression.trim().split("\\s+");
                nodes = new ArrayList<>(root.getElementsByClass(classes[0]));
                for (int index = 1; index < classes.length; index++) {
                    String className = classes[index];
                    nodes.removeIf(node -> !node.hasClass(className));
                }
                break;
            case "id":
                Element item = root.getElementById(expression);
                nodes = item == null ? Collections.emptyList() : Collections.singletonList(item);
                break;
            case "tag":
                nodes = root.getElementsByTag(expression);
                break;
            default:
                nodes = root.getElementsContainingOwnText(expression);
        }
        return indexed(nodes, pick);
    }

    private static boolean isChildrenRule(String rule) {
        String lower = rule.toLowerCase(Locale.ROOT);
        return lower.equals("children") || lower.startsWith("children.") || lower.startsWith("children!");
    }

    private static List<Element> indexed(List<Element> nodes, Matcher indexRule) {
        if (!indexRule.matches()) return nodes;
        Set<Integer> indices = new LinkedHashSet<>();
        for (String item : indexRule.group(3).split(":")) {
            int index = Integer.parseInt(item);
            if (index < 0) index += nodes.size();
            if (index >= 0 && index < nodes.size()) indices.add(index);
        }
        return filterIndices(nodes, indices, indexRule.group(2).equals("!"));
    }

    private static List<Element> bracketIndexed(List<Element> nodes, String spec, boolean exclude) {
        if (nodes.isEmpty()) return nodes;
        int size = nodes.size();
        Set<Integer> indices = new LinkedHashSet<>();
        for (String entry : spec.split(",", -1)) {
            String[] bounds = entry.trim().split(":", -1);
            if (bounds.length == 1) {
                int index = position(bounds[0], 0, size);
                if (!bounds[0].trim().isEmpty() && index >= 0 && index < size) indices.add(index);
                continue;
            }
            if (bounds.length > 3) throw new IllegalArgumentException("Invalid element index range");
            int start = position(bounds[0], 0, size);
            int end = position(bounds[1], size - 1, size);
            if ((start < 0 && end < 0) || (start >= size && end >= size)) continue;
            start = Math.max(0, Math.min(size - 1, start));
            end = Math.max(0, Math.min(size - 1, end));
            int step = bounds.length == 3 ? position(bounds[2], 1, size) : 1;
            step = Math.max(1, Math.min(size, step));
            int direction = end < start ? -1 : 1;
            for (int i = start; direction * (end - i) >= 0; i += direction * step) indices.add(i);
        }
        return filterIndices(nodes, indices, exclude);
    }

    private static int position(String value, int fallback, int size) {
        String number = value.trim();
        if (number.isEmpty()) return fallback;
        int index = Integer.parseInt(number);
        return index < 0 ? size + index : index;
    }

    private static List<Element> filterIndices(List<Element> nodes, Set<Integer> indices, boolean exclude) {
        List<Element> filtered = new ArrayList<>();
        if (exclude) {
            for (int index = 0; index < nodes.size(); index++) {
                if (!indices.contains(index)) filtered.add(nodes.get(index));
            }
        } else {
            for (int index : indices) filtered.add(nodes.get(index));
        }
        return filtered;
    }

    private static boolean isOutput(String rule) {
        return rule.equalsIgnoreCase("text") || rule.equalsIgnoreCase("ownText")
                || rule.equalsIgnoreCase("textNodes") || rule.equalsIgnoreCase("html")
                || rule.equalsIgnoreCase("all") || rule.equalsIgnoreCase("href")
                || rule.equalsIgnoreCase("src");
    }

    private static String extract(Element node, String output) {
        switch (output.toLowerCase(Locale.ROOT)) {
            case "text": return node.text();
            case "owntext": return node.ownText();
            case "textnodes":
                List<String> text = new ArrayList<>();
                for (TextNode child : node.textNodes()) text.add(child.getWholeText());
                return String.join("\n", text).trim();
            case "html": return node.html();
            default: return node.attr(output.startsWith("attr:") ? output.substring(5) : output);
        }
    }

    private static List<?> xpath(Element root, String path, boolean relativeChild) {
        if (!(root instanceof Document) && (path.startsWith("//") || (relativeChild && path.startsWith("/")))) {
            path = "." + path;
        }
        int lastSlash = path.lastIndexOf('/');
        String end = lastSlash < 0 ? path : path.substring(lastSlash + 1);
        if (end.startsWith("@") || end.equals("text()")) {
            String parent = lastSlash < 0 ? "." : path.substring(0, lastSlash);
            if (parent.endsWith("/")) parent += "*";
            if (parent.isEmpty()) parent = ".";
            List<String> values = new ArrayList<>();
            for (Element element : root.selectXpath(parent.isEmpty() ? "." : parent)) {
                if (end.startsWith("@")) values.add(element.attr(end.substring(1)));
                else values.add(extract(element, "textNodes"));
            }
            return values;
        }
        return root.selectXpath(path);
    }

    private static List<Object> json(Object root, String rawPath) throws IOException {
        if (root instanceof Element || root instanceof String) {
            try {
                String json = root instanceof Element
                        ? or(((Element) root).data().trim(), ((Element) root).wholeText()) : (String) root;
                root = new JSONTokener(json).nextValue();
            } catch (JSONException invalid) {
                return Collections.emptyList();
            }
        }
        return ReaderRuleSupport.jsonPath(root, rawPath);
    }

    private static void appendValue(List<Object> output, Object value) {
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length() && output.size() < MAX_MATCHES; i++) add(output, array.opt(i));
        } else add(output, value);
    }

    private static void add(List<Object> output, Object value) {
        if (value != null && value != JSONObject.NULL && output.size() < MAX_MATCHES) output.add(value);
    }

    private static String scalar(Object item) {
        if (item instanceof Element) return ((Element) item).text();
        return item == null || item == JSONObject.NULL ? "" : item.toString();
    }

    private static String narrative(Object item) {
        if (item instanceof Element) return extractProse((Element) item);
        String value = scalar(item);
        if (HTML_MARKUP.matcher(value).find()) return extractProse(Jsoup.parseBodyFragment(value).body());
        return cleanProse(value);
    }

    private static String extractProse(Element root) {
        StringBuilder text = new StringBuilder();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode) {
                    text.append(((TextNode) node).getWholeText());
                } else if (node instanceof Element) {
                    Element element = (Element) node;
                    if (element.normalName().equals("br") || (element.isBlock() && text.length() > 0)) {
                        text.append('\n');
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element && ((Element) node).isBlock()) text.append('\n');
            }
        }, root);
        return cleanProse(text.toString());
    }

    private static String cleanProse(String text) {
        String value = text.replace("\r\n", "\n").replace('\r', '\n').replace('\u00a0', ' ');
        value = AROUND_BREAKS.matcher(value).replaceAll("\n");
        return EXCESS_BREAKS.matcher(value).replaceAll("\n\n").trim();
    }

    private static void addImages(Set<String> pictures, Object item, String base, boolean yiciyuan)
            throws IOException {
        if (item instanceof Element) {
            for (Element image : ((Element) item).select("img")) {
                String url = "";
                for (String attr : new String[]{"data-original", "data-src", "data-lazy-src", "data-url", "src"}) {
                    url = link(base, image.attr(attr));
                    if (!url.isEmpty()) break;
                }
                addPicture(pictures, url);
            }
        } else {
            String value = scalar(item);
            if (HTML_MARKUP.matcher(value).find()) {
                String html = yiciyuan ? YiciyuanRules.quoteImageOptions(value) : value;
                addImages(pictures, Jsoup.parseBodyFragment(html, base).body(), base, yiciyuan);
            } else {
                for (String line : value.split("[\\r\\n]+")) {
                    String url = link(base, line);
                    addPicture(pictures, url);
                }
            }
        }
    }

    private static void addPicture(Set<String> pictures, String url) throws IOException {
        if (!url.isEmpty() && pictures.add(url) && pictures.size() > MAX_IMAGES) {
            throw new IOException("章节包含过多图片，请检查来源规则");
        }
    }

    private static String replace(String value, String declaration) throws IOException {
        String[] parts = declaration.split("##", 3);
        if (parts[0].trim().isEmpty()) return value;
        boolean extractFirst = parts.length == 3;
        String replacement = parts.length < 2 ? "" : parts[1];
        if (parts.length == 2 && replacement.endsWith("###")) {
            extractFirst = true;
            replacement = replacement.substring(0, replacement.length() - 3);
        } else if (parts.length == 2 && parts[1].endsWith("#")) {
            extractFirst = true;
            replacement = replacement.substring(0, replacement.length() - 1);
        }
        if (parts[0].length() > 1500) throw new IOException("来源正则表达式过长");
        try {
            Pattern pattern = Pattern.compile(parts[0]);
            Matcher matcher = pattern.matcher(new LimitedText(value, 0, value.length(),
                    System.nanoTime() + 120_000_000L));
            if (!extractFirst) return matcher.replaceAll(replacement);
            if (!matcher.find()) return "";
            StringBuffer result = new StringBuffer();
            matcher.appendReplacement(result, replacement);
            return result.substring(matcher.start());
        } catch (RegexTimeout | StackOverflowError timeout) {
            throw new IOException("来源正则表达式处理超时", timeout);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            throw new IOException("源中有无效的正则表达式或替换值", invalid);
        }
    }

    private static String abbreviation(String text) {
        return text.length() <= 80 ? text : text.substring(0, 80) + "…";
    }

    private static final class RegexTimeout extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private static final class LimitedText implements CharSequence {
        private final String value;
        private final int start;
        private final int end;
        private final long deadline;

        LimitedText(String value, int start, int end, long deadline) {
            this.value = value;
            this.start = start;
            this.end = end;
            this.deadline = deadline;
        }

        @Override public int length() { return end - start; }
        @Override public char charAt(int index) {
            if (System.nanoTime() > deadline) throw new RegexTimeout();
            if (index < 0 || index >= length()) throw new IndexOutOfBoundsException();
            return value.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            if (from < 0 || to < from || to > length()) throw new IndexOutOfBoundsException();
            return new LimitedText(value, start + from, start + to, deadline);
        }
        @Override public String toString() { return value.substring(start, end); }
    }
}
