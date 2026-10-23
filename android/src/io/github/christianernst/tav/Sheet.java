package io.github.christianernst.tav;

import android.animation.LayoutTransition;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The menu: a bottom sheet over the visuals with a stack of pages. The home page is a grid of
 * groups; a tap pushes a page (Scenes, Colours, a slider card...), the arrow in its header or Back
 * pops it. The handle at the top drags the sheet down to close it. Built from plain Views (the
 * build has no Gradle and no AndroidX), so there is no Material bottom sheet here, only its manners:
 * rounded top, a handle, 48 dp targets, a page that slides in from the right.
 */
final class Sheet extends FrameLayout {
    interface Host {
        /** The user dragged the handle down or tapped ✕: close everything (the host owns the page state). */
        void onDismiss();

        /** The arrow in a page header: like the Back key. */
        void onBack();
    }

    /** Max height as a share of the window; the visuals stay visible above it. */
    private static final float MAX_SHARE = 0.68f;
    private static final int MAX_WIDTH_DP = 520;

    private final float dp;
    private final Host host;
    private final FrameLayout pages;
    private final List<View> stack = new ArrayList<>();
    private final TextView title, back;
    private final View handle;
    private boolean shown;
    private float dragY;
    private boolean dragging;

    Sheet(Context ctx, Host host) {
        super(ctx);
        this.host = host;
        dp = ctx.getResources().getDisplayMetrics().density;
        setClickable(true);   // touches never fall through to the visuals
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF00B0B16);
        float r = 28 * dp;
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bg.setStroke(Math.round(dp), 0x2EFFFFFF);
        setBackground(bg);
        setElevation(12 * dp);
        setClipToOutline(false);

        LinearLayout column = new LinearLayout(ctx);
        column.setOrientation(LinearLayout.VERTICAL);
        LayoutTransition lt = new LayoutTransition();
        lt.enableTransitionType(LayoutTransition.CHANGING);
        lt.setDuration(220);
        column.setLayoutTransition(lt);

