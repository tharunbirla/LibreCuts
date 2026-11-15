package io.github.artcraftandroid.capcraft.ui.common

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a pick may hold. */
enum class MediaKinds { VIDEO, IMAGE, BOTH }

/**
 * An open request for media: what it may hold, one or many, and where the choice goes. With
 * [onCollage], a Collage tab sits beside the first, picking two to nine photos or videos for it.
 */
class MediaRequest(
    val kinds: MediaKinds,
    val multiple: Boolean,
    val onCancel: () -> Unit,
    val onPicked: (List<Uri>) -> Unit,
    val onCollage: ((List<Uri>) -> Unit)? = null,
)

/**
 * The app's own media picker, drawn over everything while a request is open: the phone's videos
 * and photos in a grid, newest first, by album. Without leave to read them it hands over to the
 * system's picker instead.
 */
object MediaPick {
    var request by mutableStateOf<MediaRequest?>(null)

    /** The project open in the editor, whose media the picker marks as already brought in. */
    var project: String? = null

    fun open(
        kinds: MediaKinds,
        multiple: Boolean,
        onCancel: () -> Unit = {},
        onCollage: ((List<Uri>) -> Unit)? = null,
        onPicked: (List<Uri>) -> Unit,
    ) {
        request = MediaRequest(kinds, multiple, onCancel, onPicked, onCollage)
    }
}

/** A video or picture on the phone. */
private class Item(val uri: Uri, val video: Boolean, val durationMs: Long, val bucketId: Long, val bucket: String)

/** An album: its id (null for all of them), name, how many it holds, and its newest item. */
private class Album(val id: Long?, val name: String, val count: Int, val cover: Item)

private val thumbs = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

/** The leave the picker needs: all media, or (Android 14 on) the user's own choice of it. */
private fun permissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun canRead(context: Context): Boolean = permissions().any {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}

/**
 * Whether the user let the app see only the photos and videos they chose (Android 14 on): the grid
 * then holds those alone, the ones picked for earlier projects, until they choose more.
 */
private fun limited(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return false
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    return granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
        !granted(Manifest.permission.READ_MEDIA_VIDEO) && !granted(Manifest.permission.READ_MEDIA_IMAGES)
}

/** The phone's videos and pictures, newest first. */
private fun loadMedia(context: Context): List<Item> {
    val out = ArrayList<Item>()
    val projection = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
    )
    val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE}=? OR ${MediaStore.Files.FileColumns.MEDIA_TYPE}=?"
    val args = arrayOf(
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(),
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(),
    )
    context.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
        projection,
        selection,
        args,
        "${MediaStore.MediaColumns.DATE_ADDED} DESC",
    )?.use { c ->
        val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val bucketId = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
        val bucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        while (c.moveToNext()) {
            val video = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            out.add(
                Item(
                    ContentUris.withAppendedId(base, c.getLong(id)),
                    video,
                    if (c.isNull(duration)) 0L else c.getLong(duration),
                    c.getLong(bucketId),
                    c.getString(bucket) ?: "",
                ),
            )
        }
    }
    return out
}

