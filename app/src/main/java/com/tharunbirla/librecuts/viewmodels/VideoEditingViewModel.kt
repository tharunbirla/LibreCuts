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

enum class ExportQuality(val bitrate: String, val label: String) {
    HIGH("8M", "High Quality"),
    MEDIUM("4M", "Medium Quality"),
    LOW("2M", "Low Quality")
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
    private val _selectedOperationId = MutableStateFlow<String?>(null)

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val selectedOperationId: StateFlow<String?> = _selectedOperationId.asStateFlow()
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

    fun updateMainVideoTrim(startMs: Long, endMs: Long) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null

                val ops = current.operations.toMutableList()
                
                val trimIndex = ops.indexOfFirst { it is EditOperation.Trim }
                if (trimIndex != -1) {
                    ops[trimIndex] = EditOperation.Trim(startMs, endMs)
                } else {
                    ops.add(EditOperation.Trim(startMs, endMs))
                }

                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun updateMainVideoSpeed(speed: Float, proxyUri: Uri?) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null

                val ops = current.operations.toMutableList()
                
                val speedIndex = ops.indexOfFirst { it is EditOperation.SpeedMain }
                if (speedIndex != -1) {
                    ops[speedIndex] = EditOperation.SpeedMain(speed, proxyUri)
                } else {
                    ops.add(EditOperation.SpeedMain(speed, proxyUri))
                }

                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun updateMainVideoReverse(isReversed: Boolean, proxyUri: Uri?) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null

                val ops = current.operations.toMutableList()
                val reverseIndex = ops.indexOfFirst { it is EditOperation.ReverseMain }
                if (reverseIndex != -1) {
                    ops[reverseIndex] = EditOperation.ReverseMain(isReversed, proxyUri)
                } else {
                    ops.add(EditOperation.ReverseMain(isReversed, proxyUri))
                }

                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun addCropOperation(
        aspectRatio: String,
        xFraction: Float = 0f,
        yFraction: Float = 0f,
        wFraction: Float = 1f,
        hFraction: Float = 1f
    ) = addOperation(
        EditOperation.Crop(
            aspectRatio = aspectRatio,
            xFraction = xFraction,
            yFraction = yFraction,
            wFraction = wFraction,
            hFraction = hFraction
        )
    )

    fun addTextOperation(
        text: String,
        fontSize: Int,
        position: String,
        relativeX: Float? = null,
        relativeY: Float? = null,
        color: String = "#FFFFFF",
        startTimeMs: Long? = null,
        endTimeMs: Long? = null
    ) {
        addOperation(
            EditOperation.AddText(
                text = text,
                fontSize = fontSize,
                position = TextPosition.fromLabel(position),
                relativeX = relativeX,
                relativeY = relativeY,
                color = color,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs
            )
        )
    }

    fun updateMergeItemTrim(index: Int, startMs: Long, endMs: Long) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                if (mergeIdx != -1) {
                    val mergeOp = ops[mergeIdx] as EditOperation.Merge
                    val items = mergeOp.items.toMutableList()
                    if (index >= 0 && index < items.size) {
                        val item = items[index]
                        items[index] = item.copy(trimStartMs = startMs, trimEndMs = endMs)
                        ops[mergeIdx] = mergeOp.copy(items = items)
                    }
                }
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun addMergeOperation(items: List<EditOperation.MergeItem>) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                
                if (mergeIdx != -1) {
                    val existingOp = ops[mergeIdx] as EditOperation.Merge
                    val newItems = existingOp.items + items
                    ops[mergeIdx] = existingOp.copy(items = newItems)
                } else {
                    ops.add(EditOperation.Merge(items))
                }
                
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun addTransitionOperation(index: Int, type: String) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                
                val ops = current.operations.toMutableList()
                ops.add(EditOperation.Transition(index, type))
                
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    fun splitVideoSegment(index: Int, localSplitTimeMs: Long, sourceUri: Uri, sourceDuration: Long) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                
                if (index == 0) {
                    // Split the main video
                    val trimIndex = ops.indexOfFirst { it is EditOperation.Trim }
                    val currentTrim = if (trimIndex != -1) ops[trimIndex] as EditOperation.Trim else EditOperation.Trim(0L, sourceDuration)
                    val oldEndMs = currentTrim.endMs
                    
                    // 1. Update existing Trim to end at localSplitTimeMs
                    if (trimIndex != -1) {
                        ops[trimIndex] = currentTrim.copy(endMs = localSplitTimeMs)
                    } else {
                        ops.add(0, EditOperation.Trim(currentTrim.startMs, localSplitTimeMs))
                    }
                    
                    // 2. Create new MergeItem for the second half
                    val newMergeItem = EditOperation.MergeItem(
                        uri = sourceUri,
                        durationMs = sourceDuration,
                        trimStartMs = localSplitTimeMs,
                        trimEndMs = oldEndMs
                    )
                    
                    // 3. Insert into Merge operation at the beginning
                    val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                    if (mergeIdx != -1) {
                        val mergeOp = ops[mergeIdx] as EditOperation.Merge
                        val items = mergeOp.items.toMutableList()
                        items.add(0, newMergeItem)
                        ops[mergeIdx] = mergeOp.copy(items = items)
                    } else {
                        ops.add(EditOperation.Merge(listOf(newMergeItem)))
                    }
                } else {
                    // Split a merged video (index > 0)
                    val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                    if (mergeIdx != -1) {
                        val mergeOp = ops[mergeIdx] as EditOperation.Merge
                        val items = mergeOp.items.toMutableList()
                        val itemIndex = index - 1
                        
                        if (itemIndex >= 0 && itemIndex < items.size) {
                            val targetItem = items[itemIndex]
                            val oldEndMs = targetItem.trimEndMs
                            
                            // 1. Update the existing MergeItem
                            items[itemIndex] = targetItem.copy(trimEndMs = localSplitTimeMs)
                            
                            // 2. Insert the new MergeItem
                            val newMergeItem = targetItem.copy(
                                trimStartMs = localSplitTimeMs,
                                trimEndMs = oldEndMs
                            )
                            items.add(itemIndex + 1, newMergeItem)
                            
                            ops[mergeIdx] = mergeOp.copy(items = items)
                        }
                    }
                }

                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
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

