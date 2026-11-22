package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;

import java.io.ByteArrayOutputStream;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** In-app adapters for public music sources and a user-configured OpenSubsonic library. */
final class MusicOnlineEngine {
    static final String BILIBILI = "bilibili";
    static final String NAVIDROME = "navidrome";
    static final String AUDIUS = "audius";
    static final String MAOERFM = "maoerfm";
    static final String ARCHIVE = "archive";
    private static final String AUDIUS_API = "https://api.audius.co/v1/tracks";
    private static final String AUDIUS_APP = "app_name=ANON%20TOOL";
    private static final Pattern AUDIUS_ID = Pattern.compile("[A-Za-z0-9]{3,24}");
    static final int PAGE_SIZE = 20;
    private static final int CONNECT_MS = 5_000;
    private static final int READ_MS = 6_000;
    private static final int RESPONSE_LIMIT = 1_048_576;
    private static final String BILI_API = "https://api.bilibili.com/";
    private static final String BILI_SITE = "https://www.bilibili.com/";
    private static final String BILI_SEARCH = "https://search.bilibili.com/";
    private static final Pattern BILIBILI_ID = Pattern.compile("(?:BV[a-zA-Z0-9]{10}|av[0-9]{1,20}|au[0-9]{1,20})");
    private static final Pattern VISITOR_COOKIE = Pattern.compile("[a-zA-Z0-9_+/=%.-]{1,256}");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static volatile Visitor visitor;

    private MusicOnlineEngine() {}

    record Config(String baseUrl, String username, String password) {
        @Override public String toString() { return "OpenSubsonic configuration"; }
    }

    record SearchPage(List<Song> songs, boolean hasMore) {}

    static final class Song {
        final String provider, id, title, artist, album, coverUrl;
        final long durationMs;

        Song(String provider, String id, String title, String artist, String album, String coverUrl, long durationMs) {
            this.provider = provider;
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.coverUrl = coverUrl;
            this.durationMs = durationMs;
        }
    }

    private record Visitor(String cookie, long expiresAt) {}

    private static final class SourceFailure extends IOException {
        SourceFailure(String message) { super(message); }
    }

    static List<Song> search(String provider, Config config, String query, int page) throws IOException {
        return searchPage(provider, config, query, page).songs();
    }

