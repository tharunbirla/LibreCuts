package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.SpeechModel
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.Voice
import io.github.artcraftandroid.capcraft.core.cancelSpeech
import io.github.artcraftandroid.capcraft.core.captionModels
import io.github.artcraftandroid.capcraft.core.downloadSpeechModel
import io.github.artcraftandroid.capcraft.core.voiceModels
import io.github.artcraftandroid.capcraft.core.voices
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the panel is doing. */
private sealed interface Job {
    data object Idle : Job
    data class Running(val label: String, val fraction: Float) : Job
}

/**
 * Auto captions ([captions]) or reading the title [clipId] aloud. The model is downloaded once, on
 * first use, with its size shown; everything then runs on the phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechPanel(vm: EditorViewModel, captions: Boolean, clipId: String?) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<SpeechModel>>(emptyList()) }
    var voiceList by remember { mutableStateOf<List<Voice>>(emptyList()) }
    var model by remember { mutableIntStateOf(0) }
    var voice by remember { mutableStateOf<Int?>(null) }
    var job by remember { mutableStateOf<Job>(Job.Idle) }
    var refresh by remember { mutableIntStateOf(0) }
    // captions made before, which new ones replace unless this is unticked
    val existing = remember(vm.timeline?.revision) { runCatching { vm.session?.captionCount()?.toInt() }.getOrNull() ?: 0 }
    var replace by remember { mutableStateOf(true) }
    // with no title to read, the words are typed here (text to audio)
    var words by remember { mutableStateOf("") }

    LaunchedEffect(refresh) {
        withContext(Threads.thumbnails) {
            runCatching {
                if (captions) {
                    // the smallest multilingual model and the English one
                    captionModels().filter { it.id == "tiny" || it.id == "base.en" || it.id == "base" }
                } else {
                    // the compact Kokoro first: the smallest download that reads in many voices
                    voiceModels().sortedBy { m ->
                        when {
                            m.id.contains("kokoro-int8") -> 0
                            m.id.contains("kokoro") -> 1
                            else -> 2
                        }
                    }
                }
            }.getOrDefault(emptyList()) to runCatching {
                // voices read as they are, not cloned from a recording
                if (captions) emptyList() else voices().filter { !it.name.contains("clone", ignoreCase = true) }
            }.getOrDefault(emptyList())
        }.let { (m, v) ->
            models = m
            voiceList = v
        }
    }
    DisposableEffect(Unit) { onDispose { if (job is Job.Running) runCatching { cancelSpeech() } } }

    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) {
                    val label = when (stage) {
                        "download" -> "Downloading"
                        "unpack" -> "Unpacking"
                        "read" -> "Reading the sound"
                        "load" -> "Loading"
                        else -> if (captions) "Recognizing" else "Generating"
                    }
                    job = Job.Running(label, fraction)
                }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    job = Job.Idle
                    if (result != null) {
                        vm.applyView(result)
                        vm.panel = null
                        vm.message = if (captions) "Captions added" else "Speech added"
                    } else {
                        refresh++
                    }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    job = Job.Idle
                    vm.message = message
                }
            }
        }
    }

    val chosen = models.getOrNull(model)
    val typed = !captions && clipId == null
    PanelFrame(if (captions) "Auto captions" else if (typed) "Text to audio" else "Text to speech", vm, tall = true) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            if (typed) {
                androidx.compose.foundation.text.BasicTextField(
                    value = words,
                    onValueChange = { words = it.take(600) },
                    textStyle = Type.Body.copy(color = Palette.Text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Accent),
                    decorationBox = { inner ->
                        Box {
                            if (words.isEmpty()) Text("Type the words to be read aloud", style = Type.Body.copy(color = Palette.TextSecondary))
                            inner()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A3A)).padding(12.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
            Text(if (captions) "Spoken language" else "Voice model", style = Type.Small)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                models.forEachIndexed { i, m ->
                    val label = when (m.id) {
                        "tiny" -> "Auto (fast)"
                        "base" -> "Auto"
                        "base.en" -> "English"
                        else -> m.label
                    }
                    Chip("$label · ${mb(m.sizeBytes)}${if (m.downloaded) "" else " ↓"}", i == model) { model = i }
                }
            }
            if (!captions) {
                Spacer(Modifier.height(12.dp))
                Text("Voice", style = Type.Small)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    voiceList.filter { chosen == null || it.family == chosen.family }.take(40).forEach { v ->
                        Chip(voiceLabel(v.name), voice == v.id) { voice = v.id }
                    }
                }
            }
            if (captions && existing > 0) {
                Spacer(Modifier.height(8.dp))
                CheckChip("Clear current captions", replace) { replace = !replace }
            }
            Spacer(Modifier.height(16.dp))
            when (val j = job) {
                is Job.Running -> {
                    Text(j.label, style = Type.Small)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { j.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = Palette.Accent,
                        trackColor = Palette.Disabled,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Cancel", style = Type.BodyStrong, modifier = Modifier.clickable { runCatching { cancelSpeech() }; job = Job.Idle })
                }
                Job.Idle -> {
                    val m = chosen
                    val ready = m?.downloaded == true
                    val label = when {
                        m == null -> "…"
                        !ready -> "Download (${mb(m.sizeBytes)})"
                        captions -> "Generate"
                        else -> "Generate"
                    }
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (m == null || (!captions && ready && voice == null)) Palette.Card else Palette.Accent)
                            .clickable(enabled = m != null) {
                                val target = m ?: return@clickable
                                if (!target.downloaded) {
                                    job = Job.Running("Downloading", 0f)
                                    runCatching { downloadSpeechModel(target.id, target.family, listener) }
                                        .onFailure { job = Job.Idle; vm.message = it.userMessage }
                                    return@clickable
                                }
                                val s = vm.session ?: return@clickable
                                if (captions) {
                                    job = Job.Running("Reading the sound", 0f)
                                    scope.launch(Threads.commands) {
                                        runCatching { s.autoCaptions(target.id, replace && existing > 0, false, false, listener) }
                                            .onFailure { e -> launch(Dispatchers.Main) { job = Job.Idle; vm.message = e.userMessage } }
                                    }
                                } else {
                                    val v = voice ?: return@clickable
                                    if (typed && words.isBlank()) return@clickable
                                    job = Job.Running("Generating", 0f)
                                    val at = vm.playhead
                                    scope.launch(Threads.commands) {
                                        runCatching {
                                            if (clipId == null) s.speakText(words, at, target.id, v, listener) else s.textToSpeech(clipId, target.id, v, listener)
                                        }.onFailure { e -> launch(Dispatchers.Main) { job = Job.Idle; vm.message = e.userMessage } }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = Type.BodyStrong.copy(color = if (m == null) Palette.TextSecondary else Palette.OnAccent)) }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Runs on this phone. The model is downloaded once.",
                        style = Type.Tiny,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) Palette.Text else Palette.Card).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, style = Type.Small.copy(color = if (selected) Palette.Black else Palette.Text)) }
}

/** A voice's name as people read it: "af_heart" is Heart (US), "bm_george" George (UK). */
private fun voiceLabel(raw: String): String {
    val parts = raw.split('_')
    val name = parts.last().replaceFirstChar { it.uppercase() }
    val accent = when (parts.firstOrNull()?.firstOrNull()) {
        'a' -> " (US)"
        'b' -> " (UK)"
        'j' -> " (JP)"
        'z' -> " (ZH)"
        'e' -> " (ES)"
        'f' -> " (FR)"
        'h' -> " (HI)"
        'i' -> " (IT)"
        'p' -> " (PT)"
        else -> ""
    }
    return if (parts.size >= 2 && parts.first().length == 2) "$name$accent" else name
}

private fun mb(bytes: ULong): String = "%.0f MB".format(bytes.toLong() / 1_048_576.0)

@Suppress("unused")
private val unusedFloat = mutableFloatStateOf(0f)
