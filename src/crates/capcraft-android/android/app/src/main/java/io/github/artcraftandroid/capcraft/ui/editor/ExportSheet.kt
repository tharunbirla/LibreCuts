package io.github.artcraftandroid.capcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.exportSize
import io.github.artcraftandroid.capcraft.core.suggestedBitrateKbps
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.MaterialIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.panels.Tabs
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RESOLUTIONS = listOf(480, 720, 1080, 1440, 2160)
private val RESOLUTION_LABELS = listOf("480p", "720p", "1080p", "2k", "4k")
private val FRAME_RATES = listOf(24, 25, 30, 50, 60)
/** The bitrate stops, megabits a second. */
private val MBPS = listOf(5, 10, 20, 50, 100)
private val GIF_SIZES = listOf(240, 320, 480)
private val GIF_RATES = listOf(8, 10, 15)

/** The resolution an export uses: the one chosen, or the canvas's own up to 4K. */
fun exportResolutionOf(vm: EditorViewModel): Int {
    val t = vm.timeline
    val short = t?.let { min(it.width.toInt(), it.height.toInt()) } ?: 1080
    return vm.exportResolution ?: RESOLUTIONS.lastOrNull { it <= max(short, 480) } ?: 1080
}

/** The bitrate an export uses, kilobits a second: the one chosen, or the one suggested for its size. */
fun exportKbps(vm: EditorViewModel, width: Int, height: Int): Int =
    vm.exportMbps?.let { it * 1000 } ?: suggestedBitrateKbps(width.toUInt(), height.toUInt(), vm.exportFps.toUInt()).toInt()

/**
 * The export settings, dropped down from the top over the dimmed editor: Video or GIF, then the
 * resolution, frame rate and bitrate on stepped sliders, and the size the file will come to.
 */
