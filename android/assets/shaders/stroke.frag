in float vSide;
in vec3 vCol;
void main() {
    float d = abs(vSide);
    float g = max(glow(d, 0.06) - glow(1.0, 0.06), 0.0) / (1.0 - glow(1.0, 0.06));
    fragColor = vec4(vCol * (g * g * 2.2 + glow(d, 0.15) * 0.25), 1.0);
}
