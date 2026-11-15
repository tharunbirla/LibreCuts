package io.github.artcraftandroid.capcraft.ui.editor

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.core.RowView
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.seconds1
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The timeline's fixed sizes (in dp): one second is 50dp at zoom 1. */
object Track {
    /** The band the time labels sit in, at the top. */
    const val RULER = 18f
    /** From the labels down to the main track. */
    const val RULER_GAP = 23f
    const val MAIN = 50f
    const val ROW = 30f
    /** The "add audio" and "add text" rows. */
    const val ADD_ROW = 30f
    /** Under the main track, before the first row. */
    const val FIRST_GAP = 14f
    const val GAP = 8f
    const val DP_PER_SECOND = 50f
    const val HANDLE = 14f
    const val MIN_ZOOM = 0.1f
    const val MAX_ZOOM = 20f
}

/** The kinds of row under the main track, in the order the reference lists them. */
private val KIND_ORDER = listOf(RowKind.OVERLAY, RowKind.AUDIO, RowKind.TEXT, RowKind.EFFECT, RowKind.FILTER)

/** The rows below the main track, in the order the timeline lists them. */
fun orderedRows(rows: List<RowView>): List<RowView> =
    rows.filter { it.kind == RowKind.MAIN } + KIND_ORDER.flatMap { kind -> rows.filter { it.kind == kind } }

/**
 * With nothing open, each kind on one row, as the reference's timeline rests: the clips of all its
 * lanes together, the back lane's first so the front's draw over them. The row has no lane of its
 * own (an empty id), so a clip dragged along it keeps its lane.
 */
private fun mergedRows(rows: List<RowView>): List<RowView> =
    rows.filter { it.kind == RowKind.MAIN } + KIND_ORDER.mapNotNull { kind ->
        val lanes = rows.filter { it.kind == kind }
        if (lanes.isEmpty()) null else RowView(
            trackId = "",
            kind = kind,
            muted = lanes.all { it.muted },
            visible = lanes.any { it.visible },
            allVideos = false,
            clips = lanes.flatMap { it.clips },
        )
    }

/** A folded row's line: the colour its clips are known by. */
private fun foldColour(kind: RowKind): Color = when (kind) {
    RowKind.AUDIO -> Palette.AudioWave
    RowKind.TEXT -> Palette.TextClip
    RowKind.EFFECT -> Palette.EffectClip
    RowKind.FILTER -> Palette.FilterClip
    else -> Color(0xFF8A8A8A)
}

/**
 * The kinds of row a dock group works on, the rest folding while it is open; with no group open, a
 * selected clip off the main track keeps its own kind open the same way. Null when nothing folds.
 */
private fun openKinds(vm: EditorViewModel): Set<RowKind>? {
    // a selected clip off the main track keeps its own row open, whatever group is open; a selected
    // main clip leaves the rows as they were, the group's open or all at rest, as the reference does
    val row = vm.selectedRow?.takeIf { vm.selected != null }
    if (row == RowKind.MAIN) return groupKinds(vm)
    row?.let { return setOf(it) }
    return groupKinds(vm)
}

private fun groupKinds(vm: EditorViewModel): Set<RowKind>? = when (val level = vm.dock.lastOrNull()) {
    is DockLevel.Group -> when (level.id) {
        "audio" -> setOf(RowKind.AUDIO)
        "text", "captions" -> setOf(RowKind.TEXT)
        "effects" -> setOf(RowKind.EFFECT)
        "filters" -> setOf(RowKind.FILTER)
        "overlay" -> setOf(RowKind.OVERLAY)
        else -> emptySet()
    }
    else -> null
}

/** Ruler steps, seconds: the first whose spacing is at least half a second's base width is used. */
private val STEPS = doubleArrayOf(1.0 / 30, 0.05, 1.0 / 12, 1.0 / 6, 0.25, 0.5, 1.0, 1.5, 2.5, 5.0, 10.0, 15.0, 30.0, 60.0, 90.0, 150.0, 300.0, 600.0, 900.0)

