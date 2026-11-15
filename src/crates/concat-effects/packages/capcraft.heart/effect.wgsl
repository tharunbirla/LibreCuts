struct Params { softness: f32 }

// A heart opens from the middle to show the incoming shot. The heart is the
// curve (x^2 + y^2 - 1)^3 = x^2 y^3, measured in frame heights so it keeps its
// shape on any frame, and grown until it holds the corners.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let size = max(p * 3.4, 1e-4);
    // y up, the heart's point down, a little below the middle
    let q = vec2<f32>((uv.x - 0.5) * aspect, 0.55 - uv.y) / size * 1.3;
    let r = dot(q, q) - 1.0;
    let f = r * r * r - q.x * q.x * q.y * q.y * q.y;
    let soft = max(params.softness / 100.0 * 0.4, 0.02);
    let m = smoothstep(-soft, soft, f);
    return mix(to_at(uv), from_at(uv), m);
}
