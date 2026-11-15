package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Path as AndroidPath

/** Shapes of our own, drawn on the phone: marks to point at, ring, tick or hide something. */
private enum class Shape {
    Ring, ArrowRight, ArrowDown, ArrowUp, CurvedArrow, Underline, Tick, Cross, Star, Heart, Sparkle, Burst, Bubble, Mosaic,
    // the doodles: marks as if drawn by hand
    Squiggle, Spiral, Zigzag, Crown, Lightning, Rays, Cloud, Notes, Exclaim, Question, Dots, Flower, Scribble, Hearts, Swoosh, Sun,
}

/** The look of a word badge: a pill, a starburst, a ribbon, a tilted tag or a circle. */
private enum class BadgeShape { Pill, Burst, Ribbon, Tag, Circle }

/** A word badge of our own: its words, plate and ink, and the plate's shape. */
private class Badge(val text: String, val plate: Int, val ink: Int, val shape: BadgeShape)

/**
 * One sticker: an emoji the phone's own font draws, one of our drawn shapes, or a [figure] of the
 * engine's own (a shape clip, by the name the engine keeps, whose fill and outline Style changes);
 * [words] are what search finds it by, the first of them a figure's name.
 */
private class Sticker(val words: String, val emoji: String? = null, val shape: Shape? = null, val figure: String? = null, val badge: Badge? = null) {
    val key: String = emoji?.codePoints()?.toArray()?.joinToString("-") { Integer.toHexString(it) }
        ?: figure?.let { "figure-$it" }
        ?: badge?.let { "label-" + it.text.lowercase().replace(Regex("[^a-z0-9]+"), "-") }
        ?: "shape-" + shape!!.name.lowercase()
}

private fun badge(text: String, plate: Long, ink: Long, shape: BadgeShape, words: String = "") =
    Sticker((text.lowercase() + " label badge " + words).trim(), badge = Badge(text, plate.toInt(), ink.toInt(), shape))

private fun doodle(shape: Shape, words: String) = Sticker("$words doodle", shape = shape)

/** A shelf of stickers under one icon of the tab row: an emoji, or (for the shapes) a glyph. */
private class Shelf(val name: String, val emoji: String?, val stickers: List<Sticker>)

private fun emoji(vararg pairs: Pair<String, String>) = pairs.map { (e, words) -> Sticker(words, emoji = e) }

