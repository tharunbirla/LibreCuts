struct Params { color: vec4<f32>, strength: f32, range: f32 }

// A title's letters lit from behind. Their coverage - the layer's alpha -
// is blurred by a Gaussian, along the rows in `across` and down the columns
// in `down`, and laid under the letters in the chosen colour. The blur's
// width is a share of the frame's height, so the glow is as wide on the
// monitor as in the export. Each pass weighs two neighbouring pixels with
// one bilinear fetch placed between them by their weights; coverage mixes
// linearly, so that is both of them exactly.

/// How far the taps reach, in sigmas.
const REACH: f32 = 3.0;

/// The blur's sigma in the layer's pixels: from a fifth of a percent of
/// the frame's height to two and a fifth.
fn sigma() -> f32 {
    return max((0.002 + params.range / 100.0 * 0.02) * frame.size.y, 0.5);
}

/// The Gaussian's weight `d` pixels from its centre.
fn weight(d: f32, s: f32) -> f32 {
    return exp(-d * d / (2.0 * s * s));
}

/// The layer's coverage weighed along its row about `uv`.
fn across(uv: vec2<f32>) -> vec4<f32> {
    let s = sigma();
    let px = 1.0 / frame.size.x;
    let pairs = i32(ceil(REACH * s / 2.0)) + 1;
    var sum = textureSampleLevel(source, source_sampler, uv, 0.0).a;
    var total = 1.0;
    for (var i = 0; i < pairs; i++) {
        let a = f32(2 * i + 1);
        let wa = weight(a, s);
        let wb = weight(a + 1.0, s);
        let w = wa + wb;
        let at = (a + wb / w) * px;
        sum += (textureSampleLevel(source, source_sampler, uv + vec2<f32>(at, 0.0), 0.0).a
            + textureSampleLevel(source, source_sampler, uv - vec2<f32>(at, 0.0), 0.0).a) * w;
        total += 2.0 * w;
    }
    let a = sum / total;
    return vec4<f32>(a, a, a, 1.0);
}

/// `across` weighed down its column about `uv`.
fn down(uv: vec2<f32>) -> vec4<f32> {
    let s = sigma();
    let px = 1.0 / frame.size.y;
    let pairs = i32(ceil(REACH * s / 2.0)) + 1;
    var sum = across_at(uv).r;
    var total = 1.0;
    for (var i = 0; i < pairs; i++) {
        let a = f32(2 * i + 1);
        let wa = weight(a, s);
        let wb = weight(a + 1.0, s);
        let w = wa + wb;
        let at = (a + wb / w) * px;
        sum += (across_at(uv + vec2<f32>(0.0, at)).r + across_at(uv - vec2<f32>(0.0, at)).r) * w;
        total += 2.0 * w;
    }
    let a = sum / total;
    return vec4<f32>(a, a, a, 1.0);
}

/// The letters over their glow: the blurred coverage, strengthened so the
/// light is full close to the letters, in the chosen colour and its alpha.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let halo = down_at(uv).r;
    let g = clamp(halo * params.strength / 100.0 * 2.0, 0.0, 1.0) * params.color.a;
    let a = c.a + g * (1.0 - c.a);
    let rgb = (c.rgb * c.a + params.color.rgb * g * (1.0 - c.a)) / max(a, 1e-6);
    return vec4<f32>(rgb, a);
}
