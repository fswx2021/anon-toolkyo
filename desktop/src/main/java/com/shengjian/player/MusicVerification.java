package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

final class MusicVerification {
    private MusicVerification() {}

    static void verify() throws Exception {
        MusicCollections before = new MusicCollections("");
        require(before.toggleFavorite("track-a"), "favorite added");
        require(before.toggleFavorite("track-b"), "second favorite added");
        MusicCollections.Playlist ordered = before.create("通勤");
        before.add(ordered, "track-b");
        before.add(ordered, "track-a");
        require(!before.add(ordered, "track-b"), "repeat additions are deduplicated");
        before.rename(ordered, "早班车");
        MusicCollections after = new MusicCollections(before.serialize());
        MusicCollections.Playlist restored = after.playlist(ordered.id);
        require(restored != null && restored.name.equals("早班车")
                && restored.trackIds().equals(List.of("track-b", "track-a")), "rename and order survive reload");
        require(after.isFavorite("track-b") && after.prune(Set.of("track-a"))
                && restored.trackIds().equals(List.of("track-a")), "track deletion removes stale playlist membership");
        require(!after.toggleFavorite("track-a") && !after.isFavorite("track-b"), "favorite removal survives cleanup");
        List<String> recent = new ArrayList<>(List.of("track-b", "track-a"));
        MusicCollections.recordRecent(recent, "track-a");
        require(MusicCollections.readIds(MusicCollections.writeIds(recent)).equals(List.of("track-a", "track-b")),
                "recents advance existing item while preserving next item");

        List<String> requests = LinkResolver.importInputs("复制分享 https://example.invalid/test.mp3。\n"
                + "https://example.invalid/test.mp3\nBV1ABCD12345");
        require(requests.equals(List.of("https://example.invalid/test.mp3", "BV1ABCD12345")),
                "batch paste retains order and deduplicates repeated URLs");
        JSONObject metadata = new JSONObject().put("title", "示例视频").put("pages", new JSONArray()
                .put(new JSONObject().put("cid", 101).put("part", "前篇"))
                .put(new JSONObject().put("part", "无音轨"))
                .put(new JSONObject().put("cid", 103)));
        LinkResolver.VideoParts pages = LinkResolver.videoParts("BV1ABCD12345", metadata, 3);
        require(pages.selected == 3 && pages.entries.equals(List.of(new LinkResolver.VideoPart(1, "前篇"),
                new LinkResolver.VideoPart(3, "分 P 3"))), "multipart selector preserves original page numbers");

        MusicImportTask transfer = new MusicImportTask("解析");
        transfer.begin("导入", 2, 3);
        transfer.transferred(25, 50);
        require(transfer.progress().equals(new MusicImportTask.Progress("导入", 2, 3, 25, 50)),
                "bytes and item progress are visible together");
        CountDownLatch disconnected = new CountDownLatch(1);
        HttpURLConnection request = new HttpURLConnection(new URL("https://example.invalid")) {
            @Override public void disconnect() { disconnected.countDown(); }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() {}
        };
        transfer.watch(request);
        transfer.cancel();
        require(disconnected.await(1, TimeUnit.SECONDS), "cancelling a transfer releases its network connection");
        try {
            transfer.check();
            throw new AssertionError("a cancelled job must stop");
        } catch (InterruptedIOException expected) {
            // Copy and network operations stop before committing the active transfer.
        }
        verifyCollection();
        verifyBilibiliAudioRoutes();
        System.out.println("PASS music favorites, playlist persistence, recents, batch links, multipart and import cancellation");
    }