@Composable
fun ExportSheet(vm: EditorViewModel, onExport: () -> Unit) {
    val t = vm.timeline ?: return
    BackHandler { vm.exportSheet = false }
    Box(
        Modifier.fillMaxSize().background(Color(0xAA000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.exportSheet = false },
    ) {
        Column(
            Modifier.fillMaxWidth().background(Palette.Timeline)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .statusBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Tabs(if (vm.photoTab) listOf("Video", "GIF", "Photo") else listOf("Video", "GIF"), if (vm.exportPhoto) 2 else if (vm.exportGif) 1 else 0) {
                        vm.exportGif = it == 1
                        vm.exportPhoto = it == 2
                    }
                }
                Row(
                    Modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Chip)
                        .clickable { vm.exportSheet = false }.padding(start = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = when {
                        vm.exportGif -> "GIF"
                        else -> resolutionLabel(exportResolutionOf(vm))
                    }
                    Text(label, style = Type.BodyStrong.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                    Glyph(MaterialIcons.ArrowDropUp, Palette.Text, size = 20.dp)
                }
                Spacer(Modifier.width(9.dp))
                Box(
                    Modifier.height(32.dp).width(65.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Accent)
                        .clickable { vm.exportSheet = false; onExport() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Export", style = Type.BodyStrong.copy(color = Palette.OnAccent, fontSize = 15.sp, fontWeight = FontWeight.Medium))
                }
                Spacer(Modifier.width(16.dp))
            }
            if (vm.exportGif) {
                val size = GIF_SIZES.indexOf(vm.gifSize).coerceAtLeast(0)
                SettingSlider("Resolution", "Smaller files load faster", GIF_SIZES.map { "${it}p" }, size.toFloat()) {
                    vm.gifSize = GIF_SIZES[it.roundToInt().coerceIn(0, GIF_SIZES.size - 1)]
                }
                val rate = GIF_RATES.indexOf(vm.gifFps).coerceAtLeast(0)
                SettingSlider("Frame rate", "More pictures, smoother motion", GIF_RATES.map { "$it" }, rate.toFloat()) {
                    vm.gifFps = GIF_RATES[it.roundToInt().coerceIn(0, GIF_RATES.size - 1)]
                }
                Divider()
                Text(
                    "A looping picture without sound",
                    style = Type.Small.copy(fontSize = 13.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                return@Column
            }
            val res = exportResolutionOf(vm)
            val r = RESOLUTIONS.indexOf(res).coerceAtLeast(0)
            SettingSlider("Resolution", RESOLUTION_HINTS[r], RESOLUTION_LABELS, r.toFloat()) {
                vm.exportResolution = RESOLUTIONS[it.roundToInt().coerceIn(0, RESOLUTIONS.size - 1)]
            }
            if (vm.exportPhoto) {
                Divider()
                Text(
                    "The picture under the playhead, saved as a photo",
                    style = Type.Small.copy(fontSize = 13.sp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                return@Column
            }
            val f = FRAME_RATES.indexOf(vm.exportFps).coerceAtLeast(2)
            SettingSlider("Frame rate", if (vm.exportFps >= 50) "Very smooth motion" else "Steady, everyday motion", FRAME_RATES.map { "$it" }, f.toFloat()) {
                vm.exportFps = FRAME_RATES[it.roundToInt().coerceIn(0, FRAME_RATES.size - 1)]
            }
            val dims = exportSize(t.width, t.height, res.toUInt())
            val suggested = (suggestedBitrateKbps(dims[0], dims[1], vm.exportFps.toUInt()).toInt() / 1000.0).roundToInt().coerceAtLeast(1)
            val mbps = vm.exportMbps ?: suggested
            SettingSlider("Bitrate (Mbps)", "Suggested for this video: $suggested", MBPS.map { "$it" }, mbpsToPosition(mbps), continuous = true) {
                val chosen = positionToMbps(it)
                // the suggestion is sticky, so it can be found again
                vm.exportMbps = if (abs(chosen - suggested) <= max(1, suggested / 10)) null else chosen
            }
            Divider()
            val kbps = exportKbps(vm, dims[0].toInt(), dims[1].toInt())
            val mb = (kbps + 128) / 8.0 / 1000.0 * (t.durationUs / 1e6)
            Text(
                "Estimated file size: %s MB".format(if (mb < 10) "%.1f".format(mb) else "${mb.roundToInt()}"),
                style = Type.Small.copy(fontSize = 13.sp),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

private val RESOLUTION_HINTS = listOf(
    "Small file for quick sharing",
    "Good for most sharing",
    "Sharp on any phone",
    "Extra sharp on big screens",
    "The finest detail, the biggest file",
)

@Composable
private fun Divider() {
    Box(Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
}

/** Where [mbps] sits on the bitrate slider: between its stops, on a log scale. */
private fun mbpsToPosition(mbps: Int): Float {
    val v = mbps.coerceIn(MBPS.first(), MBPS.last()).toDouble()
    val i = MBPS.indexOfLast { it <= v }.coerceIn(0, MBPS.size - 2)
    val a = MBPS[i].toDouble()
    val b = MBPS[i + 1].toDouble()
    return (i + (ln(v) - ln(a)) / (ln(b) - ln(a))).toFloat()
}

private fun positionToMbps(position: Float): Int {
    val p = position.coerceIn(0f, (MBPS.size - 1).toFloat())
    val i = p.toInt().coerceIn(0, MBPS.size - 2)
    val f = p - i
    return exp(ln(MBPS[i].toDouble()) + f * (ln(MBPS[i + 1].toDouble()) - ln(MBPS[i].toDouble()))).roundToInt()
}

/**
 * A setting's row: its name with a hint at the right, a track with a tick at each stop and a white
 * thumb, and the stops' names under it. Stepped unless [continuous].
 */
@Composable
private fun SettingSlider(title: String, hint: String, labels: List<String>, position: Float, continuous: Boolean = false, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.Body.copy(fontSize = 16.sp, color = Palette.DockText), modifier = Modifier.weight(1f))
            Text(hint, style = Type.Small.copy(fontSize = 13.sp))
        }
        Spacer(Modifier.height(12.dp))
        val last = (labels.size - 1).toFloat()
        BoxWithConstraints(Modifier.fillMaxWidth().height(28.dp)) {
            val widthPx = constraints.maxWidth.toFloat()
            fun at(x: Float): Float {
                val p = (x / widthPx * last).coerceIn(0f, last)
                return if (continuous) p else p.roundToInt().toFloat()
            }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(labels, continuous) { detectTapGestures { onChange(at(it.x)) } }
                    .pointerInput(labels, continuous) { detectDragGestures { change, _ -> onChange(at(change.position.x)) } },
            ) {
                val y = size.height / 2
                val x = size.width * (position / last)
                drawLine(Color(0xFF4A4A4A), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round)
                for (i in 1 until labels.size - 1) {
                    val tx = size.width * i / last
                    drawLine(if (tx <= x) Palette.Accent else Color(0xFF5A5A5A), Offset(tx, y - 5.dp.toPx()), Offset(tx, y + 5.dp.toPx()), 2.dp.toPx())
                }
                drawLine(Palette.Accent, Offset(0f, y), Offset(x, y), 2.dp.toPx(), StrokeCap.Round)
                drawCircle(Color.White, 11.dp.toPx(), Offset(x.coerceIn(11.dp.toPx(), size.width - 11.dp.toPx()), y))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = Type.Small.copy(fontSize = 13.sp, color = Palette.DockText),
                    textAlign = when (i) {
                        0 -> TextAlign.Start
                        labels.size - 1 -> TextAlign.End
                        else -> TextAlign.Center
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
