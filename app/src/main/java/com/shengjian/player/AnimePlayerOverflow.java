package com.shengjian.player;

import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** A playback menu layered inside the video window so immersive mode stays active. */
final class AnimePlayerOverflow implements View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private final AnimePlaybackActivity host;
    private final FrameLayout stage;
    private final View anchor;
    private final View priorFocus;
    private final boolean priorSelection;
    private final FrameLayout layer;
    private final ScrollView menu;
    private boolean showing;

    static AnimePlayerOverflow show(AnimePlaybackActivity host, FrameLayout stage, View anchor,
                                    List<String> labels, List<Runnable> actions) {
        if (host.isFinishing() || host.isDestroyed() || anchor == null || !anchor.isAttachedToWindow()
                || !anchor.isShown() || stage.getWidth() <= 0 || stage.getHeight() <= 0
                || labels.isEmpty() || labels.size() != actions.size()) return null;

        AnimePlayerOverflow popup = new AnimePlayerOverflow(host, stage, anchor, labels, actions);
        return popup.open() ? popup : null;
    }

    private AnimePlayerOverflow(AnimePlaybackActivity host, FrameLayout stage, View anchor,
                                List<String> labels, List<Runnable> actions) {
        this.host = host;
        this.stage = stage;
        this.anchor = anchor;
        priorFocus = stage.findFocus();
        priorSelection = anchor.isSelected();

        layer = new FrameLayout(host);
        layer.setFocusableInTouchMode(true);
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        layer.setOnClickListener(view -> dismiss());

        menu = new ScrollView(host);
        menu.setPadding(0, dp(4), 0, dp(4));
        menu.setBackground(PlayerViews.surface(host, 0xd21c1c21, 8, 0x26ffffff));
        menu.setElevation(dp(4));
        menu.setClipToOutline(true);
        menu.setClickable(true);
        if (Build.VERSION.SDK_INT >= 28) menu.setAccessibilityPaneTitle("播放选项");

        LinearLayout rows = new LinearLayout(host);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            TextView row = new TextView(host);
            row.setText(labels.get(i));
            row.setTextColor(0xfff3f3f6);
            row.setTextSize(14);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(7), dp(14), dp(7));
            row.setMinHeight(dp(44));
            row.setBackground(PlayerViews.button(host, Color.TRANSPARENT, 5, 0));
            row.setFocusable(true);
            Runnable action = actions.get(i);
            row.setOnClickListener(view -> {
                dismiss();
                action.run();
            });
            rows.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        }
        menu.addView(rows, new ScrollView.LayoutParams(MATCH, WRAP));
    }

    private boolean open() {
        Rect safe = safeViewport(stage, dp(8));
        int[] origin = new int[2];
        int[] anchorPosition = new int[2];
        stage.getLocationInWindow(origin);
        anchor.getLocationInWindow(anchorPosition);
        int anchorRight = anchorPosition[0] - origin[0] + anchor.getWidth();
        int anchorTop = anchorPosition[1] - origin[1];
        int belowAnchor = Math.max(safe.top, anchorTop + anchor.getHeight() + dp(2));
        int aboveAnchor = Math.min(safe.bottom, anchorTop - dp(2));
        int belowRoom = safe.bottom - belowAnchor;
        int aboveRoom = aboveAnchor - safe.top;
        boolean opensAbove = belowRoom < dp(132) && aboveRoom > belowRoom;
        int maxHeight = opensAbove ? aboveRoom : belowRoom;
        int width = Math.min(dp(204), safe.width());
        if (width < dp(120) || maxHeight < dp(44)) return false;

        int left = Math.max(safe.left, Math.min(anchorRight - width, safe.right - width));
        menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        int top = opensAbove ? aboveAnchor - menu.getMeasuredHeight() : belowAnchor;
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(width, menu.getMeasuredHeight(),
                Gravity.START | Gravity.TOP);
        position.setMarginStart(stage.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                ? stage.getWidth() - left - width : left);
        position.topMargin = top;
        layer.addView(menu, position);
        if (PageMotion.enabled()) {
            menu.setAlpha(0f);
            menu.setTranslationY(opensAbove ? dp(5) : -dp(5));
        }
        stage.addView(layer, new FrameLayout.LayoutParams(MATCH, MATCH));
        showing = true;
        anchor.addOnAttachStateChangeListener(this);
        stage.addOnLayoutChangeListener(this);
        anchor.setSelected(true);
        host.playerPanelOpened();
        layer.requestFocus();
        if (menu.getAlpha() == 0f) {
            menu.animate().alpha(1f).translationY(0).setDuration(160)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        return true;
    }

    boolean isShowing() { return showing; }

    void dismiss() {
        if (!showing) return;
        showing = false;
        menu.animate().cancel();
        anchor.removeOnAttachStateChangeListener(this);
        stage.removeOnLayoutChangeListener(this);
        stage.removeView(layer);
        anchor.setSelected(priorSelection);
        if (priorFocus != null && priorFocus.isShown()) priorFocus.requestFocus();
        host.playerPanelClosed();
    }

    @Override public void onViewAttachedToWindow(View view) {}

    @Override public void onViewDetachedFromWindow(View view) {
        new Handler(Looper.getMainLooper()).post(this::dismiss);
    }

    @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                                         int oldLeft, int oldTop, int oldRight, int oldBottom) {
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) dismiss();
    }

    private int dp(int amount) { return PlayerViews.dp(host, amount); }

    private static Rect safeViewport(View stage, int margin) {
        View root = stage.getRootView();
        int[] rootPosition = new int[2];
        int[] stagePosition = new int[2];
        root.getLocationInWindow(rootPosition);
        stage.getLocationInWindow(stagePosition);
        int rootX = rootPosition[0] - stagePosition[0];
        int rootY = rootPosition[1] - stagePosition[1];
        Rect safe = safeInsets(stage.getRootWindowInsets());
        return new Rect(
                Math.max(0, rootX + safe.left) + margin,
                Math.max(0, rootY + safe.top) + margin,
                Math.min(stage.getWidth(), rootX + root.getWidth() - safe.right) - margin,
                Math.min(stage.getHeight(), rootY + root.getHeight() - safe.bottom) - margin);
    }

    @SuppressWarnings("deprecation")
    private static Rect safeInsets(WindowInsets insets) {
        if (insets == null) return new Rect();
        if (Build.VERSION.SDK_INT >= 30) {
            Insets safe = insets.getInsets(WindowInsets.Type.displayCutout()
                    | WindowInsets.Type.tappableElement() | WindowInsets.Type.systemBars());
            return new Rect(safe.left, safe.top, safe.right, safe.bottom);
        }
        int left = insets.getSystemWindowInsetLeft();
        int top = insets.getSystemWindowInsetTop();
        int right = insets.getSystemWindowInsetRight();
        int bottom = insets.getSystemWindowInsetBottom();
        if (Build.VERSION.SDK_INT >= 29) {
            Insets tappable = insets.getTappableElementInsets();
            left = Math.max(left, tappable.left);
            top = Math.max(top, tappable.top);
            right = Math.max(right, tappable.right);
            bottom = Math.max(bottom, tappable.bottom);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
        }
        return new Rect(left, top, right, bottom);
    }
}
