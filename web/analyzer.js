// TAV audio analyzer — JavaScript twin of android/src/.../AudioAnalyzer.java.
// Turns the newest N mono samples into everything the shaders consume:
// a log-frequency spectrum, peaks, a triggered loop-seamless waveform,
// four auto-gained bands, a beat pulse, intensity and drop events.
'use strict';

const SPEC_BINS = 512, HIST_W = 256, HIST_H = 128, WAVE_LEN = 512, WAVE_XFADE = 64;

class FFT {
  constructor(n) {
    this.n = n;
    this.levels = Math.log2(n) | 0;
    this.cos = new Float32Array(n / 2);
    this.sin = new Float32Array(n / 2);
    for (let i = 0; i < n / 2; i++) {
      this.cos[i] = Math.cos(2 * Math.PI * i / n);
      this.sin[i] = Math.sin(2 * Math.PI * i / n);
    }
    this.rev = new Uint32Array(n);
    for (let i = 0; i < n; i++) {
      let r = 0;
      for (let b = 0; b < this.levels; b++) r |= ((i >> b) & 1) << (this.levels - 1 - b);
      this.rev[i] = r;
    }
  }
  // In-place iterative radix-2 Cooley–Tukey.
  transform(re, im) {
    const n = this.n;
    for (let i = 0; i < n; i++) {
      const j = this.rev[i];
      if (j > i) {
        let t = re[i]; re[i] = re[j]; re[j] = t;
        t = im[i]; im[i] = im[j]; im[j] = t;
      }
    }
    for (let size = 2; size <= n; size <<= 1) {
      const half = size >> 1, step = n / size;
      for (let i = 0; i < n; i += size) {
        for (let j = i, k = 0; j < i + half; j++, k += step) {
          const l = j + half;
          const tr = re[l] * this.cos[k] + im[l] * this.sin[k];
          const ti = -re[l] * this.sin[k] + im[l] * this.cos[k];
          re[l] = re[j] - tr; im[l] = im[j] - ti;
          re[j] += tr; im[j] += ti;
        }
      }
    }
  }
}

