struct Params { shade: f32 }

// The outgoing shot splits across the middle, its top half sliding up and
// its bottom half down off the frame, the incoming one showing between them,
// in their shadow at first.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let shift = p * 0.5;
    let top = uv.y < 0.5;
    let sy = select(uv.y - shift, uv.y + shift, top);
    let covered = select(sy >= 0.5, sy < 0.5, top);
    let a = from_at(vec2<f32>(uv.x, clamp(sy, 0.0, 1.0)));
    let b = to_at(uv);
    let gap = abs(uv.y - 0.5) / max(shift, 1e-4);
    let shaded = vec4<f32>(b.rgb * (1.0 - params.shade / 100.0 * (1.0 - clamp(gap, 0.0, 1.0)) * (1.0 - p)), b.a);
    return select(shaded, a, covered);
}
