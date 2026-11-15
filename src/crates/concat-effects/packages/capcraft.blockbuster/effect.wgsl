struct Params { amount: f32 }

// The big-screen look: teal shadows, warm skin and highlights, contrast pushed.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = split_tone(c.rgb, vec3<f32>(-0.05, 0.03, 0.07), vec3<f32>(0.07, 0.03, -0.05), 1.0);
    out = s_curve(out, 0.35);
    out = vibrance(out, 0.15);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
