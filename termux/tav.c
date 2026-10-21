/*
 * tav — Termux Audio Visualizer, terminal edition.
 *
 * Renders music as neon light in any truecolor terminal: every character cell is a
 * 2x4 braille dot matrix lit from a floating-point framebuffer, and the cell
 * background carries a blurred copy of the light, so lines are sharp and still glow.
 *
 * Audio comes in as raw s16le stereo PCM at 44.1 kHz from one of:
 *   - PulseAudio's monitor of the default sink (anything Termux plays: mpv, ncspot, ...)
 *   - the microphone, through PulseAudio's OpenSL ES source
 *   - a file, decoded with ffmpeg and played through PulseAudio while it's drawn
 *   - stdin
 *
 * Build: cc -O2 -pthread -o tav tav.c -lm
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define SR 44100
#define N 1024
#define RING (SR * 4)
#define SPEC_BINS 512
#define HIST_W 256
#define HIST_H 128
#define WAVE_LEN 512
#define WAVE_XFADE 64
#define PI 3.14159265358979f
#define TAU 6.28318530717959f

static float clamp01(float v) { return v <= 0 ? 0 : (v >= 1 ? 1 : v); }
static float mixf(float a, float b, float t) { return a + (b - a) * t; }
static float smoothf(float e0, float e1, float x) {
    float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3 - 2 * t);
}
static float hash11(float p) {
    p = p * 0.1031f;
    p -= floorf(p);
    p *= p + 33.33f;
    p *= p + p;
    return p - floorf(p);
}
static double now_s(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

/* ------------------------------------------------------------------ analyzer
 * Same algorithm and constants as AudioAnalyzer.java / web/analyzer.js. */

typedef struct {
    float re[N], im[N], win[N], cosT[N / 2], sinT[N / 2];
    int rev[N];
    float mag[N / 2 + 1], prevLog[N / 2 + 1], lp[N];
    float lo[SPEC_BINS], hi[SPEC_BINS], tilt[SPEC_BINS];
    int band[4][2], beatLo, beatHi;
    float specRaw[SPEC_BINS], spec[SPEC_BINS], peak[SPEC_BINS];
    float wave[WAVE_LEN], waveS[WAVE_LEN];
    float peakDb, bandPeak[4], bands[4], levelPeak, level, wavePeak;
    float onsetHist[60];
    int onsetIdx;
    float onsetPeak, beat, sinceBeat, sinceDrop, intensity, bassFast, bassSlow, flow;
    int beatCount, silent, drop, onBeat;
    float silentFor, lastSig;
    float hist[HIST_H][HIST_W];
    int histHead;
    float histAcc;
} Analyzer;

static int bin_of(double f, double hz) {
    int nb = N / 2 + 1;
    long b = lround(f / hz);
    return (int)(b < 1 ? 1 : (b > nb - 1 ? nb - 1 : b));
}

static void an_init(Analyzer *a, float sr) {
    memset(a, 0, sizeof *a);
    int levels = 0;
    while ((1 << levels) < N) levels++;
    for (int i = 0; i < N; i++) {
        a->win[i] = 0.5f - 0.5f * cosf(TAU * i / (N - 1));
        int r = 0;
        for (int b = 0; b < levels; b++) r |= ((i >> b) & 1) << (levels - 1 - b);
        a->rev[i] = r;
    }
    for (int i = 0; i < N / 2; i++) {
        a->cosT[i] = cosf(TAU * i / N);
        a->sinT[i] = sinf(TAU * i / N);
    }
    double fMin = 35, fMax = fmin(16000, sr * 0.475), hz = sr / N;
    for (int i = 0; i < SPEC_BINS; i++) {
        a->lo[i] = (float)(fMin * pow(fMax / fMin, (i - 0.5) / (SPEC_BINS - 1)) / hz);
        a->hi[i] = (float)(fMin * pow(fMax / fMin, (i + 0.5) / (SPEC_BINS - 1)) / hz);
        a->tilt[i] = (float)(3.0 * log2(fMin * pow(fMax / fMin, (double)i / (SPEC_BINS - 1)) / 1000));
    }
    int ranges[4][2] = {{30, 150}, {150, 600}, {600, 3000}, {3000, 12000}};
    for (int b = 0; b < 4; b++) {
        a->band[b][0] = bin_of(ranges[b][0], hz);
        int e = bin_of(ranges[b][1], hz);
        a->band[b][1] = e > a->band[b][0] + 1 ? e : a->band[b][0] + 1;
        a->bandPeak[b] = 1e-3f;
    }
    a->beatLo = bin_of(30, hz);
    a->beatHi = bin_of(110, hz);
    if (a->beatHi < a->beatLo + 2) a->beatHi = a->beatLo + 2;
    a->peakDb = -40;
    a->levelPeak = 1e-3f;
    a->wavePeak = 0.05f;
    a->onsetPeak = 1e-3f;
    a->sinceBeat = a->sinceDrop = 10;
    a->silent = 1;
    a->silentFor = 10;
    a->lastSig = NAN;
}

static void fft(Analyzer *a) {
    float *re = a->re, *im = a->im;
    for (int i = 0; i < N; i++) {
        int j = a->rev[i];
        if (j > i) {
            float t = re[i]; re[i] = re[j]; re[j] = t;
            t = im[i]; im[i] = im[j]; im[j] = t;
        }
    }
    for (int size = 2; size <= N; size <<= 1) {
        int half = size >> 1, step = N / size;
        for (int i = 0; i < N; i += size)
            for (int j = i, k = 0; j < i + half; j++, k += step) {
                int l = j + half;
                float tr = re[l] * a->cosT[k] + im[l] * a->sinT[k];
                float ti = -re[l] * a->sinT[k] + im[l] * a->cosT[k];
                re[l] = re[j] - tr; im[l] = im[j] - ti;
                re[j] += tr; im[j] += ti;
            }
    }
}

