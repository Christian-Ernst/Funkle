// Scene transition: the frozen last frame of the old scene zooms in and fades out.
uniform sampler2D uSrc;
uniform float uMix;       // 0 = only old scene, 1 = only new scene
void main() {
    vec2 uv = (vUv - 0.5) * (1.0 - 0.12 * uMix) + 0.5;
    fragColor = vec4(texture(uSrc, uv).rgb, 1.0 - uMix);
}