    /** Move the clip at [index] one step earlier (left) or later (right) in the merge list. */
    fun reorderMergeVideo(index: Int, moveForward: Boolean) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                if (mergeIdx == -1) return@update current
                val mergeOp = ops[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                val targetIdx = if (moveForward) index - 1 else index + 1
                if (targetIdx < 0 || targetIdx >= items.size) return@update current
                val tmp = items[index]; items[index] = items[targetIdx]; items[targetIdx] = tmp
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                ops[mergeIdx] = mergeOp.copy(items = items)
                current.copy(operations = ops)
            }
        }
    }

    fun updateSequenceOrder(orderedItems: List<EditOperation.MergeItem>) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val otherOps = current.operations.filterNot {
                    it is EditOperation.Trim || it is EditOperation.SpeedMain || it is EditOperation.ReverseMain || it is EditOperation.Merge
                }
                
                val newOps = mutableListOf<EditOperation>()
                val mainItem = orderedItems[0]
                newOps.add(EditOperation.Trim(mainItem.trimStartMs, mainItem.trimEndMs))
                newOps.add(EditOperation.SpeedMain(mainItem.speed, mainItem.proxyUri))
                newOps.add(EditOperation.ReverseMain(mainItem.isReversed, if (mainItem.isReversed) mainItem.proxyUri else null))
                
                if (orderedItems.size > 1) {
                    newOps.add(EditOperation.Merge(orderedItems.subList(1, orderedItems.size)))
                }
                newOps.addAll(otherOps)
                
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(
                    sourceUri = mainItem.uri,
                    sourceName = mainItem.uri.lastPathSegment ?: "video.mp4",
                    operations = newOps
                )
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

    fun reorderSequenceItem(sequenceItems: List<EditOperation.MergeItem>, fromIndex: Int, toIndex: Int) {
        val mutableItems = sequenceItems.toMutableList()
        if (fromIndex < 0 || fromIndex >= mutableItems.size || toIndex < 0 || toIndex >= mutableItems.size) return
        val temp = mutableItems[fromIndex]
        mutableItems[fromIndex] = mutableItems[toIndex]
        mutableItems[toIndex] = temp
        updateSequenceOrder(mutableItems)
    }


    /** Remove one clip from the merge list by index. Removes the entire Merge op if list becomes empty. */
    fun removeMergeVideo(index: Int) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val mergeIdx = ops.indexOfFirst { it is EditOperation.Merge }
                if (mergeIdx == -1) return@update current
                val mergeOp = ops[mergeIdx] as EditOperation.Merge
                val items = mergeOp.items.toMutableList()
                if (index < 0 || index >= items.size) return@update current
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                items.removeAt(index)
                if (items.isEmpty()) {
                    ops.removeAt(mergeIdx)
                } else {
                    ops[mergeIdx] = mergeOp.copy(items = items)
                }
                current.copy(operations = ops)
            }
        }
    }



    fun addMuteAudioOperation() {
        addOperation(EditOperation.MuteAudio())
    }


    fun addBackgroundAudioOperation(
        audioUri: Uri,
        removeOriginalAudio: Boolean = false,
        volume: Float = 1.0f,
        internalStartMs: Long = 0L,
        internalEndMs: Long = -1L,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null
    ) {
        if (removeOriginalAudio) {
            _project.update { it?.removeOperationsOfType(EditOperation.MuteAudio::class.java) }
        }
        addOperation(EditOperation.AddBackgroundAudio(
            audioUri = audioUri,
            removeOriginalAudio = removeOriginalAudio,
            volume = volume,
            internalStartMs = internalStartMs,
            internalEndMs = internalEndMs,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs
        ))
    }

    fun addImageOverlayOperation(
        imageUri: Uri,
        relativeX: Float,
        relativeY: Float,
        relativeWidth: Float,
        relativeHeight: Float,
        rotationAngle: Float,
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        fileDurationMs: Long? = null
    ) {
        addOperation(
            EditOperation.AddImageOverlay(
                imageUri = imageUri,
                relativeX = relativeX,
                relativeY = relativeY,
                relativeWidth = relativeWidth,
                relativeHeight = relativeHeight,
                rotationAngle = rotationAngle,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                fileDurationMs = fileDurationMs
            )
        )
    }

    fun addOperation(operation: EditOperation) {
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

    fun updateOperation(updatedOp: EditOperation) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                
                val ops = current.operations.toMutableList()
                val index = ops.indexOfFirst {
                    when {
                        it is EditOperation.AddText && updatedOp is EditOperation.AddText -> it.id == updatedOp.id
                        it is EditOperation.AddImageOverlay && updatedOp is EditOperation.AddImageOverlay -> it.id == updatedOp.id
                        it is EditOperation.AddBackgroundAudio && updatedOp is EditOperation.AddBackgroundAudio -> it.id == updatedOp.id
                        it is EditOperation.Merge && updatedOp is EditOperation.Merge -> it.id == updatedOp.id
                        it is EditOperation.AddSubtitles && updatedOp is EditOperation.AddSubtitles -> it.id == updatedOp.id
                        it is EditOperation.Crop && updatedOp is EditOperation.Crop -> it.id == updatedOp.id
                        it is EditOperation.Trim && updatedOp is EditOperation.Trim -> it.id == updatedOp.id
                        else -> false
                    }
                }
                
                if (index != -1) {
                    _undoStack.value = _undoStack.value + current
                    _redoStack.value = emptyList()
                    ops[index] = updatedOp
                    current.copy(operations = ops)
                } else {
                    current
                }
            }
        }
    }

    fun moveOverlayOperation(id: String, moveUp: Boolean) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                
                val ops = current.operations.toMutableList()
                val targetIndex = ops.indexOfFirst {
                    (it is EditOperation.AddText && it.id == id) ||
                    (it is EditOperation.AddImageOverlay && it.id == id)
                }
                
                if (targetIndex == -1) return@update current
                
                val overlayIndices = ops.indices.filter { idx ->
                    val op = ops[idx]
                    op is EditOperation.AddText || op is EditOperation.AddImageOverlay
                }
                
                val relativeIdx = overlayIndices.indexOf(targetIndex)
                if (relativeIdx == -1) return@update current
                
                val swapWithRelativeIdx = if (moveUp) relativeIdx + 1 else relativeIdx - 1
                if (swapWithRelativeIdx in overlayIndices.indices) {
                    val swapWithRealIdx = overlayIndices[swapWithRelativeIdx]
                    
                    _undoStack.value = _undoStack.value + current
                    _redoStack.value = emptyList()
                    
                    val temp = ops[targetIndex]
                    ops[targetIndex] = ops[swapWithRealIdx]
                    ops[swapWithRealIdx] = temp
                    
                    current.copy(operations = ops)
                } else {
                    current
                }
            }
        }
    }

    fun selectOperation(id: String?) {
        _selectedOperationId.value = id
    }

    fun deleteOperation(id: String) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                
                val ops = current.operations.toMutableList()
                val index = ops.indexOfFirst {
                    when (it) {
                        is EditOperation.AddText -> it.id == id
                        is EditOperation.AddImageOverlay -> it.id == id
                        is EditOperation.AddBackgroundAudio -> it.id == id
                        is EditOperation.AddSubtitles -> it.id == id
                        else -> false
                    }
                }
                
                if (index != -1) {
                    _undoStack.value = _undoStack.value + current
                    _redoStack.value = emptyList()
                    val opToRemove = ops[index]
                    ops.removeAt(index)

                    current.copy(operations = ops)
                } else {
                    current
                }
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
                    var newOps = it.operations.filterNot { op ->
                        when (op) {
                            is EditOperation.Trim -> op.id == operationId
                            is EditOperation.Crop -> op.id == operationId
                            is EditOperation.AddText -> op.id == operationId
                            is EditOperation.Merge -> op.id == operationId
                            is EditOperation.MuteAudio -> op.id == operationId
                            is EditOperation.Transition -> op.id == operationId
                            is EditOperation.AddBackgroundAudio -> op.id == operationId
                            is EditOperation.AddImageOverlay -> op.id == operationId
                            is EditOperation.SpeedMain -> op.id == operationId
                            is EditOperation.ReverseMain -> op.id == operationId
                            is EditOperation.AddSubtitles -> op.id == operationId
                        }
                    }
                    it.copy(operations = newOps)
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
    private fun buildCropFilterExpr(op: EditOperation.Crop): String? = when (op.aspectRatio) {
        "16:9" -> "crop=iw:iw*9/16"
        "9:16" -> "crop=ih*9/16:ih"
        "1:1"  -> "crop=min(iw\\,ih):min(iw\\,ih)"
        "Custom" -> {
            val w = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.wFraction)
            val h = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.hFraction)
            val x = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.xFraction)
            val y = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.yFraction)
            "crop=w=$w:h=$h:x=min($x\\,iw-($w)):y=min($y\\,ih-($h))"
        }
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

        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
        return "drawtext=${fontPart}text='$escapedText':fontcolor='${formatColorForFFmpeg(op.color)}':fontsize=${op.fontSize}:$positionPart$enablePart"
    }

    private fun formatColorForFFmpeg(colorHex: String): String {
        if (!colorHex.startsWith("#")) return colorHex
        return when (colorHex.length) {
            9 -> { // #AARRGGBB -> 0xRRGGBBAA
                val aa = colorHex.substring(1, 3)
                val rrggbb = colorHex.substring(3)
                "0x$rrggbb$aa"
            }
            7 -> { // #RRGGBB -> 0xRRGGBB
                "0x${colorHex.substring(1)}"
            }
            else -> colorHex
        }
    }

    private fun buildEnableExpr(startTimeMs: Long?, endTimeMs: Long?): String {
        if (startTimeMs == null && endTimeMs == null) return ""
        val startSec = maxOf(0L, startTimeMs ?: 0L) / 1000.0
        return if (endTimeMs != null) {
            val endSec = maxOf(0L, endTimeMs) / 1000.0
            ":enable='between(t,$startSec,$endSec)'"
        } else {
            ":enable='gte(t,$startSec)'"
        }
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
        inputLabel: String = "[0:v]",
        imageInputIndices: List<Pair<Int, EditOperation.AddImageOverlay>> = emptyList(),
        density: Float = 1.0f
    ): Pair<List<String>, String> {
        val stages = mutableListOf<String>()
        var currentLabel = inputLabel
        var stageIndex = 0

        for (op in operations) {
            when (op) {
                is EditOperation.Crop -> {
                    val filterExpr = buildCropFilterExpr(op)
                    if (filterExpr != null) {
                        val nextLabel = "[v$stageIndex]"
                        stages.add("$currentLabel$filterExpr$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                is EditOperation.AddText -> {
                    val filterExpr = buildDrawtextExpr(op, fontFilePath)
                    val nextLabel = "[v$stageIndex]"
                    stages.add("$currentLabel$filterExpr$nextLabel")
                    currentLabel = nextLabel
                    stageIndex++
                }
                is EditOperation.AddImageOverlay -> {
                    val imageInputIndex = imageInputIndices.find { it.second.id == op.id }?.first
                    if (imageInputIndex != null) {
                        val radians = op.rotationAngle * Math.PI / 180.0
                        val scaledImgLabel = "[scaled_img_$stageIndex]"
                        val refVidLabel = "[ref_vid_$stageIndex]"
                        val rotatedImgLabel = "[rotated_img_$stageIndex]"
                        val nextLabel = "[v$stageIndex]"

                        val path = op.imageUri.path
                        val isVideo = path != null && (path.endsWith(".mp4", ignoreCase = true) ||
                                                       path.endsWith(".mkv", ignoreCase = true) ||
                                                       path.endsWith(".mov", ignoreCase = true) ||
                                                       path.endsWith(".3gp", ignoreCase = true))

                        if (isVideo) {
                            val startSec = (op.startTimeMs ?: 0L) / 1000.0
                            val ptsLabel = "[pts_$stageIndex]"
                            stages.add("[$imageInputIndex:v]setpts=PTS-STARTPTS+${startSec}/TB,format=rgba$ptsLabel")
                            stages.add("${ptsLabel}${currentLabel}scale2ref=w=main_w*${op.relativeWidth}:h=main_h*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        } else {
                            val rgbaImgLabel = "[rgba_img_$stageIndex]"
                            stages.add("[$imageInputIndex:v]format=rgba$rgbaImgLabel")
                            stages.add("${rgbaImgLabel}${currentLabel}scale2ref=w=main_w*${op.relativeWidth}:h=main_h*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        }

                        // Rotate image/video overlay (c=none preserves transparent background, ow/oh prevent cropping)
                        stages.add("${scaledImgLabel}rotate=$radians:c=none:ow='rotw($radians)':oh='roth($radians)'${rotatedImgLabel}")
                        // Overlay image/video on the reference video
                        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
                        stages.add("${refVidLabel}${rotatedImgLabel}overlay=x=(W*${op.relativeX})-(w/2):y=(H*${op.relativeY})-(h/2)${enablePart}${nextLabel}")

                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                is EditOperation.AddSubtitles -> {
                    for (cue in op.cues) {
                        val escapedText = cue.text
                            .replace("\\", "\\\\")
                            .replace("'", "\\'")
                            .replace(":", "\\:")
                            .replace("\n", "\n")
                        
                        val fontPart = if (!fontFilePath.isNullOrBlank()) {
                            val escapedFont = fontFilePath
                                .replace("\\", "\\\\")
                                .replace("'", "\\'")
                                .replace(":", "\\:")
                            "fontfile='$escapedFont':"
                        } else {
                            ""
                        }
                        
                        val startSec = cue.startTimeMs / 1000.0
                        val endSec = cue.endTimeMs / 1000.0
                        val enablePart = ":enable='between(t,$startSec,$endSec)'"

                        val posPart = if (op.hasCustomPosition()) {
                            "x='(w*${op.relativeX})-(tw/2)':y='(h*${op.relativeY})-(th/2)'"
                        } else {
                            when (op.position) {
                                TextPosition.TOP_LEFT -> "x=24:y=24"
                                TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> "x=(w-tw)/2:y=24"
                                TextPosition.TOP_RIGHT -> "x=w-tw-24:y=24"
                                TextPosition.CENTER_LEFT -> "x=24:y=(h-th)/2"
                                TextPosition.CENTER -> "x=(w-tw)/2:y=(h-th)/2"
                                TextPosition.CENTER_RIGHT -> "x=w-tw-24:y=(h-th)/2"
                                TextPosition.BOTTOM_LEFT -> "x=24:y=h-th-24"
                                TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> "x=(w-tw)/2:y=h-th-24"
                                TextPosition.BOTTOM_RIGHT -> "x=w-tw-24:y=h-th-24"
                            }
                        }

                        val boxPart = ":box=1:boxcolor='0x00000080':boxborderw=${(8 * density).toInt()}"
                        
                        val filterExpr = "drawtext=${fontPart}text='$escapedText':fontcolor='white':fontsize=${(op.fontSize * density).toInt()}:${posPart}${boxPart}$enablePart"
                        
                        val nextLabel = "[v$stageIndex]"
                        stages.add("$currentLabel$filterExpr$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                else -> {}
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
        fontFilePath: String? = null,
        context: Context? = null
    ): String? {
        val currentProject = _project.value ?: return null
        val density = context?.resources?.displayMetrics?.density ?: 1.0f

        if (currentProject.operations.isEmpty()) {
            return "-y -i \"$sourceFilePath\" -c copy \"$outputFilePath\""
        }

        val operations = currentProject.operations

        // Detect if source video has an audio stream
        val hasAudio = try {
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(sourceFilePath)
                val hasAudioStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                hasAudioStr == "yes"
            } finally {
                retriever.release()
            }
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
        val videoOps = nonMergeOps.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddImageOverlay || it is EditOperation.AddSubtitles }
        val imageOps = operations.filterIsInstance<EditOperation.AddImageOverlay>()

        // ── Unified Input Indexing ────────────────────────────────────────────
        val cmd = StringBuilder("-y") // Always overwrite output — prevents silent failure on retry
        var inputIndex = 0

        /** Resolve a URI to a real filesystem path FFmpeg can read.
         *  content:// URIs are copied to cacheDir; file:// or plain paths are used directly. */
        fun resolveUriToPath(uri: android.net.Uri): String? {
            if (uri.scheme == "content" && context != null) {
                return copyContentUriToTempFile(context, uri)
            }
            return uri.path ?: uri.toString()
        }

        // 1. Source Video Input (Index 0)
        var outputDuration: Double? = null
        val reverseOp = operations.filterIsInstance<EditOperation.ReverseMain>().lastOrNull()
        val speedOp = operations.filterIsInstance<EditOperation.SpeedMain>().lastOrNull()
        val finalProxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        
        if (finalProxyUri != null) {
            val proxyPath = resolveUriToPath(finalProxyUri) ?: finalProxyUri.toString()
            val baseDuration = if (trimOp != null) (trimOp.endMs - trimOp.startMs) else 0L
            if (baseDuration > 0) {
                val speed = speedOp?.speed ?: 1.0f
                outputDuration = baseDuration / speed / 1000.0
                cmd.append(" -t $outputDuration -i \"$proxyPath\"")
            } else {
                cmd.append(" -i \"$proxyPath\"")
            }
        } else if (trimOp != null) {
            val startSecs = trimOp.startMs / 1000.0
            val duration = (trimOp.endMs - trimOp.startMs) / 1000.0
            if (mergeOp == null) {
                outputDuration = duration
            }
            cmd.append(" -ss $startSecs -t $duration -i \"$sourceFilePath\"")
        } else {
            cmd.append(" -i \"$sourceFilePath\"")
        }
        inputIndex++

        // 2. Merge Video Inputs
        val mergeVideoIndices = mutableListOf<Int>()
        if (mergeOp != null) {
            for (item in mergeOp.items) {
                if (item.proxyUri != null) {
                    val proxyPath = item.proxyUri.path ?: item.proxyUri.toString()
                    val duration = item.trimmedDurationMs / 1000.0
                    cmd.append(" -t $duration -i \"$proxyPath\"")
                } else {
                    val videoPath = resolveUriToPath(item.uri) ?: item.uri.toString()
                    val startSecs = item.trimStartMs / 1000.0
                    val duration = (item.trimEndMs - item.trimStartMs) / 1000.0
                    cmd.append(" -ss $startSecs -t $duration -i \"$videoPath\"")
                }
                mergeVideoIndices.add(inputIndex)
                inputIndex++
            }
        }

        // 3. Audio Inputs  (content:// URIs must be cached to a real path)
        val audioInputIndices = mutableListOf<Pair<Int, EditOperation.AddBackgroundAudio>>()
        for (audioOp in audioOps) {
            val audioPath = resolveUriToPath(audioOp.audioUri)
            if (audioPath == null) {
                Log.e(TAG, "Failed to resolve audio URI to path: ${audioOp.audioUri} — skipping track")
                continue
            }
            cmd.append(" -i \"$audioPath\"")
            audioInputIndices.add(Pair(inputIndex, audioOp))
            inputIndex++
        }

        // 4. Image Overlay Inputs  (content:// URIs must be cached to a real path)
        val imageInputIndices = mutableListOf<Pair<Int, EditOperation.AddImageOverlay>>()
        for (imageOp in imageOps) {
            val imagePath = resolveUriToPath(imageOp.imageUri)
            if (imagePath == null) {
                Log.e(TAG, "Failed to resolve image URI to path: ${imageOp.imageUri} — skipping overlay")
                continue
            }
            val isGif = imagePath.endsWith(".gif", ignoreCase = true)
            if (isGif) {
                cmd.append(" -ignore_loop 0 -i \"$imagePath\"")
            } else {
                cmd.append(" -i \"$imagePath\"")
            }
            imageInputIndices.add(Pair(inputIndex, imageOp))
            inputIndex++
        }

        // ── MERGE PATH ────────────────────────────────────────────────────────
        if (mergeOp != null) {
            val inputCount = 1 + mergeOp.videoUris.size
            val filterParts = mutableListOf<String>()

            var mainWidth = 1280
            var mainHeight = 720
            var sourceDurationMs = 0L
            try {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(sourceFilePath)
                    val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    val dStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val w = wStr?.toIntOrNull() ?: 1280
                    val h = hStr?.toIntOrNull() ?: 720
                    val r = rStr?.toIntOrNull() ?: 0
                    sourceDurationMs = dStr?.toLongOrNull() ?: 0L
                    if (r == 90 || r == 270) {
                        mainWidth = h
                        mainHeight = w
                    } else {
                        mainWidth = w
                        mainHeight = h
                    }
                } finally {
                    retriever.release()
                }
                // FFmpeg requires even dimensions
                if (mainWidth % 2 != 0) mainWidth -= 1
                if (mainHeight % 2 != 0) mainHeight -= 1
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting dimensions: ${e.message}")
            }

            val hasAudioArray = BooleanArray(inputCount)
            val durationsArray = DoubleArray(inputCount)
            
            hasAudioArray[0] = hasAudio
            durationsArray[0] = if (speedOp?.proxyUri != null) {
                val base = if (trimOp != null) (trimOp.endMs - trimOp.startMs) else sourceDurationMs
                (base / speedOp.speed) / 1000.0
            } else if (trimOp != null) {
                (trimOp.endMs - trimOp.startMs) / 1000.0
            } else {
                sourceDurationMs / 1000.0
            }

            for ((idx, item) in mergeOp.items.withIndex()) {
                val path = item.uri.path ?: item.uri.toString()
                hasAudioArray[idx + 1] = try {
                    val r = android.media.MediaMetadataRetriever()
                    try {
                        r.setDataSource(path)
                        val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                        h == "yes"
                    } finally {
                        r.release()
                    }
                } catch (e: Exception) { true }
                durationsArray[idx + 1] = item.trimmedDurationMs / 1000.0
            }


            // Normalize each clip to the same resolution, fps, pixel format.
            // Using format=yuv420p ensures consistent formats for xfade and concat inputs.
            val normTarget = "setpts=PTS-STARTPTS,scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=30,format=yuv420p"

            for (i in 0 until inputCount) {
                filterParts.add("[$i:v]${normTarget}[norm$i]")
                if (hasAudioArray[i]) {
                    filterParts.add("[$i:a]asetpts=PTS-STARTPTS,aformat=sample_rates=44100:channel_layouts=stereo[anorm$i]")
                } else {
                    val d = durationsArray[i]
                    filterParts.add("anullsrc=r=44100:cl=stereo,atrim=duration=$d,asetpts=PTS-STARTPTS[anorm$i]")
                }
            }

            // Build a list of per-gap transition info (gap i = between clip i and clip i+1)
            // transitionOps[i] is the Transition op for gap i, or null if none.
            val transitionOps = List(inputCount - 1) { gapIdx ->
                operations.filterIsInstance<EditOperation.Transition>().find { it.index == gapIdx }
            }

            // Chain xfade/acrossfade for transitions and concat for plain gaps.
            // Represents a finalized stream ready to be used as xfade input
            data class StreamInfo(val vLabel: String, val aLabel: String, val durationSec: Double)

            val pendingClipIndices = mutableListOf(0) // start with clip 0

            var currentStream: StreamInfo? = null
            var transitionLabelCounter = 0
            var concatLabelCounter = 0

            fun flushPendingGroup(clipIndices: List<Int>): StreamInfo {
                return if (clipIndices.size == 1) {
                    val i = clipIndices[0]
                    StreamInfo("[norm$i]", "[anorm$i]", durationsArray[i])
                } else {
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    val inputs = clipIndices.joinToString("") { "[norm$it][anorm$it]" }
                    // concat then normalize timestamps for proper xfade offsets
                    filterParts.add("${inputs}concat=n=${clipIndices.size}:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    val totalDur = clipIndices.sumOf { durationsArray[it] }
                    concatLabelCounter++
                    // use the normalized labels for subsequent processing
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", totalDur)
                }
            }

            for (gapIdx in 0 until inputCount - 1) {
                val transOp = transitionOps[gapIdx]
                if (transOp == null || transOp.type == "none") {
                    // No transition at this gap — just accumulate the next clip
                    pendingClipIndices.add(gapIdx + 1)
                } else {
                    // Flush the pending group into a stream, if any pending clips exist.
                    // If none exist, it means the currentStream (previous transition output) is the base.
                    val baseStream = if (pendingClipIndices.isEmpty()) {
                        currentStream ?: throw IllegalStateException("Both pending clips and currentStream are empty")
                    } else {
                        val leftStream = flushPendingGroup(pendingClipIndices)
                        pendingClipIndices.clear()
                        if (currentStream == null) {
                            leftStream
                        } else {
                            // Concat previous xfade output with the newly flushed left group
                            val vOut = "[ccat${concatLabelCounter}v]"
                            val aOut = "[ccat${concatLabelCounter}a]"
                            filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${leftStream.vLabel}${leftStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                            filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                            filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                            concatLabelCounter++
                            StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + leftStream.durationSec)
                        }
                    }

                    val rawTransDuration = transOp.durationMs / 1000.0
                    val transDuration = rawTransDuration.coerceAtMost(baseStream.durationSec).coerceAtMost(durationsArray[gapIdx + 1])
                    val offset = (baseStream.durationSec - transDuration).coerceAtLeast(0.0)

                    val xfvOut = "[xfv${transitionLabelCounter}]"
                    val xfaOut = "[xfa${transitionLabelCounter}]"
                    val nextVLabel = "[norm${gapIdx + 1}]"
                    val nextALabel = "[anorm${gapIdx + 1}]"

                    filterParts.add("${baseStream.vLabel}${nextVLabel}xfade=transition=${transOp.type}:duration=${transDuration}:offset=${offset}${xfvOut}")
                    filterParts.add("${baseStream.aLabel}${nextALabel}acrossfade=d=${transDuration}:c1=tri:c2=tri${xfaOut}")

                    val newDuration = offset + durationsArray[gapIdx + 1]
                    currentStream = StreamInfo(xfvOut, xfaOut, newDuration)
                    transitionLabelCounter++
                    // Next clip (gapIdx+1) has already been consumed by xfade — don't add it to pending
                }
            }

            // Flush any remaining pending clips
            val remainingStream = if (pendingClipIndices.isNotEmpty()) flushPendingGroup(pendingClipIndices) else null

            val finalStream: StreamInfo = when {
                currentStream == null && remainingStream != null -> remainingStream
                currentStream != null && remainingStream == null -> currentStream!!
                currentStream != null && remainingStream != null -> {
                    // Concat the last xfade output with remaining plain clips
                    // FFmpeg concat requires interleaved [v0][a0][v1][a1] order
                    val vOut = "[ccat${concatLabelCounter}v]"
                    val aOut = "[ccat${concatLabelCounter}a]"
                    // concat then normalize timestamps
                    filterParts.add("${currentStream.vLabel}${currentStream.aLabel}${remainingStream.vLabel}${remainingStream.aLabel}concat=n=2:v=1:a=1${vOut}${aOut}")
                    filterParts.add("${vOut}settb=1/30,setpts=N[norm${concatLabelCounter}v]")
                    filterParts.add("${aOut}asetpts=PTS-STARTPTS[anorm${concatLabelCounter}a]")
                    concatLabelCounter++
                    StreamInfo("[norm${concatLabelCounter - 1}v]", "[anorm${concatLabelCounter - 1}a]", currentStream.durationSec + remainingStream.durationSec)
                }
                else -> StreamInfo("[norm0]", "[anorm0]", durationsArray[0]) // fallback single clip
            }

            var currentVLabel = finalStream.vLabel
            var currentALabel = finalStream.aLabel
            val accumulatedDuration = finalStream.durationSec

            var currentVideoLabel = currentVLabel
            var currentAudioLabel = currentALabel

            outputDuration = accumulatedDuration


            val (sourceVideoStages, tempVideoLabel) = buildVideoFilterStages(
                operations = videoOps,
                fontFilePath = fontFilePath,
                inputLabel = currentVideoLabel,
                imageInputIndices = imageInputIndices,
                density = density
            )
            filterParts.addAll(sourceVideoStages)
            val finalVideoLabel = "[fmtv]"
            filterParts.add("${tempVideoLabel}format=yuv420p$finalVideoLabel")

            if (audioMuted) {
                filterParts.add("${currentAudioLabel}anullsink")
            } else if (audioInputIndices.isNotEmpty()) {
                val mixInputLabels = mutableListOf<String>()
                mixInputLabels.add(currentAudioLabel)

                for ((idx, pair) in audioInputIndices.withIndex()) {
                    val (inputIdx, audioOp) = pair
                    val trackLabel = "[a_bg_$idx]"
                    val filters = mutableListOf<String>()

                    val internalStartSec = audioOp.internalStartMs / 1000.0
                    var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                    if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                        val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        val maxEndSec = internalStartSec + timelineDurationSec
                        if (maxEndSec < internalEndSec) internalEndSec = maxEndSec
                    }

                    if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                        if (internalEndSec != Double.MAX_VALUE) {
                            filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                        } else {
                            filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                        }
                    }
                    var delayMs = audioOp.startTimeMs ?: 0L
                    if (delayMs < 0) delayMs = 0L
                    if (delayMs > 0) {
                        filters.add("adelay=$delayMs|$delayMs")
                    }
                    if (audioOp.volume != 1.0f) {
                        filters.add("volume=${audioOp.volume}")
                    }

                    if (filters.isNotEmpty()) {
                        filterParts.add("[$inputIdx:a]${filters.joinToString(",")}$trackLabel")
                        mixInputLabels.add(trackLabel)
                    } else {
                        mixInputLabels.add("[$inputIdx:a]")
                    }
                }

                if (!audioInputIndices.any { it.second.removeOriginalAudio }) {
                    val allInputs = mixInputLabels.joinToString("")
                    filterParts.add("${allInputs}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                    currentAudioLabel = "[outa]"
                } else {
                    filterParts.add("${mixInputLabels[0]}anullsink")
                    mixInputLabels.removeAt(0)
                    if (mixInputLabels.size == 1) {
                        val singleLabel = mixInputLabels[0]
                        filterParts.add("${singleLabel}aformat=sample_rates=44100:channel_layouts=stereo[outa]")
                        currentAudioLabel = "[outa]"
                    } else {
                        val allBg = mixInputLabels.joinToString("")
                        filterParts.add("${allBg}amix=inputs=${mixInputLabels.size}:duration=longest[outa]")
                        currentAudioLabel = "[outa]"
                    }
                }
            }

            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
            cmd.append(" -map \"$finalVideoLabel\"")
            if (!audioMuted) {
                cmd.append(" -map \"$currentAudioLabel\"")
            } else {
                cmd.append(" -an")
            }
            cmd.append(" -c:v h264_mediacodec -b:v ${_exportQuality.value.bitrate}")
            if (!audioMuted) {
                cmd.append(" -c:a aac")
            }

            if (outputDuration != null) {
                cmd.append(" -t $outputDuration")
            }
            cmd.append(" \"$outputFilePath\"")

            val finalCommand = cmd.toString()
            Log.d(TAG, "Built merge command: $finalCommand")
            return finalCommand
        }

        // ── NON-MERGE PATH ────────────────────────────────────────────────────
        val filterComplexParts = mutableListOf<String>()

        // ── Video filter stages (crop, text, image overlays) ──
        val (videoStages, finalVideoLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = fontFilePath,
            inputLabel = "[0:v]",
            imageInputIndices = imageInputIndices,
            density = density
        )
        filterComplexParts.addAll(videoStages)

        // ── Audio filter stages (multi-track adelay + volume mixing) ──
        var finalAudioLabel: String? = null
        if (audioMuted) {
            // No audio output — will use -an flag
        } else if (audioInputIndices.isNotEmpty()) {
            val mixInputLabels = mutableListOf<String>()

            for ((idx, pair) in audioInputIndices.withIndex()) {
                val (inputIdx, audioOp) = pair
                val trackLabel = "[a$idx]"
                val filters = mutableListOf<String>()

                val internalStartSec = audioOp.internalStartMs / 1000.0
                var internalEndSec = if (audioOp.internalEndMs > 0L) audioOp.internalEndMs / 1000.0 else Double.MAX_VALUE

                if (audioOp.startTimeMs != null && audioOp.endTimeMs != null) {
                    val timelineDurationSec = (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    val maxEndSec = internalStartSec + timelineDurationSec
                    if (maxEndSec < internalEndSec) {
                        internalEndSec = maxEndSec
                    }
                }

                if (internalStartSec > 0.0 || internalEndSec != Double.MAX_VALUE) {
                    if (internalEndSec != Double.MAX_VALUE) {
                        filters.add("atrim=start=$internalStartSec:end=$internalEndSec,asetpts=PTS-STARTPTS")
                    } else {
                        filters.add("atrim=start=$internalStartSec,asetpts=PTS-STARTPTS")
                    }
                }
                var delayMs = audioOp.startTimeMs ?: 0L
                if (delayMs < 0) delayMs = 0L
                if (delayMs > 0) {
                    filters.add("adelay=$delayMs|$delayMs")
                }
                
                if (audioOp.volume != 1.0f) {
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
            val mainVideoMuted = false
            if (hasAudio && !mainVideoMuted && !audioInputIndices.any { it.second.removeOriginalAudio }) {
                val allInputs = "[0:a]" + mixInputLabels.joinToString("")
                val totalInputs = 1 + mixInputLabels.size
                filterComplexParts.add("${allInputs}amix=inputs=$totalInputs:duration=longest[outa]")
                finalAudioLabel = "[outa]"
            } else {
                if (mixInputLabels.size == 1) {
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
            var mappedVideoLabel = finalVideoLabel
            if (hasVideoFilters) {
                val fmtLabel = "[fmtv]"
                filterComplexParts.add("${finalVideoLabel}format=yuv420p${fmtLabel}")
                mappedVideoLabel = fmtLabel
            }
            cmd.append(" -filter_complex \"${filterComplexParts.joinToString(";")}\"")

            if (hasVideoFilters) {
                cmd.append(" -map \"$mappedVideoLabel\"")
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
        cmd.append(" -c:v h264_mediacodec -b:v ${_exportQuality.value.bitrate}")
        if (!audioMuted) {
            cmd.append(" -c:a aac")
        }

        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
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
        fontFilePath: String? = null,
        density: Float = 1.0f
    ): String? {
        val currentProject = _project.value ?: return null
        val operations = currentProject.operations
            .filterNot { it is EditOperation.Merge } // Skip merge for preview

        if (operations.none { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddSubtitles }) {
            return null // No visual operations to preview
        }

        val videoOps = operations.filter { it is EditOperation.Crop || it is EditOperation.AddText || it is EditOperation.AddSubtitles }
        val trimOp = operations.filterIsInstance<EditOperation.Trim>().lastOrNull()

        val cmd = StringBuilder()
        var outputDuration: Double? = null
        if (trimOp != null) {
            val durationSecs = (trimOp.endMs - trimOp.startMs) / 1000.0
            outputDuration = durationSecs
            cmd.append("-ss ${trimOp.startMs / 1000.0} -t $durationSecs -i \"$sourceFilePath\"")
        } else {
            cmd.append("-i \"$sourceFilePath\"")
        }

        // Build filter_complex for video operations
        val (videoStages, finalLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = fontFilePath,
            density = density
        )
        if (videoStages.isNotEmpty()) {
            cmd.append(" -filter_complex \"${videoStages.joinToString(";")}\"")
            cmd.append(" -map \"$finalLabel\" -map 0:a?")
        }

        // Fast preview settings
        cmd.append(" -c:v h264_mediacodec -b:v 1500k -c:a aac")
        if (outputDuration != null) {
            cmd.append(" -t $outputDuration")
        }
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