package com.shengjian.player;

final class MangaSpread {
    private MangaSpread() {}

    static boolean use(boolean enabled, boolean horizontal, int width, int height, float minimumWidth) {
        return enabled && horizontal && width >= minimumWidth && width > height && height > 0;
    }

    static int first(int page, boolean spread) { return spread ? page - page % 2 : page; }

    static int last(int page, int count, boolean spread) {
        return Math.min(count - 1, first(page, spread) + (spread ? 1 : 0));
    }

    static int adjacent(int page, int delta, boolean spread) {
        return first(page, spread) + (spread ? 2 : 1) * delta;
    }

    static int pageAt(int start, int end, boolean rtl, float x, float width) {
        return start == end || (x < width / 2) != rtl ? start : end;
    }
}
