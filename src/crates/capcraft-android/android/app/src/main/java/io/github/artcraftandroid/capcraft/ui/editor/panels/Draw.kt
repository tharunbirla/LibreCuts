package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.BlurMaskFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One stroke of a drawing: its points as fractions of the frame, its brush, colour, opacity and
 * width (a fraction of the frame's width), or an eraser's sweep.
 */
class DrawStroke(val points: List<Offset>, val brush: String, val color: Color, val alpha: Float, val width: Float, val erase: Boolean)

/** The drawing being made, kept while the Draw sheet is open. */
object Drawing {
    val strokes = mutableStateListOf<DrawStroke>()
    var brush by mutableStateOf("pen")
    var color by mutableStateOf(Color(0xFFFFFFFF))
    var alpha by mutableFloatStateOf(1f)
    var width by mutableFloatStateOf(0.012f)
    var erasing by mutableStateOf(false)
}

/** A brush of our own: its key, its name, and which tab it is under. */
private class Brush(val key: String, val name: String, val pattern: Boolean = false)

private val BRUSHES = listOf(
    Brush("pen", "Pen"),
    Brush("marker", "Marker"),
    Brush("glow", "Glow"),
    Brush("outline", "Outline"),
    Brush("dashed", "Dashed"),
    Brush("dotted", "Dotted"),
    Brush("chalk", "Chalk"),
    Brush("arrow", "Arrow"),
    Brush("hearts", "Hearts", pattern = true),
    Brush("stars", "Stars", pattern = true),
    Brush("bubbles", "Bubbles", pattern = true),
    Brush("rainbow", "Rainbow", pattern = true),
)

/** [stroke]'s points scaled to a [w] by [h] picture, as a path. */
private fun pathOf(stroke: DrawStroke, w: Float, h: Float): android.graphics.Path {
    val path = android.graphics.Path()
    stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
    if (stroke.points.size == 1) path.lineTo(stroke.points[0].x * w + 0.5f, stroke.points[0].y * h)
    return path
}

/** Points every [step] pixels along [stroke] on a [w] by [h] picture, with the way the stroke runs there (radians). */
private fun along(stroke: DrawStroke, w: Float, h: Float, step: Float): List<Triple<Float, Float, Float>> {
    val out = mutableListOf<Triple<Float, Float, Float>>()
    var carried = 0f
    for (i in 1 until stroke.points.size) {
        val ax = stroke.points[i - 1].x * w
        val ay = stroke.points[i - 1].y * h
        val bx = stroke.points[i].x * w
        val by = stroke.points[i].y * h
        val len = hypot(bx - ax, by - ay)
        if (len <= 0f) continue
        val turn = atan2(by - ay, bx - ax)
        var at = step - carried
        while (at <= len) {
            out += Triple(ax + (bx - ax) * at / len, ay + (by - ay) * at / len, turn)
            at += step
        }
        carried = (carried + len) % step
    }
    if (out.isEmpty() && stroke.points.isNotEmpty()) out += Triple(stroke.points[0].x * w, stroke.points[0].y * h, 0f)
    return out
}

/** A heart of size [s] centred on ([x], [y]), as a path. */
private fun heartPath(x: Float, y: Float, s: Float) = android.graphics.Path().apply {
    moveTo(x, y + s * 0.35f)
    cubicTo(x - s * 0.9f, y - s * 0.25f, x - s * 0.35f, y - s * 0.85f, x, y - s * 0.3f)
    cubicTo(x + s * 0.35f, y - s * 0.85f, x + s * 0.9f, y - s * 0.25f, x, y + s * 0.35f)
    close()
}

/** A five-pointed star of size [s] centred on ([x], [y]), turned by [turn] radians. */
private fun starPath(x: Float, y: Float, s: Float, turn: Float) = android.graphics.Path().apply {
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) s * 0.6f else s * 0.25f
        val a = turn - Math.PI.toFloat() / 2 + i * Math.PI.toFloat() / 5
        val px = x + r * cos(a)
        val py = y + r * sin(a)
        if (i == 0) moveTo(px, py) else lineTo(px, py)
    }
    close()
}

/**
 * Draws [stroke] onto [canvas], a [w] by [h] picture: each brush its own way, at its opacity as a
 * whole (so where it crosses itself it is no darker), or the eraser clearing what is under it.
 * The live drawing and the picture kept are both drawn by this.
 */
