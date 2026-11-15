package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.PackageInfo
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.core.packages
import io.github.artcraftandroid.capcraft.core.still
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.toBitmap
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.ConfirmDialog
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Panel
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The engine's packages of [kind], loaded once. */
@Composable
fun rememberPackages(kind: String): List<PackageInfo> {
    val list by produceState(PackageCache.get(kind) ?: emptyList(), kind, PackageCache.version.intValue) {
        value = PackageCache.get(kind) ?: withContext(Threads.thumbnails) { packages(kind) }.also { PackageCache.put(kind, it) }
    }
    return list
}

private object PackageCache {
    private val map = HashMap<String, List<PackageInfo>>()
    /** Bumped when a kind's packages change (a look imported or removed), so its lists read them again. */
    val version = mutableIntStateOf(0)
    @Synchronized fun get(kind: String) = map[kind]
    @Synchronized fun put(kind: String, list: List<PackageInfo>) { map[kind] = list }
    fun changed(kind: String) {
        synchronized(this) { map.remove(kind) }
        version.intValue++
    }
}

/** A tile colour for a package, its own for each id. */
private fun tileBrush(id: String): Brush {
    val h = (id.hashCode() and 0xffffff)
    val hue = (h % 360).toFloat()
    return Brush.linearGradient(listOf(Color.hsv(hue, 0.45f, 0.55f), Color.hsv((hue + 40) % 360, 0.55f, 0.3f)))
}

/**
 * Filters, effects and voice effects. [target] says whether the choice goes on the selected clip's
 * own chain or on a layer over the timeline (a selected layer is re-pointed; otherwise one is added
 * at the playhead).
 */
@Composable
fun PackagesPanel(vm: EditorViewModel, kind: String, target: Panel.Target) {
    // filters and adjustments share one panel, as two tabs
    if (kind == "filter") {
        LookPanel(vm, startTab = 0, target = target)
        return
    }
    if (kind == "audio") {
        AudioEffectsPanel(vm)
        return
    }
    // effects are browsed as the reference does: a search field in the bar, categories under it
    var query by remember { mutableStateOf("") }
    PanelFrame("Effects", vm, tall = true, titleOnTop = true, header = { SearchField(query, { query = it }, "Search effects") }) {
        PackagesContent(vm, kind, target, query)
    }
}

/**
 * Presets, Filters, Adjust and Video quality, one panel with its tabs in the bar as the reference
 * has them. [startTab] is 0 for Filters, 1 for Adjust, 2 for Video quality.
 */
@Composable
fun LookPanel(vm: EditorViewModel, startTab: Int, target: Panel.Target) {
    var tab by remember { mutableIntStateOf(startTab + 1) }
    PanelFrame(
        LOOK_TABS[tab],
        vm,
        tall = true,
        titleOnTop = true,
        header = { Tabs(LOOK_TABS, tab) { tab = it } },
    ) {
        when (tab) {
            0 -> PresetsContent(vm)
            1 -> PackagesContent(vm, "filter", target, "")
            2 -> AdjustContent(vm)
            else -> QualityContent(vm)
        }
    }
}

private val LOOK_TABS = listOf("Presets", "Filters", "Adjust", "Video quality")

