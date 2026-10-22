package io.github.christianernst.tav;

/**
 * How hard the scenes react to the music: a master amount, a gain per frequency band, punch (how
 * much of the loud-and-quiet dynamics survives the analyzer's auto-gain) and the beat's strength.
 * Plain arithmetic on the analyzer's outputs, so EqualizerCheck tests it on the JVM. Immutable: the
 * UI makes a new one for every change and hands it to the render thread.
 */
final class Equalizer {
    static final int BANDS = 4;
    static final String[] BAND_NAMES = {"Bass", "Low mids", "High mids", "Treble"};
    /** Scenes read the bands as 0..1; boosting past that is allowed a little, not without limit. */
    static final float LIMIT = 1.6f;

    final float master, punch, beatGain;
    final float[] gain;

    Equalizer(float master, float[] gain, float punch, float beatGain) {
        this.master = master;
        this.gain = gain.clone();
        this.punch = punch;
        this.beatGain = beatGain;
    }

    /** Off the analyzer's auto-gain: the look the app had before the equalizer. */
    static Equalizer flat() {
        return new Equalizer(1f, new float[]{1f, 1f, 1f, 1f}, 0f, 1f);
    }

    /** The default: a stronger contrast between loud and quiet, and a bigger beat. */
    static Equalizer punch() {
        return new Equalizer(1f, new float[]{1f, 1f, 1f, 1f}, 0.7f, 1.3f);
    }

    /**
     * out[b] = master x gain[b] x the band, blended from the normalised level (norm) toward the
     * absolute one (abs) by punch.
     */
    void shapeBands(float[] norm, float[] abs, float[] out) {
        for (int b = 0; b < BANDS; b++) out[b] = clamp(master * gain[b] * ((1 - punch) * norm[b] + punch * abs[b]));
    }

    float shapeLevel(float level) {
        return clamp(master * level);
    }

    /** The beat pulse, 0..1 from the analyzer, scaled by master and beat. */
    float shapeBeat(float pulse) {
        return clamp(master * beatGain * pulse);
    }

    /** Brightness of the white lift on a drop, scaled like the beat and kept below full white. */
    float dropFlash() {
        return Math.min(0.6f * master * beatGain, 1f);
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : Math.min(LIMIT, v);
    }
}
