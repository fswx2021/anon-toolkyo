package com.shengjian.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class Library {
    private static final String TAG = "MusicLibrary";
    private static final long MAX_ART_BYTES = 12L * 1024 * 1024;
    private static final String HEX = "0123456789abcdef";
    private static Library instance;

    public static final class ImportResult {
        public final List<Track> tracks = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public int duplicates;
        public int lyrics;
    }

    private static final class Entry {
        final Track track;
        final String file;
        final String coverFile;
        final String hash;
        final String onlineReference;

        Entry(Track track, String file, String coverFile, String hash) {
            this(track, file, coverFile, hash, "");
        }

        Entry(Track track, String file, String coverFile, String hash, String onlineReference) {
            this.track = track;
            this.file = file;
            this.coverFile = coverFile;
            this.hash = hash;
            this.onlineReference = onlineReference;
        }

        boolean matchesReference(String reference) {
            return track.url.equals(reference) || onlineReference.equals(reference);
        }

        boolean hasLocalFile() {
            return !file.isEmpty() && track.available();
        }
    }

    private static final class Imported {
        final Track track;
        final boolean duplicate;

        Imported(Track track, boolean duplicate) {
            this.track = track;
            this.duplicate = duplicate;
        }
    }

    private static final class Selected {
        final Uri uri;
        final String name;
        final boolean lyric;

        Selected(Uri uri, String name, String type) {
            this.uri = uri;
            this.name = name;
            String ext = extension(name);
            lyric = "lrc".equals(ext) || "srt".equals(ext) || "vtt".equals(ext) || "txt".equals(ext)
                    || "application/x-subrip".equals(type) || "text/vtt".equals(type)
                    || "text/plain".equals(type) || "application/x-lrc".equals(type);
        }
    }

    private static final class MediaInfo {
        String title = "";
        String artist = "";
        long durationMs;
        byte[] cover;
    }

    private static final class Download {
        final boolean playlist;
        final String extension;
        final MediaInfo info;

        Download(boolean playlist, String extension, MediaInfo info) {
            this.playlist = playlist;
            this.extension = extension;
            this.info = info;
        }
    }

    private final ContentResolver resolver;
    private final AtomicFile catalog;
    private final File root;
    private final boolean readable;
    private final Object lyricStorage = new Object();
    private volatile List<Entry> entries;

    public static synchronized Library get(Context context) {
        if (instance == null) {
            instance = new Library(context.getApplicationContext());
        }
        return instance;
    }

    private Library(Context context) {
        resolver = context.getContentResolver();
        root = new File(context.getFilesDir(), "library");
        catalog = new AtomicFile(new File(root, "tracks.json"));
        List<Entry> saved = Collections.emptyList();
        boolean ok;
        try {
            saved = load();
            ok = true;
        } catch (IOException | JSONException | RuntimeException unreadableCatalog) {
            ok = false;
        }
        entries = saved;
        readable = ok;
    }

    public List<Track> tracks() {
        List<Track> result = new ArrayList<>();
        for (Entry entry : entries) {
            result.add(entry.track);
        }
        return result;
    }

    public synchronized ImportResult importFiles(List<Uri> files, Track lyricTarget) throws IOException {
        return importFiles(files, lyricTarget, null);
    }

    synchronized ImportResult importFiles(List<Uri> files, Track lyricTarget, MusicImportTask task) throws IOException {
        ensureWritable();
        ImportResult result = new ImportResult();
        List<Selected> lyricFiles = new ArrayList<>();
        Map<String, Track> imported = new LinkedHashMap<>();
        for (int i = 0; i < files.size(); i++) {
            if (task != null) {
                if (task.cancelled()) return result;
                task.begin(lyricTarget == null ? "正在导入文件" : "正在读取歌词", i + 1, files.size());
            }
            Uri uri = files.get(i);
            String name = label(uri.getLastPathSegment(), "未命名文件");
            try {
                name = displayName(uri);
                Selected file = new Selected(uri, name, resolver.getType(uri));
                if (file.lyric) {
                    if (lyricTarget == null) {
                        lyricFiles.add(file);
                    } else {
                        attachLyrics(lyricTarget, file.uri);
                        result.lyrics++;
                    }
                    continue;
                }
                if (lyricTarget != null) {
                    throw new IOException("请选择 LRC、SRT、VTT 或 TXT 歌词文件");
                }
                Imported item = importMedia(file, task);
                imported.put(stem(name).toLowerCase(Locale.ROOT), item.track);
                if (item.duplicate) {
                    result.duplicates++;
                } else {
                    result.tracks.add(item.track);
                }
            } catch (IOException | RuntimeException problem) {
                if (task != null && task.cancelled()) return result;
                result.errors.add(name + " · " + label(problem.getMessage(), "无法读取文件"));
            }
        }
        Set<String> paired = new HashSet<>();
        for (Selected lyric : lyricFiles) {
            if (task != null) {
                if (task.cancelled()) return result;
                task.begin("正在匹配歌词", 0, 0);
            }
            try {
                Track target = matchingTrack(lyric, imported, lyricFiles.size());
                if (target == null) {
                    throw new IOException("请在歌曲详情里选择此歌词");
                }
                if (paired.contains(target.id)) {
                    throw new IOException("已为此歌曲选择了另一份歌词");
                }
                attachLyrics(target, lyric.uri);
                paired.add(target.id);
                result.lyrics++;
            } catch (IOException | RuntimeException problem) {
                result.errors.add(lyric.name + " · " + label(problem.getMessage(), "无法读取歌词"));
            }
        }
        return result;
    }

    public Track findBySourceUrl(String sourceUrl) {
        if (!sourceUrl.isEmpty()) {
            for (Entry entry : entries) {
                if (entry.track.sourceUrl.equals(sourceUrl) && !MusicOnlineTrack.isReference(entry.track.url)) {
                    return entry.track;
                }
            }
        }
        return null;
    }

    public synchronized Track addLink(LinkResolver.Result link) throws IOException {
        return addLink(link, null);
    }

    synchronized Track addOnline(MusicOnlineEngine.Song song, MusicOnlineEngine.Config config) throws IOException {
        ensureWritable();
        String reference = MusicOnlineTrack.address(song, config);
        String sourceUrl = MusicOnlineEngine.sourcePage(song);
        Entry known = onlineEntry(reference);
        if (known != null && known.hasLocalFile()) return known.track;
        Track imported = findBySourceUrl(sourceUrl);
        if (imported != null && imported.available()) return imported;
        if (known != null) {
            if (known.file.isEmpty()) return known.track;
            // Recover an online reference if the downloaded file was removed outside the app.
            Track previous = known.track;
            Track online = new Track(previous.id, previous.title, previous.artist, previous.source,
                    previous.sourceUrl, reference, previous.artwork, "", "在线音频", previous.fileName,
                    previous.durationMs, previous.lyricsFile);
            replace(known, new Entry(online, "", known.coverFile, "", reference));
            return online;
        }
        String id = UUID.randomUUID().toString();
        Track track = new Track(id, label(song.title, "未命名音频"), song.artist, MusicOnlineEngine.providerName(song.provider),
                sourceUrl, reference, song.coverUrl, "", "在线音频", "", song.durationMs, lyricsFile(directory(id)));
        add(new Entry(track, "", "", ""));
        return track;
    }

    Track downloadOnline(MusicOnlineEngine.Song song, MusicOnlineEngine.Config config, MusicImportTask task) throws IOException {
        return downloadOnline(song, config, MusicOnlineTrack.address(song, config), null, task);
    }

    Track downloadOnline(Track track, MusicOnlineEngine.Config config, MusicImportTask task) throws IOException {
        Entry target = entry(track.id);
        if (target == null) throw new IOException("歌曲已从曲库移除");
        if (target.hasLocalFile()) return target.track;
        Track current = target.track;
        String address = target.onlineReference.isEmpty() ? current.url : target.onlineReference;
        MusicOnlineTrack reference = MusicOnlineTrack.parse(address);
        if (reference.isNavidrome()) reference.checkAccount(config);
        MusicOnlineEngine.Song song = new MusicOnlineEngine.Song(reference.provider(), reference.songId(),
                current.title, current.artist, "", current.artwork, current.durationMs);
        return downloadOnline(song, config, address, current.id, task);
    }

    private Track downloadOnline(MusicOnlineEngine.Song song, MusicOnlineEngine.Config config, String reference,
                                 String requestedId, MusicImportTask task) throws IOException {
        if (task != null) task.check();
        String sourceUrl = MusicOnlineEngine.sourcePage(song);
        Entry prior;
        synchronized (this) {
            ensureWritable();
            prior = downloadTarget(reference, requestedId);
            if (prior != null && prior.hasLocalFile()) return prior.track;
            if (prior == null) {
                Track imported = localBySourceUrl(sourceUrl, "");
                if (imported != null) return imported;
            }
        }

        LinkResolver.Result link = MusicOnlineEngine.resolveDownload(song, config);
        String id = UUID.randomUUID().toString();
        File staging = directory(id);
        createDirectory(staging);
        boolean keepStaging = false;
        try {
            Download download = downloadOfflineMedia(staging, link, task);
            String file = mediaFileName(download.extension);
            saveEmbeddedLyrics(new File(staging, file), download.extension, staging);

            String artwork = label(song.coverUrl, link.artwork);
            String cover = saveCover(staging, download.info.cover);
            if (cover.isEmpty() && !artwork.isEmpty() && (prior == null || prior.coverFile.isEmpty())) {
                cover = fetchCover(staging, artwork, link.referer, task);
            }
            if ((prior == null || !prior.track.hasLyrics()) && !lyricsFile(staging).isFile()) {
                if (!link.lyricUrl.isEmpty()) fetchLyrics(staging, link.lyricUrl, link.referer, task);
            }

            Track saved = commitOnlineDownload(song, reference, requestedId, sourceUrl, link, id,
                    staging, download, file, artwork, cover, task);
            keepStaging = saved.id.equals(id);
            return saved;
        } finally {
            if (!keepStaging) deleteDirectoryContents(staging);
        }
    }

    private static Download downloadOfflineMedia(File directory, LinkResolver.Result link, MusicImportTask task)
            throws IOException {
        String extension = safeExtension(link.extension);
        if ("m3u8".equals(extension) || "mpd".equals(extension)) {
            throw new IOException("此音源仅提供流式播放，无法下载为本地音频");
        }
        IOException failure = null;
        for (String address : link.mediaUrls) {
            if (task != null) task.check();
            try {
                Download download = importDownload(directory, address, link.referer, extension, task, true);
                if (download.playlist) throw new IOException("此音源仅提供流式播放，无法下载为本地音频");
                return download;
            } catch (IOException unavailable) {
                if (task != null) task.check();
                new File(directory, "download.pending").delete();
                if (failure == null) failure = unavailable;
            }
        }
        if (failure != null) throw failure;
        throw new IOException("此音源没有提供可下载的音频");
    }

    private synchronized Track commitOnlineDownload(MusicOnlineEngine.Song song, String reference,
                                                     String requestedId, String sourceUrl, LinkResolver.Result link,
                                                     String id, File staging, Download download, String file,
                                                     String artwork, String cover, MusicImportTask task) throws IOException {
        if (task != null) task.check();
        Entry current = downloadTarget(reference, requestedId);
        if (current != null && current.hasLocalFile()) return current.track;
        if (current == null) {
            Track imported = localBySourceUrl(sourceUrl, link.sourceUrl);
            if (imported != null) return imported;
        }

        Track prior = current == null ? null : current.track;
        String savedId = prior == null ? id : prior.id;
        File target = directory(savedId);
        String coverFile = current != null && !current.coverFile.isEmpty() ? current.coverFile : cover;
        MediaInfo info = download.info;
        long duration = prior == null ? song.durationMs : prior.durationMs;
        if (duration <= 0) duration = link.durationMs > 0 ? link.durationMs : info.durationMs;
        Track saved = new Track(savedId,
                prior == null ? label(song.title, label(link.title, label(info.title, "未命名音频"))) : prior.title,
                prior == null ? label(song.artist, label(link.artist, info.artist)) : prior.artist,
                prior == null ? MusicOnlineEngine.providerName(song.provider) : prior.source,
                prior == null ? label(sourceUrl, link.sourceUrl) : prior.sourceUrl,
                fileUri(target, file), coverFile.isEmpty() ? artwork : fileUri(target, coverFile),
                link.referer, label(download.extension, "媒体").toUpperCase(Locale.ROOT),
                prior == null ? "" : prior.fileName, duration, lyricsFile(target));
        Entry updated = new Entry(saved, file, coverFile, "", reference);
        if (current == null) {
            add(updated);
            return saved;
        }

        synchronized (lyricStorage) {
            List<File> moved = new ArrayList<>();
            boolean committed = false;
            try {
                createDirectory(target);
                install(staging, target, file, moved);
                if (!cover.isEmpty() && current.coverFile.isEmpty()) install(staging, target, cover, moved);
                if (!saved.hasLyrics() && lyricsFile(staging).isFile()) {
                    install(staging, target, saved.lyricsFile.getName(), moved);
                }
                if (task != null) task.check();
                replace(current, updated);
                committed = true;
            } finally {
                if (!committed) for (File installed : moved) installed.delete();
            }
        }
        return saved;
    }

    private static void install(File staging, File target, String name, List<File> moved) throws IOException {
        File installed = new File(target, name);
        move(new File(staging, name), installed);
        moved.add(installed);
    }

    private Entry downloadTarget(String reference, String requestedId) throws IOException {
        if (requestedId == null) return onlineEntry(reference);
        Entry target = entry(requestedId);
        if (target == null || !target.matchesReference(reference)) throw new IOException("歌曲已从曲库移除");
        return target;
    }

    private Entry onlineEntry(String reference) {
        for (Entry entry : entries) {
            if (entry.matchesReference(reference)) return entry;
        }
        return null;
    }

    private Track localBySourceUrl(String sourceUrl, String alternative) {
        for (Entry entry : entries) {
            String stored = entry.track.sourceUrl;
            if (!stored.isEmpty() && (stored.equals(sourceUrl) || stored.equals(alternative)) && entry.hasLocalFile()) {
                return entry.track;
            }
        }
        return null;
    }

    boolean hasLyrics(String id) {
        Entry target = entry(id);
        return target != null && target.track.hasLyrics();
    }

    synchronized void saveOnlineLyrics(String id, String reference, String text) throws IOException {
        if (text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > Lyrics.MAX_BYTES) return;
        Entry target = entry(id);
        if (target == null || !target.matchesReference(reference)) return;
        synchronized (lyricStorage) {
            if (target.track.hasLyrics()) return;
            createDirectory(directory(id));
            storeLyrics(target.track.lyricsFile, text);
        }
    }

    synchronized Track addLink(LinkResolver.Result link, MusicImportTask task) throws IOException {
        ensureWritable();
        if (task != null) task.check();
        Track duplicate = findBySourceUrl(link.sourceUrl);
        if (duplicate != null) {
            return duplicate;
        }
        String id = UUID.randomUUID().toString();
        File directory = directory(id);
        createDirectory(directory);
        boolean saved = false;
        try {
            String ext = safeExtension(link.extension);
            String file = "";
            String onlineUrl = link.url;
            MediaInfo info = new MediaInfo();
            if (!"m3u8".equals(ext)) {
                Download download;
                try {
                    download = importDownload(directory, link.url, link.referer, ext, task);
                } catch (IOException primary) {
                    if (task != null) task.check();
                    if (link.backupUrl.isEmpty()) {
                        throw primary;
                    }
                    new File(directory, "download.pending").delete();
                    download = importDownload(directory, link.backupUrl, link.referer, ext, task);
                    onlineUrl = link.backupUrl;
                }
                ext = download.extension;
                if (!download.playlist) {
                    file = mediaFileName(ext);
                    info = download.info;
                    saveEmbeddedLyrics(new File(directory, file), ext, directory);
                }
            }
            String coverFile = saveCover(directory, info.cover);
            if (task != null) task.check();
            if (coverFile.isEmpty() && !link.artwork.isEmpty()) {
                coverFile = fetchCover(directory, link.artwork, link.referer, task);
            }
            if (!link.lyricUrl.isEmpty()) {
                fetchLyrics(directory, link.lyricUrl, link.referer, task);
            }
            String format = label(ext, "媒体").toUpperCase(Locale.ROOT);
            Track track = new Track(id, label(link.title, label(info.title, "未命名音频")),
                    label(link.artist, info.artist), link.source, link.sourceUrl,
                    file.isEmpty() ? onlineUrl : fileUri(directory, file),
                    coverFile.isEmpty() ? link.artwork : fileUri(directory, coverFile),
                    link.referer, format, "", link.durationMs > 0 ? link.durationMs : info.durationMs,
                    lyricsFile(directory));
            if (task != null) task.check();
            add(new Entry(track, file, coverFile, ""));
            saved = true;
            return track;
        } finally {
            if (!saved) {
                deleteDirectoryContents(directory);
            }
        }
    }

    public synchronized void remove(Track track) throws IOException {
        Entry target = entry(track.id);
        if (target == null) {
            return;
        }
        ensureWritable();
        List<Entry> updated = new ArrayList<>(entries);
        updated.remove(target);
        save(updated);
        synchronized (lyricStorage) {
            deleteDirectoryContents(directory(track.id));
        }
    }

    synchronized void editMetadata(Track track, String title, String artist) throws IOException {
        ensureWritable();
        Entry current = entry(track.id);
        if (current == null) throw new IOException("歌曲已从曲库移除");
        String name = title.trim();
        String creator = artist.trim();
        if (name.isEmpty()) throw new IOException("请输入曲名");
        Track original = current.track;
        if (original.title.equals(name) && original.artist.equals(creator)) return;
        Track updated = new Track(original.id, name, creator, original.source, original.sourceUrl, original.url,
                original.artwork, original.referer, original.format, original.fileName, original.durationMs,
                original.lyricsFile);
        replace(current, new Entry(updated, current.file, current.coverFile, current.hash, current.onlineReference));
    }

    public synchronized void attachLyrics(Track track, Uri uri) throws IOException {
        ensureWritable();
        Entry target = entry(track.id);
        if (target == null) {
            throw new IOException("歌曲已从曲库移除");
        }
        try (InputStream input = open(uri)) {
            String text = Lyrics.decode(Lyrics.read(input));
            synchronized (lyricStorage) {
                storeLyrics(target.track.lyricsFile, text);
            }
        }
    }

    public List<Lyrics.Line> lyrics(Track track) throws IOException {
        synchronized (lyricStorage) {
            try (InputStream input = new AtomicFile(track.lyricsFile).openRead()) {
                return Lyrics.parse(Lyrics.decode(Lyrics.read(input)));
            } catch (FileNotFoundException removed) {
                return Collections.emptyList();
            }
        }
    }

    private Imported importMedia(Selected selected, MusicImportTask task) throws IOException {
        String id = UUID.randomUUID().toString();
        File directory = directory(id);
        createDirectory(directory);
        boolean saved = false;
        try {
            String ext = safeExtension(extension(selected.name));
            String file = mediaFileName(ext);
            File media = new File(directory, file);
            MessageDigest digest = newDigest();
            try (InputStream input = open(selected.uri)) {
                copy(input, media, Long.MAX_VALUE, digest, task, fileLength(selected.uri));
            }
            String hash = hex(digest.digest());
            for (Entry entry : entries) {
                if (entry.hash.equals(hash) && new File(directory(entry.track.id), entry.file).isFile()) {
                    return new Imported(entry.track, true);
                }
            }
            MediaInfo info = inspect(media);
            String cover = saveCover(directory, info.cover);
            saveEmbeddedLyrics(media, ext, directory);
            Track track = new Track(id, label(info.title, stem(selected.name)), info.artist,
                    "本地文件", "", fileUri(directory, file), cover.isEmpty() ? "" : fileUri(directory, cover),
                    "", label(ext, "媒体").toUpperCase(Locale.ROOT), selected.name, info.durationMs,
                    lyricsFile(directory));
            if (task != null) task.check();
            add(new Entry(track, file, cover, hash));
            saved = true;
            return new Imported(track, false);
        } finally {
            if (!saved) {
                deleteDirectoryContents(directory);
            }
        }
    }

    private Track matchingTrack(Selected lyric, Map<String, Track> imported, int lyricCount) {
        String name = stem(lyric.name);
        Track exact = imported.get(name.toLowerCase(Locale.ROOT));
        if (exact != null) {
            return exact;
        }
        Track match = null;
        for (Entry item : entries) {
            if (stem(item.track.fileName).equalsIgnoreCase(name)) {
                if (match != null) {
                    return null;
                }
                match = item.track;
            }
        }
        if (match == null && lyricCount == 1 && imported.size() == 1) {
            return imported.values().iterator().next();
        }
        return match;
    }

    private static MediaInfo inspect(File file) throws IOException {
        return inspect(file, false);
    }

    private static MediaInfo inspect(File file, boolean offlineOnly) throws IOException {
        MediaInfo info = new MediaInfo();
        MediaMetadataRetriever tags = new MediaMetadataRetriever();
        boolean hasAudio = false;
        try {
            tags.setDataSource(file.getPath());
            hasAudio = "yes".equals(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            info.title = label(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE), "");
            info.artist = label(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    label(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST), ""));
            info.durationMs = parseDuration(tags.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            info.cover = tags.getEmbeddedPicture();
        } catch (RuntimeException ignored) {
            // A file may be playable when its tags or artwork are unavailable.
        } finally {
            try {
                tags.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        boolean protectedAudio = false;
        if (!hasAudio || offlineOnly) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(file.getPath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("audio/")) {
                        hasAudio = true;
                        if (info.durationMs <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) {
                            info.durationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000;
                        }
                        if (offlineOnly) {
                            extractor.selectTrack(i);
                            protectedAudio = extractor.getDrmInitData() != null
                                    || extractor.getSampleCryptoInfo(new MediaCodec.CryptoInfo());
                        }
                        break;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            } finally {
                extractor.release();
            }
        }
        if (protectedAudio) throw new IOException("此音轨受 DRM 保护，无法离线播放");
        if (!hasAudio) {
            throw new IOException("未找到系统可读取的音轨");
        }
        return info;
    }

    private static long parseDuration(String duration) {
        try {
            return Long.parseLong(duration);
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static Download importDownload(File directory, String url, String referer, String ext,
                                           MusicImportTask task) throws IOException {
        return importDownload(directory, url, referer, ext, task, false);
    }

    private static Download importDownload(File directory, String url, String referer, String ext,
                                           MusicImportTask task, boolean offlineOnly) throws IOException {
        File pending = new File(directory, "download.pending");
        HttpURLConnection request = connect(url, referer, task);
        String mediaExtension;
        try {
            String mime = contentType(request);
            if (mime.contains("mpegurl") || "m3u8".equals(extension(Uri.parse(request.getURL().toString()).getPath()))) {
                return new Download(true, "m3u8", null);
            }
            if (offlineOnly && (mime.equals("application/dash+xml")
                    || "mpd".equals(extension(Uri.parse(request.getURL().toString()).getPath())))) {
                return new Download(true, "mpd", null);
            }
            if (mime.startsWith("text/") || "application/json".equals(mime) || mime.startsWith("image/")) {
                throw new IOException("该链接没有返回可下载的音视频");
            }
            mediaExtension = offlineOnly ? label(extensionForMime(mime), ext) : label(ext, extensionForMime(mime));
            if (task != null) task.check();
            long expected = request.getContentLengthLong();
            try (InputStream input = request.getInputStream()) {
                long downloaded = copy(input, pending, Long.MAX_VALUE, null, task, expected);
                if (expected > 0 && downloaded != expected) {
                    throw new IOException("媒体文件下载不完整");
                }
            }
        } finally {
            disconnect(request, task);
        }
        File media = new File(directory, mediaFileName(mediaExtension));
        move(pending, media);
        try {
            return new Download(false, mediaExtension, inspect(media, offlineOnly));
        } catch (IOException invalid) {
            media.delete();
            throw invalid;
        }
    }

    private static HttpURLConnection connect(String link, String referer, MusicImportTask task) throws IOException {
        URL address = new URL(link);
        for (int redirects = 0; redirects < 6; redirects++) {
            String scheme = address.getProtocol();
            if (!"https".equals(scheme) && !"http".equals(scheme)) {
                throw new IOException("仅支持 HTTP 或 HTTPS 音视频链接");
            }
            HttpURLConnection request = (HttpURLConnection) address.openConnection();
            request.setInstanceFollowRedirects(false);
            request.setConnectTimeout(12_000);
            request.setReadTimeout(20_000);
            request.setRequestProperty("User-Agent", LinkResolver.USER_AGENT);
            request.setRequestProperty("Accept-Encoding", "identity");
            if (!referer.isEmpty()) {
                request.setRequestProperty("Referer", referer);
            }
            int status;
            try {
                if (task != null) task.watch(request);
                status = request.getResponseCode();
            } catch (IOException failed) {
                disconnect(request, task);
                throw failed;
            }
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String redirect = request.getHeaderField("Location");
                disconnect(request, task);
                if (redirect == null) {
                    throw new IOException("媒体链接返回了无效跳转");
                }
                address = new URL(address, redirect);
                continue;
            }
            if (status != 200) {
                disconnect(request, task);
                throw new IOException("下载失败（HTTP " + status + "）");
            }
            return request;
        }
        throw new IOException("媒体链接跳转过多");
    }

    private static void disconnect(HttpURLConnection request, MusicImportTask task) {
        if (task != null) task.release(request);
        request.disconnect();
    }

    private static String contentType(HttpURLConnection request) {
        String value = label(request.getContentType(), "");
        return value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
    }

    private static String extensionForMime(String mime) {
        switch (mime) {
            case "audio/mpeg": return "mp3";
            case "audio/aac":
            case "audio/aacp": return "aac";
            case "audio/mp4":
            case "audio/x-m4a": return "m4a";
            case "audio/flac":
            case "audio/x-flac": return "flac";
            case "audio/wav":
            case "audio/x-wav": return "wav";
            case "audio/ogg":
            case "application/ogg": return "ogg";
            case "video/mp4": return "mp4";
            case "video/webm": return "webm";
            case "video/x-matroska": return "mkv";
            case "video/quicktime": return "mov";
            default: return "";
        }
    }

    private static String saveCover(File directory, byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_ART_BYTES) {
            return "";
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return "";
        }
        File file = new File(directory, "cover.img");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
            return file.getName();
        } catch (IOException ignored) {
            return "";
        }
    }

    private static String fetchCover(File directory, String link, String referer, MusicImportTask task) {
        File file = new File(directory, "cover.img");
        try {
            HttpURLConnection request = connect(link, referer, task);
            try (InputStream input = request.getInputStream()) {
                copy(input, file, MAX_ART_BYTES, null);
            } finally {
                disconnect(request, task);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                return file.getName();
            }
        } catch (IOException | RuntimeException ignored) {
        }
        file.delete();
        return "";
    }

    private static void fetchLyrics(File directory, String link, String referer, MusicImportTask task) {
        try {
            HttpURLConnection request = connect(link, referer, task);
            try (InputStream input = request.getInputStream()) {
                try {
                    storeLyrics(lyricsFile(directory), Lyrics.decode(Lyrics.read(input)));
                } catch (LinkageError unavailable) {
                    Log.w(TAG, "Optional remote lyrics unavailable", unavailable);
                }
            } finally {
                disconnect(request, task);
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void saveEmbeddedLyrics(File media, String extension, File directory) {
        try {
            String text = "flac".equals(extension) ? Lyrics.embeddedFlac(media) : Lyrics.embeddedMp3(media);
            if (!text.trim().isEmpty()) {
                storeLyrics(lyricsFile(directory), text);
            }
        } catch (LinkageError unavailable) {
            Log.w(TAG, "Optional embedded lyrics unavailable", unavailable);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void storeLyrics(File file, String text) throws IOException {
        if (text.indexOf('\0') >= 0 || text.trim().startsWith("<!DOCTYPE")
                || text.trim().startsWith("<html") || Lyrics.parse(text).isEmpty()) {
            throw new IOException("这份文件没有可用的歌词");
        }
        write(new AtomicFile(file), text.getBytes(StandardCharsets.UTF_8));
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return label(cursor.getString(index), "未命名文件");
                }
            }
        } catch (RuntimeException ignored) {
        }
        String name = uri.getLastPathSegment();
        if (name != null && name.contains("/")) {
            name = name.substring(name.lastIndexOf('/') + 1);
        }
        return label(name, "未命名文件");
    }

    private long fileLength(Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            int index = cursor == null ? -1 : cursor.getColumnIndex(OpenableColumns.SIZE);
            return index >= 0 && cursor.moveToFirst() && !cursor.isNull(index) ? cursor.getLong(index) : -1;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    private InputStream open(Uri uri) throws IOException {
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IOException("无法读取文件");
        }
        return input;
    }

    private Entry entry(String id) {
        for (Entry entry : entries) {
            if (entry.track.id.equals(id)) {
                return entry;
            }
        }
        return null;
    }

    private void add(Entry entry) throws IOException {
        List<Entry> updated = new ArrayList<>(entries);
        updated.add(0, entry);
        save(updated);
    }

    private void replace(Entry current, Entry replacement) throws IOException {
        List<Entry> updated = new ArrayList<>(entries);
        updated.set(updated.indexOf(current), replacement);
        save(updated);
    }

    private List<Entry> load() throws IOException, JSONException {
        byte[] content;
        try {
            content = catalog.readFully();
        } catch (FileNotFoundException absent) {
            if (catalog.getBaseFile().exists()) {
                throw absent;
            }
            return Collections.emptyList();
        }
        JSONArray data = new JSONArray(new String(content, StandardCharsets.UTF_8));
        List<Entry> loaded = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.getJSONObject(i);
            String id = UUID.fromString(item.getString("id")).toString();
            File directory = directory(id);
            String file = safeFile(item.optString("file"));
            String cover = safeFile(item.optString("coverFile"));
            Track track = new Track(id, item.getString("title"), item.optString("artist"),
                    item.optString("source", "本地文件"), item.optString("sourceUrl"),
                    file.isEmpty() ? item.optString("url") : fileUri(directory, file),
                    cover.isEmpty() ? item.optString("artwork") : fileUri(directory, cover),
                    item.optString("referer"), item.optString("format", "媒体"),
                    item.optString("fileName"), item.optLong("durationMs"), lyricsFile(directory));
            String reference = item.optString("onlineReference");
            loaded.add(new Entry(track, file, cover, item.optString("hash"),
                    MusicOnlineTrack.isReference(reference) ? reference : ""));
        }
        return loaded;
    }

    private void save(List<Entry> updated) throws IOException {
        JSONArray data = new JSONArray();
        try {
            for (Entry entry : updated) {
                Track track = entry.track;
                JSONObject item = new JSONObject();
                item.put("id", track.id);
                item.put("title", track.title);
                item.put("artist", track.artist);
                item.put("source", track.source);
                item.put("sourceUrl", track.sourceUrl);
                item.put("url", entry.file.isEmpty() ? track.url : "");
                item.put("file", entry.file);
                item.put("artwork", entry.coverFile.isEmpty() ? track.artwork : "");
                item.put("coverFile", entry.coverFile);
                item.put("referer", track.referer);
                item.put("format", track.format);
                item.put("fileName", track.fileName);
                item.put("durationMs", track.durationMs);
                item.put("hash", entry.hash);
                if (!entry.onlineReference.isEmpty()) item.put("onlineReference", entry.onlineReference);
                data.put(item);
            }
        } catch (JSONException invalid) {
            throw new IOException("无法更新曲库", invalid);
        }
        write(catalog, data.toString().getBytes(StandardCharsets.UTF_8));
        entries = updated;
    }

    private static void write(AtomicFile file, byte[] data) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(data);
            file.finishWrite(output);
        } catch (IOException | RuntimeException problem) {
            file.failWrite(output);
            throw problem;
        }
    }

    private void ensureWritable() throws IOException {
        if (!readable) {
            throw new IOException("曲库索引无法读取");
        }
        createDirectory(root);
    }

    private File directory(String id) {
        return new File(root, id);
    }

    private static void createDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建曲库目录");
        }
    }

    private static File lyricsFile(File directory) {
        return new File(directory, "lyrics.txt");
    }

    private static String fileUri(File directory, String name) {
        return Uri.fromFile(new File(directory, name)).toString();
    }

    private static void deleteDirectoryContents(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                child.delete();
            }
        }
        directory.delete();
    }

    private static void move(File from, File to) throws IOException {
        if (!from.renameTo(to)) {
            throw new IOException("无法保存导入的音频");
        }
    }

    private static long copy(InputStream input, File file, long limit, MessageDigest digest) throws IOException {
        return copy(input, file, limit, digest, null, -1);
    }

    private static long copy(InputStream input, File file, long limit, MessageDigest digest,
                             MusicImportTask task, long expected) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[64 * 1024];
            long total = 0;
            int count;
            while (true) {
                if (task != null) task.check();
                count = input.read(bytes);
                if (count == -1) break;
                total += count;
                if (total > limit) {
                    throw new IOException("文件过大");
                }
                output.write(bytes, 0, count);
                if (digest != null) {
                    digest.update(bytes, 0, count);
                }
                if (task != null) task.transferred(total, expected);
            }
            if (task != null) task.check();
            if (total == 0) {
                throw new IOException("文件内容为空");
            }
            return total;
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder encoded = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            encoded.append(HEX.charAt((value & 0xf0) >>> 4));
            encoded.append(HEX.charAt(value & 0x0f));
        }
        return encoded.toString();
    }

    private static String mediaFileName(String ext) {
        return ext.isEmpty() ? "media" : "media." + ext;
    }

    private static String label(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String stem(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static String extension(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String safeExtension(String extension) {
        return extension != null && extension.matches("[A-Za-z0-9]{1,12}")
                ? extension.toLowerCase(Locale.ROOT) : "";
    }

    private static String safeFile(String file) {
        return file.matches("(?:media|cover)(?:\\.[A-Za-z0-9]{1,12})?") ? file : "";
    }
}
