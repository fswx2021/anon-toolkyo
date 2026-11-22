package com.shengjian.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.alexmercerind.mediakitandroidhelper.MediaKitAndroidHelper;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.StringArray;
import com.sun.jna.ptr.DoubleByReference;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

final class AnimeMpvPlayer {
    interface Listener {
        void onReady();
        void onCompletion();
        void onError(String message);
    }

    public interface Mpv extends Library {
        int mpv_lavc_set_java_vm(Pointer javaVm);
        Pointer mpv_create();
        int mpv_initialize(Pointer handle);
        void mpv_terminate_destroy(Pointer handle);
        int mpv_set_option_string(Pointer handle, String name, String value);
        int mpv_set_property_string(Pointer handle, String name, String value);
        int mpv_command(Pointer handle, StringArray arguments);
        Pointer mpv_wait_event(Pointer handle, double timeout);
        int mpv_get_property(Pointer handle, String name, int format, DoubleByReference value);
        int mpv_get_property(Pointer handle, String name, int format, IntByReference value);
        int mpv_get_property(Pointer handle, String name, int format, LongByReference value);
    }

    public interface AndroidHelper extends Library {
        Pointer MediaKitAndroidHelperGetJavaVM();
    }

    private static final String TAG = "AnimeMpv";
    private static final int MPV_FORMAT_FLAG = 3;
    private static final int MPV_FORMAT_INT64 = 4;
    private static final int MPV_FORMAT_DOUBLE = 5;
    private static final int MPV_EVENT_NONE = 0;
    private static final int MPV_EVENT_SHUTDOWN = 1;
    private static final int MPV_EVENT_END_FILE = 7;
    private static final int MPV_EVENT_FILE_LOADED = 8;
    private static final int MPV_EVENT_VIDEO_RECONFIG = 17;
    private static final int MPV_END_FILE_REASON_EOF = 0;
    private static final int MPV_END_FILE_REASON_ERROR = 4;
    private static final int POLL_MS = 125;
    private static Mpv nativeMpv;

    private final SurfaceView view;
    private final Context appContext;
    private final File cacheDir;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread playbackThread = new HandlerThread("anime-mpv");
    private final Handler worker;
    private final AudioManager audioManager;
    private final AudioFocusRequest audioFocus;
    private final DoubleByReference number = new DoubleByReference();
    private final IntByReference flag = new IntByReference();
    private final LongByReference integer = new LongByReference();
    private final Runnable poll = this::poll;
    private volatile boolean released;
    private volatile long positionMs;
    private volatile long durationMs;
    private volatile int videoWidth;
    private volatile int videoHeight;
    private volatile boolean playing;

    // The worker owns every request, surface reference and mpv call.
    private Mpv mpv;
    private Pointer handle;
    private Surface surface;
    private int surfaceWidth;
    private int surfaceHeight;
    private long surfaceReference;
    private File caBundle;
    private Request request;
    private boolean prepared;
    private boolean wantsPlayback;
    private boolean hasAudioFocus;
    private boolean requestedAudioFocus;
    private float speed = 1f;
    private int fit;
    private String shaderList = "";

    AnimeMpvPlayer(Context context, Listener listener) {
        this.listener = listener;
        appContext = context.getApplicationContext();
        cacheDir = context.getCacheDir();
        playbackThread.start();
        worker = new Handler(playbackThread.getLooper());
        audioManager = context.getSystemService(AudioManager.class);
        audioFocus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(this::onAudioFocusChange, worker)
                .build();
        view = new SurfaceView(context);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                Surface created = holder.getSurface();
                submit(() -> surface = created);
            }

