struct Params { amount: f32 }

// The orange of sodium lamps over everything, the shadows deep.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = tint_midtones(c.rgb, vec3<f32>(0.08, 0.03, -0.06), 1.0);
    out = saturation(out, 0.8);
    out = s_curve(out, 0.25);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
