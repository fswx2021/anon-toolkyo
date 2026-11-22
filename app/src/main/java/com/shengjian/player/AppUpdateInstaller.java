package com.shengjian.player;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class AppUpdateInstaller {
    static final long MAX_APK_BYTES = 256L * 1024 * 1024;
    private static final long CACHE_AGE_MS = TimeUnit.DAYS.toMillis(1);
    private static final long PROGRESS_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_REDIRECTS = 3;
    private static final String PACKAGE_MIME = "application/vnd.android.package-archive";
    private static final String AUTHORITY_SUFFIX = ".update.apk";
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private static final Pattern APK_NAME = Pattern.compile("[1-9][0-9]{0,9}-[a-f0-9]{64}\\.apk");

    private final Context context;
    private final File downloads;
    private final File ready;

    AppUpdateInstaller(Context context) {
        this.context = context.getApplicationContext();
        downloads = new File(this.context.getCacheDir(), "app-update-downloads");
        ready = new File(this.context.getCacheDir(), "app-update-apks");
    }

    interface Progress {
        void onProgress(long bytesDownloaded, long totalBytes);
    }

    static final class Transfer {
        private final long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        private volatile boolean cancelled;
        private HttpURLConnection connection;

        void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                if (cancelled) return;
                cancelled = true;
                active = connection;
            }
            // disconnect() can wait for a socket read on some platforms.
            if (active != null) {
                Thread disconnect = new Thread(active::disconnect, "anon-update-cancel");
                disconnect.setDaemon(true);
                disconnect.start();
            }
        }

        synchronized void attach(HttpURLConnection connection) throws InterruptedIOException {
            check();
            this.connection = connection;
        }

        synchronized void detach(HttpURLConnection connection) {
            if (this.connection == connection) this.connection = null;
        }

        void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("已取消下载");
            }
            if (deadline - System.nanoTime() <= 0) throw new InterruptedIOException("下载安装包超时");
        }
    }

    File download(AppUpdates.Release release, Transfer transfer, Progress progress) throws IOException {
        transfer.check();
        String digest = release.sha256();
        if (digest == null || !SHA256.matcher(digest).matches() || release.versionCode() <= 0) {
            throw new IOException("缺少有效的安装包校验信息");
        }
        Files.createDirectories(downloads.toPath());
        Files.createDirectories(ready.toPath());
        removeOldFiles(downloads, "download-", ".apk");
        removeOldFiles(ready, "", ".apk");
        File target = new File(ready, release.versionCode() + "-" + digest.toLowerCase(Locale.ROOT) + ".apk");
        // The package parser can require an APK suffix. This staging directory is never shared.
        File part = File.createTempFile("download-", ".apk", downloads);
        try {
            fetchPackage(release, transfer, progress, part);
            verifyArchive(part, release.versionCode());
            transfer.check();
            // Publish the URI only after every check, from a directory shared solely with the installer.
            try {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            part.delete();
        }
    }

    boolean canInstall() {
        return context.getPackageManager().canRequestPackageInstalls();
    }

    Intent installationPermissionIntent() {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.fromParts("package", context.getPackageName(), null));
    }

    Intent installIntent(File apk) throws IOException {
        File verified = readyFile(apk);
        if (!verified.isFile()) throw new IOException("已下载的安装包不存在，请重新下载");
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, context.getPackageName() + AUTHORITY_SUFFIX, verified);
        } catch (IllegalArgumentException unavailable) {
            throw new IOException("无法向系统安装程序提供更新包", unavailable);
        }
        return packageInstallIntent(uri);
    }

    static Intent packageInstallIntent(Uri uri) {
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        intent.setDataAndType(uri, PACKAGE_MIME);
        intent.setClipData(ClipData.newRawUri("anon-toolkyo.apk", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    void discard(File apk) {
        if (apk == null) return;
        try {
            readyFile(apk).delete();
        } catch (IOException ignored) {
            // An invalid path must never affect another cached file.
        }
    }

    private File readyFile(File apk) throws IOException {
        File canonical = apk.getCanonicalFile();
        if (!ready.getCanonicalFile().equals(canonical.getParentFile())
                || !APK_NAME.matcher(canonical.getName()).matches()) {
            throw new IOException("无效的更新安装包");
        }
        return canonical;
    }

    private void fetchPackage(AppUpdates.Release release, Transfer transfer, Progress progress, File part)
            throws IOException {
        NetworkFailure unavailable = null;
        for (URI address : release.downloadUrls()) {
            try {
                fetchPackage(address, release.sha256(), transfer, progress, part);
                return;
            } catch (NetworkFailure error) {
                unavailable = error;
            }
        }
        if (unavailable != null) throw unavailable;
        throw new IOException("当前版本没有可用的安装包下载地址");
    }

    private void fetchPackage(URI address, String digest, Transfer transfer, Progress progress, File part)
            throws IOException {
        URI current = address;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            transfer.check();
            if (!"https".equalsIgnoreCase(current.getScheme()) || current.getHost() == null
                    || current.getUserInfo() != null || current.getPort() != -1 || current.getFragment() != null) {
                throw new IOException("安装包下载地址不安全");
            }
            HttpURLConnection connection;
            try {
                connection = (HttpURLConnection) current.toURL().openConnection();
            } catch (IOException error) {
                throw new NetworkFailure("无法连接安装包下载服务", error);
            }
            try {
                transfer.attach(connection);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setRequestProperty("User-Agent", "anon-toolkyo/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept", "application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                int status;
                try {
                    status = connection.getResponseCode();
                } catch (IOException error) {
                    transfer.check();
                    throw new NetworkFailure("无法连接安装包下载服务", error);
                }
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    current = AppUpdates.apkRedirect(address, current, connection.getHeaderField("Location"));
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new NetworkFailure("下载安装包失败（HTTP " + status + "）", null);
                }
                String type = connection.getContentType();
                if (type != null && (type.startsWith("application/json") || type.startsWith("text/"))) {
                    throw new NetworkFailure("下载地址暂未返回安装包", null);
                }
                InputStream body;
                try {
                    body = connection.getInputStream();
                } catch (IOException error) {
                    transfer.check();
                    throw new NetworkFailure("无法读取安装包", error);
                }
                try (InputStream input = body; OutputStream output = new FileOutputStream(part)) {
                    copyVerified(input, output, connection.getContentLengthLong(), digest, transfer, progress);
                }
                return;
            } finally {
                transfer.detach(connection);
                connection.disconnect();
            }
        }
        throw new IOException("下载安装包时跳转次数过多");
    }

    static void copyVerified(InputStream input, OutputStream output, long length, String expectedSha256,
                             Transfer transfer, Progress progress) throws IOException {
        if (length == 0 || length > MAX_APK_BYTES) throw new IOException("安装包大小超出允许范围");
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("无法校验安装包", unavailable);
        }
        long copied = 0;
        long lastProgress = System.nanoTime();
        progress.onProgress(0, length);
        byte[] buffer = new byte[64 * 1024];
        while (true) {
            transfer.check();
            int count;
            try {
                count = input.read(buffer);
            } catch (IOException error) {
                transfer.check();
                throw new NetworkFailure("安装包下载中断，请重试", error);
            }
            if (count == -1) break;
            copied += count;
            if (copied > MAX_APK_BYTES) throw new IOException("安装包大小超出允许范围");
            output.write(buffer, 0, count);
            sha.update(buffer, 0, count);
            long now = System.nanoTime();
            if (now - lastProgress >= PROGRESS_INTERVAL_NANOS) {
                progress.onProgress(copied, length);
                lastProgress = now;
            }
        }
        transfer.check();
        if (length > 0 && copied != length) throw new NetworkFailure("安装包未下载完整，请重试", null);
        StringBuilder actual = new StringBuilder(64);
        for (byte value : sha.digest()) {
            actual.append(Character.forDigit((value & 0xff) >>> 4, 16));
            actual.append(Character.forDigit(value & 0x0f, 16));
        }
        if (copied == 0 || !actual.toString().equalsIgnoreCase(expectedSha256)) {
            throw new IOException("安装包 SHA-256 校验不一致");
        }
        progress.onProgress(copied, length);
    }

    @SuppressWarnings("deprecation")
    private void verifyArchive(File apk, int expectedVersion) throws IOException {
        PackageManager packages = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed;
        try {
            installed = packages.getPackageInfo(context.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("无法读取当前应用的签名", error);
        }
        PackageInfo candidate = packages.getPackageArchiveInfo(apk.getPath(), flags);
        if (candidate == null) throw new IOException("下载的文件不是有效的安卓安装包");
        verifyIdentity(context.getPackageName(), versionCode(installed), signers(installed),
                candidate.packageName, versionCode(candidate), signers(candidate), expectedVersion);
    }

    static void verifyIdentity(String installedPackage, long installedVersion, Signature[] installedSigners,
                               String candidatePackage, long candidateVersion, Signature[] candidateSigners,
                               int expectedVersion) throws IOException {
        if (!installedPackage.equals(candidatePackage)) throw new IOException("安装包与当前应用不匹配");
        if (candidateVersion != expectedVersion || candidateVersion <= installedVersion) {
            throw new IOException("安装包版本与更新信息不一致");
        }
        if (installedSigners == null || installedSigners.length == 0 || candidateSigners == null
                || !new HashSet<>(Arrays.asList(installedSigners))
                        .equals(new HashSet<>(Arrays.asList(candidateSigners)))) {
            throw new IOException("安装包签名与当前应用不一致");
        }
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signers(PackageInfo info) {
        if (Build.VERSION.SDK_INT < 28) return info.signatures;
        return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
    }

    private static void removeOldFiles(File directory, String prefix, String suffix) {
        long cutoff = System.currentTimeMillis() - CACHE_AGE_MS;
        File[] stale = directory.listFiles(file -> file.isFile() && file.getName().startsWith(prefix)
                && file.getName().endsWith(suffix) && file.lastModified() < cutoff);
        if (stale != null) for (File file : stale) file.delete();
    }

    private static final class NetworkFailure extends IOException {
        NetworkFailure(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
