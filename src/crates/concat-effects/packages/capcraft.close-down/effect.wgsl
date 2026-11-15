struct Params { span: f32 }

/// How much of the clip is still shown, eased: all of it until the last
/// stretch of the duration, nothing at its last frame. With no end known
/// (no clip of its own) the clip is shown whole.
fn outro() -> f32 {
    if (frame.clip_length <= 0.0) {
        return 1.0;
    }
    let left = frame.clip_length - frame.clip_time;
    return smoothstep(0.0, 1.0, clamp(left / max(params.span, 0.05), 0.0, 1.0));
}

// Black bars close in from above and below to meet on the middle line.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let edge = 0.5 * outro();
    let m = smoothstep(edge - 0.003, edge + 0.003, abs(uv.y - 0.5));
    return vec4<f32>(c.rgb * (1.0 - m), c.a);
}
