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

    /**
     * Picks three distinct vivid hues from album art (weighted hue histogram, saturation
     * x value) and pushes them to neon brightness. Returns null for grey artwork.
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
            hsv[1] = Math.max(hsv[1], 0.65f);
            hsv[2] = 1f;
            int c = Color.HSVToColor(hsv);
            out[k] = new float[]{Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f};
        }
        return out;
    }
}
