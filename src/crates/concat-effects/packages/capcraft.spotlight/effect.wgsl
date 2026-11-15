struct Params { amount: f32, speed: f32 }

// A spotlight sweeps the picture in a slow figure of eight, the rest falling
// into shadow by the amount, a warm rim round the pool of light.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let t = frame.time * (0.2 + params.speed / 100.0 * 0.8);
    let centre = vec2<f32>(0.5 + 0.3 * sin(t), 0.5 + 0.2 * sin(2.0 * t));
    let d = length((uv - centre) * vec2<f32>(aspect, 1.0));
    let radius = 0.28;
    let pool = 1.0 - smoothstep(radius * 0.7, radius, d);
    let k = params.amount / 100.0;
    let shade = mix(1.0 - 0.85 * k, 1.0 + 0.15 * k, pool);
    let rim = exp(-pow((d - radius * 0.85) / 0.03, 2.0)) * 0.15 * k;
    return vec4<f32>(c.rgb * shade + vec3<f32>(1.0, 0.85, 0.6) * rim, c.a);
}
