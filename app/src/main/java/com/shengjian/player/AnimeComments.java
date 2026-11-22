package com.shengjian.player;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bangumi episode discussions. The request and response model follows Kazumi 2.4.4's BangumiApi. */
final class AnimeComments {
    private static final String API = "https://api.bgm.tv";
    private static final String DISCUSSIONS = "https://next.bgm.tv";
    private static final String SITE = "https://bangumi.tv";
    private static final List<String> WEB_ORIGINS = List.of(SITE, "https://bgm.tv", "https://chii.in");
    private static final int REQUEST_TIMEOUT = 5_000;
    private static final int EPISODES_PER_PAGE = 100;
    private static final int MAX_EPISODE_PAGES = 10;
    private static final int MAX_COMMENTS = 2_000;
    private static final int MAX_REPLIES = 200;
    private static final int MAX_BODY_LENGTH = 12_000;
    private static final int MAX_BODY_IMAGES = 8;
    private static final Pattern EXACT_TITLE = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern TITLE_SUFFIX = Pattern.compile("(?iu)\\s*(?:[\\[(](?:国语版?|國語版?|日语版?|日語版?|粤语版?|粵語版?|无修版?|無修版?|高清|1080p|2160p|4k)[\\])]|(?:国语版|國語版|日语版|日語版|粤语版|粵語版|在线观看|線上觀看|全集))$");
    private static final Pattern SUBJECT_PATH = Pattern.compile("/subject/(\\d{1,12})/?");
    private static final Pattern EPISODE_PATH = Pattern.compile("/ep/(\\d{1,12})/?");
    private static final Pattern EPISODE_HEADING = Pattern.compile("^([0-9]{1,8}(?:\\.[0-9]{1,6})?)\\s*[.．、]\\s*(.*)$");
    private static final Pattern SPECIAL_HEADING = Pattern.compile("(?i)^([A-Z]{1,5}\\s*[0-9]+(?:\\.[0-9]+)?)\\s*[.．、]\\s*(.*)$");
    private static final Pattern POST_DATE = Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}\\s+\\d{1,2}:\\d{1,2}");
    private static final DateTimeFormatter POST_TIME = DateTimeFormatter.ofPattern("uuuu-M-d H:m", Locale.ROOT)
            .withResolverStyle(ResolverStyle.STRICT);
    private static final ZoneOffset POST_ZONE = ZoneOffset.ofHours(8);
    private static final Pattern BB_IMAGE = Pattern.compile("(?is)\\[img(?:=[^]\\r\\n]{1,32})?]([^\\[]*?)\\[/img]");
    private static final Pattern BB_FORMAT = Pattern.compile("(?i)\\[(/?)(b|i|u|s|quote|url|color|size|align|spoiler|mask|code)(?:=([^]\\r\\n]{1,1024}))?]");
    private static final Pattern BB_COLOR = Pattern.compile("(?:#[\\da-fA-F]{6}|[a-zA-Z]{3,12})");
    private static final Safelist COMMENT_HTML = Safelist.none()
            .addTags("b", "strong", "i", "em", "u", "s", "strike", "del", "br", "p", "blockquote", "tt", "font", "a")
            .addAttributes("font", "color").addAttributes("a", "href").addProtocols("a", "href", "https");
    private static final Pattern AVATAR_STYLE = Pattern.compile("(?i)background(?:-image)?\\s*:\\s*url\\(\\s*['\"]?([^\\s'\"()]+)['\"]?\\s*\\)");
    private static final Pattern AVATAR_PATH = Pattern.compile("/(?:r/[0-9]{1,4}/)?pic/user/[a-z]/[0-9A-Za-z_./-]+");
    private static final String USER_AGENT = "shengjian/ANON-TOOL/" + BuildConfig.VERSION_NAME + " (Android)";

    record Comment(long id, String author, String text, String html, long createdAt, String avatar, List<String> images) {}
    record Thread(Comment comment, List<Comment> replies) {}
    record Discussion(long id, String title, String originalTitle, List<Thread> threads) {}
    record EpisodeChoice(long id, int position, String label, String title, String originalTitle) {}

    static final class DiscussionFailure extends IOException {
        final long episodeId;
        DiscussionFailure(long episodeId, IOException cause) {
            super(cause);
            this.episodeId = episodeId;
        }
    }

    record Episode(long id, String title, String originalTitle, int count, int total, boolean ambiguous) {
        boolean match() { return id > 0; }
    }

    private AnimeComments() {}

    static int number(AnimeEngine.Episode episode) {
        int stated = AnimeDanmaku.episodeNumber(episode.sourceTitle);
        return stated > 0 ? stated : episode.number;
    }

    static boolean sameTitle(String left, String right) {
        if (left == null || right == null) return false;
        String key = titleKey(cleanTitle(left));
        return !key.isEmpty() && key.equals(titleKey(cleanTitle(right)));
    }

    static long matchSubject(String title) throws IOException {
        String name = cleanTitle(title);
        if (name.isEmpty() || name.length() > 240) return 0;
        IOException apiFailure = null;
        try {
            JSONObject filter = new JSONObject().put("type", new JSONArray().put(2)).put("nsfw", false);
            String body = new JSONObject().put("keyword", name).put("sort", "match").put("filter", filter).toString();
            long subject = readSubject(request(API + "/v0/search/subjects?limit=20&offset=0", body, false), name);
            if (subject != 0) return Math.max(0, subject);
        } catch (IOException unavailable) {
            requireFallback(unavailable);
            apiFailure = unavailable;
        } catch (JSONException malformed) {
            throw new IOException("无法构建番剧查询", malformed);
        }
        try {
            return website("/subject_search/" + encode(name) + "?cat=2", body -> matchSubjectPage(body, name));
        } catch (IOException unavailable) {
            requireFallback(unavailable);
            if (apiFailure == null) return 0;
            unavailable.addSuppressed(apiFailure);
            if (apiFailure instanceof ReaderSources.HttpFailure http
                    && (http.status == 401 || http.status == 403 || http.status == 404)) {
                try {
                    return matchSubject(request(API + "/search/subject/" + encode(name)
                            + "?type=2&responseGroup=small&max_results=20", null, false), name);
                } catch (IOException legacyFailure) {
                    unavailable.addSuppressed(legacyFailure);
                }
            }
            throw unavailable;
        }
    }

    static long matchSubject(String json, String name) throws IOException {
        return Math.max(0, readSubject(json, name));
    }

    private static long readSubject(String json, String name) throws IOException {
        JSONObject root = object(json);
        JSONArray subjects = root.optJSONArray("data");
        if (subjects == null) subjects = root.optJSONArray("list");
        if (subjects == null && root.has("results") && root.optInt("results", -1) == 0) return 0;
        if (subjects == null) throw new IOException("Bangumi 条目响应格式有误");
        long found = 0;
        String key = titleKey(cleanTitle(name));
        if (key.isEmpty()) return 0;
        for (int i = 0; i < subjects.length(); i++) {
            JSONObject item = subjects.optJSONObject(i);
            if (item == null || item.optInt("type") != 2 || item.optBoolean("nsfw")) continue;
            long id = item.optLong("id", 0);
            if (id <= 0 || !matchesSubject(item, key)) continue;
            if (found != 0 && found != id) return -1;
            found = id;
        }
        return found;
    }

    private static boolean matchesSubject(JSONObject item, String key) {
        if (key.equals(titleKey(item.optString("name"))) || key.equals(titleKey(item.optString("name_cn")))) return true;
        JSONArray fields = item.optJSONArray("infobox");
        if (fields == null) return false;
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.optJSONObject(i);
            if (field == null || !List.of("别名", "別名").contains(field.optString("key"))) continue;
            Object value = field.opt("value");
            if (value instanceof String alias && key.equals(titleKey(alias))) return true;
            if (value instanceof JSONArray aliases) {
                for (int j = 0; j < aliases.length(); j++) {
                    JSONObject alias = aliases.optJSONObject(j);
                    if (alias != null && key.equals(titleKey(alias.optString("v")))) return true;
                }
            }
        }
        return false;
    }

    static long matchSubjectPage(String html, String name) throws IOException {
        Element list = Jsoup.parse(html).getElementById("browserItemList");
        if (list == null) throw new IOException("Bangumi 条目网页格式有误");
        long found = 0;
        String key = titleKey(cleanTitle(name));
        if (key.isEmpty()) return 0;
        for (Element heading : list.select("h3")) {
            Element anchor = heading.selectFirst("a[href]");
            Element original = heading.selectFirst("small");
            Element type = heading.selectFirst(".ico_subject_type");
            if (anchor == null || type != null && !type.hasClass("subject_type_2")) continue;
            if (!key.equals(titleKey(anchor.text())) && (original == null || !key.equals(titleKey(original.text())))) continue;
            long id = pathId(anchor, SUBJECT_PATH);
            if (id <= 0) continue;
            if (found != 0 && found != id) return 0;
            found = id;
        }
        return found;
    }

    static Discussion fetch(long subjectId, int number) throws IOException {
        if (subjectId <= 0 || number <= 0) return new Discussion(0, "", "", List.of());
        Episode episode = findEpisode(subjectId, number);
        if (!episode.match()) return new Discussion(0, "", "", List.of());
        return fetchDiscussion(episode.id(), episode.title(), episode.originalTitle());
    }

    static Discussion fetch(EpisodeChoice episode) throws IOException {
        return fetchDiscussion(episode.id(), episode.title(), episode.originalTitle());
    }

    private static Discussion fetchDiscussion(long episodeId, String title, String originalTitle) throws IOException {
        List<Thread> threads;
        try {
            threads = parseThreads(request(DISCUSSIONS + "/p1/episodes/" + episodeId + "/comments", null, false));
        } catch (IOException unavailable) {
            try {
                requireFallback(unavailable);
                threads = website("/ep/" + episodeId, AnimeComments::parseDiscussionPage);
            } catch (IOException websiteFailure) {
                if (websiteFailure != unavailable) websiteFailure.addSuppressed(unavailable);
                throw new DiscussionFailure(episodeId, websiteFailure);
            }
        }
        return new Discussion(episodeId, title, originalTitle, threads);
    }

    private static Episode findEpisode(long subjectId, int number) throws IOException {
        Episode apiResult = null;
        IOException apiFailure = null;
        try {
            JSONArray one = episodes(object(request(episodesUrl(subjectId, number - 1, 1) + "&type=0", null, false)));
            JSONObject row = one.optJSONObject(0);
            if (row != null && row.optInt("type") == 0 && row.optDouble("sort", Double.NaN) == number) {
                EpisodeChoice selected = readChoice(row, number);
                if (selected != null) return new Episode(selected.id(), selected.title(), selected.originalTitle(), 1, 1, false);
            }
            apiResult = findEpisodeApi(subjectId, number);
            if (apiResult.match() || apiResult.ambiguous()) return apiResult;
        } catch (IOException unavailable) {
            requireFallback(unavailable);
            apiFailure = unavailable;
        }
        try {
            return website("/subject/" + subjectId + "/ep", body -> parseEpisodePage(body, number));
        } catch (IOException unavailable) {
            requireFallback(unavailable);
            if (apiResult != null) return apiResult;
            unavailable.addSuppressed(apiFailure);
            throw unavailable;
        }
    }

    static List<EpisodeChoice> catalogue(long subjectId) throws IOException {
        if (subjectId <= 0) return List.of();
        try {
            List<EpisodeChoice> choices = new ArrayList<>();
            int offset = 0;
            for (int page = 0; page < MAX_EPISODE_PAGES; page++) {
                JSONObject response = object(request(episodesUrl(subjectId, offset, EPISODES_PER_PAGE), null, false));
                JSONArray rows = episodes(response);
                for (int i = 0; i < rows.length(); i++) {
                    EpisodeChoice choice = readChoice(rows.optJSONObject(i), offset + i + 1);
                    if (choice != null) choices.add(choice);
                }
                offset += rows.length();
                if (rows.length() == 0 || offset >= response.optInt("total", offset)) break;
            }
            return List.copyOf(choices);
        } catch (IOException unavailable) {
            requireFallback(unavailable);
            try {
                return website("/subject/" + subjectId + "/ep", AnimeComments::parseEpisodeCatalogue);
            } catch (IOException websiteFailure) {
                websiteFailure.addSuppressed(unavailable);
                throw websiteFailure;
            }
        }
    }

    private static String episodesUrl(long subjectId, int offset, int limit) {
        return API + "/v0/episodes?subject_id=" + subjectId + "&offset=" + offset + "&limit=" + limit;
    }

    private static JSONArray episodes(JSONObject response) throws IOException {
        JSONArray rows = response.optJSONArray("data");
        if (rows == null) throw new IOException("Bangumi 分集响应格式有误");
        return rows;
    }

    private static EpisodeChoice readChoice(JSONObject row, int position) {
        if (row == null) return null;
        long id = row.optLong("id", 0);
        if (id <= 0) return null;
        String sort = row.opt("sort") instanceof Number ? row.optString("sort") : Integer.toString(position);
        String label = switch (row.optInt("type")) {
            case 0 -> "第 " + sort + " 话";
            case 1 -> "SP " + sort;
            case 2 -> "OP " + sort;
            case 3 -> "ED " + sort;
            default -> "第 " + position + " 项";
        };
        return new EpisodeChoice(id, position, label, episodeTitle(row), text(row, "name", 180));
    }

    static List<EpisodeChoice> parseEpisodeCatalogue(String html) throws IOException {
        Document page = Jsoup.parse(html);
        if (page.selectFirst("ul.line_list") == null) throw new IOException("Bangumi 章节网页格式有误");
        List<EpisodeChoice> choices = new ArrayList<>();
        for (Element anchor : page.select("ul.line_list > li > h6 a[href]")) {
            long id = pathId(anchor, EPISODE_PATH);
            if (id <= 0) continue;
            String heading = Normalizer.normalize(anchor.text(), Normalizer.Form.NFKC);
            Matcher regular = EPISODE_HEADING.matcher(heading);
            Matcher special = SPECIAL_HEADING.matcher(heading);
            int position = choices.size() + 1;
            if (regular.matches()) {
                choices.add(new EpisodeChoice(id, position, "第 " + regular.group(1) + " 话", bounded(regular.group(2), 180), ""));
            } else if (special.matches()) {
                choices.add(new EpisodeChoice(id, position, special.group(1), bounded(special.group(2), 180), ""));
            }
        }
        return List.copyOf(choices);
    }

    private static Episode findEpisodeApi(long subjectId, int number) throws IOException {
        String address = API + "/v0/episodes?subject_id=" + subjectId + "&type=0";
        int likelyOffset = (number - 1) / EPISODES_PER_PAGE * EPISODES_PER_PAGE;
        Episode episode = requestEpisodes(address, likelyOffset, number);
        if (episode.match() || episode.ambiguous() || likelyOffset == 0 && episode.count() >= episode.total()) return episode;
        int offset = 0;
        for (int page = 0; page < MAX_EPISODE_PAGES; page++) {
            if (offset != likelyOffset) episode = requestEpisodes(address, offset, number);
            if (episode.match() || episode.ambiguous() || episode.count() == 0) break;
            offset += episode.count();
            if (offset >= episode.total()) break;
        }
        return episode;
    }

    private static Episode requestEpisodes(String address, int offset, int number) throws IOException {
        return readEpisode(request(address + "&limit=" + EPISODES_PER_PAGE + "&offset=" + offset,
                null, false), number);
    }

    static long matchingEpisodeId(String json, int number) throws IOException {
        return readEpisode(json, number).id();
    }

    private static Episode readEpisode(String json, int number) throws IOException {
        JSONObject root = object(json);
        JSONArray episodes = episodes(root);
        int total = root.optInt("total", episodes.length());
        Episode sortNumber = null, localNumber = null;
        boolean complete = episodes.length() >= total;
        for (int i = 0; i < episodes.length(); i++) {
            JSONObject row = episodes.optJSONObject(i);
            if (row == null || row.optInt("type", 0) != 0) continue;
            long id = row.optLong("id", 0);
            if (id <= 0) continue;
            boolean sortMatch = row.optDouble("sort", Double.NaN) == number;
            boolean localMatch = complete && row.optDouble("ep", Double.NaN) == number;
            if (!sortMatch && !localMatch) continue;
            Episode candidate = new Episode(id, episodeTitle(row), text(row, "name", 180), episodes.length(), total, false);
            if (sortMatch) {
                if (sortNumber != null && sortNumber.id() != id) {
                    return new Episode(0, "", "", episodes.length(), total, true);
                }
                sortNumber = candidate;
            }
            if (localMatch) {
                if (localNumber != null && localNumber.id() != id) {
                    return new Episode(0, "", "", episodes.length(), total, true);
                }
                localNumber = candidate;
            }
        }
        if (sortNumber != null && localNumber != null && sortNumber.id() != localNumber.id()) {
            return new Episode(0, "", "", episodes.length(), total, true);
        }
        if (sortNumber != null) return sortNumber;
        return localNumber != null ? localNumber : new Episode(0, "", "", episodes.length(), total, false);
    }

    private static String episodeTitle(JSONObject row) {
        String label = text(row, "name_cn", 180);
        return label.isEmpty() ? text(row, "name", 180) : label;
    }

    static Episode parseEpisodePage(String html, int number) throws IOException {
        Document page = Jsoup.parse(html);
        if (page.selectFirst("ul.line_list") == null) throw new IOException("Bangumi 章节网页格式有误");
        Episode found = new Episode(0, "", "", 0, 0, false);
        for (Element anchor : page.select("ul.line_list > li > h6 a[href]")) {
            Matcher heading = EPISODE_HEADING.matcher(Normalizer.normalize(anchor.text(), Normalizer.Form.NFKC));
            if (!heading.matches() || Double.parseDouble(heading.group(1)) != number) continue;
            long id = pathId(anchor, EPISODE_PATH);
            if (id <= 0) continue;
            if (found.match() && found.id() != id) return new Episode(0, "", "", 0, 0, true);
            found = new Episode(id, bounded(heading.group(2), 180), "", 0, 0, false);
        }
        return found;
    }

    static List<Thread> parseThreads(String json) throws IOException {
        try {
            Object value = new JSONTokener(json).nextValue();
            if (!(value instanceof JSONArray rows)) throw new IOException("Bangumi 评论响应格式有误");
            List<Thread> threads = new ArrayList<>();
            for (int i = 0; i < rows.length() && threads.size() < MAX_COMMENTS; i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                Comment comment = readComment(row);
                if (comment == null) continue;
                JSONArray children = row.optJSONArray("replies");
                List<Comment> replies = new ArrayList<>();
                if (children != null) {
                    for (int j = 0; j < children.length() && replies.size() < MAX_REPLIES; j++) {
                        JSONObject child = children.optJSONObject(j);
                        Comment reply = child == null ? null : readComment(child);
                        if (reply != null) replies.add(reply);
                    }
                }
                threads.add(new Thread(comment, List.copyOf(replies)));
            }
            return List.copyOf(threads);
        } catch (JSONException invalid) {
            throw new IOException("Bangumi 评论响应格式有误", invalid);
        }
    }

    static List<Thread> parseDiscussionPage(String html) throws IOException {
        Element comments = Jsoup.parse(html).getElementById("comment_list");
        if (comments == null) throw new IOException("Bangumi 评论网页格式有误");
        List<Thread> threads = new ArrayList<>();
        for (Element row : comments.children()) {
            if (threads.size() >= MAX_COMMENTS) break;
            if (!row.hasClass("row_reply") || !row.id().startsWith("post_")) continue;
            Comment comment = readPost(row, false);
            if (comment == null) continue;
            List<Comment> replies = new ArrayList<>();
            for (Element child : row.select(".topic_sub_reply [id^=post_]")) {
                if (replies.size() >= MAX_REPLIES) break;
                Comment reply = readPost(child, true);
                if (reply != null) replies.add(reply);
            }
            threads.add(new Thread(comment, List.copyOf(replies)));
        }
        return List.copyOf(threads);
    }

    private static Comment readPost(Element row, boolean reply) {
        if (row.hasAttr("hidden") || row.attr("style").replace(" ", "").contains("display:none")) return null;
        Element body = postPart(row, reply ? ".cmt_sub_content, .reply_content .message" : ".reply_content .message");
        if (body == null) return null;
        Element post = body.clone();
        post.select("script, style, iframe, [hidden], .topic_sub_reply").remove();
        LinkedHashSet<String> images = new LinkedHashSet<>();
        for (Element image : post.select("img")) {
            if (addMedia(images, image.attr("src"))) image.remove();
            else image.replaceWith(new TextNode("［图片］"));
        }
        String content = bounded(post.wholeText(), MAX_BODY_LENGTH);
        String html = post.html().length() <= MAX_BODY_LENGTH * 4 ? Jsoup.clean(post.html(), COMMENT_HTML) : "";
        Element author = postPart(row, ".userInfo strong a[href^=/user/], .userName a[href^=/user/], strong a[href^=/user/]");
        String name = author == null ? "" : bounded(author.text(), 80);
        Element time = postPart(row, ".re_info small, small.time");
        return new Comment(pathId(row.id().substring(5)), name.isEmpty() ? "Bangumi 用户" : name, content, html,
                time == null ? 0 : parseDate(time.text()), postAvatar(row), List.copyOf(images));
    }

    private static Element postPart(Element row, String selector) {
        for (Element part : row.select(selector)) {
            if (part.closest("[id^=post_]") == row) return part;
        }
        return null;
    }

    private static String postAvatar(Element row) {
        for (Element element : row.select(".avatarNeue, a.avatar span[style], a.avatar img, img.avatar")) {
            if (element.closest("[id^=post_]") != row) continue;
            String image = avatarUrl(element.attr("src"));
            Matcher style = AVATAR_STYLE.matcher(element.attr("style"));
            if (image.isEmpty() && style.find()) image = avatarUrl(style.group(1));
            if (!image.isEmpty()) return image;
        }
        return "";
    }

    private static long parseDate(String value) {
        Matcher found = POST_DATE.matcher(value);
        if (!found.find()) return 0;
        try {
            return LocalDateTime.parse(found.group().replaceAll("\\s+", " "), POST_TIME).toEpochSecond(POST_ZONE);
        } catch (DateTimeParseException invalid) {
            return 0;
        }
    }

    private static Comment readComment(JSONObject row) {
        if (row.optBoolean("deleted") || row.optInt("state") != 0) return null;
        String source = text(row, "content", MAX_BODY_LENGTH);
        String content = BB_FORMAT.matcher(BB_IMAGE.matcher(source).replaceAll("［图片］")).replaceAll("").strip();
        LinkedHashSet<String> images = new LinkedHashSet<>();
        String html = formatBbcode(source, images);
        JSONObject user = row.optJSONObject("user");
        String name = user == null ? "" : text(user, "nickname", 80);
        if (name.isEmpty() && user != null) name = text(user, "username", 80);
        String image = "";
        JSONObject avatar = user == null ? null : user.optJSONObject("avatar");
        if (avatar != null) {
            for (String size : List.of("small", "medium", "large")) {
                image = avatarUrl(avatar.optString(size));
                if (!image.isEmpty()) break;
            }
        }
        return new Comment(row.optLong("id"), name.isEmpty() ? "Bangumi 用户" : name,
                content, html, row.optLong("createdAt", 0), image, List.copyOf(images));
    }

    private record Markup(String tag, Element node, String href) {}

    private static String formatBbcode(String source, LinkedHashSet<String> images) {
        Matcher pictures = BB_IMAGE.matcher(source);
        StringBuffer text = new StringBuffer(source.length());
        while (pictures.find()) {
            pictures.appendReplacement(text, addMedia(images, pictures.group(1)) ? "" : "［图片］");
        }
        pictures.appendTail(text);
        Document document = Document.createShell("");
        document.outputSettings().prettyPrint(false);
        Element root = document.body();
        ArrayDeque<Markup> open = new ArrayDeque<>();
        open.push(new Markup("", root, ""));
        Matcher format = BB_FORMAT.matcher(text);
        int end = 0;
        while (format.find()) {
            appendLines(open.peek().node(), text.substring(end, format.start()));
            end = format.end();
            String tag = format.group(2).toLowerCase(Locale.ROOT);
            if (!format.group(1).isEmpty()) {
                if (open.peek().tag().equals(tag)) finishMarkup(open.pop());
                continue;
            }
            if (open.size() >= 32) continue;
            String attr = format.group(3) == null ? "" : format.group(3).strip();
            String htmlTag = switch (tag) {
                case "b", "i", "u", "s" -> tag;
                case "quote" -> "blockquote";
                case "mask", "spoiler" -> "anon-spoiler";
                case "url" -> "a";
                case "code" -> "tt";
                case "color" -> "font";
                default -> "span";
            };
            Element node = open.peek().node().appendElement(htmlTag);
            if (tag.equals("color") && BB_COLOR.matcher(attr).matches()) node.attr("color", attr);
            open.push(new Markup(tag, node, attr));
        }
        appendLines(open.peek().node(), text.substring(end));
        while (open.size() > 1) finishMarkup(open.pop());
        return root.html();
    }

    private static void finishMarkup(Markup markup) {
        if (!markup.tag().equals("url")) return;
        String address = httpsUrl(markup.href().isEmpty() ? markup.node().text() : markup.href());
        if (address.isEmpty()) markup.node().tagName("span");
        else markup.node().attr("href", address);
    }

    private static void appendLines(Element element, String text) {
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) element.appendElement("br");
            if (!lines[i].isEmpty()) element.appendChild(new TextNode(lines[i]));
        }
    }

    private static boolean addMedia(LinkedHashSet<String> images, String raw) {
        String url = httpsUrl(raw);
        if (url.isEmpty() || images.size() >= MAX_BODY_IMAGES && !images.contains(url)) return false;
        URI address = URI.create(url);
        String path = address.getRawPath();
        if (!"lain.bgm.tv".equalsIgnoreCase(address.getHost()) || path == null || !path.startsWith("/pic/")) return false;
        images.add(url);
        return true;
    }

    private static String httpsUrl(String raw) {
        String value = raw.strip();
        if (value.isEmpty() || value.length() > 1_024) return "";
        try {
            URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return "";
            return uri.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String avatarUrl(String value) {
        if (value.isEmpty() || value.length() > 1_024) return "";
        try {
            if (value.startsWith("//")) value = "https:" + value;
            URI url = URI.create(value);
            if (!"https".equals(url.getScheme()) || !"lain.bgm.tv".equalsIgnoreCase(url.getHost())
                    || url.getUserInfo() != null || url.getPort() != -1 || url.getFragment() != null) return "";
            String path = url.getPath();
            if (path == null || !AVATAR_PATH.matcher(path).matches()
                    || path.contains("..")) return "";
            return url.toASCIIString();
        } catch (IllegalArgumentException invalid) {
            return "";
        }
    }

    private static String titleKey(String value) {
        return EXACT_TITLE.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)).replaceAll("");
    }

    static String episodePage(long id) { return SITE + "/ep/" + id; }

    private static String cleanTitle(String title) {
        String value = Normalizer.normalize(title, Normalizer.Form.NFKC).strip();
        String clean;
        while (!(clean = TITLE_SUFFIX.matcher(value).replaceFirst("").strip()).equals(value)) value = clean;
        return value;
    }

    private static long pathId(Element anchor, Pattern pattern) {
        Matcher matched = pattern.matcher(anchor.attr("href"));
        return matched.matches() ? pathId(matched.group(1)) : 0;
    }

    private static long pathId(String value) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { return 0; }
    }

    private static String encode(String name) throws IOException {
        return URLEncoder.encode(name, "UTF-8").replace("+", "%20");
    }

    private static String text(JSONObject row, String key, int limit) {
        if (!(row.opt(key) instanceof String value)) return "";
        return bounded(value, limit);
    }

    private static String bounded(String value, int limit) {
        value = value.strip().replace("\u0000", "");
        if (value.length() <= limit) return value;
        return value.substring(0, Character.isHighSurrogate(value.charAt(limit - 1)) ? limit - 1 : limit) + "…";
    }

    private static JSONObject object(String json) throws IOException {
        try { return new JSONObject(json); }
        catch (JSONException invalid) { throw new IOException("Bangumi 响应格式有误", invalid); }
    }

    private interface PageReader<T> {
        T parse(String html) throws IOException;
    }

    private static <T> T website(String path, PageReader<T> reader) throws IOException {
        IOException firstFailure = null;
        for (String host : WEB_ORIGINS) {
            try {
                return reader.parse(request(host + path, null, true));
            } catch (IOException failure) {
                requireFallback(failure);
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        if (firstFailure != null) throw firstFailure;
        throw new IOException("Bangumi 网页不可用");
    }

    private static void requireFallback(IOException failure) throws IOException {
        if (java.lang.Thread.currentThread().isInterrupted()
                || failure instanceof ReaderSources.HttpFailure http && http.status == 429) throw failure;
        ReaderSearchQueue.RequestBudget budget = ReaderSearchQueue.RequestBudget.current();
        if (budget != null) budget.check();
    }

    private static String request(String url, String body, boolean html) throws IOException {
        String accept = html ? "text/html" : "application/json";
        ReaderSources.Response response = KazumiBangumiTransport.fetch(url, Map.of("User-Agent", USER_AGENT,
                "Accept", accept, "Content-Type", "application/json"), body, REQUEST_TIMEOUT);
        URI expected = URI.create(url);
        URI finalUrl = URI.create(response.url);
        String finalOrigin = "https://" + finalUrl.getHost();
        if (!"https".equals(finalUrl.getScheme()) || finalUrl.getUserInfo() != null
                || finalUrl.getPort() != -1 && finalUrl.getPort() != 443
                || !(expected.getHost().equalsIgnoreCase(finalUrl.getHost()) || html && WEB_ORIGINS.contains(finalOrigin))) {
            throw new IOException("Bangumi 返回了异站跳转");
        }
        return response.body;
    }
}
