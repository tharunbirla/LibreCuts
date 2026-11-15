struct Params { amount: f32 }

// Bright and airy: lighter, colour softened, a cool hint in the shadows.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = c.rgb * 1.06 + vec3<f32>(0.02);
    out = saturation(out, 0.85);
    out = split_tone(out, vec3<f32>(-0.01, 0.0, 0.03), vec3<f32>(0.0, 0.0, 0.0), 1.0);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
