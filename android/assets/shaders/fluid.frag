// INK: dye in water. The previous frame is carried along a curl-noise current (divergence-free,
// so the ink folds into filaments instead of piling up) and fades slowly, which is the fluid.
// The current is a field of eddies that turn over with the music clock and run faster the louder
// the track is; a beat spins the basin, the bass lifts the water. Four droppers feed it dye: the
// bass pours from the bottom in time with the beat, the mids circle the middle, the treble
// sprinkles from the top, so a tall screen fills end to end and the streaks show the flow.
// A drop pours in a ring of ink and doubles the current for a bar.
// Sliders: flow (current speed), ink (how much dye the music drips), swirl (eddy size).
float psi(vec2 q, float t) {
    // the stream function: noise whose domain is warped by a slower noise, so the eddies deform
    // and split over time rather than slide past as one fixed texture
    q += 0.45 * vec2(sin(q.y * 1.1 + t * 0.6), cos(q.x * 0.9 - t * 0.45));
    return fbm(q + vec2(t * 0.25, -t * 0.18));
}
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float halfW = uRes.x / min(uRes.x, uRes.y);
    float dp = drop(), lv = level(), bt = beat(), bs = bass(), md = mids(), tr = treble();
    float flowK = tuneExp(0, 0.4, 2.5), inkK = tuneExp(1, 0.4, 2.5), swirl = tuneExp(2, 0.5, 2.0);
    float t = uFlow * 0.3;

    // the current: curl of the stream function (central differences), buoyancy from the bass
    vec2 q = p * 1.6 * swirl + uSeed * 3.7;
    const float e = 0.025;
    float dy = psi(q + vec2(0.0, e), t) - psi(q - vec2(0.0, e), t);
    float dx = psi(q + vec2(e, 0.0), t) - psi(q - vec2(e, 0.0), t);
    vec2 vel = vec2(dy, -dx) / (2.0 * e) * 0.8;
    vel.y += 0.05 + 0.35 * bs;
    vel *= (0.35 + 1.1 * lv + 1.5 * dp) * flowK;
    // a beat spins the basin about its centre, a drop spins it hard
    vec2 ortho = vec2(-p.y, p.x) / (length(p) + 0.5);
    vel += ortho * (1.6 * bt + 2.5 * dp) * flowK;

    // the fluid: last frame fetched from upstream and faded. The constant subtraction keeps
    // 8-bit buffers from holding a stain for ever.
    vec2 uv = toUv(p - vel * 0.02 * uStep);
    vec3 col = min(texture(uPrev, uv).rgb, vec3(4.0));
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    col = max(col * pow(0.99, uStep) - 0.001 * uStep, 0.0) * inside;

    // dye: four droppers, each wandering about its own height with its own band
    float gain = 0.1 * inkK * uStep;
    for (int k = 0; k < 4; k++) {
        float fk = float(k);
        float band, y0, amp, spd;
        if (k == 0)      { band = bs; y0 = -0.75 * halfH; amp = 0.45; spd = 0.23; }
        else if (k == 1) { band = md; y0 = -0.15 * halfH; amp = 0.6;  spd = 0.31; }
        else if (k == 2) { band = md; y0 =  0.2 * halfH;  amp = 0.6;  spd = 0.27; }
        else             { band = tr; y0 =  0.75 * halfH; amp = 0.5;  spd = 0.37; }
        vec2 c = vec2(amp * halfW * sin(t * spd + fk * 2.1), y0 + 0.2 * halfH * cos(t * spd * 0.7 + fk * 1.3));
        float d2 = dot(p - c, p - c);
        float r = 0.05 + 0.12 * band;
        // the bass dropper pulses with the beat; the others pour steadily with their band
        float pour = k == 0 ? (0.15 + 0.6 * band * band + 0.8 * bt * band) : (0.08 + 0.9 * band * band);
        col += pal(fk * 0.27 + uFlow * 0.01) * exp(-d2 / (r * r)) * pour * gain;
    }
    // the waveform as a thread of light drawn across the middle
    float wv = waveS(p.x / (2.0 * halfW) + 0.5 + uFlow * 0.02) * (0.08 + 0.3 * lv);
    col += pal(0.5 + uFlow * 0.01) * glow(p.y - 0.1 * halfH * sin(uFlow * 0.1) - wv, 0.005) * (0.1 + 0.4 * lv) * gain;
    // a drop pours in a ring of ink that the current then tears apart
    float ring = exp(-uDrop * 3.0);
    col += mix(pal(0.85), palHi(), 0.3) * glow(length(p) - uDrop * 2.5, 0.05) * ring * 0.1 * uStep;
    // a faint wash of the palette's deep tone, so the water is never plain black
    col += palBg() * 0.012 * uStep * (1.0 + 0.5 * lowmid());
    fragColor = vec4(col, 1.0);
}
