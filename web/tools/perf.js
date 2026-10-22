#!/usr/bin/env node
// Cost check: times every scene's frame in headless Chromium (SwiftShader, a software GPU) and
// prints milliseconds per frame, relative to the median scene. The absolute numbers say nothing
// about a phone, but the ratios do: a scene at 3x the median costs a low-end phone about 3x the
// render budget, which the adaptive resolution then pays for in sharpness.
//
//   node web/tools/perf.js [--frames N] [--scale S] [--scenes halo,horizon] [--root DIR]
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
const FRAMES = +(args.frames || 40), SCALE = +(args.scale || 0.5);
const only = args.scenes ? args.scenes.split(',') : null;
const W = 405, H = 900, FPS = 30, START = 14.0;

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.glsl': 'text/plain', '.frag': 'text/plain', '.vert': 'text/plain' };
const server = http.createServer((req, res) => {
  const file = path.join(root, decodeURIComponent(req.url.split('?')[0]));
  if (!file.startsWith(root) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': TYPES[path.extname(file)] || 'application/octet-stream' });
  fs.createReadStream(file).pipe(res);
});

(async () => {
  await new Promise((ok) => server.listen(0, '127.0.0.1', ok));
  const url = `http://127.0.0.1:${server.address().port}/web/index.html`;
  const browser = await playwright().chromium.launch({
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
  });
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });
  page.on('pageerror', (e) => console.error('page error:', e.message));
  await page.addInitScript(() => {
    let now = 0;
    const queue = [];
    const realNow = performance.now.bind(performance);
    performance.now = () => now;
    window.__realNow = realNow;
    window.requestAnimationFrame = (cb) => queue.push(cb);
    window.__queue = queue;
    window.__advance = (ms, frames) => {
      for (let i = 0; i < frames; i++) { now += ms; queue.splice(0).forEach((cb) => cb(now)); }
    };
  });
  await page.goto(url);
  await page.waitForFunction(() => typeof eng !== 'undefined' && eng && window.__queue.length > 0, null, { timeout: 60000 });
  const scenes = await page.evaluate((SCALE) => {
    document.getElementById('start').hidden = true;
    if (typeof adaptOn !== 'undefined') adaptOn = false; else slow = -1e9;   // no adaptive steps during the timing
    if (typeof setQuality === 'function') { eng.renderScale = -1; setQuality(3, true); }
    eng.renderScale = SCALE; eng.resize(canvas.width, canvas.height);
    return SCENES.map((s) => s.id);
  }, SCALE);
  const sw = await page.evaluate(() => eng.sceneA.w + 'x' + eng.sceneA.h);
  console.log(`${root}  frames=${FRAMES} scene buffer ${sw} (SwiftShader)`);
  const rows = [];
  for (let si = 0; si < scenes.length; si++) {
    if (only && !only.includes(scenes[si])) continue;
    const ms = await page.evaluate(({ si, START, FPS, FRAMES }) => {
      eng.clearStrokes();
      setScene(si);
      an = new Analyzer(demo.sampleRate, N);
      audio.previewStart = performance.now() - START * 1000;
      // a one-pixel read forces the frame to finish; gl.finish() alone returns early in ANGLE
      const px = new Uint8Array(4), sync = () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px); };
      window.__advance(1000 / FPS, 20);   // warm up: compile, settle the transition
      sync();
      const t0 = window.__realNow();
      for (let i = 0; i < FRAMES; i++) { window.__advance(1000 / FPS, 1); sync(); }
      return (window.__realNow() - t0) / FRAMES;
    }, { si, START, FPS, FRAMES });
    rows.push([scenes[si], ms]);
  }
  const sorted = rows.map((r) => r[1]).sort((a, b) => a - b), median = sorted[Math.floor(sorted.length / 2)];
  console.log('scene      ms/frame  x median');
  for (const [id, ms] of rows) console.log(`${id.padEnd(9)} ${ms.toFixed(1).padStart(9)} ${(ms / median).toFixed(2).padStart(9)}`);
  await browser.close();
  server.close();
})().catch((e) => { console.error(e); process.exit(1); });
