struct Params { amount: f32 }

// A small film's soft palette: faded, warm in the light, gentle in contrast.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = fade(c.rgb, 0.08);
    out = split_tone(out, vec3<f32>(0.0, 0.01, 0.03), vec3<f32>(0.05, 0.02, -0.02), 1.0);
    out = saturation(out, 0.85);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
