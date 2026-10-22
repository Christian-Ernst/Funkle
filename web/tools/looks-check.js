#!/usr/bin/env node
// Plain-node check of web/looks.js: the Look document, the share-link payload (also one written
// by Look.java, to keep the two editions reading each other) and the harmonious shuffle.
'use strict';
const path = require('path');
global.localStorage = { s: {}, getItem(k) { return this.s[k] ?? null; }, setItem(k, v) { this.s[k] = String(v); }, removeItem(k) { delete this.s[k]; } };
const L = require(path.join(__dirname, '..', 'looks.js'));

let failed = false;
const check = (what, ok) => { console.log((ok ? '  ok   ' : '  FAIL ') + what); if (!ok) failed = true; };
const near = (a, b, eps = 0.002) => Math.abs(a - b) < eps;

const st = { name: '  Night   drive ', palette: [[1, 0.18, 0.55], [0.15, 0.75, 1], [0.55, 0.25, 1]], paletteName: 'Custom', rainbow: false,
  motion: { drift: 0, beat: 1 / 16, drop: 1 / 3, tilt: 0.3, pump: 0 }, scene: 'kaleido', sym: 6, brightness: 1, calm: true,
  eq: { mode: 1, master: 1, punch: 0.7, beat: 1.3, gain: [1, 1, 1, 1] } };
const doc = L.make(st);
check('name is cleaned', doc.name === 'Night drive');
check('document has v, name and three hex colours', doc.v === 1 && doc.colours.length === 3 && doc.colours.every((h) => /^#[0-9a-f]{6}$/.test(h)));
const back = L.parse(JSON.stringify(doc));
check('round-trips scene, mirror, brightness, calm', back.scene === 'kaleido' && back.sym === 6 && back.brightness === 1 && back.calm === true);
check('round-trips colours', back.palette.every((c, k) => c.every((x, i) => near(x, st.palette[k][i], 1 / 255))));
check('round-trips motion and eq', near(back.motion.beat, 1 / 16) && near(back.eq.punch, 0.7) && back.eq.mode === 1);

const payload = L.encode(doc);
check('payload is URL-safe and short', /^[A-Za-z0-9_-]+$/.test(payload) && payload.length < 420);
check('payload decodes', JSON.stringify(L.make(L.decode(payload))) === JSON.stringify(doc));
const url = L.shareUrl(doc, 'ab12cd');
check('share link carries code and payload', L.codeIn(url) === 'ab12cd' && L.payloadIn(url) === payload);
check('a bare code is recognised, a short one is not', L.codeIn(' AB12CD ') === 'ab12cd' && L.codeIn('ab12') === null);
check('app link parses', L.payloadIn(L.appUrl(doc)) === payload && L.codeIn(L.appUrl(doc)) === null);

// a payload written by Look.java (LookCheck's Look, printed once with Look.encode)
const fromJava = 'eyJyYWluYm93IjpmYWxzZSwiY2FsbSI6dHJ1ZSwiYnJpZ2h0bmVzcyI6MSwibW90aW9uIjp7ImRyb3AiOjAuMzMzLCJiZWF0IjowLjA2MywidGlsdCI6MC4zLCJwdW1wIjowLCJkcmlmdCI6MH0sInYiOjEsInN5bSI6NiwibmFtZSI6Ik5pZ2h0IGRyaXZlIiwicGFsZXR0ZSI6IkN1c3RvbSIsImVxIjp7Im1vZGUiOjEsImJlYXQiOjEuMywibWFzdGVyIjoxLCJwdW5jaCI6MC43LCJnYWluIjpbMSwxLDEsMV19LCJjb2xvdXJzIjpbIiNmZjJlOGMiLCIjMjZiZmZmIiwiIzhjNDBmZiJdLCJzY2VuZSI6ImthbGVpZG8ifQ';
const j = L.decode(fromJava);
check('a payload from the Android app opens here', j && j.name === 'Night drive' && j.scene === 'kaleido' && j.sym === 6 && j.calm === true
  && near(j.motion.beat, 0.063) && j.eq.mode === 1 && L.hex(j.palette[0]) === '#ff2e8c');

check('a colour preset is a Look that only sets colours', (() => {
  const p = L.parse({ v: 1, name: 'Ocean', colours: ['#00e6cc', '#1a66ff', '#b3ffff'], rainbow: false });
  return p && p.name === 'Ocean' && p.scene === undefined && p.sym === undefined && p.eq === undefined;
})());
check('garbage is rejected', L.parse({ v: 1 }) === null && L.parse('nope') === null && L.decode('!!!') === null && L.decode('') === null);
check('out-of-range values are dropped', (() => {
  const p = L.parse({ v: 1, name: 'x', colours: ['#000000', '#000000', '#000000'], sym: 99, brightness: 7 });
  return p && p.sym === undefined && p.brightness === undefined;
})());

check('per-scene sliders are kept, clamped and cleaned', (() => {
  const p = L.parse({ v: 1, name: 't', colours: ['#000000', '#000000', '#000000'], scene: 'halo', tune: { rays: 0.7, trails: 1.5, 'Bad Key': 0.2, nan: 'x' } });
  const d = L.make(p);
  return p && Object.keys(p.tune).join() === 'rays,trails' && p.tune.trails === 1 && d.tune.rays === 0.7 && L.make(L.parse(doc)).tune === undefined;
})());

// shelf
const e1 = L.add(L.make({ ...st, name: 'A' })), e2 = L.add(L.make({ ...st, name: 'B' }));
check('shelf keeps the newest first', L.list().map((x) => x.doc.name).join() === 'B,A');
L.add(L.make({ ...st, name: 'a' }));
check('same name replaces, case-insensitively', L.list().length === 2 && L.list()[0].doc.name === 'a');
check('suggestName avoids taken names', L.suggestName('B') === 'B 2' && L.suggestName('Z') === 'Z');
L.update(e2.id, { code: 'abc123' });
check('update keeps the code', L.list().find((x) => x.id === e2.id).code === 'abc123');
L.remove(e1.id);
check('remove works', L.list().length === 1);

// shuffle: spread, neon, distinct
let seed = 42;
const rand = () => { seed = (seed * 1664525 + 1013904223) >>> 0; return seed / 4294967296; };
let spread = 0, range = 0, distinct = 0, prev = null;
for (let i = 0; i < 500; i++) {
  const c = L.shuffle(rand), h = c.map(L.hue);
  const d = (a, b) => { const x = Math.abs(a - b) % 1; return Math.min(x, 1 - x); };
  if (d(h[0], h[1]) > 0.05 && d(h[1], h[2]) > 0.05 && d(h[0], h[2]) > 0.05) spread++;
  if (c.every(([r, g, b]) => { const mx = Math.max(r, g, b), mn = Math.min(r, g, b); return mx >= 0.85 && (mx - mn) / mx >= 0.68; })) range++;
  if (!prev || L.hex(prev[0]) !== L.hex(c[0])) distinct++;
  prev = c;
}
check('shuffled hues are at least 18 degrees apart', spread === 500);
check('shuffled colours stay bright and saturated', range === 500);
check('shuffles differ', distinct === 500);

if (failed) process.exit(1);
console.log('looks OK');
