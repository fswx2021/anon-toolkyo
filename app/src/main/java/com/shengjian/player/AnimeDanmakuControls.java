package com.shengjian.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

final class AnimeDanmakuControls implements AutoCloseable {
    static final int OPEN_XML = 132;
    private static final String[] PLATFORMS = {"哔哩哔哩", "巴哈姆特动画疯", "弹弹play", "其他平台"};
    private static final int ALL_PLATFORMS = (1 << 4) - 1;
    private static final int ALL_MODES = (1 << 3) - 1;
    private static final long DUPLICATE_WINDOW_MS = 8_000L;
    private static final int MAX_KEYWORD_TEXT = 2_000;
    private static final int MAX_KEYWORDS = 50;
    private static final String AREA = "anime_danmaku_area";
    private static final String TEXT_SIZE = "anime_danmaku_size";
    private static final String OPACITY = "anime_danmaku_opacity";
    private static final String MODES = "anime_danmaku_modes";
    private static final String MERGE_DUPLICATES = "anime_danmaku_merge_duplicates";
    private static final String BLOCKED_KEYWORDS = "anime_danmaku_keywords";
    private static final AnimeDanmaku.Credentials BUILT_IN = new AnimeDanmaku.Credentials(
            BuildConfig.DANDANPLAY_APP_ID, BuildConfig.DANDANPLAY_APP_SECRET);

    final AnimeDanmakuView view;
    private final Activity host;
    private final SharedPreferences settings;
    private final SharedPreferences displaySettings;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<AnimeDanmaku.Comment> comments = Collections.emptyList();
    private AnimeEngine.Episode episode;
    private Future<?> pending;
    private int version, documentEpisode;
    private int episodeVersion, platforms;
    private int modes;
    private boolean mergeDuplicates;
    private List<String> blockedKeywords;
    private boolean enabled, closed, autoLoading, autoAttempted;
    private String status = "";
    private Runnable visibilityChanged = () -> {};

    AnimeDanmakuControls(Activity host, LongSupplier position, BooleanSupplier playing) {
        this.host = host;
        settings = host.getSharedPreferences("anime-danmaku", Activity.MODE_PRIVATE);
        displaySettings = host.getSharedPreferences("app_settings", Activity.MODE_PRIVATE);
        platforms = settings.getInt("platforms", ALL_PLATFORMS);
        enabled = settings.getBoolean("visible", true);
        modes = displaySettings.getInt(MODES, ALL_MODES);
        mergeDuplicates = displaySettings.getBoolean(MERGE_DUPLICATES, false);
        blockedKeywords = readKeywords(displaySettings.getString(BLOCKED_KEYWORDS, ""));
        view = new AnimeDanmakuView(host);
        view.setPlaybackClock(position, playing);
        view.setEnabled(enabled);
        updateAppearance();
    }

    void mount(FrameLayout parent, int index) {
        if (view.getParent() == parent) return;
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        parent.addView(view, index, new FrameLayout.LayoutParams(-1, -1));
    }

    void bind(AnimeEngine.Episode value) {
        cancel();
        episodeVersion++;
        episode = value;
        comments = Collections.emptyList();
        autoAttempted = false;
        status = "";
        applyFilter();
        loadAutomatically();
    }

    boolean isVisible() { return enabled; }

    void setOnVisibilityChanged(Runnable listener) { visibilityChanged = listener; }

    void toggleVisibility() {
        enabled = !enabled;
        settings.edit().putBoolean("visible", enabled).apply();
        view.setEnabled(enabled);
        visibilityChanged.run();
        if (enabled) {
            loadAutomatically();
        } else {
            if (autoLoading) cancel();
            autoAttempted = false;
        }
    }

    void menu() {
        String[] actions = {"手动匹配弹幕", "筛选弹幕平台", "显示效果", "弹幕类型与去重", "屏蔽关键词",
                "导入本地弹幕 XML", "弹弹play 接入设置"};
        String title = pending != null ? "正在加载弹幕" : !comments.isEmpty()
                ? host.getString(R.string.anime_danmaku_count, comments.size())
                : !status.isEmpty() ? status : "弹幕设置";
        panel().setTitle(title).setItems(actions, (dialog, item) -> {
            switch (item) {
                case 0: searchDialog(); break;
                case 1: filterDialog(); break;
                case 2: appearanceDialog(); break;
                case 3: modeDialog(); break;
                case 4: keywordsDialog(); break;
                case 5: openXml(); break;
                case 6: accountDialog(); break;
            }
        }).show();
    }

