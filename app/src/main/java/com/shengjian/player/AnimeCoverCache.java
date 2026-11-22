package com.shengjian.player;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/** Loads and caches anime artwork using Kazumi's original or image-mirror routes. */
final class AnimeCoverCache implements AutoCloseable {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int REQUEST_TIMEOUT_MILLIS = 4_000;
    private static final int MAX_IMAGES = 160;
    private static final long DISK_BUDGET = 40L * 1024 * 1024;
    private static final int MAX_PENDING = 48;
    private static final Pattern CDN_COVER = Pattern.compile("/(?:r/[1-9][0-9]{0,3}/)?pic/cover/[a-z]/[0-9A-Za-z_./-]+");
    private static final Pattern API_COVER = Pattern.compile("/v0/subjects/[1-9][0-9]{0,11}/image");
    private static final Pattern COVER_SUBJECT = Pattern.compile("/([1-9][0-9]{0,11})(?:/image|_[^/]+)$");
    private static final Set<String> BUNDLED_SUBJECTS = Set.of("554779", "622288", "513878", "624085", "625799", "630160");
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "ANON-TOOL/" + BuildConfig.VERSION_NAME + " (Android; Bangumi artwork)",
            "Accept", "image/webp,image/png,image/jpeg,image/*;q=0.8");
    private static final Object DISK_LOCK = new Object();

    private final File directory;
    private final AssetManager assets;
    private final Handler main;
    private final ExecutorService worker = Executors.newFixedThreadPool(4);
    private final Map<String, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final LruCache<String, Long> retryAfter = new LruCache<>(64);
    private final LruCache<String, Bitmap> placeholders = new LruCache<>(6);
    private final LruCache<String, Bitmap> memory = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private boolean closed;

    AnimeCoverCache(Context context, Handler main) {
        directory = new File(context.getCacheDir(), "anime-artwork-v2");
        assets = context.getAssets();
        this.main = main;
    }

    void bind(ImageView image, String url) {
        image.animate().cancel();
        image.setAlpha(1f);
        image.setTag(url);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        if (closed || !url.startsWith("https://")) return;
        Bitmap cached = memory.get(url);
        if (cached != null) {
            show(image, cached, false);
            return;
        }
        Bitmap placeholder = placeholders.get(url);
        if (placeholder != null) show(image, placeholder, false);
        List<WeakReference<ImageView>> existing = pending.get(url);
        if (existing != null) {
            existing.removeIf(target -> target.get() == null || target.get() == image);
            if (existing.size() < 16) existing.add(new WeakReference<>(image));
            return;
        }
        Long next = retryAfter.get(url);
        if (pending.size() >= MAX_PENDING || next != null && next > SystemClock.elapsedRealtime()) return;
        pending.put(url, new ArrayList<>(List.of(new WeakReference<>(image))));
        worker.execute(() -> {
            Bitmap loaded = load(url);
            main.post(() -> deliver(url, loaded));
        });
    }

    private void deliver(String url, Bitmap bitmap) {
        List<WeakReference<ImageView>> targets = pending.remove(url);
        if (closed || targets == null) return;
        if (bitmap == null) {
            retryAfter.put(url, SystemClock.elapsedRealtime() + 10_000L);
            return;
        }
        retryAfter.remove(url);
        memory.put(url, bitmap);
        updateTargets(url, targets, bitmap, true);
    }

    private void preview(String url, Bitmap bitmap) {
        List<WeakReference<ImageView>> targets = pending.get(url);
        if (closed || targets == null) return;
        placeholders.put(url, bitmap);
        updateTargets(url, targets, bitmap, false);
    }

    private static void updateTargets(String url, List<WeakReference<ImageView>> targets,
                                      Bitmap bitmap, boolean animate) {
        for (WeakReference<ImageView> reference : targets) {
            ImageView target = reference.get();
            if (target != null && url.equals(target.getTag())) show(target, bitmap, animate);
        }
    }

    private static void show(ImageView target, Bitmap bitmap, boolean animate) {
        target.setScaleType(ImageView.ScaleType.CENTER_CROP);
        target.setImageBitmap(bitmap);
        if (animate && target.isShown()) {
            target.setAlpha(.6f);
            target.animate().alpha(1f).setDuration(120L).start();
        }
    }

    void allowRetry() { retryAfter.evictAll(); }

    private Bitmap load(String url) {
        try {
            URI uri = URI.create(url);
            AtomicFile file = new AtomicFile(new File(directory, cacheName(url)));
            Bitmap cached = decode(readCache(file));
            if (cached != null) return cached;
            Bitmap bundled = bundledPoster(uri);
            if (bundled != null) main.post(() -> preview(url, bundled));
            byte[] bytes = fetch(url);
            Bitmap loaded = decode(bytes);
            if (loaded == null && !Thread.currentThread().isInterrupted() && isBangumiCover(uri)) {
                bytes = fetch(mirror(uri));
                loaded = decode(bytes);
                if (loaded == null && !Thread.currentThread().isInterrupted()) {
                    bytes = fetch(mirror(uri, "images.weserv.nl"));
                    loaded = decode(bytes);
                }
            }
            if (loaded != null && !Thread.currentThread().isInterrupted()) writeCache(file, bytes);
            return loaded;
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }

    private Bitmap bundledPoster(URI uri) {
        if (!isBangumiCover(uri)) return null;
        Matcher subject = COVER_SUBJECT.matcher(uri.getRawPath());
        if (!subject.find() || !BUNDLED_SUBJECTS.contains(subject.group(1))) return null;
        try (InputStream image = assets.open("anime-popular-posters/" + subject.group(1) + ".webp")) {
            return BitmapFactory.decodeStream(image);
        } catch (IOException unavailable) {
            return null;
        }
    }

    private static byte[] fetch(String url) {
        try {
            return KazumiBangumiTransport.fetchImage(url, HEADERS, REQUEST_TIMEOUT_MILLIS, MAX_BYTES);
        } catch (IOException failure) {
            return null;
        }
    }

    static boolean isBangumiCover(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                || uri.getFragment() != null || uri.getPort() != -1 && uri.getPort() != 443) return false;
        String path = uri.getRawPath();
        if (path == null || !path.equals(uri.normalize().getRawPath())) return false;
        return "lain.bgm.tv".equalsIgnoreCase(uri.getHost()) && CDN_COVER.matcher(path).matches()
                || "api.bgm.tv".equalsIgnoreCase(uri.getHost()) && API_COVER.matcher(path).matches();
    }

    static String mirror(URI uri) throws IOException { return mirror(uri, "wsrv.nl"); }

    private static String mirror(URI uri, String host) throws IOException {
        if (!isBangumiCover(uri)) throw new IOException("图片无法使用 Bangumi 镜像");
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        String source = uri.getHost() + path + (query == null ? "" : "?" + query);
        return "https://" + host + "/?url=" + URLEncoder.encode(source, "UTF-8");
    }

    private static String cacheName(String url) throws IOException {
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(key, android.util.Base64.URL_SAFE
                    | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP) + ".img";
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException(unavailable);
        }
    }

    private static byte[] readCache(AtomicFile file) {
        synchronized (DISK_LOCK) {
            File data = file.getBaseFile();
            if (!data.isFile() || data.length() <= 0 || data.length() > MAX_BYTES) return null;
            try {
                byte[] bytes = file.readFully();
                data.setLastModified(System.currentTimeMillis());
                return bytes;
            } catch (IOException failure) {
                return null;
            }
        }
    }

    private void writeCache(AtomicFile file, byte[] bytes) {
        synchronized (DISK_LOCK) {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                file.finishWrite(output);
            } catch (IOException failure) {
                if (output != null) file.failWrite(output);
                return;
            }
            File[] files = directory.listFiles(entry -> entry.isFile() && entry.getName().endsWith(".img"));
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            long total = 0;
            for (int index = 0; index < files.length; index++) {
                total += files[index].length();
                if (index >= MAX_IMAGES || total > DISK_BUDGET) new AtomicFile(files[index]).delete();
            }
        }
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 640) {
            options.inSampleSize *= 2;
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        pending.clear();
        memory.evictAll();
        placeholders.evictAll();
        retryAfter.evictAll();
    }
}
