struct Params { softness: f32 }

// A diamond opens from the middle onto the incoming shot: the frame's
// distance from its middle across and down added together, in frame heights,
// grown until it holds the corners.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let d = abs((uv.x - 0.5) * aspect) + abs(uv.y - 0.5);
    let reach = p * (0.5 * aspect + 0.5 + 0.1);
    let soft = max(params.softness / 100.0 * 0.12, 0.004);
    let m = smoothstep(-soft, soft, d - reach);
    return mix(to_at(uv), from_at(uv), m);
}
