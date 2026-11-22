package com.shengjian.player;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class FinalFeatureInteractionTest {
    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static TextView label(View view, String value) {
        if (view instanceof TextView text && value.contentEquals(text.getText())) return text;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView match = label(group.getChildAt(i), value);
                if (match != null) return match;
            }
        }
        return null;
    }

    @Test public void animationHistoryIsAvailableFromTheAnimationHome() {
        Application app = RuntimeEnvironment.getApplication();
        Intent start = new Intent(app, MainActivity.class);
        try (var controller = Robolectric.buildActivity(MainActivity.class, start).create()) {
            MainActivity host = controller.get();
            AnimeSection section = new AnimeSection(host);
            try {
                TextView history = label(section.view(), "历史");
                assertNotNull(history);
                assertEquals("动画历史记录", history.getContentDescription());
                assertTrue(history.performClick());
                Intent destination = shadowOf(host).getNextStartedActivity();
                assertNotNull(destination);
                assertEquals("com.shengjian.player.AnimeFeatureActivity", destination.getComponent().getClassName());
                assertEquals("history", destination.getStringExtra("feature"));
            } finally {
                section.destroy();
            }
        }
    }

    @Test public void openSourceNoticesOpenAndRestoreAnOfflineLicense() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Intent start = new Intent(app, MainActivity.class);
        Bundle state = new Bundle();
        try (var controller = Robolectric.buildActivity(MainActivity.class, start).create()) {
            MainActivity host = controller.get();
            Method settings = MainActivity.class.getDeclaredMethod("showAppSettings");
            settings.setAccessible(true);
            settings.invoke(host);
            clickSettingsRow(host, "开源声明");
            clickSettingsRow(host, "上游源码");
            Intent source = shadowOf(host).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, source.getAction());
            assertEquals(OpenSourceNotices.COMPONENTS.get(0).source(), source.getDataString());
            clickSettingsRow(host, "GNU GPL v3");
            View license = (View) field(MainActivity.class, "licensePage").get(host);
            assertNotNull(license);
            assertEquals("GNU GPL v3", license.getAccessibilityPaneTitle());
            controller.saveInstanceState(state);
        }
        try (var restored = Robolectric.buildActivity(MainActivity.class, start).create(state)) {
            MainActivity host = restored.get();
            View license = (View) field(MainActivity.class, "licensePage").get(host);
            assertNotNull(license);
            assertEquals("GNU GPL v3", license.getAccessibilityPaneTitle());
            host.onBackPressed();
            assertEquals(null, field(MainActivity.class, "licensePage").get(host));
            clickSettingsRow(host, "Flutter 及其依赖的许可证");
            Intent flutter = shadowOf(host).getNextStartedActivity();
            assertEquals("licenses", flutter.getStringExtra("feature"));
            host.onBackPressed();
            assertEquals(View.VISIBLE, ((View) field(MainActivity.class, "appSettingsPage").get(host)).getVisibility());
        }
    }

    private static void clickSettingsRow(MainActivity host, String title) {
        TextView entry = label(host.getWindow().getDecorView(), title);
        assertNotNull(title, entry);
        assertTrue(((View) entry.getParent().getParent()).performClick());
    }

    @Test public void aboutOffersAnAvailableUpdateAndOpensItsDownload() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity host = controller.get();
            String apk = "https://github.com/owner/anon-toolkyo/releases/download/v0.30.1/anon-toolkyo-0.30.1.apk";
            field(MainActivity.class, "availableAppUpdate").set(host,
                    new AppUpdates.Release(100, "0.30.1", URI.create(apk), "版本更新说明"));
            Method settings = MainActivity.class.getDeclaredMethod("showAppSettings");
            settings.setAccessible(true);
            settings.invoke(host);
            clickSettingsRow(host, "关于与数据");
            View about = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertTrue(label(about, "查看 0.30.1 更新").performClick());
            View update = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertNotNull(label(update, "版本更新说明"));
            assertTrue(label(update, "下载更新").performClick());
            Intent next = shadowOf(host).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, next.getAction());
            assertEquals(apk, next.getDataString());
        }
    }

    @Test public void searchResultDownloadsWithoutStartingPlayback() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).create();
        MainActivity host = controller.get();
        var playing = new AtomicReference<MusicOnlineEngine.Song>();
        var saving = new AtomicReference<MusicOnlineEngine.Song>();
        var song = new MusicOnlineEngine.Song(MusicOnlineEngine.BILIBILI, "BVTEST", "搜索结果", "歌手", "", "", 120000);
        try (var section = new MusicOnlineSection(host, playing::set, saving::set,
                new FrameLayout(host), (view, url) -> {})) {
            Class<?> rowClass = Class.forName(MusicOnlineSection.class.getName() + "$SongRow");
            var rowConstructor = rowClass.getDeclaredConstructor(MusicOnlineSection.class);
            rowConstructor.setAccessible(true);
            Object row = rowConstructor.newInstance(section);
            Class<?> matchClass = Class.forName(MusicOnlineSection.class.getName() + "$Match");
            var matchConstructor = matchClass.getDeclaredConstructor(MusicOnlineEngine.Song.class, String.class, int.class);
            matchConstructor.setAccessible(true);
            var bind = rowClass.getDeclaredMethod("bind", matchClass);
            bind.setAccessible(true);
            bind.invoke(row, matchConstructor.newInstance(song, "B 站", 100));

            View item = (View) field(rowClass, "item").get(row);
            TextView download = label(item, "下载");
            assertNotNull(download);
            assertEquals("下载「搜索结果」到本地", download.getContentDescription());
            assertTrue(download.performClick());
            assertSame(song, saving.get());
            assertEquals(null, playing.get());
            assertTrue(item.performClick());
            assertSame(song, playing.get());
        } finally {
            controller.destroy();
        }
    }

    @Test public void downloadedMusicAppearsInLocalFilesAndNoLongerNeedsDownload() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity host = controller.get();
            Track downloaded = new Track("saved", "离线曲目", "歌手", "B 站", "https://www.bilibili.com/video/BVTEST",
                    "file:///saved.m4a", "", "", "M4A", "", 120000, null);
            Track streaming = new Track("stream", "在线曲目", "歌手", "B 站", "https://www.bilibili.com/video/BVONLINE",
                    "anon-music://bilibili/BVONLINE", "", "", "", "", 120000, null);
            field(MainActivity.class, "tracks").set(host, List.of(downloaded, streaming));

            Method filter = MainActivity.class.getDeclaredMethod("setMusicFilter", int.class);
            filter.setAccessible(true);
            filter.invoke(host, field(MainActivity.class, "LOCAL_TRACKS").getInt(null));
            assertEquals(List.of(downloaded), field(MainActivity.class, "visible").get(host));

            Method showMetadata = MainActivity.class.getDeclaredMethod("showTrackMetadata", Track.class);
            showMetadata.setAccessible(true);
            showMetadata.invoke(host, downloaded);
            assertEquals(View.GONE, ((View) field(MainActivity.class, "downloadTrack").get(host)).getVisibility());
        }
    }

    @SuppressWarnings("unchecked")
    @Test public void playbackFailureShowsFullReasonAndOpensTrackOrigin() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("app_settings", 0).edit().putBoolean("guide_seen", true).apply();
        var controller = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class));
        MainActivity host = controller.create().get();
        try {
            String origin = "https://www.missevan.com/sound/player?id=12";
            Track entry = new Track("clip", "曲目", "作者", "猫耳FM", origin, "anon-music://maoerfm/12",
                    "", "", "", "", 4000, null);
            var playing = new PlaybackService.Track(entry.id, entry.url, entry.title, entry.artist, "", "猫耳FM", "");
            var service = new PlaybackService();
            field(PlaybackService.class, "queue").set(service, List.of(playing));
            field(PlaybackService.class, "index").setInt(service, 0);
            String reason = "此音频受猫耳FM 播放保护，请在原网站收听。";
            field(PlaybackService.class, "error").set(service, reason);
            field(MainActivity.class, "service").set(host, service);
            Map<String, Track> tracks = (Map<String, Track>) field(MainActivity.class, "tracksById").get(host);
            tracks.put(entry.id, entry);
            Method showPlayer = MainActivity.class.getDeclaredMethod("showPlayer", boolean.class);
            showPlayer.setAccessible(true);
            showPlayer.invoke(host, false);
            View download = (View) field(MainActivity.class, "downloadTrack").get(host);
            assertEquals(View.VISIBLE, download.getVisibility());
            assertEquals("下载曲目到本地", download.getContentDescription());
            View action = (View) field(MainActivity.class, "playbackErrorAction").get(host);
            assertEquals(View.VISIBLE, action.getVisibility());
            assertTrue(action.performClick());
            Dialog error = ShadowDialog.getLatestDialog();
            View sheet = error.getWindow().getDecorView();
            assertNotNull(label(sheet, reason));
            assertNotNull(label(sheet, "重试播放"));
            assertTrue(label(sheet, "在来源网站打开").performClick());
            Intent opened = shadowOf(host).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertEquals(origin, opened.getDataString());
        } finally {
            controller.destroy();
        }
    }

    @SuppressWarnings("unchecked")
    @Test public void currentMusicOnlyShowsInMusicAcrossAllFourPages() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("app_settings", 0).edit().putBoolean("guide_seen", true).apply();
        var controller = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class));
        MainActivity host = controller.create().get();
        try {
            Track entry = new Track("demo", "Test track", "Artist", "local", "", "file:///example.ogg",
                    "", "", "ogg", "example.ogg", 4000, null);
            PlaybackService.Track playing = new PlaybackService.Track(entry.id, entry.url, entry.title,
                    entry.artist, "", "local", "");
            PlaybackService service = new PlaybackService();
            field(PlaybackService.class, "queue").set(service, List.of(playing));
            field(PlaybackService.class, "index").setInt(service, 0);
            field(MainActivity.class, "service").set(host, service);
            Map<String, Track> library = (Map<String, Track>) field(MainActivity.class, "tracksById").get(host);
            library.put(entry.id, entry);

            View miniPlayer = (View) field(MainActivity.class, "miniCard").get(host);
            View[] pages = (View[]) field(MainActivity.class, "sectionPages").get(host);
            Method selectSection = MainActivity.class.getDeclaredMethod("selectSection", int.class);
            selectSection.setAccessible(true);
            for (int tab : new int[] {0, 1, 2, 3, 0}) {
                selectSection.invoke(host, tab);
                assertEquals(tab == 0 ? View.VISIBLE : View.GONE, miniPlayer.getVisibility());
                for (int i = 0; i < pages.length; i++) {
                    assertEquals(i == tab ? View.VISIBLE : View.GONE, pages[i].getVisibility());
                }
                assertSame(playing, service.currentTrack());
            }
        } finally {
            controller.destroy();
        }
    }
}
