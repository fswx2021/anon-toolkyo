package com.shengjian.player;

import android.net.Uri;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

/** Stores references to tracks without storing temporary or authenticated media URLs. */
record MusicOnlineTrack(String provider, String scope, String songId) {
    private static final String SCHEME = "anon-music";

    static String address(MusicOnlineEngine.Song song, MusicOnlineEngine.Config config) throws IOException {
        Uri.Builder address = new Uri.Builder().scheme(SCHEME).authority(song.provider);
        if (MusicOnlineEngine.NAVIDROME.equals(song.provider)) address.appendPath(accountScope(config));
        return address.appendPath(song.id).build().toString();
    }

    static boolean isReference(String address) {
        return address.startsWith(SCHEME + "://");
    }

    static MusicOnlineTrack parse(String address) throws IOException {
        Uri uri = Uri.parse(address);
        String provider = uri.getEncodedAuthority();
        List<String> segments = uri.getPathSegments();
        if (SCHEME.equals(uri.getScheme()) && uri.getEncodedQuery() == null && uri.getEncodedFragment() == null) {
            if (MusicOnlineEngine.publicProvider(provider) && segments.size() == 1) {
                return new MusicOnlineTrack(provider, "", segments.get(0));
            }
            if (MusicOnlineEngine.NAVIDROME.equals(provider) && segments.size() == 2 && segments.get(0).matches("[0-9a-f]{64}")) {
                return new MusicOnlineTrack(provider, segments.get(0), segments.get(1));
            }
        }
        throw new IOException("无法读取在线曲目的来源");
    }

    boolean isNavidrome() { return MusicOnlineEngine.NAVIDROME.equals(provider); }

    void checkAccount(MusicOnlineEngine.Config config) throws IOException {
        MusicOnlineEngine.validateConfig(config);
        if (!scope.equals(accountScope(config))) throw new IOException("此曲目来自另一个 Navidrome 曲库，请切换回原账号或重新搜索");
    }

    private static String accountScope(MusicOnlineEngine.Config config) throws IOException {
        String key = MusicOnlineEngine.canonicalBaseUrl(config) + "\n" + config.username().trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[2 * i] = Character.forDigit((digest[i] & 0xff) >>> 4, 16);
                result[2 * i + 1] = Character.forDigit(digest[i] & 0xf, 16);
            }
            return new String(result);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("无法识别 Navidrome 曲库");
        }
    }
}
