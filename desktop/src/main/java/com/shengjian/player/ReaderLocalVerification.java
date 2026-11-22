package com.shengjian.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class ReaderLocalVerification {
    private ReaderLocalVerification() {}

    static void verify(Path root) throws Exception {
        ReaderLocalBooks importer = new ReaderLocalBooks(root.resolve("local").toFile());
        ReaderEngine.Book txt = importer.importFile("短篇.txt", ReaderSources.NOVEL, () -> text("第一章\n第一段\n第二章\n第二段"));
        ReaderLocalBooks reopened = new ReaderLocalBooks(root.resolve("local").toFile());
        List<ReaderEngine.Chapter> chapters = reopened.chapters(reopened.book(txt.url, ReaderSources.NOVEL));
        require(chapters.size() == 2 && chapters.get(0).nextChapterUrl.equals(chapters.get(1).url)
                && reopened.open(chapters.get(1)).text.contains("第二段"), "TXT chapter detection and durable reading");

        byte[] epub = archive(Map.of(
                "META-INF/container.xml", "<container><rootfiles><rootfile full-path='OPS/book.opf'/></rootfiles></container>",
                "OPS/book.opf", """
                        <package xmlns:dc="http://purl.org/dc/elements/1.1/">
                        <metadata><dc:title>本地 EPUB</dc:title><dc:creator>作者</dc:creator></metadata>
                        <manifest><item id="b" href="story/second.xhtml"/><item id="a" href="story/first.xhtml"/></manifest>
                        <spine><itemref idref="a"/><itemref idref="b"/></spine></package>
                        """,
                "OPS/story/first.xhtml", "<html><body><h1>壹</h1><p>第一段</p><p>第二段</p><script>不应显示</script></body></html>",
                "OPS/story/second.xhtml", "<html><body><h1>贰</h1><p>备用章节正文</p></body></html>"));
        ReaderEngine.Book novel = importer.importFile("test.epub", ReaderSources.NOVEL, () -> new ByteArrayInputStream(epub));
        chapters = reopened.chapters(novel);
        String body = reopened.open(chapters.get(0)).text;
        require(novel.title.equals("本地 EPUB") && novel.author.equals("作者") && chapters.size() == 2
                && chapters.get(0).title.equals("壹") && chapters.get(1).title.equals("贰")
                && body.contains("第一段\n\n第二段") && !body.contains("不应显示"), "EPUB metadata, spine and paragraphs");

        byte[] cbz = archive(Map.of("pages/10.jpg", "ten", "pages/2.png", "two", "cover.txt", "ignore"));
        ReaderEngine.Book comic = importer.importFile("comic.cbz", ReaderSources.COMIC, () -> new ByteArrayInputStream(cbz));
        verifyComic(reopened, comic);
        ReaderEngine.Book images = importer.importImages("Folder", List.of(
                new ReaderLocalBooks.ImageFile("10.jpg", () -> text("ten")),
                new ReaderLocalBooks.ImageFile("2.png", () -> text("two"))));
        verifyComic(reopened, images);

        try {
            importer.importFile("bad.zip", ReaderSources.COMIC, () -> text("broken"));
            throw new AssertionError("invalid archive imported");
        } catch (IOException expected) {
            try (var files = Files.list(root.resolve("local"))) {
                require(files.noneMatch(file -> file.getFileName().toString().startsWith(".import-")), "failed import leaves no staging files");
            }
        }
        reopened.remove(novel);
        try {
            reopened.book(novel.url, ReaderSources.NOVEL);
            throw new AssertionError("deleted local book was still openable");
        } catch (IOException expected) { }

        ReaderSources store = new ReaderSources(root.resolve("sources.json").toFile(), null);
        ReaderSources.Source source = store.importText("""
                {"bookSourceName":"Fresh source","bookSourceUrl":"https://fixture.invalid","bookSourceType":0,"searchUrl":"/search?q={{key}}"}
                """, ReaderSources.NOVEL).sources.get(0);
        ReaderEngine.Book book = new ReaderEngine.Book(source, "Stored novel", "", "", "",
                "https://fixture.invalid/book", "");
        ReaderEngine.Chapter chapter = new ReaderEngine.Chapter(book, "Cached chapter", "https://fixture.invalid/chapter");
        ReaderCache cache = new ReaderCache(root.resolve("offline").toFile());
        cache.download(chapter, new ReaderEngine.Page("Downloaded text", List.of(), chapter.url), null);
        cache.storeCatalogue(book, List.of(chapter));
        cache.clearTransient();
        ReaderCache restored = new ReaderCache(root.resolve("offline").toFile());
        List<ReaderCache.SavedDownload> downloads = restored.savedDownloads(List.of());
        require(downloads.size() == 1 && downloads.get(0).chapter.book.source.id.equals(source.id)
                && restored.loadChapter(downloads.get(0).chapter).text.equals("Downloaded text")
                && restored.storageBytes()[0] > 0 && restored.storageBytes()[2] == 0,
                "chapter discoverable and readable without source or TOC after transient purge");
        restored.removeDownload(downloads.get(0).chapter);
        require(restored.savedDownloads(List.of()).isEmpty(), "offline deletion removes completed archive");
        System.out.println("PASS local TXT, EPUB, CBZ and image library; independent offline download discovery and removal");
    }

    private static void verifyComic(ReaderLocalBooks store, ReaderEngine.Book book) throws Exception {
        ReaderEngine.Chapter chapter = store.chapters(book).get(0);
        List<String> urls = store.open(chapter).images;
        require(urls.size() == 2 && new String(store.image(chapter, urls.get(0)), StandardCharsets.UTF_8).equals("two")
                && new String(store.image(chapter, urls.get(1)), StandardCharsets.UTF_8).equals("ten"), "numeric comic page ordering");
    }

    private static ByteArrayInputStream text(String contents) {
        return new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] archive(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }
}
