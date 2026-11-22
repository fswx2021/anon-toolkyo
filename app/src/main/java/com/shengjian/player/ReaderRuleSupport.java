package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ReaderRuleSupport {
    private static final int MAX_RESULTS = 12_000;
    private static final int MAX_VISITS = 250_000;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_$-]+|\\*");
    private static final Pattern INTEGER = Pattern.compile("-?\\d{1,9}");
    private static final Pattern SLICE = Pattern.compile(
            "(-?\\d{0,9})\\s*:\\s*(-?\\d{0,9})(?:\\s*:\\s*(-?\\d{1,9}))?");
    private static final Pattern FILTER_COMPARE = Pattern.compile("==|!=|<=|>=|<|>");

    private ReaderRuleSupport() {}

    static List<Object> jsonPath(Object root, String path) throws IOException {
        if (root == null || path.isEmpty()) return Collections.emptyList();
        try {
            return follow(root, segments(path), new Budget());
        } catch (IllegalArgumentException | JSONException invalid) {
            String shown = path.length() <= 80 ? path : path.substring(0, 80) + "…";
            throw new IOException("不支持的 JSON 路径：" + shown, invalid);
        }
    }

    private static List<Segment> segments(String path) throws JSONException {
        if (path.length() > 4096) throw new IllegalArgumentException("path length");
        int offset = path.startsWith("$") ? 1 : 0;
        List<Segment> selectors = new ArrayList<>();
        while (offset < path.length()) {
            if (selectors.size() >= 96) throw new IllegalArgumentException("too many path segments");
            boolean recursive = path.startsWith("..", offset);
            if (recursive) offset += 2;
            else if (path.charAt(offset) == '.') offset++;
            if (offset >= path.length()) throw new IllegalArgumentException("missing name");
            Picker picker;
            if (path.charAt(offset) == '[') {
                int end = closeBracket(path, offset);
                picker = parseBracket(path.substring(offset + 1, end).trim());
                offset = end + 1;
            } else {
                Matcher name = NAME.matcher(path);
                name.region(offset, path.length());
                if (!name.lookingAt()) throw new IllegalArgumentException("invalid member name");
                String key = name.group();
                picker = (value, output, budget) -> {
                    if (!recursive || !(value instanceof JSONArray) || key.equals("*")) {
                        chooseMember(value, key, output, budget, 0);
                    }
                };
                offset = name.end();
            }
            selectors.add(new Segment(picker, recursive));
            if (offset < path.length() && path.charAt(offset) != '.' && path.charAt(offset) != '[') {
                throw new IllegalArgumentException("invalid path separator");
            }
        }
        return selectors;
    }

    private static int closeBracket(String path, int opening) {
        int depth = 0;
        char quote = 0;
        for (int i = opening; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '\\') {
                i++;
            } else if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return i;
            }
        }
        throw new IllegalArgumentException("unclosed bracket");
    }

    private static Picker parseBracket(String spec) throws JSONException {
        if (spec.startsWith("?(") && spec.endsWith(")")) {
            Condition condition = condition(spec.substring(2, spec.length() - 1), 0);
            return (value, output, budget) -> filter(value, condition, output, budget);
        }
        List<String> members = splitTop(spec, ",");
        List<Picker> pickers = new ArrayList<>(members.size());
        for (String member : members) pickers.add(parseMember(member.trim()));
        return (value, output, budget) -> {
            for (Picker picker : pickers) picker.pick(value, output, budget);
        };
    }

    private static Picker parseMember(String member) throws JSONException {
        if (member.equals("*")) return ReaderRuleSupport::children;
        if (INTEGER.matcher(member).matches()) {
            int index = Integer.parseInt(member);
            return (value, output, budget) -> {
                if (value instanceof JSONArray) add(output, at((JSONArray) value, index));
            };
        }
        if (member.startsWith("'") || member.startsWith("\"")) {
            Object key = literal(member);
            if (!(key instanceof String)) throw new IllegalArgumentException("invalid member");
            return (value, output, budget) -> {
                if (value instanceof JSONObject) add(output, ((JSONObject) value).opt((String) key));
            };
        }
        Matcher slice = SLICE.matcher(member);
        if (slice.matches()) {
            Integer from = slice.group(1).isEmpty() ? null : Integer.valueOf(slice.group(1));
            Integer to = slice.group(2).isEmpty() ? null : Integer.valueOf(slice.group(2));
            int step = slice.group(3) == null ? 1 : Integer.parseInt(slice.group(3));
            if (step == 0) throw new IllegalArgumentException("zero slice step");
            return (value, output, budget) -> {
                if (value instanceof JSONArray) slice((JSONArray) value, from, to, step, output, budget);
            };
        }
        throw new IllegalArgumentException("unsupported bracket selector");
    }

    private static void slice(JSONArray input, Integer from, Integer to, int step, List<Object> output,
                              Budget budget) {
        int count = input.length();
        boolean forward = step > 0;
        int min = forward ? 0 : -1;
        int max = forward ? count : count - 1;
        int start = from == null ? (forward ? min : max) : bound(from, count, min, max);
        int limit = to == null ? (forward ? max : min) : bound(to, count, min, max);
        for (long i = start; (forward ? i < limit : i > limit) && output.size() < MAX_RESULTS; i += step) {
            budget.visit();
            add(output, input.opt((int) i));
        }
    }

    private static int bound(int index, int size, int min, int max) {
        return Math.min(max, Math.max(min, index < 0 ? size + index : index));
    }

    private static void filter(Object item, Condition predicate, List<Object> output, Budget budget) {
        List<Object> children = new ArrayList<>();
        children(item, children, budget);
        for (Object child : children) {
            if (predicate.test(child, budget)) add(output, child);
        }
    }

    private static Condition condition(String expression, int depth) throws JSONException {
        if (depth > 16) throw new IllegalArgumentException("filter nesting");
        String value = expression.trim();
        for (String delimiter : new String[] {"||", "&&"}) {
            List<String> terms = splitTop(value, delimiter);
            if (terms.size() <= 1) continue;
            List<Condition> conditions = new ArrayList<>(terms.size());
            for (String term : terms) conditions.add(condition(term, depth + 1));
            boolean requireAll = delimiter.equals("&&");
            return (item, budget) -> {
                for (Condition test : conditions) {
                    if (test.test(item, budget) != requireAll) return !requireAll;
                }
                return requireAll;
            };
        }
        if (value.startsWith("(") && value.endsWith(")")) {
            return condition(value.substring(1, value.length() - 1), depth + 1);
        }
        if (value.startsWith("!")) {
            Condition positive = condition(value.substring(1), depth + 1);
            return (item, budget) -> !positive.test(item, budget);
        }
        if (!value.startsWith("@")) throw new IllegalArgumentException("filter needs @");
        Matcher comparisons = FILTER_COMPARE.matcher(value);
        while (comparisons.find()) {
            String operator = comparisons.group();
            List<String> terms = splitTop(value, operator, 2);
            if (terms.size() < 2) continue;
            List<Segment> path = currentPath(terms.get(0).trim());
            Object target = literal(terms.get(1).trim());
            return (item, budget) -> {
                for (Object found : follow(item, path, budget)) {
                    if (compare(found, target, operator)) return true;
                }
                return false;
            };
        }
        List<Segment> path = currentPath(value);
        return (item, budget) -> !follow(item, path, budget).isEmpty();
    }

    private static List<Segment> currentPath(String value) throws JSONException {
        if (!value.equals("@") && !value.startsWith("@.") && !value.startsWith("@[")) {
            throw new IllegalArgumentException("invalid current path");
        }
        return segments("$" + value.substring(1));
    }

    private static Object literal(String value) throws JSONException {
        if (value.isEmpty() || (!value.startsWith("'") && !value.startsWith("\"")
                && !value.equals("null") && !value.equals("true") && !value.equals("false")
                && !value.matches("-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"))) {
            throw new IllegalArgumentException("unsupported filter constant");
        }
        JSONTokener parser = new JSONTokener(value);
        Object result = parser.nextValue();
        if (parser.nextClean() != 0) throw new IllegalArgumentException("trailing literal content");
        return result;
    }

    private static boolean compare(Object first, Object second, String op) {
        int order = 0;
        boolean comparable = first instanceof Number && second instanceof Number
                || first instanceof String && second instanceof String;
        if (first instanceof Number && second instanceof Number) {
            order = new BigDecimal(first.toString()).compareTo(new BigDecimal(second.toString()));
        } else if (first instanceof String && second instanceof String) {
            order = ((String) first).compareTo((String) second);
        }
        boolean equal = comparable ? order == 0 : first.equals(second);
        switch (op) {
            case "==": return equal;
            case "!=": return !equal;
            case "<": return comparable && order < 0;
            case "<=": return comparable && order <= 0;
            case ">": return comparable && order > 0;
            case ">=": return comparable && order >= 0;
            default: return false;
        }
    }

    private static List<Object> follow(Object root, List<Segment> steps, Budget budget) {
        List<Object> results = Collections.singletonList(root);
        for (Segment step : steps) {
            List<Object> next = new ArrayList<>();
            for (Object item : results) {
                if (next.size() >= MAX_RESULTS) break;
                if (step.recursive) descend(item, step.picker, next, budget, 0);
                else {
                    budget.visit();
                    step.picker.pick(item, next, budget);
                }
            }
            results = next;
        }
        return results;
    }

    private static void descend(Object item, Picker picker, List<Object> output, Budget budget, int depth) {
        if (depth > 48 || output.size() >= MAX_RESULTS) return;
        budget.visit();
        picker.pick(item, output, budget);
        if (item instanceof JSONObject) {
            JSONObject object = (JSONObject) item;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                descend(object.opt(keys.next()), picker, output, budget, depth + 1);
            }
        } else if (item instanceof JSONArray) {
            JSONArray array = (JSONArray) item;
            for (int i = 0; i < array.length() && output.size() < MAX_RESULTS; i++) {
                descend(array.opt(i), picker, output, budget, depth + 1);
            }
        }
    }

    private static void chooseMember(Object item, String key, List<Object> output, Budget budget, int depth) {
        if (depth > 48 || output.size() >= MAX_RESULTS) return;
        if (key.equals("*")) {
            children(item, output, budget);
        } else if (item instanceof JSONObject) {
            add(output, ((JSONObject) item).opt(key));
        } else if (item instanceof JSONArray) {
            JSONArray array = (JSONArray) item;
            if (INTEGER.matcher(key).matches()) add(output, at(array, Integer.parseInt(key)));
            else for (int i = 0; i < array.length() && output.size() < MAX_RESULTS; i++) {
                budget.visit();
                chooseMember(array.opt(i), key, output, budget, depth + 1);
            }
        }
    }

    private static void children(Object item, List<Object> output, Budget budget) {
        if (item instanceof JSONArray) {
            JSONArray array = (JSONArray) item;
            for (int i = 0; i < array.length() && output.size() < MAX_RESULTS; i++) {
                budget.visit();
                add(output, array.opt(i));
            }
        } else if (item instanceof JSONObject) {
            JSONObject object = (JSONObject) item;
            for (Iterator<String> keys = object.keys(); keys.hasNext() && output.size() < MAX_RESULTS;) {
                budget.visit();
                add(output, object.opt(keys.next()));
            }
        }
    }

    private static Object at(JSONArray array, int index) {
        return array.opt(index < 0 ? array.length() + index : index);
    }

    private static void add(List<Object> items, Object value) {
        if (value != null && items.size() < MAX_RESULTS) items.add(value);
    }

    static List<String> splitTop(String text, String delimiter) {
        return splitTop(text, delimiter, Integer.MAX_VALUE);
    }

    static int topLevelOffset(String text, String delimiter) {
        List<String> split = splitTop(text, delimiter, 2);
        return split.size() > 1 ? split.get(0).length() : -1;
    }

    private static List<String> splitTop(String text, String delimiter, int limit) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < text.length() && parts.size() < limit - 1; i++) {
            char c = text.charAt(i);
            if (c == '\\') i++;
            else if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') quote = c;
            else if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (depth == 0 && text.startsWith(delimiter, i)) {
                parts.add(text.substring(start, i));
                start = i + delimiter.length();
                i = start - 1;
            }
        }
        parts.add(text.substring(start));
        return parts;
    }

    private interface Picker { void pick(Object item, List<Object> output, Budget budget); }
    private interface Condition { boolean test(Object item, Budget budget); }

    private static final class Segment {
        final Picker picker;
        final boolean recursive;

        Segment(Picker picker, boolean recursive) {
            this.picker = picker;
            this.recursive = recursive;
        }
    }

    private static final class Budget {
        private int visits;

        void visit() {
            if (++visits > MAX_VISITS || Thread.currentThread().isInterrupted()) {
                throw new IllegalArgumentException("path evaluation limit");
            }
        }
    }
}
