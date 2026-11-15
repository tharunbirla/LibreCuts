package io.github.artcraftandroid.capcraft.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.Motion
import io.github.artcraftandroid.capcraft.engine.Media
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.engine.toBitmap
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.common.MediaKinds
import io.github.artcraftandroid.capcraft.ui.common.MediaPick
import io.github.artcraftandroid.capcraft.ui.editor.panels.CapSlider
import io.github.artcraftandroid.capcraft.ui.editor.panels.Tabs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The project's cover: a frame of the video, chosen by sliding through it, or a picture from the
 * album, saved as the picture the home screen shows for the project.
 */
/** The picture at [uri], cut about its middle to the video's shape and made no larger than 720 across. */
private fun croppedTo(context: android.content.Context, uri: android.net.Uri, width: UInt, height: UInt): android.graphics.Bitmap? {
    val source = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
    val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
    val (w, h) = if (source.width.toFloat() / source.height > aspect) {
        (source.height * aspect).roundToInt() to source.height
    } else {
        source.width to (source.width / aspect).roundToInt()
    }
    val cut = android.graphics.Bitmap.createBitmap(source, (source.width - w) / 2, (source.height - h) / 2, w.coerceAtLeast(1), h.coerceAtLeast(1))
    val scale = minOf(1f, 720f / cut.width)
    return if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(cut, 720, (cut.height * scale).roundToInt().coerceAtLeast(1), true) else cut
}

@Composable
fun CoverEditor(vm: EditorViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val t = vm.timeline ?: return
    val end = t.durationUs.coerceAtLeast(1)
    var at by remember { mutableLongStateOf(vm.coverAt.coerceIn(0, end - 1)) }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    // 0 the video's frames, 1 a picture from the album, cut to the video's shape
    var tab by remember { mutableIntStateOf(if (vm.coverAt < 0) 1 else 0) }
    var album by remember {
        mutableStateOf(if (vm.coverAt < 0) runCatching { android.graphics.BitmapFactory.decodeFile(java.io.File(vm.path, "cover.jpg").path) }.getOrNull() else null)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    fun pickAlbum() = MediaPick.open(MediaKinds.IMAGE, multiple = false) { uris ->
        scope.launch {
            album = withContext(Threads.preview) { runCatching { croppedTo(context, uris.first(), t.width, t.height) }.getOrNull() } ?: album
            tab = 1
        }
    }
    BackHandler(onBack = onClose)

    LaunchedEffect(at) {
        val s = vm.session ?: return@LaunchedEffect
        val w = 720
        val h = (w * t.height.toFloat() / t.width.toFloat()).roundToInt() / 2 * 2
        picture = withContext(Threads.preview) {
            runCatching { s.frame(at, w.toUInt(), h.coerceAtLeast(2).toUInt(), Motion.STILL).toBitmap()?.asImageBitmap() }.getOrNull()
        } ?: picture
    }

    Column(Modifier.fillMaxSize().background(Palette.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.X, Palette.Text, size = 26.dp, description = "Close")
            }
            Spacer(Modifier.weight(1f))
            Text("Reset", style = Type.Body, modifier = Modifier.clickable { at = 0; tab = 0 }.padding(12.dp))
            Box(
                Modifier.padding(end = 12.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(Palette.Accent)
                    .clickable {
                        val s = vm.session ?: return@clickable
                        val chosen = album
                        if (tab == 1 && chosen != null) {
                            scope.launch {
                                withContext(Threads.preview) { Media.saveCover(vm.path, chosen) }
                                vm.coverAt = -1
                                runCatching { java.io.File(vm.path, "cover-time.txt").writeText("-1") }
                                vm.message = "Cover saved"
                                onClose()
                            }
                            return@clickable
                        }
                        scope.launch {
                            withContext(Threads.preview) {
                                runCatching {
                                    val w = 480
                                    val h = (w * t.height.toFloat() / t.width.toFloat()).roundToInt() / 2 * 2
                                    s.frame(at, w.toUInt(), h.coerceAtLeast(2).toUInt(), Motion.STILL).toBitmap()?.let { Media.saveCover(vm.path, it) }
                                }
                            }
                            vm.coverAt = at
                            runCatching { java.io.File(vm.path, "cover-time.txt").writeText(at.toString()) }
                            vm.message = "Cover saved"
                            onClose()
                        }
                    }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text("Save", style = Type.BodyStrong.copy(color = Palette.OnAccent)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            val aspect = t.width.toFloat() / t.height.toFloat().coerceAtLeast(1f)
            val (w, h) = if (boxW / boxH > aspect) (boxH * aspect) to boxH else boxW to (boxW / aspect)
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() }).clip(RoundedCornerShape(6.dp)).background(Palette.Card)) {
                val shown = if (tab == 1) album?.asImageBitmap() else picture
                shown?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        Tabs(listOf("Video frame", "Add from album"), tab) { i ->
            if (i == 1 && album == null) pickAlbum() else tab = i
        }
        if (tab == 0) {
            Text(clock(at), style = Type.Small, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            CapSlider(
                value = at.toFloat() / end,
                range = 0f..1f,
                label = null,
                onChange = { at = (it * end).toLong().coerceIn(0, end - 1) },
                onDone = {},
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        } else {
            Text(
                "Choose another picture",
                style = Type.Body.copy(color = Palette.Accent),
                modifier = Modifier.clickable { pickAlbum() }.padding(horizontal = 20.dp, vertical = 18.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
