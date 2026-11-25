package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class ReaderSearchGroupingTest {
    @Test public void novelSearchGroupsTheSameTitleAndLetsTheReaderChooseItsSource() throws Exception {
        verifySourceChoice(false);
    }

    @Test public void comicSearchGroupsTheSameTitleAndLetsTheReaderChooseItsSource() throws Exception {
        verifySourceChoice(true);
    }

    @Test public void comicPickerPrefersPpcatWhenOpenedAndKeepsExistingRowsDuringSearch() throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        ReaderSection section = new ReaderSection(host, true, 42,
                new ReaderCache(new File(host.getCacheDir(), "preferred-comic-cache")), () -> {});
        CountDownLatch releasePreferred = new CountDownLatch(1);
        try {
            host.setContentView(section.view());
            ReaderSources sources = (ReaderSources) field(section, "sources");
            var imported = sources.importText(new JSONArray()
                    .put(new JSONObject().put("bookSourceName", "原有图源")
                            .put("bookSourceUrl", "https://legacy-reader.example")
                            .put("bookSourceType", ReaderSources.COMIC)
                            .put("searchUrl", "/search?q={{key}}"))
                    .put(new JSONObject().put("format", "ppcat").put("bookSourceType", "漫画")
                            .put("bookSourceName", "皮皮喵图源").put("bookSourceUrl", "https://ppcat-reader.example")
                            .put("ruleSearchUrl", "/search?q={{key}}").put("ruleSearchList", ".comic")
                            .put("ruleSearchName", ".title@text").put("ruleSearchNoteUrl", "a@href"))
                    .toString(), ReaderSources.COMIC);
            var installed = imported.sources;
            assertEquals(imported.diagnostics.toString(), 2, installed.size());
            var legacy = installed.get(0);
            var preferred = installed.get(1);
            assertTrue(preferred.ppcat());

            var targets = new ArrayList<>(installed);
            Method prioritize = ReaderSection.class.getDeclaredMethod("prioritizeSearchTargets",
                    List.class, long.class);
            prioritize.setAccessible(true);
            prioritize.invoke(section, targets, System.currentTimeMillis());
            assertEquals(List.of(preferred, legacy), targets);

            search(section, targets, source -> {
                if (source.ppcat() && !releasePreferred.await(8, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("preferred fixture was not released");
                }
                return List.of(book(source, "雾港来信", "林溪", "/comic"));
            });
            Object feed = field(section, "searchFeed");
            ListView results = (ListView) field(feed, "results");
            await("waiting for the legacy source's result", () -> results.getCount() == 1);
            click(results, 0);
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            ListView choices = findList(chooser.getWindow().getDecorView());
            assertNotNull(choices);
            assertEquals(1, choices.getCount());
            assertTrue(rowText(choices, 0).contains(legacy.name));

            releasePreferred.countDown();
            await("waiting for the preferred source to appear", () -> choices.getCount() == 2);
            assertTrue(rowText(choices, 0).contains(legacy.name));
            assertTrue(rowText(choices, 1).contains(preferred.name));
            chooser.dismiss();

            click(results, 0);
            ListView reopened = findList(ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView());
            assertNotNull(reopened);
            assertEquals(2, reopened.getCount());
            assertTrue(rowText(reopened, 0).contains(preferred.name));
            assertTrue(rowText(reopened, 1).contains(legacy.name));
        } finally {
            releasePreferred.countDown();
            section.destroy();
            controller.pause().stop().destroy();
        }
    }

    private void verifySourceChoice(boolean manga) throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        int kind = manga ? ReaderSources.COMIC : ReaderSources.NOVEL;
        ReaderCache cache = new ReaderCache(new File(host.getCacheDir(), "search-group-cache"));
        ReaderSection section = new ReaderSection(host, manga, 42, cache, () -> {});
        try {
            host.setContentView(section.view());
            ReaderSources sources = (ReaderSources) field(section, "sources");
            JSONArray declarations = new JSONArray();
            for (int index = 0; index < 3; index++) {
                String origin = "https://reader-group-" + index + ".example";
                declarations.put(new JSONObject()
                        .put("bookSourceName", "测试来源 " + (index + 1))
                        .put("bookSourceUrl", origin)
                        .put("bookSourceType", kind)
                        .put("searchUrl", origin + "/search?q={{key}}"));
            }
            List<ReaderSources.Source> installed = sources.importText(declarations.toString(), kind).sources;
            assertEquals(3, installed.size());
            var first = book(installed.get(0), "《雾港来信》", "林溪", "/edition-1");
            var alternateEdition = book(installed.get(0), "雾港来信", "林溪", "/edition-2");
            var second = book(installed.get(1), "雾港来信", "陆遥", "/edition-3");
            var unknownAuthor = book(installed.get(2), "雾港来信", "", "/edition-4");
            var sequel = book(installed.get(0), "雾港来信 第二部", "林溪", "/sequel");
            String selectedChapter = "来源二独有的目录章节";
            cache.storeCatalogue(second, List.of(new ReaderEngine.Chapter(second, selectedChapter, second.url + "/1")));

            search(section, installed, Map.of(
                    first.source.id, List.of(first, first, alternateEdition, sequel),
                    second.source.id, List.of(second),
                    unknownAuthor.source.id, List.of(unknownAuthor)));
            Object feed = field(section, "searchFeed");
            ListView results = (ListView) field(feed, "results");
            Object attempt = field(field(section, "searchState"), "attempt");
            await("waiting for all three search sources",
                    () -> (int) fieldValue(attempt, "completed") == installed.size());

            assertEquals("qualifiers stay separate, while different or missing authors do not duplicate a title",
                    2, results.getCount());
            assertTrue(rowText(results, 0).contains("3 个来源"));
            assertTrue(rowText(results, 1).contains("雾港来信 第二部"));

            click(results, 1);
            AlertDialog onlySource = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(onlySource);
            ListView singleList = findList(onlySource.getWindow().getDecorView());
            assertNotNull(singleList);
            assertEquals(1, singleList.getCount());
            assertNull("opening a source chooser does not open the work yet", field(section, "book"));
            onlySource.dismiss();

            click(results, 0);
            AlertDialog chooser = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(chooser);
            ListView choices = findList(chooser.getWindow().getDecorView());
            assertNotNull(choices);
            assertEquals("two distinct editions from the same source remain selectable, but duplicate URLs do not",
                    4, choices.getCount());
            int selectedRow = -1;
            for (int index = 0; index < choices.getCount(); index++) {
                List<String> labels = rowText(choices, index);
                if (labels.stream().anyMatch(label -> label.contains(second.source.name))) {
                    assertTrue(labels.stream().anyMatch(label -> label.contains("陆遥")));
                    selectedRow = index;
                }
            }
            assertTrue("the second source is a visible choice", selectedRow >= 0);
            click(choices, selectedRow);
            await("waiting for the selected source's cached catalogue",
                    () -> !((List<?>) fieldValue(section, "chapters")).isEmpty());

            var selectedBook = (ReaderEngine.Book) field(section, "book");
            assertEquals(second.source.id, selectedBook.source.id);
            assertEquals(second.url, selectedBook.url);
            var chapters = (List<?>) field(section, "chapters");
            assertEquals(selectedChapter, ((ReaderEngine.Chapter) chapters.get(0)).title);
        } finally {
            section.destroy();
            controller.pause().stop().destroy();
        }
    }

    private static ReaderEngine.Book book(ReaderSources.Source source, String title, String author, String path) {
        return new ReaderEngine.Book(source, title, author, "", "", source.url + path, source.url + path);
    }

    private static void search(ReaderSection section, List<ReaderSources.Source> sources,
                               Map<String, List<ReaderEngine.Book>> answers) throws Exception {
        search(section, sources, source -> answers.get(source.id));
    }

    private interface SearchAnswer {
        List<ReaderEngine.Book> books(ReaderSources.Source source) throws Exception;
    }

    private static void search(ReaderSection section, List<ReaderSources.Source> sources,
                               SearchAnswer answer) throws Exception {
        Class<?> modeType = Class.forName(ReaderSection.class.getName() + "$SearchMode");
        Class<?> actionType = Class.forName(ReaderSection.class.getName() + "$SearchAction");
        Field modeField = modeType.getDeclaredField("SEARCH");
        modeField.setAccessible(true);
        Object mode = modeField.get(null);
        Object action = Proxy.newProxyInstance(actionType.getClassLoader(), new Class<?>[]{actionType},
                (proxy, method, args) -> answer.books((ReaderSources.Source) args[0]));
        Method search = ReaderSection.class.getDeclaredMethod("showResults", String.class, modeType,
                List.class, actionType, List.class, Map.class, ReaderSearchRanking.class, String.class);
        search.setAccessible(true);
        search.invoke(section, "“雾港来信”", mode, sources, action, List.of(), Map.of(),
                new ReaderSearchRanking("雾港来信", UnaryOperator.identity()), null);
    }

    private static void await(String message, BooleanSupplier condition) throws InterruptedException {
        var looper = Shadows.shadowOf(Looper.getMainLooper());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            looper.idleFor(180, TimeUnit.MILLISECONDS);
            Thread.sleep(15);
        }
        assertTrue(message, condition.getAsBoolean());
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object fieldValue(Object target, String name) {
        try {
            return field(target, name);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(name, error);
        }
    }

    private static void click(ListView list, int position) {
        var adapter = list.getAdapter();
        assertTrue(list.performItemClick(adapter.getView(position, null, list), position, adapter.getItemId(position)));
    }

    private static List<String> rowText(ListView list, int position) {
        List<String> text = new ArrayList<>();
        readText(list.getAdapter().getView(position, null, list), text);
        return text;
    }

    private static void readText(View view, List<String> labels) {
        if (view instanceof TextView label) labels.add(label.getText().toString());
        if (view instanceof ViewGroup group) {
            for (int child = 0; child < group.getChildCount(); child++) readText(group.getChildAt(child), labels);
        }
    }

    private static ListView findList(View view) {
        if (view instanceof ListView list) return list;
        if (view instanceof ViewGroup group) {
            for (int child = 0; child < group.getChildCount(); child++) {
                ListView list = findList(group.getChildAt(child));
                if (list != null) return list;
            }
        }
        return null;
    }
}
