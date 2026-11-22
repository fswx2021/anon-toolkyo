package com.shengjian.player;

import android.annotation.TargetApi;
import android.media.AudioFormat;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.DynamicsProcessing;
import android.media.audiofx.Equalizer;
import android.media.audiofx.Virtualizer;
import android.os.Build;
import android.util.Log;

import java.util.Arrays;

/**
 * Optional processing for the player's audio session. All calls and effect callbacks run on
 * the playback service's main thread.
 */
@SuppressWarnings("deprecation") // The system Spatializer API has no per-session strength control.
final class AudioEffects {
    static final int BAND_COUNT = 8;
    static final int MIN_BAND_DB = -6;
    static final int MAX_BAND_DB = 6;

    enum Profile {
        OFF("原声", "关闭音效，保留当前设置", new float[BAND_COUNT]),
        CLARITY("清晰人声", "减轻低中频浑浊，让歌声和对白更突出",
                new float[] {-1.8f, -1.4f, -2.5f, -1f, 1.1f, 3f, 1.6f, 0.2f}),
        BASS("低频增强", "增加低频厚度，保留中频和人声",
                new float[] {4.2f, 3.6f, 1f, -0.7f, -0.2f, 0f, 0.2f, 0.4f}),
        NIGHT("夜间聆听", "减轻低频和刺耳高频，缩小响亮与安静段落的音量差",
                new float[] {-3.3f, -2.5f, -1.3f, 0.4f, 1.5f, 1f, -1.1f, -1.8f}),
        CUSTOM("自定义", "从平直曲线开始，手动调整频段和动态", new float[BAND_COUNT]);

        final String label;
        final String description;
        private final float[] gains;

        Profile(String label, String description, float[] gains) {
            this.label = label;
            this.description = description;
            this.gains = gains;
        }
    }

    enum Mode { OFF, PENDING, DYNAMICS, EQUALIZER, UNAVAILABLE }
    enum SpatialMode { OFF, PENDING, HEADPHONES_REQUIRED, ACTIVE, UNSUPPORTED }

    static final class Settings {
        final Profile profile;
        final int strength;
        final int bass;
        final int leveling;
        final int spatial;
        private final int[] bandGainsDb;

        Settings(Profile profile, int strength, int[] bandGainsDb, int bass, int leveling, int spatial) {
            this.profile = profile == null ? Profile.OFF : profile;
            this.strength = percent(strength);
            this.bass = percent(bass);
            this.leveling = percent(leveling);
            this.spatial = percent(spatial);
            this.bandGainsDb = bandGainsDb == null ? new int[BAND_COUNT] : Arrays.copyOf(bandGainsDb, BAND_COUNT);
            for (int band = 0; band < BAND_COUNT; band++) {
                this.bandGainsDb[band] = Math.max(MIN_BAND_DB, Math.min(MAX_BAND_DB, this.bandGainsDb[band]));
            }
        }

        static Settings defaults() {
            return new Settings(Profile.OFF, 60, null, 0, 0, 0);
        }

        int[] bandGainsDb() {
            return bandGainsDb.clone();
        }

