package com.tharunbirla.librecuts.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object AppEventManager {
    private val _events = MutableSharedFlow<AppEvents>()
    val events = _events.asSharedFlow()

    suspend fun sendEvent(message: AppEvents) {
        _events.emit(message)
    }

    sealed class AppEvents {
        data class ExportProgress(val progress: Int) : AppEvents()
        data class ExportSuccess(val savedUri: String) : AppEvents()
        data class ExportFailure(val error: String) : AppEvents()
        data class ProxyGenerated(val sourceUri: String, val proxyUri : String, val dependencyId: String) : AppEvents()
    }

}