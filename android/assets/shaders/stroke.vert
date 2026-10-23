// User-drawn strokes. The live waveform travels along the line you drew, the line breathes
// with the beat, and the stroke is repeated uCopies times around the centre (mandala symmetry).
// Positions are screen-centred units straight from the touch, not through toP(): a stroke sits
// under the finger whatever the zoom, and only its trails take part in the scene's motion.
// On the Canvas scene the same geometry is paint (uBrush.w = 1): a brush width of its own, the
// colour the user picked (uInk) instead of the palette along the line, and only as much
// waveform and beat as the Pulse slider allows, so the drawing stays what the user drew.
in vec2 aPos;
in vec2 aNormal;
in float aS;       // 0..1 along the stroke
in float aSide;    // -1 / +1 across it
uniform float uLen;    // stroke length in centred units
uniform float uCopies;
uniform float uHue;
uniform float uAlpha;
uniform vec4 uBrush;   // paint: width (centred units), pulse 0..1, style (0 pen, 1 marker, 2 spray, 3 eraser), 1 = on
uniform vec4 uInk;     // paint colour; w = 1 uses it, 0 paints the palette along the line
out float vSide;
out vec3 vCol;
void main() {
    float copy = float(gl_InstanceID);
    float paint = uBrush.w;
    float x = aS * max(uLen, 0.2);
    float w = wave(x * 0.9 - uFlow * 0.22) * 0.7 + waveS(x * 0.35 + uFlow * 0.05) * 0.3;
    float taper = smoothstep(0.0, 0.04, aS) * smoothstep(1.0, 0.96, aS);
    float amp = (0.015 + 0.06 * level() + 0.03 * beat()) * taper * mix(1.0, 0.5 * uBrush.y * uBrush.y, paint);
    float width = mix(0.035 + 0.02 * beat(), uBrush.x * (1.0 + 0.1 * beat() * uBrush.y), paint);
    vec2 p = aPos + aNormal * (w * amp + aSide * width);
    // Copy 0 stays exactly where the finger drew it; the others are its mandala reflections.
    // (An earlier version turned every copy with the music clock, which after a minute of music
    // put the drawing a quarter turn away from the finger.)
    p = rot(TAU * copy / uCopies) * p;
    vSide = aSide;
    float s = spec(0.1 + 0.6 * aS);
    vec3 base = uInk.w > 0.5 ? uInk.rgb : pal(uHue + aS * 0.35 + copy * 0.04 + uFlow * 0.015);
    float lit = mix(0.6 + 1.1 * s + 0.5 * beat(), 1.0 + uBrush.y * (0.35 * s + 0.25 * beat()), paint);
    vCol = base * uAlpha * lit;
    gl_Position = vec4(p * min(uRes.x, uRes.y) / uRes, 0.0, 1.0);
}
