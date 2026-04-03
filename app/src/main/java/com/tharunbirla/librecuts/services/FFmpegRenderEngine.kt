package com.tharunbirla.librecuts.services

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * FFmpegRenderEngine handles all FFmpeg operations for the video editor.
 *
 * This service decouples video processing logic from the UI layer, making it:
 * - Testable: Can mock FFmpeg operations in unit tests
 * - Reusable: Can be used from any layer of the app
 * - Maintainable: All FFmpeg logic is centralized
 * - Cancelable: Provides session management for long-running operations
 *
 * FONT NOTE:
 * The drawtext filter requires an explicit fontfile= pointing to a TTF/OTF file.
 * The font= option does NOT exist in FFmpeg's drawtext filter and will always cause:
 *   "Error applying option 'font' to filter 'drawtext': Option not found"
 *
 * To provide a font:
 *   1. Place a TTF file in app/src/main/assets/fonts/ (e.g. Roboto-Regular.ttf)
 *   2. Call copyFontToCache(context) once (e.g. in your Activity.onCreate)
 *   3. Pass the returned path as fontFilePath to all text render methods
 */
class FFmpegRenderEngine(private val context: Context) {

    private val activeSessions = mutableListOf<FFmpegSession>()
    private val TAG = "FFmpegRenderEngine"

