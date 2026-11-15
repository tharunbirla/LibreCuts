package io.github.artcraftandroid.capcraft.ui.editor.panels

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.MediaKinds
import io.github.artcraftandroid.capcraft.ui.common.MediaPick
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A sound the page lists: where it is, its name, its length, who made it. */
private class Sound(val uri: Uri, val title: String, val durationMs: Long, val artist: String)

/** The sounds the user kept on this phone: extracted from videos, and favourites. */
private object SoundShelf {
    private const val FILE = "capcraft-sounds"

    private fun read(context: Context, key: String): List<Sound> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            Sound(Uri.parse(o.optString("uri")), o.optString("title"), o.optLong("duration"), o.optString("artist"))
        }
    }

    private fun write(context: Context, key: String, items: List<Sound>) {
        val list = JSONArray()
        items.forEach { list.put(JSONObject().put("uri", it.uri.toString()).put("title", it.title).put("duration", it.durationMs).put("artist", it.artist)) }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, list.toString()).apply()
    }

    fun extracted(context: Context) = read(context, "extracted")
    fun favourites(context: Context) = read(context, "favourites")

    fun addExtracted(context: Context, s: Sound) = write(context, "extracted", listOf(s) + extracted(context).filter { it.uri != s.uri })

    fun toggleFavourite(context: Context, s: Sound) {
        val now = favourites(context)
        write(context, "favourites", if (now.any { it.uri == s.uri }) now.filter { it.uri != s.uri } else listOf(s) + now)
    }
}

private fun audioPermission() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

/** The phone's music and sounds, newest first. */
private fun deviceSounds(context: Context): List<Sound> {
    val out = ArrayList<Sound>()
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.DURATION,
    )
    context.contentResolver.query(
        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Audio.Media.DURATION} > 0",
        null,
        "${MediaStore.Audio.Media.DATE_ADDED} DESC",
    )?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (c.moveToNext()) {
            out.add(
                Sound(
                    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(id)),
                    c.getString(title) ?: "",
                    c.getLong(duration),
                    c.getString(artist)?.takeIf { it != MediaStore.UNKNOWN_STRING } ?: "",
                ),
            )
        }
    }
    return out
}

/** A video's name and length, for the sound taken from it. */
private fun describeVideo(context: Context, uri: Uri): Sound {
    var title = "Extracted audio"
    var duration = 0L
    runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DURATION), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.substringBeforeLast('.')?.let { title = it }
                duration = c.getLong(1)
            }
        }
    }
    return Sound(uri, title, duration, "")
}

private fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Sounds, as a page of its own: the user's own audio (sounds taken from videos, and the music on
 * the phone) and their favourites, searchable; a tap plays a sound, + puts it on the timeline at
 * the playhead.
 */
