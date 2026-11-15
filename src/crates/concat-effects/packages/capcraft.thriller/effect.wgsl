struct Params { amount: f32 }

// Tense and grim: a sickly green cast, colour held down, deep shadows.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = tint_midtones(c.rgb, vec3<f32>(-0.02, 0.03, -0.01), 1.0);
    out = saturation(out, 0.6);
    out = film_curve(out, 0.4, 0.15);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