static void an_process(Analyzer *a, const float *s, float dt) {
    const int nb = N / 2 + 1;
    a->drop = a->onBeat = 0;
    float sig = s[0] * 3.1f + s[N >> 1] * 1.7f + s[N - 1] + s[N >> 2] * 0.3f;
    int fresh = sig != a->lastSig;
    a->lastSig = sig;

    float sumSq = 0, maxAbs = 0;
    for (int i = 0; i < N; i++) {
        sumSq += s[i] * s[i];
        if (fabsf(s[i]) > maxAbs) maxAbs = fabsf(s[i]);
        a->re[i] = s[i] * a->win[i];
        a->im[i] = 0;
    }
    float rms = sqrtf(sumSq / N);
    a->silentFor = rms < 2e-4f ? a->silentFor + dt : 0;
    a->silent = a->silentFor > 0.6f;
    fft(a);
    for (int k = 0; k < nb; k++) a->mag[k] = hypotf(a->re[k], a->im[k]) * (4.0f / N);

    float frameMax = -120;
    for (int i = 0; i < SPEC_BINS; i++) {
        float l = a->lo[i], h = a->hi[i], m = 0;
        if (h - l < 1) {
            float x = fminf(nb - 1.001f, fmaxf(0, (l + h) * 0.5f));
            int k = (int)x;
            float f = x - k;
            m = a->mag[k] * (1 - f) + a->mag[k + 1] * f;
        } else {
            int kEnd = (int)floorf(h) < nb - 1 ? (int)floorf(h) : nb - 1;
            for (int k = (int)ceilf(l); k <= kEnd; k++) if (a->mag[k] > m) m = a->mag[k];
        }
        float db = 20 * log10f(m + 1e-9f) + a->tilt[i];
        a->specRaw[i] = db;
        if (db > frameMax) frameMax = db;
    }
    a->peakDb = fmaxf(fmaxf(frameMax, a->peakDb - dt * 4), -50);
    float range = 52, floorDb = a->peakDb - range;
    float kUp = 1 - expf(-dt * 35), kDn = 1 - expf(-dt * 7);
    for (int i = 0; i < SPEC_BINS; i++) {
        float v = clamp01((a->specRaw[i] - floorDb) / range);
        v = v * v * (1.6f - 0.6f * v);
        a->specRaw[i] = v;
        float c = a->spec[i];
        a->spec[i] = c + (v - c) * (v > c ? kUp : kDn);
        a->peak[i] = fmaxf(a->spec[i], a->peak[i] - dt * 0.35f);
    }

    float bUp = 1 - expf(-dt * 25), bDn = 1 - expf(-dt * 5), decay = expf(-dt * 0.25f);
    for (int b = 0; b < 4; b++) {
        float e = 0;
        for (int k = a->band[b][0]; k < a->band[b][1]; k++) e += a->mag[k] * a->mag[k];
        float amp = sqrtf(e);
        a->bandPeak[b] = fmaxf(fmaxf(amp, a->bandPeak[b] * decay), 2e-3f);
        float v = amp / a->bandPeak[b];
        v *= v;
        a->bands[b] += (v - a->bands[b]) * (v > a->bands[b] ? bUp : bDn);
    }
    a->levelPeak = fmaxf(fmaxf(rms, a->levelPeak * decay), 2e-3f);
    float lv = rms / a->levelPeak;
    a->level += (lv - a->level) * (lv > a->level ? bUp : bDn);

    a->sinceBeat += dt;
    a->sinceDrop += dt;
    if (fresh) {
        float flux = 0;
        for (int k = 1; k < nb; k++) {
            float l = log1pf(100 * a->mag[k]), d = l - a->prevLog[k];
            if (d > 0) flux += (k >= a->beatLo && k < a->beatHi) ? d : d * 0.01f;
            a->prevLog[k] = l;
        }
        a->onsetPeak = fmaxf(fmaxf(flux, a->onsetPeak * expf(-dt * 0.08f)), 1e-3f);
        float onset = flux / a->onsetPeak, mean = 0, sq = 0;
        for (int i = 0; i < 60; i++) { mean += a->onsetHist[i]; sq += a->onsetHist[i] * a->onsetHist[i]; }
        mean /= 60;
        float sd = sqrtf(fmaxf(0, sq / 60 - mean * mean));
        a->onsetHist[a->onsetIdx] = onset;
        a->onsetIdx = (a->onsetIdx + 1) % 60;
        if (onset > mean + 1.5f * sd && onset > 0.3f && a->sinceBeat > 0.27f && !a->silent) {
            a->beat = 1;
            a->beatCount++;
            a->sinceBeat = 0;
            a->onBeat = 1;
        }
    }
    a->beat *= expf(-dt * 5.5f);

    float bass = a->bands[0];
    a->bassFast += (bass - a->bassFast) * (1 - expf(-dt / 0.35f));
    a->bassSlow += (bass - a->bassSlow) * (1 - expf(-dt / 6));
    float target = a->silent ? 0 : 0.55f * a->bassFast + 0.45f * a->level;
    a->intensity += (target - a->intensity) * (1 - expf(-dt / 1.2f));
    if (a->onBeat && a->bassFast > 0.55f && a->bassSlow < 0.42f && a->sinceDrop > 8) {
        a->drop = 1;
        a->sinceDrop = 0;
    }
    a->flow += dt * (0.25f + 1.3f * a->intensity + 0.7f * a->beat);

    /* triggered, loop-seamless waveform */
    int limit = N - (WAVE_LEN + WAVE_XFADE), start = limit / 2;
    float acc = 0;
    for (int i = 0; i < N; i++) { acc += 0.08f * (s[i] - acc); a->lp[i] = acc; }
    for (int i = 1; i < limit; i++) if (a->lp[i - 1] < 0 && a->lp[i] >= 0) { start = i; break; }
    a->wavePeak = fmaxf(fmaxf(maxAbs, a->wavePeak * expf(-dt * 0.8f)), 0.02f);
    float g = 0.95f / a->wavePeak, ks = fminf(1, dt * 14);
    for (int i = 0; i < WAVE_LEN; i++) {
        float v = s[start + i];
        if (i < WAVE_XFADE) {
            float t = (float)i / WAVE_XFADE;
            v = s[start + i + WAVE_LEN] * (1 - t) + v * t;
        }
        v *= g;
        a->wave[i] = v;
        a->waveS[i] += (v - a->waveS[i]) * ks;
    }

    a->histAcc += dt;
    if (a->histAcc >= 1.0f / 30) {
        a->histAcc = fminf(a->histAcc - 1.0f / 30, 1.0f / 30);
        a->histHead = (a->histHead + 1) % HIST_H;
        for (int i = 0; i < HIST_W; i++) a->hist[a->histHead][i] = fmaxf(a->specRaw[2 * i], a->specRaw[2 * i + 1]);
    }
}

/* linear-interpolated lookups, x in [0, 1] */
static float spec_at(const Analyzer *a, float x) {
    float f = clamp01(x) * (SPEC_BINS - 1);
    int i = (int)f;
    if (i >= SPEC_BINS - 1) return a->spec[SPEC_BINS - 1];
    return mixf(a->spec[i], a->spec[i + 1], f - i);
}
static float peak_at(const Analyzer *a, float x) {
    float f = clamp01(x) * (SPEC_BINS - 1);
    int i = (int)f;
    if (i >= SPEC_BINS - 1) return a->peak[SPEC_BINS - 1];
    return mixf(a->peak[i], a->peak[i + 1], f - i);
}
static float wave_at(const Analyzer *a, float x) {
    x -= floorf(x);
    float f = x * WAVE_LEN;
    int i = (int)f % WAVE_LEN, j = (i + 1) % WAVE_LEN;
    return mixf(a->wave[i], a->wave[j], f - floorf(f));
}
static float hist_at(const Analyzer *a, float x, int age) {
    int row = ((a->histHead - age) % HIST_H + HIST_H) % HIST_H;
    float f = clamp01(x) * (HIST_W - 1);
    int i = (int)f;
    if (i >= HIST_W - 1) return a->hist[row][HIST_W - 1];
    return mixf(a->hist[row][i], a->hist[row][i + 1], f - i);
}
/* bass at top and bottom, treble at the sides — seamless around a circle */
static float circ_freq(float u) {
    u += 0.25f;
    u -= floorf(u);
    return 1 - fabsf(fabsf(u * 2 - 1) * 2 - 1);
}

/* ------------------------------------------------------------------ audio input */

static float ringL[RING], ringR[RING];
static long ringW;             /* total frames written */
static pthread_mutex_t ringLock = PTHREAD_MUTEX_INITIALIZER;
static volatile int audioEof, running = 1;

typedef struct {
    int fd;
    FILE *play;      /* pacat for file playback, or NULL */
    int pace;        /* no player: pace reads to real time (stdin files, --no-play) */
} AudioCfg;

static void *audio_thread(void *arg) {
    AudioCfg *cfg = arg;
    unsigned char buf[4096 + 4];
    int carry = 0;
    double t0 = now_s();
    long total = 0;
    while (running) {
        ssize_t got = read(cfg->fd, buf + carry, 4096);
        if (got <= 0) {
            if (got < 0 && errno == EINTR) continue;
            audioEof = 1;
            break;
        }
        int bytes = carry + (int)got, frames = bytes / 4;
        if (cfg->play) {
            fwrite(buf, 4, frames, cfg->play);  /* blocks: the sound card sets the pace */
            fflush(cfg->play);
        } else if (cfg->pace) {
            double ahead = (double)total / SR - (now_s() - t0);
            if (ahead > 0.02) usleep((useconds_t)((ahead - 0.01) * 1e6));
        }
        pthread_mutex_lock(&ringLock);
        for (int i = 0; i < frames; i++) {
            int16_t l = (int16_t)(buf[4 * i] | buf[4 * i + 1] << 8), r = (int16_t)(buf[4 * i + 2] | buf[4 * i + 3] << 8);
            long w = ringW % RING;
            ringL[w] = l / 32768.0f;
            ringR[w] = r / 32768.0f;
            ringW++;
        }
        pthread_mutex_unlock(&ringLock);
        total += frames;
        carry = bytes - frames * 4;
        if (carry) memmove(buf, buf + frames * 4, carry);
    }
    return NULL;
}

/* copies the N frames that end `delay` frames before the newest one */
static void audio_latest(float *mono, float *L, float *R, long delay) {
    pthread_mutex_lock(&ringLock);
    long end = ringW - delay;
    for (int i = 0; i < N; i++) {
        long idx = end - N + i;
        float l = 0, r = 0;
        if (idx >= 0 && idx > ringW - RING) {
            l = ringL[idx % RING];
            r = ringR[idx % RING];
        }
        L[i] = l;
        R[i] = r;
        mono[i] = 0.5f * (l + r);
    }
    pthread_mutex_unlock(&ringLock);
}

/* ------------------------------------------------------------------ framebuffer */

typedef struct { float r, g, b; } Col;

static int W, H, cols, rows;       /* pixel (braille dot) and cell sizes */
static float *fb, *fb2;            /* RGB float images, W*H*3 */
static float *cellAvg, *cellBlur, *cellTmp;  /* per-cell average light and its blurs */
static float unitPx;               /* pixels per centred unit */
static Col pal3[3], palTarget[3];

static Col col(float r, float g, float b) { Col c = {r, g, b}; return c; }
static Col cmix(Col a, Col b, float t) { return col(mixf(a.r, b.r, t), mixf(a.g, b.g, t), mixf(a.b, b.b, t)); }
static Col cscale(Col a, float s) { return col(a.r * s, a.g * s, a.b * s); }
static Col pal(float t) {
    t = (t - floorf(t)) * 3;
    float f = t - floorf(t);
    f = f * f * (3 - 2 * f);
    if (t < 1) return cmix(pal3[0], pal3[1], f);
    if (t < 2) return cmix(pal3[1], pal3[2], f);
    return cmix(pal3[2], pal3[0], f);
}

