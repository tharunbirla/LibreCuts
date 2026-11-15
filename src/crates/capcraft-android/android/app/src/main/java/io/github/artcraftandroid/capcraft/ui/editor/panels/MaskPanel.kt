package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MASK = "concat.mask"

/** How far the feather handle travels from no softening to the most. */
private val FEATHER_REACH = 110.dp

/** The outline of the mask over the picture. */
private val Outline = Color(0xFFF8C630)

/** A mask shape: its name in the row and its number in the mask package. */
private class MaskShape(val name: String, val id: Int)

/** The shapes of the row, the package's numbers beside them. */
private val SHAPES = listOf(
    MaskShape("Horizontal", 0),
    MaskShape("Mirror", 1),
    MaskShape("Circle", 2),
    MaskShape("Rectangle", 3),
    MaskShape("Heart", 4),
    MaskShape("Stars", 5),
)

/** Where the selected clip's picture sits, kept by the surface for the panel's defaults. */
private object MaskView {
    var box by mutableStateOf<ClipBox?>(null)
}

/** The mask's parameters on [clipId], or null when it has none. */
private fun maskParams(vm: EditorViewModel, clipId: String): JSONObject? {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: return null
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        if (link.optString("id") == MASK) return link.optJSONObject("params") ?: JSONObject()
    }
    return null
}

/** Puts the mask on [clipId] with [change] made to its parameters, in its place in the chain, or takes it off (null). */
private fun setMask(vm: EditorViewModel, clipId: String, gesture: String?, change: (JSONObject.() -> Unit)?) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val next = JSONArray()
    var placed = false
    fun link(from: JSONObject?): JSONObject {
        val l = from ?: JSONObject().put("id", MASK)
        val p = l.optJSONObject("params") ?: JSONObject()
        change?.invoke(p)
        return l.put("params", p)
    }
    for (i in 0 until chain.length()) {
        val l = chain.getJSONObject(i)
        if (l.optString("id") != MASK) {
            next.put(l)
        } else if (change != null) {
            next.put(link(l))
            placed = true
        }
    }
    if (change != null && !placed) next.put(link(null))
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/** The picture's width over its height on screen, so a new circle comes out round. */
private fun pictureAspect(vm: EditorViewModel): Double {
    val t = vm.timeline ?: return 1.0
    val frame = t.width.toDouble() / t.height.toDouble().coerceAtLeast(1.0)
    val b = MaskView.box ?: return frame
    return b.width / b.height.coerceAtLeast(1e-6) * frame
}

/** A new mask, or one put back where it started: in the middle, round, sharp-edged. */
private fun JSONObject.centred(aspect: Double) {
    put("x", 50)
    put("y", 50)
    put("width", 50)
    put("height", (50 * aspect).coerceIn(1.0, 200.0))
    put("rotation", 0)
    put("feather", 0)
    put("round", 0)
}

/**
 * Mask: a row of shapes under the picture, the chosen one turning into Adjust; Invert beside the
 * title. The shape is moved, sized, turned and softened on the picture itself, or with the rulers
 * of Adjust.
 */
@Composable
fun MaskPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val params = remember(clip.json) { maskParams(vm, clip.id) }
    val shape = params?.optDouble("shape", 2.0)?.roundToInt() ?: -1
    var adjusting by remember(clip.id) { mutableStateOf(false) }
    if (adjusting && params != null) {
        MaskAdjust(vm, clip.id, shape, params) { adjusting = false }
        return
    }
    val inverted = (params?.optDouble("invert", 0.0) ?: 0.0) > 0.5
    fun pick(id: Int) = setMask(vm, clip.id, null) {
        if (!has("x")) centred(pictureAspect(vm))
        put("shape", id)
    }
    PanelFrame("Mask", vm, height = 168.dp, left = {
        val tint = if (inverted) Palette.Accent else Palette.DockText
        Row(
            Modifier
                .clickable {
                    if (params == null) vm.message = "Choose a shape first" else setMask(vm, clip.id, null) { put("invert", if (inverted) 0 else 1) }
                }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(LucideIcons.FlipHorizontal2, tint, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Invert", style = Type.Body.copy(color = tint))
        }
    }) {
        Row(
            Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MaskTile("None", shape < 0, adjust = false, onClick = { setMask(vm, clip.id, null, null) }) {
                Glyph(LucideIcons.Ban, Palette.DockText, size = 24.dp)
            }
            SHAPES.forEach { s ->
                val chosen = shape == s.id
                MaskTile(s.name, chosen, adjust = chosen, onClick = { if (chosen) adjusting = true else pick(s.id) }) { MaskGlyph(s.id) }
            }
        }
    }
}

