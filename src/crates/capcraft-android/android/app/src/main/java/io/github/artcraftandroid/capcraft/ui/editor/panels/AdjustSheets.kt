package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import org.json.JSONObject
import kotlin.math.roundToInt

/** A sheet of the Adjust tab's own: Reset at the left, its name in the middle, a button to fold it away. */
@Composable
private fun SheetHead(title: String, onReset: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 6.dp)) {
        Row(Modifier.align(Alignment.CenterStart).clickable(onClick = onReset).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(LucideIcons.RotateCcw, Palette.TextSecondary, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Reset", style = Type.Small.copy(color = Palette.TextSecondary))
        }
        Text(title, style = Type.Body.copy(color = Palette.Text), modifier = Modifier.align(Alignment.Center))
        Box(Modifier.align(Alignment.CenterEnd).size(40.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.CircleChevronDown, Palette.DockText, size = 20.dp, description = "Close")
        }
    }
}

/** The eight bands of colour HSL moves, by the key their knobs start with, and each band's hue in degrees. */
val HSL_BANDS = listOf(
    "red" to 0f, "orange" to 30f, "yellow" to 60f, "green" to 120f,
    "cyan" to 180f, "blue" to 240f, "purple" to 270f, "magenta" to 300f,
)

/** Every key the HSL sheet sets. */
val HSL_KEYS = HSL_BANDS.flatMap { (band, _) -> listOf("${band}_hue", "${band}_sat", "${band}_light") }

/**
 * HSL: a dot for each band of colour, and its hue, saturation and lightness on sliders whose tracks
 * show what they move through. [set] takes knob values (null to clear) and the gesture they fold
 * into; [params] are the knobs as they stand.
 */
@Composable
fun ColumnScope.HslSheet(params: JSONObject, set: (Map<String, Double?>, String?) -> Unit, done: () -> Unit, close: () -> Unit) {
    var band by remember { mutableIntStateOf(0) }
    val (key, hue) = HSL_BANDS[band]
    SheetHead("HSL", onReset = { set(HSL_KEYS.associateWith { null }, null) }, onClose = close)
    // the bands and their sliders scroll on a phone too short for them all
    Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            HSL_BANDS.forEachIndexed { i, (_, h) ->
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .then(if (i == band) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                        .padding(if (i == band) 4.dp else 2.dp).clip(CircleShape).background(Color.hsv(h, 0.75f, 0.95f))
                        .clickable { band = i },
                )
            }
        }
        // each track runs through what its knob moves the band through
        val tracks = listOf(
            Brush.horizontalGradient(listOf(Color.hsv((hue + 330f) % 360f, 0.8f, 0.95f), Color.hsv(hue, 0.8f, 0.95f), Color.hsv((hue + 30f) % 360f, 0.8f, 0.95f))),
            Brush.horizontalGradient(listOf(Color(0xFF9A9A9A), Color.hsv(hue, 1f, 0.95f))),
            Brush.horizontalGradient(listOf(Color.hsv(hue, 0.8f, 0.25f), Color.hsv(hue, 0.25f, 1f))),
        )
        listOf("hue" to "Hue", "sat" to "Saturation", "light" to "Lightness").forEachIndexed { i, (part, label) ->
            androidx.compose.runtime.key(key, part) {
                var held by remember { mutableStateOf<Float?>(null) }
                ValueSlider(
                    value = held ?: params.optDouble("${key}_$part", 0.0).toFloat(),
                    range = -100f..100f,
                    label = { "${it.roundToInt()}" },
                    onChange = { v ->
                        held = v
                        set(mapOf("${key}_$part" to v.roundToInt().toDouble()), "adjust-hsl-$key-$part")
                    },
                    onDone = {
                        held = null
                        done()
                    },
                    title = label,
                    track = tracks[i],
                )
            }
        }
    }
}

/** The engine's three wheels, by the key it keeps each under, and what the sheet calls them. */
private val WHEELS = listOf("lift" to "Shadows", "gamma" to "Midtones", "gain" to "Highlights")

/** Every key the Color wheel sheet sets. */
val WHEEL_KEYS = WHEELS.flatMap { (wheel, _) -> listOf("$wheel.x", "$wheel.y", "$wheel.m") }

/** The disc's hues as a sweep draws them from three o'clock: red at the top, then clockwise as the engine reads them. */
private val DISC = (0..12).map { i -> Color.hsv(((i * 30f) + 90f) % 360f, 0.85f, 1f) }

/**
 * Color wheel: the shadows, midtones and highlights each pushed towards a colour by dragging the
 * dot on its disc (as far from grey as the dot is from the middle), and made lighter or darker on
 * the slider under it - the engine's lift, gamma and gain.
 */
