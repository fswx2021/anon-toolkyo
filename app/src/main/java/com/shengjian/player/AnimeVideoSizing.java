package com.shengjian.player;

final class AnimeVideoSizing {
    static final int FIT = 0;
    static final int COVER = 1;
    static final int STRETCH = 2;
    static final int AUTO = 3;
    static final int RATIO_4_3 = 4;

    private AnimeVideoSizing() {}

    static int normalize(int preference) {
        return preference >= FIT && preference <= RATIO_4_3 ? preference : AUTO;
    }

    static int surfaceFit(int preference) {
        return switch (normalize(preference)) {
            case COVER -> COVER;
            case STRETCH, RATIO_4_3 -> STRETCH;
            default -> FIT;
        };
    }

    static double scaleForViewport(int width, int height, int sourceWidth, int sourceHeight, boolean crop) {
        double horizontal = (double) width / sourceWidth;
        double vertical = (double) height / sourceHeight;
        return crop ? Math.max(horizontal, vertical) : Math.min(horizontal, vertical);
    }

    static Frame fitFrame(int width, int height, int sourceWidth, int sourceHeight) {
        if (width <= 0 || height <= 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return new Frame(0, 0, Math.max(0, width), Math.max(0, height));
        }
        double scale = scaleForViewport(width, height, sourceWidth, sourceHeight, false);
        int fittedWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int fittedHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        return new Frame((width - fittedWidth) / 2, (height - fittedHeight) / 2, fittedWidth, fittedHeight);
    }

    record Frame(int left, int top, int width, int height) {
        int right() { return left + width; }
        int bottom() { return top + height; }
    }
}
