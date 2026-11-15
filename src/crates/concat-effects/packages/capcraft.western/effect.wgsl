struct Params { amount: f32 }

// Dusty frontier light: warm and dry, the colour drained, the blacks lifted.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 4800.0);
    out = saturation(out, 0.7);
    out = matte(out, 0.05, 0.96);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
