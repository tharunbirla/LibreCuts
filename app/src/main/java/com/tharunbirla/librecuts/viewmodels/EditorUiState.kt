package com.tharunbirla.librecuts.viewmodels

data class EditorUiState(
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Int = 0,
    val errorMessage: String? = null,
    val selectedOperationId: String? = null,
    val pendingOperationCount: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false
)