/** A shelf of the engine's packages of [kind], with its categories and a strength slider. */
@Composable
private fun ColumnScope.PackagesContent(vm: EditorViewModel, kind: String, target: Panel.Target, outerQuery: String) {
    // filters search from a round button at the head of their categories; effects from the bar
    var ownQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val query = if (kind == "filter") ownQuery else outerQuery
    // the colour-correction packages belong to Adjust, not to the filter shelf
    val all = rememberPackages(kind).filter {
        when (kind) {
            // the colour-correction packages belong to Adjust, not to the filter shelf
            "filter" -> it.id != "concat.adjust" && it.category != "Adjust"
            // keying and masking have tools of their own (Remove BG, Mask), and the colour and
            // detail corrections are Adjust's
            "effect" -> it.category !in setOf("Cutout", "Color", "Basic", "Text")
            // loudness, noise and voice cleaning are ticks in the Volume panel
            "audio" -> it.id !in CLEANER_IDS
            else -> true
        }
    }
    val categories = remember(all) { listOf("All") + all.map { it.category }.distinct() }
    var category by remember { mutableIntStateOf(0) }
    // the bookmark at the head of the shelf: what was kept by a long press
    val context = androidx.compose.ui.platform.LocalContext.current
    val keepable = kind == "effect" || kind == "filter"
    var keptTab by remember { mutableStateOf(false) }
    val kept = remember(Favorites.version.intValue, kind) { if (keepable) Favorites.of(context, kind) else emptySet() }
    fun keep(p: PackageInfo) {
        vm.message = if (Favorites.toggle(context, kind, p.id)) "Added to favorites" else "Removed from favorites"
    }
    val shown = when {
        query.isNotBlank() -> all.filter { it.name.contains(query.trim(), ignoreCase = true) }
        keptTab -> all.filter { it.id in kept }
        category == 0 -> all
        else -> all.filter { it.category == categories.getOrNull(category) }
    }
    val clip = vm.selectedClip
    val layerSelected = clip?.kind == "layer"
    val onClip = target == Panel.Target.Clip && clip != null && !layerSelected
    val ids = remember(all) { all.map { it.id }.toSet() }
    // what is applied now
    val current: String? = when {
        clip == null -> null
        layerSelected -> clip.effectId
        else -> {
            val json = vm.clipJson(clip.id)
            val chain = json?.optJSONArray(if (kind == "audio") "filters" else "videoEffects")
            (0 until (chain?.length() ?: 0)).map { chain!!.getJSONObject(it).optString("id") }.firstOrNull { it in ids }
        }
    }
    var intensity by remember(clip?.id) { mutableFloatStateOf(100f) }
    // a look of the user's own: a .cube table from the phone, then shown under Imported and put on
    var importedLook by remember { mutableStateOf<String?>(null) }
    val importLook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLook(uri) { id -> PackageCache.changed(kind); importedLook = id }
    }
    var removing by remember { mutableStateOf<PackageInfo?>(null) }
    // the chosen effect's own settings, opened by tapping its tile again
    var adjusting by remember(clip?.id) { mutableStateOf(false) }

    fun apply(p: PackageInfo?) {
        if (onClip && clip != null) {
            val key = if (kind == "audio") "filters" else "videoEffects"
            val json = vm.clipJson(clip.id)
            val chain = json?.optJSONArray(key) ?: JSONArray()
            val next = JSONArray()
            for (i in 0 until chain.length()) {
                val link = chain.getJSONObject(i)
                if (link.optString("id") !in ids) next.put(link)
            }
            if (p != null) next.put(JSONObject().put("id", p.id).put("params", JSONObject()))
            vm.patch(clip.id, JSONObject().put(key, next))
        } else if (layerSelected && clip != null) {
            if (p == null) {
                vm.edit { delete(listOf(clip.id)) }
                vm.select(null)
            } else {
                vm.patch(clip.id, JSONObject().put("name", p.name).put("videoEffects", JSONArray().put(JSONObject().put("id", p.id).put("params", JSONObject()))))
            }
        } else if (p != null) {
            vm.addLayer(p.id, p.name)
        }
    }

    LaunchedEffect(all, importedLook) {
        val look = all.firstOrNull { it.id == importedLook } ?: return@LaunchedEffect
        importedLook = null
        ownQuery = ""
        searching = false
        keptTab = false
        category = categories.indexOf(look.category).coerceAtLeast(0)
        apply(look)
    }
    removing?.let { look ->
        ConfirmDialog(
            "Remove “${look.name}”?",
            "Remove",
            destructive = true,
            onDismiss = { removing = null },
            content = {
                Text(
                    "Clips that use it go back to their own colours.",
                    style = Type.Small.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
        ) {
            removing = null
            if (current == look.id) apply(null)
            vm.removeLook(look.id) { PackageCache.changed(kind) }
        }
    }

    run {
        val chosen = all.firstOrNull { it.id == current }
        if (adjusting && onClip && clip != null && chosen != null && kind == "effect") {
            EffectAdjust(vm, clip.id, chosen) { adjusting = false }
            return@run
        }
        if (kind == "filter") {
            Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                if (searching) {
                    Box(Modifier.weight(1f)) { SearchField(ownQuery, { ownQuery = it }, "Search filters", autoFocus = true) }
                    Text(
                        "Cancel",
                        style = Type.Body.copy(color = Palette.DockText),
                        modifier = Modifier.clickable { ownQuery = ""; searching = false }.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    Box(
                        Modifier.padding(start = 12.dp).size(32.dp).clip(CircleShape).background(Color(0xFF3A3A3A)).clickable { searching = true },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Search, Palette.DockText, size = 16.dp, description = "Search filters") }
                    // a .cube table from the phone, made a filter of the user's own
                    Box(
                        Modifier.padding(start = 8.dp).size(32.dp).clip(CircleShape).background(Color(0xFF3A3A3A))
                            .clickable { importLook.launch(arrayOf("*/*")) },
                        contentAlignment = Alignment.Center,
                    ) { Glyph(LucideIcons.Upload, Palette.DockText, size = 16.dp, description = "Import a .cube look") }
                    KeptTab(keptTab) { keptTab = true }
                    Box(Modifier.weight(1f)) { Tabs(categories, if (keptTab) -1 else category) { category = it; keptTab = false } }
                }
            }
        } else if (keepable) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                KeptTab(keptTab) { keptTab = true }
                Box(Modifier.weight(1f)) {
                    Tabs(categories, if (query.isBlank() && !keptTab) category else -1) { category = it; keptTab = false }
                }
            }
        } else {
            Tabs(categories, if (query.isBlank()) category else -1) { category = it }
        }
        if (keptTab && query.isBlank() && shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Press and hold one to keep it here", style = Type.Small)
            }
            return@run
        }
        val none = if (query.isBlank() && !keptTab) 1 else 0
        ChoiceGrid(shown.size + none, Modifier.weight(1f)) { i ->
            if (i < none) {
                if (kind == "filter") {
                    FilterTile(null, current == null, onClick = { apply(null) }) {
                        Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 26.dp)
                    }
                } else {
                    GridTile("None", current == null, onClick = { apply(null) }) {
                        Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 26.dp)
                    }
                }
            } else if (kind == "filter") {
                val p = shown[i - none]
                val picture = rememberPreview(vm, p.id)
                val onLongClick = if (p.id.startsWith("user.")) ({ removing = p }) else ({ keep(p) })
                FilterTile(p.name, current == p.id, onClick = { apply(p) }, band = remember(picture) { picture?.let(::bandColour) }, onLongClick = onLongClick) {
                    PreviewPicture(picture, p.id)
                    if (p.id in kept) KeptMark()
                }
            } else {
                val p = shown[i - none]
                // a chosen effect with settings of its own offers Adjust on its tile
                val adjustable = kind == "effect" && onClip && current == p.id && adjustable(p).isNotEmpty()
                GridTile(
                    p.name,
                    current == p.id,
                    onClick = { if (adjustable) adjusting = true else apply(p) },
                    onLongClick = if (keepable) ({ keep(p) }) else null,
                ) {
                    if (kind == "audio") {
                        Box(Modifier.fillMaxSize().background(tileBrush(p.id)), contentAlignment = Alignment.Center) {
                            Glyph(LucideIcons.AudioWaveform, Color(0xCCFFFFFF), size = 26.dp)
                        }
                    } else {
                        PreviewTile(vm, p.id)
                    }
                    if (p.id in kept) KeptMark()
                    if (adjustable) {
                        Column(
                            Modifier.fillMaxSize().background(Color(0x99000000)),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Glyph(LucideIcons.SlidersHorizontal, Color.White, size = 20.dp)
                            Text("Adjust", style = Type.Small.copy(color = Color.White, fontSize = 11.sp))
                        }
                    }
                }
            }
        }
        // strength: a layer's opacity, or the package's intensity on a clip
        if (current != null && kind != "audio") {
            val p = all.firstOrNull { it.id == current }
            Spacer(Modifier.height(8.dp))
            ValueSlider(
                value = intensity,
                range = 0f..100f,
                label = { "${it.roundToInt()}" },
                onChange = { v ->
                    intensity = v
                    if (clip == null) return@ValueSlider
                    if (layerSelected) {
                        vm.patch(clip.id, JSONObject().put("opacity", v / 100.0), gesture = "strength")
                    } else if (p != null && p.intensity.isNotEmpty()) {
                        val param = p.params.firstOrNull { it.key == p.intensity } ?: return@ValueSlider
                        val value = param.min + (param.max - param.min) * v / 100.0
                        val json = vm.clipJson(clip.id) ?: return@ValueSlider
                        val chain = json.optJSONArray("videoEffects") ?: return@ValueSlider
                        for (i in 0 until chain.length()) {
                            val link = chain.getJSONObject(i)
                            if (link.optString("id") == p.id) {
                                val params = link.optJSONObject("params") ?: JSONObject()
                                params.put(p.intensity, value)
                                link.put("params", params)
                            }
                        }
                        vm.patch(clip.id, JSONObject().put("videoEffects", chain), gesture = "strength")
                    }
                },
                onDone = { vm.endGesture() },
            )
        }
        // the clip's filter, or none, on every picture of the main track, as the reference offers
        if (onClip && kind == "filter" && clip != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.clickable { filterToAll(vm, clip.id, ids) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(LucideIcons.Layers, Palette.DockText, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Apply to all", style = Type.Body.copy(color = Palette.DockText))
                }
            }
        }
    }
}

