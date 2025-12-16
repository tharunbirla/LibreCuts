package com.tharunbirla.librecuts.models

import android.net.Uri
import java.util.UUID

enum class MediaType {
    VIDEO,
    AUDIO,
    TEXT
}

data class CropData(
    val x: Float, // Top-left x (normalized 0..1 or absolute if verified)
    val y: Float, // Top-left y
    val width: Float,
    val height: Float,
    val aspectRatio: String // "16:9", "1:1", "Free"
)

data class Clip(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val mediaType: MediaType,
    val durationMs: Long,
    val startOffsetMs: Long = 0, // Where in the source file this clip starts
    val text: String? = null,
    val thumbnailPath: String? = null,

    // New properties for editing
    val volume: Float = 1.0f,
    val speed: Float = 1.0f,
    val crop: CropData? = null
)

data class Track(
    val id: String = UUID.randomUUID().toString(),
    val trackType: MediaType,
    val clips: List<Clip> = emptyList()
)

data class ProjectState(
    val tracks: List<Track> = emptyList(),
    val totalDurationMs: Long = 0
)
