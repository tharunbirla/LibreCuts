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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.artcraftandroid.capcraft.core.cancelEnhance
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Enhance: the selected clip shown from a clearer copy the engine makes on the phone, or from its
 * original again. The first time fetches the model and makes the copy; after that the switch only
 * chooses which of the two the clip shows.
 */
@Composable
fun EnhancePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    val on = remember(clip.json) { vm.clipJson(clip.id)?.optJSONObject("enhanced")?.optBoolean("on") == true }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { job = (if (stage == "download") "Downloading" else "Enhancing") to fraction }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    if (result != null) vm.applyView(result)
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    vm.message = message
                }
            }
        }
    }
    fun set(want: Boolean) {
        val s = vm.session ?: return
        if (want) job = "Starting" to 0f
        scope.launch(Threads.commands) {
            runCatching { s.enhance(clip.id, want, listener) }
                .onFailure { e -> launch(Dispatchers.Main) { job = null; vm.message = e.userMessage } }
        }
    }
    PanelFrame("Super resolution", vm, height = 210.dp, titleOnTop = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E2E))
                    .clickable(enabled = job == null) { set(!on) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Super resolution", style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Accent else Color(0xFF555555)).padding(3.dp),
                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White)) }
            }
            Spacer(Modifier.height(12.dp))
            val running = job
            if (running != null) {
                Text("${running.first} ${(running.second * 100).toInt()}%", style = Type.Small.copy(color = Palette.Text))
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A))) {
                    Box(Modifier.fillMaxWidth(running.second.coerceIn(0f, 1f)).height(4.dp).background(Palette.Accent))
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Stop",
                    style = Type.BodyStrong.copy(color = Palette.DockText),
                    modifier = Modifier.clickable { runCatching { cancelEnhance() }; job = null }.padding(vertical = 4.dp),
                )
            } else {
                Text(
                    "A clearer, sharper picture, made on this phone. A photo takes a moment; a video goes frame by frame and takes a while.",
                    style = Type.Small.copy(color = Palette.TextSecondary),
                )
            }
        }
    }
}
