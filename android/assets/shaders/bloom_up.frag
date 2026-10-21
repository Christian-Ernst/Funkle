// Dual-Kawase upsample of the lower level, added to the current level.
uniform sampler2D uSrc;   // lower (smaller) level
uniform sampler2D uAdd;   // same-size level from the down chain
uniform vec2 uTexel;      // 1 / source size
void main() {
    vec2 o = uTexel;
    vec3 c = texture(uSrc, vUv + vec2(-2.0 * o.x, 0.0)).rgb;
    c += texture(uSrc, vUv + vec2(2.0 * o.x, 0.0)).rgb;
    c += texture(uSrc, vUv + vec2(0.0, -2.0 * o.y)).rgb;
    c += texture(uSrc, vUv + vec2(0.0, 2.0 * o.y)).rgb;
    c += texture(uSrc, vUv + vec2(-o.x, -o.y)).rgb * 2.0;
    c += texture(uSrc, vUv + vec2( o.x, -o.y)).rgb * 2.0;
    c += texture(uSrc, vUv + vec2(-o.x,  o.y)).rgb * 2.0;
    c += texture(uSrc, vUv + vec2( o.x,  o.y)).rgb * 2.0;
    fragColor = vec4(c / 12.0 + texture(uAdd, vUv).rgb, 1.0);
}
