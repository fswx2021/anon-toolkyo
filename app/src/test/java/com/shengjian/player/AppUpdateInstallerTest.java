package com.shengjian.player;

import android.app.Application;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.Signature;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class)
public class AppUpdateInstallerTest {
    private static final byte[] BODY = "abc".getBytes(StandardCharsets.US_ASCII);
    private static final String DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String PACKAGE = "com.shengjian.player";

    @Test public void downloadedBytesMatchDigestAndReportProgress() throws IOException {
        ByteArrayOutputStream copy = new ByteArrayOutputStream();
        List<Long> recorded = new ArrayList<>();
        AppUpdateInstaller.copyVerified(new ByteArrayInputStream(BODY), copy, BODY.length, DIGEST,
                new AppUpdateInstaller.Transfer(), (bytes, total) -> {
                    assertEquals(BODY.length, total);
                    recorded.add(bytes);
                });
        assertArrayEquals(BODY, copy.toByteArray());
        assertEquals(Long.valueOf(0), recorded.get(0));
        assertEquals(Long.valueOf(BODY.length), recorded.get(recorded.size() - 1));
    }

    @Test public void rejectsDamagedIncompleteOversizeOrCancelledPackages() {
        assertThrows(IOException.class, () -> copy(BODY.length, "0".repeat(64), new AppUpdateInstaller.Transfer()));
        assertThrows(IOException.class, () -> copy(BODY.length + 1, DIGEST, new AppUpdateInstaller.Transfer()));
        assertThrows(IOException.class, () -> copy(AppUpdateInstaller.MAX_APK_BYTES + 1, DIGEST,
                new AppUpdateInstaller.Transfer()));
        AppUpdateInstaller.Transfer transfer = new AppUpdateInstaller.Transfer();
        transfer.cancel();
        assertThrows(InterruptedIOException.class, () -> copy(BODY.length, DIGEST, transfer));
    }

    @Test public void candidateMustHaveExpectedPackageVersionAndExactSigners() throws IOException {
        Signature[] installed = {new Signature("aa"), new Signature("bb")};
        Signature[] sameSigners = {new Signature("bb"), new Signature("aa")};
        AppUpdateInstaller.verifyIdentity(PACKAGE, 64, installed, PACKAGE, 65, sameSigners, 65);
        assertThrows(IOException.class, () -> AppUpdateInstaller.verifyIdentity(PACKAGE, 64, installed,
                "net.example.other", 65, sameSigners, 65));
        assertThrows(IOException.class, () -> AppUpdateInstaller.verifyIdentity(PACKAGE, 64, installed,
                PACKAGE, 64, sameSigners, 64));
        assertThrows(IOException.class, () -> AppUpdateInstaller.verifyIdentity(PACKAGE, 64, installed,
                PACKAGE, 66, sameSigners, 65));
        assertThrows(IOException.class, () -> AppUpdateInstaller.verifyIdentity(PACKAGE, 64, installed,
                PACKAGE, 65, new Signature[]{new Signature("aa")}, 65));
    }

    @Test public void packageInstallerReceivesOnlyAReadGrantForTheReadyCache() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        AppUpdateInstaller installer = new AppUpdateInstaller(app);
        File staged = new File(app.getCacheDir(), "app-update-downloads/65-" + DIGEST + ".apk");
        assertThrows(IOException.class, () -> installer.installIntent(staged));
        String authority = app.getPackageName() + ".update.apk";
        ProviderInfo info = app.getPackageManager().resolveContentProvider(authority, PackageManager.GET_META_DATA);
        assertNotNull(info);
        assertFalse(info.exported);
        assertTrue(info.grantUriPermissions);
        // AndroidX FileProvider expects Android's '/' path separator, including under Robolectric on Windows.
        try (XmlResourceParser paths = info.loadXmlMetaData(app.getPackageManager(), "android.support.FILE_PROVIDER_PATHS")) {
            assertNotNull(paths);
            int elements = 0;
            for (int event = paths.getEventType(); event != XmlPullParser.END_DOCUMENT; event = paths.next()) {
                if (event != XmlPullParser.START_TAG) continue;
                if (++elements == 1) {
                    assertEquals("paths", paths.getName());
                } else {
                    assertEquals("cache-path", paths.getName());
                    assertEquals("app-update-apks/", paths.getAttributeValue(null, "path"));
                }
            }
            assertEquals(2, elements);
        }
        Intent permission = installer.installationPermissionIntent();
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permission.getAction());
        assertEquals("package:" + app.getPackageName(), permission.getDataString());
        Uri uri = Uri.parse("content://" + authority + "/verified_updates/65-" + DIGEST + ".apk");
        Intent install = AppUpdateInstaller.packageInstallIntent(uri);
        assertEquals(Intent.ACTION_INSTALL_PACKAGE, install.getAction());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, install.getFlags());
        assertEquals(uri, install.getData());
        assertEquals(uri, install.getClipData().getItemAt(0).getUri());
    }

    private static void copy(long expectedLength, String digest, AppUpdateInstaller.Transfer transfer) throws IOException {
        AppUpdateInstaller.copyVerified(new ByteArrayInputStream(BODY), new ByteArrayOutputStream(),
                expectedLength, digest, transfer, (bytes, total) -> {});
    }
}
