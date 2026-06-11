package com.tharunbirla.librecuts.viewmodels

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.models.VideoEditingUiState
import com.tharunbirla.librecuts.models.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

enum class ExportQuality(val crf: Int, val preset: String, val label: String) {
    HIGH(22, "medium", "High Quality"),
    MEDIUM(28, "faster", "Medium Quality"),
    LOW(34, "ultrafast", "Low Quality")
}

class VideoEditingViewModel : ViewModel() {

    private companion object {
        const val TAG = "VideoEditingViewModel"
    }

    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _redoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _exportQuality = MutableStateFlow(ExportQuality.MEDIUM)

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val undoStack: StateFlow<List<VideoProject>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<VideoProject>> = _redoStack.asStateFlow()
    val exportQuality: StateFlow<ExportQuality> = _exportQuality.asStateFlow()

    fun setExportQuality(quality: ExportQuality) {
        _exportQuality.value = quality
    }

    val operations: StateFlow<List<EditOperation>>
        get() = MutableStateFlow(project.value?.operations ?: emptyList()).asStateFlow()

    fun initializeProject(sourceUri: Uri, sourceName: String) {
        _project.value = VideoProject(sourceUri = sourceUri, sourceName = sourceName)
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(canUndo = false) }
    }

    fun addTrimOperation(startMs: Long, endMs: Long) = addOperation(EditOperation.Trim(startMs, endMs))
    fun addCropOperation(aspectRatio: String) = addOperation(EditOperation.Crop(aspectRatio))

    fun addTextOperation(
        text: String,
        fontSize: Int,
        position: String,
        relativeX: Float? = null,
        relativeY: Float? = null,
        color: String = "#FFFFFF"
    ) {
        addOperation(
            EditOperation.AddText(
                text = text,
                fontSize = fontSize,
                position = TextPosition.fromLabel(position),
                relativeX = relativeX,
                relativeY = relativeY,
                color = color
            )
        )
    }

    fun addMergeOperation(videoUris: List<Uri>) = addOperation(EditOperation.Merge(videoUris))

    fun addMuteAudioOperation() {
        _project.update { it?.removeOperationsOfType(EditOperation.AddBackgroundAudio::class.java) }
        addOperation(EditOperation.MuteAudio())
    }

    fun addBackgroundAudioOperation(
        audioUri: Uri,
        removeOriginalAudio: Boolean = false,
        delayMs: Long = 0L,
        volume: Float = 1.0f,
        startMs: Long = 0L,
        endMs: Long = -1L
    ) {
        if (removeOriginalAudio) {
            _project.update { it?.removeOperationsOfType(EditOperation.MuteAudio::class.java) }
        }
        addOperation(EditOperation.AddBackgroundAudio(
            audioUri = audioUri,
            removeOriginalAudio = removeOriginalAudio,
            delayMs = delayMs,
            volume = volume,
            startMs = startMs,
            endMs = endMs
        ))
    }

    private fun addOperation(operation: EditOperation) {
        viewModelScope.launch {
            _project.update { current ->
                current?.let {
                    _undoStack.value = _undoStack.value + it
                    _redoStack.value = emptyList()
                    it.addOperation(operation)
                }
            }
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = _project.value?.getOperationCount() ?: 0,
                    canUndo = _undoStack.value.isNotEmpty(),
                    canRedo = false
                )
            }
        }
    }

    fun undo() {
        viewModelScope.launch {
            val current = _project.value ?: return@launch
            val last = _undoStack.value.lastOrNull() ?: return@launch
            _redoStack.value = _redoStack.value + current
            _undoStack.value = _undoStack.value.dropLast(1)
            _project.value = last
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = last.getOperationCount(),
                    canUndo = _undoStack.value.isNotEmpty(),
                    canRedo = _redoStack.value.isNotEmpty()
                )
            }
        }
    }

    fun redo() {
        viewModelScope.launch {
            val current = _project.value ?: return@launch
            val last = _redoStack.value.lastOrNull() ?: return@launch
            _undoStack.value = _undoStack.value + current
            _redoStack.value = _redoStack.value.dropLast(1)
            _project.value = last
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = last.getOperationCount(),
                    canUndo = _undoStack.value.isNotEmpty(),
                    canRedo = _redoStack.value.isNotEmpty()
                )
            }
        }
    }

    fun clearAllOperations() {
        viewModelScope.launch {
            _project.update { current ->
                current?.let {
                    _undoStack.value = _undoStack.value + it
                    _redoStack.value = emptyList()
                    it.copy(operations = emptyList())
                }
            }
            updateUiState { state ->
                state.copy(pendingOperationCount = 0, canUndo = _undoStack.value.isNotEmpty())
            }
        }
    }

    fun removeOperation(operationId: String) {
        viewModelScope.launch {
            _project.update { current ->
                current?.let {
                    _undoStack.value = _undoStack.value + it
                    _redoStack.value = emptyList()
                    it.copy(
                        operations = it.operations.filterNot { op ->
                            when (op) {
                                is EditOperation.Trim -> op.id == operationId
                                is EditOperation.Crop -> op.id == operationId
                                is EditOperation.AddText -> op.id == operationId
                                is EditOperation.Merge -> op.id == operationId
                                is EditOperation.MuteAudio -> op.id == operationId
                                is EditOperation.AddBackgroundAudio -> op.id == operationId
                            }
                        }
                    )
                }
            }
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = _project.value?.getOperationCount() ?: 0,
                    canUndo = _undoStack.value.isNotEmpty()
                )
            }
        }
    }

    fun setPreviewOperationIndex(operationIndex: Int) {
        updateUiState { it.copy(currentPreviewOperationIndex = operationIndex) }
    }

    // ── Private filter-building helpers ─────────────────────────────────────

    /** Build a crop filter expression for an aspect ratio. */
    private fun buildCropFilterExpr(aspectRatio: String): String? = when (aspectRatio) {
        "16:9" -> "crop=iw:iw*9/16"
        "9:16" -> "crop=ih*9/16:ih"
        "1:1"  -> "crop=min(iw\\,ih):min(iw\\,ih)"
        else   -> null
    }

    /** Build a drawtext filter expression for a text operation. */
    private fun buildDrawtextExpr(op: EditOperation.AddText, fontFilePath: String?): String {
        val escapedText = op.text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")

        val fontPart = if (!fontFilePath.isNullOrBlank()) {
            val escapedFont = fontFilePath
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFont':"
        } else {
            Log.w(TAG, "No fontFilePath — text overlay may not render on Android.")
            ""
        }

        // Use WYSIWYG fractional coordinates if available, else fall back to enum position
        val positionPart = if (op.hasCustomPosition()) {
            "x='(w*${op.relativeX})-(tw/2)':y='(h*${op.relativeY})-(th/2)'"
        } else {
            op.position.ffmpegParam
        }

        return "drawtext=${fontPart}text='$escapedText':fontcolor='${op.color}':fontsize=${op.fontSize}:$positionPart"
    }

    /**
     * Build the video portion of a filter_complex graph from non-merge, non-audio operations.
     *
     * @return Pair of (list of filter stages, final stream label) — e.g. (["[0:v]crop=...[v0]", "[v0]drawtext=...[v1]"], "[v1]")
     *         If no video filters needed, returns (emptyList, "[0:v]")
     */
    private fun buildVideoFilterStages(
        operations: List<EditOperation>,
        fontFilePath: String?,
        inputLabel: String = "[0:v]"
    ): Pair<List<String>, String> {
        val stages = mutableListOf<String>()
        var currentLabel = inputLabel
        var stageIndex = 0

        for (op in operations) {
            val filterExpr: String? = when (op) {
                is EditOperation.Crop -> buildCropFilterExpr(op.aspectRatio)
                is EditOperation.AddText -> buildDrawtextExpr(op, fontFilePath)
                else -> null
            }
            if (filterExpr != null) {
                val nextLabel = "[v$stageIndex]"
                stages.add("$currentLabel$filterExpr$nextLabel")
                currentLabel = nextLabel
                stageIndex++
            }
        }

        return Pair(stages, currentLabel)
    }

    /**
     * Build the consolidated FFmpeg command for final export.
     *
     * Uses -filter_complex with explicit stream labels so each filter stage
     * receives the correct input dimensions (e.g., drawtext after crop uses
     * post-crop resolution).
     *
     * @param sourceFilePath  Absolute path to the source video.
     * @param outputFilePath  Absolute path for the exported video.
     * @param fontFilePath    Absolute path to a TTF font file (from assets via copyFontToCache).
     *                        Required for text overlays to render on Android.
     */
    fun buildConsolidatedFFmpegCommand(
        sourceFilePath: String,
        outputFilePath: String,
        fontFilePath: String? = null
    ): String? {
        val currentProject = _project.value ?: return null

        if (currentProject.operations.isEmpty()) {
            return "-i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }

        val operations = currentProject.operations

        // Detect if source video has an audio stream
        val hasAudio = try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(sourceFilePath)
            val hasAudioStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            retriever.release()
            hasAudioStr == "yes"
        } catch (e: Exception) {
            Log.e(TAG, "Error checking audio in source file: ${e.message}")
            true // fallback to assuming it has audio
        }

        // ── Collect operation types ───────────────────────────────────────────
        val mergeOp = operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        val nonMergeOps = operations.filterNot { it is EditOperation.Merge }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()
        val audioOps = operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        val audioMuted = operations.any { it is EditOperation.MuteAudio }
        val videoOps = nonMergeOps.filter { it is EditOperation.Crop || it is EditOperation.AddText }

        // ── MERGE PATH: apply source ops, normalize all inputs, concat ────────
        if (mergeOp != null) {
            val inputCount = 1 + mergeOp.videoUris.size
            val cmd = StringBuilder()

            // Add all inputs
            if (trimOp != null) {
                val startSecs = trimOp.startMs / 1000.0
                val duration = (trimOp.endMs - trimOp.startMs) / 1000.0
                cmd.append("-ss $startSecs -i \"$sourceFilePath\" -to $duration")
            } else {
                cmd.append("-i \"$sourceFilePath\"")
            }
            for (videoUri in mergeOp.videoUris) {
                val videoPath = videoUri.path ?: videoUri.toString()
                cmd.append(" -i \"$videoPath\"")
            }

            // Build filter_complex
            val filterParts = mutableListOf<String>()

            // Apply video filters (crop, text) to source [0:v]
            val (sourceVideoStages, sourceVideoLabel) = buildVideoFilterStages(videoOps, fontFilePath, "[0:v]")
            filterParts.addAll(sourceVideoStages)

            // Normalize source video
            val normTarget = "scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30"
            filterParts.add("${sourceVideoLabel}${normTarget}[norm0]")

            // Normalize source audio
            filterParts.add("[0:a]aformat=sample_rates=44100:channel_layouts=stereo[anorm0]")

            // Normalize each additional input
            for (i in 1 until inputCount) {
                filterParts.add("[$i:v]${normTarget}[norm$i]")
                filterParts.add("[$i:a]aformat=sample_rates=44100:channel_layouts=stereo[anorm$i]")
            }

            // Concat all normalized streams
            val concatInputs = (0 until inputCount).joinToString("") { "[norm$it][anorm$it]" }
            filterParts.add("${concatInputs}concat=n=$inputCount:v=1:a=1[outv][outa]")

            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
            cmd.append(" -map \"[outv]\" -map \"[outa]\"")
            cmd.append(" -c:v libx264 -preset ${_exportQuality.value.preset} -crf ${_exportQuality.value.crf} -c:a aac")
            cmd.append(" \"$outputFilePath\"")

            val finalCommand = cmd.toString()
            Log.d(TAG, "Built merge command: $finalCommand")
            return finalCommand
        }

        // ── NON-MERGE PATH: build filter_complex with stream labels ──────────
        val cmd = StringBuilder()

        // Input with optional trim
        if (trimOp != null) {
            val startSecs = trimOp.startMs / 1000.0
            val duration = (trimOp.endMs - trimOp.startMs) / 1000.0
            cmd.append("-ss $startSecs -i \"$sourceFilePath\" -to $duration")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Additional audio inputs
        var audioInputIndex = 1
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = audioOp.audioUri.path ?: audioOp.audioUri.toString()
            cmd.append(" -i \"$audioPath\"")
            audioInputIndices.add(Pair(audioInputIndex, audioOp))
            audioInputIndex++
        }

        // Build the filter_complex graph
        val filterComplexParts = mutableListOf<String>()

        // ── Video filter stages (crop, text) with stream labels ──
        val (videoStages, finalVideoLabel) = buildVideoFilterStages(videoOps, fontFilePath)
        filterComplexParts.addAll(videoStages)

        // ── Audio filter stages (multi-track adelay + volume mixing) ──
        var finalAudioLabel: String? = null
        if (audioMuted) {
            // No audio output — will use -an flag
        } else if (audioInputIndices.isNotEmpty()) {
            // Build per-track adelay + volume filters
            val mixInputLabels = mutableListOf<String>()

            for ((idx, pair) in audioInputIndices.withIndex()) {
                val (inputIdx, audioOp) = pair
                val trackLabel = "[a$idx]"
                val filters = mutableListOf<String>()

                if (audioOp.startMs > 0L || audioOp.endMs > 0L) {
                    val startSec = audioOp.startMs / 1000.0
                    if (audioOp.endMs > 0L) {
                        val endSec = audioOp.endMs / 1000.0
                        filters.add("atrim=start=$startSec:end=$endSec,asetpts=PTS-STARTPTS")
                    } else {
                        filters.add("atrim=start=$startSec,asetpts=PTS-STARTPTS")
                    }
                }
                if (audioOp.delayMs > 0) {
                    filters.add("adelay=${audioOp.delayMs}|${audioOp.delayMs}")
                }
                if (audioOp.volume < 1.0f) {
                    filters.add("volume=${audioOp.volume}")
                }

                if (filters.isNotEmpty()) {
                    filterComplexParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                    mixInputLabels.add(trackLabel)
                } else {
                    mixInputLabels.add("[$inputIdx:a]")
                }
            }

            // Mix original audio with all background tracks
            if (hasAudio && !audioInputIndices.any { it.second.removeOriginalAudio }) {
                // Keep original audio — mix all together
                val allInputs = "[0:a]" + mixInputLabels.joinToString("")
                val totalInputs = 1 + mixInputLabels.size
                filterComplexParts.add("${allInputs}amix=inputs=$totalInputs:duration=longest[outa]")
                finalAudioLabel = "[outa]"
            } else {
                // Replace original audio OR source has no audio — mix only background tracks
                if (mixInputLabels.size == 1) {
                    // Single replacement track: use aformat instead of amix
                    val singleLabel = mixInputLabels[0]
                    filterComplexParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                    finalAudioLabel = "[outa]"
                } else {
                    val allBg = mixInputLabels.joinToString("")
                    filterComplexParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                    finalAudioLabel = "[outa]"
                }
            }
        }

        // ── Assemble the command ──
        val hasVideoFilters = videoStages.isNotEmpty()
        val hasAudioFilters = finalAudioLabel != null

        if (hasVideoFilters || hasAudioFilters) {
            cmd.append(" -filter_complex \"${filterComplexParts.joinToString(";")}\"")

            // Explicit stream mapping when using filter_complex
            if (hasVideoFilters) {
                cmd.append(" -map \"$finalVideoLabel\"")
            } else {
                cmd.append(" -map 0:v")
            }

            if (audioMuted) {
                cmd.append(" -an")
            } else if (hasAudioFilters) {
                cmd.append(" -map \"$finalAudioLabel\"")
            } else {
                cmd.append(" -map 0:a?")
            }
        } else if (audioMuted) {
            cmd.append(" -an")
        }

        // Codecs
        cmd.append(" -c:v libx264 -preset ${_exportQuality.value.preset} -crf ${_exportQuality.value.crf}")
        if (!audioMuted) {
            cmd.append(" -c:a aac")
        }

        cmd.append(" \"$outputFilePath\"")

        val finalCommand = cmd.toString()
        Log.d(TAG, "Built command: $finalCommand")
        return finalCommand
    }

    /**
     * Build an FFmpeg command for a fast 3-second preview around the playhead.
     * Uses ultrafast preset and high CRF for speed over quality.
     * Skips merge operations (preview is source-only).
     *
     * @param seekPositionMs  Current playhead position in ms.
     */
    fun buildPreviewCommand(
        sourceFilePath: String,
        previewOutputPath: String,
        seekPositionMs: Long,
        fontFilePath: String? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val operations = currentProject.operations
            .filterNot { it is EditOperation.Merge } // Skip merge for preview

        if (operations.none { it is EditOperation.Crop || it is EditOperation.AddText }) {
            return null // No visual operations to preview
        }

        val videoOps = operations.filter { it is EditOperation.Crop || it is EditOperation.AddText }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()

        val cmd = StringBuilder()
        if (trimOp != null) {
            val durationSecs = (trimOp.endMs - trimOp.startMs) / 1000.0
            cmd.append("-ss ${trimOp.startMs / 1000.0} -i \"$sourceFilePath\" -t $durationSecs")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Build filter_complex for video operations
        val (videoStages, finalLabel) = buildVideoFilterStages(videoOps, fontFilePath)
        if (videoStages.isNotEmpty()) {
            cmd.append(" -filter_complex \"${videoStages.joinToString(";")}\"")
            cmd.append(" -map \"$finalLabel\" -map 0:a?")
        }

        // Fast preview settings
        cmd.append(" -c:v libx264 -preset ultrafast -crf 35 -c:a aac")
        cmd.append(" -y \"$previewOutputPath\"")

        val finalCommand = cmd.toString()
        Log.d(TAG, "Built preview command: $finalCommand")
        return finalCommand
    }

    private fun copyContentUriToTempFile(context: Context, uri: Uri): String? {
        return try {
            val tempFile = File.createTempFile("temp_video", ".mp4", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { input.copyTo(it) }
            }
            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e("VideoEditingViewModel", "Failed to copy content URI: ${e.message}")
            null
        }
    }

    /**
     * Build the FFmpeg concat command for merging videos.
     * FIX: All paths are now quoted to prevent truncation on long package-name paths.
     */
    fun buildMergeCommand(
        context: Context,
        currentVideoPath: String,
        videoUrisToMerge: List<Uri>,
        listFilePath: String,
        outputFilePath: String
    ): String {
        val listContent = StringBuilder()
        listContent.append("file '$currentVideoPath'\n")

        for (uri in videoUrisToMerge) {
            val tempFilePath = copyContentUriToTempFile(context, uri)
            if (tempFilePath != null) {
                listContent.append("file '$tempFilePath'\n")
            } else {
                Log.e("VideoEditingViewModel", "Skipping URI due to copy failure: $uri")
            }
        }

        File(listFilePath).writeText(listContent.toString())

        // Both listFilePath and outputFilePath are quoted
        return "-f concat -safe 0 -i \"$listFilePath\" -c:v copy -c:a copy \"$outputFilePath\""
    }

    fun startExport() {
        updateUiState { it.copy(isExporting = true, exportProgress = 0, errorMessage = null) }
    }

    fun updateExportProgress(progress: Int) {
        updateUiState { it.copy(exportProgress = progress.coerceIn(0, 100)) }
    }

    fun finishExport() {
        updateUiState { it.copy(isExporting = false, exportProgress = 100) }
    }

    fun exportError(error: String) {
        updateUiState { it.copy(isExporting = false, errorMessage = error) }
    }

    fun clearError() {
        updateUiState { it.copy(errorMessage = null) }
    }

    fun saveRecipe(projectName: String): EditRecipe? =
        _project.value?.let { EditRecipe.fromVideoProject(projectName, it) }

    fun loadRecipe(recipe: EditRecipe) {
        _project.value = recipe.toVideoProject()
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(pendingOperationCount = recipe.operations.size, canUndo = false) }
    }

    private fun updateUiState(updater: (VideoEditingUiState) -> VideoEditingUiState) {
        _uiState.update(updater)
    }
}