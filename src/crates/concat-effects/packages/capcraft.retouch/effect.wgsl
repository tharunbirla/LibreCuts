struct Params { soften: f32, brighten: f32 }

// How much a colour looks like skin, 0 to 1: warm (red over green over
// blue), neither grey nor vivid, and not dark. Light past white and deep
// colours are not skin.
fn retouch_skin(c: vec3<f32>) -> f32 {
    if (any(c > vec3<f32>(1.0)) || any(c < vec3<f32>(0.0))) {
        return 0.0;
    }
    let hi = max(c.r, max(c.g, c.b));
    let lo = min(c.r, min(c.g, c.b));
    if (c.r < c.g || c.g < c.b || hi <= 0.0) {
        return 0.0;
    }
    let sat = (hi - lo) / hi;
    // the hue between red and yellow, 0 to 1 across those sixty degrees
    let hue = (c.g - c.b) / max(hi - lo, 1e-4);
    let warm = smoothstep(0.0, 0.15, hue) * (1.0 - smoothstep(0.75, 0.95, hue));
    let mild = smoothstep(0.12, 0.2, sat) * (1.0 - smoothstep(0.6, 0.72, sat));
    let lit = smoothstep(0.2, 0.3, hi);
    return warm * mild * lit;
}

// Skin softened by an edge-keeping average of the pixels around it - the
// more like skin, the more - and lifted towards white by `brighten`.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let skin = retouch_skin(c.rgb);
    if (skin <= 0.0) {
        return c;
    }
    let t = texel() * 2.0;
    var sum = vec3<f32>(0.0);
    var weight = 0.0;
    for (var y: i32 = -2; y <= 2; y++) {
        for (var x: i32 = -2; x <= 2; x++) {
            let n = sample_premultiplied(uv + vec2<f32>(f32(x), f32(y)) * t);
            let rgb = n.rgb / max(n.a, 1e-6);
            let d = rgb - c.rgb;
            let w = exp(-f32(x * x + y * y) / 6.0 - dot(d, d) / 0.006) * n.a;
            sum += rgb * w;
            weight += w;
        }
    }
    let soft = sum / max(weight, 1e-6);
    var rgb = mix(c.rgb, soft, skin * params.soften / 100.0);
    rgb = mix(rgb, vec3<f32>(1.0), skin * params.brighten / 100.0 * 0.25);
    return vec4<f32>(rgb, c.a);
}
