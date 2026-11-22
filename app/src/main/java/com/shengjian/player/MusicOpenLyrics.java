package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/** Looks up synchronized lyrics for a known recording using LRCLIB's public search API. */
final class MusicOpenLyrics {
    private static final String ENDPOINT = "https://lrclib.net/api/search";
    private static final int MAX_METADATA = 256;
    private static final int MAX_RESPONSE = 2 * 1024 * 1024;
    private static final int MAX_LYRICS = 48_000;
    private static final long MAX_DURATION_ERROR_MS = 2_000;
    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");
    private static final Object REQUESTS = new Object();
    private static boolean fetching;
    private static long blockedUntilNs;

    private MusicOpenLyrics() {}

    static String find(MusicOnlineEngine.Song song) throws IOException {
        if (song == null || !queryText(song.title) || !queryText(song.artist)
                || song.durationMs < 1_000 || song.durationMs > 3_600_000) return "";
        String address = ENDPOINT + "?track_name=" + encode(song.title) + "&artist_name=" + encode(song.artist);
        if (queryText(song.album)) address += "&album_name=" + encode(song.album);
        return pick(read(address), song);
    }

    static String pick(String payload, MusicOnlineEngine.Song song) throws IOException {
        try {
            JSONArray results = new JSONArray(payload);
            String title = normalize(song.title);
            String artist = normalize(song.artist);
            String album = normalize(song.album);
            String best = "";
            boolean bestAlbum = false;
            long bestDistance = Long.MAX_VALUE;
            for (int i = 0; i < Math.min(results.length(), 100); i++) {
                JSONObject candidate = results.optJSONObject(i);
                if (candidate == null || candidate.optBoolean("instrumental")
                        || !title.equals(normalize(candidate.optString("trackName", candidate.optString("name"))))
                        || !artist.equals(normalize(candidate.optString("artistName")))) continue;
                double seconds = candidate.optDouble("duration", 0);
                if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 3_600) continue;
                long distance = Math.abs(Math.round(seconds * 1_000) - song.durationMs);
                if (distance > MAX_DURATION_ERROR_MS) continue;
                boolean sameAlbum = !album.isEmpty() && album.equals(normalize(candidate.optString("albumName")));
                if (bestAlbum && !sameAlbum || sameAlbum == bestAlbum && distance >= bestDistance) continue;
                if (!(candidate.opt("syncedLyrics") instanceof String synced) || synced.isBlank()
                        || synced.length() > MAX_LYRICS || synced.indexOf('\0') >= 0) continue;
                boolean timed = Lyrics.parse(synced).stream().anyMatch(line -> line.timeMs >= 0 && !line.text.isBlank());
                if (!timed) continue;
                best = synced;
                bestAlbum = sameAlbum;
                bestDistance = distance;
            }
            return best;
        } catch (JSONException invalid) {
            throw new IOException("LRCLIB 返回的歌词格式无效");
        }
    }

    private static String read(String address) throws IOException {
        synchronized (REQUESTS) {
            if (fetching || blockedUntilNs != 0 && System.nanoTime() < blockedUntilNs) return "[]";
            fetching = true;
        }
        long retryMs = 350;
        HttpURLConnection connection = null;
        try {
            check();
            connection = (HttpURLConnection) URI.create(address).toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4_000);
            connection.setReadTimeout(5_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "ANON-TOOL/" + BuildConfig.VERSION_NAME
                    + " (Android; com.shengjian.player)");
            int status = connection.getResponseCode();
            if (status == 429 || status == 503) {
                retryMs = backoff(connection.getHeaderField("Retry-After"));
                return "[]";
            }
            if (status == 404) return "[]";
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("LRCLIB 暂不可用");
            long deadline = System.nanoTime() + 5_000_000_000L;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    check();
                    if (System.nanoTime() - deadline >= 0) throw new IOException("歌词请求超时");
                    if (bytes.size() + count > MAX_RESPONSE) throw new IOException("歌词响应内容过多");
                    bytes.write(buffer, 0, count);
                }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            if (connection != null) connection.disconnect();
            synchronized (REQUESTS) {
                blockedUntilNs = System.nanoTime() + retryMs * 1_000_000L;
                fetching = false;
            }
        }
    }

    private static long backoff(String retry) {
        long seconds = 60;
        if (retry != null && retry.matches("[0-9]{1,9}")) seconds = Long.parseLong(retry);
        long millis = Math.max(350, seconds * 1_000);
        return millis + ThreadLocalRandom.current().nextLong(Math.max(1, millis / 4));
    }

    private static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消歌词查询");
    }

    private static boolean queryText(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_METADATA
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String unified = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACES.matcher(unified.replace('’', '\'').replace('‘', '\'')).replaceAll(" ").trim();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value.trim(), StandardCharsets.UTF_8.name());
    }
}
