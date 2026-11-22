package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class AnimeSchedule {
    private AnimeSchedule() {}

    static final class Entry {
        final long id;
        final String title, originalTitle, airing;

        Entry(long id, String title, String originalTitle, String airing) {
            this.id = id;
            this.title = title;
            this.originalTitle = originalTitle;
            this.airing = airing;
        }
    }

    static List<List<Entry>> parse(String body) throws IOException {
        List<List<Entry>> week = new ArrayList<>();
        for (int i = 0; i < 7; i++) week.add(new ArrayList<>());
        Set<Long> seen = new HashSet<>();
        try {
            JSONArray days = new JSONArray(body);
            int items = 0;
            for (int index = 0; index < days.length() && index < 14; index++) {
                JSONObject day = days.optJSONObject(index);
                if (day == null) continue;
                JSONObject weekday = day.optJSONObject("weekday");
                JSONArray entries = day.optJSONArray("items");
                if (weekday == null || entries == null) continue;
                int weekdayIndex = weekday.optInt("id", 0) - 1;
                if (weekdayIndex < 0 || weekdayIndex >= 7) continue;
                for (int offset = 0; offset < entries.length() && items < 700; offset++, items++) {
                    JSONObject entry = entries.optJSONObject(offset);
                    if (entry == null || entry.optInt("type", 2) != 2) continue;
                    long id = entry.optLong("id", 0L);
                    if (id <= 0 || !seen.add(id)) continue;
                    String original = title(entry, "name");
                    String chinese = title(entry, "name_cn");
                    String name = chinese.isEmpty() ? original : chinese;
                    if (!name.isEmpty()) week.get(weekdayIndex).add(new Entry(id, name,
                            name.equals(original) ? "" : original, title(entry, "air_date")));
                }
            }
        } catch (JSONException malformed) {
            throw new IOException("放送日历响应格式有误", malformed);
        }
        for (int i = 0; i < week.size(); i++) week.set(i, Collections.unmodifiableList(week.get(i)));
        return Collections.unmodifiableList(week);
    }

    private static String title(JSONObject entry, String key) {
        String value = entry.optString(key, "").trim();
        return value.length() > 180 ? value.substring(0, 180) : value;
    }
}
