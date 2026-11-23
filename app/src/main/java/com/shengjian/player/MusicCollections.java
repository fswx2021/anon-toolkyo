package com.shengjian.player;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MusicCollections {
    static final String PREFS = "music_collections";
    static final String STATE = "state";
    static final String RECENT = "recent";
    static final String RECENT_STATS = "recent_stats";
    static final String QUEUE = "queue";
    static final String SHUFFLE_ORDER = "shuffle_order";
    static final String CURRENT = "current";
    static final String POSITION = "position";
    static final String SPEED = "speed";
    private static final int MAX_RECENT = 100;

    record HistoryStats(long lastPlayedAt, long playCount, boolean previousCountUnknown) {}

    static final class Playlist {
        final String id;
        String name;
        private final List<String> trackIds;

        Playlist(String id, String name, List<String> trackIds) {
            this.id = id;
            this.name = name;
            this.trackIds = trackIds;
        }

        List<String> trackIds() { return Collections.unmodifiableList(trackIds); }
    }

    private final Set<String> favorites = new LinkedHashSet<>();
    private final List<Playlist> playlists = new ArrayList<>();

    MusicCollections(String saved) {
        if (saved == null || saved.isEmpty()) return;
        try {
            JSONObject data = new JSONObject(saved);
            favorites.addAll(readIds(data.optJSONArray("favorites")));
            JSONArray entries = data.optJSONArray("playlists");
            if (entries == null) return;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                String id = entry.optString("id");
                String name = entry.optString("name").trim();
                if (!id.isEmpty() && !name.isEmpty() && playlist(id) == null) {
                    playlists.add(new Playlist(id, name, readIds(entry.optJSONArray("tracks"))));
                }
            }
        } catch (JSONException malformed) {
            favorites.clear();
            playlists.clear();
        }
    }

    boolean isFavorite(String track) { return favorites.contains(track); }

    boolean toggleFavorite(String track) {
        if (favorites.remove(track)) return false;
        favorites.add(track);
        return true;
    }

    List<Playlist> playlists() { return Collections.unmodifiableList(playlists); }

    Playlist playlist(String id) {
        for (Playlist entry : playlists) if (entry.id.equals(id)) return entry;
        return null;
    }

    Playlist create(String name) {
        Playlist entry = new Playlist(UUID.randomUUID().toString(), name, new ArrayList<>());
        playlists.add(entry);
        return entry;
    }

    void rename(Playlist playlist, String name) { playlist.name = name; }

    boolean add(Playlist playlist, String track) {
        if (playlist.trackIds.contains(track)) return false;
        return playlist.trackIds.add(track);
    }

    void remove(Playlist playlist, String track) { playlist.trackIds.remove(track); }

    void delete(Playlist playlist) { playlists.remove(playlist); }

    boolean prune(Set<String> trackIds) {
        boolean changed = favorites.retainAll(trackIds);
        for (Playlist playlist : playlists) changed |= playlist.trackIds.retainAll(trackIds);
        return changed;
    }

    String serialize() {
        JSONArray lists = new JSONArray();
        try {
            for (Playlist playlist : playlists) lists.put(new JSONObject().put("id", playlist.id)
                    .put("name", playlist.name).put("tracks", new JSONArray(playlist.trackIds)));
            return new JSONObject().put("favorites", new JSONArray(favorites)).put("playlists", lists).toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Invalid music collection", impossible);
        }
    }

    static List<String> readIds(String json) {
        try { return readIds(new JSONArray(json)); }
        catch (JSONException invalid) { return new ArrayList<>(); }
    }

    private static List<String> readIds(JSONArray array) {
        Set<String> unique = new LinkedHashSet<>();
        for (int i = 0; array != null && i < array.length(); i++) {
            String value = array.optString(i);
            if (!value.isEmpty()) unique.add(value);
        }
        return new ArrayList<>(unique);
    }

    static String writeIds(Collection<String> ids) { return new JSONArray(ids).toString(); }

    static void recordRecent(List<String> items, String id) {
        items.remove(id);
        items.add(0, id);
        if (items.size() > MAX_RECENT) items.subList(MAX_RECENT, items.size()).clear();
    }

    static Map<String, HistoryStats> readRecentStats(String json) {
        Map<String, HistoryStats> result = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return result;
        try {
            JSONObject saved = new JSONObject(json);
            for (var keys = saved.keys(); keys.hasNext(); ) {
                String id = keys.next();
                JSONObject row = saved.optJSONObject(id);
                if (row == null || id.isEmpty()) continue;
                long playedAt = row.optLong("last_played");
                long count = row.optLong("count");
                if (playedAt > 0 && count > 0) {
                    result.put(id, new HistoryStats(playedAt, count, row.optBoolean("older_count_unknown")));
                }
            }
        } catch (JSONException invalid) {
            return result;
        }
        return result;
    }

    static synchronized void recordPlayed(SharedPreferences preferences, String id, long playedAt) {
        if (id.isEmpty() || playedAt <= 0) return;
        List<String> ids = readIds(preferences.getString(RECENT, "[]"));
        Map<String, HistoryStats> stats = readRecentStats(preferences.getString(RECENT_STATS, "{}"));
        HistoryStats prior = stats.get(id);
        long count = prior == null ? 1 : Math.min(prior.playCount(), Long.MAX_VALUE - 1) + 1;
        boolean olderCountUnknown = prior == null ? ids.contains(id) : prior.previousCountUnknown();
        stats.put(id, new HistoryStats(playedAt, count, olderCountUnknown));
        recordRecent(ids, id);
        saveRecent(preferences, ids, stats);
    }

    static synchronized void clearRecent(SharedPreferences preferences) {
        preferences.edit().remove(RECENT).remove(RECENT_STATS).apply();
    }

    static synchronized void removeRecent(SharedPreferences preferences, String id) {
        List<String> ids = readIds(preferences.getString(RECENT, "[]"));
        Map<String, HistoryStats> stats = readRecentStats(preferences.getString(RECENT_STATS, "{}"));
        boolean removed = ids.remove(id);
        if (stats.remove(id) != null || removed) saveRecent(preferences, ids, stats);
    }

    static synchronized void pruneRecent(SharedPreferences preferences, Set<String> available) {
        List<String> ids = readIds(preferences.getString(RECENT, "[]"));
        Map<String, HistoryStats> stats = readRecentStats(preferences.getString(RECENT_STATS, "{}"));
        boolean changed = ids.retainAll(available);
        if (stats.keySet().retainAll(available) || changed) saveRecent(preferences, ids, stats);
    }

    private static void saveRecent(SharedPreferences preferences, List<String> ids, Map<String, HistoryStats> stats) {
        JSONObject saved = new JSONObject();
        try {
            for (Map.Entry<String, HistoryStats> item : stats.entrySet()) {
                HistoryStats row = item.getValue();
                saved.put(item.getKey(), new JSONObject().put("last_played", row.lastPlayedAt())
                        .put("count", row.playCount()).put("older_count_unknown", row.previousCountUnknown()));
            }
        } catch (JSONException impossible) {
            throw new IllegalStateException("Invalid music history", impossible);
        }
        preferences.edit().putString(RECENT, writeIds(ids)).putString(RECENT_STATS, saved.toString()).apply();
    }
}