            @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                if (width <= 0 || height <= 0) return;
                submit(() -> {
                    if (surface == null || (surfaceWidth == width && surfaceHeight == height)) return;
                    surfaceWidth = width;
                    surfaceHeight = height;
                    if (handle == null) openIfReady();
                    else if (mpv.mpv_set_property_string(handle, "android-surface-size", surfaceSize()) < 0) {
                        Log.w(TAG, "Unable to update the mpv rendering surface size");
                    }
                });
            }

            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                submit(() -> {
                    surface = null;
                    surfaceWidth = 0;
                    surfaceHeight = 0;
                    closeNative();
                });
            }
        });
    }

    static boolean supported() {
        if (!android.os.Process.is64Bit()) return false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi) || "x86_64".equals(abi)) return true;
        }
        return false;
    }

    SurfaceView view() { return view; }
    long position() { return positionMs; }
    long duration() { return durationMs; }
    boolean isPlaying() { return playing; }
    int width() { return videoWidth; }
    int height() { return videoHeight; }

    void load(String url, Map<String, String> headers, long initialPositionMs) {
        String scheme = Uri.parse(url).getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme) && !"file".equals(scheme)) {
            notifyMain(() -> listener.onError("播放地址格式不受支持"));
            return;
        }
        String referrer = header(headers, "Referer");
        String agent = header(headers, "User-Agent");
        submit(() -> {
            closeNative();
            positionMs = Math.max(0, initialPositionMs);
            durationMs = 0;
            videoWidth = 0;
            videoHeight = 0;
            request = new Request(url, referrer, agent);
            openIfReady();
        });
    }

    void play() { setPlaying(true); }
    void pause() { setPlaying(false); }

    private void setPlaying(boolean play) {
        submit(() -> {
            wantsPlayback = play;
            if (play) acquireAudioFocus();
            else abandonAudioFocus();
            applyPause();
        });
    }

    private void acquireAudioFocus() {
        if (requestedAudioFocus) return;
        hasAudioFocus = audioManager != null
                && audioManager.requestAudioFocus(audioFocus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        requestedAudioFocus = hasAudioFocus;
    }

    private void abandonAudioFocus() {
        if (requestedAudioFocus && audioManager != null) audioManager.abandonAudioFocusRequest(audioFocus);
        requestedAudioFocus = false;
        hasAudioFocus = false;
    }

    private void onAudioFocusChange(int change) {
        if (released || !requestedAudioFocus) return;
        hasAudioFocus = change == AudioManager.AUDIOFOCUS_GAIN;
        if (change == AudioManager.AUDIOFOCUS_LOSS) {
            wantsPlayback = false;
            abandonAudioFocus();
        }
        applyPause();
    }

    private void applyPause() {
        boolean resume = wantsPlayback && hasAudioFocus;
        if (handle != null) mpv.mpv_set_property_string(handle, "pause", resume ? "no" : "yes");
        if (!resume) playing = false;
    }

    void seek(long targetMs) {
        submit(() -> {
            positionMs = Math.max(0, targetMs);
            if (prepared) seekNative(positionMs);
        });
    }

    void speed(float multiplier) {
        if (!Float.isFinite(multiplier) || multiplier < 0.25f || multiplier > 4f) return;
        submit(() -> {
            speed = multiplier;
            if (handle != null) mpv.mpv_set_property_string(handle, "speed", Float.toString(speed));
        });
    }

    void fit(int mode) {
        if (mode < 0 || mode > 2) return;
        submit(() -> {
            fit = mode;
            if (handle != null) {
                mpv.mpv_set_property_string(handle, "keepaspect", mode == 2 ? "no" : "yes");
                mpv.mpv_set_property_string(handle, "panscan", mode == 1 ? "1" : "0");
            }
        });
    }

    void setShaders(List<File> shaders) {
        List<String> paths = new ArrayList<>();
        for (File shader : shaders) {
            String path = shader.getAbsolutePath();
            if (path.indexOf(':') < 0 && shader.isFile()) paths.add(path);
        }
        String selected = String.join(":", paths);
        submit(() -> {
            shaderList = selected;
            if (handle != null && mpv.mpv_set_property_string(handle, "glsl-shaders", shaderList) < 0) {
                notifyMain(() -> listener.onError("画质增强无法应用"));
            }
        });
    }

    void release() {
        if (released) return;
        released = true;
        main.removeCallbacksAndMessages(null);
        worker.post(() -> {
            request = null;
            surface = null;
            closeNative();
            abandonAudioFocus();
            if (caBundle != null) caBundle.delete();
            playbackThread.quitSafely();
        });
    }

    private void openIfReady() {
        if (handle != null || request == null || surface == null || !surface.isValid()
                || surfaceWidth <= 0 || surfaceHeight <= 0) return;
        try {
            mpv = loadMpv(appContext);
            surfaceReference = MediaKitAndroidHelper.newGlobalObjectRef(surface);
            if (surfaceReference == 0) {
                fail("无法连接视频显示区域");
                return;
            }
            handle = mpv.mpv_create();
            if (handle == null) {
                fail("无法初始化增强播放器");
                return;
            }
            // The pinned build excludes Lua and its built-in ytdl hook.
            if (!option("config", "no") || !option("idle", "yes") || !option("load-scripts", "no")
                    || !option("load-unsafe-playlists", "no")
                    || !option("sub-auto", "no") || !configureInput()
                    || !option("wid", Long.toString(surfaceReference))
                    || !option("vo", "gpu") || !option("gpu-context", "android")
                    || !option("android-surface-size", surfaceSize())
                    || !option("gpu-api", "opengl") || !option("hwdec", "mediacodec-copy")
                    || !option("pause", wantsPlayback && hasAudioFocus ? "no" : "yes")
                    || !option("keepaspect", fit == 2 ? "no" : "yes")
                    || !option("panscan", fit == 1 ? "1" : "0")
                    || !option("speed", Float.toString(speed))
                    || (!request.referrer.isEmpty() && !option("referrer", request.referrer))
                    || (!request.agent.isEmpty() && !option("user-agent", request.agent))
                    || (!shaderList.isEmpty() && !option("glsl-shaders", shaderList))
                    || mpv.mpv_initialize(handle) < 0) {
                fail("增强播放器初始化失败");
                return;
            }
            if (command("loadfile", request.address, "replace") < 0) {
                fail("增强播放器无法打开此视频");
                return;
            }
            worker.post(poll);
        } catch (LinkageError | IOException | GeneralSecurityException | RuntimeException failed) {
            Log.w(TAG, "Enhanced video engine unavailable: " + failed.getClass().getSimpleName());
            fail("此设备无法启动增强播放器");
        }
    }

    private String surfaceSize() { return surfaceWidth + "x" + surfaceHeight; }

    private static synchronized Mpv loadMpv(Context context) {
        if (nativeMpv != null) return nativeMpv;
        // The pinned Android libmpv build forwards this JavaVM to FFmpeg's MediaCodec layer.
        MediaKitAndroidHelper.setApplicationContextJava(context);
        AndroidHelper bridge = Native.load("mediakitandroidhelper", AndroidHelper.class);
        Pointer javaVm = bridge.MediaKitAndroidHelperGetJavaVM();
        Mpv library = Native.load("mpv", Mpv.class,
                Collections.singletonMap(Library.OPTION_STRING_ENCODING, "UTF-8"));
        if (javaVm == null || library.mpv_lavc_set_java_vm(javaVm) < 0) {
            throw new IllegalStateException("MediaCodec JavaVM unavailable");
        }
        nativeMpv = library;
        return nativeMpv;
    }

    private boolean option(String name, String value) {
        int result = mpv.mpv_set_option_string(handle, name, value);
        if (result < 0) Log.w(TAG, "mpv option failed: " + name + " (" + result + ")");
        return result >= 0;
    }

    private boolean configureInput() throws GeneralSecurityException, IOException {
        boolean local = "file".equals(Uri.parse(request.address).getScheme());
        if (local) return option("demuxer-lavf-o", "protocol_whitelist=file");
        if (caBundle == null) caBundle = writeTrustBundle();
        return option("demuxer-lavf-o", "protocol_whitelist=[http,https,tcp,tls,crypto,httpproxy]")
                && option("demuxer-lavf-propagate-opts", "yes")
                && option("network-timeout", "18") && option("tls-verify", "yes")
                && option("tls-ca-file", caBundle.getAbsolutePath());
    }

    private File writeTrustBundle() throws GeneralSecurityException, IOException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        File file = File.createTempFile("anime-mpv-certs-", ".pem", cacheDir);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.US_ASCII))) {
            int certificates = 0;
            for (TrustManager manager : factory.getTrustManagers()) {
                if (!(manager instanceof X509TrustManager)) continue;
                for (X509Certificate certificate : ((X509TrustManager) manager).getAcceptedIssuers()) {
                    String pem = Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP);
                    writer.write("-----BEGIN CERTIFICATE-----\n");
                    for (int offset = 0; offset < pem.length(); offset += 64) {
                        writer.write(pem, offset, Math.min(64, pem.length() - offset));
                        writer.newLine();
                    }
                    writer.write("-----END CERTIFICATE-----\n");
                    certificates++;
                }
            }
            if (certificates == 0) throw new GeneralSecurityException("No Android trust anchors");
            return file;
        } catch (IOException | GeneralSecurityException failed) {
            file.delete();
            throw failed;
        }
    }

    private void poll() {
        if (released || handle == null) return;
        for (int count = 0; count < 32; count++) {
            Pointer event = mpv.mpv_wait_event(handle, 0);
            if (event == null || event.getInt(0) == MPV_EVENT_NONE) break;
            int id = event.getInt(0);
            if (id == MPV_EVENT_FILE_LOADED) {
                prepared = true;
                durationMs = milliseconds("duration", 0);
                updateDimensions();
                if (positionMs > 0 && (durationMs == 0 || positionMs < durationMs - 2000)) seekNative(positionMs);
                notifyMain(listener::onReady);
            } else if (id == MPV_EVENT_VIDEO_RECONFIG) {
                updateDimensions();
            } else if (id == MPV_EVENT_END_FILE) {
                Pointer end = event.getPointer(16);
                int reason = end == null ? MPV_END_FILE_REASON_ERROR : end.getInt(0);
                if (reason == MPV_END_FILE_REASON_ERROR) {
                    fail("增强播放器无法读取视频，请重试或换一集");
                    return;
                }
                if (reason == MPV_END_FILE_REASON_EOF) {
                    prepared = false;
                    playing = false;
                    abandonAudioFocus();
                    if (durationMs > 0) positionMs = durationMs;
                    notifyMain(listener::onCompletion);
                }
            } else if (id == MPV_EVENT_SHUTDOWN) {
                fail("增强播放器已停止");
                return;
            }
        }
        if (prepared) {
            positionMs = milliseconds("time-pos", positionMs);
            durationMs = milliseconds("duration", durationMs);
            if (videoWidth == 0 || videoHeight == 0) updateDimensions();
            playing = wantsPlayback && hasAudioFocus
                    && mpv.mpv_get_property(handle, "pause", MPV_FORMAT_FLAG, flag) >= 0 && flag.getValue() == 0;
        }
        worker.postDelayed(poll, POLL_MS);
    }

    private void seekNative(long ms) {
        command("seek", Double.toString(ms / 1000d), "absolute+exact");
    }

    private int command(String... arguments) {
        return mpv.mpv_command(handle, new StringArray(arguments, "UTF-8"));
    }

    private long milliseconds(String name, long fallback) {
        if (mpv.mpv_get_property(handle, name, MPV_FORMAT_DOUBLE, number) < 0) return fallback;
        return Math.max(0, Math.round(number.getValue() * 1000));
    }

    private void updateDimensions() {
        int width = dimension("dwidth");
        int height = dimension("dheight");
        if (width == 0 || height == 0) {
            width = dimension("width");
            height = dimension("height");
        }
        if (dimension("video-out-params/rotate") % 180 == 90) {
            videoWidth = height;
            videoHeight = width;
        } else {
            videoWidth = width;
            videoHeight = height;
        }
    }

    private int dimension(String property) {
        if (mpv.mpv_get_property(handle, property, MPV_FORMAT_INT64, integer) < 0) return 0;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0, integer.getValue()));
    }

    private void fail(String message) {
        request = null;
        closeNative();
        abandonAudioFocus();
        notifyMain(() -> listener.onError(message));
    }

    private void closeNative() {
        worker.removeCallbacks(poll);
        prepared = false;
        playing = false;
        if (handle != null) {
            mpv.mpv_terminate_destroy(handle);
            handle = null;
        }
        if (surfaceReference != 0) {
            MediaKitAndroidHelper.deleteGlobalObjectRef(surfaceReference);
            surfaceReference = 0;
        }
    }

    private void submit(Runnable action) {
        if (!released) worker.post(() -> { if (!released) action.run(); });
    }

    private void notifyMain(Runnable action) {
        main.post(() -> { if (!released) action.run(); });
    }

    private static String header(Map<String, String> headers, String key) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String value = entry.getValue();
            if (key.equalsIgnoreCase(entry.getKey()) && value != null && value.length() <= 4096
                    && value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && value.indexOf(0) < 0) {
                return value;
            }
        }
        return "";
    }

    private static final class Request {
        final String address;
        final String referrer;
        final String agent;

        Request(String address, String referrer, String agent) {
            this.address = address;
            this.referrer = referrer;
            this.agent = agent;
        }
    }
}
