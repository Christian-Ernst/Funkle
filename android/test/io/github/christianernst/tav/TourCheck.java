package io.github.christianernst.tav;

import java.util.HashSet;
import java.util.Set;

/**
 * Plain-JVM check of Tour: the pacing (first bubble, quiet time, cues jump the queue), what counts as
 * done, the show limit, skip and restart, the saved line, and that the phone's step table is sound.
 * web/tools/tour-check.js runs the same cases on web/tour.js.
 */
public final class TourCheck {
    private static boolean failed;

    private static final Tour.Step[] STEPS = {
            new Tour.Step("draw", "Draw", "drag", null, false),
            new Tour.Step("mirror", "Mirror", "x{n}", "draw", true),
            new Tour.Step("tap", "Tap", "tap", null, false),
            new Tour.Step("scene", "Scene", "swipe", null, false),
            new Tour.Step("title", "Title", "swipe it", null, true),
    };

    public static void main(String[] args) {
        Tour t = new Tour(STEPS);
        long now = 1_000_000;
        t.start(now);
        check("nothing at once", t.pick(now, false) == null);
        check("nothing while busy", t.pick(now + Tour.FIRST_MS, true) == null);
        Tour.Step s = t.pick(now + Tour.FIRST_MS, false);
        check("first step after FIRST_MS", s != null && s.id.equals("draw"));
        t.shown(s, now + Tour.FIRST_MS);
        check("one bubble at a time", t.pick(now + Tour.FIRST_MS + 5000, false) == null);

        // the user draws while the bubble is up: done, and the mirror step is cued for right after
        now += Tour.FIRST_MS + 6000;
        check("doing it dismisses the bubble", t.did("draw", now));
        t.cue("mirror");
        check("a cue waits CUE_GAP_MS", t.pick(now + 500, false) == null);
        s = t.pick(now + Tour.CUE_GAP_MS, false);
        check("then the cued step jumps the queue", s != null && s.id.equals("mirror"));
        t.shown(s, now + Tour.CUE_GAP_MS);
        t.hid(now + Tour.CUE_GAP_MS + 14000);   // faded unanswered
        now += Tour.CUE_GAP_MS + 14000;
        check("quiet time after a fade", t.pick(now + Tour.GAP_MS - 1, false) == null);
        s = t.pick(now + Tour.GAP_MS, false);
        check("the clock never offers a cue-only step", s != null && s.id.equals("tap"));

        // a cue for a done step or an unknown id is ignored
        t.cue("draw");
        t.cue("nothing");
        check("stale cues ignored", t.pick(now + Tour.GAP_MS, false) == s);

        // a step shown MAX_SHOWS times and ignored stops coming back but stays undiscovered
        Tour u = new Tour(STEPS);
        long n2 = 0;
        u.start(n2);
        for (int i = 0; i < Tour.MAX_SHOWS; i++) {
            n2 += Tour.GAP_MS;
            Tour.Step d = u.pick(n2, false);
            check("show " + (i + 1) + " is draw", d != null && d.id.equals("draw"));
            u.shown(d, n2);
            u.hid(n2);
        }
        n2 += Tour.GAP_MS;
        Tour.Step d = u.pick(n2, false);
        check("ignored twice: next step instead", d != null && d.id.equals("tap"));
        check("but still undiscovered", !u.isDone("draw") && u.remaining().get(0).id.equals("draw"));
        check("mirror waits for draw", !u.remaining().isEmpty() && u.pick(n2, false).id.equals("tap"));

        // skip, restart, and the saved line
        u.skip();
        check("skipped: nothing", u.isOff() && u.pick(n2 + Tour.GAP_MS * 3, false) == null);
        String line = u.save();
        Tour v = new Tour(STEPS);
        v.load(line);
        check("save/load keeps off and shows: " + line, v.isOff() && v.save().equals(line));
        v.restart(n2);
        check("restart: on again, first bubble soon", !v.isOff() && v.pick(n2 + 1500, false) != null && v.doneCount() == 0);
        v.load("garbage;done=tap,bogus;shows=tap:1,x:3,draw:q");
        check("load drops unknown ids", v.isDone("tap") && v.doneCount() == 1 && v.save().equals("off=0;done=tap;shows=tap:1"));
        v.load(null);
        check("load(null) resets", v.doneCount() == 0 && !v.isOff());

        // the phone's table: unique ids, every "after" exists and comes earlier, placeholders only where filled
        Set<String> ids = new HashSet<>();
        boolean sound = true;
        for (int i = 0; i < Tour.PHONE.length; i++) {
            Tour.Step p = Tour.PHONE[i];
            if (!ids.add(p.id)) sound = false;
            if (p.after != null) {
                boolean earlier = false;
                for (int k = 0; k < i; k++) earlier |= Tour.PHONE[k].id.equals(p.after);
                sound &= earlier;
            }
            if (p.body.contains("{") && !p.id.equals("mirror") && !p.id.equals("scene")) sound = false;
            if (p.title.contains("{") && !p.id.equals("mirror") && !p.id.equals("scene")) sound = false;
        }
        check("phone steps are sound (" + Tour.PHONE.length + ")", sound && Tour.PHONE.length >= 9);

        if (failed) System.exit(1);
        System.out.println("  tour ok");
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