/* centred coordinates: the shorter axis spans [-1, 1], y up */
static float toX(float px) { return W * 0.5f + px * unitPx; }
static float toY(float py) { return H * 0.5f - py * unitPx; }
static float fromX(float x) { return (x + 0.5f - W * 0.5f) / unitPx; }
static float fromY(float y) { return (H * 0.5f - y - 0.5f) / unitPx; }

static void add_px(int x, int y, Col c, float w) {
    if ((unsigned)x >= (unsigned)W || (unsigned)y >= (unsigned)H) return;
    float *p = fb + 3 * (y * W + x);
    p[0] += c.r * w; p[1] += c.g * w; p[2] += c.b * w;
}
/* anti-aliased point splat */
static void splat(float x, float y, Col c, float w) {
    x -= 0.5f; y -= 0.5f;
    int x0 = (int)floorf(x), y0 = (int)floorf(y);
    float fx = x - x0, fy = y - y0;
    add_px(x0, y0, c, w * (1 - fx) * (1 - fy));
    add_px(x0 + 1, y0, c, w * fx * (1 - fy));
    add_px(x0, y0 + 1, c, w * (1 - fx) * fy);
    add_px(x0 + 1, y0 + 1, c, w * fx * fy);
}
static void pointP(float px, float py, Col c, float w) { splat(toX(px), toY(py), c, w); }
static void lineP(float x0, float y0, float x1, float y1, Col c, float w) {
    float ax = toX(x0), ay = toY(y0), bx = toX(x1), by = toY(y1);
    float len = hypotf(bx - ax, by - ay);
    int steps = (int)(len * 1.6f) + 1;
    if (steps > 4000) return;
    for (int i = 0; i <= steps; i++) {
        float t = (float)i / steps;
        splat(ax + (bx - ax) * t, ay + (by - ay) * t, c, w);
    }
}

/* previous frame warped (zoom about the centre, rotation) and faded */
static void feedback(float zoom, float ang, float fade) {
    float cs = cosf(ang), sn = sinf(ang);
    float cx = W * 0.5f, cy = H * 0.5f;
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float dx = (x + 0.5f - cx) * zoom, dy = (y + 0.5f - cy) * zoom;
            float sx = cx + dx * cs - dy * sn - 0.5f, sy = cy + dx * sn + dy * cs - 0.5f;
            float *o = fb2 + 3 * (y * W + x);
            int ix = (int)floorf(sx), iy = (int)floorf(sy);
            if (ix < 0 || iy < 0 || ix >= W - 1 || iy >= H - 1) { o[0] = o[1] = o[2] = 0; continue; }
            float fx = sx - ix, fy = sy - iy;
            const float *p00 = fb + 3 * (iy * W + ix), *p10 = p00 + 3, *p01 = p00 + 3 * W, *p11 = p01 + 3;
            for (int ch = 0; ch < 3; ch++) {
                float v = mixf(mixf(p00[ch], p10[ch], fx), mixf(p01[ch], p11[ch], fx), fy);
                o[ch] = fmaxf(fminf(v, 4.0f) * fade - 0.004f, 0);
            }
        }
    float *t = fb; fb = fb2; fb2 = t;
}

/* ------------------------------------------------------------------ user layer */

#define MAX_RIPPLES 8
#define MAX_STROKES 12
#define MAX_PTS 900
typedef struct { float x, y, born, s; } Ripple;
typedef struct { float x[MAX_PTS], y[MAX_PTS]; int n; float hue, born; } Stroke;

static Ripple ripples[MAX_RIPPLES];
static int nRipples;
static Stroke strokes[MAX_STROKES];
static int nStrokes, liveStroke = -1;
static float T;            /* seconds since start */
static int sym = 1;

static void add_ripple(float x, float y, float s) {
    if (nRipples == MAX_RIPPLES) { memmove(ripples, ripples + 1, sizeof(Ripple) * (MAX_RIPPLES - 1)); nRipples--; }
    ripples[nRipples++] = (Ripple){x, y, T, s};
}
static Stroke *new_stroke(void) {
    if (nStrokes == MAX_STROKES) { memmove(strokes, strokes + 1, sizeof(Stroke) * (MAX_STROKES - 1)); nStrokes--; }
    Stroke *s = &strokes[nStrokes++];
    s->n = 0;
    s->hue = hash11(T * 13.7f + nStrokes);
    s->born = T;
    return s;
}
static void stroke_add(Stroke *s, float x, float y) {
    if (s->n > 0) {
        float dx = x - s->x[s->n - 1], dy = y - s->y[s->n - 1], d = hypotf(dx, dy);
        if (d < 0.015f) return;
        int steps = (int)ceilf(d / 0.025f);
        float x0 = s->x[s->n - 1], y0 = s->y[s->n - 1];
        for (int k = 1; k <= steps && s->n < MAX_PTS; k++) {
            s->x[s->n] = x0 + dx * k / steps;
            s->y[s->n] = y0 + dy * k / steps;
            s->n++;
        }
    } else if (s->n < MAX_PTS) {
        s->x[0] = x; s->y[0] = y; s->n = 1;
    }
}

static const char *SHAPES[] = {"circle", "heart", "star", "spiral", "infinity", "hexagon", "flower"};
#define NSHAPES 7
static void add_shape(int kind, float size) {
    Stroke *s = new_stroke();
    for (int i = 0; i <= 360 && s->n < MAX_PTS; i++) {
        float t = i / 360.0f * TAU, x, y;
        switch (kind) {
        case 1: x = 16 * powf(sinf(t), 3) / 17; y = (13 * cosf(t) - 5 * cosf(2 * t) - 2 * cosf(3 * t) - cosf(4 * t)) / 17 + 0.1f; break;
        case 2: {
            float k = t / TAU * 10, f = k - floorf(k);
            int seg = (int)floorf(k) % 10;
            float r0 = seg % 2 ? 0.42f : 1, r1 = seg % 2 ? 1 : 0.42f, a0 = PI / 2 + seg * PI / 5, a1 = a0 + PI / 5;
            x = mixf(cosf(a0) * r0, cosf(a1) * r1, f);
            y = mixf(sinf(a0) * r0, sinf(a1) * r1, f);
            break;
        }
        case 3: { float r = 0.08f + 0.92f * i / 360.0f; x = cosf(t * 3) * r; y = sinf(t * 3) * r; break; }
        case 4: { float d = 1 + sinf(t) * sinf(t); x = cosf(t) / d * 1.1f; y = sinf(t) * cosf(t) / d * 1.1f; break; }
        case 5: {
            float k = t / TAU * 6, f = k - floorf(k);
            int seg = (int)floorf(k) % 6;
            float a0 = seg * PI / 3 + PI / 6, a1 = a0 + PI / 3;
            x = mixf(cosf(a0), cosf(a1), f);
            y = mixf(sinf(a0), sinf(a1), f);
            break;
        }
        case 6: { float r = 0.35f + 0.65f * fabsf(cosf(3 * t)); x = cosf(t) * r; y = sinf(t) * r; break; }
        default: x = cosf(t); y = sinf(t);
        }
        s->x[s->n] = x * size;
        s->y[s->n] = y * size;
        s->n++;
    }
}

static void draw_user(const Analyzer *a) {
    /* ripples: expanding rings, rim shaped by the waveform */
    int keep = 0;
    for (int i = 0; i < nRipples; i++) if (T - ripples[i].born < 4) ripples[keep++] = ripples[i];
    nRipples = keep;
    for (int i = 0; i < nRipples; i++) {
        Ripple *r = &ripples[i];
        float age = T - r->born, life = expf(-age * 0.9f) * smoothf(0, 0.06f, age) * r->s;
        for (int k = 0; k < 2; k++) {
            float R = fmaxf(age - k * 0.2f, 0) * (0.75f + 0.35f * a->level);
            if (R <= 0.001f) continue;
            float amp = (0.025f + 0.06f * a->level) * (1 + R);
            Col c = cscale(pal(i * 0.21f + k * 0.12f + age * 0.08f), life * (1 - 0.35f * k));
            int seg = 48 + (int)(R * 260);
            float px = 0, py = 0;
            for (int j = 0; j <= seg; j++) {
                float u = (float)j / seg, ang = u * TAU;
                float rr = R + amp * wave_at(a, u * (1 + k) + i * 0.37f + age * 0.1f);
                float x = r->x + cosf(ang) * rr, y = r->y + sinf(ang) * rr;
                if (j) lineP(px, py, x, y, c, 0.9f);
                px = x; py = y;
            }
        }
    }
    /* strokes: the waveform travels along the line you drew, repeated `sym` times */
    for (int si = 0; si < nStrokes; si++) {
        Stroke *s = &strokes[si];
        if (s->n < 2) continue;
        float len = 0;
        for (int i = 1; i < s->n; i++) len += hypotf(s->x[i] - s->x[i - 1], s->y[i] - s->y[i - 1]);
        float alpha = fminf(1, (T - s->born) * 3);
        float amp = 0.03f + 0.10f * a->level + 0.04f * a->beat;
        for (int copy = 0; copy < sym; copy++) {
            float ang = TAU * copy / sym + a->flow * 0.02f, cs = cosf(ang), sn = sinf(ang);
            float acc = 0, px = 0, py = 0;
            for (int i = 0; i < s->n; i++) {
                int i0 = i > 0 ? i - 1 : 0, i1 = i < s->n - 1 ? i + 1 : s->n - 1;
                float tx = s->x[i1] - s->x[i0], ty = s->y[i1] - s->y[i0], tl = hypotf(tx, ty);
                if (tl == 0) tl = 1;
                if (i) acc += hypotf(s->x[i] - s->x[i - 1], s->y[i] - s->y[i - 1]);
                float u = len > 0 ? acc / len : 0;
                float taper = smoothf(0, 0.04f, u) * smoothf(1, 0.96f, u);
                float w = wave_at(a, acc * 0.9f - a->flow * 0.22f) * amp * taper;
                float x = s->x[i] - ty / tl * w, y = s->y[i] + tx / tl * w;
                float rx = x * cs - y * sn, ry = x * sn + y * cs;
                if (i) {
                    Col c = cscale(pal(s->hue + u * 0.35f + copy * 0.04f + a->flow * 0.015f), alpha * (0.8f + 1.0f * spec_at(a, 0.1f + 0.6f * u)));
                    lineP(px, py, rx, ry, c, 1.0f);
                }
                px = rx; py = ry;
            }
        }
    }
}

