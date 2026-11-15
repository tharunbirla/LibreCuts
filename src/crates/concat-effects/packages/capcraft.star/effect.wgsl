struct Params { softness: f32 }

// A five-pointed star opens from the middle onto the incoming shot, turning
// a little as it grows. Its edge swings between an inner and an outer radius
// five times round, measured in frame heights so it keeps its shape, and it
// grows until its inner radius holds the corners.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let q = vec2<f32>((uv.x - 0.5) * aspect, 0.5 - uv.y);
    let size = max(p * 3.0, 1e-4);
    let a = atan2(q.y, q.x) + p * 0.6;
    let lobe = fract(a / (6.2831853 / 5.0));
    let edge = size * mix(0.45, 1.0, 1.0 - abs(lobe * 2.0 - 1.0));
    let soft = max(params.softness / 100.0 * 0.12, 0.004);
    let m = smoothstep(-soft, soft, length(q) - edge);
    return mix(to_at(uv), from_at(uv), m);
}
