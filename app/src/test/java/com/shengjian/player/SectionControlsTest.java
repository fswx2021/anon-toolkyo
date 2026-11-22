package com.shengjian.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Application;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = Application.class, qualifiers = "w320dp-port-mdpi")
public class SectionControlsTest {
    @Test public void headingsAndSearchActionsFitNarrowPhoneInBothThemes() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup()) {
            Activity host = controller.get();
            for (boolean dark : new boolean[]{false, true}) {
                PlayerViews.appearance(dark);
                var image = SectionControls.action(host, "以图搜番", false, () -> {});
                var sources = SectionControls.action(host, "动画源", true, () -> {});
                var heading = SectionControls.heading(host, "动画", image, sources);
                measureRow(heading);
                var title = (TextView) heading.getChildAt(0);
                assertTrue(title.isAccessibilityHeading());
                assertTrue(title.getMeasuredWidth() >= title.getPaint().measureText("动画"));
                assertEquals(PlayerViews.FG, title.getCurrentTextColor());
                assertEquals(PlayerViews.textTone(PlayerViews.PINK), sources.getCurrentTextColor());
                assertTrue(image.getRight() <= sources.getLeft() && sources.getRight() <= 288);
                var field = new EditText(host);
                field.setHint("搜索书名或作者");
                AtomicInteger submits = new AtomicInteger();
                var search = SectionControls.search(field, submits::incrementAndGet);
                measureRow(search);
                var button = (Button) search.getChildAt(2);
                assertTrue(button.getWidth() >= 48 && button.getHeight() >= 48);
                assertTrue(field.getWidth() > 128 && field.getRight() <= button.getLeft());
                button.performClick();
                field.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
                field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                field.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                assertEquals(3, submits.get());
            }
        } finally {
            PlayerViews.appearance(false);
        }
    }

    private static void measureRow(LinearLayout row) {
        int width = View.MeasureSpec.makeMeasureSpec(288, View.MeasureSpec.EXACTLY);
        int height = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        row.measure(width, height);
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }
}
