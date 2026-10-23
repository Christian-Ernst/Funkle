// PULSAR: a landscape of soft ridges that flows past under the camera. Each ridge is the
// spectrum of one moment (bass in the middle, treble at the sides), smoothed into dunes and
// filled in like solid ground, so the terrain reads as hills under rim light rather than a plot.
// The newest ridge rises at the bottom edge with the sound of right now and the field moves
// off toward the horizon at the pace the music is recorded, every ridge keeping its own shape
// (the field is the whole spectrogram ring, so the spacing follows the ridge count). Each beat
// lifts the front ridges, a drop lifts the whole range, and a pulsar on the horizon flashes
// with the beat; above, an aurora breathes with the treble and stars twinkle with the hi-hats,
// so a tall screen is full from bottom to top.
// Sliders: ridges (how many), height, stars.
float hueT;
// spectrum at a ridge position x (-1..1 across the ridge, bass in the middle), smoothed across
// seven bins so the crest is a dune, not a comb; v is 0..1
float ridge(float x, float age) {
    float ax = min(abs(x), 1.0);
    float fx = 0.02 + 0.72 * pow(ax, 0.85);
    const float d = 1.0 / 256.0;
    float v = 0.2 * hist(fx, age)
            + 0.17 * (hist(fx - d, age) + hist(fx + d, age))
            + 0.13 * (hist(fx - 2.0 * d, age) + hist(fx + 2.0 * d, age))
            + 0.1 * (hist(fx - 3.0 * d, age) + hist(fx + 3.0 * d, age));
    return v * v;
}
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float halfW = uRes.x / min(uRes.x, uRes.y);
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float dp = drop(), b = bass(), tr = treble(), bt = beat();
    hueT = uFlow * 0.008;
    float hor = 0.3 * halfH;                                   // the far edge of the land
    float yFront = -halfH - 0.3;                               // ridges are born under the bottom edge
    float hScale = tune(1, 0.25, 0.9) * min(halfH, 1.8) * (1.0 + 0.5 * dp);
    float starsAt = tune(2, 0.999, 0.975);
    float nLines = floor(tune(0, 6.0, 12.99));
    // the ridges share out the 128 spectrogram rows (4.3 s at 30 rows a second) and move back
    // as the rows age, so each keeps the spectrum it was born with: no shape ever flickers
    float spacing = 128.0 / nLines;
    float ph = fract(uHistHead / spacing);
    float sway = 0.05 * sin(uFlow * 0.4);
    // the sky colours, also the fog the far ridges sink into
    vec3 skyDeep = palBg() * 0.9, skyHor = mix(palBg() * 1.8, pal(0.6 + hueT), 0.2);
    vec3 fogC = mix(skyHor, pal(0.45 + hueT), 0.12) * 0.75;
    // the ground under the newest ridge, before it has risen
    vec3 ground = palBg() * (0.6 + 0.4 * b);

    vec3 col = vec3(0.0);
    float hit = 0.0;
    float dMax = 1.0 - 1.0 / (1.0 + 2.4);
    // only pixels low enough can be land: the sky skips the loop
    if (p.y < hor + 0.6 * hScale) {
        const int LINES = 13;
        for (int i = 0; i < LINES; i++) {
            float zc = float(i) + ph;                           // 0 = being born, nLines = gone
            if (zc >= nLines) break;
            float d = 1.0 - 1.0 / (1.0 + zc * 2.4 / nLines);    // perspective: far ridges crowd together
            float k = d / dMax;                                 // 0 front .. 1 at the horizon
            float base = mix(yFront, hor, k);
            float width = halfW * (1.35 - 0.8 * k);
            float x = (p.x - sway * k) / width;
            float env = smoothstep(1.0, 0.25, abs(x));          // the loud part is in the middle
            float age = min(zc * spacing, 127.0);
            float v = ridge(x, age);
            // a slow swell that rides with the ridges, so the land has hills even in silence
            float swell = noise(vec2(x * 2.2 + uSeed, zc * 0.3 + uSeed * 0.7)) - 0.5;
            float lift = (0.5 + 0.5 * env) * (0.35 * b + 0.6 * bt) * smoothstep(0.25, 0.0, k);   // the beat rolls in at the front
            float h = ((v * (0.4 + 0.6 * env) * (1.0 + lift) + 0.12 * swell * (0.3 + 0.7 * env) + 0.05 * env) * hScale) * (1.0 - 0.55 * k);
            float y = base + h;
            float dy = p.y - y;
            float born = smoothstep(0.0, 1.0, zc);              // the newest ridge fades up from the ground
            float fog = smoothstep(0.15, 1.0, k) * 0.65;
            vec3 lc = pal(0.05 + k * 0.5 + v * 0.3 + hueT);
            float lit = 0.35 + 1.5 * v + 0.5 * bt * smoothstep(0.3, 0.0, k) + 0.6 * dp;
            if (dy < 0.0) {
                // under this crest: the lit slope, falling away to the deep tone, and the ridges
                // behind it are hidden
                vec3 body = mix(palBg() * 0.35, lc * (0.1 + 0.28 * v), exp(dy * 9.0));
                body += lc * 0.03 * v;
                body = mix(body, fogC, fog);
                col += mix(ground, body, born);
                col += lc * glow(dy, 0.012) * lit * born * (1.0 - fog) * 0.7;
                hit = 1.0;
                break;
            }
            // above this crest: its neon halo
            col += lc * glow(dy, 0.012) * lit * born * (1.0 - fog) * 0.7;
        }
    }
    float sky = 1.0 - hit;
    // ---- sky
    float up = p.y - hor;
    vec3 skyC = mix(skyHor, skyDeep, smoothstep(0.0, 1.4, up));
    // aurora: two slow curtains that brighten with the treble and a drop
    float cur = fbm(vec2(p.x * 1.2 + uFlow * 0.04, up * 0.8 - uFlow * 0.03));
    float cur2 = fbm(vec2(p.x * 0.7 - uFlow * 0.025 + 7.0, up * 0.5 + 3.0));
    skyC += pal(0.55 + p.x * 0.08 + cur * 0.35 + hueT) * cur * cur * smoothstep(0.15, 1.2, up) * (0.15 + 0.55 * tr + 0.5 * dp);
    skyC += pal(0.8 + cur2 * 0.3 + hueT) * cur2 * cur2 * cur2 * smoothstep(0.4, 1.8, up) * (0.1 + 0.4 * tr);
    // the pulsar: a star on the horizon that flashes with every beat and flares on a drop
    vec2 pc = vec2(sway * 1.5, hor + 0.12);
    float pd = length(p - pc);
    skyC += mix(pal(0.3 + hueT), palHi(), 0.4) * glow(pd, 0.02 + 0.03 * bt + 0.06 * dp) * (0.3 + 0.6 * bt + 0.8 * dp);
    skyC += pal(0.35 + hueT) * exp(-pd * 3.0) * (0.06 + 0.2 * bt + 0.4 * dp);
    // the horizon glows where the land meets the sky
    skyC += pal(0.4 + hueT) * exp(-max(up, 0.0) * 8.0) * (0.08 + 0.2 * b + 0.5 * dp);
    // stars that twinkle with the hi-hats
    vec2 g = p * 36.0;
    vec2 cell = floor(g);
    float star = hash12(cell + 1.3);
    vec2 so = hash22(cell + 4.7) - 0.5;
    float tw = 0.5 + 0.5 * sin(uTime * (2.0 + 4.0 * star) + star * 40.0);
    skyC += palHi() * step(starsAt, star) * glow(length(g - cell - 0.5 - so * 0.6), 0.07) * smoothstep(0.1, 0.8, up) * (0.15 + 0.7 * tr) * tw * 0.8;
    // uncovered pixels are sky, or bare ground under the bottom edge where the next ridge is born
    col += mix(ground, skyC, step(hor, p.y)) * sky;
    fragColor = vec4(col, 1.0);
}
