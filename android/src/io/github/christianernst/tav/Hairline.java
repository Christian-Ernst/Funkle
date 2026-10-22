package io.github.christianernst.tav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.View;

/**
 * A 2 dp playback-progress line under the song title. It is fed once a second and moves on
 * its own in between, so it glides instead of stepping. Hidden (GONE) while the length is unknown.
 */
final class Hairline extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private long pos, dur, at;
    private float speed;

    Hairline(Context ctx) {
        super(ctx);
        track.setColor(0x40FFFFFF);
        fill.setColor(0xE6FFFFFF);
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void set(long positionMs, long durationMs, float playbackSpeed) {
        pos = positionMs;
        dur = durationMs;
        speed = playbackSpeed;
        at = SystemClock.elapsedRealtime();
        setVisibility(dur > 0 ? VISIBLE : GONE);
        invalidate();
    }

    void clear() {
        dur = 0;
        setVisibility(GONE);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (dur <= 0) return;
        long now = pos + (speed == 0f ? 0 : (long) ((SystemClock.elapsedRealtime() - at) * speed));
        float f = Math.max(0f, Math.min(1f, now / (float) dur));
        float h = getHeight(), w = getWidth(), r = h / 2f;
        c.drawRoundRect(0, 0, w, h, r, r, track);
        if (f > 0f) c.drawRoundRect(0, 0, Math.max(h, w * f), h, r, r, fill);
        if (speed != 0f && isShown()) postInvalidateDelayed(200);
    }
}
