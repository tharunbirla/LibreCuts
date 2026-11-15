struct Params { panels: f32 }

// Each band shows the middle of the picture at its own size: the slice as
// tall as a band in a tall frame, as wide as a band in a wide one.
fn effect(uv: vec2<f32>) -> vec4<f32> {
    let n = round(params.panels);
    var src = uv;
    if (frame.size.y >= frame.size.x) {
        src.y = 0.5 + (fract(uv.y * n) - 0.5) / n;
    } else {
        src.x = 0.5 + (fract(uv.x * n) - 0.5) / n;
    }
    return sample(src);
}
