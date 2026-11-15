struct Params { amount: f32, speed: f32 }

// Each flash climbs to white in the first twelfth of its period and dies
// away over the rest; the picture is mixed towards white by `amount` of it.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let phase = fract(frame.time * params.speed);
    let flash = smoothstep(0.0, 0.08, phase) * exp(-max(phase - 0.08, 0.0) * 8.0);
    let k = params.amount * 0.01 * flash;
    return vec4<f32>(mix(c.rgb, vec3<f32>(1.0), k), c.a);
}
