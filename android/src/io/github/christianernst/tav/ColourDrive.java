package io.github.christianernst.tav;

/**
 * Colour motion: how the palette moves with the music. The hue drifts with the music clock, steps
 * on every beat (and jumps on a drop), and the loud bands pull the colours toward their own colour
 * (tilt: bass toward colour 1, mids toward colour 2, treble toward colour 3) and light it up (pump).
 * The settings are immutable; {@link State} is what a frame carries. Plain arithmetic, so
 * ColourDriveCheck tests it on the JVM. The same code lives in {@code web/engine.js}.
 */
final class ColourDrive {
    static final String[] MODE_NAMES = {"Still", "Drift", "Beat", "Bands", "Custom"};
    static final int STILL = 0, BEAT = 2, CUSTOM = 4;
    /** Beat: the colours move with every beat, which is what the feature is for; Still is one tap away. */
    static final int DEFAULT = BEAT;
    /** The slider ranges in the custom card: turns per second, turns per beat, turns per drop, 0..1, 0..1. */
    static final float[] MAX = {0.1f, 0.25f, 0.5f, 1f, 1f};
    static final String[] NAMES = {"Drift", "Beat step", "Drop jump", "Band tilt", "Band pump"};
    /** How fast the hue follows a step: 1 - e^-10 per second, so a beat step is done in about 0.3 s. */
    private static final float EASE = 10f;

    final float drift, beatStep, dropStep, tilt, pump;

    ColourDrive(float drift, float beatStep, float dropStep, float tilt, float pump) {
        this.drift = drift;
        this.beatStep = beatStep;
        this.dropStep = dropStep;
        this.tilt = tilt;
        this.pump = pump;
    }

    /** The menu modes; Custom starts out as Beat. */
    static ColourDrive preset(int mode) {
        switch (mode) {
            case 1: return new ColourDrive(0.02f, 0f, 0.1f, 0.3f, 0f);
            case 2: case CUSTOM: return new ColourDrive(0f, 1f / 16f, 1f / 3f, 0.3f, 0f);
            case 3: return new ColourDrive(0f, 0f, 0f, 0.8f, 0.4f);
            default: return new ColourDrive(0f, 0f, 0f, 0f, 0f);
        }
    }

    boolean still() {
        return drift == 0 && beatStep == 0 && dropStep == 0 && tilt == 0 && pump == 0;
    }

    float amount(int i) {
        return i == 0 ? drift : i == 1 ? beatStep : i == 2 ? dropStep : i == 3 ? tilt : pump;
    }

    ColourDrive with(int i, float v) {
        return new ColourDrive(i == 0 ? v : drift, i == 1 ? v : beatStep, i == 2 ? v : dropStep, i == 3 ? v : tilt, i == 4 ? v : pump);
    }

    /** The moving part: the hue in turns (what the shader gets) and the smoothed band shares. */
    static final class State {
        float hue, hueTarget;
        final float[] mix = new float[3];
    }

    /**
     * Advances the state by dt seconds. bands are the four (shaped) bands, level 0..1, onBeat and
     * drop are the analyzer's flags for this frame.
     */
    void step(State s, float dt, float[] bands, float level, boolean onBeat, boolean drop) {
        s.hueTarget += drift * dt * (0.3f + 0.7f * Math.min(level, 1f));
        if (onBeat) s.hueTarget += beatStep;
        if (drop) s.hueTarget += dropStep;
        // no hue motion at all (Still, Bands): ease back to the chosen colours by the shortest way
        if (drift == 0 && beatStep == 0 && dropStep == 0) s.hueTarget = Math.round(s.hue);
        s.hue += (s.hueTarget - s.hue) * (1 - (float) Math.exp(-dt * EASE));
        float wrap = (float) Math.floor(s.hueTarget);   // keep both small; only the difference matters
        s.hueTarget -= wrap;
        s.hue -= wrap;
        if (tilt == 0 && pump == 0) {
            for (int k = 0; k < 3; k++) s.mix[k] = 0f;
            return;
        }
        float b0 = Math.max(bands[0], 0f), b1 = Math.max(0.5f * (bands[1] + bands[2]), 0f), b2 = Math.max(bands[3], 0f);
        float share = 1 / (b0 + b1 + b2 + 0.5f);   // silence counts as nothing, a full band as most of it
        float[] w = {b0 * share, b1 * share, b2 * share};
        for (int k = 0; k < 3; k++) {
            float rate = w[k] > s.mix[k] ? 14f : 5f;   // quick to light up, slower to let go
            s.mix[k] += (w[k] - s.mix[k]) * (1 - (float) Math.exp(-dt * rate));
        }
    }
}
