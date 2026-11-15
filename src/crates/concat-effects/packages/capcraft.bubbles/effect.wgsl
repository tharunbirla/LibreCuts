struct Params { amount: f32, size: f32 }

// Bubbles drift up the picture: the frame is cut into cells, six down and as
// many across as keep them square, each column rising at a pace of its own;
// as many cells as the amount says hold a bubble - a pale fill, a bright rim
// and a glint at its upper left - laid on as light. A bubble keeps inside
// its cell.

/// Cells down the frame.
const ROWS: f32 = 6.0;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(ROWS * aspect), 1.0);
    let col = floor(uv.x * cols);
    let at = vec2<f32>(uv.x * cols, uv.y * ROWS + frame.time * (0.3 + hash(vec2<f32>(col, 0.0), 11.0) * 0.4));
    let cell = floor(at);
    if (hash(cell, 13.0) >= params.amount / 100.0 * 0.6) {
        return c;
    }
    let middle = vec2<f32>(0.5 + (hash(cell, 17.0) - 0.5) * 0.2, 0.5);
    let d = at - cell - middle;
    let r = length(d);
    let radius = 0.16 + params.size / 100.0 * 0.2;
    let rim = exp(-pow((r - radius) / 0.03, 2.0));
    let fill = (1.0 - smoothstep(radius * 0.9, radius, r)) * 0.12;
    let g = d - vec2<f32>(-radius * 0.4, -radius * 0.4);
    let glint = exp(-dot(g, g) / (2.0 * 0.02 * 0.02));
    let light = rim * 0.6 + fill + glint * 0.8;
    return vec4<f32>(c.rgb + vec3<f32>(0.85, 0.95, 1.0) * light, c.a);
}
