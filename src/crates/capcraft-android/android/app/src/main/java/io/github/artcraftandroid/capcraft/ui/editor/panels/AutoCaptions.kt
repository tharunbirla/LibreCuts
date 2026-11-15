package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.SpeechModel
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.core.captionModels
import io.github.artcraftandroid.capcraft.core.cancelSpeech
import io.github.artcraftandroid.capcraft.core.downloadSpeechModel
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
import org.json.JSONArray
import org.json.JSONObject

/** A look for the captions about to be made: fill, outline, plate and shadow, and a face. */
private class CaptionLook(
    val name: String,
    val color: String = "#ffffff",
    val stroke: String? = null,
    val plate: String? = null,
    val shadow: Boolean = false,
    val family: String? = null,
    val weight: Int = 700,
)

private val CAPTION_LOOKS = listOf(
    CaptionLook("Default"),
    CaptionLook("Outline", stroke = "#000000"),
    CaptionLook("Yellow", color = "#ffd60a", stroke = "#000000", family = "Montserrat", weight = 900),
    CaptionLook("Boxed", plate = "#000000"),
    CaptionLook("Pink", plate = "#ff2d55"),
    CaptionLook("Bold", shadow = true, family = "Anton", weight = 400),
    CaptionLook("Clean", color = "#000000", plate = "#ffffff"),
    CaptionLook("Soft", shadow = true, family = "Montserrat", weight = 700),
)

/** The timeline's captions, as Auto captions and the caption files keep their ids. */
private fun captionIds(vm: EditorViewModel): List<String> =
    runCatching { vm.session?.extra("capcraftCaptions")?.takeIf { it.isNotBlank() }?.let(::JSONArray) }.getOrNull()
        ?.let { ids -> (0 until ids.length()).map { ids.optString(it) } }
        ?.filter { vm.clipJson(it) != null }
        .orEmpty()

/** [look] put on every caption, in one step; true when there were any. */
private fun dressCaptions(vm: EditorViewModel, look: CaptionLook): Boolean {
    val batch = JSONArray()
    captionIds(vm).forEach { id ->
        val text = vm.clipJson(id)?.optJSONObject("text") ?: return@forEach
        text.put("color", look.color)
        if (look.stroke != null) {
            text.put("strokeWidth", 0.004)
            text.put("strokeColor", look.stroke)
        } else {
            text.put("strokeWidth", 0.0)
        }
        text.put("background", look.plate ?: "")
        text.put("shadow", look.shadow)
        look.family?.let { text.put("fontFamily", it) }
        text.put("fontWeight", look.weight)
        batch.put(JSONObject().put("op", "updateClip").put("clipId", id).put("patch", JSONObject().put("text", text)))
    }
    if (batch.length() > 0) vm.edit { applyBatch(batch.toString()) }
    return batch.length() > 0
}

/**
 * Caption templates: our looks for captions, four across, one tap putting a look on every caption
 * at once, as the reference's Captions group offers its own.
 */
@Composable
fun CaptionTemplatesPanel(vm: EditorViewModel) {
    var chosen by remember { mutableIntStateOf(-1) }
    val any = remember(vm.timeline?.revision) { captionIds(vm).isNotEmpty() }
    PanelFrame("Caption templates", vm, height = 260.dp) {
        if (!any) {
            Text(
                "Make captions first - with Auto captions, Enter captions or a caption file - then give them all a look here.",
                style = Type.Small,
                modifier = Modifier.padding(24.dp),
            )
            return@PanelFrame
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CAPTION_LOOKS.chunked(4).forEachIndexed { row, looks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    looks.forEachIndexed { i, l ->
                        val index = row * 4 + i
                        LookChip(l, index == chosen) {
                            chosen = index
                            dressCaptions(vm, l)
                        }
                    }
                }
            }
        }
    }
}

/** The language a caption model hears, as the sheet names it. */
private fun languageOf(m: SpeechModel): String = when (m.id) {
    "tiny" -> "Auto detect (fast)"
    "base" -> "Auto detect"
    "base.en" -> "English"
    else -> m.label
}

/**
 * Auto captions, laid out as the reference's sheet over a dimmed preview: what is heard, the
 * spoken language, a look for the captions, and one Generate button. Everything runs on the
 * phone; a model is downloaded the first time it is chosen. With [lyrics] it is Auto lyrics: the
 * voice is cleaned out of the music first, so what is sung is heard.
 */
