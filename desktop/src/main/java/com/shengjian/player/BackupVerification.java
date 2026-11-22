package com.shengjian.player;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class BackupVerification {
    private BackupVerification() {}

    static void verify(Path root) throws Exception {
        Path source = root.resolve("source");
        String directory = "library/3ea71b7d-2f0c-411f-a3d9-87564188bd60/";
        put(source.resolve("reader-sources.json"), "[]");
        put(source.resolve("anime-sources.json"), "[]");
        put(source.resolve("library/tracks.json"), "[]");
        put(source.resolve(directory + "lyrics.txt"), "歌词");
        put(source.resolve(directory + "media.mp3"), "sound");
        put(source.resolve("reader-local/0123456789abcdef0123456789abcdef/index.json"), "{}");
        put(source.resolve("reader-offline/chapters/chapter.zip"), "saved");
        put(source.resolve("reader-offline/images/temporary.img"), "discard");
        for (boolean content : new boolean[]{false, true}) {
            JSONObject preferences = new JSONObject().put("app_settings", new JSONObject());
            JSONObject settings = new JSONObject().put("format", 1).put("preferences", preferences).put("content", content);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            BackupArchive.export(source, settings, content, buffer);
            Path extract = root.resolve("extract-" + content);
            JSONObject restored = BackupArchive.extract(new ByteArrayInputStream(buffer.toByteArray()), extract);
            if (restored.getBoolean("content") != content
                    || !Files.readString(extract.resolve("anime-sources.json")).equals("[]")
                    || !Files.readString(extract.resolve(directory + "lyrics.txt")).equals("歌词")
                    || Files.exists(extract.resolve(directory + "media.mp3")) != content
                    || Files.exists(extract.resolve("reader-local")) != content
                    || Files.exists(extract.resolve("reader-offline/chapters/chapter.zip")) != content
                    || Files.exists(extract.resolve("reader-offline/images"))) {
                throw new AssertionError("Backup content and preference round trip");
            }
        }
        ByteArrayOutputStream invalid = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(invalid)) {
            zip.putNextEntry(new ZipEntry("../unexpected.txt"));
            zip.write("invalid".getBytes(StandardCharsets.UTF_8));
        }
        try {
            BackupArchive.extract(new ByteArrayInputStream(invalid.toByteArray()), root.resolve("bad"));
            throw new AssertionError("Unsafe backup archive accepted");
        } catch (IOException rejected) {
            if (Files.exists(root.resolve("unexpected.txt"))) throw rejected;
        }
        System.out.println("PASS complete and settings-only backup archive round trip and unsafe entry rejection");
    }

    private static void put(Path file, String value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }
}
