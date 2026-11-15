struct Params { amount: f32, speed: f32 }

// Two sines a side at speeds that never line up, so the jolts never fall
// into a pattern; `amount` pixels at most, the picture pushed in by twice
// that so no edge comes into view.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let t = frame.time * params.speed;
    let wobble = vec2<f32>(
        sin(t * 7.3) + 0.5 * sin(t * 13.1 + 1.7),
        cos(t * 6.1) + 0.5 * sin(t * 11.3 + 0.4),
    ) / 1.5;
    let reach = vec2<f32>(params.amount) / frame.size;
    let zoom = 1.0 + 2.0 * max(reach.x, reach.y);
    let src = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / zoom + wobble * reach;
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
