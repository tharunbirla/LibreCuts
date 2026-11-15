package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject

/** The crop shapes, width over height; 0 is a free crop. */
private val RATIOS = listOf(
    "Custom" to 0.0,
    "9:16" to 9.0 / 16,
    "16:9" to 16.0 / 9,
    "1:1" to 1.0,
    "4:3" to 4.0 / 3,
    "2:1" to 2.0,
    "5.8\"" to 1125.0 / 2436,
    "2.35:1" to 2.35,
    "3:4" to 3.0 / 4,
    "1.85:1" to 1.85,
)

/** How far the picture can be straightened either way, in degrees. */
private const val STRAIGHTEN = 45f

/** The effect that turns a picture inside its own frame. */
private const val STRAIGHTEN_ID = "capcraft.straighten"

/** How much a picture turned by [degrees] must grow to still fill a [aspect]-shaped box. */
private fun fill(degrees: Float, aspect: Double): Double {
    val a = Math.toRadians(abs(degrees).toDouble())
    return cos(a) + max(aspect, 1 / aspect) * sin(a)
}

private fun clock(us: Long): String {
    val s = (us / 1_000_000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Crop, as a page of its own: the clip's picture with a frame to drag by its corners, edges or
 * middle, a scrubber to see another moment, a ruler that straightens the picture inside the
 * frame (grown so no corner shows), and shapes the frame can keep. Nothing changes until the
 * tick; then the crop and the turn are one undo step.
 */
@Composable
fun CropPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val json = remember(clip.id) { vm.clipJson(clip.id) ?: JSONObject() }
    val picW = clip.mediaWidth.toDouble().coerceAtLeast(1.0)
    val picH = clip.mediaHeight.toDouble().coerceAtLeast(1.0)
    // the frame as fractions of the picture: left, top, right, bottom
    val start = remember(clip.id) {
        val c = json.optJSONObject("crop")
        Rect(
            c?.optDouble("left", 0.0)?.toFloat() ?: 0f,
            c?.optDouble("top", 0.0)?.toFloat() ?: 0f,
            1f - (c?.optDouble("right", 0.0)?.toFloat() ?: 0f),
            1f - (c?.optDouble("bottom", 0.0)?.toFloat() ?: 0f),
        )
    }
    var frame by remember(clip.id) { mutableStateOf(start) }
    var ratio by remember(clip.id) { mutableIntStateOf(0) }
    val chain = json.optJSONArray("videoEffects") ?: JSONArray()
    val startStraighten = remember(clip.id) {
        (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == STRAIGHTEN_ID }
            ?.optJSONObject("params")?.optDouble("angle", 0.0)?.toFloat()?.coerceIn(-STRAIGHTEN, STRAIGHTEN) ?: 0f
    }
    var straighten by remember(clip.id) { mutableFloatStateOf(startStraighten) }
    var scrub by remember(clip.id) { mutableFloatStateOf(0f) }
    val video = clip.kind == "video"
    val spanUs = (clip.durationUs * clip.speed).toLong().coerceAtLeast(1)

    fun cropAspect(r: Rect) = (r.width * picW) / (r.height * picH).coerceAtLeast(1e-6)

    /** The biggest frame of [aspect] (width over height, in pixels) centred on the picture. */
    fun centred(aspect: Double): Rect {
        val pic = picW / picH
        return if (pic > aspect) {
            val w = (aspect / pic).toFloat()
            Rect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
        } else {
            val h = (pic / aspect).toFloat()
            Rect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
        }
    }

    fun apply() {
        val cut = JSONObject()
            .put("left", frame.left.toDouble().coerceIn(0.0, 1.0))
            .put("top", frame.top.toDouble().coerceIn(0.0, 1.0))
            .put("right", (1.0 - frame.right).coerceIn(0.0, 1.0))
            .put("bottom", (1.0 - frame.bottom).coerceIn(0.0, 1.0))
        val none = frame.left < 0.001f && frame.top < 0.001f && frame.right > 0.999f && frame.bottom > 0.999f
        val patch = JSONObject().put("crop", if (none) JSONObject.NULL else cut)
        if (straighten != startStraighten) {
            // the turn goes first in the chain, so the clip's looks are laid on the turned picture
            val next = JSONArray()
            if (straighten != 0f) {
                next.put(JSONObject().put("id", STRAIGHTEN_ID).put("params", JSONObject().put("angle", straighten.toDouble())))
            }
            for (i in 0 until chain.length()) chain.getJSONObject(i).let { if (it.optString("id") != STRAIGHTEN_ID) next.put(it) }
            patch.put("videoEffects", next)
        }
        vm.patch(clip.id, patch)
        vm.panel = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0B0B))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { vm.panel = null }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.X, Color.White, size = 24.dp, description = "Cancel")
            }
            Text(
                "Resize",
                style = Type.Body.copy(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(Modifier.size(44.dp).clickable(onClick = ::apply), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Check, Color.White, size = 26.dp, description = "Done")
            }
        }
        // the picture and the frame
        val version = Thumbs.version.intValue
        val atUs = if (video) {
            // a quarter-second grid, so scrubbing reuses what was decoded
            val raw = clip.sourceStartUs + (scrub * spanUs).toLong()
            (raw / 250_000 * 250_000).coerceIn(clip.sourceStartUs, maxOf(clip.sourceStartUs, clip.mediaDurationUs - 50_000))
        } else {
            0L
        }
        // the last picture stays up while the next moment is decoded
        val last = remember(clip.id) { arrayOfNulls<android.graphics.Bitmap>(1) }
        val fresh = remember(version, atUs) { Thumbs.still(clip.path, atUs, 720) }
        if (fresh != null) last[0] = fresh
        val shown = fresh ?: last[0]
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).background(Color.Black)) {
            CropCanvas(
                bitmap = shown,
                picAspect = (picW / picH).toFloat(),
                frame = frame,
                straighten = straighten,
                grow = fill(straighten, cropAspect(frame)).toFloat(),
                keep = RATIOS[ratio].second,
                picPixels = Size(picW.toFloat(), picH.toFloat()),
                onFrame = { frame = it },
            )
        }
        if (video) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clock((scrub * spanUs).toLong()), style = Type.Small.copy(color = Color.White, fontSize = 13.sp))
                CapSlider(scrub, 0f..1f, null, { scrub = it }, {}, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text(clock(spanUs), style = Type.Small.copy(color = Color.White, fontSize = 13.sp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF262626)))
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Rotate", style = Type.BodyStrong.copy(color = Color.White, fontSize = 15.sp))
            Text(
                "${straighten.roundToInt()}°",
                style = Type.BodyStrong.copy(color = Color.White, fontSize = 15.sp),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        DegreeRuler(straighten, { straighten = it }, Modifier.fillMaxWidth().height(56.dp))
        Spacer(Modifier.height(10.dp))
        Text("Aspect ratio", style = Type.BodyStrong.copy(color = Color.White, fontSize = 15.sp), modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp)) {
            RATIOS.forEachIndexed { i, (label, aspect) ->
                Column(
                    Modifier.width(74.dp).clickable {
                        ratio = i
                        if (aspect > 0) frame = centred(aspect)
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF2A2A2A))
                            .then(if (i == ratio) Modifier.border(2.dp, Color.White, RoundedCornerShape(10.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (aspect == 0.0) {
                            Glyph(LucideIcons.Expand, Color.White, size = 20.dp)
                        } else {
                            Canvas(Modifier.size(24.dp)) {
                                val big = size.width * 0.8f
                                val w = if (aspect >= 1) big else (big * aspect).toFloat()
                                val h = if (aspect >= 1) (big / aspect).toFloat() else big
                                drawRoundRect(
                                    Color.White,
                                    Offset((size.width - w) / 2, (size.height - h) / 2),
                                    Size(w, h),
                                    androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
                                    style = Stroke(1.6.dp.toPx()),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(label, style = Type.Tiny.copy(color = if (i == ratio) Color.White else Palette.TextSecondary), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(8.dp))
                .clickable {
                    frame = Rect(0f, 0f, 1f, 1f)
                    ratio = 0
                    straighten = 0f
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(LucideIcons.Trash2, Color.White, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Reset", style = Type.Small.copy(color = Color.White, fontSize = 13.sp))
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** What a finger on the crop frame took hold of: a corner, an edge, or the whole frame. */
private enum class Grip { TL, TR, BL, BR, L, T, R, B, MOVE }

/**
 * The picture fitted into the page, turned by [straighten] about the frame's middle and grown by
 * [grow] so the frame stays filled, under the frame:
 * white edges, heavy corners and edge marks, thirds lines, the outside dimmed. [keep] holds the
 * frame to a shape (width over height in picture pixels) while it is dragged.
 */
@Composable
private fun CropCanvas(
    bitmap: android.graphics.Bitmap?,
    picAspect: Float,
    frame: Rect,
    straighten: Float,
    grow: Float,
    keep: Double,
    picPixels: Size,
    onFrame: (Rect) -> Unit,
) {
    val latestFrame by rememberUpdatedState(frame)
    val latestKeep by rememberUpdatedState(keep)
    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(picAspect) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val box = pictureBox(Size(size.width.toFloat(), size.height.toFloat()), picAspect, 20.dp.toPx())
                val f0 = latestFrame
                val r0 = Rect(box.left + f0.left * box.width, box.top + f0.top * box.height, box.left + f0.right * box.width, box.top + f0.bottom * box.height)
                val reach = 28.dp.toPx()
                val p = down.position
                fun near(a: Offset) = (a - p).getDistance() < reach
                val grip = when {
                    near(r0.topLeft) -> Grip.TL
                    near(r0.topRight) -> Grip.TR
                    near(r0.bottomLeft) -> Grip.BL
                    near(r0.bottomRight) -> Grip.BR
                    abs(p.x - r0.left) < reach && p.y in r0.top..r0.bottom -> Grip.L
                    abs(p.x - r0.right) < reach && p.y in r0.top..r0.bottom -> Grip.R
                    abs(p.y - r0.top) < reach && p.x in r0.left..r0.right -> Grip.T
                    abs(p.y - r0.bottom) < reach && p.x in r0.left..r0.right -> Grip.B
                    r0.contains(p) -> Grip.MOVE
                    else -> return@awaitEachGesture
                }
                val least = 48.dp.toPx()
                // the shape held, as width over height on the screen
                val shape = if (latestKeep > 0) (latestKeep * (box.width / picPixels.width) / (box.height / picPixels.height)).toFloat() else 0f
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val d = c.position - p
                    var l = r0.left
                    var t = r0.top
                    var r = r0.right
                    var b = r0.bottom
                    when (grip) {
                        Grip.MOVE -> {
                            val dx = d.x.within(box.left - r0.left, box.right - r0.right)
                            val dy = d.y.within(box.top - r0.top, box.bottom - r0.bottom)
                            l += dx; r += dx; t += dy; b += dy
                        }
                        else -> {
                            if (grip == Grip.TL || grip == Grip.BL || grip == Grip.L) l = (r0.left + d.x).within(box.left, r - least)
                            if (grip == Grip.TR || grip == Grip.BR || grip == Grip.R) r = (r0.right + d.x).within(l + least, box.right)
                            if (grip == Grip.TL || grip == Grip.TR || grip == Grip.T) t = (r0.top + d.y).within(box.top, b - least)
                            if (grip == Grip.BL || grip == Grip.BR || grip == Grip.B) b = (r0.bottom + d.y).within(t + least, box.bottom)
                            if (shape > 0f) {
                                // the dragged side leads; the other follows, kept inside the picture
                                val sideways = grip == Grip.L || grip == Grip.R ||
                                    (grip !in listOf(Grip.T, Grip.B) && abs(d.x) >= abs(d.y))
                                if (sideways) {
                                    var h = (r - l) / shape
                                    val cy = (r0.top + r0.bottom) / 2
                                    val anchorTop = grip == Grip.BL || grip == Grip.BR
                                    val anchorBottom = grip == Grip.TL || grip == Grip.TR
                                    val room = when {
                                        anchorTop -> box.bottom - r0.top
                                        anchorBottom -> r0.bottom - box.top
                                        else -> 2 * min(cy - box.top, box.bottom - cy)
                                    }
                                    if (h > room) {
                                        h = room
                                        val w = h * shape
                                        if (grip == Grip.TL || grip == Grip.BL || grip == Grip.L) l = r - w else r = l + w
                                    }
                                    when {
                                        anchorTop -> { t = r0.top; b = t + h }
                                        anchorBottom -> { b = r0.bottom; t = b - h }
                                        else -> { t = cy - h / 2; b = cy + h / 2 }
                                    }
                                } else {
                                    var w = (b - t) * shape
                                    val cx = (r0.left + r0.right) / 2
                                    val anchorLeft = grip == Grip.TR || grip == Grip.BR
                                    val anchorRight = grip == Grip.TL || grip == Grip.BL
                                    val room = when {
                                        anchorLeft -> box.right - r0.left
                                        anchorRight -> r0.right - box.left
                                        else -> 2 * min(cx - box.left, box.right - cx)
                                    }
                                    if (w > room) {
                                        w = room
                                        val h = w / shape
                                        if (grip == Grip.TL || grip == Grip.TR || grip == Grip.T) t = b - h else b = t + h
                                    }
                                    when {
                                        anchorLeft -> { l = r0.left; r = l + w }
                                        anchorRight -> { r = r0.right; l = r - w }
                                        else -> { l = cx - w / 2; r = cx + w / 2 }
                                    }
                                }
                            }
                        }
                    }
                    onFrame(
                        Rect(
                            ((l - box.left) / box.width).coerceIn(0f, 1f),
                            ((t - box.top) / box.height).coerceIn(0f, 1f),
                            ((r - box.left) / box.width).coerceIn(0f, 1f),
                            ((b - box.top) / box.height).coerceIn(0f, 1f),
                        ),
                    )
                    c.consume()
                }
            }
        },
    ) {
        val box = pictureBox(size, picAspect, 20.dp.toPx())
        val r = Rect(box.left + frame.left * box.width, box.top + frame.top * box.height, box.left + frame.right * box.width, box.top + frame.bottom * box.height)
        clipRect(box.left, box.top, box.right, box.bottom) {
            image?.let { img ->
                rotate(straighten, r.center) {
                    scale(grow, grow, r.center) {
                        drawImage(
                            img,
                            srcOffset = IntOffset.Zero,
                            srcSize = IntSize(img.width, img.height),
                            dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                            dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()),
                        )
                    }
                }
            }
        }
        val dim = Color(0x99000000)
        drawRect(dim, Offset(box.left, box.top), Size(box.width, r.top - box.top))
        drawRect(dim, Offset(box.left, r.bottom), Size(box.width, box.bottom - r.bottom))
        drawRect(dim, Offset(box.left, r.top), Size(r.left - box.left, r.height))
        drawRect(dim, Offset(r.right, r.top), Size(box.right - r.right, r.height))
        val thin = 1.dp.toPx()
        for (k in 1..2) {
            val x = r.left + r.width * k / 3
            val y = r.top + r.height * k / 3
            drawLine(Color(0xCCFFFFFF), Offset(x, r.top), Offset(x, r.bottom), thin)
            drawLine(Color(0xCCFFFFFF), Offset(r.left, y), Offset(r.right, y), thin)
        }
        drawRect(Color.White, r.topLeft, r.size, style = Stroke(thin))
        val heavy = 3.dp.toPx()
        val arm = 14.dp.toPx()
        val o = heavy / 2
        for ((corner, dir) in listOf(
            r.topLeft to Offset(1f, 1f), r.topRight to Offset(-1f, 1f),
            r.bottomLeft to Offset(1f, -1f), r.bottomRight to Offset(-1f, -1f),
        )) {
            val c = Offset(corner.x - dir.x * o, corner.y - dir.y * o)
            drawLine(Color.White, c, Offset(c.x + dir.x * arm, c.y), heavy, cap = StrokeCap.Square)
            drawLine(Color.White, c, Offset(c.x, c.y + dir.y * arm), heavy, cap = StrokeCap.Square)
        }
        val mark = 9.dp.toPx()
        drawLine(Color.White, Offset(r.center.x - mark, r.top - o), Offset(r.center.x + mark, r.top - o), heavy)
        drawLine(Color.White, Offset(r.center.x - mark, r.bottom + o), Offset(r.center.x + mark, r.bottom + o), heavy)
        drawLine(Color.White, Offset(r.left - o, r.center.y - mark), Offset(r.left - o, r.center.y + mark), heavy)
        drawLine(Color.White, Offset(r.right + o, r.center.y - mark), Offset(r.right + o, r.center.y + mark), heavy)
    }
}

/** [coerceIn] that gives [low] when the range is empty, as a frame smaller than a finger's reach makes it. */
private fun Float.within(low: Float, high: Float): Float = if (high < low) low else coerceIn(low, high)

/** Where a picture of [aspect] sits when fitted into [area] with [margin] around it. */
private fun pictureBox(area: Size, aspect: Float, margin: Float): Rect {
    val w = area.width - 2 * margin
    val h = area.height - 2 * margin
    val (bw, bh) = if (w / h > aspect) (h * aspect) to h else w to (w / aspect)
    val left = (area.width - bw) / 2
    val top = (area.height - bh) / 2
    return Rect(left, top, left + bw, top + bh)
}

/**
 * The straightening ruler: a tick a degree, taller every fifth, the tens named under them,
 * sliding under a fixed cyan needle.
 */
@Composable
private fun DegreeRuler(value: Float, onChange: (Float) -> Unit, modifier: Modifier) {
    val latest by rememberUpdatedState(value)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color(0xFF8A8A8A), fontSize = 10.sp)
    Canvas(
        modifier.pointerInput(Unit) {
            val gap = 8.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = latest
                var sent = start
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    val v = (start - (c.position.x - down.position.x) / gap).roundToInt().toFloat().coerceIn(-STRAIGHTEN, STRAIGHTEN)
                    if (v != sent) {
                        sent = v
                        onChange(v)
                    }
                    c.consume()
                }
            }
        },
    ) {
        val gap = 8.dp.toPx()
        val mid = size.width / 2
        val top = 8.dp.toPx()
        for (k in -STRAIGHTEN.toInt()..STRAIGHTEN.toInt()) {
            val x = mid + (k - value) * gap
            if (x < 0f || x > size.width) continue
            val fade = 1f - abs(x - mid) / mid * 0.6f
            val major = k % 5 == 0
            val h = if (major) 14.dp.toPx() else 9.dp.toPx()
            drawLine(Color.White.copy(alpha = (if (major) 0.9f else 0.45f) * fade), Offset(x, top + (14.dp.toPx() - h) / 2), Offset(x, top + (14.dp.toPx() + h) / 2), 1.dp.toPx())
            if (k % 10 == 0) {
                val text = measurer.measure("${abs(k)}°", labelStyle)
                drawText(text, topLeft = Offset(x - text.size.width / 2f, top + 20.dp.toPx()), alpha = fade)
            }
        }
        drawLine(Palette.Accent, Offset(mid, top - 4.dp.toPx()), Offset(mid, top + 18.dp.toPx()), 2.dp.toPx(), cap = StrokeCap.Round)
    }
}
