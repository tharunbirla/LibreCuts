struct Params { color: vec4<f32>, width: f32 }

// A second line round the letters: their coverage spread out by the width -
// the most of it within a circle, read at twenty-four angles on three rings -
// and laid under them in the chosen colour. The width is a share of the
// layer's height, so the line is as thick on the monitor as in the export.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let r = (params.width / 100.0) * 0.06 * frame.size.y;
    if (r < 0.5) {
        return c;
    }
    let px = vec2<f32>(1.0) / frame.size;
    var spread = c.a;
    for (var ring = 1; ring <= 3; ring = ring + 1) {
        let d = r * f32(ring) / 3.0;
        for (var k = 0; k < 24; k = k + 1) {
            let a = f32(k) * 6.2831853 / 24.0;
            let at = uv + vec2<f32>(cos(a), sin(a)) * d * px;
            spread = max(spread, textureSampleLevel(source, source_sampler, at, 0.0).a);
        }
    }
    let g = spread * params.color.a;
    let a = c.a + g * (1.0 - c.a);
    let rgb = (c.rgb * c.a + params.color.rgb * g * (1.0 - c.a)) / max(a, 1e-6);
    return vec4<f32>(rgb, a);
}