private val SHELVES = listOf(
    Shelf("Shapes", null, listOf(
        Sticker("rectangle square box", figure = "square"),
        Sticker("circle ellipse oval round", figure = "circle"),
        Sticker("triangle", figure = "triangle"),
        Sticker("line rule stroke", figure = "line"),
        Sticker("arrow pointer", figure = "arrow"),
        Sticker("star", figure = "star"),
        Sticker("heart love", figure = "heart"),
        Sticker("hexagon", figure = "hexagon"),
        Sticker("diamond rhombus", figure = "diamond"),
        Sticker("ring circle mark", shape = Shape.Ring),
        Sticker("arrow right point", shape = Shape.ArrowRight),
        Sticker("arrow down point", shape = Shape.ArrowDown),
        Sticker("arrow up point", shape = Shape.ArrowUp),
        Sticker("arrow curved loop", shape = Shape.CurvedArrow),
        Sticker("underline swoosh highlight", shape = Shape.Underline),
        Sticker("tick check yes", shape = Shape.Tick),
        Sticker("cross no wrong", shape = Shape.Cross),
        Sticker("star", shape = Shape.Star),
        Sticker("heart love", shape = Shape.Heart),
        Sticker("sparkle shine", shape = Shape.Sparkle),
        Sticker("burst boom pow", shape = Shape.Burst),
        Sticker("speech bubble talk", shape = Shape.Bubble),
        Sticker("mosaic blur hide pixel", shape = Shape.Mosaic),
    )),
    Shelf("Labels", "🏷️", listOf(
        badge("NEW", 0xFFFF3B30, 0xFFFFFFFF, BadgeShape.Burst),
        badge("SALE", 0xFFFFD60A, 0xFF111111, BadgeShape.Tag, "discount"),
        badge("LIVE", 0xFFE53935, 0xFFFFFFFF, BadgeShape.Pill),
        badge("WOW", 0xFFFFC107, 0xFF111111, BadgeShape.Burst, "amazing"),
        badge("OMG", 0xFFFF4081, 0xFFFFFFFF, BadgeShape.Burst),
        badge("LOL", 0xFF7C4DFF, 0xFFFFFFFF, BadgeShape.Circle, "funny"),
        badge("HOT", 0xFFFF6D00, 0xFFFFFFFF, BadgeShape.Pill, "fire"),
        badge("TOP 10", 0xFF111111, 0xFFFFD60A, BadgeShape.Ribbon, "list"),
        badge("FREE", 0xFF00C853, 0xFFFFFFFF, BadgeShape.Tag),
        badge("SUBSCRIBE", 0xFFE53935, 0xFFFFFFFF, BadgeShape.Pill, "follow channel"),
        badge("LIKE", 0xFF2979FF, 0xFFFFFFFF, BadgeShape.Pill, "thumbs"),
        badge("FOLLOW", 0xFFFFFFFF, 0xFF111111, BadgeShape.Pill),
        badge("TRENDING", 0xFF111111, 0xFFFFFFFF, BadgeShape.Ribbon),
        badge("VIBES", 0xFFB388FF, 0xFF111111, BadgeShape.Circle, "mood"),
        badge("YES!", 0xFF00E676, 0xFF111111, BadgeShape.Burst),
        badge("NO!", 0xFFFF1744, 0xFFFFFFFF, BadgeShape.Burst),
        badge("BEFORE", 0xFF424242, 0xFFFFFFFF, BadgeShape.Tag),
        badge("AFTER", 0xFFFFFFFF, 0xFF111111, BadgeShape.Tag),
        badge("DAY 1", 0xFF00B8D4, 0xFFFFFFFF, BadgeShape.Circle, "start"),
        badge("THE END", 0xFF111111, 0xFFFFFFFF, BadgeShape.Ribbon, "finish"),
    )),
    Shelf("Doodles", "✏️", listOf(
        doodle(Shape.Squiggle, "squiggle wave line"),
        doodle(Shape.Spiral, "spiral swirl"),
        doodle(Shape.Zigzag, "zigzag"),
        doodle(Shape.Crown, "crown king queen"),
        doodle(Shape.Lightning, "lightning bolt flash"),
        doodle(Shape.Rays, "rays shine burst"),
        doodle(Shape.Cloud, "cloud"),
        doodle(Shape.Notes, "music notes song"),
        doodle(Shape.Exclaim, "exclamation mark"),
        doodle(Shape.Question, "question mark"),
        doodle(Shape.Dots, "dots confetti"),
        doodle(Shape.Flower, "flower"),
        doodle(Shape.Scribble, "scribble"),
        doodle(Shape.Hearts, "hearts love"),
        doodle(Shape.Swoosh, "swoosh motion lines speed"),
        doodle(Shape.Sun, "sun sunny"),
    )),
    Shelf("Smileys", "😀", emoji(
        "😀" to "grin smile happy", "😂" to "laugh tears funny", "🤣" to "rolling laugh", "🥹" to "touched tears",
        "😍" to "heart eyes love", "🥰" to "smile hearts love", "😎" to "cool sunglasses", "🤩" to "star struck wow",
        "🥳" to "party celebrate", "😅" to "sweat smile", "😇" to "halo angel", "🤔" to "thinking hmm",
        "😴" to "sleep tired", "😭" to "cry sad", "😱" to "scream shock", "😡" to "angry mad",
        "🤯" to "mind blown", "🙄" to "eye roll", "😬" to "grimace awkward", "🤗" to "hug",
        "😜" to "wink tongue silly", "🤪" to "zany crazy", "🙈" to "monkey no see", "👀" to "eyes look",
    )),
    Shelf("Hands", "👍", emoji(
        "👍" to "thumbs up like yes", "👎" to "thumbs down no", "👏" to "clap applause", "🙌" to "hands raised yay",
        "💪" to "muscle strong", "✌️" to "peace victory", "🤞" to "fingers crossed luck", "👋" to "wave hello bye",
        "🫶" to "heart hands love", "🙏" to "please thanks pray", "👌" to "ok perfect", "🤙" to "call me",
        "👉" to "point right", "👈" to "point left", "👆" to "point up", "👇" to "point down",
    )),
    Shelf("Love", "❤️", emoji(
        "❤️" to "red heart love", "🧡" to "orange heart", "💛" to "yellow heart", "💚" to "green heart",
        "💙" to "blue heart", "💜" to "purple heart", "🖤" to "black heart", "💖" to "sparkling heart",
        "💕" to "two hearts", "💔" to "broken heart", "💯" to "hundred perfect", "🔥" to "fire hot lit",
        "✨" to "sparkles magic", "⭐" to "star", "💫" to "dizzy stars", "💥" to "boom collision",
    )),
    Shelf("Party", "🎉", emoji(
        "🎉" to "party popper celebrate", "🎊" to "confetti", "🎂" to "birthday cake", "🎁" to "gift present",
        "🎈" to "balloon", "🎵" to "music note", "🎶" to "music notes song", "🎤" to "microphone sing",
        "🎧" to "headphones music", "📸" to "camera photo flash", "🎬" to "film clapper movie", "🎮" to "game controller",
        "🏆" to "trophy win", "🥇" to "gold medal first", "🎯" to "target goal", "🪩" to "disco ball dance",
    )),
    Shelf("Nature", "🌸", emoji(
        "🌈" to "rainbow", "☀️" to "sun sunny", "🌙" to "moon night", "⚡" to "lightning", "❄️" to "snow cold",
        "🌊" to "wave sea", "🌸" to "blossom flower", "🌹" to "rose flower", "🌻" to "sunflower",
        "🌴" to "palm tree", "🍀" to "clover luck", "🍁" to "leaf autumn", "🐶" to "dog puppy",
        "🐱" to "cat kitty", "🦄" to "unicorn", "🦋" to "butterfly",
    )),
    Shelf("Food", "🍕", emoji(
        "🍕" to "pizza", "🍔" to "burger", "🍟" to "fries", "🌮" to "taco", "🍣" to "sushi",
        "🍩" to "donut", "🍪" to "cookie", "🍦" to "ice cream", "🍓" to "strawberry", "🍉" to "watermelon",
        "🥑" to "avocado", "☕" to "coffee", "🧋" to "bubble tea", "🍹" to "drink cocktail",
        "🥂" to "cheers toast", "🍿" to "popcorn",
    )),
    Shelf("Travel", "✈️", emoji(
        "✈️" to "plane flight", "🚀" to "rocket launch", "🚗" to "car drive", "🚲" to "bike cycle",
        "🗺️" to "map", "📍" to "pin place location", "🏖️" to "beach", "⛰️" to "mountain",
        "🏕️" to "camping tent", "🌍" to "globe world", "🧳" to "luggage suitcase", "🎒" to "backpack",
        "⛵" to "sailboat", "🏝️" to "island", "🚆" to "train", "🌅" to "sunrise",
    )),
    Shelf("Things", "💡", emoji(
        "💡" to "idea bulb", "💎" to "gem diamond", "💰" to "money bag", "📱" to "phone mobile",
        "💻" to "laptop computer", "⏰" to "alarm clock time", "📌" to "pushpin", "🔔" to "bell notify",
        "🔑" to "key", "✅" to "check done yes", "❌" to "cross no", "⚠️" to "warning",
        "❓" to "question", "❗" to "exclamation", "🆕" to "new", "🔴" to "red dot record",
    )),
)

