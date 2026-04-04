package com.tharunbirla.librecuts.utils

enum class ErrorCode(val code: String, val description: String) {
    FFMPEG_EXECUTION_FAILED("LC-101", "FFmpeg failed to process the video."),
    FONT_MISSING("LC-102", "Font file could not be loaded from assets."),
    FILE_NOT_FOUND("LC-201", "Source video file is missing or inaccessible."),
    GALLERY_SAVE_FAILED("LC-202", "Could not save the processed video to the gallery."),
    OUT_OF_MEMORY("LC-301", "The app ran out of memory while processing frames."),
    UNEXPECTED_CRASH("LC-500", "An unexpected application error occurred.")
}