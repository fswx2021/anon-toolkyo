package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/** Maps flattened PPCat manga declarations to the shared reader's rule groups. */
final class PpcatRules {
    private static final String[] PPCAT_FIELDS = {
            "ruleBookUrlCode", "ruleBookLines", "ruleChapterGroup", "ruleContentType", "ruleContentWebUrl",
            "ruleSearchInit", "ruleFindInit", "ruleSearchUrlNext", "ruleCommentList"
    };

    private PpcatRules() {}

    static boolean matches(JSONObject source) {
        if (source.optBoolean("_ppcat")) return true;
        if (field(source, "bookSourceUrl").isEmpty()
                || field(source, "ruleSearchUrl", "ruleFindUrl").isEmpty()) return false;
        if ("ppcat".equalsIgnoreCase(field(source, "format"))) return true;
        for (String key : PPCAT_FIELDS) {
            if (!field(source, key).isEmpty()) return true;
        }
        return false;
    }

    static JSONObject normalize(JSONObject source) throws IOException {
        if (source.optBoolean("_ppcat") && source.optJSONObject("ruleToc") != null) return source;
        String kind = field(source, "bookSourceType");
        if (kind.contains("小说") || kind.contains("动画") || kind.contains("视频")) {
            throw new IOException("此源的类型不支持导入漫画");
        }
        try {
            JSONObject definition = new JSONObject()
                    .put("_ppcat", true)
                    .put("_ppcatRule", source)
                    .put("bookSourceType", ReaderSources.COMIC)
                    .put("enabled", source.optBoolean("enabled", source.optBoolean("enable", true)))
                    .put("enabledExplore", source.optBoolean("enabledExplore", true));
            for (String key : new String[]{"bookSourceName", "bookSourceUrl", "bookSourceGroup"}) {
                copy(definition, key, source, key);
            }
            if (!source.isNull("bookSourceLib")) definition.put("bookSourceLib", source.opt("bookSourceLib"));
            if (!source.isNull("header")) definition.put("header", source.opt("header"));
            set(definition, "searchUrl", request(field(source, "ruleSearchUrl")));
            set(definition, "exploreUrl", explore(field(source, "ruleFindUrl")));
            copy(definition, "bookUrlPattern", source, "ruleBookUrlPattern");

            JSONObject search = new JSONObject();
            copyResults(search, source, "ruleSearch");
            definition.put("ruleSearch", search);
            JSONObject discover = new JSONObject(search.toString());
            copyResults(discover, source, "ruleFind");
            definition.put("ruleExplore", discover);

            JSONObject info = new JSONObject();
            copy(info, "init", source, "ruleBookInit");
            copy(info, "name", source, "ruleBookName");
            copy(info, "author", source, "ruleBookAuthor");
            copy(info, "intro", source, "ruleIntroduce", "ruleBookIntroduce");
            copy(info, "coverUrl", source, "ruleCoverUrl", "ruleBookCoverUrl");
            copy(info, "tocUrl", source, "ruleChapterUrl");
            definition.put("ruleBookInfo", info);

            JSONObject toc = new JSONObject();
            copy(toc, "init", source, "ruleChapterInit");
            copy(toc, "chapterList", source, "ruleChapterList");
            copy(toc, "chapterName", source, "ruleChapterName");
            copy(toc, "chapterUrl", source, "ruleContentUrl");
            copy(toc, "nextTocUrl", source, "ruleChapterUrlNext");
            definition.put("ruleToc", toc);

            JSONObject content = new JSONObject();
            copy(content, "init", source, "ruleContentInit");
            copy(content, "content", source, "ruleBookContent", "ruleBookPageContent", "ruleContent", "ruleContentUrlContent");
            copy(content, "nextContentUrl", source, "ruleContentUrlNext");
            definition.put("ruleContent", content);
            return definition;
        } catch (JSONException invalid) {
            throw new IOException("无法读取源规则", invalid);
        }
    }

