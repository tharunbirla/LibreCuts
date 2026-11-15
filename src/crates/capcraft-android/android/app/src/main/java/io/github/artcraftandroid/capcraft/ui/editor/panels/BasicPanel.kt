package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.ClipBox
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Basic, Transform's Advanced: where the selected clip sits on rulers. Position across and up (in
 * hundredths of the frame), Zoom (per cent) and Rotate (degrees); a clip with keyframes is set at
 * its key under the playhead, added there if missing.
 */
@Composable
fun BasicPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    var tab by remember { mutableIntStateOf(0) }
    val session = vm.session
    val playhead = vm.playhead
    // where the clip is at the playhead, keys and all
    val box by produceState<ClipBox?>(null, session, vm.timeline?.revision, playhead, clip.id) {
        val s = session ?: return@produceState
        value = withContext(Threads.commands) { runCatching { s.clipBoxes(playhead) }.getOrDefault(emptyList()) }.firstOrNull { it.clipId == clip.id }
    }
    val json = remember(clip.json) { vm.clipJson(clip.id) ?: JSONObject() }
    val x = box?.offsetX ?: json.optDouble("offsetX", 0.0)
    val y = box?.offsetY ?: json.optDouble("offsetY", 0.0)
    val scale = box?.scale ?: json.optDouble("scale", 1.0)
    val rotation = json.optDouble("rotation", 0.0).takeIf { clip.keysUs.isEmpty() } ?: (box?.rotation ?: 0.0)

    /** Sets the clip's [property] to [value]: on its key under the playhead when it has keys. */
    fun set(property: String, value: Double, gesture: String?) {
        if (clip.keysUs.isNotEmpty()) {
            val at = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
            vm.command(
                JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", property).put("at", at).put("value", value),
                gesture,
            )
        } else {
            vm.command(JSONObject().put("op", "setClipTransform").put("clipId", clip.id).put(property, value), gesture)
        }
    }

    fun reset() {
        if (clip.keysUs.isNotEmpty()) {
            val batch = JSONArray()
            listOf("offsetX" to 0.0, "offsetY" to 0.0, "scale" to 1.0, "rotation" to 0.0).forEach { (p, v) ->
                val at = ((vm.playhead - clip.startUs).toDouble() / clip.durationUs.coerceAtLeast(1)).coerceIn(0.0, 1.0)
                batch.put(JSONObject().put("op", "setClipKey").put("clipId", clip.id).put("property", p).put("at", at).put("value", v))
            }
            vm.command(JSONObject().put("op", "batch").put("commands", batch))
        } else {
            vm.command(
                JSONObject().put("op", "setClipTransform").put("clipId", clip.id)
                    .put("offsetX", 0.0).put("offsetY", 0.0).put("scale", 1.0).put("rotation", 0.0),
            )
        }
    }

    PanelFrame("Basic", vm, height = 210.dp, left = {
        Row(Modifier.clickable(onClick = ::reset).padding(vertical = 8.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(LucideIcons.RotateCcw, Palette.DockText, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            Text("Reset", style = Type.Body.copy(color = Palette.DockText))
        }
    }) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Position", "Zoom", "Rotate").forEachIndexed { i, t ->
                Text(
                    t,
                    style = Type.Body.copy(
                        color = if (i == tab) Palette.Text else Palette.TextSecondary,
                        fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    modifier = Modifier.clickable { tab = i }.padding(horizontal = 10.dp, vertical = 12.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
            when (tab) {
                0 -> {
                    RulerRow("X axis", (x * 100).toFloat(), -300f..300f, { set("offsetX", it / 100.0, "basic-x") }, vm::endGesture)
                    RulerRow("Y axis", (-y * 100).toFloat(), -300f..300f, { set("offsetY", -it / 100.0, "basic-y") }, vm::endGesture)
                }
                1 -> RulerRow(null, (scale * 100).toFloat(), 5f..800f, { set("scale", it / 100.0, "basic-zoom") }, vm::endGesture, unit = "%")
                else -> RulerRow(null, rotation.toFloat(), -180f..180f, { set("rotation", it.toDouble(), "basic-rotate") }, vm::endGesture, unit = "°")
            }
        }
    }
}
