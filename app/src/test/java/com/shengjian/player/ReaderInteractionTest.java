package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.graphics.Bitmap;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.SeekBar;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34, application=Application.class)
public class ReaderInteractionTest {
    private Field field(String name) throws Exception {
        Field field = ReaderSection.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private void call(ReaderSection section, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ReaderSection.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(section, args);
    }
    private <T> T waitFor(ReaderSection section, String key, Class<T> type) throws Exception {
        for (int tries=0; tries<100; tries++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Object view = field(key).get(section);
            if (view != null) return type.cast(view);
            Thread.sleep(15);
        }
        throw new AssertionError(key + " was not ready");
    }
    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(1100, View.MeasureSpec.EXACTLY));
        view.layout(0,0,720,1100);
    }

    private static void swipe(View view, float fromX, float fromY, float toX, float toY) {
        long started = SystemClock.uptimeMillis();
        for (int step = 0; step <= 4; step++) {
            float progress = step / 4f;
            float x = view.getWidth() * (fromX + (toX - fromX) * progress);
            float y = view.getHeight() * (fromY + (toY - fromY) * progress);
            int action = step == 0 ? MotionEvent.ACTION_DOWN
                    : step == 4 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(started, started + 70L * step, action, x, y, 0);
            try {
                view.dispatchTouchEvent(event);
            } finally {
                event.recycle();
            }
        }
    }

    private void openFirstChapter(ReaderSection section, ReaderEngine.Book book, List<ReaderEngine.Chapter> chapters)
            throws Exception {
        field("book").set(section, book);
        field("chapters").set(section, chapters);
        call(section, "openChapter", new Class<?>[]{int.class, boolean.class}, 0, false);
    }
    private RadioButton radio(View root,String label) {
        if (root instanceof RadioButton button && label.contentEquals(button.getText())) return button;
        if (root instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) {
            RadioButton found=radio(group.getChildAt(i),label);
            if (found!=null) return found;
        }
        return null;
    }
    @Test public void novelKeepsViewportAndRestoresEarlierPosition() throws Exception {
        var controller=Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        host.getSharedPreferences("reader_display",0).edit().putInt("novel_page_mode",1).commit();
        ReaderSection section = new ReaderSection(host, false, 37, new ReaderCache(new File(host.getCacheDir(),"qa")), () -> {});
        try {
            host.setContentView(section.view());
            ReaderLocalBooks local = (ReaderLocalBooks) field("localBooks").get(section);
            String bookText = "第一章 开始\n"+"这是一行测试文字。\n".repeat(150)+"第二章 重逢\n下一章正文";
            ReaderEngine.Book book = local.importFile("读者.txt", ReaderSources.NOVEL,
                () -> new ByteArrayInputStream(bookText.getBytes(StandardCharsets.UTF_8)));
            field("book").set(section,book);
            var chapters=local.chapters(book);
            field("chapters").set(section,chapters);
            assertEquals(2,chapters.size());
            call(section,"openChapter",new Class<?>[]{int.class,boolean.class},0,false);
            NovelReadingView reader=waitFor(section,"novelReader",NovelReadingView.class);
            layout(section.view());
            int viewportHeight=reader.getHeight();
            assertTrue(viewportHeight>0);
            int start=reader.position();
            reader.performClick();
            layout(section.view());
            assertEquals(viewportHeight,reader.getHeight());
            assertEquals(start,reader.position());
            reader.performClick();
            call(section,"seekWithUndo",new Class<?>[]{int.class},reader.length()/2);
            assertTrue(reader.position()>start);
            assertTrue(((View)field("undoSeek").get(section)).performClick());
            assertEquals(start,reader.position());
            call(section,"openChapter",new Class<?>[]{int.class,boolean.class},1,false);
            waitFor(section,"novelReader",NovelReadingView.class);
            call(section,"turnAcrossChapter",new Class<?>[]{int.class},-1);
            reader=waitFor(section,"novelReader",NovelReadingView.class);
            assertEquals(0,field("chapterIndex").getInt(section));
            assertEquals(reader.length()-1,reader.position());
        } finally { section.destroy(); controller.pause().stop().destroy(); }
    }
    @Test public void mangaUsesRightToLeftSliderAndUndoReturnsToSamePage() throws Exception {
        var controller=Robolectric.buildActivity(Activity.class).setup();
        Activity host=controller.get();
        ReaderSection section=new ReaderSection(host,true,36,new ReaderCache(new File(host.getCacheDir(),"qa")),() -> {});
        try {
            host.setContentView(section.view());
            ReaderLocalBooks local=(ReaderLocalBooks)field("localBooks").get(section);
            byte[] raster=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jMwkAAAAASUVORK5CYII=");
            var book=local.importImages("漫画", List.of(
                new ReaderLocalBooks.ImageFile("01.png",()->new ByteArrayInputStream(raster)),
                new ReaderLocalBooks.ImageFile("02.png",()->new ByteArrayInputStream(raster)),
                new ReaderLocalBooks.ImageFile("03.png",()->new ByteArrayInputStream(raster))));
            host.getSharedPreferences("reader_display",0).edit().putString("manga_mode_"+ReaderProgress.key(book),"RTL").commit();
            field("book").set(section,book);
            field("chapters").set(section,local.chapters(book));
            call(section,"openChapter",new Class<?>[]{int.class,boolean.class},0,false);
            MangaReadingView reader=waitFor(section,"mangaReader",MangaReadingView.class);
            layout(section.view());
            SeekBar seek=(SeekBar)field("readerSeek").get(section);
            assertEquals(MangaReadingView.Mode.RTL,reader.mode());
            assertEquals(-1f,seek.getScaleX(),0f);
            assertEquals(2,seek.getMax());
            call(section,"seekWithUndo",new Class<?>[]{int.class},2);
            assertEquals(2,reader.page());
            assertTrue(((View)field("undoSeek").get(section)).performClick());
            assertEquals(0,reader.page());
            assertTrue(reader.performClick());
            assertEquals(View.VISIBLE,((View)field("readerTools").get(section)).getVisibility());
            layout(section.view());
            var panel = (ViewGroup)seek.getParent();
            float x = seek.getLeft() + seek.getPaddingLeft() + 1;
            float y = seek.getTop() + seek.getHeight()/2f;
            for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}) {
                var event = android.view.MotionEvent.obtain(0,50,action,x,y,0);
                panel.dispatchTouchEvent(event);
                event.recycle();
            }
            assertEquals("touch at left end in RTL",2,reader.page());
            call(section,"showReadingSettings",new Class<?>[]{});
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            RadioButton ltr=radio(dialog.getWindow().getDecorView(),MangaReadingView.Mode.LTR.label);
            assertNotNull(ltr);
            ltr.performClick();
            assertEquals(MangaReadingView.Mode.LTR,reader.mode());
            assertEquals(1f,seek.getScaleX(),0f);
            assertEquals("LTR",host.getSharedPreferences("reader_display",0).getString("manga_mode_"+ReaderProgress.key(book),""));
            dialog.dismiss();
        } finally { section.destroy(); controller.pause().stop().destroy(); }
    }
    @Test public void failedMangaImageCanOpenToolsAndRetryIndependently() {
        var controller=Robolectric.buildActivity(Activity.class).setup();
        int[] requests={0}, menus={0};
        MangaReadingView reader=new MangaReadingView(controller.get(), List.of("page"),MangaReadingView.Mode.LTR,0,0,
                new MangaReadingView.ImageLoader() {
                    public void load(String name,int width,int height,MangaReadingView.ImageResult result) {
                        requests[0]++;
                        result.onResult(null);
                    }
                    public void prefetch(String name,int width,int height) {}
                },new MangaReadingView.Callback() {
                    public void onTapCenter() { menus[0]++; }
                    public void onPosition(int page,int offset) {}
                });
        try {
            controller.get().setContentView(reader);
            layout(reader);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(reader.hasFailedImage());
            int before=requests[0];
            assertTrue(reader.performClick());
            assertEquals(1,menus[0]);
            assertEquals(before,requests[0]);
            assertTrue(reader.performAccessibilityAction(R.id.action_reader_retry_image,null));
            assertEquals(before+1,requests[0]);
        } finally { reader.dispose(); controller.pause().stop().destroy(); }
    }

    @Test public void scrollingNovelContinuesFromTheEndOfChapter() throws Exception {
        assertNovelContinues(false);
    }

    @Test public void pagedNovelContinuesFromTheLastPageOfChapter() throws Exception {
        assertNovelContinues(true);
    }

    private void assertNovelContinues(boolean paged) throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        host.getSharedPreferences("reader_display", 0).edit().putInt("novel_page_mode", paged ? 1 : 0).commit();
        ReaderSection section = new ReaderSection(host, false, 37, new ReaderCache(new File(host.getCacheDir(), "qa")), () -> {});
        try {
            host.setContentView(section.view());
            ReaderLocalBooks local = (ReaderLocalBooks) field("localBooks").get(section);
            String text = "第一章 开始\n" + "这是一行测试文字。\n".repeat(180)
                    + "第二章 重逢\n下一章正文\n第三章 再见\n最后一章正文";
            ReaderEngine.Book book = local.importFile("续读.txt", ReaderSources.NOVEL,
                    () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
            List<ReaderEngine.Chapter> chapters = local.chapters(book);
            assertEquals(3, chapters.size());
            openFirstChapter(section, book, chapters);
            NovelReadingView first = waitFor(section, "novelReader", NovelReadingView.class);
            layout(section.view());

            if (paged) swipe(first, .86f, .50f, .16f, .50f);
            else swipe(first, .50f, .86f, .50f, .16f);
            assertEquals("a gesture within the chapter should stay in that chapter", 0, field("chapterIndex").getInt(section));
            first.jumpTo(first.length() - 1);
            layout(section.view());
            assertEquals("jumping to the end should still leave it available to read", 0, field("chapterIndex").getInt(section));

            if (paged) swipe(first, .86f, .50f, .16f, .50f);
            else swipe(first, .50f, .86f, .50f, .16f);
            assertEquals(1, field("chapterIndex").getInt(section));
            NovelReadingView next = waitFor(section, "novelReader", NovelReadingView.class);
            assertNotSame(first, next);
            assertEquals(0, next.position());

            first.turn(1);
            assertEquals("a callback from the previous chapter must not advance the new reader",
                    1, field("chapterIndex").getInt(section));
        } finally {
            section.destroy();
            controller.pause().stop().destroy();
        }
    }

    @Test public void verticalMangaContinuesFromTheEndOfChapter() throws Exception {
        assertMangaContinues(MangaReadingView.Mode.VERTICAL);
    }

    @Test public void rightToLeftMangaContinuesFromTheLastPageOfChapter() throws Exception {
        assertMangaContinues(MangaReadingView.Mode.RTL);
    }

    @Test public void mangaDragCanReachAndCrossTheBoundaryWithoutLifting() {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Bitmap image = Bitmap.createBitmap(40, 90, Bitmap.Config.ARGB_8888);
        int[] crossed = {0};
        MangaReadingView reader = new MangaReadingView(controller.get(), List.of("page"), MangaReadingView.Mode.VERTICAL,
                0, 0, new MangaReadingView.ImageLoader() {
                    @Override public void load(String name, int width, int height, MangaReadingView.ImageResult result) {
                        result.onResult(image);
                    }

                    @Override public void prefetch(String name, int width, int height) { }
                }, new MangaReadingView.Callback() {
                    @Override public void onTapCenter() { }
                    @Override public void onPosition(int page, int offset) { }
                    @Override public boolean hasAdjacentChapter(int direction) { return direction > 0; }
                    @Override public void onChapterBoundary(int direction) { crossed[0] += direction; }
                });
        try {
            controller.get().setContentView(reader);
            layout(reader);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            reader.restorePosition(0, 1_000_000);
            int end = reader.offset();
            assertTrue(end > 100_000);
            reader.restorePosition(0, end - 75_000);
            assertEquals(0, crossed[0]);
            assertTrue(reader.offset() < end);
            swipe(reader, .50f, .84f, .50f, .14f);
            assertEquals(1, crossed[0]);
        } finally {
            reader.dispose();
            image.recycle();
            controller.pause().stop().destroy();
        }
    }

    @Test public void offlineReadingFollowsTheSavedNextChapterWithoutSkippingMissingChapters() throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        host.getSharedPreferences("reader_display", 0).edit().putInt("novel_page_mode", 1).commit();
        ReaderCache cache = new ReaderCache(new File(host.getCacheDir(), "offline-chapter-order"));
        ReaderSection section = new ReaderSection(host, false, 37, cache, () -> {});
        try {
            host.setContentView(section.view());
            ReaderSources.Source source = ReaderSources.offlineSource(ReaderSources.NOVEL,
                    ReaderSources.NOVEL + ":https://fiction.example", "离线来源");
            ReaderEngine.Book book = new ReaderEngine.Book(source, "缓存小说", "", "", "",
                    "https://fiction.example/book", "https://fiction.example/book/chapters");
            ReaderEngine.Chapter first = new ReaderEngine.Chapter(book, "第 1 章", book.url + "/1");
            ReaderEngine.Chapter second = new ReaderEngine.Chapter(book, "第 2 章", book.url + "/2");
            ReaderEngine.Chapter later = new ReaderEngine.Chapter(book, "第 4 章", book.url + "/4");
            first.nextChapterUrl = second.url;
            second.nextChapterUrl = book.url + "/3";
            for (ReaderEngine.Chapter chapter : List.of(second, later, first)) {
                cache.download(chapter, new ReaderEngine.Page(chapter.title + "\n测试正文", List.of(), chapter.url), null);
            }
            List<ReaderCache.SavedDownload> cached = cache.savedDownloads(List.of());
            assertEquals(3, cached.size());
            List<ReaderCache.SavedDownload> downloads = List.of(findDownload(cached, second.url),
                    findDownload(cached, later.url), findDownload(cached, first.url));
            call(section, "openSavedDownload", new Class<?>[]{ReaderEngine.Chapter.class, List.class},
                    downloads.get(2).chapter, downloads);
            NovelReadingView reader = waitFor(section, "novelReader", NovelReadingView.class);
            layout(section.view());
            swipe(reader, .86f, .50f, .16f, .50f);
            reader = waitFor(section, "novelReader", NovelReadingView.class);
            assertEquals(second.url, ((ReaderEngine.Page) field("loadedPage").get(section)).url);

            layout(section.view());
            swipe(reader, .86f, .50f, .16f, .50f);
            assertSame(reader, field("novelReader").get(section));
            assertEquals(second.url, ((ReaderEngine.Page) field("loadedPage").get(section)).url);
        } finally {
            section.destroy();
            controller.pause().stop().destroy();
        }
    }

    private ReaderCache.SavedDownload findDownload(List<ReaderCache.SavedDownload> downloads, String url) {
        return downloads.stream().filter(item -> item.chapter.url.equals(url)).findFirst().orElseThrow();
    }

    private void assertMangaContinues(MangaReadingView.Mode mode) throws Exception {
        var controller = Robolectric.buildActivity(Activity.class).setup();
        Activity host = controller.get();
        ReaderSection section = new ReaderSection(host, true, 36, new ReaderCache(new File(host.getCacheDir(), "qa")), () -> {});
        try {
            host.setContentView(section.view());
            ReaderLocalBooks local = (ReaderLocalBooks) field("localBooks").get(section);
            // A solid 40 by 90 image gives the long-strip reader room to scroll within the last page.
            byte[] raster = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAACgAAABaCAAAAADvf0t7AAAAJUlEQVR42u3KsQAAAAACsNDyp0oh"
                    + "gO1eeoooiqIoiqIoiqIoPgbeg4Qurl2iDgAAAABJRU5ErkJggg==");
            ReaderEngine.Book book = local.importImages("漫画续读", List.of(
                    new ReaderLocalBooks.ImageFile("01.png", () -> new ByteArrayInputStream(raster)),
                    new ReaderLocalBooks.ImageFile("02.png", () -> new ByteArrayInputStream(raster))));
            host.getSharedPreferences("reader_display", 0).edit()
                    .putString("manga_mode_" + ReaderProgress.key(book), mode.name()).commit();
            List<ReaderEngine.Chapter> chapters = List.of(
                    new ReaderEngine.Chapter(book, "第一话", book.url + "/0"),
                    new ReaderEngine.Chapter(book, "第二话", book.url + "/1"),
                    new ReaderEngine.Chapter(book, "第三话", book.url + "/2"));
            openFirstChapter(section, book, chapters);
            MangaReadingView first = waitFor(section, "mangaReader", MangaReadingView.class);
            layout(section.view());

            if (mode == MangaReadingView.Mode.RTL) swipe(first, .14f, .50f, .84f, .50f);
            else swipe(first, .50f, .84f, .50f, .14f);
            assertEquals(0, field("chapterIndex").getInt(section));
            first.restorePosition(1, 1_000_000);
            assertEquals(1, first.page());
            assertEquals("seeking to the final image should not skip it", 0, field("chapterIndex").getInt(section));

            if (mode == MangaReadingView.Mode.RTL) swipe(first, .14f, .50f, .84f, .50f);
            else swipe(first, .50f, .84f, .50f, .14f);
            assertEquals(1, field("chapterIndex").getInt(section));
            MangaReadingView next = waitFor(section, "mangaReader", MangaReadingView.class);
            assertNotSame(first, next);
            assertEquals(0, next.page());

            if (mode == MangaReadingView.Mode.RTL) swipe(first, .14f, .50f, .84f, .50f);
            else swipe(first, .50f, .84f, .50f, .14f);
            assertEquals(1, field("chapterIndex").getInt(section));
        } finally {
            section.destroy();
            controller.pause().stop().destroy();
        }
    }
}
