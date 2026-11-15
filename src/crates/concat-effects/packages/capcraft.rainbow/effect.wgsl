struct Params { amount: f32, speed: f32 }

// Hues turned by an angle that runs once round the wheel across the
// picture's diagonal and rolls along it at `speed` turns a second, mixed
// over the picture by `amount`; a grey has no hue and stays grey.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let degrees = 360.0 * fract(0.6 * uv.x + 0.4 * uv.y - frame.time * params.speed);
    let turned = hue_rotate(c.rgb, degrees);
    return vec4<f32>(mix(c.rgb, turned, params.amount * 0.01), c.a);
}
