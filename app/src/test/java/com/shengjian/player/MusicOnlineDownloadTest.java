package com.shengjian.player;

import java.io.IOException;
import java.net.URI;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class MusicOnlineDownloadTest {
    @Test public void navidromeDownloadUsesFileEndpointWithoutPublishingTheAccountUrl() throws Exception {
        var server = new MusicOnlineEngine.Config("https://library.example.invalid/music", "listener", "password");
        var song = new MusicOnlineEngine.Song(MusicOnlineEngine.NAVIDROME, "example#1", "Track", "Artist",
                "", "", 120_000L);
        LinkResolver.Result download = MusicOnlineEngine.resolveDownload(song, server);
        URI address = URI.create(download.url);
        assertEquals("/music/rest/download.view", address.getPath());
        assertTrue(address.getRawQuery().contains("&id=example%231"));
        assertFalse(address.getRawQuery().contains("password"));
        assertEquals("", download.sourceUrl);
        assertEquals("", download.referer);
        assertEquals("/music/rest/stream.view", URI.create(MusicOnlineEngine.resolve(song, server).url).getPath());
    }

    @Test public void audiusRequiresTheMatchingPublicTrackToHaveDownloadsEnabled() throws Exception {
        var song = new MusicOnlineEngine.Song(MusicOnlineEngine.AUDIUS, "Track01", "Track", "Artist",
                "", "", 120_000L);
        var metadata = new JSONObject().put("id", song.id).put("is_downloadable", true);
        LinkResolver.Result download = MusicOnlineEngine.audiusDownload(song, metadata);
        assertEquals("/v1/tracks/Track01/download", URI.create(download.url).getPath());
        assertEquals("mp3", download.extension);

        metadata.put("is_downloadable", false);
        assertThrows(IOException.class, () -> MusicOnlineEngine.audiusDownload(song, metadata));
        metadata.put("is_downloadable", true).put("is_stream_gated", true);
        assertThrows(IOException.class, () -> MusicOnlineEngine.audiusDownload(song, metadata));
        metadata.put("id", "Track02").put("is_stream_gated", false);
        assertThrows(IOException.class, () -> MusicOnlineEngine.audiusDownload(song, metadata));
    }
}
