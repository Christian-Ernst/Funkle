#!/usr/bin/env node
// Paint check: plays the Canvas scene of the web edition in headless Chromium the way a finger would
// and measures the paint: a pen stroke lands under the finger in the colour of the chosen swatch, white
// is white, the eraser takes paint away and Undo brings it back, a tap is a dot, the painting survives
// an export/import round trip and a visit to another scene, and the mirror draws every copy.
// With --out it also saves screenshots and a finished sample painting (used for the scene's thumbnail).
//
//   node web/tools/paint-check.js [--out DIR] [--root DIR]
//
// Exits 1 when a check fails.
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
const W = 405, H = 900, FPS = 30;
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.glsl': 'text/plain', '.frag': 'text/plain', '.vert': 'text/plain' };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

// Runs in the page: the frame just drawn as RGBA bytes (top row first).
function grabFrame() {
  const w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, px = new Uint8Array(w * h * 4);
  gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
  const out = new Uint8Array(w * h * 4);
  for (let y = 0; y < h; y++) out.set(px.subarray((h - 1 - y) * w * 4, (h - y) * w * 4), y * w * 4);
  return { w, h, data: Array.from(out) };
}
// Light (luma > 40) within `near` px of a polyline: its share of all light, the mean colour of that light.
function light(f, poly, near) {
  const sx = f.w / W, sy = f.h / H;
  let sum = 0, close = 0, r = 0, g = 0, b = 0, n = 0;
  for (let y = 0; y < f.h; y++) for (let x = 0; x < f.w; x++) {
    const i = (y * f.w + x) * 4, l = 0.2126 * f.data[i] + 0.7152 * f.data[i + 1] + 0.0722 * f.data[i + 2];
    if (l < 40) continue;
    let best = 1e9;
    for (let k = 1; k < poly.length; k++) {
      const [x0, y0] = poly[k - 1], [x1, y1] = poly[k], px = x / sx, py = y / sy;
      const vx = x1 - x0, vy = y1 - y0, t = Math.max(0, Math.min(1, ((px - x0) * vx + (py - y0) * vy) / (vx * vx + vy * vy || 1)));
      best = Math.min(best, Math.hypot(px - x0 - vx * t, py - y0 - vy * t));
    }
    sum += l;
    if (best <= near) { close += l; r += f.data[i]; g += f.data[i + 1]; b += f.data[i + 2]; n++; }
  }
  return { share: sum > 0 ? close / sum : 0, sum, lit: close, n, rgb: n ? [r / n, g / n, b / n] : [0, 0, 0] };
}