private fun paintStroke(canvas: android.graphics.Canvas, stroke: DrawStroke, w: Float, h: Float) {
    val width = stroke.width * w
    val argb = stroke.color.toArgb()
    fun line(paintWidth: Float, colour: Int, build: Paint.() -> Unit = {}) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = paintWidth
        color = colour
        build()
    }
    if (stroke.erase) {
        canvas.drawPath(pathOf(stroke, w, h), line(width * 2.5f, 0) { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        return
    }
    val layer = canvas.saveLayerAlpha(0f, 0f, w, h, (stroke.alpha * 255).toInt().coerceIn(0, 255))
    val path = pathOf(stroke, w, h)
    when (stroke.brush) {
        "marker" -> canvas.drawPath(path, line(width * 1.8f, argb) { strokeCap = Paint.Cap.SQUARE; alpha = 150 })
        "glow" -> {
            canvas.drawPath(path, line(width * 2.6f, argb) { maskFilter = BlurMaskFilter(width * 1.4f, BlurMaskFilter.Blur.NORMAL) })
            canvas.drawPath(path, line(width * 1.1f, argb))
            canvas.drawPath(path, line(width * 0.45f, 0xFFFFFFFF.toInt()))
        }
        "outline" -> {
            canvas.drawPath(path, line(width * 1.7f, argb))
            canvas.drawPath(path, line(width * 0.75f, 0xFFFFFFFF.toInt()))
        }
        "dashed" -> canvas.drawPath(path, line(width, argb) { strokeCap = Paint.Cap.BUTT; pathEffect = DashPathEffect(floatArrayOf(width * 2.4f, width * 1.6f), 0f) })
        "dotted" -> canvas.drawPath(path, line(width, argb) { pathEffect = DashPathEffect(floatArrayOf(0.1f, width * 2f), 0f) })
        "chalk" -> {
            // a few thin, faint strokes beside each other, as chalk drags
            for (k in 0 until 5) {
                val dx = (k - 2) * width * 0.18f
                val dy = ((k * 7) % 5 - 2) * width * 0.12f
                val shifted = android.graphics.Path(path).apply { offset(dx, dy) }
                canvas.drawPath(shifted, line(width * 0.35f, argb) { alpha = 120 })
            }
        }
        "arrow" -> {
            canvas.drawPath(path, line(width, argb))
            val pts = stroke.points
            if (pts.size >= 2) {
                val end = pts.last()
                // the way the stroke ends, taken over its last stretch so a wobble does not turn the head
                val back = pts.lastOrNull { hypot((it.x - end.x) * w, (it.y - end.y) * h) > width * 3 } ?: pts.first()
                val turn = atan2((end.y - back.y) * h, (end.x - back.x) * w)
                val ex = end.x * w
                val ey = end.y * h
                val head = width * 4f
                val wing = Math.toRadians(28.0).toFloat()
                val arrowhead = android.graphics.Path().apply {
                    moveTo(ex - head * cos(turn - wing), ey - head * sin(turn - wing))
                    lineTo(ex, ey)
                    lineTo(ex - head * cos(turn + wing), ey - head * sin(turn + wing))
                }
                canvas.drawPath(arrowhead, line(width, argb))
            }
        }
        "hearts", "stars", "bubbles", "rainbow" -> {
            val size = width * 2.2f
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
            along(stroke, w, h, size * 1.25f).forEachIndexed { i, (x, y, turn) ->
                when (stroke.brush) {
                    "hearts" -> canvas.drawPath(heartPath(x, y, size), fill.apply { color = argb })
                    "stars" -> canvas.drawPath(starPath(x, y, size, turn), fill.apply { color = argb })
                    "bubbles" -> {
                        val r = size * (0.3f + ((i * 37) % 10) / 20f)
                        canvas.drawCircle(x, y, r, line(width * 0.3f, argb))
                        canvas.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.18f, fill.apply { color = 0xCCFFFFFF.toInt() })
                    }
                    else -> canvas.drawCircle(x, y, size * 0.45f, fill.apply { color = android.graphics.Color.HSVToColor(floatArrayOf((i * 24f) % 360f, 0.8f, 1f)) })
                }
            }
        }
        else -> canvas.drawPath(path, line(width, argb))
    }
    canvas.restoreToCount(layer)
}

/**
 * The surface over the picture while drawing: a finger draws a stroke with the chosen brush, or
 * wipes with the eraser. Laid exactly over the frame, so the strokes land where they will show.
 */
@Composable
fun DrawSurface(modifier: Modifier) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    Canvas(
        modifier
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    val points = mutableListOf(Offset(down.position.x / w, down.position.y / h))
                    live = points.toList()
                    while (true) {
                        val event = awaitPointerEvent()
                        val c = event.changes.first()
                        if (!c.pressed) break
                        points += Offset(c.position.x / w, c.position.y / h)
                        live = points.toList()
                        c.consume()
                    }
                    Drawing.strokes += DrawStroke(points.toList(), Drawing.brush, Drawing.color, Drawing.alpha, Drawing.width, Drawing.erasing)
                    live = emptyList()
                }
            },
    ) {
        val all = Drawing.strokes.toList() + listOfNotNull(
            live.takeIf { it.isNotEmpty() }?.let { DrawStroke(it, Drawing.brush, Drawing.color, Drawing.alpha, Drawing.width, Drawing.erasing) },
        )
        drawIntoCanvas { c -> all.forEach { paintStroke(c.nativeCanvas, it, size.width, size.height) } }
    }
}

