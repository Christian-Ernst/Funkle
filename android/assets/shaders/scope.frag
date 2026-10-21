// SPIRO background: the rosette leaves trails that bloom outward and turn.
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    vec3 col = feedback(p, 0.988 - 0.012 * bass(), 0.006 * sin(uFlow * 0.1), 0.86);
    col += pal(0.5) * glow(length(p), 0.04 + 0.05 * bass()) * (0.1 + 0.3 * bass());
    fragColor = vec4(col, 1.0);
}
