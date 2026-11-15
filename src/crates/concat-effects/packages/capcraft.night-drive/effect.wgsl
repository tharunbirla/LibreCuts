struct Params { amount: f32 }

// City lights after dark: teal shadows, crushed blacks, warm lamps.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = split_tone(c.rgb, vec3<f32>(-0.06, 0.03, 0.08), vec3<f32>(0.07, 0.02, -0.05), 1.0);
    out = film_curve(out, 0.45, 0.2);
    out = saturation(out, 0.9);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
