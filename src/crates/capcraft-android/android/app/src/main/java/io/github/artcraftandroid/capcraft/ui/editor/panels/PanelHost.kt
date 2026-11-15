package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorMetrics
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Panel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

/** The open tool panel, sliding up over the timeline and the bottom bar. */
@Composable
fun PanelHost(vm: EditorViewModel) {
    val panel = vm.panel
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(
            visible = panel != null,
            enter = slideInVertically(tween(200)) { it },
            exit = slideOutVertically(tween(160)) { it },
        ) {
            val p = remember(panel) { panel } ?: return@AnimatedVisibility
            when (p) {
                is Panel.Speed -> SpeedPanel(vm, p.curve)
                Panel.Volume -> VolumePanel(vm)
                Panel.Opacity -> OpacityPanel(vm)
                Panel.Fade -> FadePanel(vm)
                Panel.Ratio -> RatioPanel(vm)
                is Panel.Transition -> TransitionPanel(vm, p.clipId)
                is Panel.Packages -> PackagesPanel(vm, p.kind, p.target)
                Panel.Adjust -> AdjustPanel(vm)
                Panel.Quality -> LookPanel(vm, startTab = 2, target = Panel.Target.Clip)
                // the text sheet's sliders have solid thumbs, as the reference's do
                is Panel.Text -> androidx.compose.runtime.CompositionLocalProvider(LocalSolidThumbs provides true) { TextPanel(vm, p.clipId, p.tab) }
                Panel.Stickers -> StickersPanel(vm)
                Panel.Animation -> AnimationPanel(vm)
                Panel.Crop -> CropPanel(vm)
                is Panel.Background -> BackgroundPanel(vm, p.tab)
                Panel.Voiceover -> VoiceoverPanel(vm)
                Panel.SoundFx -> SoundFxPanel(vm)
                Panel.Transcript -> TranscriptPanel(vm)
                Panel.Draw -> DrawPanel(vm)
                Panel.Tracking -> TrackingPanel(vm)
                Panel.Enhance -> EnhancePanel(vm)
                Panel.Captions -> AutoCaptionsSheet(vm)
                Panel.Lyrics -> AutoCaptionsSheet(vm, lyrics = true)
                is Panel.Speech -> SpeechPanel(vm, captions = false, clipId = p.clipId)
                Panel.RemoveBg -> RemoveBgPanel(vm)
                Panel.Chroma -> ChromaPanel(vm)
                Panel.Blend -> BlendPanel(vm)
                Panel.Mask -> MaskPanel(vm)
                Panel.Basic -> BasicPanel(vm)
                Panel.CustomCutout -> CustomCutoutPanel(vm)
                Panel.ShapeStyle -> ShapeStylePanel(vm)
                Panel.CaptionTemplates -> CaptionTemplatesPanel(vm)
                Panel.Collage -> CollagePanel(vm)
                Panel.Relight -> RelightPanel(vm)
                Panel.Sounds -> SoundsPage(vm)
                Panel.CameraTracking -> CameraTrackingPanel(vm)
                Panel.MotionBlur -> MotionBlurPanel(vm)
                Panel.BodyEffects -> BodyEffectsPanel(vm)
                Panel.IsolateVoice -> IsolateVoicePanel(vm)
                Panel.AutoReframe -> AutoReframePanel(vm)
                Panel.Retouch -> RetouchPanel(vm)
                Panel.Graphs -> GraphsPanel(vm)
                Panel.Layer -> LayerPanel(vm)
                Panel.EffectObject -> EffectObjectPanel(vm)
                Panel.LayerAdjust -> LayerAdjustPanel(vm)
                Panel.EnterCaptions -> EnterCaptionsPage(vm)
                Panel.BatchEdit -> BatchEditPanel(vm)
                Panel.Beats -> BeatsPanel(vm)
                Panel.Stabilize -> StabilizePanel(vm)
            }
        }
    }
}

/**
 * A panel's frame: a sheet with rounded top corners, its title and a confirm tick in a bar (under
 * the content, or over it with [titleOnTop]). Compact by default, so the timeline stays in sight;
 * [tall] for shelves of choices, which cover the timeline up to the play bar; [towering] for a
 * library to browse, which also covers the lower part of the preview.
 */
@Composable
fun PanelFrame(
    title: String,
    vm: EditorViewModel,
    height: Dp = 230.dp,
    tall: Boolean = false,
    towering: Boolean = false,
    titleOnTop: Boolean = false,
    left: (@Composable () -> Unit)? = null,
    /** In place of the title: the panel's own tabs, in the bar beside the tick. */
    header: (@Composable () -> Unit)? = null,
    onDone: () -> Unit = { vm.panel = null },
    content: @Composable ColumnScope.() -> Unit,
) {
    val tallHeight = vm.timelineHeight + EditorMetrics.DockHeight
    val target = when {
        // the top of the preview stays in sight over the sheet
        towering -> maxOf(tallHeight, vm.areaHeight + EditorMetrics.DockHeight - 235.dp)
        tall -> tallHeight
        else -> height
    }
    val full = androidx.compose.animation.core.animateDpAsState(target, label = "sheet").value
    val bar: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().height(50.dp).padding(horizontal = if (header != null) 0.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (header != null) {
                Box(Modifier.weight(1f)) { header() }
            } else {
                Box(Modifier.weight(1f)) { left?.invoke() }
                Text(title, style = Type.BodyStrong.copy(fontSize = 16.sp, color = Palette.DockText))
            }
            Box(Modifier.then(if (header != null) Modifier.padding(end = 12.dp) else Modifier.weight(1f)), contentAlignment = Alignment.CenterEnd) {
                Box(Modifier.size(44.dp).clickable(onClick = onDone), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.Check, Palette.DockText, size = 26.dp, description = "Done")
                }
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(Palette.DockSelected)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (titleOnTop) {
            bar()
            Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Timeline))
            Column(Modifier.fillMaxWidth().height(full - 51.dp)) { content() }
        } else {
            // a hairline sets the title bar apart from what it closes
            Column(Modifier.fillMaxWidth().height(full - 50.5.dp)) { content() }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF3C3C3C)))
            bar()
        }
    }
}

