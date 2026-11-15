struct Params { amount: f32 }

// Ten throws a second: some of eighteen bands torn sideways, and red and
// blue split apart by a share of the width, both by `amount`.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let k = params.amount * 0.01;
    let tick = floor(frame.time * 10.0);
    let band = floor(uv.y * 18.0);
    let torn = step(0.6, hash(vec2<f32>(band, tick), 11.0));
    let shift = (hash(vec2<f32>(band, tick), 13.0) - 0.5) * 0.12 * k * torn;
    let split = vec2<f32>(0.012 * k * (0.5 + hash(vec2<f32>(tick, 0.0), 17.0)), 0.0);
    let p = vec2<f32>(uv.x + shift, uv.y);
    let g = sample(clamp(p, vec2<f32>(0.0), vec2<f32>(1.0)));
    let r = sample(clamp(p + split, vec2<f32>(0.0), vec2<f32>(1.0))).r;
    let b = sample(clamp(p - split, vec2<f32>(0.0), vec2<f32>(1.0))).b;
    return vec4<f32>(r, g.g, b, g.a);
}