/** Where everything is drawn, for one frame of the timeline. */
private class Layout(
    val width: Float,
    val height: Float,
    val dp: Float,
    val playhead: Long,
    val pxPerUs: Float,
    val vScroll: Float,
    val rows: List<RowView>,
    /** The kinds of row a dock group has open; the others fold to thin lines. Null with no group open. */
    val open: Set<RowKind>? = null,
) {
    val rulerH = Track.RULER * dp
    val mainTop = (Track.RULER + Track.RULER_GAP) * dp
    val mainH = Track.MAIN * dp
    val rowH = Track.ROW * dp
    val gap = Track.GAP * dp
    val addH = Track.ADD_ROW * dp

    /** The hint rows shown until there is sound, and until there are titles; none in a group. */
    val showAddAudio = open == null && rows.firstOrNull()?.clips?.isNotEmpty() == true && rows.none { it.kind == RowKind.AUDIO }
    val showAddText = open == null && rows.firstOrNull()?.clips?.isNotEmpty() == true && rows.none { it.kind == RowKind.TEXT }

    /** A folded row's line. */
    val foldH = 1f * dp

    /** Whether row [index] is folded to a line, the group open being of another kind. */
    fun folded(index: Int): Boolean = open != null && index > 0 && rows.getOrNull(index)?.kind?.let { it !in open } == true

    /** A folded row that sits as a line over the main track: every kind but sound, as the reference stacks them. */
    private fun foldedAbove(index: Int): Boolean = folded(index) && rows.getOrNull(index)?.kind != RowKind.AUDIO

    /** Each row's top before scrolling, and past the last the top of what follows the rows. */
    private val tops = FloatArray(maxOf(rows.size + 1, 2)).also { t ->
        var y = mainTop + mainH
        var above = 0
        var below = 0
        var lastFolded = false
        for (i in 1..rows.size) {
            if (i < rows.size && foldedAbove(i)) {
                // stacked upwards from just over the main track
                above++
                t[i] = mainTop - above * (foldH + 4 * dp)
                continue
            }
            val f = i < rows.size && folded(i)
            val before = when {
                i == rows.size -> if (below == 0) Track.FIRST_GAP * dp else gap
                below == 0 -> if (f) 8 * dp else Track.FIRST_GAP * dp
                f || lastFolded -> 5 * dp
                else -> gap
            }
            t[i] = y + before
            if (i < rows.size) {
                y = t[i] + rowHeight(i)
                below++
                lastFolded = f
            }
        }
    }
    private val adds = (if (showAddAudio) 1 else 0) + (if (showAddText) 1 else 0)

    fun x(t: Long): Float = width / 2f + (t - playhead) * pxPerUs
    fun time(x: Float): Long = playhead + ((x - width / 2f) / pxPerUs).toLong()

    /** The tops of the rows under the main track: the rows first, then the add rows under them. */
    fun rowTop(index: Int): Float =
        if (index == 0) mainTop - vScroll else tops[index.coerceIn(1, rows.size.coerceAtLeast(1))] - vScroll

    private fun hintTop(slot: Int): Float = rowTop(max(1, rows.size)) + slot * (addH + gap)

    fun rowHeight(index: Int): Float = when {
        index == 0 -> mainH
        folded(index) -> foldH
        else -> rowH
    }

    /** How far the rows can scroll up. */
    val maxScroll: Float
        get() = max(0f, hintTop(adds) + vScroll + 24 * dp - height)

    fun clipRect(rowIndex: Int, clip: ClipView): Rect {
        val top = rowTop(rowIndex)
        val left = x(clip.startUs)
        val right = x(clip.startUs + clip.durationUs)
        return Rect(left, top, right, top + rowHeight(rowIndex))
    }

    /** The clip at a point, with its row index: the one drawn on top where clips of a row overlap. */
    fun hit(at: Offset): Pair<Int, ClipView>? {
        rows.forEachIndexed { i, row ->
            if (folded(i)) return@forEachIndexed
            row.clips.lastOrNull { c -> clipRect(i, c).contains(at) }?.let { return i to it }
        }
        return null
    }

    /** The add button, pinned at the right of the main row. */
    val addButton: Rect
        get() {
            val s = 36 * dp
            val top = mainTop - vScroll + (mainH - s) / 2
            return Rect(width - s - 12 * dp, top, width - 12 * dp, top + s)
        }

    /** The cover tile, just before time 0. */
    val coverTile: Rect
        get() {
            val right = x(0) - (Track.HANDLE + 10) * dp
            return Rect(right - mainH, mainTop - vScroll, right, mainTop - vScroll + mainH)
        }

    /** The mute-sound tile, before the cover. */
    val muteTile: Rect
        get() {
            val c = coverTile
            return Rect(c.left - 14 * dp - 40 * dp, c.top, c.left - 14 * dp, c.bottom)
        }

    /**
     * The transition buttons at the main track's cuts: the clip after each cut. A selected clip's
     * handles cover the cuts at its ends, so those have none.
     */
    fun transitionButtons(selected: String?): List<Pair<Rect, ClipView>> {
        val main = rows.firstOrNull() ?: return emptyList()
        val s = 20 * dp
        val cy = mainTop - vScroll + mainH / 2
        return main.clips.zipWithNext().filter { (prev, next) -> prev.id != selected && next.id != selected }.map { (_, next) ->
            val cx = x(next.startUs)
            Rect(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2) to next
        }
    }

    /** The "add audio" hint row, when there is no sound yet: from the start to the right edge. */
    val addAudioRow: Rect?
        get() {
            if (!showAddAudio) return null
            val top = hintTop(0)
            return Rect(x(0), top, width, top + addH)
        }

    /** The "add text" hint row, when there are no titles yet. */
    val addTextRow: Rect?
        get() {
            if (!showAddText) return null
            val top = hintTop(if (showAddAudio) 1 else 0)
            return Rect(x(0), top, width, top + addH)
        }

    /** The small icon tile at the left of a hint row, under the cover. */
    fun hintTile(row: Rect): Rect {
        val right = coverTile.right
        return Rect(right - 29 * dp, row.top, right, row.bottom)
    }
}

