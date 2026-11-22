package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only access to the uosc_danmaku community endpoint. */
final class AnimeDanmakuCommunity {
    private static final int MAX_TITLES = 100;
    private static final int MAX_EPISODES = 1000;
    private static final int MAX_MANUAL_TITLES = 3;
    private static final int MAX_MANUAL_EPISODES = 100;
    private static final Cache<String, Titles> TITLES = new Cache<>(12, 30);
    private static final Cache<Long, JSONObject> DETAILS = new Cache<>(6, 30);
    private static final Cache<Long, List<AnimeDanmaku.Comment>> COMMENTS = new Cache<>(2, 10);

    private AnimeDanmakuCommunity() {}

    record Work(long id, String title) {}
    record Titles(List<Work> works, boolean truncated) {}

    static AnimeDanmaku.Candidate match(String title, int episode) throws IOException {
        if (episode <= 0 || episode > 99_999 || title == null || title.length() > 200
                || title.codePointCount(0, title.length()) < 2) return null;
        Work work = exactWork(titles(title.trim()), title);
        return work == null ? null : AnimeDanmaku.parseMappedEpisode(detail(work), work.title, episode);
    }

    static List<AnimeDanmaku.Candidate> search(String title, int episode) throws IOException {
        String keyword = title == null ? "" : title.trim();
        if (keyword.codePointCount(0, keyword.length()) < 2 || keyword.length() > 200) {
            throw new IOException("请输入 2 到 200 个字符的作品名称");
        }
        if (episode < 0 || episode > 99_999) throw new IOException("集数超出范围");
        Titles results = titles(keyword);
        List<Work> sorted = new ArrayList<>(results.works);
        String normalized = AnimeDanmaku.comparableTitle(keyword);
        sorted.sort((a, b) -> Boolean.compare(AnimeDanmaku.comparableTitle(b.title).equals(normalized),
                AnimeDanmaku.comparableTitle(a.title).equals(normalized)));
        List<AnimeDanmaku.Candidate> matches = new ArrayList<>();
        IOException failure = null;
        for (int i = 0; i < Math.min(sorted.size(), MAX_MANUAL_TITLES); i++) {
            Work work = sorted.get(i);
            try {
                matches.addAll(episodeChoices(detail(work), work, episode));
            } catch (IOException error) {
                if (error instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                    throw error;
                }
                if (failure == null) failure = error;
                if (error.getMessage() != null && error.getMessage().contains("请求过多")) break;
            }
            if (matches.size() >= MAX_MANUAL_EPISODES) break;
        }
        if (matches.isEmpty() && failure != null) throw failure;
        return List.copyOf(matches.subList(0, Math.min(MAX_MANUAL_EPISODES, matches.size())));
    }

    static List<AnimeDanmaku.Comment> comments(long episodeId) throws IOException {
        if (episodeId <= 0) throw new IOException("弹幕剧集编号无效");
        List<AnimeDanmaku.Comment> cached = COMMENTS.get(episodeId);
        if (cached != null) return cached;
        List<AnimeDanmaku.Comment> result = AnimeDanmaku.parseComments(AnimeDanmaku.fetchCommunity(
                "/api/v2/comment/" + episodeId, "withRelated=true&chConvert=0", AnimeDanmaku.MAX_COMMENT_BYTES));
        COMMENTS.put(episodeId, result);
        return result;
    }

    private static Titles titles(String keyword) throws IOException {
        String key = keyword.toLowerCase(Locale.ROOT);
        Titles cached = TITLES.get(key);
        if (cached != null) return cached;
        String encoded = URLEncoder.encode(keyword, "UTF-8");
        Titles result = parseTitles(AnimeDanmaku.response(AnimeDanmaku.fetchCommunity(
                "/api/v2/search/anime", "keyword=" + encoded, AnimeDanmaku.MAX_SEARCH_BYTES)));
        TITLES.put(key, result);
        return result;
    }

