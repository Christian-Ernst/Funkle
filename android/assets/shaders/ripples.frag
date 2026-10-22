// Tap ripples: rings that expand from the touch point, their rim shaped by the live waveform.
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
        col += pal(fi * 0.21) * glow(r, 0.02) * exp(-age * 5.0) * rp.w;   // flash at the touch point
    }
    fragColor = vec4(col, 1.0);
}
