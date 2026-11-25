package com.shengjian.player;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.zip.CRC32;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** Unpacks the public AcgLibrary store format into its original rule JSON. */
final class PpcatStore {
    static final String FEED_URL = "https://raw.githubusercontent.com/AcgLibrary/ppcat_store/master/store";
    static final String MIRROR_URL =
            "https://cdn.jsdelivr.net/gh/AcgLibrary/ppcat_store@3b1d56d9cca6246995fe7a8d8e82281707fc72cf/store";
    private static final String MIRROR_SHA256 = "DmnSNUBmZKLwJdpwido2qgFAQ8E3RSa3wzjzVbWNDq0=";
    private static final int ZIP_END = 0x06054b50;
    private static final int ZIP_DIRECTORY = 0x02014b50;
    private static final int ZIP_END_LENGTH = 22;
    private static final int ZIP_DIRECTORY_HEADER = 46;
    private static final int ZIP_LOCAL_HEADER = 30;
    private static final int OMITTED_LOCAL_HEADER_BYTES = 10;
    private static final byte[] ENTRY_NAME = ".mh_rules".getBytes(StandardCharsets.US_ASCII);

    private PpcatStore() {}

    static void verifyMirror(byte[] packed) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(packed);
            if (!MessageDigest.isEqual(digest, Base64.getDecoder().decode(MIRROR_SHA256))) {
                throw new IOException("备用源规则包的 SHA-256 校验失败");
            }
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IOException("无法校验备用源规则包", unavailable);
        }
    }

    static byte[] unpack(byte[] packed, int limit) throws IOException {
        // The halves of the ZIP are swapped and its first ten bytes are omitted.
        // The odd-length variant leaves the final, zero comment-length byte at the file end.
        int footerByteAtEnd = packed.length % 2;
        int end = packed.length / 2 + footerByteAtEnd - ZIP_END_LENGTH;
        if (end < ZIP_DIRECTORY_HEADER + ENTRY_NAME.length) return null;
        ByteBuffer zip = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        if (zip.getInt(end) != ZIP_END) return null;
        int directoryLength = zip.getInt(end + 12);
        if (directoryLength < ZIP_DIRECTORY_HEADER + ENTRY_NAME.length || directoryLength > end) return null;
        int directory = end - directoryLength;
        if (zip.getInt(directory) != ZIP_DIRECTORY || !hasEntryName(packed, directory + ZIP_DIRECTORY_HEADER)) {
            return null;
        }

        int local = end + ZIP_END_LENGTH - footerByteAtEnd;
        int localName = local + ZIP_LOCAL_HEADER - OMITTED_LOCAL_HEADER_BYTES;
        int payload = localName + ENTRY_NAME.length;
        long compressedSize = Integer.toUnsignedLong(zip.getInt(directory + 20));
        long expandedSize = Integer.toUnsignedLong(zip.getInt(directory + 24));
        long checksum = Integer.toUnsignedLong(zip.getInt(directory + 16));
        int payloadTail = packed.length - footerByteAtEnd - payload;
        if (zip.getInt(end + 4) != 0 || unsignedShort(zip, end + 8) != 1
                || unsignedShort(zip, end + 10) != 1 || packed[end + 20] != 0
                || packed[footerByteAtEnd == 0 ? end + 21 : packed.length - 1] != 0
                || unsignedShort(zip, directory + 8) != 0 || unsignedShort(zip, directory + 10) != 8
                || unsignedShort(zip, directory + 28) != ENTRY_NAME.length
                || unsignedShort(zip, directory + 34) != 0 || zip.getInt(directory + 42) != 0
                || directoryLength != ZIP_DIRECTORY_HEADER + ENTRY_NAME.length
                    + unsignedShort(zip, directory + 30) + unsignedShort(zip, directory + 32)
                || compressedSize != (long) directory + payloadTail
                || Integer.toUnsignedLong(zip.getInt(end + 16)) != ZIP_LOCAL_HEADER + ENTRY_NAME.length
                    + compressedSize
                || !hasEntryName(packed, localName)
                || zip.getInt(local) != zip.getInt(directory + 12)
                || zip.getInt(local + 4) != (int) checksum || zip.getInt(local + 8) != (int) compressedSize
                || zip.getInt(local + 12) != (int) expandedSize
                || unsignedShort(zip, local + 16) != ENTRY_NAME.length || unsignedShort(zip, local + 18) != 0) {
            throw new IOException("源规则包的目录或长度不正确");
        }
        if (expandedSize > limit) throw new IOException("源规则包解压后超过导入大小限制");

        Inflater inflater = new Inflater(true);
        CRC32 crc = new CRC32();
        try (InflaterInputStream input = new InflaterInputStream(new SequenceInputStream(
                new ByteArrayInputStream(packed, payload, payloadTail),
                new ByteArrayInputStream(packed, 0, directory)), inflater)) {
            ByteArrayOutputStream json = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1; ) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("导入已取消");
                if ((long) json.size() + count > expandedSize) {
                    throw new IOException("源规则包解压长度不符合声明");
                }
                json.write(buffer, 0, count);
                crc.update(buffer, 0, count);
            }
            if (!inflater.finished() || inflater.getBytesRead() != compressedSize
                    || json.size() != expandedSize || crc.getValue() != checksum) {
                throw new IOException("源规则包校验失败");
            }
            return json.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static boolean hasEntryName(byte[] packed, int offset) {
        if (offset + ENTRY_NAME.length > packed.length) return false;
        for (int i = 0; i < ENTRY_NAME.length; i++) {
            if (packed[offset + i] != ENTRY_NAME[i]) return false;
        }
        return true;
    }

    private static int unsignedShort(ByteBuffer bytes, int offset) {
        return Short.toUnsignedInt(bytes.getShort(offset));
    }
}