@Composable
fun Timeline(vm: EditorViewModel, modifier: Modifier) {
    val density = LocalDensity.current
    val dp = density.density
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val measurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    var zoom by remember { mutableFloatStateOf(1f) }
    var vScroll by remember { mutableFloatStateOf(0f) }
    /** A trim being dragged: clip id, head or tail, the offset so far in µs. */
    var trim by remember { mutableStateOf<Triple<String, Boolean, Long>?>(null) }
    var fling by remember { mutableStateOf<Job?>(null) }
    /** A clip picked up by a long press. */
    var drag by remember { mutableStateOf<Drag?>(null) }
    val haptics = LocalHapticFeedback.current
    val thumbsVersion = Thumbs.version.intValue

    val mainPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        if (uris.isNotEmpty()) vm.addToMain(uris)
    }

    val plus = rememberVectorPainter(LucideIcons.Plus)
    val volumeOn = rememberVectorPainter(LucideIcons.Volume2)
    val volumeOff = rememberVectorPainter(LucideIcons.VolumeX)
    val textIcon = rememberVectorPainter(LucideIcons.Type)
    val effectIcon = rememberVectorPainter(LucideIcons.Sparkles)
    val filterIcon = rememberVectorPainter(LucideIcons.Blend)
    val musicIcon = rememberVectorPainter(LucideIcons.Music)
    val pencilIcon = rememberVectorPainter(LucideIcons.Pencil)
    // painters keep one drawing at one size: the hint rows get their own
    val hintPlus = rememberVectorPainter(LucideIcons.Plus)
    val hintNote = rememberVectorPainter(LucideIcons.Music2)
    val hintText = rememberVectorPainter(LucideIcons.Type)
    val hintOverlay = rememberVectorPainter(LucideIcons.PictureInPicture2)
    val hintEffect = rememberVectorPainter(LucideIcons.Star)
    val hintFilter = rememberVectorPainter(LucideIcons.Blend)

    val timeline = vm.timeline
    // with nothing open, one row per kind; a group or a selection opens its kind's lanes
    val resting = openKinds(vm) == null
    val rows = remember(timeline, resting) {
        timeline?.rows?.let(::orderedRows)?.let { if (resting) mergedRows(it) else it }.orEmpty()
    }
    // a gesture reads the rows as they are now: a press that selects a clip opens its kind mid-gesture
    val currentRows = rememberUpdatedState(rows)

    Box(modifier) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(vm) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fling?.cancel()
                        val pxPerUs0 = Track.DP_PER_SECOND * dp * zoom / 1_000_000f
                        val rows = currentRows.value
                        val layout0 = Layout(size.width.toFloat(), size.height.toFloat(), dp, vm.playhead, pxPerUs0, vScroll, rows, openKinds(vm))
                        // A handle of the selected clip?
                        val sel = vm.selected?.let { id ->
                            rows.indexOfFirst { r -> r.clips.any { it.id == id } }.takeIf { it >= 0 }?.let { i ->
                                i to rows[i].clips.first { it.id == id }
                            }
                        }
                        val handle = sel?.let { (i, c) ->
                            val r = layout0.clipRect(i, c)
                            val h = Track.HANDLE * dp
                            when {
                                Rect(r.left - h - 6 * dp, r.top, r.left + 6 * dp, r.bottom).contains(down.position) -> c.id to true
                                Rect(r.right - 6 * dp, r.top, r.right + h + 6 * dp, r.bottom).contains(down.position) -> c.id to false
                                else -> null
                            }
                        }
                        val under = if (handle == null) layout0.hit(down.position) else null
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        // 0 pending, 1 scrub, 2 pinch, 3 trim, 4 vertical, 5 drag a clip
                        var mode = if (handle != null) 3 else 0
                        var moved = Offset.Zero
                        var startSpan = 0f
                        var startZoom = zoom
                        val startPlayhead = vm.playhead
                        var trimDelta = 0L
                        var snapped: Long? = null
                        var lifted = false
                        // where the finger is across: an edge held against the side of the screen runs the timeline on
                        var fingerX = down.position.x
                        /** The trimmed edge drawn [trimDelta] from where it was, or on what it snaps to within [threshold]. */
                        fun showTrim(threshold: Long) {
                            val (id, head) = handle!!
                            val clip = vm.clip(id)
                            if (clip != null) trimDelta = trimDelta.coerceIn(trimRange(clip, head))
                            var shown = trimDelta
                            if (clip != null) {
                                val origin = if (head) clip.startUs else clip.startUs + clip.durationUs
                                val target = snapTarget(origin + trimDelta, snapTargets(currentRows.value, id, vm.playhead) + vm.allBeatTimes(), threshold)
                                if (target != null) shown = (target - origin).coerceIn(trimRange(clip, head))
                                if (target != snapped && target != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                snapped = target
                            }
                            trim = Triple(id, head, shown)
                        }
                        // A press held on a clip without moving picks it up.
                        if (under != null) {
                            val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var outcome = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.first()
                                    if (!c.pressed) { lifted = true; break }
                                    if (e.changes.count { it.pressed } > 1) break
                                    moved += c.positionChange()
                                    tracker.addPosition(c.uptimeMillis, c.position)
                                    if (abs(moved.x) > touchSlop || abs(moved.y) > touchSlop) break
                                }
                                outcome
                            } == null
                            if (held) {
                                mode = 5
                                if (vm.playing) vm.stop()
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                vm.select(under.second.id)
                                // selected, its kind opens with its lanes apart: the drag goes on from its own lane's row
                                val opened = vm.timeline?.rows?.let(::orderedRows).orEmpty()
                                val lane = opened.indexOfFirst { r -> r.clips.any { it.id == under.second.id } }
                                drag = Drag(under.second.id, if (lane >= 0) lane else under.first, 0f, 0f)
                            } else if (abs(moved.x) > touchSlop || abs(moved.y) > touchSlop) {
                                mode = if (abs(moved.y) > abs(moved.x) * 1.2f && layout0.maxScroll > 0f) 4 else 1
                                if (mode == 1 && vm.playing) vm.stop()
                            }
                        }
                        while (!lifted) {
                            // An edge dragged to the side of the screen would stop there with the timeline: held
                            // within this of either side, the timeline scrolls on under it and the edge goes with it,
                            // so an image is drawn out to a long sound's end in one drag.
                            val edgeZone = 40 * dp
                            val atSide = mode == 3 && (fingerX < edgeZone || fingerX > size.width - edgeZone)
                            val event = if (atSide) withTimeoutOrNull(16L) { awaitPointerEvent() } else awaitPointerEvent()
                            if (event == null) {
                                val pxPerUs = Track.DP_PER_SECOND * dp * zoom / 1_000_000f
                                // faster the nearer the side, up to about a screen's width a second
                                val depth = if (fingerX < edgeZone) (edgeZone - fingerX) / edgeZone else (fingerX - (size.width - edgeZone)) / edgeZone
                                val stepPx = (4 + 12 * depth.coerceIn(0f, 1f)) * dp * (if (fingerX < edgeZone) -1 else 1)
                                // no further than the edge may go: at its limit the timeline stops too
                                val (id, head) = handle!!
                                val step = vm.clip(id)?.let { clip ->
                                    (trimDelta + (stepPx / pxPerUs).toLong()).coerceIn(trimRange(clip, head)) - trimDelta
                                } ?: 0L
                                if (step != 0L) {
                                    val before = vm.playhead
                                    vm.scrub(before + step)
                                    trimDelta += vm.playhead - before
                                    showTrim((8 * dp / pxPerUs).toLong())
                                }
                                continue
                            }
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2 && mode != 3 && mode != 5) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val span = hypot(a.x - b.x, a.y - b.y)
                                if (mode != 2) {
                                    mode = 2
                                    startSpan = span
                                    startZoom = zoom
                                    if (vm.playing) vm.stop()
                                } else if (startSpan > 0f) {
                                    zoom = (startZoom * span / startSpan).coerceIn(Track.MIN_ZOOM, Track.MAX_ZOOM)
                                }
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            val change = event.changes.first()
                            val delta = change.positionChange()
                            fingerX = change.position.x
                            moved += delta
                            tracker.addPosition(change.uptimeMillis, change.position)
                            val pxPerUs = Track.DP_PER_SECOND * dp * zoom / 1_000_000f
                            val threshold = (8 * dp / pxPerUs).toLong()
                            when (mode) {
                                0 -> if (abs(moved.x) > touchSlop || abs(moved.y) > touchSlop) {
                                    mode = if (abs(moved.y) > abs(moved.x) * 1.2f && layout0.maxScroll > 0f) 4 else 1
                                    if (mode == 1 && vm.playing) vm.stop()
                                }
                                1 -> vm.scrub(vm.playhead - (delta.x / pxPerUs).toLong())
                                3 -> {
                                    trimDelta += (delta.x / pxPerUs).toLong()
                                    showTrim(threshold)
                                }
                                4 -> {
                                    val l = Layout(size.width.toFloat(), size.height.toFloat(), dp, vm.playhead, pxPerUs, vScroll, currentRows.value, openKinds(vm))
                                    vScroll = (vScroll - delta.y).coerceIn(0f, l.maxScroll)
                                }
                                5 -> {
                                    val d = drag
                                    if (d != null) {
                                        val raw = d.dx + delta.x
                                        var shownDx = raw
                                        val clip = vm.clip(d.clipId)
                                        if (clip != null && d.row != 0) {
                                            val start = clip.startUs + (raw / pxPerUs).toLong()
                                            val end = start + clip.durationUs
                                            val targets = snapTargets(currentRows.value, clip.id, vm.playhead) + vm.allBeatTimes()
                                            val shift = snapTarget(start, targets, threshold)?.let { it - start }
                                                ?: snapTarget(end, targets, threshold)?.let { it - end }
                                            if (shift != null) shownDx = raw + shift * pxPerUs
                                            val now = shift?.let { start + it }
                                            if (now != snapped && now != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            snapped = now
                                        }
                                        drag = d.copy(dx = raw, dy = d.dy + delta.y, shownDx = shownDx)
                                    }
                                }
                            }
                            if (mode != 0) change.consume()
                        }
                        when (mode) {
                            0 -> onTap(vm, layout0, down.position, startPlayhead, mainPicker::launch)
                            1 -> {
                                val v = tracker.calculateVelocity().x
                                val pxPerUs = Track.DP_PER_SECOND * dp * zoom / 1_000_000f
                                if (abs(v) > 300f) {
                                    fling = scope.launch {
                                        var last = 0f
                                        AnimationState(0f, -v).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                                            val d = value - last
                                            last = value
                                            vm.scrub(vm.playhead + (d / pxPerUs).toLong())
                                        }
                                    }
                                }
                            }
                            3 -> {
                                val shown = trim?.third ?: trimDelta
                                trim = null
                                if (shown != 0L) vm.edit { trim(handle!!.first, handle.second, shown) }
                            }
                            5 -> {
                                val d = drag
                                drag = null
                                if (d != null) dropClip(vm, currentRows.value, d, Track.DP_PER_SECOND * dp * zoom / 1_000_000f, dp)
                            }
                        }
                    }
                },
        ) {
            @Suppress("UNUSED_EXPRESSION") thumbsVersion
            val pxPerUs = Track.DP_PER_SECOND * dp * zoom / 1_000_000f
            val layout = Layout(size.width, size.height, dp, vm.playhead, pxPerUs, vScroll.coerceAtMost(Float.MAX_VALUE), rows, openKinds(vm))
            drawRect(Palette.Timeline)
            drawRuler(layout, measurer)
            // the time, at the left of the ruler band
            val now = measurer.measure(clock(vm.playhead), TextStyle(color = Palette.Text, fontSize = 12.sp))
            val total = measurer.measure(" / " + clock(vm.timeline?.durationUs ?: 0), TextStyle(color = Palette.TextSecondary, fontSize = 12.sp))
            val ty = (layout.rulerH - now.size.height) / 2f + 2 * dp
            drawRect(Palette.Timeline, Offset.Zero, Size(16 * dp + now.size.width + total.size.width + 8 * dp, layout.rulerH + 4 * dp))
            drawText(now, topLeft = Offset(16 * dp, ty))
            drawText(total, topLeft = Offset(16 * dp + now.size.width, ty))
            clipRect(top = layout.rulerH + 2 * dp) {
                // at rest, a band behind each row of sound, titles and effects, from the start on
                if (layout.open == null) {
                    rows.forEachIndexed { index, row ->
                        if (index == 0 || row.kind == RowKind.OVERLAY) return@forEachIndexed
                        val top = layout.rowTop(index)
                        drawRect(Palette.HintRow, Offset(layout.x(0), top), Size(size.width - layout.x(0), layout.rowHeight(index)))
                    }
                }
                rows.forEachIndexed { index, row ->
                    // the selected clip last, so its handles lie over its neighbours
                    row.clips.sortedBy { it.id == vm.selected }.forEach { clip ->
                        var r = layout.clipRect(index, clip)
                        val t = trim
                        if (t != null && t.first == clip.id) {
                            val dx = t.third * pxPerUs
                            r = if (t.second) r.copy(left = min(r.left + dx, r.right - 4 * dp)) else r.copy(right = max(r.right + dx, r.left + 4 * dp))
                        }
                        val d = drag
                        if (d != null && d.clipId == clip.id) return@forEach
                        if (r.right < 0 || r.left > size.width) return@forEach
                        if (layout.folded(index)) {
                            drawRect(foldColour(row.kind), Offset(r.left, r.top), Size(r.width, layout.foldH))
                            return@forEach
                        }
                        drawClip(vm, row.kind, clip, r, layout, measurer, textIcon, effectIcon, filterIcon, musicIcon)
                    }
                }
                // the clip being dragged, over the others
                drag?.let { d ->
                    val row = rows.getOrNull(d.row) ?: return@let
                    val clip = row.clips.firstOrNull { it.id == d.clipId } ?: return@let
                    val r = layout.clipRect(d.row, clip).translate(d.shownDx, if (d.row == 0) 0f else d.dy)
                    drawRoundRect(Color(0x55000000), r.topLeft + Offset(0f, 4 * dp), r.size, CornerRadius(4 * dp))
                    drawClip(vm, row.kind, clip, r, layout, measurer, textIcon, effectIcon, filterIcon, musicIcon)
                    if (d.row == 0) {
                        // where it will land among the main track's clips
                        val x = mainDropX(layout, row, clip, r.center.x)
                        drawLine(Palette.Accent, Offset(x, r.top - 4 * dp), Offset(x, r.bottom + 4 * dp), 3 * dp)
                    }
                }
                // transitions between main-track clips
                layout.transitionButtons(vm.selected).forEach { (r, next) ->
                    if (r.right < 0 || r.left > size.width) return@forEach
                    val has = next.transitionId.isNotEmpty()
                    drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(4 * dp))
                    if (has) {
                        // a transition: two wedges, one from each side, meeting in the middle
                        val hw = 4.5f * dp
                        val hh = 4f * dp
                        val c = r.center
                        val bow = Path().apply {
                            moveTo(c.x - hw, c.y - hh); lineTo(c.x, c.y); lineTo(c.x - hw, c.y + hh); close()
                            moveTo(c.x + hw, c.y - hh); lineTo(c.x, c.y); lineTo(c.x + hw, c.y + hh); close()
                        }
                        drawPath(bow, Color(0xFF333333), style = Stroke(1.6f * dp, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    } else {
                        drawRect(Color(0xFF1C1C1C), Offset(r.center.x - 1 * dp, r.center.y - 4.5f * dp), Size(2 * dp, 9 * dp))
                    }
                }
                drawLeadingTiles(vm, layout, measurer, volumeOn, volumeOff, pencilIcon)
                // the first row of each kind keeps its kind's tile at the left
                layout.rows.forEachIndexed { i, row ->
                    val icon = when (row.kind) {
                        RowKind.AUDIO -> hintNote
                        RowKind.TEXT -> hintText
                        RowKind.OVERLAY -> hintOverlay
                        RowKind.EFFECT -> hintEffect
                        RowKind.FILTER -> hintFilter
                        else -> null
                    }
                    if (i == 0 || icon == null || layout.folded(i) || layout.rows.indexOfFirst { it.kind == row.kind } != i) return@forEachIndexed
                    val top = layout.rowTop(i)
                    val tile = layout.hintTile(Rect(0f, top, 0f, top + layout.rowHeight(i)))
                    if (tile.right > 0) {
                        drawRoundRect(Palette.HintRow, tile.topLeft, tile.size, CornerRadius(3 * dp))
                        val t = 16 * dp
                        translate(tile.left + (tile.width - t) / 2, tile.top + (tile.height - t) / 2) {
                            with(icon) { draw(Size(t, t), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.HintIcon)) }
                        }
                    }
                }
                listOfNotNull(layout.addAudioRow?.let { it to "Add audio" }, layout.addTextRow?.let { it to "Add text" }).forEach { (r, text) ->
                    drawRect(Palette.HintRow, r.topLeft, r.size)
                    val s = 14 * dp
                    translate(r.left + 7 * dp, r.top + (r.height - s) / 2) {
                        with(hintPlus) { draw(Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.TextSecondary)) }
                    }
                    val label = measurer.measure(text, TextStyle(color = Palette.TextSecondary, fontSize = 12.sp))
                    drawText(label, topLeft = Offset(r.left + 7 * dp + s + 5 * dp, r.top + (r.height - label.size.height) / 2))
                    val tile = layout.hintTile(r)
                    if (tile.right > 0) {
                        drawRoundRect(Palette.HintRow, tile.topLeft, tile.size, CornerRadius(3 * dp))
                        val icon = if (text == "Add audio") hintNote else hintText
                        val t = 16 * dp
                        translate(tile.left + (tile.width - t) / 2, tile.top + (tile.height - t) / 2) {
                            with(icon) { draw(Size(t, t), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.HintIcon)) }
                        }
                    }
                }
                // the add button stays at the right
                val a = layout.addButton
                drawRoundRect(Color.White, a.topLeft, a.size, CornerRadius(6 * dp))
                translate(a.left + 7 * dp, a.top + 7 * dp) {
                    with(plus) { draw(Size(a.width - 14 * dp, a.height - 14 * dp), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)) }
                }
            }
            // the playhead, fixed at the centre
            val cx = size.width / 2
            drawLine(Color.White, Offset(cx, layout.rulerH + 5 * dp), Offset(cx, size.height), strokeWidth = 2 * dp)
        }
    }
}

