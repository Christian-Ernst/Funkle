#!/usr/bin/env node
// Brightness check: renders every scene of web/index.html in headless Chromium during the demo
// track's drop and prints how much of each frame is blown out. Frames are stepped on a virtual
// clock (30 fps), so two runs see exactly the same audio and can be compared number for number.
//
//   node web/tools/measure.js [--level 0-3] [--palette N] [--colour N] [--scenes halo,spiro]
//                             [--track demo|dense] [--sym N] [--tune a,b,c] [--root DIR] [--out DIR]
//
// --colour picks the colour motion mode (0 Still, 1 Drift, 2 Beat, 3 Bands); --palette 9 is Rainbow.
// --tune sets every scene's three sliders (0..1; the default 0.5,0.5,0.5 is each scene's own look).
//
// --track dense mixes side-chained pink noise into the demo and limits it hard: a stand-in for a
// loud mastered song, which fills the whole spectrum the way the synthetic demo doesn't.
// --root renders another checkout (e.g. a `git worktree` of the previous commit) for before/after.
// --out also saves the frames as PNGs.
//
// Columns (mean over the captures; max = the worst capture):
//   clip  share of pixels with every channel above 0.95
//   sat5  mean HSV saturation of the brightest 5 % (by luma): low means the highlights went white
//   white share of near-white pixels (max channel > 0.85, saturation < 0.2)
//   hot   share of pixels at the top of the curve (max channel > 0.9, any hue): solid blobs
//   luma  mean luma, so that "fixed" doesn't just mean "dark"
'use strict';
const fs = require('fs');
const http = require('http');
const path = require('path');

function playwright() {
  try { return require('playwright'); } catch (e) {
    const root = require('child_process').execSync('npm root -g').toString().trim();
    return require(path.join(root, 'playwright'));
  }
}

const args = {};
for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
const root = path.resolve(args.root || path.join(__dirname, '../..'));
const level = args.level === undefined ? -1 : +args.level;
const palette = +(args.palette || 0), colour = +(args.colour || 0), sym = +(args.sym || 0), track = args.track || 'demo';
const only = args.scenes ? args.scenes.split(',') : null;
const tune = args.tune ? args.tune.split(',').map(Number) : null;
const W = 405, H = 900, FPS = 30, START = 9.0;
const SHOTS = [15.2, 15.5, 16.0, 17.0, 18.5, 20.0, 22.5, 25.0];   // the drop starts at 15.0 s

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.glsl': 'text/plain', '.frag': 'text/plain', '.vert': 'text/plain' };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

// Runs in the page: reads the frame just drawn and measures it (optionally returns a PNG).
function measureFrame(wantPng) {
  const w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, n = w * h, px = new Uint8Array(n * 4);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
  const luma = new Float32Array(n), sat = new Float32Array(n);
  let clip = 0, white = 0, hot = 0, sum = 0;
  for (let i = 0; i < n; i++) {
    const r = px[i * 4] / 255, g = px[i * 4 + 1] / 255, b = px[i * 4 + 2] / 255;
    const mx = Math.max(r, g, b), mn = Math.min(r, g, b), s = mx > 1e-4 ? (mx - mn) / mx : 0;
    luma[i] = 0.2126 * r + 0.7152 * g + 0.0722 * b;
    sat[i] = s;
    sum += luma[i];
    if (mn > 0.95) clip++;
    if (mx > 0.85 && s < 0.2) white++;
    if (mx > 0.9) hot++;
  }
  const k = Math.max(1, Math.floor(n * 0.05)), cut = Float32Array.from(luma).sort()[n - k];
  let s5 = 0, c5 = 0;
  for (let i = 0; i < n; i++) if (luma[i] >= cut) { s5 += sat[i]; c5++; }
  const out = { clip: clip / n, sat5: s5 / c5, white: white / n, hot: hot / n, luma: sum / n };
  if (wantPng) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const ctx = c.getContext('2d'), img = ctx.createImageData(w, h);
    for (let y = 0; y < h; y++) img.data.set(px.subarray((h - 1 - y) * w * 4, (h - y) * w * 4), y * w * 4);
    ctx.putImageData(img, 0, 0);
    out.png = c.toDataURL('image/png').split(',')[1];
  }
  return out;
}

