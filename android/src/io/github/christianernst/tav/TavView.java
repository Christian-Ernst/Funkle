package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * The GL surface plus touch: tap = burst, drag = draw with sound (and stir the paint under the
 * finger), pinch = zoom in around the fingers, two-finger swipe = change scene, three-finger tap =
 * clear the drawings, long-press = menu. Tilting the phone steers the scene a little. Touch work is
 * handed to the renderer on the GL thread with queueEvent.
 */
final class TavView extends GLSurfaceView implements SensorEventListener {
    interface Gestures {
        void onAnyTouch();

        void onLongPress();

        /** A drag became a stroke (called on the UI thread, once per stroke). */
        void onDraw();
    }

    /** A pinch is one when the fingers' distance changed by this share before they swiped. */
    private static final float PINCH_START = 0.12f;
    private static final long THREE_TAP_MS = 350;

    private final TavRenderer renderer;
    private final Gestures gestures;
    private final float slop, swipeMin;
    private float downX, downY, twoStartX, twoLastX, pinchDist0;
    private boolean drawing, multi, longFired, pinching, threeTap;
    private long threeDownAt;
    private SensorManager sensors;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            if (!drawing && !multi) {
                longFired = true;
                gestures.onLongPress();
            }
        }
    };

    TavView(Context ctx, TavRenderer renderer, Gestures gestures) {
        super(ctx);
        this.renderer = renderer;
        this.gestures = gestures;
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 0, 0);
        setPreserveEGLContextOnPause(true);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
        float density = ctx.getResources().getDisplayMetrics().density;
        slop = Math.max(ViewConfiguration.get(ctx).getScaledTouchSlop(), 10 * density);
        swipeMin = 90 * density;
    }

    // ---- tilt: gravity along the screen axes, so the scene leans the way the phone does

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            sensors = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
            Sensor g = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (g == null && sensors != null) g = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_GAME);
        } catch (RuntimeException e) {
            sensors = null;   // no sensors on this device: the view just does not tilt
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (sensors != null) sensors.unregisterListener(this);
        sensors = null;
        super.onDetachedFromWindow();
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        // The sensor's x points to the device's right and y to its top; the screen may be rotated.
        float x = e.values[0] / SensorManager.GRAVITY_EARTH, y = e.values[1] / SensorManager.GRAVITY_EARTH;
        int rotation = Surface.ROTATION_0;
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) rotation = wm.getDefaultDisplay().getRotation();
        } catch (RuntimeException ignored) {
        }
        final float sx, sy;
        switch (rotation) {
            case Surface.ROTATION_90:  sx = -y; sy = x;  break;
            case Surface.ROTATION_180: sx = -x; sy = -y; break;
            case Surface.ROTATION_270: sx = y;  sy = -x; break;
            default:                   sx = x;  sy = y;  break;
        }
        // Gravity reads positive along the axis that points up, so a lean to the right (right edge
        // down) gives sx < 0; the renderer pans the view toward the low side.
        final float tx = Math.max(-1f, Math.min(1f, -sx)), ty = Math.max(-1f, Math.min(1f, sy));
        queueEvent(() -> renderer.setTilt(tx, ty));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ---- touch

    private float cx(float x) {
        return (x - getWidth() * 0.5f) / Math.min(getWidth(), getHeight()) * 2f;
    }

    private float cy(float y) {
        return -(y - getHeight() * 0.5f) / Math.min(getWidth(), getHeight()) * 2f;
    }

    private static float avgX(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getX(i);
        return s / e.getPointerCount();
    }

    private static float avgY(MotionEvent e) {
        float s = 0;
        for (int i = 0; i < e.getPointerCount(); i++) s += e.getY(i);
        return s / e.getPointerCount();
    }

    private static float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0;
        return (float) Math.hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestures.onAnyTouch();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downX = e.getX();
                downY = e.getY();
                drawing = multi = longFired = pinching = threeTap = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 100);
                final float x0 = cx(downX), y0 = cy(downY);
                queueEvent(() -> renderer.touchDown(x0, y0));
                return true;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(longPress);
                if (drawing) {
                    drawing = false;
                    queueEvent(renderer::endStroke);
                }
                queueEvent(renderer::touchUp);
                multi = true;
                if (e.getPointerCount() == 2) {
                    twoStartX = twoLastX = avgX(e);
                    pinchDist0 = Math.max(1f, span(e));
                    pinching = false;
                } else if (e.getPointerCount() == 3) {
                    if (pinching) queueEvent(renderer::pinchEnd);
                    pinching = false;
                    threeTap = true;
                    threeDownAt = e.getEventTime();
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    if (e.getPointerCount() == 2) {
                        float scale = span(e) / pinchDist0;
                        if (!pinching && Math.abs(scale - 1) > PINCH_START && Math.abs(avgX(e) - twoStartX) < swipeMin) {
                            pinching = true;
                            final float px = cx(avgX(e)), py = cy(avgY(e));
                            pinchDist0 = Math.max(1f, span(e));
                            queueEvent(() -> renderer.pinchBegin(px, py));
                        }
                        if (pinching) {
                            final float sc = span(e) / pinchDist0, px = cx(avgX(e)), py = cy(avgY(e));
                            queueEvent(() -> renderer.pinchUpdate(sc, px, py));
                        } else {
                            twoLastX = avgX(e);
                        }
                    } else if (threeTap && Math.hypot(e.getX() - downX, e.getY() - downY) > slop * 2) {
                        threeTap = false;
                    }
                    return true;
                }
                if (longFired) return true;
                if (!drawing && Math.hypot(e.getX() - downX, e.getY() - downY) > slop) {
                    drawing = true;
                    removeCallbacks(longPress);
                    gestures.onDraw();
                    final float x0 = cx(downX), y0 = cy(downY);
                    queueEvent(() -> renderer.beginStroke(x0, y0));
                }
                if (drawing) {
                    final int hs = e.getHistorySize();
                    final float[] xs = new float[hs + 1], ys = new float[hs + 1];
                    for (int i = 0; i < hs; i++) {
                        xs[i] = cx(e.getHistoricalX(i));
                        ys[i] = cy(e.getHistoricalY(i));
                    }
                    xs[hs] = cx(e.getX());
                    ys[hs] = cy(e.getY());
                    queueEvent(() -> {
                        for (int i = 0; i < xs.length; i++) {
                            renderer.extendStroke(xs[i], ys[i]);
                            renderer.touchMove(xs[i], ys[i]);
                        }
                    });
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() == 3 && threeTap && e.getEventTime() - threeDownAt < THREE_TAP_MS) {
                    queueEvent(renderer::clearStrokes);
                    threeTap = false;
                    twoStartX = twoLastX;   // the remaining two fingers do not count as a swipe
                    return true;
                }
                if (e.getPointerCount() == 2) {   // the second-to-last finger lifts: judge the swipe
                    if (pinching) {
                        queueEvent(renderer::pinchEnd);
                        pinching = false;
                    } else {
                        float dx = twoLastX - twoStartX;
                        if (Math.abs(dx) > swipeMin) {
                            final int dir = dx < 0 ? 1 : -1;
                            queueEvent(() -> renderer.nextScene(dir));
                        }
                    }
                    twoStartX = twoLastX;
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (drawing) {
                    queueEvent(renderer::endStroke);
                    queueEvent(renderer::touchUp);
                } else if (!multi && !longFired) {
                    final float x = cx(e.getX()), y = cy(e.getY());
                    queueEvent(() -> renderer.tap(x, y));
                } else {
                    queueEvent(renderer::touchUp);
                }
                drawing = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (drawing) queueEvent(renderer::endStroke);
                if (pinching) queueEvent(renderer::pinchEnd);
                queueEvent(renderer::touchUp);
                drawing = pinching = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
