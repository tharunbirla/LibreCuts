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
    val durationMs: Long,
    val startOffsetMs: Long = 0,
    val text: String? = null,
    // Store thumbnail path or bitmap cache key if needed
    val thumbnailPath: String? = null
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
