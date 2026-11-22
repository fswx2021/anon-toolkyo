package com.shengjian.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReaderProgress {
    static final int PIXELS = 0;
    static final int TEXT_OFFSET = 1;
    static final int IMAGE_FRACTION = 2;
    private static final String SHELF_GROUPS = "__shelf_groups";
    private static final String HISTORY_TIME = "history_time";

    static final class Position {
        final int page;
        final int offset;
        final int format;

        Position(int page, int offset, int format) {
            this.page = Math.max(0, page);
            this.offset = offset;
            this.format = format;
        }

        Position(JSONObject json) {
            this(json.optInt("page"), json.optInt("offset"), json.optInt("position_format"));
        }

        void write(JSONObject json) throws JSONException {
            json.put("page", page);
            json.put("offset", offset);
            json.put("position_format", format);
        }

        boolean samePlace(Position other) {
            return page == other.page && offset == other.offset && format == other.format;
        }
    }

    static final class Bookmark {
        final String chapterUrl;
        final String title;
        final String label;
        final Position position;

        Bookmark(JSONObject json) {
            chapterUrl = json.optString("chapter");
            title = json.optString("title");
            label = json.optString("label");
            position = new Position(json);
        }
    }

    static final class Entry {
        final String sourceId;
        final String title;
        final String author;
        final String summary;
        final String cover;
        final String bookUrl;
        final String tocUrl;
        final String chapterUrl;
        final String chapterTitle;
        final boolean favorite;
        final Position position;
        final long time;
        final long historyTime;
        final String group;
        final String latestUrl, latestTitle;
        final int unread, newChapters;

        Entry(JSONObject json) {
            sourceId = json.optString("source");
            title = json.optString("title");
            author = json.optString("author");
            summary = json.optString("summary");
            cover = json.optString("cover");
            bookUrl = json.optString("book");
            tocUrl = json.optString("toc");
            chapterUrl = json.optString("chapter");
            chapterTitle = json.optString("chapter_title");
            favorite = json.optBoolean("favorite");
            position = new Position(json);
            time = json.optLong("time");
            historyTime = json.optLong(HISTORY_TIME, chapterUrl.isEmpty() ? 0 : time);
            group = json.optString("group");
            latestUrl = json.optString("latest_url");
            latestTitle = json.optString("latest_title");
            unread = json.optInt("unread", -1);
            newChapters = json.optInt("new_chapters");
        }

        ReaderEngine.Book toBook(ReaderSources.Source source) {
            return new ReaderEngine.Book(source, title, author, summary, cover, bookUrl, tocUrl);
        }
    }

    private final SharedPreferences prefs;
    private final SharedPreferences bookmarks;

    ReaderProgress(Context context, boolean manga) {
        prefs = context.getSharedPreferences(manga ? "manga_progress" : "novel_progress",
                Context.MODE_PRIVATE);
        bookmarks = context.getSharedPreferences(manga ? "manga_bookmarks" : "novel_bookmarks",
                Context.MODE_PRIVATE);
    }

    static String key(ReaderEngine.Book book) {
        return key(book.source.id, book.url);
    }

    private static String key(String sourceId, String bookUrl) {
        String identity = sourceId.length() + ":" + sourceId + bookUrl;
        return Base64.encodeToString(identity.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    Entry get(ReaderEngine.Book book) {
        return get(key(book));
    }

    Entry get(String key) {
        return parse(prefs.getString(key, ""));
    }

    List<Entry> shelf() {
        List<Entry> entries = new ArrayList<>();
        for (Map.Entry<String, ?> item : prefs.getAll().entrySet()) {
            if (SHELF_GROUPS.equals(item.getKey()) || !(item.getValue() instanceof String)) continue;
            Entry entry = parse((String) item.getValue());
            if (entry != null) entries.add(entry);
        }
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.time).reversed());
        return entries;
    }

    List<Entry> history() {
        List<Entry> entries = shelf();
        entries.removeIf(entry -> entry.chapterUrl.isEmpty() || entry.historyTime <= 0);
        entries.sort(Comparator.comparingLong((Entry entry) -> entry.historyTime).reversed());
        return entries;
    }

    synchronized void hideHistory(Entry entry) {
        SharedPreferences.Editor edit = prefs.edit();
        hideHistory(edit, entry);
        edit.apply();
    }

    synchronized void clearHistory() {
        SharedPreferences.Editor edit = prefs.edit();
        for (Entry entry : history()) hideHistory(edit, entry);
        edit.apply();
    }

    private void hideHistory(SharedPreferences.Editor edit, Entry entry) {
        String identity = key(entry.sourceId, entry.bookUrl);
        JSONObject row = object(prefs.getString(identity, ""));
        if (row.optString("chapter").isEmpty()) return;
        put(row, HISTORY_TIME, 0);
        edit.putString(identity, row.toString());
    }

    synchronized void favorite(ReaderEngine.Book book, boolean favorite) {
        Entry old = get(book);
        save(book, old == null ? "" : old.chapterUrl, old == null ? "" : old.chapterTitle,
                old == null ? new Position(0, 0, PIXELS) : old.position, favorite,
                old == null ? System.currentTimeMillis() : old.time);
    }

    synchronized void remove(ReaderEngine.Book book) {
        String identity = key(book);
        prefs.edit().remove(identity).apply();
        bookmarks.edit().remove(identity).apply();
    }

    void mark(ReaderEngine.Chapter chapter, Position position) {
        mark(chapter, position, null, -1);
    }

    void mark(ReaderEngine.Chapter chapter, Position position, List<ReaderEngine.Chapter> catalogue, int index) {
        mark(chapter, position, catalogue, index, false);
    }

    synchronized void mark(ReaderEngine.Chapter chapter, Position position, List<ReaderEngine.Chapter> catalogue,
                           int index, boolean opened) {
        Entry old = get(chapter.book);
        if (opened || old == null || !chapter.url.equals(old.chapterUrl) || !position.samePlace(old.position)) {
            boolean visible = opened || old == null || old.chapterUrl.isEmpty() || old.historyTime > 0;
            save(chapter.book, chapter.url, chapter.title, position,
                    old != null && old.favorite, System.currentTimeMillis(), null, visible);
        }
        if (old == null || old.latestUrl.isEmpty()) return;
        boolean latest = chapter.url.equals(old.latestUrl);
        int unread = latest ? 0 : catalogue != null && index >= 0 && index < catalogue.size()
                && catalogue.get(catalogue.size() - 1).url.equals(old.latestUrl) ? catalogue.size() - index - 1 : old.unread;
        int fresh = latest ? 0 : old.newChapters;
        if (unread == old.unread && fresh == old.newChapters) return;
        JSONObject json = object(prefs.getString(key(chapter.book), ""));
        put(json, "unread", unread);
        put(json, "new_chapters", fresh);
        prefs.edit().putString(key(chapter.book), json.toString()).apply();
    }

    private void save(ReaderEngine.Book book, String chapterUrl, String chapterTitle,
                      Position position, boolean favorite, long time) {
        save(book, chapterUrl, chapterTitle, position, favorite, time, null);
    }

    private void save(ReaderEngine.Book book, String chapterUrl, String chapterTitle,
                      Position position, boolean favorite, long time, String group) {
        save(book, chapterUrl, chapterTitle, position, favorite, time, group, false);
    }

    private void save(ReaderEngine.Book book, String chapterUrl, String chapterTitle,
                      Position position, boolean favorite, long time, String group, boolean updateHistory) {
        String key = key(book);
        JSONObject json = object(prefs.getString(key, ""));
        if (group != null) put(json, "group", group);
        if (chapterUrl.isEmpty() && !favorite && json.optString("group").isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            json.put("source", book.source.id);
            json.put("book", book.url);
            json.put("title", book.title);
            json.put("author", book.author);
            json.put("summary", book.summary);
            json.put("cover", book.cover);
            json.put("toc", book.tocUrl);
            json.put("chapter", chapterUrl);
            json.put("chapter_title", chapterTitle);
            json.put("favorite", favorite);
            position.write(json);
            json.put("time", time);
            if (updateHistory) json.put(HISTORY_TIME, time);
            prefs.edit().putString(key, json.toString()).apply();
        } catch (JSONException exception) {
            throw new IllegalStateException("Invalid reader progress", exception);
        }
    }

    Map<String, String> groups() {
        JSONObject names = object(prefs.getString(SHELF_GROUPS, ""));
        Map<String, String> result = new LinkedHashMap<>();
        names.keys().forEachRemaining(id -> {
            String name = names.optString(id).trim();
            if (!name.isEmpty()) result.put(id, name);
        });
        return result;
    }

    synchronized String saveGroup(String id, String label) {
        String name = Normalizer.normalize(label, Normalizer.Form.NFKC).trim();
        if (name.isEmpty() || name.length() > 32) throw new IllegalArgumentException("请输入 1 到 32 个字的分组名称");
        Map<String, String> names = groups();
        if (id != null && !names.containsKey(id)) throw new IllegalArgumentException("这个分组已移除");
        if (id == null && names.size() >= 64) throw new IllegalArgumentException("已达到 64 个分组上限");
        for (Map.Entry<String, String> existing : names.entrySet()) {
            if (!existing.getKey().equals(id) && existing.getValue().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("已有同名分组");
            }
        }
        String selected = id == null ? UUID.randomUUID().toString() : id;
        names.put(selected, name);
        prefs.edit().putString(SHELF_GROUPS, new JSONObject(names).toString()).apply();
        return selected;
    }

    synchronized void deleteGroup(String id) {
        Map<String, String> names = groups();
        if (names.remove(id) == null) return;
        SharedPreferences.Editor edit = prefs.edit().putString(SHELF_GROUPS, new JSONObject(names).toString());
        for (Entry book : shelf()) {
            if (book.group.equals(id)) writeMembership(edit, key(book.sourceId, book.bookUrl), "");
        }
        edit.apply();
    }

    synchronized void setGroup(Entry book, String id) {
        if (!id.isEmpty() && !groups().containsKey(id)) return;
        SharedPreferences.Editor edit = prefs.edit();
        writeMembership(edit, key(book.sourceId, book.bookUrl), id);
        edit.apply();
    }

    synchronized void copyGroup(Entry from, ReaderEngine.Book to) {
        if (from == null || from.group.isEmpty() || !groups().containsKey(from.group)) return;
        Entry destination = get(to);
        if (destination == null) save(to, "", "", new Position(0, 0, TEXT_OFFSET), false,
                System.currentTimeMillis(), from.group);
        else if (destination.group.isEmpty()) setGroup(destination, from.group);
    }

    private void writeMembership(SharedPreferences.Editor edit, String key, String id) {
        JSONObject row = object(prefs.getString(key, ""));
        if (!row.has("source")) return;
        if (id.isEmpty()) row.remove("group");
        else put(row, "group", id);
        edit.putString(key, row.toString());
    }

    synchronized boolean recordCatalogue(ReaderEngine.Book book, String cachedLatest, List<ReaderEngine.Chapter> catalogue) {
        Entry old = get(book);
        if (old == null || !old.favorite || catalogue.isEmpty()) return false;
        int latest = catalogue.size() - 1;
        ReaderEngine.Chapter newest = catalogue.get(latest);
        String baseline = old.latestUrl.isEmpty() ? cachedLatest : old.latestUrl;
        int known = chapterIndex(catalogue, baseline);
        int read = chapterIndex(catalogue, old.chapterUrl);
        int added = known >= 0 ? latest - known : 0;
        int unread = read >= 0 ? latest - read : -1;
        JSONObject row = object(prefs.getString(key(book), ""));
        put(row, "latest_url", newest.url);
        put(row, "latest_title", newest.title);
        put(row, "unread", unread);
        int previouslyNew = known >= 0 ? old.newChapters : 0;
        put(row, "new_chapters", unread == 0 ? 0 : (int) Math.min(latest, (long) previouslyNew + added));
        prefs.edit().putString(key(book), row.toString()).apply();
        return added > 0;
    }

    private static int chapterIndex(List<ReaderEngine.Chapter> catalogue, String url) {
        if (url.isEmpty()) return -1;
        for (int index = catalogue.size() - 1; index >= 0; index--) {
            if (catalogue.get(index).url.equals(url)) return index;
        }
        return -1;
    }

    private static JSONObject object(String json) {
        try { return new JSONObject(json); }
        catch (JSONException invalid) { return new JSONObject(); }
    }

    private static void put(JSONObject json, String key, Object value) {
        try { json.put(key, value); }
        catch (JSONException invalid) { throw new IllegalStateException("Invalid shelf data", invalid); }
    }

    List<Bookmark> bookmarks(ReaderEngine.Book book) {
        List<Bookmark> result = new ArrayList<>();
        JSONArray array = getBookmarks(book);
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null) result.add(new Bookmark(item));
        }
        return result;
    }

    boolean addBookmark(ReaderEngine.Chapter chapter, Position position, String label) {
        JSONArray stored = getBookmarks(chapter.book);
        for (int i = 0; i < stored.length(); i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item == null) continue;
            Bookmark bookmark = new Bookmark(item);
            if (chapter.url.equals(bookmark.chapterUrl) && position.samePlace(bookmark.position)) return false;
        }
        JSONObject item = new JSONObject();
        try {
            item.put("chapter", chapter.url);
            item.put("title", chapter.title);
            item.put("label", label);
            position.write(item);
        } catch (JSONException exception) {
            throw new IllegalStateException("Invalid reader bookmark", exception);
        }
        JSONArray updated = new JSONArray().put(item);
        for (int i = 0; i < stored.length() && i < 499; i++) updated.put(stored.opt(i));
        bookmarks.edit().putString(key(chapter.book), updated.toString()).apply();
        return true;
    }

    void removeBookmark(ReaderEngine.Book book, Bookmark bookmark) {
        JSONArray stored = getBookmarks(book);
        for (int i = 0; i < stored.length(); i++) {
            JSONObject item = stored.optJSONObject(i);
            if (item == null) continue;
            Bookmark candidate = new Bookmark(item);
            if (bookmark.chapterUrl.equals(candidate.chapterUrl) && bookmark.position.samePlace(candidate.position)) {
                stored.remove(i);
                break;
            }
        }
        bookmarks.edit().putString(key(book), stored.toString()).apply();
    }

    private JSONArray getBookmarks(ReaderEngine.Book book) {
        try {
            return new JSONArray(bookmarks.getString(key(book), "[]"));
        } catch (JSONException ignored) {
            return new JSONArray();
        }
    }

    private Entry parse(String data) {
        try {
            return new Entry(new JSONObject(data));
        } catch (JSONException exception) {
            return null;
        }
    }
}
