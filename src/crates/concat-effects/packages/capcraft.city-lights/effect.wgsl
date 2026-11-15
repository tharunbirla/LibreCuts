struct Params { amount: f32 }

// Night streets: lamps warmed and lifted, shadows cooled to blue.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = split_tone(c.rgb, vec3<f32>(-0.04, -0.01, 0.07), vec3<f32>(0.08, 0.04, -0.03), 1.0);
    out = vibrance(out, 0.25);
    out = film_curve(out, 0.3, 0.2);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
