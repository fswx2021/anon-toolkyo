package com.shengjian.player;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Normalizes the flat manga-rule format to the reader's shared rule groups. */
final class YiciyuanRules {
    private static final String DONGMAN = "https://www.dongmanmanhua.cn";
    private static final String HONKAI = "https://comic.bh3.com";
    private static final String ZAIMANHUA = "https://v4api.zaimanhua.com";
    private static final String HEADER_MARKER = "@header:";
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");
    private static final Pattern ENCRYPTED = Pattern.compile("^[-+]?\\s*:_[A-Z]\\d{3}_", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESCAPED_SCRIPT_START = Pattern.compile(
            "^(?:@?js:|<js>)\\s*\\\\[rn]", Pattern.CASE_INSENSITIVE);
    private static final Pattern INLINE_IMAGE_OPTIONS = Pattern.compile(
            "<img\\b[^<>]*?\\s(?:src|data-src|data-original|data-lazy-src|data-url)\\s*=\\s*"
                    + "([\"'])[^\"'<>]*?,\\s*(?=\\{\\s*\")", Pattern.CASE_INSENSITIVE);

    private YiciyuanRules() {}

    static boolean matches(JSONObject source) {
        String type = field(source, "bookSourceType");
        return ("漫画".equals(type) || "图源".equals(type) || type.isEmpty())
                && (source.has("ruleSearchUrl") || source.has("ruleFindUrl"))
                && (source.has("ruleChapterList") || source.has("ruleBookContent")
                || source.has("ruleContentUrlContent"));
    }

    static JSONObject normalize(JSONObject source) throws IOException {
        try {
            JSONObject result = new JSONObject();
            result.put("_yiciyuan", true);
            result.put("bookSourceName", field(source, "bookSourceName"));
            result.put("bookSourceUrl", field(source, "bookSourceUrl"));
            result.put("bookSourceType", ReaderSources.COMIC);
            result.put("bookSourceGroup", field(source, "bookSourceGroup"));
            result.put("enabled", source.optBoolean("enable", source.optBoolean("enabled", true)));
            result.put("enabledExplore", source.optBoolean("enabledExplore", true));
            String searchUrl = address(source, "ruleSearchUrl");
            if (!searchDisabled(searchUrl)) set(result, "searchUrl", searchUrl);
            set(result, "exploreUrl", address(source, "ruleFindUrl"));

            JSONObject search = new JSONObject();
            set(search, "bookList", field(source, "ruleSearchList"));
            set(search, "name", field(source, "ruleSearchName"));
            set(search, "author", field(source, "ruleSearchAuthor"));
            set(search, "intro", field(source, "ruleSearchIntroduce", "ruleSearchIntro"));
            set(search, "coverUrl", field(source, "ruleSearchCoverUrl"));
            set(search, "bookUrl", field(source, "ruleSearchNoteUrl", "ruleSearchBookUrl"));
            result.put("ruleSearch", search);

            JSONObject explore = new JSONObject(search.toString());
            set(explore, "bookList", field(source, "ruleFindList"));
            set(explore, "name", field(source, "ruleFindName"));
            set(explore, "author", field(source, "ruleFindAuthor"));
            set(explore, "intro", field(source, "ruleFindIntroduce", "ruleFindIntro"));
            set(explore, "coverUrl", field(source, "ruleFindCoverUrl"));
            set(explore, "bookUrl", field(source, "ruleFindNoteUrl", "ruleFindBookUrl"));
            result.put("ruleExplore", explore);

            JSONObject info = new JSONObject();
            set(info, "name", field(source, "ruleBookName", "ruleBookInfoName"));
            set(info, "author", field(source, "ruleBookAuthor", "ruleBookInfoAuthor"));
            set(info, "intro", field(source, "ruleIntroduce", "ruleBookInfoIntro"));
            set(info, "coverUrl", field(source, "ruleCoverUrl", "ruleBookInfoCoverUrl"));
            set(info, "tocUrl", field(source, "ruleChapterUrl"));
            result.put("ruleBookInfo", info);

            JSONObject toc = new JSONObject();
            set(toc, "chapterList", field(source, "ruleChapterList"));
            set(toc, "chapterChildList", field(source, "ruleChapterChildList", "ruleChapterItemList"));
            set(toc, "chapterName", field(source, "ruleChapterName", "ruleChapterParentName"));
            set(toc, "chapterUrl", field(source, "ruleContentUrl"));
            set(toc, "nextTocUrl", field(source, "ruleChapterUrlNext"));
            result.put("ruleToc", toc);

            JSONObject content = new JSONObject();
            String contentRule = field(source, "ruleBookContent", "ruleContentUrlContent");
            HeaderSuffix chapterImages = splitHeaders(contentRule);
            set(content, "content", chapterImages.rule);
            set(content, "nextContentUrl", field(source, "ruleContentUrlNext"));
            set(content, "imageDecoder", field(source, "ruleBookContentDecoder"));
            result.put("ruleContent", content);
            if (chapterImages.headers != null) result.put("imageHeaders", chapterImages.headers);

            JSONObject requestHeaders = new JSONObject();
            addHeaders(requestHeaders, objectHeaders(source.opt("header")));
            addHeaders(requestHeaders, objectHeaders(source.opt("httpHeader")));
            String agent = field(source, "httpUserAgent");
            if (!agent.isEmpty() && !hasHeader(requestHeaders, "User-Agent")) {
                requestHeaders.put("User-Agent", agent);
            }
            if (requestHeaders.length() > 0) result.put("header", requestHeaders);
            return withPublicCompatibility(result);
        } catch (JSONException invalid) {
            throw new IOException("无法读取源规则", invalid);
        }
    }

    private static HeaderSuffix splitHeaders(String rule) {
        int marker = rule.toLowerCase(Locale.ROOT).lastIndexOf(HEADER_MARKER);
        if (marker < 0) return new HeaderSuffix(rule, null);
        JSONObject headers = objectHeaders(rule.substring(marker + HEADER_MARKER.length()));
        return headers == null ? new HeaderSuffix(rule, null)
                : new HeaderSuffix(rule.substring(0, marker).trim(), headers);
    }

    private static JSONObject objectHeaders(Object raw) {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (!(raw instanceof String) || ((String) raw).trim().isEmpty()) return null;
        try {
            JSONTokener parser = new JSONTokener((String) raw);
            Object value = parser.nextValue();
            return value instanceof JSONObject && parser.nextClean() == 0 ? (JSONObject) value : null;
        } catch (JSONException invalid) {
            return null;
        }
    }

    private static boolean hasHeader(JSONObject headers, String name) {
        for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
            if (it.next().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private static void addHeaders(JSONObject target, JSONObject headers) throws JSONException {
        if (headers == null) return;
        for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
            String name = it.next();
            target.put(name, headers.get(name));
        }
    }

    private static String address(JSONObject source, String key) {
        String declaration = field(source, key);
        return declaration.startsWith("{{") || YiciyuanScript.matches(declaration)
                ? declaration : declaration.replace("\\/", "/");
    }

    static boolean searchDisabled(String url) {
        String value = url.trim();
        return value.isEmpty() || value.startsWith("#");
    }

    static boolean isAttributeName(String rule) {
        return ATTRIBUTE_NAME.matcher(rule).matches();
    }

    static boolean isEncryptedRule(String rule) {
        return ENCRYPTED.matcher(rule.trim()).find();
    }

    static JSONObject withPublicCompatibility(JSONObject source) {
        if (!source.optBoolean("_yiciyuan")) return source;
        JSONObject search = source.optJSONObject("ruleSearch");
        String host = field(source, "bookSourceUrl");
        String name = field(source, "bookSourceName");
        String url = field(source, "searchUrl");
        if (search != null && ZAIMANHUA.equals(host) && "再漫画".equals(name)
                && (ZAIMANHUA + "/app/v1/search/index?keyword=searchKey&source=0&page=searchPage&size=20").equals(url)
                && "$.data.list||$.data".equals(field(search, "bookList"))
                && "$.title".equals(field(search, "name"))) {
            try {
                // Keep support for older data arrays. An empty modern list must ignore its pagination object.
                JSONObject compatible = new JSONObject(source.toString());
                compatible.getJSONObject("ruleSearch").put("bookList", "$.data.list||$.data[0:]");
                return compatible;
            } catch (JSONException invalid) {
                return source;
            }
        }
        JSONObject toc = source.optJSONObject("ruleToc");
        JSONObject content = source.optJSONObject("ruleContent");
        if (search == null || toc == null || content == null
                || !isEncryptedRule(field(toc, "chapterList")) || !isEncryptedRule(field(content, "content"))) {
            return source;
        }
        boolean dongman = DONGMAN.equals(host) && "咚漫".equals(name)
                && (DONGMAN + "/search?keyword=searchKey").equals(url)
                && field(search, "bookList").contains("doc.select('.card_wrap>ul li')")
                && "class.subj@text".equals(field(search, "name")) && "tag.a@href".equals(field(search, "bookUrl"));
        boolean honkai = HONKAI.equals(host) && "崩坏3漫画".equals(name)
                && (HONKAI + "/book{,_error.html}").equals(url)
                && "class.container".equals(field(search, "bookList"))
                && "class.container-title@text".equals(field(search, "name"))
                && ("id@js:\"" + HONKAI + "/book/\"+result").equals(field(search, "bookUrl"));
        if (!dongman && !honkai) return source;
        try {
            JSONObject compatible = new JSONObject(source.toString());
            JSONObject publicToc = new JSONObject();
            String images;
            if (dongman) {
                publicToc.put("chapterList", "-@css:#_listUl>li>a[href*=/viewer]")
                        .put("chapterName", "@css:.subj@text").put("chapterUrl", "href")
                        .put("nextTocUrl", "@css:.paginate a:has(.on)+a[href]@href||@css:.paginate a.pg_next[href]@href");
                images = "@css:#_imageList img[data-url]@data-url";
            } else {
                JSONObject info = compatible.optJSONObject("ruleBookInfo");
                if (info == null) info = new JSONObject();
                info.put("tocUrl", "@js:baseUrl.split(/[?#]/)[0].replace(/\\/+$/, '') + '/get_chapter'");
                compatible.put("ruleBookInfo", info);
                publicToc.put("chapterList", "-$[*]").put("chapterName", "$.title")
                        .put("chapterUrl", "/book/{$.bookid}/{$.chapterid}");
                images = "@css:img.comic_img[data-original]@data-original";
            }
            compatible.put("ruleToc", publicToc);
            compatible.put("ruleContent", new JSONObject().put("content", images));
            compatible.put("imageHeaders", new JSONObject().put("Referer", "thisPage"));
            return compatible;
        } catch (JSONException invalid) {
            return source;
        }
    }

    static String readingIssue(JSONObject source) {
        if (!source.optBoolean("_yiciyuan")) return "";
        JSONObject toc = source.optJSONObject("ruleToc");
        JSONObject content = source.optJSONObject("ruleContent");
        if (toc != null && isEncryptedRule(field(toc, "chapterList"))) {
            return "此源的章节目录规则已加密";
        }
        if (content != null && isEncryptedRule(field(content, "content"))) {
            return "此源的图片提取规则已加密";
        }
        return "";
    }

    static String restoreEscapedRule(String rule) {
        // Older exports sometimes retain one extra layer of JSON escaping inside a rule field.
        if (!ESCAPED_SCRIPT_START.matcher(rule).find() && !rule.contains(",{\\\"headers\\\":")
                && !rule.toLowerCase(Locale.ROOT).contains("@header:{\\\"")) return rule;
        for (int i = 0; i < rule.length(); i++) {
            char character = rule.charAt(i);
            if (character == '"' || character < ' ') return rule;
            if (character != '\\') continue;
            if (++i >= rule.length()) return rule;
            char escaped = rule.charAt(i);
            if (escaped == 'u') {
                for (int digit = 0; digit < 4; digit++) {
                    if (++i >= rule.length() || Character.digit(rule.charAt(i), 16) < 0) return rule;
                }
            } else if ("\"\\/bfnrt".indexOf(escaped) < 0) {
                return rule;
            }
        }
        try {
            return (String) new JSONTokener('"' + rule + '"').nextValue();
        } catch (JSONException invalid) {
            return rule;
        }
    }

    static String quoteImageOptions(String html) {
        // An old script pattern appends request JSON inside the quoted img attribute.
        Matcher options = INLINE_IMAGE_OPTIONS.matcher(html);
        StringBuilder corrected = null;
        int last = 0;
        while (options.find()) {
            int start = options.end();
            int end = objectEnd(html, start);
            String quote = options.group(1);
            if (end < 0 || !html.startsWith(quote, end)) continue;
            String json = html.substring(start, end);
            JSONObject parsed = objectHeaders(json);
            if (parsed == null || parsed.optJSONObject("headers") == null) continue;
            if (corrected == null) corrected = new StringBuilder(html.length());
            corrected.append(html, last, start).append(json.replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;")
                    .replace(quote, "\"".equals(quote) ? "&quot;" : "&#39;"));
            last = end;
        }
        return corrected == null ? html : corrected.append(html, last, html.length()).toString();
    }

    private static int objectEnd(String text, int start) {
        int depth = 0;
        boolean quoted = false;
        for (int i = start, limit = Math.min(text.length(), start + 8192); i < limit; i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (character == '\\') i++;
                else if (character == '"') quoted = false;
            } else if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return i + 1;
            }
        }
        return -1;
    }

    private static void set(JSONObject target, String key, String value) throws JSONException {
        if (!value.isEmpty()) target.put(key, value);
    }

    private static String field(JSONObject source, String... keys) {
        for (String key : keys) {
            String value = source.isNull(key) ? "" : source.optString(key, "").trim();
            if (!value.isEmpty()) return key.startsWith("rule") ? restoreEscapedRule(value) : value;
        }
        return "";
    }

    private static final class HeaderSuffix {
        final String rule;
        final JSONObject headers;

        HeaderSuffix(String rule, JSONObject headers) {
            this.rule = rule;
            this.headers = headers;
        }
    }
}