/** A length as the reference shows it on a tile: minutes and seconds, a part second counted whole. */
private fun clock(ms: Long): String {
    val s = (ms + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}

/** Hosts the picker over the app; put once at the root. */
@Composable
fun MediaPickerHost() {
    val req = MediaPick.request ?: return
    val context = LocalContext.current
    var allowed by remember(req) { mutableStateOf(canRead(context)) }
    fun finish(uris: List<Uri>) {
        MediaPick.request = null
        if (uris.isEmpty()) req.onCancel() else req.onPicked(uris)
    }
    val many = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { finish(it) }
    val one = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { finish(listOfNotNull(it)) }
    val systemPicker: () -> Unit = {
        val type = when (req.kinds) {
            MediaKinds.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
            MediaKinds.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
            MediaKinds.BOTH -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
        }
        if (req.multiple) many.launch(PickVisualMediaRequest(type)) else one.launch(PickVisualMediaRequest(type))
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        allowed = canRead(context)
        if (!allowed) systemPicker()
    }
    LaunchedEffect(req) { if (!allowed) ask.launch(permissions()) }
    if (allowed) {
        MediaPickerPage(req, onClose = { finish(emptyList()) }, onDone = ::finish) { uris ->
            MediaPick.request = null
            req.onCollage?.invoke(uris)
        }
    }
}

/**
 * The picker's page: an album menu and Videos and Photos tabs over a grid of the phone's media,
 * newest first. A tap on a tile's ring chooses it (numbered in the order chosen), and anywhere
 * else on it opens it large; the chosen line up in a tray over Add. A pick of one closes on the
 * first choice.
 */
@Composable
private fun MediaPickerPage(req: MediaRequest, onClose: () -> Unit, onDone: (List<Uri>) -> Unit, onCollage: (List<Uri>) -> Unit) {
    val context = LocalContext.current
    val tabs = when (req.kinds) {
        MediaKinds.VIDEO -> listOf("Videos")
        MediaKinds.IMAGE -> listOf("Photos")
        MediaKinds.BOTH -> listOf("Videos", "Photos")
    } + listOfNotNull("Collage".takeIf { req.onCollage != null })
    var tab by remember { mutableIntStateOf(0) }
    var album by remember { mutableStateOf<Long?>(null) }
    var albums by remember { mutableStateOf(false) }
    // read again once the user has chosen more of their media, or let the app see all of it
    var reads by remember { mutableIntStateOf(0) }
    var partial by remember { mutableStateOf(limited(context)) }
    val more = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        partial = limited(context)
        reads++
    }
    val all by produceState<List<Item>?>(null, reads) { value = withContext(Dispatchers.IO) { runCatching { loadMedia(context) }.getOrDefault(emptyList()) } }
    val chosen = remember { mutableStateListOf<Item>() }
    // what the open project holds already, marked on its tiles
    val imported = remember { MediaPick.project?.let { io.github.artcraftandroid.capcraft.engine.Media.imported(it) }.orEmpty() }
    // a tile opened large, before it is chosen
    var previewing by remember { mutableStateOf<Item?>(null) }
    BackHandler { if (albums) albums = false else onClose() }
    val videos = tabs[tab] == "Videos"
    // the Collage tab shows photos and videos together, and picks several
    val collage = tabs[tab] == "Collage"
    val multiple = req.multiple || collage
    val shown = all.orEmpty().filter { (collage || it.video == videos) && (album == null || it.bucketId == album) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E0E))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.X, Color.White, size = 24.dp, description = "Close")
            }
            Spacer(Modifier.width(18.dp))
            Row(Modifier.clickable { albums = !albums }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val name = album?.let { id -> all?.firstOrNull { it.bucketId == id }?.bucket } ?: "Albums"
                Text(name, style = Type.BodyStrong.copy(color = Color.White, fontSize = 15.sp), maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(16.dp).clip(RoundedCornerShape(4.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    Glyph(if (albums) LucideIcons.ChevronUp else LucideIcons.ChevronDown, Color.Black, size = 13.dp)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222222)))
        Row(Modifier.fillMaxWidth().height(46.dp)) {
            tabs.forEachIndexed { i, t ->
                Box(Modifier.weight(1f).fillMaxSize().clickable {
                    if (tab != i) chosen.clear()
                    tab = i
                }, contentAlignment = Alignment.Center) {
                    Text(
                        t,
                        style = Type.Body.copy(
                            color = if (i == tab) Palette.Accent else Palette.TextSecondary,
                            fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 15.sp,
                        ),
                    )
                    if (i == tab) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(Palette.Accent))
                }
            }
        }
        if (partial) {
            Row(
                Modifier.fillMaxWidth().background(Color(0xFF1C1C1C)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "CapCraft can see only the photos and videos you chose",
                    style = Type.Small.copy(color = Palette.TextSecondary),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Manage",
                    style = Type.Small.copy(color = Palette.Accent, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.clickable { more.launch(permissions()) }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                all == null -> CircularProgressIndicator(color = Palette.Accent, modifier = Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                shown.isEmpty() -> Text(
                    if (videos) "No videos" else "No photos",
                    style = Type.Small.copy(color = Palette.TextSecondary),
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 2.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(shown, key = { it.uri.toString() }) { item ->
                        val place = chosen.indexOf(item)
                        Tile(item, place, imported = item.uri.toString() in imported, onOpen = { previewing = item }, onPick = {
                            when {
                                !multiple -> onDone(listOf(item.uri))
                                place >= 0 -> chosen.remove(item)
                                chosen.size < (if (collage) 9 else 50) -> chosen.add(item)
                            }
                        })
                    }
                }
            }
            if (albums) {
                AlbumList(all.orEmpty().filter { collage || it.video == videos }, album) { id ->
                    album = id
                    albums = false
                }
            }
        }
        if (multiple) {
            Box(Modifier.fillMaxWidth().background(Color(0xFF1A1A1A))) {
                Column(Modifier.fillMaxWidth()) {
                    if (chosen.isNotEmpty()) {
                        LazyRow(
                            Modifier.fillMaxWidth().height(78.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(chosen.toList(), key = { it.uri.toString() }) { item ->
                                Box(Modifier.size(56.dp)) {
                                    Thumb(item, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
                                    if (item.video) {
                                        Text(clock(item.durationMs), style = shadowed(11), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
                                    }
                                    Box(
                                        Modifier.align(Alignment.TopEnd).padding(top = 0.dp).size(18.dp).clip(CircleShape).background(Color.White)
                                            .clickable { chosen.remove(item) },
                                        contentAlignment = Alignment.Center,
                                    ) { Glyph(LucideIcons.Minus, Color.Black, size = 12.dp, description = "Remove") }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (collage) Text("Choose 2 to 9", style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 13.sp))
                        Spacer(Modifier.weight(1f))
                        val ready = if (collage) chosen.size >= 2 else chosen.isNotEmpty()
                        Box(
                            Modifier.clip(RoundedCornerShape(8.dp))
                                .background(if (ready) Palette.Accent else Color(0xFF1C5A60))
                                .clickable(enabled = ready) { if (collage) onCollage(chosen.map { it.uri }) else onDone(chosen.map { it.uri }) }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                        ) {
                            Text(
                                if (ready) "Add (${chosen.size})" else "Add",
                                style = Type.BodyStrong.copy(color = if (ready) Color.White else Color(0xFF7FA9AD), fontSize = 14.sp),
                            )
                        }
                    }
                }
            }
        }
    }
    previewing?.let { item ->
        val place = chosen.indexOf(item)
        MediaPreview(
            item, place, multiple,
            onClose = { previewing = null },
            onPick = { if (place >= 0) chosen.remove(item) else if (chosen.size < 50) chosen.add(item) },
            onAdd = {
                previewing = null
                if (!multiple) {
                    onDone(listOf(item.uri))
                } else {
                    if (place < 0 && chosen.size < (if (collage) 9 else 50)) chosen.add(item)
                    if (!collage) onDone(chosen.map { it.uri }) else if (chosen.size >= 2) onCollage(chosen.map { it.uri })
                }
            },
        )
    }
}

private fun shadowed(size: Int) = Type.Small.copy(
    color = Color.White,
    fontSize = size.sp,
    shadow = Shadow(Color(0x99000000), blurRadius = 3f),
)

/**
 * One tile: the picture, its length for a video, and a ring that fills with its number when chosen.
 * The ring chooses it; anywhere else opens it large first, as the reference's picker does.
 */
@Composable
private fun Tile(item: Item, place: Int, imported: Boolean, onOpen: () -> Unit, onPick: () -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clickable(onClick = onOpen)) {
        Thumb(item, Modifier.fillMaxSize())
        if (place >= 0) Box(Modifier.fillMaxSize().background(Color(0x7A06343A)))
        // already in the project, as the reference marks it
        if (imported) {
            Text(
                "Imported",
                style = Type.Small.copy(color = Color.White, fontSize = 10.sp),
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp).clip(RoundedCornerShape(3.dp))
                    .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        if (item.video) {
            Text(clock(item.durationMs), style = shadowed(12), modifier = Modifier.align(Alignment.BottomEnd).padding(end = 6.dp, bottom = 4.dp))
        }
        // the ring, with room round it for a thumb
        Box(Modifier.align(Alignment.TopEnd).size(40.dp).clickable(onClick = onPick), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .then(if (place >= 0) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Color.White, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                if (place >= 0) Text("${place + 1}", style = Type.Small.copy(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
            }
        }
    }
}

/**
 * A picture or video of the phone's, large over the picker: a photo shown whole, a video playing
 * (the media volume is the phone's). The ring at the top chooses it, Add takes what is chosen.
 */
@Composable
private fun MediaPreview(item: Item, place: Int, multiple: Boolean, onClose: () -> Unit, onPick: () -> Unit, onAdd: () -> Unit) {
    val context = LocalContext.current
    androidx.activity.compose.BackHandler(onBack = onClose)
    val picture by produceState<android.graphics.Bitmap?>(null, item.uri) {
        value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(item.uri, Size(1080, 1920), null) }.getOrNull() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        if (item.video) {
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    android.widget.VideoView(ctx).apply {
                        setVideoURI(item.uri)
                        setOnPreparedListener { player -> player.isLooping = true; start() }
                    }
                },
                onRelease = { it.stopPlayback() },
                modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
            )
        } else {
            picture?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.ChevronLeft, Color.White, size = 26.dp, description = "Back")
            }
            Spacer(Modifier.weight(1f))
            if (multiple) {
                Box(Modifier.size(44.dp).clickable(onClick = onPick), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape)
                            .then(if (place >= 0) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Color.White, CircleShape)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (place >= 0) Text("${place + 1}", style = Type.Small.copy(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
                    }
                }
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp).clip(RoundedCornerShape(8.dp))
                .background(Palette.Accent).clickable(onClick = onAdd).padding(horizontal = 22.dp, vertical = 10.dp),
        ) { Text("Add", style = Type.BodyStrong.copy(color = Color.White, fontSize = 14.sp)) }
    }
}

/** A thumbnail from the phone's media store, kept in memory once made. */
@Composable
private fun Thumb(item: Item, modifier: Modifier) {
    val context = LocalContext.current
    val key = item.uri.toString()
    val picture by produceState(thumbs.get(key), key) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.loadThumbnail(item.uri, Size(240, 240), null) }.getOrNull()?.also { thumbs.put(key, it) }
            }
        }
    }
    Box(modifier.background(Color(0xFF1F1F1F))) {
        picture?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** The albums of [items], all of them first, as a list over the grid. */
@Composable
private fun AlbumList(items: List<Item>, current: Long?, onChoose: (Long?) -> Unit) {
    val list = remember(items) {
        val groups = items.groupBy { it.bucketId }
        buildList {
            items.firstOrNull()?.let { add(Album(null, "Recents", items.size, it)) }
            groups.forEach { (id, group) -> add(Album(id, group.first().bucket.ifEmpty { "Other" }, group.size, group.first())) }
        }
    }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF0E0E0E))) {
        items(list, key = { it.id ?: -1L }) { a ->
            Row(
                Modifier.fillMaxWidth().clickable { onChoose(a.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(a.cover, Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.name, style = Type.Body.copy(color = if (a.id == current) Palette.Accent else Color.White), maxLines = 1)
                    Text("${a.count}", style = Type.Small.copy(color = Palette.TextSecondary, fontSize = 12.sp))
                }
            }
        }
    }
}
