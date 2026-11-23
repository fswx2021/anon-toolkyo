package com.shengjian.player;

import android.app.AlertDialog;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Looper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class MusicHistoryTest {
    @Test public void legacyCountsRemainUnknownAndNewCountsSurviveListEviction() {
        Application app = RuntimeEnvironment.getApplication();
        var saved = app.getSharedPreferences(MusicCollections.PREFS, 0);
        saved.edit().putString(MusicCollections.RECENT, "[\"legacy\"]").apply();
        assertTrue(MusicCollections.readRecentStats(saved.getString(MusicCollections.RECENT_STATS, "{}")).isEmpty());

        MusicCollections.recordPlayed(saved, "legacy", 1_000L);
        MusicCollections.recordPlayed(saved, "legacy", 2_000L);
        for (int i = 0; i < 100; i++) {
            MusicCollections.recordPlayed(saved, "track-" + i, 3_000L + i);
        }
        assertEquals(100, MusicCollections.readIds(saved.getString(MusicCollections.RECENT, "[]")).size());
        assertEquals(new MusicCollections.HistoryStats(2_000L, 2L, true), recentStats(saved).get("legacy"));
        assertEquals(new MusicCollections.HistoryStats(3_099L, 1L, false), recentStats(saved).get("track-99"));

        MusicCollections.recordPlayed(saved, "legacy", 4_000L);
        assertEquals("legacy", MusicCollections.readIds(saved.getString(MusicCollections.RECENT, "[]")).get(0));
        assertEquals(new MusicCollections.HistoryStats(4_000L, 3L, true), recentStats(saved).get("legacy"));
        MusicCollections.pruneRecent(saved, Set.of("legacy", "track-99"));
        assertEquals(Set.of("legacy", "track-99"), recentStats(saved).keySet());
        MusicCollections.removeRecent(saved, "legacy");
        assertEquals(Set.of("track-99"), recentStats(saved).keySet());
        MusicCollections.clearRecent(saved);
        assertTrue(recentStats(saved).isEmpty());
        assertEquals("[]", saved.getString(MusicCollections.RECENT, "[]"));
    }

    private static Map<String, MusicCollections.HistoryStats> recentStats(android.content.SharedPreferences saved) {
        return MusicCollections.readRecentStats(saved.getString(MusicCollections.RECENT_STATS, "{}"));
    }

    @SuppressWarnings("unchecked")
    @Test public void existingHistoryOpensNewestFirstAndCleanupKeepsTheQueue() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Shadows.shadowOf(app).declareComponentUnbindable(new ComponentName(app, PlaybackService.class));
        var saved = app.getSharedPreferences(MusicCollections.PREFS, 0);
        saved.edit()
                .putString(MusicCollections.RECENT, "[\"second\",\"first\"]")
                .putString(MusicCollections.QUEUE, "[\"first\",\"second\"]")
                .putString(MusicCollections.CURRENT, "second")
                .putLong(MusicCollections.POSITION, 12_000L)
                .apply();
        Track first = track("first");
        Track second = track("second");
        var request = new Intent(app, MainActivity.class);
        try (var controller = Robolectric.buildActivity(MainActivity.class, request).create()) {
            MainActivity activity = controller.get();
            field("tracks").set(activity, List.of(first, second));
            var byId = (Map<String, Track>) field("tracksById").get(activity);
            byId.put(first.id, first);
            byId.put(second.id, second);
            int recentFilter = field("RECENT_TRACKS").getInt(null);
            call(activity, "setMusicFilter", int.class, recentFilter);

            assertEquals(List.of(second, first), field("visible").get(activity));
            call(activity, "removeMusicHistoryItem", String.class, second.id);
            assertEquals(List.of(first), field("visible").get(activity));
            assertEquals("[\"first\"]", saved.getString(MusicCollections.RECENT, ""));

            call(activity, "confirmClearMusicHistory");
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertEquals(List.of(), field("visible").get(activity));
            assertEquals("[]", saved.getString(MusicCollections.RECENT, "[]"));
            assertEquals("[\"first\",\"second\"]", saved.getString(MusicCollections.QUEUE, ""));
            assertEquals("second", saved.getString(MusicCollections.CURRENT, ""));
            assertEquals(12_000L, saved.getLong(MusicCollections.POSITION, 0));
        }
    }

    private static Track track(String id) {
        return new Track(id, id, "Artist", "", "", "", "", "", "mp3", id + ".mp3", 20_000L, null);
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void call(MainActivity activity, String name) throws ReflectiveOperationException {
        Method method = MainActivity.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static void call(MainActivity activity, String name, Class<?> type, Object value)
            throws ReflectiveOperationException {
        Method method = MainActivity.class.getDeclaredMethod(name, type);
        method.setAccessible(true);
        method.invoke(activity, value);
    }
}
