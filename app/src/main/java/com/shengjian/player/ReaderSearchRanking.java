package com.shengjian.player;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;

/** Scores a title and author against one search, independent of response order. */
final class ReaderSearchRanking {
    private final UnaryOperator<String> foldChinese;
    private final Name query;
    private final boolean shortLatin;

    ReaderSearchRanking(String keyword, UnaryOperator<String> foldChinese) {
        this.foldChinese = foldChinese;
        query = new Name(normalize(keyword));
        shortLatin = query.joined.length() <= 2 && query.joined.chars().allMatch(value -> value < 128);
    }

    static String requestKeyword(String input) {
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC).trim();
        if (value.length() < 3) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '《' && last == '》') || (first == '〈' && last == '〉')
                || (first == '“' && last == '”') || (first == '"' && last == '"')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    boolean exactTitle(String title) {
        return !query.joined.isEmpty() && query.joined.equals(new Name(normalize(title)).joined);
    }

    int score(String title, String author) {
        if (query.joined.isEmpty()) return 0;
        Name work = new Name(normalize(title));
        if (query.joined.equals(work.joined)) return 7000;
        String creatorText = normalize(author);
        if (creatorText.startsWith("作者:")) creatorText = creatorText.substring(3);
        Name creator = new Name(creatorText);
        if (query.joined.equals(creator.joined)) return 6000;
        int inTitle = position(work, query.joined);
        if (inTitle == 0) return 5000 - penalty(work.joined, inTitle);
        if (inTitle > 0) return 4000 - penalty(work.joined, inTitle);
        if (query.words.size() > 1 && query.words.stream().allMatch(term ->
                position(work, term) >= 0 || position(creator, term) >= 0)) {
            return 3000 - penalty(work.joined, 0);
        }
        int inAuthor = position(creator, query.joined);
        if (inAuthor == 0) return 2000 - penalty(creator.joined, inAuthor);
        return inAuthor < 0 ? 0 : 1000 - penalty(creator.joined, inAuthor);
    }

    private int position(Name name, String term) {
        if (shortLatin) {
            int offset = 0;
            for (String word : name.words) {
                if (word.equals(term)) return offset;
                offset += word.length();
            }
            return -1;
        }
        return name.joined.indexOf(term);
    }

    private int penalty(String text, int position) {
        return Math.min(999, Math.abs(text.length() - query.joined.length()) + 2 * position);
    }

    private String normalize(String value) {
        if (value == null || value.isEmpty()) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return foldChinese.apply(normalized);
    }

    private static final class Name {
        final String joined;
        final List<String> words = new ArrayList<>();

        Name(String text) {
            StringBuilder letters = new StringBuilder(text.length());
            int start = 0;
            for (int index = 0; index < text.length(); ) {
                int codePoint = text.codePointAt(index);
                index += Character.charCount(codePoint);
                int category = Character.getType(codePoint);
                if (Character.isLetterOrDigit(codePoint)
                        || (start < letters.length() && (category == Character.NON_SPACING_MARK
                        || category == Character.COMBINING_SPACING_MARK))) {
                    letters.appendCodePoint(codePoint);
                } else if (start < letters.length()) {
                    words.add(letters.substring(start));
                    start = letters.length();
                }
            }
            if (start < letters.length()) words.add(letters.substring(start));
            joined = letters.toString();
        }
    }
}
