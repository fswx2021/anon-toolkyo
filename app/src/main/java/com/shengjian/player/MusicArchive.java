package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Public music from the Internet Archive's open audio and netlabel collections. */
final class MusicArchive {
    static final String ID = "archive";
    private static final String SITE = "https://archive.org";
    private static final String USER_AGENT = "ANON-TOOL/" + BuildConfig.VERSION_NAME + " (Android; com.shengjian.player)";
    private static final int PAGE_SIZE = 20;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
    private static final Pattern LICENSE = Pattern.compile(
            "/(?:licenses/(?:by|by-sa|by-nd|by-nc|by-nc-sa|by-nc-nd)/(?:1\\.0|2\\.[05]|3\\.0|4\\.0)(?:/[-a-z]{2,15})?|publicdomain/(?:mark|zero)/1\\.0)/?");
    private static final Set<String> COLLECTIONS = Set.of("opensource_audio", "netlabels");
    private static final Set<String> FORMATS = Set.of("mp3", "vbr mp3", "64kbps mp3", "128kbps mp3", "192kbps mp3");

    private MusicArchive() {}

    static MusicOnlineEngine.SearchPage page(String keyword, int page) throws IOException {
        String phrase = '"' + keyword.strip().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        String query = "mediatype:audio AND (collection:opensource_audio OR collection:netlabels)"
                + " AND format:MP3 AND licenseurl:* AND (title:" + phrase + " OR creator:" + phrase + ")";
        String address = SITE + "/advancedsearch.php?q=" + encode(query)
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=licenseurl&fl%5B%5D=collection"
                + "&rows=" + PAGE_SIZE + "&page=" + page + "&output=json";
        return parsePage(MusicOnlineEngine.requestJson(address, "", "", USER_AGENT), page);
    }

