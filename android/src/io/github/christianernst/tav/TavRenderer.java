package io.github.christianernst.tav;

import android.content.Context;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static android.opengl.GLES30.*;

/**
 * The render engine (GLES 3.0 twin of web/engine.js). Per frame:
 * scene shader (reads its own previous frame) -> particles -> user ripples and strokes
 * -> transition blend -> dual-Kawase bloom -> composite to the screen.
 * All public methods run on the GL thread; the UI reaches it through GLSurfaceView.queueEvent.
 */
final class TavRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "TAV";
    private static final int MAX_RIPPLES = 8, BLOOM_LEVELS = 4, MAX_STROKES = 12, MAX_STROKE_POINTS = 900;
    static final float[] QUALITY_SCALE = {0.5f, 0.7f, 1.0f};
    private static final float MIN_RENDER_SCALE = 0.36f;
    /** Brightness levels Dim, Soft, Normal, Bright: composite exposure and bloom. Same table as web/engine.js. */
    static final float[] EXPOSURE = {0.45f, 0.65f, 0.85f, 1.1f}, BLOOM = {0.45f, 0.65f, 0.85f, 1.0f};
    static final int DEFAULT_BRIGHTNESS = 2;

    interface Host {
        void onSceneChanged(int scene);

        void onSilence(boolean silent);

        void onRenderError(String message);
    }

    private final Context ctx;
    private final AudioCapture capture;
    private final Host host;
    private final Map<String, String> src = new HashMap<>();
    private final Map<String, Program> programs = new HashMap<>();
    private final boolean[] broken = new boolean[Scene.ALL.length];
    private final Random rnd = new Random();

    private AudioAnalyzer an;
    private final float[] samples = new float[AudioCapture.N];
    private final ByteBuffer audioBuf = ByteBuffer.allocateDirect(512 * 4).order(ByteOrder.nativeOrder());
    private final ByteBuffer histBuf = ByteBuffer.allocateDirect(256).order(ByteOrder.nativeOrder());

    // GL objects
    private int vao, strokeVao, audioTex, histTex;
    private boolean halfFloat;
    private Fbo sceneA, sceneB, trans;
    private final Fbo[] down = new Fbo[BLOOM_LEVELS], up = new Fbo[BLOOM_LEVELS - 1];
    private int width, height;
    private Fbo cur, prev;

    // state
    private int scene, sym = 1, quality = 1, brightness = DEFAULT_BRIGHTNESS;
    private float renderScale = QUALITY_SCALE[1];
    private final AdaptiveScale adaptive = new AdaptiveScale();
    private int compiles, compilesSeen;                          // shader builds so far
    private final List<Runnable> warmUp = new ArrayList<>();     // the other shaders, one per frame
    private boolean calm;                                        // Motion: Calm, no beat pulses or flashes
    private Equalizer eq = Equalizer.flat();
    private final float[] shaped = new float[Equalizer.BANDS];   // the bands the scenes see
    private float shapedLevel, shapedBeat;
    // Clocks are double on the CPU; the shaders get them wrapped (see use()) because fp32
    // uniforms in the thousands lose the precision that fract()/sin() patterns need.
    private double time, transStart = -10;
    private float stepScale = 1, flash, seed;
    private long lastNanos;
    private float sceneTimer;
    private boolean autopilot = true, lastSilent;
    private final float[][] palette = Palettes.preset(1), target = Palettes.preset(1);
    private final List<double[]> ripples = new ArrayList<>();   // x, y, born, strength
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke liveStroke;
    private final float[] palFlat = new float[9], rippleFlat = new float[MAX_RIPPLES * 4];

    TavRenderer(Context ctx, AudioCapture capture, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.capture = capture;
        this.host = host;
    }

    // ------------------------------------------------------------------ controls (GL thread)

    void setScene(int i) {
        int n = Scene.ALL.length;
        i = ((i % n) + n) % n;
        for (int k = 0; k < n && broken[i]; k++) i = (i + 1) % n;
        if (i == scene) return;
        if (sceneB != null) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneB.fb);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, trans.fb);
            glBlitFramebuffer(0, 0, sceneB.w, sceneB.h, 0, 0, trans.w, trans.h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            transStart = time;
        }
        scene = i;
        sceneTimer = 0;
        if (an != null && an.flow > 400) an.flow = 0;   // rebase the music clock while the crossfade hides the jump
        host.onSceneChanged(i);
    }

    int scene() {
        return scene;
    }

    void nextScene(int dir) {
        setScene(scene + dir);
    }

    void setSymmetry(int s) {
        sym = Math.max(1, s);
    }

    void setAutopilot(boolean on) {
        autopilot = on;
        sceneTimer = 0;
    }

    void setQuality(int q) {
        quality = Math.max(0, Math.min(2, q));
        adaptive.reset();
        applyScale();
    }

    void setBrightness(int level) {
        brightness = Math.max(0, Math.min(EXPOSURE.length - 1, level));
    }

    /** Motion: Calm stops the beat pulses, beat ripples and drop flashes. The music still moves things. */
    void setCalm(boolean on) {
        calm = on;
    }

    void setEqualizer(Equalizer e) {
        eq = e;
    }

    void setPalette(float[][] p) {
        setPalette(p, false);
    }

    /** snap: show the colours at once instead of easing toward them (live preview while editing). */
    void setPalette(float[][] p, boolean snap) {
        for (int k = 0; k < 3; k++) {
            System.arraycopy(p[k], 0, target[k], 0, 3);
            if (snap) System.arraycopy(p[k], 0, palette[k], 0, 3);
        }
    }

    /** Called when the playing track changes: autopilot moves on with the music. */
    void onTrackChanged() {
        if (autopilot && time > 3) randomScene();
        seed = (seed + 1) % 64;
    }

    void addRipple(float x, float y, float strength) {
        ripples.add(new double[]{x, y, time, strength});
        if (ripples.size() > MAX_RIPPLES) ripples.remove(0);
    }

    void beginStroke(float x, float y) {
        liveStroke = new Stroke(rnd.nextFloat(), time);
        liveStroke.points.add(new float[]{x, y});
        strokes.add(liveStroke);
        trimStrokes();
    }

    void extendStroke(float x, float y) {
        Stroke s = liveStroke;
        if (s == null || s.points.size() >= MAX_STROKE_POINTS) return;
        float[] last = s.points.get(s.points.size() - 1);
        float dx = x - last[0], dy = y - last[1];
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d < 0.012f) return;
        // fill long jumps so the waveform has room to travel
        int steps = (int) Math.ceil(d / 0.02f);
        for (int k = 1; k <= steps && s.points.size() < MAX_STROKE_POINTS; k++) {
            float t = (float) k / steps;
            s.points.add(new float[]{last[0] + dx * t, last[1] + dy * t});
        }
        s.dirty = true;
    }

    /** Returns false when the touch was too short to count as a drawing. */
    boolean endStroke() {
        Stroke s = liveStroke;
        liveStroke = null;
        if (s == null) return false;
        if (s.points.size() < 4) {
            strokes.remove(s);
            s.release();
            return false;
        }
        return true;
    }

    void addShape(List<float[]> pts) {
        Stroke s = new Stroke(rnd.nextFloat(), time);
        s.points.addAll(pts);
        strokes.add(s);
        trimStrokes();
    }

    void clearStrokes() {
        for (Stroke s : strokes) s.release();
        strokes.clear();
        liveStroke = null;
        ripples.clear();
    }

    private void trimStrokes() {
        while (strokes.size() > MAX_STROKES) {
            Stroke old = strokes.remove(0);
            if (old == liveStroke) liveStroke = null;
            old.release();
        }
    }

    private void randomScene() {
        int n = Scene.ALL.length, next = scene;
        for (int tries = 0; tries < 10 && (next == scene || broken[next]); tries++) next = rnd.nextInt(n);
        setScene(next);
    }

    // ------------------------------------------------------------------ GLSurfaceView.Renderer

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        programs.clear();
        sceneA = sceneB = trans = null;
        for (int i = 0; i < BLOOM_LEVELS; i++) down[i] = null;
        for (int i = 0; i < BLOOM_LEVELS - 1; i++) up[i] = null;
        for (Stroke s : strokes) s.vbo = 0;   // old context's buffers are gone
        for (Stroke s : strokes) s.dirty = true;
        loadSources();
        String ext = glGetString(GL_EXTENSIONS), ver = glGetString(GL_VERSION);
        halfFloat = (ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")))
                || (ver != null && ver.contains("OpenGL ES 3.2"));
        int[] ids = new int[2];
        glGenVertexArrays(2, ids, 0);
        vao = ids[0];
        strokeVao = ids[1];
        audioTex = makeTex(512, 4, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_CLAMP_TO_EDGE);
        histTex = makeTex(256, 128, GL_R8, GL_RED, GL_UNSIGNED_BYTE, GL_REPEAT);
        lastNanos = 0;
        // compile the scene we start with right away so a broken driver is detected early
        for (int k = 0; k < Scene.ALL.length && !compileScene(scene); k++) scene = (scene + 1) % Scene.ALL.length;
        // The rest are built a frame at a time, so a scene change never waits on a compiler.
        warmUp.clear();
        for (int i = 0; i < Scene.ALL.length; i++) {
            final int k = i;
            if (i != scene) warmUp.add(() -> compileScene(k));
        }
        for (String[] p : new String[][]{{"fullscreen.vert", "blend.frag"}, {"fullscreen.vert", "bloom_down.frag"},
                {"fullscreen.vert", "bloom_up.frag"}, {"fullscreen.vert", "composite.frag"},
                {"fullscreen.vert", "ripples.frag"}, {"stroke.vert", "stroke.frag"}}) {
            warmUp.add(() -> program(p[0], p[1]));
        }
        host.onSceneChanged(scene);
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        width = w;
        height = h;
        applyScale();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 1f / 60 : Math.min(0.1f, (now - lastNanos) / 1e9f);
        // dt is the time since the previous frame started, so it includes any shader built during it
        boolean stalled = compiles != compilesSeen;
        compilesSeen = compiles;
        lastNanos = now;
        if (sceneA == null) return;
        if (!warmUp.isEmpty()) warmUp.remove(0).run();

        // audio
        if (an == null || an.sampleRate != capture.sampleRate()) an = new AudioAnalyzer(capture.sampleRate(), AudioCapture.N);
        if (!capture.read(samples)) java.util.Arrays.fill(samples, 0f);
        an.process(samples, dt);
        eq.shapeBands(an.bands, an.bandsAbs, shaped);
        shapedLevel = eq.shapeLevel(an.level);
        shapedBeat = calm ? 0f : eq.shapeBeat(an.beat);
        if (an.silent != lastSilent) {
            lastSilent = an.silent;
            host.onSilence(an.silent);
        }

        time += dt;
        if (an.flow > 2000) an.flow -= 2000;   // safety net when autopilot is off and no scene changes
        stepScale = Math.min(4f, Math.max(0.25f, dt * 60f));
        adapt(dt, stalled);
        autopilot(dt);
        for (int k = 0; k < 3; k++)
            for (int c = 0; c < 3; c++) palette[k][c] += (target[k][c] - palette[k][c]) * (1 - (float) Math.exp(-dt * 1.5));
        if (an.drop) {
            seed = (seed + 1) % 64;
            flash = calm ? 0f : eq.dropFlash();
        }
        flash *= (float) Math.exp(-dt * 4);
        Scene sc = Scene.ALL[scene];
        if (sc.beatRipples && an.onBeat && !calm) addRipple(0, 0, 0.5f + 0.5f * shaped[0]);

        // upload audio
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, audioTex);
        audioBuf.clear();
        audioBuf.put(an.audioBytes).flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 512, 4, GL_RED, GL_UNSIGNED_BYTE, audioBuf);
        if (an.histPushed) {
            glBindTexture(GL_TEXTURE_2D, histTex);
            histBuf.clear();
            histBuf.put(an.histRow).flip();
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, an.histHead, 256, 1, GL_RED, GL_UNSIGNED_BYTE, histBuf);
        }

        glBindVertexArray(vao);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        prev = sceneB;
        cur = sceneA;
        glBindFramebuffer(GL_FRAMEBUFFER, cur.fb);
        glViewport(0, 0, cur.w, cur.h);
        glDisable(GL_BLEND);
        Program sp = program("fullscreen.vert", sc.frag);
        if (sp == null) {
            broken[scene] = true;
            host.onRenderError("Scene " + sc.name + " failed to compile on this GPU");
            setScene(scene + 1);
            return;
        }
        use(sp);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);
        if (sc.pointsVert != null) {
            Program pp = program(sc.pointsVert, "particle.frag");
            if (pp != null) {
                use(pp);
                int inst = sc.instances > 0 ? sc.instances : (sym > 1 ? sym : 6);
                uniform1f(pp, "uCopies", inst);
                uniform1f(pp, "uCount", sc.pointCount);
                glDrawArraysInstanced(GL_POINTS, 0, sc.pointCount, inst);
            }
        }
        drawUserLayer();

        float tm = (float) ((time - transStart) / 0.9);
        if (tm < 1) {
            glBindVertexArray(vao);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            Program bp = program("fullscreen.vert", "blend.frag");
            if (bp != null) {
                use(bp);
                bindTex(bp, "uSrc", trans.tex, 4);
                uniform1f(bp, "uMix", tm * tm * (3 - 2 * tm));
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
        }
        glDisable(GL_BLEND);
        glBindVertexArray(vao);

        Fbo bloom = bloom();

        // composite
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
        Program cp = program("fullscreen.vert", "composite.frag");
        if (cp != null) {
            cur = null;
            use(cp);
            bindTex(cp, "uScene", sceneA.tex, 4);
            bindTex(cp, "uBloom", bloom.tex, 5);
            uniform1f(cp, "uBloomStrength", BLOOM[brightness]);
            uniform1f(cp, "uExposure", EXPOSURE[brightness]);
            uniform1f(cp, "uFlash", flash);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        Fbo t = sceneA;
        sceneA = sceneB;
        sceneB = t;
    }

    // ------------------------------------------------------------------ frame helpers

    /** Lowers the resolution when the GPU can't keep up (sustained < ~42 fps), raises it again when it can. */
    private void adapt(float dt, boolean stalled) {
        if (adaptive.update(dt, stalled, QUALITY_SCALE[quality] * adaptive.scale() > MIN_RENDER_SCALE)) {
            applyScale();
            Log.i(TAG, "adaptive resolution -> " + renderScale);
        }
    }

    private void autopilot(float dt) {
        sceneTimer += dt;
        if (!autopilot) return;
        if ((an.drop && sceneTimer > 12) || sceneTimer > 45) randomScene();
    }

    private void applyScale() {
        if (width == 0) return;
        renderScale = Math.max(0.3f, QUALITY_SCALE[quality] * adaptive.scale());
        int sw = Math.max(16, Math.round(width * renderScale)), sh = Math.max(16, Math.round(height * renderScale));
        if (sceneA != null && sceneA.w == sw && sceneA.h == sh) return;
        Fbo.free(sceneA);
        Fbo.free(sceneB);
        Fbo.free(trans);
        for (Fbo f : down) Fbo.free(f);
        for (Fbo f : up) Fbo.free(f);
        sceneA = makeFbo(sw, sh);
        sceneB = makeFbo(sw, sh);
        trans = makeFbo(sw, sh);
        int bw = sw, bh = sh;
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            bw = Math.max(1, bw >> 1);
            bh = Math.max(1, bh >> 1);
            down[i] = makeFbo(bw, bh);
            if (i < BLOOM_LEVELS - 1) up[i] = makeFbo(bw, bh);
        }
    }

    private Fbo bloom() {
        Program dp = program("fullscreen.vert", "bloom_down.frag"), upP = program("fullscreen.vert", "bloom_up.frag");
        Fbo s = cur;
        if (dp == null || upP == null) return s;
        for (int i = 0; i < BLOOM_LEVELS; i++) {
            Fbo dst = down[i];
            glBindFramebuffer(GL_FRAMEBUFFER, dst.fb);
            glViewport(0, 0, dst.w, dst.h);
            glUseProgram(dp.id);
            bindTex(dp, "uSrc", s.tex, 0);
            glUniform2f(dp.loc("uTexel"), 1f / s.w, 1f / s.h);
            glUniform1f(dp.loc("uThreshold"), i == 0 ? 0.55f : 0f);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            s = dst;
        }
        for (int i = BLOOM_LEVELS - 2; i >= 0; i--) {
            Fbo dst = up[i];
            glBindFramebuffer(GL_FRAMEBUFFER, dst.fb);
            glViewport(0, 0, dst.w, dst.h);
            glUseProgram(upP.id);
            bindTex(upP, "uSrc", s.tex, 0);
            bindTex(upP, "uAdd", down[i].tex, 1);
            glUniform2f(upP.loc("uTexel"), 1f / s.w, 1f / s.h);
            glDrawArrays(GL_TRIANGLES, 0, 3);
            s = dst;
        }
        return s;
    }

    private void drawUserLayer() {
        for (int i = ripples.size() - 1; i >= 0; i--) if (time - ripples.get(i)[2] > 4) ripples.remove(i);
        if (!ripples.isEmpty()) {
            Program p = program("fullscreen.vert", "ripples.frag");
            if (p != null) {
                use(p);
                java.util.Arrays.fill(rippleFlat, 0);
                for (int i = 0; i < ripples.size(); i++) {
                    double[] r = ripples.get(i);
                    rippleFlat[i * 4] = (float) r[0];
                    rippleFlat[i * 4 + 1] = (float) r[1];
                    rippleFlat[i * 4 + 2] = (float) (time - r[2]);
                    rippleFlat[i * 4 + 3] = (float) r[3];
                }
                glUniform4fv(p.loc("uRipple"), MAX_RIPPLES, rippleFlat, 0);
                glUniform1i(p.loc("uRippleCount"), ripples.size());
                glDrawArrays(GL_TRIANGLES, 0, 3);
            }
        }
        if (strokes.isEmpty()) return;
        Program p = program("stroke.vert", "stroke.frag");
        if (p == null) return;
        use(p);
        glBindVertexArray(strokeVao);
        float copies = Math.max(1, sym);
        uniform1f(p, "uCopies", copies);
        for (Stroke s : strokes) {
            if (s.points.size() < 2) continue;
            if (s.dirty || s.vbo == 0) s.upload();
            glBindBuffer(GL_ARRAY_BUFFER, s.vbo);
            for (int a = 0; a < 4; a++) glEnableVertexAttribArray(a);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, 24, 0);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, 24, 8);
            glVertexAttribPointer(2, 1, GL_FLOAT, false, 24, 16);
            glVertexAttribPointer(3, 1, GL_FLOAT, false, 24, 20);
            uniform1f(p, "uLen", s.len);
            uniform1f(p, "uHue", s.hue);
            uniform1f(p, "uAlpha", (float) Math.min(1, (time - s.born) * 3));
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, s.count, (int) copies);
        }
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindVertexArray(vao);
    }

    // ------------------------------------------------------------------ GL plumbing

    private void use(Program p) {
        glUseProgram(p.id);
        int res = p.loc("uRes");
        if (res >= 0) {
            if (cur != null) glUniform2f(res, cur.w, cur.h);
            else glUniform2f(res, width, height);
        }
        uniform1f(p, "uTime", (float) (time % 3600.0));
        uniform1f(p, "uFlow", an.flow);
        uniform1f(p, "uStep", stepScale);
        uniform1f(p, "uHistHead", an.histHead);
        uniform1f(p, "uSym", sym);
        uniform1f(p, "uSeed", seed);
        int l = p.loc("uBands");
        if (l >= 0) glUniform4f(l, shaped[0], shaped[1], shaped[2], shaped[3]);
        l = p.loc("uEnergy");
        if (l >= 0) glUniform4f(l, shapedLevel, shapedBeat, an.beatCount, an.intensity);
        l = p.loc("uPal");
        if (l >= 0) {
            for (int k = 0; k < 3; k++) System.arraycopy(palette[k], 0, palFlat, k * 3, 3);
            glUniform3fv(l, 3, palFlat, 0);
        }
        bindTex(p, "uAudio", audioTex, 0);
        bindTex(p, "uHist", histTex, 1);
        if (prev != null) bindTex(p, "uPrev", prev.tex, 2);
    }

    private static void bindTex(Program p, String name, int tex, int unit) {
        int l = p.loc(name);
        if (l < 0) return;
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, tex);
        glUniform1i(l, unit);
    }

    private static void uniform1f(Program p, String name, float v) {
        int l = p.loc(name);
        if (l >= 0) glUniform1f(l, v);
    }

    private boolean compileScene(int i) {
        Scene s = Scene.ALL[i];
        boolean ok = program("fullscreen.vert", s.frag) != null
                && (s.pointsVert == null || program(s.pointsVert, "particle.frag") != null);
        if (!ok) broken[i] = true;
        return ok;
    }

    private Program program(String vert, String frag) {
        String key = vert + "|" + frag;
        if (programs.containsKey(key)) return programs.get(key);
        Program p = null;
        compiles++;
        try {
            int vs = compile(GL_VERTEX_SHADER, vert), fs = compile(GL_FRAGMENT_SHADER, frag);
            int id = glCreateProgram();
            glAttachShader(id, vs);
            glAttachShader(id, fs);
            glBindAttribLocation(id, 0, "aPos");
            glBindAttribLocation(id, 1, "aNormal");
            glBindAttribLocation(id, 2, "aS");
            glBindAttribLocation(id, 3, "aSide");
            glLinkProgram(id);
            glDeleteShader(vs);
            glDeleteShader(fs);
            int[] ok = new int[1];
            glGetProgramiv(id, GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = glGetProgramInfoLog(id);
                glDeleteProgram(id);
                throw new RuntimeException(key + ": " + log);
            }
            p = new Program(id);
        } catch (RuntimeException e) {
            Log.e(TAG, "shader build failed", e);
        }
        programs.put(key, p);   // cache failures too, so we don't retry every frame
        return p;
    }

    private int compile(int type, String name) {
        String body = src.get(name);
        if (body == null) throw new RuntimeException("missing shader " + name);
        // vUv is only declared where it is read, so stages that never write it still link on strict drivers
        String head = "#version 300 es\n" + src.get("common.glsl") + "\n"
                + (type == GL_FRAGMENT_SHADER ? (body.contains("vUv") ? "in vec2 vUv;\n" : "") + "out vec4 fragColor;\n" : "");
        int s = glCreateShader(type);
        glShaderSource(s, head + body);
        glCompileShader(s);
        int[] ok = new int[1];
        glGetShaderiv(s, GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = glGetShaderInfoLog(s);
            glDeleteShader(s);
            throw new RuntimeException(name + ": " + log);
        }
        return s;
    }

    private void loadSources() {
        if (!src.isEmpty()) return;
        try {
            String[] files = ctx.getAssets().list("shaders");
            if (files == null) return;
            for (String f : files) {
                try (InputStream in = ctx.getAssets().open("shaders/" + f)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int r;
                    while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    src.put(f, new String(out.toByteArray(), StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot read shaders", e);
        }
    }

    /** One-byte-per-texel (R8) texture, zero-filled. */
    private static int makeTex(int w, int h, int ifmt, int fmt, int type, int wrapT) {
        int[] t = new int[1];
        glGenTextures(1, t, 0);
        glBindTexture(GL_TEXTURE_2D, t[0]);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        // GLES leaves a null-initialised image undefined (WebGL zero-fills it); the spectrogram
        // ring takes seconds to fill, so start from real zeros instead of leftover GPU memory.
        ByteBuffer zeros = ByteBuffer.allocateDirect(w * h).order(ByteOrder.nativeOrder());
        glTexImage2D(GL_TEXTURE_2D, 0, ifmt, w, h, 0, fmt, type, zeros);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
        return t[0];
    }

    private Fbo makeFbo(int w, int h) {
        if (halfFloat) {
            Fbo f = Fbo.create(w, h, GL_RGBA16F, GL_HALF_FLOAT);
            if (f != null) return f;
            halfFloat = false;   // driver lied; stay on 8-bit from now on
        }
        Fbo f = Fbo.create(w, h, GL_RGBA8, GL_UNSIGNED_BYTE);
        if (f == null) throw new IllegalStateException("no renderable framebuffer format");
        return f;
    }

    private static final class Program {
        final int id;
        private final Map<String, Integer> locs = new HashMap<>();

        Program(int id) {
            this.id = id;
            int[] n = new int[1];
            glGetProgramiv(id, GL_ACTIVE_UNIFORMS, n, 0);
            int[] size = new int[1], type = new int[1];
            for (int i = 0; i < n[0]; i++) {
                String name = glGetActiveUniform(id, i, size, 0, type, 0);
                if (name.endsWith("[0]")) name = name.substring(0, name.length() - 3);
                locs.put(name, glGetUniformLocation(id, name));
            }
        }

        int loc(String name) {
            Integer l = locs.get(name);
            return l == null ? -1 : l;
        }
    }

    private static final class Fbo {
        final int fb, tex, w, h;

        private Fbo(int fb, int tex, int w, int h) {
            this.fb = fb;
            this.tex = tex;
            this.w = w;
            this.h = h;
        }

        static Fbo create(int w, int h, int ifmt, int type) {
            int[] id = new int[1];
            glGenTextures(1, id, 0);
            int tex = id[0];
            glBindTexture(GL_TEXTURE_2D, tex);
            glTexImage2D(GL_TEXTURE_2D, 0, ifmt, w, h, 0, GL_RGBA, type, null);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glGenFramebuffers(1, id, 0);
            int fb = id[0];
            glBindFramebuffer(GL_FRAMEBUFFER, fb);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
            boolean ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
            if (ok) {
                glClearColor(0, 0, 0, 1);
                glClear(GL_COLOR_BUFFER_BIT);
            }
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            if (!ok) {
                glDeleteFramebuffers(1, new int[]{fb}, 0);
                glDeleteTextures(1, new int[]{tex}, 0);
                return null;
            }
            return new Fbo(fb, tex, w, h);
        }

        static void free(Fbo f) {
            if (f == null) return;
            glDeleteFramebuffers(1, new int[]{f.fb}, 0);
            glDeleteTextures(1, new int[]{f.tex}, 0);
        }
    }

    /** A user stroke: points in centred coordinates, uploaded as a triangle strip. */
    private static final class Stroke {
        final List<float[]> points = new ArrayList<>();
        final float hue;
        final double born;
        int vbo, count;
        float len;
        boolean dirty = true;

        Stroke(float hue, double born) {
            this.hue = hue;
            this.born = born;
        }

        void upload() {
            int n = points.size();
            float[] acc = new float[n];
            float total = 0;
            for (int i = 1; i < n; i++) {
                float[] a = points.get(i - 1), b = points.get(i);
                total += (float) Math.hypot(b[0] - a[0], b[1] - a[1]);
                acc[i] = total;
            }
            FloatBuffer fb = ByteBuffer.allocateDirect(n * 2 * 6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            for (int i = 0; i < n; i++) {
                float[] a = points.get(Math.max(0, i - 1)), b = points.get(Math.min(n - 1, i + 1)), p = points.get(i);
                float tx = b[0] - a[0], ty = b[1] - a[1];
                float tl = (float) Math.hypot(tx, ty);
                if (tl == 0) tl = 1;
                tx /= tl;
                ty /= tl;
                float s = total > 0 ? acc[i] / total : 0;
                fb.put(p[0]).put(p[1]).put(-ty).put(tx).put(s).put(-1f);
                fb.put(p[0]).put(p[1]).put(-ty).put(tx).put(s).put(1f);
            }
            fb.flip();
            if (vbo == 0) {
                int[] id = new int[1];
                glGenBuffers(1, id, 0);
                vbo = id[0];
            }
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, n * 2 * 6 * 4, fb, GL_DYNAMIC_DRAW);
            count = n * 2;
            len = total;
            dirty = false;
        }

        void release() {
            if (vbo != 0) GLES30.glDeleteBuffers(1, new int[]{vbo}, 0);
            vbo = 0;
        }
    }
}
