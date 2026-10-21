// Final image: scene + bloom, tone mapping, beat-driven chromatic aberration,
// vignette and fine grain.
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength;   // both set by the brightness setting
uniform float uExposure;
uniform float uFlash;     // brief lift on drops, relative to the exposure

vec3 tonemap(vec3 x) {
    // Hue-preserving: the brightest channel goes through the ACES fit (Narkowicz) and the
    // other two keep their ratio to it. A per-channel curve lets every channel of a hot core
    // reach 1, so stacked pink or cyan light bleached to a white blob; here it stays pink.
    // Only light far past the shoulder drifts part of the way toward white.
    x = max(x, 0.0);
    float m = max(x.r, max(x.g, x.b));
    float t = m * 0.8;
    float y = clamp((t * (2.51 * t + 0.03)) / (t * (2.43 * t + 0.59) + 0.14), 0.0, 1.0);
    vec3 c = x * (y / max(m, 1e-4));
    return mix(c, vec3(y), smoothstep(4.0, 24.0, m) * 0.5);
}

void main() {
    vec2 uv = vUv;
    vec2 c = uv - 0.5;
    float ca = 0.0015 + 0.006 * beat();
    vec3 col;
    col.r = texture(uScene, uv - c * ca).r;
    col.g = texture(uScene, uv).g;
    col.b = texture(uScene, uv + c * ca).b;
    vec3 bl = texture(uBloom, uv).rgb;
    col += bl * uBloomStrength * (1.0 + 0.6 * beat());
    col *= uExposure * (1.0 + 0.15 * beat() + uFlash);
    col = tonemap(col);
    col = pow(col, vec3(0.92));
    float vig = smoothstep(1.25, 0.35, length(c * vec2(uRes.x / uRes.y, 1.0) * 1.6));
    col *= mix(0.55, 1.0, vig);
    col += (hash12(gl_FragCoord.xy + fract(uTime * 7.0) * 113.0) - 0.5) * 0.025;
    fragColor = vec4(col, 1.0);
}
