package io.github.christianernst.tav;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Profile: the whole state of the app under one name. Where a {@link Look} is one picture
 * (one scene with its colours, made to be shared), a Profile is the complete set-up: the scene on
 * screen, the colours showing right now, every scene's own colours, colour motion, mirror and
 * sliders, plus the settings that belong to the whole app (brightness, motion, equalizer,
 * autopilot). Switching profiles swaps all of it; the active profile is kept up to date as the
 * user changes things, so nothing is lost when they switch away and back.
 * <pre>
 * {"v":1,"kind":"profile","name":"Party",
 *  "scene":"halo",                                   // the scene on screen
 *  "look":{"colours":[..],"rainbow":false,"motion":{..},"palette":"Neon","sym":6},   // showing now
 *  "scenes":{"halo":{"colours":[..],"palette":"Neon","motion":{..},"sym":6,"tune":{"rays":0.7}},
 *            "tunnel":{"tune":{"speed":0.8}}},      // scenes with their own colours and/or sliders
 *  "brightness":2,"calm":false,"autopilot":2,
 *  "eq":{"mode":1,"master":1,"punch":0.7,"beat":1.3,"gain":[1,1,1,1]}}
 * </pre>
 * The colour groups are Look documents without their name, so the Look parser reads them. The
 * cloud keeps the document as it is in the presets table ({@code v} and {@code name} are all it
 * needs); {@code kind} tells it apart from a Look, and a reader of Looks skips it because it has no
 * top-level colours.
 */
final class Profile {
    static final int VERSION = 1;
    static final String KIND = "profile";

    /** One scene's own settings; either part may be missing. */
    static final class SceneEntry {
        /** Colours, rainbow, motion, palette name and mirror (name and scene unused). Null = no colours of its own. */
        Look look;
        /** Slider id to 0..1 for the sliders moved off the middle; null = all in the middle. */
        JSONObject tune;
    }

    String name = "";
    String scene;                       // scene id on screen, null = keep
    Look look;                          // what is showing: colours, motion, palette, sym
    final Map<String, SceneEntry> scenes = new LinkedHashMap<>();
    int brightness = -1, calm = -1, autopilot = -1;
    Equalizer eq;
    int eqMode = -1;

    static boolean isProfile(JSONObject o) {
        return o != null && KIND.equals(o.optString("kind", "")) && o.optInt("v", 0) >= 1;
    }

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject().put("v", VERSION).put("kind", KIND).put("name", Look.cleanName(name));
            if (scene != null) o.put("scene", scene);
            if (look != null) o.put("look", colourGroup(look));
            JSONObject sc = new JSONObject();
            for (Map.Entry<String, SceneEntry> e : scenes.entrySet()) {
                SceneEntry se = e.getValue();
                JSONObject g = se.look != null ? colourGroup(se.look) : new JSONObject();
                if (se.tune != null && se.tune.length() > 0) g.put("tune", se.tune);
                if (g.length() > 0) sc.put(e.getKey(), g);
            }
            if (sc.length() > 0) o.put("scenes", sc);
            if (brightness >= 0) o.put("brightness", brightness);
            if (calm >= 0) o.put("calm", calm == 1);
            if (autopilot >= 0) o.put("autopilot", autopilot);
            if (eq != null) {
                JSONArray g = new JSONArray();
                for (float v : eq.gain) g.put(round(v));
                o.put("eq", new JSONObject().put("mode", Math.max(0, eqMode)).put("master", round(eq.master))
                        .put("punch", round(eq.punch)).put("beat", round(eq.beatGain)).put("gain", g));
            }
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    /** A Look's colours, rainbow, motion, palette and mirror, without name, version, scene, eq or tune. */
    static JSONObject colourGroup(Look l) {
        JSONObject o = l.toJson();
        o.remove("name");
        o.remove("v");
        o.remove("scene");
        o.remove("eq");
        o.remove("tune");
        o.remove("brightness");
        o.remove("calm");
        return o;
    }

    /** The colour group back as a Look (null when it has no colours). */
    static Look colourGroupLook(JSONObject g) {
        if (g == null || !g.has("colours")) return null;
        try {
            JSONObject o = new JSONObject(g.toString()).put("v", 1).put("name", "x");
            return Look.fromJson(o);
        } catch (JSONException e) {
            return null;
        }
    }

    static Profile fromJson(String json) {
        if (json == null) return null;
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    /** The Profile in a document, or null when it is not one. Unknown scenes and bad values are dropped. */
    static Profile fromJson(JSONObject o) {
        if (!isProfile(o)) return null;
        Profile p = new Profile();
        p.name = Look.cleanName(o.optString("name", ""));
        String sc = o.optString("scene", null);
        if (sc != null && Scene.indexOf(sc) >= 0) p.scene = sc;
        p.look = colourGroupLook(o.optJSONObject("look"));
        JSONObject scenes = o.optJSONObject("scenes");
        if (scenes != null) {
            for (Scene s : Scene.ALL) {   // in the scene table's order, only scenes that exist
                JSONObject g = scenes.optJSONObject(s.id);
                if (g == null) continue;
                SceneEntry e = new SceneEntry();
                e.look = colourGroupLook(g);
                JSONObject t = g.optJSONObject("tune");
                if (t != null) {
                    Look tl = Look.fromJson("{\"v\":1,\"name\":\"x\",\"colours\":[\"#000000\",\"#000000\",\"#000000\"],\"tune\":" + t + "}");
                    if (tl != null) e.tune = tl.tune;   // Look's cleaning: ids, clamping, rounding
                }
                if (e.look != null || e.tune != null) p.scenes.put(s.id, e);
            }
        }
        int b = o.optInt("brightness", -1);
        if (b >= 0 && b < TavRenderer.EXPOSURE.length) p.brightness = b;
        if (o.has("calm")) p.calm = o.optBoolean("calm", false) ? 1 : 0;
        int a = o.optInt("autopilot", -1);
        if (a >= Autopilot.OFF && a <= Autopilot.FULL) p.autopilot = a;
        JSONObject e = o.optJSONObject("eq");
        if (e != null) {
            float[] g = new float[Equalizer.BANDS];
            JSONArray ga = e.optJSONArray("gain");
            for (int k = 0; k < g.length; k++) g[k] = clamp((float) (ga != null ? ga.optDouble(k, 1) : 1), 0, 2);
            p.eq = new Equalizer(clamp((float) e.optDouble("master", 1), 0, 2), g,
                    clamp((float) e.optDouble("punch", 0), 0, 1), clamp((float) e.optDouble("beat", 1), 0, 2));
            p.eqMode = Math.max(0, Math.min(2, e.optInt("mode", 2)));
        }
        return p;
    }

    /** True when the two documents say the same thing, whatever the key order. */
    boolean sameAs(Profile other) {
        return other != null && Look.canonical(toJson()).equals(Look.canonical(other.toJson()));
    }

    /** How many scenes have colours of their own. */
    int ownedScenes() {
        int n = 0;
        for (SceneEntry e : scenes.values()) if (e.look != null) n++;
        return n;
    }

    /** A copy with another name (a new profile made from this one). */
    Profile copy(String newName) {
        Profile p = fromJson(toJson());
        p.name = Look.cleanName(newName);
        return p;
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static double round(float v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
