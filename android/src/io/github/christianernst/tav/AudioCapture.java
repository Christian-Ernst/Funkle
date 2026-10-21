package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.Visualizer;
import android.util.Log;

/**
 * Supplies the newest N mono samples from one of two sources:
 * <ul>
 * <li>SYSTEM — the {@link Visualizer} effect on audio session 0, i.e. the global
 * output mix. This is what lets TAV react to Spotify (or anything else playing)
 * without the other app's cooperation.</li>
 * <li>MIC — the microphone, for devices whose ROM blocks session-0 capture.</li>
 * </ul>
 * {@link #read} is called from the GL thread; everything else from the UI thread.
 */
final class AudioCapture {
    static final int SOURCE_SYSTEM = 0, SOURCE_MIC = 1;
    static final int N = 1024;
    private static final String TAG = "TAV";

    interface Listener {
        /** The requested source could not start; {@code fellBackTo} is the one now running, or -1. */
        void onSourceProblem(int requested, int fellBackTo);
    }

    private final Listener listener;
    private final Object lock = new Object();
    private int source = -1;
    private Visualizer visualizer;
    private byte[] visBuf;
    private float visRate = 44100;
    private AudioRecord record;
    private Thread micThread;
    private volatile boolean micRunning;
    private final float[] ring = new float[N * 4];
    private int ringPos;
    private final float micRate = 44100;

    AudioCapture(Listener listener) {
        this.listener = listener;
    }

    /** Starts (or switches to) a source. Returns the source that is actually running, or -1. */
    int start(int wanted) {
        stop();
        if (wanted == SOURCE_SYSTEM) {
            if (startVisualizer()) return source = SOURCE_SYSTEM;
            if (startMic()) {
                listener.onSourceProblem(SOURCE_SYSTEM, SOURCE_MIC);
                return source = SOURCE_MIC;
            }
        } else {
            if (startMic()) return source = SOURCE_MIC;
            if (startVisualizer()) {
                listener.onSourceProblem(SOURCE_MIC, SOURCE_SYSTEM);
                return source = SOURCE_SYSTEM;
            }
        }
        listener.onSourceProblem(wanted, -1);
        return source = -1;
    }

    int source() {
        return source;
    }

    float sampleRate() {
        return source == SOURCE_SYSTEM ? visRate : micRate;
    }

    private boolean startVisualizer() {
        try {
            Visualizer v = new Visualizer(0);
            int[] range = Visualizer.getCaptureSizeRange();
            int size = Math.max(range[0], Math.min(range[1], N));
            v.setEnabled(false);
            v.setCaptureSize(size);
            v.setScalingMode(Visualizer.SCALING_MODE_NORMALIZED);
            if (v.setEnabled(true) != Visualizer.SUCCESS) {
                v.release();
                return false;
            }
            synchronized (lock) {
                visualizer = v;
                visBuf = new byte[size];
                visRate = v.getSamplingRate() / 1000f;
                if (visRate < 8000) visRate = 44100;
            }
            return true;
        } catch (RuntimeException e) {   // UnsupportedOperationException / IllegalStateException on locked-down ROMs
            Log.w(TAG, "Visualizer on session 0 unavailable", e);
            return false;
        }
    }

    // MainActivity only starts capture once RECORD_AUDIO is granted; a revoked permission
    // surfaces as SecurityException, which is caught below.
    @SuppressLint("MissingPermission")
    private boolean startMic() {
        try {
            int min = AudioRecord.getMinBufferSize((int) micRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT);
            if (min <= 0) return false;
            AudioRecord r = new AudioRecord(MediaRecorder.AudioSource.MIC, (int) micRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, Math.max(min, N * 8 * 4));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            r.startRecording();
            // Another app holding the mic makes startRecording() fail silently on older Androids.
            if (r.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                r.release();
                return false;
            }
            record = r;
            micRunning = true;
            micThread = new Thread(() -> micLoop(r), "tav-mic");
            micThread.start();
            return true;
        } catch (RuntimeException e) {   // SecurityException without the permission, or a busy mic
            Log.w(TAG, "Microphone unavailable", e);
            return false;
        }
    }

    private void micLoop(AudioRecord r) {
        float[] chunk = new float[256];
        while (micRunning) {
            int got = r.read(chunk, 0, chunk.length, AudioRecord.READ_BLOCKING);
            if (got < 0) return;   // released or broken
            if (got == 0) {        // not delivering: don't spin a core while waiting
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }
            synchronized (lock) {
                for (int i = 0; i < got; i++) {
                    ring[ringPos] = chunk[i];
                    ringPos = (ringPos + 1) % ring.length;
                }
            }
        }
    }

    /** Copies the newest N samples into dst (oldest first). Returns false when no source runs. */
    boolean read(float[] dst) {
        synchronized (lock) {
            if (visualizer != null) {
                try {
                    if (visualizer.getWaveForm(visBuf) != Visualizer.SUCCESS) return false;
                } catch (IllegalStateException e) {
                    return false;
                }
                int m = visBuf.length;
                // capture size can be smaller than N on old devices: left-pad with silence
                int pad = N - m;
                for (int i = 0; i < N; i++) dst[i] = i < pad ? 0f : ((visBuf[i - pad] & 0xFF) - 128) / 128f;
                return true;
            }
            if (record != null) {
                int p = ringPos - N;
                if (p < 0) p += ring.length;
                for (int i = 0; i < N; i++) {
                    dst[i] = ring[p];
                    p = (p + 1) % ring.length;
                }
                return true;
            }
            return false;
        }
    }

    void stop() {
        micRunning = false;
        Thread t = micThread;
        micThread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (lock) {
            if (visualizer != null) {
                try {
                    visualizer.setEnabled(false);
                } catch (IllegalStateException ignored) {
                }
                visualizer.release();
                visualizer = null;
            }
            if (record != null) {
                try {
                    record.stop();
                } catch (IllegalStateException ignored) {
                }
                record.release();
                record = null;
            }
        }
        source = -1;
    }
}