/**
 * The speed ruler: ticks across the track, taller at the marked speeds, and a ring for a thumb with
 * the speed riding above it while it moves. [position] is 0..1 along the track.
 */
@Composable
private fun SpeedRuler(position: Float, onChange: (Float) -> Unit, onDone: () -> Unit, label: String, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf(false) }
    val marks = remember { listOf(0.1f, 1f, 2f, 5f, 10f, 16f).map(::speedToPos) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth().height(44.dp)) {
        val thumb = 20.dp
        val travel = maxWidth - thumb
        if (held) {
            Text(
                label,
                style = Type.Small.copy(color = Palette.Text, fontSize = 12.sp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(start = (travel * position - 10.dp).coerceAtLeast(0.dp)).width(40.dp),
            )
        }
        androidx.compose.foundation.Canvas(
            Modifier.fillMaxWidth().height(28.dp).align(Alignment.BottomStart)
                .pointerInput(Unit) {
                    val pad = thumb.toPx() / 2
                    val span = size.width - thumb.toPx()
                    fun at(x: Float) = ((x - pad) / span).coerceIn(0f, 1f)
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        held = true
                        onChange(at(down.position.x))
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.first()
                            if (!c.pressed) break
                            onChange(at(c.position.x))
                            c.consume()
                        }
                        held = false
                        onDone()
                    }
                },
        ) {
            val pad = thumb.toPx() / 2
            val span = size.width - thumb.toPx()
            val cy = size.height / 2
            val ticks = 36
            for (i in 0..ticks) {
                val f = i / ticks.toFloat()
                val major = marks.any { abs(it - f) < 0.5f / ticks }
                val h = if (major) 12.dp.toPx() else 6.dp.toPx()
                drawLine(
                    Color(if (major) 0xFFB0B0B0 else 0xFF6E6E6E),
                    Offset(pad + span * f, cy - h / 2),
                    Offset(pad + span * f, cy + h / 2),
                    1.dp.toPx(),
                )
            }
            val tx = pad + span * position
            drawCircle(Palette.DockSelected, thumb.toPx() / 2 - 1.dp.toPx(), Offset(tx, cy))
            drawCircle(Palette.Accent, thumb.toPx() / 2 - 1.5f.dp.toPx(), Offset(tx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f.dp.toPx()))
        }
    }
}

/** A labelled slider: a thin white track, a round thumb, and the value riding above the thumb. */
@Composable
fun ValueSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: (Float) -> String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    track: androidx.compose.ui.graphics.Brush? = null,
    filled: Boolean = LocalSolidThumbs.current,
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        title?.let {
            Text(it, style = Type.Small, modifier = Modifier.width(72.dp))
        }
        CapSlider(value, range, label, onChange, onDone, Modifier.weight(1f), track, filled)
    }
}

/** Whether the sliders inside have solid thumbs rather than rings; a sheet that wants them provides true. */
val LocalSolidThumbs = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * CapCraft's slider: a 2dp track, cyan where it is set, a ring for a thumb, and the value above it
 * while a finger is on it, fading a moment after it lifts.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun CapSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    label: ((Float) -> String)?,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    /** A track drawn whole in this, as the colour sliders show what they move through, instead of the filled line. */
    track: androidx.compose.ui.graphics.Brush? = null,
    /** A solid white thumb rather than a ring, as the reference's Adjust and text sliders have. */
    filled: Boolean = LocalSolidThumbs.current,
) {
    val span = (range.endInclusive - range.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - range.start) / span).coerceIn(0f, 1f)
    // a range either side of zero fills from zero, marked, as the reference's centred sliders do
    val centred = range.start < 0f && range.endInclusive > 0f
    val zero = if (centred) -range.start / span else 0f
    val touch = remember { MutableInteractionSource() }
    val pressed by touch.collectIsPressedAsState()
    val dragged by touch.collectIsDraggedAsState()
    val held = pressed || dragged
    val shown by androidx.compose.animation.core.animateFloatAsState(
        if (held) 1f else 0f,
        if (held) tween(80) else tween(400, delayMillis = 700),
        label = "slider-value",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
        val thumb = 18.dp
        val travel = maxWidth - thumb
        Column(Modifier.fillMaxWidth()) {
            if (label != null) {
                Box(Modifier.fillMaxWidth().height(20.dp)) {
                    Text(
                        label(value),
                        style = Type.Small.copy(color = Palette.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier
                            .padding(start = (travel * fraction + thumb / 2 - 16.dp).coerceAtLeast(0.dp))
                            .width(32.dp)
                            .graphicsLayer { alpha = shown },
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onChange,
                onValueChangeFinished = onDone,
                valueRange = range,
                interactionSource = touch,
                modifier = Modifier.fillMaxWidth().height(32.dp),
                thumb = {
                    Box(
                        Modifier
                            .size(thumb)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (filled) Color.White else Palette.DockSelected)
                            .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                    )
                },
                track = { _ ->
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                        val y = size.height / 2
                        if (track != null) {
                            drawLine(track, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
                            return@Canvas
                        }
                        drawLine(Color(0xFF4A4A4A), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), size.height, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(Palette.Accent, androidx.compose.ui.geometry.Offset(size.width * zero, y), androidx.compose.ui.geometry.Offset(size.width * fraction, y), size.height, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        if (centred) {
                            val x = size.width * zero
                            drawLine(Palette.Accent, androidx.compose.ui.geometry.Offset(x, y - 5.dp.toPx()), androidx.compose.ui.geometry.Offset(x, y + 5.dp.toPx()), 1.5.dp.toPx())
                        }
                    }
                },
            )
        }
    }
}

/** A row of text tabs, the selected one white and underlined. */
@Composable
fun Tabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        labels.forEachIndexed { i, label ->
            Column(
                Modifier.clickable { onSelect(i) }.padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    label,
                    style = Type.Body.copy(
                        color = if (i == selected) Palette.Text else Palette.TextSecondary,
                        fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(24.dp, 2.dp).background(if (i == selected) Palette.Accent else Color.Transparent))
            }
        }
    }
}

