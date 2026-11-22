package com.shengjian.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class AnimeLibrary {
    static final String FAVORITES = "favorites";
    static final String HISTORY = "history";
    private static final int MAX_SAVED = 120;

    enum WatchStatus {
        PLANNED("planned", "想看"),
        WATCHING("watching", "在看"),
        COMPLETED("completed", "看过"),
        PAUSED("paused", "搁置"),
        DROPPED("dropped", "弃番");

        final String key, label;

        WatchStatus(String key, String label) {
            this.key = key;
            this.label = label;
        }

        static WatchStatus parse(String key) {
            for (WatchStatus status : values()) if (status.key.equals(key)) return status;
            return null;
        }
    }

    static final class Item {
        final String title, url, cover, sourceId, sourceName, sourceValue, episode;
        final long bangumiId;
        private final WatchStatus status;

        Item(AnimeEngine.Show show, String episode) {
            this(show.title, show.url, show.cover, show.source.id, show.source.name, show.sourceValue, episode,
                    show.bangumiId, null);
        }

        private Item(String title, String url, String cover, String sourceId, String sourceName, String sourceValue,
                     String episode, long bangumiId, WatchStatus status) {
            this.title = title;
            this.url = url;
            this.cover = cover;
            this.sourceId = sourceId;
            this.sourceName = sourceName;
            this.sourceValue = sourceValue;
            this.episode = episode;
            this.bangumiId = bangumiId;
            this.status = status;
        }

        Item(JSONObject data) {
            this(data.optString("title"), data.optString("url"), data.optString("cover"), data.optString("source"),
                    data.optString("site"), data.optString("value"), data.optString("episode"),
                    Math.max(0, data.optLong("bangumi")), WatchStatus.parse(data.optString("status")));
        }

        JSONObject json() throws JSONException {
            JSONObject data = new JSONObject().put("title", title).put("url", url).put("cover", cover)
                    .put("source", sourceId).put("site", sourceName).put("value", sourceValue).put("episode", episode);
            if (bangumiId > 0) data.put("bangumi", bangumiId);
            if (status != null) data.put("status", status.key);
            return data;
        }

        WatchStatus trackingStatus() {
            return status == null ? WatchStatus.PLANNED : status;
        }

        Item withStatus(WatchStatus value, long knownBangumiId) {
            return new Item(title, url, cover, sourceId, sourceName, sourceValue, "", knownBangumiId, value);
        }

        boolean same(Item other) {
            return sourceId.equals(other.sourceId) && workAddress(url, sourceValue).equals(workAddress(other.url, other.sourceValue));
        }
    }

    private final SharedPreferences preferences;

    AnimeLibrary(Context context) {
        preferences = context.getSharedPreferences("anime_library", Context.MODE_PRIVATE);
    }

    List<Item> list(String group) {
        List<Item> result = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(preferences.getString(group, "[]"));
            for (int i = 0; i < Math.min(values.length(), MAX_SAVED); i++) {
                JSONObject row = values.optJSONObject(i);
                if (row == null) continue;
                Item item = new Item(row);
                if (!item.sourceId.isEmpty() && !workAddress(item.url, item.sourceValue).isEmpty() && !item.title.isEmpty()) {
                    result.add(item);
                }
            }
        } catch (JSONException | ClassCastException ignored) { }
        return result;
    }

    WatchStatus watchStatus(Item item) {
        return list(FAVORITES).stream().filter(item::same).findFirst().map(Item::trackingStatus).orElse(null);
    }

    void setWatchStatus(Item item, WatchStatus status) {
        long knownBangumiId = item.bangumiId;
        if (status != null && knownBangumiId == 0) {
            knownBangumiId = list(FAVORITES).stream().filter(item::same).mapToLong(row -> row.bangumiId).findFirst().orElse(0);
        }
        update(FAVORITES, item.withStatus(status, knownBangumiId), status != null);
    }

    void record(AnimeEngine.Episode episode) { update(HISTORY, new Item(episode.show, episode.title), true); }

    void remove(String group, Item item) { update(group, item, false); }

    void clearHistory() { preferences.edit().remove(HISTORY).apply(); }

    static String workAddress(String url, String sourceValue) { return url.isEmpty() ? sourceValue : url; }

    private void update(String group, Item item, boolean add) {
        JSONArray json = new JSONArray();
        try {
            if (add) json.put(item.json());
            for (Item row : list(group)) {
                if (!row.same(item) && json.length() < MAX_SAVED) json.put(row.json());
            }
            preferences.edit().putString(group, json.toString()).apply();
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("动画记录格式无效", invalid);
        }
    }
}
