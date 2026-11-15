struct Params { amount: f32 }

// The last hour of the day: warm, gold highlights and a rose tint in the shadows.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 5200.0);
    out = split_tone(out, vec3<f32>(0.05, -0.01, 0.04), vec3<f32>(0.08, 0.03, -0.06), 1.0);
    out = film_curve(out, 0.15, 0.25);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
