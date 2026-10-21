// Fullscreen triangle generated from gl_VertexID; no vertex buffers needed.
out vec2 vUv;
void main() {
    vec2 v = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    vUv = v;
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
}
