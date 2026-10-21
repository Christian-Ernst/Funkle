package io.github.christianernst.tav;

import android.service.notification.NotificationListenerService;

/**
 * Exists only so the user can grant notification access. With that access Android
 * lets TAV read the active media sessions (title, artist, album art) — the art is
 * what the "Album" palette is made of. No notifications are read or stored.
 */
public final class MediaListenerService extends NotificationListenerService {
}
