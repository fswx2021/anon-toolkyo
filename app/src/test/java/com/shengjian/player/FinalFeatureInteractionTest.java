package com.shengjian.player;

import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
            View project = (View) field(MainActivity.class, "openSourcePage").get(host);
            assertNull(label(project, "Kazumi 2.4.5"));
            clickSettingsRow(host, "项目网站");
            assertEquals(OpenSourceNotices.PROJECT_SITE, shadowOf(host).getNextStartedActivity().getDataString());
            clickSettingsRow(host, "查看项目许可证");
            View projectLicense = (View) field(MainActivity.class, "licensePage").get(host);
            assertEquals("项目许可证 · GPL-3.0-only", projectLicense.getAccessibilityPaneTitle());
            host.onBackPressed();
            clickSettingsRow(host, "致谢");
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
            assertEquals(View.VISIBLE, ((View) field(MainActivity.class, "openSourcePage").get(host)).getVisibility());
            host.onBackPressed();
            assertEquals(View.VISIBLE, ((View) field(MainActivity.class, "appSettingsPage").get(host)).getVisibility());
        }
    }

    @Test public void initialSourceSetupCanBeSkippedAndReopenedFromSettings() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).declareComponentUnbindable(new ComponentName(app, PlaybackService.class));
        try (var controller = Robolectric.buildActivity(MainActivity.class, new Intent(app, MainActivity.class)).create()) {
            MainActivity host = controller.get();
            Method shouldOffer = MainActivity.class.getDeclaredMethod("shouldOfferSourceSetup");
            shouldOffer.setAccessible(true);
            assertEquals(true, shouldOffer.invoke(host));
            InitialSourceSetup sourceSetup = (InitialSourceSetup) field(MainActivity.class, "sourceSetup").get(host);
            sourceSetup.music.setEnabled("bilibili", false);
            assertEquals(false, shouldOffer.invoke(host));

            Method settings = MainActivity.class.getDeclaredMethod("showAppSettings");
            settings.setAccessible(true);
            settings.invoke(host);
            clickSettingsRow(host, "一键聚合四个模块");
            Dialog setup = ShadowDialog.getLatestDialog();
            assertNotNull(setup);
            assertNotNull(label(setup.getWindow().getDecorView(), "一键聚合并启用"));
            for (String module : List.of("音乐", "漫画", "小说", "动画")) {
                assertNotNull(label(setup.getWindow().getDecorView(), module));
            }
            assertTrue(label(setup.getWindow().getDecorView(), "稍后").performClick());
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(host.getSharedPreferences("app_settings", 0).getBoolean("source_setup_seen", false));
            clickSettingsRow(host, "一键聚合四个模块");
            Dialog reopened = ShadowDialog.getLatestDialog();
            assertTrue(reopened != setup && reopened.isShowing());
            assertNotNull(label(reopened.getWindow().getDecorView(), "一键聚合并启用"));
        }
    }

    private static void clickSettingsRow(MainActivity host, String title) {
        TextView entry = label(host.getWindow().getDecorView(), title);
        assertNotNull(title, entry);
        assertTrue(((View) entry.getParent().getParent()).performClick());
    }

    private static AppUpdates.Release availableUpdate() {
        URI apk = URI.create("https://github.com/owner/anon-toolkyo/releases/download/v0.30.1/anon-toolkyo-0.30.1.apk");
        return new AppUpdates.Release(BuildConfig.VERSION_CODE + 1, "0.30.1", apk,
                "版本更新说明", "a".repeat(64), List.of(apk));
    }

    @Test public void aboutOffersInAppInstallationAndOpensTheReleaseWebsite() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity host = controller.get();
            field(MainActivity.class, "availableAppUpdate").set(host, availableUpdate());
            Method settings = MainActivity.class.getDeclaredMethod("showAppSettings");
            settings.setAccessible(true);
            settings.invoke(host);
            clickSettingsRow(host, "关于与数据");
            View about = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertTrue(label(about, "查看 0.30.1 更新").performClick());
            View update = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertNotNull(label(update, "版本更新说明"));
            assertNotNull(label(update, "应用内下载并安装"));
            assertTrue(label(update, "去网站下载").performClick());
            Intent next = shadowOf(host).getNextStartedActivity();
            assertEquals(Intent.ACTION_VIEW, next.getAction());
            assertEquals("https://github.com/owner/anon-toolkyo/releases/tag/v0.30.1", next.getDataString());
        }
    }

    @Test public void startupUpdateWaitsForWindowFocusAndDoesNotRepeat() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).declareComponentUnbindable(new ComponentName(app, PlaybackService.class));
        try (var controller = Robolectric.buildActivity(MainActivity.class)
                .create().start().resume().postResume().visible()) {
            MainActivity host = controller.get();
            host.getSharedPreferences("app_settings", Application.MODE_PRIVATE)
                    .edit().putInt("startup_update_check", 0).commit();
            Method result = MainActivity.class.getDeclaredMethod("finishAppUpdateCheck",
                    AppUpdates.Release.class, java.io.IOException.class);
            result.setAccessible(true);
            Field sheet = field(MainActivity.class, "updateSheet");
            controller.windowFocusChanged(false);
            result.invoke(host, availableUpdate(), null);
            assertNull(sheet.get(host));

            controller.windowFocusChanged(true);
            assertNotNull(sheet.get(host));
            View update = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertNotNull(label(update, "版本更新说明"));
            assertTrue(label(update, "稍后").performClick());
            shadowOf(Looper.getMainLooper()).idle();

            controller.windowFocusChanged(true);
            result.invoke(host, availableUpdate(), null);
            assertNull(sheet.get(host));
        }
    }

    @Test public void searchResultDownloadsWithoutStartingPlayback() throws Exception {
        var controller = Robolectric.buildActivity(MainActivity.class).create();
        MainActivity host = controller.get();
        var playing = new AtomicReference<MusicOnlineEngine.Song>();
        var saving = new AtomicReference<MusicOnlineEngine.Song>();
        var song = new MusicOnlineEngine.Song(MusicOnlineEngine.BILIBILI, "BVTEST", "搜索结果", "歌手", "", "", 120000);
        try (var section = new MusicOnlineSection(host, playing::set, saving::set,
                new FrameLayout(host), (view, url) -> {}, new MusicSourceCatalog.Store(
                        new java.io.File(host.getFilesDir(), "music-sources.json")))) {
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

    @Test public void musicHistoryUpdatesEvenWhenItsTrackOrderHasNotChanged() throws Exception {
        try (var controller = Robolectric.buildActivity(MainActivity.class).create()) {
            MainActivity host = controller.get();
            Track track = new Track("listened", "已播放的歌", "", "", "", "file:///listened.mp3",
                    "", "", "MP3", "", 120000, null);
            field(MainActivity.class, "tracks").set(host, List.of(track));
            Method index = MainActivity.class.getDeclaredMethod("indexTracks");
            index.setAccessible(true);
            index.invoke(host);
            var preferences = host.getSharedPreferences(MusicCollections.PREFS, Application.MODE_PRIVATE);
            long first = 1_700_000_000_000L;
            long second = first + 60_000;
            MusicCollections.recordPlayed(preferences, track.id, first);
            Method filter = MainActivity.class.getDeclaredMethod("setMusicFilter", int.class);
            filter.setAccessible(true);
            filter.invoke(host, field(MainActivity.class, "RECENT_TRACKS").getInt(null));
            BaseAdapter rows = (BaseAdapter) field(MainActivity.class, "songs").get(host);
            ViewGroup list = (ViewGroup) field(MainActivity.class, "list").get(host);
            View row = rows.getView(0, null, list);
            assertNotNull(label(row, HistoryDisplay.summary(host, "播放", 1, first, false)));

            MusicCollections.recordPlayed(preferences, track.id, second);
            Method refresh = MainActivity.class.getDeclaredMethod("refreshMusicHistory");
            refresh.setAccessible(true);
            refresh.invoke(host);
            assertNotNull(label(rows.getView(0, row, list), HistoryDisplay.summary(host, "播放", 2, second, false)));
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
