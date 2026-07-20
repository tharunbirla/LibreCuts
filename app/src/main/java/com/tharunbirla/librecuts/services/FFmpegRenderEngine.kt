package com.tharunbirla.librecuts.services

import android.content.Context
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
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
        // Probe multiple vendor font directories since OEMs differ.
        val fontDir = listOf(
            "/system/fonts",
            "/system/font",
            "/data/fonts",
            "/product/fonts"
        ).firstOrNull { File(it).isDirectory } ?: "/system/fonts"

        try {
            FFmpegKitConfig.setFontDirectory(context, fontDir, null)
            Log.d(TAG, "Font directory set to: $fontDir")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set font directory '$fontDir': ${e.message}")
        }

        // Register a global log callback so every FFmpeg stderr line hits logcat.
        // Without this, getAllLogsAsString() often returns "" when a per-session log
        // callback is also provided (antonkarpenko fork bypasses session log storage).
        try {
            FFmpegKitConfig.enableLogCallback { log ->
                val msg = log.getMessage()?.trimEnd() ?: return@enableLogCallback
                if (msg.isEmpty()) return@enableLogCallback
                when (log.getLevel()) {
                    Level.AV_LOG_ERROR, Level.AV_LOG_FATAL, Level.AV_LOG_PANIC, Level.AV_LOG_STDERR
                        -> Log.e(TAG, "[ffmpeg] $msg")
                    Level.AV_LOG_WARNING
                        -> Log.w(TAG, "[ffmpeg] $msg")
                    else -> Log.v(TAG, "[ffmpeg] $msg")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not register FFmpegKit global log callback: ${e.message}")
        }
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
            
            val fontMap = mutableMapOf<String, String>()
            val alias = fileName.substringBeforeLast('.')
            fontMap[alias] = fileName
            FFmpegKitConfig.setFontDirectory(context, context.cacheDir.absolutePath, fontMap)
            
            alias
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

                // Use getReturnCode() — the protected field 'returnCode' is not accessible
                // as a Kotlin property (only public Java getters auto-map as properties).
                val returnCode = session.getReturnCode()
                Log.d(TAG, "FFmpeg completed with return code: $returnCode")

                if (ReturnCode.isSuccess(returnCode)) {
                    RenderResult.Success(
                        outputPath = extractOutputPath(command),
                        session = session
                    )
                } else {
                    // When FFmpeg fails:
                    // getFailStackTrace() is non-null only on Java exceptions, not FFmpeg process errors.
                    // getAllLogsAsString() returns "" (empty, not null) when log callback is active
                    // — use takeIf { isNotBlank() } so the ?: fallback actually fires.
                    val failLog = session.getFailStackTrace()?.takeIf { it.isNotBlank() }
                        ?: session.getAllLogsAsString()?.takeIf { it.isNotBlank() }
                        ?: "FFmpeg exited with code ${returnCode?.getValue()} (check logcat tag '$TAG' for [ffmpeg] lines)"
                        
                    if (command.contains("h264_mediacodec")) {
                        Log.w(TAG, "Hardware encoder h264_mediacodec failed. Falling back to software encoder libx264. Error: $failLog")
                        val fallbackCommand = command.replace("h264_mediacodec", "libx264")
                        return@withContext executeCommand(fallbackCommand)
                    }

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
    suspend fun exportFinal(
        ffmpegCommand: String,
        totalDurationSecs: Double? = null,
        onProgress: ((Int) -> Unit)? = null
    ): RenderResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Executing FFmpeg command: $ffmpegCommand")
                
                val session = suspendCancellableCoroutine<FFmpegSession> { cont ->
                    val asyncSession = FFmpegKit.executeAsync(ffmpegCommand, { completeSession ->
                        cont.resume(completeSession)
                    }, { log ->
                        // Forward every FFmpeg log line to logcat so we always see the real error.
                        // (Providing this callback can bypass session log storage in some builds,
                        //  so we must log here rather than relying on getAllLogsAsString() later.)
                        val msg = log.message?.trimEnd() ?: return@executeAsync
                        if (msg.isNotEmpty()) Log.d(TAG, "[ffmpeg] $msg")
                    }, { statistics ->
                        if (totalDurationSecs != null && totalDurationSecs > 0) {
                            // statistics.getTime() actually returns milliseconds
                            val timeMs = statistics.time
                            if (timeMs > 0) {
                                val timeSecs = timeMs.toDouble() / 1000.0
                                val progress = (timeSecs / totalDurationSecs * 100).toInt()
                                onProgress?.invoke(progress.coerceIn(0, 100))
                            }
                        }
                    })
                    activeSessions.add(asyncSession)
                    
                    cont.invokeOnCancellation {
                        FFmpegKit.cancel(asyncSession.sessionId)
                        activeSessions.remove(asyncSession)
                    }
                }
                
                activeSessions.remove(session)

                // Use getReturnCode() — see executeCommand() note above.
                val returnCode = session.getReturnCode()
                Log.d(TAG, "FFmpeg completed with return code: ${returnCode?.getValue()}")

                if (ReturnCode.isSuccess(returnCode)) {
                    RenderResult.Success(
                        outputPath = extractOutputPath(ffmpegCommand),
                        session = session
                    )
                } else {
                    val failLog = session.getFailStackTrace()?.takeIf { it.isNotBlank() }
                        ?: session.getAllLogsAsString()?.takeIf { it.isNotBlank() }
                        ?: "FFmpeg exited with code ${returnCode?.getValue()} (see logcat tag '$TAG' for [ffmpeg] lines)"
                        
                    if (ffmpegCommand.contains("h264_mediacodec")) {
                        Log.w(TAG, "Hardware encoder h264_mediacodec failed. Falling back to software encoder libx264. Error: $failLog")
                        val fallbackCommand = ffmpegCommand.replace("h264_mediacodec", "libx264")
                        return@withContext exportFinal(fallbackCommand, totalDurationSecs, onProgress)
                    }

                    Log.e(TAG, "FFmpeg error: $failLog")
                    RenderResult.Failure(error = failLog, session = session)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                RenderResult.Failure(error = e.message ?: "Unknown exception")
            }
        }
    }

    /** Extract audio from a video file using FFmpeg. */
    suspend fun extractAudio(
        sourceFilePath: String,
        outputFilePath: String
    ): RenderResult {
        // -vn disables video stream, -acodec copy copies the audio stream.
        val command = "-y -i \"$sourceFilePath\" -vn -acodec copy \"$outputFilePath\""
        return executeCommand(command)
    }

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

    suspend fun generateSpeedProxy(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        speed: Float,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        
        val safeSpeed = speed.coerceIn(0.25f, 4.0f)
        val ptsMultiplier = 1.0f / safeSpeed
        
        val audioFilter = if (safeSpeed < 0.5f) {
            "atempo=0.5,atempo=${safeSpeed * 2.0f}"
        } else if (safeSpeed > 2.0f) {
            "atempo=2.0,atempo=${safeSpeed / 2.0f}"
        } else {
            "atempo=$safeSpeed"
        }
        
        val command = "-y -ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -filter:v \"setpts=${ptsMultiplier}*PTS,format=yuv420p\" -filter:a \"$audioFilter\" \"$outputFilePath\""
        return executeCommand(command)
    }

    suspend fun reverseVideo(
        sourceFilePath: String,
        startMs: Long,
        endMs: Long,
        outputFilePath: String
    ): RenderResult {
        val startSecs = startMs / 1000.0
        val durationSecs = (endMs - startMs) / 1000.0
        val command = "-y -ss $startSecs -i \"$sourceFilePath\" -to $durationSecs -filter:v \"reverse,format=yuv420p\" -filter:a \"areverse\" -c:v libx264 -c:a aac \"$outputFilePath\""
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
        "16:9" -> "crop='trunc(min(iw,ih*16/9)/2)*2':'trunc(min(ih,iw*9/16)/2)*2',setsar=1"
        "9:16" -> "crop='trunc(min(iw,ih*9/16)/2)*2':'trunc(min(ih,iw*16/9)/2)*2',setsar=1"
        "1:1"  -> "crop='trunc(min(iw,ih)/2)*2':'trunc(min(iw,ih)/2)*2',setsar=1"
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