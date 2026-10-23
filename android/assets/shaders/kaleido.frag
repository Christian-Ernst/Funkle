// MANDALA: an n-fold mirror kaleidoscope folded over its own previous frame. Rings of jewels
// (filled, soft-edged gems, one per wedge and mirrored) swell with their slice of the spectrum,
// arcs join them, and a slow echo of the last frame recurses the ornament inward. The rings
// reach past the screen's width, so a tall screen gets jewels at the top and bottom too. A drop
// draws the echoes in, swells the jewels and, unless Mirror fixes the symmetry, re-rolls the
// fold count. Sliders: jewels (their size), echo (how long it recurses), spin.
vec2 fold(vec2 p, float n, out float a) {
    float seg = TAU / n;
    a = angle(p);
    a = mod(a + seg * 0.5, seg) - seg * 0.5;
    a = abs(a);
    return vec2(cos(a), sin(a)) * length(p);
}
void main() {
    vec2 p0 = toP(gl_FragCoord.xy);
    float dp = drop(), lv = level(), bt = beat();
    float n = uSym > 1.5 ? uSym : 6.0 + 2.0 * floor(mod(uSeed, 3.0));
    float seg = TAU / n;
    vec2 p = rot(uFlow * tune(2, -0.08, 0.12)) * p0;
    float a;
    vec2 q = fold(p, n, a);
    float r = length(q);

    // echoes: previous frame, drawn gently inward and turned a little
    vec3 col = feedback(p0, 1.012 + 0.015 * bt + 0.04 * dp, 0.004 + 0.004 * sin(uFlow * 0.2), tune(1, 0.6, 0.86));
    // echoes converge on the centre and would stack into a solid disc: fade them faster there
    col *= pow(mix(0.7, 1.0, smoothstep(0.05, 0.5, length(p0))), uStep);

    // rings of jewels; each ring listens to its own slice of the spectrum
    float jewel = tune(0, 0.03, 0.1);
    for (int k = 0; k < 6; k++) {
        float fk = float(k);
        float rk = 0.22 + 0.3 * fk + 0.02 * sin(uFlow * 0.25 + fk);
        float s = 0.5 * (spec(0.03 + 0.14 * fk) + specPeak(0.03 + 0.14 * fk));
        float aa = seg * 0.5 * (0.5 + 0.3 * sin(uFlow * 0.15 + fk * 1.7));   // drifts inside the wedge
        vec2 c = vec2(cos(aa), sin(aa)) * rk;
        float size = 0.015 + jewel * s + 0.03 * dp;
        float d = length(q - c) - size;
        vec3 jc = pal(fk * 0.17 + uFlow * 0.02 + uSeed * 0.13);
        // a filled gem: soft body, a brighter rim, a hot core
        col += jc * glow(max(d, 0.0), 0.008) * (0.12 + 0.45 * s);
        col += mix(jc, palHi(), 0.3) * glow(d, 0.004) * (0.08 + 0.3 * s);
        col += mix(jc, palHi(), 0.35) * glow(max(d + size * 0.6, 0.0), 0.01) * 0.25 * s * s;
        // the arc that carries the ring, lit by its band
        col += pal(fk * 0.17 + 0.5 + uFlow * 0.02) * glow(r - rk, 0.0025) * (0.04 + 0.2 * s);
    }
    // lace: the smoothed waveform woven across the wedge, once per ring spacing
    float w = waveS(r * 0.08 - uFlow * 0.01);
    float lace = abs(a - seg * (0.25 + 0.18 * w * (0.4 + lv)));
    col += pal(0.5 + r * 0.3) * glow(lace * r, 0.0025) * smoothstep(0.1, 0.4, r) * (0.1 + 0.5 * lv);
    // spokes on the fold edges, fading in from the centre
    col += pal(0.8 + r * 0.2) * glow(a * r, 0.002) * smoothstep(0.15, 0.6, r) * (0.05 + 0.3 * lv);
    // centre bloom on the beat and the drop
    col += mix(pal(0.9), palHi(), 0.2) * glow(length(p0), 0.015 + 0.04 * bt + 0.08 * dp) * (0.1 + 0.4 * bt + 0.8 * dp);
    fragColor = vec4(col, 1.0);
}