    static SearchPage searchPage(String provider, Config config, String query, int page) throws IOException {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) return new SearchPage(List.of(), false);
        if (keyword.length() > 160 || page < 1 || page > 1000) throw new SourceFailure("搜索条件超出范围");
        if (AUDIUS.equals(provider)) return searchAudius(keyword, page);
        if (MAOERFM.equals(provider)) return MusicMaoer.page(keyword, page);
        if (ARCHIVE.equals(provider)) return MusicArchive.page(keyword, page);
        List<Song> songs;
        if (BILIBILI.equals(provider)) songs = searchBilibili(keyword, page);
        else if (NAVIDROME.equals(provider)) songs = searchNavidrome(config, keyword, page);
        else throw new SourceFailure("此音源暂未适配");
        return new SearchPage(songs, songs.size() >= PAGE_SIZE);
    }

    static LinkResolver.Result resolve(Song song, Config config) throws IOException {
        if (BILIBILI.equals(song.provider)) {
            if (!BILIBILI_ID.matcher(song.id).matches()) throw new SourceFailure("这条 B 站内容的编号无效");
            String path = song.id.startsWith("au") ? "audio/" : "video/";
            try {
                return LinkResolver.resolve(URI.create(BILI_SITE + path + song.id));
            } catch (LinkResolver.BilibiliRestricted restricted) {
                throw restricted;
            } catch (SocketTimeoutException timeout) {
                throw new SourceFailure("连接 B 站超时，请检查网络后重试");
            } catch (InterruptedIOException cancelled) {
                throw cancelled;
            } catch (Exception unavailable) {
                throw new SourceFailure("B 站暂未提供可播放音轨，请重试或选择其他结果");
            }
        }
        if (NAVIDROME.equals(song.provider)) {
            if (!validSongId(song.id)) throw new SourceFailure("这首歌在曲库中的编号无效");
            String address = subsonicUrl(config, "stream", "id=" + encode(song.id) + "&format=raw");
            return new LinkResolver.Result(address, song.title, song.artist, "", "Navidrome", "", "", "", "", "", song.durationMs);
        }
        if (AUDIUS.equals(song.provider)) {
            if (!AUDIUS_ID.matcher(song.id).matches()) throw new SourceFailure("Audius 曲目编号无效");
            String stream = AUDIUS_API + "/" + song.id + "/stream?" + AUDIUS_APP;
            return new LinkResolver.Result(stream, song.title, song.artist, song.coverUrl, "Audius",
                    "", sourcePage(song), "", "mp3", "", song.durationMs);
        }
        if (MAOERFM.equals(song.provider)) return MusicMaoer.resolve(song);
        if (ARCHIVE.equals(song.provider)) return MusicArchive.resolve(song);
        throw new SourceFailure("此音源暂未适配");
    }

    static LinkResolver.Result resolveDownload(Song song, Config config) throws IOException {
        if (NAVIDROME.equals(song.provider)) {
            if (!validSongId(song.id)) throw new SourceFailure("这首歌在曲库中的编号无效");
            String address = subsonicUrl(config, "download", "id=" + encode(song.id));
            return new LinkResolver.Result(address, song.title, song.artist, "", "Navidrome",
                    "", "", "", "", "", song.durationMs);
        }
        if (AUDIUS.equals(song.provider)) {
            if (!AUDIUS_ID.matcher(song.id).matches()) throw new SourceFailure("Audius 曲目编号无效");
            String address = AUDIUS_API + "/" + song.id;
            JSONObject track = requestJson(address + "?" + AUDIUS_APP, "", "").optJSONObject("data");
            return audiusDownload(song, track);
        }
        LinkResolver.Result media = resolve(song, config);
        if ("m3u8".equalsIgnoreCase(media.extension)) {
            throw new SourceFailure("这个音源只提供在线播放");
        }
        return media;
    }

    static LinkResolver.Result audiusDownload(Song song, JSONObject track) throws IOException {
        if (track == null || !song.id.equals(track.optString("id"))) {
            throw new SourceFailure("Audius 未返回有效曲目信息");
        }
        if (!track.optBoolean("is_downloadable") || track.optBoolean("is_unlisted")
                || track.optBoolean("is_stream_gated")) {
            throw new SourceFailure("Audius 作者未公开此曲的下载");
        }
        String address = AUDIUS_API + "/" + song.id + "/download?original=false&" + AUDIUS_APP;
        return new LinkResolver.Result(address, song.title, song.artist, song.coverUrl, "Audius",
                "", "", "", "mp3", "", song.durationMs);
    }

    static String sourcePage(Song song) {
        if (BILIBILI.equals(song.provider) && BILIBILI_ID.matcher(song.id).matches()) {
            return BILI_SITE + (song.id.startsWith("au") ? "audio/" : "video/") + song.id;
        }
        if (MAOERFM.equals(song.provider)) return MusicMaoer.sourcePage(song);
        if (ARCHIVE.equals(song.provider)) return MusicArchive.sourcePage(song);
        return "";
    }

    static String providerName(String provider) {
        return switch (provider) {
            case BILIBILI -> "哔哩哔哩";
            case NAVIDROME -> "Navidrome";
            case MAOERFM -> "猫耳FM";
            case AUDIUS -> "Audius";
            case ARCHIVE -> "Internet Archive";
            default -> "在线音源";
        };
    }

    static boolean publicProvider(String provider) {
        return BILIBILI.equals(provider) || MAOERFM.equals(provider) || AUDIUS.equals(provider) || ARCHIVE.equals(provider);
    }

    private static SearchPage searchAudius(String keyword, int page) throws IOException {
        JSONObject response = requestJson(AUDIUS_API + "/search?query=" + encode(keyword) + "&limit=" + PAGE_SIZE
                + "&offset=" + ((page - 1) * PAGE_SIZE) + "&" + AUDIUS_APP, "", "");
        JSONArray entries = response.optJSONArray("data");
        if (entries == null) throw new SourceFailure("Audius 未返回有效搜索结果");
        return new SearchPage(parseAudius(entries), entries.length() >= PAGE_SIZE);
    }

    static List<Song> parseAudius(JSONArray entries) {
        List<Song> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(entries.length(), PAGE_SIZE); i++) {
            JSONObject row = entries.optJSONObject(i);
            if (row == null || !row.optBoolean("is_streamable") || row.optBoolean("is_stream_gated")
                    || row.optBoolean("is_unlisted")) continue;
            String id = displayText(row, "id", 32);
            String title = displayText(row, "title", 512);
            if (!AUDIUS_ID.matcher(id).matches() || title.isEmpty() || !seen.add(id)) continue;
            JSONObject user = row.optJSONObject("user");
            JSONObject art = row.optJSONObject("artwork");
            String artist = user == null ? "" : displayText(user, "name", 256);
            String image = art == null ? "" : displayText(art, "480x480", 2_048);
            URI cover = webAddress(image);
            if (cover != null && !("https".equalsIgnoreCase(cover.getScheme())
                    && cover.getHost().toLowerCase(Locale.ROOT).endsWith(".audius.co"))) cover = null;
            result.add(new Song(AUDIUS, id, title, artist, "", cover == null ? "" : cover.toASCIIString(),
                    millis(row.optLong("duration"))));
        }
        return result;
    }

    static String lyrics(Song song, Config config) {
        if (Thread.currentThread().isInterrupted()) return "";
        if (!NAVIDROME.equals(song.provider)) {
            // B 站和猫耳的作者字段可能是上传者，曲名也可包含节目或视频标题。
            if (!AUDIUS.equals(song.provider)) return "";
            try { return MusicOpenLyrics.find(song); }
            catch (IOException unavailable) { return ""; }
        }
        if (!validSongId(song.id)) return "";
        try {
            JSONObject body = subsonic(config, "getLyricsBySongId", "id=" + encode(song.id));
            JSONObject list = body.optJSONObject("lyricsList");
            String structured = list == null ? "" : structuredLyrics(list.optJSONArray("structuredLyrics"));
            if (!structured.isEmpty()) return structured;
        } catch (IOException ignored) {
            // Older servers may expose only the original getLyrics endpoint.
        }
        if (song.artist.isEmpty() || Thread.currentThread().isInterrupted()) return "";
        try {
            JSONObject body = subsonic(config, "getLyrics", "artist=" + encode(song.artist) + "&title=" + encode(song.title));
            JSONObject lyric = body.optJSONObject("lyrics");
            return lyric == null ? "" : displayText(lyric, "value", 128_000);
        } catch (IOException unavailable) {
            return "";
        }
    }

    static void validateConfig(Config config) throws IOException {
        canonicalBaseUrl(config);
        if (config.username() == null || config.username().trim().isEmpty() || config.username().length() > 256
                || config.password() == null || config.password().isEmpty() || config.password().length() > 1024) {
            throw new SourceFailure("请输入 Navidrome 的用户名和密码");
        }
    }

    static String canonicalBaseUrl(Config config) throws IOException {
        String address = config == null || config.baseUrl() == null ? "" : config.baseUrl().trim();
        if (address.isEmpty()) throw new SourceFailure("请先设置 Navidrome 的服务器地址和账号");
        URI base = webAddress(address);
        if (base == null || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new SourceFailure("请输入完整的 HTTP 或 HTTPS 服务器地址");
        }
        String scheme = base.getScheme().toLowerCase(Locale.ROOT);
        String host = base.getHost().toLowerCase(Locale.ROOT);
        int port = base.getPort();
        boolean explicitPort = port != -1 && port != (scheme.equals("https") ? 443 : 80);
        String path = base.normalize().getRawPath().replaceFirst("/+$", "");
        return URI.create(scheme + "://" + host + (explicitPort ? ":" + port : "") + path).toASCIIString();
    }

    private static List<Song> searchBilibili(String keyword, int page) throws IOException {
        String address = BILI_API + "x/web-interface/search/type?search_type=video&keyword="
                + encode(keyword) + "&page=" + page + "&page_size=" + PAGE_SIZE;
        JSONObject data = biliData(requestJson(address, BILI_SEARCH, visitorCookie()));
        JSONArray entries = data.optJSONArray("result");
        if (entries == null) return List.of();
        List<Song> songs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < Math.min(entries.length(), PAGE_SIZE); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            String id = displayText(entry, "bvid", 30);
            if (!BILIBILI_ID.matcher(id).matches() && entry.optLong("aid") > 0) id = "av" + entry.optLong("aid");
            String title = Jsoup.parseBodyFragment(displayText(entry, "title", 2_048)).text();
            if (!BILIBILI_ID.matcher(id).matches() || title.isEmpty() || !ids.add(id)) continue;
            String image = displayText(entry, "pic", 2_048);
            URI cover = webAddress(image.startsWith("//") ? "https:" + image : image);
            String coverUrl = cover != null && cover.getHost().toLowerCase(Locale.ROOT).endsWith(".hdslb.com")
                    ? cover.toASCIIString() : "";
            songs.add(new Song(BILIBILI, id, title, displayText(entry, "author", 256), "", coverUrl,
                    videoDuration(entry.optString("duration"))));
        }
        return songs;
    }

    private static String visitorCookie() throws IOException {
        Visitor cached = visitor;
        if (cached != null && System.currentTimeMillis() < cached.expiresAt()) return cached.cookie();
        JSONObject guest = biliData(requestJson(BILI_API + "x/frontend/finger/spi", BILI_SITE, ""));
        String third = guest.optString("b_3"), fourth = guest.optString("b_4");
        if (!VISITOR_COOKIE.matcher(third).matches() || !VISITOR_COOKIE.matcher(fourth).matches()) {
            throw new SourceFailure("B 站访客会话暂时不可用，请稍后重试");
        }
        String cookie = "buvid3=" + third + "; buvid4=" + fourth;
        visitor = new Visitor(cookie, System.currentTimeMillis() + 900_000L);
        return cookie;
    }

    private static JSONObject biliData(JSONObject response) throws IOException {
        int status = response.optInt("code", -1);
        JSONObject data = response.optJSONObject("data");
        if (status == 0 && data != null) return data;
        if (status == -352 || status == -412 || status == -509) {
            throw new SourceFailure("B 站暂时限制了访客搜索，请稍后重试");
        }
        throw new SourceFailure("B 站没有返回可用搜索结果");
    }

    private static List<Song> searchNavidrome(Config config, String keyword, int page) throws IOException {
        JSONObject result = subsonic(config, "search3", "query=" + encode(keyword) + "&songCount=" + PAGE_SIZE
                + "&songOffset=" + ((page - 1) * PAGE_SIZE) + "&artistCount=0&albumCount=0");
        JSONObject search = result.optJSONObject("searchResult3");
        if (search == null) throw new SourceFailure("服务器没有返回有效的搜索结果");
        JSONArray entries = search.optJSONArray("song");
        if (entries == null) return List.of();
        List<Song> songs = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < Math.min(entries.length(), PAGE_SIZE); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || entry.optBoolean("isDir") || entry.optBoolean("isVideo")) continue;
            String id = entry.isNull("id") ? "" : entry.optString("id", "");
            String title = displayText(entry, "title", 512);
            if (!validSongId(id) || title.isEmpty() || !ids.add(id)) continue;
            songs.add(new Song(NAVIDROME, id, title, displayText(entry, "artist", 256),
                    displayText(entry, "album", 256), "", millis(entry.optLong("duration"))));
        }
        return songs;
    }

    private static JSONObject subsonic(Config config, String method, String parameters) throws IOException {
        JSONObject wrapper = requestJson(subsonicUrl(config, method, parameters), "", "");
        JSONObject response = wrapper.optJSONObject("subsonic-response");
        if (response == null) throw new SourceFailure("服务器没有返回有效的 Subsonic 响应");
        if ("ok".equals(response.optString("status"))) return response;
        JSONObject error = response.optJSONObject("error");
        int code = error == null ? -1 : error.optInt("code", -1);
        if (code == 40 || code == 41) throw new SourceFailure("服务器拒绝账号验证，请检查用户名和密码");
        if (code == 50 || code == 51) throw new SourceFailure("该账号没有访问当前曲库的权限");
        if (code == 20 || code == 30) throw new SourceFailure("服务器的 Subsonic 协议版本不兼容");
        throw new SourceFailure("服务器未能处理此曲库请求");
    }

    private static String subsonicUrl(Config config, String method, String parameters) throws IOException {
        validateConfig(config);
        byte[] saltBytes = new byte[12];
        RANDOM.nextBytes(saltBytes);
        String salt = hex(saltBytes);
        String token;
        try {
            token = hex(MessageDigest.getInstance("MD5").digest((config.password() + salt).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new SourceFailure("系统不支持 Subsonic 账号验证");
        }
        return canonicalBaseUrl(config) + "/rest/" + method + ".view?u=" + encode(config.username().trim())
                + "&t=" + token + "&s=" + salt + "&v=1.16.1&c=ANON_TOOL&f=json&" + parameters;
    }

    private static JSONObject requestJson(String address, String referer, String cookie) throws IOException {
        return requestJson(address, referer, cookie, referer.isEmpty() ? "ANON TOOL" : LinkResolver.USER_AGENT);
    }

    static JSONObject requestJson(String address, String referer, String cookie, String userAgent) throws IOException {
        HttpURLConnection request = null;
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        MusicImportTask task = MusicImportTask.current();
        long deadline = System.nanoTime() + (CONNECT_MS + READ_MS) * 1_000_000L;
        try {
            checkRequest(budget, task, deadline);
            request = (HttpURLConnection) URI.create(address).toURL().openConnection();
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(budget == null ? CONNECT_MS : Math.min(CONNECT_MS, budget.connectTimeoutMillis()));
            request.setReadTimeout(budget == null ? READ_MS : (int) Math.min(READ_MS, budget.remainingMillis()));
            request.setRequestProperty("Accept", "application/json");
            request.setRequestProperty("User-Agent", userAgent);
            if (!referer.isEmpty()) request.setRequestProperty("Referer", referer);
            if (!cookie.isEmpty()) request.setRequestProperty("Cookie", cookie);
            if (budget != null) budget.attach(request);
            if (task != null) task.watch(request);
            int status = request.getResponseCode();
            if (status == 401 || status == 403) throw new SourceFailure("音源拒绝访问，请检查账号或来源权限");
            if (status == 412 || status == 429) throw new SourceFailure("音源暂时限制了请求，请稍后重试");
            if (status >= 300 && status < 400) throw new SourceFailure("音源要求重定向，请使用实际服务器地址");
            if (status != HttpURLConnection.HTTP_OK) throw new SourceFailure("音源请求失败（HTTP " + status + "）");
            try (InputStream body = request.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = body.read(buffer)) != -1; ) {
                    checkRequest(budget, task, deadline);
                    if (output.size() + count > RESPONSE_LIMIT) throw new SourceFailure("音源响应过大");
                    output.write(buffer, 0, count);
                }
                checkRequest(budget, task, deadline);
                return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
            }
        } catch (SocketTimeoutException timeout) {
            if (task != null) task.check();
            throw new SourceFailure("音源响应超时");
        } catch (SourceFailure | InterruptedIOException expected) {
            throw expected;
        } catch (JSONException invalid) {
            throw new SourceFailure("音源没有返回有效的数据");
        } catch (IOException | IllegalArgumentException connectionFailed) {
            if (task != null) task.check();
            if (budget != null) budget.check();
            throw new SourceFailure("无法连接音源，请检查服务器地址或网络");
        } finally {
            if (task != null && request != null) task.release(request);
            if (request != null && (budget == null || budget.detach(request))) request.disconnect();
        }
    }

    private static void checkRequest(ReaderSearchQueue.RequestBudget budget, MusicImportTask task, long deadline)
            throws InterruptedIOException {
        if (task != null) task.check();
        if (budget != null) budget.check();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("请求已取消");
        if (System.nanoTime() >= deadline) throw new SocketTimeoutException();
    }

    private static String structuredLyrics(JSONArray variants) {
        if (variants == null) return "";
        String best = "";
        int bestRank = -1;
        for (int i = 0; i < Math.min(32, variants.length()); i++) {
            JSONObject variant = variants.optJSONObject(i);
            if (variant == null) continue;
            boolean timed = variant.optBoolean("synced");
            String kind = variant.optString("kind", "");
            int rank = (kind.isEmpty() || kind.equals("main") ? 2 : 0) + (timed ? 1 : 0);
            if (rank <= bestRank) continue;
            JSONArray lines = variant.optJSONArray("line");
            if (lines == null) continue;
            StringBuilder text = new StringBuilder();
            for (int j = 0; j < Math.min(1_200, lines.length()) && text.length() < 128_000; j++) {
                JSONObject line = lines.optJSONObject(j);
                if (line == null) continue;
                String value = displayText(line, "value", 1_024).replace('\r', ' ').replace('\n', ' ');
                long start = line.optLong("start", -1);
                if (value.isEmpty() || (timed && (start < 0 || start > 86_400_000L))) continue;
                if (timed) text.append(String.format(Locale.ROOT, "[%02d:%02d.%03d]", start / 60_000, start / 1000 % 60, start % 1000));
                text.append(value).append('\n');
            }
            if (text.length() > 0) {
                best = text.toString();
                bestRank = rank;
            }
        }
        return best;
    }

    private static long videoDuration(String text) {
        if (!text.matches("[0-9]{1,5}(?::[0-9]{1,2}){0,2}")) return 0;
        long seconds = 0;
        for (String part : text.split(":")) seconds = seconds * 60 + Long.parseLong(part);
        return millis(seconds);
    }

    private static long millis(long seconds) { return seconds > 0 && seconds <= 86_400 ? seconds * 1000 : 0; }

    private static String displayText(JSONObject data, String field, int limit) {
        if (data.isNull(field)) return "";
        String value = data.optString(field, "").trim();
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static boolean validSongId(String id) {
        if (id == null || id.isEmpty() || id.length() > 512) return false;
        for (int i = 0; i < id.length(); i++) if (Character.isISOControl(id.charAt(i))) return false;
        return true;
    }

    private static URI webAddress(String address) {
        if (address.isEmpty() || address.length() > 2_048) return null;
        try {
            URI uri = URI.create(address);
            String scheme = uri.getScheme();
            return ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) && uri.getHost() != null
                    && uri.getRawUserInfo() == null && uri.getPort() != 0 && uri.getPort() <= 65535 ? uri : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String encode(String value) throws IOException { return URLEncoder.encode(value, "UTF-8"); }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
