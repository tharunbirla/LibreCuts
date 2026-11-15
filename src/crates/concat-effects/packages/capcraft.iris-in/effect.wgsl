struct Params { span: f32 }

/// How far the intro has come, eased: nothing at the clip's first frame, all
/// of it once the duration has passed.
fn intro() -> f32 {
    return smoothstep(0.0, 1.0, clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0));
}

// A circle opens from the middle out of black onto the clip, measured in
// frame heights so it stays round, grown until it holds the corners.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let d = length((uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0));
    let r = intro() * (0.5 * sqrt(aspect * aspect + 1.0) + 0.02);
    let m = smoothstep(r - 0.01, r + 0.01, d);
    return vec4<f32>(c.rgb * (1.0 - m), c.a);
}