/** The bookmark at the head of a shelf: the kept ones, lit while shown. */
@Composable
private fun KeptTab(on: Boolean, onClick: () -> Unit) {
    Box(Modifier.padding(start = 10.dp).size(36.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Glyph(LucideIcons.Bookmark, if (on) Palette.Text else Palette.TextSecondary, size = 19.dp, description = "Favorites")
    }
}

/** A small bookmark at a kept tile's top right. */
@Composable
private fun BoxScope.KeptMark() {
    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Glyph(LucideIcons.Bookmark, Color.White, size = 11.dp)
    }
}

/** The settings of [p] a slider can set: numbers with a range, not colours. */
internal fun adjustable(p: PackageInfo) = p.params.filter { it.max > it.min && it.max < 1e6 }.take(4)

/** Sets knob [key] of the [pkg] link on [clipId]'s chain to [value]. */
internal fun setKnob(vm: EditorViewModel, clipId: String, pkg: String, key: String, value: Double, gesture: String) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: return
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        if (link.optString("id") == pkg) link.put("params", (link.optJSONObject("params") ?: JSONObject()).put(key, value))
    }
    vm.patch(clipId, JSONObject().put("videoEffects", chain), gesture)
}

/** A chosen effect's own settings on a clip, each on its slider, as the reference's Adjust shows them. */
@Composable
private fun ColumnScope.EffectAdjust(vm: EditorViewModel, clipId: String, p: PackageInfo, onBack: () -> Unit) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects")
    val params = (0 until (chain?.length() ?: 0)).map { chain!!.getJSONObject(it) }
        .firstOrNull { it.optString("id") == p.id }?.optJSONObject("params") ?: JSONObject()
    Row(Modifier.fillMaxWidth().height(46.dp).clickable(onClick = onBack).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(LucideIcons.ChevronLeft, Palette.DockText, size = 20.dp, description = "Back")
        Spacer(Modifier.width(6.dp))
        Text(p.name, style = Type.BodyStrong)
    }
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        adjustable(p).forEach { knob ->
            androidx.compose.runtime.key(knob.key) {
                // held while the finger is down, so the knob does not wait on the engine
                var held by remember { mutableStateOf<Float?>(null) }
                val step = knob.step.takeIf { it > 0 } ?: 0.0
                ValueSlider(
                    value = held ?: params.optDouble(knob.key, knob.default).toFloat(),
                    range = knob.min.toFloat()..knob.max.toFloat(),
                    label = { v -> if (step >= 1.0) "${v.roundToInt()}" else "%.1f".format(v) },
                    onChange = { v ->
                        val snapped = if (step > 0) (kotlin.math.round(v / step) * step) else v.toDouble()
                        held = snapped.toFloat()
                        setKnob(vm, clipId, p.id, knob.key, snapped, "effect-${knob.key}")
                    },
                    onDone = {
                        held = null
                        vm.endGesture()
                    },
                    title = knob.label,
                )
            }
        }
    }
}

