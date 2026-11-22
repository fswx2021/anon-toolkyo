package com.shengjian.player;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ReaderHistoryTest {
    @Test public void legacyHistoryIsSortedAndClearingPreservesResumeInfo() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        ReaderProgress novels = new ReaderProgress(context, false);
        ReaderProgress comics = new ReaderProgress(context, true);
        ReaderEngine.Chapter old = chapter("旧小说", ReaderSources.NOVEL);
        ReaderEngine.Chapter recent = chapter("新小说", ReaderSources.NOVEL);
        ReaderEngine.Chapter comic = chapter("漫画", ReaderSources.COMIC);
        ReaderProgress.Position saved = new ReaderProgress.Position(0, 18, ReaderProgress.TEXT_OFFSET);

        novels.mark(old, saved);
        novels.mark(recent, saved);
        comics.mark(comic, saved);
        var storage = context.getSharedPreferences("novel_progress", Context.MODE_PRIVATE);
        for (var chapter : List.of(recent, old)) {
            String key = ReaderProgress.key(chapter.book);
            JSONObject legacy = new JSONObject(storage.getString(key, ""));
            legacy.remove("history_time");
            legacy.put("time", chapter == old ? 1234 : 5678);
            storage.edit().putString(key, legacy.toString()).apply();
        }
        var legacyHistory = novels.history();
        assertEquals(2, legacyHistory.size());
        assertEquals("新小说", legacyHistory.get(0).title);
        assertEquals("旧小说", legacyHistory.get(1).title);
        novels.favorite(recent.book, true);
        assertTrue(novels.addBookmark(recent, saved, "标记段落"));
        novels.hideHistory(novels.get(recent.book));
        assertEquals(1, new ReaderProgress(context, false).history().size());
        novels.favorite(recent.book, false);
        novels.favorite(recent.book, true);
        novels.mark(recent, saved);
        assertEquals(1, novels.history().size());
        novels.clearHistory();
        assertTrue(new ReaderProgress(context, false).history().isEmpty());
        assertTrue(novels.get(recent.book).favorite);
        assertTrue(novels.get(recent.book).position.samePlace(saved));
        assertEquals(1, novels.bookmarks(recent.book).size());
        assertEquals(1, comics.history().size());

        novels.mark(recent, saved, List.of(recent), 0, true);
        assertEquals("新小说", novels.history().get(0).title);
    }

    @Test public void novelHistoryEntryResumesTextOffsetAndReturnsToHistory() throws Exception {
        openHistoryEntry(false);
    }

    @Test public void comicHistoryEntryResumesSavedImageAndReturnsToHistory() throws Exception {
        openHistoryEntry(true);
    }

    private static void openHistoryEntry(boolean manga) throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        var cache = new ReaderCache(new File(host.getCacheDir(), "qa-reader-history"));
        var section = new ReaderSection(host, manga, 37, cache, () -> {});
        try {
            host.setContentView(section.view());
            var books = new ReaderLocalBooks(new File(host.getFilesDir(), "reader-local"));
            ReaderEngine.Book book;
            ReaderProgress.Position position;
            if (manga) {
                byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jMwkAAAAASUVORK5CYII=");
                book = books.importImages("本地漫画", List.of(
                        new ReaderLocalBooks.ImageFile("01.png", () -> new ByteArrayInputStream(image)),
                        new ReaderLocalBooks.ImageFile("02.png", () -> new ByteArrayInputStream(image))));
                position = new ReaderProgress.Position(1, 0, ReaderProgress.IMAGE_FRACTION);
            } else {
                host.getSharedPreferences("reader_display", Context.MODE_PRIVATE).edit().putInt("novel_page_mode", 1).apply();
                byte[] text = ("第一章 开始\n" + "这是用于验证上次阅读位置的正文。\n".repeat(18)).getBytes(StandardCharsets.UTF_8);
                book = books.importFile("小说.txt", ReaderSources.NOVEL, () -> new ByteArrayInputStream(text));
                position = new ReaderProgress.Position(0, 28, ReaderProgress.TEXT_OFFSET);
            }
            ReaderEngine.Chapter chapter = books.chapters(book).get(0);
            new ReaderProgress(host, manga).mark(chapter, position);

            View shortcut = findShortcut(section.view(), manga ? "漫画历史记录" : "小说历史记录");
            assertNotNull(shortcut);
            assertTrue(shortcut.performClick());
            ListView history = find(section.view(), ListView.class);
            assertNotNull(history);
            assertEquals(1, history.getAdapter().getCount());
            assertTrue(history.performItemClick(null, 0, history.getAdapter().getItemId(0)));
            View reader = awaitReader(section.view(), manga ? MangaReadingView.class : NovelReadingView.class);
            if (manga) assertEquals(position.page, ((MangaReadingView) reader).page());
            else assertEquals(position.offset, ((NovelReadingView) reader).position());
            assertTrue(section.onBackPressed());
            assertTrue(section.onBackPressed());
            assertEquals(1, find(section.view(), ListView.class).getAdapter().getCount());
            assertTrue(section.onBackPressed());
            assertNotNull(findShortcut(section.view(), manga ? "漫画历史记录" : "小说历史记录"));
        } finally {
            section.destroy();
            controller.pause().stop().destroy();
        }
    }

    private static ReaderEngine.Chapter chapter(String title, int kind) {
        var source = ReaderSources.offlineSource(kind, kind + ":fixture", "示例来源");
        var book = new ReaderEngine.Book(source, title, "", "", "", "https://example.invalid/" + title, "");
        return new ReaderEngine.Chapter(book, "第一章", book.url + "/1");
    }

    private static View findShortcut(View view, String description) {
        if (description.contentEquals(view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findShortcut(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) {
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static View awaitReader(View root, Class<? extends View> type) throws InterruptedException {
        for (int tries = 0; tries < 100; tries++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            View reader = find(root, type);
            if (reader != null) return reader;
            Thread.sleep(15);
        }
        throw new AssertionError(type.getSimpleName() + " was not ready");
    }
}