        private static int percent(int value) {
            return Math.max(0, Math.min(100, value));
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Settings)) return false;
            Settings value = (Settings) other;
            return profile == value.profile && strength == value.strength
                    && bass == value.bass && leveling == value.leveling && spatial == value.spatial
                    && Arrays.equals(bandGainsDb, value.bandGainsDb);
        }

        @Override public int hashCode() {
            int result = profile.hashCode();
            result = 31 * result + strength;
            result = 31 * result + bass;
            result = 31 * result + leveling;
            result = 31 * result + spatial;
            return 31 * result + Arrays.hashCode(bandGainsDb);
        }
    }

    private static final String TAG = "ShengjianAudioEffects";
    private static final int[] BAND_CENTERS = {63, 125, 260, 530, 1060, 2220, 4750, 11500};
    private static final float[] BAND_CUTOFFS = {90f, 180f, 380f, 750f, 1500f, 3300f, 7000f, 20000f};
    private static final float[] BASS_BOOST_DB = {4f, 2.75f, 0.75f, 0f, 0f, 0f, 0f, 0f};
    private static final float HEADROOM_DB = 1.5f;
    private static final float FALLBACK_HEADROOM_DB = 0.5f;
    private static final float SPATIAL_HEADROOM_DB = 2.5f;
    private static final int SPATIAL_MAX_STRENGTH = 650; // Virtualizer uses a 0–1000 strength range.
    private static final int SPATIAL_BINAURAL = Virtualizer.VIRTUALIZATION_MODE_BINAURAL;

    private Settings settings = Settings.defaults();
    private final float[] gains = new float[BAND_COUNT];
    private float tonalMagnitude;
    private float presetMix;
    private int sessionId;
    private AudioEffect effect;
    private Mode engine = Mode.UNAVAILABLE;
    private boolean controlled;
    private boolean enabled;
    private boolean dynamicsFailed;
    private boolean equalizerFailed;
    private Virtualizer spatializer;
    private boolean headphones;
    private int routeId = -1;
    private boolean spatialFailed;
    private int spatialStrength = -1;

    static int bandFrequencyHz(int band) {
        return BAND_CENTERS[band];
    }

    void attach(int sessionId) {
        // Session zero is the global mix. Scope processing to this player only.
        int scopedSession = Math.max(0, sessionId);
        if (this.sessionId == scopedSession) return;
        detach();
        this.sessionId = scopedSession;
        applyProcessing();
        applySpatial();
    }

    void detach() {
        releaseSpatial();
        releaseProcessing();
        sessionId = 0;
        routeId = -1;
        headphones = false;
        spatialFailed = false;
        dynamicsFailed = false;
        equalizerFailed = false;
    }

    void update(Settings settings) {
        if (this.settings.equals(settings)) return;
        this.settings = settings;
        presetMix = settings.profile == Profile.OFF || settings.profile == Profile.CUSTOM
                ? 0f : settings.strength / 100f;
        tonalMagnitude = 0f;
        for (int band = 0; band < BAND_COUNT; band++) {
            float combined = settings.profile == Profile.OFF ? 0f : settings.profile.gains[band] * presetMix
                    + settings.bandGainsDb[band] + BASS_BOOST_DB[band] * settings.bass / 100f;
            gains[band] = Math.max(-12f, Math.min(12f, combined));
            tonalMagnitude = Math.max(tonalMagnitude, Math.abs(gains[band]));
        }
        if (!wantsSpatial()) {
            releaseSpatial();
            spatialFailed = false;
        }
        applyProcessing();
        applySpatial();
    }

    Mode mode() {
        if (!wantsProcessing()) return Mode.OFF;
        if (sessionId == 0) return Mode.PENDING;
        return controlled && enabled ? engine : Mode.UNAVAILABLE;
    }

    void setHeadphoneRoute(boolean headphones, int routeId) {
        if (this.headphones == headphones && this.routeId == routeId) return;
        this.headphones = headphones;
        this.routeId = routeId;
        releaseSpatial();
        spatialFailed = false;
        applyProcessing();
        applySpatial();
    }

    SpatialMode spatialMode() {
        if (!wantsSpatial()) return SpatialMode.OFF;
        if (Build.VERSION.SDK_INT < 28) return SpatialMode.UNSUPPORTED;
        if (sessionId == 0 || routeId < 0) return SpatialMode.PENDING;
        if (!headphones) return SpatialMode.HEADPHONES_REQUIRED;
        if (spatializer == null) return SpatialMode.UNSUPPORTED;
        try {
            return spatializer.hasControl() && spatializer.getEnabled()
                    && spatializer.getVirtualizationMode() == SPATIAL_BINAURAL
                    ? SpatialMode.ACTIVE : SpatialMode.UNSUPPORTED;
        } catch (RuntimeException unavailable) {
            releaseSpatial();
            spatialFailed = true;
            applyProcessing();
            return SpatialMode.UNSUPPORTED;
        }
    }

    private boolean wantsProcessing() {
        return settings.profile != Profile.OFF && (presetMix > 0f || tonalMagnitude > 0f || settings.leveling > 0);
    }

    private boolean wantsSpatial() {
        return settings.profile != Profile.OFF && settings.spatial > 0;
    }

    private boolean spatialRequestedForRoute() {
        return Build.VERSION.SDK_INT >= 28 && sessionId > 0 && routeId >= 0
                && headphones && wantsSpatial() && !spatialFailed;
    }

    private void applyProcessing() {
        if (sessionId == 0 || !wantsProcessing()) {
            releaseProcessing();
            return;
        }
        if (engine == Mode.EQUALIZER && tonalMagnitude == 0f) releaseProcessing();
        if (effect != null) {
            if (!controlled) return;
            Mode previous = engine;
            if (configure()) return;
            releaseProcessing();
            if (previous == Mode.DYNAMICS) dynamicsFailed = true;
            else equalizerFailed = true;
        } else if (!dynamicsFailed && Build.VERSION.SDK_INT >= 28 && openDynamics()) {
            return;
        }
        if (!equalizerFailed && tonalMagnitude > 0f) openEqualizer();
    }

    @TargetApi(28)
    private boolean openDynamics() {
        try {
            register(DynamicsEngine.create(sessionId), Mode.DYNAMICS);
            if (!controlled || configure()) return true;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Dynamics processing unavailable for session " + sessionId, unavailable);
        }
        dynamicsFailed = true;
        releaseProcessing();
        return false;
    }

    private void openEqualizer() {
        try {
            register(new Equalizer(0, sessionId), Mode.EQUALIZER);
            if (!controlled || configure()) return;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Equalizer unavailable for session " + sessionId, unavailable);
        }
        equalizerFailed = true;
        releaseProcessing();
    }

    private void register(AudioEffect candidate, Mode mode) {
        effect = candidate;
        engine = mode;
        controlled = candidate.hasControl();
        candidate.setControlStatusListener((changed, hasControl) -> {
            if (changed != effect) return;
            controlled = hasControl;
            if (hasControl) applyProcessing();
        });
        candidate.setEnableStatusListener((changed, isEnabled) -> {
            if (changed == effect) enabled = isEnabled;
        });
    }

    private boolean configure() {
        try {
            float spatialReserve = spatialRequestedForRoute() ? SPATIAL_HEADROOM_DB * settings.spatial / 100f : 0f;
            float headroomScale = Math.min(1f, Math.max(tonalMagnitude / MAX_BAND_DB,
                    Math.max(presetMix, settings.leveling / 100f)));
            if (effect instanceof Equalizer) {
                configureEqualizer((Equalizer) effect, gains, FALLBACK_HEADROOM_DB * headroomScale + spatialReserve);
            } else if (Build.VERSION.SDK_INT >= 28) {
                DynamicsEngine.configure(effect, gains, settings.profile, presetMix, settings.leveling / 100f,
                        HEADROOM_DB * headroomScale + spatialReserve);
            }
            enabled = effect.setEnabled(true) == AudioEffect.SUCCESS && effect.getEnabled();
            return enabled;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Could not adjust processing for session " + sessionId, unavailable);
            return false;
        }
    }

    private void releaseProcessing() {
        AudioEffect previous = effect;
        effect = null;
        controlled = false;
        enabled = false;
        engine = Mode.UNAVAILABLE;
        dispose(previous);
    }

    private void releaseSpatial() {
        Virtualizer previous = spatializer;
        spatializer = null;
        spatialStrength = -1;
        dispose(previous);
    }

    private void dispose(AudioEffect previous) {
        if (previous == null) return;
        try {
            if (previous.hasControl()) previous.setEnabled(false);
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Effect already unavailable when disabling", unavailable);
        }
        try {
            previous.release();
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Effect already unavailable when releasing", unavailable);
        }
    }

    private void applySpatial() {
        if (!spatialRequestedForRoute()) return;
        int desiredStrength = Math.max(1, settings.spatial * SPATIAL_MAX_STRENGTH / 100);
        if (desiredStrength == spatialStrength) return;
        try {
            if (spatializer == null) {
                spatializer = new Virtualizer(0, sessionId);
                if (!spatializer.canVirtualize(AudioFormat.CHANNEL_OUT_STEREO, SPATIAL_BINAURAL)
                        || !spatializer.getStrengthSupported()) {
                    throw new UnsupportedOperationException("Adjustable stereo headphone virtualization unavailable");
                }
                spatializer.setControlStatusListener((changed, hasControl) -> {
                    if (changed != spatializer) return;
                    spatialStrength = -1;
                    if (hasControl) applySpatial();
                });
                // A new controller or a route change can disable the native effect.
                spatializer.setEnableStatusListener((changed, isEnabled) -> {
                    if (changed == spatializer && !isEnabled) spatialStrength = -1;
                });
            }
            if (!spatializer.hasControl()) return;
            spatializer.setStrength((short) desiredStrength);
            if (spatializer.setEnabled(true) != AudioEffect.SUCCESS
                    || !spatializer.forceVirtualizationMode(SPATIAL_BINAURAL)) {
                throw new UnsupportedOperationException("Could not start headphone virtualization");
            }
            spatialStrength = desiredStrength;
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Headphone spatial effect unavailable for session " + sessionId, unavailable);
            spatialFailed = true;
            releaseSpatial();
            applyProcessing(); // Reclaim the headroom reserved for the unavailable stage.
        }
    }

    private static void configureEqualizer(Equalizer equalizer, float[] gains, float headroom) {
        short bands = equalizer.getNumberOfBands();
        if (bands < 2) throw new UnsupportedOperationException("Tonal EQ requires multiple bands");
        short[] range = equalizer.getBandLevelRange();
        if (range.length < 2 || range[0] >= 0) {
            throw new UnsupportedOperationException("Device equalizer cannot attenuate");
        }
        float reduction = maxBoost(gains) + headroom;
        for (short band = 0; band < bands; band++) {
            float frequency = equalizer.getCenterFreq(band) / 1000f;
            int gain = fallbackGain(gains, frequency, reduction, range[0], range[1]);
            equalizer.setBandLevel(band, (short) gain);
        }
    }

    private static int fallbackGain(float[] gains, float frequency, float reduction, int minMb, int maxMb) {
        // The hardware EQ has no limiter. Shape the spectrum using attenuation only.
        int levelMb = Math.round((gainAt(gains, frequency) - reduction) * 100f);
        return Math.max(minMb, Math.min(Math.min(0, maxMb), levelMb));
    }

    private static float gainAt(float[] gains, float frequency) {
        if (frequency <= BAND_CENTERS[0]) return gains[0];
        for (int band = 1; band < BAND_CENTERS.length; band++) {
            if (frequency <= BAND_CENTERS[band]) {
                double lower = BAND_CENTERS[band - 1];
                double upper = BAND_CENTERS[band];
                float fraction = (float) (Math.log(frequency / lower) / Math.log(upper / lower));
                return gains[band - 1] + fraction * (gains[band] - gains[band - 1]);
            }
        }
        return gains[gains.length - 1];
    }

    private static float maxBoost(float[] gains) {
        float max = 0f;
        for (float gain : gains) {
            max = Math.max(max, gain);
        }
        return max;
    }

    @TargetApi(28)
    private static final class DynamicsEngine {
        private static final float[] COMPRESSOR_CUTOFFS = {160f, 1000f, 5500f, 20000f};
        private static final float[] ATTACK_MS = {18f, 13f, 9f, 6f};
        private static final float[] RELEASE_MS = {155f, 125f, 105f, 75f};
        private static final float[] MUSIC_THRESHOLDS_DB = {-18f, -16f, -17f, -21f};
        private static final float[] NIGHT_THRESHOLDS_DB = {-26f, -28f, -27f, -24f};
        private static final float[] MUSIC_RATIOS = {1.45f, 1.25f, 1.25f, 1.45f};
        private static final float[] NIGHT_RATIOS = {2.5f, 3f, 2.7f, 2.3f};
        private static final DynamicsProcessing.Limiter LIMITER =
                new DynamicsProcessing.Limiter(true, true, 0, 1f, 75f, 10f, -2f, 0f);

        static AudioEffect create(int session) {
            // The native constructor adapts the supplied configuration to the engine's channel count.
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                    true, BAND_CUTOFFS.length, true, COMPRESSOR_CUTOFFS.length, false, 0, true)
                    .setPreferredFrameDuration(20f)
                    .setLimiterAllChannelsTo(LIMITER)
                    .build();
            return new DynamicsProcessing(0, session, config);
        }

        static void configure(AudioEffect effect, float[] gains, Profile profile, float presetMix,
                              float leveling, float headroom) {
            DynamicsProcessing processor = (DynamicsProcessing) effect;
            if (processor.getChannelCount() < 1) {
                throw new UnsupportedOperationException("Dynamics processing has no channels");
            }
            float inputGain = -(maxBoost(gains) + headroom);
            float previousGain = processor.getInputGainByChannelIndex(0);
            // Lower the input before increasing any band, and raise it after lowering the bands.
            if (inputGain < previousGain) processor.setInputGainAllChannelsTo(inputGain);
            for (int band = 0; band < BAND_CUTOFFS.length; band++) {
                processor.setPreEqBandAllChannelsTo(band,
                        new DynamicsProcessing.EqBand(true, BAND_CUTOFFS[band], gains[band]));
            }
            boolean night = profile == Profile.NIGHT && presetMix > 0f;
            DynamicsProcessing.Mbc compressor = new DynamicsProcessing.Mbc(
                    true, presetMix > 0f || leveling > 0f, COMPRESSOR_CUTOFFS.length);
            for (int band = 0; band < COMPRESSOR_CUTOFFS.length; band++) {
                float ratio = (night ? NIGHT_RATIOS : MUSIC_RATIOS)[band];
                if (profile == Profile.BASS && band == 0) ratio = 1.65f;
                float baseThreshold = (night ? NIGHT_THRESHOLDS_DB : MUSIC_THRESHOLDS_DB)[band];
                // As leveling rises, compress at softer levels and recover a little perceived volume.
                // Shift the detector threshold with pre-gain so EQ headroom cannot erase leveling.
                float threshold = baseThreshold + inputGain - 5f * leveling;
                float combinedRatio = 1f + (ratio - 1f) * presetMix + 1.6f * leveling;
                float makeupGain = Math.min(3f, (night ? 1.75f * presetMix : 0f) + 2.5f * leveling);
                compressor.setBand(band, new DynamicsProcessing.MbcBand(
                        true, COMPRESSOR_CUTOFFS[band], ATTACK_MS[band], RELEASE_MS[band],
                        combinedRatio, threshold, 6f, -90f, 1f, 0f, makeupGain));
            }
            processor.setMbcAllChannelsTo(compressor);
            if (inputGain >= previousGain) processor.setInputGainAllChannelsTo(inputGain);
        }
    }
}
