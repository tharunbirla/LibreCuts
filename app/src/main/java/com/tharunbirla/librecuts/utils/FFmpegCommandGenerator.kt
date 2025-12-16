package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.MediaType
import com.tharunbirla.librecuts.models.ProjectState
import java.io.File

object FFmpegCommandGenerator {

    private const val TAG = "FFmpegGen"

    fun generate(context: Context, state: ProjectState, outputPath: String): String {
        // Collect all unique URIs (excluding Text which has no URI)
        val videoTrack = state.tracks.find { it.trackType == MediaType.VIDEO }
        val audioTrack = state.tracks.find { it.trackType == MediaType.AUDIO }

        val uniqueUris = mutableListOf<Uri>()

        videoTrack?.clips?.forEach { if (it.uri != Uri.EMPTY) uniqueUris.add(it.uri) }
        audioTrack?.clips?.forEach { if (it.uri != Uri.EMPTY) uniqueUris.add(it.uri) }

        val distinctUris = uniqueUris.distinct()
        val uriMap = distinctUris.mapIndexed { index, uri -> uri to index }.toMap()

        val cmd = StringBuilder()

        // 1. Inputs
        distinctUris.forEach { uri ->
            val path = getFilePath(context, uri)
            cmd.append("-i \"$path\" ")
        }

        // 2. Filter Complex
        cmd.append("-filter_complex \"")

        // --- Video Track Processing ---
        val videoClips = videoTrack?.clips ?: emptyList()
        val videoSegments = mutableListOf<String>()
        val audioSegmentsFromVideo = mutableListOf<String>()

        videoClips.forEachIndexed { index, clip ->
            val inputIdx = uriMap[clip.uri] ?: return@forEachIndexed
            val startSec = clip.startOffsetMs / 1000.0
            val durationSec = clip.durationMs / 1000.0
            val endSec = startSec + durationSec

            // Video Trim & SetPTS
            val vLabel = "v$index"
            // Apply Crop if exists
            val cropFilter = if (clip.crop != null) {
                when (clip.crop.aspectRatio) {
                    "16:9" -> ",crop=iw:iw*9/16:(iw-ow)/2:(ih-oh)/2" // Center crop to 16:9
                    "9:16" -> ",crop=ih*9/16:ih:(iw-ow)/2:(ih-oh)/2" // Center crop to 9:16
                    "1:1" -> ",crop=min(iw,ih):min(iw,ih):(iw-ow)/2:(ih-oh)/2" // Center crop to Square
                    else -> ""
                }
            } else ""

            cmd.append("[$inputIdx:v]trim=$startSec:$endSec,setpts=PTS-STARTPTS$cropFilter[$vLabel];")
            videoSegments.add("[$vLabel]")

            // Audio Trim & SetPTS (from video file)
            // We need to check if video file has audio stream. safely assume yes for MVP or complex probe.
            val aLabel = "a$index"
            cmd.append("[$inputIdx:a]atrim=$startSec:$endSec,asetpts=PTS-STARTPTS[$aLabel];")
            audioSegmentsFromVideo.add("[$aLabel]")
        }

        // Concatenate Video Track
        val concatVLabel = "concat_v"
        val concatALabel = "concat_a" // Audio from video track

        if (videoSegments.isNotEmpty()) {
            videoSegments.forEach { cmd.append(it) }
            audioSegmentsFromVideo.forEach { cmd.append(it) }
            cmd.append("concat=n=${videoSegments.size}:v=1:a=1[$concatVLabel][$concatALabel];")
        }

        // --- Audio Track Processing (Independent Audio) ---
        val audioTrackClips = audioTrack?.clips ?: emptyList()
        val extAudioSegments = mutableListOf<String>()

        audioTrackClips.forEachIndexed { index, clip ->
             val inputIdx = uriMap[clip.uri] ?: return@forEachIndexed
             val startSec = clip.startOffsetMs / 1000.0
             val durationSec = clip.durationMs / 1000.0
             val endSec = startSec + durationSec

             val label = "ext_a$index"
             cmd.append("[$inputIdx:a]atrim=$startSec:$endSec,asetpts=PTS-STARTPTS[$label];")
             extAudioSegments.add("[$label]")
        }

        // Concatenate External Audio Track
        var finalMixAudio = "[$concatALabel]"
        if (extAudioSegments.isNotEmpty()) {
             val extConcatLabel = "concat_ext_a"
             extAudioSegments.forEach { cmd.append(it) }
             cmd.append("concat=n=${extAudioSegments.size}:v=0:a=1[$extConcatLabel];")

             // Mix Video-Audio and Ext-Audio
             val mixedAudioLabel = "mixed_a"
             // Amix: duration=longest to keep video audio? or shortest?
             // Ideally we want to keep playing until video ends.
             // "first" input is video-audio.
             cmd.append("[$concatALabel][$extConcatLabel]amix=inputs=2:duration=first[$mixedAudioLabel];")
             finalMixAudio = "[$mixedAudioLabel]"
        }

        // --- Text Overlay ---
        // Drawtext needs to be applied to the Final Concatenated Video
        // We iterate over text clips and chain drawtext filters.
        val textTrack = state.tracks.find { it.trackType == MediaType.TEXT }
        var lastVideoLabel = "[$concatVLabel]"

        // Calculate accumulated start times for text
        var accumulatedTimeMs = 0L
        textTrack?.clips?.forEachIndexed { index, clip ->
            if (clip.text != null) {
                val nextLabel = "text$index"
                val startTimeSec = accumulatedTimeMs / 1000.0
                val endTimeSec = (accumulatedTimeMs + clip.durationMs) / 1000.0

                // Escape text for ffmpeg
                val escapedText = clip.text.replace(":", "\\:").replace("'", "'")

                cmd.append("$lastVideoLabel")
                cmd.append("drawtext=text='$escapedText':fontcolor=white:fontsize=24:x=(w-text_w)/2:y=(h-text_h)/2:enable='between(t,$startTimeSec,$endTimeSec)'")
                cmd.append("[$nextLabel];")

                lastVideoLabel = "[$nextLabel]"
            }
            accumulatedTimeMs += clip.durationMs
        }

        // Remove last semicolon if exists
        if (cmd.endsWith(";")) {
            cmd.setLength(cmd.length - 1)
        }

        cmd.append("\" -map \"$lastVideoLabel\" -map \"$finalMixAudio\" -c:v libx264 -c:a aac \"$outputPath\"")

        return cmd.toString()
    }

    private fun getFilePath(context: Context, uri: Uri): String {
        // Basic file path resolution or copy to cache if content uri
        // For MVP assuming file uri or we need to implement a copy helper
        // Since VideoEditingActivity had `getFilePathFromUri`, we should probably reuse logic
        // or just use `FFmpegKitConfig.getSafParameter` if available, or copy to temp.

        // Implementation similar to Activity's logic
        if (uri.scheme == "file") return uri.path ?: ""
        // Real app needs robust content resolver.
        // Returning uri string for now, FFmpegKit often handles content:// if permissions persist
        // But usually we need `FFmpegKitConfig.getSafParameterForRead(context, uri)`
        return com.arthenica.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(context, uri)
    }
}
