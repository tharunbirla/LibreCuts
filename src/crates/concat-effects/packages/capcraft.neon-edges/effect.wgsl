struct Params { amount: f32, speed: f32 }

/// A colour round the rainbow, `h` in turns: red at 0, green a third of the way, blue two thirds.
fn rainbow(h: f32) -> vec3<f32> {
    return clamp(abs(fract(vec3<f32>(h) + vec3<f32>(0.0, 2.0 / 3.0, 1.0 / 3.0)) * 6.0 - 3.0) - 1.0, vec3<f32>(0.0), vec3<f32>(1.0));
}

// Edges traced in neon: where the picture's brightness changes sharply a
// bright line glows, its colour sliding round the rainbow over time and
// across the frame; the rest is darkened beneath it by the amount.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let t = texel();
    let l = luma(sample(uv - vec2<f32>(t.x, 0.0)).rgb);
    let r = luma(sample(uv + vec2<f32>(t.x, 0.0)).rgb);
    let u = luma(sample(uv - vec2<f32>(0.0, t.y)).rgb);
    let d = luma(sample(uv + vec2<f32>(0.0, t.y)).rgb);
    let edge = clamp((abs(r - l) + abs(d - u)) * 3.0, 0.0, 1.0);
    let hue = fract(frame.time * (0.05 + params.speed / 100.0 * 0.4) + uv.x * 0.3 + uv.y * 0.2);
    let k = params.amount / 100.0;
    let out = c.rgb * (1.0 - 0.6 * k) + rainbow(hue) * edge * 1.5 * k;
    return vec4<f32>(out, c.a);
}
