package com.shengjian.player;

import org.junit.Test;

import java.net.URI;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

public class AnimeCoverCacheTest {
    private static final long EDGERUNNERS_2 = 513878L;
    private static final String OFFICIAL_COVER =
            "https://api.bgm.tv/v0/subjects/513878/image?type=medium";

    @Test public void sourceCoverRemainsFirstWithFallbackToTheSameSubject() throws Exception {
        String source = "https://example.com/covers/edgerunners.jpg";
        AnimeCoverCache.CoverRequest request = AnimeCoverCache.coverRequest(source, EDGERUNNERS_2);
        List<String> routes = AnimeCoverCache.imageRoutes(request);
        assertEquals(List.of(source, OFFICIAL_COVER), routes.subList(0, 2));
        assertEquals("wsrv.nl", URI.create(routes.get(2)).getHost());
        assertNotEquals(request, AnimeCoverCache.coverRequest(source, 262897L));
    }

    @Test public void missingOrMalformedSourceCoverUsesTheKnownSubject() throws Exception {
        AnimeCoverCache.CoverRequest missing = AnimeCoverCache.coverRequest("", EDGERUNNERS_2);
        assertEquals(OFFICIAL_COVER, AnimeCoverCache.imageRoutes(missing).get(0));
        assertEquals(missing, AnimeCoverCache.coverRequest(" : not an image", EDGERUNNERS_2));
        assertNull(AnimeCoverCache.coverRequest("", 0));
    }

    @Test public void datedBangumiCdnPathCanResolveAFreshCover() throws Exception {
        String dated = "https://lain.bgm.tv/r/800/pic/cover/l/db/94/513878_5f77g.jpg";
        AnimeCoverCache.CoverRequest request = AnimeCoverCache.coverRequest(dated, 0);
        assertEquals(EDGERUNNERS_2, request.subjectId());
        assertEquals(List.of(dated, OFFICIAL_COVER), AnimeCoverCache.imageRoutes(request).subList(0, 2));
    }

    @Test public void externalUnmatchedSourceIsNotPassedToABangumiMirror() throws Exception {
        String source = "https://example.com/pic/cover/l/db/94/513878_5f77g.jpg";
        assertEquals(List.of(source), AnimeCoverCache.imageRoutes(AnimeCoverCache.coverRequest(source, 0)));
    }
}
