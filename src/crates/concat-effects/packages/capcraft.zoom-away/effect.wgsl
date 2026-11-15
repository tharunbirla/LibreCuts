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

// The clip pushes in about its middle over its last moments.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let z = mix(1.5, 1.0, outro());
    let s = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / z;
    return sample(clamp(s, vec2<f32>(0.0), vec2<f32>(1.0)));
}
