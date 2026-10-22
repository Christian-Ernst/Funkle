// SPIRO background: the rosette leaves trails that bloom outward and turn. Behind it the
// spectrogram hangs as a faint curtain (newest at the bottom, bass in the middle, treble at
// the sides), which fills a tall screen top to bottom.
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    vec3 col = feedback(p, 0.988 - 0.012 * bass() - 0.01 * drop(), 0.006 * sin(uFlow * 0.1), 0.82);
    // the feedback keeps about 5 frames of this centre glow stacked, so it stays faint
    col += pal(0.5) * glow(length(p), 0.04 + 0.05 * bass()) * (0.03 + 0.09 * bass());
    float halfH = uRes.y / min(uRes.x, uRes.y);
    float halfW = uRes.x / min(uRes.x, uRes.y);
    float ty = (p.y + halfH) / (2.0 * halfH);
    float fx = 0.03 + 0.75 * pow(abs(p.x) / halfW, 0.8);
    float hv = hist(fx, ty * 120.0);
    float away = smoothstep(0.6, 1.3, length(p));   // keeps the centre clear for the rosette
    col += mix(palBg() * 2.0, pal(0.2 + ty * 0.4 + uFlow * 0.01), hv) * hv * hv * 0.07 * away;
    fragColor = vec4(col, 1.0);
}
