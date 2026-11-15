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

// A circle closes on the middle, ending the clip in black: measured in
// frame heights so it stays round, from wide enough to hold the corners.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let d = length((uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0));
    let r = outro() * (0.5 * sqrt(aspect * aspect + 1.0) + 0.02);
    let m = smoothstep(r - 0.01, r + 0.01, d);
    return vec4<f32>(c.rgb * (1.0 - m), c.a);
}
