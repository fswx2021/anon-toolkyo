package com.shengjian.player;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

final class BackupArchive {
    static final String SETTINGS = "settings.json";
    private static final int MAX_ENTRIES = 20_000;
    private static final long MAX_BYTES = 16L * 1024 * 1024 * 1024;
    private static final long MAX_METADATA = 64L * 1024 * 1024;
    private static final Pattern TRACK = Pattern.compile("library/[0-9a-fA-F-]{36}/(?:media(?:\\.[A-Za-z0-9]{1,12})?|cover\\.img|lyrics\\.txt)");
    private static final Pattern LOCAL_BOOK = Pattern.compile("reader-local/[0-9a-f]{32}/(?:index\\.json|book\\.zip|[0-9]{1,5}\\.txt)");
    private static final Pattern OFFLINE = Pattern.compile("reader-offline/chapters/[A-Za-z0-9_-]{1,160}\\.zip");

    private BackupArchive() {}

    static void export(Path root, JSONObject settings, boolean content, OutputStream destination) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry(SETTINGS));
            byte[] metadata = settings.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if (metadata.length > MAX_METADATA) throw new IOException("备份设置超过允许大小");
            zip.write(metadata);
            zip.closeEntry();
            long total = metadata.length;
            int count = 1;
            for (String entry : List.of("reader-sources.json", "anime-sources.json", "library", "reader-local", "reader-offline/chapters")) {
                Path directory = root.resolve(entry);
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                try (var paths = Files.walk(directory, 2)) {
                    var files = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).iterator();
                    while (files.hasNext()) {
                        Path path = files.next();
                        String name = root.relativize(path).toString().replace('\\', '/');
                        if (!allowed(name) || !content && contentFile(name)) continue;
                        if (++count > MAX_ENTRIES) throw new IOException("备份包含过多文件");
                        zip.putNextEntry(new ZipEntry(name));
                        try (InputStream input = Files.newInputStream(path)) {
                            total += copy(input, zip, Math.min(MAX_BYTES - total, limit(name)));
                        }
                        zip.closeEntry();
                    }
                }
            }
        }
    }

    static JSONObject extract(InputStream source, Path stage) throws IOException {
        long total = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(source)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !allowed(name) || !names.add(name) || names.size() > MAX_ENTRIES) {
                    throw new IOException("备份包含无效或重复文件");
                }
                Path target = stage.resolve(name);
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    total += copy(zip, output, Math.min(MAX_BYTES - total, limit(name)));
                }
                zip.closeEntry();
            }
        }
        return readSettings(stage);
    }

    static JSONObject readSettings(Path stage) throws IOException {
        try {
            JSONObject settings = new JSONObject(readText(stage.resolve(SETTINGS)));
            if (settings.optInt("format") != 1 || settings.optJSONObject("preferences") == null) {
                throw new IOException("备份格式不受支持");
            }
            return settings;
        } catch (JSONException invalid) {
            throw new IOException("备份设置文件损坏", invalid);
        }
    }

    static String readText(Path file) throws IOException {
        if (Files.size(file) > MAX_METADATA) throw new IOException("备份资料超过允许大小");
        return new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
    }

    static boolean allowed(String name) {
        return name.equals(SETTINGS) || name.equals("reader-sources.json") || name.equals("anime-sources.json")
                || name.equals("library/tracks.json")
                || TRACK.matcher(name).matches() || LOCAL_BOOK.matcher(name).matches() || OFFLINE.matcher(name).matches();
    }

    private static boolean contentFile(String name) {
        return name.startsWith("reader-local/") || name.startsWith("reader-offline/")
                || name.startsWith("library/") && name.substring(name.lastIndexOf('/') + 1).startsWith("media");
    }

    private static long limit(String name) {
        return name.equals(SETTINGS) || name.endsWith(".json") ? MAX_METADATA : MAX_BYTES;
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long size = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("备份操作已取消");
            if (count > limit - size) throw new IOException("备份内容超过允许大小");
            output.write(buffer, 0, count);
            size += count;
        }
        return size;
    }

    static void move(Path from, Path to) throws IOException {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) Files.deleteIfExists(path);
        }
    }
}
