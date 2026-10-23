package io.github.christianernst.tav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The guided introduction: one feature at a time, on a small bubble over the running visuals, never a
 * stack of slides. A step is shown when its moment comes (a cue from the UI: the first stroke was just
 * drawn, a song title just appeared) or, for the rest, from a slow clock, one every {@link #GAP_MS}
 * while nothing else is on screen. A step is done when the user does the thing or taps "Got it"; a
 * step that was shown {@link #MAX_SHOWS} times and ignored stops coming back on its own but stays
 * undiscovered on the help page, where the whole tour can be started again. "Skip the tour" turns
 * the clock off. Pure Java, no Android: {@code TourCheck} runs it on the JVM and {@code web/tour.js}
 * is the same class for the browser edition.
 */
final class Tour {
    static final class Step {
        final String id, title, body;
        /** Shown only once this other step is done (null: any time). */
        final String after;
        /** Shown only at its moment ({@link #cue}), never from the clock. */
        final boolean onCue;

        Step(String id, String title, String body, String after, boolean onCue) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.after = after;
            this.onCue = onCue;
        }
    }

    /** A step is shown at most this often on its own. */
    static final int MAX_SHOWS = 2;
    /** Quiet time between two bubbles from the clock. */
    static final long GAP_MS = 25_000;
    /** A cued step waits at least this long after the last bubble, so two never land together. */
    static final long CUE_GAP_MS = 2_000;
    /** The first bubble of a session comes this long after {@link #start}. */
    static final long FIRST_MS = 9_000;

    /** The phone's steps, in the order the clock offers them. {n} and {scenes} are filled by the host. */
    static final Step[] PHONE = {
            new Step("draw", "Draw with sound",
                    "Drag one finger across the light. The line you draw plays the music.", null, false),
            new Step("mirror", "Mirrored ×{n}",
                    "Your line is drawn {n} times around the centre, like a mandala. "
                            + "Tap ×{n} at the bottom to change it; ×1 keeps one line.", "draw", true),
            new Step("tap", "A burst", "Tap anywhere: sparks in the colours of the music, and a shove to the paint.", null, false),
            new Step("scene", "{scenes} scenes",
                    "Swipe with two fingers, or use ‹ › at the bottom. Each scene listens to the music in its own way.", null, false),
            new Step("title", "The song title", "Swipe it for the next track, tap it to pause. It fades on its own.", null, true),
            new Step("menu", "The menu", "Long-press the light, or tap ☰, for colours, looks, autopilot and more.", null, false),
            new Step("shuffle", "Three colours that fit",
                    "⚄ at the bottom shuffles the colours. Colours in the menu has palettes, your album's colours and your own.", null, false),
            new Step("pinch", "Zoom and clear", "Pinch to zoom in for a moment. A three-finger tap clears the drawing.", "draw", false),
            new Step("autopilot", "Autopilot", "It changes scenes with the music's energy and waits for the drop. "
                    + "Your own picks always win. Find it in the menu.", "menu", false),
            new Step("tune", "Tune this scene", "Every scene has three sliders of its own: menu → Tune.", "menu", false),
            new Step("looks", "Keep what you made", "Like this? Save it as a look in the menu, and share it as a six-letter code.", "menu", false),
    };

    private final Step[] steps;
    private final Set<String> done = new LinkedHashSet<>();
    private final Map<String, Integer> shows = new HashMap<>();
    private boolean off;
    private long lastAt = Long.MIN_VALUE;   // when a bubble last showed or hid
    private Step current;
    private String cued;

    Tour(Step[] steps) {
        this.steps = steps;
    }

    Step[] steps() {
        return steps;
    }

    Step find(String id) {
        for (Step s : steps) if (s.id.equals(id)) return s;
        return null;
    }

    boolean isDone(String id) {
        return done.contains(id);
    }

    boolean isOff() {
        return off;
    }

    /** The step on screen, or null. */
    Step current() {
        return current;
    }

    int doneCount() {
        int n = 0;
        for (Step s : steps) if (done.contains(s.id)) n++;
        return n;
    }

    int size() {
        return steps.length;
    }

    /** The steps not yet discovered, in order. */
    List<Step> remaining() {
        List<Step> out = new ArrayList<>();
        for (Step s : steps) if (!done.contains(s.id)) out.add(s);
        return out;
    }

    /** A session begins: the first bubble comes {@link #FIRST_MS} from now. */
    void start(long now) {
        lastAt = now - GAP_MS + FIRST_MS;
        current = null;
    }

    /** This step's moment is now (the first stroke just landed, a title just appeared). */
    void cue(String id) {
        if (off || done.contains(id) || find(id) == null) return;
        cued = id;
    }

    private boolean eligible(Step s, boolean fromClock) {
        if (done.contains(s.id) || count(s.id) >= MAX_SHOWS) return false;
        if (s.after != null && !done.contains(s.after)) return false;
        return !(s.onCue && fromClock);
    }

    private int count(String id) {
        Integer n = shows.get(id);
        return n == null ? 0 : n;
    }

    /**
     * The step to show now, or null: nothing while the tour is off, something else is on screen (busy),
     * a bubble is up, or the quiet time is not over. A cued step goes first and waits for nothing but
     * {@link #CUE_GAP_MS}; the clock offers the rest in order.
     */
    Step pick(long now, boolean busy) {
        if (off || busy || current != null) return null;
        if (cued != null) {
            Step s = find(cued);
            if (s == null || !eligible(s, false)) cued = null;
            else if (now - lastAt >= CUE_GAP_MS) {
                cued = null;
                return s;
            } else return null;   // not yet, and nothing else in front of it
        }
        if (now - lastAt < GAP_MS) return null;
        for (Step s : steps) if (eligible(s, true)) return s;
        return null;
    }

    /** The host put the step on screen. */
    void shown(Step s, long now) {
        current = s;
        shows.put(s.id, count(s.id) + 1);
        lastAt = now;
    }

    /** The bubble faded unanswered (or was hidden for the menu). */
    void hid(long now) {
        current = null;
        lastAt = now;
    }

    /** The user did the thing (or tapped "Got it"). Returns true when that step was on screen. */
    boolean did(String id, long now) {
        if (find(id) == null) return false;
        done.add(id);
        if (id.equals(cued)) cued = null;
        boolean wasUp = current != null && current.id.equals(id);
        if (wasUp) {
            current = null;
            lastAt = now;
        }
        return wasUp;
    }

    /** "Skip the tour": no more bubbles until {@link #restart}. */
    void skip() {
        off = true;
        current = null;
        cued = null;
    }

    /** The help page's "Start the tour again": everything undiscovered, first bubble in a moment. */
    void restart(long now) {
        done.clear();
        shows.clear();
        off = false;
        current = null;
        cued = null;
        lastAt = now - GAP_MS + 1500;
    }

    /** The state as one line for a preference: {@code off=0;done=draw,tap;shows=draw:2,scene:1}. */
    String save() {
        StringBuilder b = new StringBuilder("off=").append(off ? 1 : 0).append(";done=");
        boolean first = true;
        for (String id : done) {
            if (!first) b.append(',');
            b.append(id);
            first = false;
        }
        b.append(";shows=");
        first = true;
        for (Step s : steps) {
            int n = count(s.id);
            if (n == 0) continue;
            if (!first) b.append(',');
            b.append(s.id).append(':').append(n);
            first = false;
        }
        return b.toString();
    }

    /** Reads what {@link #save} wrote; unknown ids are dropped, garbage is ignored. */
    void load(String line) {
        done.clear();
        shows.clear();
        off = false;
        if (line == null) return;
        for (String part : line.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq), v = part.substring(eq + 1);
            if (k.equals("off")) off = v.equals("1");
            else if (k.equals("done")) {
                for (String id : v.split(",")) if (find(id) != null) done.add(id);
            } else if (k.equals("shows")) {
                for (String e : v.split(",")) {
                    int c = e.indexOf(':');
                    if (c < 0 || find(e.substring(0, c)) == null) continue;
                    try {
                        shows.put(e.substring(0, c), Math.max(0, Integer.parseInt(e.substring(c + 1))));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
    }
}
