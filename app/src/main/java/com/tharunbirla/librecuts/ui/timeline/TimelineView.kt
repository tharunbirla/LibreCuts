package com.tharunbirla.librecuts.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.MediaType
import com.tharunbirla.librecuts.models.Track
import com.tharunbirla.librecuts.viewmodels.VideoEditorViewModel
import kotlin.math.roundToInt

@Composable
fun TimelineScreen(viewModel: VideoEditorViewModel) {
    val projectState by viewModel.projectState.collectAsState()
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.DarkGray)
            .padding(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Timeline", color = Color.White, style = MaterialTheme.typography.titleMedium)

            // Zoom Control
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = zoomLevel,
                    onValueChange = { zoomLevel = it },
                    valueRange = 0.5f..5.0f,
                    modifier = Modifier.width(100.dp)
                )
            }
        }

        // Render tracks
        projectState.tracks.forEach { track ->
            TrackView(track = track, viewModel = viewModel, zoomLevel = zoomLevel)
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
fun TrackView(track: Track, viewModel: VideoEditorViewModel, zoomLevel: Float) {
    Column {
        Text(
            text = track.trackType.name,
            color = Color.LightGray,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        )

        // Using LazyRow for scrolling support
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .background(Color.Gray.copy(alpha = 0.3f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            itemsIndexed(track.clips) { index, clip ->
                DraggableClipView(
                    clip = clip,
                    index = index,
                    trackType = track.trackType,
                    zoomLevel = zoomLevel,
                    onMove = { from, to -> viewModel.moveClip(track.trackType, from, to) },
                    onRemove = { viewModel.removeClip(clip.id) }
                )
            }
        }
    }
}

@Composable
fun DraggableClipView(
    clip: Clip,
    index: Int,
    trackType: MediaType,
    zoomLevel: Float,
    onMove: (Int, Int) -> Unit,
    onRemove: () -> Unit
) {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    // Calculate width based on duration and zoom
    // Base scale: 1 second = 50 dp (arbitrary base)
    val baseWidthPerSec = 20.dp
    val durationSec = clip.durationMs / 1000f
    val clipWidth = (baseWidthPerSec * durationSec * zoomLevel).coerceAtLeast(50.dp)

    Box(
        modifier = Modifier
            .offset { IntOffset(offsetX.roundToInt(), 0) }
            .zIndex(if (isDragging) 1f else 0f)
            .width(clipWidth)
            .height(70.dp)
            .background(getClipColor(clip.mediaType))
            .padding(4.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { isDragging = true },
                    onDragEnd = {
                        isDragging = false
                        // Simplified drag logic for swapping neighbors
                        val threshold = 100f
                        if (offsetX > threshold) {
                            onMove(index, index + 1)
                        } else if (offsetX < -threshold) {
                             onMove(index, index - 1)
                        }
                        offsetX = 0f
                    },
                    onDragCancel = {
                        isDragging = false
                        offsetX = 0f
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                    }
                )
            }
            .clickable { /* Select clip */ }
    ) {
        Column {
            Text(
                text = if (clip.mediaType == MediaType.TEXT) clip.text ?: "Text" else "Clip $index",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
            Text(
                text = "${durationSec}s",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }

        // Close button
         Text(
            text = "x",
            color = Color.Red,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .clickable { onRemove() }
                .padding(4.dp)
        )
    }
}

fun getClipColor(type: MediaType): Color {
    return when (type) {
        MediaType.VIDEO -> Color(0xFF3F51B5)
        MediaType.AUDIO -> Color(0xFF4CAF50)
        MediaType.TEXT -> Color(0xFFFF9800)
    }
}
