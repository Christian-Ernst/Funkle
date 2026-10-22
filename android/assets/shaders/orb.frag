// FERRO: a raymarched ferrofluid sphere. The spectrum pushes spikes out of its
// surface, the bass inflates it and the light is iridescent like oil on water.
// It floats over a dark floor that mirrors it, which fills the bottom of a tall
// screen; dust twinkles with the treble above. A drop bursts the spikes.
float spikeField(vec3 n) {
    // ferrofluid spikes on a cube-sphere lattice; each spike listens to its own frequency
    vec3 q = n;
    q.xz = rot(uFlow * 0.12) * q.xz;
    q.yz = rot(uFlow * 0.05) * q.yz;
    vec3 aq = abs(q);
    vec2 uv; float face;
    if (aq.x >= aq.y && aq.x >= aq.z)      { uv = q.yz / aq.x; face = sign(q.x); }
    else if (aq.y >= aq.z)                 { uv = q.xz / aq.y; face = 2.0 * sign(q.y); }
    else                                   { uv = q.xy / aq.z; face = 3.0 * sign(q.z); }
    vec2 g = uv * 3.0;
    vec2 cell = floor(g);
    vec2 f = fract(g) - 0.5;
    float h = hash12(cell * 7.1 + face * 13.3);
    float s = spec(0.04 + 0.6 * h);
    float cone = max(0.0, 1.0 - length(f) * 2.1);
    return (0.15 + 0.85 * s * s) * cone * cone * cone;
}
float map(vec3 p) {
    float r = length(p);
    vec3 n = p / max(r, 1e-4);
    float R = 0.62 + 0.10 * bass() + 0.04 * beat() + 0.05 * drop();
    return (r - R - (0.06 + 0.5 * level() + 0.3 * drop()) * spikeField(n)) * 0.45;
}
vec3 normalAt(vec3 p) {
    const vec2 e = vec2(0.002, -0.002);
    return normalize(e.xyy * map(p + e.xyy) + e.yyx * map(p + e.yyx) + e.yxy * map(p + e.yxy) + e.xxx * map(p + e.xxx));
}
void main() {
    vec2 p0 = toP(gl_FragCoord.xy);
    float dp = drop();
    // the floor: pixels below it see the sphere mirrored, with a slight ripple. On a landscape
    // screen the floor lies below the bottom edge.
    float floorY = -1.05;
    float below = step(p0.y, floorY);
    vec2 p = mix(p0, vec2(p0.x + 0.012 * sin(p0.y * 30.0 + uFlow * 2.0) * (0.3 + level()), 2.0 * floorY - p0.y), below);
    float refl = mix(1.0, 0.45 * exp((p0.y - floorY) * 1.3), below);   // the reflection fades with distance
    vec3 ro = vec3(0.0, 0.0, 2.25);
    vec3 rd = normalize(vec3(p * 0.62, -1.0));

    // background: trails from feedback, haze in the palette's tone, a halo and twinkling dust
    vec3 col = feedback(p0, 0.99, 0.004, 0.82) * 0.9;
    float hz = fbm(p0 * 1.1 + vec2(uFlow * 0.03, uFlow * 0.02));
    col += palBg() * (0.3 + 0.5 * hz) * 0.4 * (0.5 + 0.5 * lowmid());
    col += palBg() * smoothstep(floorY + 0.4, floorY - 1.2, p0.y) * 0.8;
    col += pal(0.6 + uFlow * 0.01) * glow(length(p) - 0.85 - 0.15 * bass(), 0.05) * 0.10 * refl;
    vec2 g = p0 * 22.0;
    vec2 cell = floor(g);
    float st = hash12(cell + 3.7);
    vec2 so = hash22(cell + 1.3) - 0.5;
    float tw = 0.5 + 0.5 * sin(uTime * (1.5 + 3.0 * st) + st * 50.0);
    col += palHi() * step(0.93, st) * glow(length(g - cell - 0.5 - so * 0.7), 0.06) * (0.05 + 0.35 * treble()) * tw;

    // bounding sphere
    float bR = 1.25;
    float bb = dot(ro, rd), cc = dot(ro, ro) - bR * bR, disc = bb * bb - cc;
    if (disc > 0.0) {
        float t = -bb - sqrt(disc), tEnd = -bb + sqrt(disc);
        bool hit = false;
        for (int i = 0; i < 90; i++) {
            float d = map(ro + rd * t);
            if (d < 0.0012) { hit = true; break; }
            t += d;
            if (t > tEnd) break;
        }
        if (hit) {
            vec3 pos = ro + rd * t;
            vec3 n = normalAt(pos);
            vec3 l = normalize(vec3(0.6, 0.8, 0.7));
            float diff = max(dot(n, l), 0.0);
            float fres = pow(clamp(1.0 - dot(n, -rd), 0.0, 1.0), 3.0);   // rounding can push the base below 0
            vec3 h = normalize(l - rd);
            float spec2 = pow(max(dot(n, h), 0.0), 60.0);
            float spike = spikeField(normalize(pos));
            vec3 irid = pal(fres * 0.8 + dot(n, vec3(0.3, 0.5, 0.2)) + uFlow * 0.02);
            vec3 base = vec3(0.015, 0.012, 0.02);    // inky black fluid
            vec3 sc = base * (0.3 + diff);
            sc += irid * fres * (0.9 + 1.5 * beat() + dp);
            sc += palHi() * spec2 * 1.2;
            sc += pal(0.1 + spike) * spike * spike * (3.0 + 4.0 * dp);   // spike tips glow hot
            // rim from a second coloured light
            sc += pal(0.5) * pow(max(dot(n, normalize(vec3(-0.8, -0.3, 0.4))), 0.0), 3.0) * 0.5;
            // the sphere hides the background; its reflection lies on the floor haze
            col = mix(sc, col + sc * refl, below);
        }
    }
    fragColor = vec4(col, 1.0);
}