(async () => {
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const url = `http://127.0.0.1:${server.address().port}/web/index.html`;
  const browser = await playwright().chromium.launch({
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, hasTouch: true });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => console.error('page error:', e.message));
  await page.addInitScript(() => {
    let now = 0;
    const queue = [];
    performance.now = () => now;
    window.requestAnimationFrame = (cb) => queue.push(cb);
    window.__queue = queue;
    window.__advance = (ms, frames) => { for (let i = 0; i < frames; i++) { now += ms; queue.splice(0).forEach((cb) => cb(now)); } };
  });
  await page.goto(url);
  await page.waitForFunction(() => typeof eng !== 'undefined' && eng && window.__queue.length > 0, null, { timeout: 60000 });
  await page.addScriptTag({ content: `window.grabFrame = ${grabFrame};` });
  const out = args.out;
  if (out) fs.mkdirSync(out, { recursive: true });
  await page.evaluate(() => {
    if (typeof adaptOn !== 'undefined') adaptOn = false;
    document.getElementById('start').hidden = true;
    setAutopilotMode(0, 'restore');
    setPalette(0, true);
    eng.palette = PALETTES[0][1].map((c) => c.slice());
    setScene(SCENES.findIndex((s) => s.id === 'canvas'));
    eng.clearStrokes();
    audio.previewStart = performance.now() - 20 * 1000;
    for (let k = 0; k < 3; k++) eng.setTune(eng.scene, k, 0.5);
  });
  const step = (frames) => page.evaluate(({ frames, FPS }) => { window.__advance(1000 / FPS, frames); }, { frames, FPS });
  const grab = () => page.evaluate(({ FPS }) => { window.__advance(1000 / FPS, 1); return window.grabFrame(); }, { FPS });
  const shot = (name) => out && page.screenshot({ path: path.join(out, name + '.png') });
  const setBrush = (tool, size, swatch, own) => page.evaluate(({ tool, size, swatch, own }) => {
    brush.tool = tool; brush.size = size; brush.swatch = swatch; if (own) brush.own = own; pushBrush();
  }, { tool, size, swatch, own });
  const drag = async (poly) => {
    await page.mouse.move(poly[0][0], poly[0][1]);
    await page.mouse.down();
    for (let k = 1; k < poly.length; k++) { await page.mouse.move(poly[k][0], poly[k][1], { steps: 8 }); await step(2); }
    await page.mouse.up();
    await step(2);
  };
  let failed = false;
  const check = (ok, msg) => { console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${msg}`); if (!ok) failed = true; };
  const fmt = (c) => c.map((v) => v.toFixed(0)).join(',');
  const sceneId = await page.evaluate(() => SCENES[eng.scene].id);
  check(sceneId === 'canvas', 'the Canvas scene is up');
  await step(FPS * 2);
  const blank = light(await grab(), [[0, 0], [0, 0]], 0);
  check(blank.sum === 0, `the empty canvas is dark (light sum ${blank.sum})`);

  // 1. A pen stroke in the first palette colour (Neon pink) lands under the finger, pink.
  await setBrush(0, 0.5, 0);
  const poly = [[60, 200], [120, 300], [200, 380], [300, 420]];
  await drag(poly);
  await step(FPS);
  let f = light(await grab(), poly, 24);
  check(f.share > 0.8, `pen: ${(f.share * 100).toFixed(0)}% of the light within 24 px of the finger's path (the bloom's halo counts)`);
  check(f.rgb[0] > f.rgb[1] * 1.6 && f.rgb[0] > f.rgb[2] * 1.2, `pen: the paint is the palette's pink (mean rgb ${fmt(f.rgb)})`);
  await shot('1-pen');

  // 2. White is white; the marker is wider than the pen.
  await setBrush(1, 0.8, 3);
  const poly2 = [[80, 560], [320, 560]];
  await drag(poly2);
  await step(FPS);
  f = light(await grab(), poly2, 40);
  const near = light(await grab(), poly2, 6);
  check(Math.min(...f.rgb) > Math.max(...f.rgb) * 0.8, `white marker: mean rgb ${fmt(f.rgb)}`);
  check(f.n > near.n * 2, `marker at size 0.8 is wide (${f.n} px within 40 px of the line, ${near.n} within 6 px)`);
  await shot('2-marker');

  // 3. The eraser takes the pink line away where it crosses it; Undo brings it back. The spot is judged
  // against a part of the same line the eraser never touched, so the music's breathing cancels out
  // (the bloom of the two cut ends still reaches into the gap, so it does not fall to nothing).
  const spot = [[160, 340], [160, 340]], ctrl = [[70, 217], [100, 267]];
  const ratio = async () => { const f = await grab(); return light(f, spot, 12).lit / Math.max(1, light(f, ctrl, 12).lit); };
  const before = await ratio();
  await setBrush(3, 0.7, 0);
  await drag([[100, 400], [220, 280]]);
  await step(FPS);
  const erased = await ratio();
  check(erased < before * 0.6, `eraser: the crossed spot fell from ${before.toFixed(2)} to ${erased.toFixed(2)} of the untouched part`);
  await shot('3-eraser');
  await page.evaluate(() => eng.undoStroke());
  await step(FPS);
  const back = await ratio();
  check(back > before * 0.7, `undo: the spot is back at ${back.toFixed(2)}`);

  // 4. A tap is a dot of paint, not a burst.
  await setBrush(0, 0.6, 1);
  await page.touchscreen.tap(300, 700);
  await step(FPS);
  const ripples = await page.evaluate(() => eng.ripples.length);
  f = light(await grab(), [[300, 700], [300, 700]], 30);
  check(f.n > 20 && ripples === 0, `tap: a dot of ${f.n} px at (300, 700), no ripple`);
  await shot('4-dot');

  // 5. Export/import keeps the strokes; another scene shows none of them and keeps its own list.
  const counts = await page.evaluate(() => {
    const n = eng.painting.length, data = JSON.parse(JSON.stringify(eng.exportPainting()));
    eng.importPainting(data);
    const m = eng.painting.length;
    setScene(0);
    const other = eng.current().length;
    eng.addStroke([[0, 0], [0.3, 0.3], [0.5, 0]]);
    const otherAfter = eng.current().length;
    setScene(SCENES.findIndex((s) => s.id === 'canvas'));
    return { n, m, other, otherAfter, backOn: eng.painting.length };
  });
  check(counts.n === counts.m && counts.n >= 3, `export/import keeps ${counts.n} strokes`);
  check(counts.other === 0 && counts.otherAfter === 1 && counts.backOn === counts.n, `Halo shows its own lines (${counts.otherAfter}), the painting is back on the Canvas (${counts.backOn})`);

  // 6. Mirror ×4 draws four copies.
  await page.evaluate(() => { eng.clearStrokes(); eng.sym = 4; });
  await setBrush(0, 0.5, 0);
  await drag([[260, 300], [340, 360]]);
  await step(FPS);
  f = light(await grab(), [[260, 300], [340, 360]], 14);
  check(f.share > 0.2 && f.share < 0.35, `mirror ×4: the finger's copy carries ${(f.share * 100).toFixed(0)}% of the light (one of four)`);
  await shot('6-mirror');

  // 7. A sample painting, for the eye and the thumbnail.
  await page.evaluate(() => { eng.clearStrokes(); eng.sym = 1; });
  const arc = (cx, cy, r, a0, a1, n) => Array.from({ length: n + 1 }, (_, i) => { const a = a0 + (a1 - a0) * i / n; return [cx + Math.cos(a) * r, cy + Math.sin(a) * r]; });
  await setBrush(2, 0.95, 2);   // spray: a violet haze
  await drag(arc(202, 450, 150, 0, Math.PI * 2, 24));
  await setBrush(1, 0.55, 1);   // marker: a cyan ring
  await drag(arc(202, 450, 120, -Math.PI / 2, Math.PI * 1.5, 40));
  await setBrush(0, 0.5, 0);    // pen: a pink wave
  await drag(Array.from({ length: 30 }, (_, i) => [60 + i * 10, 450 + Math.sin(i / 3) * 70]));
  await setBrush(0, 0.35, 3);   // white sparkle dots
  for (const [x, y] of [[120, 330], [300, 330], [150, 600], [280, 590], [202, 450]]) { await page.touchscreen.tap(x, y); await step(2); }
  await setBrush(0, 0.45, 5);   // the spectrum along a line
  await drag(Array.from({ length: 20 }, (_, i) => [80 + i * 13, 760 - Math.abs(i - 10) * 6]));
  await step(FPS * 2);
  await shot('7-sample');
  await page.evaluate(() => { eng.clearStrokes(); });
  await browser.close();
  server.close();
  console.log(failed ? 'paint check FAILED' : 'paint check ok');
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
