struct Params { amount: f32, speed: f32 }

// Little hearts float up the picture, swaying, in pinks and reds laid over
// it. The heart is the curve (x^2 + y^2 - 1)^3 = x^2 y^3, a fifth of a cell
// across, kept inside its cell.

/// Cells down the frame.
const ROWS: f32 = 7.0;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(ROWS * aspect), 1.0);
    let at = vec2<f32>(uv.x * cols, uv.y * ROWS + frame.time * (0.4 + params.speed / 100.0 * 1.2));
    let cell = floor(at);
    if (hash(cell, 3.0) >= params.amount / 100.0 * 0.4) {
        return c;
    }
    let sway = sin(frame.time * 1.5 + hash(cell, 5.0) * 6.2832) * 0.08;
    let q = (at - cell - vec2<f32>(0.5 + sway, 0.5)) * vec2<f32>(1.0, -1.0) / 0.2 + vec2<f32>(0.0, 0.15);
    let r = dot(q, q) - 1.0;
    let f = r * r * r - q.x * q.x * q.y * q.y * q.y;
    let m = 1.0 - smoothstep(-0.05, 0.05, f);
    let colour = mix(vec3<f32>(1.0, 0.25, 0.45), vec3<f32>(0.95, 0.1, 0.2), hash(cell, 7.0));
    return vec4<f32>(mix(c.rgb, colour, m * 0.9), max(c.a, m * 0.9));
}
