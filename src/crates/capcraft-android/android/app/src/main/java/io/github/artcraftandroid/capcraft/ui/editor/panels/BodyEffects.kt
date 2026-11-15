package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.SpeechListener
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.engine.Threads
import io.github.artcraftandroid.capcraft.engine.userMessage
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Where the timeline keeps each clip's body effect (the bridge's `BODY`). */
private const val BODY = "capcraftBody"

/** A body effect: the bridge's name for it, and ours. */
private class BodyLook(val kind: String, val label: String)

private val LOOKS = listOf(
    BodyLook("", "None"),
    BodyLook("blur", "Blur background"),
    BodyLook("pop", "Colour pop"),
    BodyLook("spot", "Spotlight"),
    BodyLook("neon", "Neon outline"),
    BodyLook("aura", "Aura"),
    BodyLook("lines", "Glow lines"),
    BodyLook("dream", "Dream"),
    BodyLook("trippy", "Trippy"),
    BodyLook("glitch", "Glitch"),
    BodyLook("comic", "Comic"),
)

/**
 * Body effects: looks that treat the person in the clip apart from what is behind them. The
 * person is found on the phone (the model is fetched the first time), so a new look shows on the
 * background at once and on the person when the masks are in.
 */
@Composable
fun BodyEffectsPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip?.takeIf { it.kind == "video" || it.kind == "image" } ?: vm.mainClipAtPlayhead()
    if (clip == null) {
        vm.message = "Add a video first"
        vm.panel = null
        return
    }
    val scope = rememberCoroutineScope()
    val current = remember(vm.timeline?.revision, clip.id) {
        runCatching { vm.session?.extra(BODY)?.takeIf { it.isNotBlank() }?.let(::JSONObject)?.optJSONObject(clip.id)?.optString("kind") }
            .getOrNull().orEmpty()
    }
    var progress by remember { mutableStateOf<Pair<String, Float>?>(null) }
    val listener = remember {
        object : SpeechListener {
            override fun onProgress(fraction: Float, stage: String) {
                scope.launch(Dispatchers.Main) {
                    progress = (if (stage == "download") "Downloading the model" else "Finding the person") to fraction
                }
            }

            override fun onDone(result: TimelineView?) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    result?.let { vm.applyView(it) }
                }
            }

            override fun onFailed(message: String) {
                scope.launch(Dispatchers.Main) {
                    progress = null
                    vm.message = message
                }
            }
        }
    }
    fun choose(kind: String) {
        val s = vm.session ?: return
        if (kind == current) return
        if (kind.isNotEmpty()) progress = "Finding the person" to 0f
        scope.launch(Threads.commands) {
            // the edit is in at once; the masks come after, through the listener
            runCatching { s.bodyEffect(clip.id, kind, listener); s.view() }
                .onSuccess { view -> launch(Dispatchers.Main) { vm.applyView(view) } }
                .onFailure { e -> launch(Dispatchers.Main) { progress = null; vm.message = e.userMessage } }
        }
    }
    val frame = vm.lastFrame
    PanelFrame("Body effects", vm, tall = true, titleOnTop = true) {
        val p = progress
        if (p != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${p.first}… ${(p.second * 100).roundToInt()}%", style = Type.Small.copy(color = Palette.Text), modifier = Modifier.weight(1f))
                Text(
                    "Stop",
                    style = Type.Small.copy(color = Palette.Text),
                    modifier = Modifier.clickable { vm.session?.cancelBackground(); progress = null }.padding(4.dp),
                )
            }
        }
        ChoiceGrid(LOOKS.size, Modifier.weight(1f)) { i ->
            val look = LOOKS[i]
            GridTile(look.label, look.kind == current, onClick = { choose(look.kind) }) {
                if (look.kind.isEmpty() || frame == null) {
                    Glyph(if (look.kind.isEmpty()) LucideIcons.Ban else LucideIcons.UserRound, Palette.TextSecondary, size = 26.dp)
                } else {
                    // the frame at the playhead, shaded the way the look shades the background
                    Image(
                        frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        colorFilter = tileShade(look.kind),
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Glyph(
                            LucideIcons.UserRound,
                            when (look.kind) {
                                "neon" -> Palette.Accent
                                "aura" -> androidx.compose.ui.graphics.Color(0xFFFFC94D)
                                "lines" -> androidx.compose.ui.graphics.Color(0xFFFF4DFF)
                                else -> androidx.compose.ui.graphics.Color.White
                            },
                            size = 28.dp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** How a look's tile shades the frame: grey, dark, or softened by a dimming for blur. */
private fun tileShade(kind: String): ColorFilter? = when (kind) {
    "pop" -> ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    "spot" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.35f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f, 0f, 0f, 0f, 0f, 0.35f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    "neon" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    "blur" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.8f, 0f, 0f, 0f, 30f, 0f, 0.8f, 0f, 0f, 30f, 0f, 0f, 0.8f, 0f, 30f, 0f, 0f, 0f, 1f, 0f)))
    "aura", "lines" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.6f, 0f, 0f, 0f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0f, 0f, 0.6f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    "dream" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0.75f, 0f, 0f, 0f, 60f, 0f, 0.75f, 0f, 0f, 55f, 0f, 0f, 0.75f, 0f, 70f, 0f, 0f, 0f, 1f, 0f)))
    // the channels turned round, as the colours cycle
    "trippy" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    "glitch" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1.2f, 0f, 0f, 0f, 0f, 0f, 0.7f, 0f, 0f, 0f, 0f, 0f, 1.3f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    "comic" -> ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(1.5f, 0f, 0f, 0f, -50f, 0f, 1.5f, 0f, 0f, -50f, 0f, 0f, 1.5f, 0f, -50f, 0f, 0f, 0f, 1f, 0f)))
    else -> null
}
