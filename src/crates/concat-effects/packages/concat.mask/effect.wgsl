struct Params {
    shape: f32,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    rotation: f32,
    feather: f32,
    round: f32,
    invert: f32,
}

// The mask's shapes as signed distances - below zero inside - in units of
// the picture's height, so a circle is round on a picture of any shape.

fn mask_dot2(v: vec2<f32>) -> f32 {
    return dot(v, v);
}

// A heart standing on its point at the origin, one unit tall, y up.
fn mask_heart(q: vec2<f32>) -> f32 {
    let p = vec2<f32>(abs(q.x), q.y);
    if (p.y + p.x > 1.0) {
        return sqrt(mask_dot2(p - vec2<f32>(0.25, 0.75))) - sqrt(2.0) / 4.0;
    }
    let a = mask_dot2(p - vec2<f32>(0.0, 1.0));
    let b = mask_dot2(p - vec2<f32>(0.5) * max(p.x + p.y, 0.0));
    return sqrt(min(a, b)) * sign(p.x - p.y);
}

// A five-pointed star of outer radius `r`, its inner points at `rf` of the
// way in, one point up.
fn mask_star(q: vec2<f32>, r: f32, rf: f32) -> f32 {
    let k1 = vec2<f32>(0.809016994375, -0.587785252292);
    let k2 = vec2<f32>(-k1.x, k1.y);
    var p = vec2<f32>(abs(q.x), q.y);
    p = p - 2.0 * max(dot(k1, p), 0.0) * k1;
    p = p - 2.0 * max(dot(k2, p), 0.0) * k2;
    p.x = abs(p.x);
    p.y = p.y - r;
    let ba = rf * vec2<f32>(-k1.y, k1.x) - vec2<f32>(0.0, 1.0);
    let h = clamp(dot(p, ba) / dot(ba, ba), 0.0, r);
    return length(p - ba * h) * sign(p.y * ba.x - p.x * ba.y);
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let t = texel();
    let aspect = t.y / max(t.x, 1e-6);
    var p = uv - vec2<f32>(params.x, params.y) / 100.0;
    p.x = p.x * aspect;
    let a = radians(params.rotation);
    let q = vec2<f32>(cos(a) * p.x + sin(a) * p.y, -sin(a) * p.x + cos(a) * p.y);
    let hw = max(params.width / 200.0 * aspect, 0.001);
    let hh = max(params.height / 200.0, 0.001);
    let shape = i32(params.shape + 0.5);
    var d = 0.0;
    if (shape == 0) {
        // a line through the centre: keep what is above it
        d = q.y;
    } else if (shape == 1) {
        // a band across the centre, as tall as the height
        d = abs(q.y) - hh;
    } else if (shape == 3) {
        let r = clamp(params.round / 100.0, 0.0, 1.0) * min(hw, hh);
        let b = abs(q) - vec2<f32>(hw, hh) + vec2<f32>(r);
        d = length(max(b, vec2<f32>(0.0))) + min(max(b.x, b.y), 0.0) - r;
    } else if (shape == 4) {
        let s = max(hw, hh) * 2.0;
        d = mask_heart(vec2<f32>(q.x, -q.y) / s + vec2<f32>(0.0, 0.5)) * s;
    } else if (shape == 5) {
        let s = max(hw, hh);
        d = mask_star(vec2<f32>(q.x, -q.y) / s, 1.0, 0.45) * s;
    } else {
        // an ellipse in the box
        d = (length(q / vec2<f32>(hw, hh)) - 1.0) * min(hw, hh);
    }
    let f = max(params.feather / 100.0 * 0.25, 0.0008);
    var keep = 1.0 - smoothstep(-f, f, d);
    if (params.invert > 0.5) {
        keep = 1.0 - keep;
    }
    return vec4<f32>(c.rgb, c.a * keep);
}
