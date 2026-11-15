package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.RowKind
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel

/**
 * What covers what: the selected clip's row moved in front of or behind the other rows of its
 * kind (overlays among overlays, titles and stickers among their own).
 */
@Composable
fun LayerPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val row = vm.rowOf(clip.id) ?: run { vm.panel = null; return }
    // titles, stickers and overlays are arranged together, as the reference's sheet does
    if (row.kind == RowKind.TEXT || row.kind == RowKind.OVERLAY) {
        ArrangeLayersPanel(vm)
        return
    }
    // the timeline's rows run from the back of the stack to the front
    // an effect stacks among those that treat what it treats
    val peers = vm.timeline?.rows.orEmpty().filter { it.kind == row.kind && it.allVideos == row.allVideos }
    val place = peers.indexOfFirst { it.trackId == row.trackId }.coerceAtLeast(0)
    val count = peers.size.coerceAtLeast(1)

    fun move(step: Int) = vm.edit { restack(clip.id, step) }

    PanelFrame("Layer", vm) {
        Spacer(Modifier.height(20.dp))
        Text(
            if (count == 1) "Nothing else of its kind to stack against" else "Layer ${place + 1} of $count, counted from the back",
            style = Type.Small,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            LayerTile("To back", LucideIcons.SendToBack, enabled = place > 0) { move(-count) }
            LayerTile("Backward", LucideIcons.ChevronDown, enabled = place > 0) { move(-1) }
            LayerTile("Forward", LucideIcons.ChevronUp, enabled = place < count - 1) { move(1) }
            LayerTile("To front", LucideIcons.BringToFront, enabled = place < count - 1) { move(count) }
        }
    }
}

@Composable
private fun LayerTile(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    ChoiceTile(label, selected = false, onClick = { if (enabled) onClick() }, size = 56.dp) {
        Glyph(icon, if (enabled) Palette.Text else Palette.Disabled, size = 24.dp)
    }
}
