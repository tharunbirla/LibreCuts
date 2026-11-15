struct Params { amount: f32, speed: f32 }

// Each row pushed sideways along a sine down the picture that travels at
// `speed`, by `amount` pixels at most, and the picture pushed in so the
// edges stay covered.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let reach = params.amount / frame.size.x;
    let zoom = 1.0 + 2.0 * reach;
    let p = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / zoom;
    let src = vec2<f32>(p.x + reach * sin(uv.y * 12.0 + frame.time * params.speed * 6.28318531), p.y);
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