/** A shelf of choices four across, the way the reference lays out its libraries. */
@Composable
fun ChoiceGrid(count: Int, modifier: Modifier = Modifier, item: @Composable (Int) -> Unit) {
    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(4),
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(count) { item(it) }
    }
}

/** One square of a [ChoiceGrid]: the picture, ringed white when chosen, the name under it. */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun GridTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().then(
            if (onLongClick != null) {
                Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            } else {
                Modifier.clickable(onClick = onClick)
            },
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF333333))
                .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier),
            contentAlignment = Alignment.Center,
        ) { content() }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = Type.Small.copy(fontSize = 12.sp, color = if (selected) Palette.Text else Palette.TextSecondary),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** A square choice tile with a label under it. */
@Composable
fun ChoiceTile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    size: Dp = 60.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.width(size + 12.dp).clickable(onClick = onClick).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(Palette.Card)
                .then(if (selected) Modifier.background(Color(0x22FFFFFF)) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            content()
            if (selected) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp)).background(Color.Transparent)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRoundRect(
                            Color.White,
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Type.Tiny.copy(color = if (selected) Palette.Text else Palette.TextSecondary), maxLines = 1)
    }
}

// ── Speed ──

private data class Curve(val name: String, val points: List<Pair<Double, Double>>?)

private val CURVES = listOf(
    Curve("None", null),
    Curve("Custom", listOf(0.0 to 1.0, 0.25 to 1.0, 0.5 to 1.0, 0.75 to 1.0, 1.0 to 1.0)),
    Curve("Montage", listOf(0.0 to 1.5, 0.25 to 1.0, 0.5 to 0.4, 0.75 to 1.0, 1.0 to 1.5)),
    Curve("Hero", listOf(0.0 to 1.0, 0.35 to 1.0, 0.5 to 0.2, 0.65 to 1.0, 1.0 to 1.0)),
    Curve("Bullet", listOf(0.0 to 2.0, 0.4 to 0.25, 0.6 to 0.25, 1.0 to 2.0)),
    Curve("Jump cut", listOf(0.0 to 1.0, 0.45 to 1.0, 0.5 to 6.0, 0.55 to 1.0, 1.0 to 1.0)),
    Curve("Flash in", listOf(0.0 to 6.0, 0.3 to 1.0, 1.0 to 1.0)),
    Curve("Flash out", listOf(0.0 to 1.0, 0.7 to 1.0, 1.0 to 6.0)),
)

private const val MIN_SPEED = 0.1f
private const val MAX_SPEED = 16f

private fun speedToPos(s: Float) = ((ln(s) - ln(MIN_SPEED)) / (ln(MAX_SPEED) - ln(MIN_SPEED))).coerceIn(0f, 1f)
private fun posToSpeed(p: Float) = exp(ln(MIN_SPEED) + p * (ln(MAX_SPEED) - ln(MIN_SPEED)))

