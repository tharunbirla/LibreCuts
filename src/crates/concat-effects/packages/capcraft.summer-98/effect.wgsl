struct Params { amount: f32 }

// A holiday print from the late nineties: warm, saturated, faded at the ends.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 5800.0);
    out = saturation(out, 1.2);
    out = matte(out, 0.07, 0.94);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
