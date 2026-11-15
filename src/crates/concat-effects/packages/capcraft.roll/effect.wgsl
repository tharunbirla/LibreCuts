struct Params { turn: f32 }

// The picture rolls round about its middle, pushing in so no edge shows,
// and comes back upright on the incoming shot: the outgoing turns away over
// the first half, the incoming turns in over the second.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let late = p >= 0.5;
    let a = select(p, p - 1.0, late) * 3.14159265 * (0.5 + params.turn / 100.0);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let z = 1.0 + 0.8 * sin(3.14159265 * p);
    let d = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    let c = cos(-a);
    let s = sin(-a);
    let r = vec2<f32>(d.x * c - d.y * s, d.x * s + d.y * c) / z;
    let q = r / vec2<f32>(aspect, 1.0) + vec2<f32>(0.5);
    let outgoing = from_at(q);
    let incoming = to_at(q);
    return select(outgoing, incoming, late);
}
