package io.github.christianernst.tav;

/** Plain-JVM check of ColourDrive: a still drive changes nothing, beats step the hue, bands set the mix. */
public final class ColourDriveCheck {
    private static boolean failed;

    public static void main(String[] args) {
        float[] bass = {1f, 0.1f, 0.1f, 0f}, quiet = {0f, 0f, 0f, 0f};
        ColourDrive.State s = new ColourDrive.State();

        ColourDrive still = ColourDrive.preset(ColourDrive.STILL);
        for (int i = 0; i < 100; i++) still.step(s, 1 / 60f, bass, 1f, i % 20 == 0, i == 50);
        check("Still is still", still.still() && s.hue == 0f && s.mix[0] == 0f && s.mix[1] == 0f && s.mix[2] == 0f);

        ColourDrive beat = new ColourDrive(0f, 0.25f, 0f, 0f, 0f);
        s = new ColourDrive.State();
        beat.step(s, 1 / 60f, quiet, 0f, true, false);
        float first = s.hue;
        for (int i = 0; i < 60; i++) beat.step(s, 1 / 60f, quiet, 0f, false, false);
        check("a beat steps the hue and the hue eases there", first > 0f && first < 0.25f && near(s.hue, 0.25f, 0.002f));
        for (int i = 0; i < 3; i++) beat.step(s, 1 / 60f, quiet, 0f, true, false);
        for (int i = 0; i < 120; i++) beat.step(s, 1 / 60f, quiet, 0f, false, false);
        check("four beats make a full turn and wrap", near(s.hue, 0f, 0.002f) && near(s.hueTarget, 0f, 1e-6f));

        ColourDrive drift = new ColourDrive(0.1f, 0f, 0f, 0f, 0f);
        s = new ColourDrive.State();
        for (int i = 0; i < 60; i++) drift.step(s, 1 / 60f, quiet, 1f, false, false);
        float loud = s.hueTarget;
        s = new ColourDrive.State();
        for (int i = 0; i < 60; i++) drift.step(s, 1 / 60f, quiet, 0f, false, false);
        check("drift runs at the setting when loud and slower when quiet", near(loud, 0.1f, 1e-4f) && near(s.hueTarget, 0.03f, 1e-4f));

        ColourDrive drop = new ColourDrive(0f, 0f, 0.5f, 0f, 0f);
        s = new ColourDrive.State();
        drop.step(s, 1 / 60f, quiet, 0f, false, true);
        check("a drop jumps", near(s.hueTarget, 0.5f, 1e-6f));

        ColourDrive bands = ColourDrive.preset(3);
        s = new ColourDrive.State();
        for (int i = 0; i < 120; i++) bands.step(s, 1 / 60f, bass, 1f, false, false);
        check("bass fills the bass share", s.mix[0] > 0.5f && s.mix[0] > 4 * s.mix[1] && s.mix[0] + s.mix[1] + s.mix[2] <= 1f);
        float held = s.mix[0];
        bands.step(s, 1 / 60f, quiet, 0f, false, false);
        check("the share lets go slower than it lights up", s.mix[0] < held && s.mix[0] > 0.9f * held);
        for (int i = 0; i < 120; i++) bands.step(s, 1 / 60f, quiet, 0f, false, false);
        check("silence has no share", s.mix[0] < 0.01f);

        check("with() replaces one setting", ColourDrive.preset(0).with(3, 0.5f).tilt == 0.5f
                && ColourDrive.preset(2).with(0, 0.05f).beatStep == ColourDrive.preset(2).beatStep);
        for (int m = 0; m < ColourDrive.MODE_NAMES.length; m++) {
            ColourDrive d = ColourDrive.preset(m);
            boolean inRange = true;
            for (int i = 0; i < ColourDrive.MAX.length; i++) inRange &= d.amount(i) >= 0 && d.amount(i) <= ColourDrive.MAX[i];
            check("preset " + ColourDrive.MODE_NAMES[m] + " is inside the slider ranges", inRange);
        }
        if (failed) System.exit(1);
        System.out.println("  colour drive ok");
    }

    private static boolean near(float a, float b, float eps) {
        return Math.abs(a - b) <= eps;
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            failed = true;
            System.out.println("  FAIL " + what);
        }
    }
}
