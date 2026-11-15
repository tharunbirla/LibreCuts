struct Params { amount: f32 }

// Each channel turned over in the display encoding, where white and black
// trade places as the eye expects; a level past white goes below black.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    return vec4<f32>(mix(c.rgb, vec3<f32>(1.0) - c.rgb, params.amount * 0.01), c.a);
}
