package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** A key's approach curve: a CSS timing function's control points, x1, y1, x2, y2. */
private class Graph(val name: String, val points: DoubleArray)

private val GRAPHS = listOf(
    Graph("Linear", doubleArrayOf(0.0, 0.0, 1.0, 1.0)),
    Graph("Ease in", doubleArrayOf(0.42, 0.0, 1.0, 1.0)),
    Graph("Ease out", doubleArrayOf(0.0, 0.0, 0.58, 1.0)),
    Graph("Ease in-out", doubleArrayOf(0.42, 0.0, 0.58, 1.0)),
    Graph("Rush", doubleArrayOf(0.8, 0.0, 0.2, 1.0)),
    Graph("Glide", doubleArrayOf(0.1, 0.6, 0.3, 1.0)),
    Graph("Overshoot", doubleArrayOf(0.34, 1.56, 0.64, 1.0)),
    Graph("Wind-up", doubleArrayOf(0.36, -0.4, 0.64, 1.0)),
    Graph("Elastic", doubleArrayOf(0.5, -0.6, 0.5, 1.6)),
)

/** Two keys this close (as fractions of the clip) are one moment. */
private const val NEAR = 0.002

/**
 * How the selected clip moves between two keyframes: the curve of the stretch the playhead is in
 * (or the one ending at the key it is on).
 */
@Composable
fun GraphsPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val keys: JSONArray = remember(clip.json) { vm.clipJson(clip.id)?.optJSONArray("keys") ?: JSONArray() }
    val at = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    val times = (0 until keys.length()).map { keys.getJSONObject(it).optDouble("at") }.sorted()
        .fold(mutableListOf<Double>()) { acc, t -> if (acc.isEmpty() || t - acc.last() > NEAR) acc.add(t); acc }
    // the key the stretch ends on: the first after the playhead, or the one under it
    val target = times.drop(1).firstOrNull { it > at + NEAR } ?: times.drop(1).lastOrNull { abs(it - at) <= NEAR }
    val current: DoubleArray? = target?.let { t ->
        (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull { abs(it.optDouble("at") - t) <= NEAR }?.let(::easeOf)
    }

    fun apply(g: Graph) {
        val t = target ?: return
        val batch = JSONArray()
        for (i in 0 until keys.length()) {
            val k = keys.getJSONObject(i)
            if (abs(k.optDouble("at") - t) > NEAR) continue
            batch.put(
                JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", k.optString("property"))
                    .put("at", k.optDouble("at")).put("value", k.optDouble("value"))
                    .put("ease", JSONArray().apply { g.points.forEach { put(it) } }),
            )
        }
        if (batch.length() > 0) vm.edit { applyBatch(batch.toString()) }
    }

    // a curve of one's own: its handles dragged on a square below the shelf
    var custom by remember(clip.id, target) { mutableStateOf(current != null && GRAPHS.none { g -> g.points.indices.all { abs(g.points[it] - current[it]) < 0.005 } }) }
    PanelFrame("Graphs", vm, height = if (custom) 340.dp else 230.dp) {
        if (target == null) {
            Spacer(Modifier.height(36.dp))
            Text(
                "Add two keyframes, then put the playhead between them to shape how the clip moves from one to the next.",
                style = Type.Small,
                modifier = Modifier.padding(horizontal = 32.dp).align(Alignment.CenterHorizontally),
            )
            return@PanelFrame
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 8.dp)) {
            ChoiceTile("Custom", custom, onClick = { custom = true }, size = 56.dp) { GraphGlyph(current ?: GRAPHS[0].points) }
            GRAPHS.forEach { g ->
                val on = !custom && current != null && g.points.indices.all { abs(g.points[it] - current[it]) < 0.005 }
                ChoiceTile(g.name, on, onClick = {
                    custom = false
                    apply(g)
                }, size = 56.dp) { GraphGlyph(g.points) }
            }
        }
        if (custom) {
            CurveEditor(current ?: GRAPHS[3].points) { points -> apply(Graph("Custom", points)) }
        } else {
            Text(
                "Shapes the stretch from the keyframe before the playhead to the one after it.",
                style = Type.Small,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * A curve of one's own: time across, progress up, its two handles (the timing function's control
 * points) dragged anywhere across and a little past either end up and down; the curve is set as the
 * finger lifts.
 */
@Composable
private fun CurveEditor(start: DoubleArray, onSet: (DoubleArray) -> Unit) {
    var points by remember(start.toList()) { mutableStateOf(start.copyOf()) }
    val set by rememberUpdatedState(onSet)
    // progress runs from -0.6 to 1.6 up the square, so a curve may overshoot either end
    fun y(v: Double, h: Float) = h * (1.6f - v.toFloat()) / 2.2f
    fun v(y: Float, h: Float) = (1.6 - y / h * 2.2).coerceIn(-0.6, 1.6)
    Canvas(
        Modifier.fillMaxWidth().height(110.dp).padding(horizontal = 40.dp, vertical = 4.dp).background(Color(0xFF2A2A2A)).pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                // the handle nearer the finger
                val first = Offset(points[0].toFloat() * w, y(points[1], h))
                val second = Offset(points[2].toFloat() * w, y(points[3], h))
                val which = if ((down.position - first).getDistance() <= (down.position - second).getDistance()) 0 else 2
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    val next = points.copyOf()
                    next[which] = (change.position.x / w).toDouble().coerceIn(0.0, 1.0)
                    next[which + 1] = v(change.position.y, h)
                    points = next
                    change.consume()
                }
                set(points)
            }
        },
    ) {
        val w = size.width
        val h = size.height
        drawLine(Color(0x33FFFFFF), Offset(0f, y(0.0, h)), Offset(w, y(0.0, h)), 1.dp.toPx())
        drawLine(Color(0x33FFFFFF), Offset(0f, y(1.0, h)), Offset(w, y(1.0, h)), 1.dp.toPx())
        val a = Offset(0f, y(0.0, h))
        val b = Offset(w, y(1.0, h))
        val c1 = Offset(points[0].toFloat() * w, y(points[1], h))
        val c2 = Offset(points[2].toFloat() * w, y(points[3], h))
        drawLine(Color(0x88FFFFFF), a, c1, 1.dp.toPx())
        drawLine(Color(0x88FFFFFF), b, c2, 1.dp.toPx())
        drawPath(Path().apply { moveTo(a.x, a.y); cubicTo(c1.x, c1.y, c2.x, c2.y, b.x, b.y) }, Palette.Text, style = Stroke(2.dp.toPx()))
        drawCircle(Palette.Accent, 7.dp.toPx(), c1)
        drawCircle(Palette.Accent, 7.dp.toPx(), c2)
    }
}

