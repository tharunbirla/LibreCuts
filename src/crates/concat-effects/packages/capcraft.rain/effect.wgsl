struct Params { amount: f32, speed: f32 }

// Rain: thin streaks falling at a slant in two layers, the near ones faster,
// over the picture made a shade darker and cooler by the amount. A streak
// keeps to the middle of its narrow cell.
fn streaks(uv: vec2<f32>, rows: f32, fall: f32, seed: f32) -> f32 {
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(rows * aspect * 3.0), 1.0);
    let p = vec2<f32>(uv.x + uv.y * 0.15, uv.y);
    let at = vec2<f32>(p.x * cols, p.y * rows - frame.time * fall);
    let cell = floor(at);
    if (hash(cell, seed) >= params.amount / 100.0 * 0.5) {
        return 0.0;
    }
    let d = at - cell - vec2<f32>(0.5, 0.3 + hash(cell, seed + 1.0) * 0.4);
    return exp(-abs(d.x) / 0.06) * (1.0 - smoothstep(0.0, 0.25, abs(d.y)));
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let s = 1.5 + params.speed / 100.0 * 4.0;
    let r = streaks(uv, 6.0, s, 5.0) * 0.5 + streaks(uv, 4.0, s * 1.5, 9.0) * 0.7;
    let k = params.amount / 100.0;
    let base = c.rgb * (1.0 - 0.15 * k) * vec3<f32>(0.95, 0.98, 1.05);
    return vec4<f32>(base + vec3<f32>(0.8, 0.85, 0.9) * r, c.a);
}
