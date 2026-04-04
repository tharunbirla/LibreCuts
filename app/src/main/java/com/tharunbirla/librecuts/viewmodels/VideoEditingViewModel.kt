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

class VideoEditingViewModel : ViewModel() {

    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _redoStack = MutableStateFlow<List<VideoProject>>(emptyList())

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val undoStack: StateFlow<List<VideoProject>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<VideoProject>> = _redoStack.asStateFlow()

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

    fun addTextOperation(text: String, fontSize: Int, position: String) {
        addOperation(EditOperation.AddText(text, fontSize, TextPosition.fromLabel(position)))
    }

    fun addMergeOperation(videoUris: List<Uri>) = addOperation(EditOperation.Merge(videoUris))

    fun addMuteAudioOperation() {
        _project.update { it?.removeOperationsOfType(EditOperation.AddBackgroundAudio::class.java) }
        addOperation(EditOperation.MuteAudio())
    }

    fun addBackgroundAudioOperation(audioUri: Uri, removeOriginalAudio: Boolean = false) {
        if (removeOriginalAudio) {
            _project.update { it?.removeOperationsOfType(EditOperation.MuteAudio::class.java) }
        }
        addOperation(EditOperation.AddBackgroundAudio(audioUri, removeOriginalAudio))
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

    /**
     * Build the consolidated FFmpeg command for final export.
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

        // ── MERGE: handled separately via concat demuxer ─────────────────────
        val mergeOp = operations.filterIsInstance<EditOperation.Merge>().firstOrNull()
        if (mergeOp != null) {
            val concatList = StringBuilder()
            concatList.append("file '$sourceFilePath'\n")
            for (videoUri in mergeOp.videoUris) {
                val videoPath = videoUri.path ?: videoUri.toString()
                concatList.append("file '$videoPath'\n")
            }
            // Activity replaces {CONCAT_FILE_PATH} with the actual concat list file path.
            // FIX: output path is quoted AND concat file path placeholder is quoted.
            return "-f concat -safe 0 -i \"{CONCAT_FILE_PATH}\" " +
                    "-c:v libx264 -preset faster -crf 28 -c:a aac " +
                    "\"$outputFilePath\" " +
                    "(CONCAT_LIST:${concatList})"
        }

        // ── NON-MERGE: build filter chain ────────────────────────────────────
        val filterChain = mutableListOf<String>()
        var trimStart: Long? = null
        var trimEnd: Long? = null
        var audioMuted = false
        var audioInputFile: String? = null
        var removeOriginalAudio = false

        for (op in operations) {
            when (op) {
                is EditOperation.Trim -> {
                    trimStart = op.startMs
                    trimEnd = op.endMs
                }
                is EditOperation.Crop -> {
                    val cropFilter = when (op.aspectRatio) {
                        "16:9" -> "crop=iw:iw*9/16"
                        "9:16" -> "crop=ih*9/16:ih"
                        "1:1"  -> "crop=min(iw\\,ih):min(iw\\,ih)"
                        else   -> null
                    }
                    if (cropFilter != null) filterChain.add(cropFilter)
                }
                is EditOperation.AddText -> {
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
                        Log.w("VideoEditingViewModel",
                            "No fontFilePath — text overlay may not render on Android.")
                        ""
                    }

                    val textFilter = "drawtext=${fontPart}text='$escapedText':" +
                            "fontcolor=white:fontsize=${op.fontSize}:${op.position.ffmpegParam}"
                    filterChain.add(textFilter)
                    Log.d("VideoEditingViewModel", "Text filter: $textFilter")
                }
                is EditOperation.MuteAudio -> audioMuted = true
                is EditOperation.AddBackgroundAudio -> {
                    audioInputFile = op.audioUri.path ?: op.audioUri.toString()
                    removeOriginalAudio = op.removeOriginalAudio
                }
                is EditOperation.Merge -> { /* handled above */ }
            }
        }

        val cmd = StringBuilder()

        // Input with optional trim
        if (trimStart != null && trimEnd != null) {
            val startSecs = trimStart / 1000.0
            val duration  = (trimEnd - trimStart) / 1000.0
            cmd.append("-ss $startSecs -i \"$sourceFilePath\" -to $duration")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Second input for background audio mix
        if (audioInputFile != null && !removeOriginalAudio) {
            cmd.append(" -i \"$audioInputFile\"")
        }

        // Video filter chain
        if (filterChain.isNotEmpty()) {
            cmd.append(" -vf \"${filterChain.joinToString(",")}\"")
        }

        // Video codec
        cmd.append(" -c:v libx264 -preset faster -crf 28")

        // Audio handling
        when {
            audioMuted ->
                cmd.append(" -an")
            audioInputFile != null && !removeOriginalAudio ->
                cmd.append(" -filter_complex \"[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a]\"" +
                        " -map 0:v -map \"[a]\" -c:a aac")
            audioInputFile != null && removeOriginalAudio ->
                cmd.append(" -map 0:v -map 1:a -c:a aac")
            else ->
                cmd.append(" -c:a aac")
        }

        // FIX: output path always quoted
        cmd.append(" \"$outputFilePath\"")

        val finalCommand = cmd.toString()
        Log.d("VideoEditingViewModel", "Built command: $finalCommand")
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