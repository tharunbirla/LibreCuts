package io.github.artcraftandroid.capcraft.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.drawText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.intoBitmap
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.Toast
import io.github.artcraftandroid.capcraft.ui.editor.panels.PanelHost
import io.github.artcraftandroid.capcraft.ui.export.ExportScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun EditorScreen(path: String, incoming: List<Uri>, then: String? = null, close: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: EditorViewModel = viewModel(key = path, factory = EditorViewModel.Factory(app, path))
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.open(incoming, then) }
    // the picker marks what this project holds already
    androidx.compose.runtime.DisposableEffect(path) {
        io.github.artcraftandroid.capcraft.ui.common.MediaPick.project = path
        onDispose { io.github.artcraftandroid.capcraft.ui.common.MediaPick.project = null }
    }
    // a project gets its cover as soon as it has a picture, in case the app is not left cleanly
    val hasMedia = (vm.timeline?.durationUs ?: 0L) > 0L
    LaunchedEffect(hasMedia) {
        if (hasMedia && !java.io.File(vm.path, "cover.jpg").exists()) saveCover(vm)
    }

    // Saved, cover and all, whenever the app goes to the background: it may not come back.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                vm.stop()
                scope.launch {
                    saveCover(vm)
                    vm.saveNow()
                }
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    fun leave() {
        scope.launch {
            vm.stop()
            saveCover(vm)
            vm.close()
            close()
        }
    }

    fun export() {
        vm.stop()
        if ((vm.timeline?.durationUs ?: 0L) <= 0L) vm.message = "Add media first" else exporting = true
    }

    BackHandler {
        when {
            exporting -> exporting = false
            vm.editingCover -> vm.editingCover = false
            vm.fullscreen -> vm.fullscreen = false
            vm.panel != null -> vm.panel = null
            vm.selected != null -> vm.select(null)
            vm.dock.size > 1 -> vm.dock = vm.dock.dropLast(1)
            else -> leave()
        }
    }

    // The player's clock, once a frame, while playing.
    LaunchedEffect(vm.playing) {
        if (!vm.playing) return@LaunchedEffect
        withContext(AndroidUiDispatcher.Main) {
            while (isActive && vm.playing) {
                androidx.compose.runtime.withFrameNanos { }
                vm.tick()
            }
        }
    }

    // how far above the screen's foot the preview ends: a notice shows just above it, as the reference's do
    var belowPreview by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.fillMaxSize().background(Palette.EditorTop)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            TopBar(vm = vm, onClose = ::leave, onExport = ::export)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // the preview and the timeline share what the bars leave, about 60:40
                val free = maxHeight - EditorMetrics.PlayBarHeight
                val timelineHeight = maxOf(EditorMetrics.TimelineHeight, free * 0.4f)
                vm.timelineHeight = timelineHeight
                vm.areaHeight = maxHeight
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { at ->
                            val root = at.findRootCoordinates().size.height
                            belowPreview = root - at.boundsInRoot().bottom
                        },
                    ) {
                        // under the full-screen player it is hidden, and would make every frame a second time
                        if (!vm.fullscreen) Preview(vm, Modifier.fillMaxSize())
                    }
                    PlayBar(vm)
                    Box(Modifier.fillMaxWidth().height(timelineHeight)) {
                        Timeline(vm, Modifier.fillMaxSize())
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(Palette.Timeline).navigationBarsPadding()) { Dock(vm) }
        }
        PanelHost(vm)
        if (vm.recording && vm.prompter.isNotBlank()) Teleprompter(vm.prompter)
        if (vm.toolSearch) ToolSearch(vm) { vm.toolSearch = false }
        if (vm.fullscreen) FullscreenPreview(vm)
        if (vm.exportSheet) ExportSheet(vm, onExport = ::export)
        if (vm.editingCover) CoverEditor(vm, onClose = { vm.editingCover = false })
        vm.busy?.let { Busy(it) }
        if (exporting) {
            ExportScreen(vm, onClose = { exporting = false }, onHome = { exporting = false; leave() })
        }
        Toast(vm.message, bottom = with(density) { belowPreview.toDp() } + 28.dp) { vm.message = null }
    }
}

/** Sizes shared by the editor's parts. */
object EditorMetrics {
    val TopBarHeight = 50.dp
    val PlayBarHeight = 40.dp
    val TimelineHeight = 236.dp
    val DockHeight = 70.dp
}

