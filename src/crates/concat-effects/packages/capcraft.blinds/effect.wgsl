struct Params { count: f32 }

// Blinds: the frame cut into as many slats across as asked, each showing
// the incoming shot from its top down as the cut goes on.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let slat = fract(uv.y * max(params.count, 1.0));
    let edge = 0.03;
    let m = smoothstep(p * (1.0 + 2.0 * edge) - edge, p * (1.0 + 2.0 * edge) + edge, slat);
    return mix(to_at(uv), from_at(uv), m);
}
