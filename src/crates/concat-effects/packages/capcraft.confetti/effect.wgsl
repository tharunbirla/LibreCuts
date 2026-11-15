struct Params { amount: f32, speed: f32, size: f32 }

// Confetti. The frame is cut into cells, nine down and as many across as
// keep them square, and the cells fall with time; as many of them as the
// amount says hold a piece of paper - a small oblong in one of six bright
// colours, turning as it falls and fluttering from side to side. A piece
// keeps to the middle of its cell, so pieces never meet.

/// Cells down the frame.
const ROWS: f32 = 9.0;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(ROWS * aspect), 1.0);
    let fall = frame.time * (0.05 + params.speed / 100.0 * 0.35);
    let at = vec2<f32>(uv.x * cols, (uv.y - fall) * ROWS);
    let cell = floor(at);
    let local = at - cell;
    if (hash(cell, 3.0) >= params.amount / 100.0 * 0.6) {
        return c;
    }
    // where the piece sits, how it flutters and turns, and its colour
    let flutter = sin(frame.time * 2.5 + hash(cell, 5.0) * 6.2832) * 0.08;
    let middle = vec2<f32>(0.4 + hash(cell, 7.0) * 0.2 + flutter, 0.47 + hash(cell, 11.0) * 0.06);
    let turn = hash(cell, 13.0) * 6.2832 + frame.time * (1.0 + hash(cell, 17.0) * 3.0);
    let d = local - middle;
    let q = vec2<f32>(d.x * cos(turn) + d.y * sin(turn), -d.x * sin(turn) + d.y * cos(turn));
    let extent = vec2<f32>(0.16, 0.08) * (0.5 + params.size / 100.0 * 1.1);
    // a pixel, in cells, for the edge's softening; never more than a fiftieth of a cell
    let px = min(ROWS / frame.size.y, 0.02);
    let inside = (1.0 - smoothstep(extent.x - px, extent.x + px, abs(q.x))) * (1.0 - smoothstep(extent.y - px, extent.y + px, abs(q.y)));
    var colours = array<vec3<f32>, 6>(
        vec3<f32>(1.0, 0.3, 0.4),
        vec3<f32>(1.0, 0.82, 0.2),
        vec3<f32>(0.3, 0.8, 1.0),
        vec3<f32>(0.45, 0.95, 0.45),
        vec3<f32>(0.75, 0.45, 1.0),
        vec3<f32>(1.0, 0.6, 0.25),
    );
    // the side turned away is darker, as paper is
    let paper = colours[min(i32(hash(cell, 19.0) * 6.0), 5)] * (0.7 + 0.3 * abs(cos(turn)));
    return vec4<f32>(mix(c.rgb, paper, inside), max(c.a, inside));
}
