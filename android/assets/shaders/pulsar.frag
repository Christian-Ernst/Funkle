// PULSAR: the spectrogram history as glowing ridgelines receding into the
// distance — "Unknown Pleasures", alive. Front ridges occlude the ones behind.
// Above the horizon an aurora in the palette's tone and stars that twinkle with
// the hi-hats fill the top of a tall screen; a drop lifts the whole landscape.
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float halfW = uRes.x / min(uRes.x, uRes.y);
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float dp = drop();
    float yFront = -0.9 * halfH, yBack = 0.4 * halfH;
    float hScale = 0.5 * min(halfH, 1.8) * (1.0 + 0.6 * dp);
    float sway = 0.04 * sin(uTime * 0.21);
    vec3 col = vec3(0.0);
    const int LINES = 48;
    float hit = 0.0;
    for (int i = 0; i < LINES; i++) {
        float fi = float(i);
        float d = 1.0 - 1.0 / (1.0 + fi * 0.055);            // 0 = front, -> ~0.73 at the back
        float k = d / 0.73;
        float base = mix(yFront, yBack, k);
        float width = halfW * (1.0 - 0.45 * k);
        float x = (p.x - sway * k) / width;
        if (abs(x) > 1.0) continue;
        float ax = abs(x);
        // bass in the middle, treble at the edges, calm margins like the original plot
        float env = smoothstep(1.0, 0.3, ax);
        float age = fi * 1.5;
        float v = hist(0.02 + 0.72 * pow(ax, 0.85), age);
        float n = noise(vec2(x * 14.0, floor(uHistHead - age) * 1.7)) - 0.5;
        float h = (v * v * (0.35 + 0.65 * env) + 0.08 * n * env * (0.3 + v)) * hScale * (1.0 - 0.5 * k);
        float y = base + h;
        float dy = p.y - y;
        float fade = 1.0 - 0.75 * k;
        vec3 lc = pal(0.05 + k * 0.6 + v * 0.25 + uFlow * 0.008) * fade;
        if (dy < 0.0) {                       // under this ridge: hidden behind it
            col += lc * glow(dy, 0.005) * (0.7 + 2.2 * v) * (1.0 + (i < 2 ? 1.5 : 0.0) * beat());
            col += lc * 0.08 * v * smoothstep(-0.3, 0.0, dy);   // faint sheen under the crest
            hit = 1.0;
            break;
        }
        col += lc * glow(dy, 0.005) * (0.7 + 2.2 * v);
    }
    float sky = 1.0 - hit;
    // sky: an aurora curtain in the palette's tone that brightens with the treble
    float cur = fbm(vec2(p.x * 1.6 + uFlow * 0.06, p.y * 0.7 - uFlow * 0.02));
    float aur = cur * cur * smoothstep(yBack, yBack + 0.7, p.y);
    col += (palBg() * 1.2 + pal(0.6 + p.x * 0.1 + cur * 0.3) * (0.1 + 0.4 * treble() + 0.4 * dp)) * sky * aur;
    // a pulsing horizon
    col += pal(0.4) * sky * glow(p.y - yBack - 0.02, 0.12) * (0.12 + 0.35 * bass() + 0.5 * dp);
    // stars that twinkle with the hi-hats
    vec2 g = p * 40.0;
    vec2 cell = floor(g);
    float star = hash12(cell);
    vec2 sp = hash22(cell) - 0.5;
    float tw = 0.5 + 0.5 * sin(uTime * (2.0 + 4.0 * star) + star * 40.0);
    col += palHi() * sky * step(0.985, star) * glow(length(g - cell - 0.5 - sp * 0.6), 0.06) * (0.2 + 0.8 * treble()) * tw * 0.8;
    fragColor = vec4(col, 1.0);
}
