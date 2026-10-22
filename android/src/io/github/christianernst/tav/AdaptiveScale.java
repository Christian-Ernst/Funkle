package io.github.christianernst.tav;

/**
 * Chooses the render resolution from frame times. Sustained slow frames step the scale down;
 * sustained fast frames step it back up, so a phone that was throttled for a while gets its
 * sharpness back. Frames that compiled a shader are ignored: that stall says nothing about the
 * GPU, and counting it would lower the resolution for good during start-up.
 */
final class AdaptiveScale {
    private static final float SLOW = 1f / 42, FAST = 1f / 55;
    private static final float DROP_AFTER = 2.5f, RAISE_AFTER = 15f, STEP = 0.85f;
    private float scale = 1f, slow, fast;

    float scale() {
        return scale;
    }

    void reset() {
        scale = 1f;
        slow = fast = 0;
    }

    /**
     * One frame: dt seconds since the last one; stalled when the frame compiled a shader;
     * canDrop when a lower scale is still allowed. Returns true when the scale changed.
     */
    boolean update(float dt, boolean stalled, boolean canDrop) {
        if (stalled) return false;
        if (dt > SLOW) {
            slow += dt;
            fast = 0;
        } else {
            slow = Math.max(0, slow - dt * 0.5f);
            fast = dt < FAST ? fast + dt : 0;
        }
        if (slow > DROP_AFTER && canDrop) {
            slow = 0;
            scale *= STEP;
            return true;
        }
        if (fast > RAISE_AFTER && scale < 1f) {
            fast = 0;
            scale = Math.min(1f, scale / STEP);
            return true;
        }
        return false;
    }
}
