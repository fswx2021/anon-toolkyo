package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.DefaultHandler2;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;

/** Read-only danmaku lookup and import for a single episode. */
final class AnimeDanmaku {
    static final int SCROLL = 1, BOTTOM = 4, TOP = 5;
    static final String SOURCE_BILI = "bili", SOURCE_GAMER = "gamer", SOURCE_DANDAN = "dandan", SOURCE_OTHER = "other";
    private static final URI PROVIDER = URI.create("https://api.dandanplay.net");
    private static final URI COMMUNITY_PROVIDER = URI.create("https://danmaku-api.152468.xyz");
    private static final String SEARCH_PATH = "/api/v2/search/episodes";
    private static final String BANGUMI_PATH = "/api/v2/bangumi/bgmtv/";
    private static final int RESOURCE_NOT_FOUND = 7;
    private static final String COMMENT_PATH = "/api/v2/comment/";
    static final int MAX_SEARCH_BYTES = 512 * 1024;
    static final int MAX_COMMENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_COMMENTS = 24_000;
    private static final int MAX_TEXT_LENGTH = 220;
    private static final int MAX_CANDIDATES = 100;
    private static final int MAX_LABEL = 200;
    private static final int CONFIDENT_SCORE = 120;
    private static final Pattern EPISODE = Pattern.compile(
            "(?:^|[\\s:：-])(?:第\\s*([0-9]{1,5})\\s*[集话話回]|(?:episode|ep|e)\\.?\\s*([0-9]{1,5})(?![0-9.])(?:$|\\b))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern WHOLE_NUMBER = Pattern.compile("[0-9]{1,5}");
    private static final Pattern SPECIAL = Pattern.compile("(?i)(?:^|[\\s\\[(_])(?:ova|oad|sp|special|extra)(?:[\\s\\])_.:-]|[0-9]|$)|番外|特典|预告|預告|总集篇|總集篇");

    private AnimeDanmaku() {}

    static final class Credentials {
        private final String appId, appSecret;

        Credentials(String appId, String appSecret) {
            this.appId = appId == null ? "" : appId.trim();
            this.appSecret = appSecret == null ? "" : appSecret.trim();
        }

        boolean configured() {
            return !appId.isEmpty() && appId.length() <= 128 && appId.chars().allMatch(c -> c >= 33 && c <= 126)
                    && !appSecret.isEmpty() && appSecret.length() <= 512 && appSecret.chars().noneMatch(Character::isISOControl);
        }
    }

    static final class Candidate {
        final long episodeId;
        final String animeTitle, episodeTitle;
        final int score;

        Candidate(long episodeId, String animeTitle, String episodeTitle, int score) {
            this.episodeId = episodeId;
            this.animeTitle = animeTitle;
            this.episodeTitle = episodeTitle;
            this.score = score;
        }
    }

    static final class Comment {
        final long timeMs;
        final int mode, argb;
        final String text, source;

        Comment(long timeMs, int mode, int argb, String text, String source) {
            this.timeMs = timeMs;
            this.mode = mode;
            this.argb = argb;
            this.text = text;
            this.source = source;
        }
    }

    static List<Candidate> search(Credentials credentials, String title, int episode) throws IOException {
        String term = title == null ? "" : title.trim();
        if (term.codePointCount(0, term.length()) < 2 || term.length() > MAX_LABEL) {
            throw new IOException("请输入 2 到 200 个字符的作品名称");
        }
        if (episode < 0 || episode > 99_999) throw new IOException("集数超出范围");
        String query = "anime=" + URLEncoder.encode(term, "UTF-8") + "&v2=true"
                + (episode == 0 ? "" : "&episode=" + episode);
        return parseSearch(fetch(credentials, SEARCH_PATH, query, MAX_SEARCH_BYTES), term, episode);
    }

    static Candidate match(Credentials credentials, String title, int episode, long bangumiId) throws IOException {
        if (episode <= 0 || episode > 99_999) return null;
        if (bangumiId > 0 && bangumiId <= Integer.MAX_VALUE) {
            try {
                return parseMappedEpisode(fetch(credentials, BANGUMI_PATH + bangumiId, "", MAX_SEARCH_BYTES), title, episode);
            } catch (ProviderError error) {
                if (!error.lookupUnavailable) throw error;
            }
        }
        String term = title == null ? "" : title.trim();
        if (term.codePointCount(0, term.length()) < 2 || term.length() > MAX_LABEL) return null;
        return bestMatch(search(credentials, term, episode));
    }

    static Candidate parseMappedEpisode(String json, String title, int episode) throws IOException {
        return parseMappedEpisode(response(json), title, episode);
    }

    static Candidate parseMappedEpisode(JSONObject result, String title, int episode) throws IOException {
        JSONObject show = result.optJSONObject("bangumi");
        if (result.has("bangumi") && result.isNull("bangumi")) {
            throw new ProviderError("弹弹play没有此 Bangumi 条目的映射", true);
        }
        JSONArray episodes = show == null ? null : show.optJSONArray("episodes");
        if (episodes == null) {
            if (show != null && show.has("episodes") && show.isNull("episodes")) return null;
            throw new IOException("弹弹play未返回可读的剧集列表");
        }
        if (episode <= 0 || episodes.length() > 1000) return null;
        String name = plain(string(show, "animeTitle"), MAX_LABEL);
        if (name.isEmpty()) name = plain(title == null ? "" : title, MAX_LABEL);
        Candidate match = null;
        for (int i = 0; i < episodes.length(); i++) {
            checkInterrupted();
            JSONObject row = episodes.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("episodeId", 0);
            String label = plain(string(row, "episodeTitle"), MAX_LABEL);
            String number = Normalizer.normalize(string(row, "episodeNumber").trim(), Normalizer.Form.NFKC);
            if (id <= 0 || SPECIAL.matcher(label).find()) continue;
            int explicit = episodeNumber(label);
            int found = number.isEmpty() ? explicit : decimalEpisode(number);
            if (found != episode || (explicit != 0 && explicit != episode)) continue;
            if (match != null && match.episodeId != id) return null;
            match = new Candidate(id, name, label.isEmpty() ? number : label, CONFIDENT_SCORE);
        }
        return match;
    }

    static List<Comment> comments(Credentials credentials, long episodeId) throws IOException {
        if (episodeId <= 0) throw new IOException("弹幕剧集编号无效");
        return parseComments(fetch(credentials, COMMENT_PATH + episodeId, "withRelated=true&chConvert=0", MAX_COMMENT_BYTES));
    }

    static List<Candidate> parseSearch(String json, String title, int episode) throws IOException {
        JSONObject response = response(json);
        JSONArray titles = response.optJSONArray("animes");
        if (titles == null) {
            if (response.has("animes") && response.isNull("animes")) return Collections.emptyList();
            throw new IOException("弹弹play未返回可读的剧集列表");
        }
        String query = comparableTitle(title);
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        boolean truncated = response.optBoolean("hasMore") || titles.length() > 200;
        for (int i = 0; i < Math.min(titles.length(), 200); i++) {
            checkInterrupted();
            JSONObject anime = titles.optJSONObject(i);
            if (anime == null) continue;
            String animeTitle = plain(string(anime, "animeTitle"), MAX_LABEL);
            JSONArray episodes = anime.optJSONArray("episodes");
            if (animeTitle.isEmpty() || episodes == null) continue;
            String name = comparableTitle(animeTitle);
            boolean exact = !query.isEmpty() && query.equals(name);
            int titleScore = exact ? 85 : !query.isEmpty() && name.contains(query) ? 30 : 0;
            if (episodes.length() > 1000) truncated = true;
            for (int e = 0; e < Math.min(episodes.length(), 1000); e++) {
                JSONObject row = episodes.optJSONObject(e);
                if (row == null) continue;
                long id = row.optLong("episodeId", 0);
                String label = plain(string(row, "episodeTitle"), MAX_LABEL);
                if (id <= 0 || label.isEmpty()) continue;
                int found = episodeNumber(label);
                int score = titleScore + (episode > 0 && episode == found ? 35 : 0);
                Candidate proposal = new Candidate(id, animeTitle, label, score);
                Candidate old = candidates.get(id);
                if (old == null || old.score < score) candidates.put(id, proposal);
            }
        }
        List<Candidate> sorted = new ArrayList<>(candidates.values());
        sorted.sort(Comparator.comparingInt((Candidate candidate) -> candidate.score).reversed());
        if (sorted.size() > MAX_CANDIDATES) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_CANDIDATES));
            truncated = true;
        }
        if (truncated && !sorted.isEmpty() && sorted.get(0).score >= CONFIDENT_SCORE) {
            for (int i = 0; i < sorted.size() && sorted.get(i).score >= CONFIDENT_SCORE; i++) {
                Candidate candidate = sorted.get(i);
                sorted.set(i, new Candidate(candidate.episodeId, candidate.animeTitle, candidate.episodeTitle, CONFIDENT_SCORE - 1));
            }
        }
        return Collections.unmodifiableList(sorted);
    }

    static Candidate bestMatch(List<Candidate> choices) {
        Candidate best = null;
        boolean ambiguous = false;
        for (Candidate choice : choices) {
            if (choice.score < CONFIDENT_SCORE) continue;
            if (best == null || choice.score > best.score) {
                best = choice;
                ambiguous = false;
            } else if (choice.score == best.score && choice.episodeId != best.episodeId) ambiguous = true;
        }
        return ambiguous ? null : best;
    }

    private static int decimalEpisode(String value) {
        return WHOLE_NUMBER.matcher(value).matches() ? Integer.parseInt(value) : 0;
    }

    /** A list index is not reliable for split cours and sites that reverse their playlists. */
    static int episodeNumber(String title) {
        String value = Normalizer.normalize(title == null ? "" : title.trim(), Normalizer.Form.NFKC);
        if (value.length() > MAX_LABEL || SPECIAL.matcher(value).find()) return 0;
        int number = decimalEpisode(value);
        if (number > 0) return number;
        Matcher matcher = EPISODE.matcher(value);
        if (!matcher.find()) return 0;
        return Integer.parseInt(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
    }

    static List<Comment> parseComments(String json) throws IOException {
        JSONObject response = response(json);
        JSONArray rows = response.optJSONArray("comments");
        if (rows == null) {
            if (response.has("comments") && response.isNull("comments") && response.optInt("count", -1) == 0) {
                return Collections.emptyList();
            }
            throw new IOException("弹弹play未返回可读的弹幕列表");
        }
        List<Comment> output = new ArrayList<>(Math.min(rows.length(), MAX_COMMENTS));
        for (int i = 0; i < rows.length() && output.size() < MAX_COMMENTS; i++) {
            if ((i & 255) == 0) checkInterrupted();
            JSONObject row = rows.optJSONObject(i);
            if (row != null) addComment(output, string(row, "p"), string(row, "m"), false);
        }
        return ordered(output);
    }

    static List<Comment> importXml(InputStream input) throws IOException {
        CommentXml parser = new CommentXml();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            XMLReader xml = factory.newSAXParser().getXMLReader();
            xml.setContentHandler(parser);
            xml.setErrorHandler(parser);
            xml.setEntityResolver(parser);
            xml.setProperty("http://xml.org/sax/properties/lexical-handler", parser);
            xml.parse(new InputSource(new LimitedInput(input, MAX_COMMENT_BYTES, 0)));
        } catch (ParserConfigurationException | SAXException invalid) {
            throw new IOException("无法读取 B 站格式的弹幕 XML", invalid);
        }
        if (!parser.bilibili) throw new IOException("请选择 B 站格式的弹幕 XML");
        return ordered(parser.comments);
    }

    private static final class CommentXml extends DefaultHandler2 {
        final List<Comment> comments = new ArrayList<>();
        final StringBuilder message = new StringBuilder();
        int level;
        boolean bilibili;
        String parameters;

        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException {
            throw new SAXException("XML 文档类型声明不可用于弹幕文件");
        }

        @Override public InputSource resolveEntity(String name, String publicId, String baseUri, String systemId)
                throws SAXException {
            throw new SAXException("弹幕 XML 不允许外部实体");
        }

        @Override public void startElement(String uri, String local, String name, Attributes attributes) throws SAXException {
            if (++level > 32) throw new SAXException("弹幕 XML 嵌套过深");
            if (level == 1) bilibili = name.equals("i");
            if (level != 2 || !bilibili || !name.equals("d") || comments.size() >= MAX_COMMENTS) return;
            parameters = attributes.getValue("p");
            message.setLength(0);
        }

        @Override public void characters(char[] value, int start, int count) {
            if (parameters == null || level != 2) return;
            message.append(value, start, Math.min(count, MAX_TEXT_LENGTH + 2 - message.length()));
        }

        @Override public void endElement(String uri, String local, String name) {
            if (level-- == 2 && parameters != null) {
                addComment(comments, parameters, message.toString(), true);
                parameters = null;
            }
        }
    }

    private static void addComment(List<Comment> output, String parameters, String body, boolean xml) {
        if (parameters.length() > 512) return;
        String[] fields = parameters.split(",", xml ? 5 : 4);
        if (fields.length < (xml ? 4 : 3)) return;
        try {
            double seconds = Double.parseDouble(fields[0]);
            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 24 * 3600) return;
            int mode = Integer.parseInt(fields[1].trim());
            if (xml && (mode == 2 || mode == 3)) mode = SCROLL;
            if (mode != SCROLL && mode != BOTTOM && mode != TOP) return;
            int color = Integer.parseInt(fields[xml ? 3 : 2].trim());
            if (color < 0 || color > 0x00ffffff) return;
            String text = plain(body, MAX_TEXT_LENGTH);
            if (!text.isEmpty()) output.add(new Comment(Math.round(seconds * 1_000), mode, 0xff000000 | color,
                    text, xml ? SOURCE_BILI : commentSource(fields.length < 4 ? "" : fields[3])));
        } catch (NumberFormatException ignored) { /* Skip a damaged comment. */ }
    }

    private static String commentSource(String userField) {
        String field = userField.trim().toLowerCase(Locale.ROOT);
        if (!field.startsWith("[")) return SOURCE_DANDAN;
        int end = field.indexOf(']');
        if (end < 0) return SOURCE_OTHER;
        switch (field.substring(1, end)) {
            case "bili": case "bilibili": return SOURCE_BILI;
            case "gamer": case "bahamut": return SOURCE_GAMER;
            case "dandan": case "dandanplay": return SOURCE_DANDAN;
            default: return SOURCE_OTHER;
        }
    }

    private static List<Comment> ordered(List<Comment> comments) {
        comments.sort(Comparator.comparingLong(comment -> comment.timeMs));
        return Collections.unmodifiableList(comments);
    }

    static JSONObject response(String json) throws IOException {
        Object parsed;
        try { parsed = AnimeSource.parse(json); }
        catch (IOException invalid) { throw new IOException("弹弹play返回的数据格式有误"); }
        if (!(parsed instanceof JSONObject)) throw new IOException("弹弹play返回的数据格式有误");
        JSONObject object = (JSONObject) parsed;
        if ((object.has("success") && !object.optBoolean("success", false)) || object.optInt("errorCode", 0) != 0) {
            int code = object.optInt("errorCode", 0);
            String message = object.optString("errorMessage", "").toLowerCase(Locale.ROOT);
            if (code == 429 || message.contains("quota") || message.contains("配额") || message.contains("限流")) {
                throw new IOException("弹弹play请求过多或已达应用配额，请稍后再试");
            }
            throw new ProviderError("弹弹play未能完成请求" + (code > 0 ? "（错误 " + code + "）" : ""),
                    code == RESOURCE_NOT_FOUND);
        }
        return object;
    }

    private static String fetch(Credentials credentials, String path, String query, int limit) throws IOException {
        if (credentials == null || !credentials.configured()) throw new IOException("请先填写弹弹play的 AppId 和 AppSecret");
        return fetch(PROVIDER, credentials, path, query, limit);
    }

    static String fetchCommunity(String path, String query, int limit) throws IOException {
        return fetch(COMMUNITY_PROVIDER, null, path, query, limit);
    }

    private static String fetch(URI provider, Credentials credentials, String path, String query, int limit) throws IOException {
        boolean community = credentials == null;
        String service = community ? "社区弹幕服务" : "弹弹play";
        URI url = provider.resolve(query.isEmpty() ? path : path + "?" + query);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(16);
        try {
            for (int hop = 0; hop < 5; hop++) {
                if (community && (!"https".equalsIgnoreCase(url.getScheme())
                        || !provider.getHost().equalsIgnoreCase(url.getHost()) || url.getFragment() != null
                        || url.getRawUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443))) {
                    throw new ProviderError("社区弹幕服务返回了站外地址");
                }
                HttpURLConnection request = (HttpURLConnection) url.toURL().openConnection();
                try {
                    request.setInstanceFollowRedirects(false);
                    request.setConnectTimeout(Math.min(5_000, remainingMillis(deadline)));
                    request.setReadTimeout(Math.min(7_000, remainingMillis(deadline)));
                    request.setRequestProperty("Accept", "application/json");
                    request.setRequestProperty("Accept-Encoding", "gzip");
                    if (community) request.setRequestProperty("User-Agent", "ANON TOOL/0.22.4");
                    if (!community && hop == 0) {
                        long timestamp = System.currentTimeMillis() / 1000;
                        request.setRequestProperty("X-AppId", credentials.appId);
                        request.setRequestProperty("X-Timestamp", Long.toString(timestamp));
                        request.setRequestProperty("X-Signature", signature(credentials, timestamp, path));
                    }
                    int status = request.getResponseCode();
                    remainingMillis(deadline);
                    if (status >= 300 && status < 400) {
                        url = redirect(url, request.getHeaderField("Location"));
                        continue;
                    }
                    if (status == 401 || status == 403) {
                        throw new ProviderError(community ? "社区弹幕服务拒绝访问"
                                : "弹弹play拒绝访问，请检查应用凭证和系统时间");
                    }
                    if (status == 429) throw new ProviderError(service + "请求过多或已达应用配额，请稍后再试");
                    if (status != 200) {
                        throw new ProviderError(service + "返回 HTTP " + status, status == 404 || status == 405 || status == 501);
                    }
                    try (InputStream raw = request.getInputStream();
                         InputStream input = "gzip".equalsIgnoreCase(request.getContentEncoding()) ? new GZIPInputStream(raw) : raw;
                         InputStream checked = new LimitedInput(input, limit, deadline)) {
                        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        byte[] chunk = new byte[8192];
                        for (int count; (count = checked.read(chunk)) >= 0; ) bytes.write(chunk, 0, count);
                        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                    }
                } finally { request.disconnect(); }
            }
        } catch (SocketTimeoutException timeout) { throw new IOException(service + "响应超时，请稍后再试"); }
        catch (ProviderError | InterruptedIOException precise) { throw precise; }
        catch (IOException | IllegalArgumentException network) { throw new IOException("无法连接" + service); }
        throw new IOException(service + "的下载跳转次数过多");
    }

    private static final class ProviderError extends IOException {
        final boolean lookupUnavailable;

        ProviderError(String message) { this(message, false); }

        ProviderError(String message, boolean lookupUnavailable) {
            super(message);
            this.lookupUnavailable = lookupUnavailable;
        }
    }

    static String signature(Credentials credentials, long seconds, String path) {
        String value = credentials.appId + seconds + path + credentials.appSecret;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException unsupported) { throw new IllegalStateException("SHA-256 is required", unsupported); }
    }

    static URI redirect(URI current, String location) throws IOException {
        if (location == null || location.length() > 4096) throw new ProviderError("弹弹play返回了无效下载地址");
        try {
            URI target = current.resolve(location);
            String host = target.getHost();
            if ("https".equalsIgnoreCase(target.getScheme()) && host != null && host.indexOf('.') > 0
                    && !host.chars().allMatch(c -> c == '.' || (c >= '0' && c <= '9'))
                    && !host.toLowerCase(Locale.ROOT).endsWith(".local") && target.getRawUserInfo() == null
                    && (target.getPort() == -1 || target.getPort() == 443)) return target;
        } catch (IllegalArgumentException ignored) { }
        throw new ProviderError("弹弹play返回了不受支持的下载地址");
    }

    private static int remainingMillis(long deadline) throws IOException {
        checkInterrupted();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException();
        return (int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("弹幕读取已取消");
    }

    private static final class LimitedInput extends FilterInputStream {
        long remaining;
        final long deadline;

        LimitedInput(InputStream input, long maxBytes, long deadline) {
            super(input);
            remaining = maxBytes;
            this.deadline = deadline;
        }

        @Override public int read() throws IOException {
            beforeRead();
            int value = in.read();
            charge(value == -1 ? 0 : 1);
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            beforeRead();
            int count = in.read(bytes, offset, (int) Math.min(length, remaining + 1));
            if (count > 0) charge(count);
            return count;
        }

        private void beforeRead() throws IOException {
            if (deadline == 0) checkInterrupted(); else remainingMillis(deadline);
        }

        private void charge(int bytes) throws IOException {
            remaining -= bytes;
            if (remaining < 0) throw new ProviderError("弹幕数据超过 8 MiB 容量限制");
        }
    }

    static String comparableTitle(String value) {
        String title = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(title.length());
        title.codePoints().filter(Character::isLetterOrDigit).forEach(normalized::appendCodePoint);
        return normalized.toString();
    }

    static String string(JSONObject row, String name) {
        Object value = row.opt(name);
        return value instanceof String ? (String) value : "";
    }

    static String plain(String value, int limit) {
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), limit));
        for (int i = 0; i < value.length() && cleaned.length() < limit; ) {
            int code = value.codePointAt(i);
            i += Character.charCount(code);
            if (Character.isISOControl(code) || code == 0x2028 || code == 0x2029) cleaned.append(' ');
            else if (cleaned.length() + Character.charCount(code) <= limit) cleaned.appendCodePoint(code);
        }
        return cleaned.toString().trim();
    }
}
