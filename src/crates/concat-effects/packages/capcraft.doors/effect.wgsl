struct Params { shadow: f32 }

// Doors: the outgoing shot splits down the middle and its two halves slide
// apart, the incoming one behind them darkened a little near their edges.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let d = p * 0.5;
    let left = uv.x < 0.5;
    let src = select(uv.x - d, uv.x + d, left);
    if ((left && src < 0.5) || (!left && src > 0.5)) {
        return from_at(vec2<f32>(src, uv.y));
    }
    let edge = min(0.5 + d - uv.x, uv.x - (0.5 - d));
    let shade = 1.0 - params.shadow / 100.0 * 0.5 * exp(-max(edge, 0.0) * 20.0);
    let c = to_at(uv);
    return vec4<f32>(c.rgb * shade, c.a);
}