(async () => {
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const url = `http://127.0.0.1:${server.address().port}/web/index.html`;
  const browser = await playwright().chromium.launch({
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  page.on('pageerror', (e) => console.error('page error:', e.message));
  await page.addInitScript(() => {
    // virtual clock: the page only advances when we say so
    let now = 0;
    const queue = [];
    performance.now = () => now;
    window.requestAnimationFrame = (cb) => queue.push(cb);
    window.__queue = queue;
    window.__advance = (ms, frames) => {
      for (let i = 0; i < frames; i++) { now += ms; queue.splice(0).forEach((cb) => cb(now)); }
    };
  });
  await page.goto(url);
  await page.waitForFunction(() => typeof eng !== 'undefined' && eng && window.__queue.length > 0, null, { timeout: 60000 });
  await page.addScriptTag({ content: `window.measureFrame = ${measureFrame};` });
  if (track === 'dense') {
    await page.evaluate(() => {
      const b = demo, sr = b.sampleRate, n = b.left.length, beatLen = 60 / 128;
      const L = new Float32Array(n), R = new Float32Array(n);
      let seed = 11, b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
      const rnd = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 4294967296) * 2 - 1;
      for (let i = 0; i < n; i++) {
        const w = rnd();   // pink noise (Paul Kellet's filter)
        b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.969 * b2 + w * 0.153852;
        b3 = 0.8665 * b3 + w * 0.3104856; b4 = 0.55 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.016898;
        const pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
        b6 = w * 0.115926;
        const t = i / sr, duck = 0.3 + 0.7 * Math.min(1, ((t % beatLen) / beatLen) * 3), amt = t >= 15 ? 1.4 : 0.49;
        L[i] = b.left[i] + pink * amt * duck;
        R[i] = b.right[i] + pink * amt * duck;
      }
      let peak = 1e-9;
      for (let i = 0; i < n; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
      const g = 2.5 / peak, t1 = Math.tanh(2.5);
      for (let i = 0; i < n; i++) { L[i] = Math.tanh(L[i] * g) / t1; R[i] = Math.tanh(R[i] * g) / t1; }
      demo = { sampleRate: sr, left: L, right: R, duration: b.duration };
      useDemo();
    });
  }
  const scenes = await page.evaluate(({ level, palette, colour, sym, tune }) => {
    if (typeof adaptOn !== 'undefined') adaptOn = false; else slow = -1e9;   // keep the adaptive resolution out of it
    document.getElementById('start').hidden = true;
    if (typeof setPalette === 'function') setPalette(palette); else targetPalette = PALETTES[palette][1];
    eng.palette = PALETTES[palette][1].map((c) => c.slice());
    if (typeof setColourMode === 'function') setColourMode(colour);   // 0 = Still, whatever the page's default
    if (level >= 0 && eng.setBrightness) eng.setBrightness(level);
    if (sym) eng.sym = sym;
    if (tune) SCENES.forEach((s, i) => tune.forEach((v, k) => eng.setTune(i, k, v)));
    return SCENES.map((s) => s.id);
  }, { level, palette, colour, sym, tune });
  if (args.out) fs.mkdirSync(args.out, { recursive: true });

  console.log(`${root}  track=${track} palette=${palette} colour=${colour} level=${level < 0 ? 'default' : level}${sym ? ' sym=' + sym : ''}${tune ? ' tune=' + tune.join(',') : ''}`);
  console.log('scene      clip%   max%   sat5  white%   hot%   luma');
  for (let si = 0; si < scenes.length; si++) {
    if (only && !only.includes(scenes[si])) continue;
    await page.evaluate(({ si, START }) => {
      eng.clearStrokes();
      eng.seed = 0; eng.flash = 0;
      setScene(si);
      an = new Analyzer(demo.sampleRate, N);
      audio.previewStart = performance.now() - START * 1000;
    }, { si, START });
    const ms = [];
    let t = START;
    for (const shot of SHOTS) {
      const frames = Math.round((shot - t) * FPS);
      t += frames / FPS;
      const m = await page.evaluate(({ frames, FPS, png }) => {
        window.__advance(1000 / FPS, frames);
        return window.measureFrame(png);
      }, { frames, FPS, png: !!args.out });
      if (m.png) fs.writeFileSync(path.join(args.out, `${scenes[si]}_${shot.toFixed(1)}.png`), Buffer.from(m.png, 'base64'));
      ms.push(m);
    }
    const mean = (k) => ms.reduce((a, m) => a + m[k], 0) / ms.length, pct = (v) => (v * 100).toFixed(2).padStart(6);
    console.log(`${scenes[si].padEnd(9)} ${pct(mean('clip'))} ${pct(Math.max(...ms.map((m) => m.clip)))} ` +
      `${mean('sat5').toFixed(2).padStart(6)} ${pct(mean('white'))} ${pct(mean('hot'))} ${mean('luma').toFixed(3).padStart(6)}`);
  }
  await browser.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });
