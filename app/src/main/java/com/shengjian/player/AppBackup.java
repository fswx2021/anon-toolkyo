package com.shengjian.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stages a restore, then installs it before the media and reader stores open. */
final class AppBackup {
    private static final String PENDING = ".anon-restore";
    private static final String APPLIED = ".anon-restore-applied";
    private static final String ORIGINAL = ".anon-restore-original";
    private static final String JOURNAL = ".journal";
    private static final Set<String> NAMES = Set.of("app_settings", "player_ui", "playback_settings",
            "music_collections", "manga_progress", "novel_progress", "manga_bookmarks", "novel_bookmarks",
            "reader_display", "anime_library", "anime_playback");

    private AppBackup() {}

    static void export(Context context, Uri uri, boolean content) throws IOException {
        try (OutputStream destination = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (destination == null) throw new IOException("无法打开备份文件");
            BackupArchive.export(context.getFilesDir().toPath(), snapshot(context, content), content, destination);
        }
    }

    static boolean stage(Context context, Uri uri) throws IOException {
        Path root = context.getFilesDir().toPath();
        Path pending = root.resolve(PENDING);
        if (Files.exists(pending)) throw new IOException("已有等待恢复的备份，请先重新打开应用");
        Path stage = Files.createTempDirectory(root, ".restore-import-");
        try (InputStream source = context.getContentResolver().openInputStream(uri)) {
            if (source == null) throw new IOException("无法打开备份文件");
            JSONObject settings = BackupArchive.extract(source, stage);
            verify(stage, settings);
            BackupArchive.move(stage, pending);
            return settings.optBoolean("content");
        } finally {
            BackupArchive.deleteTree(stage);
        }
    }

    static String restoreAtStartup(Context context) {
        Path root = context.getFilesDir().toPath();
        Path pending = root.resolve(PENDING);
        if (!Files.isDirectory(pending)) return "";
        try {
            install(context, root, pending);
            return "备份已恢复";
        } catch (IOException | JSONException | RuntimeException failure) {
            android.util.Log.w("AnonBackup", "Restore failed", failure);
            return "备份恢复未完成，请在设置里重试或取消";
        }
    }

    static boolean hasPending(Context context) {
        return Files.isDirectory(context.getFilesDir().toPath().resolve(PENDING));
    }

    static void cancel(Context context) throws IOException {
        Path root = context.getFilesDir().toPath();
        Path pending = root.resolve(PENDING);
        Path rollback = root.resolve(ORIGINAL);
        if (Files.isRegularFile(pending.resolve(JOURNAL))) {
            try {
                JSONObject journal = new JSONObject(BackupArchive.readText(pending.resolve(JOURNAL)));
                rollBack(context, root, pending, rollback, journal);
            } catch (JSONException invalid) {
                throw new IOException("无法读取恢复记录", invalid);
            }
        }
        BackupArchive.deleteTree(pending);
        BackupArchive.deleteTree(rollback);
    }

    private static void install(Context context, Path root, Path pending) throws IOException, JSONException {
        JSONObject settings = BackupArchive.readSettings(pending);
        JSONObject preferences = settings.getJSONObject("preferences");
        Path rollback = root.resolve(ORIGINAL);
        Path applied = root.resolve(APPLIED);
        BackupArchive.deleteTree(applied);
        JSONObject journal;
        Path journalFile = pending.resolve(JOURNAL);
        if (Files.isRegularFile(journalFile)) {
            journal = new JSONObject(BackupArchive.readText(journalFile));
        } else {
            verify(pending, settings);
            BackupArchive.deleteTree(rollback);
            JSONArray files = new JSONArray();
            try (var paths = Files.walk(pending)) {
                for (var filesIterator = paths.filter(Files::isRegularFile).iterator(); filesIterator.hasNext(); ) {
                    Path file = filesIterator.next();
                    String name = pending.relativize(file).toString().replace('\\', '/');
                    if (!name.equals(BackupArchive.SETTINGS) && BackupArchive.allowed(name)) files.put(name);
                }
            }
            journal = new JSONObject().put("files", files)
                    .put("preferences", snapshot(context, false).getJSONObject("preferences"));
            Path staging = pending.resolve(JOURNAL + ".new");
            Files.write(staging, journal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            BackupArchive.move(staging, journalFile);
        }
        try {
            List<String> plan = journalFiles(journal);
            for (String name : plan) {
                Path file = pending.resolve(name);
                Path target = root.resolve(name);
                Path old = rollback.resolve(name);
                if (!Files.exists(file)) {
                    if (!Files.exists(target)) throw new IOException("恢复时缺少备份文件");
                    continue;
                }
                Files.createDirectories(target.getParent());
                if (Files.exists(target) && !Files.exists(old)) {
                    Files.createDirectories(old.getParent());
                    BackupArchive.move(target, old);
                }
                BackupArchive.move(file, target);
            }
            writePreferences(context, preferences);
            BackupArchive.move(pending, applied);
        } catch (IOException | JSONException | RuntimeException failure) {
            try {
                rollBack(context, root, pending, rollback, journal);
            } catch (IOException | JSONException | RuntimeException rollbackFailed) {
                failure.addSuppressed(rollbackFailed);
            }
            throw failure;
        }
        try {
            BackupArchive.deleteTree(applied);
            BackupArchive.deleteTree(rollback);
        } catch (IOException problem) {
            android.util.Log.w("AnonBackup", "Backup cleanup failed", problem);
        }
    }

    private static List<String> journalFiles(JSONObject journal) throws IOException, JSONException {
        JSONArray items = journal.getJSONArray("files");
        List<String> files = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            String name = items.getString(i);
            if (!BackupArchive.allowed(name) || name.equals(BackupArchive.SETTINGS)) throw new IOException("恢复记录无效");
            files.add(name);
        }
        return files;
    }

