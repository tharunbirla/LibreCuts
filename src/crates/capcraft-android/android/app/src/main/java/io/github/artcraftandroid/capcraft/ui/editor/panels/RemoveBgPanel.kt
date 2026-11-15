package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val CHROMA = "concat.chroma-key"

/** The key's edge softened inward, kept right after the key in the chain. */
private const val FEATHER = "capcraft.feather-edge"

/** [clip]'s crop (left, top, right, bottom) and straightening in degrees, for [sourcePoint]. */
internal fun clipShape(vm: EditorViewModel, clip: ClipView): FloatArray {
    val j = vm.clipJson(clip.id)
    val crop = j?.optJSONObject("crop")
    val chain = j?.optJSONArray("videoEffects") ?: JSONArray()
    val angle = (0 until chain.length()).map { chain.getJSONObject(it) }
        .firstOrNull { it.optString("id") == "capcraft.straighten" }?.optJSONObject("params")?.optDouble("angle", 0.0) ?: 0.0
    return floatArrayOf(
        crop?.optDouble("left", 0.0)?.toFloat() ?: 0f,
        crop?.optDouble("top", 0.0)?.toFloat() ?: 0f,
        crop?.optDouble("right", 0.0)?.toFloat() ?: 0f,
        crop?.optDouble("bottom", 0.0)?.toFloat() ?: 0f,
        angle.toFloat(),
    )
}

/**
 * Where [p] (preview pixels in a [w] by [h] preview) lies in [clip]'s own picture, as fractions of
 * its width and height: back through the clip's place on the frame ([b]), its straightening and
 * its crop ([shape], from [clipShape]). Null off the clip.
 */
internal fun sourcePoint(b: ClipBox, shape: FloatArray, clip: ClipView, p: Offset, w: Float, h: Float): Offset? {
    val cx = (b.cx * w).toFloat()
    val cy = (b.cy * h).toFloat()
    val a = Math.toRadians(-b.rotation).toFloat()
    val dx = p.x - cx
    val dy = p.y - cy
    val lx = dx * cos(a) - dy * sin(a)
    val ly = dx * sin(a) + dy * cos(a)
    var u = lx / (b.width * w).toFloat() + 0.5f
    var v = ly / (b.height * h).toFloat() + 0.5f
    if (u !in 0f..1f || v !in 0f..1f) return null
    val (cl, ct, cr, cb, degrees) = shape.toList()
    val spanU = (1f - cl - cr).coerceAtLeast(0.01f)
    val spanV = (1f - ct - cb).coerceAtLeast(0.01f)
    if (degrees != 0f) {
        // undone as the straighten effect does it: turned back about the middle, and nearer it
        val aspect = clip.mediaWidth.toFloat() * spanU / (clip.mediaHeight.toFloat() * spanV).coerceAtLeast(1e-3f)
        val t = Math.toRadians(degrees.toDouble()).toFloat()
        val grow = kotlin.math.abs(cos(t)) + maxOf(aspect, 1f / aspect) * kotlin.math.abs(sin(t))
        val px = (u - 0.5f) * aspect
        val py = v - 0.5f
        val qx = (cos(t) * px + sin(t) * py) / grow
        val qy = (-sin(t) * px + cos(t) * py) / grow
        u = (qx / aspect + 0.5f).coerceIn(0f, 1f)
        v = (qy + 0.5f).coerceIn(0f, 1f)
    }
    return Offset(cl + u * spanU, ct + v * spanV)
}

/** An RGB colour as the chroma key package stores it: 0xRRGGBBAA. */
private fun packed(rgb: Int): Double = ((rgb.toLong() and 0xFFFFFF) shl 8 or 0xFF).toDouble()

/** Whether [clip] has its background taken off by the model. */
fun cutoutOn(vm: EditorViewModel, clip: ClipView): Boolean {
    val json = vm.clipJson(clip.id) ?: return false
    return json.has("cutout") && !json.isNull("cutout")
}

/**
 * Auto removal: the background taken off the selected clip by the engine's segmentation model on
 * the phone (fetched the first time), with its progress and a way to stop; or, when it is off
 * already, put back at once.
 */
@Composable
fun RemoveBgPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Pair<String, Float>?>("Removing the background" to 0f) }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) {
                    progress = (if (stage == "download") "Downloading the model" else "Removing the background") to fraction
                }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    result?.let { vm.applyView(it) }
                    vm.panel = null
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
    val wasOn = remember(clip.id) { cutoutOn(vm, clip) }
    LaunchedEffect(clip.id) {
        val s = vm.session ?: return@LaunchedEffect
        withContext(Threads.commands) {
            runCatching { s.removeBackground(clip.id, !wasOn, listener) }
                .onFailure { e -> withContext(Dispatchers.Main) { vm.message = e.userMessage; vm.panel = null } }
        }
        if (wasOn) vm.message = "Background restored"
    }
    fun stop() {
        vm.session?.let { s -> scope.launch(Threads.commands) { runCatching { s.cancelBackground() } } }
        vm.panel = null
    }
    PanelFrame("Auto removal", vm, height = 150.dp, onDone = ::stop) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            val p = progress
            if (p != null && !wasOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.first}  ${(p.second * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                    Text(
                        "Cancel",
                        style = Type.Small.copy(color = Palette.Text),
                        modifier = Modifier.clickable(onClick = ::stop).padding(8.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A))) {
                    Box(Modifier.fillMaxWidth(p.second.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
                }
            }
        }
    }
}

