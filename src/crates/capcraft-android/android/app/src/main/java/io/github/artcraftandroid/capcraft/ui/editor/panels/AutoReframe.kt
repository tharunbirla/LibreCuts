package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The shapes Auto reframe offers, width to height. */
private val SHAPES = listOf("9:16" to (9 to 16), "16:9" to (16 to 9), "1:1" to (1 to 1), "4:5" to (4 to 5), "4:3" to (4 to 3), "3:4" to (3 to 4))

/**
 * Auto reframe: the frame given a new shape and the selected clip zoomed to fill it, moved along so
 * the person in it stays in view. The person is found on the phone (the model is fetched the
 * first time).
 */
@Composable
fun AutoReframePanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val t = vm.timeline ?: return
    val scope = rememberCoroutineScope()
    val current = SHAPES.indexOfFirst { (_, r) -> r.first * t.height.toInt() == r.second * t.width.toInt() }
    var chosen by remember { mutableIntStateOf(if (current >= 0) current else 0) }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { job = (if (stage == "download") "Downloading the model" else "Finding the person") to fraction }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    result?.let { vm.applyView(it) }
                    vm.panel = null
                    vm.message = "Reframed"
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
    fun reframe() {
        val s = vm.session ?: return
        if (job != null) return
        val (w, h) = SHAPES[chosen].second
        val (fw, fh) = frameFor(w, h)
        job = "Finding the person" to 0f
        vm.stop()
        scope.launch(Threads.commands) {
            runCatching {
                if (fw != t.width.toInt() || fh != t.height.toInt()) s.setCanvas(fw.toUInt(), fh.toUInt())
                s.autoReframe(clip.id, listener)
            }.onFailure { e -> launch(Dispatchers.Main) { job = null; vm.message = e.userMessage } }
        }
    }
    PanelFrame("Auto reframe", vm, height = 250.dp, titleOnTop = true) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
                SHAPES.forEachIndexed { i, (label, r) ->
                    val aspect = r.first.toFloat() / r.second
                    val h = (40f / kotlin.math.sqrt(aspect)).coerceIn(30f, 54f)
                    val w = (h * aspect).coerceAtMost(76f)
                    Column(Modifier.padding(end = 14.dp).clickable { chosen = i }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A3A))
                                .then(if (i == chosen) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(label, style = Type.Small.copy(fontSize = 12.sp, color = if (i == chosen) Palette.Text else Palette.TextSecondary))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val running = job
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(width = 170.dp, height = 40.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (running == null) Palette.Accent else Color(0xFF3A3A3A))
                        .clickable(enabled = running == null, onClick = ::reframe),
                    contentAlignment = Alignment.Center,
                ) {
                    if (running != null) {
                        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(running.second.coerceIn(0f, 1f)).height(40.dp).background(Palette.Accent.copy(alpha = 0.5f)))
                    }
                    Text(
                        if (running == null) "Reframe" else "${(running.second * 100).roundToInt()}%",
                        style = Type.BodyStrong.copy(fontSize = 15.sp, color = Color.White),
                    )
                }
            }
            if (running != null) {
                Spacer(Modifier.height(8.dp))
                Text(running.first, style = Type.Small.copy(color = Palette.TextSecondary), modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Spacer(Modifier.width(1.dp))
            }
        }
    }
}
