struct Params { strength: f32 }

// Both shots ripple sideways in rolling waves, strongest at the middle of
// the cut, while the outgoing melts into the incoming.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let bump = sin(3.14159265 * progress);
    let amp = params.strength / 100.0 * 0.06 * bump;
    let s = vec2<f32>(uv.x + sin(uv.y * 24.0 + progress * 12.0) * amp, uv.y);
    let m = smoothstep(0.3, 0.7, progress);
    return mix(from_at(s), to_at(s), m);
}
