package com.tharunbirla.librecuts.viewmodels

import android.net.Uri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.tharunbirla.librecuts.models.AudioTrack
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.CropRect
import com.tharunbirla.librecuts.models.ProjectState
import com.tharunbirla.librecuts.models.TextTrack
import java.util.UUID

class VideoEditorViewModel : ViewModel() {

    private val _projectState = MutableLiveData<ProjectState>()
    val projectState: LiveData<ProjectState> = _projectState

    private val currentState: ProjectState
        get() = _projectState.value ?: ProjectState()

    init {
        _projectState.value = ProjectState()
    }

    fun addVideoClip(uri: Uri, durationMs: Long) {
        val newState = currentState
        val clip = Clip(
            id = UUID.randomUUID().toString(),
            sourceUri = uri,
            startTimeMs = 0,
            endTimeMs = durationMs
        )
        newState.videoTrack.add(clip)
        _projectState.value = newState
    }

    fun updateClipTrim(clipIndex: Int, startMs: Long, endMs: Long) {
        val newState = currentState
        if (clipIndex in newState.videoTrack.indices) {
            val clip = newState.videoTrack[clipIndex]
            clip.startTimeMs = startMs
            clip.endTimeMs = endMs
            _projectState.value = newState // Trigger update
        }
    }

    fun updateClipCrop(clipIndex: Int, aspectRatio: String) {
        val newState = currentState
        if (clipIndex in newState.videoTrack.indices) {
            val clip = newState.videoTrack[clipIndex]
            // For now we just store the aspect ratio.
            // In a real implementation we might calculate actual rect based on video dimensions,
            // but for TextureView matrix preview, the aspect ratio string is enough to calculate scales.
            clip.cropRect = CropRect(0f, 0f, 1f, 1f, aspectRatio)
            _projectState.value = newState
        }
    }

    fun addTextTrack(text: String, fontSize: Int, position: String, durationMs: Long) {
        val newState = currentState
        val textTrack = TextTrack(
            id = UUID.randomUUID().toString(),
            text = text,
            fontSize = fontSize,
            position = position,
            startTimeMs = 0,
            durationMs = durationMs
        )
        newState.textTracks.add(textTrack)
        _projectState.value = newState
    }

    // Placeholder for audio track
    fun addAudioTrack(uri: Uri, durationMs: Long) {
         val newState = currentState
        val audioTrack = AudioTrack(
            id = UUID.randomUUID().toString(),
            sourceUri = uri,
            startTimeMs = 0,
            durationMs = durationMs
        )
        newState.audioTracks.add(audioTrack)
        _projectState.value = newState
    }
}