    private static void rollBack(Context context, Path root, Path pending, Path rollback, JSONObject journal)
            throws IOException, JSONException {
        List<String> files = journalFiles(journal);
        for (int index = files.size() - 1; index >= 0; index--) {
            String name = files.get(index);
            Path file = pending.resolve(name);
            Path target = root.resolve(name);
            Path old = rollback.resolve(name);
            if (!Files.exists(file) && Files.exists(target)) BackupArchive.move(target, file);
            if (Files.exists(old)) BackupArchive.move(old, target);
        }
        writePreferences(context, journal.getJSONObject("preferences"));
        Files.deleteIfExists(pending.resolve(JOURNAL));
        BackupArchive.deleteTree(rollback);
    }

    private static JSONObject snapshot(Context context, boolean content) throws IOException {
        JSONObject groups = new JSONObject();
        try {
            for (String name : NAMES) {
                JSONObject entries = new JSONObject();
                for (Map.Entry<String, ?> entry : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object value = entry.getValue();
                    String type;
                    if (value instanceof String) type = "string";
                    else if (value instanceof Boolean) type = "bool";
                    else if (value instanceof Integer) type = "int";
                    else if (value instanceof Long) type = "long";
                    else if (value instanceof Float) type = "float";
                    else continue;
                    entries.put(entry.getKey(), new JSONObject().put("type", type).put("value", value));
                }
                groups.put(name, entries);
            }
            return new JSONObject().put("format", 1).put("content", content).put("preferences", groups);
        } catch (JSONException invalid) {
            throw new IOException("无法打包设置", invalid);
        }
    }

    private static void verify(Path stage, JSONObject settings) throws IOException {
        try {
            JSONObject prefs = settings.getJSONObject("preferences");
            var groups = prefs.keys();
            while (groups.hasNext()) {
                String name = groups.next();
                if (!NAMES.contains(name)) throw new IOException("备份含未知设置分组");
                JSONObject entries = prefs.getJSONObject(name);
                var keys = entries.keys();
                while (keys.hasNext()) value(null, "", entries.getJSONObject(keys.next()));
            }
            for (String path : List.of("reader-sources.json", "library/tracks.json")) {
                Path file = stage.resolve(path);
                if (!Files.exists(file)) continue;
                JSONArray rows = new JSONArray(BackupArchive.readText(file));
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    if (path.startsWith("library")) {
                        String id = java.util.UUID.fromString(row.getString("id")).toString();
                        row.getString("title");
                        String media = row.optString("file");
                        if (!media.isEmpty() && !BackupArchive.allowed("library/" + id + "/" + media)) {
                            throw new IOException("备份的曲目文件名无效");
                        }
                    } else {
                        int kind = row.getInt("kind");
                        if (kind != ReaderSources.NOVEL && kind != ReaderSources.COMIC) throw new JSONException("kind");
                        row.getJSONObject("definition").getString("bookSourceUrl");
                    }
                }
            }
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("备份中的资料清单损坏", invalid);
        }
    }

    private static void writePreferences(Context context, JSONObject prefs) throws IOException, JSONException {
        var groups = prefs.keys();
        while (groups.hasNext()) {
            String name = groups.next();
            SharedPreferences.Editor editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear();
            JSONObject entries = prefs.getJSONObject(name);
            var keys = entries.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                value(editor, key, entries.getJSONObject(key));
            }
            if (!editor.commit()) throw new IOException("恢复设置失败");
        }
    }

    private static void value(SharedPreferences.Editor editor, String key, JSONObject entry) throws JSONException {
        Object value = entry.get("value");
        switch (entry.getString("type")) {
            case "string" -> { if (!(value instanceof String)) throw new JSONException("string"); if (editor != null) editor.putString(key, (String) value); }
            case "bool" -> { if (!(value instanceof Boolean)) throw new JSONException("bool"); if (editor != null) editor.putBoolean(key, (Boolean) value); }
            case "int" -> { if (!(value instanceof Number)) throw new JSONException("int"); if (editor != null) editor.putInt(key, ((Number) value).intValue()); }
            case "long" -> { if (!(value instanceof Number)) throw new JSONException("long"); if (editor != null) editor.putLong(key, ((Number) value).longValue()); }
            case "float" -> { if (!(value instanceof Number)) throw new JSONException("float"); if (editor != null) editor.putFloat(key, ((Number) value).floatValue()); }
            default -> throw new JSONException("type");
        }
    }
}
