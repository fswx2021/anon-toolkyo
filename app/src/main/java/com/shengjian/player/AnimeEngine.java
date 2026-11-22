package com.shengjian.player;

import static com.shengjian.player.AnimeSource.field;
import static com.shengjian.player.AnimeSource.object;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native Kazumi-compatible search and episode lookup. Rule text is never executed as script. */
final class AnimeEngine {
    private static final int MAX_RESULTS = 160;
    private static final int MAX_ROADS = 48;
    private static final int MAX_EPISODES = 2000;
    private static final int MAX_EPISODES_TOTAL = 6000;
    private static final int MAX_DOCUMENT = 2 * 1024 * 1024;
    private static final Pattern ATTR_CONTAINS = Pattern.compile("\\[\\s*(@[A-Za-z_][A-Za-z0-9_.:-]*)\\s*\\*=\\s*(('[^']*'|\"[^\"]*\"))\\s*\\]");
    private static final Pattern ATTR_SUFFIX = Pattern.compile("^(.*)/@([A-Za-z_][A-Za-z0-9_.:-]*)$");
    private static final Pattern VARIABLE = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,128}");
    private static final Pattern PATH_STEP = Pattern.compile("\\.([A-Za-z0-9_$-]+)|\\[\\s*(\\*|[0-9]{1,9}|'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\")\\s*\\]");
    private static final Pattern MEDIA_PATH = Pattern.compile("(?i)\\.(m3u8?|mp4|m4v|webm|mkv|mov|ts|mpd|avi)$");

    private AnimeEngine() {}

    enum Stage { SEARCH, EPISODES, PLAYBACK }
    enum Kind { NETWORK, TIMEOUT, RULE, VERIFICATION, EMPTY }

    static final class Failure extends IOException {
        final Stage stage;
        final Kind kind;

        Failure(Stage stage, Kind kind, String message) { this(stage, kind, message, null); }

        Failure(Stage stage, Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.stage = stage;
            this.kind = kind;
        }
    }

    static final class Show {
        final AnimeSource source;
        final String title, url, cover, sourceValue;
        final long bangumiId;

        Show(AnimeSource source, String title, String url, String cover, String sourceValue) {
            this(source, title, url, cover, sourceValue, 0);
        }

        Show(AnimeSource source, String title, String url, String cover, String sourceValue, long bangumiId) {
            this.source = source;
            this.title = title;
            this.url = url;
            this.cover = cover;
            this.sourceValue = sourceValue;
            this.bangumiId = bangumiId;
        }
    }

    static final class Road {
        final String name;
        final List<Episode> episodes;

        Road(String name, List<Episode> episodes) {
            this.name = name;
            this.episodes = Collections.unmodifiableList(new ArrayList<>(episodes));
        }
    }

    static final class Episode {
        final Show show;
        final String title, sourceTitle, url;
        final int number;

        Episode(Show show, String sourceTitle, String url, int number) {
            this.show = show;
            this.sourceTitle = sourceTitle;
            this.title = first(sourceTitle, label(number));
            this.url = url;
            this.number = number;
        }
    }

    static ReaderSearchQueue.Route route(AnimeSource source) {
        String address = source.apiSearch ? field(object(source.searchApi, "request"), "url") : source.searchUrl;
        try {
            String host = URI.create(resolve(source.baseUrl, address)).getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) host = host.substring(4);
            return new ReaderSearchQueue.Route(host, false);
        } catch (IOException | IllegalArgumentException invalid) { return new ReaderSearchQueue.Route(source.id, false); }
    }

    static List<Show> search(AnimeSource source, String keyword) throws IOException {
        String value = keyword == null ? "" : keyword.trim();
        if (value.isEmpty()) return Collections.emptyList();
        if (value.length() > 160) throw rule(Stage.SEARCH, "搜索词太长", null);
        try {
            return source.apiSearch ? searchApi(source, value) : searchHtml(source, value);
        } catch (Failure | InterruptedIOException precise) { throw precise; }
        catch (IOException | IllegalArgumentException invalid) { throw rule(Stage.SEARCH, "动画源的搜索规则无法解析", invalid); }
    }

    static List<Road> episodes(Show show) throws IOException {
        try {
            List<Road> result = show.source.apiChapter ? episodesApi(show) : episodesHtml(show);
            if (result.isEmpty()) throw new Failure(Stage.EPISODES, Kind.EMPTY, "没有从此来源读取到可播放剧集");
            return result;
        } catch (Failure | InterruptedIOException precise) { throw precise; }
        catch (IOException | IllegalArgumentException invalid) { throw rule(Stage.EPISODES, "动画源的选集规则无法解析", invalid); }
    }

    static boolean isDirectMedia(String input) {
        try {
            String path = AnimeSource.web(input == null ? "" : input).getPath();
            return path != null && MEDIA_PATH.matcher(path).find();
        } catch (IOException malformed) { return false; }
    }

    static Map<String, String> playbackHeaders(Episode episode) {
        AnimeSource source = episode.show.source;
        Map<String, String> headers = new LinkedHashMap<>();
        if (!source.userAgent.isEmpty()) headers.put("User-Agent", source.userAgent);
        String referer = source.referer.isEmpty() ? first(episode.show.url, source.baseUrl) : source.referer;
        try { headers.put("Referer", safeReferer(referer, episode.url)); }
        catch (IOException invalid) { /* The target URL is validated separately. */ }
        return headers;
    }

    private static List<Show> searchHtml(AnimeSource source, String keyword) throws IOException {
        Stage stage = Stage.SEARCH;
        String url = resolve(source.baseUrl, source.searchUrl.replace("@keyword", formValue(keyword)));
        String body = null;
        if (source.usePost) {
            URI address = AnimeSource.web(url);
            body = first(address.getRawQuery(), "");
            int mark = url.indexOf('?');
            if (mark >= 0) url = url.substring(0, mark);
        }
        ReaderSources.Response response = request(source, url, body, Collections.emptyMap(), stage);
        Document html = document(response, stage);
        List<Element> rows = select(html, source.searchList, stage);
        List<Show> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < Math.min(rows.size(), MAX_RESULTS * 2) && result.size() < MAX_RESULTS; i++) {
            check();
            Element row = rows.get(i);
            String title = selectionValue(row, source.searchName, "", stage);
            String raw = selectionValue(row, source.searchResult, "href", stage);
            String link = optionalLink(response.url, raw);
            if (title.isEmpty() || link.isEmpty() || !seen.add(link)) continue;
            Element image = row.selectFirst("img");
            String cover = image == null ? "" : optionalLink(response.url, first(image.attr("data-src"), image.attr("src")));
            result.add(new Show(source, title, link, cover, link));
        }
        if (result.isEmpty() && !rows.isEmpty()) throw rule(stage, "搜索规则选中了条目，但没有找到有效的名称及链接", null);
        return result;
    }

    private static List<Road> episodesHtml(Show show) throws IOException {
        Stage stage = Stage.EPISODES;
        String url = resolve(show.source.baseUrl, first(show.url, show.sourceValue));
        if (isDirectMedia(url)) return Collections.singletonList(new Road("播放线路 1",
                Collections.singletonList(new Episode(show, "正片", url, 1))));
        ReaderSources.Response response = request(show.source, url, null, Collections.emptyMap(), stage);
        Document html = document(response, stage);
        List<Element> containers = show.source.chapterRoads.isEmpty() ? Collections.singletonList(html)
                : select(html, show.source.chapterRoads, stage);
        List<Road> result = new ArrayList<>();
        int remaining = MAX_EPISODES_TOTAL;
        for (int i = 0; i < Math.min(containers.size(), MAX_ROADS) && remaining > 0; i++) {
            check();
            Element road = containers.get(i);
            Matcher attribute = ATTR_SUFFIX.matcher(show.source.chapterResult);
            String expression = attribute.matches() ? attribute.group(1) : show.source.chapterResult;
            String href = attribute.matches() ? attribute.group(2) : "href";
            List<Element> entries = select(road, expression, stage);
            List<Episode> episodes = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int index = 0; index < Math.min(entries.size(), Math.min(MAX_EPISODES, remaining)); index++) {
                Element item = entries.get(index);
                String link = optionalLink(response.url, item.attr(href));
                if (!link.isEmpty() && seen.add(link)) {
                    episodes.add(new Episode(show, item.text().trim(), link, index + 1));
                }
            }
            remaining -= episodes.size();
            if (!episodes.isEmpty()) result.add(new Road(first(road.attr("data-name"), roadLabel(i + 1)), episodes));
        }
        return result;
    }

    private static List<Show> searchApi(AnimeSource source, String keyword) throws IOException {
        Stage stage = Stage.SEARCH;
        JSONObject config = source.searchApi;
        ReaderSources.Response response = apiRequest(source, object(config, "request"),
                Collections.singletonMap("keyword", keyword), stage);
        Object json = AnimeSource.parse(response.body);
        List<Object> rows = sequence(path(json, field(config, "listPath", "$.data[*]"), stage));
        Set<String> seen = new LinkedHashSet<>();
        List<Show> result = new ArrayList<>();
        String template = field(config, "sourceTemplate");
        if (!template.isEmpty() && !template.contains("@source")) throw rule(stage, "详情页模板必须包含 @source", null);
        for (Object row : rows) {
            check();
            if (result.size() >= MAX_RESULTS) break;
            String title = firstPath(row, field(config, "namePath", "$.name"), stage);
            String sourceValue = firstPath(row, field(config, "sourcePath", "$.url"), stage);
            if (title.isEmpty() || sourceValue.isEmpty()) continue;
            if (!template.isEmpty()) sourceValue = template.replace("@source", component(sourceValue));
            boolean isPage = !source.apiChapter || !template.isEmpty() || sourceValue.startsWith("/")
                    || sourceValue.startsWith("./") || sourceValue.startsWith("../") || sourceValue.contains("://");
            String url = isPage ? optionalLink(source.baseUrl, sourceValue) : "";
            if (!source.apiChapter && url.isEmpty()) continue;
            String cover = optionalLink(source.baseUrl, firstPath(row, field(config, "coverPath"), stage));
            if (seen.add(sourceValue)) result.add(new Show(source, title, url, cover, sourceValue));
        }
        if (result.isEmpty() && !rows.isEmpty()) throw rule(stage, "搜索接口返回了条目，但名称或来源路径未匹配", null);
        return result;
    }

    private static List<Road> episodesApi(Show show) throws IOException {
        Stage stage = Stage.EPISODES;
        JSONObject config = show.source.chapterApi;
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("source", first(show.sourceValue, show.url));
        ReaderSources.Response response = apiRequest(show.source, object(config, "request"), vars, stage);
        Object json = AnimeSource.parse(response.body);
        JSONObject variables = object(config, "variables");
        Iterator<String> names = variables.keys();
        while (names.hasNext()) {
            if (vars.size() > 64) throw rule(stage, "选集变量过多", null);
            String name = names.next();
            vars.put(name, firstPath(json, field(variables, name), stage));
        }
        if ("delimited".equals(field(config, "format"))) return delimited(show, config, json, vars);
        String roadPath = field(config, "roadsPath", "$.data.roads[*]");
        List<Object> roads = roadPath.isEmpty() ? Collections.singletonList(json) : sequence(path(json, roadPath, stage));
        List<Road> result = new ArrayList<>();
        int remaining = MAX_EPISODES_TOTAL;
        for (int r = 0; r < Math.min(roads.size(), MAX_ROADS) && remaining > 0; r++) {
            check();
            Object road = roads.get(r);
            if (road == JSONObject.NULL) continue;
            String name = firstPath(road, field(config, "roadNamePath", "$.name"), stage);
            List<Object> rows = sequence(path(road, field(config, "episodesPath", "$.episodes[*]"), stage));
            List<Episode> episodes = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int e = 0; e < Math.min(rows.size(), Math.min(MAX_EPISODES, remaining)); e++) {
                Object item = rows.get(e);
                if (item == JSONObject.NULL) continue;
                String title = firstPath(item, field(config, "episodeNamePath", "$.name"), stage);
                String raw = firstPath(item, field(config, "episodeUrlPath", "$.url"), stage);
                addEpisode(show, config, vars, episodes, seen, title, raw, r, e);
            }
            remaining -= episodes.size();
            if (!episodes.isEmpty()) result.add(new Road(first(name, roadLabel(r + 1)), episodes));
        }
        return result;
    }

    private static List<Road> delimited(Show show, JSONObject config, Object json, Map<String, Object> vars)
            throws IOException {
        Stage stage = Stage.EPISODES;
        String roadSeparator = separator(config, "roadSeparator", "$$$");
        String episodeSeparator = separator(config, "episodeSeparator", "#");
        String fieldSeparator = separator(config, "fieldSeparator", "$");
        String[] names = firstPath(json, field(config, "roadNamesPath"), stage)
                .split(Pattern.quote(roadSeparator), MAX_ROADS + 1);
        String[] groups = firstPath(json, field(config, "roadEpisodesPath"), stage)
                .split(Pattern.quote(roadSeparator), MAX_ROADS + 1);
        List<Road> result = new ArrayList<>();
        int remaining = MAX_EPISODES_TOTAL;
        for (int r = 0; r < Math.min(groups.length, MAX_ROADS) && remaining > 0; r++) {
            check();
            String[] rows = groups[r].split(Pattern.quote(episodeSeparator), MAX_EPISODES + 1);
            List<Episode> episodes = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (int e = 0; e < Math.min(rows.length, Math.min(MAX_EPISODES, remaining)); e++) {
                int separator = rows[e].indexOf(fieldSeparator);
                if (separator < 0) continue;
                addEpisode(show, config, vars, episodes, seen, rows[e].substring(0, separator).trim(),
                        rows[e].substring(separator + fieldSeparator.length()).trim(), r, e);
            }
            remaining -= episodes.size();
            if (!episodes.isEmpty()) result.add(new Road(first(r < names.length ? names[r].trim() : "", roadLabel(r + 1)), episodes));
        }
        return result;
    }

    private static void addEpisode(Show show, JSONObject config, Map<String, Object> base, List<Episode> episodes,
                                   Set<String> seen, String title, String raw, int road, int episode) throws IOException {
        String link;
        JSONObject page = config.optJSONObject("episodePage");
        if (page == null) link = optionalLink(show.source.baseUrl, raw);
        else {
            Map<String, Object> vars = new LinkedHashMap<>(base);
            vars.put("episodeUrl", raw);
            vars.put("roadIndex", road);
            vars.put("roadNumber", road + 1);
            vars.put("episodeIndex", episode);
            vars.put("episodeNumber", episode + 1);
            link = withQuery(resolve(show.source.baseUrl, render(field(page, "url"), vars, true)), object(page, "query"), vars);
        }
        if (!link.isEmpty() && seen.add(link)) episodes.add(new Episode(show, title, link, episode + 1));
    }

    private static String separator(JSONObject config, String field, String fallback) throws Failure {
        String value = field(config, field, fallback);
        if (value.isEmpty() || value.length() > 32) throw rule(Stage.EPISODES, "剧集分隔符无效", null);
        return value;
    }

    private static ReaderSources.Response apiRequest(AnimeSource source, JSONObject config, Map<String, Object> vars,
                                                     Stage stage) throws IOException {
        String method = field(config, "method", "GET").toUpperCase(Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) throw rule(stage, "仅支持 GET 和 POST 动画源请求", null);
        String address = withQuery(resolve(source.baseUrl, render(field(config, "url"), vars, true)),
                object(config, "query"), vars);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        JSONObject header = object(config, "headers");
        Iterator<String> keys = header.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            headers.put(render(key, vars, false), scalar(renderValue(header.opt(key), vars, 0)));
        }
        String body = null;
        if (method.equals("POST")) {
            String bodyType = field(config, "bodyType", "none");
            Object input = config.opt("body");
            if (bodyType.equals("json")) {
                Object encoded = renderValue(input, vars, 0);
                body = encoded instanceof String ? JSONObject.quote((String) encoded) : String.valueOf(encoded);
                headers.putIfAbsent("Content-Type", "application/json; charset=UTF-8");
            } else if (bodyType.equals("form")) {
                if (!(input instanceof JSONObject)) throw rule(stage, "POST 表单必须是 JSON 对象", null);
                body = queryValues((JSONObject) input, vars);
            } else body = "";
        }
        return request(source, address, body, headers, stage);
    }

    private static ReaderSources.Response request(AnimeSource source, String url, String body,
                                                   Map<String, String> declared, Stage stage) throws IOException {
        check();
        if (body != null && body.length() > 128 * 1024) throw rule(stage, "动画源请求内容过长", null);
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!source.userAgent.isEmpty()) headers.put("User-Agent", source.userAgent);
        headers.put("Referer", safeReferer(source.baseUrl, url));
        boolean sameOrigin = ReaderEngine.sameOrigin(source.baseUrl, url);
        for (Map.Entry<String, String> header : declared.entrySet()) {
            String key = header.getKey();
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("host") || lower.equals("connection") || lower.equals("content-length")
                    || lower.equals("transfer-encoding") || lower.startsWith("proxy-")) continue;
            if (!sameOrigin && !lower.equals("accept") && !lower.equals("accept-language") && !lower.equals("referer")
                    && !lower.equals("user-agent") && !lower.equals("content-type")) continue;
            if (!HEADER_NAME.matcher(key).matches()) throw rule(stage, "动画源的请求头名称无效", null);
            String value = AnimeSource.headerValue(header.getValue());
            if (lower.equals("referer")) value = safeReferer(value, url);
            headers.put(key, value);
        }
        try {
            ReaderSources.Response response = ReaderSources.fetchAnime(url, headers, body);
            if (response.body.length() > MAX_DOCUMENT) throw rule(stage, "动画源响应过大", null);
            return response;
        } catch (ReaderSources.HttpFailure status) {
            boolean verification = source.antiCrawler && (status.status == 403 || status.status == 429 || status.status == 503);
            throw new Failure(stage, verification ? Kind.VERIFICATION : Kind.NETWORK,
                    verification ? "站点要求网页验证（HTTP " + status.status + "）"
                            : "动画源服务器返回 HTTP " + status.status, status);
        } catch (SocketTimeoutException timeout) {
            throw new Failure(stage, Kind.TIMEOUT, "动画源响应超时", timeout);
        } catch (Failure | InterruptedIOException exact) { throw exact; }
        catch (IOException failed) { throw new Failure(stage, Kind.NETWORK, "无法连接动画源或读取网页", failed); }
    }

    private static Document document(ReaderSources.Response response, Stage stage) throws Failure {
        Document doc = Jsoup.parse(response.body, response.url);
        if (doc.selectFirst("#challenge-form, #cf-challenge-running, iframe[src*='challenges.cloudflare.com']") != null) {
            throw new Failure(stage, Kind.VERIFICATION, "站点返回网页验证，未得到作品页面");
        }
        return doc;
    }

    private static List<Element> select(Element root, String raw, Stage stage) throws Failure {
        if (raw.length() > 512 || raw.isEmpty()) throw rule(stage, "动画源选择器为空或过长", null);
        try {
            if (raw.startsWith("css:") || raw.startsWith("@css:")) {
                String expression = raw.substring(raw.indexOf(':') + 1).trim();
                if (expression.contains(":matches") || expression.contains(":has(")) throw new IllegalArgumentException();
                return root.select(expression);
            }
            String expression = ATTR_CONTAINS.matcher(raw).replaceAll("[contains($1,$2)]");
            if (root instanceof Document) return root.selectXpath(expression);
            List<String> branches = ReaderRuleSupport.splitTop(expression, "|");
            for (int i = 0; i < branches.size(); i++) {
                String branch = branches.get(i).trim();
                if (branch.startsWith("/")) branches.set(i, "." + branch);
            }
            return root.selectXpath(String.join(" | ", branches));
        } catch (RuntimeException | StackOverflowError invalid) { throw rule(stage, "动画源 XPath 或 CSS 选择器无效", invalid); }
    }

    private static String selectionValue(Element root, String raw, String defaultAttribute, Stage stage) throws Failure {
        Matcher attr = ATTR_SUFFIX.matcher(raw);
        boolean attribute = attr.matches();
        boolean ownText = raw.endsWith("/text()");
        String expression = attribute ? attr.group(1) : ownText ? raw.substring(0, raw.length() - 7) : raw;
        List<Element> matches = expression.isEmpty() || expression.equals(".") ? Collections.singletonList(root)
                : select(root, expression, stage);
        if (matches.isEmpty()) return "";
        Element node = matches.get(0);
        if (attribute || !defaultAttribute.isEmpty()) return node.attr(attribute ? attr.group(2) : defaultAttribute).trim();
        return (ownText ? node.ownText() : node.text()).trim();
    }

    private static List<Object> path(Object root, String expression, Stage stage) throws Failure {
        if (expression.isEmpty() || expression.charAt(0) != '$' || expression.length() > 512) {
            throw rule(stage, "动画 API 的 JSONPath 须以 $ 开头", null);
        }
        List<Object> values = Collections.singletonList(root);
        Matcher token = PATH_STEP.matcher(expression);
        for (int offset = 1, steps = 0; offset < expression.length(); ) {
            if (++steps > 32 || !token.region(offset, expression.length()).lookingAt()) {
                throw rule(stage, "只支持对象字段、数组下标和通配符 JSONPath", null);
            }
            String key = token.group(1);
            String index = token.group(2);
            if (key == null && (index.startsWith("'") || index.startsWith("\""))) {
                key = index.substring(1, index.length() - 1).replace("\\'", "'").replace("\\\"", "\"").replace("\\\\", "\\");
            }
            List<Object> next = new ArrayList<>();
            for (Object value : values) {
                if (next.size() >= MAX_EPISODES * 2) break;
                if (key != null && value instanceof JSONObject) putValue(next, ((JSONObject) value).opt(key));
                if (key == null && value instanceof JSONArray) {
                    JSONArray array = (JSONArray) value;
                    if (index.equals("*")) {
                        for (int i = 0; i < array.length() && next.size() < MAX_EPISODES * 2; i++) putValue(next, array.opt(i));
                    } else putValue(next, array.opt(Integer.parseInt(index)));
                }
            }
            values = next;
            offset = token.end();
        }
        return values;
    }

    private static void putValue(List<Object> values, Object data) {
        if (data != null) values.add(data);
    }

    private static List<Object> sequence(List<Object> values) {
        if (values.size() != 1 || !(values.get(0) instanceof JSONArray)) return values;
        JSONArray array = (JSONArray) values.get(0);
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < Math.min(array.length(), MAX_EPISODES * 2); i++) putValue(result, array.opt(i));
        return result;
    }

    private static String firstPath(Object node, String expression, Stage stage) throws Failure {
        if (expression.isEmpty()) return "";
        List<Object> values = path(node, expression, stage);
        return values.isEmpty() ? "" : scalar(values.get(0)).trim();
    }

    private static Object renderValue(Object value, Map<String, Object> vars, int depth) throws IOException {
        if (depth > 16) throw new IOException("动画源请求模板层级过深");
        if (value instanceof String) {
            Matcher name = VARIABLE.matcher((String) value);
            if (name.matches() && vars.containsKey(name.group(1))) return vars.get(name.group(1));
            return render((String) value, vars, false);
        }
        try {
            if (value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                JSONObject output = new JSONObject();
                Iterator<String> keys = object.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    output.put(key, renderValue(object.opt(key), vars, depth + 1));
                }
                return output;
            }
            if (value instanceof JSONArray) {
                JSONArray array = (JSONArray) value;
                JSONArray output = new JSONArray();
                for (int i = 0; i < array.length(); i++) output.put(renderValue(array.opt(i), vars, depth + 1));
                return output;
            }
        } catch (JSONException malformed) { throw new IOException("动画源请求模板无法写入 JSON", malformed); }
        return value == null ? JSONObject.NULL : value;
    }

    private static String render(String template, Map<String, Object> vars, boolean encode) throws IOException {
        Matcher variables = VARIABLE.matcher(template);
        StringBuffer result = new StringBuffer();
        while (variables.find()) {
            String name = variables.group(1);
            if (!vars.containsKey(name)) throw new IOException("动画源模板缺少变量");
            String value = scalar(vars.get(name));
            variables.appendReplacement(result, Matcher.quoteReplacement(encode ? component(value) : value));
            if (result.length() > 128 * 1024) throw new IOException("动画源模板展开后的内容过长");
        }
        return variables.appendTail(result).toString();
    }

    private static String queryValues(JSONObject query, Map<String, Object> vars) throws IOException {
        List<String> pairs = new ArrayList<>();
        Iterator<String> keys = query.keys();
        while (keys.hasNext()) {
            if (pairs.size() >= 128) throw new IOException("动画源请求参数过多");
            String key = keys.next();
            Object value = renderValue(query.opt(key), vars, 0);
            String encodedKey = formValue(render(key, vars, false));
            if (value instanceof JSONArray) {
                JSONArray values = (JSONArray) value;
                for (int i = 0; i < Math.min(values.length(), 128); i++) pairs.add(encodedKey + "=" + formValue(scalar(values.opt(i))));
            } else pairs.add(encodedKey + "=" + formValue(scalar(value)));
        }
        return String.join("&", pairs);
    }

    private static String withQuery(String url, JSONObject query, Map<String, Object> vars) throws IOException {
        if (query.length() == 0) return url;
        String parameters = queryValues(query, vars);
        int fragment = url.indexOf('#');
        String suffix = fragment < 0 ? "" : url.substring(fragment);
        String head = fragment < 0 ? url : url.substring(0, fragment);
        int mark = head.indexOf('?');
        if (mark >= 0) {
            Set<String> replacementKeys = new LinkedHashSet<>();
            for (String pair : parameters.split("&")) replacementKeys.add(queryKey(pair));
            List<String> original = new ArrayList<>();
            for (String pair : head.substring(mark + 1).split("&")) {
                if (!pair.isEmpty() && !replacementKeys.contains(queryKey(pair))) original.add(pair);
            }
            head = head.substring(0, mark);
            if (!original.isEmpty()) parameters = String.join("&", original) + "&" + parameters;
        }
        return AnimeSource.web(head + "?" + parameters + suffix).toASCIIString();
    }

    private static String queryKey(String pair) throws IOException {
        int equals = pair.indexOf('=');
        return URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), "UTF-8");
    }

    private static String safeReferer(String from, String to) throws IOException {
        URI url = AnimeSource.web(from);
        if (ReaderEngine.sameOrigin(from, to)) return from.split("#", 2)[0];
        return url.getScheme() + "://" + url.getRawAuthority() + "/";
    }

    private static String resolve(String base, String raw) throws IOException {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.startsWith("#")) throw new IOException("动画源未提供有效地址");
        URI safeBase = AnimeSource.web(base);
        if (value.startsWith("?")) return AnimeSource.web(safeBase.toASCIIString().split("[?#]", 2)[0] + value).toASCIIString();
        try { return AnimeSource.web(safeBase.resolve(value.replace(" ", "%20")).toASCIIString()).toASCIIString(); }
        catch (IllegalArgumentException invalid) { throw new IOException("动画源地址无效", invalid); }
    }

    private static String optionalLink(String base, String value) {
        try { return resolve(base, value); }
        catch (IOException invalid) { return ""; }
    }

    private static String component(String value) throws IOException {
        return formValue(value).replace("+", "%20").replace("%7E", "~");
    }

    private static String formValue(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }
    private static String scalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean ? value.toString() : "";
    }
    private static String first(String value, String alternate) { return value == null || value.isEmpty() ? alternate : value; }
    private static String label(int number) { return "第 " + number + " 集"; }
    private static String roadLabel(int number) { return "播放线路 " + number; }
    private static Failure rule(Stage stage, String message, Throwable cause) { return new Failure(stage, Kind.RULE, message, cause); }

    private static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已停止查询动画源");
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        if (budget != null) budget.check();
    }
}