    init {
        // Configure FFmpeg font directory — points to Android system fonts as a fallback.
        // This alone is NOT sufficient on most devices; always supply an explicit fontFilePath.
        FFmpegKitConfig.setFontDirectory(context, "/system/fonts", null)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Result type
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Represents the result of an FFmpeg operation.
     */
    sealed class RenderResult {
        data class Success(val outputPath: String, val session: FFmpegSession) : RenderResult()
        data class Failure(val error: String, val session: FFmpegSession? = null) : RenderResult()
        object Cancelled : RenderResult()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Font helper
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Copies a font file from your app's assets to the cache directory so FFmpeg
     * can access it via a plain file path.
     *
     * Usage (call once from your Activity):
     *   val fontPath = renderEngine.copyFontToCache("fonts/Roboto-Regular.ttf")
     *
     * @param assetPath  Path inside the assets folder, e.g. "fonts/Roboto-Regular.ttf"
     * @return           Absolute path to the cached font file, or null on failure.
     */
    fun copyFontToCache(assetPath: String = "fonts/Roboto-Regular.ttf"): String? {
        return try {
            val fileName = assetPath.substringAfterLast('/')
            val fontFile = File(context.cacheDir, fileName)
            if (!fontFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    fontFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Font copied to cache: ${fontFile.absolutePath}")
            }
            fontFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy font to cache: ${e.message}", e)
            null
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Core execution
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Execute an FFmpeg command and return the result.
     * Runs on IO dispatcher for non-blocking execution.
     */
    suspend fun executeCommand(command: String): RenderResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Executing FFmpeg command: $command")
                val session = FFmpegKit.execute(command)
                activeSessions.add(session)

                val returnCode = session.returnCode
                Log.d(TAG, "FFmpeg completed with return code: $returnCode")

                if (ReturnCode.isSuccess(returnCode)) {
                    RenderResult.Success(
                        outputPath = extractOutputPath(command),
                        session = session
                    )
                } else {
                    val failLog = session.failStackTrace ?: session.allLogsAsString ?: "Unknown error"
                    Log.e(TAG, "FFmpeg error: $failLog")
                    RenderResult.Failure(error = failLog, session = session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                RenderResult.Failure(error = e.message ?: "Unknown exception")
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session management
    // ──────────────────────────────────────────────────────────────────────────

    /** Cancel all active FFmpeg sessions. */
    suspend fun cancelAllSessions() {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel()
            activeSessions.clear()
            Log.d(TAG, "Cancelled all FFmpeg sessions")
        }
    }

    /** Cancel a specific session by ID. */
    suspend fun cancelSession(sessionId: Long) {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel(sessionId)
            activeSessions.removeIf { it.sessionId == sessionId }
            Log.d(TAG, "Cancelled session: $sessionId")
        }
    }

    fun hasActiveSessions(): Boolean = activeSessions.isNotEmpty()
    fun getActiveSessionCount(): Int = activeSessions.size
    fun getLatestSession(): FFmpegSession? = activeSessions.lastOrNull()

    // ──────────────────────────────────────────────────────────────────────────
    // Preview helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Render a low-quality trim preview for fast feedback.
     *
     * @param sourceFilePath  Path to the source video.
     * @param startMs         Trim start in milliseconds.
     * @param endMs           Trim end in milliseconds.
     * @param outputFilePath  Path for the preview output.
     */
    suspend fun renderTrimPreview(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val endSecs = endMs / 1000.0
        val durationSecs = endSecs - startSecs
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Render a preview with the specified crop aspect ratio applied.
     *
     * @param sourceFilePath  Path to the source video.
     * @param aspectRatio     "16:9", "9:16", or "1:1".
     * @param outputFilePath  Path for the preview output.
     */
    suspend fun renderCropPreview(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Render a preview with a text overlay.
     *
     * @param sourceFilePath  Path to the source video.
     * @param text            Text to overlay.
     * @param fontSize        Font size in pixels.
     * @param positionParam   FFmpeg drawtext position string,
     *                        e.g. "x=(w-text_w)/2:y=(h-text_h)/2".
     * @param outputFilePath  Path for the preview output.
     * @param fontFilePath    Absolute path to a TTF/OTF font file.
     *                        Use [copyFontToCache] to obtain this path.
     *                        If null, text may not render on Android.
     */
    suspend fun renderTextPreview(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Full-quality operations
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Export/render the final video with all operations applied.
     * The consolidated command is built by [VideoEditingViewModel.buildConsolidatedFFmpegCommand].
     */
    suspend fun exportFinal(ffmpegCommand: String): RenderResult {
        return executeCommand(ffmpegCommand)
    }

    /**
     * Merge multiple videos using the FFmpeg concat demuxer.
     *
     * @param listFilePath    Path to a file containing the concat list
     *                        (created by [VideoEditingViewModel.buildMergeCommand]).
     * @param outputFilePath  Path for the merged output.
     */
    suspend fun mergeVideos(
        listFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-f concat -safe 0 -i $listFilePath -c:v copy -c:a copy $outputFilePath"
        return executeCommand(command)
    }

    /**
     * Trim a video segment.
     *
     * @param sourceFilePath  Path to the source video.
     * @param startMs         Trim start in milliseconds.
     * @param endMs           Trim end in milliseconds.
     * @param outputFilePath  Path for the trimmed output.
     */
    suspend fun trimVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val endSecs = endMs / 1000.0
        val durationSecs = endSecs - startSecs
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Crop a video to a specific aspect ratio.
     *
     * @param sourceFilePath  Path to the source video.
     * @param aspectRatio     "16:9", "9:16", or "1:1".
     * @param outputFilePath  Path for the cropped output.
     */
    suspend fun cropVideo(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = buildCropFilter(aspectRatio)
            ?: return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Add a text overlay to a video.
     *
     * @param sourceFilePath  Path to the source video.
     * @param text            Text to render.
     * @param fontSize        Font size in pixels.
     * @param positionParam   FFmpeg drawtext position string.
     * @param outputFilePath  Path for the output with text.
     * @param fontFilePath    Absolute path to a TTF/OTF font file.
     *                        Use [copyFontToCache] to obtain this path.
     */
    suspend fun addTextOverlay(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): RenderResult {
        val textFilter = buildDrawtextFilter(text, fontSize, positionParam, fontFilePath)
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Add background audio to a video.
     *
     * @param sourceFilePath  Path to the source video.
     * @param audioFilePath   Path to the audio file.
     * @param outputFilePath  Path for the output.
     * @param replaceAudio    If true, replaces the original audio; if false, mixes them.
     */
    suspend fun addBackgroundAudio(
        sourceFilePath: String,
        audioFilePath: String,
        outputFilePath: String,
        replaceAudio: Boolean = false
    ): RenderResult {
        val command = if (replaceAudio) {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" -c:v copy -map 0:v:0 -map 1:a:0 \"$outputFilePath\""
        } else {
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" " +
                    "-filter_complex \"[0:a][1:a]amix=inputs=2:duration=first[a]\" " +
                    "-map 0:v -map \"[a]\" -c:v copy \"$outputFilePath\""
        }
        return executeCommand(command)
    }

    /**
     * Remove audio from a video.
     *
     * @param sourceFilePath  Path to the source video.
     * @param outputFilePath  Path for the output without audio.
     */
    suspend fun muteAudio(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-i \"$sourceFilePath\" -an -c:v copy \"$outputFilePath\""
        return executeCommand(command)
    }

    /**
     * Extract a single frame from a video at a specific time.
     *
     * @param sourceFilePath  Path to the source video.
     * @param timeMs          Time in milliseconds.
     * @param outputImagePath Path for the output image (JPEG or PNG).
     */
    suspend fun extractFrame(
        sourceFilePath: String,
        timeMs: Long,
        outputImagePath: String
    ): RenderResult {
        val timeSecs = timeMs / 1000.0
        val command = "-ss $timeSecs -i \"$sourceFilePath\" -vframes 1 \"$outputImagePath\""
        return executeCommand(command)
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cleanup
    // ──────────────────────────────────────────────────────────────────────────

    fun cleanup() {
        activeSessions.clear()
        Log.d(TAG, "FFmpegRenderEngine cleaned up")
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Build a drawtext filter string.
     *
     * KEY FIX: Uses fontfile= instead of the non-existent font= option.
     * font= will always produce: "Error applying option 'font' to filter 'drawtext': Option not found"
     */
    private fun buildDrawtextFilter(
        text: String,
        fontSize: Int,
        positionParam: String,
        fontFilePath: String?
    ): String {
        val escapedText = text
            .replace("\\", "\\\\")  // escape backslashes first
            .replace("'", "\\'")     // escape single quotes
            .replace(":", "\\:")     // escape colons (FFmpeg filter separator)

        val fontPart = if (!fontFilePath.isNullOrBlank()) {
            val escapedFontPath = fontFilePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFontPath':"
        } else {
            Log.w(TAG, "No fontFilePath provided. Text overlay may not render on Android. " +
                    "Call copyFontToCache() and pass the result as fontFilePath.")
            ""
        }

        return "drawtext=${fontPart}text='$escapedText':fontcolor=white:fontsize=$fontSize:$positionParam"
    }

    /**
     * Build the crop filter string for a given aspect ratio.
     * Returns null for unrecognised ratios.
     */
    private fun buildCropFilter(aspectRatio: String): String? = when (aspectRatio) {
        "16:9" -> "crop=iw:iw*9/16"
        "9:16" -> "crop=ih*9/16:ih"
        "1:1"  -> "crop=min(iw\\,ih):min(iw\\,ih)"
        else   -> null
    }

    /**
     * Extract the output file path from an FFmpeg command.
     * Assumes the last double-quoted token is the output path.
     */
    private fun extractOutputPath(command: String): String {
        val regex = """"([^"]*)"\s*$""".toRegex()
        return regex.find(command)?.groupValues?.get(1) ?: ""
    }
}