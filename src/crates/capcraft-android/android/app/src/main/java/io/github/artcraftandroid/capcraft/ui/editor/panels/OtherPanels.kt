package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.core.PackageInfo
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

// ── Animation ──

/** One animation preset: the keys it puts on a clip, as (property, at 0..1 of its run, value). */
private data class Anim(val name: String, val category: String, val keys: (base: Base) -> List<Triple<String, Double, Double>>)

/** The clip's resting values the keys lead to or from. */
private data class Base(val scale: Double, val x: Double, val y: Double, val rotation: Double, val opacity: Double)

/** Keys for one property through a run of (at, value) stops. */
private fun run(property: String, vararg stops: Pair<Double, Double>) = stops.map { (at, v) -> Triple(property, at, v) }

/** The same preset played backwards, an in turned into an out. */
private fun backwards(name: String, of: Anim) = Anim(name, of.category) { b -> of.keys(b).map { (p, at, v) -> Triple(p, 1.0 - at, v) } }

private val IN = listOf(
    Anim("Fade in", "Basic") { b -> run("opacity", 0.0 to 0.0, 1.0 to b.opacity) },
    Anim("Zoom in", "Basic") { b -> run("scale", 0.0 to b.scale * 0.6, 1.0 to b.scale) + run("opacity", 0.0 to 0.0, 0.5 to b.opacity) },
    Anim("Zoom out", "Basic") { b -> run("scale", 0.0 to b.scale * 1.5, 1.0 to b.scale) },
    Anim("Rise", "Basic") { b -> run("offsetY", 0.0 to b.y + 0.3, 1.0 to b.y) + run("opacity", 0.0 to 0.0, 0.6 to b.opacity) },
    Anim("Pop", "Basic") { b -> run("scale", 0.0 to b.scale * 0.3, 0.6 to b.scale * 1.12, 1.0 to b.scale) + run("opacity", 0.0 to 0.0, 0.3 to b.opacity) },
    Anim("Slide right", "Slide") { b -> run("offsetX", 0.0 to b.x - 1.0, 1.0 to b.x) },
    Anim("Slide left", "Slide") { b -> run("offsetX", 0.0 to b.x + 1.0, 1.0 to b.x) },
    Anim("Slide up", "Slide") { b -> run("offsetY", 0.0 to b.y + 1.0, 1.0 to b.y) },
    Anim("Slide down", "Slide") { b -> run("offsetY", 0.0 to b.y - 1.0, 1.0 to b.y) },
    Anim("Swipe in", "Slide") { b -> run("offsetX", 0.0 to b.x - 1.0, 1.0 to b.x) + run("rotation", 0.0 to b.rotation - 20, 1.0 to b.rotation) },
    Anim("Rotate", "Spin") { b -> run("rotation", 0.0 to b.rotation - 90, 1.0 to b.rotation) + run("opacity", 0.0 to 0.0, 0.6 to b.opacity) },
    Anim("Spin in", "Spin") { b -> run("rotation", 0.0 to b.rotation - 360, 1.0 to b.rotation) + run("scale", 0.0 to b.scale * 0.2, 1.0 to b.scale) },
    Anim("Twist", "Spin") { b -> run("rotation", 0.0 to b.rotation - 30, 1.0 to b.rotation) + run("scale", 0.0 to b.scale * 1.3, 1.0 to b.scale) + run("opacity", 0.0 to 0.0, 0.4 to b.opacity) },
    Anim("Bounce in", "Bounce") { b -> run("scale", 0.0 to 0.0, 0.5 to b.scale * 1.2, 0.7 to b.scale * 0.9, 0.85 to b.scale * 1.05, 1.0 to b.scale) },
    Anim("Drop", "Bounce") { b -> run("offsetY", 0.0 to b.y - 1.0, 0.6 to b.y + 0.06, 0.8 to b.y - 0.03, 1.0 to b.y) },
    Anim("Spring", "Bounce") { b -> run("offsetX", 0.0 to b.x - 1.0, 0.6 to b.x + 0.08, 0.8 to b.x - 0.04, 1.0 to b.x) },
    Anim("Shake in", "Shake") { b ->
        (0..10).map { i -> Triple("offsetX", i / 10.0, b.x + (if (i % 2 == 0) 1 else -1) * 0.05 * (1.0 - i / 10.0)) } + run("opacity", 0.0 to 0.0, 0.3 to b.opacity)
    },
    Anim("Rock in", "Shake") { b -> run("rotation", 0.0 to b.rotation - 15, 0.25 to b.rotation + 12, 0.5 to b.rotation - 8, 0.75 to b.rotation + 4, 1.0 to b.rotation) },
    Anim("Fade up", "Basic") { b -> run("offsetY", 0.0 to b.y + 0.06, 1.0 to b.y) + run("opacity", 0.0 to 0.0, 1.0 to b.opacity) },
    Anim("Grow", "Basic") { b -> run("scale", 0.0 to b.scale * 0.85, 1.0 to b.scale) + run("opacity", 0.0 to 0.0, 0.7 to b.opacity) },
    Anim("Shrink in", "Basic") { b -> run("scale", 0.0 to b.scale * 1.25, 1.0 to b.scale) + run("opacity", 0.0 to 0.0, 0.7 to b.opacity) },
    Anim("Float in", "Basic") { b -> run("offsetY", 0.0 to b.y + 0.12, 0.7 to b.y - 0.01, 1.0 to b.y) + run("opacity", 0.0 to 0.0, 0.5 to b.opacity) },
    Anim("Glide", "Slide") { b -> run("offsetX", 0.0 to b.x - 0.3, 1.0 to b.x) + run("opacity", 0.0 to 0.0, 0.6 to b.opacity) },
    Anim("Slide in corner", "Slide") { b -> run("offsetX", 0.0 to b.x - 0.7, 1.0 to b.x) + run("offsetY", 0.0 to b.y + 0.7, 1.0 to b.y) },
    Anim("Zoom spin", "Spin") { b -> run("scale", 0.0 to b.scale * 2.0, 1.0 to b.scale) + run("rotation", 0.0 to b.rotation + 90, 1.0 to b.rotation) + run("opacity", 0.0 to 0.0, 0.5 to b.opacity) },
    Anim("Tumble", "Spin") { b -> run("rotation", 0.0 to b.rotation + 720, 1.0 to b.rotation) + run("scale", 0.0 to 0.0, 1.0 to b.scale) },
    Anim("Swing in", "Spin") { b -> run("rotation", 0.0 to b.rotation - 45, 0.55 to b.rotation + 10, 0.8 to b.rotation - 4, 1.0 to b.rotation) + run("opacity", 0.0 to 0.0, 0.4 to b.opacity) },
    Anim("Elastic", "Bounce") { b -> run("scale", 0.0 to 0.0, 0.35 to b.scale * 1.25, 0.55 to b.scale * 0.85, 0.75 to b.scale * 1.08, 0.9 to b.scale * 0.97, 1.0 to b.scale) },
    Anim("Pop up", "Bounce") { b -> run("offsetY", 0.0 to b.y + 0.3, 0.6 to b.y - 0.04, 1.0 to b.y) + run("scale", 0.0 to b.scale * 0.6, 0.6 to b.scale * 1.05, 1.0 to b.scale) },
    Anim("Wiggle in", "Shake") { b ->
        run("rotation", 0.0 to b.rotation - 10, 0.2 to b.rotation + 8, 0.4 to b.rotation - 6, 0.6 to b.rotation + 4, 0.8 to b.rotation - 2, 1.0 to b.rotation) +
            run("scale", 0.0 to b.scale * 0.8, 1.0 to b.scale)
    },
)

