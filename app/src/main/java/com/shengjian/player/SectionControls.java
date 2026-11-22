package com.shengjian.player;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static com.shengjian.player.PlayerViews.*;

/** Shared first-level controls for the four library destinations. */
final class SectionControls {
    private SectionControls() {}

    static LinearLayout heading(Context context, String title, View... actions) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(context, 52));
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextColor(FG);
        label.setTextSize(24);
        label.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        row.addView(label, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        for (View action : actions) row.addView(action);
        return row;
    }

    static Button action(Context context, String title, boolean accented, Runnable click) {
        Button button = new Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setLetterSpacing(0);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(accented ? textTone(PINK) : MUTED);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(context, 48));
        button.setMinimumWidth(dp(context, 48));
        button.setMinHeight(dp(context, 48));
        button.setMinimumHeight(dp(context, 48));
        button.setPadding(dp(context, 9), 0, dp(context, 9), 0);
        button.setBackgroundTintList(null);
        button.setBackground(PlayerViews.button(context, Color.TRANSPARENT, 8, 0));
        button.setLayoutParams(new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        button.setOnClickListener(v -> click.run());
        return button;
    }

    static LinearLayout search(EditText input, Runnable submit) {
        Context context = input.getContext();
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(context, 12), 0, dp(context, 2), 0);
        bar.setBackground(surface(context, SURFACE, 10, 0));
        bar.addView(new Icon(context, "search", MUTED, 18), dp(context, 22), dp(context, 24));
        input.setMinHeight(dp(context, 48));
        input.setPadding(dp(context, 5), 0, dp(context, 4), 0);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(FG);
        input.setHintTextColor(MUTED);
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setOnEditorActionListener((field, id, event) -> {
            boolean enter = event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (id != EditorInfo.IME_ACTION_SEARCH && !enter) return false;
            if (event == null || (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0)) submit.run();
            return true;
        });
        bar.addView(input, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1));
        bar.addView(action(context, "搜索", true, submit));
        return bar;
    }
}
