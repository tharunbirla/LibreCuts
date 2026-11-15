package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.SpeechModel
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.TranscriptLine
import io.github.artcraftandroid.capcraft.core.TranscriptListener
import io.github.artcraftandroid.capcraft.core.cancelSpeech
import io.github.artcraftandroid.capcraft.core.captionModels
import io.github.artcraftandroid.capcraft.core.downloadSpeechModel
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The transcript: what is said on the timeline, line by line, to edit the video by its words. A
 * tap on a line moves the playhead there; deleting a line takes its stretch out of the main track
 * and the rest closes up. Heard on the phone, as the captions are.
 */
@Composable
fun TranscriptPanel(vm: EditorViewModel) {
    val scope = rememberCoroutineScope()
    var model by remember { mutableStateOf<SpeechModel?>(null) }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    // set by a finished download, so transcribing follows on its own
    var then by remember { mutableStateOf(false) }
    val lines = vm.transcript

    LaunchedEffect(refresh) {
        model = withContext(Threads.thumbnails) {
            runCatching { captionModels().filter { it.id == "tiny" || it.id == "base" } }.getOrDefault(emptyList())
                .let { list -> list.firstOrNull { it.downloaded } ?: list.firstOrNull() }
        }
    }
    DisposableEffect(Unit) { onDispose { if (job != null) runCatching { cancelSpeech() } } }

    fun stageName(stage: String) = when (stage) {
        "download" -> "Downloading"
        "unpack" -> "Unpacking"
        "read" -> "Reading the sound"
        "load" -> "Loading"
        else -> "Listening"
    }

    val listener = remember {
        object : TranscriptListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { job = stageName(stage) to fraction }
            }

            override fun onDone(lines: List<TranscriptLine>) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    vm.transcript = lines
                    if (lines.isEmpty()) vm.message = "No speech was found"
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
    val download = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) { job = stageName(stage) to fraction }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    then = true
                    refresh++
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

    fun transcribe() {
        val m = model ?: return
        if (!m.downloaded) {
            job = "Downloading" to 0f
            runCatching { downloadSpeechModel(m.id, m.family, download) }.onFailure { job = null; vm.message = it.userMessage }
            return
        }
        val s = vm.session ?: return
        job = "Reading the sound" to 0f
        scope.launch(Threads.commands) {
            runCatching { s.transcript(m.id, listener) }
                .onFailure { e -> launch(Dispatchers.Main) { job = null; vm.message = e.userMessage } }
        }
    }
    LaunchedEffect(model) {
        if (then && model?.downloaded == true) {
            then = false
            transcribe()
        }
    }

    fun cut(index: Int) {
        val all = vm.transcript ?: return
        val line = all.getOrNull(index) ?: return
        val gone = line.endUs - line.startUs
        vm.edit { cutRange(line.startUs, line.endUs) }
        // the lines after it come forward by what was taken out
        vm.transcript = all.filterIndexed { i, _ -> i != index }.map { l ->
            if (l.startUs >= line.endUs) TranscriptLine(l.startUs - gone, l.endUs - gone, l.text) else l
        }
    }

    PanelFrame("Transcript", vm, tall = true, titleOnTop = true, left = {
        if (lines != null && job == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Again",
                    style = Type.Small.copy(color = Palette.DockText),
                    modifier = Modifier.clickable { transcribe() }.padding(8.dp),
                )
                // the quiet between what is said, cut out; the lines move up to match
                Text(
                    "Remove pauses",
                    style = Type.Small.copy(color = Palette.Accent),
                    modifier = Modifier.clickable {
                        vm.edit {
                            val done = removePauses(400_000, lines)
                            scope.launch(Dispatchers.Main) { vm.transcript = done.lines }
                            done.view
                        }
                    }.padding(8.dp),
                )
            }
        }
    }) {
        val running = job
        when {
            running != null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(30.dp))
                Text("${running.first} ${(running.second * 100).toInt()}%", style = Type.BodyStrong)
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A))) {
                    Box(Modifier.fillMaxWidth(running.second.coerceIn(0f, 1f)).height(4.dp).background(Palette.Accent))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    "Stop",
                    style = Type.BodyStrong.copy(color = Palette.DockText),
                    modifier = Modifier.clickable { runCatching { cancelSpeech() }; job = null }.padding(8.dp),
                )
            }
            lines == null -> Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                Glyph(LucideIcons.FileText, Palette.DockText, size = 34.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    "Turn what is said into text, then cut the video by deleting its lines.",
                    style = Type.Body.copy(color = Palette.Text, textAlign = TextAlign.Center),
                )
                Spacer(Modifier.height(22.dp))
                val m = model
                Box(
                    Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Accent)
                        .clickable(enabled = m != null) { transcribe() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (m != null && !m.downloaded) "Download and transcribe (%.0f MB)".format(m.sizeBytes.toLong() / 1_048_576.0) else "Transcribe",
                        style = Type.BodyStrong.copy(fontSize = 16.sp, color = Palette.OnAccent),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Made on this phone; nothing is uploaded.", style = Type.Tiny.copy(color = Palette.TextSecondary))
            }
            lines.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No speech was found", style = Type.Body.copy(color = Palette.TextSecondary))
            }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                itemsIndexed(lines, key = { _, l -> "${l.startUs}-${l.text}" }) { i, line ->
                    val now = vm.playhead in line.startUs until line.endUs
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(if (now) Color(0xFF2F3A3C) else Color.Transparent)
                            .clickable { vm.scrub(line.startUs) }
                            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(clock(line.startUs), style = Type.Small.copy(fontSize = 12.sp, color = Palette.TextSecondary), modifier = Modifier.width(46.dp))
                        Text(
                            line.text,
                            style = Type.Body.copy(fontSize = 15.sp, color = if (now) Palette.Accent else Palette.Text),
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier.size(38.dp).clip(CircleShape).clickable { cut(i) },
                            contentAlignment = Alignment.Center,
                        ) { Glyph(LucideIcons.Trash2, Palette.TextSecondary, size = 18.dp, description = "Cut this line") }
                    }
                }
            }
        }
    }
}
