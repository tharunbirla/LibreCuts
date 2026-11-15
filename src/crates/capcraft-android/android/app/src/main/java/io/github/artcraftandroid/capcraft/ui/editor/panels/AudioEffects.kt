package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.capcraft.ui.Glyph
import io.github.artcraftandroid.capcraft.ui.LucideIcons
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.Type
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject

/** The cleaning packages, which are switches in Volume rather than effects here. */
private val CLEANING = setOf("concat.denoise", "concat.enhance-voice", "concat.loudness")

/**
 * Audio effects as the reference lays them out: its two tabs in the bar (voice filters, which colour
 * the sound, and voice characters, which change who seems to speak), a search, and a grid of small
 * named tiles seven across, Original first. A tap puts the effect on the clip's sound in place of
 * the one it had; Original takes it off.
 */
@Composable
fun AudioEffectsPanel(vm: EditorViewModel) {
    val clip = vm.selectedClip ?: run { vm.panel = null; return }
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val all = rememberPackages("audio").filter { it.id !in CLEANING }
    val ids = remember(all) { all.map { it.id }.toSet() }
    val shown = all.filter { p ->
        if (query.isNotBlank()) p.name.contains(query.trim(), ignoreCase = true)
        else if (tab == 0) p.category != "Voice" else p.category == "Voice"
    }
    val chain = remember(clip.json) { vm.clipJson(clip.id)?.optJSONArray("filters") ?: JSONArray() }
    val current = (0 until chain.length()).map { chain.getJSONObject(it).optString("id") }.firstOrNull { it in ids }

    fun apply(id: String?) {
        val old = vm.clipJson(clip.id)?.optJSONArray("filters") ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until old.length()) {
            val link = old.getJSONObject(i)
            if (link.optString("id") !in ids) next.put(link)
        }
        if (id != null) next.put(JSONObject().put("id", id).put("params", JSONObject()))
        vm.patch(clip.id, JSONObject().put("filters", next))
    }

    PanelFrame("Audio effects", vm, tall = true, titleOnTop = true, header = {
        Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Voice filters", "Voice characters").forEachIndexed { i, name ->
                val on = i == tab && query.isBlank()
                androidx.compose.foundation.layout.Column(
                    Modifier.clickable {
                        tab = i
                        query = ""
                        searching = false
                    }.padding(horizontal = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        name,
                        style = Type.Body.copy(
                            fontSize = 15.sp,
                            color = if (on) Palette.Text else Palette.TextSecondary,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                    )
                    Box(Modifier.size(28.dp, 2.dp).background(if (on) Palette.Accent else Color.Transparent))
                }
            }
        }
    }) {
        Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                Box(Modifier.weight(1f)) { SearchField(query, { query = it }, "Search audio effects", autoFocus = true) }
                Text(
                    "Cancel",
                    style = Type.Body.copy(color = Palette.DockText),
                    modifier = Modifier.clickable { query = ""; searching = false }.padding(horizontal = 10.dp, vertical = 8.dp),
                )
            } else {
                Box(Modifier.size(32.dp).clickable { searching = true }, contentAlignment = Alignment.Center) {
                    Glyph(LucideIcons.Search, Palette.DockText, size = 18.dp, description = "Search audio effects")
                }
                Box(Modifier.padding(horizontal = 10.dp).size(1.dp, 18.dp).background(Color(0xFF444444)))
                Text("All", style = Type.BodyStrong.copy(color = Palette.Text, fontSize = 15.sp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(7),
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (query.isBlank()) {
                item { SoundTile("Original", current == null, bold = true) { apply(null) } }
            }
            items(shown.size) { i ->
                val p = shown[i]
                SoundTile(p.name, current == p.id) { apply(p.id) }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A small dark tile with the effect's name on it, ringed white when chosen. */
@Composable
private fun SoundTile(name: String, selected: Boolean, bold: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(Color(0xFF3A3A3A))
            .then(if (selected) Modifier.border(2.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name,
            style = Type.Small.copy(
                color = Palette.Text,
                fontSize = if (name.length > 10) 9.sp else 11.sp,
                lineHeight = 12.sp,
                fontWeight = if (bold || selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}
