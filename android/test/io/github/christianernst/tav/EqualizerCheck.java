package io.github.christianernst.tav;

import java.util.Locale;
import java.util.Random;

/**
 * Plain-JVM check of Equalizer. The arithmetic first, then the one thing it is for: on a groove whose
 * quiet section sits 8 dB under the loud one, the auto-gained bands read the same in both, and punch
 * keeps the difference.
 */
public final class EqualizerCheck {
    private static final int SR = 44100;
    private static boolean failed;

    public static void main(String[] args) {
        float[] norm = {0.2f, 0.5f, 0.8f, 0.1f}, abs = {0.9f, 0.9f, 0.9f, 0.9f}, out = new float[4];

        Equalizer.flat().shapeBands(norm, abs, out);
        check("flat keeps the auto-gained bands", same(out, norm));
        check("flat keeps level, beat and drop flash",
                near(Equalizer.flat().shapeLevel(0.4f), 0.4f) && near(Equalizer.flat().shapeBeat(0.5f), 0.5f)
                        && near(Equalizer.flat().dropFlash(), 0.6f));

        new Equalizer(1f, new float[]{1f, 1f, 1f, 1f}, 1f, 1f).shapeBands(norm, abs, out);
        check("full punch uses the absolute bands", same(out, abs));

        new Equalizer(1f, new float[]{2f, 1f, 0f, 1f}, 0f, 1f).shapeBands(new float[]{0.5f, 0.5f, 0.5f, 0.5f}, abs, out);
        check("gains are per band", near(out[0], 1f) && near(out[1], 0.5f) && near(out[2], 0f) && near(out[3], 0.5f));

        new Equalizer(1f, new float[]{2f, 1f, 1f, 1f}, 0f, 1f).shapeBands(new float[]{1f, 1f, 1f, 1f}, abs, out);
        check("boosts stop at the limit", near(out[0], Equalizer.LIMIT));

        Equalizer silent = new Equalizer(0f, new float[]{1f, 1f, 1f, 1f}, 0.7f, 1.3f);
        silent.shapeBands(norm, abs, out);
        check("master 0 silences everything",
                same(out, new float[4]) && silent.shapeLevel(0.9f) == 0f && silent.shapeBeat(0.9f) == 0f
                        && silent.dropFlash() == 0f);

        Equalizer bigBeat = new Equalizer(1f, new float[]{1f, 1f, 1f, 1f}, 0f, 2f);
        check("beat gain doubles the pulse", near(bigBeat.shapeBeat(0.5f), 1f));
        check("drop flash never reaches full white", near(bigBeat.dropFlash(), 1f));
        check("master scales the level", near(new Equalizer(2f, new float[]{1f, 1f, 1f, 1f}, 0f, 1f).shapeLevel(0.5f), 1f));

        dynamics();

        if (failed) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("OK");
    }

    /** Loud section, then the same groove 8 dB quieter; the bands are measured in both. */
    private static void dynamics() {
        float[] s = groove();
        AudioAnalyzer an = new AudioAnalyzer(SR, AudioCapture.N);
        float[] win = new float[AudioCapture.N], flat = new float[4], punch = new float[4];
        Equalizer f = Equalizer.flat(), p = Equalizer.punch();
        double[] sumFlat = new double[2], sumPunch = new double[2];
        int[] count = new int[2];
        for (int frame = 0; frame < 16 * 60; frame++) {
            double t = frame / 60.0;
            int end = (int) Math.floor(t * SR);
            for (int i = 0; i < win.length; i++) {
                int j = end - win.length + i;
                win[i] = j >= 0 ? s[j] : 0;
            }
            an.process(win, 1f / 60);
            int part = t >= 3 && t < 8 ? 0 : t >= 11 && t < 16 ? 1 : -1;   // skip the settling
            if (part < 0) continue;
            f.shapeBands(an.bands, an.bandsAbs, flat);
            p.shapeBands(an.bands, an.bandsAbs, punch);
            sumFlat[part] += flat[0];
            sumPunch[part] += punch[0];
            count[part]++;
        }
        double loudFlat = sumFlat[0] / count[0], quietFlat = sumFlat[1] / count[1];
        double loudPunch = sumPunch[0] / count[0], quietPunch = sumPunch[1] / count[1];
        double flatRatio = quietFlat / loudFlat, punchRatio = quietPunch / loudPunch;
        check(String.format(Locale.ROOT, "flat: the quiet part reads %.2f of the loud one (bass)", flatRatio),
                flatRatio > 0.9);
        check(String.format(Locale.ROOT, "punch: the quiet part reads %.2f of the loud one (bass)", punchRatio),
                punchRatio < 0.85);
    }

    /** 16 s of kick, hat and bass at 120 BPM: 8 s at full level, then 8 s at 0.4 (about -8 dB). */
    private static float[] groove() {
        float[] s = new float[SR * 16];
        Random rnd = new Random(1);
        for (int beat = 0; beat < 32; beat++) {
            int k0 = beat * SR / 2;
            float g = beat < 16 ? 1f : 0.4f;
            for (int i = 0; i < SR * 0.35 && k0 + i < s.length; i++) {
                double t = (double) i / SR;
                double ph = 2 * Math.PI * (45 * t + 110 * (1 - Math.exp(-t * 28)) / 28);
                s[k0 + i] += (float) (g * 0.9 * Math.sin(ph) * Math.exp(-t * 7));
            }
            int h0 = k0 + SR / 4;
            for (int i = 0; i < SR * 0.05 && h0 + i < s.length; i++)
                s[h0 + i] += (float) (g * rnd.nextGaussian() * 0.15 * Math.exp(-i / (SR * 0.012)));
            for (int i = 0; i < SR / 4 && h0 + i < s.length; i++)
                s[h0 + i] += (float) (g * 0.25 * Math.signum(Math.sin(2 * Math.PI * 110 * i / SR)) * Math.exp(-i / (SR * 0.1)));
        }
        return s;
    }

    private static boolean same(float[] a, float[] b) {
        for (int i = 0; i < a.length; i++) if (!near(a[i], b[i])) return false;
        return true;
    }

    private static boolean near(float v, float want) {
        return Math.abs(v - want) < 1e-4f;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
