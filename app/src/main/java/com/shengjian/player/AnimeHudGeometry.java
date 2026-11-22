package com.shengjian.player;

/** Safe control positions over a fullscreen surface, independent of video letterboxing. */
final class AnimeHudGeometry {
    record Edges(int headerLeft, int headerRight, int transportLeft, int transportRight,
                 int top, int bottom, int railRight, boolean compact, boolean sideLabels) {}

    private AnimeHudGeometry() {}

    static Edges edges(boolean fullscreen, int width, int height, int left, int top, int right, int bottom,
                       int headerMargin, int transportMargin, int minimumControlsWidth,
                       int tabletShortSide, int wideThreshold) {
        if (!fullscreen) {
            return new Edges(headerMargin, headerMargin, transportMargin, transportMargin, 0, 0, 0, false, false);
        }
        int availableWidth = Math.max(0, width - left - right);
        int availableHeight = Math.max(0, height - top - bottom);
        boolean compact = availableWidth < wideThreshold;
        int shortest = Math.min(availableWidth, availableHeight);
        int longest = Math.max(availableWidth, availableHeight);
        boolean sideLabels = !compact && shortest >= tabletShortSide && shortest * 16L >= longest * 9L;
        int remainingWidth = Math.max(0, availableWidth - minimumControlsWidth);
        int innerHeader = Math.min(headerMargin, remainingWidth / 2);
        int innerTransport = compact ? innerHeader : Math.min(transportMargin, remainingWidth / 2);
        return new Edges(left + innerHeader, right + innerHeader, left + innerTransport, right + innerTransport,
                top, bottom, right + innerHeader, compact, sideLabels);
    }
}
