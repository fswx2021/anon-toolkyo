package com.shengjian.player;

import android.app.AlertDialog;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/** Anchors player options below the picture or at the right edge of the fullscreen player. */
final class AnimePlayerPanel extends AlertDialog.Builder {
    private final AnimePlaybackActivity host;

    AnimePlayerPanel(AnimePlaybackActivity host) {
        super(host, host.playerPanelFullscreen() || PlayerViews.dark()
                ? R.style.AnimePlayerPanelDark : R.style.AnimePlayerPanelLight);
        this.host = host;
    }

    @Override public AlertDialog show() {
        AlertDialog panel = create();
        Window window = panel.getWindow();
        if (window == null) {
            panel.show();
            return panel;
        }
        WindowManager.LayoutParams params = window.getAttributes();
        params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        params.dimAmount = .38f;
        window.setAttributes(params);
        panel.setOnDismissListener(ignored -> host.playerPanelClosed());
        position(window, true);
        panel.show();
        host.playerPanelOpened();
        View decor = window.getDecorView();
        decor.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (panel.isShowing() && (r - l != or - ol || b - t != ob - ot)) position(window, false);
        });
        if (host.playerPanelFullscreen()) applyFullscreenInsets(decor);
        return panel;
    }

    private void position(Window window, boolean initial) {
        boolean fullscreen = host.playerPanelFullscreen();
        Configuration configuration = host.getResources().getConfiguration();
        boolean side = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (initial) {
            if (side && PageMotion.enabled()) window.setWindowAnimations(R.style.AnimePlaybackSidePanelMotion);
            else PageMotion.dialog(window);
        }
        int width = side ? PlayerViews.dp(host, Math.min(360, configuration.screenWidthDp / 2f))
                : ViewGroup.LayoutParams.MATCH_PARENT;
        int height = side ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT;
        int gravity = side ? Gravity.END | Gravity.TOP : Gravity.BOTTOM;
        WindowManager.LayoutParams params = window.getAttributes();
        if (initial || params.width != width || params.height != height || params.gravity != gravity) {
            params.gravity = gravity;
            params.width = width;
            params.height = height;
            window.setAttributes(params);
            int radius = PlayerViews.dp(host, 18);
            GradientDrawable background = new GradientDrawable();
            background.setColor(fullscreen || PlayerViews.dark() ? 0xff232427 : 0xffffffff);
            background.setCornerRadii(side
                    ? new float[]{radius, radius, 0, 0, 0, 0, radius, radius}
                    : new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
            window.setBackgroundDrawable(background);
        }
    }

    @SuppressWarnings("deprecation")
    private static void applyFullscreenInsets(View decor) {
        // The themed dialog has installed its decor by this point. The Window controller
        // dereferences that decor on Android 15, so it cannot be queried before show().
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController bars = decor.getWindowInsetsController();
            if (bars != null) {
                bars.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                bars.hide(WindowInsets.Type.systemBars());
            }
            decor.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets safe = insets.getInsets(WindowInsets.Type.displayCutout() | WindowInsets.Type.mandatorySystemGestures());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
                return insets;
            });
            decor.requestApplyInsets();
        } else {
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
