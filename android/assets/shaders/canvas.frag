// Canvas: a black sheet the user paints on. The paint itself is the stroke layer (stroke.vert
// and stroke.frag, drawn fresh every frame from the renderer's painting list, which is kept
// across scenes and restarts). This pass only carries the paint's echo: the previous frame,
// faded and breathing outward a little on the music, so a line glows and ripples with the beat
// without ever leaving the place it was drawn. Sliders: Echo (how long the glow lingers; the
// renderer dims the strokes by the same amount, so a line never stacks up brighter than its
// paint), Pulse (how much the music moves the echo and bends the lines), Haze (the tint of
// the empty sheet; 0 is black).
void main() {
    vec2 p = toP(gl_FragCoord.xy);
    float fade = tune(0, 0.0, 0.9);
    float pulse = tune(1, 0.0, 1.0);
    float zoom = 1.0 - pulse * (0.0012 + 0.004 * beat() + 0.012 * drop());
    vec3 col = feedback(p, zoom, 0.0, fade);
    // the sheet: a deep tint of the palette, breathing with the level, with a faint grain
    float haze = tune(2, 0.0, 1.0);
    float grain = 0.92 + 0.16 * noise(p * 3.0 + uFlow * 0.01);
    col += palBg() * haze * grain * (0.6 + 0.4 * level() + 0.5 * drop());
    fragColor = vec4(col, 1.0);
}