        // the handle: a pill, plus the whole strip above the header drags
        handle = new View(ctx);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x59FFFFFF);
        pill.setCornerRadius(3 * dp);
        handle.setBackground(pill);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(px(40), px(5));
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = px(10);
        column.addView(handle, hlp);

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(8), px(4), px(8), 0);
        back = glyph(ctx, "←", "Back");
        back.setOnClickListener(v -> {
            tick(v);
            host.onBack();
        });
        header.addView(back, new LinearLayout.LayoutParams(px(48), px(48)));
        title = new TextView(ctx);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        title.setTextColor(Color.WHITE);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setSingleLine(true);
        title.setPadding(px(6), 0, px(6), 0);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = glyph(ctx, "✕", "Close the menu");
        close.setOnClickListener(v -> {
            tick(v);
            host.onDismiss();
        });
        header.addView(close, new LinearLayout.LayoutParams(px(48), px(48)));
        column.addView(header, new LinearLayout.LayoutParams(-1, -2));
        OnTouchListener drag = this::drag;
        header.setOnTouchListener(drag);
        setOnTouchListener(drag);

        pages = new FrameLayout(ctx);
        column.addView(pages, new LinearLayout.LayoutParams(-1, -2));
        addView(column, new LayoutParams(-1, -2));
        setVisibility(GONE);
    }

    private int px(float v) {
        return Math.round(v * dp);
    }

    private TextView glyph(Context ctx, String s, String description) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(0xE6FFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(description);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x14FFFFFF);
        t.setBackground(bg);
        return t;
    }

    static void tick(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** Caps the height so the scene stays in view, and the width on tablets. */
    @Override
    protected void onMeasure(int w, int h) {
        View parent = (View) getParent();
        int maxH = parent != null && parent.getHeight() > 0 ? Math.round(parent.getHeight() * MAX_SHARE) : MeasureSpec.getSize(h);
        int maxW = px(MAX_WIDTH_DP), width = MeasureSpec.getSize(w);
        if (width > maxW) w = MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.EXACTLY);
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
    }

    private ScrollView page(View content) {
        ScrollView s = new ScrollView(getContext());
        s.setVerticalScrollBarEnabled(false);
        s.setClipToPadding(false);
        s.setPadding(px(16), 0, px(16), px(22));
        s.addView(content, new LayoutParams(-1, -2));
        return s;
    }

    /** The first page. Its title is the wordmark; it has no back arrow. */
    void setHome(View content) {
        pages.removeAllViews();
        stack.clear();
        ScrollView p = page(content);
        pages.addView(p, new LayoutParams(-1, -2));
        stack.add(p);
        header(0);
    }

    /** Pushes a page with a header and returns it (the handle the host closes it with). */
    View push(String pageTitle, View content) {
        ScrollView p = page(content);
        p.setTag(pageTitle);
        View top = stack.get(stack.size() - 1);
        top.animate().alpha(0f).translationX(-24 * dp).setDuration(160).withEndAction(() -> top.setVisibility(INVISIBLE)).start();
        pages.addView(p, new LayoutParams(-1, -2));
        stack.add(p);
        p.setAlpha(0f);
        p.setTranslationX(48 * dp);
        p.animate().alpha(1f).translationX(0).setDuration(220).start();
        header(stack.size() - 1);
        return p;
    }

    /** Pops the top page. On the home page, closes the sheet. */
    void pop() {
        if (stack.size() <= 1) {
            hide();
            return;
        }
        remove(stack.get(stack.size() - 1));
    }

    /** Removes a page wherever it is in the stack (a card closed by its own Done). */
    void remove(View p) {
        int i = stack.indexOf(p);
        if (i <= 0) return;
        boolean top = i == stack.size() - 1;
        stack.remove(i);
        if (!top) {   // below the top: just drop it
            pages.removeView(p);
            return;
        }
        p.animate().alpha(0f).translationX(48 * dp).setDuration(160).withEndAction(() -> pages.removeView(p)).start();
        View below = stack.get(stack.size() - 1);
        below.setVisibility(VISIBLE);
        below.animate().alpha(1f).translationX(0).setDuration(220).start();
        header(stack.size() - 1);
    }

    private void header(int depth) {
        back.setVisibility(depth > 0 ? VISIBLE : INVISIBLE);
        Object t = depth > 0 ? stack.get(depth).getTag() : null;
        title.setText(t != null ? t.toString() : "Funkle");
        title.setLetterSpacing(t != null ? 0f : 0.2f);
        title.setGravity(depth > 0 ? Gravity.START : Gravity.CENTER);
    }

    int depth() {
        return stack.size() - 1;
    }

    boolean shown() {
        return shown;
    }

    void show() {
        if (shown) return;
        shown = true;
        setVisibility(VISIBLE);
        animate().cancel();
        setAlpha(0f);
        setTranslationY(getHeight() > 0 ? getHeight() * 0.35f : 120 * dp);
        animate().alpha(1f).translationY(0).setDuration(260).setInterpolator(new android.view.animation.DecelerateInterpolator(2f)).start();
    }

    /** Slides down and, once gone, drops every page but the home one. */
    void hide() {
        if (!shown) return;
        shown = false;
        animate().cancel();
        animate().alpha(0f).translationY(getHeight() * 0.35f).setDuration(200).withEndAction(() -> {
            setVisibility(GONE);
            setTranslationY(0);
            while (stack.size() > 1) {
                View p = stack.remove(stack.size() - 1);
                pages.removeView(p);
            }
            View home = stack.get(0);
            home.setVisibility(VISIBLE);
            home.setAlpha(1f);
            home.setTranslationX(0);
            header(0);
        }).start();
    }

    /** While a slider is dragged the sheet goes see-through, so the change shows on the scene. */
    void setGhost(boolean ghost) {
        animate().cancel();
        if (shown) animate().alpha(ghost ? 0.38f : 1f).setDuration(ghost ? 120 : 200).start();
    }

    /** The handle and header drag the sheet down; a short drag springs back. */
    private boolean drag(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragY = e.getRawY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = e.getRawY() - dragY;
                if (!dragging && dy > ViewConfiguration.get(getContext()).getScaledTouchSlop()) dragging = true;
                if (dragging) {
                    setTranslationY(Math.max(0, dy));
                    setAlpha(1f - Math.min(0.5f, Math.max(0, dy) / getHeight()));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging && e.getActionMasked() == MotionEvent.ACTION_UP && getTranslationY() > getHeight() * 0.22f) {
                    tick(v);
                    host.onDismiss();
                } else {
                    animate().translationY(0).alpha(1f).setDuration(180).start();
                }
                dragging = false;
                return true;
            default:
                return false;
        }
    }
}
