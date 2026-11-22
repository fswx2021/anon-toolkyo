package com.shengjian.player;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;

final class MyGoEmotes {
    private MyGoEmotes() { }

    static ImageView icon(Context context, @DrawableRes int drawableRes, int sizeDp) {
        ImageView icon = new ImageView(context);
        int size = PlayerViews.dp(context, sizeDp);
        icon.setLayoutParams(new ViewGroup.LayoutParams(size, size));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImageResource(drawableRes);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }
}
