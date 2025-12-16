package com.tharunbirla.librecuts.models

import android.net.Uri
import java.util.UUID

enum class MediaType {
    VIDEO,
    AUDIO,
    TEXT
}

data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val mediaType: MediaType,
    // Duration of the clip in the timeline
    val durationMs: Long,
    // Start time of the clip in the source file (for trimming)
    val startOffsetMs: Long = 0,
    // For text clips
    val text: String? = null,
    // For text positioning/styling
    val x: Float = 0f,
    val y: Float = 0f,
    val fontSize: Int = 16
)

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val trackType: MediaType,
    val clips: List<Clip> = emptyList()
)

data class ProjectState(
    val tracks: List<Track> = emptyList(),
    val selectedClipId: String? = null,
    val currentTimeMs: Long = 0,
    val totalDurationMs: Long = 0
)

// Helper extension to calculate start times for clips in a track (Magnetic)
fun Track.getClipStartTimes(): Map<String, Long> {
    var currentTime = 0L
    val map = mutableMapOf<String, Long>()
    for (clip in clips) {
        map[clip.id] = currentTime
        currentTime += clip.durationMs
    }
    return map
}
