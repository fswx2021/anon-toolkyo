package com.shengjian.player;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReaderWorkMatcher {
    private static final Pattern SPACES = Pattern.compile("[\\p{Z}\\s]+");
    private static final Pattern AUTHOR = Pattern.compile("^(?:作者|原作|author)\\s*:\\s*");
    private static final Pattern NUMBERED = Pattern.compile(
            "^(?:(?:第\\s*)?([0-9零〇一二两兩三四五六七八九十百千万萬]{1,12})(?:\\.([0-9]{1,3}))?\\s*[章节節话話回集]|"
                    + "(?:chapter|chap|ch\\.?)[\\s_\\-]*([0-9]{1,7})(?:\\.([0-9]{1,3}))?(?![0-9]|\\.[0-9]))(.*)$");
    private static final Pattern VOLUME = Pattern.compile("第[^卷]{1,16}卷|\\bvol(?:ume)?\\.?(?:\\s|[0-9])");
    private static final Pattern PART = Pattern.compile("^[\\s:：\\-—·(（\\[【]*(上|中|下)(?:篇|部)?(?:[\\s:：\\-—·)）\\]】].*|$)");
    private static final Pattern CHAPTER_PUNCTUATION = Pattern.compile("[\\p{Z}\\s:：·—_]+");

    enum ChapterMatch { EXACT, NUMBER, NONE }

    static final class Match {
        final ChapterMatch type;
        final int index;

        Match(ChapterMatch type, int index) {
            this.type = type;
            this.index = index;
        }
    }

    private final UnaryOperator<String> fold;

    ReaderWorkMatcher(UnaryOperator<String> fold) {
        this.fold = fold;
    }

    /** Groups search rows by name. Edition qualifiers remain part of the name. */
    String searchTitleKey(ReaderEngine.Book book) {
        String title = title(book.title);
        return title.isEmpty() ? "url\n" + book.source.id + "\n" + book.url
                : "title\n" + book.source.kind + "\n" + title;
    }

    String discoveryKey(ReaderEngine.Book book) {
        String title = title(book.title);
        String author = author(book.author);
        return title.isEmpty() || author.isEmpty() ? "url\n" + book.source.id + "\n" + book.url
                : "work\n" + book.source.kind + "\n" + title + "\n" + author;
    }

    boolean alternate(ReaderEngine.Book current, ReaderEngine.Book candidate) {
        if (current.source.id.equals(candidate.source.id) || current.source.kind != candidate.source.kind) return false;
        String work = title(current.title);
        if (work.isEmpty() || !work.equals(title(candidate.title))) return false;
        String left = author(current.author);
        String right = author(candidate.author);
        return left.isEmpty() || right.isEmpty() || left.equals(right);
    }

    boolean verifiedAuthor(ReaderEngine.Book current, ReaderEngine.Book candidate) {
        String left = author(current.author);
        return !left.isEmpty() && left.equals(author(candidate.author));
    }

    Match chapter(String currentTitle, List<ReaderEngine.Chapter> candidates) {
        String wanted = normalize(currentTitle);
        if (wanted.isEmpty()) return new Match(ChapterMatch.NONE, -1);
        String wantedText = CHAPTER_PUNCTUATION.matcher(wanted).replaceAll("");
        int exact = unique(candidates, wantedText, ChapterMatch.EXACT);
        if (exact >= 0) return new Match(ChapterMatch.EXACT, exact);
        String number = chapterNumber(wanted);
        if (number.isEmpty()) return new Match(ChapterMatch.NONE, -1);
        int byNumber = unique(candidates, number, ChapterMatch.NUMBER);
        return byNumber < 0 ? new Match(ChapterMatch.NONE, -1) : new Match(ChapterMatch.NUMBER, byNumber);
    }

    private int unique(List<ReaderEngine.Chapter> candidates, String wanted, ChapterMatch type) {
        int match = -1;
        for (int i = 0; i < candidates.size(); i++) {
            String label = normalize(candidates.get(i).title);
            String key = type == ChapterMatch.EXACT ? CHAPTER_PUNCTUATION.matcher(label).replaceAll("")
                    : chapterNumber(label);
            if (!wanted.equals(key)) continue;
            if (match >= 0) return -1;
            match = i;
        }
        return match;
    }

    private String title(String value) {
        return normalize(ReaderSearchRanking.requestKeyword(value));
    }

    private String author(String value) {
        String author = AUTHOR.matcher(normalize(value)).replaceFirst("").trim();
        if (author.equals("未知") || author.equals("不详") || author.equals("unknown")
                || author.equals("-") || author.equals("—")) return "";
        return author;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return SPACES.matcher(fold.apply(normalized)).replaceAll(" ").trim();
    }

    private static String chapterNumber(String label) {
        if (VOLUME.matcher(label).find()) return "";
        Matcher matcher = NUMBERED.matcher(label);
        if (!matcher.matches()) return "";
        String whole = matcher.group(1) == null ? matcher.group(3) : matcher.group(1);
        int number = parseNumber(whole);
        if (number < 0) return "";
        String decimal = matcher.group(2) == null ? matcher.group(4) : matcher.group(2);
        String fraction = decimal == null ? "" : decimal.replaceFirst("0+$", "");
        Matcher part = PART.matcher(matcher.group(5));
        return number + (fraction.isEmpty() ? "" : "." + fraction)
                + (part.matches() ? ":" + part.group(1) : "");
    }

    private static int parseNumber(String value) {
        if (value.matches("[0-9]{1,7}")) return Integer.parseInt(value);
        final String digits = "零一二三四五六七八九";
        final String units = "十百千万";
        value = value.replace('〇', '零').replace('两', '二').replace('兩', '二').replace('萬', '万');
        int total = 0, group = 0, digit = 0, precedingUnit = 4;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            int num = digits.indexOf(ch);
            if (num >= 0) {
                if (digit > 999_999) return -1;
                digit = digit * 10 + num;
                continue;
            }
            int unit = units.indexOf(ch);
            if (unit < 0 || (unit < 3 && (unit >= precedingUnit || digit > 9))) return -1;
            precedingUnit = unit;
            switch (unit) {
                case 0: group += (digit == 0 ? 1 : digit) * 10; break;
                case 1: group += (digit == 0 ? 1 : digit) * 100; break;
                case 2: group += (digit == 0 ? 1 : digit) * 1000; break;
                case 3:
                    if (group + digit > 9999 || total != 0) return -1;
                    total = (group + digit) * 10_000;
                    group = 0;
                    break;
                default: return -1;
            }
            if (group > 9999) return -1;
            digit = 0;
        }
        return total + group + digit;
    }
}
