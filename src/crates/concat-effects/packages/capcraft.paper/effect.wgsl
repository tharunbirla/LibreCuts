struct Params { amount: f32 }

// Paper. The colour is softened towards its grey and the picture lifted
// towards the paper's warm white, as ink on paper never reaches black; the
// paper's fibres - fine waves running every way - and its larger blotches
// show through. Sizes are shares of the frame's height, so the paper is
// the same at any size.

/// Fine waves running every way, in -1..1: the paper's fibres.
fn fibres(p: vec2<f32>) -> f32 {
    var n = sin(dot(p, vec2<f32>(370.0, 110.0)) + 1.3);
    n += sin(dot(p, vec2<f32>(-230.0, 410.0)) + 4.1);
    n += sin(dot(p, vec2<f32>(530.0, -290.0)) + 2.7) * 0.7;
    n += sin(dot(p, vec2<f32>(-710.0, -470.0)) + 0.4) * 0.5;
    n += sin(dot(p, vec2<f32>(970.0, 130.0)) + 5.2) * 0.35;
    return n / 3.55;
}

/// Broad, soft unevenness, in -1..1: the paper's blotches.
fn blotches(p: vec2<f32>) -> f32 {
    return sin(dot(p, vec2<f32>(3.1, 2.3)) + 0.7) * sin(dot(p, vec2<f32>(-2.7, 3.9)) + 2.2);
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    let aspect = frame.size.x / max(frame.size.y, 1.0);
    let p = vec2<f32>(uv.x * aspect, uv.y);
    let softened = mix(c.rgb, vec3<f32>(luma(c.rgb)), 0.25 * k);
    let lifted = mix(softened, softened * 0.85 + vec3<f32>(0.12), 0.5 * k);
    let tinted = lifted * mix(vec3<f32>(1.0), vec3<f32>(0.98, 0.95, 0.88), k);
    let texture = 1.0 + k * (0.05 * fibres(p) + 0.04 * blotches(p));
    return vec4<f32>(tinted * texture, c.a);
}
