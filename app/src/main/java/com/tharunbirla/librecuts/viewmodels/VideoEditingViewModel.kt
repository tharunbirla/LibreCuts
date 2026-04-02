package com.tharunbirla.librecuts.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.models.ExportConfig
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.models.VideoEditingUiState
import com.tharunbirla.librecuts.models.VideoProject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the VideoEditingActivity.
 * Manages the complete state of a video editing session using Kotlin StateFlow.
 * 
 * Key responsibilities:
 * 1. Store and manage the VideoProject state (all pending operations)
 * 2. Provide methods to add/remove/undo operations
 * 3. Manage UI state (loading, exporting, errors)
 * 4. Build consolidated FFmpeg commands for final export
 * 5. Handle project persistence (save/load recipes)
 * 
 * All operations are immutable and thread-safe via StateFlow.
 */
class VideoEditingViewModel : ViewModel() {
    
    // Private mutable state
    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _redoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    
    // Public read-only state
    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val undoStack: StateFlow<List<VideoProject>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<VideoProject>> = _redoStack.asStateFlow()
    
    // Convenience flows
    val operations: StateFlow<List<EditOperation>> 
        get() = _project.asStateFlow().let { projectFlow ->
            MutableStateFlow(project.value?.operations ?: emptyList()).asStateFlow()
        }
    
    /**
     * Initialize the ViewModel with a source video.
     */
    fun initializeProject(sourceUri: Uri, sourceName: String) {
        val newProject = VideoProject(
            sourceUri = sourceUri,
            sourceName = sourceName
        )
        _project.value = newProject
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { it.copy(canUndo = false) }
    }
    
    /**
     * Add a Trim operation to the project.
     */
    fun addTrimOperation(startMs: Long, endMs: Long) {
        val operation = EditOperation.Trim(startMs, endMs)
        addOperation(operation)
    }
    
    /**
     * Add a Crop operation to the project.
     */
    fun addCropOperation(aspectRatio: String) {
        val operation = EditOperation.Crop(aspectRatio)
        addOperation(operation)
    }
    
    /**
     * Add a Text overlay operation to the project.
     */
    fun addTextOperation(text: String, fontSize: Int, position: String) {
        val textPosition = TextPosition.fromLabel(position)
        val operation = EditOperation.AddText(text, fontSize, textPosition)
        addOperation(operation)
    }
    
    /**
     * Add a Merge operation to the project.
     */
    fun addMergeOperation(videoUris: List<Uri>) {
        val operation = EditOperation.Merge(videoUris)
        addOperation(operation)
    }
    
    /**
     * Add a Mute Audio operation to the project.
     */
    fun addMuteAudioOperation() {
        // Remove any existing audio operations first
        _project.update { currentProject ->
            currentProject?.removeOperationsOfType(EditOperation.AddBackgroundAudio::class.java)
        }
        val operation = EditOperation.MuteAudio()
        addOperation(operation)
    }
    
    /**
     * Add a Background Audio operation to the project.
     */
    fun addBackgroundAudioOperation(audioUri: Uri, removeOriginalAudio: Boolean = false) {
        // Remove any existing mute operations if we're replacing
        if (removeOriginalAudio) {
            _project.update { currentProject ->
                currentProject?.removeOperationsOfType(EditOperation.MuteAudio::class.java)
            }
        }
        val operation = EditOperation.AddBackgroundAudio(audioUri, removeOriginalAudio)
        addOperation(operation)
    }
    
