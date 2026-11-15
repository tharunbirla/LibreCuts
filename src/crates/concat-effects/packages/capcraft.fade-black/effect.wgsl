struct Params { hold: f32 }

// The outgoing shot fades to black by the middle, holds there as long as
// asked, and the incoming one fades up out of it.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let h = params.hold / 100.0 * 0.5;
    let shade = vec3<f32>(0.0);
    if (progress < 0.5) {
        let c = from_at(uv);
        return vec4<f32>(mix(c.rgb, shade, smoothstep(0.0, 0.5 - h * 0.5, progress)), c.a);
    }
    let c = to_at(uv);
    return vec4<f32>(mix(shade, c.rgb, smoothstep(0.5 + h * 0.5, 1.0, progress)), c.a);
}
