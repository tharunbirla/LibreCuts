struct Params { span: f32 }

/// How far the intro has come, eased: nothing at the clip's first frame, all
/// of it once the duration has passed.
fn intro() -> f32 {
    return smoothstep(0.0, 1.0, clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0));
}

// The clip rises out of black over its first moments.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    return vec4<f32>(c.rgb * intro(), c.a);
}
