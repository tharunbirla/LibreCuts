struct Params { amount: f32 }

// Red pulled one way and blue the other, a dark line on every third row
// of pixels, and now and then a band of rows shoved sideways, a new
// throw twelve times a second.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let k = params.amount * 0.01;
    let tick = floor(frame.time * 12.0);
    let band = floor(uv.y * 20.0);
    let jumps = step(0.85, hash(vec2<f32>(band, tick), 7.0));
    let shove = (hash(vec2<f32>(band, tick), 9.0) - 0.5) * 0.08 * k * jumps;
    let src = vec2<f32>(uv.x + shove, uv.y);
    let bleed = vec2<f32>((1.5 + 5.0 * k) / frame.size.x, 0.0);
    let c = sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
    let r = sample(clamp(src - bleed, vec2<f32>(0.0), vec2<f32>(1.0))).r;
    let b = sample(clamp(src + bleed, vec2<f32>(0.0), vec2<f32>(1.0))).b;
    let row = fract(uv.y * frame.size.y / 3.0);
    let line = 1.0 - 0.35 * k * step(row, 0.33);
    return vec4<f32>(vec3<f32>(r, c.g, b) * line, c.a);
}
