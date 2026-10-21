// Soft round sprite, additive.
in vec3 vCol;
void main() {
    vec2 q = gl_PointCoord * 2.0 - 1.0;
    float d = dot(q, q);
    if (d > 1.0) discard;
    float g = exp(-d * 4.0);
    fragColor = vec4(vCol * g, 1.0);
}
