// TAV demo track: 16 bars at 128 BPM (intro, breakdown with a riser, drop),
// synthesized sample by sample so the visuals can run before any audio is allowed to play.
// JavaScript port of the test track used to tune the analyzer.
'use strict';

function synthDemo(sr = 44100) {
  const bpm = 128, beat = 60 / bpm, bars = 16, dur = bars * 4 * beat, n = Math.floor(dur * sr);
  const L = new Float32Array(n), R = new Float32Array(n);
  let seed = 7;
  const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296);
  const gauss = () => Math.sqrt(-2 * Math.log(rand() + 1e-12)) * Math.cos(2 * Math.PI * rand());
  const add = (sig, start, pan = 0, gain = 1) => {
    const i0 = Math.floor(start * sr);
    const gl = gain * (1 - Math.max(0, pan)), gr = gain * (1 + Math.min(0, pan));
    for (let i = 0; i < sig.length && i0 + i < n; i++) { L[i0 + i] += sig[i] * gl; R[i0 + i] += sig[i] * gr; }
  };
  const buf = (d) => new Float32Array(Math.floor(d * sr));
  const lp = (x, a) => { let acc = 0; for (let i = 0; i < x.length; i++) { acc += a * (x[i] - acc); x[i] = acc; } return x; };
  const kick = () => {
    const s = buf(0.45);
    for (let i = 0, ph = 0; i < s.length; i++) {
      const t = i / sr; ph += 2 * Math.PI * (45 + 110 * Math.exp(-t * 28)) / sr;
      s[i] = Math.sin(ph) * Math.exp(-t * 7);
    }
    return s;
  };
  const snare = () => {
    const s = buf(0.25);
    for (let i = 0; i < s.length; i++) { const t = i / sr; s[i] = gauss() * 0.5 * Math.exp(-t * 18) + Math.sin(2 * Math.PI * 190 * t) * 0.4 * Math.exp(-t * 25); }
    return s;
  };
  const hat = (open) => {
    const s = buf(open ? 0.18 : 0.05); let prev = 0;
    for (let i = 0; i < s.length; i++) { const x = gauss(); s[i] = (x - prev) * Math.exp(-i / sr * (open ? 12 : 70)) * 0.25; prev = x; }
    return s;
  };
  const saw = (f, d, detune) => {
    const s = buf(d);
    for (let i = 0; i < s.length; i++) {
      let v = 0; for (const dt of detune) { const p = (i / sr) * f * (1 + dt); v += 2 * (p - Math.floor(p)) - 1; }
      s[i] = v / detune.length;
    }
    return s;
  };
  const N = { A1: 55, F1: 43.65, C2: 65.41, G1: 49, A3: 220, C4: 261.6, E4: 329.6, F3: 174.6, A4: 440, G3: 196, B3: 246.9, D4: 293.7, C5: 523.3, E5: 659.3, G4: 392 };
  const prog = [['A1', ['A3', 'C4', 'E4']], ['F1', ['F3', 'A3', 'C4']], ['C2', ['G3', 'C4', 'E4']], ['G1', ['G3', 'B3', 'D4']]];
  const K = kick();
  for (let bar = 0; bar < bars; bar++) {
    const sec = bar < 4 ? 'intro' : bar < 8 ? 'break' : 'drop';
    const [root, chord] = prog[bar % 4], b0 = bar * 4 * beat;
    const pad = buf(4 * beat);
    for (const c of chord) { const s = saw(N[c], 4 * beat, [-0.006, 0, 0.007]); for (let i = 0; i < pad.length; i++) pad[i] += s[i] / 3; }
    const fade = 0.3 * sr;
    for (let i = 0; i < pad.length; i++) pad[i] *= Math.min(1, i / fade, (pad.length - i) / fade);
    lp(pad, sec !== 'drop' ? 0.04 : 0.12);
    add(pad, b0, -0.3, 0.22); add(pad, b0, 0.3, 0.22);
    for (let q = 0; q < 4; q++) {
      const tb = b0 + q * beat;
      if (sec !== 'break') add(K, tb, 0, 0.9);
      if (sec === 'break' && bar === 7) { add(K, tb, 0, 0.5 + 0.12 * q); add(K, tb + beat / 2, 0, 0.5 + 0.12 * q); }
      if (sec === 'drop' && (q === 1 || q === 3)) add(snare(), tb, 0, 0.6);
      if (sec !== 'intro' || bar >= 2) {
        add(hat(false), tb + beat / 2, 0.4, 0.8);
        if (sec === 'drop') { add(hat(false), tb + beat / 4, -0.4, 0.5); add(hat(true), tb + 3 * beat / 4, 0.2, 0.5); }
      }
      if (sec !== 'break') {
        const bs = saw(N[root] * 2, beat * 0.45, [0, 0.01]);
        for (let i = 0; i < bs.length; i++) bs[i] *= Math.exp(-i / sr * 6);
        add(lp(bs, sec === 'intro' ? 0.08 : 0.2), tb + beat / 2, 0, 0.55);
      }
    }
    if (sec === 'drop') {
      const arp = bar % 4 === 0 || bar % 4 === 2 ? ['A4', 'C5', 'E5', 'C5'] : ['G4', 'C5', 'E5', 'G4'];
      for (let k = 0; k < 16; k++) {
        const f = N[arp[k % 4]], s = buf(beat / 4 * 0.9);
        for (let i = 0; i < s.length; i++) {
          const t = i / sr;
          s[i] = (Math.sign(Math.sin(2 * Math.PI * f * t)) * 0.5 + Math.sin(4 * Math.PI * f * t) * 0.3) * Math.exp(-t * 9);
        }
        add(lp(s, 0.25), b0 + k * beat / 4, 0.5 * Math.sin(k), 0.18);
      }
    }
  }
  // riser into the drop
  const i0 = Math.floor(7 * 4 * beat * sr), i1 = Math.floor(8 * 4 * beat * sr);
  for (let i = i0; i < i1; i++) { const v = gauss() * 0.35 * (i - i0) / (i1 - i0); L[i] += v; R[i] += v; }
  let peak = 1e-9;
  for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
  const g = 1 / (peak * 1.05), t13 = Math.tanh(1.3);
  for (let i = 0; i < n; i++) { L[i] = Math.tanh(L[i] * g * 1.3) / t13; R[i] = Math.tanh(R[i] * g * 1.3) / t13; }
  return { sampleRate: sr, left: L, right: R, duration: dur };
}

if (typeof module !== 'undefined') module.exports = { synthDemo };