/** The filter on [clipId] (one of [ids], or none) put on every other picture of the main track, in one step. */
private fun filterToAll(vm: EditorViewModel, clipId: String, ids: Set<String>) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val link = (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") in ids }
    val main = vm.timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN } ?: return
    val batch = JSONArray()
    main.clips.filter { it.id != clipId && (it.kind == "video" || it.kind == "image") }.forEach { other ->
        val theirs = vm.clipJson(other.id)?.optJSONArray("videoEffects") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until theirs.length()) theirs.getJSONObject(i).let { if (it.optString("id") !in ids) next.put(it) }
        if (link != null) next.put(JSONObject(link.toString()))
        batch.put(JSONObject().put("op", "updateClip").put("clipId", other.id).put("patch", JSONObject().put("videoEffects", next)))
    }
    if (batch.length() > 0) {
        vm.edit { applyBatch(batch.toString()) }
        vm.message = "Applied to all"
    }
}

// ── Adjust ──

/**
 * One knob of the Adjust tab: the key it sets, on the engine's Adjust or on [PLUS], its range and
 * where it rests. HSL and Graphs are sheets of their own rather than one slider.
 */
private data class Knob(val key: String, val label: String, val min: Float, val max: Float, val default: Float, val pkg: String = ADJUST)

private const val ADJUST = "concat.adjust"

/** Brilliance, clarity, grain and the bands of HSL: CapCraft's package beside the engine's Adjust. */
private const val PLUS = "capcraft.adjust-plus"

/** The knobs that open a sheet rather than a slider. */
private const val HSL = "hsl"
private const val GRAPHS = "graphs"
private const val WHEELS = "wheels"

/** The knobs in the reference's order. Its Hue is the green-to-magenta tint the engine calls tint. */
private val KNOBS = listOf(
    Knob("brightness", "Brightness", -100f, 100f, 0f),
    Knob("contrast", "Contrast", -100f, 100f, 0f),
    Knob("saturation", "Saturation", -100f, 100f, 0f),
    Knob("brilliance", "Brilliance", -100f, 100f, 0f, PLUS),
    Knob("sharpen", "Sharpen", 0f, 100f, 0f),
    Knob("clarity", "Clarity", -100f, 100f, 0f, PLUS),
    Knob(HSL, "HSL", 0f, 0f, 0f, PLUS),
    Knob(GRAPHS, "Graphs", 0f, 0f, 0f),
    Knob(WHEELS, "Color wheel", 0f, 0f, 0f),
    Knob("highlights", "Highlights", -100f, 100f, 0f),
    Knob("shadows", "Shadows", -100f, 100f, 0f),
    Knob("whites", "Whites", -100f, 100f, 0f),
    Knob("blacks", "Blacks", -100f, 100f, 0f),
    Knob("temperature", "Temp", 2000f, 11000f, 6500f),
    Knob("tint", "Hue", -100f, 100f, 0f),
    Knob("fade", "Fade", 0f, 100f, 0f),
    Knob("vignette", "Vignette", 0f, 100f, 0f),
    Knob("grain", "Grain", 0f, 100f, 0f, PLUS),
)

/** A smart tool before the knobs: worked out from the picture under the playhead, then set on them. */
private class SmartTool(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val colourOnly: Boolean)

private val SMART = listOf(
    SmartTool("Auto adjust", LucideIcons.WandSparkles, colourOnly = false),
    SmartTool("Color correction", LucideIcons.Leaf, colourOnly = true),
)

/** What Color correction keeps of the worked-out adjustments: the colour, not the light. */
private val COLOUR_KEYS = setOf("temperature", "tint", "saturation")

/** Where a preset keeps the [PLUS] knobs, beside the engine's Adjust ones. */
private const val PRESET_PLUS = "_plus"

/** Picture adjustments: on the selected picture's own chain, or as an adjustment layer. */
@Composable
fun AdjustPanel(vm: EditorViewModel) = LookPanel(vm, startTab = 1, target = Panel.Target.Clip)

/**
 * The effects and filters kept by a long press, for the bookmark tab at the head of their shelf,
 * on this phone. The reference keeps them in an account; there are no accounts here.
 */
object Favorites {
    private const val FILE = "capcraft-favorites"

    /** Bumped on every change, so the shelves showing them redraw. */
    val version = androidx.compose.runtime.mutableIntStateOf(0)

    fun of(context: android.content.Context, kind: String): Set<String> =
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getStringSet(kind, emptySet()).orEmpty()

    /** Keeps [id] among [kind]'s favourites, or lets it go; true when it is kept. */
    fun toggle(context: android.content.Context, kind: String, id: String): Boolean {
        val now = of(context, kind).toMutableSet()
        val kept = now.add(id) || run { now.remove(id); false }
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putStringSet(kind, now).apply()
        version.intValue++
        return kept
    }
}

