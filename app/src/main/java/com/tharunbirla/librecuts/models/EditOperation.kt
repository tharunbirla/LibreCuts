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
     * Speed operation for the main video.
     */
    data class SpeedMain(
        val speed: Float,
        val proxyUri: Uri? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Reverse operation for the main video.
     */
    data class ReverseMain(
        val isReversed: Boolean,
        val proxyUri: Uri? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()
    
    /**
     * Crop operation: Crops video to a specified aspect ratio.
     * Supported aspects: "16:9", "9:16", "1:1"
     * Uses FFmpeg's crop filter with video re-encoding.
     */
    data class Crop(
        val aspectRatio: String,
        val xFraction: Float = 0f,
        val yFraction: Float = 0f,
        val wFraction: Float = 1f,
        val hFraction: Float = 1f,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(aspectRatio in listOf("16:9", "9:16", "1:1", "Custom")) { 
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
        val relativeX: Float? = null,
        val relativeY: Float? = null,
        val color: String = "#FFFFFF",
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(text.isNotEmpty()) { "Text cannot be empty" }
            require(fontSize > 0) { "Font size must be positive" }
            relativeX?.let { require(it in 0f..1f) { "relativeX must be in 0.0..1.0" } }
            relativeY?.let { require(it in 0f..1f) { "relativeY must be in 0.0..1.0" } }
        }

        /** True when this text was placed via drag-and-drop (WYSIWYG coordinates). */
        fun hasCustomPosition(): Boolean = relativeX != null && relativeY != null
    }
    
    data class MergeItem(
        val uri: Uri,
        val durationMs: Long,
        val trimStartMs: Long = 0L,
        val trimEndMs: Long = durationMs,
        val speed: Float = 1.0f,
        val proxyUri: Uri? = null,
        val isReversed: Boolean = false
    ) : Serializable {
        val trimmedDurationMs: Long
            get() = ((trimEndMs - trimStartMs) / speed).toLong()
    }

    /**
     * Merge operation: Concatenates multiple videos with the current video.
     * Uses FFmpeg's concat demuxer for fast concatenation.
     */
    data class Merge(
        val items: List<MergeItem>,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(items.isNotEmpty()) { "Must provide at least one video to merge" }
        }

        // Backward compatibility property
        val videoUris: List<Uri> get() = items.map { it.uri }
    }
    
    /**
     * Mute audio operation: Removes or mutes the audio track.
     * Uses FFmpeg's -an flag.
     */
    data class MuteAudio(
        val id: String = System.nanoTime().toString()
    ) : EditOperation()


    /**
     * Transition operation: Adds a transition effect between two consecutive videos.
     * index: The index of the first video in the sequence. (0 = base video, 1 = merge item 0, etc.)
     */
    data class Transition(
        val index: Int,
        val type: String,
        val durationMs: Long = 1000L,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()

    /**
     * Mute clip operation: Mutes/unmutes a specific clip index.
     */
    data class MuteClip(
        val index: Int,
        val isMuted: Boolean,
        val id: String = System.nanoTime().toString()
    ) : EditOperation()

    /**
     * Color filter (LUT) operation: Applies a preset color grade filter to a specific clip index.
     */
    data class ColorFilter(
        val index: Int,
        val filterName: String,
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
        val volume: Float = 1.0f,
        val internalStartMs: Long = 0L,
        val internalEndMs: Long = -1L,
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val originalDurationMs: Long = 0L,
        val extractedFromSegmentIndex: Int? = null,
        val beats: List<Long> = emptyList(),
        val ducking: Boolean = false,
        val fadeInDurationMs: Long = 0L,
        val fadeOutDurationMs: Long = 0L,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        init {
            require(volume in 0f..2f) { "Volume must be in 0.0..2.0" }
            require(internalStartMs >= 0) { "Internal start time cannot be negative" }
        }
    }

    /**
     * Image overlay operation: Adds an image to the video at specified position, size, and rotation angle.
     * Uses FFmpeg's overlay filter.
     */
    data class AddImageOverlay(
        val imageUri: Uri,
        val relativeX: Float,
        val relativeY: Float,
        val relativeWidth: Float,
        val relativeHeight: Float,
        val rotationAngle: Float,
        val startTimeMs: Long? = null,
        val endTimeMs: Long? = null,
        val id: String = System.nanoTime().toString(),
        val fileDurationMs: Long? = null,
        val isLooping: Boolean = true,
        val chromaKeyColor: String? = null,
        val chromaKeySimilarity: Float = 0.1f
    ) : EditOperation()

    data class AddSubtitles(
        val subtitlesUri: Uri,
        val srtContent: String,
        val fileName: String,
        val cues: List<SubtitleCue>,
        val color: String = "#FFFFFF",
        val backgroundColor: String = "none",
        val fontSize: Int = 22,
        val position: TextPosition = TextPosition.BOTTOM_CENTER,
        val relativeX: Float? = null,
        val relativeY: Float? = null,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        fun hasCustomPosition(): Boolean = relativeX != null && relativeY != null
    }

    /**
     * Adjust operation: Adjusts video properties for a specific clip index.
     * All properties range from -100 to 100, default is 0.
     */
    data class Adjust(
        val index: Int,
        val brightness: Int = 0,
        val contrast: Int = 0,
        val warmth: Int = 0,
        val shadow: Int = 0,
        val highlights: Int = 0,
        val saturation: Int = 0,
        val exposure: Int = 0,
        val sharpen: Int = 0,
        val vignette: Int = 0,
        val id: String = System.nanoTime().toString()
    ) : EditOperation() {
        fun isDefault(): Boolean {
            return brightness == 0 &&
                   contrast == 0 &&
                   warmth == 0 &&
                   shadow == 0 &&
                   highlights == 0 &&
                   saturation == 0 &&
                   exposure == 0 &&
                   sharpen == 0 &&
                   vignette == 0
        }
    }
}

data class SubtitleCue(
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String
) : java.io.Serializable


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
    CENTER("x=(w-text_w)/2:y=(h-text_h)/2"),
    TOP_CENTER("x=(w-text_w)/2:y=0"),
    CENTER_LEFT("x=0:y=(h-text_h)/2"),
    CENTER_RIGHT("x=w-tw:y=(h-text_h)/2"),
    BOTTOM_CENTER("x=(w-text_w)/2:y=h-th");
    
    companion object {
        fun fromLabel(label: String): TextPosition = when (label) {
            "Bottom Right" -> BOTTOM_RIGHT
            "Top Right" -> TOP_RIGHT
            "Top Left" -> TOP_LEFT
            "Bottom Left" -> BOTTOM_LEFT
            "Center Bottom" -> CENTER_BOTTOM
            "Center Top" -> CENTER_TOP
            "Center Align" -> CENTER
            "Top Center" -> TOP_CENTER
            "Center Left" -> CENTER_LEFT
            "Center Right" -> CENTER_RIGHT
            "Bottom Center" -> BOTTOM_CENTER
            else -> CENTER
        }
        
        fun labels() = listOf(
            "Top Left",
            "Top Center",
            "Top Right",
            "Center Left",
            "Center Align",
            "Center Right",
            "Bottom Left",
            "Bottom Center",
            "Bottom Right"
        )
    }
}
