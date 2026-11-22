package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ReaderLocalBooks {
    private static final String ORIGIN = "https://local.reader.invalid";
    private static final int MAX_NOVEL_INPUT = 32 * 1024 * 1024;
    private static final long MAX_COMIC_INPUT = 512L * 1024 * 1024;
    private static final long MAX_LOCAL_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 18 * 1024 * 1024;
    private static final int MAX_ENTRIES = 5_000;
    private static final int MAX_INDEX_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CHAPTER_TEXT = 90_000;
    private static final Pattern ID = Pattern.compile("[a-f0-9]{32}");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*(?:第[^\\r\\n]{1,22}[章节回卷]|(?:序章|楔子|终章|尾声)|(?:Chapter|Part)\\s+[0-9IVXLCDM]+)[^\\r\\n]{0,65}(?=\\r?$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE = Pattern.compile("(?i).+\\.(?:jpe?g|png|webp|gif|avif|bmp)");

    @FunctionalInterface interface OpenStream { InputStream open() throws IOException; }

    static final class ImageFile {
        final String name;
        final OpenStream open;

        ImageFile(String name, OpenStream open) { this.name = name; this.open = open; }
    }

    private final File root;

    ReaderLocalBooks(File root) { this.root = root; }

    static String sourceId(int kind) { return kind + ":" + ORIGIN; }

    static boolean isSource(String id, int kind) { return sourceId(kind).equals(id); }

    static boolean supportsImage(String name) { return IMAGE.matcher(name).matches(); }

    synchronized ReaderEngine.Book importFile(String name, int kind, OpenStream loader) throws IOException {
        String extension = name.toLowerCase(Locale.ROOT);
        boolean novel = kind == ReaderSources.NOVEL;
        if (novel && !(extension.endsWith(".txt") || extension.endsWith(".epub"))) {
            throw new IOException("请选择 TXT 或 EPUB 小说");
        }
        if (!novel && !(extension.endsWith(".cbz") || extension.endsWith(".zip"))) {
            throw new IOException("请选择 CBZ 或 ZIP 漫画");
        }
        return create(kind, displayTitle(name), (directory, book) -> {
            if (novel && extension.endsWith(".txt")) {
                try (InputStream input = loader.open()) { addPlainText(directory, book, decodeText(read(input, MAX_NOVEL_INPUT))); }
            } else {
                File archive = new File(directory, "book.zip");
                try (InputStream input = loader.open(); OutputStream output = Files.newOutputStream(archive.toPath())) {
                    copy(input, output, novel ? MAX_NOVEL_INPUT : MAX_COMIC_INPUT);
                }
                try (ZipFile zip = new ZipFile(archive)) {
                    if (zip.size() > MAX_ENTRIES) throw new IOException("压缩包内的文件过多");
                    if (novel) addEpub(directory, book, zip);
                    else addComic(book, zip);
                }
                if (novel) Files.delete(archive.toPath());
            }
        });
    }

    synchronized ReaderEngine.Book importImages(String title, List<ImageFile> images) throws IOException {
        if (images.isEmpty() || images.size() > MAX_ENTRIES) throw new IOException("图片目录为空或图片过多");
        List<ImageFile> ordered = new ArrayList<>(images);
        ordered.sort((a, b) -> compareNames(a.name, b.name));
        return create(ReaderSources.COMIC, title, (directory, book) -> {
            File archive = new File(directory, "book.zip");
            JSONArray names = new JSONArray();
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive.toPath()))) {
                long bytes = 0;
                int number = 0;
                for (ImageFile file : ordered) {
                    if (!supportsImage(file.name)) continue;
                    String name = ++number + file.name.substring(file.name.lastIndexOf('.'));
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream stream = file.open.open()) {
                        bytes += copy(stream, zip, Math.min(MAX_IMAGE_BYTES, MAX_COMIC_INPUT - bytes));
                    }
                    zip.closeEntry();
                    names.put(name);
                }
            }
            if (names.length() == 0) throw new IOException("目录中没有支持的漫画图片");
            book.put("images", names);
        });
    }

    ReaderEngine.Book book(String url, int kind) throws IOException {
        return buildBook(url, index(directory(url), kind));
    }

    synchronized void remove(ReaderEngine.Book book) throws IOException {
        File directory = directory(book.url);
        if (!directory.isDirectory()) return;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("本地书籍文件不可读取");
        for (File child : children) Files.deleteIfExists(child.toPath());
        Files.deleteIfExists(directory.toPath());
    }

    List<ReaderEngine.Chapter> chapters(ReaderEngine.Book book) throws IOException {
        JSONObject index = index(directory(book.url), book.source.kind);
        List<ReaderEngine.Chapter> chapters = new ArrayList<>();
        if (book.source.kind == ReaderSources.COMIC) {
            chapters.add(new ReaderEngine.Chapter(book, "全部图片", book.url + "/0"));
            return chapters;
        }
        JSONArray titles = index.optJSONArray("chapters");
        if (titles == null || titles.length() > MAX_ENTRIES) throw new IOException("本地小说目录损坏");
        for (int i = 0; i < titles.length(); i++) {
            ReaderEngine.Chapter chapter = new ReaderEngine.Chapter(book, titles.optString(i, "正文"), book.url + "/" + i);
            if (i + 1 < titles.length()) chapter.nextChapterUrl = book.url + "/" + (i + 1);
            chapters.add(chapter);
        }
        return chapters;
    }

    ReaderEngine.Page open(ReaderEngine.Chapter chapter) throws IOException {
        File directory = directory(chapter.book.url);
        JSONObject index = index(directory, chapter.book.source.kind);
        if (chapter.book.source.kind == ReaderSources.COMIC) {
            JSONArray names = index.optJSONArray("images");
            if (names == null || names.length() == 0 || names.length() > MAX_ENTRIES) throw new IOException("本地漫画清单损坏");
            List<String> urls = new ArrayList<>(names.length());
            for (int i = 0; i < names.length(); i++) urls.add(chapter.book.url + "/image/" + i);
            return new ReaderEngine.Page("", urls, chapter.url);
        }
        int number = indexOf(chapter.url, chapter.book.url + "/");
        JSONArray titles = index.optJSONArray("chapters");
        if (titles == null || number >= titles.length()) throw new IOException("本地章节不存在");
        try (InputStream input = Files.newInputStream(new File(directory, number + ".txt").toPath())) {
            return new ReaderEngine.Page(new String(read(input, MAX_CHAPTER_TEXT * 4), StandardCharsets.UTF_8), List.of(), chapter.url);
        }
    }

    byte[] image(ReaderEngine.Chapter chapter, String url) throws IOException {
        File directory = directory(chapter.book.url);
        int number = indexOf(url, chapter.book.url + "/image/");
        JSONArray names = index(directory, ReaderSources.COMIC).optJSONArray("images");
        String path = names == null ? "" : names.optString(number, "");
        if (path.isEmpty()) throw new IOException("本地图片不存在");
        try (ZipFile zip = new ZipFile(new File(directory, "book.zip"))) {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null || entry.isDirectory() || entry.getSize() <= 0 || entry.getSize() > MAX_IMAGE_BYTES) {
                throw new IOException("本地漫画图片无效或过大");
            }
            try (InputStream input = zip.getInputStream(entry)) { return read(input, MAX_IMAGE_BYTES); }
        }
    }

    private interface ImportOperation { void run(File directory, JSONObject book) throws IOException, JSONException; }

    private ReaderEngine.Book create(int kind, String title, ImportOperation operation) throws IOException {
        Files.createDirectories(root.toPath());
        String id = UUID.randomUUID().toString().replace("-", "");
        File staging = Files.createTempDirectory(root.toPath(), ".import-").toFile();
        File destination = new File(root, id);
        String url = ORIGIN + "/" + id;
        try {
            JSONObject book = new JSONObject().put("kind", kind).put("title", title);
            operation.run(staging, book);
            Files.write(new File(staging, "index.json").toPath(), book.toString().getBytes(StandardCharsets.UTF_8));
            if (size(root) > MAX_LOCAL_BYTES) throw new IOException("本地阅读库已达到 2 GiB 上限");
            interrupted();
            try {
                Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(staging.toPath(), destination.toPath());
            }
            return buildBook(url, book);
        } catch (JSONException | IllegalArgumentException invalid) {
            throw new IOException("本地阅读文件格式无效", invalid);
        } finally {
            File[] partial = staging.listFiles();
            if (partial != null) for (File child : partial) Files.deleteIfExists(child.toPath());
            Files.deleteIfExists(staging.toPath());
        }
    }

    private static long size(File directory) throws IOException {
        File[] files = directory.listFiles();
        long total = 0;
        if (files != null) for (File file : files) {
            interrupted();
            total += file.isDirectory() ? size(file) : file.length();
        }
        return total;
    }

    private File directory(String url) throws IOException {
        if (!url.startsWith(ORIGIN + "/")) throw new IOException("本地书籍地址无效");
        String key = url.substring(ORIGIN.length() + 1);
        if (!ID.matcher(key).matches()) throw new IOException("本地书籍地址无效");
        return new File(root, key);
    }

    private static JSONObject index(File directory, int kind) throws IOException {
        File manifest = new File(directory, "index.json");
        try (InputStream input = Files.newInputStream(manifest.toPath())) {
            JSONObject data = new JSONObject(new String(read(input, MAX_INDEX_BYTES), StandardCharsets.UTF_8));
            if (data.optInt("kind", -1) != kind) throw new IOException("本地书籍类型不匹配");
            return data;
        } catch (JSONException invalid) {
            throw new IOException("本地书籍清单损坏", invalid);
        }
    }

    private static ReaderEngine.Book buildBook(String url, JSONObject index) {
        int kind = index.optInt("kind");
        ReaderSources.Source source = ReaderSources.offlineSource(kind, sourceId(kind), "本地文件");
        return new ReaderEngine.Book(source, index.optString("title", "本地书籍"), index.optString("author"),
                "", "", url, url);
    }

    private static int indexOf(String url, String prefix) throws IOException {
        try {
            if (!url.startsWith(prefix) || url.length() - prefix.length() > 5) throw new NumberFormatException();
            int index = Integer.parseInt(url.substring(prefix.length()));
            if (index >= 0 && index < MAX_ENTRIES) return index;
        } catch (NumberFormatException ignored) { }
        throw new IOException("本地条目地址无效");
    }

    private static void addPlainText(File directory, JSONObject book, String text) throws IOException, JSONException {
        text = text.trim();
        if (text.isEmpty()) throw new IOException("文本文件没有正文");
        JSONArray chapters = new JSONArray();
        Matcher matches = HEADING.matcher(text);
        int start = 0;
        String title = "正文";
        while (matches.find()) {
            if (matches.start() > start) addText(directory, chapters, title, text.substring(start, matches.start()));
            start = matches.start();
            title = matches.group().trim();
        }
        addText(directory, chapters, title, text.substring(start));
        book.put("chapters", chapters);
    }

    private static void addEpub(File directory, JSONObject book, ZipFile zip) throws IOException, JSONException {
        Document container = xml(zip, "META-INF/container.xml");
        Element rootfile = container.selectFirst("rootfile[full-path]");
        if (rootfile == null) throw new IOException("EPUB 缺少书籍清单");
        String path = archivePath("", rootfile.attr("full-path"));
        Document manifest = xml(zip, path);
        for (Element node : manifest.getAllElements()) {
            String tag = node.normalName();
            if (tag.equals("dc:title") || tag.equals("title")) {
                if (!node.text().isEmpty()) book.put("title", node.text());
            } else if (tag.equals("dc:creator") || tag.equals("creator")) {
                if (!node.text().isEmpty()) book.put("author", node.text());
            }
        }
        Map<String, String> addresses = new HashMap<>();
        for (Element item : manifest.select("manifest > item[id][href]")) addresses.put(item.id(), item.attr("href"));
        JSONArray chapters = new JSONArray();
        Set<String> visited = new HashSet<>();
        long characters = 0;
        for (Element row : manifest.select("spine > itemref[idref]")) {
            String href = addresses.get(row.attr("idref"));
            if (href == null) continue;
            String entry = archivePath(path, href);
            if (!visited.add(entry)) continue;
            Document document = Jsoup.parse(new String(zipEntry(zip, entry), StandardCharsets.UTF_8));
            document.select("script,style,noscript,svg").remove();
            Element body = document.body();
            Element heading = body.selectFirst("h1,h2,h3");
            for (Element block : body.select("p,div,section,li,blockquote,h1,h2,h3,h4,h5,h6")) block.after("\n\n");
            String text = body.wholeText().trim();
            if ((characters += text.length()) > MAX_NOVEL_INPUT) throw new IOException("EPUB 正文超过 32 MiB");
            if (!text.isEmpty()) addText(directory, chapters, heading != null ? heading.text() : "章节 " + (chapters.length() + 1), text);
        }
        if (chapters.length() == 0) throw new IOException("EPUB 没有可读取的正文，可能受保护或仅包含图片");
        book.put("chapters", chapters);
    }

    private static void addText(File directory, JSONArray chapters, String title, String text) throws IOException {
        int start = 0;
        while (start < text.length()) {
            interrupted();
            int end = Math.min(text.length(), start + MAX_CHAPTER_TEXT);
            if (end < text.length()) {
                int paragraph = text.lastIndexOf('\n', end);
                if (paragraph > start + MAX_CHAPTER_TEXT / 2) end = paragraph + 1;
                else if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            String section = text.substring(start, end).trim();
            if (!section.isEmpty()) {
                int number = chapters.length();
                if (number >= MAX_ENTRIES) throw new IOException("本地小说章节过多");
                Files.write(new File(directory, number + ".txt").toPath(), section.getBytes(StandardCharsets.UTF_8));
                chapters.put(start > 0 ? title + "（续）" : title);
            }
            start = end;
        }
    }

    private static void addComic(JSONObject book, ZipFile zip) throws IOException, JSONException {
        List<String> paths = new ArrayList<>();
        Set<String> unique = new HashSet<>();
        long bytes = 0;
        for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
            String name = entry.getName();
            if (entry.isDirectory() || name.startsWith("__MACOSX/") || name.startsWith(".") || !supportsImage(name)) continue;
            if (name.length() > 1024 || entry.getSize() <= 0 || entry.getSize() > MAX_IMAGE_BYTES
                    || (bytes += entry.getSize()) > MAX_COMIC_INPUT || !unique.add(name)) {
                throw new IOException("漫画压缩包包含过大或重复的图片条目");
            }
            paths.add(name);
        }
        if (paths.isEmpty()) throw new IOException("压缩包中没有支持的漫画图片");
        paths.sort(ReaderLocalBooks::compareNames);
        book.put("images", new JSONArray(paths));
    }

    private static Document xml(ZipFile zip, String name) throws IOException {
        return Jsoup.parse(new String(zipEntry(zip, name), StandardCharsets.UTF_8), "", Parser.xmlParser());
    }

    private static byte[] zipEntry(ZipFile zip, String name) throws IOException {
        ZipEntry file = zip.getEntry(name);
        if (file == null || file.isDirectory() || file.getSize() > 4 * 1024 * 1024) throw new IOException("EPUB 内部文件缺失或过大");
        try (InputStream input = zip.getInputStream(file)) { return read(input, 4 * 1024 * 1024); }
    }

    private static String archivePath(String base, String href) throws IOException {
        try {
            URI relative = URI.create(href.replace(" ", "%20"));
            URI address = URI.create(ORIGIN + "/" + base).resolve(relative).normalize();
            String path = address.getPath();
            if (relative.isAbsolute() || relative.getRawAuthority() != null || path == null || !path.startsWith("/")
                    || path.startsWith("/../") || path.contains("/../") || path.indexOf('\\') >= 0) {
                throw new IOException("EPUB 内部文件地址无效");
            }
            return path.substring(1);
        } catch (IllegalArgumentException invalid) { throw new IOException("EPUB 内部文件地址无效", invalid); }
    }

    private static String decodeText(byte[] bytes) throws IOException {
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xfe) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length > 1 && (bytes[0] & 0xff) == 0xfe && (bytes[1] & 0xff) == 0xff) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return decoded.startsWith("\ufeff") ? decoded.substring(1) : decoded;
        } catch (CharacterCodingException nonUtf8) {
            return new String(bytes, java.nio.charset.Charset.forName("GB18030"));
        }
    }

    private static String displayTitle(String name) {
        int period = name.lastIndexOf('.');
        return period > 0 ? name.substring(0, period) : name;
    }

    private static int compareNames(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char left = Character.toLowerCase(a.charAt(i));
            char right = Character.toLowerCase(b.charAt(j));
            if (digit(left) && digit(right)) {
                int startA = i, startB = j;
                while (i < a.length() && digit(a.charAt(i))) i++;
                while (j < b.length() && digit(b.charAt(j))) j++;
                while (startA + 1 < i && a.charAt(startA) == '0') startA++;
                while (startB + 1 < j && b.charAt(startB) == '0') startB++;
                if (i - startA != j - startB) return Integer.compare(i - startA, j - startB);
                int order = a.substring(startA, i).compareTo(b.substring(startB, j));
                if (order != 0) return order;
            } else {
                if (left != right) return Character.compare(left, right);
                i++;
                j++;
            }
        }
        return i < a.length() ? 1 : j < b.length() ? -1 : a.compareTo(b);
    }

    private static boolean digit(char value) { return value >= '0' && value <= '9'; }

    private static byte[] read(InputStream input, long max) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        copy(input, output, max);
        return output.toByteArray();
    }

    private static long copy(InputStream input, OutputStream output, long max) throws IOException {
        if (input == null) throw new IOException("无法读取本地文件");
        byte[] buffer = new byte[16_384];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            interrupted();
            if ((total += count) > max) throw new IOException("本地文件超过支持的大小");
            output.write(buffer, 0, count);
        }
        return total;
    }

    private static void interrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消");
    }
}
