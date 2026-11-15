struct Params { amount: f32 }

// Gentle black and white, the blacks lifted to a soft grey like an old print.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = mono(c.rgb, vec3<f32>(0.3, 0.5, 0.2));
    out = matte(out, 0.08, 0.95);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