/**
 * Stickers, laid out as the reference's shelf: a search field in the bar, a row of shelf icons
 * (a photo of the user's own first), and the stickers four across. Emoji are drawn by the phone's
 * emoji font and our shapes by our own drawing, each into a picture placed at the playhead.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun StickersPanel(vm: EditorViewModel) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    // -1 is the bookmark: the stickers kept by a long press
    var shelf by remember { mutableIntStateOf(0) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val kept = remember(Favorites.version.intValue) { Favorites.of(context, "sticker") }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.addStickerFrom(uri)
    }
    fun add(s: Sticker) {
        s.figure?.let { figure ->
            vm.addShape(figure, s.words.substringBefore(' ').replaceFirstChar { it.uppercase() })
            return
        }
        scope.launch {
            val file = withContext(Threads.thumbnails) { stickerFile(vm.path, s) }
            if (file != null) vm.addSticker(file)
        }
    }
    PanelFrame("Stickers", vm, tall = true, titleOnTop = true, header = { SearchField(query, { query = it }, "Search stickers") }) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).clickable {
                    photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.ImagePlus, Palette.DockText, size = 22.dp, description = "A photo of your own") }
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if (query.isBlank() && shelf == -1) Color(0xFF3A3A3A) else Color.Transparent)
                    .clickable { shelf = -1; query = "" },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Bookmark, Palette.DockText, size = 21.dp, description = "Favorites") }
            SHELVES.forEachIndexed { i, s ->
                val on = query.isBlank() && i == shelf
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0xFF3A3A3A) else Color.Transparent)
                        .clickable { shelf = i; query = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (s.emoji != null) Text(s.emoji, fontSize = 22.sp) else Glyph(LucideIcons.Shapes, Palette.DockText, size = 22.dp, description = s.name)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Timeline))
        val shown = if (query.isBlank() && shelf == -1) {
            SHELVES.flatMap { it.stickers }.filter { it.key in kept }
        } else if (query.isBlank()) SHELVES[shelf].stickers else {
            val q = query.trim().lowercase()
            SHELVES.flatMap { it.stickers }.filter { s -> s.words.split(' ').any { it.startsWith(q) } }
        }
        if (shown.isEmpty()) {
            Text(if (shelf == -1 && query.isBlank()) "Press and hold one to keep it here" else "No stickers found", style = Type.Small, modifier = Modifier.padding(24.dp))
        }
        ChoiceGrid(shown.size, Modifier.weight(1f)) { i ->
            val s = shown[i]
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333))
                    .combinedClickable(
                        onClick = { add(s) },
                        onLongClick = { vm.message = if (Favorites.toggle(context, "sticker", s.key)) "Added to favorites" else "Removed from favorites" },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (s.emoji != null) {
                    Text(s.emoji, fontSize = 40.sp)
                } else if (s.figure != null) {
                    FigureGlyph(s.figure)
                } else {
                    val picture by produceState<Bitmap?>(ShapeTiles.get(s.key), s.key) {
                        // a reused tile may still hold another shape's picture: always this one's
                        value = ShapeTiles.get(s.key) ?: withContext(Threads.thumbnails) {
                            s.badge?.let { drawBadge(it, 160) } ?: drawShape(s.shape!!, 160)
                        }.also { ShapeTiles.put(s.key, it) }
                    }
                    picture?.let { Image(it.asImageBitmap(), contentDescription = s.words, modifier = Modifier.fillMaxSize().padding(10.dp)) }
                }
                if (s.key in kept) {
                    Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp).clip(CircleShape).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        Glyph(LucideIcons.Bookmark, Color.White, size = 11.dp)
                    }
                }
            }
        }
    }
}

/** An engine figure's tile: the figure in white, as it first lands. */
@Composable
private fun FigureGlyph(figure: String) {
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(18.dp)) {
        val w = size.width
        val h = size.height
        val white = Color.White
        when (figure) {
            "square" -> drawRect(white)
            "circle" -> drawCircle(white, minOf(w, h) / 2)
            "triangle" -> drawPath(
                androidx.compose.ui.graphics.Path().apply { moveTo(w / 2, 0f); lineTo(w, h); lineTo(0f, h); close() },
                white,
            )
            "line" -> drawLine(white, androidx.compose.ui.geometry.Offset(0f, h / 2), androidx.compose.ui.geometry.Offset(w, h / 2), 4.dp.toPx())
            "star" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    val outer = minOf(w, h) / 2
                    for (i in 0 until 10) {
                        val reach = if (i % 2 == 0) outer else outer * 0.4f
                        val turn = -Math.PI.toFloat() / 2 + i * Math.PI.toFloat() / 5
                        val x = w / 2 + reach * kotlin.math.cos(turn)
                        val y = h / 2 + reach * kotlin.math.sin(turn)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                    close()
                },
                white,
            )
            "heart" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, h)
                    cubicTo(w * 0.4f, h * 0.8f, 0f, h * 0.6f, 0f, h * 0.32f)
                    cubicTo(0f, h * 0.05f, w * 0.38f, -h * 0.02f, w / 2, h * 0.22f)
                    cubicTo(w * 0.62f, -h * 0.02f, w, h * 0.05f, w, h * 0.32f)
                    cubicTo(w, h * 0.6f, w * 0.6f, h * 0.8f, w / 2, h)
                    close()
                },
                white,
            )
            "hexagon" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    val q = w / 4
                    val t = h / 2 - w * 0.433f
                    val b = h / 2 + w * 0.433f
                    moveTo(q, t); lineTo(w - q, t); lineTo(w, h / 2); lineTo(w - q, b); lineTo(q, b); lineTo(0f, h / 2); close()
                },
                white,
            )
            "diamond" -> drawPath(
                androidx.compose.ui.graphics.Path().apply {
                    moveTo(w / 2, 0f); lineTo(w * 0.875f, h / 2); lineTo(w / 2, h); lineTo(w * 0.125f, h / 2); close()
                },
                white,
            )
            else -> {
                val stroke = 4.dp.toPx()
                drawLine(white, androidx.compose.ui.geometry.Offset(0f, h / 2), androidx.compose.ui.geometry.Offset(w, h / 2), stroke)
                drawLine(white, androidx.compose.ui.geometry.Offset(w, h / 2), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.25f), stroke)
                drawLine(white, androidx.compose.ui.geometry.Offset(w, h / 2), androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.75f), stroke)
            }
        }
    }
}