/* ------------------------------------------------------------------ scenes */

static float seed;

static void scene_halo(const Analyzer *a) {
    feedback(0.994f - 0.012f * a->beat, 0.002f + 0.006f * a->bands[2], 0.80f);
    if (a->onBeat) add_ripple(0, 0, 0.5f + 0.5f * a->bands[0]);
    float b = a->bands[0], lv = a->level, R0 = 0.42f + 0.07f * b + 0.04f * a->beat;
    /* core */
    float rc = 0.08f + 0.11f * b + 0.03f * a->beat;
    int r0 = (int)(rc * unitPx) + 2, cx = W / 2, cy = H / 2;
    for (int y = cy - r0; y <= cy + r0; y++)
        for (int x = cx - r0; x <= cx + r0; x++) {
            float px = fromX(x), py = fromY(y), r = hypotf(px, py);
            float pl = 0.5f + 0.5f * sinf(px * 9 + a->flow * 1.3f) * sinf(py * 8 - a->flow);
            add_px(x, y, cmix(pal(0.7f), col(1, 1, 1), 0.25f * a->beat), smoothf(rc, 0, r) * (0.2f + 0.6f * pl) * (0.4f + b));
        }
    /* spectral flower */
    int seg = 220;
    float px = 0, py = 0;
    for (int i = 0; i <= seg; i++) {
        float u = (float)i / seg, ang = u * TAU - PI;
        float s = spec_at(a, 0.03f + 0.7f * circ_freq(u + a->flow * 0.004f));
        float rf = R0 * (0.5f + 0.42f * s), x = cosf(ang) * rf, y = sinf(ang) * rf;
        if (i) lineP(px, py, x, y, pal(0.6f + a->flow * 0.01f), 0.35f + 0.9f * s);
        px = x; py = y;
    }
    /* waveform ring, two counter-rotating strands */
    for (int k = 0; k < 2; k++) {
        float dir = k * 2 - 1;
        for (int i = 0; i <= 360; i++) {
            float u = i / 360.0f, ang = u * TAU - PI;
            float w = wave_at(a, u + dir * a->flow * 0.03f + k * 0.37f);
            float rw = R0 + (0.04f + 0.09f * lv) * w * (1 - 0.3f * k), x = cosf(ang) * rw, y = sinf(ang) * rw;
            if (i) lineP(px, py, x, y, pal(k * 0.33f + a->flow * 0.02f), 0.5f + 0.6f * lv);
            px = x; py = y;
        }
    }
    /* spectral rays */
    for (int k = 0; k < 72; k++) {
        float u = (k + 0.5f) / 72, ang = u * TAU - PI, m = circ_freq(u + a->flow * 0.004f);
        float sv = spec_at(a, 0.03f + 0.7f * m), sp = peak_at(a, 0.03f + 0.7f * m);
        float r1 = R0 + 0.07f, r2 = r1 + 0.03f + 0.5f * sv * sv, cs = cosf(ang), sn = sinf(ang);
        lineP(cs * r1, sn * r1, cs * r2, sn * r2, pal(0.25f + m * 0.5f + a->flow * 0.01f), 0.15f + 0.7f * sv);
        float cap = r1 + 0.04f + 0.5f * sp * sp;
        pointP(cs * cap, sn * cap, pal(0.1f + m * 0.5f), 0.8f * sp);
    }
}

static void scene_pulsar(const Analyzer *a) {
    memset(fb, 0, sizeof(float) * W * H * 3);
    float halfW = (float)W / fminf(W, H), halfH = (float)H / fminf(W, H);
    float yFront = -0.86f * halfH, yBack = 0.32f * halfH, hScale = 0.42f * fminf(halfH, 1.6f);
    static float *horizon;
    static int hw;
    if (hw != W) { free(horizon); horizon = malloc(sizeof(float) * W); hw = W; }
    for (int x = 0; x < W; x++) horizon[x] = 1e9f;   /* screen y (down) of the highest front ridge so far */
    int lines = 28;
    for (int i = 0; i < lines; i++) {
        float d = 1 - 1 / (1 + i * 0.09f), k = d / (1 - 1 / (1 + (lines - 1) * 0.09f));
        float base = mixf(yFront, yBack, k), width = halfW * (1 - 0.45f * k), fade = 1 - 0.75f * k;
        float prevY = -1;
        for (int x = 0; x < W; x++) {
            float xn = fromX(x) / width;
            if (fabsf(xn) > 1) { prevY = -1; continue; }
            float ax = fabsf(xn), env = smoothf(1, 0.3f, ax);
            float v = hist_at(a, 0.02f + 0.72f * powf(ax, 0.85f), (int)(i * 1.8f));
            float n = (hash11(floorf(xn * 14) + i * 7.1f + (a->histHead - i) * 1.3f) - 0.5f);
            float h = (v * v * (0.35f + 0.65f * env) + 0.06f * n * env * (0.3f + v)) * hScale * (1 - 0.5f * k);
            float sy = toY(base + h);
            Col c = cscale(pal(0.05f + k * 0.6f + v * 0.25f + a->flow * 0.008f), fade * (0.55f + 1.6f * v) * (i < 2 ? 1 + a->beat : 1));
            /* draw the vertical span from the previous column for continuity, only above the horizon */
            float y0 = prevY < 0 ? sy : prevY, y1 = sy;
            if (y0 > y1) { float t = y0; y0 = y1; y1 = t; }
            for (float yy = y0; yy <= y1 + 0.01f; yy += 0.7f)
                if (yy < horizon[x] - 0.5f) splat(x + 0.5f, yy, c, 0.8f);
            prevY = sy;
        }
        for (int x = 0; x < W; x++) {   /* this ridge hides everything behind and below it */
            float xn = fromX(x) / width;
            if (fabsf(xn) > 1) continue;
            float ax = fabsf(xn), env = smoothf(1, 0.3f, ax), v = hist_at(a, 0.02f + 0.72f * powf(ax, 0.85f), (int)(i * 1.8f));
            float h = v * v * (0.35f + 0.65f * env) * hScale * (1 - 0.5f * k);
            float sy = toY(base + h);
            if (sy < horizon[x]) horizon[x] = sy;
        }
    }
    /* stars twinkling with the treble */
    for (int i = 0; i < 60; i++) {
        float sx = hash11(i * 3.17f + 1) * W, sy = hash11(i * 7.31f + 2) * toY(yBack + 0.05f);
        float tw = 0.5f + 0.5f * sinf(T * (2 + 4 * hash11(i * 1.7f)) + i);
        splat(sx, sy, col(0.8f, 0.85f, 1), (0.15f + 0.6f * a->bands[3]) * tw);
    }
}

