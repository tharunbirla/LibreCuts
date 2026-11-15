package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.artcraftandroid.capcraft.core.ClipView
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import io.github.artcraftandroid.capcraft.ui.editor.Thumbs
import kotlin.math.roundToInt

/** A title's tile in the layers row: a warm card of our own, not the timeline's orange. */
private val TitleTile = Color(0xFFE8735A)

/**
 * What covers what, as the reference's Arrange layers sheet shows it: the titles, stickers and
 * overlays on screen at the playhead in one row, the front first, whatever kind each is. A tap
 * picks one; pressed and dragged it moves among them; the buttons at either end send the picked
 * one to the front or the back. A menu narrows the row to titles and stickers. Each moves with its
 * whole row, which is how the engine stacks.
 */
@Composable
fun ArrangeLayersPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    var textOnly by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val at = vm.playhead
    // the timeline's rows run from the back of the stack to the front; each gives the picked clip,
    // or the one it shows at the playhead
    val layers = vm.timeline?.rows.orEmpty().reversed()
        .filter { it.kind == RowKind.TEXT || (!textOnly && it.kind == RowKind.OVERLAY) }
        .mapNotNull { row -> row.clips.firstOrNull { it.id == clip.id } ?: row.clips.firstOrNull { at >= it.startUs && at < it.startUs + it.durationUs } }
    val place = layers.indexOfFirst { it.id == clip.id }

    fun put(moving: ClipView, target: ClipView, above: Boolean) {
        if (moving.id != target.id) vm.edit(select = { moving.id }) { arrange(moving.id, target.id, above) }
    }

    PanelFrame("Arrange layers", vm, height = 216.dp, titleOnTop = true) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.padding(start = 16.dp, top = 10.dp).clickable { menu = !menu }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (textOnly) "Text and stickers" else "All", style = Type.Body.copy(color = Palette.Text))
                    Spacer(Modifier.width(4.dp))
                    Glyph(if (menu) LucideIcons.ChevronUp else LucideIcons.ChevronDown, Palette.Text, size = 14.dp)
                }
                Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    EndButton(LucideIcons.ArrowLeftToLine, "To front", enabled = place > 0) { put(clip, layers.first(), above = true) }
                    Row(
                        Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (layers.isEmpty()) {
                            Text("Nothing on screen here to arrange", style = Type.Small)
                        }
                        val slot = with(LocalDensity.current) { (LAYER_TILE + 10.dp).toPx() }
                        layers.forEachIndexed { i, c ->
                            key(c.id) {
                                var drag by remember { mutableFloatStateOf(0f) }
                                LayerTile(
                                    vm,
                                    c,
                                    picked = c.id == clip.id,
                                    modifier = Modifier
                                        .zIndex(if (drag != 0f) 1f else 0f)
                                        .graphicsLayer { translationX = drag }
                                        .pointerInput(c.id, layers.size, i) {
                                            detectDragGesturesAfterLongPress(
                                                onDragStart = { vm.selected = c.id },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag += amount.x
                                                },
                                                onDragEnd = {
                                                    val to = (i + (drag / slot).roundToInt()).coerceIn(0, layers.lastIndex)
                                                    drag = 0f
                                                    // to the left is in front of the one there, to the right behind it
                                                    if (to != i) put(c, layers[to], above = to < i)
                                                },
                                                onDragCancel = { drag = 0f },
                                            )
                                        }
                                        .clickable { vm.selected = c.id },
                                )
                            }
                        }
                    }
                    EndButton(LucideIcons.ArrowRightToLine, "To back", enabled = place in 0 until layers.lastIndex) { put(clip, layers.last(), above = false) }
                }
            }
            if (menu) {
                Column(
                    Modifier.padding(start = 8.dp, top = 44.dp).width(190.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF333333)),
                ) {
                    listOf(true to "Text and stickers", false to "All").forEach { (only, name) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { textOnly = only; menu = false }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val on = textOnly == only
                            Box(
                                Modifier.size(16.dp).clip(CircleShape)
                                    .then(if (on) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.TextSecondary, CircleShape)),
                                contentAlignment = Alignment.Center,
                            ) { if (on) Glyph(LucideIcons.Check, Color.Black, size = 11.dp) }
                            Spacer(Modifier.width(10.dp))
                            Text(name, style = Type.Body.copy(color = Palette.Text))
                        }
                    }
                }
            }
        }
    }
}

private val LAYER_TILE = 52.dp

/** One layer: a title's words on a warm card, a sticker's picture, an overlay's frame; its kind on a chip. */
@Composable
private fun LayerTile(vm: EditorViewModel, c: ClipView, picked: Boolean, modifier: Modifier) {
    @Suppress("UNUSED_VARIABLE") val thumbs = Thumbs.version.intValue
    val sticker = c.kind == "image" && vm.rowOf(c.id)?.kind == RowKind.TEXT
    val label = when {
        c.kind == "text" -> "Text"
        c.kind == "shape" -> "Shape"
        sticker -> "Sticker"
        else -> "Overlay"
    }
    val bitmap = when {
        c.kind == "text" || c.kind == "shape" -> null
        sticker -> Thumbs.still(c.path, 0, 160)
        else -> cutFrame(c, end = false)
    }
    val picture = remember(bitmap) { bitmap?.asImageBitmap() }
    Box(
        modifier
            .size(LAYER_TILE + 6.dp)
            .then(if (picked) Modifier.border(2.dp, Color.White, RoundedCornerShape(8.dp)) else Modifier)
            .padding(3.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (c.kind == "text") TitleTile else Color(0xFF3A3A3A)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            c.kind == "text" -> Text(
                c.text.ifBlank { c.name }.replace('\n', ' '),
                style = Type.Small.copy(color = Color.White, fontSize = 11.sp),
                textAlign = TextAlign.Center,
                maxLines = 2,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            c.kind == "shape" -> Glyph(LucideIcons.Shapes, Color.White, size = 22.dp)
            picture != null -> Image(
                picture,
                contentDescription = null,
                contentScale = if (sticker) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize().then(if (sticker) Modifier.padding(6.dp) else Modifier),
            )
        }
        Text(
            label,
            style = Type.Tiny.copy(color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Medium),
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp)
                .clip(RoundedCornerShape(3.dp)).background(Color(0x99000000)).padding(horizontal = 3.dp),
        )
    }
}

/** A round button at an end of the layers row, its name under it. */
@Composable
private fun EndButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) Palette.Text else Palette.Disabled
    Column(Modifier.width(64.dp).clickable(enabled = enabled, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF333333)), contentAlignment = Alignment.Center) {
            Glyph(icon, tint, size = 18.dp, description = label)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = Type.Small.copy(color = tint), maxLines = 1)
    }
}
