// WORMHOLE: an endless polar tunnel. Panels light up with the spectrum, rings
// flash on the beat and the flight speed follows the music clock.
void main() {
    vec2 p0 = toP(gl_FragCoord.xy);
    // the vanishing point drifts lazily
    vec2 p = p0 + 0.14 * vec2(sin(uFlow * 0.13), cos(uFlow * 0.11));
    float r = length(p) + 1e-4;
    float a = angle(p);
    float twist = 1.6 * sin(uFlow * 0.05);
    float z = 1.1 / r + uFlow * 1.6;               // depth along the tunnel
    float u = fract(a / TAU + 0.5 + twist * 0.06 * z / (1.0 + 0.05 * z) - uFlow * 0.004);
    float m = circFreq(u);
    float s = spec(0.03 + 0.75 * m);
    float sp = specPeak(0.03 + 0.75 * m);

    float near = smoothstep(0.04, 0.6, r);          // far end dissolves into darkness
    float seg = 32.0;
    float lane = fract(u * seg);
    float laneD = min(lane, 1.0 - lane) / seg * TAU * r;          // screen distance to lane edge
    float rz = fract(z * 1.5);
    float ringD = min(rz, 1.0 - rz) / 1.5 * r * r / 1.1;           // screen distance to depth ring

    vec3 c = vec3(0.0);
    // panels: spectrum-lit tiles between the grid lines
    float panel = smoothstep(0.0, 0.012, laneD) * smoothstep(0.0, 0.012, ringD);
    float blink = step(1.0 - s * s * 0.6, hash12(vec2(floor(u * seg), floor(z * 1.5))));
    c += pal(z * 0.03 + m * 0.4) * panel * (0.015 + 0.12 * s * s + 0.6 * blink * s) * near * smoothstep(1.6, 0.4, r);
    // grid lines
    c += pal(0.3 + m * 0.6 + z * 0.02) * glow(laneD, 0.003) * (0.15 + 0.9 * sp) * near;
    c += pal(z * 0.04 + 0.5) * glow(ringD, 0.0035) * (0.2 + 1.4 * beat() + 0.5 * bass()) * near;
    // waveform filaments spiralling down the walls
    float w = wave(fract(z * 0.08) + u * 0.5);
    float filD = abs(fract(u * 6.0 + 0.5 * w * (0.3 + level())) - 0.5) / 6.0 * TAU * r;
    c += pal(0.7 + z * 0.02) * glow(filD, 0.003) * smoothstep(0.08, 0.5, r) * (0.15 + 0.8 * level());
    // light at the end of the tunnel
    c += mix(pal(0.15), vec3(1.0), 0.5) * glow(r, 0.03 + 0.04 * bass()) * (0.3 + 0.9 * bass());
    // a little motion blur from the previous frame
    c += feedback(p0, 1.006, 0.0, 0.35);
    fragColor = vec4(c, 1.0);
}
