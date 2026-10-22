// TAV render engine — WebGL2 twin of android/src/.../TavRenderer.java.
// Pipeline per frame:
//   scene shader (reads its own previous frame) -> particles -> user ripples/strokes
//   -> transition blend -> dual-Kawase bloom -> composite to screen.
'use strict';

const SCENES = [
  { id: 'halo',    name: 'Halo',    frag: 'halo.frag', beatRipples: true },
  { id: 'tunnel',  name: 'Wormhole', frag: 'tunnel.frag' },
  { id: 'pulsar',  name: 'Pulsar',  frag: 'pulsar.frag' },
  { id: 'kaleido', name: 'Mandala', frag: 'kaleido.frag' },
  { id: 'orb',     name: 'Ferro',   frag: 'orb.frag' },
  { id: 'nebula',  name: 'Nebula',  frag: 'nebula.frag', points: { vert: 'nebula.vert', count: 48000, instances: 1 } },
  { id: 'spiro',   name: 'Spiro',   frag: 'scope.frag', points: { vert: 'scope.vert', count: 4096, instances: 'sym' } },
];

const SHADER_FILES = ['common.glsl', 'fullscreen.vert', 'bloom_down.frag', 'bloom_up.frag', 'blend.frag',
  'composite.frag', 'ripples.frag', 'stroke.vert', 'stroke.frag', 'particle.frag',
  ...new Set(SCENES.flatMap((s) => [s.frag, s.points && s.points.vert].filter(Boolean)))];

const MAX_RIPPLES = 8, BLOOM_LEVELS = 4;
// Brightness setting: composite exposure and bloom per level. Same table as TavRenderer.
const BRIGHTNESS = [
  { name: 'Dim', exposure: 0.45, bloom: 0.45 },
  { name: 'Soft', exposure: 0.65, bloom: 0.65 },
  { name: 'Normal', exposure: 0.85, bloom: 0.85 },
  { name: 'Bright', exposure: 1.1, bloom: 1.0 },
];
const DEFAULT_BRIGHTNESS = 2;

// Colour motion: how the palette moves with the music. Same model as ColourDrive.java: the hue
// drifts with the music (turns per second at full level), steps on every beat and jumps on a drop,
// and the loud bands pull the colours toward their own colour (tilt) and light it up (pump).
const COLOUR_MODES = [
  { name: 'Still', drift: 0, beat: 0, drop: 0, tilt: 0, pump: 0 },
  { name: 'Drift', drift: 0.02, beat: 0, drop: 0.1, tilt: 0.3, pump: 0 },
  { name: 'Beat', drift: 0, beat: 1 / 16, drop: 1 / 3, tilt: 0.3, pump: 0 },
  { name: 'Bands', drift: 0, beat: 0, drop: 0, tilt: 0.8, pump: 0.4 },
];
const DEFAULT_COLOUR_MODE = 2;   // Beat, same as ColourDrive.DEFAULT
const COLOUR_MAX = { drift: 0.1, beat: 0.25, drop: 0.5, tilt: 1, pump: 1 };   // the slider ranges
const COLOUR_EASE = 10;   // 1 - e^-10 per second: a beat step is done in about 0.3 s

