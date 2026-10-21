package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.List;

/**
 * Follows what is playing. Two sources:
 * <ol>
 * <li>Media sessions (needs notification access): title, artist and album art from
 * Spotify or any other player.</li>
 * <li>Spotify's own broadcasts (needs "Device Broadcast Status" switched on in
 * Spotify's settings): title and artist only, but no extra permission.</li>
 * </ol>
 */
final class MediaWatcher {
    interface Listener {
        void onTrack(String title, String artist, Bitmap art);
    }

    private static final String TAG = "TAV";
    private final Context ctx;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private String lastKey = "";
    private boolean started, spotifyPlaying;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            publish(metadata);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (msm != null) pick(null);
        }
    };

    private final BroadcastReceiver spotify = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // Any app can send these actions to an exported receiver: a malformed bundle must not
            // crash us, and only plain, short strings are accepted.
            String title, artist;
            try {
                if ("com.spotify.music.playbackstatechanged".equals(i.getAction()))
                    spotifyPlaying = i.getBooleanExtra("playing", spotifyPlaying);
                title = i.getStringExtra("track");
                artist = i.getStringExtra("artist");
            } catch (RuntimeException e) {
                return;
            }
            if (controller != null) return;   // media session has richer data
            if (title != null && title.length() <= 300 && (artist == null || artist.length() <= 300))
                deliver(title, artist, null);
        }
    };

    MediaWatcher(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    static boolean hasNotificationAccess(Context ctx) {
        String flat = Settings.Secure.getString(ctx.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(new ComponentName(ctx, MediaListenerService.class).flattenToString());
    }

    void start() {
        if (started) return;
        started = true;
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

    /** Whether something is known to be playing right now (false when we can't tell). */
    boolean isPlaying() {
        if (controller != null) {
            PlaybackState st = controller.getPlaybackState();
            return st != null && st.getState() == PlaybackState.STATE_PLAYING;
        }
        return spotifyPlaying;
    }

    void stop() {
        if (!started) return;
        started = false;
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
            PlaybackState st = c.getPlaybackState();
            boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
            int score = (playing ? 2 : 0) + ("com.spotify.music".equals(c.getPackageName()) ? 1 : 0);
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
