package com.shengjian.player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class NovelTextTools {
    private static final int MAX_RULES = 24;
    private static final int MAX_LENGTH = 160;
    private static final int MAX_EDITS = 12_000;

    private NovelTextTools() {}

    static final class LiteralRule {
        final String text, replacement;

        LiteralRule(String text, String replacement) {
            this.text = text;
            this.replacement = replacement;
        }
    }

    static List<LiteralRule> literalRules(String declaration) {
        List<LiteralRule> rules = new ArrayList<>();
        String[] lines = declaration.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            int separator = line.indexOf("=>");
            if (separator < 0) throw new IllegalArgumentException("第 " + (i + 1) + " 行缺少 =>");
            String text = line.substring(0, separator).trim();
            String replacement = line.substring(separator + 2).trim();
            if (text.isEmpty()) throw new IllegalArgumentException("第 " + (i + 1) + " 行的原文不能为空");
            if (text.length() > MAX_LENGTH || replacement.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 行两侧各限 " + MAX_LENGTH + " 个字符");
            }
            if (rules.size() == MAX_RULES) throw new IllegalArgumentException("每本书最多 " + MAX_RULES + " 条规则");
            if (!text.equals(replacement)) rules.add(new LiteralRule(text, replacement));
        }
        return rules;
    }

    static final class LiteralText {
        final String original, display;
        final boolean skipped;
        private final int[] edits;

        private LiteralText(String original, String display, int[] edits, boolean skipped) {
            this.original = original;
            this.display = display;
            this.edits = edits;
            this.skipped = skipped;
        }

        int displayOffset(int originalOffset) { return map(originalOffset, 0, 2); }

        int originalOffset(int displayOffset) {
            int offset = map(displayOffset, 2, 0);
            if (offset > 0 && offset < original.length() && Character.isLowSurrogate(original.charAt(offset))
                    && Character.isHighSurrogate(original.charAt(offset - 1))) return offset - 1;
            return offset;
        }

        private int map(int offset, int from, int to) {
            int low = 0, high = edits.length / 4;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (edits[middle * 4 + from] <= offset) low = middle + 1;
                else high = middle;
            }
            if (low == 0) return offset;
            int edit = (low - 1) * 4;
            int start = edits[edit + from], end = edits[edit + from + 1];
            int mappedStart = edits[edit + to], mappedEnd = edits[edit + to + 1];
            if (offset >= end) return mappedEnd + offset - end;
            return mappedStart + (int) ((long) (offset - start) * (mappedEnd - mappedStart) / (end - start));
        }
    }

    static LiteralText literalText(String original, List<LiteralRule> rules) {
        if (rules.isEmpty()) return unchanged(original, false);
        int[] next = new int[rules.size()];
        for (int i = 0; i < next.length; i++) next[i] = original.indexOf(rules.get(i).text);
        int[] edits = new int[32];
        StringBuilder display = new StringBuilder(original.length());
        int count = 0, cursor = 0;
        while (cursor < original.length()) {
            if (Thread.currentThread().isInterrupted()) return unchanged(original, false);
            int first = original.length(), chosen = -1;
            for (int i = 0; i < next.length; i++) {
                if (next[i] >= 0 && next[i] < cursor) next[i] = original.indexOf(rules.get(i).text, cursor);
                if (next[i] >= 0 && next[i] < first) {
                    first = next[i];
                    chosen = i;
                }
            }
            if (chosen < 0) break;
            if (count == MAX_EDITS) return unchanged(original, true);
            if (count * 4 == edits.length) edits = Arrays.copyOf(edits, edits.length * 2);
            LiteralRule rule = rules.get(chosen);
            display.append(original, cursor, first);
            cursor = first + rule.text.length();
            int index = count++ * 4;
            edits[index] = first;
            edits[index + 1] = cursor;
            edits[index + 2] = display.length();
            display.append(rule.replacement);
            edits[index + 3] = display.length();
            if (display.length() > Math.max(2_000_000, original.length())) return unchanged(original, true);
        }
        if (count == 0) return unchanged(original, false);
        display.append(original, cursor, original.length());
        String cleaned = display.toString();
        if (cleaned.length() > Math.max(2_000_000, original.length()) || cleaned.trim().isEmpty()) {
            return unchanged(original, true);
        }
        return new LiteralText(original, cleaned, Arrays.copyOf(edits, count * 4), false);
    }

    private static LiteralText unchanged(String text, boolean skipped) {
        return new LiteralText(text, text, new int[0], skipped);
    }

    /** Finds a literal match in chapter offsets, wrapping once at the chapter boundary. */
    static int find(String text, String term, int from, int direction) {
        int last = text.length() - term.length();
        if (term.isEmpty() || last < 0) return -1;
        if (direction < 0) {
            int start = Math.min(from, last);
            int match = scan(text, term, start, -1, -1);
            return match >= 0 ? match : scan(text, term, last, Math.max(-1, start), -1);
        }
        int start = Math.max(0, from);
        int match = scan(text, term, start, last + 1, 1);
        return match >= 0 ? match : scan(text, term, 0, Math.min(start, last + 1), 1);
    }

    private static int scan(String text, String term, int start, int limit, int direction) {
        for (int index = start; direction < 0 ? index > limit : index < limit; index += direction) {
            if ((index & 4095) == 0 && Thread.currentThread().isInterrupted()) return -1;
            if (text.regionMatches(true, index, term, 0, term.length())) return index;
        }
        return -1;
    }

    static int speechEnd(String text, int start, int maxLength) {
        int end = Math.min(text.length(), start + maxLength);
        if (end == text.length()) return end;
        // Prefer a natural sentence boundary; keep both UTF-16 units of a supplementary character together.
        for (int index = end; index > start + maxLength / 2; index--) {
            if ("。！？；!?;\n".indexOf(text.charAt(index - 1)) >= 0) return index;
        }
        if (Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
        return end;
    }
}
