package io.github.christianernst.tav;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The notification-access hook. Granting it lets Android hand TAV the active media sessions
 * (title, artist, album art, play state) from Spotify or any other player. No notifications
 * are read or stored; the service only watches the media sessions and, when the user has
 * switched it on, posts the {@link NowPlayingNudge} once music starts while TAV is closed.
 */
public final class MediaListenerService extends NotificationListenerService {
    private static final String TAG = "TAV";
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<MediaSession.Token, MediaController> watched = new HashMap<>();
    private MediaSessionManager msm;

    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::sessions;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            evaluate();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            evaluate();
        }

        @Override
        public void onSessionDestroyed() {
            refresh();
        }
    };

    @Override
    public void onListenerConnected() {
        msm = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (msm == null) return;
        try {
            msm.addOnActiveSessionsChangedListener(changed, me(), main);
        } catch (SecurityException e) {   // access revoked between the bind and this call
            Log.w(TAG, "media sessions not accessible", e);
            msm = null;
            return;
        }
        refresh();
    }

    @Override
    public void onListenerDisconnected() {
        if (msm != null) msm.removeOnActiveSessionsChangedListener(changed);
        msm = null;
        for (MediaController c : watched.values()) c.unregisterCallback(callback);
        watched.clear();
        NowPlayingNudge.cancel(this);
    }

    private ComponentName me() {
        return new ComponentName(this, MediaListenerService.class);
    }

    private void refresh() {
        if (msm == null) return;
        try {
            sessions(msm.getActiveSessions(me()));
        } catch (SecurityException e) {
            Log.w(TAG, "media sessions not accessible", e);
        }
    }

    /** Follows every active session so a play/pause or a new track reaches {@link #evaluate}. */
    private void sessions(List<MediaController> list) {
        Map<MediaSession.Token, MediaController> now = new HashMap<>();
        if (list != null) for (MediaController c : list) now.put(c.getSessionToken(), c);
        for (Map.Entry<MediaSession.Token, MediaController> e : new ArrayList<>(watched.entrySet())) {
            if (now.containsKey(e.getKey())) continue;
            e.getValue().unregisterCallback(callback);
            watched.remove(e.getKey());
        }
        for (Map.Entry<MediaSession.Token, MediaController> e : now.entrySet()) {
            if (watched.containsKey(e.getKey())) continue;
            e.getValue().registerCallback(callback, main);
            watched.put(e.getKey(), e.getValue());
        }
        evaluate();
    }

    private void evaluate() {
        NowPlayingNudge.update(this, watched.values());
    }
}
