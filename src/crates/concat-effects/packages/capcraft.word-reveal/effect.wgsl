struct Params { color: vec4<f32>, span: f32, karaoke: f32 }

// A title's words, one after another. The painter gives every pixel of a
// word its place in the reading order, 0 for the first word to 1 for the
// last (and 0 off the words, so a plate or anything that is not a title is
// there from the start); the words reached so far are those whose place is
// no later than how far through the span the clip is, the last of them
// reached a little before the span ends so it is seen. Reached words show
// (or, karaoke, take the colour: its hue at the word's own lightness, so
// a dark outline stays dark), the rest are hidden (or as they were).

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let t = clamp(frame.clip_time / max(params.span, 0.05), 0.0, 1.0) * 1.15;
    let reached = reveal_order(uv) <= t + 1e-4;
    if (params.karaoke < 0.5) {
        return select(vec4<f32>(c.rgb, 0.0), c, reached);
    }
    let lit = params.color.rgb * clamp(luma(c.rgb) / max(luma(params.color.rgb), 0.05), 0.0, 4.0);
    let rgb = mix(c.rgb, lit, params.color.a);
    return select(c, vec4<f32>(rgb, c.a), reached);
}
