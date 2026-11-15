package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import org.json.JSONObject

/** The thickest outline the Outline slider reaches, as a fraction of the frame's height. */
private const val MAX_OUTLINE = 0.04

/**
 * A shape's look: its fill (or none, for an outline alone) and its outline's colour and thickness,
 * set on the engine's shape style. A line and an arrow are all outline.
 */
@Composable
fun ShapeStylePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip?.takeIf { it.kind == "shape" } ?: run { vm.panel = null; return }
    val style = remember(clip.json) { vm.clipJson(clip.id)?.optJSONObject("shape") ?: JSONObject() }
    val drawn = style.optString("kind") == "line" || style.optString("kind") == "arrow"
    var part by remember { mutableIntStateOf(if (drawn) 1 else 0) }
    var thickness by remember(clip.id) { mutableStateOf<Float?>(null) }

    /** The shape style with [edit] made, sent as one change folded into [gesture]. */
    fun change(gesture: String?, edit: JSONObject.() -> Unit) {
        val next = JSONObject(style.toString())
        next.edit()
        vm.patch(clip.id, JSONObject().put("shape", next), gesture)
    }
    PanelFrame("Style", vm, height = 200.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            listOf("Fill", "Outline").forEachIndexed { i, label ->
                if (drawn && i == 0) return@forEachIndexed
                Text(
                    label,
                    style = Type.Small.copy(
                        color = if (i == part) Palette.Text else Palette.TextSecondary,
                        fontWeight = if (i == part) FontWeight.SemiBold else FontWeight.Normal,
                        fontSize = 14.sp,
                    ),
                    modifier = Modifier.clickable { part = i }.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            if (part == 0) {
                SwatchRow(style.optString("fill").takeIf { it.length >= 7 }?.take(7), allowNone = true, vm = vm) { c ->
                    change(null) { put("fill", c ?: "") }
                }
            } else {
                val width = style.optDouble("strokeWidth", 0.0)
                SwatchRow(if (width > 0) style.optString("stroke", "#ffffff").takeIf { it.length >= 7 }?.take(7) else null, allowNone = !drawn, vm = vm) { c ->
                    change(null) {
                        if (c == null) {
                            put("strokeWidth", 0.0)
                        } else {
                            put("stroke", c)
                            if (width <= 0) put("strokeWidth", 0.006)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ValueSlider(
                    value = thickness ?: (width / MAX_OUTLINE * 100).toFloat().coerceIn(0f, 100f),
                    range = 0f..100f,
                    label = { "${it.roundToInt()}" },
                    onChange = { v ->
                        thickness = v
                        change("shape-outline") { put("strokeWidth", v / 100.0 * MAX_OUTLINE) }
                    },
                    onDone = {
                        thickness = null
                        vm.endGesture()
                    },
                    title = "Thickness",
                )
            }
        }
    }
}