static void scene_wormhole(const Analyzer *a) {
    float ox = 0.14f * sinf(a->flow * 0.13f), oy = 0.14f * cosf(a->flow * 0.11f);
    float twist = 1.6f * sinf(a->flow * 0.05f);
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            float px = fromX(x) + ox, py = fromY(y) + oy, r = hypotf(px, py) + 1e-4f;
            float z = 1.1f / r + a->flow * 1.6f;
            float u = atan2f(py, px) / TAU + 0.5f + twist * 0.06f * z / (1 + 0.05f * z) - a->flow * 0.004f;
            u -= floorf(u);
            float m = circ_freq(u), s = spec_at(a, 0.03f + 0.75f * m);
            float near = smoothf(0.04f, 0.6f, r);
            float lane = u * 32 - floorf(u * 32), laneD = fminf(lane, 1 - lane) / 32 * TAU * r;
            float rz = z * 1.5f - floorf(z * 1.5f), ringD = fminf(rz, 1 - rz) / 1.5f * r * r / 1.1f;
            float px1 = 1.2f / unitPx;    /* one pixel in centred units */
            float gl = px1 * px1 / (laneD * laneD + px1 * px1), gr = px1 * px1 / (ringD * ringD + px1 * px1);
            Col c = cscale(pal(0.3f + m * 0.6f + z * 0.02f), gl * (0.15f + 0.9f * peak_at(a, 0.03f + 0.75f * m)) * near);
            Col c2 = cscale(pal(z * 0.04f + 0.5f), gr * (0.2f + 1.4f * a->beat + 0.5f * a->bands[0]) * near);
            float panel = smoothf(0, 2 * px1, laneD) * smoothf(0, 2 * px1, ringD);
            float blink = hash11(floorf(u * 32) * 7.3f + floorf(z * 1.5f) * 1.9f) > 1 - s * s * 0.6f;
            Col c3 = cscale(pal(z * 0.03f + m * 0.4f), panel * (0.015f + 0.12f * s * s + 0.6f * blink * s) * near * smoothf(1.6f, 0.4f, r));
            float light = 0.03f + 0.04f * a->bands[0];
            float gc = light * light / (r * r + light * light) * (0.3f + 0.9f * a->bands[0]);
            float *p = fb + 3 * (y * W + x);
            Col e = cmix(pal(0.15f), col(1, 1, 1), 0.5f);
            p[0] = c.r + c2.r + c3.r + e.r * gc;
            p[1] = c.g + c2.g + c3.g + e.g * gc;
            p[2] = c.b + c2.b + c3.b + e.b * gc;
        }
}

static void scene_skyline(const Analyzer *a) {
    feedback(1.0f, 0, 0.55f);
    float halfW = (float)W / fminf(W, H), halfH = (float)H / fminf(W, H);
    float ground = -0.42f * halfH;
    int bars = W / 3 > 96 ? 96 : W / 3;
    if (bars < 8) bars = 8;
    for (int i = 0; i < bars; i++) {
        float u = (i + 0.5f) / bars, xn = u * 2 - 1, f = 0.02f + 0.75f * powf(fabsf(xn), 1.1f);
        float s = spec_at(a, f), pk = peak_at(a, f);
        float x = xn * halfW * 0.96f, h = (0.03f + powf(s, 1.6f) * 1.3f) * halfH * 0.8f;
        Col base = pal(0.1f + fabsf(xn) * 0.5f + a->flow * 0.01f);
        for (float yy = 0; yy <= h; yy += 0.5f / unitPx) {
            float t = yy / fmaxf(h, 1e-3f);
            pointP(x, ground + yy, cmix(base, pal(0.45f + fabsf(xn) * 0.5f), t), 0.35f + 0.5f * t + 0.4f * a->beat);
            pointP(x, ground - yy * 0.45f, base, 0.25f * (1 - t) * (1 - t));   /* reflection */
        }
        pointP(x, ground + (0.03f + powf(pk, 1.6f) * 1.3f) * halfH * 0.8f + 0.02f, col(1, 1, 1), 0.9f * pk);
    }
    lineP(-halfW, ground, halfW, ground, pal(0.6f), 0.3f + 0.5f * a->bands[0]);
}

static void scene_spiro(const Analyzer *a) {
    feedback(0.988f - 0.012f * a->bands[0], 0.006f * sinf(a->flow * 0.1f), 0.86f);
    int copies = sym > 1 ? sym : 6;
    float lobes = 5 + fmodf(seed, 4), pen = 0.16f + 0.22f * a->bands[0] + 0.05f * a->beat;
    for (int c = 0; c < copies; c++) {
        float rotA = TAU * c / copies * 0.5f / lobes * 2 + a->flow * 0.03f * (c % 2 ? 1 : -1);
        float cs = cosf(rotA), sn = sinf(rotA), px = 0, py = 0;
        int steps = 700;
        for (int i = 0; i <= steps; i++) {
            float t = (float)i / steps, th = t * TAU;
            float bx = cosf(th) * (0.62f - pen * 0.5f) + cosf(-th * (lobes - 1)) * pen;
            float by = sinf(th) * (0.62f - pen * 0.5f) + sinf(-th * (lobes - 1)) * pen;
            float bl = hypotf(bx, by) + 1e-5f, w = wave_at(a, t * 3 + c * 0.173f) * (0.03f + 0.10f * a->level);
            float x = (bx + bx / bl * w) * (1 + 0.08f * a->beat), y = (by + by / bl * w) * (1 + 0.08f * a->beat);
            float rx = x * cs - y * sn, ry = x * sn + y * cs;
            if (i) {
                float s = spec_at(a, 0.05f + 0.6f * (t * lobes - floorf(t * lobes)));
                lineP(px, py, rx, ry, pal(t * 2 + (float)c / copies * 0.4f + a->flow * 0.02f), 0.05f + 0.12f * a->level + 0.25f * s * s);
            }
            px = rx; py = ry;
        }
    }
}

static void scene_nebula(const Analyzer *a) {
    feedback(0.997f - 0.006f * a->beat, -0.001f, 0.55f);
    int count = (W * H) / 9;
    if (count > 9000) count = 9000;
    float arms = 2 + fmodf(seed, 3), front = (1 - a->beat) * 1.6f;
    float tilt = 0.95f + 0.25f * sinf(T * 0.04f), ct = cosf(tilt), st = sinf(tilt);
    for (int id = 0; id < count; id++) {
        float h1 = hash11(id * 0.1371f + 1.3f), h2 = hash11(id * 0.7193f + 4.1f), h3 = hash11(id * 0.3117f + 7.7f), h4 = hash11(id * 0.917f + 2.2f), h5 = hash11(id * 0.523f + 9.1f);
        float x, y, z, rad;
        if (h1 < 0.12f) {
            float th = h2 * TAU, ph = acosf(2 * h3 - 1);
            rad = 0.05f + 0.22f * powf(h4, 1.5f);
            x = sinf(ph) * cosf(th + a->flow * 0.15f) * rad;
            y = cosf(ph) * 0.6f * rad;
            z = sinf(ph) * sinf(th + a->flow * 0.15f) * rad;
        } else {
            float arm = floorf(h5 * arms);
            rad = 0.08f + powf(h2, 0.55f) * 1.35f;
            float scatter = (h3 - 0.5f) * (0.3f + 0.9f * rad) * (0.4f + 0.6f * h4);
            float ang = arm * TAU / arms + logf(rad + 0.05f) * 2.2f + scatter + a->flow * (0.04f + 0.06f / (rad + 0.3f));
            x = cosf(ang) * rad; y = (h4 - 0.5f) * 0.08f * (1.4f - rad); z = sinf(ang) * rad;
        }
        float s = spec_at(a, clamp01(rad / 1.45f) * 0.8f + 0.03f);
        float shock = expf(-powf((rad - front) * 7, 2)) * a->beat, k = 1 + 0.12f * s + 0.25f * shock;
        x *= k; y = y * k + (h3 - 0.5f) * 0.4f * s * s; z *= k;
        float yy = y * ct - z * st, zz = y * st + z * ct;
        float zc = 2.1f - zz;
        Col c = cscale(pal(rad * 0.6f + h3 * 0.2f + a->flow * 0.01f), 0.25f + 1.3f * s);
        float core = expf(-rad * 4);
        c.r += 0.8f * core + shock * 0.8f; c.g += 0.7f * core + shock * 0.8f; c.b += 0.55f * core + shock * 0.8f;
        pointP(x / zc * 2.5f, yy / zc * 2.5f, c, (0.3f + 0.7f * h4) * 0.55f);
    }
    float gc = 0.05f + 0.06f * a->bands[0];
    int r0 = (int)(gc * 4 * unitPx);
    for (int yy = H / 2 - r0; yy <= H / 2 + r0; yy++)
        for (int xx = W / 2 - r0; xx <= W / 2 + r0; xx++) {
            float r = hypotf(fromX(xx), fromY(yy));
            add_px(xx, yy, col(1, 0.9f, 0.8f), gc * gc / (r * r + gc * gc) * (0.15f + 0.4f * a->bands[0]));
        }
}

