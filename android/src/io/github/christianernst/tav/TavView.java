package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * The GL surface plus touch: tap = ripple, drag = draw with sound, two-finger
 * swipe = change scene, long-press = menu. Touch work is handed to the renderer
 * on the GL thread with queueEvent.
 */
final class TavView extends GLSurfaceView {
    interface Gestures {
        void onAnyTouch();

        void onLongPress();

        /** A drag became a stroke (called on the UI thread, once per stroke). */
        void onDraw();
    }

    private final TavRenderer renderer;
    private final Gestures gestures;
    private final float slop, swipeMin;
    private float downX, downY, twoStartX, twoLastX;
    private boolean drawing, multi, longFired;

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

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        gestures.onAnyTouch();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                drawing = multi = longFired = false;
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout() + 100);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                removeCallbacks(longPress);
                if (drawing) {
                    drawing = false;
                    queueEvent(renderer::endStroke);
                }
                multi = true;
                twoStartX = twoLastX = avgX(e);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (multi) {
                    twoLastX = avgX(e);
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
                        for (int i = 0; i < xs.length; i++) renderer.extendStroke(xs[i], ys[i]);
                    });
                }
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (e.getPointerCount() == 2) {   // the second-to-last finger lifts: judge the swipe
                    float dx = twoLastX - twoStartX;
                    if (Math.abs(dx) > swipeMin) {
                        final int dir = dx < 0 ? 1 : -1;
                        queueEvent(() -> renderer.nextScene(dir));
                    }
                    twoStartX = twoLastX;
                }
                return true;
            case MotionEvent.ACTION_UP:
                removeCallbacks(longPress);
                if (drawing) {
                    queueEvent(renderer::endStroke);
                } else if (!multi && !longFired) {
                    final float x = cx(e.getX()), y = cy(e.getY());
                    queueEvent(() -> renderer.addRipple(x, y, 1f));
                }
                drawing = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPress);
                if (drawing) queueEvent(renderer::endStroke);
                drawing = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }
}