private fun inNamed(name: String) = IN.first { it.name == name }

private val OUT = listOf(
    backwards("Fade out", inNamed("Fade in")),
    backwards("Zoom in", inNamed("Zoom out")),
    backwards("Zoom out", inNamed("Zoom in")),
    backwards("Sink", inNamed("Rise")),
    backwards("Pop out", inNamed("Pop")),
    backwards("Slide right", inNamed("Slide left")),
    backwards("Slide left", inNamed("Slide right")),
    backwards("Slide up", inNamed("Slide down")),
    backwards("Slide down", inNamed("Slide up")),
    backwards("Swipe out", inNamed("Swipe in")),
    backwards("Rotate", inNamed("Rotate")),
    backwards("Spin out", inNamed("Spin in")),
    backwards("Twist out", inNamed("Twist")),
    backwards("Bounce out", inNamed("Bounce in")),
    backwards("Fly up", inNamed("Drop")),
    backwards("Spring out", inNamed("Spring")),
    backwards("Shake out", inNamed("Shake in")),
    backwards("Rock out", inNamed("Rock in")),
    backwards("Fade down", inNamed("Fade up")),
    backwards("Shrink", inNamed("Grow")),
    backwards("Grow out", inNamed("Shrink in")),
    backwards("Float out", inNamed("Float in")),
    backwards("Glide out", inNamed("Glide")),
    backwards("Slide out corner", inNamed("Slide in corner")),
    backwards("Zoom spin out", inNamed("Zoom spin")),
    backwards("Tumble out", inNamed("Tumble")),
    backwards("Swing out", inNamed("Swing in")),
    backwards("Elastic out", inNamed("Elastic")),
    backwards("Pop down", inNamed("Pop up")),
    backwards("Wiggle out", inNamed("Wiggle in")),
)

