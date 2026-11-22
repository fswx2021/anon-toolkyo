package com.shengjian.player;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONObject;

final class AnimeDanmakuVerification {
    private AnimeDanmakuVerification() {}

    static void verify() throws Exception {
        AnimeDanmaku.Credentials credentials = new AnimeDanmaku.Credentials("local-fixture", "fixture-only");
        require(credentials.configured() && !new AnimeDanmaku.Credentials("local\nfixture", "value").configured(),
                "app credentials reject header injection");
        require(AnimeDanmaku.signature(credentials, 1735660800, "/api/v2/comment/100012345")
                .equals("mDzINUxXrVi5g43k+GZkSvj0eYXcopV4dceGVVqn5oI="), "SHA256 UTF-8 and standard Base64 signing vector");
        require(AnimeDanmaku.redirect(URI.create("https://api.dandanplay.net/api/v2/comment/123"),
                "https://acceleration.example.org/reply?ticket=fixture").getHost().equals("acceleration.example.org"),
                "documented search result file acceleration accepts HTTPS");
        reject(() -> AnimeDanmaku.redirect(URI.create("https://api.dandanplay.net/a"), "http://other.example.org/a"));
        reject(() -> AnimeDanmaku.redirect(URI.create("https://api.dandanplay.net/a"), "https://secret@example.org/a"));

        String exact = "{\"success\":true,\"animes\":[{\"animeTitle\":\"海边电台 第二季\",\"episodes\":[{\"episodeId\":100012111,\"episodeTitle\":\"第2集\"}]},"
                + "{\"animeTitle\":\"《海边电台》\",\"episodes\":[{\"episodeId\":200000100012345,\"episodeTitle\":\"第０２話 海边\"},"
                + "{\"episodeId\":100012333,\"episodeTitle\":\"第3集\"},{\"episodeId\":100012222,\"episodeTitle\":\"第2集 预告\"}]}]}";
        List<AnimeDanmaku.Candidate> matches = AnimeDanmaku.parseSearch(exact, "海边电台", 2);
        require(matches.size() == 4 && AnimeDanmaku.bestMatch(matches).episodeId == 200000100012345L,
                "match exact normalized title and explicit episode, preserving 64-bit IDs");
        require(AnimeDanmaku.bestMatch(AnimeDanmaku.parseSearch(exact.replace("第3集", "EP02"), "海边电台", 2)) == null
                && AnimeDanmaku.bestMatch(AnimeDanmaku.parseSearch(exact.replace("\"success\":true", "\"hasMore\":true"), "海边电台", 2)) == null
                && AnimeDanmaku.bestMatch(AnimeDanmaku.parseSearch(exact, "海边电台", 0)) == null
                && AnimeDanmaku.episodeNumber("第13集 总集篇") == 0 && AnimeDanmaku.episodeNumber("EP12.5") == 0,
                "ambiguous, truncated, untitled and special episode matches require user choice");

        String mapped = "{\"success\":true,\"bangumi\":{\"animeTitle\":\"海辺ラジオ\",\"episodes\":["
                + "{\"episodeId\":200000100045678,\"episodeNumber\":\"02\",\"episodeTitle\":\"雨后的海\"},"
                + "{\"episodeId\":20,\"episodeNumber\":\"S2\",\"episodeTitle\":\"第2话 番外\"},"
                + "{\"episodeId\":30,\"episodeTitle\":\"第3话\"}]}}";
        AnimeDanmaku.Candidate mappedMatch = AnimeDanmaku.parseMappedEpisode(mapped, "海边电台", 2);
        require(mappedMatch != null && mappedMatch.episodeId == 200000100045678L
                && mappedMatch.animeTitle.equals("海辺ラジオ"), "Bangumi mapping uses returned episode ID and structured episode number");
        require(AnimeDanmaku.parseMappedEpisode(mapped, "海边电台", 3).episodeId == 30L
                && AnimeDanmaku.parseMappedEpisode(mapped, "海边电台", 1) == null
                && AnimeDanmaku.parseMappedEpisode(mapped.replace("第3话", "第2话"), "海边电台", 2) == null
                && AnimeDanmaku.parseMappedEpisode(mapped.replace("雨后的海", "第1话"), "海边电台", 2) == null
                && AnimeDanmaku.match(credentials, "海边电台", 0, 1) == null,
                "ambiguous, conflicting or absent episode numbers never match by list order");

        String titleResponse = """
                {"success":true,"animes":[{"animeId":2,"animeTitle":"海边电台 第二季"},
                {"animeId":7,"animeTitle":"《海边电台》"},{"animeId":7,"animeTitle":"《海边电台》"}]}
                """;
        AnimeDanmakuCommunity.Titles titles = AnimeDanmakuCommunity.parseTitles(new JSONObject(titleResponse));
        AnimeDanmakuCommunity.Work communityMatch = AnimeDanmakuCommunity.exactWork(titles, "海边电台");
        require(titles.works().size() == 2 && communityMatch.id() == 7
                && AnimeDanmakuCommunity.exactWork(titles, "海边") == null,
                "public lookup picks the unique exact season and deduplicates repeated IDs");
        require(AnimeDanmakuCommunity.exactWork(AnimeDanmakuCommunity.parseTitles(new JSONObject(
                titleResponse.replace("\"animeId\":2,\"animeTitle\":\"海边电台 第二季\"",
                        "\"animeId\":2,\"animeTitle\":\"海边电台\""))), "海边电台") == null
                && AnimeDanmakuCommunity.exactWork(AnimeDanmakuCommunity.parseTitles(new JSONObject(
                titleResponse.replace("\"success\":true", "\"hasMore\":true"))), "海边电台") == null,
                "the public lookup declines duplicate title identities and truncated matches");
        String communityDetail = """
                {"success":true,"bangumi":{"animeId":7,"animeTitle":"海边电台","episodes":[
                {"episodeId":171,"episodeTitle":"第1话 初遇","episodeNumber":"1"},
                {"episodeId":172,"episodeTitle":"第2话 重逢","episodeNumber":"2"},
                {"episodeId":1791,"episodeTitle":"S1 特别篇 第1话","episodeNumber":"S1"}]}}
                """;
        JSONObject communityShow = AnimeDanmaku.response(communityDetail);
        require(AnimeDanmaku.parseMappedEpisode(communityShow, communityMatch.title(), 1).episodeId == 171
                && AnimeDanmakuCommunity.episodeChoices(communityShow, communityMatch, 1).size() == 1
                && AnimeDanmakuCommunity.episodeChoices(communityShow, communityMatch, 0).size() == 3,
                "public auto and specific episode choices exclude special episodes with distinct IDs");

        String data = "{\"count\":6,\"comments\":[{\"p\":\"9.23,4,255,[Gamer]private\",\"m\":\"晚一点\"},"
                + "{\"p\":\"1.1,1,16711680,[BiliBili]private\",\"m\":\"早一点\"},"
                + "{\"p\":\"3,5,65535,id\",\"m\":\"站内\"},{\"p\":\"3.5,5,65535,[unknown]id\",\"m\":\"其他来源\"},"
                + "{\"p\":\"NaN,1,16777215,id\",\"m\":\"坏点位\"},{\"p\":\"2,7,16777215,id\",\"m\":\"高级弹幕\"}]}";
        List<AnimeDanmaku.Comment> comments = AnimeDanmaku.parseComments(data);
        require(comments.size() == 4 && comments.get(0).timeMs == 1_100 && comments.get(0).argb == 0xffff0000
                && comments.get(0).source.equals(AnimeDanmaku.SOURCE_BILI)
                && comments.get(1).mode == AnimeDanmaku.TOP && comments.get(1).source.equals(AnimeDanmaku.SOURCE_DANDAN)
                && comments.get(2).source.equals(AnimeDanmaku.SOURCE_OTHER)
                && comments.get(3).timeMs == 9_230 && comments.get(3).mode == AnimeDanmaku.BOTTOM
                && comments.get(3).source.equals(AnimeDanmaku.SOURCE_GAMER),
                "source prefixes, plain-text rows, recognized modes, color and chronological sort");
        try {
            AnimeDanmaku.parseSearch("{\"success\":false,\"errorCode\":18,\"errorMessage\":\"secret quota details\"}", "海边电台", 2);
            throw new AssertionError("non-success JSON must surface");
        } catch (IOException expected) {
            require(expected.getMessage().contains("配额") && !expected.getMessage().contains("secret"), "quota body sanitized");
        }

        List<AnimeDanmaku.Comment> local = AnimeDanmaku.importXml(new ByteArrayInputStream(
                ("<?xml version='1.0' encoding='UTF-16'?><i><chatid>123</chatid>"
                        + "<d p='2.3,1,25,255,0,0,xyz,1'>后</d><d p='0.02,5,25,16777215'>前 &amp; 好</d>"
                        + "<d p='2.9,8,25,255'>指令</d></i>").getBytes(StandardCharsets.UTF_16)));
        require(local.size() == 2 && local.get(0).timeMs == 20 && local.get(0).text.equals("前 & 好")
                && local.get(0).source.equals(AnimeDanmaku.SOURCE_BILI) && local.get(1).argb == 0xff0000ff,
                "local XML handles BOM, distinct color column, entities and source tag");
        reject(() -> AnimeDanmaku.importXml(utf8("<!DOCTYPE i [<!ENTITY x SYSTEM 'file:///sensitive-fixture'>]>"
                + "<i><d p='0,1,25,0'>&x;</d></i>")));
        reject(() -> AnimeDanmaku.importXml(utf8("<unrelated><d p='0,1,25,0'>bad</d></unrelated>")));
        System.out.println("PASS signed and community danmaku matching, source filters, quota handling and XXE-safe XML");
    }

    private static ByteArrayInputStream utf8(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private interface Checked { void call() throws IOException; }

    private static void reject(Checked bad) throws IOException {
        try { bad.call(); } catch (IOException expected) { return; }
        throw new AssertionError("invalid danmaku input must be rejected");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }
}
