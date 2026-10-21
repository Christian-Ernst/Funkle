// User-drawn strokes. The live waveform travels along the line you drew, and the
// stroke is repeated uCopies times around the centre (mandala symmetry).
in vec2 aPos;
in vec2 aNormal;
in float aS;       // 0..1 along the stroke
in float aSide;    // -1 / +1 across it
uniform float uLen;    // stroke length in centred units
uniform float uCopies;
uniform float uHue;
uniform float uAlpha;
out float vSide;
out vec3 vCol;
void main() {
    float copy = float(gl_InstanceID);
    float x = aS * max(uLen, 0.2);
    float w = wave(x * 0.9 - uFlow * 0.22) * 0.7 + waveS(x * 0.35 + uFlow * 0.05) * 0.3;
    float taper = smoothstep(0.0, 0.04, aS) * smoothstep(1.0, 0.96, aS);
    float amp = (0.03 + 0.10 * level() + 0.04 * beat()) * taper;
    float width = 0.035 + 0.02 * beat();
    vec2 p = aPos + aNormal * (w * amp + aSide * width);
    p = rot(TAU * copy / uCopies + uFlow * 0.02) * p;
    vSide = aSide;
    float s = spec(0.1 + 0.6 * aS);
    vCol = pal(uHue + aS * 0.35 + copy * 0.04 + uFlow * 0.015) * uAlpha * (0.6 + 1.1 * s + 0.5 * beat());
    gl_Position = vec4(p * min(uRes.x, uRes.y) / uRes, 0.0, 1.0);
}
