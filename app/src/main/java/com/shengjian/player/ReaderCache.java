package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

final class ReaderCache {
    private static final long DOWNLOAD_LIMIT = 768L * 1024 * 1024;
    private static final long IMAGE_CACHE_LIMIT = 192L * 1024 * 1024;
    private static final long CATALOGUE_CACHE_LIMIT = 32L * 1024 * 1024;
    private static final int MAX_IMAGE_SIZE = 18 * 1024 * 1024;
    private static final int MAX_METADATA_SIZE = 12 * 1024 * 1024;
    private static final int MAX_IMAGES = 800;
    private static final int MAX_CHAPTERS = 12_000;
    private static final int CHECKSUM_BYTES = 32;
    private static final String MANIFEST = "chapter.json";

    @FunctionalInterface
    interface ImageLoader {
        byte[] load(String image, String pageUrl) throws IOException;
    }

    @FunctionalInterface
    interface ProgressListener {
        void onProgress(int complete, int total);
    }

    static final class SavedDownload {
        final ReaderEngine.Chapter chapter;
        final long bytes;
        final long savedAt;

        SavedDownload(ReaderEngine.Chapter chapter, long bytes, long savedAt) {
            this.chapter = chapter;
            this.bytes = bytes;
            this.savedAt = savedAt;
        }
    }

    private final File downloads;
    private final File images;
    private final File catalogues;
    private final Object downloadWriter = new Object();
    private final Object downloadFiles = new Object();
    private final Object imageFiles = new Object();
    private final Object catalogueFiles = new Object();

    ReaderCache(File directory) {
        downloads = new File(directory, "chapters");
        images = new File(directory, "images");
        catalogues = new File(directory, "catalogues");
    }

    ReaderEngine.Page loadChapter(ReaderEngine.Chapter chapter) {
        synchronized (downloadFiles) {
            File file = downloadFile(chapter);
            if (!file.isFile()) {
                return null;
            }
            try (ZipFile archive = new ZipFile(file)) {
                JSONObject state = manifest(archive);
                if (!chapter.url.equals(state.getString("chapterUrl"))) {
                    throw new IOException("缓存章节不匹配");
                }
                JSONArray data = state.getJSONArray("images");
                if (data.length() > MAX_IMAGES) {
                    throw new IOException("缓存图片清单过长");
                }
                List<String> pictures = new ArrayList<>(data.length());
                for (int i = 0; i < data.length(); i++) {
                    String address = data.getString(i);
                    checkEntry(archive.getEntry(imageEntry(address)), MAX_IMAGE_SIZE);
                    pictures.add(address);
                }
                String text = state.optString("text", "");
                boolean hasImages = chapter.book.source.kind == ReaderSources.COMIC && !pictures.isEmpty();
                if (!hasImages && text.isEmpty()) {
                    throw new IOException("缓存章节内容为空");
                }
                return new ReaderEngine.Page(text, pictures, state.getString("pageUrl"));
            } catch (IOException | JSONException invalid) {
                discard(file);
                return null;
            }
        }
    }

    boolean isDownloaded(ReaderEngine.Chapter chapter) {
        return loadChapter(chapter) != null;
    }

    void download(ReaderEngine.Chapter chapter, ReaderEngine.Page page, ImageLoader loader) throws IOException {
        download(chapter, page, loader, null);
    }