    private void loadAutomatically() {
        if (closed || !enabled || episode == null || pending != null || autoAttempted || !comments.isEmpty()) return;
        AnimeDanmaku.Credentials credentials = credentials();
        int number = AnimeDanmaku.episodeNumber(episode.sourceTitle);
        if (number <= 0) {
            status = "集数需要手动匹配";
            return;
        }
        String title = matchingTitle();
        long bangumiId = title.equals(episode.show.title) ? episode.show.bangumiId : 0;
        autoAttempted = true;
        run(null, () -> {
            AnimeDanmaku.Candidate match = credentials.configured()
                    ? AnimeDanmaku.match(credentials, title, number, bangumiId)
                    : AnimeDanmakuCommunity.match(title, number);
            return match == null ? null : fetchComments(credentials, match.episodeId);
        }, result -> {
            if (result == null) status = "未自动匹配，可手动选择";
            else if (result.isEmpty()) status = "本集暂无弹幕";
            else {
                comments = result;
                status = "";
                applyFilter();
            }
        }, error -> status = error.isEmpty() ? "自动加载失败，可手动重试" : error);
        autoLoading = true;
    }

    private AnimeDanmaku.Credentials credentials() {
        AnimeDanmaku.Credentials custom = new AnimeDanmaku.Credentials(settings.getString("id", ""),
                settings.getString("secret", ""));
        return custom.configured() ? custom : BUILT_IN;
    }

    private String matchingTitle() {
        return settings.getString("title_" + AnimePlaybackHistory.showKey(episode), episode.show.title);
    }

    private void accountDialog() {
        LinearLayout inputs = fields();
        EditText id = field(inputs, "App ID", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText secret = field(inputs, "App Secret", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        id.setText(settings.getString("id", ""));
        AlertDialog dialog = panel().setTitle("弹弹play 接入设置")
                .setMessage(BUILT_IN.configured() ? "默认使用应用凭证。也可填写你申请的凭证，清除后恢复默认。"
                        : "默认使用 uosc_danmaku 社区服务。可填写你申请的弹弹play凭证，清除后恢复社区服务。")
                .setView(inputs).setNegativeButton("取消", null)
                .setNeutralButton("清除", (d, w) -> {
                    cancel();
                    settings.edit().remove("id").remove("secret").apply();
                    autoAttempted = false;
                    loadAutomatically();
                }).setPositiveButton("保存", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String appId = id.getText().toString().trim();
            String appSecret = secret.getText().toString().trim();
            if (!new AnimeDanmaku.Credentials(appId, appSecret).configured()) {
                secret.setError("请填写有效的 App ID 和 App Secret");
                return;
            }
            cancel();
            settings.edit().putString("id", appId).putString("secret", appSecret).apply();
            dialog.dismiss();
            autoAttempted = false;
            loadAutomatically();
        });
    }

    private void searchDialog() {
        AnimeDanmaku.Credentials credentials = credentials();
        LinearLayout inputs = fields();
        EditText title = field(inputs, "动画名称（含季数）", InputType.TYPE_CLASS_TEXT);
        EditText number = field(inputs, "集数（留空搜索全部）", InputType.TYPE_CLASS_NUMBER);
        if (episode != null) {
            title.setText(matchingTitle());
            int detected = AnimeDanmaku.episodeNumber(episode.sourceTitle);
            if (detected > 0) number.setText(Integer.toString(detected));
        }
        int currentEpisode = episodeVersion;
        AlertDialog dialog = panel().setTitle("搜索在线弹幕")
                .setView(inputs).setNegativeButton("取消", null).setPositiveButton("搜索", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (currentEpisode != episodeVersion) { dialog.dismiss(); return; }
            String term = title.getText().toString().trim();
            if (term.codePointCount(0, term.length()) < 2 || term.length() > 200) {
                title.setError("请输入 2–200 字的动画名称");
                return;
            }
            String digits = number.getText().toString().trim();
            if (!digits.matches("(?:[1-9][0-9]{0,4})?")) {
                number.setError("请输入有效集数，或留空搜索全部");
                return;
            }
            int ep = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            dialog.dismiss();
            run("正在搜索弹幕…", () -> credentials.configured() ? AnimeDanmaku.search(credentials, term, ep)
                            : AnimeDanmakuCommunity.search(term, ep),
                    candidates -> choose(credentials, candidates), error -> showFailure("搜索弹幕失败", error));
        });
    }

