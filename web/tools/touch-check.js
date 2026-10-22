#!/usr/bin/env node
// Touch check: plays the web edition in headless Chromium the way a finger would (tap, drag, pinch,
// a stirring circle) and measures where the picture changed, so a stroke that lands away from the
// finger shows up as a number and a screenshot, not as a guess. The drawing layer is measured on
// its own (eng.userOnly) and then the whole scene is screenshotted for the eye.
//
//   node web/tools/touch-check.js [--scene halo] [--out DIR] [--root DIR] [--flow 150]
//
// --flow sets the music clock (uFlow) before the drag: an older stroke shader turned every drawing
// with it, so a drawing made after a minute of music sat a quarter turn from the finger.
// Exits 1 when less than 80 % of the drawn light lies within 12 px of the finger's path, or a tap's
// burst is not centred on the tap.
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
const W = 405, H = 900, FPS = 30, FLOW = +(args.flow || 150), AT = 20;
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
// Light in the frame (luma > 30): share within `near` px of the polyline, mean distance, centroid.
function light(f, poly, near) {
  const sx = f.w / W, sy = f.h / H;
  let sum = 0, close = 0, dsum = 0, cx = 0, cy = 0;
  for (let y = 0; y < f.h; y++) for (let x = 0; x < f.w; x++) {
    const i = (y * f.w + x) * 4, l = 0.2126 * f.data[i] + 0.7152 * f.data[i + 1] + 0.0722 * f.data[i + 2];
    if (l < 30) continue;
    let best = 1e9;
    for (let k = 1; k < poly.length; k++) {
      const [x0, y0] = poly[k - 1], [x1, y1] = poly[k], px = x / sx, py = y / sy;
      const vx = x1 - x0, vy = y1 - y0, t = Math.max(0, Math.min(1, ((px - x0) * vx + (py - y0) * vy) / (vx * vx + vy * vy || 1)));
      best = Math.min(best, Math.hypot(px - x0 - vx * t, py - y0 - vy * t));
    }
    sum += l; dsum += l * best; cx += l * x / sx; cy += l * y / sy;
    if (best <= near) close += l;
  }
  return sum > 0 ? { share: close / sum, meanDist: dsum / sum, cx: cx / sum, cy: cy / sum, sum } : { share: 0, meanDist: 0, cx: 0, cy: 0, sum: 0 };
}

