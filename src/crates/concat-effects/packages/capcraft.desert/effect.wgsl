struct Params { amount: f32 }

// Dry heat: sandy, sun-bleached highlights and the blues drained out.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = hsl_band(c.rgb, 220.0, 80.0, 0.0, 0.55, 1.0);
    out = split_tone(out, vec3<f32>(0.02, 0.0, -0.02), vec3<f32>(0.07, 0.04, -0.05), 1.0);
    out = matte(out, 0.03, 0.97);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
