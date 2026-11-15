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
import androidx.compose.runtime.DisposableEffect
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
import io.github.artcraftandroid.capcraft.core.cancelIsolateVoice
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the timeline keeps each voice-only copy's original (the bridge's `ISOLATED`). */
private const val ISOLATED = "capcraftIsolated"

/**
 * Isolate voice: the clip's sound with only the voice kept, cleaned on the phone by a small speech
 * network fetched the first time. Switched off, the clip has its own sound again.
 */
@Composable
fun IsolateVoicePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val scope = rememberCoroutineScope()
    val on = remember(clip.json, vm.timeline?.revision) {
        runCatching { vm.session?.extra(ISOLATED)?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.has(clip.path) }.getOrNull() == true
    }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    DisposableEffect(Unit) { onDispose { if (job != null) runCatching { cancelIsolateVoice() } } }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { job = (if (stage == "download") "Downloading the voice model" else "Isolating the voice") to fraction }
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
        if (want) job = "Isolating the voice" to 0f
        vm.stop()
        scope.launch(Threads.commands) {
            runCatching { s.isolateVoice(clip.id, want, listener) }
                .onFailure { e -> launch(Dispatchers.Main) { job = null; vm.message = e.userMessage } }
        }
    }
    PanelFrame("Isolate voice", vm, height = 210.dp, titleOnTop = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E2E))
                    .clickable(enabled = job == null && clip.audible) { set(!on) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Isolate voice", style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text), modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Accent else Color(0xFF555555)).padding(3.dp),
                    contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
                ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White)) }
            }
            Spacer(Modifier.height(12.dp))
            val running = job
            if (running != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${running.first} ${(running.second * 100).toInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                    Text(
                        "Stop",
                        style = Type.Small.copy(color = Palette.Text),
                        modifier = Modifier.clickable { runCatching { cancelIsolateVoice() }; job = null }.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A))) {
                    Box(Modifier.fillMaxWidth(running.second.coerceIn(0f, 1f)).height(3.dp).background(Palette.Accent))
                }
            } else {
                Text(
                    if (clip.audible) "Keeps the voice and takes out music and noise behind it." else "This clip has no sound.",
                    style = Type.Small.copy(color = Palette.TextSecondary),
                )
            }
        }
    }
}