(async () => {
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const url = `http://127.0.0.1:${server.address().port}/web/index.html`;
  const browser = await playwright().chromium.launch({
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, deviceScaleFactor: 1, hasTouch: true });
  const page = await ctx.newPage();
  const cdp = await ctx.newCDPSession(page);
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
  const scene = args.scene || 'halo';
  const out = args.out;
  if (out) fs.mkdirSync(out, { recursive: true });
  await page.evaluate(({ scene, AT, FLOW }) => {
    if (typeof adaptOn !== 'undefined') adaptOn = false;
    document.getElementById('start').hidden = true;
    setScene(SCENES.findIndex((s) => s.id === scene));
    SCENES.forEach((s) => { s.beatRipples = false; });   // Halo's beat rings would be measured as drawn light
    eng.clearStrokes();
    audio.previewStart = performance.now() - AT * 1000;
    window.__flow = FLOW;
  }, { scene, AT, FLOW });
  const step = (frames) => page.evaluate(({ frames, FPS }) => { an.flow = window.__flow; window.__advance(1000 / FPS, frames); }, { frames, FPS });
  // One frame, then its pixels in the same task (the drawing buffer is gone by the next one).
  const grab = () => page.evaluate(({ FPS }) => { an.flow = window.__flow; window.__advance(1000 / FPS, 1); return window.grabFrame(); }, { FPS });
  const shot = (name) => out && page.screenshot({ path: path.join(out, name + '.png') });
  const userOnly = (on) => page.evaluate((on) => { eng.userOnly = on; }, on);
  // Touch through the devtools protocol, so the page sees real pointer events with real ids.
  const touch = async (type, points) => cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map(([x, y], id) => ({ x, y, id })) });
  let failed = false;
  const check = (ok, msg) => { console.log(`  ${ok ? 'ok  ' : 'FAIL'} ${msg}`); if (!ok) failed = true; };
  console.log(`${scene}, music clock ${FLOW}`);
  await step(FPS * 2);

  // 1. A drag draws a line under the finger: measured on the drawing layer alone.
  const poly = [[60, 200], [120, 300], [200, 380], [300, 420]];   // off centre, so a turn about the centre shows
  await userOnly(true);
  await step(2);
  await page.mouse.move(poly[0][0], poly[0][1]);
  await page.mouse.down();
  for (let k = 1; k < poly.length; k++) { await page.mouse.move(poly[k][0], poly[k][1], { steps: 8 }); await step(2); }
  await step(1);
  const live = light(await grab(), poly, 12);
  await page.mouse.up();
  await step(3);
  const done = light(await grab(), poly, 12);
  await shot('1-drag-layer');
  check(done.share > 0.8 && live.share > 0.8, `drag: ${(done.share * 100).toFixed(0)}% of the drawn light within 12 px of the finger's path (${(live.share * 100).toFixed(0)}% while drawing), mean distance ${done.meanDist.toFixed(1)} px`);
  await userOnly(false);
  await step(2);
  await shot('1-drag');

  // 2. A tap bursts where the finger was.
  await page.evaluate(() => eng.clearStrokes());
  await userOnly(true);
  await step(2);
  await page.touchscreen.tap(300, 300);
  await step(2);
  const tap = light(await grab(), [[300, 300], [300, 300]], 60);
  check(tap.sum > 0 && Math.hypot(tap.cx - 300, tap.cy - 300) < 15, `tap at (300, 300): burst centred at (${tap.cx.toFixed(0)}, ${tap.cy.toFixed(0)}), ${(tap.share * 100).toFixed(0)}% within 60 px`);
  await userOnly(false);
  await step(1);
  await shot('2-tap');
  await step(8);
  await shot('2-tap-sparks');

  // 3. A pinch zooms in around the fingers and springs back after they lift.
  await step(FPS);
  const zoomBefore = await page.evaluate(() => eng.view.zoom);
  await touch('touchStart', [[180, 420], [230, 480]]);
  for (let i = 1; i <= 10; i++) {
    const s = 1 + i * 0.18;
    await touch('touchMove', [[205 - 25 * s, 450 - 30 * s], [205 + 25 * s, 450 + 30 * s]]);
    await step(1);
  }
  await step(3);
  const zoomIn = await page.evaluate(() => eng.view.zoom);
  await shot('3-pinch');
  await touch('touchEnd', []);
  const sceneBefore = await page.evaluate(() => eng.scene);
  await step(FPS * 5);
  const zoomAfter = await page.evaluate(() => eng.view.zoom);
  check(zoomIn > 1.8 && zoomAfter < 1.05 && zoomBefore === 1, `pinch: zoom ${zoomBefore.toFixed(2)} -> ${zoomIn.toFixed(2)} while pinched -> ${zoomAfter.toFixed(2)} five seconds after`);
  check(sceneBefore === await page.evaluate(() => eng.scene), 'pinch did not count as a scene swipe');

  // 4. A circle stirs the paint around the finger (uTouch.w) and the drag pushes it (uPush).
  let maxStir = 0, maxPush = 0;
  await page.mouse.move(200, 450);
  await page.mouse.down();
  for (let i = 1; i <= 40; i++) {
    const a = i / 40 * Math.PI * 2;
    await page.mouse.move(200 + 90 * Math.cos(a), 450 + 90 * Math.sin(a), { steps: 3 });
    await step(1);
    const t = await page.evaluate(() => [eng.touch.stir, Math.hypot(eng.touch.pushX, eng.touch.pushY)]);
    maxStir = Math.max(maxStir, Math.abs(t[0])); maxPush = Math.max(maxPush, t[1]);
  }
  await shot('4-stir');
  await page.mouse.up();
  await step(FPS);
  check(maxStir > 0.02 && maxPush > 0.01, `stir: up to ${maxStir.toFixed(3)} rad/frame around the finger, drag up to ${maxPush.toFixed(3)} units/frame`);

  // 5. Three fingers down and up at once clear the drawings.
  const n0 = await page.evaluate(() => eng.strokes.length);
  await touch('touchStart', [[150, 300], [220, 320], [290, 300]]);
  await step(1);
  await touch('touchEnd', []);
  await step(2);
  check(n0 > 0 && await page.evaluate(() => eng.strokes.length) === 0, `three-finger tap cleared ${n0} drawings`);
  await shot('5-cleared');

  // 6. Nothing above produced a NaN or a blown-out frame.
  const final = await grab();
  let white = 0;
  for (let i = 0; i < final.data.length; i += 4) if (final.data[i] > 250 && final.data[i + 1] > 250 && final.data[i + 2] > 250) white++;
  check(white / (final.w * final.h) < 0.02, `near-white share after everything: ${(white / (final.w * final.h) * 100).toFixed(2)}%`);
  await browser.close();
  server.close();
  process.exit(failed ? 1 : 0);
})().catch((e) => { console.error(e); process.exit(1); });
