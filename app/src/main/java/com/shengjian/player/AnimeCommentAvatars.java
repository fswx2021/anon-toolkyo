package com.shengjian.player;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Asynchronously loads Bangumi avatars and inline images for visible discussion pages. */
final class AnimeCommentAvatars implements AutoCloseable {
    private static final int MAX_PENDING = 96;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DIMENSION = 144;
    private final Handler main;
    private final LruCache<Image, Bitmap> cache = new LruCache<>(6 * 1024 * 1024) {
        @Override protected int sizeOf(Image key, Bitmap bitmap) { return bitmap.getAllocationByteCount(); }
    };
    private final LruCache<Image, Long> retryAfter = new LruCache<>(64);
    private final Map<Image, List<WeakReference<ImageView>>> pending = new HashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(3, 3, 12,
            TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> new Thread(task, "bangumi-avatar"));
    private boolean closed;

    private record Image(String url, boolean media) {}

    AnimeCommentAvatars(Handler main) {
        this.main = main;
        executor.allowCoreThreadTimeOut(true);
    }

    void bind(ImageView target, String url) {
        load(target, new Image(url, false));
    }

    void bindMedia(ImageView target, String url) {
        try {
            URI source = URI.create(url);
            if (!"https".equals(source.getScheme()) || !"lain.bgm.tv".equals(source.getHost())
                    || source.getUserInfo() != null || source.getPort() != -1
                    || source.getRawPath() == null || !source.getRawPath().startsWith("/pic/")) return;
            load(target, new Image(source.toASCIIString(), true));
        } catch (IllegalArgumentException malformed) {
            // Invalid media leaves the inline placeholder in place.
        }
    }

    private void load(ImageView target, Image request) {
        target.setTag(request);
        if (closed || request.url().isEmpty()) return;
        Bitmap bitmap = cache.get(request);
        if (bitmap != null) {
            target.setImageBitmap(bitmap);
            return;
        }
        Long retry = retryAfter.get(request);
        if (retry != null && retry > android.os.SystemClock.elapsedRealtime()) return;
        List<WeakReference<ImageView>> listeners = pending.get(request);
        if (listeners != null) {
            if (listeners.size() < MAX_PENDING) listeners.add(new WeakReference<>(target));
            return;
        }
        if (pending.size() >= MAX_PENDING) return;
        List<WeakReference<ImageView>> targets = new ArrayList<>();
        targets.add(new WeakReference<>(target));
        pending.put(request, targets);
        executor.execute(() -> {
            Bitmap image = read(request);
            main.post(() -> deliver(request, image));
        });
    }

    private void deliver(Image request, Bitmap image) {
        List<WeakReference<ImageView>> targets = pending.remove(request);
        if (closed || targets == null) return;
        if (image == null) {
            retryAfter.put(request, android.os.SystemClock.elapsedRealtime() + 30_000);
            return;
        }
        cache.put(request, image);
        for (WeakReference<ImageView> reference : targets) {
            ImageView target = reference.get();
            if (target != null && request.equals(target.getTag())) target.setImageBitmap(image);
        }
    }

    private static Bitmap read(Image request) {
        try {
            int dimension = request.media() ? 720 : MAX_DIMENSION;
            byte[] bytes = download(request, request.media() ? 2 * MAX_BYTES : MAX_BYTES);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > dimension) {
                options.inSampleSize *= 2;
            }
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        } catch (IOException | IllegalArgumentException unavailable) {
            return null;
        }
    }

    private static byte[] download(Image request, int limit) throws IOException {
        try {
            return KazumiBangumiTransport.fetchImage(request.url(),
                    Map.of("Accept", "image/*", "Referer", "https://bangumi.tv/"), 4_000, limit);
        } catch (IOException unreachable) {
            if (Thread.currentThread().isInterrupted() || !request.url().startsWith("https://lain.bgm.tv/")) {
                throw unreachable;
            }
            String original = URLEncoder.encode(request.url().substring("https://".length()), StandardCharsets.UTF_8);
            try {
                return ReaderSources.fetchImage("https://images.weserv.nl/?url=" + original,
                        Map.of("Accept", "image/*"), 4_000, limit);
            } catch (IOException mirror) {
                mirror.addSuppressed(unreachable);
                throw mirror;
            }
        }
    }

    @Override public void close() {
        closed = true;
        executor.shutdownNow();
        pending.clear();
        cache.evictAll();
        retryAfter.evictAll();
    }
}
