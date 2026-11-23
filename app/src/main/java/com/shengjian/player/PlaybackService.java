package com.shengjian.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PlaybackService extends Service {
    public static final int REPEAT_OFF = 0;
    public static final int REPEAT_ALL = 1;
    public static final int REPEAT_ONE = 2;
    public static final String EXTRA_OPEN_PLAYER = "com.shengjian.player.OPEN_PLAYER";

    private static final String TAG = "ShengjianPlayback";
    private static final String CHANNEL_ID = "audio_playback";
    private static final String AUDIO_PROFILE = "audio_profile";
    private static final String AUDIO_STRENGTH = "audio_strength";
    private static final String AUDIO_EQ = "audio_eq_";
    private static final String AUDIO_BASS = "audio_bass";
    private static final String AUDIO_LEVELING = "audio_leveling";
    private static final String AUDIO_SPATIAL = "audio_spatial";
    private static final int NOTIFICATION_ID = 519;
    private static final long PAUSE_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final long PREPARE_TIMEOUT_MS = 25 * 1000L;
    private static final int BILI_TRIES = 4;
    private static final long BILI_PREPARE_TIMEOUT_MS = 7_000L;
    private static final long BILI_STALL_TIMEOUT_MS = 12_000L;
    private static final int ARTWORK_MAX_BYTES = 8 * 1024 * 1024;
    private static final int ARTWORK_TARGET_SIZE = 512;

    public static final class Track {
        public final String id;
        public final String url;
        public final String title;
        public final String artist;
        public final String artwork;
        public final String source;
        public final String referer;

        public Track(String id, String url, String title, String artist, String artwork,
                     String source, String referer) {
            this.id = id;
            this.url = url;
            this.title = title;
            this.artist = artist;
            this.artwork = artwork;
            this.source = source;
            this.referer = referer;
        }
    }

    public final class LocalBinder extends Binder {
        public PlaybackService getService() {
            return PlaybackService.this;
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final AudioEffects audioEffects = new AudioEffects();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService onlineExecutor = Executors.newFixedThreadPool(2);
    private final List<Integer> shuffledIndices = new ArrayList<>();
    private final AudioAttributes audioAttributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build();
    private final Runnable pauseTimeout = this::dismissNotification;
    private final Runnable lookupTimeout = () -> fail("获取在线音轨超时，请重试");
    private Runnable mediaTimeout;
    private Runnable stallTimeout;
    private final Runnable refreshRouteAfterStart = this::refreshAudioRoute;
    private final Runnable sleepPause = () -> {
        sleepEndsAt = 0;
        pause();
    };
    private final Runnable savePlayback = new Runnable() {
        @Override public void run() {
            persistPlaybackPosition();
            if (playing) mainHandler.postDelayed(this, 5_000);
        }
    };
    private final AudioRouting.OnRoutingChangedListener audioRouteListener = this::onAudioRouteChanged;
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                pause();
            }
        }
    };

    private List<Track> queue = Collections.emptyList();
    private int index = -1;
    private int repeatMode = REPEAT_OFF;
    private boolean shuffled;
    private long lastPosition;
    private long mediaDuration;
    private long seekPosition = -1;
    private long sleepEndsAt;
    private float speed = 1f;
    private String error = "";
    private String recordedPlaybackTrack = "";

    private SharedPreferences playbackSettings;
    private SharedPreferences musicSettings;
    private AudioEffects.Settings effectsSettings;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private boolean hasAudioFocus;
    private boolean resumeOnFocusGain;
    private MediaSession session;
    private PendingIntent openPlayer;
    private NotificationManager notifications;
    private MediaPlayer player;
    private boolean prepared;
    private boolean loading;
    private boolean playRequested;
    private boolean playing;
    private boolean buffering;
    private boolean started;
    private boolean foreground;
    private boolean notificationVisible;
    private boolean destroyed;
    private int loadGeneration;

    private record MediaSources(List<String> urls, String referer, boolean privateStream, boolean bilibili) {}
    private Future<?> onlineLookup;
    private Future<?> onlineLyrics;
    private Bitmap artwork;
    private int artworkRequest;

    @Override public void onCreate() {
        super.onCreate();
        playbackSettings = getSharedPreferences("playback_settings", MODE_PRIVATE);
        musicSettings = getSharedPreferences(MusicCollections.PREFS, MODE_PRIVATE);
        speed = musicSettings.getFloat(MusicCollections.SPEED, 1f);
        if (!Float.isFinite(speed) || speed < .5f || speed > 2f) speed = 1f;
        effectsSettings = readEffectsSettings();
        audioEffects.update(effectsSettings);
        shuffled = playbackSettings.getBoolean("shuffle", false);
        repeatMode = playbackSettings.getInt("repeat", REPEAT_OFF);
        audioManager = getSystemService(AudioManager.class);
        audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttributes)
                .setOnAudioFocusChangeListener(this::onAudioFocusChanged, mainHandler)
                .build();
        notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "音乐播放",
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(channel);

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_OPEN_PLAYER, true);
        openPlayer = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        session = new MediaSession(this, TAG);
        session.setSessionActivity(openPlayer);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { dismissNotification(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { previous(); }
            @Override public void onSeekTo(long position) { seekTo(position); }
        }, mainHandler);

        IntentFilter noisyFilter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(noisyReceiver, noisyFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(noisyReceiver, noisyFilter);
        }
    }

    @Override public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        started = true;
        if (currentTrack() == null) {
            started = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case "play": play(); break;
                case "pause": pause(); break;
                case "previous": previous(); break;
                case "next": next(); break;
                case "dismiss": dismissNotification(); break;
                default: break;
            }
        }
        return START_NOT_STICKY;
    }

    public List<Track> getQueue() {
        return queue;
    }

    List<Track> playbackOrder() {
        if (!shuffled) return queue;
        List<Track> ordered = new ArrayList<>();
        for (int slot : shuffledIndices) ordered.add(queue.get(slot));
        return ordered;
    }

    public Track currentTrack() {
        return index >= 0 && index < queue.size() ? queue.get(index) : null;
    }

    public int currentIndex() {
        return index;
    }

    public boolean isPlaying() {
        return playing;
    }

    public boolean isBuffering() {
        return loading || buffering;
    }

    public boolean isPlayRequested() {
        return playRequested;
    }

    public String getError() {
        return error;
    }

    private AudioEffects.Settings readEffectsSettings() {
        AudioEffects.Settings defaults = AudioEffects.Settings.defaults();
        String name = playbackSettings.getString(AUDIO_PROFILE, defaults.profile.name());
        AudioEffects.Profile profile;
        try {
            profile = AudioEffects.Profile.valueOf(name);
        } catch (IllegalArgumentException invalidProfile) {
            profile = defaults.profile;
        }
        int[] gains = defaults.bandGainsDb();
        for (int band = 0; band < gains.length; band++) {
            gains[band] = playbackSettings.getInt(AUDIO_EQ + band, gains[band]);
        }
        return new AudioEffects.Settings(profile,
                playbackSettings.getInt(AUDIO_STRENGTH, defaults.strength), gains,
                playbackSettings.getInt(AUDIO_BASS, defaults.bass),
                playbackSettings.getInt(AUDIO_LEVELING, defaults.leveling),
                playbackSettings.getInt(AUDIO_SPATIAL, defaults.spatial));
    }

    AudioEffects.Settings audioSettings() {
        return effectsSettings;
    }

    AudioEffects.Mode audioEffectMode() {
        return audioEffects.mode();
    }

    AudioEffects.SpatialMode spatialEffectMode() {
        return audioEffects.spatialMode();
    }

    void setAudioEffects(AudioEffects.Settings settings) {
        if (effectsSettings.equals(settings)) return;
        effectsSettings = settings;
        audioEffects.update(settings);
        SharedPreferences.Editor editor = playbackSettings.edit()
                .putString(AUDIO_PROFILE, settings.profile.name())
                .putInt(AUDIO_STRENGTH, settings.strength)
                .putInt(AUDIO_BASS, settings.bass)
                .putInt(AUDIO_LEVELING, settings.leveling)
                .putInt(AUDIO_SPATIAL, settings.spatial);
        int[] gains = settings.bandGainsDb();
        for (int band = 0; band < gains.length; band++) {
            editor.putInt(AUDIO_EQ + band, gains[band]);
        }
        editor.apply();
    }

    public long position() {
        if (seekPosition >= 0) return seekPosition;
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (IllegalStateException exception) {
                Log.w(TAG, "Playback position temporarily unavailable", exception);
            }
        }
        return lastPosition;
    }

    public long duration() {
        return mediaDuration;
    }

    float speed() { return speed; }

    boolean setSpeed(float value) {
        if (value < .5f || value > 2f || !Float.isFinite(value)) return false;
        if (prepared && playing && !applySpeed(value)) return false;
        speed = value;
        musicSettings.edit().putFloat(MusicCollections.SPEED, speed).apply();
        publish();
        return true;
    }

    long sleepRemaining() { return Math.max(0, sleepEndsAt - SystemClock.elapsedRealtime()); }

    void sleepAfter(int minutes) {
        mainHandler.removeCallbacks(sleepPause);
        long delay = minutes * 60_000L;
        sleepEndsAt = minutes > 0 ? SystemClock.elapsedRealtime() + delay : 0;
        if (minutes > 0) mainHandler.postDelayed(sleepPause, delay);
    }

    public void setQueue(List<Track> tracks, int selectedIndex) {
        if (selectedIndex < 0 || selectedIndex >= tracks.size()) return;
        Track selected = tracks.get(selectedIndex);
        Track previous = currentTrack();
        if (previous != null && previous.id.equals(selected.id) && previous.url.equals(selected.url)
                && previous.referer.equals(selected.referer)) {
            if (!sameOrder(queue, tracks)) {
                queue = Collections.unmodifiableList(new ArrayList<>(tracks));
                index = selectedIndex;
                rebuildShuffleOrder();
                persistQueue();
                publish();
            }
            play();
        } else {
            replaceQueue(tracks, selectedIndex, 0, true, Collections.emptyList());
        }
    }

    public void restoreQueue(List<Track> tracks, int selectedIndex, long positionMs) {
        List<String> order = MusicCollections.readIds(musicSettings.getString(MusicCollections.SHUFFLE_ORDER, "[]"));
        replaceQueue(tracks, selectedIndex, positionMs, false, order);
    }

    private void replaceQueue(List<Track> tracks, int selectedIndex, long positionMs,
                              boolean autoplay, List<String> restoredOrder) {
        queue = Collections.unmodifiableList(new ArrayList<>(tracks));
        if (queue.isEmpty()) {
            clearCurrentTrack();
            persistQueue();
            return;
        }
        index = Math.max(0, Math.min(selectedIndex, queue.size() - 1));
        if (restoredOrder.isEmpty()) rebuildShuffleOrder();
        else keepShuffleOrder(restoredOrder);
        loadCurrent(positionMs, autoplay);
    }

    private void updateQueue(List<Track> tracks, List<String> order) {
        Track previousTrack = currentTrack();
        int previousIndex = previousTrack == null ? -1 : order.indexOf(previousTrack.id);
        queue = Collections.unmodifiableList(new ArrayList<>(tracks));
        index = previousTrack == null ? -1 : queueIndex(previousTrack.id);
        if (queue.isEmpty()) {
            clearCurrentTrack();
            persistQueue();
            return;
        }
        if (index < 0) {
            index = followingTrack(order, previousIndex);
            keepShuffleOrder(order);
            loadCurrent(0, playRequested);
            return;
        }
        keepShuffleOrder(order);
        Track updated = currentTrack();
        if (!Objects.equals(previousTrack.url, updated.url)
                || !Objects.equals(previousTrack.referer, updated.referer)) {
            loadCurrent(position(), playRequested);
            return;
        }
        if (!Objects.equals(previousTrack.artwork, updated.artwork)) refreshArtwork();
        updateMetadata();
        persistQueue();
        publish();
    }

    private int followingTrack(List<String> oldOrder, int previousIndex) {
        for (int i = previousIndex + 1; i < oldOrder.size(); i++) {
            int next = queueIndex(oldOrder.get(i));
            if (next >= 0) return next;
        }
        for (int i = previousIndex - 1; i >= 0; i--) {
            int previous = queueIndex(oldOrder.get(i));
            if (previous >= 0) return previous;
        }
        return 0;
    }

    void refreshQueue(List<Track> library) {
        Map<String, Track> available = new LinkedHashMap<>();
        for (Track item : library) available.put(item.id, item);
        MusicCollections.pruneRecent(musicSettings, available.keySet());
        if (queue.isEmpty()) return;
        List<Track> updated = new ArrayList<>();
        for (Track item : queue) if (available.containsKey(item.id)) updated.add(available.get(item.id));
        updateQueue(updated, trackIds(playbackOrder()));
    }

    void playQueued(String id) {
        int selected = queueIndex(id);
        if (selected < 0) return;
        if (selected == index) play();
        else {
            index = selected;
            loadCurrent(0, true);
        }
    }

    void playNext(Track track) {
        Track current = currentTrack();
        if (current == null) {
            setQueue(Collections.singletonList(track), 0);
            return;
        }
        if (current.id.equals(track.id)) return;
        List<Track> ordered = new ArrayList<>(playbackOrder());
        ordered.removeIf(item -> item.id.equals(track.id));
        for (int i = 0; i < ordered.size(); i++) {
            if (!ordered.get(i).id.equals(current.id)) continue;
            ordered.add(i + 1, track);
            applyEditedOrder(ordered);
            return;
        }
    }

    void moveQueued(String id, int direction) {
        List<Track> ordered = new ArrayList<>(playbackOrder());
        for (int i = 0; i < ordered.size(); i++) {
            if (!ordered.get(i).id.equals(id)) continue;
            int target = i + direction;
            if (target >= 0 && target < ordered.size()) {
                Collections.swap(ordered, i, target);
                applyEditedOrder(ordered);
            }
            return;
        }
    }

    void removeQueued(String id) {
        List<Track> ordered = new ArrayList<>(playbackOrder());
        List<String> previousOrder = trackIds(ordered);
        if (ordered.removeIf(item -> item.id.equals(id))) updateQueue(ordered, previousOrder);
    }

    private void applyEditedOrder(List<Track> ordered) {
        updateQueue(ordered, trackIds(ordered));
    }

    private int queueIndex(String id) {
        for (int i = 0; i < queue.size(); i++) if (Objects.equals(queue.get(i).id, id)) return i;
        return -1;
    }

    private static List<String> trackIds(List<Track> tracks) {
        List<String> ids = new ArrayList<>(tracks.size());
        for (Track item : tracks) ids.add(item.id);
        return ids;
    }

    private boolean sameOrder(List<Track> oldQueue, List<Track> newQueue) {
        if (oldQueue.size() != newQueue.size()) return false;
        for (int i = 0; i < oldQueue.size(); i++) {
            if (!Objects.equals(oldQueue.get(i).id, newQueue.get(i).id)) return false;
        }
        return true;
    }

    public void toggle() {
        if (playRequested) pause();
        else play();
    }

    public void play() {
        if (queue.isEmpty() || playRequested) return;
        if (index < 0) {
            index = 0;
            rebuildShuffleOrder();
        }
        if (loading && onlineLookup != null) {
            playRequested = true;
            publish();
            return;
        }
        if (player == null) {
            loadCurrent(lastPosition, true);
            return;
        }
        playRequested = true;
        if (startPlayback()) publish();
    }

    public void pause() {
        if (!playRequested) return;
        pausePlayback(false);
        publish();
    }

    public void next() {
        skip(1, true);
    }

    public void previous() {
        if (position() > 3000 || queue.size() == 1) seekTo(0);
        else skip(-1, true);
    }

    public void seekTo(long positionMs) {
        if (currentTrack() == null) return;
        long target = Math.max(0, positionMs);
        if (mediaDuration > 0) target = Math.min(target, mediaDuration);
        lastPosition = target;
        if (seekPlayer(target)) {
            persistPlaybackPosition();
            publish();
        }
    }

    public boolean isShuffle() {
        return shuffled;
    }

    public void setShuffle(boolean enabled) {
        if (shuffled == enabled) return;
        shuffled = enabled;
        rebuildShuffleOrder();
        playbackSettings.edit().putBoolean("shuffle", enabled).apply();
        persistQueue();
        publish();
    }

    public int getRepeatMode() {
        return repeatMode;
    }

    public void setRepeatMode(int mode) {
        if (mode < REPEAT_OFF || mode > REPEAT_ONE || mode == repeatMode) return;
        repeatMode = mode;
        playbackSettings.edit().putInt("repeat", mode).apply();
        publish();
    }

    private void rebuildShuffleOrder() {
        shuffledIndices.clear();
        if (!shuffled || index < 0) return;
        for (int i = 0; i < queue.size(); i++) {
            if (i != index) shuffledIndices.add(i);
        }
        Collections.shuffle(shuffledIndices);
        shuffledIndices.add(0, index);
    }

    private void keepShuffleOrder(List<String> orderedIds) {
        shuffledIndices.clear();
        if (!shuffled || index < 0) return;
        Map<String, Integer> available = new LinkedHashMap<>();
        for (int i = 0; i < queue.size(); i++) available.put(queue.get(i).id, i);
        for (String id : orderedIds) {
            Integer slot = available.remove(id);
            if (slot != null) shuffledIndices.add(slot);
        }
        shuffledIndices.addAll(available.values());
    }

    private void persistQueue() {
        Track selected = currentTrack();
        musicSettings.edit().putString(MusicCollections.QUEUE, MusicCollections.writeIds(trackIds(queue)))
                .putString(MusicCollections.SHUFFLE_ORDER, shuffled
                        ? MusicCollections.writeIds(trackIds(playbackOrder())) : "[]")
                .putString(MusicCollections.CURRENT, selected == null ? "" : selected.id)
                .putLong(MusicCollections.POSITION, selected == null ? 0 : Math.max(0, position())).apply();
    }

    private void persistPlaybackPosition() {
        Track selected = currentTrack();
        if (selected != null) {
            lastPosition = Math.max(0, position());
            musicSettings.edit().putString(MusicCollections.CURRENT, selected.id)
                    .putLong(MusicCollections.POSITION, lastPosition).apply();
        }
    }

    private boolean skip(int direction, boolean manual) {
        if (queue.size() < 2 || index < 0) return false;
        int offset = shuffled ? shuffledIndices.indexOf(index) : index;
        int target = offset + direction;
        if (target < 0 || target >= queue.size()) {
            if (!manual && repeatMode == REPEAT_OFF) return false;
            target = Math.floorMod(target, queue.size());
        }
        index = shuffled ? shuffledIndices.get(target) : target;
        loadCurrent(0, true);
        return true;
    }

    private void loadCurrent(long positionMs, boolean autoplay) {
        Track track = currentTrack();
        if (track == null) return;

        if (!track.id.equals(recordedPlaybackTrack)) recordedPlaybackTrack = "";
        releasePlayer();
        if (!autoplay) {
            releaseAudioFocus();
            resumeOnFocusGain = false;
        }
        lastPosition = Math.max(0, positionMs);
        mediaDuration = 0;
        error = "";
        playRequested = autoplay;
        loading = true;
        persistQueue();
        refreshArtwork();
        updateMetadata();

        if (MusicOnlineTrack.isReference(track.url)) {
            if (!autoplay) {
                loading = false;
                publish();
                return;
            }
            resolveOnline(track, loadGeneration);
        } else {
            openMedia(track.url, track.referer, false);
        }
    }

    private void resolveOnline(Track track, int generation) {
        onlineLookup = onlineExecutor.submit(() -> {
            try {
                MusicOnlineTrack reference = MusicOnlineTrack.parse(track.url);
                MusicOnlineEngine.Config config = reference.isNavidrome() ? MusicOnlineAccounts.get(this) : null;
                if (reference.isNavidrome()) reference.checkAccount(config);
                MusicOnlineEngine.Song song = new MusicOnlineEngine.Song(reference.provider(), reference.songId(),
                        track.title, track.artist, "", track.artwork, 0);
                LinkResolver.Result media = MusicOnlineEngine.resolve(song, config);
                mainHandler.post(() -> {
                    if (destroyed || loadGeneration != generation) return;
                    onlineLookup = null;
                    mainHandler.removeCallbacks(lookupTimeout);
                    boolean bilibili = MusicOnlineEngine.BILIBILI.equals(reference.provider());
                    List<String> urls = media.mediaUrls;
                    if (bilibili && urls.size() > BILI_TRIES) urls = urls.subList(0, BILI_TRIES);
                    openMedia(new MediaSources(urls, media.referer, reference.isNavidrome(), bilibili), 0);
                    if (loadGeneration == generation && reference.isNavidrome()) {
                        onlineLyrics = onlineExecutor.submit(() -> loadOnlineLyrics(track, song, config));
                    }
                });
            } catch (IOException failure) {
                String message = failure.getMessage();
                mainHandler.post(() -> {
                    if (!destroyed && loadGeneration == generation) fail(message == null ? "暂时无法读取在线音轨" : message);
                });
            } catch (RuntimeException failure) {
                mainHandler.post(() -> {
                    if (!destroyed && loadGeneration == generation) fail("暂时无法读取在线音轨");
                });
            }
        });
        mainHandler.postDelayed(lookupTimeout, PREPARE_TIMEOUT_MS);
        publish();
    }

    private void loadOnlineLyrics(Track track, MusicOnlineEngine.Song song, MusicOnlineEngine.Config config) {
        Library library = Library.get(this);
        try {
            if (library.hasLyrics(track.id)) return;
            String lyrics = MusicOnlineEngine.lyrics(song, config);
            if (!Thread.currentThread().isInterrupted()) library.saveOnlineLyrics(track.id, track.url, lyrics);
        } catch (IOException unavailable) {
            Log.d(TAG, "Online lyrics unavailable");
        }
    }

    private void openMedia(String address, String referer, boolean privateStream) {
        openMedia(new MediaSources(List.of(address), referer, privateStream, false), 0);
    }

    private void openMedia(MediaSources sources, int attempt) {
        String address = sources.urls().get(attempt);
        MediaPlayer pending = new MediaPlayer();
        player = pending;
        mediaTimeout = () -> {
            if (pending == player && !prepared && !retryMedia(pending, sources, attempt)) {
                fail(sources.bilibili() ? "B 站音轨的播放线路暂不可用，请重试或换一个结果"
                        : "载入超时，请检查来源后重试");
            }
        };
        if (sources.bilibili()) {
            stallTimeout = () -> {
                if (pending == player && buffering && playing && !retryMedia(pending, sources, attempt)) {
                    fail("B 站音轨缓冲时间过长，请检查网络后重试");
                }
            };
        }
        try {
            pending.setAudioAttributes(audioAttributes);
            watchAudioRoute(pending, true);
            pending.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
            pending.setOnPreparedListener(this::onPrepared);
            pending.setOnCompletionListener(this::onCompleted);
            pending.setOnErrorListener((failed, what, extra) -> {
                if (retryMedia(failed, sources, attempt)) return true;
                return onPlayerError(failed, what, extra);
            });
            pending.setOnInfoListener(this::onPlayerInfo);
            pending.setOnSeekCompleteListener(seeked -> {
                if (seeked == player) {
                    seekPosition = -1;
                    publish();
                }
            });
            Uri uri = Uri.parse(address);
            if (isWebUri(uri)) {
                Map<String, String> headers = webHeaders(sources.referer());
                if (sources.bilibili()) headers.put("User-Agent", LinkResolver.USER_AGENT);
                if (sources.privateStream()) headers.put("android-allow-cross-domain-redirect", "0");
                pending.setDataSource(this, uri, headers);
            } else if (uri.getScheme() == null) {
                pending.setDataSource(address);
            } else {
                pending.setDataSource(this, uri);
            }
            pending.prepareAsync();
            mainHandler.postDelayed(mediaTimeout, sources.bilibili() ? BILI_PREPARE_TIMEOUT_MS : PREPARE_TIMEOUT_MS);
            publish();
        } catch (IOException | RuntimeException exception) {
            if (!sources.privateStream()) Log.w(TAG, "Unable to open media source", exception);
            if (!retryMedia(pending, sources, attempt)) fail("无法打开文件或播放地址，请检查来源后重试");
        }
    }

    private boolean retryMedia(MediaPlayer failed, MediaSources sources, int attempt) {
        if (failed != player || attempt + 1 >= sources.urls().size() || prepared && !sources.bilibili()) return false;
        if (mediaTimeout != null) mainHandler.removeCallbacks(mediaTimeout);
        if (stallTimeout != null) mainHandler.removeCallbacks(stallTimeout);
        mainHandler.post(() -> {
            if (player != failed) return;
            lastPosition = position();
            releasePlayer();
            loading = true;
            openMedia(sources, attempt + 1);
        });
        return true;
    }

    private void onPrepared(MediaPlayer ready) {
        if (ready != player) return;
        if (mediaTimeout != null) mainHandler.removeCallbacks(mediaTimeout);
        loading = false;
        prepared = true;
        try {
            MediaPlayer.TrackInfo[] tracks = ready.getTrackInfo();
            int video = ready.getSelectedTrack(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_VIDEO);
            boolean hasAudio = false;
            for (MediaPlayer.TrackInfo track : tracks) {
                hasAudio |= track.getTrackType() == MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO;
            }
            if (tracks.length > 0 && !hasAudio) {
                fail("没有找到可播放的音轨");
                return;
            }
            // The player has no video surface. Deselect video when supported by the container.
            if (video >= 0) {
                try {
                    ready.deselectTrack(video);
                } catch (RuntimeException exception) {
                    Log.d(TAG, "Video track cannot be deselected for this container", exception);
                }
            }
            mediaDuration = Math.max(0, ready.getDuration());
            Track track = currentTrack();
            if (track != null && mediaDuration > 0 && track.url.startsWith("anon-music://audius/")) {
                MusicOnlineEngine.Song song = new MusicOnlineEngine.Song(MusicOnlineEngine.AUDIUS, "",
                        track.title, track.artist, "", track.artwork, mediaDuration);
                onlineLyrics = onlineExecutor.submit(() -> loadOnlineLyrics(track, song, null));
            }
            audioEffects.attach(ready.getAudioSessionId());
            updateMetadata();
            if (lastPosition != 0 && !seekPlayer(lastPosition)) return;
            if (startPlayback()) publish();
        } catch (RuntimeException exception) {
            Log.w(TAG, "Unable to prepare audio tracks", exception);
            fail("无法读取音轨，请尝试其他格式");
        }
    }

    private boolean seekPlayer(long target) {
        if (!prepared || player == null) return true;
        seekPosition = mediaDuration > 0 ? Math.min(target, mediaDuration) : target;
        try {
            player.seekTo(seekPosition, MediaPlayer.SEEK_CLOSEST);
            return true;
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Media seek failed", exception);
            fail("无法跳转到指定播放位置");
            return false;
        }
    }

    private boolean startPlayback() {
        if (!playRequested || !prepared || playing || resumeOnFocusGain) return true;
        if (!hasAudioFocus) {
            int granted = audioManager.requestAudioFocus(audioFocusRequest);
            if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                fail("暂时无法取得音频播放权限，请重试");
                return false;
            }
            hasAudioFocus = true;
        }
        try {
            player.start();
            playing = true;
            if (buffering) scheduleStallTimeout();
            if (speed != 1f && !applySpeed(speed)) {
                speed = 1f;
                musicSettings.edit().putFloat(MusicCollections.SPEED, speed).apply();
            }
            Track startedTrack = currentTrack();
            if (startedTrack != null && !startedTrack.id.equals(recordedPlaybackTrack)) {
                MusicCollections.recordPlayed(musicSettings, startedTrack.id, System.currentTimeMillis());
                recordedPlaybackTrack = startedTrack.id;
            }
            mainHandler.removeCallbacks(savePlayback);
            mainHandler.postDelayed(savePlayback, 5_000);
            refreshAudioRoute();
            if (Build.VERSION.SDK_INT >= 28) {
                mainHandler.removeCallbacks(refreshRouteAfterStart);
                mainHandler.postDelayed(refreshRouteAfterStart, 400);
            }
            return true;
        } catch (IllegalStateException exception) {
            Log.w(TAG, "Media start failed", exception);
            fail("播放失败，请重试");
            return false;
        }
    }

    private boolean applySpeed(float value) {
        try {
            PlaybackParams params = player.getPlaybackParams().setSpeed(value).setPitch(1f);
            player.setPlaybackParams(params);
            return true;
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            return false;
        }
    }

    private void onAudioRouteChanged(AudioRouting routing) {
        if (routing == player) refreshAudioRoute();
    }

    private void watchAudioRoute(MediaPlayer target, boolean on) {
        if (Build.VERSION.SDK_INT < 28) return;
        try {
            if (on) target.addOnRoutingChangedListener(audioRouteListener, mainHandler);
            else target.removeOnRoutingChangedListener(audioRouteListener);
        } catch (RuntimeException unavailable) {
            Log.d(TAG, "Playback route callbacks unavailable", unavailable);
        }
    }

    private void refreshAudioRoute() {
        AudioDeviceInfo output = null;
        if (Build.VERSION.SDK_INT >= 28 && player != null && playing) {
            try {
                output = player.getRoutedDevice();
            } catch (RuntimeException unavailable) {
                Log.d(TAG, "Playback route temporarily unavailable", unavailable);
            }
        }
        audioEffects.setHeadphoneRoute(output != null && isHeadphoneOutput(output.getType()),
                output == null ? -1 : output.getId());
    }

    private static boolean isHeadphoneOutput(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return true;
            default:
                return false;
        }
    }

    private void onCompleted(MediaPlayer finished) {
        if (finished != player) return;
        recordedPlaybackTrack = "";
        playing = false;
        mainHandler.removeCallbacks(savePlayback);
        persistPlaybackPosition();
        buffering = false;
        if (stallTimeout != null) mainHandler.removeCallbacks(stallTimeout);
        if (playRequested) {
            if (repeatMode == REPEAT_ONE || (repeatMode == REPEAT_ALL && queue.size() == 1)) {
                lastPosition = 0;
                if (startPlayback()) publish();
                return;
            }
            if (skip(1, false)) return;
        }
        playRequested = false;
        refreshAudioRoute();
        releaseAudioFocus();
        seekTo(0);
    }

    private boolean onPlayerError(MediaPlayer failed, int what, int extra) {
        if (failed != player) return true;
        Log.w(TAG, "Media error " + what + "/" + extra);
        if (extra == MediaPlayer.MEDIA_ERROR_UNSUPPORTED
                || extra == MediaPlayer.MEDIA_ERROR_MALFORMED) {
            fail("当前设备无法解码该音轨");
        } else if (extra == MediaPlayer.MEDIA_ERROR_IO || extra == MediaPlayer.MEDIA_ERROR_TIMED_OUT) {
            fail("读取媒体失败，请检查文件或网络后重试");
        } else {
            fail("无法播放此音频，请检查格式或来源");
        }
        return true;
    }

    private boolean onPlayerInfo(MediaPlayer media, int what, int extra) {
        if (media != player) return false;
        if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
            if (!buffering && playing) scheduleStallTimeout();
            buffering = true;
        } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
            buffering = false;
            if (stallTimeout != null) mainHandler.removeCallbacks(stallTimeout);
        } else {
            return false;
        }
        publish();
        return true;
    }

    private void scheduleStallTimeout() {
        if (stallTimeout == null) return;
        mainHandler.removeCallbacks(stallTimeout);
        mainHandler.postDelayed(stallTimeout, BILI_STALL_TIMEOUT_MS);
    }

    private void onAudioFocusChanged(int change) {
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            if (!hasAudioFocus && !resumeOnFocusGain) return;
            hasAudioFocus = true;
            if (player != null) player.setVolume(1f, 1f);
            if (resumeOnFocusGain) {
                resumeOnFocusGain = false;
                if (startPlayback()) publish();
            }
        } else if (hasAudioFocus && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            if (player != null) player.setVolume(0.25f, 0.25f);
        } else if (hasAudioFocus && change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            pausePlayback(true);
            publish();
        } else if (hasAudioFocus && change == AudioManager.AUDIOFOCUS_LOSS) {
            pausePlayback(false);
            publish();
        }
    }

    private void pausePlayback(boolean temporary) {
        resumeOnFocusGain = temporary && playRequested;
        playRequested = resumeOnFocusGain;
        if (playing && player != null) {
            try {
                player.pause();
            } catch (IllegalStateException exception) {
                Log.w(TAG, "Media pause failed", exception);
            }
        }
        playing = false;
        mainHandler.removeCallbacks(savePlayback);
        if (stallTimeout != null) mainHandler.removeCallbacks(stallTimeout);
        persistPlaybackPosition();
        refreshAudioRoute();
        if (!temporary) releaseAudioFocus();
    }

    private void fail(String message) {
        lastPosition = position();
        releasePlayer();
        releaseAudioFocus();
        playRequested = false;
        resumeOnFocusGain = false;
        error = message;
        publish();
    }

    private void releasePlayer() {
        loadGeneration++;
        if (onlineLookup != null) onlineLookup.cancel(true);
        if (onlineLyrics != null) onlineLyrics.cancel(true);
        onlineLookup = null;
        onlineLyrics = null;
        mainHandler.removeCallbacks(lookupTimeout);
        if (mediaTimeout != null) mainHandler.removeCallbacks(mediaTimeout);
        if (stallTimeout != null) mainHandler.removeCallbacks(stallTimeout);
        mediaTimeout = null;
        stallTimeout = null;
        mainHandler.removeCallbacks(refreshRouteAfterStart);
        mainHandler.removeCallbacks(savePlayback);
        audioEffects.detach();
        MediaPlayer released = player;
        player = null;
        prepared = false;
        loading = false;
        buffering = false;
        playing = false;
        seekPosition = -1;
        if (released != null) {
            watchAudioRoute(released, false);
            released.release();
        }
    }

    private void releaseAudioFocus() {
        if (!hasAudioFocus) return;
        hasAudioFocus = false;
        audioManager.abandonAudioFocusRequest(audioFocusRequest);
        if (player != null) player.setVolume(1f, 1f);
    }

    private void clearCurrentTrack() {
        index = -1;
        recordedPlaybackTrack = "";
        lastPosition = 0;
        mediaDuration = 0;
        error = "";
        releasePlayer();
        refreshArtwork();
        updateMetadata();
        dismissNotification();
    }

    private void dismissNotification() {
        pausePlayback(false);
        notificationVisible = false;
        mainHandler.removeCallbacks(pauseTimeout);
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
        notifications.cancel(NOTIFICATION_ID);
        publish();
        if (started) {
            started = false;
            stopSelf();
        }
    }

    private void updateMetadata() {
        Track track = currentTrack();
        if (track == null) {
            session.setMetadata(null);
            return;
        }
        String subtitle = TextUtils.isEmpty(track.artist) ? track.source : track.artist;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, track.id)
                .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, subtitle);
        if (mediaDuration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, mediaDuration);
        if (artwork != null) {
            metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
                    .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork);
        }
        session.setMetadata(metadata.build());
    }

    // Media session notifications are exempt from POST_NOTIFICATIONS.
    @android.annotation.SuppressLint("NotificationPermission")
    private void publish() {
        if (playRequested) {
            notificationVisible = true;
            mainHandler.removeCallbacks(pauseTimeout);
        }
        updateSessionState();
        if (notificationVisible) {
            Notification media = buildNotification();
            if (playRequested && !foreground) {
                try {
                    // A bound service alone would be destroyed when the player screen unbinds.
                    if (!started) {
                        startService(new Intent(this, PlaybackService.class));
                        started = true;
                    }
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIFICATION_ID, media, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
                    } else {
                        startForeground(NOTIFICATION_ID, media);
                    }
                    foreground = true;
                } catch (IllegalStateException | SecurityException exception) {
                    Log.w(TAG, "Unable to start background playback", exception);
                    notificationVisible = false;
                    started = false;
                    stopSelf();
                    fail("无法启动后台播放，请打开播放器后重试");
                    return;
                }
            } else {
                if (foreground && !playRequested) {
                    mainHandler.removeCallbacks(pauseTimeout);
                    mainHandler.postDelayed(pauseTimeout, PAUSE_TIMEOUT_MS);
                }
                notifications.notify(NOTIFICATION_ID, media);
            }
        }
    }

    private void updateSessionState() {
        int state = PlaybackState.STATE_NONE;
        long actions = 0;
        if (currentTrack() != null) {
            actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                    | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                    | PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            if (queue.size() > 1) actions |= PlaybackState.ACTION_SKIP_TO_NEXT;
            if (mediaDuration > 0) actions |= PlaybackState.ACTION_SEEK_TO;
            if (!error.isEmpty()) state = PlaybackState.STATE_ERROR;
            else if (playRequested && (isBuffering() || resumeOnFocusGain)) {
                state = PlaybackState.STATE_BUFFERING;
            }
            else if (playing) state = PlaybackState.STATE_PLAYING;
            else state = PlaybackState.STATE_PAUSED;
        }
        PlaybackState.Builder playback = new PlaybackState.Builder()
                .setActions(actions)
                .setState(state, position(), state == PlaybackState.STATE_PLAYING ? speed : 0f,
                        SystemClock.elapsedRealtime());
        if (!error.isEmpty()) playback.setErrorMessage(error);
        session.setPlaybackState(playback.build());
        session.setActive(notificationVisible);
    }

    private Notification buildNotification() {
        Track track = currentTrack();
        boolean canPause = playRequested;
        String subtitle = TextUtils.isEmpty(track.artist) ? track.source : track.artist;
        Notification.Builder notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_media_notification)
                .setContentTitle(track.title)
                .setContentText(subtitle)
                .setContentIntent(openPlayer)
                .setDeleteIntent(actionIntent("dismiss"))
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setOngoing(canPause)
                .setColor(PlayerViews.PINK)
                .setLargeIcon(artwork)
                .addAction(buildAction(android.R.drawable.ic_media_previous, "上一首", "previous"))
                .addAction(canPause
                        ? buildAction(android.R.drawable.ic_media_pause, "暂停", "pause")
                        : buildAction(android.R.drawable.ic_media_play, "播放", "play"));
        Notification.MediaStyle style = new Notification.MediaStyle().setMediaSession(session.getSessionToken());
        if (queue.size() > 1) {
            notification.addAction(buildAction(android.R.drawable.ic_media_next, "下一首", "next"));
            style.setShowActionsInCompactView(0, 1, 2);
        } else {
            style.setShowActionsInCompactView(0, 1);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            notification.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return notification.setStyle(style).build();
    }

    private Notification.Action buildAction(int icon, String label, String action) {
        return new Notification.Action.Builder(Icon.createWithResource("android", icon), label,
                actionIntent(action)).build();
    }

    private PendingIntent actionIntent(String action) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        return PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private void refreshArtwork() {
        artwork = null;
        int request = ++artworkRequest;
        Track track = currentTrack();
        if (track == null || TextUtils.isEmpty(track.artwork)) return;
        artworkExecutor.execute(() -> {
            Bitmap image = readArtwork(track);
            mainHandler.post(() -> {
                if (!destroyed && request == artworkRequest && image != null) {
                    artwork = image;
                    updateMetadata();
                    publish();
                }
            });
        });
    }

    private Bitmap readArtwork(Track track) {
        HttpURLConnection connection = null;
        try {
            Uri uri = Uri.parse(track.artwork);
            InputStream stream;
            if (isWebUri(uri)) {
                connection = (HttpURLConnection) new URL(track.artwork).openConnection();
                connection.setConnectTimeout(4000);
                connection.setReadTimeout(4000);
                for (Map.Entry<String, String> header : webHeaders(track.referer).entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                stream = connection.getInputStream();
            } else {
                stream = getContentResolver().openInputStream(uri);
            }
            byte[] data;
            try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) return null;
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > ARTWORK_MAX_BYTES) return null;
                    output.write(buffer, 0, read);
                }
                data = output.toByteArray();
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, options);
            options.inSampleSize = 1;
            int longestSide = Math.max(options.outWidth, options.outHeight);
            while (longestSide / options.inSampleSize > ARTWORK_TARGET_SIZE) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | RuntimeException exception) {
            Log.d(TAG, "Artwork unavailable", exception);
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Map<String, String> webHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36");
        if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
        return headers;
    }

    private static boolean isWebUri(Uri uri) {
        return "http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme());
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (!playRequested) dismissNotification();
    }

    @Override public void onDestroy() {
        destroyed = true;
        persistPlaybackPosition();
        mainHandler.removeCallbacksAndMessages(null);
        artworkExecutor.shutdownNow();
        onlineExecutor.shutdownNow();
        unregisterReceiver(noisyReceiver);
        releasePlayer();
        releaseAudioFocus();
        session.release();
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
        notifications.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }
}