@Composable
fun SoundsPage(vm: EditorViewModel) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var device by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var shelf by remember { mutableIntStateOf(0) }
    var allowed by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, audioPermission()) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    // opened by Replace, the sound chosen takes the clip's place; else it goes in at the playhead
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { vm.replacingSound = null } }
    fun take(uri: android.net.Uri) {
        val old = vm.replacingSound?.let { vm.clip(it) }
        if (old != null) vm.replace(old, uri) else vm.addFile(uri, asAudio = true)
        vm.replacingSound = null
    }
    val fromFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            take(uri)
            vm.panel = null
        }
    }
    val music by produceState<List<Sound>?>(null, allowed, device) {
        value = if (allowed && device) withContext(Dispatchers.IO) { runCatching { deviceSounds(context) }.getOrDefault(emptyList()) } else null
    }
    val extracted = remember(shelf) { SoundShelf.extracted(context) }
    val favourites = remember(shelf) { SoundShelf.favourites(context) }
    var playing by remember { mutableStateOf<Uri?>(null) }
    // whether the player has the sound ready; asking it for times before then is an error
    var ready by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    // how far the playing sound has got, 0 to 1, read from the player while it plays
    var heard by remember { mutableStateOf(0f) }
    LaunchedEffect(playing, ready) {
        if (!ready) heard = 0f
        while (playing != null && ready) {
            runCatching {
                if (player.duration > 0) heard = player.currentPosition.toFloat() / player.duration
            }
            kotlinx.coroutines.delay(100)
        }
    }
    fun seek(fraction: Float) {
        if (ready) runCatching { if (player.duration > 0) player.seekTo((fraction * player.duration).toInt()) }
        heard = fraction
    }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    fun play(s: Sound) {
        runCatching {
            player.reset()
            ready = false
            if (playing == s.uri) {
                playing = null
                return
            }
            player.setDataSource(context, s.uri)
            player.setOnPreparedListener {
                it.start()
                ready = true
            }
            player.setOnCompletionListener {
                playing = null
                ready = false
            }
            // handled here, so a failed sound just stops instead of calling the completion listener
            player.setOnErrorListener { _, _, _ ->
                playing = null
                ready = false
                true
            }
            player.prepareAsync()
            playing = s.uri
        }.onFailure { playing = null }
    }
    fun use(s: Sound) {
        runCatching { player.reset() }
        ready = false
        take(s.uri)
        vm.panel = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable { vm.panel = null }, contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.X, Color.White, size = 24.dp, description = "Close")
            }
            Text(
                "Sounds",
                style = Type.BodyStrong.copy(color = Color.White, fontSize = 17.sp),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(44.dp))
        }
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(38.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(LucideIcons.Search, Palette.TextSecondary, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Search your sounds", style = Type.Body.copy(color = Palette.TextSecondary, fontSize = 15.sp))
                BasicTextField(
                    query,
                    { query = it },
                    singleLine = true,
                    textStyle = Type.Body.copy(color = Color.White, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth().height(44.dp)) {
            listOf(LucideIcons.Folder, LucideIcons.Bookmark).forEachIndexed { i, icon ->
                Box(Modifier.weight(1f).fillMaxSize().clickable { tab = i }, contentAlignment = Alignment.Center) {
                    Glyph(icon, if (i == tab) Color.White else Palette.TextSecondary, size = 22.dp)
                    if (i == tab) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Palette.Accent))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2A2A2A)))
        val needle = query.trim().lowercase()
        fun List<Sound>.matching() = if (needle.isEmpty()) this else filter { needle in it.title.lowercase() || needle in it.artist.lowercase() }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (tab == 0) {
                item {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                        Spacer(Modifier.height(16.dp))
                        Text("Your audio", style = Type.Body.copy(color = Color.White, fontSize = 17.sp))
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth()) {
                            ShelfTile("Extracted", LucideIcons.ImagePlay, !device, Modifier.weight(1f)) { device = false }
                            Spacer(Modifier.width(36.dp))
                            ShelfTile("Device", LucideIcons.Folder, device, Modifier.weight(1f)) {
                                device = true
                                if (!allowed) ask.launch(audioPermission())
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        if (!device) {
                            Box(
                                Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, Palette.Accent, RoundedCornerShape(20.dp))
                                    .clickable {
                                        MediaPick.open(MediaKinds.VIDEO, multiple = false) { uris ->
                                            val uri = uris.first()
                                            SoundShelf.addExtracted(context, describeVideo(context, uri))
                                            shelf++
                                            take(uri)
                                            vm.panel = null
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) { Text("Extract audio from video", style = Type.Body.copy(color = Palette.Accent, fontSize = 15.sp)) }
                            Spacer(Modifier.height(8.dp))
                        } else if (!allowed) {
                            Text("Allow access to the music on this phone to list it here.", style = Type.Small.copy(color = Palette.TextSecondary))
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "Choose a file instead",
                                style = Type.Body.copy(color = Palette.Accent),
                                modifier = Modifier.clickable { fromFiles.launch(arrayOf("audio/*")) }.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
                val list = (if (device) music.orEmpty() else extracted).matching()
                items(list, key = { "${device}|${it.uri}" }) { s ->
                    SoundRow(s, playing == s.uri, favourites.any { it.uri == s.uri }, heard, ::seek, onPlay = { play(s) }, onUse = { use(s) }, onKeep = {
                        SoundShelf.toggleFavourite(context, s)
                        shelf++
                    })
                }
                if (device && allowed && music != null && music.orEmpty().isEmpty()) {
                    item { Text("No music on this phone", style = Type.Small.copy(color = Palette.TextSecondary), modifier = Modifier.padding(20.dp)) }
                }
            } else {
                val list = favourites.matching()
                if (list.isEmpty()) {
                    item { Text("Sounds you bookmark show here.", style = Type.Small.copy(color = Palette.TextSecondary), modifier = Modifier.padding(20.dp)) }
                }
                items(list, key = { "fav|${it.uri}" }) { s ->
                    SoundRow(s, playing == s.uri, true, heard, ::seek, onPlay = { play(s) }, onUse = { use(s) }, onKeep = {
                        SoundShelf.toggleFavourite(context, s)
                        shelf++
                    })
                }
            }
        }
    }
}

/** One of Your audio's two shelves, a dark tile, lit when chosen. */
@Composable
private fun ShelfTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.height(76.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF262626)).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Glyph(icon, if (on) Color.White else Palette.TextSecondary, size = 26.dp)
        Spacer(Modifier.height(6.dp))
        Text(label, style = Type.Small.copy(color = if (on) Color.White else Palette.TextSecondary, fontSize = 12.sp))
    }
}

