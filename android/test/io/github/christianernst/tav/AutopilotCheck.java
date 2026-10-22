package io.github.christianernst.tav;

/**
 * Plain-JVM check of the autopilot director on a synthetic song: a quiet verse, a build-up, a drop
 * into a loud chorus, a breakdown. It must hold through the build-up, switch on the downbeat of the
 * bar after the drop to a scene that suits the energy, hear the tempo, mark the sections, and weight
 * the scenes the user keeps.
 */
public final class AutopilotCheck {
    private static boolean failed;
    private static final String[] IDS = {"halo", "tunnel", "pulsar", "kaleido", "orb", "nebula", "spiro"};
    private static final float DT = 1 / 60f;

    /** A metronome at bpm with the given energy; returns the looks the director asked for. */
    private static final class Song {
        final Autopilot ap;
        float t, lastBeat = -10, bpm = 140;
        int beats;
        final java.util.List<Autopilot.Look> looks = new java.util.ArrayList<>();
        final java.util.List<Float> at = new java.util.ArrayList<>();

        Song(Autopilot ap) {
            this.ap = ap;
        }

        /** Plays seconds of music: intensity, bass and treble shares, with a drop flag on the first beat. */
        void play(float seconds, float intensity, float bass, float treble, boolean dropOnFirstBeat) {
            float end = t + seconds;
            boolean first = true;
            while (t < end) {
                boolean beat = bpm > 0 && t - lastBeat >= 60f / bpm;
                if (beat) {
                    lastBeat = t;
                    beats++;
                }
                boolean drop = beat && first && dropOnFirstBeat;
                if (beat) first = false;
                float[] bands = {bass, 0.5f * intensity, 0.4f * intensity, treble};
                Autopilot.Look l = ap.step(DT, bands, intensity, intensity, beat, drop, intensity <= 0, null);
                if (l != null) {
                    looks.add(l);
                    at.add(t);
                }
                t += DT;
            }
        }
    }

