package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class AppUpdates {
    static final long CHECK_INTERVAL_MS = TimeUnit.DAYS.toMillis(1);
    private static final int RELEASE_LIMIT = 256 * 1024;
    private static final int MANIFEST_LIMIT = 32 * 1024;
    private static final int TIMEOUT_MS = 8_000;
    private static final String MANIFEST = "update.json";
    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,9}){2}");
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");

    interface Fetch {
        String get(URI address, int limit, boolean asset) throws IOException;
    }

    record Release(int versionCode, String versionName, URI apkUrl, String notes) {
        boolean newerThan(int installedVersionCode) { return versionCode > installedVersionCode; }
    }

    private final String repository;
    private final Fetch fetch;

    AppUpdates(String repository) {
        this(repository, AppUpdates::request);
    }

    AppUpdates(String repository, Fetch fetch) {
        this.repository = repository;
        this.fetch = fetch;
    }

    boolean configured() {
        return REPOSITORY.matcher(repository).matches()
                && !repository.endsWith("/.") && !repository.endsWith("/..");
    }

    static boolean due(long now, long lastCheck) {
        return lastCheck <= 0 || now < lastCheck || now - lastCheck >= CHECK_INTERVAL_MS;
    }

    Release latest() throws IOException {
        if (!configured()) throw new IOException("当前安装包未设置更新仓库");
        String latest = fetch.get(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"),
                RELEASE_LIMIT, false);
        if (latest == null) return null;
        try {
            JSONObject release = new JSONObject(latest);
            String tag = release.getString("tag_name");
            if (release.optBoolean("draft") || release.optBoolean("prerelease")
                    || !tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) return null;
            JSONArray assets = release.getJSONArray("assets");
            JSONObject manifestAsset = asset(assets, MANIFEST);
            if (manifestAsset == null) return null;
            String versionName = tag.substring(1);
            String apkName = "anon-toolkyo-" + versionName + ".apk";
            JSONObject apkAsset = asset(assets, apkName);
            if (apkAsset == null) throw invalidRelease();

            URI manifest = assetUrl(manifestAsset.getString("browser_download_url"), tag, MANIFEST);
            URI apk = assetUrl(apkAsset.getString("browser_download_url"), tag, apkName);
            String body = fetch.get(manifest, MANIFEST_LIMIT, true);
            if (body == null) throw invalidRelease();
            JSONObject descriptor = new JSONObject(body);
            Object number = descriptor.get("versionCode");
            if (!(number instanceof Integer versionCode) || versionCode <= 0
                    || !versionName.equals(descriptor.getString("versionName"))
                    || !apk.equals(assetUrl(descriptor.getString("apkUrl"), tag, apkName))) {
                throw invalidRelease();
            }
            String sha256 = descriptor.getString("sha256");
            String registeredDigest = apkAsset.optString("digest", "");
            if (!SHA256.matcher(sha256).matches()
                    || (!registeredDigest.isEmpty() && !"null".equals(registeredDigest)
                        && !("sha256:" + sha256).equalsIgnoreCase(registeredDigest))) {
                throw invalidRelease();
            }
            String notes = descriptor.optString("notes", "").trim();
            if (notes.length() > 6_000) notes = notes.substring(0, 5_999) + "…";
            return new Release(versionCode, versionName, apk, notes);
        } catch (JSONException malformed) {
            throw invalidRelease();
        }
    }

    private static JSONObject asset(JSONArray assets, String name) {
        for (int i = 0; i < assets.length(); i++) {
            JSONObject candidate = assets.optJSONObject(i);
            if (candidate != null && name.equals(candidate.optString("name"))) return candidate;
        }
        return null;
    }

    private URI assetUrl(String value, String tag, String name) throws IOException {
        String prefix = "https://github.com/" + repository + "/";
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())
                || !value.substring(prefix.length()).equals("releases/download/" + tag + "/" + name)) {
            throw invalidRelease();
        }
        return URI.create(prefix + value.substring(prefix.length()));
    }

    private static IOException invalidRelease() {
        return new IOException("发布信息不完整或校验不一致");
    }

    private static String request(URI address, int limit, boolean asset) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        URI current = address;
        for (int redirects = 0; redirects <= 2; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(remainingMillis(deadline));
            connection.setReadTimeout(remainingMillis(deadline));
            connection.setRequestProperty("User-Agent", "anon-toolkyo/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept", asset ? "application/json" : "application/vnd.github+json");
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
                if (asset && (status == 302 || status == 303 || status == 307 || status == 308)) {
                    current = releaseAssetRedirect(connection.getHeaderField("Location"));
                    continue;
                }
                if (status == 403 || status == 429) throw new IOException("更新服务暂时限制了访问，请稍后再试");
                if (status != HttpURLConnection.HTTP_OK) throw new IOException("更新服务暂时不可用");
                if (connection.getContentLengthLong() > limit) throw invalidRelease();
                try (InputStream input = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    for (int count; (count = input.read(buffer)) != -1; ) {
                        remainingMillis(deadline);
                        if (bytes.size() + count > limit) throw invalidRelease();
                        bytes.write(buffer, 0, count);
                    }
                    return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("更新文件跳转过多");
    }

    private static int remainingMillis(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("检查更新已取消");
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new IOException("检查更新超时，请稍后再试");
        return (int) remaining;
    }

    static URI releaseAssetRedirect(String location) throws IOException {
        if (location == null) throw invalidRelease();
        URI redirect;
        try {
            redirect = URI.create(location);
        } catch (IllegalArgumentException malformed) {
            throw invalidRelease();
        }
        String host = redirect.getHost();
        if (!"https".equalsIgnoreCase(redirect.getScheme())
                || (!"release-assets.githubusercontent.com".equalsIgnoreCase(host)
                    && !"objects.githubusercontent.com".equalsIgnoreCase(host))
                || redirect.getUserInfo() != null || redirect.getPort() != -1 || redirect.getFragment() != null
                || redirect.getRawPath().isEmpty()) throw invalidRelease();
        return redirect;
    }
}
