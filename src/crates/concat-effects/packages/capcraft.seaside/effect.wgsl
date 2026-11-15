struct Params { amount: f32 }

// Clear, cool light off the water: blues and cyans richer, highlights a touch cooler.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = vibrance(c.rgb, 0.35);
    out = hsl_band(out, 200.0, 70.0, 0.0, 1.25, 1.03);
    out = split_tone(out, vec3<f32>(0.0, 0.02, 0.05), vec3<f32>(-0.03, 0.01, 0.04), 1.0);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
