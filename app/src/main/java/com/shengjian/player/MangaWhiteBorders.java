package com.shengjian.player;

/** Detects continuous white or transparent strips in a small sample of a manga page. */
final class MangaWhiteBorders {
    private MangaWhiteBorders() {}

    static int[] find(int[] colors, int width, int height) {
        if (width < 24 || height < 24) return null;
        int top = margin(colors, 0, width, height, 1, width);
        int bottom = margin(colors, (height - 1) * width, width, height, 1, -width);
        int left = margin(colors, 0, height, width, width, 1);
        int right = margin(colors, width - 1, height, width, width, -1);
        if (top == 0 && right == 0 && bottom == 0 && left == 0) return null;
        if (blank(colors, (height / 2) * width, width, 1)
                && blank(colors, width / 2, height, width)) {
            boolean anyInk = false;
            for (int color : colors) {
                if (!paper(color)) { anyInk = true; break; }
            }
            if (!anyInk) return null;
        }
        return new int[]{left, top, width - right, height - bottom};
    }

    private static int margin(int[] pixels, int start, int across, int depth, int step, int inward) {
        if (!blank(pixels, start, across, step)) return 0;
        int line = 1;
        int maximum = depth / 8;
        while (line < maximum && blank(pixels, start + line * inward, across, step)) line++;
        return Math.max(0, line - 1);
    }

    private static boolean blank(int[] pixels, int start, int length, int step) {
        int nonBlank = 0;
        int tolerance = length / 100;
        for (int i = 0, at = start; i < length; i++, at += step) {
            if (!paper(pixels[at]) && ++nonBlank > tolerance) return false;
        }
        return true;
    }

    private static boolean paper(int color) {
        return (color >>> 24) < 20 || ((color >> 16) & 255) >= 244
                && ((color >> 8) & 255) >= 244 && (color & 255) >= 244;
    }
}
