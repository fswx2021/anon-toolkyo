package com.shengjian.player;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.PathInterpolator;

final class PageMotion {
    private static final PathInterpolator EASE_OUT = new PathInterpolator(.2f, 0f, 0f, 1f);
    private static final int PAGE_DURATION = 320;
    private static final int TAB_DURATION = 280;

    private PageMotion() {}

    static boolean enabled() {
        return ValueAnimator.areAnimatorsEnabled();
    }

    static void enter(View target, boolean back) {
        start(target, offset(target, back, 44), .42f, PAGE_DURATION);
    }

    static void enterTab(View target, boolean back) {
        start(target, offset(target, back, 32), .48f, TAB_DURATION);
    }

    static void prepareExit(ViewGroup host, View outgoing, boolean back) {
        host.setLayoutTransition(null);
        if (outgoing == null || !enabled() || !outgoing.isShown() || !outgoing.isLaidOut()) return;
        outgoing.animate().cancel();
        LayoutTransition transition = new LayoutTransition();
        transition.setAnimateParentHierarchy(false);
        transition.disableTransitionType(LayoutTransition.APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        transition.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        int type = LayoutTransition.DISAPPEARING;
        transition.setStartDelay(type, 0);
        transition.setDuration(type, TAB_DURATION);
        transition.setInterpolator(type, EASE_OUT);
        transition.setAnimator(type, ObjectAnimator.ofPropertyValuesHolder(outgoing,
                PropertyValuesHolder.ofFloat(View.TRANSLATION_X, -offset(outgoing, back, 24)),
                PropertyValuesHolder.ofFloat(View.ALPHA, 0f)));
        transition.addTransitionListener(new LayoutTransition.TransitionListener() {
            @Override public void startTransition(LayoutTransition motion, ViewGroup parent, View child, int role) {}

            @Override public void endTransition(LayoutTransition motion, ViewGroup parent, View child, int role) {
                reset(child);
            }
        });
        host.setLayoutTransition(transition);
    }

    static void fade(View target) {
        start(target, 0, .56f, 240);
    }

    static void reset(View target) {
        target.animate().cancel();
        target.setAlpha(1f);
        target.setTranslationX(0f);
    }

    static void dialog(Window window) {
        if (window != null) window.setWindowAnimations(enabled() ? R.style.PageMotionDialog : 0);
    }

    private static int offset(View target, boolean back, int dp) {
        int distance = PlayerViews.dp(target.getContext(), dp);
        boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        return back == rtl ? distance : -distance;
    }

    private static void start(View target, int distance, float alpha, int duration) {
        reset(target);
        if (!enabled() || !target.isShown()) return;
        target.setTranslationX(distance);
        target.setAlpha(alpha);
        target.animate()
                .translationX(0f)
                .alpha(1f)
                .setInterpolator(EASE_OUT)
                .setDuration(duration)
                .start();
    }
}