static float scopeL[N], scopeR[N];
static void scene_scope(const Analyzer *a) {
    feedback(0.985f, 0.004f, 0.72f);
    /* stereo vectorscope: mid up, side across. Mono music draws a vertical line,
     * wide stereo blooms into clouds and knots. */
    float g = 0.95f / fmaxf(a->wavePeak, 0.05f), px = 0, py = 0;
    for (int i = 0; i < N; i++) {
        float m = (scopeL[i] + scopeR[i]) * 0.7071f * g, sd = (scopeL[i] - scopeR[i]) * 0.7071f * g;
        float ang = a->flow * 0.03f, x = sd * cosf(ang) - m * sinf(ang), y = sd * sinf(ang) + m * cosf(ang);
        x *= 0.9f; y *= 0.9f;
        if (i) lineP(px, py, x, y, pal((float)i / N + a->flow * 0.02f), 0.05f + 0.12f * a->level);
        px = x; py = y;
    }
}

typedef void (*SceneFn)(const Analyzer *);
static const struct { const char *name; SceneFn fn; } SCENES[] = {
    {"Halo", scene_halo}, {"Pulsar", scene_pulsar}, {"Wormhole", scene_wormhole}, {"Skyline", scene_skyline},
    {"Spiro", scene_spiro}, {"Nebula", scene_nebula}, {"Vectorscope", scene_scope},
};
#define NSCENES ((int)(sizeof SCENES / sizeof SCENES[0]))

static const float PALETTES[][3][3] = {
    {{1.0f, 0.18f, 0.55f}, {0.15f, 0.75f, 1.0f}, {0.55f, 0.25f, 1.0f}},
    {{1.0f, 0.45f, 0.1f}, {1.0f, 0.15f, 0.45f}, {0.45f, 0.15f, 0.85f}},
    {{0.5f, 1.0f, 0.15f}, {0.05f, 0.9f, 0.75f}, {0.9f, 1.0f, 0.3f}},
    {{0.75f, 0.9f, 1.0f}, {0.2f, 0.6f, 1.0f}, {0.35f, 0.3f, 1.0f}},
    {{1.0f, 0.2f, 0.05f}, {1.0f, 0.6f, 0.1f}, {1.0f, 0.95f, 0.5f}},
    {{1.0f, 0.4f, 0.85f}, {0.3f, 0.95f, 0.9f}, {0.65f, 0.55f, 1.0f}},
    {{0.2f, 1.0f, 0.6f}, {0.2f, 0.55f, 1.0f}, {0.85f, 0.3f, 1.0f}},
};
static const char *PALETTE_NAMES[] = {"Neon", "Sunset", "Toxic", "Ice", "Inferno", "Vapor", "Aurora"};
#define NPAL 7

/* ------------------------------------------------------------------ terminal output */

static int ttyIn = -1, colorMode = 24;   /* 24 = truecolor, 8 = xterm-256 */
static int lite;                          /* no background glow: about half the bytes per frame */
static struct termios origTerm;
static int termSaved;
static char *out;
static size_t outCap, outLen;
static uint32_t *prevCell;   /* packed glyph + colours of what is on screen */

static void emit(const char *s, size_t n) {
    if (outLen + n > outCap) {
        outCap = (outLen + n) * 2;
        out = realloc(out, outCap);
    }
    memcpy(out + outLen, s, n);
    outLen += n;
}
static void emitf(const char *fmt, ...) {
    char tmp[128];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n > 0) emit(tmp, (size_t)(n < (int)sizeof tmp ? n : (int)sizeof tmp - 1));
}
static void flush_out(void) {
    size_t off = 0;
    while (off < outLen) {
        ssize_t w = write(STDOUT_FILENO, out + off, outLen - off);
        if (w < 0) { if (errno == EINTR || errno == EAGAIN) continue; break; }
        off += (size_t)w;
    }
    outLen = 0;
}

static void restore_term(void) {
    const char *s = "\x1b[?1006l\x1b[?1002l\x1b[?1000l\x1b[0m\x1b[?25h\x1b[?1049l";
    if (write(STDOUT_FILENO, s, strlen(s)) < 0) { /* nothing more we can do */ }
    if (termSaved) tcsetattr(ttyIn, TCSANOW, &origTerm);
}
static void on_signal(int sig) {
    (void)sig;
    running = 0;
}
static volatile int resized = 1;
static void on_winch(int sig) {
    (void)sig;
    resized = 1;
}

static int xterm256(int r, int g, int b) {
    int ri = (r * 5 + 127) / 255, gi = (g * 5 + 127) / 255, bi = (b * 5 + 127) / 255;
    return 16 + 36 * ri + 6 * gi + bi;
}

static int statusTimer;
static char status[256];
static int showHelp;

static void set_status(const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(status, sizeof status, fmt, ap);
    va_end(ap);
    statusTimer = 90;
}

static void alloc_buffers(void) {
    struct winsize ws;
    if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) != 0 || ws.ws_col == 0) { ws.ws_col = 80; ws.ws_row = 24; }
    cols = ws.ws_col;
    rows = ws.ws_row;
    W = cols * 2;
    H = rows * 4;
    unitPx = fminf(W, H) * 0.5f;
    free(fb); free(fb2); free(cellAvg); free(cellBlur); free(cellTmp); free(prevCell);
    fb = calloc((size_t)W * H * 3, sizeof(float));
    fb2 = calloc((size_t)W * H * 3, sizeof(float));
    cellAvg = calloc((size_t)cols * rows * 3, sizeof(float));
    cellBlur = calloc((size_t)cols * rows * 3, sizeof(float));
    cellTmp = calloc((size_t)cols * rows * 3, sizeof(float));
    prevCell = calloc((size_t)cols * rows * 3, sizeof(uint32_t));
    emit("\x1b[0m\x1b[2J", 7);
}

static unsigned char tone(float v) {
    v = 1 - expf(-v * 1.25f);
    return (unsigned char)(clamp01(v) * 255 + 0.5f);
}

static void blur_cells(const float *src, float *dst) {
    for (int cy = 0; cy < rows; cy++)
        for (int cx = 0; cx < cols; cx++) {
            float acc[3] = {0, 0, 0}, wsum = 0;
            for (int oy = -1; oy <= 1; oy++)
                for (int ox = -1; ox <= 1; ox++) {
                    int x = cx + ox, y = cy + oy;
                    if (x < 0 || y < 0 || x >= cols || y >= rows) continue;
                    float w = (ox == 0 && oy == 0) ? 2 : 1;
                    const float *p = src + 3 * (y * cols + x);
                    acc[0] += p[0] * w; acc[1] += p[1] * w; acc[2] += p[2] * w;
                    wsum += w;
                }
            float *d = dst + 3 * (cy * cols + cx);
            d[0] = acc[0] / wsum; d[1] = acc[1] / wsum; d[2] = acc[2] / wsum;
        }
}

