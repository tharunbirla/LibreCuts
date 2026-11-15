package io.github.artcraftandroid.capcraft.ui.editor.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.capcraft.core.MobileSession
import io.github.artcraftandroid.capcraft.core.TimelineView
import io.github.artcraftandroid.capcraft.ui.Palette
import io.github.artcraftandroid.capcraft.ui.editor.EditorViewModel
import org.json.JSONArray
import org.json.JSONObject

/** Where the timeline keeps a collage: its pictures' clip ids in cell order, and the layout chosen. */
private const val COLLAGE_KEY = "capcraftCollage"

/** One cell of a collage: where it sits on the frame, in fractions of the frame's width and height. */
internal class Cell(val x: Float, val y: Float, val w: Float, val h: Float)

/** A collage as the timeline keeps it. */
internal class Collage(val ids: List<String>, val layout: Int)

/** Rows of [counts] cells each, the rows equally tall and each row's cells equally wide. */
private fun rows(vararg counts: Int): List<Cell> = counts.withIndex().flatMap { (r, n) ->
    (0 until n).map { c -> Cell(c / n.toFloat(), r / counts.size.toFloat(), 1f / n, 1f / counts.size) }
}

/** [rows] turned on its side: columns of [counts] cells each. */
private fun columns(vararg counts: Int): List<Cell> = rows(*counts).map { Cell(it.y, it.x, it.h, it.w) }

/**
 * One big cell taking [share] of the frame along one [side] (0 left, 1 top, 2 right, 3 bottom), and
 * [rest] cells side by side in what is left.
 */
private fun featured(rest: Int, share: Float, side: Int): List<Cell> {
    val others = (0 until rest).map { i ->
        val f = i / rest.toFloat()
        val g = 1f / rest
        when (side) {
            0 -> Cell(share, f, 1 - share, g)
            1 -> Cell(f, share, g, 1 - share)
            2 -> Cell(0f, f, 1 - share, g)
            else -> Cell(f, 0f, g, 1 - share)
        }
    }
    val big = when (side) {
        0 -> Cell(0f, 0f, share, 1f)
        1 -> Cell(0f, 0f, 1f, share)
        2 -> Cell(1 - share, 0f, share, 1f)
        else -> Cell(0f, 1 - share, 1f, share)
    }
    return listOf(big) + others
}

/** The layouts for [n] pictures, the first the one a new collage starts with. */
internal fun collageLayouts(n: Int): List<List<Cell>> = when (n) {
    1 -> listOf(rows(1))
    2 -> listOf(rows(1, 1), rows(2), featured(1, 2 / 3f, 0), featured(1, 2 / 3f, 1), featured(1, 2 / 3f, 2), featured(1, 2 / 3f, 3))
    3 -> listOf(rows(1, 1, 1), rows(3), featured(2, 0.5f, 0), featured(2, 0.5f, 1), featured(2, 0.5f, 2), featured(2, 0.5f, 3))
    4 -> listOf(rows(2, 2), rows(4), rows(1, 1, 1, 1), featured(3, 0.6f, 0), featured(3, 0.6f, 1), rows(1, 3), rows(3, 1))
    5 -> listOf(rows(2, 3), rows(3, 2), columns(2, 3), columns(3, 2), rows(1, 4), featured(4, 0.5f, 1))
    6 -> listOf(rows(3, 3), rows(2, 2, 2), rows(1, 2, 3), featured(5, 0.5f, 1), columns(1, 2, 3))
    7 -> listOf(rows(3, 4), rows(4, 3), rows(2, 3, 2), columns(3, 4), columns(2, 3, 2))
    8 -> listOf(rows(4, 4), rows(2, 2, 2, 2), rows(3, 2, 3), columns(3, 2, 3))
    else -> listOf(rows(3, 3, 3), rows(4, 1, 4), columns(4, 1, 4))
}

/** The collage kept on this session's timeline, if it is one. */
internal fun MobileSession.collage(): Collage? {
    val kept = runCatching { extra(COLLAGE_KEY).takeIf { it.isNotBlank() }?.let(::JSONObject) }.getOrNull() ?: return null
    val ids = kept.optJSONArray("ids") ?: return null
    return Collage((0 until ids.length()).map { ids.optString(it) }, kept.optInt("layout", 0))
}