// ── Chroma key ──

/** The colour picker over the picture while the Chroma key sheet is open, as fractions of the preview; null when put away. */
object ChromaPick {
    var at by mutableStateOf<Offset?>(null)
}

/** The chroma key link on [clipId] changed by [change], or taken off (null). */
private fun setChroma(vm: EditorViewModel, clipId: String, gesture: String?, change: (JSONObject.() -> Unit)?) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val next = JSONArray()
    var link: JSONObject? = null
    var feather: JSONObject? = null
    for (i in 0 until chain.length()) {
        val l = chain.getJSONObject(i)
        when (l.optString("id")) {
            CHROMA -> link = l
            FEATHER -> feather = l
            else -> next.put(l)
        }
    }
    // the key last, its feather after it; taking the key off takes the feather too
    if (change != null) {
        val l = link ?: JSONObject().put("id", CHROMA).put("params", JSONObject())
        val p = l.optJSONObject("params") ?: JSONObject()
        p.change()
        next.put(l.put("params", p))
        feather?.let { next.put(it) }
    }
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/** The key's edge feathered by [amount] per cent, right after the key; none at 0. */
private fun setFeather(vm: EditorViewModel, clipId: String, amount: Double, gesture: String?) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val next = JSONArray()
    for (i in 0 until chain.length()) {
        val l = chain.getJSONObject(i)
        when (l.optString("id")) {
            FEATHER -> {}
            CHROMA -> {
                next.put(l)
                if (amount > 0) next.put(JSONObject().put("id", FEATHER).put("params", JSONObject().put("amount", amount)))
            }
            else -> next.put(l)
        }
    }
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/**
 * Chroma key: a colour picker (a ring dragged over the picture onto the colour to take out) and
 * how hard it keys, how much shadow it keeps, how far its edge is feathered, and how much of the
 * colour's spill on the edges it cleans up.
 */
@Composable
fun ChromaPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val chroma: JSONObject? = remember(clip.json) {
        vm.clipJson(clip.id)?.optJSONArray("videoEffects")?.let { chain ->
            (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == CHROMA }
        }
    }
    val params = chroma?.optJSONObject("params") ?: JSONObject()
    val feather: Double = remember(clip.json) {
        vm.clipJson(clip.id)?.optJSONArray("videoEffects")?.let { chain ->
            (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == FEATHER }
        }?.optJSONObject("params")?.optDouble("amount", 0.0) ?: 0.0
    }
    DisposableEffect(clip.id) {
        // the picker comes out at once for a clip with no key yet
        if (chroma == null) ChromaPick.at = Offset(0.5f, 0.45f)
        onDispose { ChromaPick.at = null }
    }
    val picking = ChromaPick.at != null
    PanelFrame("Chroma key", vm, height = 376.dp, titleOnTop = true) {
        Spacer(Modifier.height(14.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF333333))
                    .then(if (picking) Modifier.border(1.5.dp, Palette.Accent, CircleShape) else Modifier)
                    .clickable { ChromaPick.at = if (picking) null else Offset(0.5f, 0.45f) },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Crosshair, if (picking || chroma != null) Palette.Accent else Palette.DockText, size = 22.dp) }
            Spacer(Modifier.height(6.dp))
            Text("Color picker", style = Type.Small.copy(fontSize = 12.sp, color = if (picking || chroma != null) Palette.Accent else Palette.TextSecondary))
        }
        Spacer(Modifier.height(12.dp))
        val on = chroma != null
        ChromaSlider(vm, clip.id, "Intensity", "similarity", params.optDouble("similarity", 30.0).toFloat(), 1f..60f, on)
        ChromaSlider(vm, clip.id, "Shadow", "softness", params.optDouble("softness", 10.0).toFloat(), 0f..50f, on)
        ChromaSlider(vm, clip.id, "Feather edge", "feather", feather.toFloat(), 0f..100f, on) { v ->
            setFeather(vm, clip.id, v.toDouble(), "chroma-feather")
        }
        ChromaSlider(vm, clip.id, "Clean up edge", "spill", params.optDouble("spill", 50.0).toFloat(), 0f..100f, on)
    }
}

