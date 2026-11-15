struct Params { span: f32 }

/// How far the intro has come, eased: nothing at the clip's first frame, all
/// of it once the duration has passed.
fn intro() -> f32 {
    return smoothstep(0.0, 1.0, clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0));
}

// The clip starts close in about its middle and pulls back to its full frame.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let z = mix(1.5, 1.0, intro());
    let s = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / z;
    return sample(clamp(s, vec2<f32>(0.0), vec2<f32>(1.0)));
}
