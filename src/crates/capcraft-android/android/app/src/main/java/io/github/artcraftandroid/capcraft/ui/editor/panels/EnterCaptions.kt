package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.SpeechModel
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.Voice
import io.github.artcraftandroid.capcraft.core.cancelSpeech
import io.github.artcraftandroid.capcraft.core.downloadSpeechModel
import io.github.artcraftandroid.capcraft.core.voiceModels
import io.github.artcraftandroid.capcraft.core.voices
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lines of our own for Try an example: a short script, a caption a line. */
private const val EXAMPLE = "Morning light over the harbour.\n" +
    "The boats head out before the town wakes.\n" +
    "By noon the market is full of voices.\n" +
    "And at dusk, everyone comes back to the water."

/**
 * Enter captions, as the reference's page: a script typed or pasted in, each line its own caption,
 * from the playhead on. With Voiceover ticked a voice of the phone's reads each line, its caption
 * up for as long as the reading and the readings on an audio row; without it, each caption stays
 * up as long as it takes to read. Next makes them.
 */
@Composable
fun EnterCaptionsPage(vm: EditorViewModel) {
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf("") }
    var voiceover by remember { mutableStateOf(true) }
    var model by remember { mutableStateOf<SpeechModel?>(null) }
    var voiceList by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var voice by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var refresh by remember { mutableStateOf(0) }
    // a press of Next waiting on the voice's download
    var afterDownload by remember { mutableStateOf(false) }
    // the script is written full screen, as the reference's card opens it
    var writing by remember { mutableStateOf(false) }
    if (writing) FullTextEditor(words, onBack = { writing = false }) { typed ->
        words = typed.take(4000)
        writing = false
    }

    LaunchedEffect(refresh) {
        withContext(Threads.thumbnails) {
            // the compact Kokoro first, as text to speech picks it: the smallest download with many voices
            runCatching {
                voiceModels().sortedBy { m -> if (m.id.contains("kokoro-int8")) 0 else if (m.id.contains("kokoro")) 1 else 2 }.firstOrNull()
            }.getOrNull() to runCatching { voices().filter { !it.name.contains("clone", ignoreCase = true) } }.getOrDefault(emptyList())
        }.let { (m, v) ->
            model = m
            voiceList = v.filter { m == null || it.family == m.family }.take(24)
            if (voice == null) voice = voiceList.firstOrNull()?.id
        }
    }
    DisposableEffect(Unit) { onDispose { if (busy != null) runCatching { cancelSpeech() } } }

    fun make() {
        val s = vm.session ?: return
        val at = vm.playhead
        val m = model
        val v = voice
        if (!voiceover || m == null || v == null) {
            vm.edit { enterCaptions(words, at) }
            vm.panel = null
            vm.message = "Captions added"
            return
        }
        busy = "Reading aloud" to 0f
        scope.launch(Threads.commands) {
            runCatching { s.enterCaptionsSpoken(words, at, m.id, v, listener(scope, vm, { busy = it }, { refresh++; if (afterDownload) { afterDownload = false } })) }
                .onFailure { e -> launch(Dispatchers.Main) { busy = null; vm.message = e.userMessage } }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { vm.panel = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF263040), Color(0xFF1A1A1A), Color(0xFF161616))))
                .statusBarsPadding().navigationBarsPadding().imePadding(),
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable { vm.panel = null }, contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.X, Palette.Text, size = 24.dp, description = "Close")
                }
                Spacer(Modifier.weight(1f))
                val ready = words.isNotBlank() && busy == null
                Text(
                    "Next",
                    style = Type.BodyStrong.copy(fontSize = 15.sp, color = if (ready) Color.Black else Color(0x99000000)),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (ready) Palette.Accent else Palette.Accent.copy(alpha = 0.45f))
                        .clickable(enabled = ready) {
                            val m = model
                            if (voiceover && m != null && !m.downloaded) {
                                // the voice is fetched once, then the captions are made
                                afterDownload = true
                                busy = "Downloading the voice" to 0f
                                runCatching {
                                    downloadSpeechModel(m.id, m.family, listener(scope, vm, { busy = it }) {
                                        refresh++
                                        if (afterDownload) {
                                            afterDownload = false
                                            busy = null
                                            make()
                                        }
                                    })
                                }.onFailure { busy = null; afterDownload = false; vm.message = it.userMessage }
                            } else {
                                make()
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                )
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                // the script
                Column(
                    Modifier.fillMaxWidth().height(372.dp).clip(RoundedCornerShape(14.dp))
                        .background(Brush.verticalGradient(listOf(Color(0xFF3A3D44), Color(0xFF2A2B2E))))
                        .padding(16.dp),
                ) {
                    Glyph(LucideIcons.Quote, Color(0xFF6C6F76), size = 26.dp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().weight(1f).clickable { writing = true }) {
                        if (words.isNotEmpty()) {
                            Text(words, style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text, lineHeight = 24.sp))
                        } else {
                            Column {
                                Text("Enter text", style = Type.Body.copy(fontSize = 16.sp, color = Palette.TextSecondary))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Type or paste a script. Each line becomes a caption of its own.",
                                    style = Type.Body.copy(fontSize = 16.sp, color = Palette.TextSecondary, lineHeight = 24.sp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "Try an example",
                                    style = Type.Body.copy(color = Palette.Text),
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Color(0xFF6C6F76), RoundedCornerShape(8.dp))
                                        .clickable { words = EXAMPLE }.padding(horizontal = 12.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                // read aloud, or not
                Row(Modifier.clickable { voiceover = !voiceover }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape)
                            .then(if (voiceover) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.TextSecondary, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) { if (voiceover) Glyph(LucideIcons.Check, Color.Black, size = 14.dp) }
                    Spacer(Modifier.width(10.dp))
                    Text("Voiceover", style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text))
                }
                if (voiceover) {
                    Spacer(Modifier.height(10.dp))
                    val size = model?.let { if (it.downloaded) "" else " · %.0f MB download".format(it.sizeBytes.toLong() / 1_048_576.0) }.orEmpty()
                    Text("Read by: Text to speech$size", style = Type.Small.copy(color = Palette.TextSecondary))
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        voiceList.forEach { v ->
                            val on = voice == v.id
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2C2C2C))
                                    .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                                    .clickable { voice = v.id }.padding(4.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    voiceName(v.name),
                                    style = Type.Small.copy(color = if (on) Palette.Text else Palette.TextSecondary, fontWeight = FontWeight.Medium),
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
                busy?.let { (label, fraction) ->
                    Spacer(Modifier.height(24.dp))
                    Text(label, style = Type.Small)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = Palette.Accent, trackColor = Palette.Disabled)
                    Spacer(Modifier.height(10.dp))
                    Text("Cancel", style = Type.BodyStrong, modifier = Modifier.clickable { runCatching { cancelSpeech() }; busy = null; afterDownload = false })
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** A voice's name as people read it: "af_heart" is Heart. */
private fun voiceName(raw: String): String = raw.split('_').last().replaceFirstChar { it.uppercase() }

/** Reports a speech job on the page, and closes it with the captions made. */
private fun listener(
    scope: kotlinx.coroutines.CoroutineScope,
    vm: EditorViewModel,
    onBusy: (Pair<String, Float>?) -> Unit,
    onFetched: () -> Unit,
): SpeechListener = object : SpeechListener {
    override fun onProgress(fraction: Float, stage: String) {
        scope.launch(Dispatchers.Main) {
            onBusy(
                when (stage) {
                    "download" -> "Downloading the voice"
                    "unpack" -> "Unpacking"
                    "load" -> "Loading the voice"
                    else -> "Reading aloud"
                } to fraction,
            )
        }
    }

    override fun onDone(result: TimelineView?) {
        scope.launch(Dispatchers.Main) {
            if (result != null) {
                onBusy(null)
                vm.applyView(result)
                vm.panel = null
                vm.message = "Captions added"
            } else {
                // a download finished
                onFetched()
            }
        }
    }

    override fun onFailed(message: String) {
        scope.launch(Dispatchers.Main) {
            onBusy(null)
            vm.message = message
        }
    }
}
