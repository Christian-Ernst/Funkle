#!/usr/bin/env node
// Plain-node check of AdaptiveScale in web/engine.js: the same cases as AdaptiveScaleCheck.java,
// so the two editions keep choosing the same resolution from the same frame times.
'use strict';
const path = require('path');
const { AdaptiveScale, AUTO_START, QUALITY } = require(path.join(__dirname, '..', 'engine.js'));

let failed = false;
const check = (what, ok) => { console.log((ok ? '  ok   ' : '  FAIL ') + what); if (!ok) failed = true; };
const near = (v, want) => Math.abs(v - want) < (want > 2 ? 0.1 : 1e-4);

let a = new AdaptiveScale(), changed = false;
for (let i = 0; i < 30; i++) changed = a.update(0.25, true, true) || changed;
check('compile stalls ignored', !changed && a.scale === 1);

a = new AdaptiveScale(); changed = false;
for (let f = 0; f < 3600; f++) changed = a.update(f % 60 === 0 ? 0.2 : 1 / 60, false, true) || changed;
check('occasional hitches ignored', !changed && a.scale === 1);

a = new AdaptiveScale(); let drops = 0;
for (let f = 0; f < 80; f++) if (a.update(1 / 30, false, true)) drops++;
check('sustained slow frames step down once', drops === 1 && near(a.scale, 0.85));
for (let f = 0; f < 80; f++) if (a.update(1 / 30, false, true)) drops++;
check('and again, not faster', drops === 2 && near(a.scale, 0.7225));

a = new AdaptiveScale(); changed = false;
for (let f = 0; f < 300; f++) changed = a.update(1 / 30, false, false) || changed;
check('floor respected', !changed && a.scale === 1);

a = new AdaptiveScale();
for (let f = 0; f < 180; f++) a.update(1 / 30, false, true);
for (let f = 0; f < 16 * 60; f++) a.update(1 / 60, false, true);
check('sustained speed steps back up', near(a.scale, 0.85));
for (let f = 0; f < 16 * 60; f++) a.update(1 / 60, false, true);
check('and back to full', near(a.scale, 1));

a = new AdaptiveScale(AUTO_START);
check('auto starts below full', near(a.scale, 0.7));
for (let f = 0; f < 3 * 16 * 60; f++) a.update(1 / 60, false, true);
check('auto reaches full within 45 s', near(a.scale, 1));

a = new AdaptiveScale(AUTO_START);
const waits = [];
for (let round = 0; round < 4; round++) {
  let t = 0;
  while (!a.update(1 / 60, false, true)) t += 1 / 60;
  waits.push(t);
  for (let f = 0; f < 80 && !a.update(1 / 30, false, true); f++) { /* slow at the new size: step back */ }
}
check('the climb wait doubles after each undone climb: ' + waits.map((w) => Math.round(w)).join(', ') + ' s',
  near(waits[0], 15) && near(waits[1], 30) && near(waits[2], 60) && near(waits[3], 120));
a = new AdaptiveScale(AUTO_START); a.setRefreshRate(120); changed = false;
for (let f = 0; f < 3600; f++) changed = a.update(1 / 60, false, true) || changed;
check('120 Hz: 60 fps neither climbs nor drops', !changed && near(a.scale, 0.7));
for (let f = 0; f < 1600; f++) a.update(1 / 100, false, true);
check('120 Hz: 100 fps climbs', near(a.scale, 0.7 / 0.85));
a.setRefreshRate(60);
check('60 Hz: the fast limit is 1/55 s', near(a.fastLimit, 1 / 55));
check('quality table: Auto, Low, Medium, High', QUALITY.map((q) => q.name).join() === 'Auto,Low,Medium,High'
  && QUALITY.map((q) => q.scale).join() === '1,0.5,0.7,1');

if (failed) { console.log('FAIL'); process.exit(1); }
console.log('OK');
