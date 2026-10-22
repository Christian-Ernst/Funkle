// HORIZON: a flight over a terrain whose height is the spectrogram: what just played rises right
// in front of the camera and drifts away toward the horizon (bass ridge in the middle, treble at
// the sides). The ground is dark with a neon grid on its rows and peaks that glow hot; the far
// end sinks into haze. Above the horizon a sky in the palette's deep tone, a sun that pulses with
// the bass, an aurora that breathes with the treble and stars for the hi-hats fill the top of a
// tall screen. A drop lifts the whole landscape and flares the sun.
// Sliders: height (how tall the music builds the ground), haze (how soon it fades), glow (the
// grid and sky lights).
const float ROWS = 10.0;   // spectrogram rows per unit of depth: 128 rows reach ~13 units
float hScale;
float ground(vec2 xz) {
    float ax = abs(xz.x) / 2.4;
    float fx = 0.02 + 0.72 * pow(min(ax, 1.0), 0.85);
    float age = min(xz.y * ROWS, 127.0);
    // three taps across the bins, so the spectrum's steps don't show as terraces
    float v = 0.5 * hist(fx, age) + 0.25 * (hist(fx - 1.0 / 256.0, age) + hist(fx + 1.0 / 256.0, age));
    float env = smoothstep(1.3, 0.4, ax);                           // calm plains at the sides
    float n = noise(xz * vec2(2.5, 1.8) + vec2(0.0, uSeed * 1.3)) - 0.5;
    return v * v * hScale * (0.3 + 0.7 * env) + 0.05 * n * (0.3 + v) + 0.04 * env;
}
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float dp = drop(), b = bass(), tr = treble();
    hScale = tune(0, 0.25, 0.95) * (1.0 + 0.6 * dp);
    float haze = tune(1, 0.03, 0.17), lights = tune(2, 0.4, 1.6);
    vec3 sky0 = palBg() * 1.2, sky1 = mix(palBg() * 2.0, pal(0.6 + uFlow * 0.01), 0.2);
    vec3 fogC = mix(palBg() * 2.2, pal(0.6 + uFlow * 0.01), 0.12);
    vec3 sunDir = normalize(vec3(0.35, 0.22, 1.0));

    vec3 ro = vec3(0.0, 0.9, 0.0);
    vec3 rd = normalize(vec3(p.x * 0.8, p.y * 0.8 - 0.22, 1.0));
    // the camera sways with the low mids, like a slow banking turn
    rd.xy = rot(0.05 * sin(uTime * 0.19) * (0.5 + lowmid())) * rd.xy;

    // ---- sky
    float up = max(rd.y, 0.0);
    vec3 col = mix(sky1, sky0, smoothstep(0.0, 0.5, up));
    float sunD = max(dot(rd, sunDir), 0.0);
    vec3 sunCol = mix(pal(0.1 + uFlow * 0.01), palHi(), 0.4);
    col += sunCol * (smoothstep(0.985, 0.996, sunD) * (0.5 + 0.6 * b + 1.2 * dp)
                   + pow(sunD, 12.0) * (0.12 + 0.2 * b + 0.4 * dp)
                   + pow(sunD, 3.0) * exp(-abs(rd.y - sunDir.y) * 30.0) * (0.1 + 0.3 * b)) * lights;
    // aurora: a curtain that brightens with the treble
    vec2 sp = rd.xy / max(rd.z, 0.2);
    float cur = fbm(vec2(sp.x * 1.4 + uFlow * 0.05, sp.y * 0.8 - uFlow * 0.03));
    col += pal(0.5 + sp.x * 0.1 + cur * 0.3) * cur * cur * smoothstep(0.05, 0.6, up) * (0.1 + 0.5 * tr + 0.4 * dp) * lights;
    // stars that twinkle with the hi-hats
    vec2 g = sp * 30.0;
    vec2 cell = floor(g);
    float star = hash12(cell + 2.1);
    vec2 so = hash22(cell + 5.3) - 0.5;
    float tw = 0.5 + 0.5 * sin(uTime * (2.0 + 4.0 * star) + star * 40.0);
    col += palHi() * step(0.986, star) * glow(length(g - cell - 0.5 - so * 0.6), 0.06) * smoothstep(0.02, 0.3, up) * (0.15 + 0.7 * tr) * tw * 0.8 * lights;
    // the horizon glows where the ground meets the sky
    col += pal(0.4) * exp(-abs(rd.y) * 18.0) * (0.15 + 0.3 * b + 0.6 * dp) * lights;

    // ---- ground: a fixed march with growing steps, then one interpolation at the hit
    if (rd.y < -0.005) {
        float t = 0.05, tPrev = 0.05, hPrev = 1.0;
        float hit = -1.0;
        for (int i = 0; i < 64; i++) {
            vec3 pos = ro + rd * t;
            float h = pos.y - ground(pos.xz);
            if (h < 0.0) {
                hit = mix(tPrev, t, hPrev / (hPrev - h));   // where the ray crossed the ground
                break;
            }
            tPrev = t;
            hPrev = h;
            t += 0.03 + t * 0.065;
            if (t > 16.0) break;
        }
        if (hit > 0.0) {
            vec3 pos = ro + rd * hit;
            const vec2 e = vec2(0.03, 0.0);
            float h0 = ground(pos.xz);
            vec3 n = normalize(vec3(h0 - ground(pos.xz + e.xy), e.x, h0 - ground(pos.xz + e.yx)));
            float ax = abs(pos.x) / 2.4;
            float v = hist(0.02 + 0.72 * pow(min(ax, 1.0), 0.85), min(pos.z * ROWS, 127.0));
            float diff = max(dot(n, sunDir), 0.0);
            vec3 base = palBg() * 0.9 * (0.2 + 0.9 * diff);                         // dark ground
            vec3 glowC = pal(0.15 + v * 0.3 + pos.z * 0.03 + uFlow * 0.01);
            base += glowC * v * v * (0.25 + 0.6 * diff) * (0.8 + dp);                // lit by what it heard
            // the neon grid: a line on every spectrogram row and lanes across
            float rowF = fract(pos.z * ROWS * 0.5), laneF = fract(pos.x * 2.0 + 0.5);
            float rowL = glow(min(rowF, 1.0 - rowF), 0.03) * (0.5 + 2.0 * v);
            float laneL = glow(min(laneF, 1.0 - laneF), 0.012) * 0.4;
            base += glowC * (rowL * 0.7 + laneL) * (0.1 + 0.5 * v * v + 0.15 * beat()) * lights;
            // peaks catch fire
            base += mix(glowC, palHi(), 0.2) * smoothstep(0.5, 1.0, v) * v * (0.2 + 0.5 * dp) * lights;
            // a wave of light rolls out from the camera on each beat
            float frontD = (pos.z - (1.0 - beat()) * 8.0) * 1.5;
            base += glowC * exp(-frontD * frontD) * beat() * 0.3 * lights;
            float fog = 1.0 - exp(-hit * haze * (1.0 + 0.4 * lowmid()));
            col = mix(base, fogC, fog);
        }
    }
    fragColor = vec4(col, 1.0);
}