/** A clip being dragged: where it was picked up, how far it has moved, and how far it is drawn moved (snapped). */
private data class Drag(val clipId: String, val row: Int, val dx: Float, val dy: Float, val shownDx: Float = dx)

/**
 * How far a trimmed edge may go from where it was: never past the clip's other end (a tenth of a
 * second short of it), and for a file's clip never past the file's ends; a still or a title stretches
 * as far as it is drawn.
 */
private fun trimRange(clip: ClipView, head: Boolean): LongRange {
    val file = clip.kind == "video" || clip.kind == "audio"
    val speed = clip.speed.takeIf { it > 0 } ?: 1.0
    val least = 100_000L
    return if (head) {
        val earliest = if (file) -(clip.sourceStartUs / speed).toLong() else -clip.startUs
        earliest..(clip.durationUs - least).coerceAtLeast(earliest)
    } else {
        val latest = if (file && clip.mediaDurationUs > 0) {
            ((clip.mediaDurationUs - clip.sourceStartUs) / speed).toLong() - clip.durationUs
        } else {
            Long.MAX_VALUE / 4
        }
        (least - clip.durationUs).coerceAtMost(latest)..latest
    }
}

/** Times a moving edge snaps to: the playhead and every other clip's ends. */
private fun snapTargets(rows: List<RowView>, except: String, playhead: Long): List<Long> =
    listOf(playhead) + rows.flatMap { r -> r.clips.filter { it.id != except }.flatMap { listOf(it.startUs, it.startUs + it.durationUs) } }

