struct Params { amount: f32, speed: f32 }

// Three lights - pink, blue and green - each a soft round a third of the
// frame's height across, run round the frame on loops of their own. Where
// one falls the picture takes its colour and a little of it besides, so a
// dark floor still shows the light; elsewhere the picture is dimmed, as a
// room is with its lights down.

/// How wide a light is, in frame heights.
const SPREAD: f32 = 0.15;

/// How much of a light falls `d` from its middle.
fn spot(d: vec2<f32>) -> f32 {
    return exp(-dot(d, d) / (2.0 * SPREAD * SPREAD));
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    let t = frame.time * (0.3 + params.speed / 100.0 * 1.7);
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let p = vec2<f32>(uv.x * aspect, uv.y);
    let a = vec2<f32>(aspect * (0.5 + 0.4 * sin(t * 1.3)), 0.5 + 0.35 * cos(t * 0.9));
    let b = vec2<f32>(aspect * (0.5 + 0.4 * sin(t * 0.8 + 2.1)), 0.5 + 0.35 * cos(t * 1.1 + 1.3));
    let g = vec2<f32>(aspect * (0.5 + 0.4 * sin(t * 1.7 + 4.2)), 0.5 + 0.35 * cos(t * 0.7 + 3.7));
    let light = vec3<f32>(1.0, 0.15, 0.6) * spot(p - a)
        + vec3<f32>(0.15, 0.5, 1.0) * spot(p - b)
        + vec3<f32>(0.3, 1.0, 0.25) * spot(p - g);
    let rgb = c.rgb * (1.0 - 0.5 * k) + (c.rgb + vec3<f32>(0.03)) * light * 3.0 * k;
    return vec4<f32>(rgb, c.a);
}