/** Our shapes' tile pictures, drawn once. */
private object ShapeTiles {
    private val map = HashMap<String, Bitmap>()
    @Synchronized fun get(key: String) = map[key]
    @Synchronized fun put(key: String, b: Bitmap) { map[key] = b }
}

/** A sticker as a transparent square PNG in the project's media folder, drawn once. */
private fun stickerFile(projectPath: String, s: Sticker): File? = runCatching {
    val dir = File(projectPath, "media").apply { mkdirs() }
    val file = File(dir, "sticker-${s.key}.png")
    if (!file.exists()) {
        val b = when {
            s.emoji != null -> drawEmoji(s.emoji, 512)
            s.badge != null -> drawBadge(s.badge, 512)
            else -> drawShape(s.shape!!, 512)
        }
        FileOutputStream(file).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    file
}.getOrNull()

private fun drawEmoji(emoji: String, size: Int): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(b)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * 0.78f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT
        color = AndroidColor.WHITE
    }
    val fm = paint.fontMetrics
    c.drawText(emoji, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
    return b
}

/** A word badge on a transparent square [size] pixels across: its plate, a white rim, the words fitted inside. */
private fun drawBadge(badge: Badge, size: Int): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(b)
    val k = size / 512f
    c.scale(k, k)
    val plate = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = badge.plate }
    val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeJoin = Paint.Join.ROUND
        color = if (badge.plate == AndroidColor.WHITE) 0xFF111111.toInt() else AndroidColor.WHITE
    }
    // the plate's outline and the box the words must fit
    val (outline, box) = when (badge.shape) {
        BadgeShape.Pill -> AndroidPath().apply { addRoundRect(RectF(40f, 176f, 472f, 336f), 80f, 80f, AndroidPath.Direction.CW) } to RectF(80f, 196f, 432f, 316f)
        BadgeShape.Circle -> AndroidPath().apply { addCircle(256f, 256f, 200f, AndroidPath.Direction.CW) } to RectF(110f, 180f, 402f, 332f)
        BadgeShape.Burst -> AndroidPath().apply {
            val points = 16
            for (i in 0 until points * 2) {
                val r = if (i % 2 == 0) 235.0 else 190.0
                val a = -PI / 2 + i * PI / points
                val x = (256 + r * cos(a)).toFloat()
                val y = (256 + r * sin(a)).toFloat()
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        } to RectF(120f, 190f, 392f, 322f)
        BadgeShape.Ribbon -> AndroidPath().apply {
            moveTo(30f, 190f); lineTo(482f, 190f); lineTo(440f, 256f); lineTo(482f, 322f); lineTo(30f, 322f); lineTo(72f, 256f); close()
        } to RectF(96f, 204f, 416f, 308f)
        BadgeShape.Tag -> AndroidPath().apply {
            moveTo(110f, 180f); lineTo(470f, 180f); lineTo(470f, 332f); lineTo(110f, 332f); lineTo(42f, 256f); close()
            addCircle(110f, 256f, 16f, AndroidPath.Direction.CCW)
        } to RectF(140f, 200f, 450f, 312f)
    }
    // a tag hangs at a slant, as a price tag does
    if (badge.shape == BadgeShape.Tag) c.rotate(-8f, 256f, 256f)
    c.drawPath(outline, plate)
    c.drawPath(outline, rim)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = badge.ink
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        textSize = box.height() * 0.8f
    }
    // shrunk until the words fit the box across
    val width = ink.measureText(badge.text)
    if (width > box.width()) ink.textSize *= box.width() / width
    val fm = ink.fontMetrics
    c.drawText(badge.text, box.centerX(), box.centerY() - (fm.ascent + fm.descent) / 2f, ink)
    return b
}

