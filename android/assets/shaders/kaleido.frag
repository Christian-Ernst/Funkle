// MANDALA: an n-fold mirror kaleidoscope folded over its own previous frame.
// Rings of jewels pulse with the spectrum, the waveform is woven into lace, and
// the feedback pulls every echo inward so the ornament keeps recursing. A drop
// sucks the echoes in, swells the jewels and, unless Mirror fixes the symmetry,
// re-rolls the fold count. Sliders: jewels (their size), echo (how long it recurses), spin.
vec2 fold(vec2 p, float n, out float a) {
    float seg = TAU / n;
    a = angle(p);
    a = mod(a + seg * 0.5, seg) - seg * 0.5;
    a = abs(a);
    return vec2(cos(a), sin(a)) * length(p);
}
void main() {
    vec2 p0 = toP(gl_FragCoord.xy);
    float dp = drop();
    float n = uSym > 1.5 ? uSym : 6.0 + 2.0 * floor(mod(uSeed, 3.0));
    float seg = TAU / n;
    vec2 p = rot(uFlow * tune(2, -0.12, 0.2)) * p0;
    float a;
    vec2 q = fold(p, n, a);
    float r = length(q);

    // echoes: previous frame, pulled inward and twisted
    vec3 col = feedback(p0, 1.025 + 0.03 * beat() + 0.05 * dp, 0.01 + 0.01 * sin(uFlow * 0.2), tune(1, 0.64, 0.92));
    // echoes converge on the centre and would stack into a solid disc: fade them faster there
    col *= pow(mix(0.75, 1.0, smoothstep(0.05, 0.5, length(p0))), uStep);

    // rings of jewels; each ring listens to its own slice of the spectrum
    for (int k = 0; k < 5; k++) {
        float fk = float(k);
        float rk = 0.18 + 0.19 * fk + 0.03 * sin(uFlow * 0.3 + fk);
        float s = spec(0.04 + 0.16 * fk);
        float aa = seg * 0.5 * (0.5 + 0.35 * sin(uFlow * 0.2 + fk * 1.7));   // drifts inside the wedge
        vec2 c = vec2(cos(aa), sin(aa)) * rk;
        float d = length(q - c) - (0.012 + tune(0, 0.02, 0.12) * s * s + 0.04 * dp);
        col += pal(fk * 0.17 + uFlow * 0.02 + uSeed * 0.13) * glow(d, 0.006) * (0.25 + 1.2 * s);
        // thin orbit line
        col += pal(fk * 0.17 + 0.5) * glow(r - rk, 0.003) * 0.12 * (0.3 + s);
    }
    // lace: the waveform folded across the wedge
    float w = waveS(r * 0.7 - uFlow * 0.03);
    float lace = abs(a - seg * (0.25 + 0.2 * w * (0.4 + level())));
    col += pal(0.5 + r * 0.4) * glow(lace * r, 0.003) * smoothstep(0.05, 0.25, r) * (0.3 + 0.9 * level());
    // spectral arcs on the fold edges
    float sa = spec(clamp(r * 0.6, 0.0, 1.0) * 0.75 + 0.03);
    col += pal(0.8 + r * 0.2) * glow(a * r, 0.003 + 0.01 * sa) * sa * 0.6;
    // centre bloom on the beat and the drop
    col += mix(pal(0.9), palHi(), 0.2) * glow(length(p0), 0.015 + 0.05 * beat() + 0.08 * dp) * (0.12 + 0.5 * beat() + 0.8 * dp);
    fragColor = vec4(col, 1.0);
}
