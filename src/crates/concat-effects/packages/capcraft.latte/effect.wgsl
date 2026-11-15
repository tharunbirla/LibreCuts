struct Params { amount: f32 }

// Soft and creamy: warm, lifted blacks and gentle colour, for cafe tables.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 5600.0);
    out = saturation(out, 0.85);
    out = matte(out, 0.06, 0.97);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
