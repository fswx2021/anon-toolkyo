package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class AnimeCalendar {
    private static final String[] DAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "anime-calendar");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile Snapshot cached;

    private record Snapshot(List<List<AnimeSchedule.Entry>> days, long loaded) {}

    private AnimeCalendar() {}

    static void show(Activity host, Consumer<AnimeSchedule.Entry> onSearch) {
        new Panel(host, onSearch).show();
    }

    private static final class Panel {
        final Activity host;
        final Consumer<AnimeSchedule.Entry> onSearch;
        final LinearLayout body;
        final TextView state;
        final ListView works;
        AlertDialog dialog;
        Future<?> request;
        List<List<AnimeSchedule.Entry>> days;
        List<AnimeSchedule.Entry> visible = Collections.emptyList();
        int day = LocalDate.now().getDayOfWeek().getValue() - 1;

        Panel(Activity host, Consumer<AnimeSchedule.Entry> onSearch) {
            this.host = host;
            this.onSearch = onSearch;
            body = new LinearLayout(host);
            body.setOrientation(LinearLayout.VERTICAL);
            int padding = PlayerViews.dp(host, 16);
            body.setPadding(padding, 0, padding, padding);
            LinearLayout weekdays = new LinearLayout(host);
            for (int i = 0; i < DAYS.length; i++) {
                int position = i;
                TextView item = new TextView(host);
                item.setText(DAYS[i]);
                item.setGravity(android.view.Gravity.CENTER);
                item.setBackground(PlayerViews.button(host, PlayerViews.SURFACE, 9, 0));
                item.setTextColor(PlayerViews.FG);
                item.setOnClickListener(view -> selectDay(position));
                weekdays.addView(item, new LinearLayout.LayoutParams(0, PlayerViews.dp(host, 44), 1));
            }
            body.addView(weekdays);
            state = new TextView(host);
            state.setTextColor(PlayerViews.MUTED);
            state.setTextSize(13);
            state.setPadding(0, padding / 2, 0, padding / 2);
            body.addView(state);
            works = new ListView(host);
            works.setOnItemClickListener((parent, row, position, id) -> {
                if (position >= visible.size()) return;
                dialog.dismiss();
                onSearch.accept(visible.get(position));
            });
            int height = Math.min(PlayerViews.dp(host, 420), host.getResources().getDisplayMetrics().heightPixels / 2);
            body.addView(works, new LinearLayout.LayoutParams(-1, height));
        }

        void show() {
            dialog = new AlertDialog.Builder(host).setTitle("放送日历 · Bangumi").setView(body)
                    .setPositiveButton("关闭", null).setNeutralButton("刷新", null).create();
            dialog.setOnDismissListener(ignored -> {
                if (request != null) request.cancel(true);
                PageMotion.reset(works);
            });
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> load());
            Snapshot snapshot = cached;
            if (snapshot != null && android.os.SystemClock.elapsedRealtime() - snapshot.loaded < 30 * 60_000L) {
                days = snapshot.days;
                render();
            } else load();
        }

        void load() {
            if (request != null) request.cancel(true);
            state.setText("正在读取放送日历…");
            request = NETWORK.submit(() -> {
                try {
                    ReaderSources.Response response = ReaderSources.fetch("https://api.bgm.tv/calendar",
                            Map.of("User-Agent", "ANON-TOOL/0.22.0 (Android; Bangumi public calendar)",
                                    "Accept", "application/json"));
                    List<List<AnimeSchedule.Entry>> result = AnimeSchedule.parse(response.body);
                    if (Thread.currentThread().isInterrupted()) return;
                    cached = new Snapshot(result, android.os.SystemClock.elapsedRealtime());
                    host.runOnUiThread(() -> {
                        if (!dialog.isShowing() || host.isDestroyed()) return;
                        days = result;
                        render();
                    });
                } catch (IOException | RuntimeException error) {
                    if (Thread.currentThread().isInterrupted()) return;
                    host.runOnUiThread(() -> {
                        if (dialog.isShowing() && !host.isDestroyed()) state.setText("放送日历暂时不可用，可以刷新重试。");
                    });
                }
            });
        }

        void selectDay(int position) {
            if (day == position) return;
            day = position;
            render();
            if (days != null) PageMotion.fade(works);
        }

        void render() {
            if (days == null) return;
            visible = days.get(day);
            state.setText(visible.isEmpty() ? DAYS[day] + "暂无条目" : DAYS[day] + " · 点击作品搜索");
            List<String> labels = new ArrayList<>(visible.size());
            for (AnimeSchedule.Entry item : visible) {
                labels.add(item.originalTitle.isEmpty() ? item.title : item.title + "\n" + item.originalTitle);
            }
            works.setAdapter(new ArrayAdapter<>(host, android.R.layout.simple_list_item_1, labels));
        }
    }
}
