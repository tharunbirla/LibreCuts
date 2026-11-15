struct Params { amount: f32, speed: f32 }

// A band of light sweeps across the picture on a slant every few seconds, as
// a shine runs across glass, laid on as light by the amount.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let period = 3.0 - params.speed / 100.0 * 2.0;
    let phase = fract(frame.time / period);
    let s = uv.x + uv.y * 0.5 - (phase * 2.4 - 0.7);
    let band = exp(-s * s / (2.0 * 0.06 * 0.06));
    return vec4<f32>(c.rgb + vec3<f32>(1.0, 0.97, 0.9) * band * 0.8 * params.amount / 100.0, c.a);
}
