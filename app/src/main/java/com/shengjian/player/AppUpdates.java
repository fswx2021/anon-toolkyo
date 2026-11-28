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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class AppUpdates {
    private static final int RELEASE_LIMIT = 256 * 1024;
    private static final int MANIFEST_LIMIT = 32 * 1024;
    private static final int NOTES_LIMIT = 6_000;
    private static final int TIMEOUT_MS = 8_000;
    private static final String MANIFEST = "update.json";
    private static final String LATEST_MANIFEST_PATH = "/releases/latest/download/" + MANIFEST;
    private static final String RELEASE_ASSET_PATH = "/releases/download/";
    private static final String GITEE_WEB = "https://gitee.com/";
    private static final Pattern REPOSITORY = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern VERSION = Pattern.compile("[0-9]{1,9}(?:\\.[0-9]{1,9}){2}");
    private static final Pattern SHA256 = Pattern.compile("[a-fA-F0-9]{64}");
    private enum Channel { GITHUB, GITEE, MIRROR }

    interface Fetch {
        Response get(URI address, int limit, boolean asset) throws IOException;
    }

    record Response(String body, String versionFromRedirect) { }
    record ManifestRedirect(URI url, String version) { }

    record Release(int versionCode, String versionName, URI apkUrl, String notes,
                   String sha256, List<URI> downloadUrls) {
        Release {
            downloadUrls = List.copyOf(downloadUrls);
        }

        boolean newerThan(int installedVersionCode) { return versionCode > installedVersionCode; }

        URI websiteUrl() {
            String url = apkUrl.toString();
            int releasePath = url.indexOf(RELEASE_ASSET_PATH);
            if (releasePath < 0 || (!githubHost(apkUrl) && !giteeHost(apkUrl))) return apkUrl;
            return URI.create(url.substring(0, releasePath) + "/releases/tag/v" + versionName);
        }
    }

    private final String repository;
    private final String giteeRepository;
    private final URI mirrorBase;
    private final Fetch fetch;

    AppUpdates(String repository) {
        this(repository, "");
    }

    AppUpdates(String repository, String mirror) {
        this(repository, mirror, AppUpdates::request);
    }

    AppUpdates(String repository, Fetch fetch) {
        this(repository, "", fetch);
    }

    AppUpdates(String repository, String mirror, Fetch fetch) {
        this(repository, mirror, "", fetch);
    }

    AppUpdates(String repository, String mirror, String giteeRepository) {
        this(repository, mirror, giteeRepository, AppUpdates::request);
    }

    AppUpdates(String repository, String mirror, String giteeRepository, Fetch fetch) {
        this.repository = repository;
        this.giteeRepository = giteeRepository;
        this.mirrorBase = mirrorDirectory(mirror);
        this.fetch = fetch;
    }

    boolean configured() {
        return githubConfigured() || validRepository(giteeRepository) || mirrorBase != null;
    }

    private boolean githubConfigured() {
        return validRepository(repository);
    }

    private static boolean validRepository(String value) {
        return REPOSITORY.matcher(value).matches() && !value.endsWith("/.") && !value.endsWith("/..");
    }

    private static URI mirrorDirectory(String mirror) {
        if (mirror.isEmpty()) return null;
        try {
            URI address = URI.create(mirror);
            String path = address.getRawPath();
            if (trustedHttps(address) && !githubHost(address) && !giteeHost(address) && address.getRawQuery() == null
                    && path.matches("/(?:[A-Za-z0-9._~-]+/)*") && address.equals(address.normalize())) {
                return address;
            }
        } catch (IllegalArgumentException malformed) {
            // A malformed optional mirror leaves GitHub as the update source.
        }
        return null;
    }

    Release latest() throws IOException {
        return latest(0);
    }

    Release latest(int installedVersionCode) throws IOException {
        if (!configured()) throw new IOException("当前安装包未设置更新仓库");
        Release available = null;
        IOException failure = null;
        if (validRepository(giteeRepository)) {
            try {
                available = latestGitee();
                if (available != null && available.newerThan(installedVersionCode)) return available;
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                failure = unavailable;
            }
        }
        if (mirrorBase != null) {
            try {
                Response response = fetch.get(mirrorBase.resolve(MANIFEST), MANIFEST_LIMIT, true);
                if (response != null) {
                    Release mirror = parseManifest(response.body(), null, "", Channel.MIRROR, 0, "");
                    if (mirror.newerThan(installedVersionCode)) return mirror;
                    if (available == null || mirror.newerThan(available.versionCode())) available = mirror;
                }
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                failure = unavailable;
            }
        }
        if (githubConfigured()) {
            try {
                Release github = latestGithub();
                if (github != null && (available == null || github.newerThan(available.versionCode()))) return github;
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
                failure = unavailable;
            }
        }
        if (available != null) return available;
        if (failure != null) throw failure;
        return null;
    }

    private Release latestGitee() throws IOException {
        String api = GITEE_WEB + "api/v5/repos/" + giteeRepository + "/releases";
        Response latest = fetch.get(URI.create(api + "/latest"), RELEASE_LIMIT, false);
        if (latest == null) return null;
        try {
            JSONObject release = new JSONObject(latest.body());
            if (!release.optBoolean("draft") && !release.optBoolean("prerelease")) return giteeManifest(release);
            Response recent = fetch.get(URI.create(api + "?direction=desc&per_page=8"), RELEASE_LIMIT, false);
            if (recent == null) return null;
            JSONArray releases = new JSONArray(recent.body());
            for (int i = 0; i < Math.min(releases.length(), 8); i++) {
                JSONObject candidate = releases.getJSONObject(i);
                if (candidate.optBoolean("draft") || candidate.optBoolean("prerelease")) continue;
                Release stable = giteeManifest(candidate);
                if (stable != null) return stable;
            }
            return null;
        } catch (JSONException malformed) {
            throw invalidRelease();
        }
    }

    private Release giteeManifest(JSONObject release) throws IOException, JSONException {
        String tag = release.getString("tag_name");
        if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) return null;
        String version = tag.substring(1);
        JSONArray assets = release.getJSONArray("assets");
        if (asset(assets, MANIFEST) == null || asset(assets, apkName(version)) == null) return null;
        Response manifest = fetch.get(giteeAsset(tag, MANIFEST), MANIFEST_LIMIT, true);
        if (manifest == null) throw invalidRelease();
        return parseManifest(manifest.body(), version, "", Channel.GITEE, 0, release.opt("body"));
    }

    private URI giteeAsset(String tag, String filename) {
        return URI.create(GITEE_WEB + giteeRepository + RELEASE_ASSET_PATH + tag + "/" + filename);
    }

    private static String apkName(String version) {
        return "anon-toolkyo-" + version + ".apk";
    }

    private Release latestGithub() throws IOException {
        Response latest;
        try {
            latest = fetch.get(URI.create("https://api.github.com/repos/" + repository + "/releases/latest"),
                    RELEASE_LIMIT, false);
        } catch (IOException apiUnavailable) {
            if (Thread.currentThread().isInterrupted()) throw apiUnavailable;
            return latestManifest();
        }
        if (latest == null) return null;
        try {
            JSONObject release = new JSONObject(latest.body());
            String tag = release.getString("tag_name");
            if (release.optBoolean("draft") || release.optBoolean("prerelease")
                    || !tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) return null;
            JSONArray assets = release.getJSONArray("assets");
            JSONObject manifestAsset = asset(assets, MANIFEST);
            if (manifestAsset == null) return null;
            String versionName = tag.substring(1);
            String apkName = apkName(versionName);
            JSONObject apkAsset = asset(assets, apkName);
            if (apkAsset == null) throw invalidRelease();

            URI manifest = assetUrl(manifestAsset.getString("browser_download_url"), tag, MANIFEST);
            assetUrl(apkAsset.getString("browser_download_url"), tag, apkName);
            return fetchGithubManifest(manifest, manifestAsset.optLong("id"), versionName,
                    apkAsset.optString("digest", ""), apkAsset.optLong("id"), release.opt("body"));
        } catch (JSONException malformed) {
            throw invalidRelease();
        }
    }

    private Release fetchGithubManifest(URI browserUrl, long manifestAssetId, String version,
                                        String digest, long apkAssetId, Object releaseBody) throws IOException {
        if (manifestAssetId > 0) {
            try {
                Response response = fetch.get(githubAssetApi(manifestAssetId), MANIFEST_LIMIT, true);
                if (response != null) {
                    return parseManifest(response.body(), version, digest, Channel.GITHUB, apkAssetId, releaseBody);
                }
            } catch (IOException unavailable) {
                if (Thread.currentThread().isInterrupted()) throw unavailable;
            }
        }
        Response response = fetch.get(browserUrl, MANIFEST_LIMIT, true);
        if (response == null) throw invalidRelease();
        return parseManifest(response.body(), version, digest, Channel.GITHUB, apkAssetId, releaseBody);
    }

    private URI githubAssetApi(long assetId) {
        return URI.create("https://api.github.com/repos/" + repository + "/releases/assets/" + assetId);
    }

    private Release latestManifest() throws IOException {
        URI address = URI.create("https://github.com/" + repository + LATEST_MANIFEST_PATH);
        Response response = fetch.get(address, MANIFEST_LIMIT, true);
        if (response == null) return null;
        return parseManifest(response.body(), response.versionFromRedirect(), "", Channel.GITHUB, 0, "");
    }

    private Release parseManifest(String body, String expectedVersion, String registeredDigest,
                                  Channel channel, long githubAssetId, Object releaseBody) throws IOException {
        try {
            JSONObject descriptor = new JSONObject(body);
            Object number = descriptor.get("versionCode");
            String versionName = descriptor.getString("versionName");
            if (!(number instanceof Integer versionCode) || versionCode <= 0
                    || !VERSION.matcher(versionName).matches()
                    || (expectedVersion != null && !expectedVersion.equals(versionName))) {
                throw invalidRelease();
            }
            String tag = "v" + versionName;
            String filename = apkName(versionName);
            URI mirrorApk = mirrorBase == null ? null : mirrorBase.resolve(tag + "/" + filename);
            String declaredUrl = descriptor.getString("apkUrl");
            URI apk;
            if (channel == Channel.MIRROR) {
                if (!mirrorApk.toString().equals(declaredUrl)) throw invalidRelease();
                apk = mirrorApk;
            } else if (channel == Channel.GITEE) {
                apk = giteeAsset(tag, filename);
                if (!apk.toString().equals(declaredUrl)) {
                    if (!githubConfigured()) throw invalidRelease();
                    assetUrl(declaredUrl, tag, filename);
                }
            } else {
                apk = assetUrl(declaredUrl, tag, filename);
            }
            String sha256 = descriptor.getString("sha256");
            if (!SHA256.matcher(sha256).matches()
                    || (!registeredDigest.isEmpty() && !"null".equals(registeredDigest)
                        && !("sha256:" + sha256).equalsIgnoreCase(registeredDigest))) {
                throw invalidRelease();
            }
            String notes = normalizeNotes(descriptor.opt("notes"));
            if (notes.isEmpty()) notes = normalizeNotes(releaseBody);
            List<URI> routes = new ArrayList<>();
            if (githubAssetId > 0) routes.add(githubAssetApi(githubAssetId));
            routes.add(apk);
            if (channel != Channel.GITHUB && githubConfigured()) {
                routes.add(URI.create("https://github.com/" + repository + RELEASE_ASSET_PATH + tag + "/" + filename));
            } else if (channel == Channel.GITHUB && mirrorApk != null) {
                routes.add(mirrorApk);
            }
            return new Release(versionCode, versionName, apk, notes, sha256.toLowerCase(Locale.ROOT), routes);
        } catch (JSONException malformed) {
            throw invalidRelease();
        }
    }

    private static String normalizeNotes(Object value) {
        if (!(value instanceof String text)) return "";
        String notes = text.replace("\r\n", "\n").replace('\r', '\n').strip();
        if (notes.length() <= NOTES_LIMIT) return notes;
        int end = NOTES_LIMIT - 1;
        if (Character.isHighSurrogate(notes.charAt(end - 1))) end--;
        return notes.substring(0, end) + "…";
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

    private static Response request(URI address, int limit, boolean asset) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(TIMEOUT_MS);
        URI current = address;
        String manifestVersion = null;
        for (int redirects = 0; redirects <= 2; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(remainingMillis(deadline));
            connection.setReadTimeout(remainingMillis(deadline));
            connection.setRequestProperty("User-Agent", "anon-toolkyo/" + BuildConfig.VERSION_NAME);
            String accept = asset || giteeHost(address) ? "application/json" : "application/vnd.github+json";
            if (asset && "api.github.com".equalsIgnoreCase(address.getHost())) accept = "application/octet-stream";
            connection.setRequestProperty("Accept", accept);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
                if (asset && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
                    String location = connection.getHeaderField("Location");
                    if (giteeHost(address)) {
                        current = giteeAssetRedirect(address, current, location);
                    } else if (!githubHost(address)) {
                        throw invalidRelease();
                    } else if (redirects == 0 && address.getRawPath().endsWith(LATEST_MANIFEST_PATH)) {
                        ManifestRedirect redirect = latestManifestRedirect(address, location);
                        current = redirect.url();
                        manifestVersion = redirect.version();
                    } else {
                        current = releaseAssetRedirect(location);
                    }
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
                    return new Response(new String(bytes.toByteArray(), StandardCharsets.UTF_8), manifestVersion);
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

    private static URI redirectUri(String location) throws IOException {
        try {
            if (location == null) throw invalidRelease();
            return URI.create(location);
        } catch (IllegalArgumentException malformed) {
            throw invalidRelease();
        }
    }

    static ManifestRedirect latestManifestRedirect(URI original, String location) throws IOException {
        URI redirect = original.resolve(redirectUri(location));
        if ("github.com".equalsIgnoreCase(redirect.getHost())) {
            String path = original.getRawPath();
            String repoPath = path.substring(0, path.length() - LATEST_MANIFEST_PATH.length());
            String prefix = repoPath + RELEASE_ASSET_PATH;
            String target = redirect.getRawPath();
            String suffix = "/" + MANIFEST;
            if (!"https".equalsIgnoreCase(redirect.getScheme())
                    || redirect.getUserInfo() != null || redirect.getPort() != -1
                    || redirect.getRawQuery() != null || redirect.getFragment() != null
                    || !target.regionMatches(true, 0, prefix, 0, prefix.length())
                    || !target.endsWith(suffix)
                    || target.length() <= prefix.length() + suffix.length()) throw invalidRelease();
            String tag = target.substring(prefix.length(), target.length() - suffix.length());
            if (!tag.startsWith("v") || !VERSION.matcher(tag.substring(1)).matches()) throw invalidRelease();
            return new ManifestRedirect(redirect, tag.substring(1));
        }
        return new ManifestRedirect(releaseAssetRedirect(location), null);
    }

    static URI releaseAssetRedirect(String location) throws IOException {
        URI redirect = redirectUri(location);
        String host = redirect.getHost();
        if (!trustedHttps(redirect)
                || (!"release-assets.githubusercontent.com".equalsIgnoreCase(host)
                    && !"objects.githubusercontent.com".equalsIgnoreCase(host))
                || redirect.getRawPath().isEmpty()) throw invalidRelease();
        return redirect;
    }

    static URI apkRedirect(URI initialAddress, URI currentAddress, String location) throws IOException {
        if (giteeHost(initialAddress)) return giteeAssetRedirect(initialAddress, currentAddress, location);
        if (!trustedHttps(initialAddress) || !githubHost(initialAddress)) throw invalidRelease();
        if (!initialAddress.equals(currentAddress)) releaseAssetRedirect(currentAddress.toString());
        return releaseAssetRedirect(location);
    }

    private static URI giteeAssetRedirect(URI initial, URI current, String location) throws IOException {
        String path = initial.getRawPath();
        int split = path.indexOf(RELEASE_ASSET_PATH);
        if (!trustedHttps(initial) || initial.getRawQuery() != null || split < 1
                || !validRepository(path.substring(1, split))) throw invalidRelease();
        String prefix = path.substring(0, split) + "/attach_files/";
        String filename = path.substring(path.lastIndexOf('/') + 1);
        URI target = current.resolve(redirectUri(location));
        if (!trustedHttps(target)) throw invalidRelease();
        String targetPath = target.getRawPath();
        if (initial.equals(current) && giteeHost(target) && target.getRawQuery() == null
                && targetPath.startsWith(prefix) && targetPath.substring(prefix.length())
                        .matches("[0-9]+/download/" + Pattern.quote(filename))) return target;
        String currentPath = current.getRawPath();
        boolean fromAttachment = giteeHost(current) && currentPath.startsWith(prefix)
                && currentPath.substring(prefix.length()).matches("[0-9]+/download/" + Pattern.quote(filename));
        if ((initial.equals(current) || fromAttachment) && "foruda.gitee.com".equalsIgnoreCase(target.getHost())
                && targetPath.matches("/attach_file/[0-9]+/[A-Za-z0-9._-]+")
                && filename.equalsIgnoreCase(targetPath.substring(targetPath.lastIndexOf('/') + 1))) return target;
        throw invalidRelease();
    }

    private static boolean giteeHost(URI address) {
        return "gitee.com".equalsIgnoreCase(address.getHost());
    }

    private static boolean githubHost(URI address) {
        String host = address.getHost();
        return "github.com".equalsIgnoreCase(host) || "api.github.com".equalsIgnoreCase(host);
    }

    private static boolean trustedHttps(URI address) {
        return "https".equalsIgnoreCase(address.getScheme()) && address.getHost() != null
                && address.getUserInfo() == null && address.getPort() == -1 && address.getFragment() == null;
    }
}
