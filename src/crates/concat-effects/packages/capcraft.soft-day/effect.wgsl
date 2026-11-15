struct Params { amount: f32 }

// An overcast day made kind: brighter, the colour gentle, nothing harsh.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = c.rgb * 1.04 + vec3<f32>(0.015);
    out = saturation(out, 0.9);
    out = film_curve(out, 0.1, 0.25);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
