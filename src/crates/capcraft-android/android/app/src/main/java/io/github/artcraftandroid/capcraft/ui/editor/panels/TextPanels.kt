package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorMetrics
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The fonts the engine draws titles in. */
val FONTS = listOf(
    "Inter", "Montserrat", "Hanken Grotesk", "Space Grotesk", "Anton", "Bebas Neue", "Archivo Black",
    "Abril Fatface", "DM Serif Display", "Lobster", "Pacifico", "Permanent Marker", "Caveat Brush",
    "Bangers", "Staatliches", "Secular One", "Hammersmith One", "Londrina Solid", "Black Ops One",
    "Pixelify Sans", "Rubik Scribble", "Shadows Into Light Two", "Emilys Candy", "Frijole", "Mystery Quest",
    "Atomic Age", "Germania One", "Bungee Hairline", "Zen Tokyo Zoo", "Kosugi Maru",
)

/** The kinds the Fonts tab sorts the families into, the first showing them all. */
private val FONT_KINDS = listOf("All", "Clean", "Bold", "Script", "Serif", "Fun")

/** Which kind each bundled family is shown under, besides All. */
private val FONT_KIND = mapOf(
    "Inter" to "Clean", "Montserrat" to "Clean", "Hanken Grotesk" to "Clean", "Space Grotesk" to "Clean", "Kosugi Maru" to "Clean",
    "Anton" to "Bold", "Bebas Neue" to "Bold", "Archivo Black" to "Bold", "Staatliches" to "Bold", "Secular One" to "Bold",
    "Hammersmith One" to "Bold", "Londrina Solid" to "Bold", "Black Ops One" to "Bold", "Germania One" to "Bold",
    "Lobster" to "Script", "Pacifico" to "Script", "Caveat Brush" to "Script", "Shadows Into Light Two" to "Script", "Permanent Marker" to "Script",
    "Abril Fatface" to "Serif", "DM Serif Display" to "Serif", "Atomic Age" to "Serif",
    "Bangers" to "Fun", "Pixelify Sans" to "Fun", "Rubik Scribble" to "Fun", "Emilys Candy" to "Fun", "Frijole" to "Fun",
    "Mystery Quest" to "Fun", "Bungee Hairline" to "Fun", "Zen Tokyo Zoo" to "Fun",
)

/** The engine's font families, each with a Compose font made from its file (null until loaded). */
@Composable
fun rememberFontFaces(): List<Pair<String, androidx.compose.ui.text.font.FontFamily?>> {
    val faces by androidx.compose.runtime.produceState(FontFaceCache.list) {
        if (FontFaceCache.list.isNotEmpty()) return@produceState
        value = withContext(Threads.thumbnails) {
            runCatching {
                io.github.artcraftandroid.capcraft.core.fontFaces().map { face ->
                    face.family to runCatching {
                        androidx.compose.ui.text.font.FontFamily(
                            androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromFile(face.path)),
                        )
                    }.getOrNull()
                }
            }.getOrDefault(FONTS.map { it to null })
        }.also { FontFaceCache.list = it }
    }
    return faces
}

/** What a font file can be offered as by the phone's file picker. */
private val FONT_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
    "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
)

/** The fonts added to this project, each with its face for drawing its name. */
private fun projectFaces(vm: EditorViewModel): List<Pair<String, androidx.compose.ui.text.font.FontFamily?>> =
    runCatching { vm.session?.projectFonts() }.getOrNull().orEmpty().map { face ->
        face.family to runCatching {
            androidx.compose.ui.text.font.FontFamily(androidx.compose.ui.text.font.Typeface(android.graphics.Typeface.createFromFile(face.path)))
        }.getOrNull()
    }

private object FontFaceCache {
    @Volatile var list: List<Pair<String, androidx.compose.ui.text.font.FontFamily?>> = emptyList()
}

/**
 * Colour swatches for text, strokes and backgrounds: the greys from white to black, then each hue
 * round the wheel in four shades from pale to deep, as a long palette to scroll.
 */
val SWATCHES: List<String> = buildList {
    addAll(listOf("#ffffff", "#d1d1d6", "#8e8e93", "#48484a", "#000000"))
    // hue in degrees and saturation, for each family
    val families = listOf(0f to 0.9f, 25f to 0.95f, 48f to 0.95f, 90f to 0.75f, 140f to 0.7f, 175f to 0.8f, 200f to 0.9f, 220f to 0.9f, 255f to 0.75f, 280f to 0.8f, 320f to 0.8f, 30f to 0.45f)
    for ((hue, saturation) in families) {
        for (lightness in listOf(0.85f, 0.68f, 0.52f, 0.36f)) add(hslHex(hue, saturation, lightness))
    }
}

/** `#rrggbb` for a hue in degrees and a saturation and lightness in 0..1. */
private fun hslHex(h: Float, s: Float, l: Float): String {
    val c = (1 - kotlin.math.abs(2 * l - 1)) * s
    val x = c * (1 - kotlin.math.abs((h / 60f) % 2 - 1))
    val m = l - c / 2
    val (r, g, b) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun byte(v: Float) = ((v + m).coerceIn(0f, 1f) * 255).toInt()
    return "#%02x%02x%02x".format(byte(r), byte(g), byte(b))
}

private fun color(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)

/** A title's style as the engine holds it, with defaults for a new one. */
private fun styleOf(vm: EditorViewModel, clipId: String?): JSONObject =
    clipId?.let { vm.clipJson(it)?.optJSONObject("text") }
        ?: JSONObject()
            .put("content", "")
            .put("fontFamily", "Inter")
            .put("fontSize", 0.06)
            .put("fontWeight", 700)
            .put("color", "#ffffff")
            .put("shadow", false)

