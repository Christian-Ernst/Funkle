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

        if (failed) {
            System.out.println("FAIL");
            System.exit(1);
        }
        System.out.println("OK");
    }

    private static boolean near(float v, float want) {
        return Math.abs(v - want) < 1e-4f;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
