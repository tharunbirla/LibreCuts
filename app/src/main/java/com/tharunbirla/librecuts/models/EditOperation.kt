package com.tharunbirla.librecuts.models

import android.net.Uri
import java.io.Serializable

/**
 * Sealed class representing all possible edit operations that can be applied to a video.
 * Each operation is immutable and stores only the necessary parameters to render the effect.
 * Operations are applied in order during the final export process.
 */
sealed class EditOperation : Serializable {
    
    /**
     * Trim operation: Cuts the video from startMs to endMs.
     * Uses FFmpeg's -ss and -to flags with copy codec for fast processing.
     */
    data class Trim(
        val startMs: Long,
        val endMs: Long,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(startMs >= 0) { "Start time cannot be negative" }
            require(endMs > startMs) { "End time must be greater than start time" }
        }
    }
    
    /**
     * Crop operation: Crops video to a specified aspect ratio.
     * Supported aspects: "16:9", "9:16", "1:1"
     * Uses FFmpeg's crop filter with video re-encoding.
     */
    data class Crop(
        val aspectRatio: String,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(aspectRatio in listOf("16:9", "9:16", "1:1")) { 
                "Unsupported aspect ratio: $aspectRatio" 
            }
        }
    }
    
    /**
     * Text overlay operation: Adds customizable text to the video at specified position and size.
     * Supports 7 predefined positions (Bottom Right, Top Right, Top Left, Bottom Left, Center Bottom, Center Top, Center Align)
     * Uses FFmpeg's drawtext filter.
     */
    data class AddText(
        val text: String,
        val fontSize: Int,
        val position: TextPosition,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(text.isNotEmpty()) { "Text cannot be empty" }
            require(fontSize > 0) { "Font size must be positive" }
        }
    }
    
    /**
     * Merge operation: Concatenates multiple videos with the current video.
     * Uses FFmpeg's concat demuxer for fast concatenation.
     */
    data class Merge(
        val videoUris: List<Uri>,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(videoUris.isNotEmpty()) { "Must provide at least one video to merge" }
        }
    }
    
    /**
     * Mute audio operation: Removes or mutes the audio track.
     * Uses FFmpeg's -an flag.
     */
    data class MuteAudio(
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Add background audio operation: Overlays an audio file over the video.
     * If removeOriginalAudio is true, the original audio is removed.
     * Uses FFmpeg's -i and audio filters.
     */
    data class AddBackgroundAudio(
        val audioUri: Uri,
        val removeOriginalAudio: Boolean = false,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
}

/**
 * Enum for text positioning options in video overlays.
 * Maps to FFmpeg drawtext position parameters.
 */
enum class TextPosition(val ffmpegParam: String) : Serializable {
    BOTTOM_RIGHT("x=w-tw:y=h-th"),
    TOP_RIGHT("x=w-tw:y=0"),
    TOP_LEFT("x=0:y=0"),
    BOTTOM_LEFT("x=0:y=h-th"),
    CENTER_BOTTOM("x=(w-text_w)/2:y=h-th"),
    CENTER_TOP("x=(w-text_w)/2:y=0"),
    CENTER("x=(w-text_w)/2:y=(h-text_h)/2");
    
    companion object {
        fun fromLabel(label: String): TextPosition = when (label) {
            "Bottom Right" -> BOTTOM_RIGHT
            "Top Right" -> TOP_RIGHT
            "Top Left" -> TOP_LEFT
            "Bottom Left" -> BOTTOM_LEFT
            "Center Bottom" -> CENTER_BOTTOM
            "Center Top" -> CENTER_TOP
            "Center Align" -> CENTER
            else -> CENTER
        }
        
        fun labels() = listOf(
            "Bottom Right",
            "Top Right",
            "Top Left",
            "Bottom Left",
            "Center Bottom",
            "Center Top",
            "Center Align"
        )
    }
}
