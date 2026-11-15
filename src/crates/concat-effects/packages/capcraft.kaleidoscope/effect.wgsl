struct Params { segments: f32, speed: f32 }

// Every point's angle about the middle folded into one wedge and mirrored
// at its edge, the wedges turning by `speed` radians a second; distances
// kept in square pixels.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let aspect = frame.size.x / frame.size.y;
    let p = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    let wedge = 6.28318531 / max(round(params.segments), 1.0);
    var a = atan2(p.y, p.x) + frame.time * params.speed;
    a = a - wedge * floor(a / wedge);
    a = min(a, wedge - a);
    let q = vec2<f32>(cos(a), sin(a)) * length(p);
    let src = q / vec2<f32>(aspect, 1.0) + vec2<f32>(0.5);
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
