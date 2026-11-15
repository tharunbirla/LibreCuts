struct Params { amount: f32 }

// Deep, lush greens, the yellows turned toward them, a little more depth.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = hsl_band(c.rgb, 110.0, 80.0, 6.0, 1.3, 0.95);
    out = hsl_band(out, 60.0, 40.0, 10.0, 1.1, 1.0);
    out = s_curve(out, 0.25);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
