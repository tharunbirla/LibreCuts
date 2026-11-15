// The right half read from the left half, turned over at the middle.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    return sample(vec2<f32>(0.5 - abs(uv.x - 0.5), uv.y));
}
