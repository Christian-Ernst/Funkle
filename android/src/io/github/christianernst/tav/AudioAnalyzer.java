package io.github.christianernst.tav;

/**
 * Turns the newest N mono samples into everything the shaders consume: a
 * log-frequency spectrum with peaks, a triggered loop-seamless waveform, four
 * auto-gained bands, a beat pulse, intensity, drop events and a music clock.
 * Java twin of web/analyzer.js — keep the two in sync.
 */
final class AudioAnalyzer {
    static final int SPEC_BINS = 512, HIST_W = 256, HIST_H = 128, WAVE_LEN = 512, WAVE_XFADE = 64;

    final int n;
    final float sampleRate;
    private final FFT fft;
    private final float[] re, im, win, mag, prevLog, lp;
    private final float[] lo = new float[SPEC_BINS], hi = new float[SPEC_BINS], tilt = new float[SPEC_BINS];
    private final int[][] bandRange = new int[4][2];
    private final int beatLo, beatHi;

    private final float[] specRaw = new float[SPEC_BINS], spec = new float[SPEC_BINS], peak = new float[SPEC_BINS];
    private final float[] wave = new float[WAVE_LEN], waveS = new float[WAVE_LEN];
    private float peakDb = -40f;
    private final float[] bandPeak = {1e-3f, 1e-3f, 1e-3f, 1e-3f};
    final float[] bands = new float[4];
    /** The same bands without auto-gain (in dB, mapped to 0..1 and squared): loud stays loud. */
    final float[] bandsAbs = new float[4];
    private float levelPeak = 1e-3f;
    float level;
    private float wavePeak = 0.05f;
    private final float[] onsetHist = new float[60];
    private int onsetIdx;
    private float onsetPeak = 1e-3f;
    float beat;
    int beatCount;
    private float sinceBeat = 10f, sinceDrop = 10f;
    float intensity;
    private float bassFast, bassSlow;
    float flow;
    boolean silent = true;
    float silentFor = 10f;
    boolean drop, onBeat;
    private float lastSig = Float.NaN, staleFor;
    private final float[] zeros;

    /** Rows: spectrum, peaks, waveform, smoothed waveform; R8 texture 512x4. */
    final byte[] audioBytes = new byte[SPEC_BINS * 4];
    final byte[] histRow = new byte[HIST_W];
    int histHead;
    boolean histPushed;
    private float histAcc;