/** Adjustments the user kept, by name, on this phone. */
object AdjustPresets {
    private const val FILE = "capcraft-presets"
    private const val KEY = "adjust"

    fun all(context: android.content.Context): List<Pair<String, JSONObject>> {
        val raw = context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            o.optString("name") to (o.optJSONObject("params") ?: JSONObject())
        }
    }

    private fun write(context: android.content.Context, items: List<Pair<String, JSONObject>>) {
        val list = JSONArray()
        items.forEach { (name, params) -> list.put(JSONObject().put("name", name).put("params", params)) }
        context.getSharedPreferences(FILE, android.content.Context.MODE_PRIVATE).edit().putString(KEY, list.toString()).apply()
    }

    /** Keeps [params] as a new preset and returns the name it was given. */
    fun save(context: android.content.Context, params: JSONObject): String {
        val items = all(context)
        val used = items.map { it.first }.toSet()
        val name = (1..999).map { "Preset $it" }.first { it !in used }
        write(context, listOf(name to params) + items)
        return name
    }

    fun remove(context: android.content.Context, name: String) = write(context, all(context).filter { it.first != name })
}

/** The params of the [pkg] link on [clipId]'s chain, or empty. */
private fun linkParams(vm: EditorViewModel, clipId: String?, pkg: String): JSONObject =
    clipId?.let { vm.clipJson(it) }?.optJSONArray("videoEffects")?.let { chain ->
        (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == pkg }?.optJSONObject("params")
    } ?: JSONObject()

/**
 * [chain] with the Adjust links set to [adjust] and [plus] (none where empty), the engine's first
 * and CapCraft's straight after, the chain's other links as they were. With [layer] the engine's
 * link stays even with nothing set: an adjustment layer is known by it, and one left with only
 * CapCraft's was no longer taken for one, so the next move started another layer.
 */
private fun withAdjust(chain: JSONArray, adjust: JSONObject, plus: JSONObject, layer: Boolean = false): JSONArray {
    val next = JSONArray()
    var placed = false
    fun place() {
        if (adjust.length() > 0 || layer) next.put(JSONObject().put("id", ADJUST).put("params", adjust))
        if (plus.length() > 0) next.put(JSONObject().put("id", PLUS).put("params", plus))
        placed = true
    }
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        when (link.optString("id")) {
            ADJUST, PLUS -> if (!placed) place()
            else -> next.put(link)
        }
    }
    if (!placed) place()
    return next
}

/**
 * Whether Adjust works on [clip]: a video, a picture, or an adjustment layer - one made with the
 * engine's Adjust, or one an earlier build left with only CapCraft's.
 */
private fun takesAdjust(clip: ClipView?): Boolean =
    clip != null && (clip.kind == "video" || clip.kind == "image" || (clip.kind == "layer" && (clip.effectId == ADJUST || clip.effectId == PLUS)))

