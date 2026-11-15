package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.detectBeats
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The timeline's scale, which the strip of sound keeps so a beat sits where it does below. */
private const val DP_PER_SECOND = 50f

/**
 * Beats: the clip's sound in a strip under the playhead with its beats marked; a button that adds
 * a beat at the playhead (or deletes the one it is on); and Auto-generate, which listens to the
 * sound for them, Light finding the strong ones, Intense more.
 */
@Composable
fun BeatsPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var finding by remember { mutableStateOf(false) }
    val marks = vm.beats[clip.mediaId].orEmpty()
    var auto by remember(clip.id) { mutableStateOf(marks.isNotEmpty()) }
    var intense by remember(clip.id) { mutableStateOf(false) }
    val speed = clip.speed.takeIf { it > 0 } ?: 1.0
    // the playhead as a time in the file
    val here = (clip.sourceStartUs + (vm.playhead - clip.startUs) * speed) / 1e6
    val inside = vm.playhead in clip.startUs..(clip.startUs + clip.durationUs)
    val near = marks.indexOfFirst { abs(it - here) < 0.1 }

    fun find(dense: Boolean) {
        if (vm.session == null) return
        finding = true
        scope.launch {
            val end = if (clip.mediaDurationUs > 0) clip.mediaDurationUs else clip.sourceStartUs + (clip.durationUs * speed).toLong()
            val found = withContext(Threads.commands) {
                runCatching { detectBeats(clip.path, vm.path, 0, end, dense) }.getOrNull()
            }
            finding = false
            when {
                found == null -> vm.message = "Could not read the sound"
                found.isEmpty() -> vm.message = "No beats found"
                else -> vm.setBeats(clip.mediaId, found.map { it / 1e6 })
            }
        }
    }

    PanelFrame("Beats", vm, tall = true, titleOnTop = true) {
        Spacer(Modifier.height(14.dp))
        BeatStrip(vm, clip)
        Spacer(Modifier.height(30.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val deleting = near >= 0
            Row(
                Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (inside) Color.White else Color(0xFF5A5A5A))
                    .clickable(enabled = inside) {
                        if (deleting) vm.setBeats(clip.mediaId, marks.filterIndexed { i, _ -> i != near })
                        else vm.setBeats(clip.mediaId, marks + here)
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(if (deleting) LucideIcons.Minus else LucideIcons.Plus, Color.Black, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (deleting) "Delete beat" else "Add beat",
                    style = Type.Body.copy(color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.height(30.dp))
        Row(Modifier.fillMaxWidth().padding(end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(118.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Toggle(auto) {
                    auto = !auto
                    if (auto) find(intense) else vm.setBeats(clip.mediaId, null)
                }
                Spacer(Modifier.height(8.dp))
                Text(if (finding) "Listening…" else "Auto-generate", style = Type.Small.copy(color = Palette.Text, fontSize = 13.sp))
            }
            Box(Modifier.width(1.dp).height(52.dp).background(Color(0xFF3A3A3A)))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                TwoStops(intense, enabled = auto) {
                    if (it != intense) {
                        intense = it
                        find(it)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    val tone = if (auto) Palette.Text else Palette.TextSecondary
                    Text("Light", style = Type.Small.copy(color = tone, fontSize = 13.sp))
                    Spacer(Modifier.weight(1f))
                    Text("Intense", style = Type.Small.copy(color = tone, fontSize = 13.sp))
                }
            }
        }
    }
}

/**
 * The clip's sound at the timeline's scale with the playhead fixed in the middle and the beats
 * as yellow dots; dragging it moves the playhead as the timeline does.
 */
@Composable
private fun BeatStrip(vm: EditorViewModel, clip: ClipView) {
    val version = Thumbs.version.intValue
    val playhead by rememberUpdatedState(vm.playhead)
    val speed = clip.speed.takeIf { it > 0 } ?: 1.0
    val fromUs = clip.sourceStartUs
    val toUs = clip.sourceStartUs + (clip.durationUs * speed).toLong()
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(62.dp)
            .pointerInput(clip.id) {
                val pxPerUs = DP_PER_SECOND * density / 1_000_000f
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = playhead
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        vm.scrub(start - ((c.position.x - down.position.x) / pxPerUs).toLong())
                        c.consume()
                    }
                }
            },
    ) {
        val pxPerUs = DP_PER_SECOND * density / 1_000_000f
        val mid = size.width / 2
        val top = 4.dp.toPx()
        val h = size.height - 8.dp.toPx()
        drawRect(Color(0xFF3B3E42), Offset(0f, top), Size(size.width, h))
        val x0 = mid + (clip.startUs - vm.playhead) * pxPerUs
        val x1 = x0 + clip.durationUs * pxPerUs
        if (x1 > 0f && x0 < size.width) {
            val buckets = ((x1 - x0) / (3.dp.toPx())).toInt().coerceIn(8, 600)
            val peaks = if (version >= 0) Thumbs.wave(clip.path, vm.path, fromUs, toUs, buckets) else null
            val base = top + h - 6.dp.toPx()
            val reach = h - 14.dp.toPx()
            val line = Path()
            if (peaks != null && peaks.isNotEmpty()) {
                peaks.forEachIndexed { i, p ->
                    val x = x0 + (x1 - x0) * i / (peaks.size - 1).coerceAtLeast(1)
                    val y = base - p.coerceIn(0f, 1f) * reach
                    if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
                }
            } else {
                line.moveTo(x0, base)
                line.lineTo(x1, base)
            }
            drawPath(line, Palette.Accent, style = Stroke(2.dp.toPx()))
            vm.beatTimes(clip).forEach { t ->
                drawCircle(Palette.Beat, 3.dp.toPx(), Offset(mid + (t - vm.playhead) * pxPerUs, top + h - 10.dp.toPx()))
            }
        }
        drawLine(Color.White, Offset(mid, 0f), Offset(mid, size.height), 2.dp.toPx())
    }
}

/** A small switch: a ring at the left when off, a filled thumb on cyan at the right when on. */
@Composable
private fun Toggle(on: Boolean, onClick: () -> Unit) {
    Canvas(Modifier.size(44.dp, 26.dp).clickable(onClick = onClick)) {
        val r = 9.dp.toPx()
        val cy = size.height / 2
        val trackH = 4.dp.toPx()
        drawRoundRect(
            if (on) Palette.Accent else Color(0xFF6A6A6A),
            Offset(r, cy - trackH / 2),
            Size(size.width - 2 * r, trackH),
            CornerRadius(trackH / 2),
        )
        val cx = if (on) size.width - r - 1.dp.toPx() else r + 1.dp.toPx()
        if (on) {
            drawCircle(Color.White, r, Offset(cx, cy))
        } else {
            drawCircle(Palette.DockSelected, r, Offset(cx, cy))
            drawCircle(Color.White, r - 1.dp.toPx(), Offset(cx, cy), style = Stroke(2.dp.toPx()))
        }
    }
}

/** A slider with two places, Light at the left and Intense at the right; greyed when [enabled] is off. */
@Composable
private fun TwoStops(right: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    val latest by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var last = down.position.x
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        last = c.position.x
                        c.consume()
                    }
                    latest(last > size.width / 2)
                }
            },
    ) {
        val r = 7.dp.toPx()
        val cy = size.height / 2
        drawLine(Color(0xFF555555), Offset(r, cy), Offset(size.width - r, cy), 2.dp.toPx())
        drawCircle(Color(0xFF555555), 2.dp.toPx(), Offset(size.width - r, cy))
        val cx = if (right) size.width - r else r
        if (enabled && right) drawLine(Palette.Accent, Offset(r, cy), Offset(cx, cy), 2.dp.toPx())
        drawCircle(if (enabled) Color.White else Color(0xFF666666), r, Offset(cx, cy))
    }
}