/** The target nearest [time] within [threshold], or null. */
private fun snapTarget(time: Long, targets: List<Long>, threshold: Long): Long? =
    targets.minByOrNull { abs(it - time) }?.takeIf { abs(it - time) <= threshold }

/** Where a main-track clip dragged to [centerX] lands: the cut before the clip whose middle is past it. */
private fun mainDropX(layout: Layout, row: RowView, moving: ClipView, centerX: Float): Float {
    val others = row.clips.filter { it.id != moving.id }
    var t = 0L
    for (c in others) {
        val mid = layout.x(t + c.durationUs / 2)
        if (centerX < mid) return layout.x(t)
        t += c.durationUs
    }
    return layout.x(t)
}

/** Lets go of a dragged clip: main-track clips change place in the order; others move in time and between rows of their kind. */
private fun dropClip(vm: EditorViewModel, rows: List<RowView>, d: Drag, pxPerUs: Float, dp: Float) {
    val row = rows.getOrNull(d.row) ?: return
    val clip = row.clips.firstOrNull { it.id == d.clipId } ?: return
    if (d.row == 0) {
        val others = row.clips.filter { it.id != clip.id }
        val centre = clip.startUs + clip.durationUs / 2 + (d.shownDx / pxPerUs).toLong()
        var t = 0L
        var index = others.size
        for ((i, c) in others.withIndex()) {
            if (centre < t + c.durationUs / 2) { index = i; break }
            t += c.durationUs
        }
        val from = row.clips.indexOfFirst { it.id == clip.id }
        if (index != from) vm.edit { reorderMain(clip.id, index.toUInt()) }
        return
    }
    // rows of the same kind the finger passed over
    val step = (Track.ROW + Track.GAP) * dp
    val target = (d.row + (d.dy / step).roundToInt()).coerceIn(1, rows.size - 1)
    val trackId = rows.getOrNull(target)?.takeIf { it.kind == row.kind }?.trackId ?: ""
    val start = (clip.startUs + (d.shownDx / pxPerUs).toLong()).coerceAtLeast(0)
    if (start != clip.startUs || (trackId.isNotEmpty() && trackId != row.trackId)) {
        vm.edit(select = { clip.id }) { moveClip(clip.id, trackId, start) }
    }
}

