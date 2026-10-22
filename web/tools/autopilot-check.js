#!/usr/bin/env node
// Plain-node check of the web Autopilot (the twin of android/test/.../AutopilotCheck.java) on the same
// synthetic song: a quiet verse, a build-up, a drop into a loud chorus, a breakdown.
//   node web/tools/autopilot-check.js
'use strict';
const path = require('path');
const E = require(path.join(__dirname, '..', 'engine.js'));
const { Autopilot } = E;
const IDS = ['halo', 'tunnel', 'pulsar', 'kaleido', 'orb', 'nebula', 'spiro'], DT = 1 / 60;
let failed = false;
function check(what, ok) { if (!ok) failed = true; console.log('  ' + (ok ? 'ok   ' : 'FAIL ') + what); }
function rng(seed) { let x = seed >>> 0 || 1; return () => { x ^= x << 13; x >>>= 0; x ^= x >>> 17; x ^= x << 5; x >>>= 0; return x / 4294967296; }; }

class Song {
  constructor(ap) { this.ap = ap; this.t = 0; this.lastBeat = -10; this.bpm = 140; this.looks = []; this.at = []; }
  play(seconds, intensity, bass, treble, dropOnFirstBeat) {
    const end = this.t + seconds;
    let first = true;
    while (this.t < end) {
      const beat = this.t - this.lastBeat >= 60 / this.bpm;
      if (beat) this.lastBeat = this.t;
      const drop = beat && first && dropOnFirstBeat;
      if (beat) first = false;
      const l = this.ap.step(DT, [bass, 0.5 * intensity, 0.4 * intensity, treble], intensity, beat, drop, intensity <= 0, null);
      if (l) { this.looks.push(l); this.at.push(this.t); }
      this.t += DT;
    }
  }
}

let ap = new Autopilot(IDS, rng(1));
check('a scene without a mood suits everything', new Autopilot(['halo', 'brand-new']).fit(1, 0) === 1 && new Autopilot(['halo', 'brand-new']).fit(1, 1) === 1);
check('Pulsar does not suit a quiet verse', ap.fit(2, 0.1) < 0.25 && ap.fit(2, 0.8) === 1);
check('Ferro suits a quiet verse', ap.fit(4, 0.1) === 1 && ap.fit(4, 0.9) < 0.25);
ap.setMode(E.AUTOPILOT_OFF);
let s = new Song(ap);
s.play(120, 0.8, 0.9, 0.6, true);
check('Off never changes the look', s.looks.length === 0);