@Composable
private fun TopBar(vm: EditorViewModel, onClose: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(EditorMetrics.TopBarHeight).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(start = 6.dp).size(44.dp, 50.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.X, Palette.DockText, size = 26.dp, description = "Close")
        }
        // finding a tool by its name, beside the close button as the reference has it
        Box(Modifier.size(44.dp, 50.dp).clickable { vm.stop(); vm.toolSearch = true }, contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.Search, Palette.DockText, size = 24.dp, description = "Find a tool")
        }
        Spacer(Modifier.weight(1f))
        val t = vm.timeline
        val short = t?.let { min(it.width.toInt(), it.height.toInt()) } ?: 1080
        Row(
            Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Chip)
                .clickable { vm.panel = null; vm.exportSheet = true }
                .padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (vm.exportGif) "GIF" else resolutionLabel(vm.exportResolution ?: short),
                style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            Glyph(MaterialIcons.ArrowDropDown, Palette.Text, size = 20.dp)
        }
        Spacer(Modifier.width(9.dp))
        Box(
            Modifier
                .height(32.dp)
                .width(65.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Accent)
                .clickable(onClick = onExport),
            contentAlignment = Alignment.Center,
        ) {
            Text("Export", style = Type.BodyStrong.copy(color = Palette.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        }
        Spacer(Modifier.width(16.dp))
    }
}

/** "1080P" for a 1080-pixel short side, "2K"/"4K" above. */
fun resolutionLabel(short: Int): String = when {
    short >= 2160 -> "4K"
    short >= 1440 -> "2K"
    else -> "${short}P"
}

/**
 * The composited frame at the playhead. While paused, every playhead or edit change asks for a new
 * frame (the newest wins); while playing, each frame of the timeline is drawn once, when the display reaches it.
 */
@Composable
fun Preview(vm: EditorViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier.background(Palette.EditorTop), contentAlignment = Alignment.Center) {
        val t = vm.timeline
        if (t == null) {
            CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            return@BoxWithConstraints
        }
        val density = LocalDensity.current
        val boxW = with(density) { maxWidth.toPx() }
        val boxH = with(density) { maxHeight.toPx() }
        val aspect = t.width.toFloat() / t.height.toFloat().coerceAtLeast(1f)
        val (shownW, shownH) = if (boxW / boxH > aspect) (boxH * aspect) to boxH else boxW to (boxW / aspect)
        // the frame the engine draws: the shown size, at most 1080 on its short side
        val cap = min(1f, 1080f / min(shownW, shownH).coerceAtLeast(1f))
        val frameW = ((shownW * cap).roundToInt() / 2 * 2).coerceAtLeast(2)
        val frameH = ((shownH * cap).roundToInt() / 2 * 2).coerceAtLeast(2)
        val image = rememberFrames(vm, frameW, frameH)
        Box(
            Modifier
                .size(with(density) { shownW.toDp() }, with(density) { shownH.toDp() })
                .background(Color.Black),
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize()) }
            // while drawing, the finger draws on the frame instead of moving what is on it
            // the eyedropper's tap goes to the picture, not into a stroke
            if (vm.panel == Panel.Draw && vm.pickColor == null) {
                io.github.artcraftandroid.capcraft.ui.editor.panels.DrawSurface(Modifier.fillMaxSize())
            } else if (vm.panel == Panel.Tracking || vm.panel == Panel.CameraTracking) {
                io.github.artcraftandroid.capcraft.ui.editor.panels.TrackSurface(Modifier.fillMaxSize())
            } else if (vm.panel == Panel.Chroma && io.github.artcraftandroid.capcraft.ui.editor.panels.ChromaPick.at != null) {
                io.github.artcraftandroid.capcraft.ui.editor.panels.ChromaSurface(vm, Modifier.fillMaxSize())
            } else if (vm.panel == Panel.Mask) {
                io.github.artcraftandroid.capcraft.ui.editor.panels.MaskSurface(vm, Modifier.fillMaxSize())
            } else if (vm.panel == Panel.CustomCutout) {
                io.github.artcraftandroid.capcraft.ui.editor.panels.CutoutSurface(vm, Modifier.fillMaxSize())
            } else if (!vm.fullscreen) {
                PreviewOverlay(vm, Modifier.fillMaxSize())
            }
        }
        if (t.rows.all { it.clips.isEmpty() }) {
            Text("Add media to start editing", style = Type.Small)
        }
    }
}

/** How long the playhead rests after a scrub before the monitor shows the exact frame, milliseconds. */
private const val SCRUB_REST_MS = 150L

