struct Params { span: f32 }

/// How far the intro has come, eased: nothing at the clip's first frame, all
/// of it once the duration has passed.
fn intro() -> f32 {
    return smoothstep(0.0, 1.0, clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0));
}

// Black bars above and below part from the middle line to show the clip.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let edge = 0.5 * intro();
    let m = smoothstep(edge - 0.003, edge + 0.003, abs(uv.y - 0.5));
    return vec4<f32>(c.rgb * (1.0 - m), c.a);
}