    private static void verifyBilibiliAudioRoutes() throws IOException {
        String choice = "https://first.example.invalid/high.m4s?token=one";
        String sameHost = "https://first.example.invalid/mirror.m4s?token=two";
        String secondHost = "https://second.example.invalid/high.m4s?token=three";
        String lowerBitrate = "https://third.example.invalid/low.m4s?token=four";
        JSONObject aac = new JSONObject().put("bandwidth", 192_000).put("codecs", "mp4a.40.2")
                .put("baseUrl", choice).put("base_url", choice)
                .put("backupUrl", new JSONArray().put(choice).put(sameHost))
                .put("backup_url", new JSONArray().put("//second.example.invalid/high.m4s?token=three")
                        .put("file:///invalid"));
        JSONObject lower = new JSONObject().put("bandwidth", 64_000).put("codecs", "mp4a.40.2")
                .put("backup_url", new JSONArray().put(lowerBitrate));
        JSONObject other = new JSONObject().put("bandwidth", 3_000_000).put("codecs", "ec-3")
                .put("baseUrl", "https://third.example.invalid/other.m4s");
        JSONObject playback = new JSONObject().put("timelength", 225_000).put("dash",
                new JSONObject().put("audio", new JSONArray().put(other).put(lower).put(aac)));
        LinkResolver.Stream stream = LinkResolver.videoStream(playback);
        require(stream.urls.equals(List.of(choice, secondHost, lowerBitrate, sameHost,
                "https://third.example.invalid/other.m4s")), "AAC priority, distinct hosts and both backup fields");
        require(stream.extension.equals("m4a") && stream.durationMs == 225_000, "DASH audio retains media information");
        JSONObject mp4 = new JSONObject().put("durl", new JSONArray().put(new JSONObject()
                .put("url", "https://first.example.invalid/media.mp4")
                .put("backupUrl", new JSONArray().put("https://second.example.invalid/media.mp4"))));
        LinkResolver.Stream legacy = LinkResolver.videoStream(mp4);
        require(legacy.extension.equals("mp4") && legacy.urls.size() == 2, "legacy MP4 keeps alternate route");
        try {
            LinkResolver.videoStream(playback.put("is_preview", 1));
            throw new AssertionError("preview response must not expose playable candidates");
        } catch (IOException expected) {
            require(expected.getMessage().contains("试看"), "preview stays restricted");
        }
        System.out.println("PASS Bilibili AAC ranking, CDN fallback, legacy MP4 and preview handling");
    }

    private static void verifyCollection() throws Exception {
        URI publicPage = URI.create("https://space.bilibili.com/123/favlist?fid=456&ftype=collect");
        List<String> requests = new ArrayList<>();
        LinkResolver.VideoCollection collection = LinkResolver.videoCollection(publicPage, (address, referer) -> {
            require(address.startsWith("https://api.bilibili.com/x/polymer/web-space/seasons_archives_list?mid=123&season_id=456"),
                    "collection only requests the known Bilibili endpoint");
            require(referer.equals(publicPage.toString()), "collection request carries the ordinary public share URL");
            requests.add(address);
            int page = requests.size();
            JSONArray entries = new JSONArray();
            if (page == 1) {
                for (int i = 1; i <= 30; i++) entries.put(new JSONObject().put("aid", i).put("title", "视频" + i));
            } else {
                entries.put(new JSONObject().put("bvid", "BV1ABCD12345").put("title", "最后一首"));
            }
            return collectionResponse(123, 456, page, 31, entries);
        });
        require(requests.size() == 2 && requests.get(1).contains("page_num=2&page_size=30"), "collection pages advance");
        require(collection.title().equals("公开合集") && collection.entries().size() == 31 && !collection.limited()
                && collection.entries().get(30).videoUrl().equals("https://www.bilibili.com/video/BV1ABCD12345"),
                "all collection results retain source order and resumable canonical video URLs");
        URI unsafe = URI.create("https://space.bilibili.com.example.invalid/123/favlist?fid=456&ftype=collect");
        URI favorites = URI.create("https://space.bilibili.com/123/favlist?fid=456");
        for (URI address : List.of(unsafe, favorites)) {
            require(LinkResolver.videoCollection(address, (endpoint, ref) -> {
                throw new AssertionError("non-collection URL must not trigger a public API request");
            }) == null, "similar URLs are not treated as collections");
        }
        try {
            LinkResolver.videoCollection(publicPage, (address, referer) -> collectionResponse(124, 456, 1, 0, new JSONArray()));
            throw new AssertionError("mismatched owner must not show an unrelated collection");
        } catch (java.io.IOException expected) {
            require(expected.getMessage().contains("编号"), "mismatched metadata is surfaced");
        }
        URI legacy = URI.create("https://space.bilibili.com/123/channel/collectiondetail?sid=456");
        int[] pages = {0};
        LinkResolver.VideoCollection limited = LinkResolver.videoCollection(legacy, (address, referer) -> {
            JSONArray entries = new JSONArray();
            int start = pages[0] * 30;
            for (int i = 1; i <= 30; i++) entries.put(new JSONObject().put("aid", start + i));
            return collectionResponse(123, 456, ++pages[0], 10_000, entries);
        });
        require(limited.limited() && limited.entries().size() == 300 && pages[0] == 10,
                "oversized collections expose the first 300 titles without unbounded network requests");
        System.out.println("PASS public Bilibili collection routes, ordered pagination, ownership check and bound");
    }

    private static JSONObject collectionResponse(long mid, long id, int page, int total, JSONArray entries) {
        return new JSONObject().put("code", 0).put("data", new JSONObject()
                .put("meta", new JSONObject().put("mid", mid).put("season_id", id).put("name", "公开合集"))
                .put("page", new JSONObject().put("page_num", page).put("total", total)).put("archives", entries));
    }

    private static void require(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
}
