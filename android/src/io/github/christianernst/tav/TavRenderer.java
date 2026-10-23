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
    /**
     * Quality Auto, Low, Medium, High: the most of the screen's pixels the scene buffers may use.
     * Auto starts at {@link AdaptiveScale#AUTO_START} and climbs to full while the frames stay fast;
     * the others start at their ceiling. All of them step down when the GPU can't keep up
     * (sustained under ~42 fps), to {@link #MIN_RENDER_SCALE} at the lowest. Same table as web/engine.js.
     */
    static final float[] QUALITY_SCALE = {1.0f, 0.5f, 0.7f, 1.0f};
    static final int QUALITY_AUTO = 0, DEFAULT_QUALITY = QUALITY_AUTO;
    private static final float MIN_RENDER_SCALE = 0.36f;
    /** Brightness levels Dim, Soft, Normal, Bright: composite exposure and bloom. Same table as web/engine.js. */
    static final float[] EXPOSURE = {0.45f, 0.65f, 0.85f, 1.1f}, BLOOM = {0.45f, 0.65f, 0.85f, 1.0f};
    static final int DEFAULT_BRIGHTNESS = 2;

    interface Host {
        void onSceneChanged(int scene);

        /** The autopilot changed the look: the scene has already switched; the colours are the host's to apply. */
        void onAutopilotLook(Autopilot.Look look);

        /** The user's scene weights changed (see Autopilot.weights()); the host keeps them. */
        void onAutopilotWeights(String weights);

        void onSilence(boolean silent);

        void onRenderError(String message);

        /**
         * The number of drawings on screen changed (a stroke finished, undone or cleared), so a Clear or Undo
         * control can show or hide. Called on the GL thread; post to the UI thread before touching views.
         */
        default void onStrokesChanged(int count) {
        }
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
    private int scene, sym = 1, quality = DEFAULT_QUALITY, brightness = DEFAULT_BRIGHTNESS;
    private float renderScale = AdaptiveScale.AUTO_START;
    private final AdaptiveScale adaptive = new AdaptiveScale();

    {
        adaptive.reset(AdaptiveScale.AUTO_START);
    }
    private int compiles, compilesSeen;                          // shader builds so far
    private final List<Runnable> warmUp = new ArrayList<>();     // the other shaders, one per frame
    private boolean calm;                                        // Motion: Calm, no beat pulses or flashes
    private Equalizer eq = Equalizer.flat();
    private final float[] shaped = new float[Equalizer.BANDS];   // the bands the scenes see
    private float shapedLevel, shapedBeat;
    // Clocks are double on the CPU; the shaders get them wrapped (see use()) because fp32
    // uniforms in the thousands lose the precision that fract()/sin() patterns need.
    private double time, transStart = -10;
    private float transDur = 0.9f;
    private float stepScale = 1, flash, seed;
    /** Each scene's sliders, 0..1 (0.5 = the look it was made with); uploaded as uTune. */
    private final float[][] tune = new float[Scene.ALL.length][Scene.TUNE];
    // Drops: seconds since the last one (the scenes' drop() envelope), and a surge that runs the
    // music clock ahead for a moment so every scene lurches forward on the hit.
    private float dropAge = 100, surge, flowExtra;
    private long lastNanos;
    private final Autopilot director = new Autopilot(sceneIds(), System.nanoTime());
    private final boolean[] usable = new boolean[Scene.ALL.length];
    private boolean lastSilent;
    private final float[][] palette = Palettes.preset(1), target = Palettes.preset(1);
    private boolean rainbow;                                     // Colours: Rainbow (the shader paints the spectrum)
    private ColourDrive drive = ColourDrive.preset(ColourDrive.STILL);
    private final ColourDrive.State colour = new ColourDrive.State();
    private final List<double[]> ripples = new ArrayList<>();   // x, y, born, strength
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke liveStroke;
    private final float[] palFlat = new float[9], rippleFlat = new float[MAX_RIPPLES * 4];

    // The user's hand on the picture, uploaded as uView, uTouch and uPush (see common.glsl). All of it is
    // smoothed here, per frame, so the shaders only ever see small steps. Same numbers as web/engine.js.
    static final float ZOOM_MAX = 3f, TOUCH_REACH = 0.45f, STIR_MAX = 0.08f, LENS_MAX = 0.6f, BLAST = 0.05f,
            TILT_PAN = 0.35f, TILT_MAX = 0.15f, ZOOM_HOLD = 1.5f;
    private float zoom = 1, zoomTarget = 1, panX, panY, panTargetX, panTargetY;
    private float touchX, touchY, reach, lens, stir, pushX, pushY, blast, pushAccX, pushAccY, turnAcc;
    private float lastTouchX, lastTouchY, lastVelX, lastVelY;
    private boolean fingerDown, pinching;
    private double pinchEnd = -100;
    private float pinchWorldX, pinchWorldY, pinchZoom0 = 1;
    private float tiltX, tiltY, tiltRestX, tiltRestY;
    private boolean tiltSeen;

    TavRenderer(Context ctx, AudioCapture capture, Host host) {
        this.ctx = ctx.getApplicationContext();
        this.capture = capture;
        this.host = host;
        for (float[] t : tune) java.util.Arrays.fill(t, 0.5f);
    }

    private static String[] sceneIds() {
        String[] ids = new String[Scene.ALL.length];
        for (int i = 0; i < ids.length; i++) ids[i] = Scene.ALL[i].id;
        return ids;
    }

    // ------------------------------------------------------------------ controls (GL thread)

    /** The user picked a scene: the autopilot holds it and remembers the choice. */
    void setScene(int i) {
        i = applyScene(i, 0.9f);
        director.onUserScene(i);
        host.onAutopilotWeights(director.weights());
    }

    /** The scene from the preferences, or a stand-in for a broken one: not the user's pick. */
    void restoreScene(int i) {
        director.onScene(applyScene(i, 0.9f));
    }

    /** Switches with a crossfade of fade seconds; returns the scene shown (broken ones are skipped). */
    private int applyScene(int i, float fade) {
        int n = Scene.ALL.length;
        i = ((i % n) + n) % n;
        for (int k = 0; k < n && broken[i]; k++) i = (i + 1) % n;
        if (i == scene) return i;
        if (sceneB != null) {
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sceneB.fb);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, trans.fb);
            glBlitFramebuffer(0, 0, sceneB.w, sceneB.h, 0, 0, trans.w, trans.h, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            transStart = time;
            transDur = fade;
        }
        scene = i;
        if (an != null && an.flow > 400) {   // rebase the music clock while the crossfade hides the jump
            an.flow = 0;
            flowExtra = 0;
        }
        host.onSceneChanged(i);
        return i;
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

    /** Autopilot.OFF, SCENES or FULL (scenes and colours). */
    void setAutopilot(int mode) {
        director.setMode(mode);
    }

    void setAutopilotWeights(String weights) {
        director.loadWeights(weights);
    }

    /** The user's own palette is Album or Custom: the autopilot leaves the colours alone. */
    void setColoursLocked(boolean locked) {
        director.setColoursLocked(locked);
    }

    /** The user chose colours or a colour motion: the autopilot keeps them for the rest of the track. */
    void autopilotUserColours() {
        director.onUserPalette();
    }

    void setQuality(int q) {
        quality = Math.max(0, Math.min(QUALITY_SCALE.length - 1, q));
        adaptive.reset(quality == QUALITY_AUTO ? AdaptiveScale.AUTO_START : 1f);
        applyScale();
    }

    /** The display's refresh rate, so "fast enough to climb" means the same headroom on a 120 Hz panel. */
    void setRefreshRate(float hz) {
        adaptive.setRefreshRate(hz);
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

    void setRainbow(boolean on) {
        rainbow = on;
    }

    void setColourDrive(ColourDrive d) {
        drive = d;
    }

    /** Slider k of scene s (menu → Tune this scene); shows on the next frame. */
    void setTune(int s, int k, float v) {
        if (s < 0 || s >= tune.length || k < 0 || k >= Scene.TUNE) return;
        tune[s][k] = Float.isNaN(v) ? 0.5f : Math.max(0f, Math.min(1f, v));
    }

    /** Called when the playing track changes: the autopilot moves on with the music. */
    void onTrackChanged() {
        if (time > 3) director.onTrack();
        seed = (seed + 1) % 64;
    }

    void addRipple(float x, float y, float strength) {
        ripples.add(new double[]{x, y, time, strength});
        if (ripples.size() > MAX_RIPPLES) ripples.remove(0);
    }

    // ---- touch: the finger on the picture (centred units: the short screen axis is [-1, 1])

    /** A finger landed: from now on the picture bends around it. Drawing starts with {@link #beginStroke}. */
    void touchDown(float x, float y) {
        touchX = lastTouchX = x;
        touchY = lastTouchY = y;
        fingerDown = true;
        lastVelX = lastVelY = 0;
        turnAcc = pushAccX = pushAccY = 0;
    }

    /** The finger moved: the paint under it is dragged along, and a curve stirs it round. */
    void touchMove(float x, float y) {
        float dx = x - lastTouchX, dy = y - lastTouchY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > 0) {
            if (lastVelX != 0 || lastVelY != 0) {
                float lv = (float) Math.sqrt(lastVelX * lastVelX + lastVelY * lastVelY);
                turnAcc += (lastVelX * dy - lastVelY * dx) / (lv * d) * Math.min(1f, d * 20f);   // signed turning, in radians-ish
            }
            lastVelX = dx;
            lastVelY = dy;
        }
        pushAccX += dx;
        pushAccY += dy;
        lastTouchX = touchX = x;
        lastTouchY = touchY = y;
    }

    void touchUp() {
        fingerDown = false;
    }

    /** A tap: a burst of rings and sparks from the point, and the paint around it is shoved outward. */
    void tap(float x, float y) {
        addRipple(x, y, 1f);
        touchX = x;
        touchY = y;
        reach = TOUCH_REACH;
        blast = BLAST;
        fingerDown = false;
    }

    /** Two fingers: pinch to magnify around them (1..ZOOM_MAX); the view springs back after they lift. */
    void pinchBegin(float cx, float cy) {
        pinching = true;
        pinchZoom0 = zoom;
        pinchWorldX = cx / zoom + panX;
        pinchWorldY = cy / zoom + panY;
    }

    void pinchUpdate(float scale, float cx, float cy) {
        if (!pinching) return;
        zoomTarget = Math.max(1f, Math.min(ZOOM_MAX, pinchZoom0 * scale));
        panTargetX = pinchWorldX - cx / zoomTarget;
        panTargetY = pinchWorldY - cy / zoomTarget;
        clampPan();
    }

    void pinchEnd() {
        if (!pinching) return;
        pinching = false;
        pinchEnd = time;
    }

    /** Gravity along the screen's x and y axes (-1..1; +y is up). The scene drifts the way the phone leans. */
    void setTilt(float x, float y) {
        tiltX = x;
        tiltY = y;
        if (!tiltSeen) {
            tiltRestX = x;
            tiltRestY = y;
            tiltSeen = true;
        }
    }

    private void clampPan() {
        float m = Math.max(1, Math.min(width, height));
        float ex = width / m, ey = height / m, room = 1 - 1 / zoomTarget;
        panTargetX = Math.max(-ex * room, Math.min(ex * room, panTargetX));
        panTargetY = Math.max(-ey * room, Math.min(ey * room, panTargetY));
    }

    /** Steps the touch state one frame; uploaded by {@link #use}. */
    private void touchFrame(float dt) {
        float k = 1 - (float) Math.exp(-dt * 14);
        if (fingerDown) {
            reach += (TOUCH_REACH - reach) * k;
            float s = Math.max(-STIR_MAX, Math.min(STIR_MAX, turnAcc * 0.6f / Math.max(stepScale, 0.25f)));
            stir += (s - stir) * k;
            lens += (Math.max(-LENS_MAX, Math.min(LENS_MAX, turnAcc * 6f)) - lens) * k;
            pushX = 0.8f * pushAccX / Math.max(stepScale, 0.25f);
            pushY = 0.8f * pushAccY / Math.max(stepScale, 0.25f);
        } else {
            float f = (float) Math.exp(-dt * 6);
            reach *= blast > 1e-3 ? 1 : f;
            stir *= f;
            lens *= f;
            pushX *= f;
            pushY *= f;
        }
        blast *= (float) Math.exp(-dt * 8);
        turnAcc = pushAccX = pushAccY = 0;
        if (!pinching && zoomTarget != 1 && time - pinchEnd > ZOOM_HOLD) {
            zoomTarget = 1;
            panTargetX = panTargetY = 0;
        }
        float z = 1 - (float) Math.exp(-dt * (pinching ? 14 : 2.5));
        zoom += (zoomTarget - zoom) * z;
        panX += (panTargetX - panX) * z;
        panY += (panTargetY - panY) * z;
        float r = 1 - (float) Math.exp(-dt / 4);   // the way the phone is held is the rest position
        tiltRestX += (tiltX - tiltRestX) * r;
        tiltRestY += (tiltY - tiltRestY) * r;
    }

    private float tiltPan(float t, float rest) {
        return Math.max(-TILT_MAX, Math.min(TILT_MAX, (t - rest) * TILT_PAN));
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
        host.onStrokesChanged(strokes.size());
        return true;
    }

    void addShape(List<float[]> pts) {
        Stroke s = new Stroke(rnd.nextFloat(), time);
        s.points.addAll(pts);
        strokes.add(s);
        trimStrokes();
        host.onStrokesChanged(strokes.size());
    }

    void clearStrokes() {
        for (Stroke s : strokes) s.release();
        strokes.clear();
        liveStroke = null;
        ripples.clear();
        host.onStrokesChanged(0);
    }

    /** Takes back the latest drawing (the one being drawn, if a finger is down). */
    void undoStroke() {
        if (strokes.isEmpty()) return;
        Stroke s = strokes.remove(strokes.size() - 1);
        if (s == liveStroke) liveStroke = null;
        s.release();
        host.onStrokesChanged(strokes.size());
    }

    int strokeCount() {
        return strokes.size();
    }

    private void trimStrokes() {
        while (strokes.size() > MAX_STROKES) {
            Stroke old = strokes.remove(0);
            if (old == liveStroke) liveStroke = null;
            old.release();
        }
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
        touchFrame(dt);
        adapt(dt, stalled);
        autopilot(dt);
        for (int k = 0; k < 3; k++)
            for (int c = 0; c < 3; c++) palette[k][c] += (target[k][c] - palette[k][c]) * (1 - (float) Math.exp(-dt * 1.5));
        drive.step(colour, dt, shaped, shapedLevel, an.onBeat, an.drop);
        if (an.drop && !calm) {   // Calm: no flash, and no re-roll of the scenes' look on a drop
            seed = (seed + 1) % 64;
            flash = eq.dropFlash();
        }
        flash *= (float) Math.exp(-dt * 4);
        dropAge += dt;
        if (an.drop && !calm) {
            dropAge = 0;
            surge = 1;
        }
        flowExtra += surge * 2f * dt;
        surge *= (float) Math.exp(-dt * 1.5);
        if (flowExtra > 2000) flowExtra -= 2000;
        Scene sc = Scene.ALL[scene];
        if (sc.beatRipples && an.onBeat && !calm) addRipple(0, 0, 0.5f + 0.4f * shaped[0]);   // under 0.95: no sparks

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
            restoreScene(scene + 1);
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

        float tm = (float) ((time - transStart) / transDur);
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

    /** The director hears the shaped bands and the analyzer's flags; a look it asks for is applied here. */
    private void autopilot(float dt) {
        for (int i = 0; i < usable.length; i++) usable[i] = !broken[i];
        Autopilot.Look look = director.step(dt, shaped, shapedLevel, an.intensity, an.onBeat, an.drop, an.silent, usable);
        if (look == null) return;
        if (look.scene >= 0) applyScene(look.scene, look.fade);
        host.onAutopilotLook(look);
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
        uniform1f(p, "uFlow", an.flow + flowExtra);
        uniform1f(p, "uDrop", calm ? 100f : dropAge);
        uniform1f(p, "uStep", stepScale);
        uniform1f(p, "uHistHead", an.histHead);
        uniform1f(p, "uSym", sym);
        uniform1f(p, "uSeed", seed);
        int l = p.loc("uView");
        if (l >= 0) glUniform4f(l, zoom, panX + tiltPan(tiltX, tiltRestX), panY + tiltPan(tiltY, tiltRestY), lens);
        l = p.loc("uTouch");
        if (l >= 0) glUniform4f(l, touchX, touchY, reach, stir);
        l = p.loc("uPush");
        if (l >= 0) glUniform4f(l, pushX, pushY, blast, 0f);
        l = p.loc("uTune");
        if (l >= 0) glUniform4f(l, tune[scene][0], tune[scene][1], tune[scene][2], 0f);
        l = p.loc("uBands");
        if (l >= 0) glUniform4f(l, shaped[0], shaped[1], shaped[2], shaped[3]);
        l = p.loc("uEnergy");
        if (l >= 0) glUniform4f(l, shapedLevel, shapedBeat, an.beatCount, an.intensity);
        l = p.loc("uPal");
        if (l >= 0) {
            for (int k = 0; k < 3; k++) System.arraycopy(palette[k], 0, palFlat, k * 3, 3);
            glUniform3fv(l, 3, palFlat, 0);
        }
        l = p.loc("uColour");
        if (l >= 0) glUniform4f(l, colour.hue, drive.tilt, drive.pump, rainbow ? 1f : 0f);
        l = p.loc("uBandMix");
        if (l >= 0) glUniform3f(l, colour.mix[0], colour.mix[1], colour.mix[2]);
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