private fun onTap(vm: EditorViewModel, layout: Layout, at: Offset, playhead: Long, pickMain: (PickVisualMediaRequest) -> Unit) {
    // choosing several: a tap on a clip adds it or takes it away, and nothing else happens
    vm.chosen?.let { set ->
        val id = layout.hit(at)?.second?.id ?: return
        vm.chosen = if (id in set) set - id else set + id
        return
    }
    if (layout.addButton.contains(at)) {
        pickMain(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
        return
    }
    layout.transitionButtons(vm.selected).firstOrNull { it.first.inflate(6 * layout.dp).contains(at) }?.let { (_, next) ->
        vm.select(null)
        vm.panel = Panel.Transition(next.id)
        return
    }
    if (layout.muteTile.contains(at)) {
        vm.toggleMainSound()
        return
    }
    if (layout.coverTile.contains(at)) {
        vm.select(null)
        vm.editingCover = true
        return
    }
    layout.addAudioRow?.let { r ->
        if (r.contains(at) || layout.hintTile(r).contains(at)) { vm.select(null); vm.dock = listOf(DockLevel.Root, DockLevel.Group("audio")); return }
    }
    layout.addTextRow?.let { r ->
        if (r.contains(at) || layout.hintTile(r).contains(at)) { vm.select(null); vm.panel = Panel.Text(null); return }
    }
    val hit = layout.hit(at)
    if (hit == null) {
        vm.select(null)
        return
    }
    val clip = hit.second
    vm.select(if (vm.selected == clip.id) null else clip.id)
    @Suppress("UNUSED_VARIABLE") val unused = playhead
}

private fun DrawScope.drawRuler(layout: Layout, measurer: androidx.compose.ui.text.TextMeasurer) {
    val dp = layout.dp
    val secondPx = layout.pxPerUs * 1_000_000f
    val half = Track.DP_PER_SECOND * dp / 2
    val step = STEPS.firstOrNull { it * secondPx > half * 1.01f } ?: STEPS.last()
    val stepUs = (step * 1_000_000).toLong().coerceAtLeast(1)
    val first = floor(layout.time(-50f).toDouble() / stepUs).toLong().coerceAtLeast(0)
    val last = ceil(layout.time(layout.width + 50f).toDouble() / stepUs).toLong()
    val style = TextStyle(color = Palette.TextSecondary, fontSize = 11.sp)
    for (i in first..last) {
        val t = i * stepUs
        val x = layout.x(t)
        if (i % 2 == 0L) {
            val label = if (step < 1.0) {
                val frames = ((t % 1_000_000) * 30 / 1_000_000)
                if (t % 1_000_000 == 0L) mmss(t) else "${frames}f"
            } else mmss(t)
            val m = measurer.measure(label, style)
            drawText(m, topLeft = Offset(x - m.size.width / 2f, (layout.rulerH - m.size.height) / 2f + 2 * dp))
        } else {
            drawCircle(Palette.TextSecondary, 1.2f * dp, Offset(x, layout.rulerH / 2f + 2 * dp))
        }
    }
}

private fun mmss(us: Long): String {
    val s = us / 1_000_000
    return "%02d:%02d".format(s / 60, s % 60)
}

private fun DrawScope.drawLeadingTiles(
    vm: EditorViewModel,
    layout: Layout,
    measurer: androidx.compose.ui.text.TextMeasurer,
    on: androidx.compose.ui.graphics.vector.VectorPainter,
    off: androidx.compose.ui.graphics.vector.VectorPainter,
    pencil: androidx.compose.ui.graphics.vector.VectorPainter,
) {
    val dp = layout.dp
    val main = layout.rows.firstOrNull() ?: return
    if (main.clips.isEmpty()) return
    val mute = layout.muteTile
    if (mute.right > 0) {
        val muted = main.muted
        val icon = if (muted) off else on
        val s = 20 * dp
        translate(mute.left + (mute.width - s) / 2, mute.top + 6 * dp) {
            with(icon) { draw(Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Palette.DockText)) }
        }
        val label = measurer.measure(
            if (muted) "Unmute\nclip" else "Mute\nclip",
            TextStyle(color = Palette.DockText, fontSize = 11.sp, lineHeight = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center),
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = mute.width.roundToInt().coerceAtLeast(1)),
        )
        drawText(label, topLeft = Offset(mute.left + (mute.width - label.size.width) / 2, mute.top + 28 * dp))
    }
    val cover = layout.coverTile
    if (cover.right > 0) {
        val first = main.clips.first()
        val pic = coverPicture(first, cover.height.roundToInt())
        drawRoundRect(Color(0xFF2A2A2A), cover.topLeft, cover.size, CornerRadius(10 * dp))
        clipRectRounded(cover, 10 * dp) {
            if (pic != null) drawCropped(pic, cover)
            drawRect(Color(0x33000000), cover.topLeft, cover.size)
        }
        val p = 18 * dp
        translate(cover.left + (cover.width - p) / 2, cover.top + 9 * dp) {
            with(pencil) { draw(Size(p, p), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)) }
        }
        val label = measurer.measure(
            "Cover",
            TextStyle(color = Color.White, fontSize = 11.sp, shadow = androidx.compose.ui.graphics.Shadow(Color(0x99000000), blurRadius = 4f)),
        )
        drawText(label, topLeft = Offset(cover.left + (cover.width - label.size.width) / 2, cover.top + 29 * dp))
    }
}

private fun coverPicture(clip: ClipView, height: Int): Bitmap? = when (clip.kind) {
    "video" -> Thumbs.still(clip.path, clip.sourceStartUs, height)
    "image" -> Thumbs.still(clip.path, 0, height)
    else -> null
}

private fun DrawScope.clipRectRounded(r: Rect, radius: Float, block: DrawScope.() -> Unit) {
    val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, CornerRadius(radius))) }
    clipPath(path) { block() }
}

