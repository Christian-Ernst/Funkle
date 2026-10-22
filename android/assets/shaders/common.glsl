// Shared prelude for every TAV shader (vertex and fragment).
// The loader prepends "#version 300 es" and, for fragment shaders,
// "in vec2 vUv; out vec4 fragColor;".
precision highp float;
precision highp int;

uniform vec2  uRes;       // render-target size in pixels
uniform float uTime;      // seconds since start
uniform float uFlow;      // music-driven clock: runs faster when the track is intense
uniform float uStep;      // frame duration in 60 Hz frames (1.0 at 60 fps, 0.5 at 120 fps)
uniform sampler2D uAudio; // 512x4 R8: spectrum, spectrum peaks, waveform, smoothed waveform
uniform sampler2D uHist;  // 256x128 R8 spectrogram ring buffer
uniform float uHistHead;  // row index of the newest spectrogram line
uniform sampler2D uPrev;  // previous frame of the scene buffer (feedback)
uniform vec4  uBands;     // bass, low-mid, mid, treble   (0..1, auto-gained)
uniform vec4  uEnergy;    // level, beat pulse (1 -> 0), beat count, intensity
uniform vec3  uPal[3];    // palette (album art or preset)
uniform vec4  uColour;    // colour motion: hue shift (turns), band tilt, band pump, rainbow flag
uniform vec3  uBandMix;   // smoothed share of bass, mids and treble (each 0..1, sum <= 1)
uniform float uSym;       // symmetry order chosen by the user
uniform float uSeed;      // changes on drops; scenes use it to re-roll their look

#define PI  3.14159265
#define TAU 6.28318531

float spec(float x)     { return texture(uAudio, vec2(x, 0.125)).r; }
float specPeak(float x) { return texture(uAudio, vec2(x, 0.375)).r; }
// The waveform rows are loop-seamless, so fract() wraps cleanly around circles.
float wave(float x)     { return texture(uAudio, vec2(fract(x), 0.625)).r * 2.0 - 1.0; }
float waveS(float x)    { return texture(uAudio, vec2(fract(x), 0.875)).r * 2.0 - 1.0; }
// age = 0 is the newest spectrogram line, 127 the oldest.
float hist(float x, float age) {
    return texture(uHist, vec2(x, (uHistHead - age + 0.5) / 128.0)).r;
}

float bass()   { return uBands.x; }
float lowmid() { return uBands.y; }
float mids()   { return uBands.z; }
float treble() { return uBands.w; }
float level()  { return uEnergy.x; }
float beat()   { return uEnergy.y; }

float maxc(vec3 c) { return max(c.r, max(c.g, c.b)); }

// Hue rotation about the grey axis (Rodrigues), rescaled so the brightest channel keeps its
// value: the light a scene adds into its feedback must not grow when the hue turns.
vec3 hueShift(vec3 c, float turns) {
    float a = turns * TAU, co = cos(a), si = sin(a);
    const vec3 k = vec3(0.57735027);
    vec3 r = max(c * co + cross(k, c) * si + k * dot(k, c) * (1.0 - co), 0.0);
    return r * (maxc(c) / max(maxc(r), 1e-4));
}

