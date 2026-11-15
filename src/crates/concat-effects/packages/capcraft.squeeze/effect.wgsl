struct Params { depth: f32 }

// Squeeze: the outgoing shot is pressed from both sides towards an upright
// line in the middle and gone, the incoming one showing beside it and dimmed
// a little by the pressed shot's shadow while it goes.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let w = 1.0 - p;
    let x = uv.x - 0.5;
    if (abs(x) < w * 0.5) {
        return from_at(vec2<f32>(0.5 + x / max(w, 1e-4), uv.y));
    }
    let c = to_at(uv);
    let shade = 1.0 - params.depth / 100.0 * 0.6 * w;
    return vec4<f32>(c.rgb * shade, c.a);
}
