struct Params { tone: f32, grain: f32 }

// Drawn in the display encoding, as a print is judged by eye: the grey of
// each pixel tinted brown by `tone`, grain thrown afresh 24 times a second,
// the light breathing a little, the corners darkened, and a pale scratch
// down the frame on some of the twelve throws a second.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let grey = luma(c.rgb);
    var rgb = mix(c.rgb, vec3<f32>(grey) * vec3<f32>(1.08, 0.88, 0.62), params.tone * 0.01);
    let n = hash(floor(uv * frame.size), floor(frame.time * 24.0)) - 0.5;
    rgb = rgb + vec3<f32>(n * params.grain * 0.0016);
    rgb = rgb * (1.0 + 0.06 * sin(frame.time * 31.0) * sin(frame.time * 9.0));
    rgb = rgb * (1.0 - 0.5 * smoothstep(0.3, 0.75, length(uv - vec2<f32>(0.5))));
    let tick = floor(frame.time * 12.0);
    let xs = hash(vec2<f32>(tick, 1.0), 5.0);
    let on = step(0.4, hash(vec2<f32>(tick, 2.0), 5.0));
    let line = 1.0 - smoothstep(0.0, 1.2 * texel().x, abs(uv.x - xs));
    rgb = mix(rgb, vec3<f32>(0.9), line * 0.5 * on);
    return vec4<f32>(rgb, c.a);
}