/* framebuffer -> braille cells with glowing truecolor backgrounds */
static void present(void) {
    static const int dotBit[4][2] = {{0x01, 0x08}, {0x02, 0x10}, {0x04, 0x20}, {0x40, 0x80}};
    static const float bayer[4][2] = {{0, 4}, {6, 2}, {1, 5}, {7, 3}};
    for (int cy = 0; cy < rows; cy++)
        for (int cx = 0; cx < cols; cx++) {
            float s[3] = {0, 0, 0};
            for (int dy = 0; dy < 4; dy++)
                for (int dx = 0; dx < 2; dx++) {
                    const float *p = fb + 3 * ((cy * 4 + dy) * W + cx * 2 + dx);
                    s[0] += p[0]; s[1] += p[1]; s[2] += p[2];
                }
            float *c = cellAvg + 3 * (cy * cols + cx);
            c[0] = s[0] / 8; c[1] = s[1] / 8; c[2] = s[2] / 8;
        }
    /* cheap glow: two 3x3 blurs of the cell light; the result lights the cell background */
    blur_cells(cellAvg, cellBlur);
    blur_cells(cellBlur, cellTmp);

    int statusRow = (statusTimer > 0 || showHelp) ? rows - 1 : -1;
    emit("\x1b[?2026h", 8);
    int cursorX = -1, cursorY = -1;
    int lastFg = -1, lastBg = -1;
    for (int cy = 0; cy < rows; cy++) {
        if (cy == statusRow) continue;
        for (int cx = 0; cx < cols; cx++) {
            int bits = 0;
            float fr = 0, fg = 0, fbb = 0, lit = 0;
            for (int dy = 0; dy < 4; dy++)
                for (int dx = 0; dx < 2; dx++) {
                    const float *p = fb + 3 * ((cy * 4 + dy) * W + cx * 2 + dx);
                    float L = fmaxf(p[0], fmaxf(p[1], p[2]));
                    float thr = 0.10f + (bayer[dy][dx] + 0.5f) / 8 * 0.45f;
                    if (L > thr) {
                        bits |= dotBit[dy][dx];
                        fr += p[0]; fg += p[1]; fbb += p[2];
                        lit++;
                    }
                }
            const float *bgp = cellTmp + 3 * (cy * cols + cx);
            /* coarse background steps keep runs of equal colour long: far fewer escape codes */
            int br = tone(bgp[0] * 0.55f) & 0xF0, bgg = tone(bgp[1] * 0.55f) & 0xF0, bb = tone(bgp[2] * 0.55f) & 0xF0;
            int bg = lite ? 0 : (br << 16) | (bgg << 8) | bb;
            int fgc = 0;
            if (bits) {
                float k = 1.35f / lit;
                int r = tone(fr * k) & 0xF8, g = tone(fg * k) & 0xF8, b = tone(fbb * k) & 0xF8;
                fgc = (r << 16) | (g << 8) | b;
            }
            uint32_t *pc = prevCell + 3 * (cy * cols + cx);
            uint32_t glyph = bits ? 0x2800u + bits : ' ';
            if (pc[0] == glyph && pc[1] == (uint32_t)fgc && pc[2] == (uint32_t)bg) continue;
            pc[0] = glyph; pc[1] = fgc; pc[2] = bg;
            if (cursorY != cy || cursorX != cx) emitf("\x1b[%d;%dH", cy + 1, cx + 1);
            if (bg != lastBg) {
                if (bg == 0) emit("\x1b[49m", 5);
                else if (colorMode == 24) emitf("\x1b[48;2;%d;%d;%dm", bg >> 16, (bg >> 8) & 255, bg & 255);
                else emitf("\x1b[48;5;%dm", xterm256(bg >> 16, (bg >> 8) & 255, bg & 255));
                lastBg = bg;
            }
            if (bits) {
                if (fgc != lastFg) {
                    if (colorMode == 24) emitf("\x1b[38;2;%d;%d;%dm", fgc >> 16, (fgc >> 8) & 255, fgc & 255);
                    else emitf("\x1b[38;5;%dm", xterm256(fgc >> 16, (fgc >> 8) & 255, fgc & 255));
                    lastFg = fgc;
                }
                char u[3] = {(char)(0xE0 | (glyph >> 12)), (char)(0x80 | ((glyph >> 6) & 0x3F)), (char)(0x80 | (glyph & 0x3F))};
                emit(u, 3);
            } else {
                emit(" ", 1);
            }
            cursorX = cx + 1;
            cursorY = cy;
        }
    }
    if (statusRow >= 0) {
        const char *help = " space/\xe2\x86\x92 next  1-7 scene  tap ripple  drag draw  s shape  m mirror  c clear  k colours  a autopilot  q quit ";
        const char *txt = showHelp ? help : status;
        emitf("\x1b[%d;1H\x1b[0m\x1b[38;2;235;235;245m\x1b[48;2;20;16;34m\x1b[2K %s", rows, txt);
        emit("\x1b[0m", 4);
        for (int cx = 0; cx < cols; cx++) prevCell[3 * ((rows - 1) * cols + cx)] = 0xFFFFFFFFu;   /* repaint after */
        lastFg = lastBg = -1;
    }
    emit("\x1b[?2026l", 8);
    flush_out();
}

/* ------------------------------------------------------------------ input */

static int scene, autopilot = 1, palIdx;
static float sceneTimer;
static int dragging, downX, downY, moved;

static int rebaseFlow;

static void set_scene(int i) {
    scene = ((i % NSCENES) + NSCENES) % NSCENES;
    sceneTimer = 0;
    rebaseFlow = 1;
    set_status("%s   (%d/%d)   press h for help", SCENES[scene].name, scene + 1, NSCENES);
}

static void handle_mouse(int b, int x, int y, int press) {
    float px = fromX((x - 1) * 2 + 1), py = fromY((y - 1) * 4 + 2);
    if (b == 0 && press) {              /* left button down */
        dragging = 1; moved = 0; downX = x; downY = y;
    } else if (b == 32 && dragging) {   /* drag */
        if (!moved && (abs(x - downX) + abs(y - downY)) >= 1) {
            moved = 1;
            Stroke *s = new_stroke();
            liveStroke = (int)(s - strokes);
            stroke_add(s, fromX((downX - 1) * 2 + 1), fromY((downY - 1) * 4 + 2));
        }
        if (liveStroke >= 0 && liveStroke < nStrokes) stroke_add(&strokes[liveStroke], px, py);
    } else if (!press) {                /* release */
        if (dragging && !moved) add_ripple(px, py, 1);
        dragging = 0;
        liveStroke = -1;
    } else if (b == 64 || b == 65) {    /* wheel: scenes */
        set_scene(scene + (b == 64 ? -1 : 1));
    }
}

static void handle_input(void) {
    static unsigned char buf[512];
    static int have;
    ssize_t n = read(ttyIn, buf + have, sizeof buf - have);
    if (n > 0) have += (int)n;
    int i = 0;
    while (i < have) {
        unsigned char c = buf[i];
        if (c == 0x1b) {
            if (i + 1 >= have) { if (n <= 0) { running = 0; i++; } break; }   /* lone ESC quits */
            if (buf[i + 1] == '[' && i + 2 < have && buf[i + 2] == '<') {    /* SGR mouse */
                int j = i + 3, vals[3] = {0, 0, 0}, k = 0;
                while (j < have && k < 3) {
                    if (buf[j] >= '0' && buf[j] <= '9') vals[k] = vals[k] * 10 + (buf[j] - '0');
                    else if (buf[j] == ';') k++;
                    else break;
                    j++;
                }
                if (j >= have) break;   /* incomplete */
                handle_mouse(vals[0], vals[1], vals[2], buf[j] == 'M');
                i = j + 1;
                continue;
            }
            if (buf[i + 1] == '[' && i + 2 < have) {
                char k = buf[i + 2];
                if (k == 'C') set_scene(scene + 1);
                else if (k == 'D') set_scene(scene - 1);
                i += 3;
                continue;
            }
            if (buf[i + 1] == '[') break;
            running = 0;
            i++;
            continue;
        }
        switch (c) {
        case 'q': case 3: running = 0; break;
        case ' ': case 'n': set_scene(scene + 1); break;
        case 'p': set_scene(scene - 1); break;
        case 'h': case '?': showHelp = !showHelp; break;
        case 'a': autopilot = !autopilot; set_status("Autopilot %s", autopilot ? "on" : "off"); break;
        case 'c': nStrokes = 0; nRipples = 0; liveStroke = -1; set_status("Cleared"); break;
        case 'm': {
            static const int syms[] = {1, 2, 3, 4, 6, 8, 12};
            int k = 0;
            while (k < 7 && syms[k] != sym) k++;
            sym = syms[(k + 1) % 7];
            set_status(sym == 1 ? "Mirror off" : "Mirror x%d", sym);
            break;
        }
        case 'k':
            palIdx = (palIdx + 1) % NPAL;
            for (int j = 0; j < 3; j++) palTarget[j] = col(PALETTES[palIdx][j][0], PALETTES[palIdx][j][1], PALETTES[palIdx][j][2]);
            set_status("Colours: %s", PALETTE_NAMES[palIdx]);
            break;
        case 's': {
            static int nextShape;
            add_shape(nextShape, 0.62f);
            set_status("Shape: %s  (s for the next one, c to clear)", SHAPES[nextShape]);
            nextShape = (nextShape + 1) % NSHAPES;
            break;
        }
        default:
            if (c >= '1' && c <= '0' + NSCENES) set_scene(c - '1');
        }
        i++;
    }
    memmove(buf, buf + i, have - i);
    have -= i;
}

/* ------------------------------------------------------------------ setup */

static FILE *spawned[2];
static pid_t spawnedPid[2];

/* popen() variant that remembers the pid so we can kill it on exit */
static int spawn(const char *cmd, int wantWrite, int slot) {
    int fds[2];
    if (pipe(fds) < 0) return -1;
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (wantWrite) dup2(fds[0], STDIN_FILENO);
        else dup2(fds[1], STDOUT_FILENO);
        int devnull = open("/dev/null", O_WRONLY);
        if (devnull >= 0) dup2(devnull, STDERR_FILENO);
        close(fds[0]); close(fds[1]);
        execl("/bin/sh", "sh", "-c", cmd, (char *)NULL);
        const char *sh = getenv("PREFIX");
        if (sh) {
            char path[512];
            snprintf(path, sizeof path, "%s/bin/sh", sh);
            execl(path, "sh", "-c", cmd, (char *)NULL);
        }
        _exit(127);
    }
    spawnedPid[slot] = pid;
    if (wantWrite) {
        close(fds[0]);
        spawned[slot] = fdopen(fds[1], "w");
        return fds[1];
    }
    close(fds[1]);
    return fds[0];
}

static int have_cmd(const char *name) {
    char cmd[256];
    snprintf(cmd, sizeof cmd, "command -v %s >/dev/null 2>&1", name);
    return system(cmd) == 0;
}

static void ensure_pulse(void) {
    if (system("pactl info >/dev/null 2>&1") == 0) return;
    if (system("pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1") != 0) return;
    for (int i = 0; i < 20 && system("pactl info >/dev/null 2>&1") != 0; i++) usleep(100000);
}

