package io.github.christianernst.tav;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;

import java.util.Collection;

/**
 * The quiet "music started" nudge: when Spotify (or, if the user wants, any player) starts
 * playing and TAV is not on screen, a silent notification offers to light it up. It is the
 * playbook's opt-in media-session trigger, with its rate limits:
 * <ul>
 * <li>never while TAV is visible, and taken down again as soon as the music stops;</li>
 * <li>one notification per listening session (a track change only updates it);</li>
 * <li>a swipe-away keeps it quiet for {@link #QUIET_AFTER_DISMISS_MS}.</li>
 * </ul>
 * The channel is low importance: a status-bar icon and a line in the shade, no sound, no pop-up.
 */
final class NowPlayingNudge {
    /** Setting (prefs key {@code nudge}): off, Spotify only, any player. */
    static final int OFF = 0, SPOTIFY = 1, ANY = 2;
    static final String PREF = "nudge";
    static final String SPOTIFY_PACKAGE = "com.spotify.music";
    static final String CHANNEL = "nudge";
    static final int ID = 41;
    static final long QUIET_AFTER_DISMISS_MS = 60 * 60 * 1000L;
    private static final String PREF_DISMISSED = "nudge_dismissed";
    private static final String ACTION_DISMISSED = "io.github.christianernst.tav.NUDGE_DISMISSED";

    /** Set by MainActivity between onStart and onStop; the nudge is pointless while TAV shows. */
    static volatile boolean tavVisible;
    /** The listening session the current notification belongs to (player package), or null. */
    private static String shownFor;

    private NowPlayingNudge() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("tav", Context.MODE_PRIVATE);
    }

    /** Whether notifications may be posted at all (POST_NOTIFICATIONS is a runtime permission from API 33). */
    static boolean allowed(Context ctx) {
        if (Build.VERSION.SDK_INT >= 33
                && ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
            return false;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && (Build.VERSION.SDK_INT < 24 || nm.areNotificationsEnabled());
    }

    /** Called with the active sessions whenever any of them changes state or track. */
    static void update(Context ctx, Collection<MediaController> sessions) {
        int mode = prefs(ctx).getInt(PREF, OFF);
        MediaController playing = mode == OFF ? null : playing(sessions, mode == SPOTIFY);
        if (playing == null || tavVisible || !allowed(ctx)) {
            cancel(ctx);
            return;
        }
        String pkg = playing.getPackageName();
        boolean fresh = !pkg.equals(shownFor);   // a new listening session, not a track change
        long quietUntil = prefs(ctx).getLong(PREF_DISMISSED, 0) + QUIET_AFTER_DISMISS_MS;
        if (fresh && System.currentTimeMillis() < quietUntil) return;
        post(ctx, playing, fresh);
        shownFor = pkg;
    }

    static MediaController playing(Collection<MediaController> sessions, boolean spotifyOnly) {
        for (MediaController c : sessions) {
            if (spotifyOnly && !SPOTIFY_PACKAGE.equals(c.getPackageName())) continue;
            PlaybackState st = c.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) return c;
        }
        return null;
    }

    private static void post(Context ctx, MediaController c, boolean fresh) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        MediaMetadata m = c.getMetadata();
        String title = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = m == null ? null : m.getString(MediaMetadata.METADATA_KEY_ARTIST);
        Bitmap art = null;
        if (m != null) {
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        String player = SPOTIFY_PACKAGE.equals(c.getPackageName()) ? "Spotify" : "Music";
        String head = title == null || title.isEmpty() ? player + " is playing" : "♪  " + title;
        String body = (artist == null || artist.isEmpty() ? "" : artist + "  ·  ") + "Tap to light it up";

        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Intent open = new Intent(ctx, MainActivity.class)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .putExtra("from_nudge", true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent tap = PendingIntent.getActivity(ctx, 1, open, flags);
        Intent gone = new Intent(ctx, Dismissed.class).setAction(ACTION_DISMISSED);
        PendingIntent swiped = PendingIntent.getBroadcast(ctx, 2, gone, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Music started", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("A quiet offer to light up the music when a player starts");
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(ctx, CHANNEL);
        } else {
            b = new Notification.Builder(ctx).setPriority(Notification.PRIORITY_LOW);
        }
        b.setSmallIcon(R.drawable.ic_nudge)
                .setColor(0xFFFF2E8C)
                .setContentTitle(head)
                .setContentText(body)
                .setContentIntent(tap)
                .setDeleteIntent(swiped)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_RECOMMENDATION);
        if (art != null) b.setLargeIcon(art);
        try {
            nm.notify(ID, b.build());
        } catch (RuntimeException e) {   // notifications switched off between the check and the post
            if (fresh) shownFor = null;
        }
    }

    static void cancel(Context ctx) {
        shownFor = null;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ID);
    }

    /** A swipe-away: stay quiet for an hour, whatever starts playing. */
    public static final class Dismissed extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent i) {
            if (!ACTION_DISMISSED.equals(i.getAction())) return;
            shownFor = null;
            prefs(ctx).edit().putLong(PREF_DISMISSED, System.currentTimeMillis()).apply();
        }
    }
}
