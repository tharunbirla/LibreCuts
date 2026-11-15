struct Params { color: vec4<f32>, opacity: f32, blur: f32, distance: f32, angle: f32 }

// A title's shadow. The letters' coverage - the layer's alpha - is blurred
// by a Gaussian, along the rows in `across` and down the columns in `down`,
// then read back moved by the distance and angle and laid under the letters
// in the chosen colour. Blur and distance are shares of the frame's height,
// so the shadow sits the same on the monitor as in the export. Each pass
// weighs two neighbouring pixels with one bilinear fetch placed between
// them by their weights; coverage mixes linearly, so that is both exactly.

/// How far the taps reach, in sigmas.
const REACH: f32 = 3.0;

/// The blur's sigma in the layer's pixels: a twentieth of a percent of the
/// frame's height when sharp, to one and a quarter percent.
fn sigma() -> f32 {
    return max((0.0005 + params.blur / 100.0 * 0.012) * frame.size.y, 0.5);
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

/// The letters over their shadow, which is the blurred coverage read from
/// where the letters would be had they not been moved: up to three percent
/// of the frame's height away, the way `angle` points.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let turn = radians(params.angle);
    let reach = params.distance / 100.0 * 0.03;
    let moved = vec2<f32>(cos(turn) * frame.size.y / frame.size.x, sin(turn)) * reach;
    let shade = down_at(uv - moved).r;
    let s = clamp(shade * params.opacity / 100.0, 0.0, 1.0) * params.color.a;
    let a = c.a + s * (1.0 - c.a);
    let rgb = (c.rgb * c.a + params.color.rgb * s * (1.0 - c.a)) / max(a, 1e-6);
    return vec4<f32>(rgb, a);
}
