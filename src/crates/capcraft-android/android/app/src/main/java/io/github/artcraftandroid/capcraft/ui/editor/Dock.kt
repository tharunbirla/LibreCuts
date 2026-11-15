package io.github.artcraftandroid.capcraft.ui.editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.MediaKinds
import io.github.artcraftandroid.capcraft.ui.common.MediaPick
import io.github.artcraftandroid.capcraft.ui.editor.panels.collage

/** One tool of the bottom bar. */
data class Tool(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    /** A thin line between groups of tools rather than a tool. */
    val divider: Boolean = false,
    val action: () -> Unit,
)

/**
 * The bar while several clips are chosen together: a new title set apart, then what can be done to
 * all of them at once, and Deselect all to stop choosing.
 */
private fun multiTools(vm: EditorViewModel): List<Tool> {
    val chosen = vm.chosen.orEmpty()
    return listOf(
        Tool("addtext", "Add text", LucideIcons.Type) {
            vm.chosen = null
            vm.select(null)
            vm.panel = Panel.Text(null)
        },
        divider("d-multi"),
        Tool("delete", "Delete", LucideIcons.Trash2, enabled = chosen.isNotEmpty()) {
            vm.edit { delete(chosen.toList()) }
            vm.chosen = null
        },
        Tool("deselect", if (chosen.isEmpty()) "Deselect all" else "Deselect all (${chosen.size})", LucideIcons.SquareCheckBig) { vm.chosen = null },
    )
}

/** The line between two groups of tools. */
private fun divider(id: String) = Tool(id, "", LucideIcons.X, divider = true) {}

/** The pickers the bottom bar starts. */
class Pickers(
    val visual: (onPicked: (List<Uri>) -> Unit) -> Unit,
    val single: (onPicked: (Uri) -> Unit) -> Unit,
    val audio: (onPicked: (Uri) -> Unit) -> Unit,
    val video: (onPicked: (Uri) -> Unit) -> Unit,
    /** A caption file, through the system's document picker. */
    val captions: (onPicked: (Uri) -> Unit) -> Unit,
    /** Where a SubRip file of the captions goes, through the system's save dialog, starting from a name. */
    val saveCaptions: (name: String, onPicked: (Uri) -> Unit) -> Unit,
)

@Composable
fun rememberPickers(): Pickers {
    val audio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PickerCallbacks.audio?.invoke(it) }
    }
    val captions = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { PickerCallbacks.captions?.invoke(it) }
    }
    val saveCaptions = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { PickerCallbacks.saveCaptions?.invoke(it) }
    }
    return Pickers(
        // pictures and videos come through the app's own picker
        visual = { cb -> MediaPick.open(MediaKinds.BOTH, multiple = true) { cb(it) } },
        single = { cb -> MediaPick.open(MediaKinds.BOTH, multiple = false) { cb(it.first()) } },
        audio = { cb -> PickerCallbacks.audio = cb; audio.launch(arrayOf("audio/*")) },
        video = { cb -> MediaPick.open(MediaKinds.VIDEO, multiple = false) { cb(it.first()) } },
        // caption files have no settled type: SubRip comes as text, as its own type or as bytes
        captions = { cb ->
            PickerCallbacks.captions = cb
            captions.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream"))
        },
        saveCaptions = { name, cb -> PickerCallbacks.saveCaptions = cb; saveCaptions.launch(name) },
    )
}

/** Where a picker's result goes (the launchers outlive the lambdas that start them). */
private object PickerCallbacks {
    var audio: ((Uri) -> Unit)? = null
    var captions: ((Uri) -> Unit)? = null
    var saveCaptions: ((Uri) -> Unit)? = null
}

