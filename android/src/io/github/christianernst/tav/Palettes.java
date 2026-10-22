package io.github.christianernst.tav;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Preset palettes, the user's own palette and colour extraction from album art. */
final class Palettes {
    static final String[] NAMES = {"Album", "Neon", "Sunset", "Toxic", "Ice", "Inferno", "Vapor", "Aurora",
            "Ocean", "Candy", "Rainbow", "Custom"};
    static final int ALBUM = 0, RAINBOW = 10, CUSTOM = 11;
    /** Where Custom sat before Ocean, Candy and Rainbow were added (prefs_version 1). */
    static final int CUSTOM_V1 = 8;
    /** Lowest HSV brightness the colour editor offers: the neon look needs bright colours. */
    static final float MIN_VALUE = 0.5f;
    private static final float[][][] PRESETS = {
            null,
            {{1.0f, 0.18f, 0.55f}, {0.15f, 0.75f, 1.0f}, {0.55f, 0.25f, 1.0f}},
            {{1.0f, 0.45f, 0.1f}, {1.0f, 0.15f, 0.45f}, {0.45f, 0.15f, 0.85f}},
            {{0.5f, 1.0f, 0.15f}, {0.05f, 0.9f, 0.75f}, {0.9f, 1.0f, 0.3f}},
            {{0.75f, 0.9f, 1.0f}, {0.2f, 0.6f, 1.0f}, {0.35f, 0.3f, 1.0f}},
            {{1.0f, 0.2f, 0.05f}, {1.0f, 0.6f, 0.1f}, {1.0f, 0.95f, 0.5f}},
            {{1.0f, 0.4f, 0.85f}, {0.3f, 0.95f, 0.9f}, {0.65f, 0.55f, 1.0f}},
            {{0.2f, 1.0f, 0.6f}, {0.2f, 0.55f, 1.0f}, {0.85f, 0.3f, 1.0f}},
            {{0.0f, 0.9f, 0.8f}, {0.1f, 0.4f, 1.0f}, {0.7f, 1.0f, 1.0f}},
            {{1.0f, 0.25f, 0.6f}, {1.0f, 0.6f, 0.15f}, {0.6f, 0.3f, 1.0f}},
            // Rainbow: the shader paints the spectrum; these are what bass, mids and treble pull toward
            {{1.0f, 0.1f, 0.1f}, {0.1f, 1.0f, 0.2f}, {0.2f, 0.3f, 1.0f}},
    };

    private Palettes() {
    }

    /** Schema version of the preset document ({@link #toJson}). */
    static final int PRESET_VERSION = 1;

    /** A colour preset as read back from its JSON document. */
    static final class Preset {
        String name = "";
        final int[] argb = new int[3];
        boolean rainbow;
        ColourDrive motion = ColourDrive.preset(ColourDrive.STILL);
    }