@Composable
fun AutoCaptionsSheet(vm: EditorViewModel, lyrics: Boolean = false) {
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<SpeechModel>>(emptyList()) }
    var model by remember { mutableIntStateOf(0) }
    var choosing by remember { mutableStateOf(false) }
    var look by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    // set by a finished download, so generating follows on its own
    var then by remember { mutableStateOf(false) }
    val existing = remember(vm.timeline?.revision) { runCatching { vm.session?.captionCount()?.toInt() }.getOrNull() ?: 0 }
    var replace by remember { mutableStateOf(true) }
    var uppercase by remember { mutableStateOf(false) }
    var removePunctuation by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        models = withContext(Threads.thumbnails) {
            runCatching { captionModels().filter { it.id == "tiny" || it.id == "base" || it.id == "base.en" } }.getOrDefault(emptyList())
        }
    }
    DisposableEffect(Unit) { onDispose { if (job != null) runCatching { cancelSpeech() } } }

    /** The chosen look put on every caption the timeline lists. */
    fun dress() {
        if (look != 0) dressCaptions(vm, CAPTION_LOOKS[look])
    }

    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) {
                    val label = when (stage) {
                        "download" -> "Downloading"
                        "unpack" -> "Unpacking"
                        "read" -> "Reading the sound"
                        "load" -> "Loading"
                        // lyrics: the voice parted from the music before it is heard
                        "work" -> "Separating the voice"
                        else -> "Recognizing"
                    }
                    job = label to fraction
                }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    job = null
                    if (result != null) {
                        vm.applyView(result)
                        dress()
                        vm.panel = null
                        vm.message = "Captions added"
                    } else {
                        then = true
                        refresh++
                    }
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

    fun generate() {
        val m = models.getOrNull(model) ?: return
        if (!m.downloaded) {
            job = "Downloading" to 0f
            runCatching { downloadSpeechModel(m.id, m.family, listener) }.onFailure { job = null; vm.message = it.userMessage }
            return
        }
        val s = vm.session ?: return
        job = "Reading the sound" to 0f
        val shouldReplace = replace && existing > 0
        val useUppercase = uppercase
        val stripPunctuation = removePunctuation
        scope.launch(Threads.commands) {
            runCatching {
                if (lyrics) s.autoLyrics(m.id, shouldReplace, useUppercase, stripPunctuation, listener)
                else s.autoCaptions(m.id, shouldReplace, useUppercase, stripPunctuation, listener)
            }
                .onFailure { e -> launch(Dispatchers.Main) { job = null; vm.message = e.userMessage } }
        }
    }
    LaunchedEffect(models) {
        if (then && models.getOrNull(model)?.downloaded == true) {
            then = false
            generate()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // the dimmed picture behind; a tap there closes the sheet
        Box(
            Modifier.fillMaxSize().background(Color(0x99000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (job == null) vm.panel = null },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).background(Palette.DockSelected)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 14.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(54.dp), contentAlignment = Alignment.Center) {
                Text(if (lyrics) "Auto lyrics" else "Auto captions", style = Type.BodyStrong.copy(fontSize = 17.sp, color = Palette.Text))
                Box(
                    Modifier.align(Alignment.CenterEnd).size(40.dp).clickable {
                        if (job != null) runCatching { cancelSpeech() }
                        vm.panel = null
                    },
                    contentAlignment = Alignment.CenterEnd,
                ) { Glyph(LucideIcons.X, Palette.Text, size = 24.dp, description = "Close") }
            }
            Card {
                SheetRow(LucideIcons.ScanText, "Generate from", "All sound", chevron = false) {}
            }
            Spacer(Modifier.height(12.dp))
            Card {
                val m = models.getOrNull(model)
                SheetRow(LucideIcons.Languages, "Spoken language", m?.let(::languageOf) ?: "…", chevron = true, open = choosing) { choosing = !choosing }
                if (choosing) {
                    models.forEachIndexed { i, option ->
                        Row(
                            Modifier.fillMaxWidth().height(44.dp).clickable { model = i; choosing = false }.padding(start = 48.dp, end = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(languageOf(option), style = Type.Body.copy(color = if (i == model) Palette.Accent else Palette.Text), modifier = Modifier.weight(1f))
                            Text(
                                mb(option.sizeBytes) + if (option.downloaded) "" else "  ↓",
                                style = Type.Small.copy(color = Palette.TextSecondary),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph(LucideIcons.Captions, Palette.Text, size = 20.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Style", style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text))
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CAPTION_LOOKS.forEachIndexed { i, l -> LookChip(l, i == look) { look = i } }
                }
            }
            Spacer(Modifier.height(12.dp))
            Card {
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable(enabled = job == null) { uppercase = !uppercase }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Uppercase captions", style = Type.Body.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                    Switch(uppercase)
                }
                Row(
                    Modifier.fillMaxWidth().height(48.dp).clickable(enabled = job == null) { removePunctuation = !removePunctuation }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Remove . , ! ? ; :", style = Type.Body.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                    Switch(removePunctuation)
                }
            }
            if (existing > 0) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp).clickable { replace = !replace }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Glyph(LucideIcons.Replace, Palette.Text, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Replace current captions", style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text), modifier = Modifier.weight(1f))
                        Switch(replace)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val running = job
            Box(
                Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(8.dp)).background(if (running == null) Palette.Accent else Color(0xFF3A3A3A))
                    .clickable(enabled = running == null && models.isNotEmpty()) { generate() },
                contentAlignment = Alignment.Center,
            ) {
                if (running != null) {
                    // the share done fills the button from the left
                    Box(Modifier.align(Alignment.CenterStart).fillMaxWidth(running.second.coerceIn(0f, 1f)).height(46.dp).background(Palette.Accent.copy(alpha = 0.55f)))
                }
                val m = models.getOrNull(model)
                Text(
                    when {
                        running != null -> "${running.first} ${(running.second * 100).toInt()}%"
                        m != null && !m.downloaded -> "Download and generate (${mb(m.sizeBytes)})"
                        else -> "Generate"
                    },
                    style = Type.BodyStrong.copy(fontSize = 16.sp, color = if (running == null) Palette.OnAccent else Palette.Text),
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (running != null) "Tap the cross to stop" else "Made on this phone; nothing is uploaded.",
                style = Type.Tiny.copy(color = Palette.TextSecondary),
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

/** A rounded card on the sheet. */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2E2E2E))) { content() }
}

/** One setting on the sheet: an icon, its name, and what it is set to, with a chevron when it opens. */
@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, chevron: Boolean, open: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(enabled = chevron, onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, Palette.Text, size = 20.dp)
        Spacer(Modifier.width(12.dp))
        Text(label, style = Type.Body.copy(fontSize = 16.sp, color = Palette.Text), modifier = Modifier.weight(1f))
        Text(value, style = Type.Body.copy(color = Palette.TextSecondary))
        if (chevron) {
            Spacer(Modifier.width(4.dp))
            Glyph(if (open) LucideIcons.ChevronDown else LucideIcons.ChevronRight, Palette.TextSecondary, size = 18.dp)
        }
    }
}

/** A caption look's tile: sample words drawn in it, ringed when chosen. */
@Composable
private fun LookChip(l: CaptionLook, selected: Boolean, onClick: () -> Unit) {
    val faces = rememberFontFaces().toMap()
    Box(
        Modifier.size(78.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A3A))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (l.name == "Default") {
            Text("Default", style = Type.Body.copy(color = Palette.Text))
            return@Box
        }
        val colour = runCatching { Color(android.graphics.Color.parseColor(l.color)) }.getOrDefault(Color.White)
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).background(l.plate?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            val base = Type.BodyStrong.copy(
                fontSize = 15.sp,
                fontWeight = FontWeight(l.weight),
                fontFamily = l.family?.let { faces[it] },
                shadow = if (l.shadow) androidx.compose.ui.graphics.Shadow(Color(0xCC000000), androidx.compose.ui.geometry.Offset(2f, 3f), 6f) else null,
            )
            if (l.stroke != null) {
                Text("Hi there", style = base.copy(color = Color(android.graphics.Color.parseColor(l.stroke)), drawStyle = Stroke(width = 5f), shadow = null))
            }
            Text("Hi there", style = base.copy(color = colour))
        }
    }
}

/** A small on/off switch drawn as the editor's others are. */
@Composable
private fun Switch(on: Boolean) {
    Box(
        Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Palette.Accent else Color(0xFF555555)).padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(18.dp).clip(CircleShape).background(Color.White)) }
}

private fun mb(bytes: ULong): String = "%.0f MB".format(bytes.toLong() / 1_048_576.0)
