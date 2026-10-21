package io.github.christianernst.tav;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Plain-JVM check of AudioAnalyzer, fed at 60 fps like the renderer does.
 * <ul>
 * <li>No arguments: synthesizes 12 s of a 120 BPM kick + hi-hat + bass groove (8 s groove,
 * 4 s silence) and fails unless the beats land on the kicks and the silence is detected.</li>
 * <li>WAV path [--8bit]: prints beats and drops for a 16-bit stereo 44.1 kHz WAV, optionally
 * quantised the way Android's Visualizer delivers samples (8-bit).</li>
 * </ul>
 */
public final class AnalyzerCheck {
    private static final int SR = 44100;

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            selfTest();
            return;
        }
        byte[] wav = Files.readAllBytes(Paths.get(args[0]));
        ByteBuffer b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        int frames = (wav.length - 44) / 4;
        float[] mono = new float[frames];
        boolean eightBit = args.length > 1 && args[1].equals("--8bit");
        for (int i = 0; i < frames; i++) {
            mono[i] = (b.getShort(44 + 4 * i) + b.getShort(46 + 4 * i)) / 65536f;
            if (eightBit) mono[i] = Math.round(Math.max(-128, Math.min(127, mono[i] * 128))) / 128f;
        }
        Result r = run(mono);
        System.out.println("beats " + r.beats.length + " " + join(r.beats));
        System.out.println("drops " + join(r.drops));
    }

    private static final class Result {
        float[] beats, drops;
        boolean silentAtEnd;
    }

    private static Result run(float[] mono) {
        int n = AudioCapture.N, fps = 60;
        AudioAnalyzer an = new AudioAnalyzer(SR, n);
        float[] win = new float[n];
        float[] beats = new float[4096], drops = new float[64];
        int nb = 0, nd = 0;
        for (int f = 0; (long) f * SR / fps < mono.length; f++) {
            double t = (double) f / fps;
            int end = (int) Math.floor(t * SR);
            for (int i = 0; i < n; i++) {
                int j = end - n + i;
                win[i] = j >= 0 ? mono[j] : 0;
            }
            an.process(win, 1f / fps);
            if (an.onBeat && nb < beats.length) beats[nb++] = (float) t;
            if (an.drop && nd < drops.length) drops[nd++] = (float) t;
        }
        Result r = new Result();
        r.beats = Arrays.copyOf(beats, nb);
        r.drops = Arrays.copyOf(drops, nd);
        r.silentAtEnd = an.silent;
        return r;
    }

    private static void selfTest() {
        float[] s = new float[SR * 12];
        Random rnd = new Random(1);
        for (int beat = 0; beat < 16; beat++) {           // 8 s at 120 BPM
            int k0 = beat * SR / 2;
            for (int i = 0; i < SR * 0.35; i++) {         // kick: falling sine sweep
                double t = (double) i / SR;
                double ph = 2 * Math.PI * (45 * t + 110 * (1 - Math.exp(-t * 28)) / 28);
                s[k0 + i] += (float) (0.9 * Math.sin(ph) * Math.exp(-t * 7));
            }
            int h0 = k0 + SR / 4;
            for (int i = 0; i < SR * 0.05; i++)           // off-beat hat
                s[h0 + i] += (float) (rnd.nextGaussian() * 0.15 * Math.exp(-i / (SR * 0.012)));
            for (int i = 0; i < SR / 4; i++)              // off-beat bass note
                s[h0 + i] += (float) (0.25 * Math.signum(Math.sin(2 * Math.PI * 110 * i / SR)) * Math.exp(-i / (SR * 0.1)));
        }
        Result r = run(s);
        System.out.println("beats " + r.beats.length + " " + join(r.beats));
        int onKick = 0;
        boolean inSilence = false;
        for (float t : r.beats) {
            double phase = t % 0.5;
            if (t < 8 && (phase < 0.06 || phase > 0.46)) onKick++;
            if (t > 8.1f) inSilence = true;
        }
        boolean ok = r.beats.length >= 13 && r.beats.length <= 17 && onKick >= r.beats.length - 1
                && r.silentAtEnd && !inSilence;
        System.out.println("on-kick " + onKick + "/" + r.beats.length + ", silent at end: " + r.silentAtEnd
                + ", beats during silence: " + inSilence);
        // a source that stops advancing: 3 s of groove, then the same last window for 3 s
        AudioAnalyzer an = new AudioAnalyzer(SR, AudioCapture.N);
        float[] win = new float[AudioCapture.N];
        for (int f = 0; f < 6 * 60; f++) {
            if (f < 3 * 60) System.arraycopy(s, f * SR / 60, win, 0, win.length);
            an.process(win, 1f / 60);
        }
        boolean frozenOk = an.silent && an.bands[0] < 0.2f && an.level < 0.2f;
        System.out.printf(Locale.ROOT, "frozen input -> silent: %b, bass %.2f, level %.2f%n", an.silent, an.bands[0], an.level);
        if (!ok || !frozenOk) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("OK");
    }

    private static String join(float[] v) {
        StringBuilder sb = new StringBuilder();
        for (float x : v) sb.append(String.format(Locale.ROOT, "%.2f ", x));
        return sb.toString().trim();
    }
}
