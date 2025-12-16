package com.tharunbirla.librecuts.utils

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.tharunbirla.librecuts.models.ProjectState
import java.util.Locale

object FFmpegCommandGenerator {

    fun generateCommand(state: ProjectState, context: Context): String {
        // -y to overwrite output
        val cmdBuilder = StringBuilder("-y ")
        val filterComplex = StringBuilder()

        // Inputs
        // We need to map each Clip to an input index [0:v], [1:v] etc.
        val clipInputIndices = mutableListOf<String>()
        var inputIndex = 0

        state.videoTrack.forEach { clip ->
            val path = getFilePathFromUri(context, clip.sourceUri)
            cmdBuilder.append("-i \"$path\" ")

            // Generate filter chain for THIS clip
            // [0:v] trim=start:end, setpts=PTS-STARTPTS, crop=... [v0];

            val clipLabel = "v$inputIndex"
            val audioLabel = "a$inputIndex"

            // Video Filter Chain
            var vFilter = "[$inputIndex:v]"

            // Trim
            // Note: clipping in filter is `trim=start_pts=XX:end_pts=YY` or `trim=start=X:end=Y`.
            // But we use input seeking usually for speed?
            // If we use filter, we must use setpts=PTS-STARTPTS.
            // Using -ss before -i is faster but less accurate for non-keyframes without re-encoding?
            // Since we are re-encoding (filter complex), filter trim is frame accurate.

            // start/end in SECONDS
            val startSec = clip.startTimeMs / 1000.0
            val endSec = clip.endTimeMs / 1000.0

            vFilter += "trim=${String.format(Locale.US, "%.3f", startSec)}:${String.format(Locale.US, "%.3f", endSec)},setpts=PTS-STARTPTS"

            // Crop
            clip.cropRect?.let { crop ->
                // "16:9" -> crop=iw:iw*9/16:0:(ih-oh)/2 (Center crop example)
                // The existing logic used -vf "crop=iw:iw*9/16".
                // We need to replicate that logic or do it better.
                // Assuming Center Crop logic for aspect ratios.

                // We don't know the exact W/H here without metadata.
                // But FFmpeg supports expressions 'iw' 'ih'.

                val cropFilter = when(crop.aspectRatio) {
                    "16:9" -> "crop=w=iw:h=iw*9/16:x=(iw-ow)/2:y=(ih-oh)/2"
                    "9:16" -> "crop=w=ih*9/16:h=ih:x=(iw-ow)/2:y=(ih-oh)/2"
                    "1:1" -> "crop=w=min(iw\\,ih):h=min(iw\\,ih):x=(iw-ow)/2:y=(ih-oh)/2"
                    else -> ""
                }

                if (cropFilter.isNotEmpty()) {
                    vFilter += ",$cropFilter"
                }
            }

            // Scale logic
            // We use a simplified strategy: If the clip crop is 16:9, output 1280x720. If 9:16 or other, output 720x1280.
            // This is a heuristic to handle the most common cases.
            val outputW: Int
            val outputH: Int
            if (clip.cropRect?.aspectRatio == "16:9") {
                 outputW = 1280
                 outputH = 720
            } else {
                 outputW = 720
                 outputH = 1280
            }

            vFilter += ",scale=$outputW:$outputH:force_original_aspect_ratio=decrease,pad=$outputW:$outputH:(ow-iw)/2:(oh-ih)/2"

            vFilter += "[$clipLabel];"

            // Audio Filter Chain
            var aFilter = "[$inputIndex:a]"
            aFilter += "atrim=${String.format(Locale.US, "%.3f", startSec)}:${String.format(Locale.US, "%.3f", endSec)},asetpts=PTS-STARTPTS[$audioLabel];"

            filterComplex.append(vFilter).append(aFilter)

            clipInputIndices.add(clipLabel)
            clipInputIndices.add(audioLabel) // We need to track audio streams too

            inputIndex++
        }

        // Concat
        // [v0][a0][v1][a1]... concat=n=2:v=1:a=1 [v_concat][a_concat]

        state.videoTrack.indices.forEach { i ->
            filterComplex.append("[v$i][a$i]")
        }
        filterComplex.append("concat=n=${state.videoTrack.size}:v=1:a=1[v_concat][a_concat];")

        var lastVLabel = "v_concat"

        // Text Overlays
        // [v_concat] drawtext=... [v_text1]; [v_text1] drawtext=... [v_final]
        state.textTracks.forEachIndexed { i, textTrack ->
             val nextLabel = "v_text$i"
             // Escape text
             val safeText = textTrack.text.replace(":", "\\:").replace("'", "'")

             // drawtext=text='...':x=...:y=...:enable='between(t,START,END)'
             // We need start/end time. State has duration?
             // ProjectState textTrack has startTimeMs and durationMs?
             // In ViewModel I set startTimeMs=0. So it appears at start?
             // For now, let's assume it shows for the whole duration if not specified, or just start=0.

             // "enable='between(t,0,10)'"
             // Let's assume text is always visible for now based on ViewModel (0 to duration).

             filterComplex.append("[$lastVLabel]drawtext=text='$safeText':fontcolor=white:fontsize=${textTrack.fontSize}:${textTrack.position}[$nextLabel];")
             lastVLabel = nextLabel
        }

        // Add Audio Overlays? (Placeholder)

        // Final map
        cmdBuilder.append("-filter_complex \"$filterComplex\" ")
        cmdBuilder.append("-map \"[$lastVLabel]\" -map \"[a_concat]\" ")

        // Output format settings (fast encode)
        cmdBuilder.append("-c:v libx264 -preset ultrafast -c:a aac ")

        return cmdBuilder.toString()
    }

    private fun getFilePathFromUri(context: Context, uri: Uri): String {
        var filePath = ""
        if ("content" == uri.scheme) {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (idx != -1) filePath = it.getString(idx)
                }
            }
        } else if ("file" == uri.scheme) {
            filePath = uri.path ?: ""
        }
        return filePath
    }
}
