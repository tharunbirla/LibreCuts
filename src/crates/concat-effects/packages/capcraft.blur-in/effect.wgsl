struct Params { span: f32 }

/// How far the intro has come, eased: nothing at the clip's first frame, all
/// of it once the duration has passed.
fn intro() -> f32 {
    return smoothstep(0.0, 1.0, clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0));
}

// The clip comes into focus from a soft blur: twenty-five taps spread over
// a few hundredths of the frame at first, drawn together as it sharpens.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let spread = (1.0 - intro()) * 0.015;
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