    void download(ReaderEngine.Chapter chapter, ReaderEngine.Page page, ImageLoader loader,
                  ProgressListener progress) throws IOException {
        synchronized (downloadWriter) {
            if (isDownloaded(chapter)) {
                return;
            }
            checkInterrupted();
            ReaderEngine.Page content = page == null ? ReaderEngine.open(chapter) : page;
            boolean hasImages = chapter.book.source.kind == ReaderSources.COMIC && !content.images.isEmpty();
            if (!hasImages && content.text.isEmpty()) {
                throw new IOException("章节没有可以下载的内容");
            }
            if (content.images.size() > MAX_IMAGES) {
                throw new IOException("章节图片数量超出缓存上限");
            }
            if (!content.images.isEmpty() && loader == null) {
                throw new IOException("章节图片暂时无法下载");
            }
            byte[] info = encodeChapter(chapter, content);
            File pending;
            long space;
            synchronized (downloadFiles) {
                Files.createDirectories(downloads.toPath());
                pending = new File(downloads, ".pending");
                Files.deleteIfExists(pending.toPath());
                space = DOWNLOAD_LIMIT - total(entries(downloads, ".zip"));
            }
            if (space <= info.length) {
                throw quotaExceeded();
            }
            LinkedHashSet<String> pictures = new LinkedHashSet<>(content.images);
            int complete = 0;
            try {
                if (progress != null) {
                    progress.onProgress(0, pictures.size());
                }
                try (FileOutputStream file = new FileOutputStream(pending);
                     ZipOutputStream output = new ZipOutputStream(new BufferedOutputStream(
                             new BoundedOutput(file, space)))) {
                    addEntry(output, MANIFEST, info);
                    for (String image : pictures) {
                        checkInterrupted();
                        byte[] bytes = loader.load(image, content.url);
                        if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_SIZE) {
                            throw new IOException("第 " + (complete + 1) + " 张图片无法保存");
                        }
                        addEntry(output, imageEntry(image), bytes);
                        complete++;
                        if (progress != null) {
                            progress.onProgress(complete, pictures.size());
                        }
                    }
                    output.finish();
                    output.flush();
                    file.getFD().sync();
                }
                checkInterrupted();
                synchronized (downloadFiles) {
                    replace(pending, downloadFile(chapter));
                }
            } finally {
                discard(pending);
            }
        }
    }

    void removeDownload(ReaderEngine.Chapter chapter) throws IOException {
        synchronized (downloadWriter) {
            synchronized (downloadFiles) {
                Files.deleteIfExists(downloadFile(chapter).toPath());
            }
        }
    }

    List<SavedDownload> savedDownloads(List<ReaderEngine.Book> legacyBooks) {
        Map<String, ReaderEngine.Book> byKey = new HashMap<>();
        for (ReaderEngine.Book book : legacyBooks) byKey.put(bookKey(book), book);
        List<SavedDownload> result = new ArrayList<>();
        synchronized (downloadFiles) {
            for (File file : entries(downloads, ".zip")) {
                try (ZipFile archive = new ZipFile(file)) {
                    JSONObject state = manifest(archive);
                    String name = file.getName();
                    int separator = name.indexOf('-');
                    if (separator < 0) continue;
                    String key = name.substring(0, separator);
                    ReaderEngine.Book book = byKey.get(key);
                    if (book == null) book = offlineBook(state);
                    if (book == null || !bookKey(book).equals(key)) continue;
                    ReaderEngine.Chapter chapter = readCatalogueRow(book, state);
                    if (!downloadFile(chapter).equals(file)) continue;
                    result.add(new SavedDownload(chapter, file.length(), file.lastModified()));
                } catch (IOException | JSONException | IllegalArgumentException ignored) {
                    // Shared storage can contain legacy bundles which lack the book identity.
                }
            }
        }
        result.sort(Comparator.comparingLong((SavedDownload saved) -> saved.savedAt).reversed());
        return result;
    }

    long[] storageBytes() {
        long[] bytes = new long[3];
        synchronized (downloadFiles) { bytes[0] = total(entries(downloads, ".zip")); }
        synchronized (imageFiles) { bytes[1] = total(entries(images, ".img")); }
        synchronized (catalogueFiles) { bytes[2] = total(entries(catalogues, ".json")); }
        return bytes;
    }

    void clearTransient() throws IOException {
        synchronized (imageFiles) {
            for (File file : entries(images, ".img")) Files.deleteIfExists(file.toPath());
        }
        synchronized (catalogueFiles) {
            for (File file : entries(catalogues, ".json")) Files.deleteIfExists(file.toPath());
        }
    }

    private static ReaderEngine.Book offlineBook(JSONObject state) throws JSONException {
        JSONObject identity = state.optJSONObject("book");
        if (identity == null) return null;
        int kind = identity.getInt("kind");
        if (kind != ReaderSources.COMIC && kind != ReaderSources.NOVEL) return null;
        ReaderSources.Source source = ReaderSources.offlineSource(kind, identity.getString("sourceId"),
                identity.optString("sourceName", "离线来源"));
        return new ReaderEngine.Book(source, identity.getString("title"), identity.optString("author"),
                "", "", identity.getString("url"), identity.optString("tocUrl"));
    }

    List<ReaderEngine.Chapter> loadCatalogue(ReaderEngine.Book book) {
        File file = catalogueFile(book);
        synchronized (catalogueFiles) {
            if (file.isFile()) {
                try {
                    byte[] bytes = readChecked(file, MAX_METADATA_SIZE);
                    JSONArray data = new JSONArray(new String(bytes, StandardCharsets.UTF_8));
                    if (data.length() == 0 || data.length() > MAX_CHAPTERS) {
                        throw new IOException("缓存目录无效");
                    }
                    List<ReaderEngine.Chapter> chapters = new ArrayList<>(data.length());
                    for (int i = 0; i < data.length(); i++) {
                        chapters.add(readCatalogueRow(book, data.getJSONObject(i)));
                    }
                    touch(file);
                    return chapters;
                } catch (IOException | JSONException invalid) {
                    discard(file);
                }
            }
        }
        return downloadedCatalogue(book);
    }

    void storeCatalogue(ReaderEngine.Book book, List<ReaderEngine.Chapter> chapters) {
        if (chapters.isEmpty() || chapters.size() > MAX_CHAPTERS) {
            return;
        }
        byte[] data;
        try {
            JSONArray items = new JSONArray();
            for (ReaderEngine.Chapter chapter : chapters) {
                JSONObject row = new JSONObject();
                row.put("title", chapter.title);
                row.put("chapterUrl", chapter.url);
                row.put("nextChapterUrl", chapter.nextChapterUrl);
                items.put(row);
            }
            data = items.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException invalid) {
            return;
        }
        if (data.length > MAX_METADATA_SIZE) {
            return;
        }
        synchronized (catalogueFiles) {
            saveTransient(catalogueFile(book), data, ".json", CATALOGUE_CACHE_LIMIT);
        }
    }

    byte[] readImage(ReaderEngine.Chapter chapter, ReaderSources.Source source, String imageDeclaration,
                     String requestUrl, Map<String, String> effectiveHeaders) throws IOException {
        checkInterrupted();
        if (chapter != null && source.id.equals(chapter.book.source.id)) {
            byte[] offline = loadDownloadedImage(chapter, imageDeclaration);
            if (offline != null) {
                return offline;
            }
        }
        File file = new File(images, requestKey(source, requestUrl, effectiveHeaders) + ".img");
        synchronized (imageFiles) {
            if (file.isFile()) {
                try {
                    byte[] bytes = readChecked(file, MAX_IMAGE_SIZE);
                    touch(file);
                    return bytes;
                } catch (IOException invalid) {
                    discard(file);
                }
            }
        }
        byte[] bytes = ReaderSources.fetchImage(requestUrl, effectiveHeaders);
        if (bytes.length > 0 && bytes.length <= MAX_IMAGE_SIZE) {
            synchronized (imageFiles) {
                saveTransient(file, bytes, ".img", IMAGE_CACHE_LIMIT);
            }
        }
        return bytes;
    }

    private byte[] loadDownloadedImage(ReaderEngine.Chapter chapter, String image) {
        synchronized (downloadFiles) {
            File file = downloadFile(chapter);
            if (!file.isFile()) {
                return null;
            }
            try (ZipFile archive = new ZipFile(file)) {
                ZipEntry entry = archive.getEntry(imageEntry(image));
                return entry == null ? null : readEntry(archive, entry, MAX_IMAGE_SIZE);
            } catch (IOException invalid) {
                discard(file);
                return null;
            }
        }
    }

    void removeTransientImage(ReaderSources.Source source, String url, Map<String, String> headers) {
        synchronized (imageFiles) {
            discard(new File(images, requestKey(source, url, headers) + ".img"));
        }
    }

    List<ReaderEngine.Chapter> downloadedCatalogue(ReaderEngine.Book book) {
        List<ReaderEngine.Chapter> chapters = new ArrayList<>();
        String prefix = bookKey(book) + "-";
        synchronized (downloadFiles) {
            File[] files = entries(downloads, ".zip");
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).thenComparing(File::getName));
            for (File file : files) {
                if (!file.getName().startsWith(prefix)) {
                    continue;
                }
                try (ZipFile archive = new ZipFile(file)) {
                    chapters.add(readCatalogueRow(book, manifest(archive)));
                } catch (IOException | JSONException invalid) {
                    discard(file);
                }
            }
        }
        return chapters.isEmpty() ? null : chapters;
    }

    private static ReaderEngine.Chapter readCatalogueRow(ReaderEngine.Book book, JSONObject row)
            throws JSONException {
        ReaderEngine.Chapter chapter = new ReaderEngine.Chapter(book, row.getString("title"),
                row.getString("chapterUrl"));
        chapter.nextChapterUrl = row.optString("nextChapterUrl", "");
        return chapter;
    }

    private static JSONObject manifest(ZipFile archive) throws IOException, JSONException {
        return new JSONObject(new String(readEntry(archive, archive.getEntry(MANIFEST), MAX_METADATA_SIZE),
                StandardCharsets.UTF_8));
    }

    private static byte[] encodeChapter(ReaderEngine.Chapter chapter, ReaderEngine.Page page) throws IOException {
        try {
            JSONObject state = new JSONObject();
            ReaderEngine.Book book = chapter.book;
            state.put("book", new JSONObject().put("sourceId", book.source.id).put("kind", book.source.kind)
                    .put("sourceName", book.source.name).put("title", book.title).put("author", book.author)
                    .put("url", book.url).put("tocUrl", book.tocUrl));
            state.put("title", chapter.title);
            state.put("chapterUrl", chapter.url);
            state.put("nextChapterUrl", chapter.nextChapterUrl);
            state.put("pageUrl", page.url);
            state.put("text", page.text);
            state.put("images", new JSONArray(page.images));
            byte[] data = state.toString().getBytes(StandardCharsets.UTF_8);
            if (data.length > MAX_METADATA_SIZE) {
                throw new IOException("章节清单超过缓存上限");
            }
            return data;
        } catch (JSONException invalid) {
            throw new IOException("无法保存章节信息", invalid);
        }
    }

    private static void checkEntry(ZipEntry entry, int limit) throws IOException {
        if (entry == null || entry.getSize() <= 0 || entry.getSize() > limit) {
            throw new IOException("缓存文件大小无效");
        }
    }

    private static byte[] readEntry(ZipFile archive, ZipEntry entry, int limit) throws IOException {
        checkEntry(entry, limit);
        byte[] content;
        try (InputStream input = archive.getInputStream(entry)) {
            content = readExactly(input, (int) entry.getSize());
            if (input.read() != -1) {
                throw new IOException("缓存文件大小无效");
            }
        }
        CRC32 checksum = new CRC32();
        checksum.update(content);
        if (checksum.getValue() != entry.getCrc()) {
            throw new IOException("缓存文件校验失败");
        }
        return content;
    }

    private static void addEntry(ZipOutputStream archive, String name, byte[] bytes) throws IOException {
        CRC32 checksum = new CRC32();
        checksum.update(bytes);
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(bytes.length);
        entry.setCompressedSize(bytes.length);
        entry.setCrc(checksum.getValue());
        archive.putNextEntry(entry);
        archive.write(bytes);
        archive.closeEntry();
    }

    private static byte[] readChecked(File file, int limit) throws IOException {
        long size = file.length();
        if (size <= CHECKSUM_BYTES || size > limit + CHECKSUM_BYTES) {
            throw new IOException("缓存文件大小无效");
        }
        try (InputStream input = new FileInputStream(file)) {
            byte[] expected = readExactly(input, CHECKSUM_BYTES);
            byte[] content = readExactly(input, (int) (size - CHECKSUM_BYTES));
            if (input.read() != -1 || !MessageDigest.isEqual(expected, sha256().digest(content))) {
                throw new IOException("缓存文件校验失败");
            }
            return content;
        }
    }

    private static byte[] readExactly(InputStream input, int size) throws IOException {
        byte[] result = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(result, offset, size - offset);
            if (count < 0) {
                throw new IOException("缓存文件已截断");
            }
            offset += count;
        }
        return result;
    }

    private static void saveTransient(File file, byte[] bytes, String extension, long limit) {
        File parent = file.getParentFile();
        File pending = new File(parent, ".pending");
        try {
            Files.createDirectories(parent.toPath());
            Files.deleteIfExists(pending.toPath());
            File[] previous = entries(parent, extension);
            long remaining = total(previous) + bytes.length + CHECKSUM_BYTES;
            Arrays.sort(previous, Comparator.comparingLong(File::lastModified));
            for (File old : previous) {
                if (remaining <= limit) {
                    break;
                }
                long size = old.length();
                if (old.delete()) {
                    remaining -= size;
                }
            }
            if (remaining > limit) {
                return;
            }
            try (OutputStream output = new FileOutputStream(pending)) {
                output.write(sha256().digest(bytes));
                output.write(bytes);
            }
            replace(pending, file);
        } catch (IOException unavailable) {
            // Transient cache failures do not prevent reading from the source.
        } finally {
            discard(pending);
        }
    }

    private File downloadFile(ReaderEngine.Chapter chapter) {
        return new File(downloads, bookKey(chapter.book) + "-" + fingerprint(chapter.url) + ".zip");
    }

    private File catalogueFile(ReaderEngine.Book book) {
        return new File(catalogues, bookKey(book) + ".json");
    }

    private static String bookKey(ReaderEngine.Book book) {
        return fingerprint(book.source.id, book.url);
    }

    private static String imageEntry(String declaration) {
        return "image-" + fingerprint(declaration);
    }

    private static String requestKey(ReaderSources.Source source, String url, Map<String, String> headers) {
        Map<String, String> ordered = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            ordered.putAll(headers);
        }
        List<String> key = new ArrayList<>();
        key.add(source.id);
        key.add(url);
        for (Map.Entry<String, String> header : ordered.entrySet()) {
            key.add(header.getKey().toLowerCase(Locale.ROOT));
            key.add(header.getValue());
        }
        return fingerprint(key.toArray(new String[0]));
    }

    private static String fingerprint(String... parts) {
        MessageDigest digest = sha256();
        for (String part : parts) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) ':');
            digest.update(bytes);
        }
        byte[] hash = digest.digest();
        StringBuilder key = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            key.append(Character.forDigit((value >>> 4) & 15, 16));
            key.append(Character.forDigit(value & 15, 16));
        }
        return key.toString();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new AssertionError(missing);
        }
    }

    private static File[] entries(File directory, String extension) {
        File[] list = directory.listFiles(file -> file.isFile() && file.getName().endsWith(extension));
        return list == null ? new File[0] : list;
    }

    private static long total(File[] files) {
        long size = 0;
        for (File file : files) {
            size += file.length();
        }
        return size;
    }

    private static void replace(File pending, File file) throws IOException {
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    private static void discard(File file) {
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException unavailable) {
            // Invalid or abandoned data will be ignored on the next read.
        }
    }

    private static void touch(File file) {
        file.setLastModified(System.currentTimeMillis());
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("下载已取消");
        }
    }

    private static IOException quotaExceeded() {
        return new IOException("离线章节空间已满，请先移除不需要的下载");
    }

    private static final class BoundedOutput extends FilterOutputStream {
        private long remaining;

        BoundedOutput(OutputStream output, long limit) {
            super(output);
            remaining = limit;
        }

        @Override public void write(int value) throws IOException {
            claim(1);
            out.write(value);
        }

        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            claim(length);
            out.write(bytes, offset, length);
        }

        private void claim(int length) throws IOException {
            if (length > remaining) {
                throw quotaExceeded();
            }
            remaining -= length;
        }
    }
}