/** The drawing as a transparent picture the size of the frame, written into the project. */
private fun render(projectPath: String, frameW: Int, frameH: Int): File? = runCatching {
    val b = android.graphics.Bitmap.createBitmap(frameW, frameH, android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(b)
    Drawing.strokes.forEach { paintStroke(c, it, frameW.toFloat(), frameH.toFloat()) }
    val dir = File(projectPath, "media").apply { mkdirs() }
    val file = File(dir, "drawing-${System.currentTimeMillis()}.png")
    FileOutputStream(file).use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    file
}.getOrNull()

/** A brush's tile: a short sample stroke drawn with it in the chosen colour. */
@Composable
private fun BrushTile(brush: Brush, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val sample = DrawStroke(
                (0..12).map { i -> Offset(i / 12f, 0.5f + 0.28f * sin(i / 12f * 2 * Math.PI.toFloat())) },
                brush.key,
                Drawing.color,
                1f,
                if (brush.pattern) 0.11f else 0.075f,
                false,
            )
            drawIntoCanvas { c -> paintStroke(c.nativeCanvas, sample, size.width, size.height) }
        }
    }
}

/**
 * Drawing over the video, from the Text group as the reference has it: Basic and Pattern brushes
 * across the top, the colours, then Size and Opacity with the pen and the eraser at the right. The
 * tick puts the drawing on the timeline at the playhead as a picture over the video, where it was
 * drawn; the editor's undo takes back the last stroke while the sheet is open.
 */
@Composable
fun DrawPanel(vm: EditorViewModel) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(if (BRUSHES.first { it.key == Drawing.brush }.pattern) 1 else 0) }
    var setting by remember { mutableIntStateOf(0) }
    fun finish() {
        vm.pickColor = null
        if (Drawing.strokes.isEmpty()) {
            vm.panel = null
            return
        }
        val t = vm.timeline ?: return
        scope.launch {
            val file = withContext(Threads.thumbnails) { render(vm.path, t.width.toInt(), t.height.toInt()) }
            Drawing.strokes.clear()
            vm.panel = null
            if (file == null) vm.message = "Could not keep the drawing" else vm.addDrawing(file)
        }
    }
    PanelFrame("Draw", vm, height = 290.dp, titleOnTop = true, header = {
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Basic", "Pattern").forEachIndexed { i, name ->
                Text(
                    name,
                    style = Type.Body.copy(
                        fontSize = 15.sp,
                        color = if (i == tab) Palette.Text else Palette.TextSecondary,
                        fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    modifier = Modifier.clickable { tab = i }.padding(horizontal = 6.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clickable(enabled = Drawing.strokes.isNotEmpty()) { Drawing.strokes.removeAt(Drawing.strokes.lastIndex) },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Undo2, if (Drawing.strokes.isEmpty()) Palette.Disabled else Palette.DockText, size = 20.dp, description = "Undo the last stroke") }
        }
    }, onDone = ::finish) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BRUSHES.filter { it.pattern == (tab == 1) }.forEach { b ->
                    BrushTile(b, !Drawing.erasing && Drawing.brush == b.key) {
                        Drawing.brush = b.key
                        Drawing.erasing = false
                    }
                }
            }
            SwatchRow(hexOf(Drawing.color.toArgb() and 0xFFFFFF), vm = vm) { hex ->
                hex?.let { runCatching { Drawing.color = Color(android.graphics.Color.parseColor(it)) } }
                Drawing.erasing = false
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("Size", "Opacity").forEachIndexed { i, name ->
                    Text(
                        name,
                        style = Type.Small.copy(fontSize = 13.sp, color = if (i == setting) Palette.Accent else Palette.TextSecondary),
                        modifier = Modifier.clickable { setting = i }.padding(end = 18.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // the pen and the eraser, side by side in one pill
                Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xFF333333))) {
                    Box(
                        Modifier.size(44.dp, 32.dp).background(if (!Drawing.erasing) Color(0xFF4A4A4A) else Color.Transparent).clickable { Drawing.erasing = false },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Pencil, if (!Drawing.erasing) Palette.Accent else Palette.DockText, size = 17.dp, description = "Pen") }
                    Box(
                        Modifier.size(44.dp, 32.dp).background(if (Drawing.erasing) Color(0xFF4A4A4A) else Color.Transparent).clickable { Drawing.erasing = true },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Eraser, if (Drawing.erasing) Palette.Accent else Palette.DockText, size = 17.dp, description = "Eraser") }
                }
            }
            if (setting == 0) {
                CapSlider(Drawing.width * 1000, 3f..40f, { "${it.toInt()}" }, { Drawing.width = it / 1000f }, {}, filled = true)
            } else {
                CapSlider(Drawing.alpha * 100, 10f..100f, { "${it.toInt()}" }, { Drawing.alpha = it / 100f }, {}, filled = true)
            }
        }
    }
}