    private static void copyResults(JSONObject target, JSONObject source, String prefix) throws JSONException {
        copy(target, "init", source, prefix + "Init");
        copy(target, "bookList", source, prefix + "List");
        copy(target, "name", source, prefix + "Name");
        copy(target, "author", source, prefix + "Author");
        copy(target, "intro", source, prefix + "Introduce");
        copy(target, "coverUrl", source, prefix + "CoverUrl");
        copy(target, "bookUrl", source, prefix + "NoteUrl", prefix + "BookUrl");
    }

    private static String explore(String declaration) throws IOException, JSONException {
        if (declaration.isEmpty()) return "";
        JSONArray entries = new JSONArray();
        for (String raw : declaration.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int separator = line.indexOf("::");
            String title = separator < 0 ? "发现" : line.substring(0, separator).trim();
            String address = request(separator < 0 ? line : line.substring(separator + 2));
            if (!address.isEmpty()) entries.put(new JSONObject().put("title", title).put("url", address));
        }
        return entries.length() == 0 ? "" : entries.toString();
    }

    /** PPCat request modifiers apply after the address or POST body. */
    static String request(String declaration) throws IOException {
        String rule = declaration.trim();
        String lower = rule.toLowerCase(Locale.ROOT);
        if (rule.isEmpty() || lower.startsWith("js:") || lower.startsWith("<js>")
                || ReaderRuleSupport.topLevelOffset(lower, "@js:") >= 0
                || ReaderRuleSupport.topLevelOffset(rule, ",{") >= 0) return rule;
        List<String> parts = ReaderRuleSupport.splitTop(rule, "@");
        try {
            JSONObject options = new JSONObject();
            StringBuilder address = new StringBuilder(stripEncoding(parts.get(0), options));
            StringBuilder body = null;
            boolean json = false;
            for (int index = 1; index < parts.size(); index++) {
                String part = parts.get(index).trim();
                if (part.regionMatches(true, 0, "Header:", 0, 7)) {
                    JSONTokener parser = new JSONTokener(part.substring(7).trim());
                    Object header = parser.nextValue();
                    if (!(header instanceof JSONObject) || parser.nextClean() != 0) {
                        throw new JSONException("Invalid PPCat request headers");
                    }
                    options.put("headers", header);
                } else if (part.equalsIgnoreCase("PostJson")) {
                    json = true;
                } else if (part.equalsIgnoreCase("WebView")) {
                    options.put("webView", true);
                } else if (body != null || part.startsWith("{") || part.startsWith("[")
                        || part.matches("(?s)[^=&/?#@]+=.*")) {
                    if (body == null) body = new StringBuilder();
                    else body.append('@');
                    body.append(stripEncoding(part, options));
                } else {
                    address.append('@').append(stripEncoding(part, options));
                }
            }
            if (body != null || json) {
                options.put("method", "POST");
                options.put("body", body == null ? "" : body.toString());
                if (json) options.put("_ppcatJson", true);
            }
            return options.length() == 0 ? address.toString() : address + "," + options;
        } catch (JSONException invalid) {
            throw new IOException("源的请求规则格式有误", invalid);
        }
    }

    private static String stripEncoding(String value, JSONObject options) throws JSONException {
        int at = ReaderRuleSupport.topLevelOffset(value.toLowerCase(Locale.ROOT), "|char=");
        if (at < 0) return value.trim();
        String charset = value.substring(at + 6).trim();
        if (charset.isEmpty()) throw new JSONException("Empty PPCat request charset");
        options.put("charset", charset);
        return value.substring(0, at).trim();
    }

    private static void copy(JSONObject target, String key, JSONObject source, String... names)
            throws JSONException {
        set(target, key, field(source, names));
    }

    private static void set(JSONObject target, String key, String value) throws JSONException {
        if (!value.isEmpty()) target.put(key, value);
    }

    private static String field(JSONObject source, String... names) {
        for (String name : names) {
            Object value = source.opt(name);
            if (value instanceof String && !((String) value).trim().isEmpty()) return ((String) value).trim();
        }
        return "";
    }
}
