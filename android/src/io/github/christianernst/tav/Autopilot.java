package io.github.christianernst.tav;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The music-aware autopilot: picks scenes by the track's energy, holds through a build-up, switches
 * on the downbeat of the bar after a drop, marks section changes with new colours and weights the
 * scenes the user keeps. Plain arithmetic over the analyzer's per-frame values, so AutopilotCheck
 * runs it on the JVM; {@code web/engine.js} has the same class with the same numbers.
 *
 * <p>The scene list is data: the director is built from the scene ids, and {@link #MOODS} says which
 * energy each scene suits. A scene without an entry suits everything, so a new scene joins the
 * rotation without a change here.
 */
final class Autopilot {
    static final String[] MODE_NAMES = {"Off", "Scenes", "Scenes + colours"};
    static final int OFF = 0, SCENES = 1, FULL = 2;
    /** Full: the colours mark the sections too. A new install has Album colours, which it leaves alone. */
    static final int DEFAULT = FULL;

    /** Energy range (0..1) a scene suits best, by scene id. Outside it the fit falls off over 0.35. */
    static final Object[][] MOODS = {
            {"halo", 0.0f, 0.65f},
            {"tunnel", 0.35f, 1.0f},
            {"pulsar", 0.5f, 1.0f},
            {"kaleido", 0.2f, 0.85f},
            {"orb", 0.0f, 0.5f},
            {"nebula", 0.0f, 0.6f},
            {"spiro", 0.3f, 0.9f},
            {"fluid", 0.2f, 0.9f},
            {"landscape", 0.1f, 0.7f},
    };
    /** Palette families by energy, by palette name (the host maps a name to its own index). */
    static final String[] PAL_LOW = {"Ice", "Ocean", "Aurora", "Vapor"},
            PAL_MID = {"Neon", "Sunset", "Aurora", "Candy", "Vapor"},
            PAL_HIGH = {"Inferno", "Neon", "Candy", "Toxic", "Rainbow"};
    /** Colour motion modes (ColourDrive.MODE_NAMES indices) by energy: Drift, Beat, Bands. */
    static final int MOTION_LOW = 1, MOTION_MID = 2, MOTION_HIGH = 3;
    static final float LOW_ENERGY = 0.3f, HIGH_ENERGY = 0.65f, FAST_BPM = 125f;

    /** What the director wants shown: a scene (or -1), a palette name (or null), a colour mode (or -1). */
    static final class Look {
        int scene = -1, colourMode = -1;
        String palette;
        /** drop, track, section, energy or time. */
        String reason;
        /** Crossfade seconds: short on a drop, long on a quiet section change. */
        float fade;
        float energy, bpm;
    }

    private final String[] ids;
    private final float[] lo, hi, weight;
    private final Random rnd;
    private int mode = DEFAULT;
    private boolean coloursLocked;

    // the music
    private float energy, energyFast, buildFor, sinceBeat = 10, beatIv;
    private final float[] fast = new float[3], slow = new float[3];
    private float diffFor, sinceSection, misfitFor;
    private boolean building, sectionNow, wasSilent = true;
    private int sections;
    // drops: wait for the downbeat of the next bar
    private boolean dropArmed;
    private int dropBeats;
    private float dropWait;
    // the look
    private int scene = -1, lastColourMode = -1;
    private String lastPalette;
    private float sinceChange = 20, trackAge;   // at start a misfit may be fixed soon, a fit stays a while
    private boolean sceneHeld, palettePinned, trackPending, autoScene;
    private final List<Integer> recent = new ArrayList<>();
    private String pending;
    private float pendingWait;

    Autopilot(String[] sceneIds, long seed) {
        ids = sceneIds;
        int n = ids.length;
        lo = new float[n];
        hi = new float[n];
        weight = new float[n];
        for (int i = 0; i < n; i++) {
            float[] m = mood(ids[i]);
            lo[i] = m[0];
            hi[i] = m[1];
            weight[i] = 1f;
        }
        rnd = new Random(seed);
    }

    static float[] mood(String id) {
        for (Object[] m : MOODS) if (m[0].equals(id)) return new float[]{(Float) m[1], (Float) m[2]};
        return new float[]{0f, 1f};
    }

    void setMode(int m) {
        mode = Math.max(OFF, Math.min(FULL, m));
        pending = null;
        if (mode == OFF) dropArmed = false;
    }

    int mode() {
        return mode;
    }

    /** The user's own palette is Album or Custom: leave the colours alone, only the colour motion moves. */
    void setColoursLocked(boolean locked) {
        coloursLocked = locked;
    }

    // ------------------------------------------------------------------ what the user does

    /** The user picked a scene: hold it through this section, and remember they like it. */
    void onUserScene(int i) {
        if (autoScene && scene >= 0 && scene != i && sinceChange < 8) weight[scene] = Math.max(0.3f, weight[scene] * 0.7f);
        if (i >= 0 && i < weight.length) weight[i] = Math.min(3f, weight[i] * 1.3f);
        scene = i;
        autoScene = false;
        sceneHeld = true;
        sinceChange = 0;
        pending = null;
    }

    /** The scene changed without the user asking for it (a restore at start-up, a broken shader). */
    void onScene(int i) {
        scene = i;
        autoScene = false;
    }

    /** The user chose colours: they stay for the rest of this track. */
    void onUserPalette() {
        palettePinned = true;
        lastPalette = null;
    }

    /** The playing track changed: a new look on the next beat. */
    void onTrack() {
        trackAge = 0;
        palettePinned = false;
        sceneHeld = false;
        dropArmed = false;
        if (mode != OFF) trackPending = true;
    }

    // ------------------------------------------------------------------ the music, per frame

    /**
     * One frame. bands and level are the analyzer's auto-gained values, intensity its 0..1 energy,
     * the flags its beat and drop for this frame. usable says which scenes may be shown (null: all).
     * Returns the look to apply, or null.
     */
    Look step(float dt, float[] bands, float level, float intensity, boolean onBeat, boolean drop, boolean silent, boolean[] usable) {
        sinceChange += dt;
        sinceSection += dt;
        trackAge += dt;
        sinceBeat += dt;
        sectionNow = false;
        if (silent) {
            buildFor = 0;
            building = false;
            misfitFor = 0;
            wasSilent = true;
            return null;
        }

        // energy: the slow value is the section's, the fast one hears a build-up coming
        energy += (intensity - energy) * (1 - (float) Math.exp(-dt / 3));
        energyFast += (intensity - energyFast) * (1 - (float) Math.exp(-dt / 0.8));
        boolean rising = energyFast - energy > 0.06f && energyFast > 0.25f;
        buildFor = rising ? buildFor + dt : Math.max(0, buildFor - 2 * dt);
        building = buildFor > 1.5f;

        // tempo: every beat interval votes, as one, two, three or four beats (or half a beat), in a
        // decaying histogram over 75..200 bpm; the fullest bin is the tempo. Missed and doubled beats
        // then only add votes for the same tempo.
        if (onBeat) {
            if (sinceBeat > 0.2f && sinceBeat < 3f) {
                for (int b = 0; b < TEMPO_BINS; b++) tempo[b] *= 0.96f;
                vote(sinceBeat * 2, 0.5f);
                for (int k = 1; k <= 4; k++) vote(sinceBeat / k, 1f);
                int top = 0;
                float all = 0;
                for (int b = 0; b < TEMPO_BINS; b++) {
                    all += tempo[b];
                    if (tempo[b] > tempo[top]) top = b;
                }
                if (tempo[top] > 2f && tempo[top] > 0.25f * all) {   // a real consensus, not scattered onsets
                    float sum = 0, w = 0;
                    for (int b = Math.max(0, top - 1); b <= Math.min(TEMPO_BINS - 1, top + 1); b++) {
                        sum += tempo[b] * (TEMPO_MIN + (b + 0.5f) * TEMPO_BIN);
                        w += tempo[b];
                    }
                    beatIv = sum / w;
                }
            }
            sinceBeat = 0;
        }

        // sections: where the mix sits now against where it sat for the last while
        float sum = Math.max(bands[0], 0) + Math.max(bands[1], 0) + Math.max(bands[2], 0) + Math.max(bands[3], 0) + 0.3f;
        float[] f = {intensity, 1.5f * Math.max(bands[3], 0) / sum, 1.5f * Math.max(bands[0], 0) / sum};
        if (wasSilent) {   // sound after silence: that is where the section starts, not a change
            System.arraycopy(f, 0, fast, 0, 3);
            System.arraycopy(f, 0, slow, 0, 3);
            wasSilent = false;
            sinceSection = 0;
        }
        float dist = 0;
        for (int k = 0; k < 3; k++) {
            fast[k] += (f[k] - fast[k]) * (1 - (float) Math.exp(-dt / 1.5));
            slow[k] += (f[k] - slow[k]) * (1 - (float) Math.exp(-dt / 8));
            dist += Math.abs(fast[k] - slow[k]);
        }
        diffFor = dist > 0.18f ? diffFor + dt : 0;
        if (diffFor > 1.5f && sinceSection > 12) {
            sectionNow = true;
            sections++;
            sinceSection = 0;
            diffFor = 0;
            System.arraycopy(fast, 0, slow, 0, 3);
            sceneHeld = false;
        }

        if (mode == OFF) return null;

        // a drop: let the scene do its own drop move, then switch on the downbeat of the next bar
        if (drop) {
            dropArmed = true;
            dropBeats = 0;
            dropWait = 0;
        }
        if (dropArmed) {
            dropWait += dt;
            if (onBeat && dropWait > 0.05f) dropBeats++;
            if (dropBeats >= 4 || dropWait > Math.max(1.6f, 4 * beatIv + 0.3f)) {
                dropArmed = false;
                if (sinceChange > 10) want("drop");
            }
        }

        // a wanted change waits for the next beat, so the crossfade lands on the music
        if (pending != null) {
            pendingWait += dt;
            if (onBeat || pendingWait > 0.8f || (beatIv == 0 && pendingWait > 0.3f)) return make(usable);
            return null;
        }

        boolean fits = scene >= 0 && scene < ids.length && fit(scene, energy) > 0.25f;
        misfitFor = fits ? 0 : misfitFor + dt;
        if (trackPending && trackAge > 2) want("track");
        else if (sectionNow && sinceChange > 15 && !building) want("section");
        else if (!sceneHeld && !building && sinceChange > 20 && misfitFor > 6) want("energy");
        else if (!sceneHeld && !building && sinceChange > 45 + 45 * (1 - energy)) want("time");
        return null;
    }

    private void want(String reason) {
        pending = reason;
        pendingWait = 0;
    }

    private Look make(boolean[] usable) {
        String reason = pending;
        pending = null;
        trackPending = false;
        Look look = new Look();
        look.reason = reason;
        look.energy = energy;
        look.bpm = bpm();
        look.fade = "drop".equals(reason) ? 0.7f : "track".equals(reason) ? 1.2f : "section".equals(reason) ? 1.4f : "energy".equals(reason) ? 1.6f : 1.8f;
        // a drop or a new section is judged by what plays now, the rest by the section so far
        float e = "drop".equals(reason) ? Math.max(energy, energyFast) : "section".equals(reason) || "track".equals(reason) ? energyFast : energy;
        int next = pick(e, usable);
        if (next >= 0 && next != scene) {
            look.scene = next;
            scene = next;
            autoScene = true;
            sceneHeld = false;
            sinceChange = 0;
            misfitFor = 0;
            recent.add(next);
            if (recent.size() > 2) recent.remove(0);
        }
        if (mode == FULL && !"energy".equals(reason) && !"time".equals(reason)) {
            int motion = e < LOW_ENERGY ? MOTION_LOW : e < HIGH_ENERGY ? MOTION_MID : look.bpm >= FAST_BPM ? MOTION_HIGH : MOTION_MID;
            if (motion != lastColourMode) {
                look.colourMode = motion;
                lastColourMode = motion;
            }
            if (!coloursLocked && !palettePinned) {
                String[] family = e < LOW_ENERGY ? PAL_LOW : e < HIGH_ENERGY ? PAL_MID : PAL_HIGH;
                String p = family[rnd.nextInt(family.length)];
                if (p.equals(lastPalette)) p = family[(indexOf(family, p) + 1 + rnd.nextInt(family.length - 1)) % family.length];
                look.palette = p;
                lastPalette = p;
            }
        }
        return look.scene < 0 && look.palette == null && look.colourMode < 0 ? null : look;
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return 0;
    }

    /** How well scene i suits an energy: 1 inside its range, down to 0.1 over 0.35 outside it. */
    float fit(int i, float e) {
        float d = e < lo[i] ? lo[i] - e : e > hi[i] ? e - hi[i] : 0;
        return Math.max(0.1f, 1 - d / 0.35f);
    }

    /** Weighted random pick: the fit cubed (a calm scene must not land on the chorus) times the user's weight, the two latest scenes mostly skipped. */
    int pick(float e, boolean[] usable) {
        float total = 0, best = 0;
        float[] score = new float[ids.length];
        for (int i = 0; i < ids.length; i++)
            if (i != scene && (usable == null || i >= usable.length || usable[i])) best = Math.max(best, fit(i, e));
        float floor = best >= 0.75f ? 0.5f : 0f;   // with a good fit on offer, the poor ones sit out
        for (int i = 0; i < ids.length; i++) {
            if (i == scene || (usable != null && i < usable.length && !usable[i])) continue;
            float ft = fit(i, e);
            if (ft < floor) continue;
            score[i] = ft * ft * ft * weight[i] * (recent.contains(i) ? 0.25f : 1f);
            total += score[i];
        }
        if (total <= 0) return -1;
        float r = rnd.nextFloat() * total;
        for (int i = 0; i < ids.length; i++) {
            r -= score[i];
            if (score[i] > 0 && r <= 0) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ for the host, the tests and the event

    float energy() {
        return energy;
    }

    float bpm() {
        return beatIv > 0 ? 60f / beatIv : 0f;
    }

    private static final int TEMPO_BINS = 50;
    private static final float TEMPO_MIN = 0.3f, TEMPO_BIN = 0.01f;   // 0.30..0.80 s: 200 down to 75 bpm
    private final float[] tempo = new float[TEMPO_BINS];

    /** One vote for a beat interval, shared with the neighbouring bins. */
    private void vote(float iv, float w) {
        float x = (iv - TEMPO_MIN) / TEMPO_BIN;
        int b = (int) Math.floor(x);
        if (b < -1 || b > TEMPO_BINS) return;
        float f = x - b;
        if (b >= 0 && b < TEMPO_BINS) tempo[b] += w * (1 - f);
        if (b + 1 >= 0 && b + 1 < TEMPO_BINS) tempo[b + 1] += w * f;
    }

    boolean building() {
        return building;
    }

    int sections() {
        return sections;
    }

    boolean pending() {
        return pending != null;
    }

    float weight(int i) {
        return weight[i];
    }

    /** The user's scene weights as {@code id=weight;...}, for the preferences. */
    String weights() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (weight[i] == 1f) continue;
            if (sb.length() > 0) sb.append(';');
            sb.append(ids[i]).append('=').append(Math.round(weight[i] * 100) / 100f);
        }
        return sb.toString();
    }

    void loadWeights(String s) {
        if (s == null || s.isEmpty()) return;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            for (int i = 0; i < ids.length; i++) {
                if (!ids[i].equals(part.substring(0, eq))) continue;
                try {
                    weight[i] = Math.max(0.3f, Math.min(3f, Float.parseFloat(part.substring(eq + 1))));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }
}