/** Draws [b] filling [r], cropped to keep its shape. */
/** [b] whole inside [r], its shape kept, centred. */
private fun DrawScope.drawFitted(b: Bitmap, r: Rect) {
    val scale = min(r.width / b.width, r.height / b.height)
    val w = b.width * scale
    val h = b.height * scale
    drawImage(
        b.asImageBitmap(),
        dstOffset = IntOffset((r.left + (r.width - w) / 2).roundToInt(), (r.top + (r.height - h) / 2).roundToInt()),
        dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.drawCropped(b: Bitmap, r: Rect) {
    val img = b.asImageBitmap()
    val scale = max(r.width / b.width, r.height / b.height)
    val sw = r.width / scale
    val sh = r.height / scale
    val sx = ((b.width - sw) / 2).coerceAtLeast(0f)
    val sy = ((b.height - sh) / 2).coerceAtLeast(0f)
    drawImage(
        img,
        srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
        srcSize = IntSize(sw.roundToInt().coerceIn(1, b.width), sh.roundToInt().coerceIn(1, b.height)),
        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
        dstSize = IntSize(r.width.roundToInt().coerceAtLeast(1), r.height.roundToInt().coerceAtLeast(1)),
    )
}

private fun DrawScope.drawClip(
    vm: EditorViewModel,
    kind: RowKind,
    clip: ClipView,
    r: Rect,
    layout: Layout,
    measurer: androidx.compose.ui.text.TextMeasurer,
    textIcon: androidx.compose.ui.graphics.vector.VectorPainter,
    effectIcon: androidx.compose.ui.graphics.vector.VectorPainter,
    filterIcon: androidx.compose.ui.graphics.vector.VectorPainter,
    musicIcon: androidx.compose.ui.graphics.vector.VectorPainter,
) {
    val dp = layout.dp
    val selected = vm.selected == clip.id
    val inner = Rect(r.left + 1 * dp, r.top, r.right - 1 * dp, r.bottom)
    val radius = 4 * dp
    when (clip.kind) {
        "video", "image" -> if (kind == RowKind.TEXT) {
            // a sticker: gold, its picture small at the head, as the reference shows one
            drawRoundRect(Palette.StickerClip, inner.topLeft, inner.size, CornerRadius(radius))
            clipRectRounded(inner, radius) {
                val s = inner.height - 12 * dp
                Thumbs.still(clip.path, 0, 96)?.let { drawFitted(it, Rect(Offset(max(inner.left, 0f) + 6 * dp, inner.top + 6 * dp), Size(s, s))) }
            }
        } else {
            drawRoundRect(Palette.OverlayClip, inner.topLeft, inner.size, CornerRadius(radius))
            clipRectRounded(inner, radius) { drawFrames(clip, inner, layout) }
        }
        "audio" -> {
            drawRoundRect(Palette.AudioClip, inner.topLeft, inner.size, CornerRadius(radius))
            clipRectRounded(inner, radius) {
                drawWave(vm, clip, inner, layout)
                drawLabel(clip.name, musicIcon, inner, measurer, dp)
            }
            // fades: the silence shaded off, with the ramp drawn
            clipRectRounded(inner, radius) {
                if (clip.fadeInUs > 0) {
                    val x = layout.x(clip.startUs + clip.fadeInUs)
                    drawPath(Path().apply { moveTo(inner.left, inner.top); lineTo(x, inner.top); lineTo(inner.left, inner.bottom); close() }, Color(0x77000000))
                    drawLine(Color(0xCCFFFFFF), Offset(inner.left, inner.bottom), Offset(x, inner.top), 1.2f * dp)
                }
                if (clip.fadeOutUs > 0) {
                    val x = layout.x(clip.startUs + clip.durationUs - clip.fadeOutUs)
                    drawPath(Path().apply { moveTo(inner.right, inner.top); lineTo(x, inner.top); lineTo(inner.right, inner.bottom); close() }, Color(0x77000000))
                    drawLine(Color(0xCCFFFFFF), Offset(x, inner.top), Offset(inner.right, inner.bottom), 1.2f * dp)
                }
            }
            // beat marks along the bottom
            vm.beatTimes(clip).forEach { t -> drawCircle(Palette.Beat, 2.5f * dp, Offset(layout.x(t), inner.bottom - 5 * dp)) }
        }
        "text", "shape" -> {
            drawRoundRect(if (clip.id in vm.captions) Palette.CaptionClip else Palette.TextClip, inner.topLeft, inner.size, CornerRadius(radius))
            // a title is known by its words alone
            clipRectRounded(inner, radius) { drawLabel(clip.text.ifBlank { clip.name }.replace('\n', ' '), if (clip.kind == "text") null else textIcon, inner, measurer, dp) }
        }
        else -> {
            val filter = kind == RowKind.FILTER
            val fill = if (filter) Palette.FilterClip else Palette.EffectClip
            drawRoundRect(fill, inner.topLeft, inner.size, CornerRadius(radius))
            // what an effect treats, then its name, each on a chip, as the reference labels them
            clipRectRounded(inner, radius) {
                var x = max(inner.left, 0f) + 6 * dp
                // what it treats shows once its kind is open, as the reference keeps the resting row to names
                if (!filter && layout.open != null) {
                    val all = vm.rowOf(clip.id)?.allVideos == true
                    x = drawChip(if (all) "All videos" else "Main video", x, inner, Color(0xD9FFFFFF), fill, measurer, dp)
                }
                drawChip(clip.name, x, inner, Color(0x24000000), Color(0xE6FFFFFF), measurer, dp)
            }
        }
    }
    if (clip.speed != 1.0 && (clip.kind == "video" || clip.kind == "audio")) {
        val chip = measurer.measure("%.1fx".format(clip.speed), TextStyle(color = Color.White, fontSize = 9.sp))
        drawRoundRect(Color(0x99000000), Offset(inner.left + 3 * dp, inner.bottom - chip.size.height - 4 * dp), Size(chip.size.width + 6f * dp, chip.size.height + 2f * dp), CornerRadius(3 * dp))
        drawText(chip, topLeft = Offset(inner.left + 6 * dp, inner.bottom - chip.size.height - 3 * dp))
    }
    // one of several chosen together: outlined, with no handles to trim it by
    if (vm.chosen?.contains(clip.id) == true) {
        drawRoundRect(Color.White, Offset(r.left, r.top), r.size, CornerRadius(radius), style = Stroke(1.5f * dp))
    }
    if (selected) {
        val h = Track.HANDLE * dp
        drawRoundRect(Color.White, Offset(r.left, r.top), r.size, CornerRadius(radius), style = Stroke(2 * dp))
        // handles outside the clip, with a grip line
        drawRoundRect(Color.White, Offset(r.left - h, r.top), Size(h, r.height), CornerRadius(radius))
        drawRoundRect(Color.White, Offset(r.right, r.top), Size(h, r.height), CornerRadius(radius))
        drawLine(Color.Black, Offset(r.left - h / 2, r.top + r.height * 0.32f), Offset(r.left - h / 2, r.bottom - r.height * 0.32f), 2 * dp)
        drawLine(Color.Black, Offset(r.right + h / 2, r.top + r.height * 0.32f), Offset(r.right + h / 2, r.bottom - r.height * 0.32f), 2 * dp)
        // a main-track clip shows its length; the thinner rows keep their label clear
        if (kind == RowKind.MAIN) {
            val label = measurer.measure(seconds1(clip.durationUs), TextStyle(color = Color.White, fontSize = 9.sp))
            drawRoundRect(Color(0xAA000000), Offset(r.left + 4 * dp, r.top + 3 * dp), Size(label.size.width + 8f * dp, label.size.height + 2f * dp), CornerRadius(3 * dp))
            drawText(label, topLeft = Offset(r.left + 8 * dp, r.top + 4 * dp))
        }
        // keyframes on the selected clip
        clip.keysUs.forEach { k ->
            val kx = layout.x(clip.startUs + k)
            val cy = r.bottom - 6 * dp
            val d = 4 * dp
            val p = Path().apply { moveTo(kx, cy - d); lineTo(kx + d, cy); lineTo(kx, cy + d); lineTo(kx - d, cy); close() }
            drawPath(p, Color.White)
        }
    }
}

/** [text] on a chip of [fill] inside [r] from [x], in [ink]; returns where a next chip may start. */
private fun DrawScope.drawChip(
    text: String,
    x: Float,
    r: Rect,
    fill: Color,
    ink: Color,
    measurer: androidx.compose.ui.text.TextMeasurer,
    dp: Float,
): Float {
    val room = (r.right - x - 14 * dp).roundToInt()
    if (room <= 10 * dp) return r.right
    val m = measurer.measure(
        text,
        TextStyle(color = ink, fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = room),
    )
    val h = m.size.height + 2 * dp
    val top = r.top + (r.height - h) / 2
    drawRoundRect(fill, Offset(x, top), Size(m.size.width + 8 * dp, h), CornerRadius(3 * dp))
    drawText(m, topLeft = Offset(x + 4 * dp, top + 1 * dp))
    return x + m.size.width + 12 * dp
}

private fun DrawScope.drawLabel(
    text: String,
    icon: androidx.compose.ui.graphics.vector.VectorPainter?,
    r: Rect,
    measurer: androidx.compose.ui.text.TextMeasurer,
    dp: Float,
) {
    val s = if (icon == null) -2 * dp else 13 * dp
    val left = max(r.left, 0f) + 6 * dp
    if (icon != null) {
        translate(left, r.top + (r.height - s) / 2) {
            with(icon) { draw(Size(s, s), colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White)) }
        }
    }
    val room = (r.right - left - s - 10 * dp).roundToInt()
    if (room <= 8) return
    val m = measurer.measure(
        text,
        TextStyle(color = Color.White, fontSize = 11.sp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        constraints = androidx.compose.ui.unit.Constraints(maxWidth = room),
    )
    drawText(m, topLeft = Offset(left + s + 4 * dp, r.top + (r.height - m.size.height) / 2))
}

/** A clip's pictures: square tiles from a filmstrip of its stretch of the file. */
private fun DrawScope.drawFrames(clip: ClipView, r: Rect, layout: Layout) {
    val tile = r.height
    if (r.width <= 0f) return
    if (clip.kind == "image") {
        val pic = Thumbs.still(clip.path, 0, (tile * 1.2f).roundToInt()) ?: return
        var x = r.left
        while (x < r.right) {
            if (x + tile > 0 && x < layout.width) drawCropped(pic, Rect(x, r.top, x + tile, r.bottom))
            x += tile
        }
        return
    }
    val needed = ceil(r.width / tile).toInt().coerceAtLeast(1)
    // quantised so zooming does not ask for a new strip every frame
    val count = when {
        needed <= 4 -> needed
        needed <= 8 -> 8
        needed <= 16 -> 16
        needed <= 32 -> 32
        else -> 60
    }
    val from = clip.sourceStartUs
    val to = clip.sourceStartUs + (clip.durationUs * clip.speed).toLong()
    val strip = Thumbs.strip(clip.path, from, to, count, (tile * 0.8f).roundToInt().coerceAtLeast(16)) ?: return
    val frameW = strip.width.toFloat() / count
    val img = strip.asImageBitmap()
    for (i in 0 until needed) {
        val x = r.left + i * tile
        if (x + tile < 0 || x > layout.width) continue
        val src = min(count - 1, (i.toLong() * count / needed).toInt())
        val srcW = min(frameW, strip.height.toFloat())
        val srcX = src * frameW + (frameW - srcW) / 2
        drawImage(
            img,
            srcOffset = IntOffset(srcX.roundToInt(), 0),
            srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), strip.height),
            dstOffset = IntOffset(x.roundToInt(), r.top.roundToInt()),
            dstSize = IntSize(tile.roundToInt(), r.height.roundToInt()),
        )
    }
}

private fun DrawScope.drawWave(vm: EditorViewModel, clip: ClipView, r: Rect, layout: Layout) {
    val dp = layout.dp
    val bucketPx = 3 * dp
    val buckets = (r.width / bucketPx).roundToInt().coerceIn(8, 2048)
    val quant = 1 shl (32 - Integer.numberOfLeadingZeros(buckets - 1)).coerceIn(3, 11)
    val from = clip.sourceStartUs
    val to = clip.sourceStartUs + (clip.durationUs * clip.speed).toLong()
    val wave = Thumbs.wave(clip.path, vm.path, from, to, quant) ?: return
    val w = r.width / wave.size
    val mid = r.top + r.height / 2
    for (i in wave.indices) {
        val x = r.left + i * w
        if (x < -w || x > layout.width) continue
        val h = (wave[i] * (r.height - 6 * dp) * (clip.volume.toFloat().coerceIn(0f, 2f) / 2f + 0.5f)).coerceAtLeast(1 * dp)
        drawRect(Palette.AudioWave, Offset(x, mid - h / 2), Size(max(1f, w * 0.6f), h))
    }
}