/** Counts the frames a drag of the playhead gets and how long each took, logged when it rests, as playback logs its rate. */
private class ScrubMeter {
    private var frames = 0
    private var busy = 0L
    private var first = 0L
    private var last = 0L
    fun add(started: Long) {
        val now = System.nanoTime()
        if (frames == 0) first = started
        frames++
        busy += now - started
        last = now
    }
    fun rest(width: Int, height: Int) {
        if (frames == 0) return
        val span = (last - first).coerceAtLeast(1L)
        android.util.Log.d("CapCraft", "scrub %d frames, %.1f fps, %.1f ms a frame at %dx%d".format(frames, frames * 1e9 / span, busy / 1e6 / frames, width, height))
        frames = 0
        busy = 0L
    }
}

/**
 * Three bitmaps the engine draws into in turn: the one on screen, the one made ahead of it while playing, and one
 * more, so the one on screen is never written mid-frame.
 */
private class FrameBuffers {
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    fun take(): Bitmap? = bitmaps[next]
    fun put(b: Bitmap) {
        bitmaps[next] = b
        next = (next + 1) % bitmaps.size
    }
}

@Composable
private fun rememberFrames(vm: EditorViewModel, width: Int, height: Int): ImageBitmap? {
    var image by remember { mutableStateOf<ImageBitmap?>(null) }
    val buffers = remember { FrameBuffers() }
    val meter = remember { ScrubMeter() }
    val session = vm.session
    val revision = vm.timeline?.revision
    LaunchedEffect(session, revision, width, height, vm.playing) {
        val s = session ?: return@LaunchedEffect
        if (vm.playing) {
            // which frame of the timeline an instant falls in, and the instant a frame is made for: a quarter of the way
            // in, as the engine takes the frame nearest the time it is asked for, and the middle was a tie that
            // rounding settled both ways, so one frame in three came back as the one before it
            val num = (vm.timeline?.fpsNum ?: 30L).coerceAtLeast(1L)
            val den = (vm.timeline?.fpsDen ?: 1L).coerceAtLeast(1L)
            fun frameAt(us: Long) = us * num / (den * 1_000_000L)
            fun timeOf(frame: Long) = (4 * frame + 1) * den * 1_000_000L / (4 * num)
            suspend fun make(frame: Long): Bitmap? {
                val pic = withContext(Threads.preview) {
                    runCatching { s.frame(timeOf(frame), width.toUInt(), height.toUInt(), Motion.PLAYING) }.getOrNull()
                } ?: return null
                val b = pic.intoBitmap(buffers.take()) ?: return null
                buffers.put(b)
                return b
            }
            var shown = -1L
            var ready: Bitmap? = null
            var readyFrame = -1L
            var frames = 0
            var skipped = 0L
            var since = System.nanoTime()
            withContext(AndroidUiDispatcher.Main) {
                while (isActive && vm.playing) {
                    // each frame of the timeline is made once, while the one before it is on screen, and shown on the
                    // display frame the playhead reaches it; one the engine was too slow for is skipped, not caught up
                    // on, so the picture keeps time with the sound
                    androidx.compose.runtime.withFrameNanos { }
                    val frame = frameAt(vm.playhead)
                    if (frame == shown) continue
                    if (shown >= 0 && frame > shown + 1) skipped += frame - shown - 1
                    val b = (if (frame == readyFrame) ready else null) ?: make(frame) ?: break
                    image = b.asImageBitmap()
                    shown = frame
                    frames++
                    val now = System.nanoTime()
                    if (now - since >= 2_000_000_000L) {
                        android.util.Log.d("CapCraft", "preview %.1f fps at %dx%d, %d frames skipped".format(frames * 1e9 / (now - since), width, height, skipped))
                        frames = 0
                        skipped = 0
                        since = now
                    }
                    readyFrame = frame + 1
                    ready = make(readyFrame) ?: break
                }
            }
        } else {
            suspend fun show(at: Long, motion: Motion) {
                val started = System.nanoTime()
                val pic = withContext(Threads.preview) {
                    runCatching { s.frame(at, width.toUInt(), height.toUInt(), motion) }.getOrNull()
                } ?: return
                if (motion == Motion.SCRUBBING) meter.add(started)
                val b = pic.intoBitmap(buffers.take()) ?: return
                buffers.put(b)
                vm.lastFrame = b
                image = b.asImageBitmap()
            }
            var rest: Job? = null
            // every frame made is shown, and the next is made for wherever the playhead is by then:
            // a drag moves it faster than frames are made, and waiting for it to hold still showed few
            snapshotFlow { vm.playhead }.conflate().collect { playhead ->
                rest?.cancel()
                // the last frame, not the black just past it, at the very end
                val end = vm.timeline?.durationUs ?: 0L
                val at = if (end > 0 && playhead >= end) (end - 34_000).coerceAtLeast(0) else playhead
                if (SystemClock.uptimeMillis() - vm.scrubbedAt < SCRUB_REST_MS) {
                    // under a finger, the light scrub copy; once the playhead rests, the exact frame
                    show(at, Motion.SCRUBBING)
                    rest = launch {
                        delay(SCRUB_REST_MS)
                        meter.rest(width, height)
                        show(at, Motion.STILL)
                    }
                } else {
                    show(at, Motion.STILL)
                }
            }
        }
    }
    return image
}