    static MusicOnlineEngine.SearchPage parsePage(JSONObject payload, int page) throws IOException {
        JSONObject response = payload.optJSONObject("response");
        JSONArray entries = response == null ? null : response.optJSONArray("docs");
        if (entries == null) throw new IOException("Internet Archive 没有返回有效的音乐搜索结果");
        Set<String> seen = new HashSet<>();
        List<MusicOnlineEngine.Song> songs = new ArrayList<>();
        for (int i = 0; i < Math.min(PAGE_SIZE, entries.length()); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !publicMusic(entry)) continue;
            String id = text(entry, "identifier", 128);
            String title = text(entry, "title", 250);
            if (!valid(id) || title.isEmpty() || !seen.add(id)) continue;
            String art = SITE + "/services/img/" + id;
            songs.add(new MusicOnlineEngine.Song(ID, id, title + " · 首轨", text(entry, "creator", 160), title, art, 0));
        }
        boolean more = response.optInt("numFound", 0) > (long) page * PAGE_SIZE;
        return new MusicOnlineEngine.SearchPage(songs, more);
    }

    static LinkResolver.Result resolve(MusicOnlineEngine.Song song) throws IOException {
        if (!valid(song.id)) throw new IOException("Internet Archive 的音频编号无效");
        JSONObject payload = MusicOnlineEngine.requestJson(SITE + "/metadata/" + song.id, "", "", USER_AGENT);
        return resolveMetadata(payload, song);
    }

    static LinkResolver.Result resolveMetadata(JSONObject payload, MusicOnlineEngine.Song song) throws IOException {
        JSONObject metadata = payload.optJSONObject("metadata");
        if (metadata == null || !song.id.equals(metadata.optString("identifier")) || !publicMusic(metadata)
                || restricted(payload) || restricted(metadata) || !"audio".equals(metadata.optString("mediatype"))) {
            throw new IOException("该条目不再提供带开放许可的公开音频");
        }
        JSONArray files = payload.optJSONArray("files");
        JSONObject first = null;
        int firstNumber = Integer.MAX_VALUE;
        if (files != null) {
            for (int i = 0; i < Math.min(10_000, files.length()); i++) {
                JSONObject file = files.optJSONObject(i);
                if (file == null || restricted(file) || !FORMATS.contains(file.optString("format").toLowerCase(Locale.ROOT))) continue;
                String path = filename(file);
                if (path.isEmpty()) continue;
                int number = trackNumber(file, path);
                if (first == null || number < firstNumber || number == firstNumber && betterFile(file, first)) {
                    first = file;
                    firstNumber = number;
                }
            }
        }
        if (first == null) throw new IOException("该条目没有公开的 MP3 音轨");
        String name = filename(first);
        String title = text(first, "title", 250);
        if (title.isEmpty()) {
            title = name.substring(name.lastIndexOf('/') + 1, name.length() - 4);
        }
        String artist = text(first, "creator", 160);
        if (artist.isEmpty()) artist = song.artist;
        StringBuilder address = new StringBuilder(SITE).append("/download/").append(song.id);
        for (String segment : name.split("/")) address.append('/').append(encode(segment));
        double seconds = first.optDouble("length", 0);
        long duration = Double.isFinite(seconds) && seconds > 0 && seconds <= 86_400 ? Math.round(seconds * 1_000) : 0;
        return new LinkResolver.Result(address.toString(), title, artist, song.coverUrl, "Internet Archive",
                "", sourcePage(song), "", "mp3", "", duration);
    }

    static String sourcePage(MusicOnlineEngine.Song song) {
        return valid(song.id) ? SITE + "/details/" + song.id : "";
    }

    private static boolean publicMusic(JSONObject entry) {
        Object collection = entry.opt("collection");
        boolean inCollection = collection instanceof String text && COLLECTIONS.contains(text);
        if (collection instanceof JSONArray names) {
            for (int i = 0; i < Math.min(100, names.length()); i++) {
                inCollection |= COLLECTIONS.contains(names.optString(i));
            }
        }
        if (!inCollection || restricted(entry)) return false;
        Object license = entry.opt("licenseurl");
        if (license instanceof String url) return openLicense(url);
        if (license instanceof JSONArray list && list.length() > 0 && list.length() <= 16) {
            for (int i = 0; i < list.length(); i++) {
                if (!openLicense(list.optString(i))) return false;
            }
            return true;
        }
        return false;
    }

    private static boolean openLicense(String raw) {
        try {
            URI url = URI.create(raw.strip());
            return ("https".equalsIgnoreCase(url.getScheme()) || "http".equalsIgnoreCase(url.getScheme()))
                    && ("creativecommons.org".equalsIgnoreCase(url.getHost()) || "www.creativecommons.org".equalsIgnoreCase(url.getHost()))
                    && url.getUserInfo() == null && url.getPort() == -1 && LICENSE.matcher(url.getPath()).matches();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean restricted(JSONObject row) {
        for (String key : List.of("private", "access-restricted-item", "is_dark")) {
            String value = row.optString(key);
            if ("true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value) || "1".equals(value)) return true;
        }
        return false;
    }

    private static boolean valid(String id) {
        return id != null && IDENTIFIER.matcher(id).matches();
    }

    private static String filename(JSONObject file) {
        String name = file.optString("name");
        if (name.length() > 512 || name.contains("\\") || !name.toLowerCase(Locale.ROOT).endsWith(".mp3")
                || name.chars().anyMatch(Character::isISOControl)) return "";
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return "";
        }
        return name;
    }

    private static int trackNumber(JSONObject file, String name) {
        String raw = file.optString("track").split("/", 2)[0].strip();
        if (raw.matches("[0-9]{1,4}") && Integer.parseInt(raw) > 0) return Integer.parseInt(raw);
        String basename = name.substring(name.lastIndexOf('/') + 1);
        int end = 0;
        while (end < Math.min(basename.length(), 4) && basename.charAt(end) >= '0' && basename.charAt(end) <= '9') end++;
        if (end > 0 && end < basename.length() && Character.isWhitespace(basename.charAt(end))) {
            return Integer.parseInt(basename.substring(0, end));
        }
        return Integer.MAX_VALUE;
    }

    private static boolean betterFile(JSONObject candidate, JSONObject current) {
        boolean candidateOriginal = "original".equals(candidate.optString("source"));
        boolean currentOriginal = "original".equals(current.optString("source"));
        if (candidateOriginal != currentOriginal) return candidateOriginal;
        return candidate.optString("name").compareToIgnoreCase(current.optString("name")) < 0;
    }

    private static String text(JSONObject object, String key, int max) {
        Object field = object.opt(key);
        if (field instanceof JSONArray names) field = names.opt(0);
        if (!(field instanceof String value)) return "";
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").strip();
        if (cleaned.length() <= max) return cleaned;
        int end = Character.isHighSurrogate(cleaned.charAt(max - 1)) ? max - 1 : max;
        return cleaned.substring(0, end).strip();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }
}
