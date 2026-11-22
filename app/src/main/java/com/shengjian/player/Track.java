package com.shengjian.player;

import java.io.File;

public final class Track {
    public final String id;
    public final String title;
    public final String artist;
    public final String source;
    public final String sourceUrl;
    public final String url;
    public final String artwork;
    public final String referer;
    public final String format;
    public final String fileName;
    public final long durationMs;
    final File lyricsFile;

    Track(String id, String title, String artist, String source, String sourceUrl, String url,
          String artwork, String referer, String format, String fileName, long durationMs,
          File lyricsFile) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.source = source;
        this.sourceUrl = sourceUrl;
        this.url = url;
        this.artwork = artwork;
        this.referer = referer;
        this.format = format;
        this.fileName = fileName;
        this.durationMs = durationMs;
        this.lyricsFile = lyricsFile;
    }

    public boolean isLocal() {
        return !isOnline();
    }

    public boolean isOnline() {
        return url.startsWith("https:") || url.startsWith("http:") || MusicOnlineTrack.isReference(url);
    }

    public boolean hasLyrics() {
        return lyricsFile.isFile();
    }

    boolean available() {
        if (isOnline()) return true;
        try {
            java.net.URI address = java.net.URI.create(url);
            return "file".equals(address.getScheme()) && new File(address).isFile();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