/** One of the key's sliders, its name at the left; greyed until a colour is picked. [set] stands in for setting the key's own [key]. */
@Composable
private fun ChromaSlider(
    vm: EditorViewModel,
    clipId: String,
    label: String,
    key: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    set: ((Float) -> Unit)? = null,
) {
    var held by remember(clipId, key) { mutableStateOf<Float?>(null) }
    Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Small.copy(fontSize = 13.sp, color = if (enabled) Palette.Text else Palette.TextSecondary), modifier = Modifier.width(118.dp))
        if (enabled) {
            CapSlider(held ?: value, range, null, {
                held = it
                if (set != null) set(it) else setChroma(vm, clipId, "chroma-$key") { put(key, it.toDouble()) }
            }, {
                held = null
                vm.endGesture()
            }, Modifier.weight(1f))
        } else {
            Canvas(Modifier.weight(1f).height(32.dp)) {
                val cy = size.height / 2
                drawLine(Color(0xFF4A4A4A), Offset(0f, cy), Offset(size.width, cy), 2.dp.toPx())
                drawCircle(Color(0xFF555555), 8.dp.toPx(), Offset(8.dp.toPx(), cy), style = Stroke(2.dp.toPx()))
            }
        }
    }
}

/**
 * The colour picker over the picture: a ring showing the colour under its middle, dragged onto
 * the colour to key out; the key is set when the finger lifts. The colour is read from the clip's
 * own frame, so a key already on does not hide it.
 */
@Composable
fun ChromaSurface(vm: EditorViewModel, modifier: Modifier) {
    val clip = vm.selectedClip ?: return
    val at = ChromaPick.at ?: return
    val session = vm.session
    val playhead = vm.playhead
    val box by produceState<ClipBox?>(null, session, vm.timeline?.revision, playhead, clip.id) {
        val s = session ?: return@produceState
        value = withContext(Threads.commands) { runCatching { s.clipBoxes(playhead) }.getOrDefault(emptyList()) }.firstOrNull { it.clipId == clip.id }
    }
    val speed = clip.speed.takeIf { it > 0 } ?: 1.0
    val sourceUs = if (clip.kind == "video") {
        (clip.sourceStartUs + ((playhead - clip.startUs).coerceIn(0, clip.durationUs) * speed).toLong())
            .coerceIn(clip.sourceStartUs, maxOf(clip.sourceStartUs, clip.mediaDurationUs - 50_000)) / 100_000 * 100_000
    } else {
        0L
    }
    val version = Thumbs.version.intValue
    val frame = remember(version, sourceUs, clip.path) { Thumbs.still(clip.path, sourceUs, 360) }
    // the clip's crop and straightening, so a point on the picture is found in the whole frame
    val shape = remember(clip.json) { clipShape(vm, clip) }
    val latestBox by rememberUpdatedState(box)
    val latestFrame by rememberUpdatedState(frame)
    val latestShape by rememberUpdatedState(shape)
    val measurer = rememberTextMeasurer()
    val hint = TextStyle(color = Color.White, fontSize = 11.sp)

    /** The clip's colour under [p] (preview pixels in a [w] by [h] preview), or null off the clip. */
    fun colourAt(p: Offset, w: Float, h: Float): Int? {
        val f = latestFrame ?: return null
        val at = sourcePoint(latestBox ?: return null, latestShape, clip, p, w, h) ?: return null
        val x = (at.x * (f.width - 1)).roundToInt()
        val y = (at.y * (f.height - 1)).roundToInt()
        return f.getPixel(x, y) and 0xFFFFFF
    }

    Canvas(
        modifier.pointerInput(clip.id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat()
                val h = size.height.toFloat()
                val start = ChromaPick.at ?: return@awaitEachGesture
                val origin = Offset(start.x * w, start.y * h)
                // the ring is taken anywhere on the picture and moves with the finger
                down.consume()
                var last = origin
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    last = Offset((origin.x + c.position.x - down.position.x).coerceIn(0f, w), (origin.y + c.position.y - down.position.y).coerceIn(0f, h))
                    ChromaPick.at = Offset(last.x / w, last.y / h)
                    c.consume()
                }
                colourAt(last, w, h)?.let { rgb -> setChroma(vm, clip.id, null) { put("color", packed(rgb)) } }
            }
        },
    ) {
        val p = Offset(at.x * size.width, at.y * size.height)
        val r = 30.dp.toPx()
        val ring = 9.dp.toPx()
        val colour = colourAt(p, size.width, size.height)?.let { Color(0xFF000000.toInt() or it) } ?: Color(0xFF777777)
        drawCircle(colour, r, p, style = Stroke(ring))
        drawCircle(Color.White, r + ring / 2, p, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color.White, r - ring / 2, p, style = Stroke(1.5.dp.toPx()))
        drawCircle(Color.White, 2.dp.toPx(), p)
        val text = measurer.measure("Drag to select color", hint)
        drawText(text, topLeft = Offset(p.x - text.size.width / 2f, p.y + r + ring / 2 + 6.dp.toPx()))
    }
}
