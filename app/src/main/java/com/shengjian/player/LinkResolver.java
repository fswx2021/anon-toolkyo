package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LinkResolver {
    static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";

    private static final String BILI_WEB = "https://www.bilibili.com/";
    private static final String BILI_API = "https://api.bilibili.com/";
    private static final String BILI_MUSIC = BILI_WEB + "audio/music-service-c/web/";

    static final class BilibiliRestricted extends IOException {
        BilibiliRestricted(String message) { super(message); }
    }

    private static final Pattern WEB_LINK = Pattern.compile(
            "https?://[^\\s<>\"'，。！？、；：“”‘’【】《》「」]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BILI_ID = Pattern.compile(
            "(?:(?i:BV)[0-9A-Za-z]{10}|(?i:av)[0-9]{1,20})");
    private static final Pattern BILI_VIDEO = Pattern.compile(
            "^/(?:video|s/video)/([^/]+)/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILI_AUDIO = Pattern.compile(
            "^/audio/au([0-9]{1,20})/?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BILI_COLLECTION = Pattern.compile(
            "^/([0-9]{1,19})/(favlist|channel/collectiondetail)/?$");
    private static final int COLLECTION_PAGE_SIZE = 30;
    private static final int COLLECTION_MAX_PAGES = 10;
    private static final int[] WBI_KEY_ORDER = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13
    };

    private LinkResolver() {}

    record VideoPart(int number, String name) {}

    record CollectionVideo(String title, String videoUrl) {}

    record VideoCollection(String title, List<CollectionVideo> entries, boolean limited) {}

    private record CollectionAddress(long owner, long collection) {
        String referer() {
            return "https://space.bilibili.com/" + owner + "/favlist?fid=" + collection + "&ftype=collect";
        }
    }

    @FunctionalInterface
    interface CollectionPage {
        JSONObject fetch(String address, String referer) throws IOException, JSONException;
    }

    static URI importAddress(String text) throws IOException {
        URI link = extractLink(text);
        return isShortLink(link) ? expandShortLink(link) : link;
    }

    static VideoCollection videoCollection(URI link) throws IOException, JSONException {
        return videoCollection(link, LinkResolver::getJson);
    }

    static VideoCollection videoCollection(URI link, CollectionPage transport) throws IOException, JSONException {
        CollectionAddress collection = collectionAddress(link);
        if (collection == null) return null;
        String referer = collection.referer();
        String title = "B 站公开合集";
        List<CollectionVideo> items = new ArrayList<>();
        Set<String> videos = new LinkedHashSet<>();
        int total = -1, received = 0;
        for (int number = 1; number <= COLLECTION_MAX_PAGES; number++) {
            JSONObject data = dataOf(transport.fetch(BILI_API + "x/polymer/web-space/seasons_archives_list?mid="
                    + collection.owner + "&season_id=" + collection.collection + "&page_num=" + number
                    + "&page_size=" + COLLECTION_PAGE_SIZE, referer));
            JSONObject meta = data.optJSONObject("meta");
            JSONObject page = data.optJSONObject("page");
            JSONArray archives = data.optJSONArray("archives");
            if (meta == null || meta.optLong("mid") != collection.owner
                    || meta.optLong("season_id") != collection.collection) {
                throw new IOException("合集链接中的 UP 主或合集编号与返回内容不一致");
            }
            if (archives == null || page == null || page.optInt("page_num") != number
                    || page.optInt("total", -1) < 0 || archives.length() > COLLECTION_PAGE_SIZE) {
                throw new IOException("无法读取这个 B 站合集的视频列表");
            }
            total = page.optInt("total");
            title = textValue(meta, "name", title);
            int before = items.size();
            for (int i = 0; i < archives.length(); i++) {
                JSONObject entry = archives.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("bvid");
                if (!BILI_ID.matcher(id).matches() || !id.startsWith("BV")) {
                    long aid = entry.optLong("aid");
                    if (aid <= 0) continue;
                    id = "av" + aid;
                }
                if (videos.add(id)) items.add(new CollectionVideo(textValue(entry, "title", id), BILI_WEB + "video/" + id));
            }
            received += archives.length();
            if (received >= total) break;
            if (items.size() == before) throw new IOException("这个 B 站合集返回了不完整的视频列表，请重试");
        }
        if (items.isEmpty()) throw new IOException("这个 B 站合集没有可导入的公开视频");
        return new VideoCollection(title, List.copyOf(items), received < total);
    }

    private static CollectionAddress collectionAddress(URI link) {
        if (!"space.bilibili.com".equalsIgnoreCase(link.getHost())) return null;
        Matcher path = BILI_COLLECTION.matcher(link.getPath());
        if (!path.matches()) return null;
        boolean favoritesPage = path.group(2).equals("favlist");
        if (favoritesPage && !"collect".equalsIgnoreCase(queryValue(link, "ftype"))) return null;
        String sid = queryValue(link, favoritesPage ? "fid" : "sid");
        if (!sid.matches("[0-9]{1,19}")) return null;
        try {
            long owner = Long.parseLong(path.group(1));
            long collection = Long.parseLong(sid);
            return owner > 0 && collection > 0 ? new CollectionAddress(owner, collection) : null;
        } catch (NumberFormatException overflow) {
            return null;
        }
    }

    static final class VideoParts {
        private final String id;
        private final JSONObject video;
        final String title;
        final int selected;
        final List<VideoPart> entries;

        private VideoParts(String id, JSONObject video, int selected, List<VideoPart> entries) {
            this.id = id;
            this.video = video;
            this.title = textValue(video, "title", id);
            this.selected = selected;
            this.entries = List.copyOf(entries);
        }

        Result resolve(int part) throws Exception { return resolveBilibiliVideo(id, part, video); }
    }

    static VideoParts videoParts(URI link) throws Exception {
        if (!isBilibili(link) || BILI_AUDIO.matcher(link.getPath()).matches()) return null;
        String id = videoId(link);
        if (id.isEmpty()) return null;
        return videoParts(id, fetchBilibiliVideo(id), videoPage(link));
    }

    static VideoParts videoParts(String id, JSONObject video, int selected) {
        JSONArray pages = video.optJSONArray("pages");
        List<VideoPart> entries = new ArrayList<>();
        for (int i = 0; pages != null && i < pages.length(); i++) {
            JSONObject part = pages.optJSONObject(i);
            if (part == null || part.optLong("cid") <= 0) continue;
            int number = i + 1;
            entries.add(new VideoPart(number, textValue(part, "part", "分 P " + number)));
        }
        return new VideoParts(id, video, selected, entries);
    }

    static final class Result {
        final String url;
        final String title;
        final String artist;
        final String artwork;
        final String source;
        final String referer;
        final String sourceUrl;
        final String backupUrl;
        final List<String> mediaUrls;
        final String extension;
        final String lyricUrl;
        final long durationMs;

        Result(String url, String title, String artist, String artwork, String source,
               String referer, String sourceUrl, String backupUrl, String extension,
               String lyricUrl, long durationMs) {
            this(backupUrl.isEmpty() || url.equals(backupUrl) ? List.of(url) : List.of(url, backupUrl),
                    title, artist, artwork, source, referer, sourceUrl, extension, lyricUrl, durationMs);
        }

        Result(List<String> mediaUrls, String title, String artist, String artwork, String source,
               String referer, String sourceUrl, String extension, String lyricUrl, long durationMs) {
            this.mediaUrls = List.copyOf(mediaUrls);
            this.url = this.mediaUrls.get(0);
            this.title = title;
            this.artist = artist;
            this.artwork = artwork;
            this.source = source;
            this.referer = referer;
            this.sourceUrl = sourceUrl;
            this.backupUrl = this.mediaUrls.size() > 1 ? this.mediaUrls.get(1) : "";
            this.extension = extension;
            this.lyricUrl = lyricUrl;
            this.durationMs = durationMs;
        }
    }

    static Result resolve(String text) throws Exception {
        return resolve(importAddress(text));
    }

    static Result resolve(URI link) throws Exception {
        if (!isBilibili(link)) {
            String name = basename(link);
            String extension = extensionOfName(name);
            if (!extension.isEmpty()) {
                name = name.substring(0, name.length() - extension.length() - 1);
            }
            String source = link.getHost();
            return new Result(link.toString(), name.isEmpty() ? source : name, "", "",
                    source, "", link.toString(), "", extension, "", 0);
        }

        Matcher audio = BILI_AUDIO.matcher(link.getPath());
        if (audio.matches()) {
            return resolveBilibiliAudio(audio.group(1));
        }
        if (collectionAddress(link) != null) throw new IOException("请单独粘贴一个 B 站合集链接，以便选择视频");
        String id = videoId(link);
        if (id.isEmpty()) {
            throw new IOException("请选择单个 B 站公开视频或音频，复制它的分享链接");
        }
        int page = videoPage(link);
        return resolveBilibiliVideo(id, page);
    }

    static List<String> importInputs(String text) throws IOException {
        String share = text == null ? "" : text.trim();
        Set<String> links = new LinkedHashSet<>();
        for (String line : share.split("\\R")) {
            String value = line.trim();
            URI direct = parseWebUri(value);
            if (direct != null || BILI_ID.matcher(value).matches()) {
                links.add(direct == null ? value : direct.toString());
            } else {
                Matcher matcher = WEB_LINK.matcher(value);
                while (matcher.find()) {
                    URI link = parseWebUri(matcher.group().replaceFirst("[).,!?;]+$", ""));
                    if (link != null) links.add(link.toString());
                }
            }
        }
        if (links.isEmpty()) throw new IOException("请粘贴完整的分享链接或音视频文件链接");
        return new ArrayList<>(links);
    }

    private static URI extractLink(String text) throws IOException {
        String input = text == null ? "" : text.trim();
        if (BILI_ID.matcher(input).matches()) {
            return URI.create(BILI_WEB + "video/" + input);
        }
        URI pastedLink = parseWebUri(input);
        if (pastedLink != null) {
            return pastedLink;
        }
        Matcher matcher = WEB_LINK.matcher(input);
        while (matcher.find()) {
            String candidate = matcher.group().replaceFirst("[).,!?;]+$", "");
            URI link = parseWebUri(candidate);
            if (link != null) {
                return link;
            }
        }
        throw new IOException("请粘贴完整的分享链接或音视频文件链接");
    }

    private static URI parseWebUri(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                    && uri.getHost() != null && uri.getRawUserInfo() == null ? uri : null;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isBilibili(URI link) {
        String host = link.getHost().toLowerCase(Locale.ROOT);
        return host.equals("bilibili.com") || host.endsWith(".bilibili.com");
    }

    private static boolean isShortLink(URI link) {
        String host = link.getHost().toLowerCase(Locale.ROOT);
        return host.equals("b23.tv") || host.equals("bili2233.cn");
    }

    private static URI expandShortLink(URI link) throws IOException {
        URI current = URI.create("https://" + link.getHost() + link.getRawPath()
                + (link.getRawQuery() == null ? "" : "?" + link.getRawQuery()));
        for (int hop = 0; hop < 6; hop++) {
            if (isBilibili(current) && (!videoId(current).isEmpty() || collectionAddress(current) != null
                    || BILI_AUDIO.matcher(current.getPath()).matches())) {
                return current;
            }
            HttpURLConnection connection = open(current.toString(), BILI_WEB);
            try {
                int status = connection.getResponseCode();
                String redirect = connection.getHeaderField("Location");
                if (status < 300 || status >= 400 || redirect == null) {
                    break;
                }
                URI next = parseWebUri(current.resolve(redirect).toString());
                if (next == null || (!isShortLink(next) && !isBilibili(next))) {
                    break;
                }
                current = "http".equalsIgnoreCase(next.getScheme())
                        ? URI.create("https" + next.toString().substring(4)) : next;
            } finally {
                disconnect(connection);
            }
        }
        throw new IOException("这个 B 站短链接暂时无法打开，请复制视频网页链接再试");
    }

    private static String videoId(URI link) {
        Matcher video = BILI_VIDEO.matcher(link.getPath());
        String id = video.matches() ? video.group(1) : queryValue(link, "bvid");
        if (id.isEmpty() && link.getPath().equals("/player.html")) {
            String aid = queryValue(link, "aid");
            id = aid.isEmpty() ? "" : "av" + aid;
        }
        if (!BILI_ID.matcher(id).matches()) {
            return "";
        }
        return id.regionMatches(true, 0, "BV", 0, 2)
                ? "BV" + id.substring(2) : "av" + id.substring(2);
    }

    private static int videoPage(URI link) throws IOException {
        String selected = queryValue(link, "p");
        if (selected.isEmpty() && link.getPath().equals("/player.html")) {
            selected = queryValue(link, "page");
        }
        if (selected.isEmpty()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(selected);
            if (page > 0) {
                return page;
            }
        } catch (NumberFormatException ignored) {
            // Keep the same error for malformed and out-of-range page numbers.
        }
        throw new IOException("这个 B 站链接的分 P 编号无效");
    }

    private static String queryValue(URI link, String key) {
        String query = link.getRawQuery();
        if (query != null) {
            for (String field : query.split("&")) {
                int equals = field.indexOf('=');
                if (equals >= 0 && field.substring(0, equals).equals(key)) {
                    return decode(field.substring(equals + 1));
                }
            }
        }
        return "";
    }

    private static Result resolveBilibiliVideo(String id, int page) throws Exception {
        return resolveBilibiliVideo(id, page, fetchBilibiliVideo(id));
    }

    private static JSONObject fetchBilibiliVideo(String id) throws IOException, JSONException {
        String idParameter = id.startsWith("BV") ? "bvid=" + id : "aid=" + id.substring(2);
        return dataOf(getJson(BILI_API + "x/web-interface/view?" + idParameter, BILI_WEB));
    }

    private static Result resolveBilibiliVideo(String id, int page, JSONObject video) throws Exception {
        String idParameter = id.startsWith("BV") ? "bvid=" + id : "aid=" + id.substring(2);
        JSONArray parts = video.optJSONArray("pages");
        JSONObject part = parts == null ? null : parts.optJSONObject(page - 1);
        if (page > 1 && part == null) {
            throw new IOException("这个视频没有所选分 P");
        }
        long cid = part == null ? video.optLong("cid") : part.optLong("cid");
        if (cid <= 0) {
            throw new IOException("未找到这个 B 站视频的可用音轨");
        }
        String bvid = textValue(video, "bvid", id);
        String sourceUrl = BILI_WEB + "video/" + bvid + (page > 1 ? "?p=" + page : "");
        Stream stream = videoPlayback(idParameter, bvid, cid, sourceUrl);
        String title = textValue(video, "title", bvid);
        if (parts != null && parts.length() > 1 && part != null) {
            String label = textValue(part, "part", "");
            if (!label.isEmpty() && !label.equals(title)) {
                title += " · " + label;
            }
        }
        JSONObject owner = video.optJSONObject("owner");
        long duration = stream.durationMs;
        if (duration <= 0) {
            duration = (part == null ? video.optLong("duration") : part.optLong("duration")) * 1000;
        }
        return new Result(stream.urls, title, owner == null ? "" : textValue(owner, "name", ""),
                webUrl(video.optString("pic")), "哔哩哔哩", sourceUrl, sourceUrl,
                stream.extension, "", duration);
    }

    private static Stream videoPlayback(String idParameter, String bvid, long cid,
                                        String referer) throws Exception {
        String options = "&cid=" + cid + "&qn=64&fnver=0&fnval=16&fourk=0";
        try {
            return videoStream(dataOf(getJson(
                    BILI_API + "x/player/playurl?" + idParameter + options, referer)));
        } catch (IOException | JSONException firstFailure) {
            if (firstFailure instanceof BilibiliRestricted || firstFailure instanceof InterruptedIOException
                    || !BILI_ID.matcher(bvid).matches() || !bvid.startsWith("BV")) {
                throw firstFailure;
            }
            try {
                String params = signedPlaybackQuery(bvid, cid);
                return videoStream(dataOf(getJson(
                        BILI_API + "x/player/wbi/playurl?" + params, referer)));
            } catch (Exception secondFailure) {
                firstFailure.addSuppressed(secondFailure);
                throw firstFailure;
            }
        }
    }

    private static String signedPlaybackQuery(String bvid, long cid) throws Exception {
        JSONObject session = getJson(BILI_API + "x/web-interface/nav", BILI_WEB).getJSONObject("data");
        JSONObject images = session.getJSONObject("wbi_img");
        String key = stem(images.getString("img_url")) + stem(images.getString("sub_url"));
        if (key.length() < 64) {
            throw new IOException("B 站暂时未提供播放校验信息");
        }
        StringBuilder mixedKey = new StringBuilder(32);
        for (int index : WBI_KEY_ORDER) {
            mixedKey.append(key.charAt(index));
        }

        Map<String, String> values = new TreeMap<>();
        values.put("bvid", bvid);
        values.put("cid", Long.toString(cid));
        values.put("fnval", "16");
        values.put("fnver", "0");
        values.put("fourk", "0");
        values.put("qn", "64");
        values.put("wts", Long.toString(System.currentTimeMillis() / 1000));
        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append(entry.getKey()).append('=').append(URLEncoder.encode(entry.getValue(), "UTF-8"));
        }
        byte[] digest = MessageDigest.getInstance("MD5")
                .digest((query.toString() + mixedKey).getBytes(StandardCharsets.UTF_8));
        query.append("&w_rid=");
        for (byte value : digest) {
            query.append(Character.forDigit((value >> 4) & 15, 16));
            query.append(Character.forDigit(value & 15, 16));
        }
        return query.toString();
    }

    private static String stem(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static final class Stream {
        final List<String> urls;
        final String extension;
        final long durationMs;

        Stream(List<String> urls, String extension, long durationMs) {
            this.urls = List.copyOf(urls);
            this.extension = extension;
            this.durationMs = durationMs;
        }
    }

    static Stream videoStream(JSONObject playback) throws IOException {
        if (playback.optInt("is_preview") == 1 || playback.optBoolean("is_preview")) {
            throw new BilibiliRestricted("这个 B 站视频只提供试看");
        }
        long duration = playback.optLong("timelength");
        JSONObject dash = playback.optJSONObject("dash");
        JSONArray audio = dash == null ? null : dash.optJSONArray("audio");
        if (audio != null) {
            List<JSONObject> variants = new ArrayList<>();
            for (int i = 0; i < audio.length(); i++) {
                JSONObject candidate = audio.optJSONObject(i);
                if (candidate != null) variants.add(candidate);
            }
            variants.sort(Comparator.comparingLong(LinkResolver::audioScore).reversed());
            Set<String> urls = new LinkedHashSet<>();
            for (JSONObject variant : variants) {
                addUrl(urls, variant.optString("baseUrl"));
                addUrl(urls, variant.optString("base_url"));
                addBackups(urls, variant);
            }
            if (!urls.isEmpty()) return new Stream(preferDifferentHosts(urls), "m4a", duration);
        }
        JSONArray legacy = playback.optJSONArray("durl");
        JSONObject file = legacy != null && legacy.length() == 1 ? legacy.optJSONObject(0) : null;
        if (file != null) {
            Set<String> urls = new LinkedHashSet<>();
            addUrl(urls, file.optString("url"));
            addBackups(urls, file);
            if (!urls.isEmpty()) {
                List<String> ordered = preferDifferentHosts(urls);
                String suffix = extension(ordered.get(0));
                return new Stream(ordered, suffix.isEmpty() ? "mp4" : suffix, duration);
            }
        }
        throw new IOException("这个 B 站视频暂未提供可导入的音轨");
    }

    private static long audioScore(JSONObject audio) {
        boolean aac = audio.optString("codecs").toLowerCase(Locale.ROOT).startsWith("mp4a");
        long bandwidth = audio.optLong("bandwidth", audio.optInt("id"));
        return Math.max(0, Math.min(bandwidth, 999_999)) + (aac ? 1_000_000L : 0);
    }

    private static void addBackups(Set<String> urls, JSONObject media) {
        addUrls(urls, media.optJSONArray("backupUrl"));
        addUrls(urls, media.optJSONArray("backup_url"));
    }

    private static void addUrls(Set<String> urls, JSONArray candidates) {
        if (candidates == null) return;
        for (int i = 0; i < candidates.length(); i++) addUrl(urls, candidates.optString(i));
    }

    private static void addUrl(Set<String> urls, String value) {
        String url = webUrl(value);
        if (!url.isEmpty()) urls.add(url);
    }

    private static List<String> preferDifferentHosts(Set<String> urls) {
        Map<String, String> byHost = new LinkedHashMap<>();
        for (String url : urls) {
            byHost.putIfAbsent(URI.create(url).getHost().toLowerCase(Locale.ROOT), url);
        }
        Set<String> ordered = new LinkedHashSet<>(byHost.values());
        ordered.addAll(urls);
        return List.copyOf(ordered);
    }

    private static Result resolveBilibiliAudio(String sid) throws IOException, JSONException {
        String sourceUrl = BILI_WEB + "audio/au" + sid;
        JSONObject song = dataOf(getJson(BILI_MUSIC + "song/info?sid=" + sid, sourceUrl));
        JSONObject playback = dataOf(getJson(BILI_MUSIC
                + "url?sid=" + sid + "&privilege=2&quality=2", sourceUrl));
        Set<String> urls = new LinkedHashSet<>();
        addUrls(urls, playback.optJSONArray("cdns"));
        if (urls.isEmpty()) {
            throw new IOException("这个 B 站音频暂未提供可用播放地址");
        }
        List<String> ordered = preferDifferentHosts(urls);
        String extension = extension(ordered.get(0));
        return new Result(ordered, textValue(song, "title", "音频 au" + sid),
                textValue(song, "author", textValue(song, "uname", "")),
                webUrl(song.optString("cover")), "哔哩哔哩", sourceUrl, sourceUrl,
                extension.isEmpty() ? "m4a" : extension,
                webUrl(song.optString("lyric")), song.optLong("duration") * 1000);
    }

    private static String webUrl(String value) {
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        URI uri = parseWebUri(value);
        return uri == null ? "" : uri.toString();
    }

    private static JSONObject dataOf(JSONObject response) throws IOException {
        int code = response.optInt("code", -1);
        JSONObject data = response.optJSONObject("data");
        if (code == 0 && data != null) {
            return data;
        }
        if (code == -352 || code == -412 || code == -509) {
            throw new BilibiliRestricted("B 站暂时限制了链接访问，请稍后再试");
        }
        if (code == -101 || code == -10403 || code == -403) {
            throw new BilibiliRestricted("这个 B 站内容需要登录或播放权限");
        }
        String message = textValue(response, "message", "");
        throw new IOException(message.isEmpty() ? "B 站暂时未提供播放信息" : "B 站返回：" + message);
    }

    private static String textValue(JSONObject object, String key, String fallback) {
        if (object.isNull(key)) {
            return fallback;
        }
        String value = object.optString(key).trim();
        return value.isEmpty() ? fallback : value;
    }

    private static JSONObject getJson(String address, String referer) throws IOException, JSONException {
        HttpURLConnection connection = open(address, referer);
        try {
            int status = connection.getResponseCode();
            if (status == 412 || status == 429) {
                throw new BilibiliRestricted("B 站暂时限制了链接访问，请稍后再试");
            }
            if (status == 401 || status == 403) {
                throw new BilibiliRestricted("B 站拒绝了本次访问，请在原站确认播放权限");
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("B 站未能提供播放信息（HTTP " + status + "）");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    body.append(buffer, 0, count);
                    if (body.length() > 2_000_000) {
                        throw new IOException("B 站响应超出可读取范围");
                    }
                }
                return new JSONObject(body.toString());
            }
        } finally {
            disconnect(connection);
        }
    }

    private static HttpURLConnection open(String address, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(12_000);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        MusicImportTask task = MusicImportTask.current();
        if (task != null) task.watch(connection);
        return connection;
    }

    private static void disconnect(HttpURLConnection connection) {
        MusicImportTask task = MusicImportTask.current();
        if (task != null) task.release(connection);
        connection.disconnect();
    }

    private static String extension(String address) {
        URI link = parseWebUri(address);
        return link == null ? "" : extensionOfName(basename(link));
    }

    private static String extensionOfName(String name) {
        int dot = name.lastIndexOf('.');
        String suffix = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return suffix.matches("[a-z0-9]{1,8}") ? suffix : "";
    }

    private static String basename(URI link) {
        String path = link.getRawPath();
        return decode(path.substring(path.lastIndexOf('/') + 1).replace("+", "%2B"));
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (IOException | IllegalArgumentException ignored) {
            return value;
        }
    }
}