    AudioAnalyzer(float sampleRate, int n) {
        this.sampleRate = sampleRate;
        this.n = n;
        fft = new FFT(n);
        re = new float[n];
        im = new float[n];
        win = new float[n];
        lp = new float[n];
        zeros = new float[n];
        for (int i = 0; i < n; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
        int nb = n / 2 + 1;
        mag = new float[nb];
        prevLog = new float[nb];

        // log-frequency display mapping with +3 dB/octave tilt (music is roughly pink)
        double fMin = 35, fMax = Math.min(16000, sampleRate * 0.475), hz = sampleRate / n;
        for (int i = 0; i < SPEC_BINS; i++) {
            lo[i] = (float) (logFreq(fMin, fMax, i - 0.5) / hz);
            hi[i] = (float) (logFreq(fMin, fMax, i + 0.5) / hz);
            tilt[i] = (float) (3.0 * Math.log(logFreq(fMin, fMax, i) / 1000) / Math.log(2));
        }
        int[][] hzRanges = {{30, 150}, {150, 600}, {600, 3000}, {3000, 12000}};
        for (int b = 0; b < 4; b++) {
            int a = bin(hzRanges[b][0], hz, nb);
            bandRange[b][0] = a;
            bandRange[b][1] = Math.max(a + 1, bin(hzRanges[b][1], hz, nb));
        }
        beatLo = bin(30, hz, nb);
        beatHi = Math.max(beatLo + 2, bin(110, hz, nb));
    }

    private static double logFreq(double fMin, double fMax, double x) {
        return fMin * Math.pow(fMax / fMin, x / (SPEC_BINS - 1));
    }

    private static int bin(double f, double hz, int nb) {
        return (int) Math.max(1, Math.min(nb - 1, Math.round(f / hz)));
    }

    /** Absolute band levels: -60 dB maps to 0, -6 dB to 1. Set on a synthetic groove; check on a phone. */
    private static final float ABS_FLOOR_DB = -60, ABS_RANGE_DB = 54;

    private static float clamp01(float v) {
        return v <= 0 ? 0 : (v >= 1 ? 1 : v);
    }

    /** samples: the newest n mono samples, oldest first. */
    void process(float[] samples, float dt) {
        final int nb = n / 2 + 1;
        drop = false;
        onBeat = false;

        // Visualizer polling can hand us the same buffer twice; flux must ignore that.
        float sig = samples[0] * 3.1f + samples[n >> 1] * 1.7f + samples[n - 1] + samples[n >> 2] * 0.3f;
        boolean fresh = sig != lastSig;
        lastSig = sig;
        // A source that stops advancing (dead mic, stalled capture) keeps handing over its last
        // buffer; auto-gain would pin that at full intensity. After 0.5 s of it, listen to silence.
        staleFor = fresh ? 0 : staleFor + dt;
        if (staleFor > 0.5f) samples = zeros;

        float sumSq = 0, maxAbs = 0;
        for (int i = 0; i < n; i++) {
            float s = samples[i];
            sumSq += s * s;
            float a = Math.abs(s);
            if (a > maxAbs) maxAbs = a;
            re[i] = s * win[i];
            im[i] = 0;
        }
        float rms = (float) Math.sqrt(sumSq / n);
        silentFor = rms < 2e-4f ? silentFor + dt : 0;
        silent = silentFor > 0.6f;

        fft.transform(re, im);
        float norm = 4f / n;   // Hann coherent gain 0.5 -> full-scale sine ~ 1.0
        for (int k = 0; k < nb; k++) mag[k] = (float) Math.hypot(re[k], im[k]) * norm;

        // --- display spectrum ---
        float frameMaxDb = -120;
        for (int i = 0; i < SPEC_BINS; i++) {
            float l = lo[i], h = hi[i], m;
            if (h - l < 1) {
                float x = Math.min(nb - 1.001f, Math.max(0, (l + h) * 0.5f));
                int k = (int) x;
                float f = x - k;
                m = mag[k] * (1 - f) + mag[k + 1] * f;
            } else {
                m = 0;
                int kEnd = Math.min(nb - 1, (int) Math.floor(h));
                for (int k = (int) Math.ceil(l); k <= kEnd; k++) if (mag[k] > m) m = mag[k];
            }
            float db = (float) (20 * Math.log10(m + 1e-9)) + tilt[i];
            specRaw[i] = db;
            if (db > frameMaxDb) frameMaxDb = db;
        }
        peakDb = Math.max(Math.max(frameMaxDb, peakDb - dt * 4), -50);
        final float range = 52, floorDb = peakDb - range;
        float kUp = 1 - (float) Math.exp(-dt * 35), kDn = 1 - (float) Math.exp(-dt * 7);
        for (int i = 0; i < SPEC_BINS; i++) {
            float v = clamp01((specRaw[i] - floorDb) / range);
            v = v * v * (1.6f - 0.6f * v);   // a touch of contrast
            specRaw[i] = v;
            float s = spec[i];
            spec[i] = s + (v - s) * (v > s ? kUp : kDn);
            peak[i] = Math.max(spec[i], peak[i] - dt * 0.35f);
        }

        // --- bands, level (auto-gained against their own recent peaks) ---
        float bUp = 1 - (float) Math.exp(-dt * 25), bDn = 1 - (float) Math.exp(-dt * 5);
        float decay = (float) Math.exp(-dt * 0.25);
        for (int b = 0; b < 4; b++) {
            float e = 0;
            for (int k = bandRange[b][0]; k < bandRange[b][1]; k++) e += mag[k] * mag[k];
            float amp = (float) Math.sqrt(e);
            bandPeak[b] = Math.max(Math.max(amp, bandPeak[b] * decay), 2e-3f);
            float v = amp / bandPeak[b];
            v = v * v;
            float c = bands[b];
            bands[b] = c + (v - c) * (v > c ? bUp : bDn);
            float a = clamp01((20 * (float) Math.log10(amp + 1e-9f) - ABS_FLOOR_DB) / ABS_RANGE_DB);
            a *= a;
            float ca = bandsAbs[b];
            bandsAbs[b] = ca + (a - ca) * (a > ca ? bUp : bDn);
        }
        levelPeak = Math.max(Math.max(rms, levelPeak * decay), 2e-3f);
        float lv = rms / levelPeak;
        level += (lv - level) * (lv > level ? bUp : bDn);

        // --- beat: bass spectral flux against an adaptive threshold ---
        sinceBeat += dt;
        sinceDrop += dt;
        if (fresh) {
            float flux = 0;
            for (int k = 1; k < nb; k++) {
                float l = (float) Math.log1p(100 * mag[k]);
                float d = l - prevLog[k];
                if (d > 0) flux += (k >= beatLo && k < beatHi) ? d : d * 0.01f;
                prevLog[k] = l;
            }
            onsetPeak = Math.max(Math.max(flux, onsetPeak * (float) Math.exp(-dt * 0.08)), 1e-3f);
            float onset = flux / onsetPeak;
            float mean = 0, sq = 0;
            for (float h : onsetHist) {
                mean += h;
                sq += h * h;
            }
            mean /= onsetHist.length;
            float sd = (float) Math.sqrt(Math.max(0, sq / onsetHist.length - mean * mean));
            onsetHist[onsetIdx] = onset;
            onsetIdx = (onsetIdx + 1) % onsetHist.length;
            if (onset > mean + 1.5f * sd && onset > 0.3f && sinceBeat > 0.27f && !silent) {
                beat = 1;
                beatCount++;
                sinceBeat = 0;
                onBeat = true;
            }
        }
        beat *= (float) Math.exp(-dt * 5.5);

        // --- intensity, drops and the music clock ---
        float bass = bands[0];
        bassFast += (bass - bassFast) * (1 - (float) Math.exp(-dt / 0.35));
        bassSlow += (bass - bassSlow) * (1 - (float) Math.exp(-dt / 6));
        float target = silent ? 0 : 0.55f * bassFast + 0.45f * level;
        intensity += (target - intensity) * (1 - (float) Math.exp(-dt / 1.2));
        if (onBeat && bassFast > 0.55f && bassSlow < 0.42f && sinceDrop > 8) {
            drop = true;
            sinceDrop = 0;
        }
        flow += dt * (0.25f + 1.3f * intensity + 0.7f * beat);

        buildWave(samples, maxAbs, dt);
        pack(dt);
    }

    /**
     * Oscilloscope-style trigger on a rising zero crossing of the low-passed signal,
     * then crossfade the tail into the head so the 512 samples loop seamlessly.
     */
    private void buildWave(float[] samples, float maxAbs, float dt) {
        int need = WAVE_LEN + WAVE_XFADE, limit = n - need;
        float acc = 0;
        for (int i = 0; i < n; i++) {
            acc += 0.08f * (samples[i] - acc);
            lp[i] = acc;
        }
        int start = Math.max(0, limit >> 1);
        for (int i = 1; i < limit; i++) {
            if (lp[i - 1] < 0 && lp[i] >= 0) {
                start = i;
                break;
            }
        }
        wavePeak = Math.max(Math.max(maxAbs, wavePeak * (float) Math.exp(-dt * 0.8)), 0.02f);
        float g = 0.95f / wavePeak, ks = Math.min(1, dt * 14);
        for (int i = 0; i < WAVE_LEN; i++) {
            float s = samples[start + i];
            if (i < WAVE_XFADE) {
                float t = (float) i / WAVE_XFADE;
                s = samples[start + i + WAVE_LEN] * (1 - t) + s * t;
            }
            s *= g;
            wave[i] = s;
            waveS[i] += (s - waveS[i]) * ks;
        }
    }

    private static byte q(float v) {
        return (byte) (v <= 0 ? 0 : (v >= 1 ? 255 : (int) (v * 255 + 0.5f)));
    }

    private void pack(float dt) {
        byte[] b = audioBytes;
        for (int i = 0; i < SPEC_BINS; i++) {
            b[i] = q(spec[i]);
            b[SPEC_BINS + i] = q(peak[i]);
            b[2 * SPEC_BINS + i] = q(wave[i] * 0.5f + 0.5f);
            b[3 * SPEC_BINS + i] = q(waveS[i] * 0.5f + 0.5f);
        }
        histPushed = false;
        histAcc += dt;
        if (histAcc >= 1f / 30) {
            histAcc = Math.min(histAcc - 1f / 30, 1f / 30);
            for (int i = 0; i < HIST_W; i++) histRow[i] = q(Math.max(specRaw[2 * i], specRaw[2 * i + 1]));
            histHead = (histHead + 1) % HIST_H;
            histPushed = true;
        }
    }

    /** In-place iterative radix-2 Cooley-Tukey FFT. */
    static final class FFT {
        private final int n;
        private final float[] cos, sin;
        private final int[] rev;

        FFT(int n) {
            this.n = n;
            int levels = Integer.numberOfTrailingZeros(n);
            cos = new float[n / 2];
            sin = new float[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = (float) Math.cos(2 * Math.PI * i / n);
                sin[i] = (float) Math.sin(2 * Math.PI * i / n);
            }
            rev = new int[n];
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - levels);
        }

        void transform(float[] re, float[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (j > i) {
                    float t = re[i];
                    re[i] = re[j];
                    re[j] = t;
                    t = im[i];
                    im[i] = im[j];
                    im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1, step = n / size;
                for (int i = 0; i < n; i += size) {
                    for (int j = i, k = 0; j < i + half; j++, k += step) {
                        int l = j + half;
                        float tr = re[l] * cos[k] + im[l] * sin[k];
                        float ti = -re[l] * sin[k] + im[l] * cos[k];
                        re[l] = re[j] - tr;
                        im[l] = im[j] - ti;
                        re[j] += tr;
                        im[j] += ti;
                    }
                }
            }
        }
    }
}