    /**
     * Generic method to add any operation to the project.
     * Updates undo/redo stacks and UI state.
     */
    private fun addOperation(operation: EditOperation) {
        viewModelScope.launch {
            _project.update { currentProject ->
                currentProject?.let {
                    // Save current state to undo stack
                    _undoStack.value = _undoStack.value + listOf(it)
                    // Clear redo stack on new operation
                    _redoStack.value = emptyList()
                    // Add the new operation
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
    
    /**
     * Undo the last operation.
     */
    fun undo() {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            val lastUndoState = _undoStack.value.lastOrNull() ?: return@launch
            
            // Save current to redo stack
            _redoStack.value = _redoStack.value + listOf(currentProject)
            // Remove from undo stack and restore
            _undoStack.value = _undoStack.value.dropLast(1)
            _project.value = lastUndoState
            
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = lastUndoState.getOperationCount(),
                    canUndo = _undoStack.value.isNotEmpty(),
                    canRedo = _redoStack.value.isNotEmpty()
                )
            }
        }
    }
    
    /**
     * Redo the last undone operation.
     */
    fun redo() {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            val lastRedoState = _redoStack.value.lastOrNull() ?: return@launch
            
            // Save current to undo stack
            _undoStack.value = _undoStack.value + listOf(currentProject)
            // Remove from redo stack and restore
            _redoStack.value = _redoStack.value.dropLast(1)
            _project.value = lastRedoState
            
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = lastRedoState.getOperationCount(),
                    canUndo = _undoStack.value.isNotEmpty(),
                    canRedo = _redoStack.value.isNotEmpty()
                )
            }
        }
    }
    
    /**
     * Clear all operations and reset to original video.
     */
    fun clearAllOperations() {
        viewModelScope.launch {
            _project.update { currentProject ->
                currentProject?.let {
                    _undoStack.value = _undoStack.value + listOf(it)
                    _redoStack.value = emptyList()
                    it.copy(operations = emptyList())
                }
            }
            updateUiState { state ->
                state.copy(
                    pendingOperationCount = 0,
                    canUndo = _undoStack.value.isNotEmpty()
                )
            }
        }
    }
    
    /**
     * Remove a specific operation by ID.
     */
    fun removeOperation(operationId: String) {
        viewModelScope.launch {
            _project.update { currentProject ->
                currentProject?.let {
                    _undoStack.value = _undoStack.value + listOf(it)
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
    
    /**
     * Set the current preview to show the video state after applying N operations.
     * This is used for previewing intermediate states without re-rendering.
     * 
     * @param operationIndex: -1 for original, 0 for after first op, etc.
     */
    fun setPreviewOperationIndex(operationIndex: Int) {
        updateUiState { state ->
            state.copy(currentPreviewOperationIndex = operationIndex)
        }
    }
    
    /**
     * Build the consolidated FFmpeg command string for final export.
     * Combines all operations into a single, optimized FFmpeg pipeline.
     */
    fun buildConsolidatedFFmpegCommand(
        sourceFilePath: String,
        outputFilePath: String
    ): String? {
        val currentProject = _project.value ?: return null
        if (currentProject.operations.isEmpty()) {
            // No operations; just copy the source
            return "-i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }
        
        val operations = currentProject.operations
        val filterChain = mutableListOf<String>()
        var trimStart: Long? = null
        var trimEnd: Long? = null
        var audioMuted = false
        var audioInputFile: String? = null
        var removeOriginalAudio = false
        
        // First pass: collect all filter operations
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
                        "1:1" -> "crop=min(iw\\,ih):min(iw\\,ih)"
                        else -> null
                    }
                    if (cropFilter != null) filterChain.add(cropFilter)
                }
                is EditOperation.AddText -> {
                    // Text rendering via drawtext filter is not supported in current FFmpeg build
                    // Text overlays will be rendered on UI layer during preview
                    // Skipping text filter in FFmpeg pipeline
                }
                is EditOperation.MuteAudio -> {
                    audioMuted = true
                }
                is EditOperation.AddBackgroundAudio -> {
                    audioInputFile = op.audioUri.path ?: op.audioUri.toString()
                    removeOriginalAudio = op.removeOriginalAudio
                }
                is EditOperation.Merge -> {
                    // Merge is handled separately in video concatenation logic
                    // For now, we skip it in the filter chain
                }
            }
        }
        
        // Build the command
        val commandBuilder = StringBuilder()
        
        // Trim section: use -ss and -to flags
        if (trimStart != null && trimEnd != null) {
            val startSecs = trimStart / 1000.0
            val endSecs = trimEnd / 1000.0
            commandBuilder.append("-ss $startSecs -i \"$sourceFilePath\" -to ${endSecs - startSecs}")
        } else {
            commandBuilder.append("-i \"$sourceFilePath\"")
        }
        
        // Audio section
        if (audioInputFile != null && !removeOriginalAudio) {
            commandBuilder.append(" -i \"$audioInputFile\"")
        }
        
        // Video filter chain
        if (filterChain.isNotEmpty()) {
            commandBuilder.append(" -vf \"${filterChain.joinToString(",")}\"")
        }
        
        // Codecs and audio handling
        commandBuilder.append(" -c:v libx264 -preset medium")
        
        when {
            audioMuted -> commandBuilder.append(" -an") // No audio
            audioInputFile != null && !removeOriginalAudio -> {
                // Mix original and new audio
                commandBuilder.append(" -filter_complex \"[1:a]aformat=sample_rates=44100:channel_layouts=stereo[a]\" -map 0:v -map \"[a]\" -c:a aac")
            }
            audioInputFile != null && removeOriginalAudio -> {
                // Use only the new audio
                commandBuilder.append(" -map 0:v -map 1:a -c:a aac")
            }
            else -> {
                // Keep original audio
                commandBuilder.append(" -c:a aac")
            }
        }
        
        commandBuilder.append(" \"$outputFilePath\"")
        
        return commandBuilder.toString()
    }
    
    /**
     * Build FFmpeg command for merging videos.
     * Creates a concat demuxer list file and returns the merge command.
     */
    fun buildMergeCommand(
        currentVideoPath: String,
        videoPathsToMerge: List<String>,
        listFilePath: String,
        outputFilePath: String
    ): String {
        val listContent = StringBuilder()
        listContent.append("file '$currentVideoPath'\n")
        for (path in videoPathsToMerge) {
            listContent.append("file '$path'\n")
        }
        
        // Write list file (caller should handle this)
        return "-f concat -safe 0 -i $listFilePath -c:v copy -c:a copy $outputFilePath"
    }
    
    /**
     * Mark export as started (show loading screen).
     */
    fun startExport() {
        updateUiState { state ->
            state.copy(
                isExporting = true,
                exportProgress = 0,
                errorMessage = null
            )
        }
    }
    
    /**
     * Update export progress.
     */
    fun updateExportProgress(progress: Int) {
        updateUiState { state ->
            state.copy(exportProgress = progress.coerceIn(0, 100))
        }
    }
    
    /**
     * Mark export as completed.
     */
    fun finishExport() {
        updateUiState { state ->
            state.copy(
                isExporting = false,
                exportProgress = 100
            )
        }
    }
    
    /**
     * Handle export error.
     */
    fun exportError(error: String) {
        updateUiState { state ->
            state.copy(
                isExporting = false,
                errorMessage = error
            )
        }
    }
    
    /**
     * Clear error message.
     */
    fun clearError() {
        updateUiState { state ->
            state.copy(errorMessage = null)
        }
    }
    
    /**
     * Save the current project as a Recipe for later loading.
     */
    fun saveRecipe(projectName: String): EditRecipe? {
        return _project.value?.let { project ->
            EditRecipe.fromVideoProject(projectName, project)
        }
    }
    
    /**
     * Load a previously saved Recipe.
     */
    fun loadRecipe(recipe: EditRecipe) {
        _project.value = recipe.toVideoProject()
        _undoStack.value = emptyList()
        _redoStack.value = emptyList()
        updateUiState { state ->
            state.copy(
                pendingOperationCount = recipe.operations.size,
                canUndo = false
            )
        }
    }
    
    /**
     * Update UI state using a lambda.
     */
    private fun updateUiState(updater: (VideoEditingUiState) -> VideoEditingUiState) {
        _uiState.update(updater)
    }
}
