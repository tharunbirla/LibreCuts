struct Params { amount: f32 }

// Hard black and white through a red filter: dark skies, bright skin, deep blacks.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = mono(c.rgb, vec3<f32>(0.6, 0.3, 0.1));
    out = s_curve(out, 0.6);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
