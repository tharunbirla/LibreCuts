struct Params { strength: f32 }

// Ripples: rings run out from the middle and bend both shots, strongest at
// the middle of the cut, while one dissolves into the other.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let d = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    let r = length(d);
    let swell = 4.0 * progress * (1.0 - progress);
    let bend = sin(r * 40.0 - progress * 25.0) * 0.02 * params.strength / 100.0 * swell;
    let along = select(vec2<f32>(0.0), d / max(r, 1e-4), r > 1e-4);
    let at = clamp(uv + along * vec2<f32>(1.0 / aspect, 1.0) * bend, vec2<f32>(0.0), vec2<f32>(1.0));
    return mix(from_at(at), to_at(at), smoothstep(0.3, 0.7, progress));
}
