struct Params { flash: f32 }

// A push into the outgoing shot, a flash of white at the cut, and the
// incoming shot settling back from close in.
fn transition(uv: vec2<f32>, progress: f32) -> vec4<f32> {
    let late = progress >= 0.5;
    let z = select(1.0 + progress * 1.2, 1.0 + (1.0 - progress) * 1.2, late);
    let s = vec2<f32>(0.5) + (uv - vec2<f32>(0.5)) / z;
    let a = from_at(s);
    let b = to_at(s);
    let pic = select(a, b, late);
    let flash = pow(max(1.0 - abs(progress - 0.5) * 2.0, 0.0), 3.0) * params.flash / 100.0;
    return vec4<f32>(mix(pic.rgb, vec3<f32>(1.0), flash), pic.a);
}