    private static JSONObject detail(Work work) throws IOException {
        JSONObject result = DETAILS.get(work.id);
        if (result == null) {
            result = AnimeDanmaku.response(AnimeDanmaku.fetchCommunity(
                    "/api/v2/bangumi/" + work.id, "", AnimeDanmaku.MAX_SEARCH_BYTES));
            JSONObject bangumi = result.optJSONObject("bangumi");
            if (bangumi == null || bangumi.optLong("animeId", work.id) != work.id) {
                throw new IOException("社区弹幕服务返回了不同作品");
            }
            DETAILS.put(work.id, result);
        }
        return result;
    }

    static Titles parseTitles(JSONObject response) throws IOException {
        JSONArray entries = response.optJSONArray("animes");
        if (entries == null) {
            if (response.has("animes") && response.isNull("animes")) return new Titles(List.of(), false);
            throw new IOException("社区弹幕服务未返回可读的作品列表");
        }
        Map<Long, Work> works = new LinkedHashMap<>();
        boolean truncated = response.optBoolean("hasMore") || entries.length() > MAX_TITLES;
        for (int i = 0; i < Math.min(entries.length(), MAX_TITLES); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            long id = entry.optLong("animeId", 0);
            String title = AnimeDanmaku.plain(AnimeDanmaku.string(entry, "animeTitle"), 200);
            if (id <= 0 || title.isEmpty()) continue;
            Work previous = works.putIfAbsent(id, new Work(id, title));
            if (previous != null && !previous.title.equals(title)) truncated = true;
        }
        return new Titles(List.copyOf(works.values()), truncated);
    }

    static Work exactWork(Titles results, String title) {
        String expected = AnimeDanmaku.comparableTitle(title);
        if (expected.isEmpty() || results.truncated) return null;
        Work match = null;
        for (Work work : results.works) {
            if (!expected.equals(AnimeDanmaku.comparableTitle(work.title))) continue;
            if (match != null) return null;
            match = work;
        }
        return match;
    }

    static List<AnimeDanmaku.Candidate> episodeChoices(JSONObject response, Work work, int episode)
            throws IOException {
        JSONObject title = response.optJSONObject("bangumi");
        JSONArray episodes = title == null ? null : title.optJSONArray("episodes");
        if (episodes == null) {
            if (title != null && title.has("episodes") && title.isNull("episodes")) return List.of();
            throw new IOException("社区弹幕服务未返回可读的剧集列表");
        }
        Map<Long, AnimeDanmaku.Candidate> matches = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(episodes.length(), MAX_EPISODES); i++) {
            JSONObject row = episodes.optJSONObject(i);
            if (row == null) continue;
            long id = row.optLong("episodeId", 0);
            String label = AnimeDanmaku.plain(AnimeDanmaku.string(row, "episodeTitle"), 200);
            if (label.isEmpty()) label = AnimeDanmaku.plain(row.optString("episodeNumber", ""), 32);
            if (id <= 0 || label.isEmpty()) continue;
            String numberField = row.optString("episodeNumber", "").trim();
            int number = AnimeDanmaku.episodeNumber(numberField.isEmpty() ? label : numberField);
            if (episode > 0 && number != episode) continue;
            matches.putIfAbsent(id, new AnimeDanmaku.Candidate(id, work.title, label, 0));
        }
        return List.copyOf(matches.values());
    }

    private static final class Cache<K, V> {
        private record Entry<V>(V value, long expires) {}

        private final Map<K, Entry<V>> items = new LinkedHashMap<>(16, 0.75f, true);
        private final int limit;
        private final long lifetime;

        Cache(int limit, int minutes) {
            this.limit = limit;
            lifetime = TimeUnit.MINUTES.toNanos(minutes);
        }

        synchronized V get(K key) {
            Entry<V> entry = items.get(key);
            if (entry == null) return null;
            if (entry.expires - System.nanoTime() > 0) return entry.value;
            items.remove(key);
            return null;
        }

        synchronized void put(K key, V value) {
            items.put(key, new Entry<>(value, System.nanoTime() + lifetime));
            if (items.size() > limit) items.remove(items.keySet().iterator().next());
        }
    }
}