@Composable
private fun PlayBar(vm: EditorViewModel) {
    val t = vm.timeline
    Row(
        Modifier.fillMaxWidth().height(EditorMetrics.PlayBarHeight).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { vm.fullscreen = true }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Expand, Palette.DockText, size = 24.dp, description = "Full screen")
            }
        }
        Box(Modifier.size(44.dp).clickable { vm.togglePlay() }, contentAlignment = Alignment.Center) {
            Glyph(if (vm.playing) LucideIcons.Pause else LucideIcons.Play, Palette.DockText, size = 28.dp, description = if (vm.playing) "Pause" else "Play")
        }
        Row(Modifier.weight(1f), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val canUndo = t?.canUndo == true
            val canRedo = t?.canRedo == true
            val sel = vm.selectedClip
            if (sel == null) {
                // the linkage switch: what sits over a main clip follows it, or stays put
                Box(Modifier.size(36.dp).clickable { vm.toggleLinkage() }, contentAlignment = Alignment.Center) {
                    LinkageGlyph(vm.linkageOn)
                }
            }
            if (sel != null && sel.kind != "layer") {
                val onKey = sel.keysUs.any { kotlin.math.abs(sel.startUs + it - vm.playhead) <= 34_000 }
                val inside = vm.playhead >= sel.startUs && vm.playhead <= sel.startUs + sel.durationUs
                Box(
                    Modifier.size(36.dp).clickable(enabled = inside) { vm.stop(); vm.edit { toggleKeyframe(sel.id, vm.playhead) } },
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(
                        if (onKey) LucideIcons.DiamondMinus else LucideIcons.DiamondPlus,
                        if (!inside) Palette.Disabled else if (onKey) Palette.Accent else Palette.DockText,
                        size = 24.dp,
                        description = if (onKey) "Remove keyframe" else "Add keyframe",
                    )
                }
            }
            Box(Modifier.size(36.dp).clickable(enabled = canUndo) { vm.stop(); vm.step(back = true) }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Undo2, if (canUndo) Palette.DockText else Palette.Disabled, size = 23.dp, description = "Undo")
            }
            Box(Modifier.size(36.dp).clickable(enabled = canRedo) { vm.stop(); vm.step(back = false) }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Redo2, if (canRedo) Palette.DockText else Palette.Disabled, size = 23.dp, description = "Redo")
            }
        }
    }
}

/**
 * The linkage switch's mark: a frame with a second, open one over its corner - what sits over a
 * clip, held to it - and ON or OFF in that corner.
 */
@Composable
private fun LinkageGlyph(on: Boolean) {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val label = if (on) "ON" else "OFF"
    androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx())
        val tint = Palette.DockText
        val r = androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx())
        drawRoundRect(tint, Offset(1.dp.toPx(), 1.dp.toPx()), androidx.compose.ui.geometry.Size(12.dp.toPx(), 13.dp.toPx()), r, style = stroke)
        // the second frame: its left edge a bracket, open where the first one shows through
        val x0 = 9.dp.toPx()
        val y0 = 7.dp.toPx()
        val x1 = 23.dp.toPx()
        val y1 = 22.dp.toPx()
        val w = 1.6.dp.toPx()
        // the front frame hides the back one where they overlap
        drawRect(Palette.EditorTop, Offset(x0 - w / 2, y0 - w / 2), androidx.compose.ui.geometry.Size(x1 - x0 + w, y1 - y0 + w))
        drawLine(tint, Offset(x0 + 3.dp.toPx(), y0), Offset(x1, y0), w)
        drawLine(tint, Offset(x1, y0), Offset(x1, y1 - 7.dp.toPx()), w)
        drawLine(tint, Offset(x0, y0 + 3.dp.toPx()), Offset(x0, y1), w)
        drawLine(tint, Offset(x0, y1), Offset(x0 + 3.dp.toPx(), y1), w)
        val text = measurer.measure(
            label,
            androidx.compose.ui.text.TextStyle(color = tint, fontSize = 7.5.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        )
        drawText(text, topLeft = Offset(x1 - text.size.width + 1.dp.toPx(), y1 - text.size.height + 2.dp.toPx()))
    }
}

