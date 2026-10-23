#!/usr/bin/env node
// Plain-node check of Tour in web/tour.js: the same cases as TourCheck.java, so both editions pace
// the introduction the same way.
'use strict';
const path = require('path');
const { Tour, TOUR_STEPS, TOUR_MAX_SHOWS, TOUR_GAP_MS, TOUR_CUE_GAP_MS, TOUR_FIRST_MS } = require(path.join(__dirname, '..', 'tour.js'));

let failed = false;
const check = (what, ok) => { console.log((ok ? '  ok   ' : '  FAIL ') + what); if (!ok) failed = true; };
const STEPS = [
  { id: 'draw', title: 'Draw', body: 'drag' },
  { id: 'mirror', title: 'Mirror', body: 'x{n}', after: 'draw', onCue: true },
  { id: 'tap', title: 'Tap', body: 'tap' },
  { id: 'scene', title: 'Scene', body: 'swipe' },
  { id: 'title', title: 'Title', body: 'swipe it', onCue: true },
];

let t = new Tour(STEPS), now = 1000000;
t.start(now);
check('nothing at once', t.pick(now, false) === null);
check('nothing while busy', t.pick(now + TOUR_FIRST_MS, true) === null);
let s = t.pick(now + TOUR_FIRST_MS, false);
check('first step after FIRST_MS', s && s.id === 'draw');
t.shown(s, now + TOUR_FIRST_MS);
check('one bubble at a time', t.pick(now + TOUR_FIRST_MS + 5000, false) === null);
now += TOUR_FIRST_MS + 6000;
check('doing it dismisses the bubble', t.did('draw', now));
t.cue('mirror');
check('a cue waits CUE_GAP_MS', t.pick(now + 500, false) === null);
s = t.pick(now + TOUR_CUE_GAP_MS, false);
check('then the cued step jumps the queue', s && s.id === 'mirror');
t.shown(s, now + TOUR_CUE_GAP_MS);
t.hid(now + TOUR_CUE_GAP_MS + 14000);
now += TOUR_CUE_GAP_MS + 14000;
check('quiet time after a fade', t.pick(now + TOUR_GAP_MS - 1, false) === null);
s = t.pick(now + TOUR_GAP_MS, false);
check('the clock never offers a cue-only step', s && s.id === 'tap');
t.cue('draw'); t.cue('nothing');
check('stale cues ignored', t.pick(now + TOUR_GAP_MS, false) === s);

const u = new Tour(STEPS); let n2 = 0;
u.start(n2);
for (let i = 0; i < TOUR_MAX_SHOWS; i++) {
  n2 += TOUR_GAP_MS;
  const d = u.pick(n2, false);
  check('show ' + (i + 1) + ' is draw', d && d.id === 'draw');
  u.shown(d, n2); u.hid(n2);
}
n2 += TOUR_GAP_MS;
let d = u.pick(n2, false);
check('ignored twice: next step instead', d && d.id === 'tap');
check('but still undiscovered', !u.isDone('draw') && u.remaining()[0].id === 'draw');
u.skip();
check('skipped: nothing', u.isOff() && u.pick(n2 + TOUR_GAP_MS * 3, false) === null);
const line = u.save();
const v = new Tour(STEPS);
v.load(line);
check('save/load keeps off and shows: ' + line, v.isOff() && v.save() === line);
v.restart(n2);
check('restart: on again, first bubble soon', !v.isOff() && v.pick(n2 + 1500, false) !== null && v.doneCount() === 0);
v.load('garbage;done=tap,bogus;shows=tap:1,x:3,draw:q');
check('load drops unknown ids', v.isDone('tap') && v.doneCount() === 1 && v.save() === 'off=0;done=tap;shows=tap:1');
v.load(null);
check('load(null) resets', v.doneCount() === 0 && !v.isOff());

const ids = new Set();
let sound = TOUR_STEPS.length >= 8;
TOUR_STEPS.forEach((p, i) => {
  if (ids.has(p.id)) sound = false; ids.add(p.id);
  if (p.after && !TOUR_STEPS.slice(0, i).some((q) => q.id === p.after)) sound = false;
  if ((p.body + p.title).includes('{') && p.id !== 'mirror' && p.id !== 'scene') sound = false;
});
check('web steps are sound (' + TOUR_STEPS.length + ')', sound);

if (failed) process.exit(1);
console.log('  tour ok');