@Composable
private fun SpeedPanel(vm: EditorViewModel, curve: Boolean) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val tab = if (curve) 1 else 0
    // the pitch choice sits in the bar, at the left of the title, as the reference has it; a sound's
    // speed has it at the top right instead, with no durations
    val pitchFollows = remember(clip.json) { vm.clipJson(clip.id)?.optBoolean("preservePitch", true) == false }
    val sound = clip.kind == "audio"
    val pitch: @Composable () -> Unit = {
        Row(
            Modifier.clickable { vm.patch(clip.id, JSONObject().put("preservePitch", pitchFollows)) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .then(if (pitchFollows) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.TextSecondary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (pitchFollows) Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.OnAccent)) }
            Spacer(Modifier.width(8.dp))
            Text("Change audio pitch", style = Type.Small.copy(color = Palette.DockText, fontSize = 13.sp), maxLines = 1)
        }
    }
    PanelFrame(if (curve) "Curve" else "Speed", vm, height = if (sound) 200.dp else 230.dp, left = if (curve || !clip.audible || sound) null else pitch) {
        if (tab == 0) {
            var speed by remember(clip.id) { mutableFloatStateOf(clip.speed.toFloat()) }
            val sourceUs = (clip.durationUs * clip.speed).toLong()
            if (sound) {
                Row(Modifier.fillMaxWidth().padding(end = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.End) { pitch() }
                Spacer(Modifier.height(20.dp))
            } else {
                // the lengths before and after, heading the sheet
                Row(Modifier.padding(start = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Duration:  %.1fs".format(sourceUs / 1e6), style = Type.Small.copy(fontSize = 14.sp, color = Palette.TextSecondary))
                    Text("  →  ", style = Type.Small.copy(fontSize = 14.sp, color = Color(0xFFFFC94D)))
                    Text("%.1fs".format(sourceUs / 1e6 / speed), style = Type.Small.copy(fontSize = 14.sp, color = Palette.Text))
                }
                Spacer(Modifier.height(18.dp))
            }
            SpeedRuler(
                position = speedToPos(speed),
                onChange = {
                    var s = posToSpeed(it)
                    // stick to 1x and the round marks
                    for (mark in floatArrayOf(1f, 2f, 5f, 10f)) if (abs(s - mark) / mark < 0.04f) s = mark
                    speed = (s * 10).roundToInt() / 10f
                    vm.command(
                        JSONObject().put("op", "setClipSpeed").put("clipId", clip.id).put("speed", speed.toDouble()),
                        gesture = "speed",
                    )
                },
                onDone = { vm.endGesture() },
                label = "%.1fx".format(speed),
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(18.dp)) {
                val travel = maxWidth - 20.dp
                for ((mark, text) in listOf(0.1f to "0.1x", 1f to "1x", 2f to "2x", 5f to "5x", 10f to "10x", 16f to "16x")) {
                    Text(
                        text,
                        style = Type.Small.copy(fontSize = 12.sp, color = Palette.DockText),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(start = (travel * speedToPos(mark) + 10.dp - 16.dp).coerceAtLeast(0.dp)).width(32.dp),
                    )
                }
            }
        } else {
            val current: List<Pair<Double, Double>>? = remember(clip.json) {
                runCatching { JSONObject(clip.json).optJSONArray("speedCurve") }.getOrNull()?.let { a ->
                    (0 until a.length()).map { a.getJSONObject(it).let { p -> p.optDouble("at") to p.optDouble("speed") } }
                }
            }
            var editing by remember(clip.id) { mutableStateOf(false) }
            if (editing && current != null) {
                SpeedCurveEditor(vm, clip, current, onBack = { editing = false })
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 12.dp)) {
                    CURVES.forEach { c ->
                        val selected = if (c.points == null) current == null else current != null && sameCurve(current, c.points)
                        ChoiceTile(if (selected && c.points != null) "Edit" else c.name, selected, onClick = {
                            // a second tap on the chosen curve opens it for editing
                            if (selected && c.points != null) {
                                editing = true
                                return@ChoiceTile
                            }
                            val curve: Any = c.points?.let { pts ->
                                JSONArray().apply { pts.forEach { (at, s) -> put(JSONObject().put("at", at).put("speed", s)) } }
                            } ?: JSONObject.NULL
                            vm.command(JSONObject().put("op", "setClipSpeedCurve").put("clipId", clip.id).put("curve", curve))
                            if (c.name == "Custom") editing = true
                        }) {
                            CurveGlyph(c.points)
                        }
                    }
                    // a curve edited away from every preset still shows, and opens
                    if (current != null && CURVES.none { it.points != null && sameCurve(current, it.points) }) {
                        ChoiceTile("Edit", true, onClick = { editing = true }) { CurveGlyph(current) }
                    }
                }
            }
        }
    }
}

/** Whether two curves are the same points, to within rounding. */
private fun sameCurve(a: List<Pair<Double, Double>>, b: List<Pair<Double, Double>>) =
    a.size == b.size && a.indices.all { abs(a[it].first - b[it].first) < 0.005 && abs(a[it].second - b[it].second) < 0.05 }

@Composable
private fun CurveGlyph(points: List<Pair<Double, Double>>?) {
    androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
        if (points == null) {
            drawLine(Palette.TextSecondary, androidx.compose.ui.geometry.Offset(6f, size.height - 6f), androidx.compose.ui.geometry.Offset(size.width - 6f, 6f), 2.dp.toPx())
            return@Canvas
        }
        val max = points.maxOf { it.second }.coerceAtLeast(1.0)
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, (at, s) ->
            val x = (at * size.width).toFloat()
            val y = (size.height - (s / max) * size.height * 0.85 - size.height * 0.05).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Color.White, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
    }
}

// ── Blend ──

/**
 * The blend modes in the reference's order and under its short names (its Filter is screen, its
 * Slide multiply), by the name the engine keeps; the engine's last three follow.
 */
private val BLENDS = listOf(
    "normal" to "Normal", "screen" to "Filter", "darken" to "Darken", "overlay" to "Overlay", "multiply" to "Slide",
    "lighten" to "Lighten", "hardLight" to "Hard", "softLight" to "Soft", "linearBurn" to "Burn", "colorBurn" to "Color burn",
    "colorDodge" to "Dodge", "add" to "Add", "difference" to "Difference", "exclusion" to "Exclusion",
)

