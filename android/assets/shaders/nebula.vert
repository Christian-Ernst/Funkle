// NEBULA particles: a stateless spiral galaxy. Every star is a pure function of
// its id and the music clock. Orbit radius maps to frequency, so each ring of the
// galaxy breathes with its own part of the spectrum, every beat sends a
// shockwave rolling out from the core and a drop sends a bigger, slower one. On a
// tall screen the disc lies along the long axis. Sliders: spin, glow, spread (the disc's reach).
uniform float uCount;
out vec3 vCol;
void main() {
    float id = float(gl_VertexID);
    float h1 = hash11(id * 0.1371 + 1.3), h2 = hash11(id * 0.7193 + 4.1), h3 = hash11(id * 0.3117 + 7.7), h4 = hash11(id * 0.917 + 2.2);
    float h5 = hash11(id * 0.523 + 9.1);
    float arms = 2.0 + floor(mod(uSeed, 3.0));
    vec3 pos;
    float rad;
    if (h1 < 0.12) {
        // bulge: a fuzzy sphere at the core
        float th = h2 * TAU, ph = acos(2.0 * h3 - 1.0);
        rad = 0.05 + 0.22 * pow(h4, 1.5);
        pos = vec3(sin(ph) * cos(th), cos(ph) * 0.6, sin(ph) * sin(th)) * rad;
        pos.xz = rot(uFlow * 0.15) * pos.xz;
    } else {
        float arm = floor(h5 * arms);
        rad = 0.08 + pow(h2, 0.55) * tune(2, 0.9, 1.8);
        float scatter = (h3 - 0.5) * (0.3 + 0.9 * rad) * (0.4 + 0.6 * h4);
        float ang = arm * TAU / arms + log(rad + 0.05) * 2.2 + scatter + uFlow * tuneExp(0, 0.25, 4.0) * (0.04 + 0.06 / (rad + 0.3));
        pos = vec3(cos(ang) * rad, (h4 - 0.5) * 0.08 * (1.4 - rad), sin(ang) * rad);
    }
    float s = spec(clamp(rad / 1.45, 0.0, 1.0) * 0.8 + 0.03);
    // shockwave: a ring that leaves the core on each beat
    float front = (1.0 - beat()) * 1.6;
    float dr = (rad - front) * 7.0;   // squared by hand: pow() of a negative base is undefined
    float shock = exp(-dr * dr) * beat();
    float dr2 = (rad - uDrop * 1.1) * 4.0;
    shock = min(shock + exp(-dr2 * dr2) * drop() * 1.5, 2.0);
    pos *= 1.0 + 0.12 * s + 0.25 * shock;
    pos.y += (h3 - 0.5) * 0.4 * s * s;
    // camera: tilted disc slowly turning
    pos.yz = rot(0.95 + 0.25 * sin(uTime * 0.04)) * pos.yz;
    pos.xy = rot(0.3 * sin(uTime * 0.03)) * pos.xy;
    float zc = 2.1 - pos.z;
    vec2 sp = pos.xy / zc * 2.5;
    if (uRes.y > uRes.x) sp = vec2(-sp.y, sp.x) * 1.15;   // portrait: the disc along the long axis
    gl_Position = vec4(sp * min(uRes.x, uRes.y) / uRes, 0.0, 1.0);
    float px = min(uRes.x, uRes.y) / 700.0;
    gl_PointSize = max(1.0, (1.4 + 3.0 * s * h4 + 3.0 * shock) * px * 2.1 / zc);
    // Thousands of stars overlap at the core: tint it from the palette rather than white and
    // thin it out, so it glows instead of filling in solid.
    float core = exp(-rad * 4.0);
    vec3 c = pal(rad * 0.6 + h3 * 0.2 + uFlow * 0.01) * (0.25 + 1.3 * s);
    c += mix(pal(0.1 + h3 * 0.3), palHi(), 0.35) * core * 0.5;
    c += mix(pal(rad * 0.6 + 0.3), palHi(), 0.5) * shock * 0.8;
    vCol = c * (0.3 + 0.7 * h4) * tuneExp(1, 0.3, 1.2) * (1.0 - 0.55 * core);
}
