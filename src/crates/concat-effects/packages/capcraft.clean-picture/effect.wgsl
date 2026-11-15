struct Params { strength: f32 }

// Each pixel averaged with the 24 around it, every neighbour weighed down
// the farther it lies and the more its colour differs from the pixel's, so
// grain and speckle even out while an edge - a large difference - keeps
// its line. `strength` lets more different neighbours count, and spreads
// them wider.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let s = clamp(params.strength / 100.0, 0.0, 1.0);
    if (s <= 0.0) {
        return c;
    }
    let t = texel() * (1.0 + s);
    let range = 0.02 + 0.16 * s;
    var sum = vec4<f32>(0.0);
    var weight = 0.0;
    for (var y: i32 = -2; y <= 2; y++) {
        for (var x: i32 = -2; x <= 2; x++) {
            let n = sample_premultiplied(uv + vec2<f32>(f32(x), f32(y)) * t);
            let d = n.rgb / max(n.a, 1e-6) - c.rgb;
            let w = exp(-f32(x * x + y * y) / 4.5 - dot(d, d) / (2.0 * range * range));
            sum += n * w;
            weight += w;
        }
    }
    return vec4<f32>(sum.rgb / max(sum.a, 1e-6), sum.a / max(weight, 1e-6));
}
