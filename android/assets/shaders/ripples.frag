// Tap bursts: rings that expand from the touch point, their rim shaped by the live waveform, and
// sparks that fly out along the spectrum (a loud band throws its spark further).
uniform vec4 uRipple[8];   // xy = centre, z = age (s), w = strength
uniform int uRippleCount;
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    vec3 col = vec3(0.0);
    for (int i = 0; i < 8; i++) {
        if (i >= uRippleCount) break;
        vec4 rp = uRipple[i];
        float age = rp.z, fi = float(i);
        vec2 d = p - rp.xy;
        float r = length(d);
        float u = angle(d) / TAU + 0.5;
        float life = exp(-age * 0.75) * smoothstep(0.0, 0.06, age) * rp.w;
        for (int k = 0; k < 3; k++) {
            float fk = float(k);
            float R = max(age - fk * 0.18, 0.0) * (0.75 + 0.35 * level());
            float amp = (0.025 + 0.06 * level()) * (1.0 + R);
            float rr = R + amp * wave(u * (1.0 + fk) + fi * 0.37 + age * 0.1);
            col += pal(fi * 0.21 + fk * 0.12 + age * 0.08) * glow(r - rr, 0.004 + 0.002 * fk) * life * (1.0 - 0.3 * fk) * step(0.001, R);
        }
        // Sparks: 24 spokes, each as long as the spectrum is loud at its angle, shot out in the
        // first half second and falling back as they fade. Only a finger's tap (strength 1), not a beat ring.
        if (rp.w > 0.95) {
            float spokes = 24.0;
            float su = u * spokes + fi * 0.5;
            float sk = floor(su + 0.5);
            float across = abs(su - sk) / spokes * TAU * max(r, 0.02);   // distance to the spoke, in units
            float f = fract(sk / spokes);
            float loud = spec(0.05 + 0.75 * circFreq(f));
            float reach = (0.25 + 0.9 * loud) * (1.0 - exp(-age * 6.0)) * (0.35 + 0.65 * exp(-age * 1.2));
            float head = r / max(reach, 0.01);
            float along = smoothstep(0.08, 0.3, head) * (1.0 - smoothstep(0.85, 1.0, head));
            col += pal(fi * 0.21 + f + age * 0.15) * glow(across, 0.006) * along * exp(-age * 2.0) * (0.8 + loud);
        }
        col += pal(fi * 0.21) * glow(r, 0.02) * exp(-age * 5.0) * rp.w;   // flash at the touch point
    }
    fragColor = vec4(col, 1.0);
}
