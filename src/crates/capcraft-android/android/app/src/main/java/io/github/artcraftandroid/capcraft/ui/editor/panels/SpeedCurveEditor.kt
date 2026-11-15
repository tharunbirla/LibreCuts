package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.core.ClipView
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import org.json.JSONArray
import org.json.JSONObject

private const val CURVE_MIN = 0.1
private const val CURVE_MAX = 10.0

/** Where a speed sits on the editor's height, 0 at the bottom (slowest) to 1 at the top. */
private fun speedToY(s: Double) = (ln(s.coerceIn(CURVE_MIN, CURVE_MAX)) - ln(CURVE_MIN)) / (ln(CURVE_MAX) - ln(CURVE_MIN))
private fun yToSpeed(y: Double) = exp(ln(CURVE_MIN) + y.coerceIn(0.0, 1.0) * (ln(CURVE_MAX) - ln(CURVE_MIN)))

/** The source seconds a curve covers per timeline second, on average: the trapezoid rule. */
private fun meanOf(points: List<Pair<Double, Double>>): Double {
    var area = 0.0
    for (i in 1 until points.size) {
        val (a, sa) = points[i - 1]
        val (b, sb) = points[i]
        area += (b - a) * (sa + sb) / 2
    }
    return area.takeIf { it > 0 } ?: 1.0
}

/**
 * The speed curve drawn big, its points dragged up for faster and down for slower and along between
 * their neighbours. Add beat puts a point at the playhead; Delete beat takes the chosen one away.
 */
@Composable
fun SpeedCurveEditor(vm: EditorViewModel, clip: ClipView, start: List<Pair<Double, Double>>, onBack: () -> Unit) {
    var points by remember(clip.id) { mutableStateOf(start.sortedBy { it.first }) }
    var chosen by remember(clip.id) { mutableIntStateOf(-1) }
    val sourceSeconds = clip.durationUs * clip.speed / 1e6
    val playheadAt = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)

    fun send(next: List<Pair<Double, Double>>, gesture: String?) {
        points = next
        val curve = JSONArray().apply { next.forEach { (at, s) -> put(JSONObject().put("at", at).put("speed", s)) } }
        vm.command(JSONObject().put("op", "setClipSpeedCurve").put("clipId", clip.id).put("curve", curve), gesture)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable(onClick = onBack).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.ChevronLeft, Palette.Text, size = 18.dp)
                Text("Curves", style = Type.Small.copy(color = Palette.Text))
            }
            Spacer(Modifier.weight(1f))
            Text("%.1fs → %.1fs".format(sourceSeconds, sourceSeconds / meanOf(points)), style = Type.Small)
            Spacer(Modifier.weight(1f))
            val removable = chosen in 1 until points.size - 1
            Text(
                if (removable) "Delete beat" else "Add beat",
                style = Type.Small.copy(color = Palette.Text),
                modifier = Modifier.clickable {
                    if (removable) {
                        send(points.filterIndexed { i, _ -> i != chosen }, null)
                        chosen = -1
                    } else {
                        // a new point on the curve where the playhead is, unless one is already there
                        if (points.none { abs(it.first - playheadAt) < 0.03 }) {
                            val i = points.indexOfFirst { it.first > playheadAt }.coerceAtLeast(1)
                            val (a, sa) = points[i - 1]
                            val (b, sb) = points[i]
                            val f = ((playheadAt - a) / (b - a).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
                            val next = points.toMutableList().apply { add(i, playheadAt to sa + (sb - sa) * f) }
                            send(next, null)
                            chosen = i
                        }
                    }
                }.padding(8.dp),
            )
            Spacer(Modifier.width(4.dp))
        }
        val latest by rememberUpdatedState(points)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .padding(horizontal = 24.dp, vertical = 12.dp)
                .pointerInput(clip.id) {
                    detectTapGestures { tap ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        chosen = latest.indexOfFirst { (at, s) ->
                            Offset((at * w).toFloat(), ((1 - speedToY(s)) * h).toFloat()).minus(tap).getDistance() < 28.dp.toPx()
                        }
                    }
                }
                .pointerInput(clip.id) {
                    var dragging = -1
                    detectDragGestures(
                        onDragStart = { down ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            dragging = latest.withIndex().minByOrNull { (_, p) ->
                                Offset((p.first * w).toFloat(), ((1 - speedToY(p.second)) * h).toFloat()).minus(down).getDistance()
                            }?.index ?: -1
                            chosen = dragging
                        },
                        onDragEnd = { dragging = -1; vm.endGesture() },
                        onDragCancel = { dragging = -1; vm.endGesture() },
                    ) { change, _ ->
                        val i = dragging
                        if (i < 0) return@detectDragGestures
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val p = change.position
                        val now = latest
                        // the ends stay at the clip's ends; the others stay between their neighbours
                        val at = when (i) {
                            0 -> 0.0
                            now.size - 1 -> 1.0
                            else -> (p.x / w).toDouble().coerceIn(now[i - 1].first + 0.02, now[i + 1].first - 0.02)
                        }
                        var speed = yToSpeed(1 - (p.y / h).toDouble())
                        if (abs(speed - 1.0) < 0.06) speed = 1.0
                        val next = now.toMutableList().apply { set(i, at to (speed * 10).toInt() / 10.0) }
                        send(next, "curve-edit")
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun y(s: Double) = ((1 - speedToY(s)) * h).toFloat()
            for ((s, label) in listOf(10.0 to "10x", 1.0 to "1x", 0.1 to "0.1x")) {
                drawLine(Color(0x33FFFFFF), Offset(0f, y(s)), Offset(w, y(s)), 1.dp.toPx())
                drawContext.canvas.nativeCanvas.drawText(
                    label, -20.dp.toPx(), y(s) + 4.dp.toPx(),
                    android.graphics.Paint().apply { color = 0x99FFFFFF.toInt(); textSize = 9.dp.toPx(); isAntiAlias = true },
                )
            }
            val path = Path()
            points.forEachIndexed { i, (at, s) ->
                val x = (at * w).toFloat()
                if (i == 0) path.moveTo(x, y(s)) else path.lineTo(x, y(s))
            }
            drawPath(path, Color.White, style = Stroke(2.dp.toPx()))
            points.forEachIndexed { i, (at, s) ->
                val c = Offset((at * w).toFloat(), y(s))
                drawCircle(if (i == chosen) Palette.Accent else Color.White, radius = if (i == chosen) 7.dp.toPx() else 5.dp.toPx(), center = c)
            }
            val px = (playheadAt * w).toFloat()
            drawLine(Palette.Accent.copy(alpha = 0.8f), Offset(px, 0f), Offset(px, h), 1.5.dp.toPx())
        }
    }
}