@Composable
fun ColumnScope.WheelsSheet(params: JSONObject, set: (Map<String, Double?>, String?) -> Unit, done: () -> Unit, close: () -> Unit) {
    val setNow by rememberUpdatedState(set)
    val doneNow by rememberUpdatedState(done)
    SheetHead("Color wheel", onReset = { set(WHEEL_KEYS.associateWith { null }, null) }, onClose = close)
    Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        WHEELS.forEach { (wheel, label) ->
            // the dot while the finger moves it, before the engine has it
            var live by remember(wheel) { mutableStateOf<Offset?>(null) }
            val stored = Offset(params.optDouble("$wheel.x", 0.0).toFloat(), params.optDouble("$wheel.y", 0.0).toFloat())
            val dot = live ?: stored
            var master by remember(wheel) { mutableStateOf<Float?>(null) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Canvas(
                    Modifier.size(84.dp).pointerInput(wheel) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            val r = size.width / 2f
                            fun at(p: Offset): Offset {
                                val v = Offset((p.x - r) / r, -(p.y - r) / r)
                                val reach = v.getDistance()
                                return if (reach > 1f) v / reach else v
                            }
                            var p = at(down.position)
                            live = p
                            setNow(mapOf("$wheel.x" to p.x.toDouble(), "$wheel.y" to p.y.toDouble()), "adjust-wheel-$wheel")
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                p = at(change.position)
                                live = p
                                setNow(mapOf("$wheel.x" to p.x.toDouble(), "$wheel.y" to p.y.toDouble()), "adjust-wheel-$wheel")
                                change.consume()
                            }
                            live = null
                            doneNow()
                        }
                    },
                ) {
                    val r = size.minDimension / 2
                    drawCircle(Brush.sweepGradient(DISC), r)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFDDDDDD), Color(0x00DDDDDD)), radius = r), r)
                    val at = Offset(center.x + dot.x * r, center.y - dot.y * r)
                    drawCircle(Color.White, 6.dp.toPx(), at, style = Stroke(2.dp.toPx()))
                }
                Spacer(Modifier.height(6.dp))
                Text(label, style = Type.Small.copy(color = Palette.Text))
                CapSlider(
                    value = master ?: params.optDouble("$wheel.m", 0.0).toFloat(),
                    range = -1f..1f,
                    label = null,
                    onChange = { v ->
                        master = v
                        setNow(mapOf("$wheel.m" to v.toDouble()), "adjust-wheel-$wheel-m")
                    },
                    onDone = {
                        master = null
                        doneNow()
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

/** The curves Graphs draws, by the key the engine keeps them under, and the colour of each one's dot. */
private val CURVES = listOf("luma" to Color.White, "red" to Color(0xFFE53935), "green" to Color(0xFF43A047), "blue" to Color(0xFF1E88E5))

/** The most points a curve keeps, as the engine reads them. */
private const val MOST_POINTS = 8

/** Every key the Graphs sheet can set. */
val CURVE_KEYS = CURVES.flatMap { (curve, _) -> (0 until MOST_POINTS).flatMap { n -> listOf("$curve.$n.x", "$curve.$n.y") } }

/** A curve's points as [params] keep them, in order along it; the straight line when it has fewer than two. */
private fun pointsOf(params: JSONObject, curve: String): List<Offset> {
    val points = (0 until MOST_POINTS).mapNotNull { n ->
        if (!params.has("$curve.$n.x") || !params.has("$curve.$n.y")) return@mapNotNull null
        Offset(params.optDouble("$curve.$n.x").toFloat().coerceIn(0f, 1f), params.optDouble("$curve.$n.y").toFloat().coerceIn(0f, 1f))
    }.sortedBy { it.x }
    return if (points.size < 2) listOf(Offset(0f, 0f), Offset(1f, 1f)) else points
}

/** The slopes a curve leaves its points at, Fritsch and Carlson's, as the engine draws it: no overshoot. */
private fun slopes(p: List<Offset>): List<Float> {
    val n = p.size
    val d = (0 until n - 1).map { i -> (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x).coerceAtLeast(1e-6f) }
    val m = MutableList(n) { i ->
        when (i) {
            0 -> d.first()
            n - 1 -> d.last()
            else -> if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        }
    }
    for (i in 0 until n - 1) {
        if (d[i] == 0f) {
            m[i] = 0f
            m[i + 1] = 0f
            continue
        }
        val a = m[i] / d[i]
        val b = m[i + 1] / d[i]
        val h = a * a + b * b
        if (h > 9f) {
            val t = 3f / kotlin.math.sqrt(h)
            m[i] = t * a * d[i]
            m[i + 1] = t * b * d[i]
        }
    }
    return m
}

/** Where the curve through [p] is at [x]. */
private fun curveAt(p: List<Offset>, m: List<Float>, x: Float): Float {
    if (x <= p.first().x) return p.first().y
    if (x >= p.last().x) return p.last().y
    val i = (0 until p.size - 1).first { x <= p[it + 1].x }
    val h = p[i + 1].x - p[i].x
    val t = (x - p[i].x) / h
    val t2 = t * t
    val t3 = t2 * t
    return ((2 * t3 - 3 * t2 + 1) * p[i].y + (t3 - 2 * t2 + t) * h * m[i] + (-2 * t3 + 3 * t2) * p[i + 1].y + (t3 - t2) * h * m[i + 1]).coerceIn(0f, 1f)
}

/**
 * Graphs: the light's curve and each colour's, as the engine's Adjust keeps them. A tap on the
 * square adds a point, a drag moves the nearest, and a point dragged out over its neighbour goes.
 */
@Composable
fun ColumnScope.GraphsSheet(params: JSONObject, set: (Map<String, Double?>, String?) -> Unit, done: () -> Unit, close: () -> Unit) {
    var which by remember { mutableIntStateOf(0) }
    val (curve, tint) = CURVES[which]
    // the points being dragged, shown before the engine has them
    var live by remember(curve) { mutableStateOf<List<Offset>?>(null) }
    val stored = pointsOf(params, curve)
    val points = live ?: stored
    val current by rememberUpdatedState(stored)
    // the drag outlives a recomposition, so it reaches for the newest of these
    val setNow by rememberUpdatedState(set)
    val doneNow by rememberUpdatedState(done)

    /** The curve's points written as the engine keeps them, the keys of points no longer there cleared. */
    fun write(next: List<Offset>, gesture: String?) {
        val sorted = next.sortedBy { it.x }
        val values = HashMap<String, Double?>()
        for (n in 0 until MOST_POINTS) {
            val p = sorted.getOrNull(n)
            values["$curve.$n.x"] = p?.x?.toDouble()
            values["$curve.$n.y"] = p?.y?.toDouble()
        }
        setNow(values, gesture)
    }
    SheetHead("Graphs", onReset = { set(CURVE_KEYS.associateWith { null }, null) }, onClose = close)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        CURVES.forEachIndexed { i, (_, colour) ->
            Box(
                Modifier.padding(horizontal = 10.dp).size(26.dp).clip(CircleShape)
                    .then(if (i == which) Modifier.border(2.dp, Color.White, CircleShape) else Modifier)
                    .padding(if (i == which) 4.dp else 1.dp).clip(CircleShape).background(colour)
                    .clickable { which = i },
            )
        }
    }
    Canvas(
        Modifier.fillMaxWidth().weight(1f).padding(horizontal = 18.dp, vertical = 8.dp).pointerInput(curve) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                fun unit(o: Offset) = Offset((o.x / w).coerceIn(0f, 1f), (1f - o.y / h).coerceIn(0f, 1f))
                var shape = current.toMutableList()
                val at = unit(down.position)
                // the point under the finger, or a new one on the curve's line there
                var index = shape.indexOfFirst { kotlin.math.abs(it.x - at.x) * w < 28.dp.toPx() && kotlin.math.abs(it.y - at.y) * h < 28.dp.toPx() }
                if (index < 0 && shape.size < MOST_POINTS && at.x > 0.02f && at.x < 0.98f) {
                    shape.add(at)
                    shape = shape.sortedBy { it.x }.toMutableList()
                    index = shape.indexOf(at)
                    live = shape.toList()
                    write(shape, "adjust-curve")
                }
                var gone = false
                while (index >= 0) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val p = unit(change.position)
                    val last = shape.size - 1
                    val x = when (index) {
                        0 -> 0f
                        last -> 1f
                        else -> p.x
                    }
                    // a middle point carried past a neighbour is taken away
                    gone = index in 1 until last && (x <= shape[index - 1].x || x >= shape[index + 1].x)
                    val moved = shape.toMutableList().also { it[index] = Offset(x.coerceIn(0f, 1f), p.y) }
                    val shown = if (gone) moved.filterIndexed { i, _ -> i != index } else moved
                    live = shown
                    write(shown, "adjust-curve")
                    if (!gone) shape = moved
                    change.consume()
                }
                live = null
                doneNow()
            }
        },
    ) {
        val w = size.width
        val h = size.height
        // the square, lit as the curve's colour runs from dark to light, and its quarters
        drawRect(Brush.linearGradient(listOf(tint.copy(alpha = 0.55f), Color(0xFF2A2A2A)), Offset(0f, 0f), Offset(w, h)))
        for (k in 1..3) {
            drawLine(Color(0x22FFFFFF), Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
            drawLine(Color(0x22FFFFFF), Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
        }
        val m = slopes(points)
        val path = Path()
        val steps = 64
        for (s in 0..steps) {
            val x = s / steps.toFloat()
            val y = curveAt(points, m, x)
            if (s == 0) path.moveTo(x * w, (1 - y) * h) else path.lineTo(x * w, (1 - y) * h)
        }
        drawPath(path, Color.White, style = Stroke(1.5.dp.toPx()))
        points.forEach { p -> drawCircle(Color.White, 7.dp.toPx(), Offset(p.x * w, (1 - p.y) * h)) }
    }
}
