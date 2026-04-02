package com.tharunbirla.librecuts.services

import android.content.Context
import android.net.Uri
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
 */
class FFmpegRenderEngine(private val context: Context) {
    
    private val activeSessions = mutableListOf<FFmpegSession>()
    private val TAG = "FFmpegRenderEngine"
    
    init {
        // Set up FFmpeg kit configuration
        FFmpegKitConfig.setFontDirectory(context, "/system/fonts", null)
    }
    
    /**
     * Represents the result of an FFmpeg operation.
     */
    sealed class RenderResult {
        data class Success(val outputPath: String, val session: FFmpegSession) : RenderResult()
        data class Failure(val error: String, val session: FFmpegSession? = null) : RenderResult()
        object Cancelled : RenderResult()
    }
    
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
                
                return@withContext if (ReturnCode.isSuccess(returnCode)) {
                    RenderResult.Success(
                        outputPath = extractOutputPath(command),
                        session = session
                    )
                } else {
                    Log.e(TAG, "FFmpeg error: ${session.failStackTrace}")
                    RenderResult.Failure(
                        error = session.failStackTrace ?: "Unknown error",
                        session = session
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during FFmpeg execution: ${e.message}", e)
                RenderResult.Failure(error = e.message ?: "Unknown exception")
            }
        }
    }
    
    /**
     * Cancel all active FFmpeg sessions.
     */
    suspend fun cancelAllSessions() {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel()
            activeSessions.clear()
            Log.d(TAG, "Cancelled all FFmpeg sessions")
        }
    }
    
    /**
     * Cancel a specific session by ID.
     */
    suspend fun cancelSession(sessionId: Long) {
        withContext(Dispatchers.IO) {
            FFmpegKit.cancel(sessionId)
            activeSessions.removeIf { it.sessionId == sessionId }
            Log.d(TAG, "Cancelled session: $sessionId")
        }
    }
    
    /**
     * Check if any sessions are currently active.
     */
    fun hasActiveSessions(): Boolean = activeSessions.isNotEmpty()
    
    /**
     * Get the count of active sessions.
     */
    fun getActiveSessionCount(): Int = activeSessions.size
    
    /**
     * Get the most recent active session.
     */
    fun getLatestSession(): FFmpegSession? = activeSessions.lastOrNull()
    
    /**
     * Render a preview of the video with specified trim bounds.
     * Creates a low-quality preview for fast feedback without re-rendering the full video.
     * 
     * @param sourceFilePath Path to the source video
     * @param startMs Trim start time in milliseconds
     * @param endMs Trim end time in milliseconds
     * @param outputFilePath Path for the preview output
     * @return RenderResult with the output path if successful
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
     * Render a preview with crop applied.
     * 
     * @param sourceFilePath Path to the source video
     * @param aspectRatio Aspect ratio string ("16:9", "9:16", "1:1")
     * @param outputFilePath Path for the preview output
     * @return RenderResult with the output path if successful
     */
    suspend fun renderCropPreview(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = when (aspectRatio) {
            "16:9" -> "crop=iw:iw*9/16"
            "9:16" -> "crop=ih*9/16:ih"
            "1:1" -> "crop=min(iw\\,ih):min(iw\\,ih)"
            else -> return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        }
        
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }
    
    /**
     * Render a preview with text overlay.
     * 
     * @param sourceFilePath Path to the source video
     * @param text Text to overlay
     * @param fontSize Font size in pixels
     * @param positionParam FFmpeg position parameter (e.g., "x=(w-text_w)/2:y=(h-text_h)/2")
     * @param outputFilePath Path for the preview output
     * @return RenderResult with the output path if successful
     */
    suspend fun renderTextPreview(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String
    ): RenderResult {
        val textFilter = "drawtext=text='${text.replace("'", "\\'")}':fontcolor=white:fontsize=$fontSize:$positionParam"
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }
    
    /**
     * Merge multiple videos.
     * 
     * @param listFilePath Path to a file containing list of videos to merge (FFmpeg concat format)
     * @param outputFilePath Path for the merged output
     * @return RenderResult with the output path if successful
     */
    suspend fun mergeVideos(
        listFilePath: String,
        outputFilePath: String
    ): RenderResult {
        val command = "-f concat -safe 0 -i $listFilePath -c:v copy -c:a copy $outputFilePath"
        return executeCommand(command)
    }
    
    /**
     * Export/render the final video with all operations applied.
     * This is the main export function called when the user clicks "Save/Export".
     * 
     * @param ffmpegCommand The consolidated FFmpeg command built by the ViewModel
     * @return RenderResult with the output path if successful
     */
    suspend fun exportFinal(ffmpegCommand: String): RenderResult {
        return executeCommand(ffmpegCommand)
    }
    
    /**
     * Trim a video segment.
     * 
     * @param sourceFilePath Path to the source video
     * @param startMs Trim start time in milliseconds
     * @param endMs Trim end time in milliseconds
     * @param outputFilePath Path for the trimmed output
     * @return RenderResult with the output path if successful
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
     * @param sourceFilePath Path to the source video
     * @param aspectRatio Aspect ratio string ("16:9", "9:16", "1:1")
     * @param outputFilePath Path for the cropped output
     * @return RenderResult with the output path if successful
     */
    suspend fun cropVideo(
        sourceFilePath: String,
        aspectRatio: String,
        outputFilePath: String
    ): RenderResult {
        val cropFilter = when (aspectRatio) {
            "16:9" -> "crop=iw:iw*9/16"
            "9:16" -> "crop=ih*9/16:ih"
            "1:1" -> "crop=min(iw\\,ih):min(iw\\,ih)"
            else -> return RenderResult.Failure("Invalid aspect ratio: $aspectRatio")
        }
        val command = "-i \"$sourceFilePath\" -vf \"$cropFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }
    
    /**
     * Add text overlay to a video.
     * 
     * @param sourceFilePath Path to the source video
     * @param text Text to overlay
     * @param fontSize Font size in pixels
     * @param positionParam FFmpeg position parameter
     * @param outputFilePath Path for the output with text
     * @return RenderResult with the output path if successful
     */
    suspend fun addTextOverlay(
        sourceFilePath: String,
        text: String,
        fontSize: Int,
        positionParam: String,
        outputFilePath: String
    ): RenderResult {
        val textFilter = "drawtext=text='${text.replace("'", "\\'")}':fontcolor=white:fontsize=$fontSize:$positionParam"
        val command = "-i \"$sourceFilePath\" -vf \"$textFilter\" -c:a copy \"$outputFilePath\""
        return executeCommand(command)
    }
    
    /**
     * Add background audio to a video.
     * 
     * @param sourceFilePath Path to the source video
     * @param audioFilePath Path to the audio file to add
     * @param outputFilePath Path for the output with audio
     * @param replaceAudio If true, replaces the original audio; if false, mixes them
     * @return RenderResult with the output path if successful
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
            // Mix audio: use amix filter to combine both audio tracks
            "-i \"$sourceFilePath\" -i \"$audioFilePath\" -filter_complex \"[0:a][1:a]amix=inputs=2:duration=first[a]\" -map 0:v -map \"[a]\" -c:v copy \"$outputFilePath\""
        }
        return executeCommand(command)
    }
    
    /**
     * Mute audio from a video.
     * 
     * @param sourceFilePath Path to the source video
     * @param outputFilePath Path for the output without audio
     * @return RenderResult with the output path if successful
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
     * @param sourceFilePath Path to the source video
     * @param timeMs Time in milliseconds to extract the frame
     * @param outputImagePath Path for the output image
     * @return RenderResult with the output path if successful
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
    
    /**
     * Clean up resources when done.
     */
    fun cleanup() {
        activeSessions.clear()
        Log.d(TAG, "FFmpegRenderEngine cleaned up")
    }
    
    /**
     * Extract the output file path from an FFmpeg command.
     * This is a simple heuristic that assumes the last double-quoted string is the output.
     * Works for standard FFmpeg command patterns.
     */
    private fun extractOutputPath(command: String): String {
        // Find the last quoted string
        val regex = """"([^"]*)"\s*$""".toRegex()
        val match = regex.find(command)
        return match?.groupValues?.get(1) ?: ""
    }
}
