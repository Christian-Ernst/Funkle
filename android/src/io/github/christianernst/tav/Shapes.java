package io.github.christianernst.tav;

import java.util.ArrayList;
import java.util.List;

/**
 * Preset outlines the user can drop onto the screen. Each is a closed polyline in
 * centred coordinates (shorter screen axis = [-1, 1]); the renderer traces the live
 * waveform along it.
 */
final class Shapes {
    static final String[] NAMES = {"Circle", "Heart", "Star", "Spiral", "Infinity", "Hexagon", "Flower"};

    private Shapes() {
    }

    static List<float[]> make(int kind, float cx, float cy, float size) {
        List<float[]> pts = new ArrayList<>();
        int steps = 360;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps * Math.PI * 2;
            double x, y;
            switch (kind) {
                case 1: // heart
                    x = 16 * Math.pow(Math.sin(t), 3) / 17;
                    y = (13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t)) / 17 + 0.1;
                    break;
                case 2: { // five-pointed star
                    double k = (t / (Math.PI * 2)) * 10, f = k - Math.floor(k);
                    int seg = (int) Math.floor(k) % 10;
                    double r0 = seg % 2 == 0 ? 1 : 0.42, r1 = seg % 2 == 0 ? 0.42 : 1;
                    double a0 = Math.PI / 2 + seg * Math.PI / 5, a1 = a0 + Math.PI / 5;
                    double x0 = Math.cos(a0) * r0, y0 = Math.sin(a0) * r0, x1 = Math.cos(a1) * r1, y1 = Math.sin(a1) * r1;
                    x = x0 + (x1 - x0) * f;
                    y = y0 + (y1 - y0) * f;
                    break;
                }
                case 3: { // spiral (open)
                    double a = t * 3, r = 0.08 + 0.92 * (double) i / steps;
                    x = Math.cos(a) * r;
                    y = Math.sin(a) * r;
                    break;
                }
                case 4: // lemniscate
                    x = Math.cos(t) / (1 + Math.sin(t) * Math.sin(t)) * 1.1;
                    y = Math.sin(t) * Math.cos(t) / (1 + Math.sin(t) * Math.sin(t)) * 1.1;
                    break;
                case 5: { // hexagon
                    double k = (t / (Math.PI * 2)) * 6, f = k - Math.floor(k);
                    int seg = (int) Math.floor(k) % 6;
                    double a0 = seg * Math.PI / 3 + Math.PI / 6, a1 = a0 + Math.PI / 3;
                    x = Math.cos(a0) + (Math.cos(a1) - Math.cos(a0)) * f;
                    y = Math.sin(a0) + (Math.sin(a1) - Math.sin(a0)) * f;
                    break;
                }
                case 6: { // rose
                    double r = 0.35 + 0.65 * Math.abs(Math.cos(3 * t));
                    x = Math.cos(t) * r;
                    y = Math.sin(t) * r;
                    break;
                }
                default:
                    x = Math.cos(t);
                    y = Math.sin(t);
            }
            pts.add(new float[]{(float) (cx + x * size), (float) (cy + y * size)});
        }
        return pts;
    }
}