@Composable
private fun FullscreenPreview(vm: EditorViewModel) {
    Column(Modifier.fillMaxSize().background(Palette.Black).statusBarsPadding().navigationBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { Preview(vm, Modifier.fillMaxSize()) }
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clickable { vm.togglePlay() }, contentAlignment = Alignment.Center) {
                Glyph(if (vm.playing) LucideIcons.Pause else LucideIcons.Play, Palette.Text, size = 26.dp)
            }
            Text(clock(vm.playhead), style = Type.Small.copy(color = Palette.Text))
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { Scrubber(vm) }
            Text(clock(vm.timeline?.durationUs ?: 0), style = Type.Small)
            Box(Modifier.size(40.dp).clickable { vm.fullscreen = false }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Minimize, Palette.Text, size = 20.dp, description = "Exit full screen")
            }
        }
    }
}

/** The full-screen player's scrubber: a thin line, white as far as the playhead, and a ring there. */
@Composable
private fun Scrubber(vm: EditorViewModel) {
    val end = (vm.timeline?.durationUs ?: 0L).coerceAtLeast(1L)
    val fraction = (vm.playhead.toFloat() / end).coerceIn(0f, 1f)
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(32.dp).pointerInput(end) {
            val r = 8.dp.toPx()
            fun at(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
            awaitEachGesture {
                val down = awaitFirstDown()
                vm.scrub((at(down.position.x) * end).toLong())
                while (true) {
                    val e = awaitPointerEvent()
                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) break
                    vm.scrub((at(c.position.x) * end).toLong())
                    c.consume()
                }
            }
        },
    ) {
        val r = 8.dp.toPx()
        val cy = size.height / 2
        val x = r + (size.width - 2 * r) * fraction
        drawLine(Color(0xFF5A5A5A), Offset(r, cy), Offset(size.width - r, cy), 1.5.dp.toPx())
        drawLine(Color.White, Offset(r, cy), Offset(x, cy), 1.5.dp.toPx())
        drawCircle(Palette.Black, r - 1.dp.toPx(), Offset(x, cy))
        drawCircle(Color.White, r - 1.dp.toPx(), Offset(x, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

@Composable
private fun Busy(label: String) {
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000)).clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Palette.Panel).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.height(12.dp))
            Text(label, style = Type.Body)
        }
    }
}

/** The project's cover: its first frame, small, saved beside the document for the home screen. */
private suspend fun saveCover(vm: EditorViewModel) {
    // a picture from the album, chosen in the cover editor, is kept as it is
    if (vm.coverAt < 0) return
    val s = vm.session ?: return
    val t = vm.timeline ?: return
    if (t.durationUs <= 0) return
    withContext(Threads.preview) {
        runCatching {
            val w = 240
            val h = (w * t.height.toFloat() / t.width.toFloat()).roundToInt() / 2 * 2
            val at = vm.coverAt.coerceIn(0, (t.durationUs - 1).coerceAtLeast(0))
            val pic = s.frame(at, w.toUInt(), h.coerceAtLeast(2).toUInt(), Motion.STILL)
            pic.intoBitmap(null)?.let { Media.saveCover(vm.path, it) }
        }
    }
}

@Suppress("unused")
private fun Modifier.fullHeight() = this.fillMaxHeight()

/** The teleprompter: the script in a dark band over the top of the picture, rolling up slowly. */
@Composable
private fun Teleprompter(script: String) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    LaunchedEffect(script) {
        // about four words a second, as people read aloud
        val words = script.split(Regex("\\s+")).size
        kotlinx.coroutines.delay(800)
        scroll.animateScrollTo(scroll.maxValue, androidx.compose.animation.core.tween(durationMillis = words * 250, easing = androidx.compose.animation.core.LinearEasing))
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(top = EditorMetrics.TopBarHeight + 8.dp, start = 16.dp, end = 16.dp)) {
        Box(
            Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xCC000000))
                .verticalScroll(scroll, enabled = false).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(script, style = Type.Title.copy(fontSize = 22.sp, lineHeight = 30.sp, color = Color.White))
        }
    }
}