/** The phone's own drawing of each mode, for its tile's sample (linear burn has none; multiply is nearest). */
private fun sampleMode(id: String) = when (id) {
    "darken" -> androidx.compose.ui.graphics.BlendMode.Darken
    "screen" -> androidx.compose.ui.graphics.BlendMode.Screen
    "overlay" -> androidx.compose.ui.graphics.BlendMode.Overlay
    "multiply", "linearBurn" -> androidx.compose.ui.graphics.BlendMode.Multiply
    "lighten" -> androidx.compose.ui.graphics.BlendMode.Lighten
    "hardLight" -> androidx.compose.ui.graphics.BlendMode.Hardlight
    "softLight" -> androidx.compose.ui.graphics.BlendMode.Softlight
    "colorBurn" -> androidx.compose.ui.graphics.BlendMode.ColorBurn
    "colorDodge" -> androidx.compose.ui.graphics.BlendMode.ColorDodge
    "add" -> androidx.compose.ui.graphics.BlendMode.Plus
    "difference" -> androidx.compose.ui.graphics.BlendMode.Difference
    "exclusion" -> androidx.compose.ui.graphics.BlendMode.Exclusion
    else -> androidx.compose.ui.graphics.BlendMode.SrcOver
}

/**
 * A mode's sample, filling its tile: an evening sky over a row of rooftops, and a figure in front
 * mixed into it the mode's way.
 */
@Composable
private fun BlendSample(id: String) {
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen },
    ) {
        val w = size.width
        val h = size.height
        drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFF7CDA8), Color(0xFFE48460))))
        val roofs = listOf(0.64f, 0.5f, 0.72f, 0.42f, 0.6f, 0.68f, 0.54f)
        val across = w / roofs.size
        roofs.forEachIndexed { i, top ->
            drawRect(
                Color(0xFF6E5853),
                androidx.compose.ui.geometry.Offset(i * across, h * top),
                androidx.compose.ui.geometry.Size(across * 0.86f, h * (1 - top)),
            )
        }
        val mode = sampleMode(id)
        val figure = Color(0xFF2D6A80)
        drawCircle(figure, w * 0.15f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.36f), blendMode = mode)
        drawRoundRect(
            figure,
            androidx.compose.ui.geometry.Offset(w * 0.22f, h * 0.56f),
            androidx.compose.ui.geometry.Size(w * 0.56f, h * 0.5f),
            androidx.compose.ui.geometry.CornerRadius(w * 0.2f),
            blendMode = mode,
        )
    }
}

/** How an overlay mixes with what is under it: the modes across, and how strongly under them. */
@Composable
private fun BlendPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val json = remember(clip.json) { vm.clipJson(clip.id) }
    val current = json?.optString("blend")?.ifEmpty { "normal" } ?: "normal"
    var v by remember(clip.id) { mutableFloatStateOf(((json?.optDouble("opacity", 1.0) ?: 1.0) * 100).toFloat()) }
    PanelFrame("Splice", vm, height = 190.dp) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, end = 6.dp, top = 10.dp)) {
            BLENDS.forEach { (id, label) ->
                ChoiceTile(label, current == id, onClick = {
                    vm.patch(clip.id, JSONObject().put("blend", if (id == "normal") "" else id))
                }, size = 52.dp) {
                    BlendSample(id)
                }
            }
        }
        ValueSlider(v, 0f..100f, { "${it.roundToInt()}" }, {
            v = it
            vm.patch(clip.id, JSONObject().put("opacity", it / 100.0), gesture = "blend-opacity")
        }, { vm.endGesture() })
    }
}

// ── Volume, opacity, fade ──

@Composable
private fun VolumePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    // the slider's middle is 100%: the left half runs to silence, the right half up to 1000%
    var at by remember(clip.id) { mutableFloatStateOf(volumeToSlider((clip.volume * 100).toFloat())) }
    val chain = remember(clip.json) {
        vm.clipJson(clip.id)?.optJSONArray("filters")?.let { c -> (0 until c.length()).map { c.getJSONObject(it).optString("id") } }.orEmpty()
    }

    /** Puts the cleaning package [id] on the clip's sound, or takes it off. */
    fun toggle(id: String) {
        val j = vm.clipJson(clip.id) ?: return
        val old = j.optJSONArray("filters") ?: JSONArray()
        val next = JSONArray()
        var had = false
        for (i in 0 until old.length()) {
            val link = old.getJSONObject(i)
            if (link.optString("id") == id) had = true else next.put(link)
        }
        // cleaning runs before any voice effect, so the effect gets the clean sound
        if (!had) {
            val all = JSONArray().put(JSONObject().put("id", id).put("params", JSONObject()))
            for (i in 0 until next.length()) all.put(next.getJSONObject(i))
            vm.patch(clip.id, JSONObject().put("filters", all))
        } else {
            vm.patch(clip.id, JSONObject().put("filters", next))
        }
    }

    // a sound's volume has its title below and only the noise switch, as the reference's has
    val sound = clip.kind == "audio"
    PanelFrame("Volume", vm, height = if (sound) 200.dp else if (clip.audible) 280.dp else 170.dp, titleOnTop = !sound) {
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.Bottom) {
            Text("0", style = Type.Body.copy(color = Palette.TextSecondary), modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(12.dp))
            CapSlider(
                value = at,
                range = 0f..1000f,
                label = { "${sliderToVolume(it).roundToInt()}" },
                onChange = {
                    at = if (abs(it - 500f) < 15f) 500f else it
                    vm.patch(clip.id, JSONObject().put("volume", sliderToVolume(at) / 100.0), gesture = "volume")
                },
                onDone = { vm.endGesture() },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("1000", style = Type.Body.copy(color = Palette.TextSecondary), modifier = Modifier.padding(bottom = 6.dp))
        }
        if (clip.audible) {
            Spacer(Modifier.height(10.dp))
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                CLEANERS.forEachIndexed { i, (id, label, about) ->
                    if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0xFF333333)))
                    SwitchRow(label, about, id in chain) { toggle(id) }
                }
                if (!sound) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Color(0xFF333333)))
                    // every clip with sound brought to this one's loudness, or the adjustment off them all
                    SwitchRow("Loudness adjustment", "Brings the other clips' sound to this clip's loudness.", LOUDNESS in chain) {
                        val want = LOUDNESS !in chain
                        vm.edit { matchLoudness(clip.id, want) }
                    }
                }
            }
        }
    }
}

