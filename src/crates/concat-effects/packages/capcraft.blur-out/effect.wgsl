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

// The clip drifts out of focus as it ends: twenty-five taps drawn apart over
// a few hundredths of the frame.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let spread = (1.0 - outro()) * 0.015;
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let step = vec2<f32>(spread / aspect, spread);
    var acc = vec4<f32>(0.0);
    for (var i = -2; i <= 2; i = i + 1) {
        for (var j = -2; j <= 2; j = j + 1) {
            acc = acc + sample(clamp(uv + vec2<f32>(f32(i), f32(j)) * step, vec2<f32>(0.0), vec2<f32>(1.0)));
        }
    }
    return acc / 25.0;
}
