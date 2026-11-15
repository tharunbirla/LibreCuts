package io.github.artcraftandroid.capcraft.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Palette
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A box's four corners in the preview's pixels, rotated about its centre. */
private fun corners(b: ClipBox, w: Float, h: Float): List<Offset> {
    val cx = (b.cx * w).toFloat()
    val cy = (b.cy * h).toFloat()
    val hw = (b.width * w / 2).toFloat()
    val hh = (b.height * h / 2).toFloat()
    val r = Math.toRadians(b.rotation)
    val c = cos(r).toFloat()
    val s = sin(r).toFloat()
    return listOf(-hw to -hh, hw to -hh, hw to hh, -hw to hh).map { (x, y) -> Offset(cx + x * c - y * s, cy + x * s + y * c) }
}

/** Whether [p] lies inside box [b] (on a preview [w] by [h]). */
private fun inside(b: ClipBox, p: Offset, w: Float, h: Float): Boolean {
    val cx = (b.cx * w).toFloat()
    val cy = (b.cy * h).toFloat()
    val r = -Math.toRadians(b.rotation)
    val dx = p.x - cx
    val dy = p.y - cy
    val x = dx * cos(r) - dy * sin(r)
    val y = dx * sin(r) + dy * cos(r)
    return abs(x) <= b.width * w / 2 + 12 && abs(y) <= b.height * h / 2 + 12
}

/** Which corner button a press is on: 0 delete, 1 edit, 2 turn and scale, 3 duplicate; -1 none. */
private fun cornerAt(pts: List<Offset>, p: Offset, reach: Float): Int = pts.indexOfFirst { (it - p).getDistance() <= reach }

/**
 * Over the preview: the selected picture's or title's frame, a tap to select what is under the
 * finger, and dragging, pinching and twisting to move, scale and turn it. A title, sticker or
 * overlay also gets the reference's corner buttons: delete at the top left, edit (a title) at the
 * top right, duplicate at the bottom left, and a handle at the bottom right that turns and scales
 * it about its middle. One gesture is one undo step.
 */
