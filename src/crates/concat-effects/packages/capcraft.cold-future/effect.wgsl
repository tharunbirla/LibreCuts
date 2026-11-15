struct Params { amount: f32 }

// A cold future: greens and cyans in the shadows, crisp and clinical.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = split_tone(c.rgb, vec3<f32>(-0.04, 0.05, 0.04), vec3<f32>(-0.02, 0.02, 0.03), 1.0);
    out = saturation(out, 0.85);
    out = s_curve(out, 0.3);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
