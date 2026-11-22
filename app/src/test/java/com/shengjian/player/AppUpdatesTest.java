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

    @Test public void limitsAutomaticChecksToOncePerDayAndToleratesClockCorrection() {
        long last = 4_000;
        assertFalse(AppUpdates.due(last + AppUpdates.CHECK_INTERVAL_MS - 1, last));
        assertTrue(AppUpdates.due(last + AppUpdates.CHECK_INTERVAL_MS, last));
        assertTrue(AppUpdates.due(last - 1, last));
        assertTrue(AppUpdates.due(last, 0));
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
