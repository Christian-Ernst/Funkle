package io.github.christianernst.tav;

import java.util.Random;

/**
 * Plain-JVM check of Look (the saved-look document and its share-link payload) and of the
 * harmonious shuffle. Needs a real org.json on the classpath, because android.jar's is a stub;
 * check.sh fetches one.
 */
public final class LookCheck {
    private static boolean failed;

    public static void main(String[] args) {
        // a full Look survives JSON and the link payload
        Look l = new Look();
        l.name = "  Night   drive ";
        l.argb[0] = 0xFFFF2E8C;
        l.argb[1] = 0xFF26BFFF;
        l.argb[2] = 0xFF8C40FF;
        l.rainbow = false;
        l.motion = ColourDrive.preset(ColourDrive.BEAT);
        l.palette = "Custom";
        l.scene = "kaleido";
        l.sym = 6;
        l.brightness = 1;
        l.calm = 1;
        l.eq = Equalizer.punch();
        l.eqMode = 1;
        String json = l.toJson().toString();
        Look back = Look.fromJson(json);
        check("name is cleaned", "Night drive".equals(back.name));
        check("colours round-trip", back.argb[0] == 0xFFFF2E8C && back.argb[1] == 0xFF26BFFF && back.argb[2] == 0xFF8C40FF);
        check("scene, mirror, brightness, calm round-trip", "kaleido".equals(back.scene) && back.sym == 6 && back.brightness == 1 && back.calm == 1);
        check("equalizer round-trips", back.eq != null && near(back.eq.punch, 0.7f) && near(back.eq.beatGain, 1.3f) && back.eqMode == 1);
        check("colour motion round-trips", back.motion != null && near(back.motion.beatStep, 1f / 16) && near(back.motion.tilt, 0.3f));
        check("the document has v and name for the cloud", json.contains("\"v\":1") && json.contains("\"name\":\"Night drive\""));

        String payload = l.encode();
        check("payload is URL-safe", payload.matches("[A-Za-z0-9_-]+"));
        check("payload is short enough for a message", payload.length() < 420);
        Look dec = Look.decode(payload);
        check("payload decodes to the same document", dec != null && dec.toJson().toString().equals(json));
        String url = l.shareUrl("ab12cd");
        check("share link carries code and payload", "ab12cd".equals(Look.codeIn(url)) && payload.equals(Look.payloadIn(url)));
        check("code alone is recognised", "ab12cd".equals(Look.codeIn("  AB12CD ")) && Look.codeIn("ab12") == null && Look.codeIn("zz") == null);
        check("app link parses too", payload.equals(Look.payloadIn(l.appUrl(null))) && Look.codeIn(l.appUrl(null)) == null);

        // a bare colour preset (the presets thread's document) is a Look that only sets colours
        Look bare = Look.fromJson("{\"v\":1,\"name\":\"Ocean\",\"colours\":[\"#00e6cc\",\"#1a66ff\",\"#b3ffff\"],\"rainbow\":false}");
        check("a colour preset is a Look", bare != null && "Ocean".equals(bare.name) && bare.scene == null && bare.sym == -1 && bare.eq == null && bare.calm == -1);
        check("garbage is rejected", Look.fromJson("{\"v\":1}") == null && Look.fromJson("nope") == null && Look.decode("!!!") == null && Look.decode("") == null);
        Look odd = Look.fromJson("{\"v\":1,\"name\":\"x\",\"colours\":[\"#000000\",\"#000000\",\"#000000\"],\"scene\":\"nosuch\",\"sym\":99,\"brightness\":7}");
        check("unknown scene and out-of-range values are dropped", odd != null && odd.scene == null && odd.sym == -1 && odd.brightness == -1);
        check("an empty name becomes Look", "Look".equals(Look.cleanName("   ")) && Look.cleanName("a".repeat(60)).length() == 40);

        // harmonious shuffle: spread hues, neon saturation and brightness, never the same twice
        Random r = new Random(42);
        int spreadOk = 0, rangeOk = 0, distinct = 0;
        int[] prev = null;
        for (int i = 0; i < 500; i++) {
            int[] c = Palettes.shuffle(r);
            float h0 = Palettes.hueOf(c[0]), h1 = Palettes.hueOf(c[1]), h2 = Palettes.hueOf(c[2]);
            if (hueDist(h0, h1) > 0.05f && hueDist(h1, h2) > 0.05f && hueDist(h0, h2) > 0.05f) spreadOk++;
            boolean range = true;
            for (int k : c) {
                float mx = Math.max((k >> 16 & 255), Math.max((k >> 8 & 255), (k & 255))) / 255f;
                float mn = Math.min((k >> 16 & 255), Math.min((k >> 8 & 255), (k & 255))) / 255f;
                float sat = mx > 0 ? (mx - mn) / mx : 0;
                if (mx < 0.85f || sat < 0.68f) range = false;
            }
            if (range) rangeOk++;
            if (prev == null || prev[0] != c[0] || prev[1] != c[1]) distinct++;
            prev = c;
        }
        check("shuffled hues are at least 18 degrees apart", spreadOk == 500);
        check("shuffled colours stay bright and saturated", rangeOk == 500);
        check("shuffles differ", distinct == 500);
        check("hsvToArgb matches the usual formula", Palettes.hsvToArgb(0f, 1f, 1f) == 0xFFFF0000 && Palettes.hsvToArgb(1f / 3, 1f, 1f) == 0xFF00FF00
                && Palettes.hsvToArgb(2f / 3, 1f, 1f) == 0xFF0000FF && near(Palettes.hueOf(0xFF00FFFF), 0.5f));

        if (failed) System.exit(1);
        System.out.println("  Look: document, share payload and shuffle OK");
    }

    private static float hueDist(float a, float b) {
        float d = Math.abs(a - b) % 1f;
        return Math.min(d, 1f - d);
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.002f;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
