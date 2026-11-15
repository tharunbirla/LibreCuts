struct Params { angle: f32 }

// The picture turned by `angle` degrees clockwise about its middle - in
// pixels, so it turns true on a picture of any shape - and grown by the
// least that keeps every corner of the frame on the picture.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let t = texel();
    let aspect = t.y / max(t.x, 1e-6);
    let a = radians(params.angle);
    let grow = abs(cos(a)) + max(aspect, 1.0 / aspect) * abs(sin(a));
    var p = uv - vec2<f32>(0.5);
    p.x = p.x * aspect;
    // where this pixel was before the turn: turned back, and nearer the middle
    let q = vec2<f32>(cos(a) * p.x + sin(a) * p.y, -sin(a) * p.x + cos(a) * p.y) / grow;
    let src = vec2<f32>(q.x / aspect, q.y) + vec2<f32>(0.5);
    return sample(clamp(src, vec2<f32>(0.0), vec2<f32>(1.0)));
}
