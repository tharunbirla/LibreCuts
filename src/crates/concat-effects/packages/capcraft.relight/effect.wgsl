struct Params { key: vec4<f32>, fill: vec4<f32>, angle: f32, strength: f32, softness: f32, spot: f32 }

// The picture lit again. Across the frame, along the way the key light
// shines, the light runs from the fill's colour on the far side to the
// key's on the near one, the change as soft as asked; a little above the
// middle a soft round of light is added. The light shades the picture:
// a light of middle grey leaves it as it was, a brighter one lifts it in
// its colour and a darker one sinks it, as much as the strength says.

/// How wide the round of light is, in frame heights.
const ROUND: f32 = 0.2;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let p = (uv - vec2<f32>(0.5)) * vec2<f32>(aspect, 1.0);
    // towards where the key light comes from: the left at 0, the top at 90
    let a = radians(params.angle);
    let towards = vec2<f32>(-cos(a), -sin(a));
    let soft = 0.05 + params.softness / 100.0 * 0.6;
    let near = smoothstep(-soft, soft, dot(p, towards));
    let light = mix(params.fill.rgb, params.key.rgb, near);
    let q = p - vec2<f32>(0.0, -0.08);
    let glow = exp(-dot(q, q) / (2.0 * ROUND * ROUND)) * params.spot / 100.0;
    let k = params.strength / 100.0;
    let shade = mix(vec3<f32>(1.0), light * 2.0, k) + vec3<f32>(glow * 0.6 * k);
    return vec4<f32>(c.rgb * shade, c.a);
}
