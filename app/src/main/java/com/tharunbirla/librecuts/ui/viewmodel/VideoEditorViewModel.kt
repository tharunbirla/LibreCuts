package com.tharunbirla.librecuts.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.tharunbirla.librecuts.data.model.Clip
import com.tharunbirla.librecuts.data.repository.VideoRepository
import kotlinx.coroutines.launch
import java.util.UUID

class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = VideoRepository(application)

    private val _clips = MutableLiveData<List<Clip>>(emptyList())
    val clips: LiveData<List<Clip>> = _clips

    private val _currentTimeMs = MutableLiveData<Long>(0L)
    val currentTimeMs: LiveData<Long> = _currentTimeMs

    private val _isPlaying = MutableLiveData<Boolean>(false)
    val isPlaying: LiveData<Boolean> = _isPlaying

    private val _totalDurationMs = MutableLiveData<Long>(0L)
    val totalDurationMs: LiveData<Long> = _totalDurationMs

    fun addVideo(uri: Uri) {
        viewModelScope.launch {
            val path = repository.getRealFilePath(uri)
            if (path != null) {
                val duration = repository.getVideoDuration(uri)
                val newClip = Clip(
                    id = UUID.randomUUID().toString(),
                    uri = uri,
                    filePath = path,
                    durationMs = duration,
                    startTimeMs = _totalDurationMs.value ?: 0L,
                    trimEndMs = duration
                )

                val currentList = _clips.value?.toMutableList() ?: mutableListOf()
                currentList.add(newClip)
                _clips.value = currentList

                recalculateTimeline()
            }
        }
    }

    private fun recalculateTimeline() {
        var runningTime = 0L
        val currentList = _clips.value ?: return

        currentList.forEach { clip ->
            clip.startTimeMs = runningTime
            runningTime += clip.playbackDuration
        }
        _totalDurationMs.value = runningTime
    }

    fun updateCurrentTime(timeMs: Long) {
        _currentTimeMs.value = timeMs
    }

    fun setPlaying(playing: Boolean) {
        _isPlaying.value = playing
    }
}