/**
 * Adding or editing a title: the words at the top with the keyboard, and the style below. A new
 * title is placed at the playhead with the first letter typed.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TextPanel(vm: EditorViewModel, clipId: String?, startTab: Int = 3) {
    var id by remember { mutableStateOf(clipId) }
    var style by remember { mutableStateOf(styleOf(vm, clipId)) }
    var text by remember { mutableStateOf(style.optString("content")) }
    // 0 is typing (the keyboard up, no tab chosen); 1 to 5 the tabs. A new title starts on the
    // templates with the keyboard down, as the reference opens it; a title being changed starts on
    // its styles, or the tab the tool that opened it names.
    var tab by remember { mutableIntStateOf(if (clipId == null) 1 else startTab) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    // on a tab the sheet towers over the lower part of the preview; while typing the keyboard has the room
    val body = maxOf(300.dp, vm.areaHeight + EditorMetrics.DockHeight - 277.dp - 120.dp)

    // A new title is made once, with the first letter; what is typed while it is being made
    // goes on it as soon as its id comes back.
    var creating by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    // a caption's look goes on every caption, as the reference's sheet does unless this is unticked
    var toAll by remember { mutableStateOf(true) }
    val caption = clipId != null && clipId in vm.captions

    fun push(next: JSONObject, gesture: String?) {
        style = next
        val target = id
        when {
            target != null && caption && toAll -> {
                // each keeps its own words; one step for them all
                val batch = org.json.JSONArray()
                vm.captions.forEach { other ->
                    val look = if (other == target) {
                        next
                    } else {
                        val theirs = vm.clipJson(other)?.optJSONObject("text") ?: return@forEach
                        JSONObject(next.toString()).put("content", theirs.optString("content"))
                    }
                    batch.put(JSONObject().put("op", "updateClip").put("clipId", other).put("patch", JSONObject().put("text", look)))
                }
                vm.command(JSONObject().put("op", "batch").put("commands", batch), gesture)
            }
            target != null -> vm.patch(target, JSONObject().put("text", next), gesture)
            creating -> pending = next
            next.optString("content").isNotBlank() -> {
                creating = true
                vm.addText(next) { made ->
                    id = made
                    creating = false
                    pending?.let { vm.patch(made, JSONObject().put("text", it), gesture) }
                    pending = null
                }
            }
        }
    }

    fun change(gesture: String?, edit: JSONObject.() -> Unit) {
        val next = JSONObject(style.toString())
        next.edit()
        push(next, gesture)
    }

    // favourites: a long press on a template or a font keeps it under the bookmark
    val context = androidx.compose.ui.platform.LocalContext.current
    fun kept(on: Boolean) {
        vm.message = if (on) "Added to favorites" else "Removed from favorites"
    }

    // the words open in a full-screen editor from the field's expand button
    var expanded by remember { mutableStateOf(false) }
    if (expanded) {
        FullTextEditor(text, onBack = { expanded = false }) { words ->
            expanded = false
            text = words
            change(null) { put("content", words) }
        }
    }

    // a font file from the phone, added to the project and set on the title
    val importFont = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFont(uri) { family -> change(null) { put("fontFamily", family) } }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(Palette.DockSelected)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = text,
                onValueChange = {
                    text = it
                    change("text-typing") { put("content", it) }
                },
                textStyle = Type.Body.copy(fontSize = 16.sp),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF3A3A3A))
                    .padding(horizontal = 12.dp, vertical = 11.dp)
                    .focusRequester(focus)
                    .onFocusChanged { if (it.isFocused) tab = 0 },
                decorationBox = { inner ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            if (text.isEmpty()) Text("Enter text", style = Type.Body.copy(fontSize = 16.sp, color = Palette.TextSecondary))
                            inner()
                        }
                        Glyph(
                            LucideIcons.Expand,
                            Palette.DockText,
                            Modifier.padding(start = 8.dp).clickable {
                                keyboard?.hide()
                                expanded = true
                            },
                            size = 18.dp,
                            description = "Expand",
                        )
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.size(40.dp).clickable {
                    vm.endGesture()
                    keyboard?.hide()
                    vm.panel = null
                },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Check, Palette.DockText, size = 26.dp, description = "Done") }
        }
        // the sheet's handle
        Box(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF555555)))
        Tabs(listOf("Templates", "Fonts", "Styles", "Effects", "Animations", "Bubbles"), tab - 1) {
            tab = it + 1
            keyboard?.hide()
            focusManager.clearFocus()
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Timeline))
        // the look on every caption, on the tabs that set it
        if (caption && tab in setOf(1, 2, 3, 6)) {
            Row(
                Modifier.padding(start = 14.dp, top = 8.dp, bottom = 2.dp).clickable { toAll = !toAll }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape)
                        .then(if (toAll) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.TextSecondary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) { if (toAll) Box(Modifier.size(7.dp).clip(CircleShape).background(Color.Black)) }
                Spacer(Modifier.width(8.dp))
                Text("Apply to all captions", style = Type.Small.copy(color = Palette.Text))
            }
        }
        if (tab != 0) {
            // a grid scrolls by itself; the other tabs scroll as a column
            Column(
                Modifier.fillMaxWidth().height(body)
                    .then(if (tab == 5) Modifier else Modifier.verticalScroll(rememberScrollState()))
                    .padding(horizontal = if (tab == 5) 0.dp else 12.dp),
            ) {
                when (tab) {
                    1 -> TemplatesTab(style, onKept = ::kept) { t ->
                        change(null) {
                            put("fontFamily", t.family)
                            put("fontWeight", t.weight)
                            put("italic", t.italic)
                            put("color", t.color)
                            if (t.stroke != null) {
                                put("strokeWidth", 0.005)
                                put("strokeColor", t.stroke)
                            } else {
                                put("strokeWidth", 0.0)
                            }
                            put("background", t.plate ?: "")
                            put("shadow", t.shadow)
                            // an empty title takes the template's words, to be typed over
                            if (text.isBlank()) {
                                text = t.word
                                put("content", t.word)
                            }
                        }
                    }
                    // four across, each name in its own face, as the reference shows its fonts
                    2 -> Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val bundled = rememberFontFaces()
                        val own = remember(vm.timeline?.revision) { projectFaces(vm) }
                        var fontKind by remember { mutableIntStateOf(0) }
                        var fontQuery by remember { mutableStateOf<String?>(null) }
                        var keptFonts by remember { mutableStateOf(false) }
                        val keptSet = remember(Favorites.version.intValue) { Favorites.of(context, "font") }
                        KindsRow(FONT_KINDS, fontKind, fontQuery, { fontKind = it; keptFonts = false }, { fontQuery = it }, "Search fonts", keptFonts) { keptFonts = true }
                        // the Import tile first (the empty name), then the project's own fonts, then everyone's;
                        // a kind shows its own families alone
                        val everyone = bundled.filter { b -> own.none { it.first == b.first } }
                        val fontWords = fontQuery?.trim().orEmpty()
                        val faces = when {
                            fontWords.isNotEmpty() -> (own + everyone).filter { it.first.contains(fontWords, ignoreCase = true) }
                            keptFonts -> (own + everyone).filter { it.first in keptSet }
                            fontKind == 0 -> listOf<Pair<String, androidx.compose.ui.text.font.FontFamily?>>("" to null) + own + everyone
                            else -> everyone.filter { FONT_KIND[it.first] == FONT_KINDS[fontKind] }
                        }
                        faces.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (f, typeface) ->
                                    if (f.isEmpty()) {
                                        Column(
                                            Modifier.weight(1f).height(46.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF333333))
                                                .clickable { importFont.launch(FONT_TYPES) },
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Glyph(LucideIcons.Plus, Palette.Text, size = 16.dp)
                                            Text("Import", style = Type.Small.copy(color = Palette.Text, fontSize = 11.sp))
                                        }
                                        return@forEach
                                    }
                                    val selected = style.optString("fontFamily").trim('"') == f
                                    Box(
                                        Modifier
                                            .weight(1f)
                                            .height(46.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(0xFF333333))
                                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                                            .combinedClickable(
                                                onClick = { change(null) { put("fontFamily", f) } },
                                                onLongClick = { kept(Favorites.toggle(context, "font", f)) },
                                            )
                                            .padding(horizontal = 4.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            f,
                                            style = Type.Body.copy(color = Palette.Text, fontSize = 14.sp),
                                            fontFamily = typeface,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        if (f in keptSet) {
                                            Glyph(LucideIcons.Bookmark, Color.White, Modifier.align(Alignment.TopEnd).padding(2.dp), size = 10.dp)
                                        }
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                    3 -> StylesTab(vm, id, style, ::change) { words -> text = words }
                    4 -> WordArtTab(vm, id, style, ::change)
                    5 -> {
                        val made = id
                        if (made == null) {
                            Text("Type the words first, then choose how they come and go.", style = Type.Small, modifier = Modifier.padding(top = 16.dp))
                        } else {
                            AnimationChoices(vm, made)
                        }
                    }
                    6 -> BubblesTab(style) { b ->
                        change(null) {
                            if (b == null) {
                                put("backgroundShape", "")
                                put("backgroundOutline", "")
                                put("background", "")
                            } else {
                                put("backgroundShape", b.shape)
                                put("backgroundOutline", b.outline)
                                put("background", b.plate)
                                put("color", b.words)
                                put("strokeWidth", 0.0)
                                put("backgroundPaddingX", 0.025)
                                put("backgroundPaddingY", 0.018)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The words in a full-screen editor: back leaves them as they were, Done keeps them, and the arrows
 * step back and forth through what was typed here.
 */
