struct Params { amount: f32 }

// Chalky, dreamy colour: faded, softened and blushed pink in the light.
// Mixed in by the amount, so none of it is as shot.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    var out = saturation(c.rgb, 0.7);
    out = fade(out, 0.1);
    out = split_tone(out, vec3<f32>(0.01, 0.0, 0.03), vec3<f32>(0.04, 0.0, 0.02), 1.0);
    return vec4<f32>(mix(c.rgb, out, k), c.a);
}
