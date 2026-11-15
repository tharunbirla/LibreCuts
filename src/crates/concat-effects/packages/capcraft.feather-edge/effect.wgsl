struct Params { amount: f32 }

// A cutout's edge feathered inward. Its coverage - the layer's alpha - is
// blurred by a Gaussian, along the rows in `across` and down the columns in
// `down`, and the alpha is then scaled by how far past half the blur is: whole
// half a blur inside the edge, nothing at it. The picture fades out before
// its edge rather than the taken-out colour spreading past it. The blur's
// width is a share of the frame's height, so the feather is as wide on the
// monitor as in the export. Each pass weighs two neighbouring pixels with one
// bilinear fetch placed between them by their weights; coverage mixes
// linearly, so that is both of them exactly.

/// How far the taps reach, in sigmas.
const REACH: f32 = 3.0;

/// The blur's sigma in the layer's pixels: up to four percent of the
/// frame's height.
fn sigma() -> f32 {
    return max(params.amount / 100.0 * 0.04 * frame.size.y, 0.5);
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

/// The picture with its alpha faded towards the cutout's edge; untouched at
/// no feather.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    if (params.amount <= 0.0) {
        return c;
    }
    let inside = smoothstep(0.5, 1.0, down_at(uv).r);
    return vec4<f32>(c.rgb, c.a * inside);
}