/** An option with a line saying what it does, and a switch for it. */
@Composable
fun SwitchRow(label: String, about: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.Body.copy(fontSize = 15.sp, color = Palette.DockText))
            Spacer(Modifier.height(2.dp))
            Text(about, style = Type.Small.copy(fontSize = 13.sp))
        }
        Spacer(Modifier.width(12.dp))
        androidx.compose.material3.Switch(
            checked = on,
            onCheckedChange = { onToggle() },
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color(0xFF3A3A3A),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** The sound cleaners the Volume panel offers, in the engine's packages. */
private val CLEANERS = listOf(
    Triple("concat.denoise", "Reduce noise", "Takes the hum and hiss out from behind the sound."),
)

/** The engine's loudness package, which Loudness adjustment sets on every clip with sound. */
private const val LOUDNESS = "concat.loudness"

/** The sound cleaners, kept off the audio effects shelf: the panel's, loudness, and Enhance voice, a tool of its own. */
val CLEANER_IDS = CLEANERS.map { it.first }.toSet() + LOUDNESS + "concat.enhance-voice"

private fun sliderToVolume(at: Float): Float = if (at <= 500f) at / 5f else 100f + (at - 500f) * 1.8f

private fun volumeToSlider(v: Float): Float = (if (v <= 100f) v * 5f else 500f + (v - 100f) / 1.8f).coerceIn(0f, 1000f)

/** A tick box with its label, for an on/off choice in a panel. */
@Composable
fun CheckChip(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(18.dp).clip(RoundedCornerShape(4.dp))
                .background(if (on) Palette.Accent else Color.Transparent)
                .then(if (on) Modifier else Modifier.border(1.5.dp, Palette.TextSecondary, RoundedCornerShape(4.dp))),
            contentAlignment = Alignment.Center,
        ) {
            if (on) Glyph(LucideIcons.Check, Palette.Black, size = 14.dp)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, style = Type.Small.copy(color = Palette.Text))
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
private fun OpacityPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val start = remember(clip.id) { vm.clipJson(clip.id)?.optDouble("opacity", 1.0) ?: 1.0 }
    var v by remember(clip.id) { mutableFloatStateOf((start * 100).toFloat()) }
    PanelFrame("Opacity", vm, height = 170.dp) {
        Spacer(Modifier.height(40.dp))
        ValueSlider(
            value = v,
            range = 0f..100f,
            label = { "${it.roundToInt()}" },
            onChange = {
                v = it
                vm.patch(clip.id, JSONObject().put("opacity", v / 100.0), gesture = "opacity")
            },
            onDone = { vm.endGesture() },
        )
    }
}

@Composable
private fun FadePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val json = remember(clip.id) { vm.clipJson(clip.id) }
    val max = (clip.durationUs / 2e6).toFloat().coerceAtMost(10f).coerceAtLeast(0.1f)
    var fadeIn by remember(clip.id) { mutableFloatStateOf((json?.optDouble("fadeIn", 0.0) ?: 0.0).toFloat().coerceAtMost(max)) }
    var fadeOut by remember(clip.id) { mutableFloatStateOf((json?.optDouble("fadeOut", 0.0) ?: 0.0).toFloat().coerceAtMost(max)) }
    // a short sheet, the names indented and the sliders stopping short of the edge, as the reference's
    PanelFrame("Fade", vm, height = 168.dp) {
        val inset = Modifier.padding(start = 34.dp, end = 50.dp)
        Spacer(Modifier.height(10.dp))
        ValueSlider(fadeIn, 0f..max, { "%.1fs".format(it) }, {
            fadeIn = it
            vm.patch(clip.id, JSONObject().put("fadeIn", it.toDouble()), gesture = "fadein")
        }, { vm.endGesture() }, inset, title = "Fade in")
        ValueSlider(fadeOut, 0f..max, { "%.1fs".format(it) }, {
            fadeOut = it
            vm.patch(clip.id, JSONObject().put("fadeOut", it.toDouble()), gesture = "fadeout")
        }, { vm.endGesture() }, inset, title = "Fade out")
    }
}

// ── Aspect ratio ──