class Analyzer {
  constructor(sampleRate, n) {
    this.sr = sampleRate; this.n = n;
    this.fft = new FFT(n);
    this.re = new Float32Array(n); this.im = new Float32Array(n);
    this.win = new Float32Array(n);
    for (let i = 0; i < n; i++) this.win[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
    const nb = n / 2 + 1;
    this.mag = new Float32Array(nb); this.prevLog = new Float32Array(nb);

    // log-frequency display mapping with +3 dB/octave tilt (music is roughly pink)
    const fMin = 35, fMax = Math.min(16000, sampleRate * 0.475), hz = sampleRate / n;
    this.lo = new Float32Array(SPEC_BINS); this.hi = new Float32Array(SPEC_BINS); this.tilt = new Float32Array(SPEC_BINS);
    for (let i = 0; i < SPEC_BINS; i++) {
      const f = (x) => fMin * Math.pow(fMax / fMin, x / (SPEC_BINS - 1));
      this.lo[i] = f(i - 0.5) / hz; this.hi[i] = f(i + 0.5) / hz;
      this.tilt[i] = 3.0 * Math.log2(f(i) / 1000);
    }
    const bin = (f) => Math.max(1, Math.min(nb - 1, Math.round(f / hz)));
    this.bandRange = [[30, 150], [150, 600], [600, 3000], [3000, 12000]].map(([a, b]) => [bin(a), Math.max(bin(a) + 1, bin(b))]);
    this.beatRange = [bin(30), Math.max(bin(30) + 2, bin(110))];

    this.specRaw = new Float32Array(SPEC_BINS);
    this.spec = new Float32Array(SPEC_BINS);
    this.peak = new Float32Array(SPEC_BINS);
    this.wave = new Float32Array(WAVE_LEN);
    this.waveS = new Float32Array(WAVE_LEN);
    this.lp = new Float32Array(n);
    this.peakDb = -40;
    this.bandPeak = [1e-3, 1e-3, 1e-3, 1e-3];
    this.bands = [0, 0, 0, 0];
    this.levelPeak = 1e-3; this.level = 0;
    this.wavePeak = 0.05;
    this.onsetHist = new Float32Array(60); this.onsetIdx = 0; this.onsetPeak = 1e-3;
    this.beat = 0; this.beatCount = 0; this.sinceBeat = 10;
    this.intensity = 0; this.bassFast = 0; this.bassSlow = 0; this.sinceDrop = 10;
    this.flow = 0; this.time = 0;
    this.silent = true; this.silentFor = 10;
    this.drop = false; this.onBeat = false;
    this.lastSig = NaN; this.staleFor = 0; this.zeros = new Float32Array(n);

    this.audioBytes = new Uint8Array(SPEC_BINS * 4);
    this.histRow = new Uint8Array(HIST_W); this.histHead = 0; this.histAcc = 0; this.histPushed = false;
  }

  /** samples: Float32Array of the newest n mono samples (oldest first). */
  process(samples, dt) {
    const n = this.n, nb = n / 2 + 1;
    this.time += dt;
    this.drop = false; this.onBeat = false;

    // Visualizer polling can hand us the same buffer twice; flux must ignore that.
    const sig = samples[0] * 3.1 + samples[n >> 1] * 1.7 + samples[n - 1] + samples[n >> 2] * 0.3;
    const fresh = sig !== this.lastSig; this.lastSig = sig;
    // A source that stops advancing (suspended context, dead mic) keeps handing over its last
    // buffer; auto-gain would pin that at full intensity. After 0.5 s of it, listen to silence.
    this.staleFor = fresh ? 0 : this.staleFor + dt;
    if (this.staleFor > 0.5) samples = this.zeros;

    let sumSq = 0, maxAbs = 0;
    for (let i = 0; i < n; i++) {
      const s = samples[i];
      sumSq += s * s; const a = Math.abs(s); if (a > maxAbs) maxAbs = a;
      this.re[i] = s * this.win[i]; this.im[i] = 0;
    }
    const rms = Math.sqrt(sumSq / n);
    this.silentFor = rms < 2e-4 ? this.silentFor + dt : 0;
    this.silent = this.silentFor > 0.6;

    this.fft.transform(this.re, this.im);
    const norm = 4 / n;    // Hann coherent gain 0.5 → full-scale sine ≈ 1.0
    let frameMaxDb = -120;
    for (let k = 0; k < nb; k++) this.mag[k] = Math.hypot(this.re[k], this.im[k]) * norm;

    // --- display spectrum ---
    for (let i = 0; i < SPEC_BINS; i++) {
      const lo = this.lo[i], hi = this.hi[i];
      let m;
      if (hi - lo < 1) {
        const x = Math.min(nb - 1.001, Math.max(0, (lo + hi) * 0.5)), k = x | 0, f = x - k;
        m = this.mag[k] * (1 - f) + this.mag[k + 1] * f;
      } else {
        m = 0;
        for (let k = Math.ceil(lo); k <= Math.min(nb - 1, Math.floor(hi)); k++) if (this.mag[k] > m) m = this.mag[k];
      }
      const db = 20 * Math.log10(m + 1e-9) + this.tilt[i];
      this.specRaw[i] = db;
      if (db > frameMaxDb) frameMaxDb = db;
    }
    this.peakDb = Math.max(frameMaxDb, this.peakDb - dt * 4, -50);
    const range = 52, floorDb = this.peakDb - range;
    const kUp = 1 - Math.exp(-dt * 35), kDn = 1 - Math.exp(-dt * 7);
    for (let i = 0; i < SPEC_BINS; i++) {
      let v = (this.specRaw[i] - floorDb) / range;
      v = v <= 0 ? 0 : v >= 1 ? 1 : v;
      v = v * v * (1.6 - 0.6 * v);      // a touch of contrast
      this.specRaw[i] = v;
      const s = this.spec[i];
      this.spec[i] = s + (v - s) * (v > s ? kUp : kDn);
      this.peak[i] = Math.max(this.spec[i], this.peak[i] - dt * 0.35);
    }

    // --- bands, level (auto-gained against their own recent peaks) ---
    const bUp = 1 - Math.exp(-dt * 25), bDn = 1 - Math.exp(-dt * 5);
    for (let b = 0; b < 4; b++) {
      const [k0, k1] = this.bandRange[b];
      let e = 0;
      for (let k = k0; k < k1; k++) e += this.mag[k] * this.mag[k];
      const amp = Math.sqrt(e);
      this.bandPeak[b] = Math.max(amp, this.bandPeak[b] * Math.exp(-dt * 0.25), 2e-3);
      let v = amp / this.bandPeak[b]; v = v * v;
      const c = this.bands[b];
      this.bands[b] = c + (v - c) * (v > c ? bUp : bDn);
    }
    this.levelPeak = Math.max(rms, this.levelPeak * Math.exp(-dt * 0.25), 2e-3);
    const lv = rms / this.levelPeak;
    this.level += (lv - this.level) * (lv > this.level ? bUp : bDn);

    // --- beat: bass spectral flux against an adaptive threshold ---
    this.sinceBeat += dt; this.sinceDrop += dt;
    if (fresh) {
      const [k0, k1] = this.beatRange;
      let flux = 0;
      for (let k = 1; k < nb; k++) {
        const l = Math.log1p(100 * this.mag[k]);
        const d = l - this.prevLog[k];
        if (d > 0) flux += k >= k0 && k < k1 ? d : d * 0.01;
        this.prevLog[k] = l;
      }
      this.onsetPeak = Math.max(flux, this.onsetPeak * Math.exp(-dt * 0.08), 1e-3);
      const onset = flux / this.onsetPeak;
      let mean = 0, sq = 0;
      const h = this.onsetHist;
      for (let i = 0; i < h.length; i++) { mean += h[i]; sq += h[i] * h[i]; }
      mean /= h.length;
      const sd = Math.sqrt(Math.max(0, sq / h.length - mean * mean));
      h[this.onsetIdx] = onset; this.onsetIdx = (this.onsetIdx + 1) % h.length;
      if (onset > mean + 1.5 * sd && onset > 0.3 && this.sinceBeat > 0.27 && !this.silent) {
        this.beat = 1; this.beatCount++; this.sinceBeat = 0; this.onBeat = true;
      }
    }
    this.beat *= Math.exp(-dt * 5.5);

    // --- intensity, drops and the music clock ---
    const bass = this.bands[0];
    this.bassFast += (bass - this.bassFast) * (1 - Math.exp(-dt / 0.35));
    this.bassSlow += (bass - this.bassSlow) * (1 - Math.exp(-dt / 6));
    const target = this.silent ? 0 : 0.55 * this.bassFast + 0.45 * this.level;
    this.intensity += (target - this.intensity) * (1 - Math.exp(-dt / 1.2));
    if (this.onBeat && this.bassFast > 0.55 && this.bassSlow < 0.42 && this.sinceDrop > 8) {
      this.drop = true; this.sinceDrop = 0;
    }
    this.flow += dt * (0.25 + 1.3 * this.intensity + 0.7 * this.beat);

    this.buildWave(samples, maxAbs, dt);
    this.pack(dt);
  }

  // Oscilloscope-style trigger on a rising zero crossing of the low-passed signal,
  // then crossfade the tail into the head so the 512 samples loop seamlessly.
  buildWave(samples, maxAbs, dt) {
    const n = this.n, need = WAVE_LEN + WAVE_XFADE, limit = n - need;
    let acc = 0; const lp = this.lp;
    for (let i = 0; i < n; i++) { acc += 0.08 * (samples[i] - acc); lp[i] = acc; }
    let start = Math.max(0, limit >> 1);
    for (let i = 1; i < limit; i++) if (lp[i - 1] < 0 && lp[i] >= 0) { start = i; break; }
    this.wavePeak = Math.max(maxAbs, this.wavePeak * Math.exp(-dt * 0.8), 0.02);
    const g = 0.95 / this.wavePeak, ks = Math.min(1, dt * 14);
    for (let i = 0; i < WAVE_LEN; i++) {
      let s = samples[start + i];
      if (i < WAVE_XFADE) {
        const t = i / WAVE_XFADE;
        s = samples[start + i + WAVE_LEN] * (1 - t) + s * t;
      }
      s *= g;
      this.wave[i] = s;
      this.waveS[i] += (s - this.waveS[i]) * ks;
    }
  }

  pack(dt) {
    const B = this.audioBytes, q = (v) => (v <= 0 ? 0 : v >= 1 ? 255 : (v * 255 + 0.5) | 0);
    for (let i = 0; i < SPEC_BINS; i++) {
      B[i] = q(this.spec[i]);
      B[SPEC_BINS + i] = q(this.peak[i]);
      B[2 * SPEC_BINS + i] = q(this.wave[i] * 0.5 + 0.5);
      B[3 * SPEC_BINS + i] = q(this.waveS[i] * 0.5 + 0.5);
    }
    this.histPushed = false;
    this.histAcc += dt;
    if (this.histAcc >= 1 / 30) {
      this.histAcc = Math.min(this.histAcc - 1 / 30, 1 / 30);
      for (let i = 0; i < HIST_W; i++) this.histRow[i] = q(Math.max(this.specRaw[2 * i], this.specRaw[2 * i + 1]));
      this.histHead = (this.histHead + 1) % HIST_H;
      this.histPushed = true;
    }
  }
}

if (typeof module !== 'undefined') module.exports = { Analyzer, FFT, SPEC_BINS, HIST_W, HIST_H };
