struct Params { brilliance: f32, clarity: f32, grain: f32, red_hue: f32, red_sat: f32, red_light: f32, orange_hue: f32, orange_sat: f32, orange_light: f32, yellow_hue: f32, yellow_sat: f32, yellow_light: f32, green_hue: f32, green_sat: f32, green_light: f32, cyan_hue: f32, cyan_sat: f32, cyan_light: f32, blue_hue: f32, blue_sat: f32, blue_light: f32, purple_hue: f32, purple_sat: f32, purple_light: f32, magenta_hue: f32, magenta_sat: f32, magenta_light: f32 }

// Further adjustments, in the display encoding where a look is judged by eye.
// Clarity sets each part of the picture's light against the light around it
// (a wide Gaussian of it, drawn across in one pass and down in the next), most
// in the middle tones. Brilliance bends the tone curve: black and white stay,
// the shadows and low middles rise and the upper middles ease. HSL moves the
// hue, saturation and lightness of eight bands of colour, each pixel shared
// between the two bands either side of its hue, greys left alone. Grain is
// the prelude's, a little stronger in the middle tones.

/// How far the blur's taps reach, in sigmas.
const REACH: f32 = 3.0;

/// The clarity blur's sigma in the layer's pixels: one percent of the
/// frame's height.
fn sigma() -> f32 {
    return max(0.01 * frame.size.y, 0.5);
}

/// The Gaussian's weight d pixels from its centre.
fn weight(d: f32, s: f32) -> f32 {
    return exp(-d * d / (2.0 * s * s));
}

/// The layer's light weighed along its row about uv.
fn across(uv: vec2<f32>) -> vec4<f32> {
    let s = sigma();
    let px = 1.0 / frame.size.x;
    let pairs = i32(ceil(REACH * s / 2.0)) + 1;
    var sum = luma(sample_premultiplied(uv).rgb);
    var total = 1.0;
    for (var i = 0; i < pairs; i++) {
        let a = f32(2 * i + 1);
        let wa = weight(a, s);
        let wb = weight(a + 1.0, s);
        let w = wa + wb;
        let at = (a + wb / w) * px;
        sum += (luma(sample_premultiplied(uv + vec2<f32>(at, 0.0)).rgb) + luma(sample_premultiplied(uv - vec2<f32>(at, 0.0)).rgb)) * w;
        total += 2.0 * w;
    }
    let l = sum / total;
    return vec4<f32>(l, l, l, 1.0);
}

/// The across picture weighed down its column about uv.
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
    let l = sum / total;
    return vec4<f32>(l, l, l, 1.0);
}

/// A colour brought from the light it had to another, its hue kept; near
/// black, where a ratio would blow up, by adding the difference instead.
fn relit(rgb: vec3<f32>, had: f32, wanted: f32) -> vec3<f32> {
    if (had < 0.002) {
        return rgb + vec3<f32>(wanted - had);
    }
    return rgb * (wanted / had);
}

/// Hue in degrees, saturation and lightness of a colour in 0..1.
fn to_hsl(c: vec3<f32>) -> vec3<f32> {
    let high = max(c.r, max(c.g, c.b));
    let low = min(c.r, min(c.g, c.b));
    let l = (high + low) * 0.5;
    let d = high - low;
    if (d < 1e-5) {
        return vec3<f32>(0.0, 0.0, l);
    }
    let s = d / (1.0 - abs(2.0 * l - 1.0) + 1e-6);
    var h = 0.0;
    if (high == c.r) {
        h = 60.0 * ((c.g - c.b) / d);
    } else if (high == c.g) {
        h = 60.0 * ((c.b - c.r) / d + 2.0);
    } else {
        h = 60.0 * ((c.r - c.g) / d + 4.0);
    }
    if (h < 0.0) {
        h += 360.0;
    }
    return vec3<f32>(h, clamp(s, 0.0, 1.0), l);
}

