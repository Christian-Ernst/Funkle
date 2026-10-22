package io.github.christianernst.tav;

/**
 * Plain-JVM check of AdaptiveScale: start-up compile stalls must not lower the resolution,
 * an occasional hitch must not either, sustained slowness must, and sustained speed must
 * bring it back.
 */
public final class AdaptiveScaleCheck {
    private static boolean failed;

    public static void main(String[] args) {
        // 1. shader compiles during start-up: 30 very slow frames, all marked as compiles
        AdaptiveScale a = new AdaptiveScale();
        boolean changed = false;
        for (int i = 0; i < 30; i++) changed |= a.update(0.25f, true, true);
        check("compile stalls ignored", !changed && a.scale() == 1f);

        // 2. one 200 ms hitch every second, otherwise 60 fps, for a minute
        a = new AdaptiveScale();
        changed = false;
        for (int f = 0; f < 60 * 60; f++) changed |= a.update(f % 60 == 0 ? 0.2f : 1f / 60, false, true);
        check("occasional hitches ignored", !changed && a.scale() == 1f);

        // 3. sustained 30 fps: one step after about 2.5 s, the next one 2.5 s later
        a = new AdaptiveScale();
        int drops = 0;
        for (int f = 0; f < 80; f++) if (a.update(1f / 30, false, true)) drops++;
        check("sustained slow frames step down once", drops == 1 && near(a.scale(), 0.85f));
        for (int f = 0; f < 80; f++) if (a.update(1f / 30, false, true)) drops++;
        check("and again, not faster", drops == 2 && near(a.scale(), 0.7225f));

        // 4. the quality floor: nothing lowers below it
        a = new AdaptiveScale();
        changed = false;
        for (int f = 0; f < 10 * 30; f++) changed |= a.update(1f / 30, false, false);
        check("floor respected", !changed && a.scale() == 1f);

        // 5. recovery from two steps down (0.7225): 15 s of fast frames raises one step, 30 s to full
        a = new AdaptiveScale();
        for (int f = 0; f < 3 * 30 * 2; f++) a.update(1f / 30, false, true);
        for (int f = 0; f < 16 * 60; f++) a.update(1f / 60, false, true);
        check("sustained speed steps back up", near(a.scale(), 0.85f));
        for (int f = 0; f < 16 * 60; f++) a.update(1f / 60, false, true);
        check("and back to full", near(a.scale(), 1f));

        // 6. medium frames (between fast and slow) neither drop nor count as recovery
        a = new AdaptiveScale();
        for (int f = 0; f < 80; f++) a.update(1f / 30, false, true);
        changed = false;
        for (int f = 0; f < 60 * 60; f++) changed |= a.update(1f / 48, false, true);
        check("medium frames leave the scale alone", !changed && near(a.scale(), 0.85f));

        // 7. Auto starts at 0.7 and climbs to full when the frames stay fast: three steps, 15 s each
        a = new AdaptiveScale();
        a.reset(AdaptiveScale.AUTO_START);
        check("auto starts below full", near(a.scale(), 0.7f));
        for (int f = 0; f < 16 * 60; f++) a.update(1f / 60, false, true);
        check("auto climbs one step", near(a.scale(), 0.7f / 0.85f));
        for (int f = 0; f < 2 * 16 * 60; f++) a.update(1f / 60, false, true);
        check("and reaches full within 45 s", near(a.scale(), 1f));

        // 8. a phone on the edge: fast until it climbs, then slow at the higher size. Each undone
        //    climb doubles the wait (15, 30, 60, 120 s), so the flicker dies out instead of repeating.
        a = new AdaptiveScale();
        a.reset(AdaptiveScale.AUTO_START);
        float[] waits = new float[4];
        for (int round = 0; round < 4; round++) {
            float t = 0;
            while (!a.update(1f / 60, false, true)) t += 1f / 60;   // climb
            waits[round] = t;
            for (int f = 0; f < 80 && !a.update(1f / 30, false, true); f++) { /* slow at the new size: step back */ }
        }
        check("the climb wait doubles after each undone climb: " + Math.round(waits[0]) + ", " + Math.round(waits[1]) + ", "
                + Math.round(waits[2]) + ", " + Math.round(waits[3]) + " s",
                near(waits[0], 15f) && near(waits[1], 30f) && near(waits[2], 60f) && near(waits[3], 120f));
        check("and lands back where it started", near(a.scale(), 0.7f));

        // 9. a climb that held (slow frames only much later) does not lengthen the wait
        a = new AdaptiveScale();
        a.reset(AdaptiveScale.AUTO_START);
        for (int f = 0; f < 16 * 60; f++) a.update(1f / 60, false, true);
        for (int f = 0; f < 60 * 60; f++) a.update(1f / 50, false, true);   // a minute of medium frames
        for (int f = 0; f < 80 && !a.update(1f / 30, false, true); f++) { /* drop */ }
        float t = 0;
        while (!a.update(1f / 60, false, true)) t += 1f / 60;
        check("a climb that held keeps the short wait", near(t, 15f));

        if (failed) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("OK");
    }

    private static boolean near(float v, float want) {
        return Math.abs(v - want) < (want > 2 ? 0.1f : 1e-4f);   // seconds are counted in 1/60 steps
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
