package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.parser.Parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Public search and playback metadata for 猫耳FM. */
final class MusicMaoer {
    static final String ID = "maoerfm";
    static final String NAME = "猫耳FM";
    private static final String SITE = "https://www.missevan.com";
    private static final String USER_AGENT = "ANON-TOOL/" + BuildConfig.VERSION_NAME + " (Android; music search)";
    private static final int PLAYLIST_LIMIT = 128 * 1024;
    private static final Pattern SOUND_ID = Pattern.compile("[1-9][0-9]{0,17}");

    private MusicMaoer() {}

    static MusicOnlineEngine.SearchPage page(String query, int page) throws IOException {
        String address = SITE + "/sound/getsearch?s=" + URLEncoder.encode(query, "UTF-8")
                + "&p=" + page + "&type=3&page_size=" + MusicOnlineEngine.PAGE_SIZE;
        return parseSearch(MusicOnlineEngine.requestJson(address, SITE + "/sound/search", "", USER_AGENT));
    }

    static MusicOnlineEngine.SearchPage parseSearch(JSONObject response) throws IOException {
        JSONObject info = info(response);
        JSONArray items = info.optJSONArray("Datas");
        if (items == null) throw new IOException("猫耳FM 没有返回有效的搜索结果");
        List<MusicOnlineEngine.Song> songs = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < Math.min(items.length(), MusicOnlineEngine.PAGE_SIZE); i++) {
            JSONObject item = items.optJSONObject(i);
            if (!isFree(item)) continue;
            String id = item.optString("id", "");
            String title = text(item, "soundstr", 512);
            if (!SOUND_ID.matcher(id).matches() || title.isEmpty() || !seen.add(id)) continue;
            String image = officialUrl(item.optString("front_cover", ""));
            songs.add(new MusicOnlineEngine.Song(ID, id, title, text(item, "username", 256), "", image, duration(item)));
        }
        JSONObject pagination = info.optJSONObject("pagination");
        boolean hasMore = pagination == null ? items.length() >= MusicOnlineEngine.PAGE_SIZE
                : pagination.optInt("p", 1) < pagination.optInt("maxpage", 1);
        return new MusicOnlineEngine.SearchPage(songs, hasMore);
    }

    static String sourcePage(MusicOnlineEngine.Song song) {
        return SOUND_ID.matcher(song.id).matches() ? SITE + "/sound/player?id=" + song.id : "";
    }

    static LinkResolver.Result resolve(MusicOnlineEngine.Song song) throws IOException {
        String page = sourcePage(song);
        if (page.isEmpty()) throw new IOException("猫耳FM 声音编号无效");
        JSONObject response = MusicOnlineEngine.requestJson(SITE + "/sound/getsound?soundid=" + song.id, page, "", USER_AGENT);
        LinkResolver.Result media = parseMedia(response, song);
        List<String> candidates = media.backupUrl.isEmpty() ? List.of(media.url) : List.of(media.url, media.backupUrl);
        MusicImportTask task = MusicImportTask.current();
        IOException unavailable = null;
        for (String address : candidates) {
            try {
                String extension = extension(address);
                if ("m3u8".equals(extension)) validatePlaylist(loadPlaylist(address, page));
                else if (extension.isEmpty()) throw new IOException("猫耳FM 暂未提供可播放音轨");
                return new LinkResolver.Result(address, media.title, media.artist, media.artwork, NAME, page, page,
                        "", extension, "", media.durationMs);
            } catch (IOException failure) {
                if (task != null) task.check();
                if (Thread.currentThread().isInterrupted()) throw failure;
                if (unavailable == null || failure instanceof ProtectedStream) unavailable = failure;
            }
        }
        throw unavailable == null ? new IOException("猫耳FM 暂未提供可播放音轨") : unavailable;
    }

    static LinkResolver.Result parseMedia(JSONObject response, MusicOnlineEngine.Song song) throws IOException {
        JSONObject sound = info(response).optJSONObject("sound");
        if (sound == null || !song.id.equals(sound.optString("id", ""))) throw new IOException("猫耳FM 声音信息无效");
        // Recheck access at playback time. An old search result may have changed to a paid sound.
        if (!isFree(sound)) throw new IOException("这条猫耳FM 音频需要播放权限");
        String standard = officialUrl(sound.optString("soundurl", ""));
        String high = officialUrl(sound.optString("soundurl_128", ""));
        String address = high.isEmpty() ? standard : high;
        String backup = address.equals(standard) ? "" : standard;
        if (address.isEmpty()) throw new IOException("猫耳FM 暂未提供可播放音轨");
        String page = sourcePage(song);
        long duration = duration(sound);
        return new LinkResolver.Result(address, song.title, song.artist, song.coverUrl, NAME, page, page, backup,
                extension(address), "", duration > 0 ? duration : song.durationMs);
    }

    private static String extension(String address) {
        String path = URI.create(address).getPath().toLowerCase(Locale.ROOT);
        for (String type : List.of("m3u8", "m4a", "mp3", "aac", "flac", "ogg", "opus", "wav")) {
            if (path.endsWith("." + type)) return type;
        }
        return "";
    }

    private static final class ProtectedStream extends IOException {
        ProtectedStream() { super("该音频受猫耳FM 播放保护，请在原网站打开"); }
    }

    static void validatePlaylist(String body) throws IOException {
        boolean mediaSegments = false;
        String[] rows = body.split("\\r?\\n");
        if (rows.length == 0 || !"#EXTM3U".equals(rows[0].trim())) throw new IOException("猫耳FM 返回的播放清单无效");
        for (String row : rows) {
            row = row.trim();
            if (row.startsWith("#EXT-X-KEY:") || row.startsWith("#EXT-X-SESSION-KEY:")) {
                String attributes = row.substring(row.indexOf(':') + 1);
                if (!"METHOD=NONE".equals(attributes)) throw new ProtectedStream();
            }
            if (row.startsWith("#EXTINF:")) mediaSegments = true;
        }
        if (!mediaSegments) throw new IOException("猫耳FM 暂未提供可直接播放的清单，请在原网站打开");
    }

    private static String loadPlaylist(String address, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(4_000);
        connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Referer", referer);
        MusicImportTask task = MusicImportTask.current();
        long deadline = System.nanoTime() + 7_000_000_000L;
        try {
            if (task != null) task.watch(connection);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("猫耳FM 播放清单暂时无法读取");
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1; ) {
                    if (task != null) task.check();
                    if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
                        throw new IOException("猫耳FM 播放清单读取超时");
                    }
                    if (bytes.size() + count > PLAYLIST_LIMIT) throw new IOException("猫耳FM 播放清单过大");
                    bytes.write(buffer, 0, count);
                }
                if (task != null) task.check();
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        } catch (IOException failure) {
            if (task != null) task.check();
            throw new IOException("无法读取猫耳FM 播放清单，请在原网站打开");
        } finally {
            if (task != null) task.release(connection);
            connection.disconnect();
        }
    }

    private static JSONObject info(JSONObject response) throws IOException {
        JSONObject info = response.optJSONObject("info");
        if (!response.optBoolean("success") || info == null) throw new IOException("猫耳FM 暂未返回有效数据");
        return info;
    }

    private static boolean isFree(JSONObject item) {
        return item != null && "0".equals(item.optString("pay_type", ""))
                && item.optInt("episode_vip", 0) == 0 && !item.optBoolean("episode_vip");
    }

    private static String text(JSONObject item, String field, int limit) {
        if (item.isNull(field)) return "";
        String value = item.optString(field, "").trim();
        int bound = Math.min(limit, value.length());
        return Parser.unescapeEntities(value.substring(0, bound), false).trim();
    }

    private static long duration(JSONObject item) {
        long value = item.optLong("duration", 0);
        return value > 0 && value <= 86_400_000L ? value : 0;
    }

    private static String officialUrl(String value) {
        if (value.isEmpty() || value.length() > 4096) return "";
        try {
            URI address = URI.create(value.startsWith("//") ? "https:" + value : value);
            String host = address.getHost();
            if (host == null || !"https".equalsIgnoreCase(address.getScheme()) || address.getRawUserInfo() != null
                    || (address.getPort() != -1 && address.getPort() != 443)) return "";
            String domain = host.toLowerCase(Locale.ROOT);
            return domain.equals("missevan.com") || domain.endsWith(".missevan.com")
                    || domain.equals("maoercdn.com") || domain.endsWith(".maoercdn.com") ? address.toASCIIString() : "";
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }
}
