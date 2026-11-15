struct Params { amount: f32 }

// Club colours: magenta and cyan pushed, the rest dark and glossy.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = hsl_band(c.rgb, 300.0, 70.0, 10.0, 1.4, 1.05);
    out = hsl_band(out, 185.0, 60.0, 0.0, 1.4, 1.05);
    out = s_curve(out, 0.35);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
