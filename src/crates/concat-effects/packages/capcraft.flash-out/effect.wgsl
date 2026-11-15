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

// The clip ends in a flash of white that swells over its last moments.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    return vec4<f32>(mix(vec3<f32>(1.0), c.rgb, outro()), c.a);
}
