struct Params { spread: f32 }

// A dissolve that lets the outgoing shot's brightest parts through to the
// incoming one first and its shadows last, a soft spread between.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let a = from_at(uv);
    let b = to_at(uv);
    let w = max(params.spread / 100.0 * 0.6, 0.02);
    let k = 1.0 - clamp(luma(a.rgb), 0.0, 1.0);
    let m = smoothstep(k, k + w, progress * (1.0 + w));
    return mix(a, b, m);
}
