package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Follows what is playing. Two sources:
 * <ol>
 * <li>Media sessions (needs notification access): title, artist and album art from
 * Spotify or any other player.</li>
 * <li>Spotify's own broadcasts (needs "Device Broadcast Status" switched on in
 * Spotify's settings): title and artist only, but no extra permission.</li>
 * </ol>
 * It also drives playback: through the media session when it has one, otherwise with
 * media-key events, which Android delivers to the active player without any permission.
 */
final class MediaWatcher {
    interface Listener {
        void onTrack(String title, String artist, Bitmap art);

        /** Called on the main thread whenever the known play/pause state flips. */
        void onPlayback(boolean playing);
    }

    private static final String TAG = "TAV";
    private final Context ctx;
    private final AudioManager audio;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private String lastKey = "";
    private boolean started, spotifyPlaying, spotifyStateSeen, lastPlaying;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (msm != null) pick(null);
            notifyPlaying();
        }
    };

    private final BroadcastReceiver spotify = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Any app can send these actions to an exported receiver: a malformed bundle must not
            // crash us, and only plain, short strings are accepted.
            String title, artist;
            try {
                if ("com.spotify.music.playbackstatechanged".equals(i.getAction()) && i.hasExtra("playing")) {
                    spotifyPlaying = i.getBooleanExtra("playing", spotifyPlaying);
                    spotifyStateSeen = true;
                }
                title = i.getStringExtra("track");
                artist = i.getStringExtra("artist");
            } catch (RuntimeException e) {
                return;
            }
            notifyPlaying();
            if (controller != null) return;   // media session has richer data
            if (title != null && title.length() <= 300 && (artist == null || artist.length() <= 300))
                deliver(title, artist, null);
        }
    };

    MediaWatcher(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.audio = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        this.listener = listener;
    }

    static boolean hasNotificationAccess(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(ctx, MediaListenerService.class).flattenToString());
    }

    void start() {
        if (started) return;
        started = true;
        main.post(poll);
        IntentFilter f = new IntentFilter();
        f.addAction("com.spotify.music.metadatachanged");
        f.addAction("com.spotify.music.playbackstatechanged");
        registerSpotify(f);
        if (hasNotificationAccess(ctx)) {
            try {
                msm = (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                ComponentName me = new ComponentName(ctx, MediaListenerService.class);
                msm.addOnActiveSessionsChangedListener(sessionsChanged, me, main);
                pick(msm.getActiveSessions(me));
            } catch (SecurityException e) {
                Log.w(TAG, "media sessions not accessible", e);
                msm = null;
            }
        }
    }

    /** Spotify broadcasts from another app, so the receiver has to be exported. */
    @SuppressLint({"UnspecifiedRegisterReceiverFlag", "InlinedApi"})
    private void registerSpotify(IntentFilter f) {
        // The flags overload exists since API 26; RECEIVER_EXPORTED is only read (and required) from API 33.
        if (Build.VERSION.SDK_INT >= 26) ctx.registerReceiver(spotify, f, Context.RECEIVER_EXPORTED);
        else ctx.registerReceiver(spotify, f);
    }

    /**
     * Whether music is playing: the media session's state when we have one, else Spotify's own
     * broadcast, else Android's "music stream active", which needs no permission at all.
     */
    boolean isPlaying() {
        if (controller != null) return active(controller.getPlaybackState());
        if (spotifyStateSeen) return spotifyPlaying;
        return audio != null && audio.isMusicActive();
    }

    /** Buffering and skipping are still "playing" from the listener's point of view. */
    private static boolean active(PlaybackState st) {
        if (st == null) return false;
        switch (st.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    /** Without session callbacks nothing reports changes, so look once a second. */
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            notifyPlaying();
            if (started) main.postDelayed(this, 1000);
        }
    };

    /** After a command: report the real state even if it equals what we last said. */
    private final Runnable recheck = new Runnable() {
        @Override
        public void run() {
            lastPlaying = isPlaying();
            listener.onPlayback(lastPlaying);
        }
    };

    void stop() {
        if (!started) return;
        started = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(recheck);
        try {
            ctx.unregisterReceiver(spotify);
        } catch (IllegalArgumentException ignored) {
        }
        if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsChanged);
        msm = null;
        setController(null);
    }

    /** Prefers a playing session, Spotify first. */
    private void pick(List<MediaController> list) {
        if (msm == null) return;
        if (list == null) {
            try {
                list = msm.getActiveSessions(new ComponentName(ctx, MediaListenerService.class));
            } catch (SecurityException e) {
                return;
            }
        }
        MediaController best = null;
        int bestScore = -1;
        for (MediaController c : list) {
            // Playing wins; otherwise stay with the session we have (pausing a podcast must not
            // hand the controls to a paused Spotify); Spotify breaks ties.
            boolean current = controller != null && controller.getSessionToken().equals(c.getSessionToken());
            int score = (active(c.getPlaybackState()) ? 4 : 0) + (current ? 2 : 0)
                    + ("com.spotify.music".equals(c.getPackageName()) ? 1 : 0);
            if (score > bestScore) {
                best = c;
                bestScore = score;
            }
        }
        if (best == null) {
            setController(null);
            return;
        }
        if (controller == null || !controller.getSessionToken().equals(best.getSessionToken())) {
            setController(best);
            publish(best.getMetadata());
        }
    }

    private void setController(MediaController c) {
        if (controller != null) controller.unregisterCallback(callback);
        controller = c;
        if (c != null) c.registerCallback(callback, main);
        notifyPlaying();
    }

    private void notifyPlaying() {
        boolean p = isPlaying();
        if (p == lastPlaying) return;
        lastPlaying = p;
        listener.onPlayback(p);
    }

    // ------------------------------------------------------------------ transport

    /**
     * Toggles from the state the user sees and returns the state asked for. The answer is
     * shown at once; 1.5 s later it is corrected if the player didn't follow.
     */
    boolean playPause() {
        boolean target = !lastPlaying;
        MediaController c = controller;
        if (c != null) {
            if (target) c.getTransportControls().play();
            else c.getTransportControls().pause();
        } else {
            // explicit keys, so a wrong guess can't turn "pause" into "play"
            sendKey(target ? KeyEvent.KEYCODE_MEDIA_PLAY : KeyEvent.KEYCODE_MEDIA_PAUSE);
        }
        lastPlaying = target;
        listener.onPlayback(target);
        main.removeCallbacks(recheck);
        main.postDelayed(recheck, 1500);
        return target;
    }

    void next() {
        if (controller != null) controller.getTransportControls().skipToNext();
        else sendKey(KeyEvent.KEYCODE_MEDIA_NEXT);
    }

    void previous() {
        if (controller != null) controller.getTransportControls().skipToPrevious();
        else sendKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /** A media button press, delivered by Android to whichever player is active (or was last). */
    private void sendKey(int code) {
        if (audio == null) return;
        long t = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    private void publish(MediaMetadata m) {
        if (m == null) return;
        String title = m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        if (title != null) deliver(title, artist, art);
    }

    private void deliver(String title, String artist, Bitmap art) {
        String key = title + "\u0000" + artist + "\u0000" + (art != null);
        if (key.equals(lastKey)) return;
        lastKey = key;
        listener.onTrack(title, artist == null ? "" : artist, art);
    }
}