/**
 * Lays [ids]'s pictures into the cells of layout [layout] on [view]'s frame, as one step: each
 * cropped to its cell's shape about its middle and scaled to fill it, with the collage kept on the
 * timeline. A picture no longer there leaves its cell empty. The view after.
 */
internal fun MobileSession.layCollage(view: TimelineView, ids: List<String>, layout: Int): TimelineView {
    val cells = collageLayouts(ids.size).getOrNull(layout) ?: return view
    val frameW = view.width.toDouble()
    val frameH = view.height.toDouble()
    val clips = view.rows.flatMap { it.clips }
    val batch = JSONArray()
    ids.zip(cells).forEach { (id, cell) ->
        val clip = clips.firstOrNull { it.id == id } ?: return@forEach
        val mw = clip.mediaWidth.toDouble().takeIf { it > 0 } ?: frameW
        val mh = clip.mediaHeight.toDouble().takeIf { it > 0 } ?: frameH
        val cellW = cell.w * frameW
        val cellH = cell.h * frameH
        // cut to the cell's shape, evenly off both sides that are too long
        var left = 0.0
        var top = 0.0
        if (mw / mh > cellW / cellH) left = (1 - (cellW / cellH) / (mw / mh)) / 2 else top = (1 - (mw / mh) / (cellW / cellH)) / 2
        val cutW = mw * (1 - 2 * left)
        val cutH = mh * (1 - 2 * top)
        // the engine fits the cut picture to the frame; the scale takes it from there to the cell
        val fit = minOf(frameW / cutW, frameH / cutH)
        batch.put(
            JSONObject().put("op", "updateClip").put("clipId", id).put(
                "patch",
                JSONObject().put("crop", JSONObject().put("left", left).put("right", left).put("top", top).put("bottom", top)),
            ),
        )
        batch.put(
            JSONObject().put("op", "setClipTransform").put("clipId", id)
                .put("scale", cellW / (cutW * fit))
                .put("offsetX", cell.x + cell.w / 2 - 0.5)
                .put("offsetY", cell.y + cell.h / 2 - 0.5)
                .put("rotation", 0.0),
        )
    }
    batch.put(
        JSONObject().put("op", "setTimelineExtra").put("key", COLLAGE_KEY)
            .put("value", JSONObject().put("ids", JSONArray(ids)).put("layout", layout)),
    )
    return apply(JSONObject().put("op", "batch").put("commands", batch).toString(), null)
}

/** Layout: the collage's layouts for as many pictures as it has, the chosen one lit; a tap lays them out so. */
@Composable
fun CollagePanel(vm: EditorViewModel) {
    val kept = remember(vm.timeline?.revision) { vm.session?.collage() }
    if (kept == null) {
        vm.panel = null
        return
    }
    val layouts = collageLayouts(kept.ids.size)
    PanelFrame("Layout", vm, height = 150.dp) {
        LazyRow(Modifier.fillMaxWidth().padding(top = 22.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            items(layouts.size) { i ->
                LayoutTile(layouts[i], i == kept.layout) {
                    vm.edit { layCollage(view(), kept.ids, i) }
                }
            }
        }
    }
}

/** A layout drawn as its cells: outlined, and filled and cyan-edged when chosen. */
@Composable
private fun LayoutTile(cells: List<Cell>, selected: Boolean, onClick: () -> Unit) {
    Canvas(Modifier.padding(horizontal = 9.dp).size(48.dp).clickable(onClick = onClick)) {
        val gap = 1.5.dp.toPx()
        val edge = if (selected) Palette.Accent else Color(0xFFDDDDDD)
        cells.forEach { c ->
            val at = Offset(c.x * size.width + gap / 2, c.y * size.height + gap / 2)
            val box = Size(c.w * size.width - gap, c.h * size.height - gap)
            if (selected) drawRect(Color(0xFF2E5552), at, box)
            drawRect(edge, at, box, style = Stroke(1.5.dp.toPx()))
        }
    }
}
