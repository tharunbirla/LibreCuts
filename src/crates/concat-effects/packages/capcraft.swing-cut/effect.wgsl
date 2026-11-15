struct Params { blur: f32 }

// A fast pan to the left: the outgoing shot goes off and the incoming
// comes on beside it, smeared along the pan most at its fastest.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let p = smoothstep(0.0, 1.0, progress);
    let spread = params.blur / 100.0 * 0.12 * sin(3.14159265 * progress);
    var c = vec4<f32>(0.0);
    for (var i = 0; i < 7; i = i + 1) {
        let x = uv.x + p + (f32(i) / 6.0 - 0.5) * spread;
        let a = from_at(vec2<f32>(clamp(x, 0.0, 1.0), uv.y));
        let b = to_at(vec2<f32>(clamp(x - 1.0, 0.0, 1.0), uv.y));
        c = c + select(b, a, x < 1.0);
    }
    return c / 7.0;
}
