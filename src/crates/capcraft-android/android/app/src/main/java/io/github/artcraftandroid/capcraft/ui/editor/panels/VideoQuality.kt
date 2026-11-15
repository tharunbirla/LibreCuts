package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.toBitmap
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Panel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val ADJUST = "concat.adjust"
private const val CLEAN = "capcraft.clean-picture"
private const val SHARPEN = "concat.sharpen"

/**
 * Where the timeline keeps, by clip, what the one-tap tools set: which is on ("enhance" or
 * "auto"), how strongly, and the adjustments they worked out at full strength.
 */
private const val QUALITY = "capcraftQuality"

/** What Auto enhance adds besides the auto adjustments: some sharpening and a little cleaning. */
private const val ENHANCE_SHARPEN = 20.0
private const val ENHANCE_CLEAN = 30.0

private class QualityTool(val key: String, val label: String, val icon: ImageVector)

private val TOOLS = listOf(
    QualityTool("enhance", "Auto enhance", LucideIcons.WandSparkles),
    QualityTool("hd", "Enhance quality", MaterialIcons.Hd),
    QualityTool("noise", "Reduce image noise", MaterialIcons.Grain),
    QualityTool("auto", "Auto adjust", LucideIcons.Palette),
    QualityTool("stabilize", "Stabilize", LucideIcons.Vibrate),
    QualityTool("flow", "Optical flow", LucideIcons.Layers),
    QualityTool("superres", "Super resolution", LucideIcons.ZoomIn),
    QualityTool("flicker", "Remove flickers", LucideIcons.Sun),
)

/**
 * Adjustments that bring [frame] to an even exposure, contrast and colour, read from its
 * histogram: the middle tone centred, a flat picture stretched, clipped ends drawn in, a dull one
 * given colour, and a colour cast taken out by assuming the scene averages to grey.
 */