class ColourDrive {
  constructor(settings) {
    this.set(settings);
    this.hue = 0; this.hueTarget = 0; this.mix = [0, 0, 0];
  }
  set(settings) {
    const d = Object.assign({}, COLOUR_MODES[0], settings);
    for (const k of Object.keys(COLOUR_MAX)) this[k] = Math.max(0, Math.min(COLOUR_MAX[k], +d[k] || 0));
  }
  get still() { return !(this.drift || this.beat || this.drop || this.tilt || this.pump); }
  // bands: the four bands, level 0..1, onBeat and drop: the analyzer's flags for this frame
  step(dt, bands, level, onBeat, drop) {
    this.hueTarget += this.drift * dt * (0.3 + 0.7 * Math.min(level, 1));
    if (onBeat) this.hueTarget += this.beat;
    if (drop) this.hueTarget += this.drop;
    this.hue += (this.hueTarget - this.hue) * (1 - Math.exp(-dt * COLOUR_EASE));
    const wrap = Math.floor(this.hueTarget);   // keep both small; only the difference matters
    this.hueTarget -= wrap; this.hue -= wrap;
    if (!this.tilt && !this.pump) { this.mix = [0, 0, 0]; return; }
    const b0 = Math.max(bands[0], 0), b1 = Math.max(0.5 * (bands[1] + bands[2]), 0), b2 = Math.max(bands[3], 0);
    const share = 1 / (b0 + b1 + b2 + 0.5);   // silence counts as nothing, a full band as most of it
    const w = [b0 * share, b1 * share, b2 * share];
    for (let k = 0; k < 3; k++) {
      const rate = w[k] > this.mix[k] ? 14 : 5;   // quick to light up, slower to let go
      this.mix[k] += (w[k] - this.mix[k]) * (1 - Math.exp(-dt * rate));
    }
  }
}

// A colour preset as one JSON document (shared with Palettes.java): the three colours as hex,
// whether the shader paints the rainbow instead, and the colour motion. v is the schema version.
const PRESET_VERSION = 1;
function exportPreset(name, palette, rainbow, drive) {
  const hex = (c) => '#' + c.map((v) => Math.round(Math.max(0, Math.min(1, v)) * 255).toString(16).padStart(2, '0')).join('');
  const motion = {};
  for (const k of Object.keys(COLOUR_MAX)) motion[k] = +(drive[k] || 0);
  return { v: PRESET_VERSION, name: String(name || ''), colours: palette.map(hex), rainbow: !!rainbow, motion };
}
// Returns { name, palette, rainbow, motion } or null when the document is not a preset.
function importPreset(doc) {
  if (!doc || typeof doc !== 'object' || !(doc.v >= 1) || !Array.isArray(doc.colours) || doc.colours.length !== 3) return null;
  const palette = doc.colours.map((h) => {
    const m = /^#?([0-9a-f]{6})$/i.exec(String(h));
    if (!m) return null;
    return [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16) / 255);
  });
  if (palette.some((c) => !c)) return null;
  const motion = {};
  for (const k of Object.keys(COLOUR_MAX)) motion[k] = Math.max(0, Math.min(COLOUR_MAX[k], +(doc.motion && doc.motion[k]) || 0));
  return { name: String(doc.name || ''), palette, rainbow: !!doc.rainbow, motion };
}

class TavEngine {
  constructor(gl, sources) {
    this.gl = gl; this.src = sources;
    this.half = !!gl.getExtension('EXT_color_buffer_float');
    gl.getExtension('OES_texture_float_linear');
    this.vao = gl.createVertexArray();
    this.progs = {};
    this.time = 0; this.scene = 0; this.sym = 6; this.seed = 0;
    this.palette = [[1.0, 0.18, 0.55], [0.15, 0.75, 1.0], [0.55, 0.25, 1.0]];
    this.rainbow = false;   // Colours: Rainbow (the shader paints the spectrum)
    this.colour = new ColourDrive(COLOUR_MODES[0]);
    this.ripples = []; this.strokes = [];
    this.transStart = -10; this.flash = 0;
    this.brightness = DEFAULT_BRIGHTNESS;
    this.renderScale = 1; this.w = 0; this.h = 0;
    this.audioTex = this.makeTex(512, 4, gl.R8, gl.RED, gl.UNSIGNED_BYTE, gl.LINEAR, gl.CLAMP_TO_EDGE);
    this.histTex = this.makeTex(256, 128, gl.R8, gl.RED, gl.UNSIGNED_BYTE, gl.LINEAR, gl.REPEAT);
    this.strokeBuf = gl.createBuffer();
    this.strokeVao = gl.createVertexArray();
  }

