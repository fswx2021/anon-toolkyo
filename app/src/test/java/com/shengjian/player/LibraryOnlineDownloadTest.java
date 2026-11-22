package com.shengjian.player;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaMetadataRetriever;
import android.util.AtomicFile;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class,
        shadows = {LibraryOnlineDownloadTest.WavMetadata.class, LibraryOnlineDownloadTest.AtomicFileReplace.class})
public class LibraryOnlineDownloadTest {
    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void existingTrackBecomesOfflineAndKeepsIdentityAndLyricsAcrossRestarts() throws Exception {
        byte[] wav = wave();
        AtomicInteger requests = new AtomicInteger();
        File appFiles = files.newFolder();
        try (LocalResponse server = new LocalResponse("audio/wav", wav, requests)) {
            MusicOnlineEngine.Config config = server.config();
            MusicOnlineEngine.Song song = song();
            Library library = library(appFiles);
            Track online = library.addOnline(song, config);
            library.editMetadata(online, "Edited title", "Edited artist");
            String lyric = "[00:00.50]Existing karaoke line";
            library.saveOnlineLyrics(online.id, online.url, lyric);

            Track local = library.downloadOnline(online, config, new MusicImportTask("Download"));

            assertTrue(local.isLocal());
            assertEquals(online.id, local.id);
            assertEquals("Edited title", local.title);
            assertEquals("Edited artist", local.artist);
            assertEquals("WAV", local.format);
            assertArrayEquals(wav, Files.readAllBytes(new File(URI.create(local.url)).toPath()));
            assertEquals(lyric, readText(local.lyricsFile));
            assertEquals(1, library.tracks().size());
            assertEquals(1, requests.get());

            Library reopened = library(appFiles);
            assertEquals(local.id, reopened.addOnline(song, config).id);
            assertEquals(local.url, reopened.downloadOnline(song, config, null).url);
            assertEquals(1, reopened.tracks().size());
            assertEquals(1, requests.get());
            String catalog = readText(new File(appFiles, "library/tracks.json"));
            assertFalse(catalog.contains("/rest/"));
            assertFalse(catalog.contains("fixture-password"));
            assertFalse(catalog.contains("fixture-user"));
        }
    }

    @Test public void playlistFailureAndCancellationDoNotAddAnOfflineTrack() throws Exception {
        byte[] playlist = "#EXTM3U\nsegment.ts\n".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        File appFiles = files.newFolder();
        try (LocalResponse server = new LocalResponse("application/vnd.apple.mpegurl", playlist, requests)) {
            MusicOnlineEngine.Config config = server.config();
            Library library = library(appFiles);
            MusicOnlineEngine.Song song = song();
            Track online = library.addOnline(song, config);
            assertThrows(IOException.class, () -> library.downloadOnline(online, config, null));
            assertEquals(1, requests.get());
            assertEquals(1, library.tracks().size());
            assertEquals(online.url, library.tracks().get(0).url);
            assertEquals(online.id, library.tracks().get(0).id);
            assertEquals(0, new File(appFiles, "library").listFiles(File::isDirectory).length);

            MusicImportTask cancelled = new MusicImportTask("Download");
            cancelled.cancel();
            assertThrows(IOException.class, () -> library.downloadOnline(song, config, cancelled));
            assertEquals(1, requests.get());
        }
    }

    private static Library library(File appFiles) throws Exception {
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return appFiles; }
        };
        var constructor = Library.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static MusicOnlineEngine.Song song() {
        return new MusicOnlineEngine.Song(MusicOnlineEngine.NAVIDROME, "fixture-song", "Search title",
                "Search artist", "", "", 1_000);
    }

    private static String readText(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static final class LocalResponse implements AutoCloseable {
        private final ServerSocket server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));

        LocalResponse(String mime, byte[] content, AtomicInteger requests) throws IOException {
            byte[] header = ("HTTP/1.1 200 OK\r\nConnection: close\r\nContent-Type: " + mime
                    + "\r\nContent-Length: " + content.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            Thread worker = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket peer = server.accept()) {
                        BufferedReader request = new BufferedReader(new InputStreamReader(peer.getInputStream(), StandardCharsets.US_ASCII));
                        String start = request.readLine();
                        for (String line; (line = request.readLine()) != null && !line.isEmpty();) {
                            // Read through the request headers before returning the response.
                        }
                        if (start != null && start.startsWith("GET /rest/download.view?")) {
                            requests.incrementAndGet();
                            peer.getOutputStream().write(header);
                            peer.getOutputStream().write(content);
                        }
                    } catch (IOException finished) {
                        return;
                    }
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        MusicOnlineEngine.Config config() {
            return new MusicOnlineEngine.Config("http://127.0.0.1:" + server.getLocalPort(),
                    "fixture-user", "fixture-password");
        }

        @Override public void close() throws IOException { server.close(); }
    }

    private static byte[] wave() {
        int sampleRate = 8_000;
        byte[] samples = new byte[sampleRate * 2];
        return ByteBuffer.allocate(44 + samples.length).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples.length).put(samples).array();
    }

    @Implements(MediaMetadataRetriever.class)
    public static class WavMetadata extends ShadowMediaMetadataRetriever {
        @Implementation @Override protected void setDataSource(String path) {
            try (var input = new DataInputStream(Files.newInputStream(new File(path).toPath()))) {
                byte[] riff = new byte[12];
                input.readFully(riff);
                String header = new String(riff, StandardCharsets.US_ASCII);
                if (header.startsWith("RIFF") && header.endsWith("WAVE")) {
                    addMetadata(path, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO, "yes");
                }
            } catch (IOException missing) {
                throw new IllegalArgumentException(missing);
            }
            super.setDataSource(path);
        }
    }

    @Implements(AtomicFile.class)
    public static class AtomicFileReplace {
        @RealObject private AtomicFile file;

        @Implementation protected void finishWrite(FileOutputStream output) {
            // Reproduce Android's rename-over-existing behavior on Windows hosts.
            try {
                output.getFD().sync();
                output.close();
                File target = file.getBaseFile();
                File staged = new File(target.getPath() + ".new");
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