private val COMBO = listOf(
    Anim("Drift", "Basic") { b -> run("scale", 0.0 to b.scale, 1.0 to b.scale * 1.15) },
    Anim("Float", "Basic") { b -> run("offsetY", 0.0 to b.y, 0.25 to b.y - 0.03, 0.5 to b.y, 0.75 to b.y + 0.03, 1.0 to b.y) },
    Anim("Pulse", "Basic") { b -> run("scale", 0.0 to b.scale, 0.25 to b.scale * 1.08, 0.5 to b.scale, 0.75 to b.scale * 1.08, 1.0 to b.scale) },
    Anim("Heartbeat", "Basic") { b -> (0..8).map { i -> Triple("scale", i / 8.0, b.scale * if (i % 2 == 1) 1.12 else 1.0) } },
    Anim("Swing", "Motion") { b -> run("rotation", 0.0 to b.rotation - 8, 0.25 to b.rotation + 8, 0.5 to b.rotation - 8, 0.75 to b.rotation + 8, 1.0 to b.rotation) },
    Anim("Wobble", "Motion") { b ->
        run("rotation", 0.0 to b.rotation, 0.25 to b.rotation + 5, 0.5 to b.rotation - 5, 0.75 to b.rotation + 5, 1.0 to b.rotation) +
            run("scale", 0.0 to b.scale, 0.25 to b.scale * 1.04, 0.5 to b.scale, 0.75 to b.scale * 1.04, 1.0 to b.scale)
    },
    Anim("Bounce", "Motion") { b -> (0..8).map { i -> Triple("offsetY", i / 8.0, b.y - if (i % 2 == 1) 0.06 else 0.0) } },
    Anim("Shake", "Motion") { b -> (0..10).map { i -> Triple("offsetX", i / 10.0, b.x + if (i % 2 == 0) 0.0 else if (i % 4 == 1) 0.015 else -0.015) } },
    Anim("Spin", "Motion") { b -> run("rotation", 0.0 to b.rotation, 1.0 to b.rotation + 360) },
    Anim("Sway", "Motion") { b -> run("offsetX", 0.0 to b.x, 0.25 to b.x - 0.02, 0.5 to b.x, 0.75 to b.x + 0.02, 1.0 to b.x) },
    Anim("Tilt", "Motion") { b -> run("rotation", 0.0 to b.rotation, 0.25 to b.rotation - 4, 0.5 to b.rotation, 0.75 to b.rotation + 4, 1.0 to b.rotation) },
    Anim("Orbit", "Motion") { b ->
        (0..8).map { i -> Triple("offsetX", i / 8.0, b.x + 0.02 * kotlin.math.cos(i / 8.0 * 2 * Math.PI)) } +
            (0..8).map { i -> Triple("offsetY", i / 8.0, b.y + 0.02 * kotlin.math.sin(i / 8.0 * 2 * Math.PI)) }
    },
    Anim("Zoom pulse", "Basic") { b -> run("scale", 0.0 to b.scale, 0.5 to b.scale * 1.15, 1.0 to b.scale) },
    Anim("Jump", "Motion") { b ->
        run("offsetY", 0.0 to b.y, 0.2 to b.y - 0.08, 0.4 to b.y, 0.5 to b.y, 0.7 to b.y - 0.08, 0.9 to b.y, 1.0 to b.y)
    },
)

/** A title's or sticker's loops: a motion that ends where it began, played round and round. */
private val LOOP = COMBO.filter { it.name != "Drift" } + listOf(
    Anim("Blink", "Basic") { b -> run("opacity", 0.0 to b.opacity, 0.5 to b.opacity * 0.2, 1.0 to b.opacity) },
    Anim("Breathe", "Basic") { b -> run("scale", 0.0 to b.scale, 0.5 to b.scale * 0.92, 1.0 to b.scale) + run("opacity", 0.0 to b.opacity, 0.5 to b.opacity * 0.7, 1.0 to b.opacity) },
)

/** The package that brings a title's words in one by one, or lights them one by one. */
private const val WORDS = "capcraft.word-reveal"

/** A title's word by word ins: no keys of their own, the package on its chain does them. */
private val WORD_INS = listOf(
    Anim("Typewriter", "Words") { _ -> emptyList() },
    Anim("Karaoke", "Words") { _ -> emptyList() },
)
private val WORD_NAMES = WORD_INS.map { it.name }.toSet()

/** Whether [clipId] is a title or sticker, which loop where a picture has combos. */
private fun loops(vm: EditorViewModel, clipId: String) = vm.rowOf(clipId)?.kind == io.github.artcraftandroid.capcraft.core.RowKind.TEXT

/** Where the timeline keeps each clip's chosen animations, by clip id. */
private const val ANIMATIONS = "capcraftAnimations"

/** A chosen animation: the preset's name and how long it runs. */
private data class Chosen(val name: String, val seconds: Float)

/**
 * In, out and combo animations, made from the clip's own keys. An in and an out can be on a clip
 * together; a combo runs the clip's whole length and replaces them. What is chosen is kept on the
 * timeline, so the panel shows it again and the keys can be rebuilt when one of them changes.
 */
@Composable
fun AnimationPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    var tab by remember { mutableIntStateOf(0) }
    val third = if (loops(vm, clip.id)) "Loop" else "Combo"
    // a search and the kept ones ahead of the tabs, as the reference's bar has them
    var query by remember { mutableStateOf<String?>(null) }
    var kept by remember { mutableStateOf(false) }
    PanelFrame("Animations", vm, tall = true, titleOnTop = true, header = {
        AnimHeader(third, tab, query, kept, onTab = { tab = it; kept = false }, onQuery = { query = it }, onKept = { kept = true })
    }) {
        AnimationChoices(vm, clip.id, fixedTab = tab, query = query.orEmpty(), kept = kept)
    }
}