    private void choose(AnimeDanmaku.Credentials credentials, List<AnimeDanmaku.Candidate> candidates) {
        if (candidates.isEmpty()) { message("没有匹配的弹幕剧集，可调整动画名或集数"); return; }
        int current = episodeVersion;
        String[] labels = new String[candidates.size()];
        for (int i = 0; i < labels.length; i++) {
            AnimeDanmaku.Candidate item = candidates.get(i);
            labels[i] = item.animeTitle + " · " + item.episodeTitle;
        }
        panel().setTitle("选择弹幕对应的剧集").setNegativeButton("取消", null)
                .setItems(labels, (d, i) -> {
                    if (current == episodeVersion) {
                        AnimeDanmaku.Candidate item = candidates.get(i);
                        run("正在获取弹幕…", () -> fetchComments(credentials, item.episodeId), result -> {
                            if (episode != null && !result.isEmpty()) {
                                settings.edit().putString("title_" + AnimePlaybackHistory.showKey(episode), item.animeTitle)
                                        .apply();
                            }
                            loaded(result);
                        }, error -> showFailure("获取弹幕失败", error));
                    }
                }).show();
    }

    private List<AnimeDanmaku.Comment> fetchComments(AnimeDanmaku.Credentials credentials, long episodeId)
            throws IOException {
        return credentials.configured() ? AnimeDanmaku.comments(credentials, episodeId)
                : AnimeDanmakuCommunity.comments(episodeId);
    }

    private void openXml() {
        documentEpisode = episodeVersion;
        try {
            host.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*"), OPEN_XML);
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            message("无法打开文件选择器");
        }
    }

    void acceptDocument(Intent data) {
        Uri uri = data == null ? null : data.getData();
        if (uri == null || !"content".equals(uri.getScheme()) || closed
                || documentEpisode != episodeVersion) return;
        run("正在读取弹幕文件…", () -> {
            try (InputStream input = host.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取文件");
                return AnimeDanmaku.importXml(input);
            }
        }, this::loaded, error -> showFailure("读取弹幕文件失败", error));
    }

    private void loaded(List<AnimeDanmaku.Comment> result) {
        if (result.isEmpty()) { message("这个剧集没有可显示的弹幕"); return; }
        comments = result;
        status = "";
        int filtered = applyFilter();
        message(enabled ? host.getString(R.string.anime_danmaku_loaded, comments.size(), filtered)
                : "弹幕已加载，点「弹」按钮显示");
    }

    private int applyFilter() {
        List<AnimeDanmakuView.Comment> filtered = new ArrayList<>();
        Map<String, Long> lastShown = mergeDuplicates ? new HashMap<>() : null;
        for (AnimeDanmaku.Comment item : comments) {
            int bit;
            switch (item.source) {
                case AnimeDanmaku.SOURCE_BILI: bit = 1; break;
                case AnimeDanmaku.SOURCE_GAMER: bit = 2; break;
                case AnimeDanmaku.SOURCE_DANDAN: bit = 4; break;
                default: bit = 8;
            }
            if ((platforms & bit) == 0) continue;
            AnimeDanmakuView.Mode mode = item.mode == AnimeDanmaku.TOP ? AnimeDanmakuView.Mode.TOP
                    : item.mode == AnimeDanmaku.BOTTOM ? AnimeDanmakuView.Mode.BOTTOM : AnimeDanmakuView.Mode.SCROLL;
            if ((modes & (1 << mode.ordinal())) == 0) continue;
            if (!blockedKeywords.isEmpty() || lastShown != null) {
                String normalized = item.text.toLowerCase(Locale.ROOT);
                if (isBlocked(normalized)) continue;
                if (lastShown != null) {
                    Long previous = lastShown.get(normalized);
                    if (previous != null && item.timeMs - previous < DUPLICATE_WINDOW_MS) continue;
                    lastShown.put(normalized, item.timeMs);
                }
            }
            filtered.add(new AnimeDanmakuView.Comment(item.timeMs, item.text, mode, item.argb));
        }
        view.setComments(filtered);
        return filtered.size();
    }

