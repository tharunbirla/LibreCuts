package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.BiasAlignment
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.cancelStabilize
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the timeline keeps, for each steadied copy, the file it came from and how strongly. */
private const val STABILIZED = "capcraftStabilized"

private val LEVELS = listOf("None", "Minimal cropping", "Recommended", "Most stable")

/**
 * Steadying shaky footage: a steadied copy of the clip's file is made on the phone (the stronger,
 * the more the picture is zoomed to hide the edges) and the clip shows it; None goes back.
 */
@Composable
fun StabilizePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    val level = remember(clip.json, clip.path) {
        runCatching {
            vm.session?.extra(STABILIZED)?.takeIf { it.isNotBlank() }?.let { JSONObject(it).optJSONObject(clip.path)?.optInt("level", 0) }
        }.getOrNull() ?: 0
    }

    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { progress = fraction }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    result?.let { vm.applyView(it) }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    vm.message = message
                }
            }
        }
    }

    fun choose(next: Int) {
        val s = vm.session ?: return
        if (progress != null || next == level) return
        if (next > 0) progress = 0f
        vm.stop()
        scope.launch(Threads.commands) {
            runCatching { s.stabilize(clip.id, next.toUInt(), listener) }
                .onFailure { e -> launch(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
        }
    }

    PanelFrame("Stabilize", vm, height = if (progress != null) 200.dp else 162.dp) {
        Spacer(Modifier.height(22.dp))
        StopSlider(level, LEVELS.size, enabled = progress == null, onChoose = ::choose)
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(20.dp)) {
            LEVELS.forEachIndexed { i, label ->
                val align = when (i) {
                    0 -> Alignment.CenterStart
                    LEVELS.size - 1 -> Alignment.CenterEnd
                    else -> BiasAlignment(-1f + 2f * i / (LEVELS.size - 1), 0f)
                }
                Text(
                    label,
                    style = Type.Small.copy(color = if (i == level) Palette.Text else Palette.TextSecondary, fontSize = 13.sp),
                    modifier = Modifier.align(align),
                )
            }
        }
        val p = progress
        Spacer(Modifier.height(14.dp))
        if (p != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Steadying… ${(p * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                Text(
                    "Cancel",
                    style = Type.Small.copy(color = Palette.Text),
                    modifier = Modifier.clickable { runCatching { cancelStabilize() } }.padding(8.dp),
                )
            }
        }
    }
}

/**
 * A line with [count] stops and a ring on the chosen one; a tap or a drag moves the ring, which
 * settles on the nearest stop when the finger lifts.
 */
@Composable
private fun StopSlider(chosen: Int, count: Int, enabled: Boolean, onChoose: (Int) -> Unit) {
    var held by remember { mutableStateOf<Float?>(null) }
    val latest by rememberUpdatedState(onChoose)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(horizontal = 20.dp)
            .pointerInput(enabled, count) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun at(x: Float) = (x / size.width).coerceIn(0f, 1f)
                    held = at(down.position.x)
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        held = at(c.position.x)
                        c.consume()
                    }
                    val stop = ((held ?: 0f) * (count - 1)).roundToInt()
                    held = null
                    latest(stop)
                }
            },
    ) {
        val cy = size.height / 2
        drawLine(Color(0xFF4A4A4A), Offset(0f, cy), Offset(size.width, cy), 1.dp.toPx())
        for (i in 1 until count) {
            val x = size.width * i / (count - 1)
            drawLine(Color(0xFF6A6A6A), Offset(x, cy - 3.dp.toPx()), Offset(x, cy + 3.dp.toPx()), 1.dp.toPx())
        }
        val f = held ?: (chosen.toFloat() / (count - 1))
        val x = (size.width * f).coerceIn(0f, size.width)
        val r = 8.dp.toPx()
        drawCircle(Palette.DockSelected, r, Offset(x, cy))
        drawCircle(Color.White, r - 1.dp.toPx(), Offset(x, cy), style = Stroke(2.dp.toPx()))
    }
}