/** The animations' bar: a search, the bookmark, then In, Out and Combo (or Loop), as the reference's. */
@Composable
private fun AnimHeader(
    third: String,
    tab: Int,
    query: String?,
    kept: Boolean,
    onTab: (Int) -> Unit,
    onQuery: (String?) -> Unit,
    onKept: () -> Unit,
) {
    if (query != null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Box(Modifier.weight(1f)) { SearchField(query, { onQuery(it) }, "Search animations", autoFocus = true) }
            Text(
                "Cancel",
                style = Type.Body.copy(color = Palette.DockText, fontSize = 14.sp),
                modifier = Modifier.clickable { onQuery(null) }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color(0xFF3A3A3A)).clickable { onQuery("") },
            contentAlignment = Alignment.Center,
        ) { Glyph(LucideIcons.Search, Palette.DockText, size = 16.dp, description = "Search animations") }
        Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 16.dp).background(Color(0xFF444444)))
        Box(Modifier.size(32.dp).clickable(onClick = onKept), contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.Bookmark, if (kept) Palette.Text else Palette.TextSecondary, size = 18.dp, description = "Favorites")
        }
        Tabs(listOf("In", "Out", third), if (kept) -1 else tab) { onTab(it) }
    }
}

/** A sample of words for a title's animation tiles, as the reference shows them: light type on clear. */
private val SAMPLE_WORDS: androidx.compose.ui.graphics.ImageBitmap by lazy {
    val side = 240
    val bitmap = android.graphics.Bitmap.createBitmap(side, side, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8D8D8.toInt()
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        textSize = 46f
        textAlign = android.graphics.Paint.Align.CENTER
    }
    canvas.drawText("ABC123", side / 2f, side / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
    bitmap.asImageBitmap()
}

/**
 * The In, Out and Combo shelves for the clip [clipId], for the Animation panel and the text panel's
 * tab; a title or sticker has Loop in Combo's place, its motion repeated for the clip's length at
 * the pace its Speed sets.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AnimationChoices(vm: EditorViewModel, clipId: String, fixedTab: Int? = null, query: String = "", kept: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val keptSet = remember(Favorites.version.intValue) { Favorites.of(context, "animation") }
    val clip = vm.clip(clipId) ?: return
    var own by remember { mutableIntStateOf(0) }
    // the Animations panel shows the tabs in its bar; the text panel's tab shows them here
    val tab = fixedTab ?: own
    val maxSeconds = (clip.durationUs / 1e6).toFloat().coerceAtLeast(0.1f)
    val stored = remember(clip.id) {
        runCatching { vm.session?.extra(ANIMATIONS)?.takeIf { it.isNotBlank() }?.let { JSONObject(it).optJSONObject(clip.id) } }.getOrNull()
    }

    fun read(part: String) = stored?.optJSONObject(part)?.let { Chosen(it.optString("name"), it.optDouble("seconds", 0.5).toFloat()) }
    var inAnim by remember(clip.id) { mutableStateOf(read("in")) }
    var outAnim by remember(clip.id) { mutableStateOf(read("out")) }
    var comboAnim by remember(clip.id) { mutableStateOf(read("combo")) }
    val looping = loops(vm, clip.id)
    var loopAnim by remember(clip.id) { mutableStateOf(read("loop")) }

    fun apply() {
        val json = vm.clipJson(clip.id) ?: return
        val base = Base(
            json.optDouble("scale", 1.0), json.optDouble("offsetX", 0.0), json.optDouble("offsetY", 0.0),
            json.optDouble("rotation", 0.0), json.optDouble("opacity", 1.0),
        )
        val batch = JSONArray()
        for (p in listOf("opacity", "scale", "offsetX", "offsetY", "rotation")) {
            batch.put(JSONObject().put("op", "clearClipKeys").put("clipId", clip.id).put("property", p))
        }

        fun lay(anim: Anim, from: Double, span: Double) {
            anim.keys(base).forEach { (property, at, value) ->
                batch.put(
                    JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", property)
                        .put("at", (from + at * span).coerceIn(0.0, 1.0)).put("value", value)
                        .put("ease", JSONArray(listOf(0.42, 0.0, 0.58, 1.0))),
                )
            }
        }
        val combo = comboAnim?.let { c -> COMBO.firstOrNull { it.name == c.name } }
        val loop = loopAnim?.let { c -> LOOP.firstOrNull { it.name == c.name } }
        if (loop != null) {
            // round after round for the clip's length, each as long as the speed says
            val rounds = (maxSeconds / loopAnim!!.seconds.coerceAtLeast(0.1f)).roundToInt().coerceIn(1, 60)
            for (i in 0 until rounds) lay(loop, i.toDouble() / rounds, 1.0 / rounds)
        } else if (combo != null) {
            lay(combo, 0.0, 1.0)
        } else {
            var spanIn = inAnim?.let { it.seconds / maxSeconds }?.toDouble() ?: 0.0
            var spanOut = outAnim?.let { it.seconds / maxSeconds }?.toDouble() ?: 0.0
            // an in and an out that would overlap share the clip
            if (spanIn + spanOut > 1.0) {
                val k = 1.0 / (spanIn + spanOut)
                spanIn *= k
                spanOut *= k
            }
            inAnim?.let { c -> (IN + WORD_INS).firstOrNull { it.name == c.name } }?.let { lay(it, 0.0, spanIn.coerceAtLeast(0.01)) }
            outAnim?.let { c -> OUT.firstOrNull { it.name == c.name } }?.let { lay(it, 1.0 - spanOut, spanOut.coerceAtLeast(0.01)) }
        }

        // word by word: the package on the title's chain, over the In's length
        val words = inAnim?.name?.takeIf { it in WORD_NAMES }
        val chain = json.optJSONArray("videoEffects") ?: JSONArray()
        val hadWords = (0 until chain.length()).any { chain.getJSONObject(it).optString("id") == WORDS }
        if (words != null || hadWords) {
            // first on the chain, before the title's glow, shadow and curve
            val next = JSONArray()
            if (words != null) {
                val params = JSONObject().put("span", inAnim!!.seconds.toDouble()).put("karaoke", if (words == "Karaoke") 1 else 0)
                next.put(JSONObject().put("id", WORDS).put("params", params))
            }
            for (i in 0 until chain.length()) chain.getJSONObject(i).let { if (it.optString("id") != WORDS) next.put(it) }
            batch.put(JSONObject().put("op", "updateClip").put("clipId", clip.id).put("patch", JSONObject().put("videoEffects", next)))
        }

        val all = runCatching { vm.session?.extra(ANIMATIONS)?.takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull() ?: JSONObject()
        val mine = JSONObject()
        fun keep(part: String, c: Chosen?) = c?.let { mine.put(part, JSONObject().put("name", it.name).put("seconds", it.seconds.toDouble())) }
        keep("in", inAnim)
        keep("out", outAnim)
        keep("combo", comboAnim)
        keep("loop", loopAnim)
        if (mine.length() == 0) all.remove(clip.id) else all.put(clip.id, mine)
        batch.put(JSONObject().put("op", "setTimelineExtra").put("key", ANIMATIONS).put("value", if (all.length() == 0) JSONObject.NULL else all))
        vm.edit { applyBatch(batch.toString()) }
    }

    val current = when (tab) { 0 -> inAnim; 1 -> outAnim; else -> if (looping) loopAnim else comboAnim }

    fun choose(name: String?) {
        // the words take most of the clip, where an in takes half a second
        val seconds = if (name in WORD_NAMES && current?.name !in WORD_NAMES) (maxSeconds * 0.8f).coerceAtLeast(0.1f) else current?.seconds ?: when {
            tab == 2 && looping -> min(1f, maxSeconds)
            tab == 2 -> maxSeconds
            else -> min(0.5f, maxSeconds)
        }
        val next = name?.let { Chosen(it, seconds) }
        when (tab) {
            0 -> { inAnim = next; if (next != null) { comboAnim = null; loopAnim = null } }
            1 -> { outAnim = next; if (next != null) { comboAnim = null; loopAnim = null } }
            else -> {
                if (looping) loopAnim = next else comboAnim = next
                if (next != null) { inAnim = null; outAnim = null }
            }
        }
        apply()
    }

    // in the text panel's tab the bar is drawn here, with its own search and bookmark
    var ownQuery by remember { mutableStateOf<String?>(null) }
    var ownKept by remember { mutableStateOf(false) }
    val query = if (fixedTab == null) ownQuery.orEmpty() else query
    val kept = if (fixedTab == null) ownKept else kept
    Column(Modifier.fillMaxSize()) {
        if (fixedTab == null) {
            AnimHeader(if (looping) "Loop" else "Combo", own, ownQuery, ownKept, onTab = { own = it; ownKept = false }, onQuery = { ownQuery = it }, onKept = { ownKept = true })
        }
        // word by word only for a title: a sticker loops like one but has no words
        val shelf = when (tab) { 0 -> if (clip.kind == "text") IN + WORD_INS else IN; 1 -> OUT; else -> if (looping) LOOP else COMBO }
        val categories = remember(tab) { listOf("All") + shelf.map { it.category }.distinct() }
        var category by remember(tab) { mutableIntStateOf(0) }
        // the clip's own panel sorts them into kinds; a title's tab shows them all, as the reference's
        if (query.isBlank() && !kept && fixedTab != null) Tabs(categories, category) { category = it }
        // a still of the clip for the tiles, copied once: the preview's own buffer is drawn over
        val picture = remember(clip.id) {
            if (clip.kind == "text") SAMPLE_WORDS
            else runCatching { vm.lastFrame?.copy(android.graphics.Bitmap.Config.ARGB_8888, false)?.asImageBitmap() }.getOrNull()
        }
        // searched across the shelf, or the kept ones of it, or a category of it
        fun keyOf(a: Anim) = "$tab:${a.name}"
        val list = when {
            query.isNotBlank() -> shelf.filter { it.name.contains(query.trim(), ignoreCase = true) }
            kept -> shelf.filter { keyOf(it) in keptSet }
            category == 0 -> shelf
            else -> shelf.filter { it.category == categories[category] }
        }
        val none = if (query.isBlank() && !kept) 1 else 0
        if (kept && list.isEmpty()) {
            Text("Press and hold one to keep it here", style = Type.Small, modifier = Modifier.padding(24.dp))
        }
        ChoiceGrid(list.size + none, Modifier.weight(1f)) { i ->
            if (i < none) {
                GridTile("None", current == null, onClick = { choose(null) }) {
                    Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 26.dp)
                }
            } else {
                val a = list[i - none]
                GridTile(
                    a.name,
                    current?.name == a.name,
                    onClick = { choose(a.name) },
                    onLongClick = { vm.message = if (Favorites.toggle(context, "animation", keyOf(a))) "Added to favorites" else "Removed from favorites" },
                ) {
                    AnimGlyph(a, playing = true, picture = picture, modifier = Modifier.fillMaxSize())
                    if (keyOf(a) in keptSet) {
                        Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                            Glyph(LucideIcons.Bookmark, Color.White, size = 11.dp)
                        }
                    }
                }
            }
        }
        if (current != null) {
            var length by remember(clip.id, tab) { mutableFloatStateOf(current.seconds.coerceIn(0.1f, maxSeconds)) }
            // a loop's pace is one round's length, from quick to slow
            val loopTab = tab == 2 && looping
            val range = if (loopTab) 0.2f..maxOf(0.3f, minOf(3f, maxSeconds)) else 0.1f..maxSeconds
            ValueSlider(length.coerceIn(range), range, { "%.1fs".format(it) }, { length = it }, {
                val next = current.copy(seconds = length)
                when {
                    tab == 0 -> inAnim = next
                    tab == 1 -> outAnim = next
                    loopTab -> loopAnim = next
                    else -> comboAnim = next
                }
                apply()
            }, title = if (loopTab) "Speed" else "Duration")
        }
    }
}

/**
 * A tile's picture of a preset: the clip's own frame filling the tile and doing what the preset
 * does, looping with a rest at the end.
 */
@Composable
private fun AnimGlyph(
    anim: Anim,
    playing: Boolean,
    size: androidx.compose.ui.unit.Dp = 34.dp,
    picture: androidx.compose.ui.graphics.ImageBitmap? = null,
    modifier: Modifier? = null,
) {
    val t = if (playing) {
        rememberInfiniteTransition(label = "anim")
            .animateFloat(
                0f,
                1f,
                infiniteRepeatable(
                    androidx.compose.animation.core.keyframes {
                        durationMillis = 1600
                        0f at 0
                        1f at 1100
                        1f at 1600
                    },
                    RepeatMode.Restart,
                ),
                label = "t",
            )
            .value
    } else {
        0.45f
    }
    val keys = remember(anim) { anim.keys(Base(1.0, 0.0, 0.0, 0.0, 1.0)) }

    fun at(property: String, fallback: Double): Double {
        val run = keys.filter { it.first == property }.sortedBy { it.second }
        if (run.isEmpty()) return fallback
        if (t <= run.first().second) return run.first().third
        if (t >= run.last().second) return run.last().third
        val i = run.indexOfFirst { it.second >= t }
        val a = run[i - 1]
        val b = run[i]
        val f = (t - a.second) / (b.second - a.second).coerceAtLeast(1e-6)
        return a.third + (b.third - a.third) * f
    }
    androidx.compose.foundation.Canvas(modifier ?: Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val full = picture != null
        val s = at("scale", 1.0).toFloat()
        val x = at("offsetX", 0.0).toFloat() * w * (if (full) 1f else 0.5f)
        val y = at("offsetY", 0.0).toFloat() * (if (full) h else w * 0.5f)
        val r = at("rotation", 0.0).toFloat()
        val o = at("opacity", 1.0).toFloat().coerceIn(0f, 1f)
        if (!full) drawRect(Color(0x33FFFFFF), style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        clipRect {
            withTransform({
                translate(x, y)
                rotate(r)
                scale(s * (if (full) 1f else 0.6f), s * (if (full) 1f else 0.6f))
            }) {
                if (picture != null) {
                    // the clip's own frame filling the tile, its longer side cropped
                    val k = maxOf(w / picture.width, h / picture.height)
                    val sw = (w / k).toInt().coerceIn(1, picture.width)
                    val sh = (h / k).toInt().coerceIn(1, picture.height)
                    drawImage(
                        picture,
                        srcOffset = androidx.compose.ui.unit.IntOffset((picture.width - sw) / 2, (picture.height - sh) / 2),
                        srcSize = androidx.compose.ui.unit.IntSize(sw, sh),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt()),
                        alpha = o,
                    )
                } else {
                    drawRoundRect(Palette.Text.copy(alpha = o), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                }
            }
        }
    }
}

// ── Background ──

/**
 * The canvas background: a colour, a picture or the clip itself blurred, behind what does not fill
 * the frame. It is the main-track clip's own (the selected one, else the one under the playhead), as
 * the reference keeps it; Apply to all gives every clip the same.
 */
@Composable
fun BackgroundPanel(vm: EditorViewModel, tab: Int) {
    var current by remember { mutableStateOf<JSONObject?>(null) }
    val s = vm.session
    val main = vm.timeline?.rows?.firstOrNull { it.kind == io.github.artcraftandroid.capcraft.core.RowKind.MAIN }
    val target = vm.selectedClip?.takeIf { c -> main?.clips?.any { it.id == c.id } == true }
        ?: main?.clips?.firstOrNull { vm.playhead >= it.startUs && vm.playhead < it.startUs + it.durationUs }
        ?: main?.clips?.lastOrNull()
    val targetId = target?.id
    androidx.compose.runtime.LaunchedEffect(vm.timeline?.revision, targetId) {
        current = s?.let { session ->
            kotlinx.coroutines.withContext(io.github.artcraftandroid.capcraft.engine.Threads.commands) {
                runCatching {
                    (if (targetId != null) session.clipBackground(targetId) else session.background()).takeIf { it.isNotBlank() }?.let { JSONObject(it) }
                }.getOrNull()
            }
        }
    }
    // on the clip, none being a choice of its own; the whole project when there is no clip yet
    fun set(json: JSONObject?) = vm.edit {
        if (targetId != null) setClipBackground(targetId, (json ?: JSONObject().put("kind", "none")).toString()) else setBackground(json?.toString() ?: "")
    }
    fun applyToAll() {
        val chosen = current
        vm.edit { setBackground(chosen?.toString() ?: "") }
        vm.message = "Applied to all"
    }
    val kind = current?.optString("kind")
    // the clip's own frame for the blur tiles, softened more at each level
    val blurs = remember {
        val frame = vm.lastFrame?.let { runCatching { it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        (1..4).map { level ->
            frame?.let { f ->
                // shrunk to a few pixels across and stretched back: the softer, the fewer
                val across = (40 / level).coerceAtLeast(4)
                val down = (across * f.height / f.width.coerceAtLeast(1)).coerceAtLeast(2)
                val small = android.graphics.Bitmap.createScaledBitmap(f, across, down, true)
                android.graphics.Bitmap.createScaledBitmap(small, f.width / 4, f.height / 4, true).asImageBitmap()
            }
        }
    }
    PanelFrame(listOf("Color", "Image", "Blur").getOrElse(tab) { "Background" }, vm, height = 170.dp, left = {
        Row(Modifier.clickable(onClick = ::applyToAll).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(LucideIcons.Layers, Palette.DockText, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Apply to all", style = Type.Body.copy(color = Palette.DockText))
        }
    }) {
        Row(
            Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (tab) {
                0 -> {
                    // the eyedropper takes a colour from the preview
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF3A3A3A)).clickable {
                            vm.pickColor = { rgb -> set(JSONObject().put("kind", "color").put("color", "#%06x".format(rgb and 0xFFFFFF))) }
                            vm.message = "Tap the preview to pick a colour"
                        },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Pipette, Palette.DockText, size = 18.dp) }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF3A3A3A)).clickable { set(null) },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.CircleSlash, Palette.DockText, size = 18.dp) }
                    Box(Modifier.padding(horizontal = 12.dp).size(1.dp, 24.dp).background(Color(0xFF4A4A4A)))
                    SWATCHES.forEach { hex ->
                        val on = kind == "color" && current?.optString("color").equals(hex, true)
                        Box(
                            Modifier.padding(end = 10.dp).size(38.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(hex)))
                                .then(if (on) Modifier.border(3.dp, Palette.Accent, CircleShape) else Modifier)
                                .clickable { set(JSONObject().put("kind", "color").put("color", hex)) },
                        )
                    }
                }
                1 -> {
                    BgTile(selected = current == null, onClick = { set(null) }) { Glyph(LucideIcons.CircleSlash, Palette.DockText, size = 22.dp) }
                    BgTile(selected = kind == "image", onClick = {
                        io.github.artcraftandroid.capcraft.ui.common.MediaPick.open(io.github.artcraftandroid.capcraft.ui.common.MediaKinds.IMAGE, multiple = false) {
                            vm.setBackgroundImage(it.first(), targetId)
                        }
                    }) { Glyph(LucideIcons.Plus, Palette.DockText, size = 24.dp) }
                }
                else -> {
                    BgTile(selected = current == null, onClick = { set(null) }) { Glyph(LucideIcons.CircleSlash, Palette.DockText, size = 22.dp) }
                    (1..4).forEach { level ->
                        BgTile(selected = kind == "blur" && current?.optInt("level") == level, onClick = {
                            set(JSONObject().put("kind", "blur").put("level", level))
                        }) {
                            val pic = blurs[level - 1]
                            if (pic != null) {
                                androidx.compose.foundation.Image(pic, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Glyph(LucideIcons.Droplet, Palette.DockText, size = 22.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A background choice: a square, ringed when chosen. */
@Composable
private fun BgTile(selected: Boolean, onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.padding(end = 14.dp).size(width = 58.dp, height = 58.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A3A))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

// ── Voiceover ──

/**
 * Recording a voice-over at the playhead while the timeline plays silently, laid out as the
 * reference's Record sheet: the teleprompter at the left, the microphone in the middle, and voice
 * enhancement and a voice changer at the right, both put on the recording when it stops.
 */
@Composable
fun VoiceoverPanel(vm: EditorViewModel) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var file by remember { mutableStateOf<File?>(null) }
    var startedAt by remember { mutableLongStateOf(0L) }
    var enhance by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf<PackageInfo?>(null) }
    var choosingVoice by remember { mutableStateOf(false) }
    var writing by remember { mutableStateOf(false) }
    val voices = rememberPackages("audio").filter { it.id !in CLEANER_IDS }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }

    fun stop() {
        val r = recorder ?: return
        recorder = null
        vm.recording = false
        runCatching { r.stop() }
        r.release()
        vm.stop()
        vm.setMuted(false)
        val made = file ?: return
        file = null
        val chain = JSONArray()
        if (enhance) chain.put(JSONObject().put("id", "concat.enhance-voice").put("params", JSONObject()))
        voice?.let { chain.put(JSONObject().put("id", it.id).put("params", JSONObject())) }
        vm.addRecording(made, startedAt) { id -> if (chain.length() > 0) vm.patch(id, JSONObject().put("filters", chain)) }
    }

    fun start() {
        if (!granted) { ask.launch(Manifest.permission.RECORD_AUDIO); return }
        val out = Media.uniqueFile(File(vm.path, "media"), "voiceover.m4a")
        val r = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48000)
            setAudioEncodingBitRate(128_000)
            setOutputFile(out.absolutePath)
        }
        runCatching { r.prepare(); r.start() }.onFailure { r.release(); vm.message = "The microphone is not available."; return }
        recorder = r
        file = out
        startedAt = vm.playhead
        vm.recording = true
        vm.setMuted(true)
        vm.start()
    }

    DisposableEffect(Unit) { onDispose { stop() } }

    PanelFrame("Record", vm, height = if (choosingVoice) 270.dp else 220.dp, titleOnTop = true) {
        if (choosingVoice) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<PackageInfo?>(null) + voices).forEach { p ->
                    val on = voice?.id == p?.id
                    Text(
                        p?.name ?: "None",
                        style = Type.Small.copy(color = if (on) Palette.Black else Palette.Text),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) Palette.Text else Color(0xFF3A3A3A))
                            .clickable { voice = p; choosingVoice = false }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // the microphone in the middle, the teleprompter alone at its left
            RecordTool(LucideIcons.ScrollText, "Teleprompter", on = vm.prompter.isNotBlank(), modifier = Modifier.weight(2f)) { writing = true }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(if (recorder != null) Palette.Danger else Palette.Accent)
                        .clickable { if (recorder != null) stop() else start() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (recorder != null) {
                        Box(Modifier.size(20.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
                    } else {
                        Glyph(LucideIcons.Mic, Color.White, size = 28.dp, description = "Record")
                    }
                }
            }
            RecordTool(LucideIcons.MicVocal, "Enhance voice", on = enhance, modifier = Modifier.weight(1f)) { enhance = !enhance }
            RecordTool(LucideIcons.AudioWaveform, voice?.name ?: "Voice changer", on = voice != null, modifier = Modifier.weight(1f)) { choosingVoice = !choosingVoice }
        }
        Text(
            if (recorder != null) "Recording ${clock(vm.playhead - startedAt)} · tap to stop" else "Tap to record",
            style = Type.Small.copy(color = Palette.TextSecondary),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp),
        )
    }
    if (writing) {
        var script by remember { mutableStateOf(vm.prompter) }
        io.github.artcraftandroid.capcraft.ui.common.ConfirmDialog(
            text = "Teleprompter",
            confirm = "Done",
            onDismiss = { writing = false },
            content = {
                androidx.compose.foundation.text.BasicTextField(
                    value = script,
                    onValueChange = { script = it },
                    textStyle = Type.Body,
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (script.isEmpty()) Text("Type what you will say; it scrolls over the picture while you record.", style = Type.Small)
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Card).padding(12.dp),
                )
            },
        ) {
            vm.prompter = script.trim()
            writing = false
        }
    }
}

/** A tool beside the record button: its glyph, lit when on, and its name. */
@Composable
private fun RecordTool(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(icon, if (on) Palette.Accent else Palette.DockText, size = 23.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = Type.DockLabel.copy(color = if (on) Palette.Accent else Palette.DockText, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            maxLines = 2,
        )
    }
}

// ── Auto captions ──

@Composable
fun CaptionsPanel(vm: EditorViewModel) {
    CaptionsPanelImpl(vm)
}

@Composable
private fun CaptionsPanelImpl(vm: EditorViewModel) {
    SpeechPanel(vm, captions = true, clipId = null)
}