    private boolean isBlocked(String text) {
        for (String keyword : blockedKeywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private static List<String> readKeywords(String input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        String bounded = input.substring(0, Math.min(input.length(), MAX_KEYWORD_TEXT));
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String line : bounded.split("\\r?\\n|\\r")) {
            String keyword = line.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) unique.add(keyword);
            if (unique.size() >= MAX_KEYWORDS) break;
        }
        return new ArrayList<>(unique);
    }

    private void keywordsDialog() {
        LinearLayout inputs = fields();
        EditText keywords = new EditText(host);
        keywords.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        keywords.setGravity(Gravity.TOP | Gravity.START);
        keywords.setMinLines(3);
        keywords.setMaxLines(6);
        keywords.setText(displaySettings.getString(BLOCKED_KEYWORDS, ""));
        keywords.setHint("每行输入一个关键词");
        keywords.setContentDescription("屏蔽关键词，每行一个");
        keywords.setFilters(new InputFilter[] {new InputFilter.LengthFilter(MAX_KEYWORD_TEXT)});
        inputs.addView(keywords, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog dialog = panel().setTitle("屏蔽关键词")
                .setMessage("最多 50 个关键词，匹配时忽略大小写。留空即可清除。")
                .setView(inputs).setNegativeButton("取消", null).setPositiveButton("应用", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = keywords.getText().toString().trim();
            int entries = 0;
            for (String line : value.split("\\r?\\n|\\r")) {
                if (!line.trim().isEmpty()) entries++;
            }
            if (entries > MAX_KEYWORDS) {
                keywords.setError("最多输入 50 个关键词");
                return;
            }
            displaySettings.edit().putString(BLOCKED_KEYWORDS, value).apply();
            blockedKeywords = readKeywords(value);
            applyFilter();
            dialog.dismiss();
        });
    }

    private void modeDialog() {
        String[] types = {"滚动弹幕", "顶部弹幕", "底部弹幕", "合并 8 秒内相同弹幕"};
        boolean[] chosen = {(modes & 1) != 0, (modes & 2) != 0, (modes & 4) != 0, mergeDuplicates};
        panel().setTitle("弹幕类型与去重")
                .setMultiChoiceItems(types, chosen, (d, i, checked) -> chosen[i] = checked)
                .setNegativeButton("取消", null).setPositiveButton("应用", (d, w) -> {
                    modes = 0;
                    for (int i = 0; i < 3; i++) if (chosen[i]) modes |= 1 << i;
                    mergeDuplicates = chosen[3];
                    displaySettings.edit().putInt(MODES, modes).putBoolean(MERGE_DUPLICATES, mergeDuplicates).apply();
                    applyFilter();
                }).show();
    }