/** One shape of the row: its drawing in a dark square, ringed white when chosen, Adjust on it then. */
@Composable
private fun MaskTile(label: String, selected: Boolean, adjust: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Column(Modifier.width(78.dp).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF3A3A3A))
                .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (adjust) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(LucideIcons.SlidersHorizontal, Color.White, size = 20.dp)
                    Spacer(Modifier.height(2.dp))
                    Text("Adjust", style = Type.Tiny.copy(color = Color.White, fontWeight = FontWeight.SemiBold))
                }
            } else {
                glyph()
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Type.Tiny.copy(color = if (selected) Palette.Text else Palette.TextSecondary), maxLines = 1)
    }
}

/** A shape's drawing for its tile: a frame standing for the picture, the shape in it. */
@Composable
private fun MaskGlyph(shape: Int) {
    Canvas(Modifier.size(30.dp)) {
        val w = size.width
        val h = size.height
        val line = 1.5.dp.toPx()
        val c = Color(0xFFE6E6E6)
        val shade = Color(0x55FFFFFF)
        val corner = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        drawRoundRect(c, Offset(line / 2, line / 2), Size(w - line, h - line), corner, style = Stroke(line))
        when (shape) {
            0 -> {
                drawRect(shade, Offset(line, line), Size(w - 2 * line, h / 2 - line))
                drawLine(c, Offset(0f, h / 2), Offset(w, h / 2), line)
            }
            1 -> {
                drawRect(shade, Offset(line, h * 0.3f), Size(w - 2 * line, h * 0.4f))
                drawLine(c, Offset(0f, h * 0.3f), Offset(w, h * 0.3f), line)
                drawLine(c, Offset(0f, h * 0.7f), Offset(w, h * 0.7f), line)
            }
            2 -> drawCircle(c, w * 0.24f, style = Stroke(line))
            3 -> drawRect(c, Offset(w * 0.27f, h * 0.27f), Size(w * 0.46f, h * 0.46f), style = Stroke(line))
            else -> {
                val pts = outline(shape, w * 0.26f, w * 0.26f, 0f, 0f, 1f)
                val p = Path()
                pts.forEachIndexed { i, q -> if (i == 0) p.moveTo(w / 2 + q.x, h / 2 + q.y) else p.lineTo(w / 2 + q.x, h / 2 + q.y) }
                p.close()
                drawPath(p, c, style = Stroke(line))
            }
        }
    }
}

/**
 * The outline of a closed shape, as points around its centre: [hw] and [hh] its half sizes,
 * [round] its corners' radius, [reach] how far a line runs. The heart and the star are sized by the
 * larger half, as the mask package draws them.
 */
private fun outline(shape: Int, hw: Float, hh: Float, round: Float, reach: Float, scale: Float): List<Offset> {
    val out = ArrayList<Offset>()
    when (shape) {
        2 -> for (i in 0 until 72) {
            val a = i / 72f * 2 * Math.PI.toFloat()
            out.add(Offset(hw * cos(a), hh * sin(a)))
        }
        3 -> {
            val r = round.coerceIn(0f, min(hw, hh))
            val corners = listOf(Offset(hw - r, -hh + r) to -90f, Offset(hw - r, hh - r) to 0f, Offset(-hw + r, hh - r) to 90f, Offset(-hw + r, -hh + r) to 180f)
            for ((c, from) in corners) for (k in 0..8) {
                val a = Math.toRadians((from + k * 11.25f).toDouble()).toFloat()
                out.add(Offset(c.x + r * cos(a), c.y + r * sin(a)))
            }
        }
        4 -> {
            // a heart on its point, one unit tall from the point to the dip, y up
            val s = 2 * max(hw, hh) * scale
            val unit = ArrayList<Offset>()
            unit.add(Offset(0f, 0f))
            val r = (Math.sqrt(2.0) / 4).toFloat()
            for (k in 0..24) {
                val a = Math.toRadians(-45.0 + k * 7.5).toFloat()
                unit.add(Offset(0.25f + r * cos(a), 0.75f + r * sin(a)))
            }
            for (k in 0..24) {
                val a = Math.toRadians(45.0 + k * 7.5).toFloat()
                unit.add(Offset(-0.25f + r * cos(a), 0.75f + r * sin(a)))
            }
            unit.forEach { out.add(Offset(it.x * s, (0.5f - it.y) * s)) }
        }
        5 -> {
            val s = max(hw, hh) * scale
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) s else s * 0.45f
                val a = Math.toRadians(i * 36.0).toFloat()
                out.add(Offset(r * sin(a), -r * cos(a)))
            }
        }
        else -> {
            out.add(Offset(-reach, 0f))
            out.add(Offset(reach, 0f))
        }
    }
    return out
}