@Composable
fun Dock(vm: EditorViewModel) {
    val pickers = rememberPickers()
    val selected = vm.selectedClip
    val row = vm.selectedRow
    val level = vm.dock.last()
    val choosing = vm.chosen != null
    val key = when {
        choosing -> "multi"
        selected != null -> "clip:${row}:${selected.kind}"
        level is DockLevel.Group -> "group:${level.id}"
        else -> "root"
    }
    val sub = vm.clipLevel
    val tools = when {
        choosing -> multiTools(vm)
        selected != null && row != null && sub != null -> clipSubTools(vm, selected, sub)
        selected != null && row != null -> clipTools(vm, selected, row, pickers)
        level is DockLevel.Group -> groupTools(vm, level.id, pickers)
        else -> rootTools(vm)
    }
    val showBack = choosing || selected != null || level is DockLevel.Group
    val raised = choosing || selected != null || level is DockLevel.Group
    Column(Modifier.fillMaxWidth().background(if (raised) Palette.DockSelected else Palette.Timeline)) {
        if (!raised) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.DockSelected))
        Row(
            Modifier.fillMaxWidth().height(EditorMetrics.DockHeight - if (raised) 0.dp else 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showBack) {
                Box(
                    Modifier
                        .padding(start = 10.dp, end = 2.dp)
                        .size(32.dp, 58.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Palette.DockBack)
                        .clickable {
                            when {
                                choosing -> vm.chosen = null
                                selected != null && sub != null -> vm.clipLevel = null
                                selected != null -> vm.select(null)
                                else -> vm.dock = vm.dock.dropLast(1)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // a main clip picked at rest backs out with one chevron, as the reference's does
                    val restingMain = selected != null && row == RowKind.MAIN && level !is DockLevel.Group
                    Glyph(
                        if ((selected != null && !restingMain) || choosing) LucideIcons.ChevronsLeft else LucideIcons.ChevronLeft,
                        Palette.DockText,
                        size = 22.dp,
                        description = "Back",
                    )
                }
            }
            AnimatedContent(
                targetState = key + (sub ?: ""),
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                label = "dock",
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { _ ->
                val few = selected != null && tools.size <= 6 && tools.none { it.divider }
                if (sub != null || few || (selected == null && level is DockLevel.Group && tools.size <= 4)) {
                    // a second level, a group of a few tools or a clip's short bar spreads them across it
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        // up to four keep their names on one line, as the reference's do
                        tools.forEach { tool -> ToolItem(tool, width = if (tools.size <= 4) 88.dp else 60.dp) }
                    }
                } else {
                    // a tool put before the first (a collage's Layout) shows from the start, not scrolled past
                    val row = remember(key, tools.firstOrNull()?.id) { androidx.compose.foundation.lazy.LazyListState() }
                    LazyRow(Modifier.fillMaxHeight(), state = row, verticalAlignment = Alignment.CenterVertically) {
                        items(tools, key = { it.id }) { tool -> ToolItem(tool) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolItem(tool: Tool, width: androidx.compose.ui.unit.Dp = 60.dp) {
    if (tool.divider) {
        Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 22.dp).background(Palette.DockBack))
        return
    }
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .clickable(enabled = tool.enabled, onClick = tool.action)
            .padding(top = 13.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val tint = if (tool.enabled) Palette.DockText else Palette.Disabled
        Glyph(tool.icon, tint, size = 23.dp)
        Spacer(Modifier.height(6.dp))
        Text(
            tool.label,
            style = Type.DockLabel.copy(color = tint, textAlign = TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private fun rootTools(vm: EditorViewModel): List<Tool> = listOfNotNull(
    // a collage's layouts first, as its own editor starts with them
    if (vm.timeline != null && vm.session?.collage() != null) {
        Tool("collage", "Layout", LucideIcons.LayoutTemplate) { vm.panel = Panel.Collage }
    } else {
        null
    },
    Tool("edit", "Edit", LucideIcons.Scissors) {
        vm.mainClipAtPlayhead()?.let { vm.select(it.id) } ?: run { vm.message = "Add a clip first" }
    },
    Tool("audio", "Audio", LucideIcons.Music2) { vm.dock = vm.dock + DockLevel.Group("audio") },
    Tool("text", "Text", LucideIcons.Type) { vm.dock = vm.dock + DockLevel.Group("text") },
    Tool("effects", "Effects", LucideIcons.Star) { vm.dock = vm.dock + DockLevel.Group("effects") },
    Tool("overlay", "Overlay", LucideIcons.Image) { vm.dock = vm.dock + DockLevel.Group("overlay") },
    // the auto captions sheet opens straight over the group, as the reference does
    Tool("captions", "Captions", LucideIcons.Captions) {
        vm.dock = vm.dock + DockLevel.Group("captions")
        vm.panel = Panel.Captions
    },
    // both open their sheet over a group of the two, as the reference does
    Tool("filters", "Filters", LucideIcons.Blend) {
        vm.dock = vm.dock + DockLevel.Group("filters")
        vm.panel = Panel.Packages("filter", Panel.Target.Layer)
    },
    Tool("adjust", "Adjust", LucideIcons.Settings2) {
        vm.dock = vm.dock + DockLevel.Group("filters")
        vm.panel = Panel.Adjust
    },
    Tool("stickers", "Stickers", LucideIcons.Sticker) { vm.panel = Panel.Stickers },
    Tool("transcript", "Transcript", LucideIcons.FileText) { vm.panel = Panel.Transcript },
    Tool("ratio", "Aspect ratio", LucideIcons.Square) { vm.panel = Panel.Ratio },
    Tool("background", "Background", MaterialIcons.Texture) { vm.dock = vm.dock + DockLevel.Group("background") },
)

private fun groupTools(vm: EditorViewModel, id: String, pickers: Pickers): List<Tool> = when (id) {
    "audio" -> listOf(
        Tool("extracted", "Extract", LucideIcons.AudioLines) { pickers.video { vm.addFile(it, asAudio = true) } },
        Tool("sounds", "Sounds", LucideIcons.Music2) { vm.panel = Panel.Sounds },
        Tool("soundfx", "Sound FX", LucideIcons.Sparkles) { vm.panel = Panel.SoundFx },
        Tool("voiceover", "Record", LucideIcons.Mic) { vm.panel = Panel.Voiceover },
        divider("d-audiomake"),
        Tool("texttoaudio", "Text to audio", LucideIcons.AudioLines) { vm.panel = Panel.Speech(null) },
    )
    "text" -> listOf(
        Tool("addtext", "Add text", LucideIcons.Type) { vm.panel = Panel.Text(null) },
        Tool("autocaptions", "Auto Captions", LucideIcons.Captions) { vm.panel = Panel.Captions },
        Tool("stickers", "Stickers", LucideIcons.Sticker) { vm.panel = Panel.Stickers },
        Tool("draw", "Draw", LucideIcons.Paintbrush) { vm.panel = Panel.Draw },
        divider("d-textmore"),
        // a new title starts on the text panel's templates
        Tool("texttemplate", "Text template", LucideIcons.LayoutTemplate) { vm.panel = Panel.Text(null) },
        Tool("texttoaudio", "Text to audio", LucideIcons.AudioLines) { vm.panel = Panel.Speech(null) },
        Tool("autolyrics", "Auto lyrics", LucideIcons.Music) { vm.panel = Panel.Lyrics },
    )
    "overlay" -> listOf(
        Tool("addoverlay", "Add overlay", LucideIcons.SquarePlus) { pickers.single { vm.addFile(it, asAudio = false) } },
    )
    "filters" -> listOf(
        Tool("filterlayer", "Filters", LucideIcons.Blend) { vm.panel = Panel.Packages("filter", Panel.Target.Layer) },
        Tool("adjustlayer", "Adjust", LucideIcons.Settings2) { vm.panel = Panel.Adjust },
    )
    "effects" -> listOf(
        Tool("videoeffects", "Video effects", LucideIcons.Star) { vm.panel = Panel.Packages("effect", Panel.Target.Layer) },
        Tool("bodyeffects", "Body effects", LucideIcons.UserRound) { vm.panel = Panel.BodyEffects },
    )
    "background" -> listOf(
        Tool("bgcolor", "Color", LucideIcons.Palette) { vm.panel = Panel.Background(0) },
        Tool("bgimage", "Image", LucideIcons.Image) { vm.panel = Panel.Background(1) },
        Tool("bgblur", "Blur", LucideIcons.Droplet) { vm.panel = Panel.Background(2) },
    )
    "captions" -> listOf(
        Tool("entercaptions", "Enter captions", LucideIcons.TextCursorInput) { vm.panel = Panel.EnterCaptions },
        Tool("autocaptions", "Auto Captions", LucideIcons.Captions) { vm.panel = Panel.Captions },
        Tool("captiontemplates", "Caption templates", LucideIcons.LayoutTemplate) { vm.panel = Panel.CaptionTemplates },
        Tool("autolyrics", "Auto lyrics", LucideIcons.Music) { vm.panel = Panel.Lyrics },
        Tool("importcaptions", "Import captions", LucideIcons.FolderOpen) { pickers.captions { vm.importCaptions(it) } },
        Tool("exportcaptions", "Export captions", LucideIcons.Download) { vm.exportCaptions { name -> pickers.saveCaptions(name) { vm.saveCaptions(it) } } },
    )
    else -> emptyList()
}

/** The second level a clip tool opens: Speed's two kinds, Transform's three tools (Advanced the rulers), Remove BG's three, Effects' two. */
private fun clipSubTools(vm: EditorViewModel, clip: ClipView, level: String): List<Tool> = when (level) {
    // effects on the clip itself: the picture's, or those that treat the person apart
    "effects" -> listOf(
        Tool("videoeffects", "Video effects", LucideIcons.Star) { vm.panel = Panel.Packages("effect", Panel.Target.Clip) },
        Tool("bodyeffects", "Body effects", LucideIcons.UserRound) { vm.panel = Panel.BodyEffects },
    )
    "speed" -> listOf(
        Tool("normal", "Normal", LucideIcons.TrendingUp) { vm.panel = Panel.Speed(curve = false) },
        Tool("curve", "Curve", LucideIcons.ChartSpline) { vm.panel = Panel.Speed(curve = true) },
    )
    "transform" -> listOf(
        Tool("rotate", "Rotate", LucideIcons.RotateCw) { vm.rotate(clip) },
        Tool("mirror", "Mirror", LucideIcons.FlipVertical2) { vm.mirror(clip) },
        Tool("advanced", "Advanced", LucideIcons.SlidersHorizontal) { vm.panel = Panel.Basic },
    )
    // the model takes the background off, or a colour is keyed out; a new background then shows behind
    "removebg" -> listOf(
        Tool("autoremove", "Auto removal", LucideIcons.UserRoundX) { vm.panel = Panel.RemoveBg },
        Tool("customremove", "Custom removal", LucideIcons.Paintbrush) { vm.panel = Panel.CustomCutout },
        Tool("chroma", "Chroma key", LucideIcons.Pipette) { vm.panel = Panel.Chroma },
        Tool("changebg", "Change BG", LucideIcons.ImagePlus, enabled = io.github.artcraftandroid.capcraft.ui.editor.panels.cutoutOn(vm, clip)) {
            vm.panel = Panel.Background(0)
        },
    )
    else -> emptyList()
}

private fun clipTools(vm: EditorViewModel, clip: ClipView, row: RowKind, pickers: Pickers): List<Tool> {
    val inside = vm.playhead > clip.startUs + 30_000 && vm.playhead < clip.startUs + clip.durationUs - 30_000
    // always offered, as the reference does; at a clip's very edge there is nothing to cut
    val split = Tool("split", "Split", LucideIcons.SquareSplitHorizontal) {
        if (inside) vm.edit { split(listOf(clip.id), vm.playhead) } else vm.message = "Move the playhead inside the clip to split it"
    }
    val delete = Tool("delete", "Delete", LucideIcons.Trash) {
        vm.edit { delete(listOf(clip.id)) }
        vm.select(null)
    }
    val duplicate = Tool("duplicate", "Duplicate", LucideIcons.Copy) { vm.edit(select = { it.createdId.ifEmpty { null } }) { duplicate(clip.id) } }
    // the curve between keyframes needs two of them
    val graphs = Tool("graphs", "Graphs", LucideIcons.Spline, enabled = clip.keysUs.distinct().size >= 2) { vm.panel = Panel.Graphs }
    val layer = Tool("layer", "Layers", LucideIcons.Layers) { vm.panel = Panel.Layer }
    if (clip.kind == "image" && row == RowKind.TEXT) {
        // a sticker, in the reference's order: another sticker set apart, the clip's edits, then the rest
        return listOfNotNull(
            Tool("stickers", "Stickers", LucideIcons.Sticker) {
                vm.select(null)
                vm.panel = Panel.Stickers
            },
            divider("d-stickers"),
            split,
            Tool("animation", "Animations", LucideIcons.SquarePlay) { vm.panel = Panel.Animation },
            duplicate,
            delete,
            Tool("mirror", "Mirror", LucideIcons.FlipVertical2) { vm.mirror(clip) },
            divider("d-stickermore"),
            // several titles and stickers at once, starting with this one
            Tool("multiselect", "Multi-select", LucideIcons.SquareCheckBig) {
                vm.select(null)
                vm.chosen = setOf(clip.id)
            },
            Tool("basic", "Basic", LucideIcons.SlidersHorizontal) { vm.panel = Panel.Basic },
            layer,
            Tool("tracking", "Tracking", LucideIcons.Crosshair) { vm.panel = Panel.Tracking },
            // the curve between keyframes, once there are two
            graphs.takeIf { it.enabled },
        )
    }
    // a caption, in the reference's order for one, its cloud tools left out
    if (clip.kind == "text" && clip.id in vm.captions) {
        return listOf(
            split,
            Tool("style", "Style", LucideIcons.ALargeSmall) { vm.panel = Panel.Text(clip.id) },
            // every caption's words in one list
            Tool("editcaptions", "Edit captions", LucideIcons.TextCursorInput) { vm.panel = Panel.BatchEdit },
            duplicate,
            delete,
            Tool("tts", "Text to speech", LucideIcons.AudioLines) { vm.panel = Panel.Speech(clip.id) },
            Tool("basic", "Basic", LucideIcons.SlidersHorizontal) { vm.panel = Panel.Basic },
            layer,
        )
    }
    return when (clip.kind) {
        "video", "image" -> buildList {
            val video = clip.kind == "video"
            val main = row == RowKind.MAIN
            // over the video, the bar starts with another overlay, set apart, as the reference's does
            if (!main) {
                add(Tool("addoverlay", "Add overlay", LucideIcons.SquarePlus) {
                    vm.select(null)
                    pickers.single { vm.addFile(it, asAudio = false) }
                })
                add(divider("d-addoverlay"))
            }
            add(split)
            // a picture with no sound of its own (none recorded, or taken out to a track) has no
            // Volume, and its Delete comes after Speed, as the reference lays such a clip's bar out
            val ownSound = video && (clip.audible || clip.muted) && !vm.extracted(clip)
            if (ownSound) add(Tool("volume", "Volume", LucideIcons.Volume2) { vm.panel = Panel.Volume })
            // how an overlay mixes with what is under it, under the reference's name for it
            if (!main) add(Tool("blend", "Splice", LucideIcons.Layers2) { vm.panel = Panel.Blend })
            if (main) {
                add(Tool("animation", "Animations", LucideIcons.SquarePlay) { vm.panel = Panel.Animation })
                add(Tool("effects", "Effects", LucideIcons.Star) { vm.clipLevel = "effects" })
                if (ownSound || !video) add(delete)
            } else {
                add(delete)
                add(Tool("animation", "Animations", LucideIcons.SquarePlay) { vm.panel = Panel.Animation })
                add(Tool("effects", "Effects", LucideIcons.Star) { vm.clipLevel = "effects" })
            }
            if (video) add(Tool("speed", "Speed", LucideIcons.Gauge) { vm.clipLevel = "speed" })
            if (main && video && !ownSound) add(delete)
            if (video) add(Tool("beats", "Beats", LucideIcons.Flag, enabled = clip.audible) { vm.panel = Panel.Beats })
            add(Tool("crop", "Crop", LucideIcons.Crop) { vm.panel = Panel.Crop })
            add(duplicate)
            add(Tool("replace", "Replace", LucideIcons.Repeat) { pickers.single { vm.replace(clip, it) } })
            if (main) add(Tool("tooverlay", "Overlay", LucideIcons.Shuffle) { vm.switchToOverlay(clip) })
            else add(Tool("tomain", "Maintrack", LucideIcons.Shuffle) { vm.switchToMain(clip) })
            add(Tool("adjust", "Adjust", LucideIcons.Settings2) { vm.panel = Panel.Adjust })
            add(Tool("filters", "Filters", LucideIcons.Blend) { vm.panel = Panel.Packages("filter", Panel.Target.Clip) })
            add(divider("d-look"))
            add(Tool("retouch", "Retouch", LucideIcons.Smile) { vm.panel = Panel.Retouch })
            // a clearer copy, for photos and footage alike
            add(Tool("enhancequality", "Video quality", MaterialIcons.Hd) { vm.panel = Panel.Quality })
            add(Tool("removebg", "Remove BG", LucideIcons.UserRoundX) { vm.clipLevel = "removebg" })
            // the picture lit again, on the main track as the reference offers it
            if (main) add(Tool("relight", "Relight", LucideIcons.Lightbulb) { vm.panel = Panel.Relight })
            add(Tool("opacity", "Opacity", LucideIcons.Droplet) { vm.panel = Panel.Opacity })
            if (video) add(Tool("motionblur", "Motion blur", LucideIcons.Wind) { vm.panel = Panel.MotionBlur })
            add(divider("d-shape"))
            add(Tool("transform", "Transform", LucideIcons.Crop) { vm.clipLevel = "transform" })
            if (!main) add(Tool("layer", "Layer", LucideIcons.Layers) { vm.panel = Panel.Layer })
            add(Tool("autoreframe", "Auto reframe", LucideIcons.Ratio) { vm.panel = Panel.AutoReframe })
            if (video) {
                add(Tool("stabilize", "Stabilize", LucideIcons.Vibrate) { vm.panel = Panel.Stabilize })
                add(Tool("cameratracking", "Camera tracking", LucideIcons.Crosshair) { vm.panel = Panel.CameraTracking })
            }
            // a picture over the video can follow what is under it
            if (!main) add(Tool("tracking", "Tracking", LucideIcons.Crosshair) { vm.panel = Panel.Tracking })
            if (video) {
                add(divider("d-sound"))
                // once its sound is out on a track of its own, the clip offers only to take it back
                if (vm.extracted(clip)) {
                    add(Tool("recover", "Recover audio", LucideIcons.Undo2) { vm.recoverAudio(clip) })
                } else {
                    add(Tool("extract", "Extract audio", LucideIcons.AudioLines, enabled = clip.audible) { vm.extractAudio(clip) })
                    add(Tool("isolate", "Isolate voice", LucideIcons.Speech, enabled = clip.audible) { vm.panel = Panel.IsolateVoice })
                    add(Tool("noise", "Reduce noise", LucideIcons.AudioWaveform, enabled = clip.audible) { vm.toggleCleaner(clip, "concat.denoise", "Noise reduction") })
                    add(Tool("voice", "Audio effects", LucideIcons.Headphones, enabled = clip.audible) { vm.panel = Panel.Packages("audio", Panel.Target.Clip) })
                    add(Tool("enhance", "Enhance voice", LucideIcons.MicVocal, enabled = clip.audible) { vm.toggleCleaner(clip, "concat.enhance-voice", "Voice enhancement") })
                }
            }
            add(divider("d-time"))
            if (video) {
                add(Tool("freeze", "Freeze", LucideIcons.GalleryHorizontal, enabled = inside) { vm.freeze(clip) })
                add(Tool("reverse", "Reverse", LucideIcons.RotateCcw) { vm.reverse(clip) })
            }
            add(Tool("mask", "Mask", LucideIcons.CircleDashed) { vm.panel = Panel.Mask })
            if (main) {
                val free = vm.unlinked(clip)
                add(Tool("unlink", if (free) "Link" else "Unlink", if (free) LucideIcons.Link2 else LucideIcons.Unlink2) { vm.toggleUnlinked(clip) })
            }
            if (video) add(Tool("transcript", "Transcript", LucideIcons.FileText) { vm.panel = Panel.Transcript })
            add(graphs)
        }
        // in the reference's order: a new recording set apart, the clip's edits, then its sound
        "audio" -> listOf(
            Tool("record", "Record", LucideIcons.Mic) {
                vm.select(null)
                vm.panel = Panel.Voiceover
            },
            divider("d-record"),
            split,
            Tool("volume", "Volume", LucideIcons.Volume2) { vm.panel = Panel.Volume },
            duplicate,
            delete,
            // another sound in its place, keeping where it sits and how long it plays
            Tool("replace", "Replace", LucideIcons.Repeat) {
                vm.replacingSound = clip.id
                vm.panel = Panel.Sounds
            },
            Tool("beats", "Beats", LucideIcons.Flag) { vm.panel = Panel.Beats },
            // a sound's speed is one ruler, no curve, as the reference has it
            Tool("speed", "Speed", LucideIcons.Gauge) { vm.panel = Panel.Speed(curve = false) },
            Tool("fade", "Fade", LucideIcons.ChartSpline) { vm.panel = Panel.Fade },
            Tool("isolate", "Isolate voice", LucideIcons.Speech) { vm.panel = Panel.IsolateVoice },
            Tool("voice", "Audio effects", LucideIcons.Headphones) { vm.panel = Panel.Packages("audio", Panel.Target.Clip) },
            Tool("enhance", "Enhance voice", LucideIcons.MicVocal) { vm.toggleCleaner(clip, "concat.enhance-voice", "Voice enhancement") },
            Tool("noise", "Reduce noise", LucideIcons.AudioWaveform) { vm.toggleCleaner(clip, "concat.denoise", "Noise reduction") },
            Tool("transcript", "Transcript", LucideIcons.FileText) { vm.panel = Panel.Transcript },
        )
        "text", "shape" -> listOf(
            // a new title from here too, set apart, as the reference starts this bar
            Tool("addtext", "Add text", LucideIcons.Type) {
                vm.select(null)
                vm.panel = Panel.Text(null)
            },
            divider("d-addtext"),
            split,
            Tool("style", "Style", LucideIcons.ALargeSmall) { vm.panel = if (clip.kind == "shape") Panel.ShapeStyle else Panel.Text(clip.id) },
            Tool("animation", "Animations", LucideIcons.SquarePlay) { vm.panel = Panel.Animation },
            delete,
            duplicate,
            divider("d-texttools"),
            Tool("tts", "Text to speech", LucideIcons.AudioLines, enabled = clip.kind == "text") { vm.panel = Panel.Speech(clip.id) },
            // on screen from the first frame to the last
            Tool("lasting", "Lasting text", LucideIcons.MoveHorizontal) {
                vm.edit(select = { clip.id }) { lastingText(clip.id) }
                vm.message = "The text now shows for the whole video"
            },
            Tool("texteffects", "Effects", LucideIcons.WandSparkles, enabled = clip.kind == "text") { vm.panel = Panel.Text(clip.id, tab = 4) },
            Tool("tracking", "Tracking", LucideIcons.Crosshair) { vm.panel = Panel.Tracking },
            // one clip per line or sentence, each for its share of the time
            Tool("autosplit", "Auto split", LucideIcons.SquareSplitHorizontal, enabled = clip.kind == "text") {
                vm.edit(select = { clip.id }) { autoSplitText(clip.id) }
            },
            layer,
            Tool("basic", "Basic", LucideIcons.SlidersHorizontal) { vm.panel = Panel.Basic },
            // several titles and stickers at once, starting with this one
            Tool("multiselect", "Multi-select", LucideIcons.SquareCheckBig) {
                vm.select(null)
                vm.chosen = setOf(clip.id)
            },
        )
        // a look on its row: its strength or its settings, then the clip tools
        else -> if (row == RowKind.FILTER) listOf(
            Tool("adjust", "Adjust", LucideIcons.SlidersHorizontal) {
                vm.panel = if (clip.effectId == "concat.adjust") Panel.Adjust else Panel.Packages("filter", Panel.Target.Layer)
            },
            duplicate,
            split,
            Tool("layer", "Layer", LucideIcons.Layers) { vm.panel = Panel.Layer },
            delete,
        ) else listOf(
            // an effect: its settings, another in its place, and what it treats
            Tool("adjust", "Adjust", LucideIcons.SlidersHorizontal) { vm.panel = Panel.LayerAdjust },
            Tool("replace", "Replace", LucideIcons.Replace) { vm.panel = Panel.Packages("effect", Panel.Target.Layer) },
            duplicate,
            Tool("object", "Object", LucideIcons.Database) { vm.panel = Panel.EffectObject },
            delete,
            Tool("layer", "Layer", LucideIcons.Layers) { vm.panel = Panel.Layer },
        )
    }
}

/** The effects the search sheet shows as trending: our newest. */
private val TRENDING_EFFECTS = listOf("capcraft.disco-lights", "capcraft.confetti", "capcraft.twinkle", "capcraft.light-leak")

/**
 * The search, as the reference's: a sheet under the top bar with a box to say what to do. Empty,
 * it suggests a few things to do and shows trending effects and sounds; typed into, it finds the
 * tools (the selected clip's first, then the top level's and every group's) and the effects whose
 * names match. Choosing anything closes it and does it.
 */
@Composable
fun ToolSearch(vm: EditorViewModel, onClose: () -> Unit) {
    val pickers = rememberPickers()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    val selected = vm.selectedClip
    val row = vm.selectedRow
    val all = buildList {
        if (selected != null && row != null) addAll(clipTools(vm, selected, row, pickers))
        addAll(rootTools(vm))
        for (group in listOf("audio", "text", "effects", "overlay", "captions", "filters", "background")) addAll(groupTools(vm, group, pickers))
    }.filter { !it.divider && it.enabled }.distinctBy { it.label }
    val effects = io.github.artcraftandroid.capcraft.ui.editor.panels.rememberPackages("effect")
        .filter { it.category !in setOf("Cutout", "Color", "Basic", "Text") }
    val words = query.trim()
    val tools = if (words.isEmpty()) emptyList() else all.filter { it.label.contains(words, ignoreCase = true) }
    val found = if (words.isEmpty()) emptyList() else effects.filter { it.name.contains(words, ignoreCase = true) }.take(8)

    /** The clip a suggestion acts on: the selected picture, or the main clip under the playhead. */
    fun target() = selected?.takeIf { it.kind == "video" || it.kind == "image" } ?: vm.mainClipAtPlayhead()

    androidx.activity.compose.BackHandler(onBack = onClose)
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000))
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose),
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(top = 76.dp)
                .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                .background(Palette.DockSelected)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    io.github.artcraftandroid.capcraft.ui.editor.panels.SearchField(query, { query = it }, "Describe what you want to do")
                }
                Text(
                    "Done",
                    style = Type.BodyStrong.copy(color = Palette.Text, fontSize = 16.sp),
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                if (words.isEmpty()) {
                    SearchHeading("Recommended")
                    listOf<Pair<String, () -> Unit>>(
                        "Flip video horizontally" to { target()?.let { vm.mirror(it) } ?: run { vm.message = "Add a clip first" } },
                        "Add auto captions" to { vm.panel = Panel.Captions },
                        "Reduce video noise" to {
                            target()?.let { vm.toggleCleaner(it, "concat.denoise", "Noise reduction") } ?: run { vm.message = "Add a clip first" }
                        },
                    ).forEach { (line, act) ->
                        Text(
                            line,
                            style = Type.Body.copy(color = Palette.DockText, fontSize = 14.sp),
                            modifier = Modifier.fillMaxWidth().clickable { onClose(); act() }.padding(vertical = 9.dp),
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    SearchHeading("Trending")
                    SearchSubheading("Effects") {
                        onClose()
                        vm.panel = Panel.Packages("effect", Panel.Target.Layer)
                    }
                    EffectRow(vm, effects.filter { it.id in TRENDING_EFFECTS }.sortedBy { TRENDING_EFFECTS.indexOf(it.id) }) { e ->
                        onClose()
                        vm.addLayer(e.id, e.name)
                    }
                    Spacer(Modifier.height(16.dp))
                    SearchSubheading("Sounds") {
                        onClose()
                        vm.panel = Panel.SoundFx
                    }
                    io.github.artcraftandroid.capcraft.ui.editor.panels.TRENDING_SOUNDS.forEach { name ->
                        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A3A)), contentAlignment = Alignment.Center) {
                                Glyph(LucideIcons.Music, Palette.DockText, size = 22.dp)
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(name, style = Type.Body.copy(color = Palette.Text, fontSize = 15.sp))
                                val seconds = io.github.artcraftandroid.capcraft.ui.editor.panels.soundFxSeconds(name) ?: 0.0
                                Text("00:%02d".format(kotlin.math.ceil(seconds).toInt()), style = Type.Small.copy(fontSize = 12.sp, color = Palette.TextSecondary))
                            }
                            Box(
                                Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color(0xFF3A3A3A)).clickable {
                                    io.github.artcraftandroid.capcraft.ui.editor.panels.addSoundFx(vm, name, scope)
                                    onClose()
                                },
                                contentAlignment = Alignment.Center,
                            ) { Glyph(LucideIcons.Plus, Palette.Text, size = 18.dp, description = "Add $name") }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                } else {
                    if (tools.isEmpty() && found.isEmpty()) {
                        Text("Nothing by that name", style = Type.Small, modifier = Modifier.padding(vertical = 24.dp))
                    }
                    if (tools.isNotEmpty()) {
                        SearchHeading("Tools")
                        tools.chunked(4).forEach { line ->
                            Row(Modifier.fillMaxWidth()) {
                                line.forEach { tool ->
                                    Box(Modifier.weight(1f).height(80.dp), contentAlignment = Alignment.Center) {
                                        ToolItem(tool.copy(action = { onClose(); tool.action() }))
                                    }
                                }
                                repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    if (found.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        SearchHeading("Effects")
                        found.chunked(4).forEach { line ->
                            EffectRow(vm, line) { e ->
                                onClose()
                                vm.addLayer(e.id, e.name)
                            }
                            Spacer(Modifier.height(10.dp))
                        }
                    }
                }
            }
        }
    }
}

/** A section's title in the search sheet. */
@Composable
private fun SearchHeading(title: String) {
    Text(title, style = Type.BodyStrong.copy(color = Palette.Text, fontSize = 17.sp), modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

/** A trending shelf's name, and More at the right to open its whole panel. */
@Composable
private fun SearchSubheading(title: String, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = Type.Body.copy(color = Palette.DockText, fontSize = 14.sp), modifier = Modifier.weight(1f))
        Row(Modifier.clickable(onClick = onMore).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("More", style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 13.sp))
            Glyph(LucideIcons.ChevronRight, Palette.TextSecondary, size = 16.dp)
        }
    }
}

/** Up to four effects across: the frame at the playhead through each, its name under it. */
@Composable
private fun EffectRow(
    vm: EditorViewModel,
    line: List<io.github.artcraftandroid.capcraft.core.PackageInfo>,
    onPick: (io.github.artcraftandroid.capcraft.core.PackageInfo) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        line.forEach { e ->
            Column(Modifier.weight(1f).clickable { onPick(e) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp))) {
                    io.github.artcraftandroid.capcraft.ui.editor.panels.PreviewTile(vm, e.id)
                }
                Spacer(Modifier.height(4.dp))
                Text(e.name, style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
    }
}
