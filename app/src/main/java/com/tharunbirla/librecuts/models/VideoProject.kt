package com.tharunbirla.librecuts.models

import android.net.Uri
import java.io.Serializable

/**
 * VideoProject represents the complete state of a video editing session.
 * It stores all pending operations without rendering them to disk.
 * The original source video remains untouched; all edits are stored as operation objects.
 */
data class VideoProject(
    val sourceUri: Uri,
    val sourceName: String,
    val operations: List<EditOperation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis()
) : Serializable {
    
    /**
     * Returns the total video duration that would be rendered.
     * This is computed during the final export and is not stored.
     */
    fun getDurationAfterTrims(): Long? {
        // This will be computed when building the final FFmpeg command
        var effectiveDuration: Long? = null
        
        for (operation in operations) {
            if (operation is EditOperation.Trim) {
                effectiveDuration = operation.endMs - operation.startMs
                // Note: only the last trim is considered; in a real scenario,
                // you'd chain them if multiple trims exist
            }
        }
        
        return effectiveDuration
    }
    
    /**
     * Returns a copy of this project with a new operation added.
     */
    fun addOperation(operation: EditOperation): VideoProject {
        return copy(
            operations = operations + operation,
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns a copy of this project with the last operation removed.
     */
    fun undoLastOperation(): VideoProject? {
        if (operations.isEmpty()) return null
        return copy(
            operations = operations.dropLast(1),
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns a copy of this project with all operations of a given type removed.
     */
    fun removeOperationsOfType(operationType: Class<out EditOperation>): VideoProject {
        return copy(
            operations = operations.filterNot { it::class.java == operationType },
            lastModifiedAt = System.currentTimeMillis()
        )
    }
    
    /**
     * Returns true if this project has any pending operations.
     */
    fun hasOperations(): Boolean = operations.isNotEmpty()
    
    /**
     * Returns the count of pending operations.
     */
    fun getOperationCount(): Int = operations.size
    
    /**
     * Returns the count of a specific operation type.
     */
    fun getOperationCount(operationType: Class<out EditOperation>): Int {
        return operations.count { it::class.java == operationType }
    }
}

/**
 * EditRecipe represents a serializable, persistent version of a VideoProject.
 * Can be saved to disk as JSON and restored later, enabling "Save Project" functionality.
 */
data class EditRecipe(
    val projectName: String,
    val sourceUri: Uri,
    val sourceName: String,
    val operations: List<EditOperation> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastModifiedAt: Long = System.currentTimeMillis()
) : Serializable {
    
    /**
     * Converts this EditRecipe back to a VideoProject for editing.
     */
    fun toVideoProject(): VideoProject {
        return VideoProject(
            sourceUri = sourceUri,
            sourceName = sourceName,
            operations = operations,
            createdAt = createdAt,
            lastModifiedAt = lastModifiedAt
        )
    }
    
    companion object {
        /**
         * Create an EditRecipe from a VideoProject.
         */
        fun fromVideoProject(projectName: String, project: VideoProject): EditRecipe {
            return EditRecipe(
                projectName = projectName,
                sourceUri = project.sourceUri,
                sourceName = project.sourceName,
                operations = project.operations,
                createdAt = project.createdAt,
                lastModifiedAt = project.lastModifiedAt
            )
        }
    }
}

/**
 * Represents the UI state for the video editing session.
 * Separates state management concerns from business logic.
 */
data class VideoEditingUiState(
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Int = 0, // 0-100
    val errorMessage: String? = null,
    val pendingOperationCount: Int = 0,
    val currentPreviewOperationIndex: Int = -1, // -1 means show original, 0+ means after Nth operation
    val canUndo: Boolean = false,
    val canRedo: Boolean = false // Future enhancement
)

/**
 * Represents export/rendering configuration options.
 */
data class ExportConfig(
    val outputPath: String,
    val videoCodec: String = "libx264", // or "copy" for no re-encoding
    val audioCodec: String = "aac",
    val bitrate: String = "2M",
    val preset: String = "medium", // fast, medium, slow for quality/speed tradeoff
    val frameRate: String = "30" // Can be extracted from source if needed
)
