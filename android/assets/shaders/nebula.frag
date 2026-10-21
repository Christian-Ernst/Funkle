// NEBULA background: deep-space haze plus short motion trails of the stars.
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    vec3 col = feedback(p, 0.997 - 0.006 * beat(), -0.001, 0.55);
    float h = fbm(p * 1.3 + vec2(uFlow * 0.02, -uFlow * 0.01));
    col += pal(0.65 + h * 0.3) * pow(h, 3.0) * (0.04 + 0.08 * lowmid());
    col += mix(pal(0.1), vec3(1.0, 0.9, 0.8), 0.2) * glow(length(p), 0.05 + 0.06 * bass()) * (0.12 + 0.3 * bass());
    fragColor = vec4(col, 1.0);
}