/// A colour back from its hue in degrees, saturation and lightness.
fn from_hsl(hsl: vec3<f32>) -> vec3<f32> {
    let k = (vec3<f32>(0.0, 8.0, 4.0) + vec3<f32>(hsl.x / 30.0)) % vec3<f32>(12.0);
    let a = hsl.y * min(hsl.z, 1.0 - hsl.z);
    return vec3<f32>(hsl.z) - a * max(min(min(k - 3.0, 9.0 - k), vec3<f32>(1.0)), vec3<f32>(-1.0));
}

fn effect(uv: vec2<f32>) -> vec4<f32> {
    let c = sample(uv);
    var rgb = c.rgb;
    if (abs(params.clarity) >= 1.0) {
        let l = luma(rgb);
        let around = down_at(uv).r;
        let middle = 1.0 - abs(clamp(l, 0.0, 1.0) * 2.0 - 1.0);
        let k = params.clarity / 100.0 * (0.2 + 0.5 * middle);
        rgb = relit(rgb, l, max(l + (l - around) * k, 0.0));
    }
    if (abs(params.brilliance) >= 1.0) {
        let b = params.brilliance / 100.0;
        let l = luma(rgb);
        let t = clamp(l, 0.0, 1.0);
        let bent = l + b * (0.6 * t * (1.0 - t) * (1.0 - t) - 0.25 * t * t * (1.0 - t));
        rgb = relit(rgb, l, max(bent, 0.0));
    }
    var hues = array<f32, 8>(params.red_hue, params.orange_hue, params.yellow_hue, params.green_hue, params.cyan_hue, params.blue_hue, params.purple_hue, params.magenta_hue);
    var sats = array<f32, 8>(params.red_sat, params.orange_sat, params.yellow_sat, params.green_sat, params.cyan_sat, params.blue_sat, params.purple_sat, params.magenta_sat);
    var lights = array<f32, 8>(params.red_light, params.orange_light, params.yellow_light, params.green_light, params.cyan_light, params.blue_light, params.purple_light, params.magenta_light);
    var any_band = 0.0;
    for (var i = 0; i < 8; i++) {
        any_band += abs(hues[i]) + abs(sats[i]) + abs(lights[i]);
    }
    if (any_band >= 1.0) {
        var centres = array<f32, 8>(0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0);
        let inside = clamp(rgb, vec3<f32>(0.0), vec3<f32>(1.0));
        let hsl = to_hsl(inside);
        var turn = 0.0;
        var more = 0.0;
        var lighter = 0.0;
        for (var i = 0; i < 8; i++) {
            let here = centres[i];
            let next = select(centres[min(i + 1, 7)], 360.0, i == 7);
            let before = select(centres[max(i - 1, 0)], centres[7] - 360.0, i == 0);
            // how far the pixel's hue is past this band's centre, either way round the wheel
            var d = hsl.x - here;
            if (d > 180.0) {
                d -= 360.0;
            }
            if (d < -180.0) {
                d += 360.0;
            }
            let span = select(here - before, next - here, d >= 0.0);
            let w = smoothstep(0.0, 1.0, 1.0 - clamp(abs(d) / span, 0.0, 1.0));
            turn += w * hues[i];
            more += w * sats[i];
            lighter += w * lights[i];
        }
        // greys have no hue to move
        let coloured = smoothstep(0.02, 0.2, hsl.y);
        let h = hsl.x + turn / 100.0 * 30.0 * coloured;
        let s = clamp(hsl.y * (1.0 + more / 100.0 * coloured), 0.0, 1.0);
        let dl = lighter / 100.0 * coloured * 0.35;
        let l = clamp(hsl.z + dl * select(hsl.z, 1.0 - hsl.z, dl > 0.0), 0.0, 1.0);
        rgb = from_hsl(vec3<f32>(((h % 360.0) + 360.0) % 360.0, s, l)) + (rgb - inside);
    }
    if (params.grain >= 1.0) {
        // the most in the middle tones, as film's; none on pure black or white
        let l = clamp(luma(rgb), 0.0, 1.0);
        let ends = smoothstep(0.0, 0.08, l) * (1.0 - smoothstep(0.92, 1.0, l));
        rgb += grain_at(uv, params.grain / 100.0 * 0.12 * (0.5 + 2.0 * l * (1.0 - l)) * ends);
    }
    return vec4<f32>(rgb, c.a);
}
