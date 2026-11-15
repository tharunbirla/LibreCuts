struct Params { amount: f32, speed: f32 }

// Snow falls across the picture in three layers, the near flakes bigger and
// faster, each drifting a little from side to side as it falls. A flake keeps
// to the middle of its cell.
fn flakes(uv: vec2<f32>, rows: f32, fall: f32, seed: f32, size: f32) -> f32 {
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let cols = max(round(rows * aspect), 1.0);
    let at = vec2<f32>(uv.x * cols, uv.y * rows - frame.time * fall);
    let cell = floor(at);
    if (hash(cell, seed) >= params.amount / 100.0 * 0.7) {
        return 0.0;
    }
    let sway = sin(frame.time * 1.3 + hash(cell, seed + 1.0) * 6.2832) * 0.1;
    let middle = vec2<f32>(0.5 + (hash(cell, seed + 2.0) - 0.5) * 0.3 + sway, 0.5 + (hash(cell, seed + 3.0) - 0.5) * 0.3);
    let d = at - cell - middle;
    return exp(-dot(d, d) / (2.0 * size * size));
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let s = 0.3 + params.speed / 100.0 * 1.5;
    let f = flakes(uv, 14.0, s, 3.0, 0.05) * 0.6 + flakes(uv, 9.0, s * 1.6, 7.0, 0.06) * 0.8 + flakes(uv, 5.0, s * 2.4, 11.0, 0.07);
    return vec4<f32>(c.rgb + vec3<f32>(f * 1.2), c.a);
}