/** The Presets tab: the adjustments kept with Save preset, put on the selected clip by a tap. */
@Composable
private fun ColumnScope.PresetsContent(vm: EditorViewModel) {
    val context = vm.getApplication<android.app.Application>()
    var items by remember { mutableStateOf(AdjustPresets.all(context)) }
    val clip = vm.selectedClip
    val visual = takesAdjust(clip)
    fun apply(params: JSONObject) {
        val c = clip?.takeIf { visual }
        if (c == null) {
            vm.message = "Choose a clip to put the preset on"
            return
        }
        val adjust = JSONObject(params.toString()).apply { remove(PRESET_PLUS) }
        val plus = params.optJSONObject(PRESET_PLUS)?.let { JSONObject(it.toString()) } ?: JSONObject()
        val chain = vm.clipJson(c.id)?.optJSONArray("videoEffects") ?: JSONArray()
        vm.patch(c.id, JSONObject().put("videoEffects", withAdjust(chain, adjust, plus, layer = c.kind == "layer")))
    }
    if (items.isEmpty()) {
        Text(
            "Adjust a clip, then tap Save preset in Adjust to keep the look here.",
            style = Type.Small.copy(color = Palette.TextSecondary),
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(items.size) { i ->
            val (name, params) = items[i]
            val keys = params.keys().asSequence().filter { it != PRESET_PLUS } +
                (params.optJSONObject(PRESET_PLUS)?.keys()?.asSequence() ?: emptySequence())
            Row(
                Modifier.fillMaxWidth().clickable { apply(params) }.padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.SlidersHorizontal, Palette.DockText, size = 18.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(name, style = Type.Body.copy(color = Palette.Text))
                    Text(
                        keys.map { key -> KNOBS.firstOrNull { it.key == key }?.label ?: if (key in HSL_KEYS) "HSL" else if ("." in key) "Graphs" else key }
                            .distinct().joinToString(" · "),
                        style = Type.Small.copy(fontSize = 11.sp, color = Palette.TextSecondary),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(40.dp).clickable { AdjustPresets.remove(context, name); items = AdjustPresets.all(context) },
                    contentAlignment = Alignment.Center,
                ) { Glyph(LucideIcons.Trash2, Palette.TextSecondary, size = 18.dp, description = "Delete $name") }
            }
        }
    }
}

/**
 * The Adjust tab: Smart and Customize over one row of round buttons - the smart tools, then a knob
 * each in the reference's order - the chosen one's slider under them (HSL, Graphs and Color wheel
 * open sheets instead), and Reset, Apply to all and Save preset below.
 */
@Composable
private fun ColumnScope.AdjustContent(vm: EditorViewModel) {
    // a knob by its place in KNOBS; a smart tool as -1 - its place in SMART
    var which by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val clip = vm.selectedClip
    val visual = takesAdjust(clip)
    val target = if (visual) clip else null
    val adjust = remember(target?.json) { linkParams(vm, target?.id, ADJUST) }
    val plus = remember(target?.json) { linkParams(vm, target?.id, PLUS) }
    val knob = KNOBS[which.coerceAtLeast(0)]
    val smart = SMART.getOrNull(-1 - which)
    val scope = rememberCoroutineScope()
    val row = androidx.compose.foundation.lazy.rememberLazyListState()
    // which of Smart and Customize is lit: the one last tapped, or the chosen tool's
    var section by remember { mutableIntStateOf(1) }
    // what a smart tool worked out from the picture, at full strength, under the clip and tool it was
    // for (the clip's view is new after every edit), and how much of it is on
    var found by remember { mutableStateOf<Pair<String, JSONObject>?>(null) }
    var strength by remember { mutableFloatStateOf(100f) }
    var reading by remember { mutableStateOf(false) }
    fun paramsOf(k: Knob) = if (k.pkg == PLUS) plus else adjust
    var value by remember(target?.id, which) { mutableFloatStateOf(paramsOf(knob).optDouble(knob.key, knob.default.toDouble()).toFloat()) }
    // an adjustment layer asked for and not yet selected: a drag's next moves come before it is there
    var layerAsked by remember(target?.id) { mutableStateOf(false) }

    /** Sets knobs of [pkg] (null clears one) on the selected picture, or starts an adjustment layer when there is none. */
    fun setKeys(pkg: String, values: Map<String, Double?>, gesture: String?) {
        val c = target
        if (c == null) {
            // nothing to adjust: one adjustment layer at the playhead, which the next move edits
            if (!layerAsked) {
                layerAsked = true
                vm.addLayer(ADJUST, "Adjust")
            }
            return
        }
        val chain = vm.clipJson(c.id)?.optJSONArray("videoEffects") ?: JSONArray()
        val a = linkParams(vm, c.id, ADJUST)
        val b = linkParams(vm, c.id, PLUS)
        val into = if (pkg == PLUS) b else a
        values.forEach { (key, v) -> if (v == null) into.remove(key) else into.put(key, v) }
        vm.patch(c.id, JSONObject().put("videoEffects", withAdjust(chain, a, b, layer = c.kind == "layer")), gesture)
    }

    fun reset() {
        val c = target ?: return
        val chain = vm.clipJson(c.id)?.optJSONArray("videoEffects") ?: return
        vm.patch(c.id, JSONObject().put("videoEffects", withAdjust(chain, JSONObject(), JSONObject(), layer = c.kind == "layer")))
        value = knob.default
    }

    /** [base] put on the knobs at [percent], each step from neutral scaled, the temperature from 6500. */
    fun putFound(base: JSONObject, percent: Float, gesture: String?) {
        val values = base.keys().asSequence().associateWith { key ->
            val v = base.optDouble(key)
            if (key == "temperature") 6500 + (v - 6500) * percent / 100 else v * percent / 100
        }
        setKeys(ADJUST, values, gesture)
    }

    /** Reads the picture under the playhead and puts what [tool] makes of it on the knobs. */
    fun runSmart(tool: SmartTool) {
        val c = target?.takeIf { it.kind == "video" || it.kind == "image" } ?: run {
            vm.message = "Choose a video or photo first"
            return
        }
        val key = "${c.id}:${-1 - SMART.indexOf(tool)}"
        reading = true
        scope.launch {
            val speed = c.speed.takeIf { it > 0 } ?: 1.0
            val at = if (c.kind == "video") {
                (c.sourceStartUs + ((vm.playhead - c.startUs).coerceIn(0, c.durationUs) * speed).toLong())
                    .coerceIn(c.sourceStartUs, maxOf(c.sourceStartUs, c.mediaDurationUs - 50_000))
            } else {
                0L
            }
            val all = withContext(Threads.thumbnails) {
                runCatching { still(c.path, at, 160u).toBitmap()?.let(::autoAdjustments) }.getOrNull()
            }
            reading = false
            if (all == null) {
                vm.message = "Could not read the picture"
                return@launch
            }
            val base = JSONObject()
            all.keys().forEach { key -> if (!tool.colourOnly || key in COLOUR_KEYS) base.put(key, all.get(key)) }
            if (base.length() == 0) {
                vm.message = "The picture already looks balanced"
                return@launch
            }
            found = key to base
            strength = 100f
            putFound(base, 100f, null)
        }
    }

    /** The selected clip's adjustments kept as a preset, to put on other clips later. */
    fun savePreset() {
        if (target == null || adjust.length() + plus.length() == 0) {
            vm.message = "Adjust something first"
            return
        }
        val kept = JSONObject(adjust.toString())
        if (plus.length() > 0) kept.put(PRESET_PLUS, JSONObject(plus.toString()))
        val saved = AdjustPresets.save(vm.getApplication(), kept)
        vm.message = "Saved as $saved"
    }

    /** The selected clip's adjustments, on every picture of the main track too. */
    fun applyToAll() {
        val c = target ?: return
        val a = linkParams(vm, c.id, ADJUST)
        val b = linkParams(vm, c.id, PLUS)
        val main = vm.timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN } ?: return
        val batch = JSONArray()
        main.clips.filter { it.id != c.id && (it.kind == "video" || it.kind == "image") }.forEach { other ->
            val chain = vm.clipJson(other.id)?.optJSONArray("videoEffects") ?: JSONArray()
            val next = withAdjust(chain, JSONObject(a.toString()), JSONObject(b.toString()))
            batch.put(JSONObject().put("op", "updateClip").put("clipId", other.id).put("patch", JSONObject().put("videoEffects", next)))
        }
        if (batch.length() > 0) {
            vm.edit { applyBatch(batch.toString()) }
            vm.message = "Applied to all"
        }
    }

    run {
        // an open sheet takes the place of the tools, so a phone with less height still shows all of it;
        // its fold-away button brings them back
        if (sheet == null) {
            // Smart and Customize: where each part of the row starts, the lit one where the row was sent
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 2.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                listOf("Smart", "Customize").forEachIndexed { i, name ->
                    Text(
                        name,
                        style = Type.Body.copy(
                            fontSize = 15.sp,
                            color = if (section == i) Palette.Text else Palette.TextSecondary,
                            fontWeight = if (section == i) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.clickable {
                            section = i
                            scope.launch { row.animateScrollToItem(if (i == 0) 0 else SMART.size + 1) }
                        }.padding(vertical = 6.dp),
                    )
                }
            }
            LazyRow(Modifier.fillMaxWidth(), state = row, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp), verticalAlignment = Alignment.Top) {
                items(SMART.size) { i ->
                    val tool = SMART[i]
                    AdjustTool(tool.label, which == -1 - i, changed = false, onClick = {
                        which = -1 - i
                        section = 0
                        sheet = null
                        runSmart(tool)
                    }) { tint -> Glyph(tool.icon, tint, size = 22.dp) }
                }
                item {
                    Box(Modifier.padding(top = 18.dp, start = 4.dp, end = 4.dp).size(1.dp, 26.dp).background(Color(0xFF474747)))
                }
                items(KNOBS.size) { i ->
                    val k = KNOBS[i]
                    val changed = when (k.key) {
                        HSL -> HSL_KEYS.any { plus.optDouble(it, 0.0) != 0.0 }
                        GRAPHS -> CURVE_KEYS.any { adjust.has(it) }
                        WHEELS -> WHEEL_KEYS.any { adjust.optDouble(it, 0.0) != 0.0 }
                        else -> paramsOf(k).has(k.key) && paramsOf(k).optDouble(k.key) != k.default.toDouble()
                    }
                    AdjustTool(k.label, i == which, changed, onClick = {
                        which = i
                        section = 1
                        sheet = k.key.takeIf { it == HSL || it == GRAPHS || it == WHEELS }
                    }) { tint ->
                        if (k.key == HSL) {
                            Text("HSL", style = Type.Small.copy(fontSize = 13.sp, color = tint))
                        } else {
                            Glyph(knobIcon(k.key), tint, size = 22.dp)
                        }
                    }
                }
            }
        }
        when (sheet) {
            HSL -> {
                HslSheet(plus, { values, gesture -> setKeys(PLUS, values, gesture) }, { vm.endGesture() }) { sheet = null }
                return@run
            }
            GRAPHS -> {
                GraphsSheet(adjust, { values, gesture -> setKeys(ADJUST, values, gesture) }, { vm.endGesture() }) { sheet = null }
                return@run
            }
            WHEELS -> {
                WheelsSheet(adjust, { values, gesture -> setKeys(ADJUST, values, gesture) }, { vm.endGesture() }) { sheet = null }
                return@run
            }
        }
        Spacer(Modifier.height(18.dp))
        val narrow = Modifier.padding(horizontal = 56.dp)
        if (smart != null) {
            // how much of what the smart tool worked out is on
            val base = found?.takeIf { it.first == "${target?.id}:$which" }?.second
            when {
                reading -> Text("Reading the picture…", style = Type.Small, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp))
                base != null -> ValueSlider(
                    value = strength,
                    range = 0f..100f,
                    label = { v -> "${v.roundToInt()}" },
                    onChange = { v ->
                        strength = v
                        putFound(base, v, "adjust-smart")
                    },
                    onDone = { vm.endGesture() },
                    modifier = narrow,
                    filled = true,
                )
            }
        } else if (knob.key != HSL && knob.key != GRAPHS && knob.key != WHEELS) {
            ValueSlider(
                value = value,
                range = knob.min..knob.max,
                label = { v -> "${v.roundToInt()}" },
                onChange = { v ->
                    value = if (abs(v - knob.default) < (knob.max - knob.min) * 0.02f) knob.default else v
                    setKeys(knob.pkg, mapOf(knob.key to value.toDouble()), "adjust-${knob.key}")
                },
                onDone = { vm.endGesture() },
                modifier = narrow,
                filled = true,
            )
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.clickable(onClick = ::reset).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.RotateCcw, Palette.DockText, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Reset", style = Type.Body.copy(color = Palette.DockText))
            }
            Spacer(Modifier.width(12.dp))
            Row(Modifier.clickable(onClick = ::applyToAll).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.Layers, Palette.DockText, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Apply to all", style = Type.Body.copy(color = Palette.DockText))
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.clickable(onClick = ::savePreset).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.Bookmark, Palette.DockText, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Save preset", style = Type.Body.copy(color = Palette.DockText))
            }
        }
    }
}