// The full spectrum, t in turns, slightly toward white so yellow and cyan stay as bright as the rest.
vec3 rainbow(float t) {
    vec3 c = clamp(abs(fract(t + vec3(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
    return 0.1 + 0.9 * c;
}

// Palette colour k, lit up or dimmed by its band when the pump is on (factor 1 -+ pump).
vec3 palColour(int k, float w) { return uPal[k] * (1.0 + uColour.z * clamp(3.0 * w - 1.0, -1.0, 1.0)); }

// Smooth cyclic blend through the three palette colours (or the rainbow), then the colour
// motion: the loud bands pull the hue their way and the whole palette turns with the music.
vec3 pal(float t) {
    vec3 c;
    if (uColour.w > 0.5) {
        c = rainbow(t);
    } else {
        vec3 c0 = palColour(0, uBandMix.x), c1 = palColour(1, uBandMix.y), c2 = palColour(2, uBandMix.z);
        t = fract(t) * 3.0;
        float f = fract(t);
        f = f * f * (3.0 - 2.0 * f);
        if (t < 1.0) c = mix(c0, c1, f);
        else if (t < 2.0) c = mix(c1, c2, f);
        else c = mix(c2, c0, f);
    }
    // The pull is as strong as one band's lead over the other two: a lone bass hit pulls hard
    // toward colour 1, while a full mix pulls nowhere (summing all three colours would make grey).
    float ws = uBandMix.x + uBandMix.y + uBandMix.z;
    float lead = max(2.0 * max(uBandMix.x, max(uBandMix.y, uBandMix.z)) - ws, 0.0);
    if (uColour.y * lead > 0.0) {
        vec3 tint = uPal[0] * uBandMix.x + uPal[1] * uBandMix.y + uPal[2] * uBandMix.z;
        tint *= maxc(c) / max(maxc(tint), 1e-4);   // as bright as c, so the pull changes hue, not light
        c = mix(c, tint, uColour.y * min(lead, 1.0));
    }
    if (uColour.x != 0.0) c = hueShift(c, uColour.x);
    return c;
}

mat2 rot(float a) { float c = cos(a), s = sin(a); return mat2(c, s, -s, c); }

// atan(0, 0) is undefined in GLSL ES and NaN on some GPUs; the exact centre pixel of an
// odd-sized target hits it, and one NaN texel turns into a black block after bloom.
float angle(vec2 p) { return dot(p, p) < 1e-14 ? 0.0 : atan(p.y, p.x); }

float hash11(float p) { p = fract(p * 0.1031); p *= p + 33.33; p *= p + p; return fract(p); }
float hash12(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
vec2  hash22(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973)); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.xx + p3.yz) * p3.zy); }

float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1, 0)), u.x),
               mix(hash12(i + vec2(0, 1)), hash12(i + vec2(1, 1)), u.x), u.y);
}
float fbm(vec2 p) {
    float v = 0.0, a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = rot(0.6) * p * 2.03 + 11.7; a *= 0.5; }
    return v;
}

// Centred coordinates: the shorter screen axis spans [-1, 1].
vec2 toP(vec2 fragCoord) { return (fragCoord - 0.5 * uRes) / min(uRes.x, uRes.y) * 2.0; }
vec2 toUv(vec2 p)        { return p * min(uRes.x, uRes.y) / uRes * 0.5 + 0.5; }

// Previous frame sampled through a zoom/rotate warp and faded. zoom < 1 makes the
// old image grow outward, zoom > 1 pulls it toward the centre. All three are
// per-60-Hz-frame amounts, rescaled by uStep so 90/120 Hz screens look the same.
// The constant subtraction guarantees trails die out even in 8-bit buffers.
vec3 feedback(vec2 p, float zoom, float ang, float fade) {
    vec2 uv = toUv(rot(ang * uStep) * p * pow(zoom, uStep));
    vec3 c = min(texture(uPrev, uv).rgb, vec3(4.0));
    float inside = step(0.0, uv.x) * step(uv.x, 1.0) * step(0.0, uv.y) * step(uv.y, 1.0);
    return max(c * pow(fade, uStep) - 0.004 * uStep, 0.0) * inside;
}

// Frequency coordinate around a circle: bass at top and bottom, treble at the sides,
// mirrored so it is seamless and symmetric on both axes. u = angle / TAU + 0.5.
float circFreq(float u) { return 1.0 - abs(abs(fract(u + 0.25) * 2.0 - 1.0) * 2.0 - 1.0); }

// Neon line profile: 1 at distance 0, half at distance w, then ~1/d^2 so that
// thousands of overlapping lines don't flood the screen (bloom adds the halo).
float glow(float d, float w) { return w * w / (d * d + w * w); }
