struct Params { rate: f32 }

// Scaled up about the middle by `rate` per cent for every second since the
// clip began, and never past three times, timed to the clip so it plays
// the same wherever the clip sits.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let zoom = min(1.0 + params.rate * 0.01 * frame.clip_time, 3.0);
    let src = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / zoom;
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