    private void appearanceDialog() {
        LinearLayout inputs = fields();
        SeekBar area = slider(inputs, "滚动弹幕区域", "%", 25, 100,
                displaySettings.getInt(AREA, AnimeDanmakuView.DEFAULT_AREA));
        SeekBar font = slider(inputs, "字号", " sp", 12, 26,
                displaySettings.getInt(TEXT_SIZE, AnimeDanmakuView.DEFAULT_TEXT_SIZE));
        SeekBar opacity = slider(inputs, "不透明度", "%", 10, 100,
                displaySettings.getInt(OPACITY, AnimeDanmakuView.DEFAULT_OPACITY));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(inputs);
        panel().setTitle("弹幕显示效果").setView(scroll)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (d, w) -> {
                    displaySettings.edit().remove(AREA).remove(TEXT_SIZE).remove(OPACITY).apply();
                    updateAppearance();
                })
                .setPositiveButton("应用", (d, w) -> {
                    displaySettings.edit().putInt(AREA, area.getProgress())
                            .putInt(TEXT_SIZE, font.getProgress()).putInt(OPACITY, opacity.getProgress()).apply();
                    updateAppearance();
                }).show();
    }

    private SeekBar slider(LinearLayout parent, String name, String unit, int min, int max, int value) {
        TextView title = new TextView(host);
        title.setTextSize(14);
        SeekBar bar = new SeekBar(host);
        bar.setMin(min);
        bar.setMax(max);
        bar.setProgress(value);
        bar.setContentDescription(name);
        title.setText(name + " " + bar.getProgress() + unit);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                title.setText(name + " " + progress + unit);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        parent.addView(title, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(bar, new LinearLayout.LayoutParams(-1, PlayerViews.dp(host, 42)));
        return bar;
    }

    private void updateAppearance() {
        view.setAppearance(displaySettings.getInt(AREA, AnimeDanmakuView.DEFAULT_AREA),
                displaySettings.getInt(TEXT_SIZE, AnimeDanmakuView.DEFAULT_TEXT_SIZE),
                displaySettings.getInt(OPACITY, AnimeDanmakuView.DEFAULT_OPACITY));
    }

    private void filterDialog() {
        boolean[] chosen = new boolean[PLATFORMS.length];
        for (int i = 0; i < chosen.length; i++) chosen[i] = (platforms & (1 << i)) != 0;
        panel().setTitle("筛选弹幕平台")
                .setMultiChoiceItems(PLATFORMS, chosen, (d, i, checked) -> chosen[i] = checked)
                .setNegativeButton("取消", null).setPositiveButton("应用", (d, w) -> {
                    platforms = 0;
                    for (int i = 0; i < chosen.length; i++) if (chosen[i]) platforms |= 1 << i;
                    settings.edit().putInt("platforms", platforms).apply();
                    applyFilter();
                }).show();
    }

    private LinearLayout fields() {
        LinearLayout inputs = new LinearLayout(host);
        inputs.setOrientation(LinearLayout.VERTICAL);
        int margin = (int) (24 * host.getResources().getDisplayMetrics().density);
        inputs.setPadding(margin, margin / 2, margin, 0);
        return inputs;
    }

    private AlertDialog.Builder panel() {
        return host instanceof AnimePlaybackActivity player ? new AnimePlayerPanel(player) : new AlertDialog.Builder(host);
    }

    private EditText field(LinearLayout inputs, String hint, int type) {
        EditText field = new EditText(host);
        field.setSingleLine(true);
        field.setInputType(type);
        field.setHint(hint);
        inputs.addView(field, new LinearLayout.LayoutParams(-1, -2));
        return field;
    }

    private interface Request<T> { T get() throws IOException; }

    private <T> void run(String loading, Request<T> task, Consumer<T> accept, Consumer<String> failure) {
        if (closed) return;
        cancel();
        int request = version;
        if (loading != null) message(loading);
        pending = worker.submit(() -> {
            try {
                T result = task.get();
                deliver(request, () -> accept.accept(result));
            } catch (IOException e) {
                String detail = e.getMessage();
                deliver(request, () -> failure.accept(detail != null && detail.matches("[\\p{IsHan}A-Za-z0-9（）。，、/ +：-]{1,100}")
                        ? detail : ""));
            } catch (RuntimeException e) {
                deliver(request, () -> failure.accept(""));
            }
        });
    }

    private void deliver(int request, Runnable update) {
        host.runOnUiThread(() -> {
            if (closed || host.isFinishing() || request != version) return;
            pending = null;
            autoLoading = false;
            update.run();
        });
    }

    private void message(String text) {
        Toast.makeText(host, text, Toast.LENGTH_SHORT).show();
    }

    private void showFailure(String label, String detail) {
        message(detail.isEmpty() ? label : label + " · " + detail);
    }

    private void cancel() {
        version++;
        if (pending != null) pending.cancel(true);
        pending = null;
        autoLoading = false;
    }

    @Override public void close() {
        closed = true;
        cancel();
        worker.shutdownNow();
        view.setEnabled(false);
    }
}