static void shell_quote(char *dst, size_t cap, const char *s) {
    size_t o = 0;
    if (o < cap - 1) dst[o++] = '\'';
    for (; *s && o < cap - 6; s++) {
        if (*s == '\'') { memcpy(dst + o, "'\\''", 4); o += 4; }
        else dst[o++] = *s;
    }
    if (o < cap - 1) dst[o++] = '\'';
    dst[o] = 0;
}

static void usage(void) {
    puts("tav — Termux Audio Visualizer (terminal edition)\n\n"
         "  tav                 visualize whatever plays through PulseAudio (mpv, ncspot, ...)\n"
         "  tav song.mp3        play a file and visualize it (needs ffmpeg)\n"
         "  tav --mic           listen through the microphone (Termux:API mic permission)\n"
         "  tav --stdin         read raw s16le 44.1 kHz stereo from stdin\n"
         "  tav --cmd 'CMD'     read raw PCM from any command\n\n"
         "options:  --scene N   --fps N   --lite (no glow, for slow terminals)   --256 (no truecolor)\n"
         "          --no-play (files: draw without sound)   --delay MS (audio/visual sync)\n\n"
         "keys: space/arrows scenes, 1-7 pick, s shapes, m mirror, c clear, k colours, a autopilot, h help, q quit\n"
         "touch/mouse: tap = ripple, drag = draw with sound");
}

int main(int argc, char **argv) {
    const char *file = NULL, *cmd = NULL;
    int useStdin = 0, mic = 0, noPlay = 0, fps = 30;
    long delayMs = -1;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) { usage(); return 0; }
        else if (!strcmp(argv[i], "--stdin")) useStdin = 1;
        else if (!strcmp(argv[i], "--mic")) mic = 1;
        else if (!strcmp(argv[i], "--no-play")) noPlay = 1;
        else if (!strcmp(argv[i], "--256")) colorMode = 8, lite = 1;   /* the 256-colour cube is too coarse for dim glow */
        else if (!strcmp(argv[i], "--lite")) lite = 1;
        else if (!strcmp(argv[i], "--cmd") && i + 1 < argc) cmd = argv[++i];
        else if (!strcmp(argv[i], "--fps") && i + 1 < argc) fps = atoi(argv[++i]);
        else if (!strcmp(argv[i], "--delay") && i + 1 < argc) delayMs = atol(argv[++i]);
        else if (!strcmp(argv[i], "--scene") && i + 1 < argc) scene = (atoi(argv[++i]) - 1 + NSCENES) % NSCENES;
        else if (argv[i][0] == '-') { fprintf(stderr, "unknown option %s (try --help)\n", argv[i]); return 2; }
        else file = argv[i];
    }
    if (fps < 5) fps = 5;
    if (fps > 120) fps = 120;

    AudioCfg cfg = {-1, NULL, 0};
    char shellCmd[4096], q[3000];
    const char *sourceName = "PulseAudio monitor";
    if (useStdin) {
        cfg.fd = STDIN_FILENO;
        cfg.pace = !isatty(STDIN_FILENO) && noPlay;
        sourceName = "stdin";
    } else if (cmd) {
        cfg.fd = spawn(cmd, 0, 0);
        sourceName = "command";
    } else if (file) {
        if (!have_cmd("ffmpeg")) { fprintf(stderr, "tav: playing files needs ffmpeg (pkg install ffmpeg)\n"); return 1; }
        shell_quote(q, sizeof q, file);
        snprintf(shellCmd, sizeof shellCmd, "exec ffmpeg -nostdin -loglevel error -i %s -f s16le -ac 2 -ar 44100 -", q);
        cfg.fd = spawn(shellCmd, 0, 0);
        if (!noPlay && have_cmd("pacat")) {
            ensure_pulse();
            spawn("exec pacat --raw --format=s16le --rate=44100 --channels=2 --latency-msec=60", 1, 1);
            cfg.play = spawned[1];
            if (delayMs < 0) delayMs = 90;
        } else {
            cfg.pace = 1;
        }
        const char *base = strrchr(file, '/');
        sourceName = base ? base + 1 : file;
    } else {
        if (!have_cmd("parec")) {
            fprintf(stderr, "tav: needs PulseAudio (pkg install pulseaudio), or use: tav song.mp3 / --stdin\n");
            return 1;
        }
        ensure_pulse();
        const char *dev = "@DEFAULT_MONITOR@";
        if (mic) {
            if (system("pactl list short sources 2>/dev/null | grep -q tav_mic") != 0)
                if (system("pactl load-module module-sles-source source_name=tav_mic >/dev/null 2>&1") != 0)
                    fprintf(stderr, "tav: could not load the OpenSL ES microphone source\n");
            dev = "tav_mic";
            sourceName = "microphone";
        }
        snprintf(shellCmd, sizeof shellCmd,
                 "exec parec --raw --format=s16le --rate=44100 --channels=2 --latency-msec=20 -d %s", dev);
        cfg.fd = spawn(shellCmd, 0, 0);
    }
    if (cfg.fd < 0) { fprintf(stderr, "tav: could not start the audio source\n"); return 1; }
    if (delayMs < 0) delayMs = 0;

    ttyIn = open("/dev/tty", O_RDONLY | O_NONBLOCK);
    if (ttyIn < 0 || !isatty(STDOUT_FILENO)) { fprintf(stderr, "tav: needs an interactive terminal\n"); return 1; }
    if (tcgetattr(ttyIn, &origTerm) == 0) {
        termSaved = 1;
        struct termios raw = origTerm;
        raw.c_lflag &= ~(ICANON | ECHO);
        raw.c_cc[VMIN] = 0;
        raw.c_cc[VTIME] = 0;
        tcsetattr(ttyIn, TCSANOW, &raw);
    }
    atexit(restore_term);
    signal(SIGINT, on_signal);
    signal(SIGTERM, on_signal);
    signal(SIGHUP, on_signal);
    signal(SIGPIPE, SIG_IGN);
    signal(SIGWINCH, on_winch);
    const char *init = "\x1b[?1049h\x1b[?25l\x1b[?1000h\x1b[?1002h\x1b[?1006h\x1b[2J";
    if (write(STDOUT_FILENO, init, strlen(init)) < 0) return 1;

    pthread_t th;
    pthread_create(&th, NULL, audio_thread, &cfg);

    static Analyzer an;
    an_init(&an, SR);
    for (int j = 0; j < 3; j++) pal3[j] = palTarget[j] = col(PALETTES[0][j][0], PALETTES[0][j][1], PALETTES[0][j][2]);
    float mono[N];
    set_scene(scene);
    set_status("%s  \xc2\xb7  listening to %s  \xc2\xb7  h for help", SCENES[scene].name, sourceName);

    double last = now_s(), frameDur = 1.0 / fps;
    unsigned rng = 12345;
    while (running) {
        double t = now_s();
        float dt = (float)fmin(0.1, t - last);
        last = t;
        if (resized) { resized = 0; alloc_buffers(); }
        handle_input();
        audio_latest(mono, scopeL, scopeR, delayMs * SR / 1000);
        an_process(&an, mono, dt);
        T += dt;
        /* keep the float music clock small: rebase on scene changes, wrap as a safety net */
        if (rebaseFlow && an.flow > 400) an.flow = 0;
        rebaseFlow = 0;
        if (an.flow > 2000) an.flow -= 2000;
        sceneTimer += dt;
        if (an.drop) seed = fmodf(seed + 1, 64);
        if (autopilot && ((an.drop && sceneTimer > 12) || sceneTimer > 45)) {
            rng = rng * 1103515245u + 12345u;
            int next = (int)((rng >> 16) % (NSCENES - 1));
            set_scene(next >= scene ? next + 1 : next);
        }
        for (int j = 0; j < 3; j++) {
            float k = 1 - expf(-dt * 1.5f);
            pal3[j] = cmix(pal3[j], palTarget[j], k);
        }
        SCENES[scene].fn(&an);
        draw_user(&an);
        if (statusTimer > 0) statusTimer--;
        present();
        if (audioEof && file) {
            long w;
            pthread_mutex_lock(&ringLock);
            w = ringW;
            pthread_mutex_unlock(&ringLock);
            static long lastW = -1;
            static double since;
            if (w == lastW) since += dt; else since = 0;
            lastW = w;
            if (since > 1.5) running = 0;   /* song finished */
        }
        double spare = frameDur - (now_s() - t);
        if (spare > 0) {
            struct pollfd pfd = {ttyIn, POLLIN, 0};
            poll(&pfd, 1, (int)(spare * 1000));
        }
    }
    running = 0;
    for (int i = 0; i < 2; i++) if (spawnedPid[i] > 0) kill(spawnedPid[i], SIGTERM);
    if (cfg.play) fclose(cfg.play);
    close(cfg.fd);
    restore_term();
    termSaved = 0;
    for (int i = 0; i < 2; i++) if (spawnedPid[i] > 0) waitpid(spawnedPid[i], NULL, 0);
    return 0;
}