@Composable
internal fun FullTextEditor(start: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    var words by remember { mutableStateOf(start) }
    val back = remember { mutableStateListOf<String>() }
    val ahead = remember { mutableStateListOf<String>() }
    val focus = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onBack,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).statusBarsPadding().navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clickable(onClick = onBack), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.ChevronLeft, Palette.Text, size = 26.dp, description = "Back")
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.size(40.dp).clickable(enabled = back.isNotEmpty()) {
                        ahead.add(words)
                        words = back.removeAt(back.lastIndex)
                    },
                    contentAlignment = Alignment.Center,
                ) { Glyph(LucideIcons.Undo2, if (back.isNotEmpty()) Palette.Text else Palette.Disabled, size = 22.dp, description = "Undo") }
                Box(
                    Modifier.size(40.dp).clickable(enabled = ahead.isNotEmpty()) {
                        back.add(words)
                        words = ahead.removeAt(ahead.lastIndex)
                    },
                    contentAlignment = Alignment.Center,
                ) { Glyph(LucideIcons.Redo2, if (ahead.isNotEmpty()) Palette.Text else Palette.Disabled, size = 22.dp, description = "Redo") }
                Spacer(Modifier.width(8.dp))
                Text(
                    "Done",
                    style = Type.BodyStrong.copy(fontSize = 14.sp, color = Color(0xFF111111)),
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White).clickable { onDone(words) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            BasicTextField(
                value = words,
                onValueChange = {
                    // a step for each change of word, not each letter
                    if (back.isEmpty() || it.trimEnd().count { c -> c == ' ' || c == '\n' } != words.trimEnd().count { c -> c == ' ' || c == '\n' }) back.add(words)
                    ahead.clear()
                    words = it
                },
                textStyle = Type.Body.copy(fontSize = 17.sp, color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 20.dp, vertical = 12.dp).focusRequester(focus),
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

/** A bubble: the plate's shape, its fill and outline (empty for none), and the words' colour inside it. */
private class Bubble(val shape: String, val plate: String, val outline: String, val words: String)

/** Our bubbles: the engine's shapes in a few colourings each. */
private val BUBBLES = listOf(
    Bubble("speech", "#ffffff", "#111111", "#111111"),
    Bubble("oval", "#ffffff", "#111111", "#111111"),
    Bubble("thought", "#ffffff", "#111111", "#111111"),
    Bubble("cloud", "#ffffff", "#111111", "#111111"),
    Bubble("burst", "#ffd84a", "#111111", "#111111"),
    Bubble("banner", "#e8443a", "", "#ffffff"),
    Bubble("tag", "#111111", "", "#ffffff"),
    Bubble("speech", "#111111", "#ffffff", "#ffffff"),
    Bubble("oval", "#ff8fc8", "#ffffff", "#ffffff"),
    Bubble("cloud", "#3b3f5c", "#ffffff", "#ffffff"),
    Bubble("burst", "#ff4d6d", "#ffffff", "#ffffff"),
    Bubble("banner", "#f2b33d", "", "#3a2600"),
    Bubble("thought", "#bfe8ff", "#1a4b66", "#1a4b66"),
    Bubble("tag", "#f6f1e7", "#3a3a3a", "#3a3a3a"),
    Bubble("speech", "#6be3a8", "", "#0d3322"),
    Bubble("oval", "#ffffff", "", "#111111"),
)

/** The Bubbles tab: None, then our bubbles four across, the title's own ringed. */
@Composable
private fun BubblesTab(style: JSONObject, onPick: (Bubble?) -> Unit) {
    val shape = style.optString("backgroundShape")
    val plate = style.optString("background").lowercase()
    val outline = style.optString("backgroundOutline").lowercase()
    val tiles = listOf<Bubble?>(null) + BUBBLES
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { b ->
                    val selected = if (b == null) shape.isEmpty() else b.shape == shape && b.plate == plate && b.outline == outline
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF333333))
                            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
                            .clickable { onPick(b) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (b == null) Glyph(LucideIcons.Ban, Palette.Text, size = 24.dp) else BubbleSample(b)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A colour the document keeps as #rrggbb, for drawing. */
private fun hexColour(hex: String): Color? =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

/** A bubble drawn as the engine draws it round a short word: its outline under its fill, then the word. */
@Composable
private fun BubbleSample(b: Bubble) {
    val fill = hexColour(b.plate) ?: Color.White
    val edge = hexColour(b.outline)
    val ink = hexColour(b.words) ?: Color.Black
    Box(Modifier.fillMaxSize().padding(10.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            // the words' box: as the engine grows the block round it, a rounder shape gets a smaller box
            val round = b.shape in setOf("oval", "thought", "cloud", "burst")
            val w = size.width * if (round) 0.58f else if (b.shape == "banner" || b.shape == "tag") 0.62f else 0.8f
            val h = size.height * if (round) 0.24f else 0.34f
            val l = (size.width - w) / 2 + if (b.shape == "tag") w * 0.08f else 0f
            val t = (size.height - h) / 2 - if (b.shape == "speech" || b.shape == "thought" || b.shape == "oval") size.height * 0.08f else 0f
            val outlines = bubbleOutlines(b.shape, l, t, w, h)
            if (edge != null) outlines.forEach { drawPath(it, edge, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round)) }
            outlines.forEach { drawPath(it, fill) }
        }
        Text("Hi!", style = Type.BodyStrong.copy(fontSize = 13.sp, color = ink), modifier = Modifier.offset(y = if (b.shape == "speech" || b.shape == "thought" || b.shape == "oval") (-6).dp else 0.dp))
    }
}

/** The oval round a [w] by [h] box, as far past it at the sides as above and below: its semi-axes, as the engine finds them. */
private fun roundAxes(w: Float, h: Float): Pair<Float, Float> {
    val hw = w / 2
    val hh = h / 2
    var low = 0f
    var high = maxOf(hw, hh, 1f)
    repeat(24) {
        val mid = (low + high) / 2
        val reach = (hw / (hw + mid)).let { it * it } + (hh / (hh + mid)).let { it * it }
        if (reach > 1f) low = mid else high = mid
    }
    return (hw + high) to (hh + high)
}

/** The outlines whose union is the bubble round an [l], [t], [w] by [h] box: the engine's shapes, for a tile. */
private fun bubbleOutlines(shape: String, l: Float, t: Float, w: Float, h: Float): List<androidx.compose.ui.graphics.Path> {
    val r = l + w
    val b = t + h
    val cx = l + w / 2
    val cy = t + h / 2
    val (a, bb) = roundAxes(w, h)
    fun path(build: androidx.compose.ui.graphics.Path.() -> Unit) = androidx.compose.ui.graphics.Path().apply(build)
    fun oval(sx: Float = 1f, sy: Float = 1f) = path { addOval(androidx.compose.ui.geometry.Rect(cx - a * sx, cy - bb * sy, cx + a * sx, cy + bb * sy)) }
    fun polygon(vararg points: Pair<Float, Float>) = path {
        points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
        close()
    }
    fun circle(x: Float, y: Float, radius: Float) = path { addOval(androidx.compose.ui.geometry.Rect(x - radius, y - radius, x + radius, y + radius)) }
    return when (shape) {
        "speech" -> {
            val corner = minOf(h, w) * 0.3f
            val base = minOf(w * 0.2f, h * 0.6f)
            val x0 = l + w * 0.16f
            listOf(
                path { addRoundRect(androidx.compose.ui.geometry.RoundRect(l, t, r, b, androidx.compose.ui.geometry.CornerRadius(corner))) },
                polygon(x0 to b - h * 0.2f, x0 + base to b - h * 0.2f, l + w * 0.08f to b + h * 0.5f),
            )
        }
        "oval" -> {
            fun at(turn: Float) = (cx + a * 0.85f * kotlin.math.cos(turn)) to (cy + bb * 0.85f * kotlin.math.sin(turn))
            listOf(oval(), polygon(at(0.75f), at(1.25f), cx + a * 0.8f to cy + bb * 1.5f))
        }
        "thought" -> {
            val small = minOf(a, bb) * 0.2f
            listOf(oval(), circle(cx - a * 0.5f, cy + bb * 1.12f, small), circle(cx - a * 0.68f, cy + bb * 1.38f, small * 0.55f))
        }
        "cloud" -> {
            val perimeter = Math.PI.toFloat() * (3 * (a + bb) - kotlin.math.sqrt((3 * a + bb) * (a + 3 * bb)))
            val count = (perimeter / maxOf(minOf(a, bb) * 1.1f, 1f)).roundToInt().coerceIn(8, 24)
            val bump = perimeter / count * 0.6f
            listOf(oval()) + (0 until count).map { i ->
                val turn = i.toFloat() / count * 2 * Math.PI.toFloat()
                circle(cx + a * kotlin.math.cos(turn), cy + bb * kotlin.math.sin(turn), bump)
            }
        }
        "burst" -> {
            val spikes = 14
            val points = (0 until spikes * 2).map { i ->
                val turn = i.toFloat() / (spikes * 2) * 2 * Math.PI.toFloat()
                val (sx, sy) = if (i % 2 == 0) 1.3f to 1.38f else 1.06f to 1.06f
                (cx + a * sx * kotlin.math.cos(turn)) to (cy + bb * sy * kotlin.math.sin(turn))
            }
            listOf(polygon(*points.toTypedArray()))
        }
        "banner" -> {
            val end = h * 0.6f
            val notch = h * 0.3f
            listOf(polygon(l - end to t, r + end to t, r + end - notch to cy, r + end to b, l - end to b, l - end + notch to cy))
        }
        "tag" -> listOf(polygon(l - h * 0.5f to cy, l to t, r to t, r to b, l to b))
        else -> listOf(path { addRect(androidx.compose.ui.geometry.Rect(l, t, r, b)) })
    }
}

/**
 * A word-art look of our own for a title: its fill, its own outline, and any of a gradient over the
 * letters, a second outer line, a glow and a cast shadow (the engine's title packages).
 */
private class WordArt(
    val name: String,
    val kind: String,
    val fill: String = "#ffffff",
    val stroke: String? = null,
    val gradient: Pair<String, String>? = null,
    val outline: String? = null,
    val glow: String? = null,
    val shadow: String? = null,
) {
    /** What the panel compares to tell which look a title wears. */
    val signature: String get() = listOf(gradient?.first ?: fill, stroke, gradient?.second, outline, glow, shadow).joinToString("|") { it.orEmpty().lowercase() }
}

/** The kinds the Effects tab sorts its looks into; the first shows them all. */
private val WORD_ART_KINDS = listOf("Trending", "Glow", "Outline", "Gradient", "Comic")

private val WORD_ART = listOf(
    WordArt("Outline", "Outline", stroke = "#000000"),
    WordArt("Neon Blue", "Glow", glow = "#00b4ff"),
    WordArt("Neon Pink", "Glow", glow = "#ff2fb9"),
    WordArt("Neon Green", "Glow", glow = "#39ff14"),
    WordArt("Halo", "Glow", glow = "#ffffff", stroke = "#9e9e9e"),
    WordArt("Double", "Outline", stroke = "#000000", outline = "#ffd400"),
    WordArt("Fire", "Gradient", gradient = "#ffe259" to "#ff3d00", stroke = "#7a1f00", glow = "#ff6a00"),
    WordArt("Gold", "Gradient", gradient = "#fff3b0" to "#d4a017", stroke = "#6b4e00", shadow = "#000000"),
    WordArt("Ice", "Gradient", gradient = "#ffffff" to "#7fd8ff", stroke = "#0a4c8c", glow = "#9be7ff"),
    WordArt("Candy", "Outline", fill = "#ff8fcf", stroke = "#ffffff", outline = "#ff2f92"),
    WordArt("Sunset", "Gradient", gradient = "#ffb347" to "#ff4e8a", stroke = "#ffffff"),
    WordArt("Ocean", "Gradient", gradient = "#6ee7f9" to "#2563eb", outline = "#ffffff"),
    WordArt("Comic", "Comic", fill = "#ffe600", stroke = "#000000", outline = "#ffffff", shadow = "#000000"),
    WordArt("Retro", "Comic", fill = "#ffd166", stroke = "#ef476f", shadow = "#073b4c"),
    WordArt("Pop", "Comic", fill = "#ff1744", stroke = "#ffffff", outline = "#000000"),
    WordArt("Violet", "Glow", fill = "#e1bee7", stroke = "#7b1fa2", glow = "#b388ff"),
    WordArt("Mint", "Glow", stroke = "#00bfa5", glow = "#64ffda"),
    WordArt("Drop Shadow", "Outline", shadow = "#000000"),
    WordArt("Ink", "Outline", fill = "#000000", stroke = "#ffffff", outline = "#000000"),
    WordArt("Lime", "Gradient", gradient = "#eeff41" to "#00e676", stroke = "#1b5e20"),
    WordArt("Prism", "Gradient", gradient = "#ff5acd" to "#5ad8ff", stroke = "#ffffff"),
    WordArt("Chrome", "Gradient", gradient = "#ffffff" to "#8e9eab", stroke = "#263238", shadow = "#000000"),
    WordArt("Lava", "Glow", gradient = "#ffc107" to "#d50000", glow = "#ff3d00"),
    WordArt("Night", "Glow", outline = "#1a237e", glow = "#3d5afe"),
    WordArt("Bubblegum", "Comic", fill = "#ffffff", stroke = "#ff4081", outline = "#ffffff", shadow = "#c51162"),
    WordArt("Toxic", "Comic", fill = "#b2ff59", stroke = "#000000", glow = "#76ff03"),
)

/** The look [style] and the title's packages add up to, as a [WordArt.signature]. */
private fun wornSignature(vm: EditorViewModel, clipId: String, style: JSONObject): String {
    val stroke = if (style.optDouble("strokeWidth", 0.0) > 0.0) style.optString("strokeColor") else ""
    val outline = styleParams(vm, clipId, OUTLINE)?.let { hexColour(it.optDouble("color", 255.0)) }.orEmpty()
    val glow = styleParams(vm, clipId, GLOW)?.let { hexColour(it.optDouble("color", 255.0)) }.orEmpty()
    val shadow = styleParams(vm, clipId, SHADOW)?.let { hexColour(it.optDouble("color", 255.0)) }.orEmpty()
    return listOf(style.optString("color"), stroke, style.optString("gradient"), outline, glow, shadow).joinToString("|") { it.lowercase() }
}

/**
 * The text panel's Effects tab as the reference lays it out: the kinds over a grid of word-art
 * looks, each tile the word drawn in it; a tap puts it on the title (its fill and outline, and its
 * gradient, outer line, glow and shadow) in one step, None takes them all off. A long press keeps
 * one under the bookmark.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WordArtTab(vm: EditorViewModel, clipId: String?, style: JSONObject, change: (String?, JSONObject.() -> Unit) -> Unit) {
    val made = clipId ?: run {
        Text("Type the words first, then give them an effect.", style = Type.Small, modifier = Modifier.padding(16.dp))
        return
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val faces = rememberFontFaces().toMap()
    var kind by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf<String?>(null) }
    var keptTab by remember { mutableStateOf(false) }
    val kept = remember(Favorites.version.intValue) { Favorites.of(context, "wordart") }
    val words = query?.trim().orEmpty()
    val shown = when {
        words.isNotEmpty() -> WORD_ART.filter { it.name.contains(words, ignoreCase = true) }
        keptTab -> WORD_ART.filter { it.name in kept }
        kind == 0 -> WORD_ART
        else -> WORD_ART.filter { it.kind == WORD_ART_KINDS[kind] }
    }
    val worn = remember(style, vm.timeline?.revision) { wornSignature(vm, made, style) }

    fun wear(look: WordArt?) {
        change("word-art") {
            // a gradient is the words' own: the fill at the top, the second colour at the bottom
            put("color", look?.gradient?.first ?: look?.fill ?: "#ffffff")
            put("gradient", look?.gradient?.second ?: "")
            if (look?.stroke != null) {
                put("strokeWidth", 0.006)
                put("strokeColor", look.stroke)
            } else {
                put("strokeWidth", 0.0)
            }
            put("shadow", false)
        }
        setStyles(
            vm,
            made,
            mapOf(
                OUTLINE to look?.outline?.let { JSONObject().put("color", packedColour(it)).put("width", 35) },
                GLOW to look?.glow?.let { JSONObject().put("color", packedColour(it)).put("strength", 75).put("range", 45) },
                SHADOW to look?.shadow?.let { JSONObject().put("color", packedColour(it)).put("opacity", 80).put("blur", 10).put("distance", 25).put("angle", 45) },
            ),
            "word-art",
        )
        vm.endGesture()
    }

    KindsRow(WORD_ART_KINDS, kind, query, { kind = it; keptTab = false }, { query = it }, "Search effects", keptTab) { keptTab = true }
    if (keptTab && shown.isEmpty()) {
        Text("Press and hold one to keep it here", style = Type.Small, modifier = Modifier.padding(vertical = 24.dp))
    }
    val none = if (words.isEmpty() && !keptTab) 1 else 0
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (List<WordArt?>(none) { null } + shown).chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { look ->
                    val chosen = if (look == null) worn == WordArt("", "").signature else worn == look.signature
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E2E2E))
                            .then(if (chosen) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                            .combinedClickable(
                                onClick = { wear(look) },
                                onLongClick = {
                                    if (look != null) {
                                        vm.message = if (Favorites.toggle(context, "wordart", look.name)) "Added to favorites" else "Removed from favorites"
                                    }
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (look == null) {
                            Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 26.dp)
                        } else {
                            WordArtSample(look, faces["Archivo Black"])
                            if (look.name in kept) {
                                Glyph(LucideIcons.Bookmark, Color.White, Modifier.align(Alignment.TopEnd).padding(3.dp), size = 11.dp)
                            }
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** "ART" drawn in [look]: its glow and outer line under its outline, its fill or gradient on top. */
@Composable
private fun WordArtSample(look: WordArt, face: androidx.compose.ui.text.font.FontFamily?) {
    val base = Type.BodyStrong.copy(fontSize = 29.sp, fontFamily = face, letterSpacing = 0.5.sp)
    Box(contentAlignment = Alignment.Center) {
        // the halo: a blurred shadow of the widest layer, in the glow's colour
        val under = look.glow?.let { androidx.compose.ui.graphics.Shadow(color(it), androidx.compose.ui.geometry.Offset.Zero, 30f) }
        val cast = look.shadow?.let { androidx.compose.ui.graphics.Shadow(color(it).copy(alpha = 0.85f), androidx.compose.ui.geometry.Offset(4f, 5f), 3f) }
        if (look.outline != null) {
            Text("ART", style = base.copy(color = color(look.outline), drawStyle = Stroke(width = 20f, join = androidx.compose.ui.graphics.StrokeJoin.Round), shadow = under ?: cast))
        }
        if (look.stroke != null) {
            Text("ART", style = base.copy(color = color(look.stroke), drawStyle = Stroke(width = 9f, join = androidx.compose.ui.graphics.StrokeJoin.Round), shadow = if (look.outline == null) under ?: cast else null))
        }
        val fill = look.gradient?.let { (a, b) -> base.copy(brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(color(a), color(b)))) } ?: base.copy(color = color(look.fill))
        Text("ART", style = fill.copy(shadow = if (look.outline == null && look.stroke == null) under ?: cast else null))
    }
}

/** A ready-made title of our own: its sample words, a face and a look. */
private class TextTemplate(
    val word: String,
    val family: String,
    val color: String,
    val weight: Int = 400,
    val italic: Boolean = false,
    val stroke: String? = null,
    val plate: String? = null,
    val shadow: Boolean = false,
    /** Which of [TEMPLATE_KINDS] it is shown under, besides the first. */
    val kinds: Set<String> = emptySet(),
) {
    /** What it is kept under among the favourites. */
    val key: String get() = "$word|$family"
}

/** The kinds the Templates tab sorts its titles into; the first shows them all. */
private val TEMPLATE_KINDS = listOf("Trending", "Bold", "Script", "Labels", "Fun", "Classic")

/** The plain title every other template departs from. */
private val DEFAULT_TEMPLATE = TextTemplate("", "Inter", "#ffffff", weight = 700)

private val TEMPLATES = listOf(
    DEFAULT_TEMPLATE,
    TextTemplate("BIG NEWS", "Anton", "#ffffff", stroke = "#000000", kinds = setOf("Bold")),
    TextTemplate("Weekend", "Pacifico", "#ffd166", shadow = true, kinds = setOf("Script")),
    TextTemplate("SALE", "Bebas Neue", "#ffffff", plate = "#ff2d55", kinds = setOf("Labels", "Bold")),
    TextTemplate("Day One", "DM Serif Display", "#ffffff", italic = true, shadow = true, kinds = setOf("Classic")),
    TextTemplate("WOW!", "Bangers", "#ffcc00", stroke = "#000000", kinds = setOf("Fun", "Bold")),
    TextTemplate("Road trip", "Permanent Marker", "#ffffff", shadow = true, kinds = setOf("Fun")),
    TextTemplate("Cheers", "Lobster", "#ff8fab", stroke = "#ffffff", kinds = setOf("Script")),
    TextTemplate("LIVE", "Archivo Black", "#ffffff", plate = "#e53935", kinds = setOf("Labels")),
    TextTemplate("TOP 5", "Montserrat", "#00e5ff", weight = 900, stroke = "#000000", kinds = setOf("Bold")),
    TextTemplate("Sunday", "Caveat Brush", "#ffffff", shadow = true, kinds = setOf("Script")),
    TextTemplate("Oh no!", "Londrina Solid", "#ff5252", weight = 900, stroke = "#ffffff", kinds = setOf("Fun")),
    TextTemplate("Ready?", "Space Grotesk", "#000000", weight = 700, plate = "#ffd600", kinds = setOf("Labels")),
    TextTemplate("vibes", "Rubik Scribble", "#e1bee7", shadow = true, kinds = setOf("Fun", "Script")),
    TextTemplate("Chapter 1", "Abril Fatface", "#ffffff", plate = "#000000", kinds = setOf("Classic", "Labels")),
    TextTemplate("THE END", "Staatliches", "#ffffff", shadow = true, kinds = setOf("Classic", "Bold")),
    TextTemplate("Level up", "Pixelify Sans", "#69f0ae", stroke = "#000000", kinds = setOf("Fun")),
    TextTemplate("New post", "Montserrat", "#ffffff", weight = 900, plate = "#7c4dff", kinds = setOf("Labels")),
    TextTemplate("Thank you", "Pacifico", "#ffffff", stroke = "#ff4081", kinds = setOf("Script")),
    TextTemplate("SUMMER", "Anton", "#ffeb3b", shadow = true, kinds = setOf("Bold")),
    TextTemplate("Coming soon", "Space Grotesk", "#ffffff", weight = 700, plate = "#222222", kinds = setOf("Labels", "Classic")),
    TextTemplate("GAME OVER", "Pixelify Sans", "#ff5252", stroke = "#000000", kinds = setOf("Fun")),
    TextTemplate("hello", "Caveat Brush", "#80deea", shadow = true, kinds = setOf("Script")),
    TextTemplate("Episode 2", "DM Serif Display", "#ffffff", italic = true, plate = "#1a237e", kinds = setOf("Classic", "Labels")),
    TextTemplate("FOLLOW", "Archivo Black", "#ffffff", plate = "#ff1744", kinds = setOf("Labels", "Bold")),
    TextTemplate("BREAKING", "Black Ops One", "#ffffff", plate = "#d50000", kinds = setOf("Labels", "Bold")),
    TextTemplate("Pro tip", "Hanken Grotesk", "#111111", weight = 700, plate = "#b2ff59", kinds = setOf("Labels")),
    TextTemplate("Fun fact", "Kosugi Maru", "#ffffff", plate = "#2979ff", kinds = setOf("Labels", "Fun")),
    TextTemplate("STEP 1", "Secular One", "#ffffff", stroke = "#000000", kinds = setOf("Bold")),
    TextTemplate("Mission", "Hammersmith One", "#ffab00", shadow = true, kinds = setOf("Bold")),
    TextTemplate("Spooky", "Frijole", "#ff9100", stroke = "#000000", kinds = setOf("Fun")),
    TextTemplate("Sweet", "Emilys Candy", "#ff80ab", stroke = "#ffffff", kinds = setOf("Fun", "Script")),
    TextTemplate("Plot twist", "Mystery Quest", "#ffffff", shadow = true, kinds = setOf("Fun")),
    TextTemplate("NIGHT OUT", "Bungee Hairline", "#ffffff", shadow = true, kinds = setOf("Fun", "Classic")),
    TextTemplate("Kawaii", "Zen Tokyo Zoo", "#ffffff", plate = "#ff4081", kinds = setOf("Fun")),
    TextTemplate("Travel diary", "Shadows Into Light Two", "#ffffff", shadow = true, kinds = setOf("Script")),
    TextTemplate("RETRO", "Atomic Age", "#ffd740", stroke = "#d84315", kinds = setOf("Classic")),
    TextTemplate("WORKOUT", "Germania One", "#ffffff", plate = "#212121", kinds = setOf("Bold", "Labels")),
    TextTemplate("Happy birthday", "Lobster", "#ffeb3b", stroke = "#e91e63", kinds = setOf("Script", "Fun")),
    TextTemplate("Before", "Montserrat", "#ffffff", weight = 900, plate = "#424242", kinds = setOf("Labels")),
    TextTemplate("After", "Montserrat", "#111111", weight = 900, plate = "#ffffff", kinds = setOf("Labels")),
    TextTemplate("OMG", "Bangers", "#ffffff", stroke = "#d500f9", kinds = setOf("Fun", "Bold")),
    TextTemplate("Highlights", "Bebas Neue", "#00e676", shadow = true, kinds = setOf("Bold")),
    TextTemplate("Subscribe", "Archivo Black", "#ffffff", plate = "#e53935", kinds = setOf("Labels")),
    TextTemplate("Recipe", "DM Serif Display", "#ffe0b2", italic = true, shadow = true, kinds = setOf("Classic")),
    TextTemplate("Unboxing", "Space Grotesk", "#ffffff", weight = 700, stroke = "#304ffe", kinds = setOf("Bold")),
    TextTemplate("today", "Permanent Marker", "#ffee58", shadow = true, kinds = setOf("Script", "Fun")),
    TextTemplate("Q&A", "Abril Fatface", "#ffffff", plate = "#6200ea", kinds = setOf("Classic", "Labels")),
)

/**
 * The text panel's Templates tab: the kinds across the top as text, the chosen one white, and under
 * them our own ready-made titles of that kind, four across, each drawn as it looks; Default first.
 */
@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun TemplatesTab(style: JSONObject, onKept: (Boolean) -> Unit, onPick: (TextTemplate) -> Unit) {
    val faces = rememberFontFaces().toMap()
    var kind by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var keptTab by remember { mutableStateOf(false) }
    val kept = remember(Favorites.version.intValue) { Favorites.of(context, "template") }
    val words = query?.trim().orEmpty()
    val shown = if (words.isNotEmpty()) {
        TEMPLATES.filter { it !== DEFAULT_TEMPLATE && (it.word.contains(words, ignoreCase = true) || it.family.contains(words, ignoreCase = true)) }
    } else if (keptTab) {
        TEMPLATES.filter { it.key in kept }
    } else {
        TEMPLATES.filter { it === DEFAULT_TEMPLATE || kind == 0 || TEMPLATE_KINDS[kind] in it.kinds }
    }
    KindsRow(TEMPLATE_KINDS, kind, query, { kind = it; keptTab = false }, { query = it }, "Search templates", keptTab) { keptTab = true }
    if (keptTab && shown.isEmpty()) {
        Text("Press and hold one to keep it here", style = Type.Small, modifier = Modifier.padding(vertical = 24.dp))
    }
    Column(Modifier.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shown.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    val chosen = style.optString("fontFamily").trim('"') == t.family && style.optString("color").equals(t.color, true) &&
                        (style.optString("background").isNotEmpty() == (t.plate != null))
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF2E2E2E))
                            .then(if (chosen) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
                            .combinedClickable(
                                onClick = { onPick(t) },
                                onLongClick = { if (t !== DEFAULT_TEMPLATE) onKept(Favorites.toggle(context, "template", t.key)) },
                            )
                            .padding(4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (t === DEFAULT_TEMPLATE) {
                            Text("Default", style = Type.Body.copy(color = Palette.Text, fontSize = 15.sp))
                            return@Box
                        }
                        if (t.key in kept) {
                            Glyph(LucideIcons.Bookmark, Color.White, Modifier.align(Alignment.TopEnd).padding(2.dp), size = 11.dp)
                        }
                        Box(
                            Modifier.clip(RoundedCornerShape(3.dp)).background(t.plate?.let(::color) ?: Color.Transparent)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            // shrunk until no word is broken across lines
                            var size by remember(t) { mutableStateOf(21f) }
                            val base = Type.BodyStrong.copy(
                                fontSize = size.sp,
                                lineHeight = (size + 1f).sp,
                                fontWeight = FontWeight(t.weight),
                                fontStyle = if (t.italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
                                fontFamily = faces[t.family],
                                shadow = if (t.shadow) androidx.compose.ui.graphics.Shadow(Color(0xAA000000), androidx.compose.ui.geometry.Offset(2f, 3f), 6f) else null,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            if (t.stroke != null) {
                                Text(t.word, style = base.copy(color = color(t.stroke), drawStyle = Stroke(width = 6f), shadow = null), maxLines = 2)
                            }
                            Text(
                                t.word,
                                style = base.copy(color = color(t.color)),
                                maxLines = 2,
                                onTextLayout = { layout ->
                                    val words = t.word.split(' ').count { it.isNotEmpty() }
                                    if ((layout.lineCount > minOf(words, 2) || layout.hasVisualOverflow) && size > 10f) size -= 1.5f
                                },
                            )
                        }
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A row of kinds as the reference heads its shelves: a search at the start, then the kinds as words,
 * the chosen one white. With the search open ([query] not null) the row is a search field and a
 * Cancel.
 */
@Composable
private fun KindsRow(
    kinds: List<String>,
    chosen: Int,
    query: String?,
    onKind: (Int) -> Unit,
    onQuery: (String?) -> Unit,
    hint: String,
    keptOn: Boolean = false,
    onKept: () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (query != null) {
            Box(Modifier.weight(1f)) { SearchField(query, { onQuery(it) }, hint, autoFocus = true) }
            Text(
                "Cancel",
                style = Type.Body.copy(color = Palette.DockText, fontSize = 14.sp),
                modifier = Modifier.clickable { onQuery(null) }.padding(horizontal = 10.dp, vertical = 8.dp),
            )
            return@Row
        }
        Box(Modifier.size(30.dp).clickable { onQuery("") }, contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.Search, Palette.DockText, size = 18.dp, description = hint)
        }
        Box(Modifier.padding(horizontal = 8.dp).size(1.dp, 16.dp).background(Color(0xFF444444)))
        // the ones kept by a long press
        Box(Modifier.padding(end = 10.dp).size(28.dp).clickable(onClick = onKept), contentAlignment = Alignment.Center) {
            Glyph(LucideIcons.Bookmark, if (keptOn) Palette.Text else Palette.TextSecondary, size = 17.dp, description = "Favorites")
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            kinds.forEachIndexed { i, name ->
                val on = i == chosen && !keptOn
                Text(
                    name,
                    style = Type.Body.copy(fontSize = 14.sp, color = if (on) Palette.Text else Palette.TextSecondary, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    modifier = Modifier.clickable { onKind(i) }.padding(vertical = 4.dp),
                )
            }
        }
    }
}

/** A ready-made look for a title: fill, outline, plate and shadow. */
private class TextLook(val color: String, val stroke: String? = null, val plate: String? = null, val shadow: Boolean = false) {
    /** Whether the title's [style] is this look: its colour, outline, plate and shadow. */
    fun worn(style: JSONObject): Boolean {
        fun rgb(s: String?) = s.orEmpty().lowercase().take(7)
        val outlined = style.optDouble("strokeWidth", 0.0) > 0.0
        return rgb(style.optString("color", "#ffffff")) == rgb(color) &&
            outlined == (stroke != null) && (!outlined || rgb(style.optString("strokeColor")) == rgb(stroke)) &&
            rgb(style.optString("background")) == rgb(plate) &&
            style.optBoolean("shadow", false) == shadow
    }
}

private val LOOKS = listOf(
    TextLook("#ffffff", shadow = true),
    TextLook("#ffffff", stroke = "#000000"),
    TextLook("#000000", stroke = "#ffffff"),
    TextLook("#ffcc00", stroke = "#000000"),
    TextLook("#ff3b30", stroke = "#ffffff"),
    TextLook("#30b0ff", stroke = "#000000"),
    TextLook("#ffffff", plate = "#000000"),
    TextLook("#000000", plate = "#ffffff"),
    TextLook("#ffffff", plate = "#ff2d55"),
    TextLook("#000000", plate = "#ffcc00"),
    TextLook("#ffffff", plate = "#5856d6"),
    TextLook("#ffe600", stroke = "#ff2d55"),
    TextLook("#00e5ff", stroke = "#002b5c"),
    TextLook("#ffffff", stroke = "#ff2d55"),
    TextLook("#ff9f0a", shadow = true),
    TextLook("#a6ff00", stroke = "#000000"),
    TextLook("#ff6ec7", stroke = "#ffffff"),
    TextLook("#ffffff", plate = "#34c759"),
    TextLook("#ffffff", plate = "#007aff"),
    TextLook("#ff2d55", plate = "#ffffff"),
    TextLook("#ffd60a", plate = "#000000"),
    TextLook("#5ac8fa", shadow = true),
)

/** The thickest outline the Stroke slider reaches, as a fraction of the frame's height. */
private const val MAX_STROKE = 0.012

/** The roundest plate corner the Background slider reaches. */
private const val MAX_RADIUS = 0.05

/** The most air the Background's Width and Height sliders put round the words, as fractions of the frame's height. */
private const val MAX_PAD_X = 0.1
private const val MAX_PAD_Y = 0.06

/**
 * The title's own packages, first to last on its chain: its words brought in (an In animation's),
 * then bent, lit and shadowed, so a word not yet in casts no glow and the curve bends what shows.
 */
private const val WORDS = "capcraft.word-reveal"
private const val CURVE = "capcraft.text-curve"
private const val GLOW = "capcraft.text-glow"
private const val SHADOW = "capcraft.text-shadow"
private const val OUTLINE = "capcraft.text-outline"

/** The letters bent first, then lined, lit and shadowed. */
private val STYLE_ORDER = listOf(WORDS, CURVE, OUTLINE, GLOW, SHADOW)

/** The params on the title's [pkg] link, or null when it has none. */
private fun styleParams(vm: EditorViewModel, clipId: String?, pkg: String): JSONObject? {
    val chain = clipId?.let { vm.clipJson(it) }?.optJSONArray("videoEffects") ?: return null
    for (i in 0 until chain.length()) {
        val link = chain.getJSONObject(i)
        if (link.optString("id") == pkg) return link.optJSONObject("params") ?: JSONObject()
    }
    return null
}

/**
 * Puts [params] on the title's [pkg] link, or takes the link off (null). The style packages lead
 * the chain in [STYLE_ORDER], so the letters are bent before they glow and cast a shadow; the
 * title's other effects follow as they were.
 */
private fun setStyle(vm: EditorViewModel, clipId: String, pkg: String, params: JSONObject?, gesture: String? = null) {
    val old = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val styles = HashMap<String, Any>()
    val rest = JSONArray()
    for (i in 0 until old.length()) {
        val link = old.getJSONObject(i)
        if (link.optString("id") in STYLE_ORDER) styles[link.optString("id")] = link else rest.put(link)
    }
    if (params == null) styles.remove(pkg) else styles[pkg] = JSONObject().put("id", pkg).put("params", params)
    val next = JSONArray()
    STYLE_ORDER.forEach { id -> styles[id]?.let(next::put) }
    for (i in 0 until rest.length()) next.put(rest.get(i))
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/** [setStyle] for several packages at once, in one change of the chain (null takes one off). */
private fun setStyles(vm: EditorViewModel, clipId: String, params: Map<String, JSONObject?>, gesture: String? = null) {
    val old = vm.clipJson(clipId)?.optJSONArray("videoEffects") ?: JSONArray()
    val styles = HashMap<String, Any>()
    val rest = JSONArray()
    for (i in 0 until old.length()) {
        val link = old.getJSONObject(i)
        if (link.optString("id") in STYLE_ORDER) styles[link.optString("id")] = link else rest.put(link)
    }
    params.forEach { (pkg, value) -> if (value == null) styles.remove(pkg) else styles[pkg] = JSONObject().put("id", pkg).put("params", value) }
    val next = JSONArray()
    STYLE_ORDER.forEach { id -> styles[id]?.let(next::put) }
    for (i in 0 until rest.length()) next.put(rest.get(i))
    vm.patch(clipId, JSONObject().put("videoEffects", next), gesture)
}

/** A `#rrggbb` colour as a package's colour knob holds it: red, green, blue and alpha in one number. */
private fun packedColour(hex: String): Double =
    runCatching { ((android.graphics.Color.parseColor(hex).toLong() and 0xFFFFFF) shl 8 or 0xFF).toDouble() }.getOrDefault(255.0)

/** A package's colour knob back as `#rrggbb`. */
private fun hexColour(packed: Double): String = "#%06x".format((packed.toLong() shr 8) and 0xFFFFFF)

/** The words in capitals, in small letters, or each word capitalised: the Case tab's three. */
private val CASES = listOf<(String) -> String>(
    { it.uppercase() },
    { it.lowercase() },
    { s -> Regex("(^|\\s)(\\p{L})").replace(s.lowercase()) { it.groupValues[1] + it.groupValues[2].uppercase() } },
)

/** The style tab's parts, in the reference's order. */
private val STYLE_PARTS = listOf("Text", "Stroke", "Glow", "Background", "Shadow", "Curve", "Spacing", "Bold italic", "Case")

/**
 * The text panel's Styles tab: ready-made looks, then one part of the look at a time. Glow,
 * Shadow and Curve are packages on the title's own chain, so they need the title made first.
 */
@Composable
private fun StylesTab(
    vm: EditorViewModel,
    clipId: String?,
    style: JSONObject,
    change: (String?, JSONObject.() -> Unit) -> Unit,
    onWords: (String) -> Unit,
) {
    var part by remember { mutableIntStateOf(0) }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Card).clickable {
                change(null) { put("color", "#ffffff"); put("strokeWidth", 0.0); put("background", ""); put("shadow", false) }
            },
            contentAlignment = Alignment.Center,
        ) { Glyph(LucideIcons.CircleX, Palette.TextSecondary, size = 20.dp) }
        LOOKS.forEach { look ->
            LookTile(look, look.worn(style)) {
                change(null) {
                    put("color", look.color)
                    if (look.stroke != null) {
                        put("strokeWidth", 0.004)
                        put("strokeColor", look.stroke)
                    } else {
                        put("strokeWidth", 0.0)
                    }
                    put("background", look.plate ?: "")
                    put("shadow", look.shadow)
                }
            }
        }
    }
    // the parts as plain words, the chosen one white, as the reference lists them
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        STYLE_PARTS.forEachIndexed { i, label ->
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
    Spacer(Modifier.height(4.dp))
    if (part in setOf(2, 4, 5) && clipId == null) {
        Text("Type the words first, then give them this.", style = Type.Small, modifier = Modifier.padding(top = 12.dp))
        return
    }
    when (part) {
        0 -> {
            SwatchRow(style.optString("color", "#ffffff"), vm = vm) { c -> change(null) { put("color", c) } }
            ValueSlider(
                value = (style.optDouble("fontSize", 0.06) * 1000).toFloat(),
                range = 20f..200f,
                label = { "${it.toInt()}" },
                onChange = { v -> change("text-size") { put("fontSize", v / 1000.0) } },
                onDone = { vm.endGesture() },
                title = "Size",
            )
            ValueSlider(
                value = (style.optDouble("opacity", 1.0) * 100).toFloat(),
                range = 0f..100f,
                label = { "${it.toInt()}" },
                onChange = { v -> change("text-opacity") { put("opacity", v / 100.0) } },
                onDone = { vm.endGesture() },
                title = "Opacity",
            )
        }
        1 -> {
            val width = style.optDouble("strokeWidth", 0.0)
            SwatchRow(if (width > 0) style.optString("strokeColor", "#000000") else null, allowNone = true, vm = vm) { c ->
                change(null) {
                    if (c == null) {
                        put("strokeWidth", 0.0)
                    } else {
                        put("strokeColor", c)
                        if (width <= 0) put("strokeWidth", 0.004)
                    }
                }
            }
            ValueSlider(
                value = (width / MAX_STROKE * 100).toFloat().coerceIn(0f, 100f),
                range = 0f..100f,
                label = { "${it.toInt()}" },
                onChange = { v -> change("text-stroke") { put("strokeWidth", v / 100.0 * MAX_STROKE) } },
                onDone = { vm.endGesture() },
                title = "Thickness",
            )
        }
        2 -> GlowPart(vm, clipId ?: return)
        3 -> {
            val plate = style.optString("background")
            val hex = plate.take(7).takeIf { it.length == 7 }
            val alpha = if (plate.length == 9) plate.substring(7).toIntOrNull(16) ?: 255 else 255
            SwatchRow(hex, allowNone = true, vm = vm) { c ->
                change(null) { put("background", if (c == null) "" else c + if (alpha < 255) "%02x".format(alpha) else "") }
            }
            if (hex != null) {
                ValueSlider(
                    value = alpha / 255f * 100f,
                    range = 0f..100f,
                    label = { "${it.toInt()}" },
                    onChange = { v ->
                        change("text-plate-opacity") { put("background", hex + "%02x".format((v / 100f * 255).toInt().coerceIn(0, 255))) }
                    },
                    onDone = { vm.endGesture() },
                    title = "Opacity",
                )
                ValueSlider(
                    value = (style.optDouble("backgroundRadius", 0.0135) / MAX_RADIUS * 100).toFloat().coerceIn(0f, 100f),
                    range = 0f..100f,
                    label = { "${it.toInt()}" },
                    onChange = { v -> change("text-plate-radius") { put("backgroundRadius", v / 100.0 * MAX_RADIUS) } },
                    onDone = { vm.endGesture() },
                    title = "Round",
                )
                // the air between the words and the plate's sides, and above and below them
                ValueSlider(
                    value = (style.optDouble("backgroundPaddingX", 0.0315) / MAX_PAD_X * 100).toFloat().coerceIn(0f, 100f),
                    range = 0f..100f,
                    label = { "${it.toInt()}" },
                    onChange = { v -> change("text-plate-width") { put("backgroundPaddingX", v / 100.0 * MAX_PAD_X) } },
                    onDone = { vm.endGesture() },
                    title = "Width",
                )
                ValueSlider(
                    value = (style.optDouble("backgroundPaddingY", 0.018) / MAX_PAD_Y * 100).toFloat().coerceIn(0f, 100f),
                    range = 0f..100f,
                    label = { "${it.toInt()}" },
                    onChange = { v -> change("text-plate-height") { put("backgroundPaddingY", v / 100.0 * MAX_PAD_Y) } },
                    onDone = { vm.endGesture() },
                    title = "Height",
                )
            }
        }
        4 -> ShadowPart(vm, clipId ?: return, style, change)
        5 -> CurvePart(vm, clipId ?: return)
        6 -> {
            // across: left, centre and right; down, a column per line: the same three read top to bottom
            val vertical = style.optBoolean("vertical", false)
            val align = style.optString("align", "center")
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(false, true).forEach { down ->
                    listOf("left" to LucideIcons.AlignLeft, "center" to LucideIcons.AlignCenter, "right" to LucideIcons.AlignRight).forEach { (a, icon) ->
                        StyleTile(vertical == down && align == a, {
                            change(null) {
                                put("align", a)
                                put("vertical", down)
                            }
                        }, Modifier.weight(1f)) {
                            Glyph(icon, Palette.Text, if (down) Modifier.rotate(90f) else Modifier, size = 20.dp)
                        }
                    }
                }
            }
            ValueSlider(
                value = (style.optDouble("tracking", 0.0) * 1000).toFloat().coerceIn(-10f, 50f),
                range = -10f..50f,
                label = { "${it.toInt()}" },
                onChange = { v -> change("text-tracking") { put("tracking", v / 1000.0) } },
                onDone = { vm.endGesture() },
                title = "Character",
            )
            ValueSlider(
                value = (style.optDouble("lineHeight", 1.2) * 10).toFloat().coerceIn(6f, 30f),
                range = 6f..30f,
                label = { "%.1f".format(it / 10f) },
                onChange = { v -> change("text-leading") { put("lineHeight", v / 10.0) } },
                onDone = { vm.endGesture() },
                title = "Line",
            )
        }
        7 -> Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val bold = style.optDouble("fontWeight", 700.0) >= 600
            val italic = style.optBoolean("italic", false)
            StyleTile(bold, { change(null) { put("fontWeight", if (bold) 400 else 700) } }, Modifier.weight(1f)) {
                Text("B", style = Type.BodyStrong.copy(fontSize = 18.sp, color = Palette.Text, fontWeight = FontWeight.Bold))
            }
            StyleTile(italic, { change(null) { put("italic", !italic) } }, Modifier.weight(1f)) {
                Text("I", style = Type.Body.copy(fontSize = 18.sp, color = Palette.Text, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
            }
            val underline = style.optBoolean("underline", false)
            StyleTile(underline, { change(null) { put("underline", !underline) } }, Modifier.weight(1f)) {
                Text("U", style = Type.Body.copy(fontSize = 18.sp, color = Palette.Text, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))
            }
        }
        else -> Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val words = style.optString("content")
            listOf("TT", "tt", "Tt").forEachIndexed { i, label ->
                // the first of the three the words already are, so "OK" reads as capitals, not as each word capitalised
                val now = words.isNotBlank() && CASES.indexOfFirst { it(words) == words } == i
                StyleTile(now, {
                    val next = CASES[i](words)
                    if (next != words) {
                        onWords(next)
                        change(null) { put("content", next) }
                    }
                }, Modifier.weight(1f)) {
                    Text(label, style = Type.BodyStrong.copy(fontSize = 17.sp, color = Palette.Text))
                }
            }
        }
    }
}

/** The Glow part: none or a soft light, then its colour, how strong and how far it spreads. */
@Composable
private fun GlowPart(vm: EditorViewModel, clipId: String) {
    val glow = styleParams(vm, clipId, GLOW)
    fun glowWith(gesture: String?, edit: JSONObject.() -> Unit) {
        val next = JSONObject((glow ?: JSONObject().put("color", packedColour("#ffffff")).put("strength", 60).put("range", 50)).toString())
        next.edit()
        setStyle(vm, clipId, GLOW, next, gesture)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StyleTile(glow == null, { setStyle(vm, clipId, GLOW, null) }) { Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 20.dp) }
        StyleTile(glow != null, { if (glow == null) glowWith(null) {} }) {
            Text(
                "Aa",
                style = Type.BodyStrong.copy(
                    fontSize = 20.sp,
                    color = Color.White,
                    shadow = androidx.compose.ui.graphics.Shadow(Color.White, androidx.compose.ui.geometry.Offset.Zero, 18f),
                ),
            )
        }
    }
    if (glow == null) return
    SwatchRow(hexColour(glow.optDouble("color", packedColour("#ffffff"))), vm = vm) { c -> glowWith(null) { put("color", packedColour(c ?: "#ffffff")) } }
    ValueSlider(
        value = glow.optDouble("strength", 60.0).toFloat(),
        range = 0f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> glowWith("text-glow-strength") { put("strength", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Intensity",
    )
    ValueSlider(
        value = glow.optDouble("range", 50.0).toFloat(),
        range = 0f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> glowWith("text-glow-range") { put("range", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Range",
    )
}

/**
 * The Shadow part: none or a colour, then how dark, soft, far and which way. The engine's own
 * plain shadow, which the ready-made looks wear, shows here as a black one and gives way to the
 * package's when anything is changed.
 */
@Composable
private fun ShadowPart(vm: EditorViewModel, clipId: String, style: JSONObject, change: (String?, JSONObject.() -> Unit) -> Unit) {
    val shadow = styleParams(vm, clipId, SHADOW)
    val plain = style.optBoolean("shadow", false)
    fun shadowWith(gesture: String?, edit: JSONObject.() -> Unit) {
        val next = JSONObject(
            (shadow ?: JSONObject().put("color", packedColour("#000000")).put("opacity", 80).put("blur", 20).put("distance", 20).put("angle", 45)).toString(),
        )
        next.edit()
        // one shadow at a time, and the two changes one undo step
        val fold = gesture ?: "text-shadow"
        if (plain) change(fold) { put("shadow", false) }
        setStyle(vm, clipId, SHADOW, next, fold)
        if (gesture == null) vm.endGesture()
    }
    val chosen = shadow?.let { hexColour(it.optDouble("color", 255.0)) } ?: if (plain) "#000000" else null
    SwatchRow(chosen, allowNone = true, vm = vm) { c ->
        if (c == null) {
            if (plain) change("text-shadow") { put("shadow", false) }
            setStyle(vm, clipId, SHADOW, null, "text-shadow")
            vm.endGesture()
        } else {
            shadowWith(null) { put("color", packedColour(c)) }
        }
    }
    if (chosen == null) return
    val now = shadow ?: JSONObject()
    ValueSlider(
        value = now.optDouble("opacity", 80.0).toFloat(),
        range = 0f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> shadowWith("text-shadow-opacity") { put("opacity", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Opacity",
    )
    ValueSlider(
        value = now.optDouble("blur", 20.0).toFloat(),
        range = 0f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> shadowWith("text-shadow-blur") { put("blur", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Blur",
    )
    ValueSlider(
        value = now.optDouble("distance", 20.0).toFloat(),
        range = 0f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> shadowWith("text-shadow-distance") { put("distance", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Distance",
    )
    ValueSlider(
        value = now.optDouble("angle", 45.0).toFloat(),
        range = -180f..180f,
        label = { "${it.toInt()}°" },
        onChange = { v -> shadowWith("text-shadow-angle") { put("angle", v.toInt()) } },
        onDone = { vm.endGesture() },
        title = "Angle",
    )
}

/** The Curve part: straight or on an arc, then how far it bends either way. */
@Composable
private fun CurvePart(vm: EditorViewModel, clipId: String) {
    val curve = styleParams(vm, clipId, CURVE)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StyleTile(curve == null, { setStyle(vm, clipId, CURVE, null) }) { Glyph(LucideIcons.CircleSlash, Palette.TextSecondary, size = 20.dp) }
        StyleTile(curve != null, { if (curve == null) setStyle(vm, clipId, CURVE, JSONObject().put("bend", 50)) }) { ArcWord("ABCDE") }
    }
    if (curve == null) return
    ValueSlider(
        value = curve.optDouble("bend", 50.0).toFloat(),
        range = -100f..100f,
        label = { "${it.toInt()}" },
        onChange = { v -> setStyle(vm, clipId, CURVE, JSONObject().put("bend", v.toInt()), "text-curve") },
        onDone = { vm.endGesture() },
        title = "Intensity",
    )
}

/** One of a style part's wide tiles: a dark box, outlined in white when it is the one chosen. */
@Composable
private fun StyleTile(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier.width(110.dp), content: @Composable () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3A3A3A))
            .then(if (selected) Modifier.border(1.5.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A short word set on an arc, each letter turned to the curve: the Curve part's sample. */
@Composable
private fun ArcWord(word: String) {
    Box(Modifier.size(80.dp, 34.dp)) {
        val middle = (word.length - 1) / 2f
        word.forEachIndexed { i, ch ->
            val turn = (i - middle) * 18f
            val rad = Math.toRadians(turn.toDouble())
            Text(
                ch.toString(),
                style = Type.Small.copy(fontSize = 11.sp, color = Palette.Text, fontWeight = FontWeight.SemiBold),
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = (40 * kotlin.math.sin(rad)).toFloat().dp, y = (40 * (1 - kotlin.math.cos(rad)) - 4).toFloat().dp)
                    .graphicsLayer { rotationZ = turn },
            )
        }
    }
}

/** A look's tile: "Aa" drawn the way the look would draw the title. */
@Composable
private fun LookTile(look: TextLook, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Card)
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.clip(RoundedCornerShape(3.dp)).background(look.plate?.let(::color) ?: Color.Transparent)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            val base = Type.BodyStrong.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold)
            if (look.stroke != null) {
                Text("Aa", style = base.copy(color = color(look.stroke), drawStyle = Stroke(width = 5f)))
            }
            Text("Aa", style = base.copy(color = color(look.color)))
        }
    }
}

/** The spectrum's hues, round the wheel and back to red. */
private val HUES = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/** An RGB colour as `#rrggbb`. */
internal fun hexOf(rgb: Int): String = "#" + Integer.toHexString(rgb and 0xFFFFFF).padStart(6, '0')

/**
 * A row of colours: none (when allowed), the eyedropper (the next tap on the preview gives its
 * colour), a colour of one's own from the spectrum, then the ready ones. The chosen one is ringed.
 */
@Composable
internal fun SwatchRow(selected: String?, allowNone: Boolean = false, vm: EditorViewModel? = null, onPick: (String?) -> Unit) {
    var mixing by remember { mutableStateOf(false) }
    val own = selected != null && SWATCHES.none { it.equals(selected, ignoreCase = true) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allowNone) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(Palette.Card)
                    .then(if (selected == null) Modifier.border(2.dp, Palette.Text, CircleShape) else Modifier)
                    .clickable { onPick(null) },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.CircleX, Palette.TextSecondary, size = 16.dp) }
        }
        if (vm != null) {
            val picking = vm.pickColor != null
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(if (picking) Palette.Text else Palette.Card).clickable {
                    if (picking) {
                        vm.pickColor = null
                    } else {
                        vm.pickColor = { rgb -> onPick(hexOf(rgb)) }
                        vm.message = "Tap the picture to take a colour from it"
                    }
                },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Pipette, if (picking) Palette.Black else Palette.Text, size = 15.dp, description = "Pick a colour") }
        }
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.sweepGradient(HUES))
                .then(if (own || mixing) Modifier.border(2.dp, Palette.Text, CircleShape) else Modifier)
                .clickable { mixing = !mixing },
        )
        Box(Modifier.size(1.dp, 20.dp).background(Color(0x44FFFFFF)))
        SWATCHES.forEach { hex ->
            val on = selected?.equals(hex, ignoreCase = true) == true
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(color(hex))
                    .border(if (on) 2.dp else 0.5.dp, if (on) Palette.Accent else Color(0x55FFFFFF), CircleShape)
                    .clickable { onPick(hex) },
            )
        }
    }
    if (mixing) ColourMixer(selected ?: "#ffffff") { hex -> onPick(hex) }
}