/** A key's curve as stored: four numbers, or one of the older names. */
private fun easeOf(key: JSONObject): DoubleArray {
    key.optJSONArray("ease")?.let { a -> if (a.length() == 4) return DoubleArray(4) { a.optDouble(it) } }
    return when (key.optString("ease")) {
        "in" -> GRAPHS[1].points
        "out" -> GRAPHS[2].points
        "inOut" -> GRAPHS[3].points
        else -> GRAPHS[0].points
    }
}

/** The curve drawn in a tile: time across, progress up. */
@Composable
private fun GraphGlyph(p: DoubleArray) {
    Canvas(Modifier.size(32.dp)) {
        val w = size.width
        val h = size.height
        // leave room for curves that overshoot either end
        fun y(v: Double) = (h * 0.8f - v.toFloat() * h * 0.6f)
        val path = Path().apply {
            moveTo(0f, y(0.0))
            cubicTo(p[0].toFloat() * w, y(p[1]), p[2].toFloat() * w, y(p[3]), w, y(1.0))
        }
        drawLine(Color(0x44FFFFFF), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1.dp.toPx())
        drawLine(Color(0x44FFFFFF), Offset(0f, y(1.0)), Offset(w, y(1.0)), 1.dp.toPx())
        drawPath(path, Palette.Text, style = Stroke(1.8.dp.toPx()))
    }
}
