package io.github.christianernst.tav;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

/**
 * A slider over a colour gradient: hue, saturation or brightness in the colour editor. Drawn
 * rather than a styled SeekBar so the track looks the same on every Android version; screen
 * readers still get it as a seek bar.
 */
final class ColourSlider extends View {
    interface Listener {
        /** done: the finger lifted (or a key or screen-reader step), a good moment to save. */
        void onChange(float value, boolean done);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), knob = new Paint(Paint.ANTI_ALIAS_FLAG),
            ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float dp;
    private int[] colors = {0xFF000000, 0xFFFFFFFF};
    private float value;
    private Listener listener;

    ColourSlider(Context ctx, String name) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f * dp);
        ring.setColor(0xFFFFFFFF);
        setContentDescription(name);
        setFocusable(true);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** The gradient along the track, left to right. */
    void setColors(int... colors) {
        this.colors = colors;
        track.setShader(null);
        invalidate();
    }

    /** The knob shows the colour the slider currently gives. */
    void setKnobColor(int color) {
        knob.setColor(color);
        invalidate();
    }

    void setValue(float v) {
        value = Math.max(0f, Math.min(1f, v));
        invalidate();
    }

    float value() {
        return value;
    }

    private float pad() {
        return 14 * dp;   // knob radius plus its ring
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), wSpec), resolveSize(Math.round(40 * dp), hSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        track.setShader(null);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), cy = getHeight() / 2f, pad = pad(), half = 5 * dp, r = 11 * dp;
        if (track.getShader() == null)
            track.setShader(new LinearGradient(pad, 0, w - pad, 0, colors, null, Shader.TileMode.CLAMP));
        c.drawRoundRect(pad - half, cy - half, w - pad + half, cy + half, half, half, track);
        float x = pad + value * (w - 2 * pad);
        c.drawCircle(x, cy, r, knob);
        c.drawCircle(x, cy, r, ring);
    }

    @SuppressLint("ClickableViewAccessibility")   // a drag control; screen readers use the scroll actions below
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);   // drags stay ours inside a ScrollView
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE || a == MotionEvent.ACTION_UP) {
            float pad = pad();
            setValue((e.getX() - pad) / Math.max(1f, getWidth() - 2 * pad));
            if (listener != null) listener.onChange(value, a == MotionEvent.ACTION_UP);
        } else if (a == MotionEvent.ACTION_CANCEL && listener != null) {
            listener.onChange(value, true);
        }
        return true;
    }

    private void step(int dir) {
        setValue(value + dir * 0.04f);
        if (listener != null) listener.onChange(value, true);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            step(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName(SeekBar.class.getName());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_PERCENT, 0, 100, value * 100));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            step(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
            return true;
        }
        return super.performAccessibilityAction(action, args);
    }
}
