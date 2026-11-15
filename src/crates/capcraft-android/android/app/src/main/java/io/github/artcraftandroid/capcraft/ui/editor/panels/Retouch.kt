package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

private const val RETOUCH = "capcraft.retouch"

/** The retouch link on [clipId] with [key] set to [value], or taken off ([key] null). */
private fun setRetouch(vm: EditorViewModel, clipId: String, key: String?, value: Double, gesture: String?) {
    val chain = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val next = JSONArray()
    var placed = false
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        if (link.optString("id") != RETOUCH) {
            next.put(link)
        } else if (key != null) {
            val params = link.optJSONObject("params") ?: JSONObject()
            next.put(link.put("params", params.put(key, value)))
            placed = true
        }
    }
    if (key != null && !placed) {
        // a new retouch starts with no smoothing, so only the slider moved shows
        next.put(JSONObject().put("id", RETOUCH).put("params", JSONObject().put("soften", 0).put("brighten", 0).put(key, value)))
    }
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/**
 * Retouch: skin smoothed and brightened. Skin is told from the rest by its colour, so a warm
 * wall may soften a little with it.
 */
@Composable
fun RetouchPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val params = remember(clip.json) {
        vm.clipJson(clip.id)?.optJSONArray("videoEffects")?.let { chain ->
            (0 until chain.length()).map { chain.getJSONObject(it) }.firstOrNull { it.optString("id") == RETOUCH }
        }?.optJSONObject("params")
    }
    PanelFrame("Retouch", vm, height = 240.dp, titleOnTop = true, left = {
        Row(
            Modifier.clickable(enabled = params != null) { setRetouch(vm, clip.id, null, 0.0, null) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val tint = if (params != null) Palette.DockText else Palette.Disabled
            Glyph(LucideIcons.RotateCcw, tint, size = 16.dp)
            Spacer(Modifier.width(6.dp))
            Text("Reset", style = Type.Small.copy(color = tint))
        }
    }) {
        Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            RetouchSlider(vm, clip.id, "Smooth", "soften", params?.optDouble("soften", 0.0) ?: 0.0)
            Spacer(Modifier.height(10.dp))
            RetouchSlider(vm, clip.id, "Brighten", "brighten", params?.optDouble("brighten", 0.0) ?: 0.0)
        }
    }
}

@Composable
private fun RetouchSlider(vm: EditorViewModel, clipId: String, label: String, key: String, value: Double) {
    var held by remember(clipId, key) { mutableStateOf<Float?>(null) }
    ValueSlider(
        value = held ?: value.toFloat(),
        range = 0f..100f,
        label = { "${it.roundToInt()}" },
        onChange = {
            held = it
            setRetouch(vm, clipId, key, it.toDouble(), "retouch-$key")
        },
        onDone = {
            held = null
            vm.endGesture()
        },
        title = label,
    )
}
