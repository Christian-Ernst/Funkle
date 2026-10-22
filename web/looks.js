// TAV looks: a Look is everything that decides how TAV looks right now, named so it can be saved,
// brought back and shared. The document is the one Look.java writes (and the colour preset of
// Palettes.toJson / exportPreset is a valid Look that only sets colours):
//
//   { v: 1, name, colours: ['#rrggbb' x3], rainbow, motion: {drift, beat, drop, tilt, pump},
//     palette: 'Neon' | 'Custom', scene: 'halo', sym: 6, brightness: 2, calm: false, eq: {...} }
//
// Share links carry the Look itself (#d=<base64url JSON>), so they open without the cloud and
// without an account; a six-character cloud code rides along as #l=code when there is one.
// Also here: the shelf of saved Looks in localStorage, and the harmonious shuffle. No DOM, so
// web/tools/looks-check.js runs it in node.
'use strict';

const TavLooks = (() => {
  const VERSION = 1, MAX_NAME = 40, MAX = 60, KEY = 'tav.looks';
  const SHARE_BASE = 'https://christian-ernst.github.io/TAV/web/', APP_SCHEME = 'tav://look';
  const MOTION_KEYS = ['drift', 'beat', 'drop', 'tilt', 'pump'];
  const MOTION_MAX = { drift: 0.1, beat: 0.25, drop: 0.5, tilt: 1, pump: 1 };   // same as COLOUR_MAX in engine.js

  const clamp = (v, lo, hi) => (Number.isFinite(v) ? Math.max(lo, Math.min(hi, v)) : lo);
  const round3 = (v) => Math.round(v * 1000) / 1000;
  const hex = (c) => '#' + c.map((x) => Math.round(clamp(x, 0, 1) * 255).toString(16).padStart(2, '0')).join('');
  const unhex = (h) => {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(h || ''));
    return m ? [1, 3, 5].map((i) => parseInt(m[1].slice(i - 1, i + 1), 16) / 255) : null;
  };

  function cleanName(s) {
    s = String(s == null ? '' : s).trim().replace(/\s+/g, ' ');
    if (s.length > MAX_NAME) s = s.slice(0, MAX_NAME).trim();
    return s || 'Look';
  }

  // state: { name, palette: [[r,g,b] x3] (0..1), paletteName, rainbow, motion: {drift,...}, scene: id,
  //          sym, brightness, calm (bool), eq: {mode, master, punch, beat, gain[4]} }; missing parts are left out
  function make(st) {
    const doc = { v: VERSION, name: cleanName(st.name), colours: st.palette.map(hex), rainbow: !!st.rainbow };
    const motion = {};
    for (const k of MOTION_KEYS) motion[k] = round3(clamp(+(st.motion && st.motion[k]) || 0, 0, MOTION_MAX[k]));
    doc.motion = motion;
    doc.palette = st.paletteName || 'Custom';
    if (st.scene) doc.scene = String(st.scene);
    if (st.sym >= 1) doc.sym = st.sym | 0;
    if (st.brightness >= 0) doc.brightness = st.brightness | 0;
    if (typeof st.calm === 'boolean') doc.calm = st.calm;
    if (st.eq) {
      doc.eq = { mode: st.eq.mode | 0, master: round3(st.eq.master), punch: round3(st.eq.punch), beat: round3(st.eq.beat),
        gain: (st.eq.gain || [1, 1, 1, 1]).map(round3) };
    }
    return doc;
  }

  // The state in a document, or null when it is not a Look.
  function parse(doc) {
    if (typeof doc === 'string') { try { doc = JSON.parse(doc); } catch (e) { return null; } }
    if (!doc || typeof doc !== 'object' || !(doc.v >= 1) || !Array.isArray(doc.colours) || doc.colours.length !== 3) return null;
    const palette = doc.colours.map(unhex);
    if (palette.some((c) => !c)) return null;
    const st = { name: cleanName(doc.name), palette, paletteName: typeof doc.palette === 'string' ? doc.palette : 'Custom', rainbow: !!doc.rainbow, motion: {} };
    for (const k of MOTION_KEYS) st.motion[k] = clamp(+(doc.motion && doc.motion[k]) || 0, 0, MOTION_MAX[k]);
    if (typeof doc.scene === 'string') st.scene = doc.scene;
    if (Number.isInteger(doc.sym) && doc.sym >= 1 && doc.sym <= 12) st.sym = doc.sym;
    if (Number.isInteger(doc.brightness) && doc.brightness >= 0 && doc.brightness <= 3) st.brightness = doc.brightness;
    if (typeof doc.calm === 'boolean') st.calm = doc.calm;
    if (doc.eq && typeof doc.eq === 'object') {
      const g = Array.isArray(doc.eq.gain) ? doc.eq.gain : [];
      st.eq = { mode: clamp(doc.eq.mode | 0, 0, 2), master: clamp(+doc.eq.master, 0, 2), punch: clamp(+doc.eq.punch, 0, 1),
        beat: clamp(+doc.eq.beat, 0, 2), gain: [0, 1, 2, 3].map((i) => clamp(g[i] === undefined ? 1 : +g[i], 0, 2)) };
      if (!Number.isFinite(+doc.eq.master)) st.eq.master = 1;
      if (!Number.isFinite(+doc.eq.beat)) st.eq.beat = 1;
    }
    return st;
  }

  // ---------- share links
  const b64 = (bytes) => btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  function encode(doc) { return b64(new TextEncoder().encode(JSON.stringify(doc))); }
  function decode(payload) {
    if (!payload || !/^[A-Za-z0-9_-]+$/.test(payload) || payload.length % 4 === 1) return null;
    try {
      const bin = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
      return parse(new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0))));
    } catch (e) { return null; }
  }
  const shareUrl = (doc, code) => SHARE_BASE + '#' + (code ? 'l=' + code + '&' : '') + 'd=' + encode(doc);
  const appUrl = (doc, code) => APP_SCHEME + '?' + (code ? 'l=' + code + '&' : '') + 'd=' + encode(doc);
  function codeIn(s) {
    if (!s) return null;
    const m = /(?:^|[#?&])l=([a-z0-9]{6})(?:$|&)/i.exec(s);
    if (m) return m[1].toLowerCase();
    const t = String(s).trim().toLowerCase();
    return /^[a-z0-9]{6}$/.test(t) ? t : null;
  }
  function payloadIn(s) {
    const m = s && /(?:^|[#?&])d=([A-Za-z0-9_-]+)/.exec(s);
    return m ? m[1] : null;
  }

  // ---------- the shelf (this browser), mirrored to the cloud by the page when signed in
  let shelf = null;
  function load() {
    if (shelf) return shelf;
    try { shelf = JSON.parse(localStorage.getItem(KEY)) || []; } catch (e) { shelf = []; }
    if (!Array.isArray(shelf)) shelf = [];
    return shelf;
  }
  function save() { try { localStorage.setItem(KEY, JSON.stringify(shelf)); } catch (e) { /* private mode */ } }
  const uuid = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2));
  const list = () => load().slice();
  // Adds at the top; a Look with the same name replaces the old one. Returns the entry.
  function add(doc, origin = 'own') {
    load();
    doc = Object.assign({}, doc, { name: cleanName(doc.name) });
    let e = shelf.find((x) => x.doc.name.toLowerCase() === doc.name.toLowerCase());
    if (e) { shelf.splice(shelf.indexOf(e), 1); e.doc = doc; } else e = { id: uuid(), doc, cloudId: null, code: null, origin };
    e.updatedAt = Date.now();
    shelf.unshift(e);
    shelf.length = Math.min(shelf.length, MAX);
    save();
    return e;
  }
  function update(id, patch) {
    const e = load().find((x) => x.id === id);
    if (e) { Object.assign(e, patch); save(); }
    return e;
  }
  function remove(id) { load(); shelf = shelf.filter((x) => x.id !== id); save(); }
  function suggestName(base) {
    load();
    let name = cleanName(base), n = 2;
    while (shelf.some((x) => x.doc.name.toLowerCase() === name.toLowerCase())) name = cleanName(base + ' ' + n++);
    return name;
  }

  // ---------- harmonious shuffle: hues a golden angle apart (one roll in three analogous), neon saturation and brightness.
  // Same recipe as Palettes.shuffle; returns [[r,g,b] x3] in 0..1.
  function shuffle(rand = Math.random) {
    const h0 = rand();
    const step = Math.floor(rand() * 3) === 0 ? 0.08 + 0.06 * rand() : 0.382;
    const out = [];
    for (let k = 0; k < 3; k++) {
      const h = (h0 + k * step + (rand() - 0.5) * 0.03 + 1) % 1, s = 0.72 + 0.28 * rand(), v = 0.88 + 0.12 * rand();
      out.push(hsv(h, s, v));
    }
    return out;
  }
  function hsv(h, s, v) {
    const i = Math.floor(h * 6), f = h * 6 - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
    return [[v, t, p], [q, v, p], [p, v, t], [p, q, v], [t, p, v], [v, p, q]][i % 6];
  }
  function hue(c) {
    const [r, g, b] = c, mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
    if (d < 1e-6) return 0;
    const h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    return (h / 6 + 1) % 1;
  }

  return { VERSION, SHARE_BASE, cleanName, make, parse, encode, decode, shareUrl, appUrl, codeIn, payloadIn,
    list, add, update, remove, suggestName, shuffle, hex, unhex, hue };
})();

if (typeof module !== 'undefined') module.exports = TavLooks;