  // ---------- GL helpers ----------
  makeTex(w, h, ifmt, fmt, type, filter, wrap) {
    const gl = this.gl, t = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, t);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, ifmt, w, h, 0, fmt, type, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, filter);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, wrap);
    return t;
  }
  makeFbo(w, h) {
    const gl = this.gl;
    const tex = this.half
      ? this.makeTex(w, h, gl.RGBA16F, gl.RGBA, gl.HALF_FLOAT, gl.LINEAR, gl.CLAMP_TO_EDGE)
      : this.makeTex(w, h, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, gl.LINEAR, gl.CLAMP_TO_EDGE);
    const fb = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fb);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    gl.clearColor(0, 0, 0, 1); gl.clear(gl.COLOR_BUFFER_BIT);
    return { fb, tex, w, h };
  }
  freeFbo(f) { if (f) { this.gl.deleteFramebuffer(f.fb); this.gl.deleteTexture(f.tex); } }

  program(vertName, fragName) {
    const key = vertName + '|' + fragName;
    if (this.progs[key]) return this.progs[key];
    const gl = this.gl, common = this.src['common.glsl'];
    const compile = (type, body, name) => {
      const s = gl.createShader(type);
      // vUv is only declared where it is read, so stages that never write it still link on strict drivers
      const head = '#version 300 es\n' + common + '\n' +
        (type === gl.FRAGMENT_SHADER ? (body.includes('vUv') ? 'in vec2 vUv;\n' : '') + 'out vec4 fragColor;\n' : '');
      gl.shaderSource(s, head + body);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(name + ': ' + gl.getShaderInfoLog(s));
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, this.src[vertName], vertName));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, this.src[fragName], fragName));
    gl.bindAttribLocation(p, 0, 'aPos'); gl.bindAttribLocation(p, 1, 'aNormal');
    gl.bindAttribLocation(p, 2, 'aS'); gl.bindAttribLocation(p, 3, 'aSide');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(key + ': ' + gl.getProgramInfoLog(p));
    const prog = { p, loc: {} };
    const n = gl.getProgramParameter(p, gl.ACTIVE_UNIFORMS);
    for (let i = 0; i < n; i++) {
      const info = gl.getActiveUniform(p, i), name = info.name.replace(/\[0\]$/, '');
      prog.loc[name] = gl.getUniformLocation(p, name);
    }
    return (this.progs[key] = prog);
  }
  compileAll() {
    for (const s of SCENES) {
      this.program('fullscreen.vert', s.frag);
      if (s.points) this.program(s.points.vert, 'particle.frag');
    }
    for (const f of ['bloom_down.frag', 'bloom_up.frag', 'blend.frag', 'composite.frag', 'ripples.frag']) this.program('fullscreen.vert', f);
    this.program('stroke.vert', 'stroke.frag');
  }

  use(prog, an) {
    const gl = this.gl, L = prog.loc;
    gl.useProgram(prog.p);
    const set1 = (n, v) => { if (L[n]) gl.uniform1f(L[n], v); };
    if (L.uRes) gl.uniform2f(L.uRes, this.cur ? this.cur.w : this.w, this.cur ? this.cur.h : this.h);
    // fp32 uniforms in the thousands lose the precision fract()/sin() patterns need: wrap the clocks
    set1('uTime', this.time % 3600); set1('uFlow', an.flow); set1('uStep', this.step || 1); set1('uHistHead', an.histHead);
    set1('uSym', this.sym); set1('uSeed', this.seed);
    if (L.uBands) gl.uniform4f(L.uBands, an.bands[0], an.bands[1], an.bands[2], an.bands[3]);
    if (L.uEnergy) gl.uniform4f(L.uEnergy, an.level, an.beat, an.beatCount, an.intensity);
    if (L.uPal) gl.uniform3fv(L.uPal, this.palette.flat());
    if (L.uColour) gl.uniform4f(L.uColour, this.colour.hue, this.colour.tilt, this.colour.pump, this.rainbow ? 1 : 0);
    if (L.uBandMix) gl.uniform3fv(L.uBandMix, this.colour.mix);
    let unit = 0;
    const bind = (name, tex) => { if (!L[name]) return; gl.activeTexture(gl.TEXTURE0 + unit); gl.bindTexture(gl.TEXTURE_2D, tex); gl.uniform1i(L[name], unit++); };
    bind('uAudio', this.audioTex); bind('uHist', this.histTex);
    if (this.prev) bind('uPrev', this.prev.tex);
    return bind;
  }

  resize(w, h) {
    this.w = w; this.h = h;
    const sw = Math.max(16, Math.round(w * this.renderScale)), sh = Math.max(16, Math.round(h * this.renderScale));
    [this.sceneA, this.sceneB, this.trans, ...(this.down || []), ...(this.up || [])].forEach((f) => this.freeFbo(f));
    this.sceneA = this.makeFbo(sw, sh); this.sceneB = this.makeFbo(sw, sh); this.trans = this.makeFbo(sw, sh);
    this.down = []; this.up = [];
    let bw = sw, bh = sh;
    for (let i = 0; i < BLOOM_LEVELS; i++) {
      bw = Math.max(1, bw >> 1); bh = Math.max(1, bh >> 1);
      this.down.push(this.makeFbo(bw, bh));
      if (i < BLOOM_LEVELS - 1) this.up.push(this.makeFbo(bw, bh));
    }
  }

  setScene(i) {
    i = ((i % SCENES.length) + SCENES.length) % SCENES.length;
    if (i === this.scene) return;
    const gl = this.gl, src = this.sceneB;   // sceneB always holds the last finished frame
    gl.bindFramebuffer(gl.READ_FRAMEBUFFER, src.fb);
    gl.bindFramebuffer(gl.DRAW_FRAMEBUFFER, this.trans.fb);
    gl.blitFramebuffer(0, 0, src.w, src.h, 0, 0, this.trans.w, this.trans.h, gl.COLOR_BUFFER_BIT, gl.NEAREST);
    this.transStart = this.time;
    this.scene = i;
    this.rebaseFlow = true;
  }

  setBrightness(i) { this.brightness = Math.max(0, Math.min(BRIGHTNESS.length - 1, i)); }

  addRipple(x, y, strength = 1) {
    this.ripples.push({ x, y, born: this.time, s: strength });
    if (this.ripples.length > MAX_RIPPLES) this.ripples.shift();
  }

  // points: array of [x, y] in centred coordinates (shorter axis = [-1, 1])
  addStroke(points, hue = Math.random()) {
    if (points.length < 2) return;
    this.strokes.push({ points, hue, born: this.time, vbo: null, dirty: true });
    if (this.strokes.length > 12) this.strokes.shift();
  }

  // Live drawing, same rules as TavRenderer: points at least 0.012 apart, long jumps filled in.
  beginStroke(x, y) {
    this.live = { points: [[x, y]], hue: Math.random(), born: this.time, vbo: null, dirty: true };
    this.strokes.push(this.live);
    if (this.strokes.length > 12) this.strokes.shift();
  }
  extendStroke(x, y) {
    const s = this.live;
    if (!s || s.points.length >= 900) return;
    const [lx, ly] = s.points[s.points.length - 1], dx = x - lx, dy = y - ly, d = Math.hypot(dx, dy);
    if (d < 0.012) return;
    const steps = Math.ceil(d / 0.02);
    for (let k = 1; k <= steps && s.points.length < 900; k++) s.points.push([lx + dx * k / steps, ly + dy * k / steps]);
    s.dirty = true;
  }
  endStroke() {
    const s = this.live;
    this.live = null;
    if (s && s.points.length < 4) this.strokes = this.strokes.filter((x) => x !== s);
  }
  clearStrokes() { this.strokes = []; this.ripples = []; this.live = null; }

  buildStroke(st) {
    const pts = st.points, n = pts.length, data = new Float32Array(n * 2 * 6);
    let len = 0; const acc = [0];
    for (let i = 1; i < n; i++) { len += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); acc.push(len); }
    for (let i = 0; i < n; i++) {
      const a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
      let tx = b[0] - a[0], ty = b[1] - a[1]; const tl = Math.hypot(tx, ty) || 1; tx /= tl; ty /= tl;
      for (let side = 0; side < 2; side++) {
        const o = (i * 2 + side) * 6;
        data[o] = pts[i][0]; data[o + 1] = pts[i][1]; data[o + 2] = -ty; data[o + 3] = tx;
        data[o + 4] = len > 0 ? acc[i] / len : 0; data[o + 5] = side ? 1 : -1;
      }
    }
    st.len = len; st.data = data; st.count = n * 2; st.dirty = false;
  }

  // ---------- frame ----------
  frame(dt, an) {
    const gl = this.gl, sc = SCENES[this.scene];
    this.time += dt;
    if (this.rebaseFlow && an.flow > 400) an.flow = 0;   // rebase the music clock while the crossfade hides the jump
    this.rebaseFlow = false;
    if (an.flow > 2000) an.flow -= 2000;
    this.step = Math.min(4, Math.max(0.25, dt * 60));
    this.colour.step(dt, an.bands, an.level, an.onBeat, an.drop);
    if (an.drop) { this.seed = (this.seed + 1) % 64; this.flash = 0.6; }
    this.flash *= Math.exp(-dt * 4);
    if (sc.beatRipples && an.onBeat) this.addRipple(0, 0, 0.5 + 0.5 * an.bands[0]);

    gl.bindTexture(gl.TEXTURE_2D, this.audioTex);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, 512, 4, gl.RED, gl.UNSIGNED_BYTE, an.audioBytes);
    if (an.histPushed) {
      gl.bindTexture(gl.TEXTURE_2D, this.histTex);
      gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, an.histHead, 256, 1, gl.RED, gl.UNSIGNED_BYTE, an.histRow);
    }

    gl.bindVertexArray(this.vao);
    gl.disable(gl.DEPTH_TEST);
    this.prev = this.sceneB; this.cur = this.sceneA;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.cur.fb);
    gl.viewport(0, 0, this.cur.w, this.cur.h);
    gl.disable(gl.BLEND);
    this.use(this.program('fullscreen.vert', sc.frag), an);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    gl.enable(gl.BLEND); gl.blendFunc(gl.ONE, gl.ONE);
    if (sc.points) {
      const p = this.program(sc.points.vert, 'particle.frag');
      this.use(p, an);
      const inst = sc.points.instances === 'sym' ? (this.sym > 1 ? this.sym : 6) : sc.points.instances;
      if (p.loc.uCopies) gl.uniform1f(p.loc.uCopies, inst);
      if (p.loc.uCount) gl.uniform1f(p.loc.uCount, sc.points.count);
      gl.drawArraysInstanced(gl.POINTS, 0, sc.points.count, inst);
    }
    this.drawUserLayer(an);

    const tm = (this.time - this.transStart) / 0.9;
    if (tm < 1) {
      gl.bindVertexArray(this.vao);
      gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA);
      const p = this.program('fullscreen.vert', 'blend.frag');
      this.use(p, an);
      gl.activeTexture(gl.TEXTURE0 + 3); gl.bindTexture(gl.TEXTURE_2D, this.trans.tex); gl.uniform1i(p.loc.uSrc, 3);
      gl.uniform1f(p.loc.uMix, tm * tm * (3 - 2 * tm));
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    gl.disable(gl.BLEND);
    gl.bindVertexArray(this.vao);

    // bloom
    const down = this.program('fullscreen.vert', 'bloom_down.frag'), up = this.program('fullscreen.vert', 'bloom_up.frag');
    let src = this.cur;
    for (let i = 0; i < BLOOM_LEVELS; i++) {
      const dst = this.down[i];
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb); gl.viewport(0, 0, dst.w, dst.h);
      gl.useProgram(down.p);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, src.tex); gl.uniform1i(down.loc.uSrc, 0);
      gl.uniform2f(down.loc.uTexel, 1 / src.w, 1 / src.h);
      gl.uniform1f(down.loc.uThreshold, i === 0 ? 0.55 : 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      src = dst;
    }
    for (let i = BLOOM_LEVELS - 2; i >= 0; i--) {
      const dst = this.up[i], add = this.down[i];
      gl.bindFramebuffer(gl.FRAMEBUFFER, dst.fb); gl.viewport(0, 0, dst.w, dst.h);
      gl.useProgram(up.p);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, src.tex); gl.uniform1i(up.loc.uSrc, 0);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, add.tex); gl.uniform1i(up.loc.uAdd, 1);
      gl.uniform2f(up.loc.uTexel, 1 / src.w, 1 / src.h);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
      src = dst;
    }

    // composite
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, this.w, this.h);
    const comp = this.program('fullscreen.vert', 'composite.frag');
    this.cur = null;
    const bind = this.use(comp, an);
    gl.uniform2f(comp.loc.uRes, this.w, this.h);
    bind('uScene', this.sceneA.tex); bind('uBloom', src.tex);
    const lit = BRIGHTNESS[this.brightness];
    gl.uniform1f(comp.loc.uBloomStrength, lit.bloom);
    gl.uniform1f(comp.loc.uExposure, lit.exposure);
    gl.uniform1f(comp.loc.uFlash, this.flash);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    [this.sceneA, this.sceneB] = [this.sceneB, this.sceneA];
  }

  drawUserLayer(an) {
    const gl = this.gl;
    this.ripples = this.ripples.filter((r) => this.time - r.born < 4);
    if (this.ripples.length) {
      const p = this.program('fullscreen.vert', 'ripples.frag');
      this.use(p, an);
      const arr = new Float32Array(MAX_RIPPLES * 4);
      this.ripples.forEach((r, i) => arr.set([r.x, r.y, this.time - r.born, r.s], i * 4));
      gl.uniform4fv(p.loc.uRipple, arr);
      gl.uniform1i(p.loc.uRippleCount, this.ripples.length);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    if (this.strokes.length) {
      const p = this.program('stroke.vert', 'stroke.frag');
      this.use(p, an);
      gl.bindVertexArray(this.strokeVao);
      gl.bindBuffer(gl.ARRAY_BUFFER, this.strokeBuf);
      for (let a = 0; a < 4; a++) gl.enableVertexAttribArray(a);
      gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 24, 0);
      gl.vertexAttribPointer(1, 2, gl.FLOAT, false, 24, 8);
      gl.vertexAttribPointer(2, 1, gl.FLOAT, false, 24, 16);
      gl.vertexAttribPointer(3, 1, gl.FLOAT, false, 24, 20);
      const copies = Math.max(1, this.sym);
      gl.uniform1f(p.loc.uCopies, copies);
      for (const st of this.strokes) {
        if (st.dirty) this.buildStroke(st);
        gl.bufferData(gl.ARRAY_BUFFER, st.data, gl.STREAM_DRAW);
        gl.uniform1f(p.loc.uLen, st.len);
        gl.uniform1f(p.loc.uHue, st.hue);
        gl.uniform1f(p.loc.uAlpha, Math.min(1, (this.time - st.born) * 3));
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, st.count, copies);
      }
      gl.bindVertexArray(this.vao);
    }
  }
}

if (typeof module !== 'undefined') {
  module.exports = { TavEngine, ColourDrive, SCENES, SHADER_FILES, BRIGHTNESS, COLOUR_MODES, COLOUR_MAX, DEFAULT_COLOUR_MODE, exportPreset, importPreset };
}
