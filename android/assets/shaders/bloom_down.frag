// Dual-Kawase downsample. On the first pass uThreshold > 0 keeps only bright light.
uniform sampler2D uSrc;
uniform vec2 uTexel;      // 1 / source size
uniform float uThreshold;
vec3 prefilter(vec3 c) {
    if (uThreshold <= 0.0) return c;
    float br = max(c.r, max(c.g, c.b));
    float soft = clamp(br - uThreshold + 0.25, 0.0, 0.5);
    soft = soft * soft / 0.5;   // soft knee
    return c * max(soft, br - uThreshold) / max(br, 1e-4);
}
void main() {
    vec2 o = uTexel;
    vec3 c = texture(uSrc, vUv).rgb * 4.0;
    c += texture(uSrc, vUv + vec2(-o.x, -o.y)).rgb;
    c += texture(uSrc, vUv + vec2( o.x, -o.y)).rgb;
    c += texture(uSrc, vUv + vec2(-o.x,  o.y)).rgb;
    c += texture(uSrc, vUv + vec2( o.x,  o.y)).rgb;
    fragColor = vec4(prefilter(c / 8.0), 1.0);
}
