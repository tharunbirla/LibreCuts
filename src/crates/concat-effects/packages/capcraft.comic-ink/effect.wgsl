struct Params { amount: f32, levels: f32 }

// A comic panel. Each channel is rounded to the nearest of a few steps and
// the result made bolder, away from its grey; the edges - where the
// lightness changes fast across a pixel's neighbours - are inked black, a
// line about a pixel of a 720-line picture wide whatever the size.

fn light_at(uv: vec2<f32>) -> f32 {
    return luma(sample(clamp(uv, vec2<f32>(0.0), vec2<f32>(1.0))).rgb);
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    let k = params.amount / 100.0;
    let steps = max(params.levels, 2.0);
    var poster = floor(c.rgb * steps + vec3<f32>(0.5)) / steps;
    let grey = luma(poster);
    poster = vec3<f32>(grey) + (poster - vec3<f32>(grey)) * (1.0 + 0.4 * k);
    // the change of lightness across the neighbours, the Sobel way
    let o = vec2<f32>(1.0 / frame.size.x, 1.0 / frame.size.y) * max(1.0, frame.size.y / 720.0);
    let tl = light_at(uv + vec2<f32>(-o.x, -o.y));
    let t = light_at(uv + vec2<f32>(0.0, -o.y));
    let tr = light_at(uv + vec2<f32>(o.x, -o.y));
    let l = light_at(uv + vec2<f32>(-o.x, 0.0));
    let r = light_at(uv + vec2<f32>(o.x, 0.0));
    let bl = light_at(uv + vec2<f32>(-o.x, o.y));
    let b = light_at(uv + vec2<f32>(0.0, o.y));
    let br = light_at(uv + vec2<f32>(o.x, o.y));
    let gx = (tr + 2.0 * r + br) - (tl + 2.0 * l + bl);
    let gy = (bl + 2.0 * b + br) - (tl + 2.0 * t + tr);
    let ink = smoothstep(0.08, 0.3, length(vec2<f32>(gx, gy))) * k;
    return vec4<f32>(mix(mix(c.rgb, poster, k), vec3<f32>(0.0), ink), c.a);
}
