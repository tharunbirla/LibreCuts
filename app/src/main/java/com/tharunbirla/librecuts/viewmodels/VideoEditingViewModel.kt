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

// ExportQuality enum removed in favor of individual parameters

class VideoEditingViewModel : ViewModel() {

    private companion object {
        const val TAG = "VideoEditingViewModel"
    }

    private val _project = MutableStateFlow<VideoProject?>(null)
    private val _uiState = MutableStateFlow(VideoEditingUiState())
    private val _undoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _redoStack = MutableStateFlow<List<VideoProject>>(emptyList())
    private val _exportResolution = MutableStateFlow(1080)
    private val _exportFps = MutableStateFlow(30)
    private val _exportAudioOnly = MutableStateFlow(false)
    private val _selectedOperationId = MutableStateFlow<String?>(null)

    val project: StateFlow<VideoProject?> = _project.asStateFlow()
    val uiState: StateFlow<VideoEditingUiState> = _uiState.asStateFlow()
    val selectedOperationId: StateFlow<String?> = _selectedOperationId.asStateFlow()
    val undoStack: StateFlow<List<VideoProject>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<VideoProject>> = _redoStack.asStateFlow()
    val exportResolution: StateFlow<Int> = _exportResolution.asStateFlow()
    val exportFps: StateFlow<Int> = _exportFps.asStateFlow()
    val exportAudioOnly: StateFlow<Boolean> = _exportAudioOnly.asStateFlow()