/**
 * A colour of one's own: saturation across and brightness down a square of the hue, the hue on a
 * bar below it. The colour is taken as the finger lifts, so one drag is one change.
 */
@Composable
private fun ColourMixer(start: String, onPick: (String) -> Unit) {
    val hsv = remember(start) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(runCatching { android.graphics.Color.parseColor(start) }.getOrDefault(-1), it) }
    }
    var hue by remember(start) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(start) { mutableFloatStateOf(hsv[1]) }
    var brightness by remember(start) { mutableFloatStateOf(hsv[2]) }
    val pick by androidx.compose.runtime.rememberUpdatedState(onPick)
    fun hex() = hexOf(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))

    /** Follows one finger, handing each place to [at] as fractions of the area, and the colour on as it lifts. */
    fun Modifier.follow(at: (Float, Float) -> Unit) = pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown()
            at((down.position.x / size.width).coerceIn(0f, 1f), (down.position.y / size.height).coerceIn(0f, 1f))
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                at((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f))
                change.consume()
            }
            pick(hex())
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(
            Modifier.weight(1f).height(110.dp).clip(RoundedCornerShape(6.dp)).follow { x, y ->
                saturation = x
                brightness = 1f - y
            },
        ) {
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val at = androidx.compose.ui.geometry.Offset(saturation * size.width, (1f - brightness) * size.height)
            drawCircle(Color.White, 7.dp.toPx(), at, style = Stroke(2.dp.toPx()))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(36.dp).clip(CircleShape).background(Color.hsv(hue, saturation, brightness)).border(1.dp, Color(0x55FFFFFF), CircleShape))
    }
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).follow { x, _ -> hue = (x * 360f).coerceIn(0f, 359.9f) },
    ) {
        drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(HUES))
        drawCircle(
            Color.White,
            size.height / 2 - 2.dp.toPx(),
            androidx.compose.ui.geometry.Offset(hue / 360f * size.width, size.height / 2),
            style = Stroke(2.dp.toPx()),
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) Palette.Text else Palette.Card).clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(label, style = Type.Small.copy(color = if (on) Palette.Black else Palette.Text, fontWeight = FontWeight.Medium)) }
}

// ── Stickers ──

/** Emoji the sticker panel offers, drawn by the phone's own emoji font. */

