package com.shengjian.player;

import android.content.Context;
import android.media.MediaPlayer;
import android.widget.VideoView;

final class AnimeVideoView extends VideoView {
    private int fit;
    private MediaPlayer controller;
    private int videoWidth;
    private int videoHeight;

    AnimeVideoView(Context context) {
        super(context);
    }

    void fit(int value) {
        if (fit == value) return;
        fit = value;
        requestLayout();
    }

    void dimensions(MediaPlayer prepared) {
        controller = prepared;
        refreshVideoSize();
        requestLayout();
    }

    int sourceWidth() { return videoWidth; }

    int sourceHeight() { return videoHeight; }

    @Override public void suspend() {
        controller = null;
        super.suspend();
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (width == 0 || height == 0
                || MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                || MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        refreshVideoSize();
        if (videoWidth <= 0 || videoHeight <= 0) {
            super.onMeasure(widthSpec, heightSpec);
            return;
        }
        if (fit != AnimeVideoSizing.STRETCH) {
            double scale = scaleForViewport(width, height, videoWidth, videoHeight, fit == AnimeVideoSizing.COVER);
            width = Math.max(1, (int) Math.round(videoWidth * scale));
            height = Math.max(1, (int) Math.round(videoHeight * scale));
        }
        setMeasuredDimension(width, height);
    }

    private void refreshVideoSize() {
        if (controller == null) return;
        try {
            int width = controller.getVideoWidth();
            int height = controller.getVideoHeight();
            if (width > 0 && height > 0) {
                videoWidth = width;
                videoHeight = height;
            }
        } catch (IllegalStateException released) {
            // VideoView will deliver a new controller after its surface is recreated.
            controller = null;
        }
    }

    static double scaleForViewport(int width, int height, int videoWidth, int videoHeight, boolean crop) {
        return AnimeVideoSizing.scaleForViewport(width, height, videoWidth, videoHeight, crop);
    }
}