internal fun autoAdjustments(frame: Bitmap): JSONObject {
    val w = frame.width
    val h = frame.height
    val px = IntArray(w * h)
    frame.getPixels(px, 0, w, 0, 0, w, h)
    val n = px.size.coerceAtLeast(1)
    val lum = FloatArray(px.size)
    var sr = 0.0
    var sg = 0.0
    var sb = 0.0
    var chroma = 0.0
    for (i in px.indices) {
        val c = px[i]
        val r = (c shr 16 and 255) / 255f
        val g = (c shr 8 and 255) / 255f
        val b = (c and 255) / 255f
        lum[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        sr += r
        sg += g
        sb += b
        chroma += (max(r, max(g, b)) - min(r, min(g, b))).toDouble()
    }
    lum.sort()
    val lo = lum[(n * 0.02).toInt().coerceIn(0, n - 1)]
    val hi = lum[(n * 0.98).toInt().coerceIn(0, n - 1)]
    val mid = lum[n / 2]
    val out = JSONObject()
    val brightness = ((0.47f - mid) * 110).coerceIn(-30f, 30f)
    if (abs(brightness) >= 3) out.put("brightness", brightness.roundToInt())
    val contrast = ((0.82f - (hi - lo)) * 80).coerceIn(-15f, 35f)
    if (abs(contrast) >= 3) out.put("contrast", contrast.roundToInt())
    if (hi > 0.96f) out.put("highlights", -20)
    if (lo < 0.04f) out.put("shadows", 20)
    val saturation = ((0.28 - chroma / n) * 80).coerceIn(0.0, 25.0)
    if (saturation >= 3) out.put("saturation", saturation.roundToInt())
    val g = (sg / n).coerceAtLeast(0.02)
    // a warm cast is cooled by a higher kelvin, a green one pushed towards magenta
    val warmth = (sr - sb) / n / g
    if (abs(warmth) > 0.04) out.put("temperature", (6500 + warmth * 5000).coerceIn(5000.0, 8500.0).roundToInt())
    val green = (sg / n - (sr + sb) / n / 2) / g
    if (abs(green) > 0.04) out.put("tint", (green * 150).coerceIn(-30.0, 30.0).roundToInt())
    return out
}

/** [base] at [strength] per cent: each step from neutral scaled, the temperature from 6500. */
private fun scaled(base: JSONObject, strength: Double): JSONObject {
    val out = JSONObject()
    val f = strength / 100.0
    for (key in base.keys()) {
        val v = base.optDouble(key)
        out.put(key, if (key == "temperature") 6500 + (v - 6500) * f else v * f)
    }
    return out
}

/**
 * The Video quality tab of the look sheet: one-tap tools in round buttons and, for the one chosen,
 * how strongly. Auto enhance and Auto adjust read the frame at the playhead; Enhance quality and
 * Stabilize open their own sheets.
 */
@Composable
internal fun ColumnScope.QualityContent(vm: EditorViewModel) {
    val clip = vm.selectedClip
    val visual = clip != null && (clip.kind == "video" || clip.kind == "image")
    val scope = rememberCoroutineScope()
    val revision = vm.timeline?.revision
    val extra = remember(revision) {
        runCatching { vm.session?.extra(QUALITY)?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull() ?: JSONObject()
    }
    val mine = clip?.let { extra.optJSONObject(it.id) }
    val chain = remember(clip?.json) { clip?.let { vm.clipJson(it.id)?.optJSONArray("videoEffects") } ?: JSONArray() }
    val cleanLink = (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == CLEAN }
    val sharpLink = (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == SHARPEN }

    /** The clip's chain with its sharpening at [amount], or without it (null). */
    fun withSharpen(amount: Double?): JSONArray {
        val old = clip?.let { vm.clipJson(it.id)?.optJSONArray("videoEffects") } ?: JSONArray()
        val next = JSONArray()
        var placed = false
        for (i in 0 until old.length()) {
            val link = old.getJSONObject(i)
            if (link.optString("id") != SHARPEN) {
                next.put(link)
            } else if (amount != null && !placed) {
                next.put(JSONObject().put("id", SHARPEN).put("params", JSONObject().put("amount", amount)))
                placed = true
            }
        }
        if (amount != null && !placed) next.put(JSONObject().put("id", SHARPEN).put("params", JSONObject().put("amount", amount)))
        return next
    }
    val on = mine?.optString("on").orEmpty()
    val enhanced = remember(clip?.json) { clip?.let { vm.clipJson(it.id)?.optJSONObject("enhanced")?.optBoolean("on") } == true }
    var which by remember(clip?.id) { mutableStateOf(on.ifEmpty { if (cleanLink != null) "noise" else "enhance" }) }
    var working by remember { mutableStateOf(false) }
    // a flicker-free copy being made, how far along
    var evening by remember { mutableStateOf<Float?>(null) }
    // a smoothed slow-motion copy being made, how far along
    var smoothing by remember { mutableStateOf<Float?>(null) }
    val smoothed = remember(clip?.json, revision) {
        clip != null && runCatching {
            vm.session?.extra("capcraftSmoothed")?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.has(clip.path)
        }.getOrNull() == true
    }
    val smoothListener = remember {
        object : io.github.artcraftandroid.capcraft.core.SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { smoothing = fraction }
            }

            override fun onDone(result: io.github.artcraftandroid.capcraft.core.TimelineView?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    smoothing = null
                    result?.let { vm.applyView(it) }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    smoothing = null
                    vm.message = message
                }
            }
        }
    }
    val deflickered = remember(clip?.json, revision) {
        clip != null && runCatching {
            vm.session?.extra("capcraftDeflickered")?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.has(clip.path)
        }.getOrNull() == true
    }
    val evenListener = remember {
        object : io.github.artcraftandroid.capcraft.core.SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) { evening = fraction }
            }

            override fun onDone(result: io.github.artcraftandroid.capcraft.core.TimelineView?) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    evening = null
                    result?.let { vm.applyView(it) }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    evening = null
                    vm.message = message
                }
            }
        }
    }

    /** The clip's chain with the adjustments [remove] taken out and [add] put in, and the cleaning set to [clean] (null leaves it). */
    fun chainWith(remove: JSONObject?, add: JSONObject?, clean: Double?, dropClean: Boolean): JSONArray {
        val c = clip ?: return JSONArray()
        val old = vm.clipJson(c.id)?.optJSONArray("videoEffects") ?: JSONArray()
        val next = JSONArray()
        var adjusted = false
        var cleaned = false
        for (i in 0 until old.length()) {
            val link = JSONObject(old.getJSONObject(i).toString())
            when (link.optString("id")) {
                ADJUST -> {
                    val p = link.optJSONObject("params") ?: JSONObject()
                    remove?.keys()?.forEach { p.remove(it) }
                    add?.keys()?.forEach { p.put(it, add.get(it)) }
                    adjusted = true
                    if (p.length() > 0) next.put(link.put("params", p))
                }
                CLEAN -> {
                    cleaned = true
                    when {
                        dropClean -> {}
                        clean != null -> next.put(link.put("params", JSONObject().put("strength", clean)))
                        else -> next.put(link)
                    }
                }
                else -> next.put(link)
            }
        }
        if (!adjusted && add != null && add.length() > 0) next.put(JSONObject().put("id", ADJUST).put("params", add))
        if (!cleaned && clean != null && !dropClean) next.put(JSONObject().put("id", CLEAN).put("params", JSONObject().put("strength", clean)))
        return next
    }

    /** The chain and the timeline's note of the tools, changed together as one undo step. */
    fun commit(next: JSONArray, note: JSONObject?, gesture: String? = null) {
        val c = clip ?: return
        val all = JSONObject(extra.toString())
        if (note == null) all.remove(c.id) else all.put(c.id, note)
        val batch = JSONArray()
            .put(JSONObject().put("op", "updateClip").put("clipId", c.id).put("patch", JSONObject().put("videoEffects", next)))
            .put(JSONObject().put("op", "setTimelineExtra").put("key", QUALITY).put("value", if (all.length() == 0) JSONObject.NULL else all))
        vm.command(JSONObject().put("op", "batch").put("commands", batch), gesture)
    }

    /** What the one-tap tool now on wrote, taken back out. */
    fun undoneChain(): JSONArray {
        val base = mine?.optJSONObject("base")
        val whole = if (on == "enhance") JSONObject(base?.toString() ?: "{}").put("sharpen", 0) else base
        return chainWith(whole, null, null, dropClean = on == "enhance")
    }

    /** Turns on Auto enhance ([full] false: Auto adjust), reading the frame at the playhead. */
    fun turnOn(full: Boolean) {
        val c = clip ?: return
        working = true
        scope.launch {
            val speed = c.speed.takeIf { it > 0 } ?: 1.0
            val at = if (c.kind == "video") {
                (c.sourceStartUs + ((vm.playhead - c.startUs).coerceIn(0, c.durationUs) * speed).toLong())
                    .coerceIn(c.sourceStartUs, maxOf(c.sourceStartUs, c.mediaDurationUs - 50_000))
            } else {
                0L
            }
            val base = withContext(Threads.thumbnails) {
                runCatching { still(c.path, at, 160u).toBitmap()?.let(::autoAdjustments) }.getOrNull()
            }
            working = false
            if (base == null) {
                vm.message = "Could not read the picture"
                return@launch
            }
            // whichever one-tap tool was on gives way
            val cleared = if (on.isNotEmpty()) undoneChain() else null
            val add = if (full) JSONObject(base.toString()).put("sharpen", ENHANCE_SHARPEN) else base
            val next = if (cleared != null) {
                mergeInto(cleared, add, if (full) ENHANCE_CLEAN else null)
            } else {
                chainWith(null, add, if (full) ENHANCE_CLEAN else null, dropClean = false)
            }
            commit(next, JSONObject().put("on", if (full) "enhance" else "auto").put("strength", 100).put("base", base))
            which = if (full) "enhance" else "auto"
        }
    }

    fun setStrength(s: Double) {
        val base = mine?.optJSONObject("base") ?: return
        val add = scaled(base, s)
        if (on == "enhance") add.put("sharpen", ENHANCE_SHARPEN * s / 100)
        val next = chainWith(null, add, if (on == "enhance") ENHANCE_CLEAN * s / 100 else null, dropClean = false)
        commit(next, JSONObject(mine.toString()).put("strength", s), gesture = "quality-strength")
    }

    fun tap(key: String) {
        if (!visual) {
            vm.message = "Choose a video or photo first"
            return
        }
        when (key) {
            // clarity: the picture sharpened, as strongly as the slider says
            "hd" -> {
                if (which == "hd" && sharpLink != null) commit(withSharpen(null), mine)
                else if (sharpLink == null) commit(withSharpen(1.1), mine)
                which = "hd"
            }
            "superres" -> vm.panel = Panel.Enhance
            // the frames slow motion lacks, drawn in between; a second tap on the chosen tool undoes it
            "flow" -> {
                val c = clip
                val s = vm.session
                when {
                    c == null || s == null -> {}
                    c.kind != "video" -> vm.message = "Optical flow is for videos"
                    smoothing != null -> {}
                    smoothed && which != "flow" -> {}
                    !smoothed && c.speed > 0.95 -> vm.message = "Slow the clip down first"
                    else -> {
                        val on = !smoothed
                        if (on) smoothing = 0f
                        vm.stop()
                        scope.launch(io.github.artcraftandroid.capcraft.engine.Threads.commands) {
                            runCatching { s.smoothSlowMotion(c.id, on, smoothListener) }
                                .onFailure { e -> launch(kotlinx.coroutines.Dispatchers.Main) { smoothing = null; vm.message = e.message ?: "Could not smooth the motion" } }
                        }
                    }
                }
                which = "flow"
            }
            "stabilize" -> if (clip?.kind == "video") vm.panel = Panel.Stabilize else vm.message = "Stabilize is for videos"
            "flicker" -> {
                val c = clip
                val s = vm.session
                when {
                    c == null || s == null -> {}
                    c.kind != "video" -> vm.message = "Remove flickers is for videos"
                    evening != null -> {}
                    // a second tap on the chosen tool puts the original back
                    deflickered && which != "flicker" -> {}
                    else -> {
                        val on = !deflickered
                        if (on) evening = 0f
                        vm.stop()
                        scope.launch(io.github.artcraftandroid.capcraft.engine.Threads.commands) {
                            runCatching { s.removeFlicker(c.id, on, evenListener) }
                                .onFailure { e -> launch(kotlinx.coroutines.Dispatchers.Main) { evening = null; vm.message = e.message ?: "Could not remove flickers" } }
                        }
                    }
                }
                which = "flicker"
            }
            "noise" -> {
                if (which == "noise" && cleanLink != null) commit(chainWith(null, null, null, dropClean = true), mine)
                else if (cleanLink == null) commit(chainWith(null, null, 50.0, dropClean = false), mine)
                which = "noise"
            }
            else -> {
                val full = key == "enhance"
                when {
                    on == key && which == key -> commit(undoneChain(), null)
                    on == key -> which = key
                    else -> turnOn(full)
                }
                which = key
            }
        }
    }

    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TOOLS.forEachIndexed { i, t ->
            val active = when (t.key) {
                "enhance", "auto" -> on == t.key
                "noise" -> cleanLink != null
                "hd" -> sharpLink != null
                "superres" -> enhanced
                "flow" -> smoothed
                "flicker" -> deflickered
                else -> false
            }
            Column(
                Modifier.width(80.dp).clickable { tap(t.key) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color(0xFF333333))
                        .then(if (which == t.key) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(t.icon, if (active) Palette.Accent else Palette.DockText, size = 22.dp)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    t.label,
                    style = Type.Small.copy(fontSize = 11.sp, color = if (which == t.key) Palette.Text else Palette.TextSecondary),
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    // narrow enough that two-word names break onto two lines, as the reference sets them
                    modifier = Modifier.width(62.dp),
                )
            }
            if (i == 0) {
                Box(Modifier.padding(top = 14.dp).width(1.dp).height(28.dp).background(Color(0xFF3A3A3A)))
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    val strength: Double? = when (which) {
        "enhance", "auto" -> if (on == which) mine?.optDouble("strength", 100.0) else null
        "noise" -> cleanLink?.optJSONObject("params")?.optDouble("strength", 50.0)
        "hd" -> sharpLink?.optJSONObject("params")?.optDouble("amount", 1.1)?.let { ((it - 0.2) / 1.8 * 100).coerceIn(0.0, 100.0) }
        else -> null
    }
    var held by remember(which, clip?.id) { mutableStateOf<Float?>(null) }
    when {
        smoothing != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Smoothing the motion… ${((smoothing ?: 0f) * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
            Text(
                "Cancel",
                style = Type.Small.copy(color = Palette.Text),
                modifier = Modifier.clickable { runCatching { io.github.artcraftandroid.capcraft.core.cancelSmoothSlowMotion() } }.padding(8.dp),
            )
        }
        evening != null -> Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Removing flickers… ${((evening ?: 0f) * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
            Text(
                "Cancel",
                style = Type.Small.copy(color = Palette.Text),
                modifier = Modifier.clickable { runCatching { io.github.artcraftandroid.capcraft.core.cancelDeflicker() } }.padding(8.dp),
            )
        }
        working -> Text("Reading the picture…", style = Type.Small.copy(color = Palette.TextSecondary), modifier = Modifier.padding(horizontal = 20.dp))
        strength != null -> ValueSlider(
            value = held ?: strength.toFloat(),
            range = 0f..100f,
            label = { "${it.roundToInt()}" },
            onChange = { v ->
                held = v
                if (which == "noise") {
                    commit(chainWith(null, null, v.toDouble(), dropClean = false), mine, gesture = "quality-noise")
                } else if (which == "hd") {
                    commit(withSharpen(0.2 + v / 100.0 * 1.8), mine, gesture = "quality-sharpen")
                } else {
                    setStrength(v.toDouble())
                }
            },
            onDone = {
                held = null
                vm.endGesture()
            },
        )
    }
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.clickable {
                if (clip != null && (on.isNotEmpty() || cleanLink != null)) commit(chainWith(mine?.optJSONObject("base")?.let { b -> if (on == "enhance") JSONObject(b.toString()).put("sharpen", 0) else b }, null, null, dropClean = true), null)
            }.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(LucideIcons.RotateCcw, Palette.DockText, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Reset", style = Type.Body.copy(color = Palette.DockText))
        }
    }
}

/** [chain] with [add] laid into its adjustments and the cleaning set to [clean] when given. */
private fun mergeInto(chain: JSONArray, add: JSONObject, clean: Double?): JSONArray {
    val next = JSONArray()
    var adjusted = false
    var cleaned = false
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        when (link.optString("id")) {
            ADJUST -> {
                val p = link.optJSONObject("params") ?: JSONObject()
                add.keys().forEach { p.put(it, add.get(it)) }
                next.put(link.put("params", p))
                adjusted = true
            }
            CLEAN -> {
                cleaned = true
                next.put(if (clean != null) link.put("params", JSONObject().put("strength", clean)) else link)
            }
            else -> next.put(link)
        }
    }
    if (!adjusted && add.length() > 0) next.put(JSONObject().put("id", ADJUST).put("params", add))
    if (!cleaned && clean != null) next.put(JSONObject().put("id", CLEAN).put("params", JSONObject().put("strength", clean)))
    return next
}
