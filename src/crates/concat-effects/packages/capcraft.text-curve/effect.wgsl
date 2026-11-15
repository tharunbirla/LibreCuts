struct Params { bend: f32 }

// A title bent onto an arc. The painted title is centred on the frame, so
// the frame's middle is the middle of its line: the arc passes through it,
// round a centre straight below it (above, bent the other way). Each pixel
// is read from where it lies along the arc and how far off it, as if the
// arc were unrolled into the straight line the title was painted on, so the
// letters turn to follow the curve. Distances are in frame heights; at full
// bend the arc's radius is a fifth of the frame's height.

/// Beyond this turn either way the arc would come round on itself.
const LIMIT: f32 = 3.1;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let b = params.bend / 100.0;
    if (abs(b) < 0.005) {
        return sample(uv);
    }
    let aspect = frame.size.x / frame.size.y;
    // the pixel in frame heights from the frame's middle, y down
    let q = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    let side = sign(b);
    let radius = 0.2 / abs(b);
    let d = q - vec2<f32>(0.0, side * radius);
    let turn = atan2(d.x, -side * d.y);
    let source_uv = vec2<f32>(turn * radius / aspect, side * (radius - length(d))) + vec2<f32>(0.5);
    let c = sample(source_uv);
    let inside = abs(turn) < LIMIT && all(source_uv >= vec2<f32>(0.0)) && all(source_uv <= vec2<f32>(1.0));
    return select(vec4<f32>(c.rgb, 0.0), c, inside);
}
