// HALO: a breathing core, a waveform ring and spectral rays. Every beat launches a
// ripple from the centre (see ripples.frag), so the music spreads across the screen
// in rings; short feedback trails give the motion its smear.
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float r = length(p);
    float a = atan(p.y, p.x);
    float u = a / TAU + 0.5;
    float b = bass(), bt = beat(), lv = level();

    vec3 col = feedback(p, 0.994 - 0.012 * bt, 0.002 + 0.006 * mids(), 0.80);
    col *= vec3(0.97, 0.95, 1.0);              // trails cool off as they travel

    float R0 = 0.42 + 0.07 * b + 0.04 * bt;

    // spectral flower inside the ring
    float m = circFreq(u + uFlow * 0.004);
    float s = spec(0.03 + 0.7 * m);
    float rf = R0 * (0.5 + 0.42 * s);
    col += pal(0.55 + 0.3 * s + uFlow * 0.01) * smoothstep(rf, rf - 0.15, r) * smoothstep(0.1, R0, r) * 0.12 * s;
    col += pal(0.6 + uFlow * 0.01) * glow(r - rf, 0.005) * (0.3 + 0.9 * s);

    // the waveform ring, two counter-rotating strands
    for (int k = 0; k < 2; k++) {
        float fk = float(k);
        float dir = fk * 2.0 - 1.0;
        float w = wave(u + dir * uFlow * 0.03 + fk * 0.37);
        float rw = R0 + (0.04 + 0.09 * lv) * w * (1.0 - 0.3 * fk);
        col += pal(fk * 0.33 + uFlow * 0.02) * glow(r - rw, 0.0045) * (0.45 + 0.6 * lv);
    }

    // spectral rays radiating out of the ring
    float nr = 96.0;
    float cu = (floor(u * nr) + 0.5) / nr;
    float cm = circFreq(cu + uFlow * 0.004);
    float sv = spec(0.03 + 0.7 * cm);
    float sr = specPeak(0.03 + 0.7 * cm);
    float arc = abs(fract(u * nr) - 0.5) / nr * TAU * r;   // distance to ray centre line
    float r1 = R0 + 0.07, r2 = r1 + 0.03 + 0.5 * sv * sv;
    float ray = smoothstep(0.009, 0.004, arc) * smoothstep(r1 - 0.01, r1, r) * smoothstep(r2, r2 - 0.03, r);
    col += pal(0.25 + cm * 0.5 + uFlow * 0.01) * ray * (0.03 + 0.3 * sv * sv);
    float cap = r1 + 0.04 + 0.5 * sr * sr;
    col += pal(0.1 + cm * 0.5) * smoothstep(0.009, 0.004, arc) * glow(r - cap, 0.004) * 0.7 * sr;

    // core: plasma that swells with the bass
    float rc = 0.08 + 0.11 * b + 0.03 * bt;
    float pl = fbm(p * 4.0 + vec2(uFlow * 0.4, -uFlow * 0.3));
    col += mix(pal(0.7), vec3(1.0), 0.25 * bt) * smoothstep(rc, 0.0, r) * (0.15 + 0.6 * pl) * (0.4 + b);
    col += pal(0.8) * glow(r - rc, 0.006) * (0.2 + 0.7 * b);

    fragColor = vec4(col, 1.0);
}
