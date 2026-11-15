package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What an effect on the timeline treats, as the reference's Object sheet asks: every picture
 * beneath it, overlays too, or the main track alone (where a new effect starts). The engine's
 * effects treat what lies under their row, so a choice moves the effect under the overlays or
 * over them; one overlay alone is not something a row can single out.
 */
@Composable
fun EffectObjectPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip?.takeIf { it.kind == "layer" } ?: run { vm.panel = null; return }
    val all = vm.rowOf(clip.id)?.allVideos == true
    // the main track's picture where the effect starts
    val main = vm.timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN }?.clips
        ?.firstOrNull { clip.startUs >= it.startUs && clip.startUs < it.startUs + it.durationUs }
        ?: vm.timeline?.rows?.firstOrNull { it.kind == RowKind.MAIN }?.clips?.firstOrNull()
    @Suppress("UNUSED_VARIABLE") val thumbs = Thumbs.version.intValue
    val bitmap = main?.let { cutFrame(it, end = false) }
    val picture = remember(bitmap) { bitmap?.asImageBitmap() }

    fun choose(every: Boolean) {
        if (every != all) vm.edit(select = { clip.id }) { setReach(clip.id, every) }
    }

    PanelFrame("Object", vm, height = 200.dp) {
        Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ChoiceTile("All videos", selected = all, onClick = { choose(true) }) {
                Glyph(LucideIcons.Database, Palette.Text, size = 24.dp)
            }
            ChoiceTile("Main video", selected = !all, onClick = { choose(false) }) {
                picture?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Glyph(LucideIcons.Film, Palette.Text, size = 24.dp)
            }
        }
    }
}

/**
 * An effect's own settings on the timeline, each on its slider, as the reference's Adjust sheet
 * for an effect: Reset at the left of the title, the tick at the right.
 */
@Composable
fun LayerAdjustPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip?.takeIf { it.kind == "layer" } ?: run { vm.panel = null; return }
    val p = rememberPackages("effect").firstOrNull { it.id == clip.effectId } ?: run { vm.panel = null; return }
    val knobs = adjustable(p)
    val params = remember(clip.json) {
        val chain = vm.clipJson(clip.id)?.optJSONArray("videoEffects")
        (0 until (chain?.length() ?: 0)).map { chain!!.getJSONObject(it) }
            .firstOrNull { it.optString("id") == p.id }?.optJSONObject("params") ?: JSONObject()
    }

    fun reset() {
        knobs.forEach { setKnob(vm, clip.id, p.id, it.key, it.default, "effect-reset") }
        vm.endGesture()
    }

    PanelFrame(
        "Adjust",
        vm,
        height = (104 + 52 * knobs.size.coerceIn(1, 4)).dp,
        left = {
            Row(Modifier.clickable(onClick = ::reset).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Glyph(LucideIcons.RotateCcw, Palette.DockText, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("Reset", style = Type.Body.copy(color = Palette.DockText))
            }
        },
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            if (knobs.isEmpty()) {
                Text("This effect has nothing to set", style = Type.Small, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            knobs.forEach { knob ->
                key(knob.key) {
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
                            setKnob(vm, clip.id, p.id, knob.key, snapped, "effect-${knob.key}")
                        },
                        onDone = {
                            held = null
                            vm.endGesture()
                        },
                        title = knob.label,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
