// The guided introduction, the same class as Tour.java: one feature at a time on a small bubble, shown at
// its moment (a cue) or from a slow clock, done when the user does the thing, skippable, restartable.
// Loaded by index.html as a plain script and by web/tools/tour-check.js in node.
'use strict';

const TOUR_MAX_SHOWS = 2, TOUR_GAP_MS = 25000, TOUR_CUE_GAP_MS = 2000, TOUR_FIRST_MS = 9000;

// The browser's steps; {n} and {scenes} are filled by the page.
const TOUR_STEPS = [
  { id: 'draw', title: 'Draw with sound', body: 'Drag across the light. The line you draw plays the music.' },
  { id: 'mirror', title: 'Mirrored ×{n}', body: 'Your line is drawn {n} times around the centre, like a mandala. The Mirror button below changes it; off keeps one line.', after: 'draw', onCue: true },
  { id: 'tap', title: 'A burst', body: 'Click or tap anywhere: sparks in the colours of the music, and a shove to the paint.' },
  { id: 'scene', title: '{scenes} scenes', body: 'Swipe with two fingers, press ← →, or pick one below. Each scene listens to the music in its own way.' },
  { id: 'shuffle', title: 'Three colours that fit', body: '⚄ Shuffle picks three colours that go together. Colours cycles the palettes.' },
  { id: 'pinch', title: 'Zoom and clear', body: 'Pinch to zoom in for a moment. A three-finger tap, or c, clears the drawing.', after: 'draw' },
  { id: 'autopilot', title: 'Autopilot', body: 'It changes scenes with the music’s energy and waits for the drop. Your own picks always win.' },
  { id: 'tune', title: 'Tune this scene', body: 'Every scene has three sliders of its own, under Tune.' },
  { id: 'looks', title: 'Keep what you made', body: 'Like this? Save it as a look and share it as a link or a six-letter code.' },
];

class Tour {
  constructor(steps) {
    this.steps = steps;
    this.done = new Set();
    this.shows = new Map();
    this.off = false;
    this.lastAt = -Infinity;
    this.current = null;
    this.cued = null;
  }
  find(id) { return this.steps.find((s) => s.id === id) || null; }
  isDone(id) { return this.done.has(id); }
  isOff() { return this.off; }
  doneCount() { return this.steps.filter((s) => this.done.has(s.id)).length; }
  size() { return this.steps.length; }
  remaining() { return this.steps.filter((s) => !this.done.has(s.id)); }
  start(now) { this.lastAt = now - TOUR_GAP_MS + TOUR_FIRST_MS; this.current = null; }
  cue(id) { if (this.off || this.done.has(id) || !this.find(id)) return; this.cued = id; }
  count(id) { return this.shows.get(id) || 0; }
  eligible(s, fromClock) {
    if (this.done.has(s.id) || this.count(s.id) >= TOUR_MAX_SHOWS) return false;
    if (s.after && !this.done.has(s.after)) return false;
    return !(s.onCue && fromClock);
  }
  pick(now, busy) {
    if (this.off || busy || this.current) return null;
    if (this.cued) {
      const s = this.find(this.cued);
      if (!s || !this.eligible(s, false)) this.cued = null;
      else if (now - this.lastAt >= TOUR_CUE_GAP_MS) { this.cued = null; return s; }
      else return null;
    }
    if (now - this.lastAt < TOUR_GAP_MS) return null;
    return this.steps.find((s) => this.eligible(s, true)) || null;
  }
  shown(s, now) { this.current = s; this.shows.set(s.id, this.count(s.id) + 1); this.lastAt = now; }
  hid(now) { this.current = null; this.lastAt = now; }
  did(id, now) {
    if (!this.find(id)) return false;
    this.done.add(id);
    if (this.cued === id) this.cued = null;
    const wasUp = !!this.current && this.current.id === id;
    if (wasUp) { this.current = null; this.lastAt = now; }
    return wasUp;
  }
  skip() { this.off = true; this.current = null; this.cued = null; }
  restart(now) { this.done.clear(); this.shows.clear(); this.off = false; this.current = null; this.cued = null; this.lastAt = now - TOUR_GAP_MS + 1500; }
  save() {
    const shows = this.steps.filter((s) => this.count(s.id) > 0).map((s) => s.id + ':' + this.count(s.id));
    return 'off=' + (this.off ? 1 : 0) + ';done=' + [...this.done].join(',') + ';shows=' + shows.join(',');
  }
  load(line) {
    this.done.clear(); this.shows.clear(); this.off = false;
    if (!line) return;
    for (const part of String(line).split(';')) {
      const eq = part.indexOf('=');
      if (eq < 0) continue;
      const k = part.slice(0, eq), v = part.slice(eq + 1);
      if (k === 'off') this.off = v === '1';
      else if (k === 'done') { for (const id of v.split(',')) if (this.find(id)) this.done.add(id); }
      else if (k === 'shows') {
        for (const e of v.split(',')) {
          const c = e.indexOf(':');
          if (c < 0 || !this.find(e.slice(0, c))) continue;
          const n = parseInt(e.slice(c + 1), 10);
          if (Number.isFinite(n) && n > 0) this.shows.set(e.slice(0, c), n);
        }
      }
    }
  }
}

if (typeof module !== 'undefined') module.exports = { Tour, TOUR_STEPS, TOUR_MAX_SHOWS, TOUR_GAP_MS, TOUR_CUE_GAP_MS, TOUR_FIRST_MS };
