package com.shengjian.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Owns the local engine, audio focus and one pending utterance for the current chapter. */
final class NovelSpeech {
    private final Context app;
    private final String chapter;
    private final IntConsumer position;
    private final Consumer<String> error;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final Runnable initTimeout = () -> fail("系统朗读引擎启动超时，请检查手机的文字转语音设置。");
    private TextToSpeech engine;
    private AudioFocusRequest focus;
    private String utterance;
    private int generation;
    private int cursor;

    NovelSpeech(Context context, String chapter, IntConsumer position, Consumer<String> error) {
        this.app = context.getApplicationContext();
        this.chapter = chapter;
        this.position = position;
        this.error = error;
        audio = app.getSystemService(AudioManager.class);
    }

    boolean active() { return engine != null; }

    void start(int offset) {
        stop();
        cursor = offset;
        int token = generation;
        try {
            engine = new TextToSpeech(app, status -> main.post(() -> initialized(token, status)));
            main.postDelayed(initTimeout, 8_000);
        } catch (RuntimeException unavailable) {
            fail("无法启动系统朗读，请检查手机的文字转语音设置。");
        }
    }

    void stop() {
        generation++;
        utterance = null;
        main.removeCallbacks(initTimeout);
        TextToSpeech old = engine;
        engine = null;
        if (old != null) {
            try {
                old.stop();
            } catch (RuntimeException ignored) {
                // The engine might already have disconnected.
            }
            try {
                old.shutdown();
            } catch (RuntimeException ignored) {
                // Audio focus still belongs to this controller.
            }
        }
        if (focus != null) {
            audio.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }

    private void initialized(int token, int status) {
        if (token != generation || engine == null) return;
        main.removeCallbacks(initTimeout);
        if (status != TextToSpeech.SUCCESS) {
            fail("没有可用的系统朗读引擎，请检查手机的文字转语音设置。");
            return;
        }
        try {
            configureEngine();
        } catch (RuntimeException disconnected) {
            fail("系统朗读引擎不可用，请检查手机的文字转语音设置。");
        }
    }

    private void configureEngine() {
        Locale locale = readingLocale();
        Voice voice = localVoice(locale);
        if (voice == null || engine.setVoice(voice) != TextToSpeech.SUCCESS) {
            fail("请在系统文字转语音设置中安装离线" + locale.getDisplayLanguage(Locale.SIMPLIFIED_CHINESE) + "语音。");
            return;
        }
        if (!requestFocus()) {
            fail("当前无法开始朗读，请稍后重试。");
            return;
        }
        engine.setAudioAttributes(attributes);
        int listener = engine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}

            @Override public void onDone(String id) {
                main.post(() -> { if (accepts(id)) speakNext(); });
            }

            @Override public void onError(String id) {
                main.post(() -> { if (accepts(id)) fail("朗读中断，请检查离线语音。"); });
            }
        });
        if (listener != TextToSpeech.SUCCESS) {
            fail("无法接收朗读进度，请检查离线语音。");
            return;
        }
        speakNext();
    }

    private boolean accepts(String id) {
        return engine != null && id != null && id.equals(utterance);
    }

    private boolean requestFocus() {
        if (audio == null) return false;
        int token = generation;
        AudioFocusRequest request = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> { if (token == generation && change < 0) stop(); }, main).build();
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false;
        focus = request;
        return true;
    }

    private Voice localVoice(Locale locale) {
        Set<Voice> voices = engine.getVoices();
        if (voices == null) return null;
        return voices.stream().filter(voice -> !voice.isNetworkConnectionRequired()
                        && locale.getLanguage().equals(voice.getLocale().getLanguage())
                        && (voice.getFeatures() == null
                        || !voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)))
                .max(Comparator.<Voice>comparingInt(voice -> locale.getCountry().equals(voice.getLocale().getCountry()) ? 1 : 0)
                        .thenComparingInt(Voice::getQuality).thenComparing(Voice::getName)).orElse(null);
    }

    private Locale readingLocale() {
        int end = Math.min(chapter.length(), cursor + 1200);
        boolean han = false;
        for (int index = cursor; index < end; ) {
            int codePoint = chapter.codePointAt(index);
            index += Character.charCount(codePoint);
            Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
            if (script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA) return Locale.JAPAN;
            if (script == Character.UnicodeScript.HANGUL) return Locale.KOREA;
            if (script == Character.UnicodeScript.HAN) han = true;
        }
        Locale locale = Locale.getDefault();
        if (han) return locale.getLanguage().equals("zh") ? locale : Locale.SIMPLIFIED_CHINESE;
        return locale.getLanguage().equals("zh") ? Locale.ENGLISH : locale;
    }

    private void speakNext() {
        while (cursor < chapter.length()) {
            int start = cursor;
            int limit = Math.min(320, TextToSpeech.getMaxSpeechInputLength());
            cursor = NovelTextTools.speechEnd(chapter, start, limit);
            String phrase = chapter.substring(start, cursor).trim();
            if (phrase.isEmpty()) continue;
            utterance = "novel-" + generation + "-" + start;
            if (engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, utterance) == TextToSpeech.SUCCESS) {
                position.accept(start);
            } else {
                fail("无法朗读当前内容，请检查离线语音。");
            }
            return;
        }
        stop();
    }

    private void fail(String message) {
        stop();
        error.accept(message);
    }
}
