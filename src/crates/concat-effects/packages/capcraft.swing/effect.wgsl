struct Params { angle: f32, speed: f32 }

// Turned about the middle by up to `angle` degrees and back, in square
// pixels whatever the picture's shape, and scaled up by what the turn
// would otherwise bare at the corners.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let a = radians(params.angle) * sin(frame.time * params.speed * 3.14159265);
    let aspect = frame.size.x / frame.size.y;
    let p = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    let c = cos(a);
    let s = sin(a);
    let zoom = abs(c) + abs(s) * max(aspect, 1.0 / aspect);
    let turned = vec2<f32>(c * p.x + s * p.y, -s * p.x + c * p.y) / zoom;
    let src = turned / vec2<f32>(aspect, 1.0) + vec2<f32>(0.5);
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
