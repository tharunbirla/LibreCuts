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
 * FONT NOTE:
 * drawtext requires fontfile= pointing to a TTF/OTF file on disk.
 * font= does NOT exist in FFmpeg's drawtext filter — it always causes:
 *   "Error applying option 'font' to filter 'drawtext': Option not found"
 *
 * Usage:
 *   1. Place TTF at app/src/main/assets/fonts/Roboto-Regular.ttf
 *   2. Call copyFontToCache() once in Activity.onCreate
 *   3. Pass the returned path as fontFilePath to all text render methods
 */
class FFmpegRenderEngine(private val context: Context) {

    private val activeSessions = mutableListOf<FFmpegSession>()
    private val TAG = "FFmpegRenderEngine"

    init {
        // System fonts as a last-resort fallback — not reliable on all Android devices.
        // Always supply an explicit fontFilePath for guaranteed text rendering.
        FFmpegKitConfig.setFontDirectory(context, "/system/fonts", null)
    }

    // ── Result type ───────────────────────────────────────────────────────────

    sealed class RenderResult {
        data class Success(val outputPath: String, val session: FFmpegSession) : RenderResult()
        data class Failure(val error: String, val session: FFmpegSession? = null) : RenderResult()
        object Cancelled : RenderResult()
    }

    // ── Font helper ───────────────────────────────────────────────────────────

    /**
     * Copies a font from assets to cacheDir so FFmpeg can access it as a plain path.
     *
     * @param assetPath  e.g. "fonts/Roboto-Regular.ttf"
     * @return           Absolute path to the cached file, or null on failure.
     */
    fun copyFontToCache(assetPath: String = "fonts/Roboto-Regular.ttf"): String? {
        return try {
            val fileName = assetPath.substringAfterLast('/')
            val fontFile = File(context.cacheDir, fileName)
            if (!fontFile.exists()) {
                context.assets.open(assetPath).use { input ->
                    fontFile.outputStream().use { input.copyTo(it) }
                }
                Log.d(TAG, "Font copied to cache: ${fontFile.absolutePath}")
            } else {
                Log.d(TAG, "Font already in cache: ${fontFile.absolutePath}")
            }
            fontFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy font to cache: ${e.message}", e)
            null
        }
    }

    // ── Core execution ────────────────────────────────────────────────────────

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
                    val failLog = session.failStackTrace
                        ?: session.allLogsAsString
                        ?: "Unknown error"
                    Log.e(TAG, "FFmpeg error: $failLog")
                    RenderResult.Failure(error = failLog, session = session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                RenderResult.Failure(error = e.message ?: "Unknown exception")
            }
        }
    }

    // ── Session management ────────────────────────────────────────────────────

    suspend fun cancelAllSessions() {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel()
            activeSessions.clear()
            Log.d(TAG, "Cancelled all FFmpeg sessions")
        }
    }

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

    // ── Preview helpers ───────────────────────────────────────────────────────

    suspend fun renderTrimPreview(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

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
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
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

    // ── Full-quality operations ───────────────────────────────────────────────

    /** Export the final video using the consolidated command from the ViewModel. */
    suspend fun exportFinal(ffmpegCommand: String): RenderResult = executeCommand(ffmpegCommand)

    /**
     * Merge videos using the FFmpeg concat demuxer.
     * FIX: Both listFilePath and outputFilePath are quoted.
     */
    suspend fun mergeVideos(
        listFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun trimVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -c copy \"$outputFilePath\""
        return executeCommand(command)
    }

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
     * @param fontFilePath  Absolute path to a TTF file. Use [copyFontToCache] to get this.
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

    suspend fun muteAudio(sourceFilePath: String, outputFilePath: String): RenderResult {
        val command = "-i \"$sourceFilePath\" -an -c:v copy \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun extractFrame(
        sourceFilePath: String,
        timeMs: Long,
        outputImagePath: String
    ): RenderResult {
        val timeSecs = timeMs / 1000.0
        val command = "-ss $timeSecs -i \"$sourceFilePath\" -vframes 1 \"$outputImagePath\""
        return executeCommand(command)
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    fun cleanup() {
        activeSessions.clear()
        Log.d(TAG, "FFmpegRenderEngine cleaned up")
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Build a drawtext filter string.
     * Uses fontfile= (not the non-existent font= option).
     */
    private fun buildDrawtextFilter(
        text: String,
        fontSize: Int,
        positionParam: String,
        fontFilePath: String?
    ): String {
        val escapedText = text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")

        val fontPart = if (!fontFilePath.isNullOrBlank()) {
            val escapedFontPath = fontFilePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFontPath':"
        } else {
            Log.w(TAG, "No fontFilePath — text may not render. Call copyFontToCache() first.")
            ""
        }

        return "drawtext=${fontPart}text='$escapedText':fontcolor=white:fontsize=$fontSize:$positionParam"
    }

    private fun buildCropFilter(aspectRatio: String): String? = when (aspectRatio) {
        "16:9" -> "crop=iw:iw*9/16"
        "9:16" -> "crop=ih*9/16:ih"
        "1:1"  -> "crop=min(iw\\,ih):min(iw\\,ih)"
        else   -> null
    }

    /**
     * Extract the output file path from an FFmpeg command.
     *
     * FIX: Handles both quoted paths (standard) and unquoted paths (legacy merge commands).
     * Previously only matched quoted strings, which caused truncated paths when the output
     * was accidentally left unquoted — producing errors like:
     *   "Unable to find a suitable output format for '/data/user/0/com.tharunb'"
     */
    private fun extractOutputPath(command: String): String {
        // Try quoted path first (preferred — all new commands use this)
        val quotedRegex = """"([^"]*)"\s*$""".toRegex()
        quotedRegex.find(command)?.groupValues?.get(1)?.let { return it }

        // Fallback: last whitespace-delimited token (for unquoted legacy commands)
        return command.trimEnd().split("\\s+".toRegex()).lastOrNull() ?: ""
    }
}