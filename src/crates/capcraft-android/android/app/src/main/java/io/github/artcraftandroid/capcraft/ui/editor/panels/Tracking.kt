package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.TrackListener
import io.github.artcraftandroid.capcraft.core.cancelTracking
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Where the tracking target sits, as fractions of the frame, while the Tracking sheet is open. */
object TrackTarget {
    var at by mutableStateOf(Offset(0.5f, 0.5f))
}

/** The target over the picture: a ring with a cross that a finger drags onto what to follow. */
@Composable
fun TrackSurface(modifier: Modifier) {
    Canvas(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                TrackTarget.at = Offset((down.position.x / w).coerceIn(0f, 1f), (down.position.y / h).coerceIn(0f, 1f))
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.first()
                    if (!c.pressed) break
                    TrackTarget.at = Offset((c.position.x / w).coerceIn(0f, 1f), (c.position.y / h).coerceIn(0f, 1f))
                    c.consume()
                }
            }
        },
    ) {
        val c = Offset(TrackTarget.at.x * size.width, TrackTarget.at.y * size.height)
        val r = 34.dp.toPx()
        drawCircle(Color(0x33000000), r + 2.dp.toPx(), c)
        drawCircle(Palette.Accent, r, c, style = Stroke(3.dp.toPx()))
        drawLine(Color.White, Offset(c.x - 10.dp.toPx(), c.y), Offset(c.x + 10.dp.toPx(), c.y), 2.dp.toPx())
        drawLine(Color.White, Offset(c.x, c.y - 10.dp.toPx()), Offset(c.x, c.y + 10.dp.toPx()), 2.dp.toPx())
    }
}

/**
 * Tracking: the selected title, sticker or picture made to follow something in the video under
 * it. The target is dragged onto it over the picture; Start reads the footage on the phone and
 * sets the clip's position along the way.
 */
@Composable
fun TrackingPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) { onDispose { if (progress != null) runCatching { cancelTracking() } } }
    val listener = remember {
        object : TrackListener {
            override fun onProgress(fraction: Float) {
                scope.launch(Dispatchers.Main) { progress = fraction }
            }

            override fun onDone(result: TimelineView) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    vm.applyView(result)
                    vm.panel = null
                    vm.message = "Tracking added"
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
    PanelFrame("Tracking", vm, height = 200.dp, titleOnTop = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val running = progress
            Text(
                if (running == null) "Drag the ring onto what the clip should follow, then start." else "Following… ${(running * 100).toInt()}%",
                style = Type.Body.copy(color = Palette.Text, textAlign = TextAlign.Center),
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (running == null) Palette.Accent else Color(0xFF3A3A3A))
                    .clickable {
                        if (running != null) {
                            runCatching { cancelTracking() }
                            progress = null
                            return@clickable
                        }
                        val s = vm.session ?: return@clickable
                        val target = TrackTarget.at
                        progress = 0f
                        scope.launch(Threads.commands) {
                            runCatching { s.track(clip.id, target.x.toDouble(), target.y.toDouble(), listener) }
                                .onFailure { e -> launch(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (running != null) {
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(running.coerceIn(0f, 1f)).height(46.dp).background(Palette.Accent.copy(alpha = 0.5f)))
                }
                Text(
                    if (running == null) "Start" else "Stop",
                    style = Type.BodyStrong.copy(fontSize = 16.sp, color = if (running == null) Palette.OnAccent else Palette.Text),
                )
            }
        }
    }
}

/**
 * Camera tracking: the selected video moved and zoomed in on itself so what the ring is on stays
 * in the middle of the frame. The ring is dragged onto the subject over the picture; Track reads
 * the footage on the phone and sets the clip's position along the way.
 */
@Composable
fun CameraTrackingPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var zoom by remember { mutableStateOf(1.3f) }
    DisposableEffect(Unit) { onDispose { if (progress != null) runCatching { cancelTracking() } } }
    val listener = remember {
        object : TrackListener {
            override fun onProgress(fraction: Float) {
                scope.launch(Dispatchers.Main) { progress = fraction }
            }

            override fun onDone(result: TimelineView) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    vm.applyView(result)
                    vm.panel = null
                    vm.message = "Camera tracking added"
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
    PanelFrame("Camera tracking", vm, height = 300.dp, titleOnTop = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text("Select tracking subject", style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 13.sp))
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.size(width = 104.dp, height = 56.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF262626))
                    .border(1.5.dp, Palette.Accent, RoundedCornerShape(6.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Glyph(LucideIcons.Crosshair, Palette.Accent, size = 20.dp)
                    Text("Custom", style = Type.Small.copy(color = Palette.Accent, fontSize = 12.sp))
                }
            }
            Spacer(Modifier.height(6.dp))
            ValueSlider(zoom, 1f..2f, { "${(it * 100).roundToInt()}%" }, { zoom = it }, {}, title = "Zoom")
            Spacer(Modifier.height(4.dp))
            val running = progress
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 150.dp, height = 40.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (running == null) Palette.Accent else Color(0xFF3A3A3A))
                        .clickable {
                            if (running != null) {
                                runCatching { cancelTracking() }
                                progress = null
                                return@clickable
                            }
                            val s = vm.session ?: return@clickable
                            val target = TrackTarget.at
                            progress = 0f
                            scope.launch(Threads.commands) {
                                runCatching { s.cameraTrack(clip.id, target.x.toDouble(), target.y.toDouble(), zoom.toDouble(), listener) }
                                    .onFailure { e -> launch(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (running != null) {
                        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(running.coerceIn(0f, 1f)).height(40.dp).background(Palette.Accent.copy(alpha = 0.5f)))
                    }
                    Text(
                        if (running == null) "Track" else "Stop  ${(running * 100).toInt()}%",
                        style = Type.BodyStrong.copy(fontSize = 15.sp, color = Color.White),
                    )
                }
            }
        }
    }
}