private data class Ratio(val label: String, val w: Int, val h: Int)

private val RATIOS = listOf(
    Ratio("Original", 0, 0),
    Ratio("9:16", 9, 16),
    Ratio("16:9", 16, 9),
    Ratio("1:1", 1, 1),
    Ratio("4:3", 4, 3),
    Ratio("3:4", 3, 4),
    Ratio("5.8\"", 18, 39),
    Ratio("4:5", 4, 5),
    Ratio("2:1", 2, 1),
    Ratio("2.35:1", 235, 100),
    Ratio("1.85:1", 185, 100),
    Ratio("21:9", 21, 9),
    Ratio("1:2", 1, 2),
)

/** A frame of shape [w]:[h], 1080 on its short side. */
fun frameFor(w: Int, h: Int): Pair<Int, Int> =
    if (w >= h) ((1080.0 * w / h).roundToInt() / 2 * 2) to 1080 else 1080 to ((1080.0 * h / w).roundToInt() / 2 * 2)

@Composable
private fun RatioPanel(vm: EditorViewModel) {
    val t = vm.timeline ?: return
    PanelFrame("Ratio", vm, height = 180.dp) {
        Row(
            Modifier.fillMaxWidth().weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // the frame's own shape counts as Original first, as the reference marks it
            val chosen = RATIOS.indexOfFirst { r ->
                val (fw, fh) = if (r.w == 0) originalFrame(vm) else frameFor(r.w, r.h)
                fw * t.height.toInt() == fh * t.width.toInt()
            }
            RATIOS.forEachIndexed { index, r ->
                val (fw, fh) = if (r.w == 0) originalFrame(vm) else frameFor(r.w, r.h)
                val selected = index == chosen
                // each tile in its ratio's shape, about the same area, standing on one line
                val aspect = if (r.w == 0) 1f else r.w.toFloat() / r.h
                val h = (45f / kotlin.math.sqrt(aspect)).coerceIn(37f, 64f)
                val w = (h * aspect).coerceAtMost(92f)
                Column(
                    Modifier.padding(end = 12.dp, bottom = 14.dp).clickable {
                        // a collage is laid out again in the new frame
                        vm.edit {
                            val view = setCanvas(fw.toUInt(), fh.toUInt())
                            collage()?.let { layCollage(view, it.ids, it.layout) } ?: view
                        }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A3A))
                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (r.w == 0) {
                            Glyph(LucideIcons.Maximize, Palette.DockText, size = 18.dp)
                        } else {
                            // a small outline of the shape
                            val ow = (w * 0.42f).coerceAtMost(30f)
                            val oh = (ow / aspect).coerceAtMost(h * 0.5f)
                            Box(Modifier.size((oh * aspect).dp, oh.dp).border(1.5.dp, Palette.DockText, RoundedCornerShape(2.dp)))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(r.label, style = Type.Small.copy(fontSize = 12.sp, color = if (selected) Palette.Text else Palette.DockText))
                }
            }
        }
    }
}

/** The first main-track picture's frame, or the current one. */
private fun originalFrame(vm: EditorViewModel): Pair<Int, Int> {
    val t = vm.timeline
    return vm.originalSize ?: ((t?.width?.toInt() ?: 1080) to (t?.height?.toInt() ?: 1920))
}

// ── Transitions ──

/** The tab order for the engine's transition categories; any others follow. */
private val TRANSITION_ORDER = listOf("Basic", "Dissolve", "Slide", "Wipe", "Zoom", "Spin", "Light", "Glitch")

/** A frame of a clip for a transition tile: its last when [end], else its first. */
internal fun cutFrame(c: io.github.artcraftandroid.capcraft.core.ClipView, end: Boolean): android.graphics.Bitmap? = when (c.kind) {
    "video" -> {
        val at = if (end) c.sourceStartUs + (c.durationUs * c.speed).toLong() - 50_000 else c.sourceStartUs
        Thumbs.still(c.path, at.coerceIn(c.sourceStartUs, maxOf(c.sourceStartUs, c.mediaDurationUs - 50_000)), 240)
    }
    "image" -> Thumbs.still(c.path, 0, 240)
    else -> null
}

