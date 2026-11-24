package com.shengjian.player;

import android.app.Application;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AppUpdatesTest {
    private static final String REPO = "owner/anon-toolkyo";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String LATEST_MANIFEST = "https://github.com/" + REPO
            + "/releases/latest/download/update.json";
    private static final String ASSETS = "https://github.com/" + REPO + "/releases/download/v0.29.10/";
    private static final String APK_NAME = "anon-toolkyo-0.29.10.apk";
    private static final String APK = ASSETS + APK_NAME;
    private static final String ASSET_API = "https://api.github.com/repos/" + REPO + "/releases/assets/";
    private static final String MIRROR = "https://updates.example.org/anon/";
    private static final String MIRROR_APK = MIRROR + "v0.29.10/" + APK_NAME;
    private static final String GITEE_REPO = "publisher/anon-toolkyo";
    private static final String GITEE_API = "https://gitee.com/api/v5/repos/" + GITEE_REPO + "/releases/latest";
    private static final String GITEE_ASSETS = "https://gitee.com/" + GITEE_REPO + "/releases/download/v0.29.10/";
    private static final String GITEE_APK = GITEE_ASSETS + APK_NAME;
    private static final String DIGEST = "0123456789abcdef".repeat(4);

    @Test public void usesPinnedReleaseAssetsAndOrdersUpdatesByVersionCode() throws Exception {
        List<String> calls = new ArrayList<>();
        String release = release(ASSETS + "update.json", false, DIGEST);
        String manifest = manifest(APK, 70);
        AppUpdates updates = new AppUpdates(REPO, (address, limit, asset) -> {
            calls.add(address.toString());
            return response(asset ? manifest : release);
        });

        AppUpdates.Release latest = updates.latest();
        assertEquals(List.of(API, ASSETS + "update.json"), calls);
        assertEquals("0.29.10", latest.versionName());
        assertEquals(URI.create(APK), latest.apkUrl());
        assertEquals(List.of(URI.create(APK)), latest.downloadUrls());
        assertEquals(DIGEST, latest.sha256());
        assertEquals(URI.create("https://github.com/" + REPO + "/releases/tag/v0.29.10"), latest.websiteUrl());
        assertEquals("更新说明", latest.notes());
        assertTrue(latest.newerThan(69));
        assertFalse(latest.newerThan(70));
        assertFalse(latest.newerThan(71));
    }

    @Test public void rejectsForeignManifestBeforeCallingItsUrl() throws Exception {
        String release = release("https://github.com/other/anon-toolkyo/releases/download/v0.29.10/update.json",
                false, DIGEST);
        AppUpdates updates = new AppUpdates(REPO, (address, limit, asset) -> {
            assertFalse(asset);
            return response(release);
        });
        assertThrows(IOException.class, updates::latest);
    }

    @Test public void rejectsForeignApkAndDigestMismatch() throws Exception {
        AppUpdates foreign = catalog(release(ASSETS + "update.json", false, DIGEST),
                manifest("https://github.com.other.example/" + REPO + "/releases/download/v0.29.10/" + APK_NAME, 70));
        AppUpdates digest = catalog(release(ASSETS + "update.json", false, "1".repeat(64)), manifest(APK, 70));
        assertThrows(IOException.class, foreign::latest);
        assertThrows(IOException.class, digest::latest);
    }

    @Test public void prereleasesAndUnconfiguredRepositoriesNeverFetchAManifest() throws Exception {
        String prerelease = release(ASSETS + "update.json", true, DIGEST);
        AppUpdates updates = new AppUpdates(REPO, (address, limit, asset) -> {
            assertFalse(asset);
            return response(prerelease);
        });
        assertNull(updates.latest());
        assertFalse(new AppUpdates("").configured());
        assertFalse(new AppUpdates("someone/..").configured());
        assertFalse(new AppUpdates("someone/anon-toolkyo?redirect").configured());
    }

    @Test public void allowsOnlyHttpsGithubAssetHostsForRedirects() throws Exception {
        String allowed = "https://release-assets.githubusercontent.com/object?signature=value";
        assertEquals(URI.create(allowed), AppUpdates.releaseAssetRedirect(allowed));
        assertThrows(IOException.class, () -> AppUpdates.releaseAssetRedirect(
                "http://release-assets.githubusercontent.com/object"));
        assertThrows(IOException.class, () -> AppUpdates.releaseAssetRedirect(
                "https://release-assets.githubusercontent.com.example.org/object"));
        assertThrows(IOException.class, () -> AppUpdates.releaseAssetRedirect(
                "https://user@release-assets.githubusercontent.com/object"));
    }

    @Test public void prefersOfficialAssetApisEvenWhenTheWebsiteCannotBeReached() throws Exception {
        JSONObject release = new JSONObject(release(ASSETS + "update.json", false, DIGEST));
        release.getJSONArray("assets").getJSONObject(0).put("id", 500);
        release.getJSONArray("assets").getJSONObject(1).put("id", 501);
        String body = manifest(APK, 70);
        List<URI> calls = new ArrayList<>();
        AppUpdates updates = new AppUpdates(REPO, (address, limit, asset) -> {
            calls.add(address);
            if (!asset) return response(release.toString());
            if (address.equals(URI.create(ASSET_API + "500"))) return response(body);
            throw new IOException("The website is not reachable");
        });
        AppUpdates.Release latest = updates.latest();
        assertEquals(List.of(URI.create(API), URI.create(ASSET_API + "500")), calls);
        assertEquals(List.of(URI.create(ASSET_API + "501"), URI.create(APK)), latest.downloadUrls());

        AppUpdates legacy = new AppUpdates(REPO, (address, limit, asset) -> {
            if (!asset) return response(release.toString());
            if (address.equals(URI.create(ASSET_API + "500"))) return response("{\"id\":500}");
            return response(body);
        });
        assertEquals(latest, legacy.latest());
    }

    @Test public void usesPinnedMirrorWhenGitHubIsUnavailableAndKeepsCanonicalFallback() throws Exception {
        List<String> calls = new ArrayList<>();
        String mirrorManifest = manifest(MIRROR_APK, 70);
        AppUpdates updates = new AppUpdates(REPO, MIRROR, (address, limit, asset) -> {
            calls.add(address.toString());
            if (address.toString().equals(MIRROR + "update.json")) return response(mirrorManifest);
            throw new IOException("GitHub is not reachable");
        });
        AppUpdates.Release latest = updates.latest();
        assertEquals(List.of(MIRROR + "update.json"), calls);
        assertEquals(URI.create(MIRROR_APK), latest.websiteUrl());
        assertEquals(List.of(URI.create(MIRROR_APK), URI.create(APK)), latest.downloadUrls());

        String originalManifest = manifest(APK, 70);
        String originalRelease = release(ASSETS + "update.json", false, DIGEST);
        AppUpdates reachable = new AppUpdates(REPO, MIRROR, (address, limit, asset) -> {
            if (address.toString().startsWith(MIRROR)) return null;
            return response(asset ? originalManifest : originalRelease);
        });
        assertEquals(List.of(URI.create(APK), URI.create(MIRROR_APK)), reachable.latest().downloadUrls());
    }

    @Test public void downloadsFromGiteeWithoutContactingGithubWhenDomesticReleaseIsNewer() throws Exception {
        List<String> calls = new ArrayList<>();
        String release = release(ASSETS + "update.json", false, DIGEST);
        String manifest = manifest(APK, 70);
        AppUpdates updates = new AppUpdates(REPO, "", GITEE_REPO, (address, limit, asset) -> {
            calls.add(address.toString());
            if (address.toString().equals(GITEE_API)) return response(release);
            assertEquals(GITEE_ASSETS + "update.json", address.toString());
            return response(manifest);
        });
        AppUpdates.Release latest = updates.latest(69);
        assertEquals(List.of(GITEE_API, GITEE_ASSETS + "update.json"), calls);
        assertEquals(List.of(URI.create(GITEE_APK), URI.create(APK)), latest.downloadUrls());
        assertEquals(URI.create("https://gitee.com/" + GITEE_REPO + "/releases/tag/v0.29.10"), latest.websiteUrl());
        assertEquals(DIGEST, latest.sha256());
    }

    @Test public void fallsBackToGithubWhenGiteeIsStaleInvalidOrInaccessible() throws Exception {
        String release = release(ASSETS + "update.json", false, DIGEST);
        String githubManifest = manifest(APK, 70);
        for (String giteeManifest : new String[] {manifest(GITEE_APK, 69),
                manifest(GITEE_APK.replace("/publisher/", "/foreign/"), 70), null}) {
            AppUpdates updates = new AppUpdates(REPO, "", GITEE_REPO, (address, limit, asset) -> {
                String value = address.toString();
                if (value.equals(GITEE_API)) {
                    if (giteeManifest == null) throw new IOException("offline");
                    return response(release);
                }
                if (value.startsWith(GITEE_ASSETS)) return response(giteeManifest);
                return response(asset ? githubManifest : release);
            });
            assertEquals(URI.create(APK), updates.latest(69).apkUrl());
        }
    }

    @Test public void findsAStableGiteeReleaseWhenLatestIsAPreview() throws Exception {
        String preview = release(ASSETS + "update.json", true, DIGEST);
        String releases = new JSONArray().put(new JSONObject(preview))
                .put(new JSONObject(release(ASSETS + "update.json", false, DIGEST))).toString();
        String manifest = manifest(GITEE_APK, 70);
        AppUpdates updates = new AppUpdates(REPO, "", GITEE_REPO, (address, limit, asset) -> {
            if (address.toString().equals(GITEE_API)) return response(preview);
            if (address.toString().equals(GITEE_API.replace("/latest", "?direction=desc&per_page=8"))) {
                return response(releases);
            }
            assertEquals(GITEE_ASSETS + "update.json", address.toString());
            return response(manifest);
        });
        assertEquals(URI.create(GITEE_APK), updates.latest(69).apkUrl());
    }

    @Test public void giteeAssetRedirectsStayOnTheRepositoryAndKnownAttachmentCdn() throws Exception {
        URI initial = URI.create(GITEE_APK);
        String attachment = "/" + GITEE_REPO + "/attach_files/3793043/download/" + APK_NAME;
        URI intermediate = AppUpdates.apkRedirect(initial, initial, attachment);
        assertEquals(URI.create("https://gitee.com" + attachment), intermediate);
        String cdn = "https://foruda.gitee.com/attach_file/1795360714977508753/" + APK_NAME + "?expires=123";
        assertEquals(URI.create(cdn), AppUpdates.apkRedirect(initial, intermediate, cdn));
        assertThrows(IOException.class, () -> AppUpdates.apkRedirect(initial, initial,
                attachment.replace("/publisher/", "/foreign/")));
        assertThrows(IOException.class, () -> AppUpdates.apkRedirect(initial, intermediate,
                cdn.replace("foruda.gitee.com", "foruda.gitee.com.example.org")));
        assertThrows(IOException.class, () -> AppUpdates.apkRedirect(initial, intermediate,
                cdn.replace(APK_NAME, "different.apk")));
    }

    @Test public void rejectsInvalidMirrorBasesAndForeignApksOrRedirects() throws Exception {
        assertFalse(new AppUpdates("", "http://updates.example.org/anon/").configured());
        assertFalse(new AppUpdates("", MIRROR + "../").configured());
        assertFalse(new AppUpdates("", MIRROR + "?some=param").configured());
        assertFalse(new AppUpdates("", "https://updates.example.org/anon").configured());
        for (String apk : List.of(APK, MIRROR_APK.replace("/anon/", "/other/"), MIRROR_APK + "?q=1")) {
            String manifest = manifest(apk, 70);
            AppUpdates injected = new AppUpdates("", MIRROR, (address, limit, asset) -> response(manifest));
            assertThrows(IOException.class, injected::latest);
        }
        URI mirrorApk = URI.create(MIRROR_APK);
        String cdn = "https://release-assets.githubusercontent.com/object";
        assertThrows(IOException.class, () -> AppUpdates.apkRedirect(mirrorApk, mirrorApk, cdn));
        assertEquals(URI.create(cdn), AppUpdates.apkRedirect(URI.create(APK), URI.create(APK), cdn));
        URI api = URI.create(ASSET_API + "501");
        assertEquals(URI.create(cdn), AppUpdates.apkRedirect(api, api, cdn));
        assertThrows(IOException.class, () -> AppUpdates.apkRedirect(URI.create(APK), URI.create(MIRROR_APK), cdn));
    }

    @Test public void readsThePinnedLatestManifestWhenApiAccessFails() throws Exception {
        List<String> calls = new ArrayList<>();
        String manifest = manifest(APK, 70);
        AppUpdates updates = new AppUpdates(REPO, (address, limit, asset) -> {
            calls.add(address.toString());
            if (!asset) throw new IOException("HTTP 403");
            return new AppUpdates.Response(manifest, "0.29.10");
        });
        AppUpdates.Release latest = updates.latest();
        assertEquals(List.of(API, LATEST_MANIFEST), calls);
        assertEquals(URI.create(APK), latest.apkUrl());
        assertTrue(latest.newerThan(69));

        String foreignApk = manifest(APK.replace("/owner/", "/other/"), 70);
        AppUpdates foreign = new AppUpdates(REPO, (address, limit, asset) -> {
            if (!asset) throw new IOException("HTTP 403");
            return new AppUpdates.Response(foreignApk, "0.29.10");
        });
        assertThrows(IOException.class, foreign::latest);

        AppUpdates wrongTag = new AppUpdates(REPO, (address, limit, asset) -> {
            if (!asset) throw new IOException("HTTP 403");
            return new AppUpdates.Response(manifest, "0.29.9");
        });
        assertThrows(IOException.class, wrongTag::latest);
    }

    @Test public void latestManifestRedirectAllowsOnlyAnOfficialCdnOrTheSameRepositoryManifest() throws Exception {
        URI original = URI.create(LATEST_MANIFEST);
        String sameManifest = ASSETS + "update.json";
        assertEquals(new AppUpdates.ManifestRedirect(URI.create(sameManifest), "0.29.10"),
                AppUpdates.latestManifestRedirect(original, URI.create(sameManifest).getRawPath()));
        String cdn = "https://release-assets.githubusercontent.com/object?signature=value";
        assertEquals(new AppUpdates.ManifestRedirect(URI.create(cdn), null),
                AppUpdates.latestManifestRedirect(original, cdn));
        assertThrows(IOException.class, () -> AppUpdates.latestManifestRedirect(
                original, sameManifest.replace("/owner/", "/other/")));
        assertThrows(IOException.class, () -> AppUpdates.latestManifestRedirect(
                original, sameManifest.replace("v0.29.10", "v0.29.10-beta")));
        assertThrows(IOException.class, () -> AppUpdates.latestManifestRedirect(original, APK));
        assertThrows(IOException.class, () -> AppUpdates.latestManifestRedirect(
                original, sameManifest + "?redirect=external"));
        assertThrows(IOException.class, () -> AppUpdates.latestManifestRedirect(
                original, cdn.replace(".com/", ".com.example.org/")));
    }

    private static AppUpdates catalog(String release, String manifest) {
        return new AppUpdates(REPO, (address, limit, asset) -> response(asset ? manifest : release));
    }

    private static AppUpdates.Response response(String body) {
        return new AppUpdates.Response(body, null);
    }

    private static String release(String manifestUrl, boolean prerelease, String apkDigest) throws Exception {
        return new JSONObject().put("tag_name", "v0.29.10").put("prerelease", prerelease)
                .put("assets", new JSONArray()
                        .put(new JSONObject().put("name", "update.json").put("browser_download_url", manifestUrl))
                        .put(new JSONObject().put("name", APK_NAME).put("browser_download_url", APK)
                                .put("digest", "sha256:" + apkDigest)))
                .toString();
    }

    private static String manifest(String apkUrl, int code) throws Exception {
        return new JSONObject().put("versionName", "0.29.10").put("versionCode", code)
                .put("apkUrl", apkUrl).put("sha256", DIGEST).put("notes", "更新说明").toString();
    }
}
