struct Params { amount: f32, speed: f32 }

// A sharp swell at each beat - a raised cosine to the sixth power, so most
// of the time is spent at rest - scaling the picture up by `amount` per
// cent about its middle.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let beat = pow(0.5 + 0.5 * cos(frame.time * params.speed * 6.28318531), 6.0);
    let zoom = 1.0 + params.amount * 0.01 * beat;
    let src = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / zoom;
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
