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
    private static final long SUBJECT_COVER_MAX_AGE = 24L * 60 * 60 * 1_000;
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
    private final Map<CoverRequest, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final LruCache<CoverRequest, Long> retryAfter = new LruCache<>(64);
    private final LruCache<CoverRequest, Bitmap> placeholders = new LruCache<>(6);
    private final LruCache<CoverRequest, Bitmap> memory = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(CoverRequest key, Bitmap image) { return image.getAllocationByteCount(); }
    };
    private boolean closed;

    record CoverRequest(URI image, long subjectId) {
        boolean mutable() { return subjectId > 0 || isSubjectCover(image); }
    }

    AnimeCoverCache(Context context, Handler main) {
        directory = new File(context.getCacheDir(), "anime-artwork-v3");
        assets = context.getAssets();
        this.main = main;
    }

    void bind(ImageView image, String url) {
        bind(image, url, 0);
    }

    void bind(ImageView image, String url, long subjectId) {
        image.animate().cancel();
        image.setAlpha(1f);
        CoverRequest request = coverRequest(url, subjectId);
        image.setTag(request);
        image.setScaleType(ImageView.ScaleType.CENTER);
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        if (closed || request == null) return;
        Bitmap cached = memory.get(request);
        if (cached != null) {
            show(image, cached, false);
            return;
        }
        Bitmap placeholder = placeholders.get(request);
        if (placeholder != null) show(image, placeholder, false);
        List<WeakReference<ImageView>> existing = pending.get(request);
        if (existing != null) {
            existing.removeIf(target -> target.get() == null || target.get() == image);
            if (existing.size() < 16) existing.add(new WeakReference<>(image));
            return;
        }
        Long next = retryAfter.get(request);
        if (pending.size() >= MAX_PENDING || next != null && next > SystemClock.elapsedRealtime()) return;
        pending.put(request, new ArrayList<>(List.of(new WeakReference<>(image))));
        worker.execute(() -> {
            Bitmap loaded = load(request);
            main.post(() -> deliver(request, loaded));
        });
    }

    private void deliver(CoverRequest request, Bitmap bitmap) {
        List<WeakReference<ImageView>> targets = pending.remove(request);
        if (closed || targets == null) return;
        if (bitmap == null) {
            retryAfter.put(request, SystemClock.elapsedRealtime() + 10_000L);
            return;
        }
        retryAfter.remove(request);
        memory.put(request, bitmap);
        updateTargets(request, targets, bitmap, true);
    }

    private void preview(CoverRequest request, Bitmap bitmap) {
        List<WeakReference<ImageView>> targets = pending.get(request);
        if (closed || targets == null) return;
        placeholders.put(request, bitmap);
        updateTargets(request, targets, bitmap, false);
    }

    private static void updateTargets(CoverRequest request, List<WeakReference<ImageView>> targets,
                                      Bitmap bitmap, boolean animate) {
        for (WeakReference<ImageView> reference : targets) {
            ImageView target = reference.get();
            if (target != null && request.equals(target.getTag())) show(target, bitmap, animate);
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

    void allowRetry() {
        retryAfter.evictAll();
        for (CoverRequest request : memory.snapshot().keySet()) {
            if (request.mutable()) memory.remove(request);
        }
    }

    private Bitmap load(CoverRequest request) {
        try {
            AtomicFile file = new AtomicFile(new File(directory, cacheName(request)));
            Bitmap cached = decode(readCache(file, request.mutable()));
            if (cached != null) return cached;
            Bitmap bundled = bundledPoster(request.subjectId());
            if (bundled != null) main.post(() -> preview(request, bundled));
            for (String address : imageRoutes(request)) {
                if (Thread.currentThread().isInterrupted()) return null;
                byte[] bytes = fetch(address);
                Bitmap loaded = decode(bytes);
                if (loaded == null) continue;
                if (!Thread.currentThread().isInterrupted()) writeCache(file, bytes);
                return loaded;
            }
            return null;
        } catch (IOException | IllegalArgumentException failure) {
            return null;
        }
    }

    static CoverRequest coverRequest(String url, long subjectId) {
        if (subjectId > 999_999_999_999L) subjectId = 0;
        URI image = null;
        if (url != null && !url.isEmpty()) {
            try {
                URI parsed = URI.create(url);
                if ("https".equalsIgnoreCase(parsed.getScheme()) && parsed.getHost() != null
                        && parsed.getRawUserInfo() == null) image = parsed;
            } catch (IllegalArgumentException invalid) {
                // A known Bangumi subject can still supply its own artwork.
            }
        }
        if (subjectId <= 0 && image != null && isBangumiCover(image)) {
            Matcher subject = COVER_SUBJECT.matcher(image.getRawPath());
            if (subject.find()) subjectId = Long.parseLong(subject.group(1));
        }
        if (image == null && subjectId > 0) image = URI.create(AnimeDiscovery.subjectCover(subjectId));
        return image == null ? null : new CoverRequest(image, Math.max(0L, subjectId));
    }

    static List<String> imageRoutes(CoverRequest request) throws IOException {
        URI image = request.image();
        URI relayImage = image;
        List<String> routes = new ArrayList<>();
        routes.add(image.toString());
        if (!isSubjectCover(image) && request.subjectId() > 0) {
            // The subject endpoint can redirect to a newer poster or a smaller rendition.
            relayImage = URI.create(AnimeDiscovery.subjectCover(request.subjectId()));
            routes.add(relayImage.toString());
        } else if (!isBangumiCover(image)) {
            return List.copyOf(routes);
        }
        routes.add(mirror(isBangumiCover(image) ? image : relayImage));
        routes.add(mirror(relayImage, "images.weserv.nl"));
        return List.copyOf(routes);
    }

    private Bitmap bundledPoster(long subjectId) {
        String subject = Long.toString(subjectId);
        if (!BUNDLED_SUBJECTS.contains(subject)) return null;
        try (InputStream image = assets.open("anime-popular-posters/" + subject + ".webp")) {
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
                || isSubjectCover(uri);
    }

    private static boolean isSubjectCover(URI uri) {
        return "api.bgm.tv".equalsIgnoreCase(uri.getHost()) && API_COVER.matcher(uri.getRawPath()).matches();
    }

    static String mirror(URI uri) throws IOException { return mirror(uri, "wsrv.nl"); }

    private static String mirror(URI uri, String host) throws IOException {
        if (!isBangumiCover(uri)) throw new IOException("图片无法使用 Bangumi 镜像");
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        String source = uri.getHost() + path + (query == null ? "" : "?" + query);
        return "https://" + host + "/?url=" + URLEncoder.encode(source, "UTF-8");
    }

    private static String cacheName(CoverRequest request) throws IOException {
        try {
            String identity = request.image() + "\n" + request.subjectId();
            byte[] key = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return android.util.Base64.encodeToString(key, android.util.Base64.URL_SAFE
                    | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP) + ".img";
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException(unavailable);
        }
    }

    private static byte[] readCache(AtomicFile file, boolean mutable) {
        synchronized (DISK_LOCK) {
            File data = file.getBaseFile();
            if (!data.isFile() || data.length() <= 0 || data.length() > MAX_BYTES) return null;
            long now = System.currentTimeMillis();
            long age = now - data.lastModified();
            if (mutable && (age < 0 || age > SUBJECT_COVER_MAX_AGE)) return null;
            try {
                byte[] bytes = file.readFully();
                if (!mutable) data.setLastModified(now);
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