    fun setExportSettings(resolution: Int, fps: Int, audioOnly: Boolean) {
        _exportResolution.value = resolution
        _exportFps.value = fps
        _exportAudioOnly.value = audioOnly
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

    fun updateMainVideoMirror(isMirrored: Boolean) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null

                val ops = current.operations.toMutableList()
                val mirrorIndex = ops.indexOfFirst { it is EditOperation.MirrorMain }
                if (mirrorIndex != -1) {
                    ops[mirrorIndex] = EditOperation.MirrorMain(isMirrored)
                } else {
                    ops.add(EditOperation.MirrorMain(isMirrored))
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

    fun toggleMergeItemMirror(index: Int) {
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
                        items[index] = item.copy(isMirrored = !item.isMirrored)
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

    fun updateCanvasBackgroundOperation(
        type: EditOperation.CanvasBackground.BackgroundType,
        colorHex: String = "#000000",
        imageUri: Uri? = null,
        blurRadius: Int = 20
    ) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val existingIdx = ops.indexOfFirst { it is EditOperation.CanvasBackground }
                if (existingIdx != -1) {
                    val op = ops[existingIdx] as EditOperation.CanvasBackground
                    ops[existingIdx] = op.copy(type = type, colorHex = colorHex, imageUri = imageUri, blurRadius = blurRadius)
                } else {
                    ops.add(EditOperation.CanvasBackground(type = type, colorHex = colorHex, imageUri = imageUri, blurRadius = blurRadius))
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

    fun addTextOperation(
        text: String,
        fontSize: Int,
        position: String,
        relativeX: Float? = null,
        relativeY: Float? = null,
        color: String = "#FFFFFF",
        startTimeMs: Long? = null,
        endTimeMs: Long? = null,
        fontPath: String? = null,
        opacity: Float = 1.0f,
        borderThickness: Int = 0,
        borderColor: String = "#000000",
        textAlign: String = "center",
        letterSpacing: Float = 0f,
        lineSpacing: Float = 0f
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
                endTimeMs = endTimeMs,
                fontPath = fontPath,
                opacity = opacity,
                borderThickness = borderThickness,
                borderColor = borderColor,
                textAlign = textAlign,
                letterSpacing = letterSpacing,
                lineSpacing = lineSpacing
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



    fun toggleMuteClip(index: Int) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val existingIdx = ops.indexOfFirst { it is EditOperation.MuteClip && it.index == index }
                if (existingIdx != -1) {
                    val currentMute = ops[existingIdx] as EditOperation.MuteClip
                    ops[existingIdx] = currentMute.copy(isMuted = !currentMute.isMuted)
                } else {
                    ops.add(EditOperation.MuteClip(index, true))
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

    fun setColorFilter(index: Int, filterName: String) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val existingIdx = ops.indexOfFirst { it is EditOperation.ColorFilter && it.index == index }
                if (existingIdx != -1) {
                    if (filterName == "none") {
                        ops.removeAt(existingIdx)
                    } else {
                        ops[existingIdx] = (ops[existingIdx] as EditOperation.ColorFilter).copy(filterName = filterName)
                    }
                } else if (filterName != "none") {
                    ops.add(EditOperation.ColorFilter(index, filterName))
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
        fileDurationMs: Long? = null,
        chromaKeyColor: String? = null,
        chromaKeySimilarity: Float = 0.1f,
        opacity: Float = 1.0f,
        isMirrored: Boolean = false
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
                fileDurationMs = fileDurationMs,
                chromaKeyColor = chromaKeyColor,
                chromaKeySimilarity = chromaKeySimilarity,
                opacity = opacity,
                isMirrored = isMirrored
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
                        it is EditOperation.CanvasBackground && updatedOp is EditOperation.CanvasBackground -> it.id == updatedOp.id
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
        if (_selectedOperationId.value == id) {
            _selectedOperationId.value = null
        }
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
        if (_selectedOperationId.value == operationId) {
            _selectedOperationId.value = null
        }
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
                            is EditOperation.MuteClip -> op.id == operationId
                            is EditOperation.ColorFilter -> op.id == operationId
                            is EditOperation.Adjust -> op.id == operationId
                            is EditOperation.MirrorMain -> op.id == operationId
                            is EditOperation.CanvasBackground -> op.id == operationId
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

    fun setAdjust(
        index: Int,
        brightness: Int = 0,
        contrast: Int = 0,
        warmth: Int = 0,
        shadow: Int = 0,
        highlights: Int = 0,
        saturation: Int = 0,
        exposure: Int = 0,
        sharpen: Int = 0,
        vignette: Int = 0
    ) {
        viewModelScope.launch {
            _project.update { current ->
                if (current == null) return@update null
                val ops = current.operations.toMutableList()
                val existingIdx = ops.indexOfFirst { it is EditOperation.Adjust && it.index == index }
                val newOp = EditOperation.Adjust(
                    index = index,
                    brightness = brightness,
                    contrast = contrast,
                    warmth = warmth,
                    shadow = shadow,
                    highlights = highlights,
                    saturation = saturation,
                    exposure = exposure,
                    sharpen = sharpen,
                    vignette = vignette
                )
                if (existingIdx != -1) {
                    if (newOp.isDefault()) {
                        ops.removeAt(existingIdx)
                    } else {
                        ops[existingIdx] = newOp
                    }
                } else if (!newOp.isDefault()) {
                    ops.add(newOp)
                }
                _undoStack.value = _undoStack.value + current
                _redoStack.value = emptyList()
                current.copy(operations = ops)
            }
        }
    }

    fun setPreviewOperationIndex(operationIndex: Int) {
        updateUiState { it.copy(currentPreviewOperationIndex = operationIndex) }
    }

    // ── Private filter-building helpers ─────────────────────────────────────

    /** Build a crop filter expression for an aspect ratio. */
    private fun buildCropFilterExpr(op: EditOperation.Crop): String? = when (op.aspectRatio) {
        "16:9" -> "crop='trunc(min(iw,ih*16/9)/2)*2':'trunc(min(ih,iw*9/16)/2)*2',setsar=1"
        "9:16" -> "crop='trunc(min(iw,ih*9/16)/2)*2':'trunc(min(ih,iw*16/9)/2)*2',setsar=1"
        "1:1"  -> "crop='trunc(min(iw,ih)/2)*2':'trunc(min(iw,ih)/2)*2',setsar=1"
        "Custom" -> {
            val w = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.wFraction)
            val h = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.hFraction)
            val x = String.format(java.util.Locale.US, "trunc(iw*%.4f/2)*2", op.xFraction)
            val y = String.format(java.util.Locale.US, "trunc(ih*%.4f/2)*2", op.yFraction)
            "crop=w=$w:h=$h:x='min($x,iw-($w))':y='min($y,ih-($h))',setsar=1"
        }
        else   -> null
    }

    /** Build a drawtext filter expression for a text operation. */
    private fun buildDrawtextExpr(op: EditOperation.AddText, fontFilePath: String?): String {
        val escapedText = op.text
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace(":", "\\:")

        val fontToUse = op.fontPath ?: fontFilePath
        val fontPart = if (!fontToUse.isNullOrBlank()) {
            val escapedFont = fontToUse
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace(":", "\\:")
            "fontfile='$escapedFont':"
        } else {
            Log.w(TAG, "No fontFilePath — text overlay may not render on Android.")
            ""
        }

        // Use WYSIWYG fractional coordinates if available, else fall back to enum position
        val positionPart = if (op.positionKeyframes.isNotEmpty()) {
            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY ?: 0.5f, startTimeMs = op.startTimeMs ?: 0L)
            "x='(w*($xExpr))-(tw/2)':y='(h*($yExpr))-(th/2)'"
        } else if (op.hasCustomPosition()) {
            "x='(w*${op.relativeX})-(tw/2)':y='(h*${op.relativeY})-(th/2)'"
        } else {
            op.position.ffmpegParam
        }

        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
        
        val alphaPart = if (op.opacityKeyframes.isNotEmpty()) {
            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = op.startTimeMs ?: 0L)
            ":alpha='$alphaExpr'"
        } else if (op.opacity < 1.0f) {
            ":alpha='${op.opacity}'"
        } else {
            ""
        }
        val borderPart = if (op.borderThickness > 0) ":borderw=${op.borderThickness}:bordercolor='${formatColorForFFmpeg(op.borderColor)}'" else ""
        val alignPart = if (op.textAlign.isNotEmpty()) ":text_align=${op.textAlign}" else ""
        val lineSpacingPart = if (op.lineSpacing != 0f) ":line_spacing=${op.lineSpacing.toInt()}" else ""
        
        return "drawtext=${fontPart}text='$escapedText':fontcolor='${formatColorForFFmpeg(op.color)}':fontsize=${op.fontSize}:$positionPart$enablePart$alphaPart$borderPart$alignPart$lineSpacingPart"
    }

    private fun buildFFmpegInterpolationExpr(
        keyframes: List<EditOperation.KeyframePoint>,
        useValueY: Boolean,
        defaultValue: Float,
        startTimeMs: Long,
        timeVar: String = "t"
    ): String {
        if (keyframes.isEmpty()) return defaultValue.toString()
        val sorted = keyframes.sortedBy { it.timeMs }
        val startSec = startTimeMs / 1000.0
        val tRel = "($timeVar-$startSec)"
        
        if (sorted.size == 1) {
            val v = if (useValueY) sorted[0].valueY else sorted[0].valueX
            return v.toString()
        }
        
        var expr = ""
        val lastVal = if (useValueY) sorted.last().valueY else sorted.last().valueX
        expr = lastVal.toString()
        
        for (i in sorted.size - 2 downTo 0) {
            val k1 = sorted[i]
            val k2 = sorted[i + 1]
            val t1 = k1.timeMs / 1000.0
            val t2 = k2.timeMs / 1000.0
            val v1 = if (useValueY) k1.valueY else k1.valueX
            val v2 = if (useValueY) k2.valueY else k2.valueX
            
            val diffVal = v2 - v1
            val diffTime = t2 - t1
            val segmentExpr = if (diffTime > 0) {
                "$v1 + ($diffVal) * ($tRel - $t1) / ($diffTime)"
            } else {
                v1.toString()
            }
            
            expr = "if(lt($tRel, $t2), $segmentExpr, $expr)"
        }
        
        val firstVal = if (useValueY) sorted.first().valueY else sorted.first().valueX
        val firstTime = sorted.first().timeMs / 1000.0
        expr = "if(lt($tRel, $firstTime), $firstVal, $expr)"
        
        return expr
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

    private fun getFFmpegFilterForAdjust(op: EditOperation.Adjust): String? {
        val filters = mutableListOf<String>()

        // 1. eq filter (brightness, contrast, saturation)
        if (op.brightness != 0 || op.contrast != 0 || op.saturation != 0) {
            val b = op.brightness / 100.0
            val c = 1.0 + (op.contrast / 100.0)
            val s = 1.0 + (op.saturation / 100.0)
            filters.add("eq=brightness=$b:contrast=$c:saturation=$s")
        }

        // 2. exposure
        if (op.exposure != 0) {
            val ev = (op.exposure / 100.0) * 3.0
            filters.add("exposure=exposure=$ev")
        }

        // 3. warmth (colorbalance)
        if (op.warmth != 0) {
            val w = (op.warmth / 100.0) * 0.3
            filters.add("colorbalance=rs=$w:rm=$w:rh=$w:bs=${-w}:bm=${-w}:bh=${-w}")
        }

        // 4. shadows & highlights
        if (op.shadow != 0 || op.highlights != 0) {
            val sh = op.shadow / 100.0
            val hl = op.highlights / 100.0
            filters.add("shadows_highlights=shadows=$sh:highlights=$hl")
        }

        // 5. sharpen (unsharp)
        if (op.sharpen != 0) {
            val amt = (op.sharpen / 100.0) * 1.5
            filters.add("unsharp=luma_amount=$amt")
        }

        // 6. vignette
        if (op.vignette != 0) {
            val angle = 1.5 - (op.vignette / 100.0) * 0.9
            filters.add("vignette=angle=$angle")
        }

        return if (filters.isNotEmpty()) filters.joinToString(",") else null
    }

    private fun getFFmpegFilterForName(name: String): String? {
        return when (name.lowercase()) {
            "vintage" -> "curves=preset=vintage"
            "warm" -> "colorchannelmixer=1.1:0:0:0:0:1.0:0:0:0:0:0.9"
            "cool" -> "colorchannelmixer=0.9:0:0:0:0:1.0:0:0:0:0:1.1"
            "contrast" -> "curves=preset=strong_contrast"
            "monochrome" -> "hue=s=0"
            "vignette" -> "vignette"
            "negative" -> "curves=preset=negative"
            "crossprocess" -> "curves=preset=cross_process"
            else -> null
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

        // Process overlays first so their relative coordinates align with the uncropped original video, matching the UI.
        val overlayOps = operations.filterNot { it is EditOperation.Crop }
        val cropOps = operations.filterIsInstance<EditOperation.Crop>()

        for (op in overlayOps) {
            when (op) {
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
                        val isGif = path != null && path.endsWith(".gif", ignoreCase = true)
                        val isVideo = path != null && (path.endsWith(".mp4", ignoreCase = true) ||
                                                       path.endsWith(".mkv", ignoreCase = true) ||
                                                       path.endsWith(".mov", ignoreCase = true) ||
                                                       path.endsWith(".3gp", ignoreCase = true))

                        if (isVideo) {
                            val startSec = (op.startTimeMs ?: 0L) / 1000.0
                            val ptsLabel = "[pts_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            val speedExpr = if (op.speedKeyframes.isNotEmpty()) {
                                buildFFmpegInterpolationExpr(op.speedKeyframes, useValueY = false, defaultValue = 1.0f, startTimeMs = 0L, timeVar = "T")
                            } else {
                                "1.0"
                            }
                            val safeSpeedExpr = "if(gt($speedExpr,0.1),$speedExpr,0.1)"
                            stages.add("[$imageInputIndex:v]setpts='(PTS-STARTPTS)/($safeSpeedExpr)+${startSec}/TB',format=rgba$mirrorStr$ptsLabel")
                            stages.add("${ptsLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        } else {
                            val rgbaImgLabel = "[rgba_img_$stageIndex]"
                            val mirrorStr = if (op.isMirrored) ",hflip" else ""
                            if (op.opacityKeyframes.isNotEmpty()) {
                                // Static image: convert to video stream so geq's T variable advances
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr,loop=loop=-1:size=1:start=0,fps=25$rgbaImgLabel")
                            } else {
                                stages.add("[$imageInputIndex:v]format=rgba$mirrorStr$rgbaImgLabel")
                            }
                            stages.add("${rgbaImgLabel}${currentLabel}scale2ref=w=iw*${op.relativeWidth}:h=ih*${op.relativeHeight}${scaledImgLabel}${refVidLabel}")
                        }

                        var currentOverlayLabel = scaledImgLabel
                        if (op.chromaKeyColor != null) {
                            val colorkeyLabel = "[colorkey_$stageIndex]"
                            val color = formatColorForFFmpeg(op.chromaKeyColor)
                            // colorkey=color=0xRRGGBB:similarity=0.1:blend=0.1
                            stages.add("${currentOverlayLabel}colorkey=${color}:${op.chromaKeySimilarity}:0.1${colorkeyLabel}")
                            currentOverlayLabel = colorkeyLabel
                        }

                        if (op.opacityKeyframes.isNotEmpty()) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            val alphaExpr = buildFFmpegInterpolationExpr(op.opacityKeyframes, useValueY = false, defaultValue = op.opacity, startTimeMs = 0L, timeVar = "T")
                            stages.add("${currentOverlayLabel}format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='alpha(X,Y)*($alphaExpr)'${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        } else if (op.opacity < 1.0f) {
                            val opacityLabel = "[opacity_$stageIndex]"
                            stages.add("${currentOverlayLabel}format=rgba,colorchannelmixer=aa=${op.opacity}${opacityLabel}")
                            currentOverlayLabel = opacityLabel
                        }

                        // Rotate image/video overlay (c=none preserves transparent background, ow/oh prevent cropping)
                        stages.add("${currentOverlayLabel}rotate=$radians:c=none:ow='rotw($radians)':oh='roth($radians)'${rotatedImgLabel}")
                        // Overlay image/video on the reference video
                        val enablePart = buildEnableExpr(op.startTimeMs, op.endTimeMs)
                        val shortestPart = if (((isGif || isVideo) && op.isLooping) || (!isGif && !isVideo && op.opacityKeyframes.isNotEmpty())) ":shortest=1" else ""
                        
                        val overlayX = if (op.positionKeyframes.isNotEmpty()) {
                            val xExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = false, defaultValue = op.relativeX, startTimeMs = op.startTimeMs ?: 0L)
                            "x='(W*($xExpr))-(w/2)'"
                        } else {
                            "x='(W*${op.relativeX})-(w/2)'"
                        }
                        val overlayY = if (op.positionKeyframes.isNotEmpty()) {
                            val yExpr = buildFFmpegInterpolationExpr(op.positionKeyframes, useValueY = true, defaultValue = op.relativeY, startTimeMs = op.startTimeMs ?: 0L)
                            "y='(H*($yExpr))-(h/2)'"
                        } else {
                            "y='(H*${op.relativeY})-(h/2)'"
                        }
                        
                        stages.add("${refVidLabel}${rotatedImgLabel}overlay=$overlayX:$overlayY${shortestPart}${enablePart}${nextLabel}")

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

                        val boxPart = ":box=1:boxcolor='0x00000080':boxborderw=8"
                        
                        val filterExpr = "drawtext=${fontPart}text='$escapedText':fontcolor='white':fontsize=${op.fontSize}:${posPart}${boxPart}$enablePart"
                        
                        val nextLabel = "[v$stageIndex]"
                        stages.add("$currentLabel$filterExpr$nextLabel")
                        currentLabel = nextLabel
                        stageIndex++
                    }
                }
                else -> {}
            }
        }

        // Process crop at the end so it crops the video and all overlays together, matching the preview.
        for (op in cropOps) {
            val filterExpr = buildCropFilterExpr(op)
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
                    
                    val isImage = videoPath.endsWith(".png", ignoreCase = true) || 
                                  videoPath.endsWith(".jpg", ignoreCase = true) || 
                                  videoPath.endsWith(".jpeg", ignoreCase = true) ||
                                  videoPath.endsWith(".webp", ignoreCase = true)
                    
                    if (isImage) {
                        cmd.append(" -loop 1 -t $duration -i \"$videoPath\"")
                    } else {
                        cmd.append(" -ss $startSecs -t $duration -i \"$videoPath\"")
                    }
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
            val isVideo = imagePath.endsWith(".mp4", ignoreCase = true) ||
                          imagePath.endsWith(".mkv", ignoreCase = true) ||
                          imagePath.endsWith(".mov", ignoreCase = true) ||
                          imagePath.endsWith(".3gp", ignoreCase = true)
            if ((isGif || isVideo) && imageOp.isLooping) {
                cmd.append(" -stream_loop -1 -i \"$imagePath\"")
            } else {
                cmd.append(" -i \"$imagePath\"")
            }
            imageInputIndices.add(Pair(inputIndex, imageOp))
            inputIndex++
        }

        // 5. Canvas Background Image Input
        val bgOp = operations.filterIsInstance<EditOperation.CanvasBackground>().lastOrNull()
        var bgImageIndex = -1
        if (bgOp != null && bgOp.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgOp.imageUri != null) {
            val bgPath = resolveUriToPath(bgOp.imageUri)
            if (bgPath != null) {
                cmd.append(" -loop 1 -i \"$bgPath\"")
                bgImageIndex = inputIndex
                inputIndex++
            }
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
                    val isImage = path.endsWith(".png", ignoreCase = true) || 
                                  path.endsWith(".jpg", ignoreCase = true) || 
                                  path.endsWith(".jpeg", ignoreCase = true) ||
                                  path.endsWith(".webp", ignoreCase = true)
                    if (isImage) {
                        false
                    } else {
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(path)
                            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                            h == "yes"
                        } finally {
                            r.release()
                        }
                    }
                } catch (e: Exception) { false }
                durationsArray[idx + 1] = item.trimmedDurationMs / 1000.0
            }


            for (i in 0 until inputCount) {
                val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == i }
                val lutFilterExpr = colorFilterOp?.let { getFFmpegFilterForName(it.filterName) }
                val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == i }
                val adjustFilterExpr = adjustOp?.let { getFFmpegFilterForAdjust(it) }