/**
 * One of Adjust's round buttons: an outlined circle and its name under it, both cyan when chosen,
 * and a dot on the circle when what it sets has been moved.
 */
@Composable
private fun AdjustTool(label: String, selected: Boolean, changed: Boolean, onClick: () -> Unit, icon: @Composable (Color) -> Unit) {
    val tint = if (selected) Palette.Accent else Palette.DockText
    Column(
        Modifier.width(68.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(48.dp)) {
            Box(Modifier.fillMaxSize().border(1.dp, Color(0xFF474747), CircleShape), contentAlignment = Alignment.Center) { icon(tint) }
            if (changed) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 2.dp).size(7.dp).clip(CircleShape).background(Palette.Accent))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = Type.Small.copy(fontSize = 11.sp, color = tint, fontWeight = FontWeight.Medium),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
        )
    }
}

private fun knobIcon(key: String) = when (key) {
    "brightness" -> LucideIcons.Sun
    "contrast" -> LucideIcons.Contrast
    "saturation" -> LucideIcons.Droplet
    "brilliance" -> LucideIcons.SunMedium
    "sharpen" -> LucideIcons.Diamond
    "clarity" -> LucideIcons.Triangle
    GRAPHS -> LucideIcons.Spline
    WHEELS -> LucideIcons.Aperture
    "highlights" -> LucideIcons.Circle
    "shadows" -> LucideIcons.CircleDashed
    "whites" -> LucideIcons.Square
    "blacks" -> LucideIcons.SquareDashed
    "temperature" -> LucideIcons.Thermometer
    "tint" -> LucideIcons.Palette
    "fade" -> LucideIcons.Blend
    "vignette" -> LucideIcons.Aperture
    "grain" -> LucideIcons.Sparkles
    else -> LucideIcons.Circle
}