// ── Adjust ──

/** The Adjust sheet: Position, Size, Rotate and Feather in tabs (and Round for a rectangle), and Reset. */
@Composable
private fun MaskAdjust(vm: EditorViewModel, clipId: String, shape: Int, params: JSONObject, onClose: () -> Unit) {
    val tabs = when (shape) {
        0 -> listOf("Position", "Rotate", "Feather")
        3 -> listOf("Position", "Size", "Rotate", "Feather", "Round")
        else -> listOf("Position", "Size", "Rotate", "Feather")
    }
    var tab by remember { mutableIntStateOf(0) }
    val name = tabs[tab.coerceAtMost(tabs.size - 1)]
    fun send(gesture: String, key: String, value: Double) = setMask(vm, clipId, gesture) { put(key, value) }
    fun num(key: String, default: Double) = params.optDouble(key, default).toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(Palette.DockSelected)
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(start = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { i, t ->
                Text(
                    t,
                    style = Type.Body.copy(
                        color = if (t == name) Palette.Text else Palette.TextSecondary,
                        fontWeight = if (t == name) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    modifier = Modifier.clickable { tab = i }.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.CircleChevronDown, Palette.DockText, size = 22.dp, description = "Close")
            }
        }
        Column(Modifier.fillMaxWidth().height(110.dp), verticalArrangement = Arrangement.Center) {
            when (name) {
                "Position" -> {
                    RulerRow("X axis", num("x", 50.0) - 50, -50f..50f, { send("mask-x", "x", it + 50.0) }, vm::endGesture)
                    RulerRow("Y axis", 50 - num("y", 50.0), -50f..50f, { send("mask-y", "y", 50.0 - it) }, vm::endGesture)
                }
                "Size" -> {
                    if (shape != 1) RulerRow("X axis", num("width", 60.0), 1f..200f, { send("mask-width", "width", it.toDouble()) }, vm::endGesture)
                    RulerRow("Y axis", num("height", 60.0), 1f..200f, { send("mask-height", "height", it.toDouble()) }, vm::endGesture)
                }
                "Rotate" -> RulerRow(null, num("rotation", 0.0), -180f..180f, { send("mask-rotation", "rotation", it.toDouble()) }, vm::endGesture, unit = "°")
                else -> {
                    // Feather, or the rectangle's corners
                    val key = if (name == "Round") "round" else "feather"
                    var held by remember(key) { mutableStateOf<Float?>(null) }
                    val feather = held ?: num(key, 0.0)
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CapSlider(feather, 0f..100f, null, {
                            held = it
                            send("mask-$key", key, it.toDouble())
                        }, {
                            held = null
                            vm.endGesture()
                        }, Modifier.weight(1f))
                        Text(
                            "${feather.roundToInt()}",
                            style = Type.Small.copy(color = Palette.Text),
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(40.dp),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.clickable { setMask(vm, clipId, null) { centred(pictureAspect(vm)) } }.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(LucideIcons.RotateCcw, Palette.DockText, size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text("Reset", style = Type.Body.copy(color = Palette.DockText))
            }
        }
    }
}

/** A labelled ruler with its number at the right; the number follows the finger while it moves. */
@Composable
internal fun RulerRow(
    label: String?,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    unit: String = "",
) {
    var held by remember { mutableStateOf<Float?>(null) }
    val shown = held ?: value
    Row(Modifier.fillMaxWidth().height(46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        if (label != null) Text(label, style = Type.Small.copy(color = Palette.Text, fontSize = 13.sp), modifier = Modifier.width(56.dp))
        TickRuler(shown, range, {
            held = it
            onChange(it)
        }, {
            held = null
            onDone()
        }, Modifier.weight(1f))
        Text(
            "${shown.roundToInt()}$unit",
            style = Type.Small.copy(color = Palette.Text, fontSize = 13.sp),
            textAlign = TextAlign.End,
            modifier = Modifier.width(44.dp),
        )
    }
}

/**
 * A ruler of ticks, one a step and a taller one every fifth, sliding under a fixed cyan needle:
 * dragging it left raises the value. Ticks fade towards the ends.
 */
@Composable
fun TickRuler(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latest by rememberUpdatedState(value)
    Canvas(
        modifier
            .height(36.dp)
            .pointerInput(range) {
                val gap = 7.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = latest
                    var sent = start
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val v = (start - (c.position.x - down.position.x) / gap).roundToInt().toFloat().coerceIn(range)
                        if (v != sent) {
                            sent = v
                            onChange(v)
                        }
                        c.consume()
                    }
                    onDone()
                }
            },
    ) {
        val gap = 7.dp.toPx()
        val mid = size.width / 2
        val cy = size.height / 2
        // a faint band behind the ticks, fading out at either end, as the reference's rulers lie on
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to Color.Transparent,
                0.12f to Color(0x14FFFFFF),
                0.88f to Color(0x14FFFFFF),
                1f to Color.Transparent,
            ),
        )
        val reach = (mid / gap).toInt() + 1
        val lo = max(ceil(range.start).toInt(), floor(value).toInt() - reach)
        val hi = min(floor(range.endInclusive).toInt(), ceil(value).toInt() + reach)
        for (k in lo..hi) {
            val x = mid + (k - value) * gap
            if (x < 0f || x > size.width) continue
            val fade = 1f - abs(x - mid) / mid * 0.7f
            val major = k % 5 == 0
            val h = if (major) 13.dp.toPx() else 8.dp.toPx()
            drawLine(
                Color.White.copy(alpha = (if (major) 0.85f else 0.4f) * fade),
                Offset(x, cy - h / 2),
                Offset(x, cy + h / 2),
                1.dp.toPx(),
            )
        }
        drawLine(Palette.Accent, Offset(mid, cy - 12.dp.toPx()), Offset(mid, cy + 12.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}

// ── On the picture ──

/** The mask placed on the preview: from the shape's own space, in screen pixels around its centre, to the screen and back. */
private class MaskPlace(box: ClipBox, w: Float, h: Float, val p: JSONObject) {
    val bw = (box.width * w).toFloat()
    val bh = (box.height * h).toFloat()
    val centre = Offset((box.cx * w).toFloat(), (box.cy * h).toFloat())
    val turn = Math.toRadians(box.rotation).toFloat()
    val m = Offset(((p.optDouble("x", 50.0) / 100 - 0.5) * bw).toFloat(), ((p.optDouble("y", 50.0) / 100 - 0.5) * bh).toFloat())
    val degrees = p.optDouble("rotation", 0.0).toFloat()
    val a = Math.toRadians(degrees.toDouble()).toFloat()
    val hw = (p.optDouble("width", 60.0) / 200 * bw).toFloat()
    val hh = (p.optDouble("height", 60.0) / 200 * bh).toFloat()
    val shape = p.optDouble("shape", 2.0).roundToInt()
    val round = (p.optDouble("round", 0.0) / 100).toFloat() * min(hw, hh)
    val feather = p.optDouble("feather", 0.0).toFloat()

    /** How far the shape reaches right, up and down of its centre. */
    val right: Float
    val top: Float
    val bottom: Float

    init {
        val big = max(hw, hh)
        when (shape) {
            0 -> { right = 0f; top = 0f; bottom = 0f }
            4 -> { right = 1.208f * big; top = 1.208f * big; bottom = big }
            5 -> { right = 0.951f * big; top = big; bottom = 0.809f * big }
            else -> { right = hw; top = hh; bottom = hh }
        }
    }

    fun screen(q: Offset): Offset = rot(rot(q, a) + m, turn) + centre
    fun local(o: Offset): Offset = rot(rot(o - centre, -turn) - m, -a)
}

private fun rot(v: Offset, a: Float) = Offset(v.x * cos(a) - v.y * sin(a), v.x * sin(a) + v.y * cos(a))

/** A handle on the picture: what it changes and where it sits in the shape's space. */
private class Handle(val kind: String, val at: Offset)

private fun handles(place: MaskPlace, gap: Float, featherReach: Float, roundReach: Float): List<Handle> = buildList {
    val s = place.shape
    if (s in 1..5) add(Handle("height", Offset(0f, -(place.top + gap))))
    if (s in 2..5) add(Handle("width", Offset(place.right + gap, 0f)))
    add(Handle("feather", Offset(0f, place.bottom + gap + place.feather / 100 * featherReach)))
    if (s == 3) {
        val k = gap * 0.7f + place.p.optDouble("round", 0.0).toFloat() / 100 * roundReach
        add(Handle("round", Offset(-place.hw - k, -place.hh - k)))
    }
}

private fun normalise(d: Double): Double {
    var r = d % 360.0
    if (r > 180) r -= 360
    if (r <= -180) r += 360
    return r
}

/**
 * The mask over the picture while its panel is open: the shape's outline and its handles. One
 * finger moves the shape, two turn and size it; the handles stretch it, soften its edge, or round
 * a rectangle's corners.
 */
@Composable
fun MaskSurface(vm: EditorViewModel, modifier: Modifier) {
    val clip = vm.selectedClip ?: return
    val session = vm.session
    val revision = vm.timeline?.revision ?: 0L
    val playhead = vm.playhead
    val playing = vm.playing
    val box by produceState<ClipBox?>(null, session, revision, playhead, playing, clip.id) {
        val s = session
        if (s == null || playing) {
            value = null
            return@produceState
        }
        value = withContext(Threads.commands) { runCatching { s.clipBoxes(playhead) }.getOrDefault(emptyList()) }.firstOrNull { it.clipId == clip.id }
    }
    SideEffect { box?.let { MaskView.box = it } }
    val saved = remember(clip.json) { maskParams(vm, clip.id) }
    var live by remember { mutableStateOf<JSONObject?>(null) }
    val latestBox by rememberUpdatedState(box)
    val latestParams by rememberUpdatedState(saved)
    val upDown = rememberVectorPainter(LucideIcons.MoveVertical)
    val across = rememberVectorPainter(LucideIcons.MoveHorizontal)
    val soften = rememberVectorPainter(LucideIcons.ChevronsDown)
    Canvas(
        modifier.pointerInput(clip.id, session) {
            val gap = 22.dp.toPx()
            val featherReach = FEATHER_REACH.toPx()
            val roundReach = 36.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val b = latestBox ?: return@awaitEachGesture
                val start = latestParams ?: return@awaitEachGesture
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val first = MaskPlace(b, w, h, start)
                val grab = handles(first, gap, featherReach, roundReach)
                    .map { it to (first.screen(it.at) - down.position).getDistance() }
                    .filter { it.second < 30.dp.toPx() }
                    .minByOrNull { it.second }?.first?.kind
                down.consume()
                val current = JSONObject(start.toString())
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.none { it.pressed }) break
                    val place = MaskPlace(b, w, h, current)
                    if (grab == null) {
                        val pan = rot(e.calculatePan(), -place.turn)
                        current.put("x", (current.optDouble("x", 50.0) + pan.x / place.bw * 100).coerceIn(0.0, 100.0))
                        current.put("y", (current.optDouble("y", 50.0) + pan.y / place.bh * 100).coerceIn(0.0, 100.0))
                        val zoom = e.calculateZoom()
                        if (zoom != 1f && place.shape != 0) {
                            current.put("width", (current.optDouble("width", 60.0) * zoom).coerceIn(1.0, 200.0))
                            current.put("height", (current.optDouble("height", 60.0) * zoom).coerceIn(1.0, 200.0))
                        }
                        val turned = e.calculateRotation()
                        if (turned != 0f) current.put("rotation", normalise(current.optDouble("rotation", 0.0) + turned))
                    } else {
                        val finger = e.changes.firstOrNull { it.id == down.id } ?: break
                        val q = place.local(finger.position)
                        val uniform = place.shape == 4 || place.shape == 5
                        // a handle's distance from the centre is the shape's reach there; the heart
                        // and the star keep their proportions
                        fun stretch(key: String, extent: Float, wanted: Float, whole: Float) {
                            val want = wanted.coerceAtLeast(2f)
                            if (uniform) {
                                val f = (want / extent.coerceAtLeast(1f)).toDouble()
                                current.put("width", (current.optDouble("width", 60.0) * f).coerceIn(1.0, 200.0))
                                current.put("height", (current.optDouble("height", 60.0) * f).coerceIn(1.0, 200.0))
                            } else {
                                current.put(key, (want / whole * 200).toDouble().coerceIn(1.0, 200.0))
                            }
                        }
                        when (grab) {
                            "height" -> stretch("height", place.top, -q.y - gap, place.bh)
                            "width" -> stretch("width", place.right, q.x - gap, place.bw)
                            "feather" -> current.put("feather", ((q.y - place.bottom - gap) / featherReach * 100).toDouble().coerceIn(0.0, 100.0))
                            "round" -> {
                                val k = ((-q.x - place.hw) + (-q.y - place.hh)) / 2
                                current.put("round", ((k - gap * 0.7f) / roundReach * 100).toDouble().coerceIn(0.0, 100.0))
                            }
                        }
                    }
                    val next = JSONObject(current.toString())
                    live = next
                    setMask(vm, clip.id, "mask-" + (grab ?: "move")) { next.keys().forEach { k -> put(k, next.get(k)) } }
                    e.changes.forEach { it.consume() }
                }
                live = null
                vm.endGesture()
            }
        },
    ) {
        val b = box ?: return@Canvas
        val params = live ?: saved ?: return@Canvas
        val place = MaskPlace(b, size.width, size.height, params)
        val stroke = 1.5.dp.toPx()
        clipRect {
            val pts = outline(place.shape, place.hw, place.hh, place.round, hypot(size.width, size.height), 1f)
            when (place.shape) {
                0 -> drawLine(Outline, place.screen(pts[0]), place.screen(pts[1]), stroke)
                1 -> {
                    val reach = hypot(size.width, size.height)
                    drawLine(Outline, place.screen(Offset(-reach, -place.hh)), place.screen(Offset(reach, -place.hh)), stroke)
                    drawLine(Outline, place.screen(Offset(-reach, place.hh)), place.screen(Offset(reach, place.hh)), stroke)
                }
                else -> {
                    val path = Path()
                    pts.forEachIndexed { i, q ->
                        val o = place.screen(q)
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    path.close()
                    drawPath(path, Outline, style = Stroke(stroke))
                }
            }
        }
        val gap = 22.dp.toPx()
        val turnDegrees = place.degrees + b.rotation.toFloat()
        for (hd in handles(place, gap, FEATHER_REACH.toPx(), 36.dp.toPx())) {
            val at = place.screen(hd.at)
            drawCircle(Color.White, 12.dp.toPx(), at)
            when (hd.kind) {
                "round" -> rotate(turnDegrees, at) { cornerMark(at) }
                else -> {
                    val painter = when (hd.kind) {
                        "height" -> upDown
                        "width" -> across
                        else -> soften
                    }
                    val icon = 14.dp.toPx()
                    rotate(turnDegrees, at) {
                        translate(at.x - icon / 2, at.y - icon / 2) {
                            with(painter) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(Color(0xFF161616))) }
                        }
                    }
                }
            }
        }
    }
}

/** The round-corners handle's mark: a corner bent round. */
private fun DrawScope.cornerMark(at: Offset) {
    val r = 5.dp.toPx()
    val p = Path().apply {
        moveTo(at.x - r, at.y + r)
        lineTo(at.x - r, at.y)
        quadraticTo(at.x - r, at.y - r, at.x, at.y - r)
        lineTo(at.x + r, at.y - r)
    }
    drawPath(p, Color(0xFF161616), style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
}
