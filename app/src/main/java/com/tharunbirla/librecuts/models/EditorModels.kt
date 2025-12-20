package com.tharunbirla.librecuts.models

import android.net.Uri

data class ProjectState(
    val videoTrack: MutableList<Clip> = mutableListOf(),
    val audioTracks: MutableList<AudioTrack> = mutableListOf(),
    val textTracks: MutableList<TextTrack> = mutableListOf()
)

data class Clip(
    val id: String,
    val sourceUri: Uri,
    var startTimeMs: Long = 0, // Start time in the source video
    var endTimeMs: Long,       // End time in the source video
    var cropRect: CropRect? = null // Null means no crop (full size)
)

data class AudioTrack(
    val id: String,
    val sourceUri: Uri,
    var startTimeMs: Long = 0,
    var durationMs: Long
)

data class TextTrack(
    val id: String,
    var text: String,
    var fontSize: Int,
    var position: String, // FFmpeg position format e.g. "x=(w-text_w)/2:y=(h-text_h)/2"
    var startTimeMs: Long = 0,
    var durationMs: Long
)

data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val aspectRatio: String // "16:9", "1:1", etc.
)
