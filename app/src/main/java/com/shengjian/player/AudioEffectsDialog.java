package com.shengjian.player;

import static com.shengjian.player.PlayerViews.BG;
import static com.shengjian.player.PlayerViews.DARK_MINT;
import static com.shengjian.player.PlayerViews.FG;
import static com.shengjian.player.PlayerViews.LINE;
import static com.shengjian.player.PlayerViews.MINT;
import static com.shengjian.player.PlayerViews.MUTED;
import static com.shengjian.player.PlayerViews.PINK;
import static com.shengjian.player.PlayerViews.SURFACE;
import static com.shengjian.player.PlayerViews.button;
import static com.shengjian.player.PlayerViews.surface;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Locale;
import java.util.function.IntConsumer;

final class AudioEffectsDialog {
    interface ChangeListener {
        void onChange(AudioEffects.Settings settings);
    }

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final long PREVIEW_INTERVAL_MS = 65;
    private static final int PINK_WASH = 0xfff9dce9;
    private static final String[] TAB_NAMES = {"预设", "均衡器", "高级"};

    private final Activity activity;
    private final Dialog dialog;
    private final ChangeListener listener;
    private final Runnable openSoundSettings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable preview = this::apply;
    private final NumberFormat percent = NumberFormat.getPercentInstance();
    private final EnumMap<AudioEffects.Profile, PresetRow> presets =
            new EnumMap<>(AudioEffects.Profile.class);
    private final TextView[] tabButtons = new TextView[TAB_NAMES.length];
    private final LinearLayout[] pages = new LinearLayout[TAB_NAMES.length];
    private final Band[] bands = new Band[AudioEffects.BAND_COUNT];
    private final ScrollView scroll;
    private final TextView statusText;
    private TextView eqPrompt, advancedPrompt, eqNote, resetEq, resetManual;
    private Slider presetStrength, bandGain, bassStrength, levelingStrength, spatialStrength;
    private AudioEffects.Profile profile;
    private AudioEffects.Settings applied;
    private AudioEffects.Mode mode;
    private AudioEffects.SpatialMode spatialMode;
    private final int[] gains;
    private int strength, bass, leveling, spatial;
    private int selectedBand = 3;
    private long lastApplyMs;

    AudioEffectsDialog(Activity activity, AudioEffects.Settings settings, ChangeListener listener,
                       Runnable openSoundSettings) {
        this.activity = activity;
        this.listener = listener;
        this.openSoundSettings = openSoundSettings;
        applied = settings;
        profile = settings.profile;
        strength = settings.strength;
        gains = settings.bandGainsDb();
        bass = settings.bass;
        leveling = settings.leveling;
        spatial = settings.spatial;

        dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setOnDismissListener(ignored -> apply());

        LinearLayout content = column();
        content.setPadding(dp(16), dp(15), dp(16), dp(13));
        GradientDrawable finish = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{PINK_WASH, SURFACE, SURFACE});
        finish.setCornerRadius(dp(25));
        finish.setStroke(dp(1), LINE);
        content.setBackground(finish);
        dialog.setContentView(content);

        LinearLayout header = row();
        PlayerViews.Icon symbol = new PlayerViews.Icon(activity, "wave", PINK, 25);
        symbol.setBackground(surface(activity, SURFACE, 16, LINE));
        header.addView(symbol, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout titles = column();
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, WRAP, 1);
        titleLayout.setMarginStart(dp(10));
        header.addView(titles, titleLayout);
        TextView title = text("音效", 23, FG, true);
        if (Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true);
        titles.addView(title);
        statusText = text("", 12, MUTED, false);
        statusText.setPadding(dp(8), dp(3), dp(8), dp(3));
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(WRAP, WRAP);
        statusLayout.topMargin = dp(3);
        titles.addView(statusText, statusLayout);
        add(content, header, 0);

