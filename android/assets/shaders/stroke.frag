uniform vec4 uBrush;   // see stroke.vert; the style picks the profile across the line
in float vSide;
in vec3 vCol;
void main() {
    float d = abs(vSide);
    int style = uBrush.w > 0.5 ? int(uBrush.z + 0.5) : 0;
    if (style == 3) {
        // eraser: coverage, drawn with the blend (0, 1 - src) so it takes paint away
        fragColor = vec4(vec3(smoothstep(1.0, 0.8, d)), 1.0);
        return;
    }
    float g;
    if (style == 1) g = smoothstep(1.0, 0.7, d) * 0.9;                       // marker: flat, a soft edge
    else if (style == 2) g = exp(-d * d * 3.0) * 0.22 - 0.011 * d * d;       // spray: soft, faint, no edge
    else if (uBrush.w > 0.5) g = smoothstep(1.0, 0.45, d) * 1.3 + glow(d, 0.3) * 0.4;   // paint pen: full and bright
    else {                                                                   // the singing line: a hot core and a halo
        float n = max(glow(d, 0.06) - glow(1.0, 0.06), 0.0) / (1.0 - glow(1.0, 0.06));
        g = n * n * 2.2 + glow(d, 0.15) * 0.25;
    }
    fragColor = vec4(vCol * max(g, 0.0), 1.0);
}
