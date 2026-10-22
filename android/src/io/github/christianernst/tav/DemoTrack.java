package io.github.christianernst.tav;

/**
 * The built-in demo beat: 16 bars at 128 BPM (intro, breakdown with a riser, drop), synthesized
 * sample by sample so the visuals have music before the app may hear anything. It drives the first
 * run, before the audio permission is asked for, and can be played out loud through an AudioTrack.
 * A port of {@code web/demo.js} (the same track the analyzer was tuned on), mono and about 30 s.
 */
final class DemoTrack {
    static final int RATE = 44100;
    static final float BPM = 128;

    private DemoTrack() {
    }

    /** Seconds of one loop. */
    static float duration() {
        return 16 * 4 * 60f / BPM;
    }

    /** Builds the track (about 30 MB of float math, a fraction of a second on a phone). */
    static float[] synth() {
        final int sr = RATE;
        final float beat = 60f / BPM;
        final int bars = 16, n = (int) (bars * 4 * beat * sr);
        final float[] L = new float[n], R = new float[n];
        final long[] seed = {7};
        class Rnd {
            float next() {
                seed[0] = (seed[0] * 1664525L + 1013904223L) & 0xFFFFFFFFL;
                return seed[0] / 4294967296f;
            }

            float gauss() {
                return (float) (Math.sqrt(-2 * Math.log(next() + 1e-12)) * Math.cos(2 * Math.PI * next()));
            }
        }
        final Rnd rnd = new Rnd();
        class Mix {
            void add(float[] sig, float start, float pan, float gain) {
                int i0 = (int) (start * sr);
                float gl = gain * (1 - Math.max(0, pan)), gr = gain * (1 + Math.min(0, pan));
                for (int i = 0; i < sig.length && i0 + i < n; i++) {
                    L[i0 + i] += sig[i] * gl;
                    R[i0 + i] += sig[i] * gr;
                }
            }

            float[] buf(float d) {
                return new float[(int) (d * sr)];
            }

            float[] lp(float[] x, float a) {
                float acc = 0;
                for (int i = 0; i < x.length; i++) {
                    acc += a * (x[i] - acc);
                    x[i] = acc;
                }
                return x;
            }

            float[] kick() {
                float[] s = buf(0.45f);
                double ph = 0;
                for (int i = 0; i < s.length; i++) {
                    double t = (double) i / sr;
                    ph += 2 * Math.PI * (45 + 110 * Math.exp(-t * 28)) / sr;
                    s[i] = (float) (Math.sin(ph) * Math.exp(-t * 7));
                }
                return s;
            }

            float[] snare() {
                float[] s = buf(0.25f);
                for (int i = 0; i < s.length; i++) {
                    double t = (double) i / sr;
                    s[i] = (float) (rnd.gauss() * 0.5 * Math.exp(-t * 18) + Math.sin(2 * Math.PI * 190 * t) * 0.4 * Math.exp(-t * 25));
                }
                return s;
            }

            float[] hat(boolean open) {
                float[] s = buf(open ? 0.18f : 0.05f);
                float prev = 0;
                for (int i = 0; i < s.length; i++) {
                    float x = rnd.gauss();
                    s[i] = (float) ((x - prev) * Math.exp(-(double) i / sr * (open ? 12 : 70)) * 0.25);
                    prev = x;
                }
                return s;
            }

            float[] saw(float f, float d, float[] detune) {
                float[] s = buf(d);
                for (int i = 0; i < s.length; i++) {
                    double v = 0;
                    for (float dt : detune) {
                        double p = ((double) i / sr) * f * (1 + dt);
                        v += 2 * (p - Math.floor(p)) - 1;
                    }
                    s[i] = (float) (v / detune.length);
                }
                return s;
            }
        }
        final Mix m = new Mix();
        // note table (Hz)
        final float A1 = 55, F1 = 43.65f, C2 = 65.41f, G1 = 49, A3 = 220, C4 = 261.6f, E4 = 329.6f, F3 = 174.6f,
                A4 = 440, G3 = 196, B3 = 246.9f, D4 = 293.7f, C5 = 523.3f, E5 = 659.3f, G4 = 392;
        final float[][] prog = {{A1, A3, C4, E4}, {F1, F3, A3, C4}, {C2, G3, C4, E4}, {G1, G3, B3, D4}};
        final float[] K = m.kick();
        for (int bar = 0; bar < bars; bar++) {
            final int sec = bar < 4 ? 0 : bar < 8 ? 1 : 2;   // intro, break, drop
            final float[] chord = prog[bar % 4];
            final float root = chord[0], b0 = bar * 4 * beat;
            float[] pad = m.buf(4 * beat);
            for (int c = 1; c < 4; c++) {
                float[] s = m.saw(chord[c], 4 * beat, new float[]{-0.006f, 0, 0.007f});
                for (int i = 0; i < pad.length; i++) pad[i] += s[i] / 3;
            }
            float fade = 0.3f * sr;
            for (int i = 0; i < pad.length; i++) pad[i] *= Math.min(1, Math.min(i / fade, (pad.length - i) / fade));
            m.lp(pad, sec != 2 ? 0.04f : 0.12f);
            m.add(pad, b0, -0.3f, 0.22f);
            m.add(pad, b0, 0.3f, 0.22f);
            for (int q = 0; q < 4; q++) {
                float tb = b0 + q * beat;
                if (sec != 1) m.add(K, tb, 0, 0.9f);
                if (sec == 1 && bar == 7) {
                    m.add(K, tb, 0, 0.5f + 0.12f * q);
                    m.add(K, tb + beat / 2, 0, 0.5f + 0.12f * q);
                }
                if (sec == 2 && (q == 1 || q == 3)) m.add(m.snare(), tb, 0, 0.6f);
                if (sec != 0 || bar >= 2) {
                    m.add(m.hat(false), tb + beat / 2, 0.4f, 0.8f);
                    if (sec == 2) {
                        m.add(m.hat(false), tb + beat / 4, -0.4f, 0.5f);
                        m.add(m.hat(true), tb + 3 * beat / 4, 0.2f, 0.5f);
                    }
                }
                if (sec != 1) {
                    float[] bs = m.saw(root * 2, beat * 0.45f, new float[]{0, 0.01f});
                    for (int i = 0; i < bs.length; i++) bs[i] *= (float) Math.exp(-(double) i / sr * 6);
                    m.add(m.lp(bs, sec == 0 ? 0.08f : 0.2f), tb + beat / 2, 0, 0.55f);
                }
            }
            if (sec == 2) {
                float[] arp = bar % 4 == 0 || bar % 4 == 2 ? new float[]{A4, C5, E5, C5} : new float[]{G4, C5, E5, G4};
                for (int k = 0; k < 16; k++) {
                    float f = arp[k % 4];
                    float[] s = m.buf(beat / 4 * 0.9f);
                    for (int i = 0; i < s.length; i++) {
                        double t = (double) i / sr;
                        s[i] = (float) ((Math.signum(Math.sin(2 * Math.PI * f * t)) * 0.5 + Math.sin(4 * Math.PI * f * t) * 0.3) * Math.exp(-t * 9));
                    }
                    m.add(m.lp(s, 0.25f), b0 + k * beat / 4, 0.5f * (float) Math.sin(k), 0.18f);
                }
            }
        }
        // riser into the drop
        int i0 = (int) (7 * 4 * beat * sr), i1 = (int) (8 * 4 * beat * sr);
        for (int i = i0; i < i1; i++) {
            float v = rnd.gauss() * 0.35f * (i - i0) / (float) (i1 - i0);
            L[i] += v;
            R[i] += v;
        }
        float peak = 1e-9f;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.max(Math.abs(L[i]), Math.abs(R[i])));
        float g = 1 / (peak * 1.05f), t13 = (float) Math.tanh(1.3);
        float[] mono = new float[n];
        for (int i = 0; i < n; i++) {
            float l = (float) Math.tanh(L[i] * g * 1.3) / t13, r = (float) Math.tanh(R[i] * g * 1.3) / t13;
            mono[i] = 0.5f * (l + r);
        }
        return mono;
    }
}
