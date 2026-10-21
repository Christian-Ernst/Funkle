// SPIRO points: a spirograph (hypotrochoid) whose pen offset follows the bass,
// with the live waveform etched along the curve. uCopies rotated layers, each
// reading a different stretch of the waveform, stack into a lace rosette.
uniform float uCount;
uniform float uCopies;
out vec3 vCol;
void main() {
    float t = float(gl_VertexID) / uCount;
    float copy = float(gl_InstanceID);
    float th = t * TAU;
    float lobes = 5.0 + mod(uSeed, 4.0);
    float pen = 0.16 + 0.22 * bass() + 0.05 * beat();
    vec2 base = vec2(cos(th), sin(th)) * (0.62 - pen * 0.5)
              + vec2(cos(-th * (lobes - 1.0)), sin(-th * (lobes - 1.0))) * pen;
    vec2 nrm = normalize(base + 1e-5);
    float w = wave(t * 3.0 + copy * 0.173);
    vec2 q = base + nrm * w * (0.03 + 0.10 * level());
    q *= 1.0 + 0.08 * beat();
    q = rot(TAU * copy / uCopies * 0.5 / max(lobes, 1.0) * 2.0 + uFlow * 0.03 * (mod(copy, 2.0) * 2.0 - 1.0)) * q;
    gl_Position = vec4(q * min(uRes.x, uRes.y) / uRes, 0.0, 1.0);
    gl_PointSize = max(1.5, (1.6 + 1.5 * beat()) * min(uRes.x, uRes.y) / 700.0);
    float s = spec(0.05 + 0.6 * fract(t * lobes));
    // All copies cover the same rosette: 1/sqrt(copies) keeps Mirror from multiplying the brightness.
    vCol = pal(t * 2.0 + copy / uCopies * 0.4 + uFlow * 0.02) * (0.05 + 0.18 * level() + 0.25 * s * s) * 1.4 / sqrt(uCopies);
}
