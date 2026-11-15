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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The brush Custom removal paints with: one of the engine's four, by the name its strokes keep
 * ("smartBrush" keeps the thing under it, "brush" all it goes over, and the erasers take away), and
 * how wide, as a share of the picture's width.
 */
object CutoutBrush {
    var tool by mutableStateOf("smartBrush")
    var size by mutableFloatStateOf(0.06f)
    val erasing get() = tool == "eraser" || tool == "smartEraser"

    /** A smart stroke read by the brush model, as it goes: what is being done and how far. */
    var reading by mutableStateOf<Pair<String, Float>?>(null)
}

/** The four brushes, in the reference's order: by the name the engine keeps, what the tile says, its icon. */
private val BRUSHES = listOf(
    Triple("smartBrush", "Smart brush", LucideIcons.WandSparkles),
    Triple("brush", "Brush", LucideIcons.Paintbrush),
    Triple("smartEraser", "Smart eraser", LucideIcons.Sparkles),
    Triple("eraser", "Eraser", LucideIcons.Eraser),
)

/**
 * After a smart stroke: the brush model reads what it lies on, along the clip, with its progress in
 * [CutoutBrush.reading], and the picture is drawn again when it has.
 */
private fun readStrokes(vm: EditorViewModel, clipId: String) {
    val s = vm.session ?: return
    CutoutBrush.reading = "Reading the stroke" to 0f
    val listener = object : SpeechListener {
        override fun onProgress(fraction: Float, stage: String) {
            CutoutBrush.reading = (if (stage == "download") "Downloading the brush model" else "Reading the stroke") to fraction
        }

        override fun onDone(result: TimelineView?) {
            CutoutBrush.reading = null
            result?.let { view -> vm.viewModelScope.launch(Dispatchers.Main) { vm.applyView(view) } }
        }

        override fun onFailed(message: String) {
            CutoutBrush.reading = null
            vm.message = message
        }
    }
    vm.viewModelScope.launch(Threads.commands) {
        runCatching { s.readSmartStrokes(clipId, listener) }.onFailure { e ->
            CutoutBrush.reading = null
            vm.message = e.userMessage
        }
    }
}

/**
 * Custom removal: the subject found by the model, as Auto removal finds it, then put right by hand
 * on the picture - Brush keeps what it goes over, Eraser takes it away - each stroke one step.
 */
@Composable
fun CustomCutoutPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { progress = (if (stage == "download") "Downloading the model" else "Finding the subject") to fraction }
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
                    vm.panel = null
                }
            }
        }
    }
    // the model's cutout first, when the clip has none to put right
    LaunchedEffect(clip.id) {
        if (cutoutOn(vm, clip)) return@LaunchedEffect
        val s = vm.session ?: return@LaunchedEffect
        progress = "Finding the subject" to 0f
        withContext(Threads.commands) {
            runCatching { s.removeBackground(clip.id, true, listener) }
                .onFailure { e -> withContext(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
        }
    }
    PanelFrame("Custom removal", vm, height = 200.dp) {
        val p = progress ?: CutoutBrush.reading
        if (p != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Text("${p.first}  ${(p.second * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A))) {
                    Box(Modifier.fillMaxWidth(p.second.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
                }
            }
            return@PanelFrame
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            BRUSHES.forEach { (id, label, icon) ->
                val chosen = CutoutBrush.tool == id
                Column(
                    Modifier.width(80.dp).clip(RoundedCornerShape(8.dp)).clickable { CutoutBrush.tool = id }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF333333))
                            .then(if (chosen) Modifier.border(2.dp, Color.White, RoundedCornerShape(22.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { Glyph(icon, if (chosen) Palette.Text else Palette.DockText, size = 20.dp) }
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = Type.Small.copy(color = if (chosen) Palette.Text else Palette.TextSecondary))
                }
            }
        }
        ValueSlider(
            value = CutoutBrush.size * 100f,
            range = 1f..30f,
            label = { "${it.roundToInt()}" },
            onChange = { CutoutBrush.size = it / 100f },
            onDone = {},
            title = "Size",
        )
    }
}

/**
 * The picture under Custom removal: the finger paints a stroke (shown as it goes, green to keep,
 * red to take away), and as it lifts the stroke goes on the clip's cutout in the picture's own
 * fractions, painted at the frame under the playhead.
 */
@Composable
fun CutoutSurface(vm: EditorViewModel, modifier: Modifier) {
    val clip = vm.selectedClip ?: return
    val session = vm.session
    val playhead = vm.playhead
    val box by produceState<ClipBox?>(null, session, vm.timeline?.revision, playhead, clip.id) {
        val s = session ?: return@produceState
        value = withContext(Threads.commands) { runCatching { s.clipBoxes(playhead) }.getOrDefault(emptyList()) }.firstOrNull { it.clipId == clip.id }
    }
    val shape = remember(clip.json) { clipShape(vm, clip) }
    val latestBox by rememberUpdatedState(box)
    val latestShape by rememberUpdatedState(shape)
    val latestPlayhead by rememberUpdatedState(playhead)
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Canvas(
        modifier.pointerInput(clip.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                down.consume()
                val points = mutableListOf(down.position)
                live = points.toList()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    if ((change.position - points.last()).getDistance() > 3f) {
                        points.add(change.position)
                        live = points.toList()
                    }
                    change.consume()
                }
                live = emptyList()
                val b = latestBox ?: return@awaitEachGesture
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val mapped = points.mapNotNull { sourcePoint(b, latestShape, clip, it, w, h) }
                if (mapped.isEmpty()) return@awaitEachGesture
                // the width as a share of the whole picture's, the crop allowed for
                val spanU = (1f - latestShape[0] - latestShape[2]).coerceAtLeast(0.01f)
                val speed = clip.speed.takeIf { it > 0 } ?: 1.0
                val at = (clip.sourceStartUs + ((latestPlayhead - clip.startUs).coerceIn(0, clip.durationUs) * speed).toLong()) / 1e6
                val tool = CutoutBrush.tool
                val stroke = JSONObject()
                    .put("tool", tool)
                    .put("size", (CutoutBrush.size * spanU).toDouble().coerceIn(0.005, 0.5))
                    .put("points", JSONArray(mapped.map { JSONArray(listOf(it.x.toDouble(), it.y.toDouble())) }))
                    .put("at", at)
                vm.command(JSONObject().put("op", "addCutoutStroke").put("clipId", clip.id).put("stroke", stroke), null)
                // a smart stroke is read by the brush model once the engine has it
                if (tool.startsWith("smart")) {
                    vm.viewModelScope.launch {
                        kotlinx.coroutines.delay(300)
                        readStrokes(vm, clip.id)
                    }
                }
            }
        },
    ) {
        val b = box ?: return@Canvas
        val radius = (CutoutBrush.size * b.width * size.width).toFloat() / 2f
        val colour = if (CutoutBrush.erasing) Color(0x88FF3B30) else Color(0x8834C759)
        live.forEach { drawCircle(colour, radius, it) }
    }
}