    public static void main(String[] args) {
        // --- energy fit and the scene list are data ---
        Autopilot ap = new Autopilot(IDS, 1);
        check("a scene without a mood suits everything", Autopilot.mood("brand-new-scene")[0] == 0f && Autopilot.mood("brand-new-scene")[1] == 1f);
        check("Pulsar does not suit a quiet verse", ap.fit(2, 0.1f) < 0.25f && ap.fit(2, 0.8f) == 1f);
        check("Ferro suits a quiet verse", ap.fit(4, 0.1f) == 1f && ap.fit(4, 0.9f) < 0.25f);
        Autopilot grown = new Autopilot(new String[]{"halo", "fluid", "landscape"}, 3);
        boolean picked = false;
        for (int i = 0; i < 50; i++) picked |= grown.pick(0.5f, null) > 0;
        check("new scenes join the rotation", picked);

        // --- off does nothing ---
        ap.setMode(Autopilot.OFF);
        Song off = new Song(ap);
        off.play(120, 0.8f, 0.9f, 0.6f, true);
        check("Off never changes the look", off.looks.isEmpty());

        // --- a song ---
        ap = new Autopilot(IDS, 7);   // song
        ap.onScene(4);   // Ferro, the restored scene
        Song s = new Song(ap);
        s.play(2, 0f, 0f, 0f, false);
        check("silence does nothing", s.looks.isEmpty());
        s.play(30, 0.15f, 0.2f, 0.1f, false);   // quiet verse: Ferro fits, nothing to do yet
        check("a fitting scene is kept through the verse", s.looks.isEmpty());
        check("the tempo is heard", Math.abs(ap.bpm() - 140) < 6);
        Autopilot sloppy = new Autopilot(IDS, 2);
        float t = 0, last = -10;
        int count = 0;
        for (int i = 0; i < 60 * 40; i++) {   // 128 bpm with every third beat missed, as a real detector does
            boolean beat = t - last >= 60f / 128;
            if (beat) {
                last = t;
                count++;
            }
            sloppy.step(DT, new float[]{0.5f, 0.3f, 0.3f, 0.2f}, 0.5f, 0.5f, beat && count % 3 != 0, false, false, null);
            t += DT;
        }
        check("missed beats do not drag the tempo", Math.abs(sloppy.bpm() - 128) < 6);
        Autopilot synco = new Autopilot(IDS, 2);
        t = 0;
        int frame = 0;
        for (int i = 0; i < 60 * 40; i++) {   // 128 bpm with an off-beat hit after every second beat
            float beatLen = 60f / 128, ph = t % (2 * beatLen);
            boolean beat = Math.abs(ph) < DT / 2 || Math.abs(ph - beatLen) < DT / 2 || Math.abs(ph - 1.5f * beatLen) < DT / 2;
            synco.step(DT, new float[]{0.5f, 0.3f, 0.3f, 0.2f}, 0.5f, 0.5f, beat, false, false, null);
            t += DT;
            frame++;
        }
        check("off-beat hits do not drag the tempo", Math.abs(synco.bpm() - 128) < 6);
        float e = ap.energy();
        check("energy follows the music", e > 0.1f && e < 0.2f);

        // build-up: energy climbs for 8 s; the verse is long enough that the timeout would otherwise fire
        s.play(70, 0.15f, 0.2f, 0.1f, false);
        int beforeBuild = s.looks.size();
        for (int i = 0; i < 16; i++) s.play(0.5f, 0.15f + 0.04f * i, 0.2f + 0.03f * i, 0.1f + 0.02f * i, false);
        check("it is a build-up", ap.building());
        check("the build-up is held", s.looks.size() == beforeBuild);

        // the drop: loud chorus
        int beatsAtDrop = s.beats;
        s.play(20, 0.9f, 0.95f, 0.5f, true);
        int afterDrop = s.looks.size();
        check("the drop brings a new look", afterDrop > beforeBuild);
        Autopilot.Look dropLook = s.looks.get(beforeBuild);
        check("the switch comes on the downbeat of the next bar, not on the drop", "drop".equals(dropLook.reason)
                && s.at.get(beforeBuild) > s.t - 20 + 3 * 60f / 140 - 0.1f && s.at.get(beforeBuild) < s.t - 20 + 5 * 60f / 140);
        check("the drop scene suits a loud chorus", dropLook.scene >= 0 && ap.fit(dropLook.scene, 0.9f) >= 0.5f);
        check("a drop crossfades fast", dropLook.fade < 1f);
        check("the chorus gets high-energy colours", dropLook.palette != null && contains(Autopilot.PAL_HIGH, dropLook.palette)
                && dropLook.colourMode == Autopilot.MOTION_HIGH);

        // breakdown: quiet, different mix -> a section change with calmer colours
        int beforeBreak = s.looks.size();
        s.play(2, 0.9f, 0.95f, 0.5f, false);
        s.play(10, 0.12f, 0.1f, 0.5f, false);
        check("a quiet breakdown is a new section", s.looks.size() > beforeBreak && "section".equals(s.looks.get(beforeBreak).reason));
        Autopilot.Look br = s.looks.get(beforeBreak);
        check("the breakdown gets calm colours", br.colourMode == Autopilot.MOTION_LOW || br.palette == null || contains(Autopilot.PAL_LOW, br.palette));

        // --- the user's hand ---
        ap = new Autopilot(IDS, 11);
        ap.onScene(0);
        s = new Song(ap);
        s.play(5, 0.5f, 0.5f, 0.3f, false);
        ap.onUserScene(2);   // Pulsar on a mid groove
        check("a pick is remembered", ap.weight(2) > 1f);
        int n = s.looks.size();
        s.play(100, 0.5f, 0.5f, 0.3f, false);
        check("a scene the user picked is held, no timeout switch", s.looks.size() == n);
        s.play(3, 0.5f, 0.5f, 0.3f, true);   // a drop still moves on
        s.play(3, 0.9f, 0.9f, 0.5f, false);
        check("a drop still moves the held scene on", s.looks.size() == n + 1);
        int auto = s.looks.get(n).scene;
        s.play(2, 0.9f, 0.9f, 0.5f, false);
        ap.onUserScene(3);   // switched away at once: the autopilot's choice was unwelcome
        check("leaving an autopilot scene at once lowers its weight", ap.weight(auto) < 1f);
        Autopilot copy = new Autopilot(IDS, 1);
        copy.loadWeights(ap.weights());
        check("weights survive the preferences", Math.abs(copy.weight(auto) - ap.weight(auto)) < 0.011f && Math.abs(copy.weight(3) - ap.weight(3)) < 0.011f);

        // --- colours: pinned palette and locked colours ---
        ap = new Autopilot(IDS, 5);
        ap.onScene(0);
        ap.onUserPalette();
        s = new Song(ap);
        s.play(3, 0.5f, 0.5f, 0.3f, true);
        s.play(3, 0.9f, 0.9f, 0.5f, false);
        check("a palette the user chose stays for the track", s.looks.size() == 1 && s.looks.get(0).palette == null);
        ap.onTrack();
        s.play(1.5f, 0.9f, 0.9f, 0.5f, false);
        check("a new track is heard before it gets a look", s.looks.size() == 1);
        s.play(2, 0.9f, 0.9f, 0.5f, false);
        check("a new track brings a new look and frees the palette", s.looks.size() == 2 && "track".equals(s.looks.get(1).reason) && s.looks.get(1).palette != null);
        ap.setColoursLocked(true);
        ap.onTrack();
        s.play(4, 0.9f, 0.9f, 0.5f, false);
        check("Album colours are left alone", s.looks.size() == 3 && s.looks.get(2).palette == null);
        ap.setMode(Autopilot.SCENES);
        ap.setColoursLocked(false);
        ap.onTrack();
        s.play(4, 0.3f, 0.3f, 0.3f, false);
        check("Scenes mode never touches the colours", s.looks.size() == 4 && s.looks.get(3).palette == null && s.looks.get(3).colourMode < 0);

        if (failed) {
            System.out.println("AutopilotCheck FAILED");
            System.exit(1);
        }
        System.out.println("  autopilot ok");
    }

    private static boolean contains(String[] a, String s) {
        for (String x : a) if (x.equals(s)) return true;
        return false;
    }

    private static void check(String what, boolean ok) {
        if (!ok) failed = true;
        System.out.println("  " + (ok ? "ok   " : "FAIL ") + what);
    }
}
