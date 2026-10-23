package io.github.christianernst.tav;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * A Look: everything that decides how TAV looks right now, named so it can be saved, reapplied
 * and shared. One JSON document, the same in {@code web/looks.js}:
 * <pre>
 * {"v":1,"name":"Night drive",
 *  "colours":["#ff2e8c","#26bfff","#8c40ff"],"rainbow":false,          // the colour preset (Palettes.toJson)
 *  "motion":{"drift":0,"beat":0.0625,"drop":0.333,"tilt":0.3,"pump":0},
 *  "palette":"Neon",                                                   // a preset name, or "Custom"
 *  "scene":"halo","sym":6,"brightness":2,"calm":false,
 *  "eq":{"mode":1,"master":1,"punch":0.7,"beat":1.3,"gain":[1,1,1,1]},
 *  "tune":{"rays":0.7,"trails":0.5,"rings":0.3}}                      // the scene's sliders, 0..1 (optional)
 * </pre>
 * The colour fields are the presets thread's format, so a bare colour preset is a valid Look that
 * only sets colours; every other key is optional and a reader keeps its current value for a missing
 * one. The cloud stores the document as it is ({@code presets.data}), which needs {@code v} and a
 * name of 1 to 40 characters.
 *
 * Share links carry the Look itself ({@link #encode}), so a link opens without the cloud and
 * without an account; a six-character code from the cloud rides along when there is one.
 */
final class Look {
    static final int VERSION = 1;
    static final int MAX_NAME = 40;
    /** The web edition, which opens share links in any browser; the app catches the same links. */
    static final String SHARE_BASE = "https://christian-ernst.github.io/Funkle/web/";
    /** The app's own link, for the "Open in TAV" button on the web page and for QR codes. */
    static final String APP_SCHEME = "tav://look";

    String name = "";
    /** The colour preset; palette names a menu preset ("Neon") or "Custom" for these colours. */
    final int[] argb = new int[3];
    boolean rainbow;
    ColourDrive motion;
    String palette = "Custom";
    String scene;          // Scene id, null = keep
    int sym = -1;          // -1 = keep
    int brightness = -1;
    int calm = -1;         // 0 full, 1 calm
    int eqMode = -1;
    Equalizer eq;
    /** The scene's per-scene sliders by id, each 0..1 (the scenes thread's "tune"); null = keep. */
    JSONObject tune;

    /** Trims and clips a name to what the cloud accepts; empty becomes "Look". */
    static String cleanName(String s) {
        s = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        if (s.length() > MAX_NAME) s = s.substring(0, MAX_NAME).trim();
        return s.isEmpty() ? "Look" : s;
    }

    JSONObject toJson() {
        try {
            JSONObject o = new JSONObject(Palettes.toJson(cleanName(name), argb, rainbow,
                    motion != null ? motion : ColourDrive.preset(ColourDrive.STILL)));
            JSONObject m = o.optJSONObject("motion");   // floats as the shader sees them, not 0.30000001192092896
            if (m != null) for (java.util.Iterator<String> it = m.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, round((float) m.optDouble(k, 0)));
            }
            o.put("palette", palette == null ? "Custom" : palette);
            if (scene != null) o.put("scene", scene);
            if (sym > 0) o.put("sym", sym);
            if (brightness >= 0) o.put("brightness", brightness);
            if (calm >= 0) o.put("calm", calm == 1);
            if (eq != null) {
                JSONArray g = new JSONArray();
                for (float v : eq.gain) g.put(round(v));
                o.put("eq", new JSONObject().put("mode", Math.max(0, eqMode)).put("master", round(eq.master))
                        .put("punch", round(eq.punch)).put("beat", round(eq.beatGain)).put("gain", g));
            }
            if (tune != null && tune.length() > 0) o.put("tune", tune);
            return o;
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public String toString() {
        return toJson().toString();
    }

    /** The Look in a document, or null when it is not one (no colours, wrong version). */
    static Look fromJson(String json) {
        if (json == null) return null;
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    static Look fromJson(JSONObject o) {
        if (o == null) return null;
        Palettes.Preset p = Palettes.fromJson(o.toString());
        if (p == null) return null;
        Look l = new Look();
        l.name = cleanName(p.name);
        System.arraycopy(p.argb, 0, l.argb, 0, 3);
        l.rainbow = p.rainbow;
        l.motion = p.motion;
        l.palette = o.optString("palette", "Custom");
        String sc = o.optString("scene", null);
        if (sc != null) for (Scene s : Scene.ALL) if (s.id.equals(sc)) l.scene = sc;
        int sym = o.optInt("sym", -1);
        if (sym >= 1 && sym <= 12) l.sym = sym;
        int b = o.optInt("brightness", -1);
        if (b >= 0 && b < TavRenderer.EXPOSURE.length) l.brightness = b;
        if (o.has("calm")) l.calm = o.optBoolean("calm", false) ? 1 : 0;
        JSONObject e = o.optJSONObject("eq");
        if (e != null) {
            float[] g = new float[Equalizer.BANDS];
            JSONArray ga = e.optJSONArray("gain");
            for (int k = 0; k < g.length; k++) g[k] = clamp((float) (ga != null ? ga.optDouble(k, 1) : 1), 0, 2);
            l.eq = new Equalizer(clamp((float) e.optDouble("master", 1), 0, 2), g,
                    clamp((float) e.optDouble("punch", 0), 0, 1), clamp((float) e.optDouble("beat", 1), 0, 2));
            l.eqMode = Math.max(0, Math.min(2, e.optInt("mode", 2)));
        }
        JSONObject t = o.optJSONObject("tune");
        if (t != null) {
            JSONObject clean = new JSONObject();
            Iterator<String> keys = t.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                double v = t.optDouble(k, Double.NaN);
                if (k.matches("[a-z][a-z0-9_]{0,15}") && !Double.isNaN(v)) {
                    try {
                        clean.put(k, round(clamp((float) v, 0, 1)));
                    } catch (JSONException ignored) {
                    }
                }
            }
            if (clean.length() > 0) l.tune = clean;
        }
        return l;
    }

    /** True when the two documents say the same thing, whatever the key order. */
    boolean sameAs(Look other) {
        return other != null && canonical(toJson()).equals(canonical(other.toJson()));
    }

    /** The JSON with its keys sorted at every level, for comparing documents. */
    static String canonical(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) keys.add(it.next());
            Collections.sort(keys);
            StringBuilder b = new StringBuilder("{");
            for (String k : keys) b.append(JSONObject.quote(k)).append(':').append(canonical(o.opt(k))).append(',');
            return b.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder b = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) b.append(canonical(a.opt(i))).append(',');
            return b.append(']').toString();
        }
        if (v instanceof Number) return String.valueOf(((Number) v).doubleValue());
        return String.valueOf(v);
    }

    // ------------------------------------------------------------------ share links

    /**
     * The link to share: the web page with the Look inline ({@code d=}) and, when the cloud gave
     * the Look a code, {@code l=code} in front of it. Either part opens the Look on its own.
     */
    String shareUrl(String code) {
        return SHARE_BASE + "#" + (code != null ? "l=" + code + "&" : "") + "d=" + encode();
    }

    /** The app link with the same payload (tav://look?d=...). */
    String appUrl(String code) {
        return APP_SCHEME + "?" + (code != null ? "l=" + code + "&" : "") + "d=" + encode();
    }

    /** The document as URL-safe base64 (no padding), small enough for a message. */
    String encode() {
        return base64Url(toJson().toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The Look in an encoded payload, or null when it does not decode. */
    static Look decode(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        byte[] bytes = unbase64Url(payload);
        return bytes == null ? null : fromJson(new String(bytes, StandardCharsets.UTF_8));
    }

    /** A code (six letters or digits) from a link fragment, a query or the code itself; null if none. */
    static String codeIn(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[#?&])l=([a-z0-9]{6})(?:$|&)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
        if (m.find()) return m.group(1).toLowerCase(Locale.ROOT);
        String t = s.trim().toLowerCase(Locale.ROOT);
        return t.matches("[a-z0-9]{6}") ? t : null;
    }

    /** The inline payload from a link fragment or query, or null. */
    static String payloadIn(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:^|[#?&])d=([A-Za-z0-9_-]+)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static final String B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    // java.util.Base64 needs API 26 and android.util.Base64 is a stub on the JVM: a few lines do it.
    static String base64Url(byte[] in) {
        StringBuilder out = new StringBuilder((in.length + 2) / 3 * 4);
        for (int i = 0; i < in.length; i += 3) {
            int b = (in[i] & 0xFF) << 16 | (i + 1 < in.length ? (in[i + 1] & 0xFF) << 8 : 0) | (i + 2 < in.length ? in[i + 2] & 0xFF : 0);
            out.append(B64.charAt(b >> 18 & 63)).append(B64.charAt(b >> 12 & 63));
            if (i + 1 < in.length) out.append(B64.charAt(b >> 6 & 63));
            if (i + 2 < in.length) out.append(B64.charAt(b & 63));
        }
        return out.toString();
    }

    static byte[] unbase64Url(String s) {
        s = s.replace('+', '-').replace('/', '_').replace("=", "");
        if (s.length() % 4 == 1) return null;
        byte[] out = new byte[s.length() * 3 / 4];
        int n = 0, acc = 0, bits = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = B64.indexOf(s.charAt(i));
            if (v < 0) return null;
            acc = acc << 6 | v;
            bits += 6;
            if (bits >= 8) {
                bits -= 8;
                out[n++] = (byte) (acc >> bits & 0xFF);
            }
        }
        return out;
    }

    private static float clamp(float v, float lo, float hi) {
        return Float.isNaN(v) ? lo : Math.max(lo, Math.min(hi, v));
    }

    private static double round(float v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
