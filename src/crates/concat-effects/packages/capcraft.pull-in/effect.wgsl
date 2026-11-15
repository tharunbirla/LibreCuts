struct Params { dim: f32 }

// The incoming shot grows from a small frame in the middle to the whole
// picture, over the outgoing one, which darkens behind it as it goes.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let s = mix(0.15, 1.0, p);
    let q = (uv - vec2<f32>(0.5)) / s + vec2<f32>(0.5);
    let inside = q.x >= 0.0 && q.x <= 1.0 && q.y >= 0.0 && q.y <= 1.0;
    let a = from_at(uv);
    let b = to_at(clamp(q, vec2<f32>(0.0), vec2<f32>(1.0)));
    let behind = vec4<f32>(a.rgb * (1.0 - params.dim / 100.0 * 0.7 * p), a.a);
    return select(behind, b, inside);
}
