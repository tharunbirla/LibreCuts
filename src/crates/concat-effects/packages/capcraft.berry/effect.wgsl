struct Params { amount: f32 }

// Fruit at its ripest: reds and magentas richer, a cool, clean background.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = hsl_band(c.rgb, 340.0, 60.0, 0.0, 1.35, 1.0);
    out = vibrance(out, 0.2);
    out = tint_midtones(out, vec3<f32>(0.02, -0.02, 0.01), 1.0);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
