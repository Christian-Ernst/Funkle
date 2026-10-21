// Final image: scene + bloom, tone mapping, beat-driven chromatic aberration,
// vignette and fine grain.
uniform sampler2D uScene;
uniform sampler2D uBloom;
uniform float uBloomStrength;
uniform float uExposure;
uniform float uFlash;     // brief white lift on drops

vec3 tonemap(vec3 x) {
    // ACES fitted curve (Narkowicz) — hot cores roll off to white, halos stay saturated.
    x *= 0.8;
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
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
    col *= uExposure * (1.0 + 0.15 * beat()) + uFlash;
    col = tonemap(col);
    col = pow(col, vec3(0.92));
    float vig = smoothstep(1.25, 0.35, length(c * vec2(uRes.x / uRes.y, 1.0) * 1.6));
    col *= mix(0.55, 1.0, vig);
    col += (hash12(gl_FragCoord.xy + fract(uTime * 7.0) * 113.0) - 0.5) * 0.025;
    fragColor = vec4(col, 1.0);
}
