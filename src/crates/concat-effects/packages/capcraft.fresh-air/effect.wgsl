struct Params { amount: f32 }

// A clear, bright day: a touch cooler, greens and blues livelier.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 7000.0);
    out = hsl_band(out, 160.0, 90.0, 0.0, 1.15, 1.02);
    out = vibrance(out, 0.2);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
