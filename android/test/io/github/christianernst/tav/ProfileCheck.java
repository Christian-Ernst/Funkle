package io.github.christianernst.tav;

import org.json.JSONObject;

/**
 * Plain-JVM check of Profile (the whole-app document: scene on screen, live colours, every scene's
 * own colours and sliders, the app settings). Needs a real org.json like LookCheck.
 */
public final class ProfileCheck {
    private static boolean failed;

    public static void main(String[] args) throws Exception {
        Profile p = new Profile();
        p.name = "  Party  night ";
        p.scene = "pulsar";
        p.look = look(0xFFFF2E8C, "Neon", 6, ColourDrive.BEAT);
        Profile.SceneEntry halo = new Profile.SceneEntry();
        halo.look = look(0xFF00E6CC, "Custom", 1, 1);
        halo.tune = new JSONObject().put("rays", 0.7).put("trails", 0.25);
        p.scenes.put("halo", halo);
        Profile.SceneEntry tunnel = new Profile.SceneEntry();   // sliders only, no colours of its own
        tunnel.tune = new JSONObject().put("speed", 0.9);
        p.scenes.put("tunnel", tunnel);
        Profile.SceneEntry album = new Profile.SceneEntry();    // Album colours are a choice a scene can own
        album.look = look(0xFF000000, "Album", 4, ColourDrive.STILL);
        p.scenes.put("orb", album);
        p.brightness = 1;
        p.calm = 1;
        p.autopilot = Autopilot.SCENES;
        p.eq = Equalizer.punch();
        p.eqMode = 1;

        JSONObject json = p.toJson();
        String s = json.toString();
        check("document has v, kind and a clean name for the cloud", json.optInt("v") == 1 && "profile".equals(json.optString("kind")) && "Party night".equals(json.optString("name")));
        check("document is small enough for the presets table", s.length() < 2000);
        check("a profile is not a Look (no top-level colours)", Look.fromJson(json) == null && !json.has("colours"));
        check("isProfile tells it apart", Profile.isProfile(json) && !Profile.isProfile(look(0, "Neon", 1, 0).toJson()));

        Profile back = Profile.fromJson(s);
        check("scene, settings round-trip", back != null && "pulsar".equals(back.scene) && back.brightness == 1 && back.calm == 1
                && back.autopilot == Autopilot.SCENES && back.eqMode == 1 && back.eq != null && near(back.eq.punch, 0.7f));
        check("live look round-trips with its palette name and mirror", back.look != null && "Neon".equals(back.look.palette) && back.look.sym == 6
                && back.look.argb[0] == 0xFFFF2E8C && back.look.motion != null && near(back.look.motion.beatStep, ColourDrive.preset(ColourDrive.BEAT).beatStep));
        check("scene entries round-trip in the scene table's order", back.scenes.size() == 3 && back.scenes.keySet().iterator().next().equals("halo"));
        Profile.SceneEntry h = back.scenes.get("halo"), t = back.scenes.get("tunnel"), o = back.scenes.get("orb");
        check("a scene's own colours and sliders", h != null && h.look != null && "Custom".equals(h.look.palette) && h.look.argb[0] == 0xFF00E6CC
                && h.tune != null && near((float) h.tune.optDouble("rays"), 0.7f) && near((float) h.tune.optDouble("trails"), 0.25f));
        check("a scene with sliders only", t != null && t.look == null && t.tune != null && near((float) t.tune.optDouble("speed"), 0.9f));
        check("Album stays a choice", o != null && o.look != null && "Album".equals(o.look.palette) && o.look.sym == 4);
        check("ownedScenes counts colours, not sliders", back.ownedScenes() == 2);
        check("sameAs compares documents, not key order", back.sameAs(p)
                && Profile.fromJson("{\"name\":\"a\",\"kind\":\"profile\",\"calm\":true,\"v\":1}").sameAs(Profile.fromJson("{\"v\":1,\"kind\":\"profile\",\"name\":\"a\",\"calm\":true}"))
                && !Profile.fromJson("{\"v\":1,\"kind\":\"profile\",\"name\":\"a\",\"calm\":false}").sameAs(back));
        check("the colour groups carry no name, version, scene or eq", !s.contains("\"look\":{\"v\"") && !json.optJSONObject("look").has("name")
                && !json.optJSONObject("look").has("eq") && !json.optJSONObject("scenes").optJSONObject("halo").has("scene"));

        Profile copy = back.copy("Chill");
        check("a copy has the new name and the same content", "Chill".equals(copy.name) && copy.scenes.size() == 3 && !copy.sameAs(back));
        copy.name = back.name;
        check("... and nothing else differs", copy.sameAs(back));

        Profile odd = Profile.fromJson("{\"v\":1,\"kind\":\"profile\",\"name\":\"x\",\"scene\":\"nosuch\",\"brightness\":9,\"autopilot\":7,"
                + "\"scenes\":{\"nosuch\":{\"tune\":{\"a\":1}},\"halo\":{\"tune\":{\"rays\":3,\"Bad\":1}},\"orb\":{}}}");
        check("unknown scene, bad values and empty entries are dropped", odd != null && odd.scene == null && odd.brightness == -1 && odd.autopilot == -1
                && odd.scenes.size() == 1 && near((float) odd.scenes.get("halo").tune.optDouble("rays"), 1f) && odd.scenes.get("halo").tune.length() == 1);
        check("not a profile", Profile.fromJson("{\"v\":1,\"name\":\"Ocean\",\"colours\":[\"#00e6cc\",\"#1a66ff\",\"#b3ffff\"]}") == null
                && Profile.fromJson("nope") == null && Profile.fromJson((String) null) == null);
        Profile empty = Profile.fromJson("{\"v\":1,\"kind\":\"profile\",\"name\":\"\"}");
        check("an empty profile is valid and keeps everything", empty != null && "Look".equals(empty.name) && empty.scene == null && empty.look == null
                && empty.scenes.isEmpty() && empty.eq == null);

        if (failed) System.exit(1);
        System.out.println("  Profile: document, scene entries and round-trip OK");
    }

    private static Look look(int c0, String palette, int sym, int mode) {
        Look l = new Look();
        l.argb[0] = c0;
        l.argb[1] = 0xFF26BFFF;
        l.argb[2] = 0xFF8C40FF;
        l.palette = palette;
        l.sym = sym;
        l.motion = ColourDrive.preset(mode);
        return l;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 0.002f;
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed = true;
    }
}