/** The frame at the playhead with package [id] over it, drawn by the engine, as a picker tile. */
@Composable
internal fun PreviewTile(vm: EditorViewModel, id: String) = PreviewPicture(rememberPreview(vm, id), id)

/**
 * A filter's tile, a little taller than wide as the reference draws them: the picture, its name on a
 * band across the foot in a shade of the picture's own colour, ringed white when chosen. [label]
 * null (None) has no band.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun FilterTile(
    label: String?,
    selected: Boolean,
    onClick: () -> Unit,
    band: Color? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333333))
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (label != null) {
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp).background(band ?: Color(0xCC3A3A3A)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = Type.Small.copy(fontSize = 11.5.sp, color = Color.White),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (selected) Box(Modifier.matchParentSize().border(2.dp, Color.White, RoundedCornerShape(6.dp)))
    }
}

/** The band under a filter's name: the picture's average colour, deepened so white reads on it. */
private fun bandColour(b: android.graphics.Bitmap): Color {
    val one = android.graphics.Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
    val c = Color(one)
    return Color(c.red * 0.55f, c.green * 0.55f, c.blue * 0.55f, 0.92f)
}

/** The current frame through package [id], made off the main thread and kept. */
@Composable
private fun rememberPreview(vm: EditorViewModel, id: String): android.graphics.Bitmap? {
    val s = vm.session
    val revision = vm.timeline?.revision ?: 0L
    val at = vm.playhead / 500_000 * 500_000
    val t = vm.timeline
    // the short side about as many pixels as a tile is wide, so the cropped picture stays sharp
    val side = 216
    val (w, h) = if (t != null && t.width > t.height) (side * t.width.toInt() / t.height.toInt()) to side else
        side to (side * (t?.height?.toInt() ?: 16) / (t?.width?.toInt() ?: 9))
    val key = "$id|$revision|$at"
    // held per package: a grid slot handed to another package starts from that one's picture, never
    // the last one's; an edit or a move of the playhead keeps the old picture until the new is drawn
    val picture = remember(id) { mutableStateOf(TileCache.get(key)) }
    LaunchedEffect(key) {
        TileCache.get(key)?.let { picture.value = it; return@LaunchedEffect }
        if (s == null) return@LaunchedEffect
        withContext(Threads.thumbnails) {
            runCatching { s.frameWith(at, id, w.coerceAtLeast(2).toUInt(), h.coerceAtLeast(2).toUInt()) }.getOrNull()
                ?.toBitmap()
        }?.let {
            TileCache.put(key, it)
            picture.value = it
        }
    }
    return picture.value
}

@Composable
private fun PreviewPicture(b: android.graphics.Bitmap?, id: String) {
    if (b == null) {
        Box(Modifier.fillMaxSize().background(tileBrush(id)))
    } else {
        androidx.compose.foundation.Image(
            b.asImageBitmap(), contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize(),
        )
    }
}

/** Picker tiles, kept while the panel is reopened: up to 32 MB of them. */
private object TileCache {
    private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(32 shl 20) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap) = value.allocationByteCount
    }
    fun get(key: String): android.graphics.Bitmap? = cache.get(key)
    fun put(key: String, b: android.graphics.Bitmap) { cache.put(key, b) }
}
