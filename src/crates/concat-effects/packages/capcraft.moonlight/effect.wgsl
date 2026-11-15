struct Params { amount: f32 }

// Silvery moonlit blue, the colour nearly gone, the dark kept soft.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = saturation(c.rgb, 0.25);
    out = out * vec3<f32>(0.82, 0.92, 1.12);
    out = matte(out, 0.04, 0.95);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
