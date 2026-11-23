package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class AnimeDiscovery {
    private static final int MAX_ENTRIES = 24;
    static final int MAX_JSON_LENGTH = 512 * 1024;
    private static final String RANKING = "https://api.bgm.tv/v0/subjects?type=2&sort=rank&limit=" + MAX_ENTRIES;
    private static final String POPULAR = "https://api.kazumi.fyi/kazumi/v1/popular/subjects?limit=" + MAX_ENTRIES;
    private static final String TRENDS = "https://next.bgm.tv/p1/trending/subjects?type=2&limit=" + MAX_ENTRIES;
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "ANON-TOOL/" + BuildConfig.VERSION_NAME + " (Android; Bangumi anime discovery)",
            "Accept", "application/json");

    record Entry(AnimeSchedule.Entry subject, String cover, double score, int rank, String summary) {
        String previewJson() {
            try {
                JSONObject rating = new JSONObject().put("rank", rank).put("score", Double.isFinite(score) ? score : 0);
                return new JSONObject().put("id", subject.id).put("name_cn", subject.title)
                        .put("name", subject.originalTitle.isEmpty() ? subject.title : subject.originalTitle)
                        .put("date", subject.airing).put("summary", summary).put("rating", rating)
                        .put("rank", rank).put("images", new JSONObject().put("large", cover)).toString();
            } catch (JSONException invalid) {
                return "";
            }
        }
    }
    record Page(List<Entry> entries, boolean hasMore) {}
    record Snapshot(Page page, String json) {}

    private AnimeDiscovery() {}

    static List<Entry> fetch() throws IOException {
        return parse(KazumiBangumiTransport.fetch(RANKING, HEADERS, null, 5_000).body).entries();
    }

    // Kazumi 2.4.4 exposes a public cached list for discovery. The official Next API serves the same use case.
    static Snapshot fetchPopular(int offset) throws IOException {
        IOException mirrorFailure;
        try {
            return requestPage(POPULAR, offset);
        } catch (IOException failure) {
            mirrorFailure = failure;
        }
        try {
            return requestPage(TRENDS, offset);
        } catch (IOException failure) {
            failure.addSuppressed(mirrorFailure);
            throw failure;
        }
    }

    static int nextOffset(int offset) { return offset + MAX_ENTRIES; }

    private static Snapshot requestPage(String endpoint, int offset) throws IOException {
        String body = KazumiBangumiTransport.fetch(endpoint + "&offset=" + offset, HEADERS, null, 5_000).body;
        Page page = parse(body);
        if (offset == 0 && page.entries().isEmpty()) throw new IOException("Bangumi 热门榜暂时没有数据");
        return new Snapshot(page, body);
    }

    static Page parse(String body) throws IOException {
        if (body == null || body.length() > MAX_JSON_LENGTH) {
            throw new IOException("动画排行响应过长");
        }
        List<Entry> result = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        try {
            Object root = new JSONTokener(body).nextValue();
            JSONArray rows;
            if (root instanceof JSONArray array) rows = array;
            else if (root instanceof JSONObject object) rows = object.getJSONArray("data");
            else throw new JSONException("缺少动画列表");
            for (int i = 0; i < Math.min(rows.length(), MAX_ENTRIES); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                JSONObject subjectData = row.optJSONObject("subject");
                if (subjectData != null) row = subjectData;
                if (row.optInt("type", 2) != 2 || row.optBoolean("nsfw")) continue;
                long id = row.optLong("id");
                String original = text(row, "name", 150);
                String chinese = text(row, "name_cn", 150);
                if (chinese.isEmpty()) chinese = text(row, "nameCN", 150);
                String title = chinese.isEmpty() ? original : chinese;
                if (id <= 0 || title.isEmpty() || !ids.add(id)) continue;
                String date = text(row, "date", 32);
                JSONObject airtime = row.optJSONObject("airtime");
                if (date.isEmpty() && airtime != null) date = text(airtime, "date", 32);
                AnimeSchedule.Entry subject = new AnimeSchedule.Entry(id, title,
                        title.equals(original) ? "" : original, date);
                JSONObject rating = row.optJSONObject("rating");
                double score = rating == null ? 0 : rating.optDouble("score", 0);
                int rank = rating == null ? 0 : rating.optInt("rank", 0);
                String cover = thumbnail(row);
                if (cover.isEmpty()) cover = subjectCover(id);
                result.add(new Entry(subject, cover,
                        Double.isFinite(score) && score > 0 && score <= 10 ? score : 0, Math.max(0, rank),
                        text(row, "summary", 2_000)));
            }
            return new Page(List.copyOf(result), rows.length() >= MAX_ENTRIES);
        } catch (JSONException malformed) {
            throw new IOException("动画排行响应格式有误", malformed);
        }
    }

    private static String thumbnail(JSONObject row) {
        JSONObject images = row.optJSONObject("images");
        if (images != null) {
            for (String size : new String[]{"large", "common", "medium", "small", "grid"}) {
                String cover = bangumiCover(text(images, size, 1_024));
                if (!cover.isEmpty()) return cover;
            }
        }
        return bangumiCover(text(row, "image", 1_024));
    }

    private static String bangumiCover(String value) {
        if (value.startsWith("//")) value = "https:" + value;
        if (value.startsWith("http://")) value = "https://" + value.substring(7);
        try {
            return AnimeCoverCache.isBangumiCover(URI.create(value)) ? value : "";
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    static String subjectCover(long id) {
        return id > 0 ? "https://api.bgm.tv/v0/subjects/" + id + "/image?type=medium" : "";
    }

    private static String text(JSONObject entry, String key, int limit) {
        Object value = entry.opt(key);
        if (!(value instanceof String)) return "";
        String text = ((String) value).trim();
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }
}
