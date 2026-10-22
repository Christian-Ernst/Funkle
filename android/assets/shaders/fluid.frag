// INK: dye in water. The previous frame is carried along a curl-noise flow (divergence-free, so
// the ink folds and streaks instead of piling up) and fades slowly, which is the fluid; the
// bands drip dye into it at their own heights (bass low, mids in the middle, treble high, so a
// tall screen fills top to bottom), the waveform draws a thin thread across, and a beat
// spins the whole basin. A drop pours in a ring of ink and doubles the current for a bar.
// Sliders: flow (current speed), ink (how much dye the music drips), swirl (eddy size).
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float halfW = uRes.x / min(uRes.x, uRes.y);
    float dp = drop(), lv = level(), bt = beat();
    float flowK = tuneExp(0, 0.4, 2.5), inkK = tuneExp(1, 0.4, 2.5), swirl = tuneExp(2, 0.5, 2.0);

    // the current: curl of a drifting noise potential, plus buoyancy that the bass feeds
    vec2 q = p * 1.1 * swirl + vec2(uFlow * 0.035, -uFlow * 0.025) + uSeed * 3.7;
    const float e = 0.03;
    float ny = fbm(q + vec2(0.0, e)) - fbm(q - vec2(0.0, e));
    float nx = fbm(q + vec2(e, 0.0)) - fbm(q - vec2(e, 0.0));
    vec2 vel = vec2(ny, -nx) / (2.0 * e) * 0.8;
    vel.y += 0.25 + 0.6 * bass();
    vel *= (0.5 + 0.9 * lv + 1.2 * dp) * flowK;
    vec2 ortho = vec2(-p.y, p.x) / (length(p) + 0.4);   // a beat swirls the basin around its centre
    vel += ortho * bt * 2.0 * flowK;

    // the fluid: last frame fetched from upstream and faded. The constant subtraction keeps
    // 8-bit buffers from holding a stain for ever.
    vec2 uv = toUv(p - vel * 0.02 * uStep);
    vec3 col = min(texture(uPrev, uv).rgb, vec3(4.0));
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    col = max(col * pow(0.978, uStep) - 0.0015 * uStep, 0.0) * inside;

    // dye: one dropper per band, wandering about its own height
    float gain = 0.12 * inkK * uStep;
    for (int k = 0; k < 3; k++) {
        float fk = float(k);
        float band = k == 0 ? bass() : k == 1 ? mids() : treble();
        vec2 c = vec2(0.55 * halfW * sin(uFlow * (0.17 + 0.05 * fk) + fk * 2.1),
                      (fk - 1.0) * 0.6 * halfH + 0.25 * cos(uFlow * (0.13 + 0.04 * fk) + fk * 1.3));
        float d2 = dot(p - c, p - c);
        float r = 0.14 + 0.2 * band;
        col += pal(fk / 3.0 + uFlow * 0.01) * exp(-d2 / (r * r)) * (0.1 + band * band) * gain;
    }
    // the waveform as a thread of light drawn across the middle
    float wv = waveS(p.x / (2.0 * halfW) + 0.5 + uFlow * 0.02) * (0.08 + 0.3 * lv);
    col += pal(0.5 + uFlow * 0.01) * glow(p.y - 0.15 * halfH * sin(uFlow * 0.1) - wv, 0.006) * (0.1 + 0.4 * lv) * gain;
    // a drop pours in a ring of ink that the current then tears apart
    float ring = exp(-uDrop * 3.0);
    col += mix(pal(0.85), palHi(), 0.3) * glow(length(p) - uDrop * 2.5, 0.05) * ring * 0.12 * uStep;
    // a faint wash of the palette's deep tone, so the water is never plain black
    col += palBg() * 0.02 * uStep * (1.0 + 0.5 * lowmid());
    fragColor = vec4(col, 1.0);
}