{
  const sloppy = new Autopilot(IDS, rng(2));
  let t = 0, last = -10, count = 0;
  for (let i = 0; i < 60 * 40; i++) {   // 128 bpm with every third beat missed, as a real detector does
    const beat = t - last >= 60 / 128;
    if (beat) { last = t; count++; }
    sloppy.step(DT, [0.5, 0.3, 0.3, 0.2], 0.5, beat && count % 3 !== 0, false, false, null);
    t += DT;
  }
  check('missed beats do not drag the tempo', Math.abs(sloppy.bpm - 128) < 6);
  const synco = new Autopilot(IDS, rng(2));
  t = 0;
  for (let i = 0; i < 60 * 40; i++) {   // 128 bpm with an off-beat hit after every second beat
    const beatLen = 60 / 128, ph = t % (2 * beatLen);
    const beat = Math.abs(ph) < DT / 2 || Math.abs(ph - beatLen) < DT / 2 || Math.abs(ph - 1.5 * beatLen) < DT / 2;
    synco.step(DT, [0.5, 0.3, 0.3, 0.2], 0.5, beat, false, false, null);
    t += DT;
  }
  check('off-beat hits do not drag the tempo', Math.abs(synco.bpm - 128) < 6);
}
for (const seed of [7, 101, 102, 103]) {
  ap = new Autopilot(IDS, rng(seed));
  ap.onScene(4);
  s = new Song(ap);
  s.play(2, 0, 0, 0, false);
  s.play(30, 0.15, 0.2, 0.1, false);
  check(`[${seed}] a fitting scene is kept through the verse`, s.looks.length === 0);
  check(`[${seed}] the tempo is heard`, Math.abs(ap.bpm - 140) < 6);
  s.play(70, 0.15, 0.2, 0.1, false);
  const beforeBuild = s.looks.length;
  for (let i = 0; i < 16; i++) s.play(0.5, 0.15 + 0.04 * i, 0.2 + 0.03 * i, 0.1 + 0.02 * i, false);
  check(`[${seed}] the build-up is held`, ap.building && s.looks.length === beforeBuild);
  s.play(20, 0.9, 0.95, 0.5, true);
  const dl = s.looks[beforeBuild], t0 = s.t - 20;
  check(`[${seed}] the drop brings a new look on the downbeat of the next bar`, dl && dl.reason === 'drop'
    && s.at[beforeBuild] > t0 + 3 * 60 / 140 - 0.1 && s.at[beforeBuild] < t0 + 5 * 60 / 140);
  check(`[${seed}] the drop scene suits a loud chorus`, dl && dl.scene >= 0 && ap.fit(dl.scene, 0.9) >= 0.5 && dl.fade < 1);
  check(`[${seed}] the chorus gets high-energy colours`, dl && E.AUTOPILOT_PALETTES.high.includes(dl.palette) && dl.colourMode === E.AUTOPILOT_MOTION.high);
  const beforeBreak = s.looks.length;
  s.play(2, 0.9, 0.95, 0.5, false);
  s.play(10, 0.12, 0.1, 0.5, false);
  const br = s.looks[beforeBreak];
  check(`[${seed}] a quiet breakdown is a new section with calm colours`, br && br.reason === 'section'
    && (br.colourMode === E.AUTOPILOT_MOTION.low || !br.palette || E.AUTOPILOT_PALETTES.low.includes(br.palette)));
}

ap = new Autopilot(IDS, rng(11));
ap.onScene(0);
s = new Song(ap);
s.play(5, 0.5, 0.5, 0.3, false);
ap.onUserScene(2);
check('a pick is remembered', ap.weight[2] > 1);
let n = s.looks.length;
s.play(100, 0.5, 0.5, 0.3, false);
check('a scene the user picked is held', s.looks.length === n);
s.play(3, 0.5, 0.5, 0.3, true);
s.play(3, 0.9, 0.9, 0.5, false);
check('a drop still moves the held scene on', s.looks.length === n + 1);
const auto = s.looks[n].scene;
s.play(2, 0.9, 0.9, 0.5, false);
ap.onUserScene(3);
check('leaving an autopilot scene at once lowers its weight', ap.weight[auto] < 1);
const copy = new Autopilot(IDS);
copy.loadWeights(ap.weights());
check('weights survive storage', Math.abs(copy.weight[auto] - ap.weight[auto]) < 0.011 && Math.abs(copy.weight[3] - ap.weight[3]) < 0.011);

ap = new Autopilot(IDS, rng(5));
ap.onScene(0);
ap.onUserPalette();
s = new Song(ap);
s.play(3, 0.5, 0.5, 0.3, true);
s.play(3, 0.9, 0.9, 0.5, false);
check('a palette the user chose stays for the track', s.looks.length === 1 && !s.looks[0].palette);
ap.onTrack();
s.play(1.5, 0.9, 0.9, 0.5, false);
check('a new track is heard before it gets a look', s.looks.length === 1);
s.play(2, 0.9, 0.9, 0.5, false);
check('a new track brings a new look and frees the palette', s.looks.length === 2 && s.looks[1].reason === 'track' && !!s.looks[1].palette);
ap.setMode(E.AUTOPILOT_SCENES);
ap.onTrack();
s.play(4, 0.3, 0.3, 0.3, false);
check('Scenes mode never touches the colours', s.looks.length === 3 && !s.looks[2].palette && s.looks[2].colourMode < 0);

if (failed) { console.log('autopilot-check FAILED'); process.exit(1); }
console.log('  autopilot ok');
