struct Params { amount: f32, speed: f32 }

// Dreamy: the picture softened by a gentle glow of its own blur screened on,
// lifted a little and washed with a pastel tint that drifts between pink and
// lilac, all by the amount.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let t = texel() * 3.0;
    let blur = (sample(uv + vec2<f32>(t.x, 0.0)) + sample(uv - vec2<f32>(t.x, 0.0))
        + sample(uv + vec2<f32>(0.0, t.y)) + sample(uv - vec2<f32>(0.0, t.y))
        + sample(uv + t) + sample(uv - t)
        + sample(uv + vec2<f32>(t.x, -t.y)) + sample(uv + vec2<f32>(-t.x, t.y))) / 8.0;
    let glow = screen(c.rgb, blur.rgb * 0.6);
    let h = 0.5 + 0.5 * sin(frame.time * (0.3 + params.speed / 100.0));
    let tint = mix(vec3<f32>(1.0, 0.85, 0.92), vec3<f32>(0.9, 0.85, 1.0), h);
    let k = params.amount / 100.0;
    return vec4<f32>(mix(c.rgb, glow * tint + vec3<f32>(0.04), k), c.a);
}