/** One of our shapes on a transparent square [size] pixels across. */
private fun drawShape(shape: Shape, size: Int): Bitmap {
    val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = AndroidCanvas(b)
    val k = size / 512f
    c.scale(k, k)
    val red = 0xFFE53935.toInt()
    fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colour
    }
    fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = colour }
    /** An arrow's head at (x, y), pointing along [angle] radians. */
    fun head(x: Float, y: Float, angle: Double, paint: Paint) {
        val len = 90.0
        for (side in listOf(-0.55, 0.55)) {
            c.drawLine(x, y, (x - len * cos(angle + side)).toFloat(), (y - len * sin(angle + side)).toFloat(), paint)
        }
    }
    /** A star of [points] points about the middle, its tips at [outer] and notches at [inner]. */
    fun star(points: Int, outer: Float, inner: Float): AndroidPath = AndroidPath().apply {
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / points
            val x = (256 + r * cos(a)).toFloat()
            val y = (256 + r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    when (shape) {
        Shape.Ring -> {
            // drawn by hand: a little more than once round, never quite meeting itself
            val path = AndroidPath()
            val turns = 2 * PI * 1.08
            for (i in 0..120) {
                val t = turns * i / 120
                val rx = 205 + 8 * sin(3 * t)
                val ry = 150 + 7 * cos(2 * t)
                val x = rx * cos(t)
                val y = ry * sin(t)
                val tilt = -0.14
                val px = (256 + x * cos(tilt) - y * sin(tilt)).toFloat()
                val py = (256 + x * sin(tilt) + y * cos(tilt)).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            c.drawPath(path, stroke(red, 34f))
        }
        Shape.ArrowRight -> {
            val p = stroke(red, 36f)
            c.drawPath(AndroidPath().apply { moveTo(70f, 340f); quadTo(250f, 150f, 430f, 250f) }, p)
            head(430f, 250f, kotlin.math.atan2(100.0, 180.0), p)
        }
        Shape.ArrowDown -> {
            val p = stroke(red, 38f)
            c.drawLine(256f, 70f, 256f, 430f, p)
            head(256f, 430f, PI / 2, p)
        }
        Shape.ArrowUp -> {
            val p = stroke(red, 38f)
            c.drawLine(110f, 410f, 400f, 120f, p)
            head(400f, 120f, -PI / 4, p)
        }
        Shape.CurvedArrow -> {
            val p = stroke(red, 34f)
            c.drawPath(AndroidPath().apply { moveTo(90f, 380f); cubicTo(60f, 120f, 420f, 90f, 410f, 330f) }, p)
            head(410f, 330f, PI / 2 + 0.25, p)
        }
        Shape.Underline -> {
            val p = stroke(0xE6FFD60A.toInt(), 46f)
            c.drawPath(AndroidPath().apply { moveTo(60f, 290f); cubicTo(180f, 240f, 320f, 300f, 452f, 250f) }, p)
        }
        Shape.Tick -> c.drawPath(AndroidPath().apply { moveTo(110f, 270f); lineTo(215f, 370f); lineTo(410f, 150f) }, stroke(0xFF2ECC71.toInt(), 50f))
        Shape.Cross -> {
            val p = stroke(red, 50f)
            c.drawLine(130f, 130f, 382f, 382f, p)
            c.drawLine(382f, 130f, 130f, 382f, p)
        }
        Shape.Star -> {
            val s = star(5, 220f, 95f)
            c.drawPath(s, fill(0xFFFFC107.toInt()))
            c.drawPath(s, stroke(AndroidColor.WHITE, 16f))
        }
        Shape.Heart -> {
            val h = AndroidPath().apply {
                moveTo(256f, 440f)
                cubicTo(40f, 300f, 40f, 90f, 180f, 90f)
                cubicTo(220f, 90f, 250f, 115f, 256f, 150f)
                cubicTo(262f, 115f, 292f, 90f, 332f, 90f)
                cubicTo(472f, 90f, 472f, 300f, 256f, 440f)
                close()
            }
            c.drawPath(h, fill(0xFFFF3B5C.toInt()))
            c.drawPath(h, stroke(AndroidColor.WHITE, 16f))
        }
        Shape.Sparkle -> c.drawPath(star(4, 230f, 55f), fill(0xFFFFE066.toInt()))
        Shape.Burst -> {
            val s = star(12, 235f, 165f)
            c.drawPath(s, fill(0xFFFFD60A.toInt()))
            c.drawPath(s, stroke(red, 14f))
        }
        Shape.Bubble -> {
            val body = AndroidPath().apply {
                addRoundRect(RectF(50f, 80f, 462f, 350f), 70f, 70f, AndroidPath.Direction.CW)
                moveTo(150f, 340f)
                lineTo(120f, 450f)
                lineTo(240f, 345f)
                close()
            }
            c.drawPath(body, fill(AndroidColor.WHITE))
            c.drawPath(body, stroke(0xFF161616.toInt(), 12f))
            c.drawRect(140f, 335f, 245f, 352f, fill(AndroidColor.WHITE))
        }
        Shape.Squiggle -> {
            val path = AndroidPath()
            for (i in 0..80) {
                val t = i / 80.0
                val x = (60 + 392 * t).toFloat()
                val y = (256 + 70 * sin(t * PI * 5)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, stroke(0xFF00B8D4.toInt(), 30f))
        }
        Shape.Spiral -> {
            val path = AndroidPath()
            for (i in 0..200) {
                val t = i / 200.0 * 6 * PI
                val r = 12 + 200 * t / (6 * PI)
                val x = (256 + r * cos(t)).toFloat()
                val y = (256 + r * sin(t)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, stroke(0xFFFF4081.toInt(), 22f))
        }
        Shape.Zigzag -> c.drawPath(AndroidPath().apply {
            moveTo(50f, 300f); lineTo(120f, 200f); lineTo(190f, 300f); lineTo(260f, 200f); lineTo(330f, 300f); lineTo(400f, 200f); lineTo(462f, 290f)
        }, stroke(0xFFFFD60A.toInt(), 30f))
        Shape.Crown -> {
            val crown = AndroidPath().apply {
                moveTo(90f, 370f); lineTo(70f, 160f); lineTo(170f, 250f); lineTo(256f, 120f); lineTo(342f, 250f); lineTo(442f, 160f); lineTo(422f, 370f); close()
            }
            c.drawPath(crown, fill(0xFFFFC107.toInt()))
            c.drawPath(crown, stroke(0xFF5D4037.toInt(), 16f))
            for (x in listOf(70f, 256f, 442f)) c.drawCircle(x, if (x == 256f) 112f else 152f, 22f, fill(0xFFE53935.toInt()))
        }
        Shape.Lightning -> {
            val bolt = AndroidPath().apply { moveTo(300f, 50f); lineTo(140f, 290f); lineTo(250f, 290f); lineTo(200f, 462f); lineTo(380f, 210f); lineTo(270f, 210f); close() }
            c.drawPath(bolt, fill(0xFFFFEB3B.toInt()))
            c.drawPath(bolt, stroke(0xFF212121.toInt(), 14f))
        }
        Shape.Rays -> {
            val p = stroke(0xFFFF9100.toInt(), 26f)
            for (i in 0 until 12) {
                val a = i * PI / 6
                c.drawLine((256 + 110 * cos(a)).toFloat(), (256 + 110 * sin(a)).toFloat(), (256 + 220 * cos(a)).toFloat(), (256 + 220 * sin(a)).toFloat(), p)
            }
        }
        Shape.Cloud -> {
            val cloud = AndroidPath().apply {
                addCircle(180f, 290f, 90f, AndroidPath.Direction.CW)
                addCircle(270f, 230f, 115f, AndroidPath.Direction.CW)
                addCircle(355f, 295f, 85f, AndroidPath.Direction.CW)
                addRect(180f, 290f, 355f, 380f, AndroidPath.Direction.CW)
            }
            c.drawPath(cloud, stroke(0xFF263238.toInt(), 22f))
            c.drawPath(cloud, fill(AndroidColor.WHITE))
        }
        Shape.Notes -> {
            val ink = fill(0xFF111111.toInt())
            val p = stroke(0xFF111111.toInt(), 22f)
            c.drawOval(RectF(120f, 330f, 220f, 400f), ink)
            c.drawOval(RectF(300f, 290f, 400f, 360f), ink)
            c.drawLine(210f, 365f, 210f, 130f, p)
            c.drawLine(390f, 325f, 390f, 90f, p)
            c.drawPath(AndroidPath().apply { moveTo(210f, 130f); lineTo(390f, 90f); lineTo(390f, 150f); lineTo(210f, 190f); close() }, ink)
        }
        Shape.Exclaim -> {
            val red2 = fill(0xFFFF1744.toInt())
            c.drawPath(AndroidPath().apply { moveTo(200f, 60f); lineTo(312f, 60f); lineTo(285f, 330f); lineTo(227f, 330f); close() }, red2)
            c.drawCircle(256f, 410f, 45f, red2)
        }
        Shape.Question -> {
            val p = stroke(0xFF2979FF.toInt(), 52f)
            c.drawPath(AndroidPath().apply { moveTo(160f, 170f); cubicTo(160f, 50f, 360f, 50f, 350f, 170f); cubicTo(345f, 240f, 256f, 240f, 256f, 320f) }, p)
            c.drawCircle(256f, 420f, 34f, fill(0xFF2979FF.toInt()))
        }
        Shape.Dots -> {
            val random = java.util.Random(19)
            val colours = listOf(0xFFFF4081, 0xFF00E5FF, 0xFFFFD600, 0xFF76FF03, 0xFF7C4DFF)
            repeat(26) {
                val x = 70f + random.nextInt(372)
                val y = 70f + random.nextInt(372)
                c.drawCircle(x, y, 14f + random.nextInt(16), fill(colours[random.nextInt(colours.size)].toInt()))
            }
        }
        Shape.Flower -> {
            for (i in 0 until 6) {
                val a = i * PI / 3
                c.drawCircle((256 + 105 * cos(a)).toFloat(), (256 + 105 * sin(a)).toFloat(), 85f, fill(0xFFFF80AB.toInt()))
            }
            c.drawCircle(256f, 256f, 75f, fill(0xFFFFD54F.toInt()))
        }
        Shape.Scribble -> {
            val path = AndroidPath()
            val random = java.util.Random(23)
            path.moveTo(90f, 260f)
            repeat(14) { path.quadTo(90f + random.nextInt(332), 120f + random.nextInt(272), 90f + random.nextInt(332), 120f + random.nextInt(272)) }
            c.drawPath(path, stroke(0xFF212121.toInt(), 16f))
        }
        Shape.Hearts -> {
            fun heart(cx: Float, cy: Float, r: Float, colour: Int) {
                val h = AndroidPath().apply {
                    moveTo(cx, cy + r)
                    cubicTo(cx - r * 1.6f, cy + r * 0.2f, cx - r, cy - r * 1.1f, cx, cy - r * 0.35f)
                    cubicTo(cx + r, cy - r * 1.1f, cx + r * 1.6f, cy + r * 0.2f, cx, cy + r)
                    close()
                }
                c.drawPath(h, fill(colour))
            }
            heart(190f, 300f, 110f, 0xFFFF1744.toInt())
            heart(360f, 190f, 70f, 0xFFFF80AB.toInt())
            heart(380f, 370f, 50f, 0xFFF50057.toInt())
        }
        Shape.Swoosh -> {
            val p = stroke(0xFFFFFFFF.toInt(), 24f)
            val shade = stroke(0xFF111111.toInt(), 34f)
            for ((i, y) in listOf(170f, 256f, 342f).withIndex()) {
                val path = AndroidPath().apply { moveTo(60f + i * 40, y); quadTo(256f, y - 40, 452f - i * 30, y) }
                c.drawPath(path, shade)
                c.drawPath(path, p)
            }
        }
        Shape.Sun -> {
            val p = stroke(0xFFFFB300.toInt(), 24f)
            for (i in 0 until 10) {
                val a = i * PI / 5
                c.drawLine((256 + 150 * cos(a)).toFloat(), (256 + 150 * sin(a)).toFloat(), (256 + 225 * cos(a)).toFloat(), (256 + 225 * sin(a)).toFloat(), p)
            }
            c.drawCircle(256f, 256f, 120f, fill(0xFFFFD54F.toInt()))
            c.drawCircle(256f, 256f, 120f, stroke(0xFFFFB300.toInt(), 14f))
        }
        Shape.Mosaic -> {
            // squares of grey, fixed so the same block comes out every time
            val random = java.util.Random(7)
            val cell = 512f / 8
            for (y in 0 until 8) for (x in 0 until 8) {
                val g = 60 + random.nextInt(170)
                c.drawRect(x * cell, y * cell, (x + 1) * cell, (y + 1) * cell, fill(AndroidColor.rgb(g, g, g)))
            }
        }
    }
    return b
}