                val isMirrored = if (i == 0) {
                    operations.any { it is EditOperation.MirrorMain && it.isMirrored }
                } else {
                    mergeOp?.items?.getOrNull(i - 1)?.isMirrored == true
                }
                
                // 1. Prepare raw video with basic visual filters
                var preFilters = "setpts=PTS-STARTPTS"
                if (lutFilterExpr != null) preFilters += ",$lutFilterExpr"
                if (adjustFilterExpr != null) preFilters += ",$adjustFilterExpr"
                if (isMirrored) preFilters += ",hflip"
                
                filterParts.add("[$i:v]$preFilters[pre$i]")

                // 2. Apply background/padding
                if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.BLUR) {
                    val blur = bgOp.blurRadius
                    val bgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight,boxblur=$blur"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[pre$i]split=2[bg_orig$i][fg_orig$i]")
                    filterParts.add("[bg_orig$i]$bgScale[bg$i]")
                    filterParts.add("[fg_orig$i]$fgScale[fg$i]")
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2,setsar=1,fps=30,format=yuv420p[norm$i]")
                } else if (bgOp?.type == EditOperation.CanvasBackground.BackgroundType.IMAGE && bgImageIndex != -1) {
                    val bgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=increase,crop=$mainWidth:$mainHeight"
                    val fgScale = "scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease"
                    filterParts.add("[$bgImageIndex:v]$bgScale[bg$i]")
                    filterParts.add("[pre$i]$fgScale[fg$i]")
                    // Use shortest=1 in case the loop goes on forever, but we specified -loop 1 which creates an infinite stream for image
                    filterParts.add("[bg$i][fg$i]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1,fps=30,format=yuv420p[norm$i]")
                } else {
                    // Default COLOR
                    val color = bgOp?.colorHex ?: "black"
                    val padColor = if (color.startsWith("#")) color else "black"
                    filterParts.add("[pre$i]scale=$mainWidth:$mainHeight:force_original_aspect_ratio=decrease,pad=$mainWidth:$mainHeight:(ow-iw)/2:(oh-ih)/2:color=$padColor,setsar=1,fps=30,format=yuv420p[norm$i]")
                }

                val isClipMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == i }?.isMuted ?: false
                if (hasAudioArray[i] && !isClipMuted) {
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
            filterParts.add("${tempVideoLabel}scale=-2:${_exportResolution.value},fps=${_exportFps.value},format=yuv420p$finalVideoLabel")

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
                    if (audioOp.fadeInDurationMs > 0L) {
                        val fadeSec = audioOp.fadeInDurationMs / 1000.0
                        filters.add("afade=t=in:st=0:d=$fadeSec")
                    }
                    if (audioOp.fadeOutDurationMs > 0L) {
                        val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                        val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                            (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                        } else if (audioOp.internalEndMs > 0L) {
                            (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                        } else {
                            (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                        }
                        val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                        filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
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
                    var mainAudioRef = currentAudioLabel
                    val duckingCount = audioInputIndices.count { it.second.ducking }
                    
                    if (duckingCount > 0) {
                        val splits = (0..duckingCount).map { "[main_split_$it]" }
                        filterParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                        mainAudioRef = splits[0]
                        
                        var splitIdx = 1
                        for (i in mixInputLabels.indices.drop(1)) { // skip index 0 which is currentAudioLabel
                            val opIndex = i - 1
                            val op = audioInputIndices[opIndex].second
                            if (op.ducking) {
                                val bgLabel = mixInputLabels[i]
                                val sidechainLabel = splits[splitIdx++]
                                val duckedLabel = "[ducked_$opIndex]"
                                filterParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$opIndex]")
                                filterParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$opIndex]")
                                filterParts.add("[bg_fmt_$opIndex][sc_fmt_$opIndex]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                                mixInputLabels[i] = duckedLabel
                            }
                        }
                    }
                    
                    mixInputLabels[0] = mainAudioRef
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

            if (_exportAudioOnly.value) {
                filterParts.add("${finalVideoLabel}nullsink")
            }
            cmd.append(" -filter_complex \"${filterParts.joinToString(";")}\"")
            
            if (_exportAudioOnly.value) {
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                    // LibreCuts ffmpeg might have libmp3lame, fallback to libmp3lame or default mp3 encoder
                    cmd.append(" -c:a libmp3lame -b:a 192k")
                } else {
                    cmd.append(" -an")
                }
            } else {
                cmd.append(" -map \"$finalVideoLabel\"")
                if (!audioMuted) {
                    cmd.append(" -map \"$currentAudioLabel\"")
                } else {
                    cmd.append(" -an")
                }
                
                val bitrate = when (_exportResolution.value) {
                    2160 -> "30M"
                    1440 -> "16M"
                    1080 -> "8M"
                    720 -> "5M"
                    480 -> "2M"
                    else -> "1M"
                }
                cmd.append(" -c:v h264_mediacodec -b:v $bitrate")
                
                if (!audioMuted) {
                    cmd.append(" -c:a aac")
                }
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
        var currentInputVideoLabel = "[0:v]"

        val colorFilterOp = operations.filterIsInstance<EditOperation.ColorFilter>().find { it.index == 0 }
        val adjustOp = operations.filterIsInstance<EditOperation.Adjust>().find { it.index == 0 }

        val prepFilters = mutableListOf<String>()
        if (colorFilterOp != null) {
            val lutFilterExpr = getFFmpegFilterForName(colorFilterOp.filterName)
            if (lutFilterExpr != null) {
                prepFilters.add(lutFilterExpr)
            }
        }
        if (adjustOp != null) {
            val adjustFilterExpr = getFFmpegFilterForAdjust(adjustOp)
            if (adjustFilterExpr != null) {
                prepFilters.add(adjustFilterExpr)
            }
        }
        val mirrorMainOp = operations.filterIsInstance<EditOperation.MirrorMain>().find { it.isMirrored }
        if (mirrorMainOp != null) {
            prepFilters.add("hflip")
        }

        if (prepFilters.isNotEmpty()) {
            val combinedFilters = prepFilters.joinToString(",")
            filterComplexParts.add("[0:v]${combinedFilters}[cfv]")
            currentInputVideoLabel = "[cfv]"
        }

        // ── Video filter stages (crop, text, image overlays) ──
        val (videoStages, finalVideoLabel) = buildVideoFilterStages(
            operations = videoOps,
            fontFilePath = fontFilePath,
            inputLabel = currentInputVideoLabel,
            imageInputIndices = imageInputIndices,
            density = density
        )
        filterComplexParts.addAll(videoStages)

        // ── Audio filter stages (multi-track adelay + volume mixing) ──
        var finalAudioLabel: String? = null
        val mainVideoMuted = operations.filterIsInstance<EditOperation.MuteClip>().find { it.index == 0 }?.isMuted ?: false
        val effectiveAudioMuted = audioMuted || (mainVideoMuted && audioInputIndices.isEmpty())

        if (effectiveAudioMuted) {
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
                if (audioOp.fadeInDurationMs > 0L) {
                    val fadeSec = audioOp.fadeInDurationMs / 1000.0
                    filters.add("afade=t=in:st=0:d=$fadeSec")
                }
                if (audioOp.fadeOutDurationMs > 0L) {
                    val fadeSec = audioOp.fadeOutDurationMs / 1000.0
                    val clipDur = if (audioOp.endTimeMs != null && audioOp.startTimeMs != null) {
                        (audioOp.endTimeMs - audioOp.startTimeMs) / 1000.0
                    } else if (audioOp.internalEndMs > 0L) {
                        (audioOp.internalEndMs - audioOp.internalStartMs) / 1000.0
                    } else {
                        (audioOp.originalDurationMs - audioOp.internalStartMs) / 1000.0
                    }
                    val fadeOutStart = maxOf(0.0, clipDur - fadeSec)
                    filters.add("afade=t=out:st=$fadeOutStart:d=$fadeSec")
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
            if (hasAudio && !mainVideoMuted && !audioInputIndices.any { it.second.removeOriginalAudio }) {
                var mainAudioRef = "[0:a]"
                val duckingCount = audioInputIndices.count { it.second.ducking }
                
                if (duckingCount > 0) {
                    val splits = (0..duckingCount).map { "[main_split_$it]" }
                    filterComplexParts.add("${mainAudioRef}asplit=${duckingCount + 1}${splits.joinToString("")}")
                    mainAudioRef = splits[0]
                    
                    var splitIdx = 1
                    for (i in mixInputLabels.indices) {
                        val op = audioInputIndices[i].second
                        if (op.ducking) {
                            val bgLabel = mixInputLabels[i]
                            val sidechainLabel = splits[splitIdx++]
                            val duckedLabel = "[ducked_$i]"
                            filterComplexParts.add("${bgLabel}aformat=sample_rates=44100:channel_layouts=stereo[bg_fmt_$i]")
                            filterComplexParts.add("${sidechainLabel}aformat=sample_rates=44100:channel_layouts=stereo[sc_fmt_$i]")
                            filterComplexParts.add("[bg_fmt_$i][sc_fmt_$i]sidechaincompress=threshold=0.03:ratio=4:attack=5:release=500${duckedLabel}")
                            mixInputLabels[i] = duckedLabel
                        }
                    }
                }

                val allInputs = mainAudioRef + mixInputLabels.joinToString("")
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
        val hasVideoFilters = videoStages.isNotEmpty() || prepFilters.isNotEmpty()
        val hasAudioFilters = finalAudioLabel != null

        if (hasVideoFilters || hasAudioFilters) {
            var mappedVideoLabel = finalVideoLabel
            if (hasVideoFilters) {
                val fmtLabel = "[fmtv]"
                filterComplexParts.add("${finalVideoLabel}scale=-2:${_exportResolution.value},fps=${_exportFps.value},format=yuv420p${fmtLabel}")
                mappedVideoLabel = fmtLabel
            }
            
            if (_exportAudioOnly.value && hasVideoFilters) {
                filterComplexParts.add("${mappedVideoLabel}nullsink")
            }
            
            cmd.append(" -filter_complex \"${filterComplexParts.joinToString(";")}\"")

            if (!_exportAudioOnly.value) {
                if (hasVideoFilters) {
                    cmd.append(" -map \"$mappedVideoLabel\"")
                } else {
                    cmd.append(" -map 0:v")
                }
            }

            if (effectiveAudioMuted) {
                cmd.append(" -an")
            } else if (hasAudioFilters) {
                cmd.append(" -map \"$finalAudioLabel\"")
            } else {
                cmd.append(" -map 0:a?")
            }
        } else {
            if (_exportAudioOnly.value) {
                cmd.append(" -vn")
                if (!effectiveAudioMuted) {
                    cmd.append(" -map 0:a?")
                }
            }
            if (effectiveAudioMuted) {
                cmd.append(" -an")
            }
        }

        // Codecs
        if (_exportAudioOnly.value) {
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a libmp3lame -b:a 192k")
            }
        } else {
            val bitrate = when (_exportResolution.value) {
                2160 -> "30M"
                1440 -> "16M"
                1080 -> "8M"
                720 -> "5M"
                480 -> "2M"
                else -> "1M"
            }
            // If there are no video filters, we still need to apply the scale and fps output params
            if (!hasVideoFilters) {
                cmd.append(" -c:v h264_mediacodec -b:v $bitrate -vf scale=-2:${_exportResolution.value} -r ${_exportFps.value}")
            } else {
                cmd.append(" -c:v h264_mediacodec -b:v $bitrate")
            }
            
            if (!effectiveAudioMuted) {
                cmd.append(" -c:a aac")
            }
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