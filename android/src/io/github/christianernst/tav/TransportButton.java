package io.github.christianernst.tav;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Round glass button with a drawn previous / play / pause / next icon. Drawn rather than
 * typed: the ⏮ ⏯ ⏭ characters turn into colour emoji on many Android fonts.
 */
final class TransportButton extends View {
    static final int PREVIOUS = 0, PLAY = 1, PAUSE = 2, NEXT = 3;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean primary;
    private int kind;

    TransportButton(Context ctx, int kind, boolean primary) {
        super(ctx);
        this.kind = kind;
        this.primary = primary;
        fill.setColor(primary ? 0xFFFF2E8C : 0x1AFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(0x33FFFFFF);
        ring.setStrokeWidth(ctx.getResources().getDisplayMetrics().density);
        icon.setColor(0xFFFFFFFF);
        setClickable(true);
        describe();
    }

    void setKind(int kind) {
        if (this.kind == kind) return;
        this.kind = kind;
        describe();
        invalidate();
    }

    private void describe() {
        setContentDescription(kind == PREVIOUS ? "Previous track" : kind == NEXT ? "Next track" : kind == PLAY ? "Play" : "Pause");
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, r = Math.min(w, h) / 2 - ring.getStrokeWidth();
        c.drawCircle(cx, cy, r, fill);
        if (!primary) c.drawCircle(cx, cy, r, ring);
        float s = r * 0.42f;   // icon half-size
        path.reset();
        switch (kind) {
            case PLAY:
                triangle(cx - s * 0.7f, cy, s * 1.6f, true);
                break;
            case PAUSE:
                c.drawRect(cx - s * 0.75f, cy - s, cx - s * 0.2f, cy + s, icon);
                c.drawRect(cx + s * 0.2f, cy - s, cx + s * 0.75f, cy + s, icon);
                break;
            case NEXT:
                triangle(cx - s * 0.85f, cy, s * 1.3f, true);
                c.drawRect(cx + s * 0.55f, cy - s * 0.85f, cx + s * 0.85f, cy + s * 0.85f, icon);
                break;
            default:   // PREVIOUS
                triangle(cx + s * 0.85f, cy, s * 1.3f, false);
                c.drawRect(cx - s * 0.85f, cy - s * 0.85f, cx - s * 0.55f, cy + s * 0.85f, icon);
        }
        c.drawPath(path, icon);
    }

    /** Triangle whose flat side sits at x0, pointing right (or left) by `len`. */
    private void triangle(float x0, float cy, float len, boolean right) {
        float half = len * 0.62f, tip = right ? x0 + len : x0 - len;
        path.moveTo(x0, cy - half);
        path.lineTo(tip, cy);
        path.lineTo(x0, cy + half);
        path.close();
    }
}
