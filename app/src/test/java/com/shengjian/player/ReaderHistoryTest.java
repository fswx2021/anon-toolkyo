package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
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
import org.robolectric.shadows.ShadowAlertDialog;

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
        assertEquals(0, legacyHistory.get(0).historyCount);
        assertTrue(legacyHistory.get(0).historyCountPartial);
        assertEquals(5678, legacyHistory.get(0).historyTime);
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
        assertEquals(1, novels.history().get(0).historyCount);
        assertFalse(novels.history().get(0).historyCountPartial);
    }

    @Test public void progressAndChapterChangesPreserveTheLastVisitAndCount() throws Exception {
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        ReaderProgress novels = new ReaderProgress(context, false);
        ReaderEngine.Chapter first = chapter("已记录的小说", ReaderSources.NOVEL);
        ReaderEngine.Chapter next = new ReaderEngine.Chapter(first.book, "第二章", first.book.url + "/2");
        List<ReaderEngine.Chapter> catalogue = List.of(first, next);
        ReaderProgress.Position position = new ReaderProgress.Position(0, 14, ReaderProgress.TEXT_OFFSET);
        novels.mark(first, position, catalogue, 0, true);
        ReaderProgress.Entry opened = novels.get(first.book);
        assertEquals(1, opened.historyCount);
        assertFalse(opened.historyCountPartial);

        var storage = context.getSharedPreferences("novel_progress", Context.MODE_PRIVATE);
        String key = ReaderProgress.key(first.book);
        JSONObject elapsed = new JSONObject(storage.getString(key, ""));
        elapsed.put("history_time", 1234);
        storage.edit().putString(key, elapsed.toString()).apply();
        novels.mark(next, new ReaderProgress.Position(0, 31, ReaderProgress.TEXT_OFFSET), catalogue, 1);
        novels.favorite(first.book, true);
        ReaderProgress.Entry advanced = new ReaderProgress(context, false).get(first.book);
        assertEquals(1, advanced.historyCount);
        assertEquals(1234, advanced.historyTime);
        assertEquals(next.url, advanced.chapterUrl);

        novels.mark(next, advanced.position, catalogue, 1, true);
        ReaderProgress.Entry reopened = novels.history().get(0);
        assertEquals(2, reopened.historyCount);
        assertFalse(reopened.historyCountPartial);
        assertTrue(reopened.historyTime > advanced.historyTime);
    }

    @Test public void novelHistoryEntryResumesTextOffsetAndReturnsToHistory() throws Exception {
        openHistoryEntry(false);
    }

    @Test public void comicHistoryEntryResumesSavedImageAndReturnsToHistory() throws Exception {
        openHistoryEntry(true);
    }

    @Test public void mangaGridCanRemoveAndRestoreAnImportedBook() throws Exception {
        removeBookFromShelf(true);
    }

    @Test public void novelListCanRemoveAndRestoreAnImportedBook() throws Exception {
        removeBookFromShelf(false);
    }

    private static void removeBookFromShelf(boolean manga) throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        ReaderSection section = null;
        try {
            host.getSharedPreferences("reader_display", Context.MODE_PRIVATE).edit()
                    .putBoolean(manga ? "shelf_manga_grid" : "shelf_novel_grid", manga).apply();
            var books = new ReaderLocalBooks(new File(host.getFilesDir(), "reader-local"));
            ReaderEngine.Book book;
            if (manga) {
                byte[] image = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jMwkAAAAASUVORK5CYII=");
                book = books.importImages("漫画书架作品", List.of(
                        new ReaderLocalBooks.ImageFile("01.png", () -> new ByteArrayInputStream(image))));
            } else {
                byte[] data = "第一章 开头\n这是本地书架回归测试正文。".getBytes(StandardCharsets.UTF_8);
                book = books.importFile("小说书架作品.txt", ReaderSources.NOVEL, () -> new ByteArrayInputStream(data));
            }
            var progress = new ReaderProgress(host, manga);
            ReaderEngine.Chapter chapter = books.chapters(book).get(0);
            var position = new ReaderProgress.Position(0, 5, ReaderProgress.TEXT_OFFSET);
            progress.favorite(book, true);
            progress.mark(chapter, position, List.of(chapter), 0, true);
            assertTrue(progress.addBookmark(chapter, position, "保留书签"));

            section = new ReaderSection(host, manga, 37,
                    new ReaderCache(new File(host.getCacheDir(), "qa-reader-shelf")), () -> {});
            host.setContentView(section.view());
            ListView shelf = find(section.view(), ListView.class);
            assertNotNull(shelf);
            View row = shelf.getAdapter().getView(shelf.getHeaderViewsCount(), null, shelf);
            View menu = findShortcut(row, "管理「" + book.title + "」");
            assertNotNull(menu);
            assertTrue(menu.performClick());
            chooseDialogItem(1);
            AlertDialog confirmation = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(confirmation);
            assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            Shadows.shadowOf(Looper.getMainLooper()).idle();

            var reopened = new ReaderProgress(host, manga);
            assertTrue(reopened.shelf().isEmpty());
            assertNull(find(section.view(), ListView.class));
            assertEquals(1, reopened.history().size());
            assertTrue(reopened.get(book).position.samePlace(position));
            assertEquals(1, reopened.get(book).historyCount);
            assertEquals(1, reopened.bookmarks(book).size());
            assertFalse(books.chapters(book).isEmpty());
            reopened.mark(chapter, new ReaderProgress.Position(0, 9, ReaderProgress.TEXT_OFFSET));
            assertTrue(reopened.shelf().isEmpty());

            View manage = findShortcut(section.view(), "管理书架");
            assertNotNull(manage);
            assertTrue(manage.performClick());
            chooseDialogItem(5);
            chooseDialogItem(0);
            assertEquals(book.title, reopened.shelf().get(0).title);
            assertTrue(reopened.get(book).favorite);
            assertNotNull(find(section.view(), ListView.class));
        } finally {
            if (section != null) section.destroy();
            controller.pause().stop().destroy();
        }
    }

    private static void chooseDialogItem(int index) {
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        ListView choices = dialog.getListView();
        assertNotNull(choices);
        assertTrue(choices.performItemClick(null, index, choices.getAdapter().getItemId(index)));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
            ReaderProgress stats = new ReaderProgress(host, manga);
            ReaderProgress.Entry opened = stats.get(book);
            assertEquals(2, opened.historyCount);
            assertTrue(opened.historyCountPartial);
            section.pause();
            assertEquals(opened.historyCount, stats.get(book).historyCount);
            assertEquals(opened.historyTime, stats.get(book).historyTime);
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
