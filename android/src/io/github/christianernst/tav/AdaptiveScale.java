package io.github.christianernst.tav;

/**
 * Chooses the render resolution from frame times. Sustained slow frames step the scale down;
 * sustained fast frames step it back up, so a phone that was throttled for a while gets its
 * sharpness back. Frames that compiled a shader are ignored: that stall says nothing about the
 * GPU, and counting it would lower the resolution for good during start-up.
 *
 * The scale may start below 1 (Quality: Auto starts at {@link #AUTO_START} and climbs to the
 * sharpest the phone holds). When a step up is undone by a step down soon after, the next climb
 * waits twice as long, so a phone on the edge settles instead of flickering between two sizes.
 * The same logic, with the same numbers, is {@code AdaptiveScale} in web/engine.js.
 */
final class AdaptiveScale {
    static final float AUTO_START = 0.7f;
    /** Under this frame rate a frame counts as slow, on any display: 42 fps is where motion starts to stutter. */
    private static final float SLOW = 1f / 42;
    /**
     * A frame counts as fast when it takes at most 1.09 vsync periods of a 60..90 Hz display: 55 fps on a
     * 60 Hz panel, 83 fps on a 90 or 120 Hz one. On a 120 Hz phone a steady 60 fps is smooth but says
     * nothing about headroom, so it neither climbs nor drops; only frames with real headroom climb.
     */
    private static final float FAST_PERIODS = 1.09f, REFRESH_MIN = 60f, REFRESH_MAX = 90f;
    private static final float DROP_AFTER = 2.5f, RAISE_AFTER = 15f, RAISE_MAX = 120f, UNDONE_WITHIN = 30f, STEP = 0.85f;
    private float fastLimit = FAST_PERIODS / REFRESH_MIN;
    private float scale = 1f, slow, fast;
    private float raiseAfter = RAISE_AFTER, sinceRaise = Float.MAX_VALUE;

    float scale() {
        return scale;
    }

    void reset() {
        reset(1f);
    }

    /** The display's refresh rate in Hz (Display.getRefreshRate()); sets what counts as a fast frame. */
    void setRefreshRate(float hz) {
        if (!(hz > 0)) return;
        fastLimit = FAST_PERIODS / Math.max(REFRESH_MIN, Math.min(REFRESH_MAX, hz));
    }

    /** Seconds a frame may take and still count as fast. */
    float fastLimit() {
        return fastLimit;
    }

    /** Starts over at the given scale (1 = full), with the climb wait back at its shortest. */
    void reset(float start) {
        scale = Math.max(0.1f, Math.min(1f, start));
        slow = fast = 0;
        raiseAfter = RAISE_AFTER;
        sinceRaise = Float.MAX_VALUE;
    }

    /**
     * One frame: dt seconds since the last one; stalled when the frame compiled a shader;
     * canDrop when a lower scale is still allowed. Returns true when the scale changed.
     */
    boolean update(float dt, boolean stalled, boolean canDrop) {
        if (stalled) return false;
        if (sinceRaise < Float.MAX_VALUE) sinceRaise += dt;
        if (dt > SLOW) {
            slow += dt;
            fast = 0;
        } else {
            slow = Math.max(0, slow - dt * 0.5f);
            fast = dt < fastLimit ? fast + dt : 0;
        }
        if (slow > DROP_AFTER && canDrop) {
            slow = 0;
            scale *= STEP;
            // a climb that didn't hold: wait longer before the next one
            if (sinceRaise < UNDONE_WITHIN) raiseAfter = Math.min(RAISE_MAX, raiseAfter * 2);
            sinceRaise = Float.MAX_VALUE;
            return true;
        }
        if (fast > raiseAfter && scale < 1f) {
            fast = 0;
            scale = Math.min(1f, scale / STEP);
            sinceRaise = 0;
            return true;
        }
        return false;
    }
}
