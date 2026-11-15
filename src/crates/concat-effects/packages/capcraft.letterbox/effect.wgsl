struct Params { size: f32 }

// A bar `size` per cent of the picture's height at the top and at the
// bottom, solid black.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let s = params.size * 0.01;
    if (uv.y < s || uv.y > 1.0 - s) {
        return vec4<f32>(0.0, 0.0, 0.0, 1.0);
    }
    return sample(uv);
}