/** A sound in a list: its cover, name, length and maker; while it plays, its bookmark and + to use it. */
@Composable
private fun SoundRow(
    s: Sound,
    playing: Boolean,
    kept: Boolean,
    heard: Float,
    onSeek: (Float) -> Unit,
    onPlay: () -> Unit,
    onUse: () -> Unit,
    onKeep: () -> Unit,
) {
    val context = LocalContext.current
    val cover by produceState<Bitmap?>(null, s.uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(s.uri, Size(120, 120), null) }.getOrNull() }
    }
    Column(Modifier.fillMaxWidth().background(if (playing) Color(0xFF1E1E1E) else Color.Transparent)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF2C2C2C)), contentAlignment = Alignment.Center) {
                val c = cover
                if (c != null) Image(c.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Glyph(LucideIcons.Music2, Palette.TextSecondary, size = 22.dp)
                if (playing) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.Pause, Color.White, size = 20.dp)
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title.ifEmpty { "Untitled" }, style = Type.Body.copy(color = Color.White, fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(clock(s.durationMs), s.artist).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 12.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (playing) {
                Box(Modifier.size(40.dp).clickable(onClick = onKeep), contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.Bookmark, if (kept) Palette.Accent else Color.White, size = 20.dp, description = "Bookmark")
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.size(30.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Accent).clickable(onClick = onUse),
                    contentAlignment = Alignment.Center,
                ) { Glyph(LucideIcons.Plus, Color.White, size = 18.dp, description = "Use") }
            }
        }
        if (playing) {
            // a dotted line for the sound with a marker where it has got to; a touch moves it
            androidx.compose.foundation.Canvas(
                Modifier.fillMaxWidth().height(30.dp).padding(start = 86.dp, end = 20.dp).pointerInput(s.uri) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onSeek((down.position.x / size.width).coerceIn(0f, 1f))
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            onSeek((c.position.x / size.width).coerceIn(0f, 1f))
                            c.consume()
                        }
                    }
                },
            ) {
                val cy = size.height / 2
                val step = 4.dp.toPx()
                var x = 0f
                while (x <= size.width) {
                    drawCircle(Color(0xFF8A8A8A), 0.9.dp.toPx(), androidx.compose.ui.geometry.Offset(x, cy))
                    x += step
                }
                val at = size.width * heard.coerceIn(0f, 1f)
                drawLine(Color.White, androidx.compose.ui.geometry.Offset(at, cy - 9.dp.toPx()), androidx.compose.ui.geometry.Offset(at, cy + 9.dp.toPx()), 1.5.dp.toPx())
            }
        }
    }
}
