struct Params { amount: f32, speed: f32 }

// Dark for the second half of every period, by `amount` per cent of the
// light.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let off = step(0.5, fract(frame.time * params.speed));
    return vec4<f32>(c.rgb * (1.0 - params.amount * 0.01 * off), c.a);
}
