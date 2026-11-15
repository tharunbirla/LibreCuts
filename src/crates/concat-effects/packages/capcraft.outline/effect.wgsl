struct Params { width: f32, strength: f32, hue: f32 }

// A fully saturated colour of the given hue, in degrees.
fn outline_hue(h: f32) -> vec3<f32> {
    let k = vec3<f32>(0.0, 2.0 / 3.0, 1.0 / 3.0);
    return clamp(abs(fract(vec3<f32>(h / 360.0) + k) * 6.0 - 3.0) - 1.0, vec3<f32>(0.0), vec3<f32>(1.0));
}

// How opaque the picture is anywhere within `width` pixels, looked for on
// two rings of sixteen; where the pixel itself is clear, that much glow
// shows under it. An opaque picture, or an evenly see-through one, has no
// edge and is left as it is.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let t = texel() * params.width;
    var around = 0.0;
    for (var i: i32 = 0; i < 16; i++) {
        let a = f32(i) * 0.39269908;
        let d = vec2<f32>(cos(a), sin(a)) * t;
        around = max(around, sample_premultiplied(uv + d).a);
        around = max(around, sample_premultiplied(uv + d * 0.5).a);
    }
    let outside = 1.0 - smoothstep(0.0, 0.5, c.a);
    let g = clamp(around * outside * params.strength / 100.0, 0.0, 1.0);
    let glow = outline_hue(params.hue);
    let a = c.a + g * (1.0 - c.a);
    let rgb = (c.rgb * c.a + glow * g * (1.0 - c.a)) / max(a, 1e-6);
    return vec4<f32>(rgb, a);
}
