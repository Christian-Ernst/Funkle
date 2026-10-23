// HORIZON: a night sea under a striped sun. Far mountains on the horizon are the spectrogram of a
// few seconds ago, a calm skyline that reshapes itself slowly; the water in front mirrors the
// sun, the sky and the mountains, broken into a shimmering path by waves that roll toward the
// camera in time with the music clock, their crests lit by what just played (bass in the
// middle, treble at the sides) and by every beat. Above, an aurora breathes with the treble and
// stars twinkle with the hi-hats, so a tall screen is full from the bottom edge to the top.
// A drop lifts the mountains, flares the sun and heightens the waves.
// Sliders: height (mountains and waves), haze (how soon the sea fades into the sky), glow (the
// sun, crests and sky lights).
float hScale, lights, hueT, hor;
// spectrum at a world x (bass in the middle, treble at the sides), smoothed across seven bins
float ridge(float xw, float age) {
    float ax = min(abs(xw) / 2.6, 1.0);
    float fx = 0.02 + 0.72 * pow(ax, 0.85);
    const float d = 1.0 / 256.0;
    float v = 0.2 * hist(fx, age)
            + 0.17 * (hist(fx - d, age) + hist(fx + d, age))
            + 0.13 * (hist(fx - 2.0 * d, age) + hist(fx + 2.0 * d, age))
            + 0.1 * (hist(fx - 3.0 * d, age) + hist(fx + 3.0 * d, age));
    return v * v * (0.4 + 0.6 * smoothstep(1.2, 0.3, ax));
}
// the skyline: the spectrogram of three to four seconds ago, averaged so it drifts rather than jumps
float mountains(float x) {
    float xw = x * 1.4;
    float v = (ridge(xw, 96.0) + ridge(xw, 108.0) + ridge(xw, 120.0)) / 3.0;
    float peaks = 0.4 + 0.6 * noise(vec2(xw * 0.7 + uSeed * 0.3, 4.2));   // some summits, some passes
    return (0.03 + 0.2 * noise(vec2(xw * 1.5 + uSeed, 1.7)) + v * 0.7 * peaks) * hScale;
}
// everything above the water line, so the sea can mirror it
vec3 sky(vec2 p, float b, float tr, float dp) {
    float up = p.y - hor;
    vec3 sky0 = palBg() * 0.9, sky1 = mix(palBg() * 1.8, pal(0.6 + hueT), 0.18);
    vec3 col = mix(sky1, sky0, smoothstep(0.0, 1.3, up));
    // the sun: a big disc with the classic horizontal cuts in its lower half, swelling with the bass
    vec2 sunC = vec2(0.0, hor + 0.5);
    float sunR = 0.3 + 0.05 * b + 0.1 * dp;
    float sd = length(p - sunC);
    float disc = smoothstep(sunR + 0.012, sunR - 0.012, sd);
    float cutY = (p.y - sunC.y) / sunR;        // -1 at the bottom of the disc, 0 at its middle
    float cuts = 1.0 - smoothstep(0.35, 0.55, sin((p.y - hor) * 40.0 + uTime * 0.3) * 0.5 + 0.5) * smoothstep(0.1, -0.3, cutY);
    vec3 sunCol = mix(pal(0.1 + hueT), pal(0.35 + hueT), smoothstep(-1.0, 1.0, cutY));
    sunCol = mix(sunCol, palHi(), 0.1 * smoothstep(0.2, 1.0, cutY));
    col += sunCol * disc * cuts * (0.42 + 0.25 * b + 0.8 * dp) * lights;
    col += sunCol * exp(-max(sd - sunR, 0.0) * 4.0) * (0.12 + 0.15 * b + 0.4 * dp) * lights;   // corona
    // aurora: a slow curtain high in the sky that brightens with the treble
    float cur = fbm(vec2(p.x * 0.9 + uFlow * 0.03, up * 0.6 - uFlow * 0.02));
    col += pal(0.5 + p.x * 0.08 + cur * 0.3 + hueT) * cur * cur * smoothstep(0.3, 1.4, up) * (0.12 + 0.5 * tr + 0.4 * dp) * lights;
    // stars that twinkle with the hi-hats
    vec2 g = p * 24.0;
    vec2 cell = floor(g);
    float star = hash12(cell + 2.1);
    vec2 so = hash22(cell + 5.3) - 0.5;
    float tw = 0.5 + 0.5 * sin(uTime * (2.0 + 4.0 * star) + star * 40.0);
    col += palHi() * step(0.984, star) * glow(length(g - cell - 0.5 - so * 0.6), 0.07) * smoothstep(0.2, 0.9, up) * (0.15 + 0.7 * tr) * tw * 0.8 * lights;
    // the horizon glows where the sea meets the sky
    col += pal(0.4 + hueT) * exp(-max(up, 0.0) * 10.0) * (0.07 + 0.15 * b + 0.5 * dp) * lights;
    // the mountains: a dark skyline with a lit crest, sunk a little into the haze
    float mh = mountains(p.x);
    float crest = up - mh;
    vec3 rock = mix(palBg() * 0.4, sky1, 0.2);
    rock += pal(0.15 + hueT) * exp(-max(-crest, 0.0) * 16.0) * (0.08 + 0.2 * b) * lights;    // the sun's glow on the slopes
    col = mix(col, rock, smoothstep(0.006, -0.006, crest) * step(0.0, up));
    col += pal(0.2 + hueT) * glow(crest, 0.003) * step(0.0, up) * (0.15 + 0.3 * b + 0.5 * dp) * lights;
    return col;
}
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float dp = drop(), b = bass(), tr = treble(), bt = beat();
    hScale = tune(0, 0.35, 1.2) * (1.0 + 0.5 * dp);
    float hazeK = tune(1, 0.06, 0.4);
    lights = tune(2, 0.5, 1.6);
    hueT = uFlow * 0.01;
    hor = -0.1 * halfH;                        // the water line sits a little under the middle
    float camH = 0.5;                          // camera height over the water, in world units

    vec3 col;
    if (p.y >= hor) {
        col = sky(p, b, tr, dp);
    } else {
        // ---- the sea
        float z = camH / (hor - p.y);          // distance along the water
        float xw = p.x * z;
        // waves: rows that roll toward the camera with the music clock, lit by what just played
        float roll = uFlow * 0.35;
        float rows = z * 2.5 - roll;
        float wn = noise(vec2(xw * 1.5 + 3.0, rows * 2.0)) - 0.5;                    // chop
        float age = min(z * 6.0, 127.0);
        float v = ridge(xw, age);
        float rowF = fract(rows + 0.15 * wn);
        float near = smoothstep(14.0, 2.0, z);                                       // crests vanish into the distance
        // the reflection: the sky mirrored in the water line and smeared along the swell, which
        // stretches the sun into a shimmering path; strongest near the camera, where the waves
        // are big on screen, and dimmer in the near, steep view
        float swell = noise(vec2(xw * 1.2 + 1.0, rows * 4.0)) - 0.5;
        float amp = (0.25 + 0.4 * hScale * (0.3 + v) + 0.3 * dp) / (0.5 + z * 0.5);
        vec2 pr = vec2(p.x + amp * 0.3 * wn, 2.0 * hor - p.y + amp * 2.0 * swell);
        vec3 refl = sky(pr, b, tr, dp);
        float fres = mix(0.4, 0.75, smoothstep(0.0, 12.0, z));
        col = palBg() * 0.4 + refl * fres;
        // the crests: a neon line on every wave, brighter where the music was loud and on the beat
        float wave = exp(-pow((z - (1.0 - bt) * 10.0) * 0.6, 2.0)) * bt;              // a beat rolls out to the horizon
        vec3 crestC = pal(0.15 + v * 0.3 + z * 0.02 + hueT);
        col += crestC * glow(min(rowF, 1.0 - rowF), 0.025) * (0.08 + 0.6 * v + 0.15 * b + 0.5 * wave) * (0.5 + 0.5 * hScale) * near * lights;
        // glitter: thin flecks of chop that catch the sun and the crests
        float sp = noise(vec2(xw * 10.0, rows * 40.0 + 3.0));
        sp *= sp; sp *= sp; sp *= sp;
        float path = 1.0 + 3.0 * exp(-p.x * p.x / (0.03 + 0.02 * z));                   // the sun's glitter path
        col += mix(crestC, palHi(), 0.3) * sp * path * (0.25 + 0.5 * v + 0.4 * b) * smoothstep(8.0, 1.0, z) * lights;
        // haze toward the horizon
        float fog = 1.0 - exp(-z * hazeK);
        col = mix(col, mix(palBg() * 1.8, pal(0.6 + hueT), 0.18), fog * 0.85);
    }
    fragColor = vec4(col, 1.0);
}