    /**
     * One self-contained JSON document for a colour preset, shared with {@code web/engine.js}:
     * {@code {"v":1,"name":..,"colours":["#rrggbb" x3],"rainbow":bool,"motion":{drift,beat,drop,tilt,pump}}}.
     */
    static String toJson(String name, int[] argb, boolean rainbow, ColourDrive motion) {
        try {
            JSONArray colours = new JSONArray();
            for (int c : argb) colours.put(String.format(Locale.ROOT, "#%06x", c & 0xFFFFFF));
            JSONObject m = new JSONObject().put("drift", motion.drift).put("beat", motion.beatStep)
                    .put("drop", motion.dropStep).put("tilt", motion.tilt).put("pump", motion.pump);
            return new JSONObject().put("v", PRESET_VERSION).put("name", name == null ? "" : name)
                    .put("colours", colours).put("rainbow", rainbow).put("motion", m).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The preset in a document from {@link #toJson}, or null when it is not one. */
    static Preset fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray colours = o.getJSONArray("colours");
            if (o.getInt("v") < 1 || colours.length() != 3) return null;
            Preset p = new Preset();
            p.name = o.optString("name", "");
            for (int k = 0; k < 3; k++) {
                String h = colours.getString(k);
                if (!h.matches("#?[0-9a-fA-F]{6}")) return null;
                p.argb[k] = 0xFF000000 | Integer.parseInt(h.startsWith("#") ? h.substring(1) : h, 16);
            }
            p.rainbow = o.optBoolean("rainbow", false);
            JSONObject m = o.optJSONObject("motion");
            if (m != null) {
                float[] v = new float[ColourDrive.MAX.length];
                String[] keys = {"drift", "beat", "drop", "tilt", "pump"};
                for (int i = 0; i < v.length; i++) v[i] = Math.max(0f, Math.min(ColourDrive.MAX[i], (float) m.optDouble(keys[i], 0)));
                p.motion = new ColourDrive(v[0], v[1], v[2], v[3], v[4]);
            }
            return p;
        } catch (JSONException | NumberFormatException e) {
            return null;
        }
    }

    /** Preset palette by index; ALBUM (no art yet) and CUSTOM fall back to Neon. */
    static float[][] preset(int i) {
        float[][] p = (i <= 0 || i >= PRESETS.length || PRESETS[i] == null) ? PRESETS[1] : PRESETS[i];
        return new float[][]{p[0].clone(), p[1].clone(), p[2].clone()};
    }

    /** Three ARGB colours, as the custom palette is stored, to shader colours. */
    static float[][] fromColors(int[] argb) {
        float[][] p = new float[3][];
        for (int k = 0; k < 3; k++)
            p[k] = new float[]{Color.red(argb[k]) / 255f, Color.green(argb[k]) / 255f, Color.blue(argb[k]) / 255f};
        return p;
    }

    static int[] toColors(float[][] p) {
        int[] argb = new int[3];
        for (int k = 0; k < 3; k++)
            argb[k] = Color.rgb(Math.round(p[k][0] * 255), Math.round(p[k][1] * 255), Math.round(p[k][2] * 255));
        return argb;
    }

    /** Floors for album colours: enough saturation and brightness to glow, the cover's own tone above that. */
    private static final float ALBUM_MIN_SAT = 0.5f, ALBUM_MIN_VALUE = 0.7f;

    /**
     * Picks three distinct hues from album art (weighted hue histogram, saturation x value) and
     * keeps each one's own saturation and brightness above a floor, so a dusty cover gives a dusty
     * palette and a neon cover a neon one. Returns null for grey artwork.
     */
    static float[][] fromBitmap(Bitmap art) {
        if (art == null) return null;
        Bitmap small = Bitmap.createScaledBitmap(art, 48, 48, true);
        int[] px = new int[48 * 48];
        small.getPixels(px, 0, 48, 0, 0, 48, 48);
        if (small != art) small.recycle();
        final int B = 36;
        float[] weight = new float[B];
        float[][] sum = new float[B][3];
        float[] hsv = new float[3];
        for (int c : px) {
            Color.colorToHSV(c, hsv);
            if (hsv[1] < 0.22f || hsv[2] < 0.18f) continue;
            int b = (int) (hsv[0] / 360f * B) % B;
            float w = hsv[1] * hsv[2];
            weight[b] += w;
            sum[b][0] += Color.red(c) * w;
            sum[b][1] += Color.green(c) * w;
            sum[b][2] += Color.blue(c) * w;
        }
        int[] pick = new int[3];
        int found = 0;
        for (int k = 0; k < 3; k++) {
            int best = -1;
            for (int b = 0; b < B; b++) {
                if (weight[b] <= 0.5f) continue;
                boolean far = true;
                for (int j = 0; j < found; j++) {
                    int d = Math.abs(b - pick[j]);
                    if (Math.min(d, B - d) < 4) far = false;   // at least 40 degrees apart
                }
                if (far && (best < 0 || weight[b] > weight[best])) best = b;
            }
            if (best < 0) break;
            pick[found++] = best;
        }
        if (found == 0) return null;
        float[][] out = new float[3][];
        for (int k = 0; k < 3; k++) {
            int[] rgb = new int[3];
            if (k < found) {
                int b = pick[k];
                for (int ch = 0; ch < 3; ch++) rgb[ch] = (int) (sum[b][ch] / weight[b]);
                Color.RGBToHSV(rgb[0], rgb[1], rgb[2], hsv);
            } else {
                // not enough distinct hues: rotate the dominant one
                for (int ch = 0; ch < 3; ch++) rgb[ch] = (int) (sum[pick[0]][ch] / weight[pick[0]]);
                Color.RGBToHSV(rgb[0], rgb[1], rgb[2], hsv);
                hsv[0] = (hsv[0] + (k == 1 ? 50 : -50) + 360) % 360;
            }
            hsv[1] = Math.max(hsv[1], ALBUM_MIN_SAT);
            hsv[2] = Math.max(hsv[2], ALBUM_MIN_VALUE);
            int c = Color.HSVToColor(hsv);
            out[k] = new float[]{Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f};
        }
        return out;
    }
    // ------------------------------------------------------------------ harmonious shuffle

    /**
     * Three colours that go together, at random: the hues sit a golden angle (137.5°) apart with a
     * little jitter, so every roll is spread over the wheel without ever being the same, and
     * saturation and brightness stay in the neon range the scenes are tuned for. Pure arithmetic
     * (no android.graphics), so LookCheck can test it on the JVM. The same recipe is in
     * {@code web/looks.js}.
     */
    static int[] shuffle(java.util.Random r) {
        int[] out = new int[3];
        float h = r.nextFloat();
        // one in three rolls is analogous (close hues, calmer); the rest are spread by the golden angle
        float step = r.nextInt(3) == 0 ? 0.08f + 0.06f * r.nextFloat() : 0.382f;
        for (int k = 0; k < 3; k++) {
            float hue = (h + k * step + (r.nextFloat() - 0.5f) * 0.03f + 1f) % 1f;
            float sat = 0.72f + 0.28f * r.nextFloat();
            float val = 0.88f + 0.12f * r.nextFloat();
            out[k] = hsvToArgb(hue, sat, val);
        }
        return out;
    }

    /** HSV (hue 0..1) to opaque ARGB; what android.graphics.Color.HSVToColor does, on any JVM. */
    static int hsvToArgb(float h, float s, float v) {
        float hh = (h % 1f + 1f) % 1f * 6f;
        int i = (int) hh;
        float f = hh - i, p = v * (1 - s), q = v * (1 - s * f), t = v * (1 - s * (1 - f));
        float r, g, b;
        switch (i % 6) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default: r = v; g = p; b = q; break;
        }
        return 0xFF000000 | Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    /** Hue (0..1) of an ARGB colour, for tests and labels. */
    static float hueOf(int argb) {
        float r = (argb >> 16 & 255) / 255f, g = (argb >> 8 & 255) / 255f, b = (argb & 255) / 255f;
        float mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b)), d = mx - mn;
        if (d < 1e-6f) return 0f;
        float h = mx == r ? (g - b) / d % 6f : mx == g ? (b - r) / d + 2f : (r - g) / d + 4f;
        return (h / 6f + 1f) % 1f;
    }
}
