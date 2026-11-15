struct Params { amount: f32, speed: f32, size: f32 }

// Twinkles. The frame is cut into cells, nine down and as many across as
// keep them square; as many as the amount says hold a glint - a bright core
// and four thin arms along the axes - that swells and fades on a beat of
// its own, white with a touch of colour, laid over the picture as light. A
// glint keeps to the middle of its cell and fades out before its edge.

/// Cells down the frame.
const ROWS: f32 = 9.0;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(ROWS * aspect), 1.0);
    let at = vec2<f32>(uv.x * cols, uv.y * ROWS);
    let cell = floor(at);
    if (hash(cell, 23.0) >= params.amount / 100.0 * 0.5) {
        return c;
    }
    let middle = vec2<f32>(0.47 + hash(cell, 29.0) * 0.06, 0.47 + hash(cell, 31.0) * 0.06);
    let d = at - cell - middle;
    let r = length(d);
    let reach = 0.15 + params.size / 100.0 * 0.22;
    let rate = (1.5 + params.speed / 100.0 * 5.0) * (0.6 + hash(cell, 37.0) * 0.8);
    let beat = pow(0.5 + 0.5 * sin(frame.time * rate + hash(cell, 41.0) * 6.2832), 3.0);
    let thin = 0.018;
    let arms = exp(-abs(d.x) / thin) * exp(-abs(d.y) / (reach * 0.35)) + exp(-abs(d.y) / thin) * exp(-abs(d.x) / (reach * 0.35));
    let core = exp(-r * r / (2.0 * 0.03 * 0.03));
    let glint = (arms * 0.8 + core) * (1.0 - smoothstep(reach * 0.7, reach, r)) * beat;
    let tint = mix(vec3<f32>(1.0), vec3<f32>(0.8 + hash(cell, 43.0) * 0.2, 0.85, 1.0), 0.6);
    return vec4<f32>(c.rgb + tint * glint * 1.5, max(c.a, min(glint, 1.0)));
}
