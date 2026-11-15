struct Params { amount: f32 }

// An old television. The picture is bowed out from the middle, the corners
// drawn in and what falls outside the glass black; a dark line runs
// between its rows of light, two hundred and forty of them down the frame
// whatever its size; each three columns of pixels are a red, a green and a
// blue phosphor; and the corners fall into shadow.

/// Rows of light down the frame.
const ROWS: f32 = 240.0;

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let k = params.amount / 100.0;
    let q = uv - vec2<f32>(0.5);
    let src = q * (1.0 + dot(q, q) * 0.25 * k) + vec2<f32>(0.5);
    let alpha = sample(uv).a;
    if (any(src < vec2<f32>(0.0)) || any(src > vec2<f32>(1.0))) {
        return vec4<f32>(vec3<f32>(0.0), alpha);
    }
    let c = sample(src);
    let row = fract(src.y * ROWS);
    let scan = 1.0 - 0.45 * k * smoothstep(0.5, 1.0, abs(row - 0.5) * 2.0);
    let col = fract(src.x * frame.size.x / 3.0);
    var phosphor = vec3<f32>(0.75, 0.75, 1.15);
    if (col < 1.0 / 3.0) {
        phosphor = vec3<f32>(1.15, 0.75, 0.75);
    } else if (col < 2.0 / 3.0) {
        phosphor = vec3<f32>(0.75, 1.15, 0.75);
    }
    let mask = mix(vec3<f32>(1.0), phosphor, 0.5 * k);
    let shade = max(1.0 - k * 0.6 * pow(length(q) * 1.414, 3.0), 0.0);
    return vec4<f32>(c.rgb * scan * mask * shade, c.a);
}
