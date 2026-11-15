package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.engine.clock
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import org.json.JSONObject

/**
 * Edit captions, as the reference's sheet: every caption in one list by its start, the one picked
 * lit, each with a play mark and its time, its words corrected in place (on the timeline as they
 * are typed, one undo step for the whole sitting). Select ticks lines to take off together. For a
 * title that is no caption, the titles on its row.
 */
@Composable
fun BatchEditPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    val row = vm.rowOf(clip.id) ?: run { vm.panel = null; return }
    val keyboard = LocalSoftwareKeyboardController.current
    // the lines as the sheet opened, so the list does not reorder while typing
    val lines = remember(row.trackId) {
        val captions = vm.captions
        val all = vm.timeline?.rows.orEmpty().flatMap { it.clips }
        (if (clip.id in captions) all.filter { it.id in captions } else row.clips.filter { it.kind == "text" })
            .sortedBy { it.startUs }.map { it.id to it.startUs }
    }
    val alive = lines.filter { (id, _) -> vm.clip(id) != null }
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }

    fun done() {
        vm.endGesture()
        keyboard?.hide()
        vm.panel = null
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.62f)
            .background(Palette.Panel)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 12.dp)) {
            Text(
                if (clip.id in vm.captions) "Edit captions" else "Batch edit",
                style = Type.BodyStrong.copy(fontSize = 16.sp, color = Palette.DockText),
                modifier = Modifier.align(Alignment.Center),
            )
            Box(Modifier.size(44.dp).align(Alignment.CenterEnd).clickable(onClick = ::done), contentAlignment = Alignment.Center) {
                Glyph(LucideIcons.Check, Palette.Text, size = 26.dp, description = "Done")
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(alive, key = { it.first }) { (id, start) ->
                CaptionLine(
                    vm, id, start,
                    current = id == vm.selected,
                    tick = if (picking) id in picked else null,
                    onTick = { picked = if (id in picked) picked - id else picked + id },
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF2E2E2E)))
        // what can be done to several lines at once
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (picking) {
                BarItem(LucideIcons.X, "Cancel") { picking = false; picked = emptySet() }
                BarItem(LucideIcons.Trash, if (picked.isEmpty()) "Delete" else "Delete (${picked.size})", enabled = picked.isNotEmpty()) {
                    val gone = picked.toList()
                    vm.edit { delete(gone) }
                    picking = false
                    picked = emptySet()
                    if (clip.id in gone) vm.panel = null
                }
            } else {
                BarItem(LucideIcons.SquareCheckBig, "Select") { picking = true }
            }
        }
    }
}

/** A dock-style item of the sheet's bottom bar: an icon over its name. */
@Composable
private fun BarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val tint = if (enabled) Palette.DockText else Palette.Disabled
    Column(Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 18.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Glyph(icon, tint, size = 22.dp)
        Spacer(Modifier.height(4.dp))
        Text(label, style = Type.DockLabel.copy(color = tint))
    }
}

@Composable
private fun CaptionLine(vm: EditorViewModel, id: String, start: Long, current: Boolean, tick: Boolean?, onTick: () -> Unit) {
    val style = remember(id) { vm.clipJson(id)?.optJSONObject("text") ?: JSONObject() }
    var words by remember(id) { mutableStateOf(style.optString("content")) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (current) Color(0xFF333333) else Palette.Panel)
            .clickable { if (tick != null) onTick() else { vm.selected = id; vm.scrub(start) } }
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tick != null) {
            Box(
                Modifier.size(20.dp).clip(CircleShape)
                    .then(if (tick) Modifier.background(Palette.Accent) else Modifier.border(1.5.dp, Palette.TextSecondary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) { if (tick) Glyph(LucideIcons.Check, Color.Black, size = 13.dp) }
            Spacer(Modifier.width(10.dp))
        } else {
            // plays from this line
            Box(
                Modifier.size(28.dp).clickable {
                    vm.selected = id
                    vm.scrub(start)
                    vm.start()
                },
                contentAlignment = Alignment.Center,
            ) { Glyph(LucideIcons.Play, if (current) Palette.Text else Palette.TextSecondary, size = 14.dp, description = "Play") }
        }
        Text(clock(start), style = Type.Small.copy(color = if (current) Palette.Text else Palette.TextSecondary), modifier = Modifier.width(48.dp))
        BasicTextField(
            value = words,
            onValueChange = {
                words = it
                // one undo step for the whole sitting of corrections
                vm.patch(id, JSONObject().put("text", JSONObject(style.toString()).put("content", it)), gesture = "batch-edit")
            },
            enabled = tick == null,
            textStyle = Type.Body.copy(color = if (current) Palette.Text else Palette.TextSecondary, fontSize = 15.sp),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
        )
    }
}
