package com.tharunbirla.librecuts.data.model

import android.net.Uri

data class Clip(
    val id: String,
    val uri: Uri,
    val filePath: String,
    val durationMs: Long,
    var startTimeMs: Long = 0L,
    var trimStartMs: Long = 0L,
    var trimEndMs: Long = durationMs
) {
    val playbackDuration: Long
        get() = trimEndMs - trimStartMs
}
