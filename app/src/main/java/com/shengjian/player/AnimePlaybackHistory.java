package com.shengjian.player;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

final class AnimePlaybackHistory {
    static final String PREFS = "anime_playback";

    private AnimePlaybackHistory() {}

    static long position(Context context, AnimeEngine.Episode episode) {
        return Math.max(0, prefs(context).getLong("episode_" + episodeKey(episode), 0));
    }

    static int resumeIndex(Context context, List<AnimeEngine.Episode> episodes) {
        int recent = mostRecentIndex(context, episodes);
        if (recent >= 0) return recent;
        for (int i = 0; i < episodes.size(); i++) if (position(context, episodes.get(i)) > 0) return i;
        return 0;
    }

    static int mostRecentIndex(Context context, List<AnimeEngine.Episode> episodes) {
        if (episodes.isEmpty()) return -1;
        String recent = prefs(context).getString("show_" + showKey(episodes.get(0)), "");
        if (!recent.isEmpty()) for (int i = 0; i < episodes.size(); i++) {
            if (recent.equals(episodeKey(episodes.get(i)))) return i;
        }
        return -1;
    }

    static void save(Context context, AnimeEngine.Episode episode, long position) {
        String key = episodeKey(episode);
        prefs(context).edit().putString("show_" + showKey(episode), key)
                .putLong("episode_" + key, Math.max(0, position)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String showKey(AnimeEngine.Episode episode) {
        return digest(episode.show.source.id + '\n' + AnimeLibrary.workAddress(episode.show.url, episode.show.sourceValue));
    }

    private static String episodeKey(AnimeEngine.Episode episode) {
        return digest(episode.show.source.id + '\n' + AnimeLibrary.workAddress(episode.show.url, episode.show.sourceValue)
                + '\n' + episode.url);
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP
                    | android.util.Base64.NO_PADDING);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
