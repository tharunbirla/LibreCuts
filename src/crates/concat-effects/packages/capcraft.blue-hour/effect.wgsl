struct Params { amount: f32 }

// Deep blue night, darker overall, the brights kept clear.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = white_balance(c.rgb, 9000.0);
    out = film_curve(out, 0.45, 0.1);
    out = saturation(out, 0.9);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
