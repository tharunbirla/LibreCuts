struct Params { amount: f32 }

// Black and white warmed to old gold, hard and glowing.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = mono(c.rgb, vec3<f32>(0.4, 0.45, 0.15));
    out = s_curve(out, 0.45);
    out = out * vec3<f32>(1.06, 0.98, 0.84);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