@Composable
fun PreviewOverlay(vm: EditorViewModel, modifier: Modifier) {
    val session = vm.session
    val revision = vm.timeline?.revision ?: 0L
    val playhead = vm.playhead
    val playing = vm.playing
    val touchSlop = LocalViewConfiguration.current.touchSlop
    /** A transform being dragged, shown before the engine has it. */
    var live by remember { mutableStateOf<ClipBox?>(null) }
    /** The frame's middle lines the dragged picture is caught on, shown as guides: up and down, across. */
    var guides by remember { mutableStateOf(false to false) }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val boxes by produceState(emptyList<ClipBox>(), session, revision, playhead, playing) {
        if (session == null || playing) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Threads.commands) { runCatching { session.clipBoxes(playhead) }.getOrDefault(emptyList()) }
    }
    val selected = vm.selected
    // read inside the gesture, which must not restart when an edit brings new boxes
    val currentBoxes by androidx.compose.runtime.rememberUpdatedState(boxes)
    val deleteIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(io.github.artcraftandroid.capcraft.ui.LucideIcons.X)
    val editIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(io.github.artcraftandroid.capcraft.ui.LucideIcons.Pencil)
    val animateIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(io.github.artcraftandroid.capcraft.ui.LucideIcons.SquarePlay)
    val turnIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(io.github.artcraftandroid.capcraft.ui.LucideIcons.RotateCwSquare)
    val copyIcon = androidx.compose.ui.graphics.vector.rememberVectorPainter(io.github.artcraftandroid.capcraft.ui.LucideIcons.Copy)

    /** Sends a transform to the engine: a keyed clip by its key at the playhead, added there if missing. */
    fun push(next: ClipBox) {
        val clip = vm.clip(next.clipId)
        if (clip != null && clip.keysUs.isNotEmpty()) {
            val at = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            val batch = org.json.JSONArray()
            for ((property, value) in listOf(
                "offsetX" to next.offsetX, "offsetY" to next.offsetY,
                "scale" to next.scale, "rotation" to normalise(next.rotation),
            )) {
                batch.put(
                    JSONObject().put("op", "setClipKey").put("clipId", next.clipId).put("property", property)
                        .put("at", at).put("value", value),
                )
            }
            vm.command(JSONObject().put("op", "batch").put("commands", batch), gesture = "preview-transform")
        } else {
            vm.command(
                JSONObject().put("op", "setClipTransform").put("clipId", next.clipId)
                    .put("offsetX", next.offsetX).put("offsetY", next.offsetY)
                    .put("scale", next.scale).put("rotation", normalise(next.rotation)),
                gesture = "preview-transform",
            )
        }
    }

    Canvas(
        modifier
            .fillMaxSize()
            .pointerInput(session) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val boxes = currentBoxes
                    val selected = vm.selected
                    val w = size.width.toFloat()
                    val h = size.height.toFloat()
                    // the corner buttons of a framed selection come first
                    val framedBox = boxes.firstOrNull { it.clipId == selected }
                        ?.takeIf { vm.selectedRow != io.github.artcraftandroid.capcraft.core.RowKind.MAIN }
                    val button = framedBox?.let { cornerAt(corners(it, w, h), down.position, 22.dp.toPx()) } ?: -1
                    if (framedBox != null && button == 2) {
                        // turn and scale about the middle, by where the finger goes
                        val middle = Offset((framedBox.cx * w).toFloat(), (framedBox.cy * h).toFloat())
                        val from = down.position - middle
                        val reach0 = from.getDistance().coerceAtLeast(1f)
                        val angle0 = kotlin.math.atan2(from.y, from.x)
                        var turned = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if (!change.pressed) break
                            val now = change.position - middle
                            val k = (now.getDistance() / reach0).toDouble()
                            val turn = Math.toDegrees((kotlin.math.atan2(now.y, now.x) - angle0).toDouble())
                            val next = framedBox.copy(
                                scale = (framedBox.scale * k).coerceIn(0.05, 8.0),
                                width = framedBox.width * k,
                                height = framedBox.height * k,
                                rotation = squared(framedBox.rotation + turn),
                            )
                            if (next.rotation != (live ?: framedBox).rotation && next.rotation % 90.0 == 0.0) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            if (!turned && vm.playing) vm.stop()
                            turned = true
                            live = next
                            push(next)
                            change.consume()
                        }
                        if (turned) vm.endGesture()
                        live = null
                        return@awaitEachGesture
                    }
                    if (framedBox != null && button >= 0) {
                        // a tap on a button, if the finger stays on it
                        var stayed = true
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.first()
                            if ((change.position - down.position).getDistance() > touchSlop * 2) stayed = false
                            if (!change.pressed) break
                        }
                        if (stayed) {
                            val id = framedBox.clipId
                            val text = vm.clip(id)?.kind == "text"
                            when (button) {
                                0 -> {
                                    vm.edit { delete(listOf(id)) }
                                    vm.select(null)
                                }
                                1 -> if (text) vm.panel = Panel.Text(id) else if (sticker(vm, id)) vm.panel = Panel.Animation
                                3 -> vm.edit(select = { it.createdId.ifEmpty { null } }) { duplicate(id) }
                            }
                        }
                        return@awaitEachGesture
                    }
                    val start = boxes.firstOrNull { it.clipId == selected && inside(it, down.position, w, h) }
                        ?: boxes.firstOrNull { inside(it, down.position, w, h) }
                    // where the fingers have taken it, before the guides catch it
                    var raw = start
                    var moved = false
                    var twisted = false
                    var total = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val box = raw ?: continue
                        val pan = event.calculatePan()
                        val zoom = event.calculateZoom()
                        val turn = event.calculateRotation()
                        total += event.changes.first().positionChange()
                        if (!moved && (abs(total.x) > touchSlop || abs(total.y) > touchSlop || zoom != 1f || turn != 0f)) {
                            moved = true
                            if (vm.selected != box.clipId) vm.select(box.clipId)
                            if (vm.playing) vm.stop()
                        }
                        if (!moved) continue
                        @Suppress("UNUSED_VARIABLE") val centroid = event.calculateCentroid()
                        val next = box.copy(
                            cx = box.cx + pan.x / w,
                            cy = box.cy + pan.y / h,
                            offsetX = box.offsetX + pan.x / w,
                            offsetY = box.offsetY + pan.y / h,
                            scale = (box.scale * zoom).coerceIn(0.05, 8.0),
                            width = box.width * zoom,
                            height = box.height * zoom,
                            rotation = box.rotation + turn,
                        )
                        raw = next
                        if (turn != 0f) twisted = true
                        // the picture's middle catches on the frame's, and a twist on the square turns
                        val reach = 6.dp.toPx()
                        val acrossX = abs((next.cx - 0.5) * w) < reach
                        val acrossY = abs((next.cy - 0.5) * h) < reach
                        val shown = next.copy(
                            cx = if (acrossX) 0.5 else next.cx,
                            offsetX = if (acrossX) next.offsetX + (0.5 - next.cx) else next.offsetX,
                            cy = if (acrossY) 0.5 else next.cy,
                            offsetY = if (acrossY) next.offsetY + (0.5 - next.cy) else next.offsetY,
                            rotation = if (twisted) squared(next.rotation) else next.rotation,
                        )
                        val caught = (acrossX && !guides.first) || (acrossY && !guides.second) ||
                            (twisted && shown.rotation != next.rotation && shown.rotation != live?.rotation)
                        if (caught) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        guides = acrossX to acrossY
                        live = shown
                        push(shown)
                        event.changes.forEach { it.consume() }
                    }
                    val picker = vm.pickColor
                    if (!moved && picker != null) {
                        // the colour under the finger, from the frame on screen
                        vm.lastFrame?.let { frame ->
                            val px = (down.position.x / w * frame.width).toInt().coerceIn(0, frame.width - 1)
                            val py = (down.position.y / h * frame.height).toInt().coerceIn(0, frame.height - 1)
                            picker(frame.getPixel(px, py) and 0xFFFFFF)
                        }
                        vm.pickColor = null
                    } else if (moved) {
                        vm.endGesture()
                    } else {
                        // a tap: what is under the finger, or nothing
                        val hit = boxes.firstOrNull { inside(it, down.position, w, h) }
                        if (hit == null) vm.select(null) else if (hit.clipId != vm.selected) vm.select(hit.clipId)
                    }
                    live = null
                    guides = false to false
                }
            },
    ) {
        // the middle lines a dragged picture is held on
        val (upright, level) = guides
        if (upright) drawLine(Palette.Accent, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.dp.toPx())
        if (level) drawLine(Palette.Accent, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.dp.toPx())
        val shown = live ?: boxes.firstOrNull { it.clipId == selected } ?: return@Canvas
        if (shown.clipId != selected) return@Canvas
        val pts = corners(shown, size.width, size.height)
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            pts.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        // a main-track clip is marked by a quiet border; anything above it gets the white frame and corners
        if (vm.selectedRow == io.github.artcraftandroid.capcraft.core.RowKind.MAIN) {
            drawPath(path, Color(0xCC7A8082), style = Stroke(2.dp.toPx()))
        } else {
            drawPath(path, Color.White, style = Stroke(1.5.dp.toPx()))
            val text = vm.clip(shown.clipId)?.kind == "text"
            val r = 12.5f.dp.toPx()
            val icon = 14.dp.toPx()
            // a title's top right edits it; a sticker's animates it
            val second = if (text) editIcon else if (sticker(vm, shown.clipId)) animateIcon else null
            listOf(deleteIcon, second, turnIcon, copyIcon).forEachIndexed { i, painter ->
                if (painter == null) return@forEachIndexed
                val at = pts[i]
                drawCircle(Color(0xFF161616), r, at)
                translate(at.x - icon / 2, at.y - icon / 2) {
                    with(painter) {
                        draw(androidx.compose.ui.geometry.Size(icon, icon), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
                    }
                }
            }
        }
    }
}

/** Whether [clipId] is a sticker: a picture on a text row. */
private fun sticker(vm: EditorViewModel, clipId: String) =
    vm.clip(clipId)?.kind == "image" && vm.rowOf(clipId)?.kind == io.github.artcraftandroid.capcraft.core.RowKind.TEXT

/** A turn within three degrees of a square one (0, 90, 180, -90) set on it, as the reference's twist catches. */
private fun squared(d: Double): Double {
    val square = Math.round(normalise(d) / 90.0) * 90.0
    return if (abs(normalise(d) - square) < 3.0) square else d
}

/** Degrees kept in (-180, 180]. */
private fun normalise(d: Double): Double {
    var r = d % 360.0
    if (r > 180) r -= 360
    if (r <= -180) r += 360
    return r
}
