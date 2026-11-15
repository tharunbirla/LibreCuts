struct Params { amount: f32 }

// Warm, soft portrait light, skin kept true and the contrast rounded.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    let keep = 1.0 - skin_mask(c.rgb) * 0.7;
    var out = tint_midtones(c.rgb, vec3<f32>(0.04, 0.01, -0.03), keep);
    out = film_curve(out, 0.2, 0.3);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
