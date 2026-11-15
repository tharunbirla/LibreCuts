struct Params { strength: f32 }

// A camera shake: both shots jolt about and smear along the way they jolt,
// hardest at the middle, while one dissolves into the other.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let swell = 4.0 * progress * (1.0 - progress);
    let jolt = vec2<f32>(sin(progress * 41.0), cos(progress * 33.0)) * 0.04 * params.strength / 100.0 * swell;
    var from_sum = vec4<f32>(0.0);
    var to_sum = vec4<f32>(0.0);
    for (var i = 0; i < 5; i++) {
        let at = clamp(uv + jolt * (f32(i) / 4.0 - 0.5), vec2<f32>(0.0), vec2<f32>(1.0));
        from_sum += from_at(at);
        to_sum += to_at(at);
    }
    return mix(from_sum / 5.0, to_sum / 5.0, smoothstep(0.35, 0.65, progress));
}