        LinearLayout tabs = row();
        tabs.setPadding(dp(3), dp(3), dp(3), dp(3));
        tabs.setBackground(surface(activity, PINK_WASH, 16, 0));
        for (int i = 0; i < tabButtons.length; i++) {
            int tab = i;
            tabButtons[i] = action(TAB_NAMES[i], () -> showTab(tab));
            tabs.addView(tabButtons[i], new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        add(content, tabs, 12);

        scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout.LayoutParams scrollLayout = new LinearLayout.LayoutParams(MATCH, 0, 1);
        scrollLayout.topMargin = dp(10);
        content.addView(scroll, scrollLayout);
        pages[0] = buildPresets();
        pages[1] = buildEqualizer();
        pages[2] = buildAdvanced();

        TextView done = action("完成", this::dismiss);
        done.setBackground(button(activity, PINK, 13, 0));
        done.setTextColor(Color.WHITE);
        add(content, done, 8);

        showTab(0);
        renderSettings();
        setStatus(profile == AudioEffects.Profile.OFF ? AudioEffects.Mode.OFF : AudioEffects.Mode.PENDING,
                spatial == 0 ? AudioEffects.SpatialMode.OFF : AudioEffects.SpatialMode.PENDING);
    }

    void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        PageMotion.dialog(dialog.getWindow());
        dialog.show();
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setDimAmount(0.46f);
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            width = metrics.getBounds().width() - insets.left - insets.right;
            height = metrics.getBounds().height() - insets.top - insets.bottom;
        }
        window.setLayout(Math.min(dp(450), Math.max(1, width - dp(22))),
                Math.min(dp(607), Math.max(1, height - dp(22))));
    }

    boolean isShowing() {
        return dialog.isShowing();
    }

    void dismiss() {
        if (!dialog.isShowing()) return;
        apply();
        dialog.dismiss();
    }

    void setStatus(AudioEffects.Mode mode, AudioEffects.SpatialMode spatialMode) {
        if (this.mode == mode && this.spatialMode == spatialMode) return;
        this.mode = mode;
        this.spatialMode = spatialMode;
        renderStatus();
    }

    private void renderStatus() {
        String headline;
        switch (mode) {
            case DYNAMICS: headline = "动态处理已启用"; break;
            case EQUALIZER: headline = "兼容均衡已启用"; break;
            case PENDING: headline = "播放后应用所选效果"; break;
            case UNAVAILABLE: headline = "当前输出无法应用均衡或动态处理"; break;
            default: headline = AudioEffects.Profile.OFF == profile ? "原声模式" : "尚未启用均衡或动态处理";
        }
        if (spatialMode == AudioEffects.SpatialMode.ACTIVE) {
            headline = mode == AudioEffects.Mode.OFF ? "耳机声场已启用" : headline + " · 耳机声场";
        }
        statusText.setText(headline);
        boolean active = mode == AudioEffects.Mode.DYNAMICS
                || mode == AudioEffects.Mode.EQUALIZER
                || spatialMode == AudioEffects.SpatialMode.ACTIVE;
        statusText.setTextColor(active ? MINT : MUTED);
        statusText.setBackground(surface(activity, active ? DARK_MINT : SURFACE, 10, active ? 0 : LINE));

        boolean dynamicUnavailable = mode == AudioEffects.Mode.EQUALIZER || mode == AudioEffects.Mode.UNAVAILABLE;
        levelingStrength.description.setText(dynamicUnavailable
                ? "当前输出不可用，设置会保留"
                : "缩小响亮与安静段落之间的音量差");
        switch (spatialMode) {
            case ACTIVE:
                spatialStrength.description.setText("当前耳机输出已启用声场扩展");
                break;
            case HEADPHONES_REQUIRED:
                spatialStrength.description.setText("连接耳机后应用声场设置");
                break;
            case UNSUPPORTED:
                spatialStrength.description.setText("当前耳机输出不支持系统声场扩展");
                break;
            case PENDING:
                spatialStrength.description.setText("播放并连接兼容耳机后生效");
                break;
            default:
                spatialStrength.description.setText("扩展兼容耳机的立体声声场");
        }
    }

    private LinearLayout buildPresets() {
        LinearLayout page = column();
        for (AudioEffects.Profile option : AudioEffects.Profile.values()) {
            PresetRow preset = new PresetRow(option);
            add(page, preset.view, presets.isEmpty() ? 0 : 5);
            presets.put(option, preset);
        }
        presetStrength = new Slider("预设强度", "", 0, 100, strength, value -> strength = value);
        add(page, presetStrength.view, 9);
        return page;
    }

    private LinearLayout buildEqualizer() {
        LinearLayout page = column();
        eqPrompt = originalPrompt();
        add(page, eqPrompt, 0);

        LinearLayout heading = row();
        TextView title = text("8 段均衡", 16, FG, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, WRAP, 1));
        resetEq = action("重置均衡", () -> {
            Arrays.fill(gains, 0);
            renderSettings();
            apply();
        });
        resetEq.setPadding(dp(10), 0, dp(10), 0);
        heading.addView(resetEq, new LinearLayout.LayoutParams(WRAP, dp(44)));
        add(page, heading, 0);

        LinearLayout graph = row();
        graph.setPadding(dp(4), dp(6), dp(4), dp(6));
        graph.setBackground(surface(activity, BG, 16, LINE));
        for (int i = 0; i < bands.length; i++) {
            bands[i] = new Band(i);
            graph.addView(bands[i].view, new LinearLayout.LayoutParams(0, WRAP, 1));
        }
        add(page, graph, 4);
        eqNote = text("", 12, MUTED, false);
        add(page, eqNote, 7);
        bandGain = new Slider(frequency(selectedBand, false), "",
                AudioEffects.MIN_BAND_DB, AudioEffects.MAX_BAND_DB, gains[selectedBand], value -> {
                    gains[selectedBand] = value;
                    bands[selectedBand].render();
                    resetEq.setEnabled(hasCustomGains());
                    resetEq.setAlpha(resetEq.isEnabled() ? 1f : 0.5f);
                });
        add(page, bandGain.view, 10);
        return page;
    }

    private LinearLayout buildAdvanced() {
        LinearLayout page = column();
        advancedPrompt = originalPrompt();
        add(page, advancedPrompt, 0);
        bassStrength = new Slider("低频力度", "提高低音的相对存在感", 0, 100, bass, value -> bass = value);
        levelingStrength = new Slider("动态平衡", "缩小响亮与安静段落之间的音量差",
                0, 100, leveling, value -> leveling = value);
        spatialStrength = new Slider("耳机声场", "扩展兼容耳机的立体声声场",
                0, 100, spatial, value -> spatial = value);
        add(page, bassStrength.view, 0);
        add(page, levelingStrength.view, 6);
        add(page, spatialStrength.view, 6);
        resetManual = action("重置均衡与高级调节", () -> {
            Arrays.fill(gains, 0);
            bass = 0;
            leveling = 0;
            spatial = 0;
            renderSettings();
            apply();
        });
        add(page, resetManual, 8);
        if (openSoundSettings != null) {
            add(page, action("系统声音设置", () -> {
                dismiss();
                openSoundSettings.run();
            }), 8);
            TextView note = text("杜比全景声等功能以手机设置为准", 12, MUTED, false);
            note.setGravity(Gravity.CENTER);
            add(page, note, 4);
        }
        return page;
    }

    private TextView originalPrompt() {
        TextView prompt = action("当前为原声 · 启用自定义音效", () -> selectProfile(AudioEffects.Profile.CUSTOM));
        prompt.setTextColor(MINT);
        prompt.setBackground(button(activity, DARK_MINT, 13, 0));
        return prompt;
    }

    private void showTab(int index) {
        View previous = scroll.getChildAt(0);
        if (previous == pages[index]) return;
        if (previous != null) PageMotion.reset(previous);
        scroll.removeAllViews();
        scroll.addView(pages[index], new ScrollView.LayoutParams(MATCH, WRAP));
        scroll.scrollTo(0, 0);
        for (int i = 0; i < tabButtons.length; i++) {
            boolean selected = index == i;
            TextView view = tabButtons[i];
            view.setSelected(selected);
            view.setTextColor(selected ? PINK : FG);
            view.setBackground(button(activity, selected ? SURFACE : Color.TRANSPARENT, 12,
                    selected ? LINE : 0));
        }
        if (previous != null) PageMotion.fade(pages[index]);
    }

    private void selectProfile(AudioEffects.Profile option) {
        if (profile == option) return;
        profile = option;
        renderSettings();
        apply();
    }

    private void renderSettings() {
        boolean enabled = profile != AudioEffects.Profile.OFF;
        boolean usePreset = enabled && profile != AudioEffects.Profile.CUSTOM;
        for (PresetRow preset : presets.values()) preset.render();
        presetStrength.view.setVisibility(usePreset ? View.VISIBLE : View.GONE);
        presetStrength.render(strength, usePreset);
        eqPrompt.setVisibility(enabled ? View.GONE : View.VISIBLE);
        advancedPrompt.setVisibility(enabled ? View.GONE : View.VISIBLE);
        eqNote.setText(usePreset ? "均衡调节会叠加在当前预设上" : "点选频段后调整增益");
        for (Band band : bands) band.render();
        renderSelectedBand();
        resetEq.setEnabled(enabled && hasCustomGains());
        resetEq.setAlpha(resetEq.isEnabled() ? 1f : 0.5f);
        bassStrength.render(bass, enabled);
        levelingStrength.render(leveling, enabled);
        spatialStrength.render(spatial, enabled);
        boolean changed = hasCustomGains() || bass != 0 || leveling != 0 || spatial != 0;
        resetManual.setEnabled(enabled && changed);
        resetManual.setAlpha(resetManual.isEnabled() ? 1f : 0.5f);
        if (mode != null) renderStatus();
    }

    private void renderSelectedBand() {
        bandGain.setLabel(frequency(selectedBand, false) + " 增益");
        bandGain.render(gains[selectedBand], profile != AudioEffects.Profile.OFF);
    }

    private boolean hasCustomGains() {
        for (int gain : gains) if (gain != 0) return true;
        return false;
    }

    private void onAdjust(boolean dragging) {
        boolean anyManual = hasCustomGains() || bass != 0 || leveling != 0 || spatial != 0;
        resetManual.setEnabled(anyManual);
        resetManual.setAlpha(anyManual ? 1f : 0.5f);
        handler.removeCallbacks(preview);
        long delay = PREVIEW_INTERVAL_MS - (SystemClock.uptimeMillis() - lastApplyMs);
        if (!dragging || delay <= 0) apply();
        else handler.postDelayed(preview, delay);
    }

    private void apply() {
        handler.removeCallbacks(preview);
        AudioEffects.Settings next = new AudioEffects.Settings(profile, strength, gains, bass, leveling, spatial);
        if (next.equals(applied)) return;
        applied = next;
        lastApplyMs = SystemClock.uptimeMillis();
        listener.onChange(next);
    }

    private String frequency(int band, boolean compact) {
        int hz = AudioEffects.bandFrequencyHz(band);
        if (compact && hz >= 1000) {
            return hz % 1000 == 0 ? (hz / 1000) + "k"
                    : String.format(Locale.ROOT, "%.1fk", hz / 1000f);
        }
        return compact ? Integer.toString(hz) : hz + " Hz";
    }

    private String gainText(int value) {
        return (value > 0 ? "+" : "") + value;
    }

    private final class PresetRow {
        final AudioEffects.Profile option;
        final LinearLayout view;
        final TextView title;
        final RadioButton indicator;

        PresetRow(AudioEffects.Profile option) {
            this.option = option;
            view = row();
            view.setMinimumHeight(dp(51));
            view.setPadding(dp(12), dp(7), dp(10), dp(7));
            view.setFocusable(true);
            view.setContentDescription(option.label + "，" + option.description);
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName(RadioButton.class.getName());
                    info.setCheckable(true);
                    info.setChecked(profile == option);
                }
            });
            view.setOnClickListener(ignored -> selectProfile(option));
            LinearLayout info = column();
            info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            view.addView(info, new LinearLayout.LayoutParams(0, WRAP, 1));
            title = text(option.label, 15, FG, true);
            info.addView(title);
            add(info, text(option.description, 12, MUTED, false), 2);

            indicator = new RadioButton(activity);
            indicator.setPadding(0, 0, 0, 0);
            indicator.setMinimumWidth(0);
            indicator.setMinimumHeight(0);
            indicator.setBackground(null);
            indicator.setFocusable(false);
            indicator.setClickable(false);
            indicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            indicator.setButtonTintList(new ColorStateList(
                    new int[][]{{android.R.attr.state_checked}, {}}, new int[]{PINK, MUTED}));
            view.addView(indicator, new LinearLayout.LayoutParams(dp(27), dp(28)));
        }

        void render() {
            boolean selected = profile == option;
            view.setSelected(selected);
            view.setBackground(button(activity, selected ? PINK_WASH : SURFACE, 14, selected ? PINK : LINE));
            title.setTextColor(selected ? PINK : FG);
            indicator.setChecked(selected);
        }
    }

    private final class Band {
        final int index;
        final LinearLayout view;
        final TextView db;
        final TextView label;
        final BandMeter meter;

        Band(int index) {
            this.index = index;
            view = column();
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(0, dp(6), 0, dp(6));
            view.setFocusable(true);
            view.setOnClickListener(ignored -> {
                int previous = selectedBand;
                selectedBand = index;
                bands[previous].render();
                render();
                renderSelectedBand();
            });
            db = text("", 11, MINT, true);
            db.setGravity(Gravity.CENTER);
            db.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(db);
            meter = new BandMeter();
            meter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(meter, new LinearLayout.LayoutParams(MATCH, dp(76)));
            label = text(frequency(index, true), 10, MUTED, true);
            label.setGravity(Gravity.CENTER);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            view.addView(label);
        }

        void render() {
            boolean enabled = profile != AudioEffects.Profile.OFF;
            boolean selected = selectedBand == index;
            view.setEnabled(enabled);
            view.setSelected(selected);
            view.setBackground(button(activity, selected ? PINK_WASH : Color.TRANSPARENT, 9, 0));
            view.setAlpha(enabled ? 1f : 0.55f);
            String value = gainText(gains[index]);
            db.setText(value);
            db.setTextColor(selected ? PINK : gains[index] != 0 ? MINT : MUTED);
            label.setTextColor(selected ? PINK : MUTED);
            view.setContentDescription(frequency(index, false) + "，" + value + " dB");
            meter.gain = gains[index];
            meter.invalidate();
        }
    }

    private final class BandMeter extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float rail = dp(2);
        private final int pad = dp(8);
        private final int dot = dp(3);
        int gain;

        BandMeter() {
            super(activity);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            paint.setColor(LINE);
            canvas.drawRoundRect(cx - rail, pad, cx + rail, getHeight() - pad, rail, rail, paint);
            canvas.drawRect(0, cy, getWidth(), cy + Math.max(1, dp(1)), paint);
            int maximum = Math.max(AudioEffects.MAX_BAND_DB, -AudioEffects.MIN_BAND_DB);
            float y = cy - (cy - pad) * gain / Math.max(1, maximum);
            paint.setColor(gain == 0 ? MUTED : gain > 0 ? PINK : MINT);
            canvas.drawRoundRect(cx - rail, Math.min(y, cy), cx + rail, Math.max(y, cy), rail, rail, paint);
            canvas.drawCircle(cx, y, dot, paint);
        }
    }

    private final class Slider {
        final LinearLayout view = column();
        final TextView label;
        final TextView description;
        final TextView current;
        final SeekBar slider;
        final int minimum;
        boolean dragging;

        Slider(String name, String subtitle, int minimum, int maximum, int initial, IntConsumer changed) {
            this.minimum = minimum;
            view.setPadding(dp(12), dp(8), dp(12), dp(1));
            view.setBackground(surface(activity, BG, 13, LINE));
            LinearLayout heading = row();
            label = text("", 14, FG, true);
            heading.addView(label, new LinearLayout.LayoutParams(0, WRAP, 1));
            current = text("", 12, PINK, true);
            current.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            current.setPadding(dp(7), dp(3), dp(7), dp(3));
            current.setBackground(surface(activity, PINK_WASH, 9, 0));
            current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            heading.addView(current);
            add(view, heading, 0);
            description = text(subtitle, 12, MUTED, false);
            description.setVisibility(subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            add(view, description, 3);

            slider = new SeekBar(activity);
            slider.setMax(maximum - minimum);
            slider.setKeyProgressIncrement(minimum == 0 ? 5 : 1);
            slider.setPadding(dp(8), 0, dp(8), 0);
            slider.setThumbTintList(ColorStateList.valueOf(PINK));
            slider.setProgressTintList(ColorStateList.valueOf(PINK));
            slider.setProgressBackgroundTintList(ColorStateList.valueOf(LINE));
            setLabel(name);
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    int value = progress + minimum;
                    renderValue(value);
                    changed.accept(value);
                    onAdjust(dragging);
                }

                @Override public void onStartTrackingTouch(SeekBar seek) {
                    dragging = true;
                }

                @Override public void onStopTrackingTouch(SeekBar seek) {
                    dragging = false;
                    apply();
                }
            });
            view.addView(slider, new LinearLayout.LayoutParams(MATCH, dp(47)));
            render(initial, true);
        }

        void setLabel(String name) {
            label.setText(name);
            slider.setContentDescription(name);
        }

        void render(int value, boolean enabled) {
            slider.setProgress(value - minimum);
            slider.setEnabled(enabled);
            label.setTextColor(enabled ? FG : MUTED);
            current.setTextColor(enabled ? PINK : MUTED);
            slider.setAlpha(enabled ? 1f : 0.45f);
            renderValue(value);
        }

        private void renderValue(int value) {
            String display = minimum == 0 ? percent.format(value / 100.0) : gainText(value) + " dB";
            current.setText(display);
            if (Build.VERSION.SDK_INT >= 30) slider.setStateDescription(display);
        }
    }

    private TextView action(String label, Runnable click) {
        TextView view = text(label, 14, FG, true);
        view.setGravity(Gravity.CENTER);
        view.setMinimumHeight(dp(44));
        view.setPadding(dp(7), dp(4), dp(7), dp(4));
        view.setBackground(button(activity, BG, 13, LINE));
        view.setFocusable(true);
        view.setOnClickListener(ignored -> click.run());
        return view;
    }

    private void add(LinearLayout parent, View child, int gap) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(MATCH, WRAP);
        params.topMargin = dp(gap);
        parent.addView(child, params);
    }

    private TextView text(String text, int size, int color, boolean bold) {
        TextView view = new TextView(activity);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        return row;
    }

    private int dp(int amount) {
        return PlayerViews.dp(activity, amount);
    }
}
