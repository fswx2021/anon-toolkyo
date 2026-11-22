package com.shengjian.player;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ListView;
import android.widget.TextView;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=34)
public class MusicLyricsPaneInteractionTest {
    @Test public void browsingLyricsDoesNotSeekAndTappingLineSeeksThenFollows() {
        var host = Robolectric.buildActivity(Activity.class).setup();
        try {
            var seek = new AtomicLong(-1);
            var attach = new AtomicInteger();
            MusicLyricsPane pane = new MusicLyricsPane(host.get(), seek::set, attach::incrementAndGet);
            host.get().setContentView(pane);
            StringBuilder body = new StringBuilder();
            for(int sec = 0; sec < 40; sec++) body.append(String.format(java.util.Locale.ROOT, "[00:%02d]Line %d%n", sec, sec));
            var timed = Lyrics.parse(body.toString());
            pane.showLines(timed, 1000);
            pane.measure(View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(350, View.MeasureSpec.EXACTLY));
            pane.layout(0, 0, 350, 350);
            shadowOf(Looper.getMainLooper()).idle();
            ListView list = null;
            TextView follow = null, importButton = null;
            for (int i = 0; i < pane.getChildCount(); i++) {
                View child = pane.getChildAt(i);
                if (child instanceof ListView v) list = v;
                if (child instanceof TextView v && v.getText().toString().equals("回到当前歌词")) follow = v;
                if (child instanceof TextView v && v.getText().toString().equals("添加歌词文件")) importButton = v;
            }
            assertNotNull(list);
            assertNotNull(follow);
            assertNotNull(importButton);
            var rows = list.getAdapter();
            assertEquals(40, rows.getCount());
            assertTrue(rows.getView(1, null, list).isActivated());
            list.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
            assertEquals(View.VISIBLE, follow.getVisibility());
            pane.setPosition(12000);
            assertTrue(rows.getView(12, null, list).isActivated());
            assertEquals(-1, seek.get());
            assertEquals(View.VISIBLE, follow.getVisibility());
            assertTrue(follow.performClick());
            assertEquals(View.GONE, follow.getVisibility());
            list.performItemClick(rows.getView(3, null, list), 3, 3);
            assertEquals(3000, seek.get());
            assertTrue(rows.getView(3, null, list).isActivated());
            list.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null);
            pane.showLines(Lyrics.parse("纯文本\n第二行"), 1000);
            assertEquals(2, rows.getCount());
            assertEquals(View.GONE, follow.getVisibility());
            assertFalse(rows.isEnabled(0));
            pane.empty("这首曲目还没有歌词", true);
            assertEquals(View.GONE, list.getVisibility());
            assertEquals(View.VISIBLE, importButton.getVisibility());
            assertTrue(importButton.performClick());
            assertEquals(1, attach.get());
        } finally {
            host.pause().stop().destroy();
        }
    }
}
