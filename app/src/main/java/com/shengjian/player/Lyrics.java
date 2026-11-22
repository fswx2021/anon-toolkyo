package com.shengjian.player;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Lyrics {
    public static final class Line {
        public final long timeMs;
        public final long endMs;
        public final String text;

        private Line(long timeMs, long endMs, String text) {
            this.timeMs = timeMs;
            this.endMs = endMs;
            this.text = text;
        }
    }

    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,6}):([0-5]?\\d)(?:[.:,](\\d{1,3}))?]");
    private static final Pattern WORD_TIME = Pattern.compile("<\\d{1,6}:[0-5]?\\d(?:[.:,]\\d{1,3})?>");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\h*\\[offset:\\h*([-+]?\\d{1,10})\\h*]");
    private static final Pattern LRC_TAG = Pattern.compile("^\\[[\\p{L}_][\\p{L}\\d_ -]*:.*]\\s*$");
    private static final String CUE_TIME = "((?:\\d{1,6}:)?[0-5]?\\d:[0-5]\\d[.,]\\d{1,3})";
    private static final Pattern CUE = Pattern.compile("^\\s*" + CUE_TIME + "\\s*-->\\s*" + CUE_TIME + "(?:\\s+.*)?$");
    private static final Pattern CAPTION_MARKUP = Pattern.compile("<[^>]+>|\\{\\\\[^}]+\\}");

    private Lyrics() {
    }

    public static List<Line> parse(String input) {
        String[] rows = input.replace("\uFEFF", "").split("\\r\\n|\\n|\\r", -1);
        List<Line> timed = parseCaptions(rows);
        if (!timed.isEmpty()) {
            return sortAndMerge(timed);
        }

        long offset = 0L;
        Matcher adjustment = OFFSET.matcher(input.replace("\uFEFF", ""));
        if (adjustment.find()) {
            offset = Long.parseLong(adjustment.group(1));
        }

        List<Line> plain = new ArrayList<>();
        for (String row : rows) {
            String text = row.trim();
            if (text.isEmpty() || LRC_TAG.matcher(text).matches()) {
                continue;
            }
            Matcher timestamp = LRC_TIME.matcher(text);
            List<Long> times = new ArrayList<>();
            int textStart = 0;
            while (timestamp.find(textStart) && timestamp.start() == textStart) {
                long time = Long.parseLong(timestamp.group(1)) * 60_000L
                        + Long.parseLong(timestamp.group(2)) * 1000L + fraction(timestamp.group(3));
                times.add(Math.max(0, time - offset));
                textStart = timestamp.end();
                while (textStart < text.length() && Character.isWhitespace(text.charAt(textStart))) {
                    textStart++;
                }
            }
            if (times.isEmpty()) {
                plain.add(new Line(-1, Long.MAX_VALUE, text));
            } else {
                String line = WORD_TIME.matcher(text.substring(textStart)).replaceAll("").trim();
                for (long time : times) {
                    timed.add(new Line(time, Long.MAX_VALUE, line));
                }
            }
        }
        return timed.isEmpty() ? plain : sortAndMerge(timed);
    }

    private static List<Line> parseCaptions(String[] rows) {
        List<Line> captions = new ArrayList<>();
        for (int i = 0; i < rows.length; i++) {
            Matcher cue = CUE.matcher(rows[i]);
            if (!cue.matches()) {
                continue;
            }
            long from = cueTime(cue.group(1));
            long until = cueTime(cue.group(2));
            StringBuilder text = new StringBuilder();
            while (i + 1 < rows.length && !rows[i + 1].trim().isEmpty()
                    && !CUE.matcher(rows[i + 1]).matches()) {
                if (i + 2 < rows.length && rows[i + 1].trim().matches("\\d+")
                        && CUE.matcher(rows[i + 2]).matches()) {
                    break;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(CAPTION_MARKUP.matcher(rows[++i].trim()).replaceAll(""));
            }
            if (until > from && text.length() > 0) {
                captions.add(new Line(from, until, text.toString().replace("&amp;", "&")
                        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")));
            }
        }
        return captions;
    }

    private static List<Line> sortAndMerge(List<Line> lines) {
        lines.sort(Comparator.comparingLong(line -> line.timeMs));
        List<Line> merged = new ArrayList<>();
        for (Line line : lines) {
            int last = merged.size() - 1;
            if (last < 0 || merged.get(last).timeMs != line.timeMs) {
                merged.add(line);
                continue;
            }
            Line previous = merged.get(last);
            String text = previous.text;
            if (text.isEmpty()) {
                text = line.text;
            } else if (!line.text.isEmpty() && !text.equals(line.text)) {
                text += "\n" + line.text;
            }
            merged.set(last, new Line(line.timeMs, Math.max(previous.endMs, line.endMs), text));
        }
        return merged;
    }

    public static int activeIndex(List<Line> lines, long positionMs) {
        if (lines.isEmpty() || lines.get(0).timeMs < 0) {
            return -1;
        }
        int low = 0;
        int high = lines.size() - 1;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            if (lines.get(middle).timeMs <= positionMs) {
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        for (int i = high; i >= 0; i--) {
            if (positionMs < lines.get(i).endMs) {
                return i;
            }
        }
        return -1;
    }

    static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count + output.size() > MAX_BYTES) {
                throw new IOException("歌词文件超过 2 MB");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    static String decode(byte[] bytes) throws IOException {
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe)
                || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff))) {
            return new String(bytes, StandardCharsets.UTF_16);
        }
        if (bytes.length >= 4 && bytes[1] == 0 && bytes[3] == 0) {
            return new String(bytes, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 4 && bytes[0] == 0 && bytes[2] == 0) {
            return new String(bytes, StandardCharsets.UTF_16BE);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException invalidUtf8) {
            try {
                return Charset.forName("GB18030").newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException invalidGbk) {
                throw new IOException("无法识别歌词编码", invalidGbk);
            }
        }
    }

    private static long cueTime(String time) {
        String[] parts = time.split("[:.,]");
        int i = parts.length - 1;
        long result = fraction(parts[i--]) + Long.parseLong(parts[i--]) * 1000L
                + Long.parseLong(parts[i--]) * 60_000L;
        if (i == 0) {
            result += Long.parseLong(parts[i]) * 3_600_000L;
        }
        return result;
    }

    private static int fraction(String part) {
        return part == null ? 0 : Integer.parseInt((part + "00").substring(0, 3));
    }

    static String embeddedMp3(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 10 || input.readUnsignedByte() != 'I'
                    || input.readUnsignedByte() != 'D' || input.readUnsignedByte() != '3') {
                return "";
            }
            int version = input.readUnsignedByte();
            input.readUnsignedByte();
            int flags = input.readUnsignedByte();
            long end = 10L + syncSafe(input.readInt());
            if (version < 2 || version > 4 || (flags & 0x80) != 0 || end > input.length()) {
                return "";
            }
            if ((flags & 0x40) != 0) {
                if (version == 2) {
                    return "";
                }
                int extended = input.readInt();
                input.seek(version == 4 ? 10L + syncSafe(extended) : 14L + extended);
            }
            while (input.getFilePointer() + (version == 2 ? 6 : 10) < end) {
                byte[] name = new byte[version == 2 ? 3 : 4];
                input.readFully(name);
                if (name[0] == 0) {
                    return "";
                }
                int length = version == 2 ? input.readUnsignedByte() << 16 | input.readUnsignedShort()
                        : input.readInt();
                if (version == 4) {
                    length = syncSafe(length);
                }
                int frameFlags = version == 2 ? 0 : input.readUnsignedShort();
                long next = input.getFilePointer() + length;
                if (length <= 0 || next > end) {
                    return "";
                }
                String frame = new String(name, StandardCharsets.US_ASCII);
                if (("USLT".equals(frame) || "ULT".equals(frame)) && (frameFlags & 0xFF) == 0
                        && length <= MAX_BYTES) {
                    byte[] contents = new byte[length];
                    input.readFully(contents);
                    String lyric = decodeUslt(contents);
                    if (!lyric.trim().isEmpty()) {
                        return lyric;
                    }
                }
                input.seek(next);
            }
            return "";
        }
    }

    static String embeddedFlac(File file) throws IOException {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            if (input.length() < 8 || input.readInt() != 0x664c6143) {
                return "";
            }
            boolean last;
            do {
                int type = input.readUnsignedByte();
                last = (type & 0x80) != 0;
                int size = input.readUnsignedByte() << 16 | input.readUnsignedShort();
                long next = input.getFilePointer() + size;
                if (next > input.length()) {
                    return "";
                }
                if ((type & 0x7f) == 4 && size <= MAX_BYTES) {
                    byte[] comments = new byte[size];
                    input.readFully(comments);
                    return vorbisLyrics(ByteBuffer.wrap(comments).order(java.nio.ByteOrder.LITTLE_ENDIAN));
                }
                input.seek(next);
            } while (!last && input.getFilePointer() + 4 <= input.length());
            return "";
        }
    }

    private static String vorbisLyrics(ByteBuffer comments) {
        if (comments.remaining() < 8) {
            return "";
        }
        int vendorLength = comments.getInt();
        if (vendorLength < 0 || vendorLength > comments.remaining() - 4) {
            return "";
        }
        comments.position(comments.position() + vendorLength);
        int count = comments.getInt();
        for (int i = 0; i < count && comments.remaining() >= 4; i++) {
            int length = comments.getInt();
            if (length < 0 || length > comments.remaining()) {
                return "";
            }
            byte[] raw = new byte[length];
            comments.get(raw);
            String comment = new String(raw, StandardCharsets.UTF_8);
            if (comment.regionMatches(true, 0, "LYRICS=", 0, 7)) {
                return comment.substring(7);
            }
            if (comment.regionMatches(true, 0, "UNSYNCEDLYRICS=", 0, 15)) {
                return comment.substring(15);
            }
        }
        return "";
    }

    private static int syncSafe(int size) {
        return (size & 0x7F000000) >>> 3 | (size & 0x007F0000) >>> 2
                | (size & 0x00007F00) >>> 1 | size & 0x0000007F;
    }

    private static String decodeUslt(byte[] bytes) {
        if (bytes.length < 5 || bytes[0] < 0 || bytes[0] > 3) {
            return "";
        }
        Charset encoding;
        switch (bytes[0]) {
            case 1:
                encoding = StandardCharsets.UTF_16;
                break;
            case 2:
                encoding = StandardCharsets.UTF_16BE;
                break;
            case 3:
                encoding = StandardCharsets.UTF_8;
                break;
            default:
                encoding = StandardCharsets.ISO_8859_1;
                break;
        }
        String fields = new String(bytes, 4, bytes.length - 4, encoding);
        int delimiter = fields.indexOf('\0');
        if (delimiter < 0) {
            return "";
        }
        return fields.substring(delimiter + 1).replace("\0", "").replace("\uFEFF", "");
    }
}