@Composable
private fun TransitionPanel(vm: EditorViewModel, clipId: String) {
    val clip = vm.clip(clipId) ?: run { vm.panel = null; return }
    val packages = rememberPackages("transition")
    val json = remember(clip.json) { vm.clipJson(clip.id)?.optJSONObject("transitionIn") }
    val main = vm.timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN }
    val at = main?.clips?.indexOfFirst { it.id == clipId } ?: -1
    val before = main?.clips?.getOrNull(at - 1)
    val after = main?.clips?.getOrNull(at)
    // as long as half the shorter side, to a tenth of a second
    val shorter = listOfNotNull(before?.durationUs, after?.durationUs).minOrNull() ?: 6_000_000L
    val longest = ((shorter / 2 / 100_000L) / 10f).coerceIn(0.2f, 3f)
    var duration by remember(clipId) { mutableFloatStateOf((json?.optDouble("duration", 0.5) ?: 0.5).toFloat().coerceIn(0.1f, longest)) }
    var chosen by remember(clipId) { mutableStateOf(json?.optString("id")?.takeIf { it.isNotEmpty() }) }
    // the sheet stands tall for browsing and sinks once one is chosen, to show it on the picture
    var browsing by remember(clipId) { mutableStateOf(chosen == null) }
    var query by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(0) }
    val categories = remember(packages) {
        listOf("All") + packages.map { it.category }.distinct().sortedBy { TRANSITION_ORDER.indexOf(it).let { i -> if (i < 0) 99 else i } }
    }
    // the bookmark ahead of the tabs: the transitions kept by a long press
    val context = androidx.compose.ui.platform.LocalContext.current
    var keptTab by remember { mutableStateOf(false) }
    val kept = remember(Favorites.version.intValue) { Favorites.of(context, "transition") }
    val shown = remember(packages, query, tab, categories, keptTab, kept) {
        when {
            query.isNotBlank() -> packages.filter { it.name.contains(query.trim(), ignoreCase = true) }
            keptTab -> packages.filter { it.id in kept }
            tab == 0 -> packages
            else -> packages.filter { it.category == categories.getOrNull(tab) }
        }
    }
    // the frames either side of the cut, once they are made
    @Suppress("UNUSED_VARIABLE") val thumbs = Thumbs.version.intValue
    val fromBitmap = before?.let { cutFrame(it, end = true) }
    val toBitmap = after?.let { cutFrame(it, end = false) }
    val from = remember(fromBitmap) { fromBitmap?.asImageBitmap() }
    val to = remember(toBitmap) { toBitmap?.asImageBitmap() }
    fun applyTo(id: String?, d: Float, all: Boolean) {
        val value: Any = if (id == null) JSONObject.NULL else JSONObject().put("id", id).put("duration", d.toDouble())
        val targets = if (all) main?.clips?.drop(1)?.map { it.id } ?: return else listOf(clipId)
        val batch = JSONArray()
        targets.forEach { batch.put(JSONObject().put("op", "updateClip").put("clipId", it).put("patch", JSONObject().put("transitionIn", value))) }
        vm.edit { applyBatch(batch.toString()) }
    }
    PanelFrame("Transitions", vm, tall = !browsing, towering = browsing, titleOnTop = true, header = {
        SearchField(query, { query = it }, "Search transitions")
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(start = 10.dp).size(36.dp).clickable { keptTab = true; query = "" }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Bookmark, if (keptTab && query.isBlank()) Palette.Text else Palette.TextSecondary, size = 19.dp, description = "Favorites")
            }
            Box(Modifier.weight(1f)) {
                Tabs(categories, if (query.isBlank() && !keptTab) tab else -1) {
                    tab = it
                    keptTab = false
                    query = ""
                }
            }
        }
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp).size(24.dp, 3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF4A4A4A)))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val none = if (query.isBlank() && !keptTab) 1 else 0
            if (keptTab && query.isBlank() && shown.isEmpty()) {
                Text("Press and hold one to keep it here", style = Type.Small, modifier = Modifier.align(Alignment.Center))
            }
            ChoiceGrid(shown.size + none, Modifier.fillMaxSize()) { i ->
                if (i < none) {
                    GridTile("None", chosen == null, onClick = { chosen = null; applyTo(null, duration, false) }) {
                        Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 26.dp)
                    }
                } else {
                    val p = shown[i - none]
                    GridTile(
                        p.name,
                        chosen == p.id,
                        onClick = {
                            chosen = p.id
                            browsing = false
                            applyTo(p.id, duration, false)
                        },
                        onLongClick = { vm.message = if (Favorites.toggle(context, "transition", p.id)) "Added to favorites" else "Removed from favorites" },
                    ) {
                        TransitionGlyph(p.id, playing = true, from = from, to = to, modifier = Modifier.fillMaxSize())
                        if (p.id in kept) {
                            Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                Glyph(LucideIcons.Bookmark, Color.White, size = 11.dp)
                            }
                        }
                    }
                }
            }
            if (chosen != null) {
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color(0xF2333333))
                        .clickable {
                            applyTo(chosen, duration, true)
                            vm.message = "Applied to all"
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(LucideIcons.Layers, Palette.Text, size = 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Apply to all", style = Type.Body.copy(color = Palette.Text))
                }
            }
        }
        if (chosen != null) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF2C2C2C)).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("0.1s", style = Type.Small.copy(color = Palette.TextSecondary))
                CapSlider(duration, 0.1f..longest, { "%.1fs".format(it) }, { duration = it }, { applyTo(chosen, duration, false) }, Modifier.weight(1f).padding(horizontal = 12.dp))
                Text("%.1fs".format(longest), style = Type.Small.copy(color = Palette.TextSecondary))
            }
        }
    }
}

/** A search field for a panel's bar: a rounded box with a magnifier, and a cross to clear it. */
@Composable
internal fun SearchField(value: String, onChange: (String) -> Unit, hint: String, autoFocus: Boolean = false) {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // a box opened from a magnifier takes the keyboard at once
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.padding(start = 12.dp, end = 4.dp).fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3A3A3A)).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(LucideIcons.Search, Palette.TextSecondary, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, style = Type.Body.copy(color = Palette.TextSecondary), maxLines = 1)
            androidx.compose.foundation.text.BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Type.Body.copy(color = Palette.Text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().then(Modifier.focusRequester(focus)),
            )
        }
        if (value.isNotEmpty()) {
            Box(Modifier.size(28.dp).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.X, Palette.TextSecondary, size = 16.dp)
            }
        }
    }
}
