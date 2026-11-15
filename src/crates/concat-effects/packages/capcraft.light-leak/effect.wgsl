struct Params { amount: f32, speed: f32 }

// Light getting in at the edges: a big orange glow sliding up and down past
// the left side, a smaller red one past the right, each swelling and
// fading as it goes, and a faint yellow one drifting along the top. The
// glows are light, laid over the picture.

/// How much of a glow of width `r` falls `d` from its middle.
fn soft(d: vec2<f32>, r: f32) -> f32 {
    return exp(-dot(d, d) / (2.0 * r * r));
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    let t = frame.time * (0.1 + params.speed / 100.0 * 0.6);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let p = vec2<f32>(uv.x * aspect, uv.y);
    let a = vec2<f32>(-0.1 * aspect, 0.5 + 0.45 * sin(t));
    let b = vec2<f32>(1.1 * aspect, 0.5 + 0.45 * sin(t * 0.7 + 2.0));
    let g = vec2<f32>(aspect * (0.5 + 0.45 * sin(t * 0.5 + 4.0)), -0.15);
    let glow = vec3<f32>(1.0, 0.45, 0.12) * soft(p - a, 0.45) * (0.7 + 0.3 * sin(t * 2.3))
        + vec3<f32>(1.0, 0.15, 0.1) * soft(p - b, 0.35) * (0.7 + 0.3 * sin(t * 1.9 + 1.0))
        + vec3<f32>(1.0, 0.85, 0.4) * soft(p - g, 0.4) * 0.6;
    return vec4<f32>(c.rgb + glow * 1.2 * k, c.a);
}
