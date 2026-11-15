package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.cancelMotionBlur
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the timeline keeps each blurred copy's original and settings (the bridge's `BLURRED`). */
private const val BLURRED = "capcraftBlurred"

private val DIRECTIONS = listOf("Both", "Forward", "Backward")
private val TIMES = listOf("Once", "Twice")

/**
 * Motion blur: what moves in the clip leaves a trail, the frames around each blended into it. Blur
 * says how far it reaches, Blend how much of it shows, Direction whether frames before, after or
 * both count, Times doubles the reach. The tick makes the blurred copy on the phone; Reset puts
 * the original back.
 */
@Composable
fun MotionBlurPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    val saved: JSONObject? = remember(clip.json) {
        runCatching { vm.session?.extra(BLURRED)?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.optJSONObject(clip.path) }.getOrNull()
    }
    var blur by remember(clip.id) { mutableFloatStateOf(saved?.optDouble("blur", 0.0)?.toFloat() ?: 0f) }
    var blend by remember(clip.id) { mutableFloatStateOf(saved?.optDouble("blend", 0.0)?.toFloat() ?: 0f) }
    var direction by remember(clip.id) { mutableIntStateOf(saved?.optInt("direction", 0) ?: 0) }
    var times by remember(clip.id) { mutableIntStateOf((saved?.optInt("times", 1) ?: 1) - 1) }
    var progress by remember { mutableStateOf<Float?>(null) }
    DisposableEffect(Unit) { onDispose { if (progress != null) runCatching { cancelMotionBlur() } } }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { progress = fraction }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    result?.let { vm.applyView(it) }
                    vm.panel = null
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    vm.message = message
                }
            }
        }
    }
    fun run(b: Float, m: Float) {
        val s = vm.session ?: return
        if (progress != null) return
        vm.stop()
        progress = 0f
        scope.launch(Threads.commands) {
            runCatching { s.motionBlur(clip.id, b.roundToInt().toUInt(), m.roundToInt().toUInt(), direction.toUInt(), (times + 1).toUInt(), listener) }
                .onFailure { e -> launch(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
        }
    }
    val changed = saved != null || blur > 0f || blend > 0f
    PanelFrame(
        "Motion blur",
        vm,
        height = 330.dp,
        titleOnTop = true,
        left = {
            Row(
                Modifier.clickable(enabled = changed && progress == null) {
                    blur = 0f
                    blend = 0f
                    direction = 0
                    times = 0
                    if (saved != null) run(0f, 0f)
                }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (changed) Palette.DockText else Palette.Disabled
                Glyph(LucideIcons.RotateCcw, tint, size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text("Reset", style = Type.Small.copy(color = tint))
            }
        },
        onDone = { if (blur > 0f && blend > 0f) run(blur, blend) else if (saved != null) run(0f, 0f) else vm.panel = null },
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            BlurSlider("Blur", blur) { blur = it }
            BlurSlider("Blend", blend) { blend = it }
            Choice("Direction", DIRECTIONS, direction) { direction = it }
            Choice("Times", TIMES, times) { times = it }
            val p = progress
            if (p != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Blurring… ${(p * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                    Text(
                        "Cancel",
                        style = Type.Small.copy(color = Palette.Text),
                        modifier = Modifier.clickable { runCatching { cancelMotionBlur() } }.padding(8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BlurSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Small.copy(fontSize = 13.sp, color = Palette.Text), modifier = Modifier.width(90.dp))
        CapSlider(value, 0f..100f, null, onChange, {}, Modifier.weight(1f), filled = true)
    }
}

/** A setting with a few values, chosen from a menu under a grey button. */
@Composable
private fun Choice(label: String, options: List<String>, chosen: Int, onChoose: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(54.dp).padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Type.Small.copy(fontSize = 13.sp, color = Palette.Text), modifier = Modifier.weight(1f))
        Box {
            Row(
                Modifier.size(width = 100.dp, height = 36.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF3A3A3A))
                    .clickable { open = true },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(options[chosen], style = Type.Small.copy(fontSize = 13.sp, color = Palette.Text))
                Spacer(Modifier.width(4.dp))
                Glyph(io.github.artcraftandroid.capcraft.ui.MaterialIcons.ArrowDropDown, Palette.Text, size = 18.dp)
            }
            DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Color(0xFF2E2E2E)) {
                options.forEachIndexed { i, option ->
                    DropdownMenuItem(
                        text = { Text(option, style = Type.Small.copy(color = if (i == chosen) Palette.Accent else Palette.Text)) },
                        onClick = {
                            onChoose(i)
                            open = false
                        },
                    )
                }
            }
        }
    }
}
