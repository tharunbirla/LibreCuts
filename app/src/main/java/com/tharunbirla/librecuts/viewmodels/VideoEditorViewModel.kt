package com.tharunbirla.librecuts.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ClippingMediaSource
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.MediaType
import com.tharunbirla.librecuts.models.ProjectState
import com.tharunbirla.librecuts.models.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _projectState = MutableStateFlow(ProjectState())
    val projectState: StateFlow<ProjectState> = _projectState.asStateFlow()

    private val dataSourceFactory = DefaultDataSource.Factory(application)

    init {
        // Initialize with default tracks
        _projectState.update {
            it.copy(
                tracks = listOf(
                    Track(trackType = MediaType.VIDEO), // Main video track
                    Track(trackType = MediaType.AUDIO), // Audio track
                    Track(trackType = MediaType.TEXT)   // Text track
                )
            )
        }
    }

    fun addClip(uri: Uri, type: MediaType, durationMs: Long, text: String? = null) {
        _projectState.update { state ->
            val newClip = Clip(
                uri = uri,
                mediaType = type,
                durationMs = durationMs,
                text = text
            )

            val updatedTracks = state.tracks.map { track ->
                if (track.trackType == type) {
                    track.copy(clips = track.clips + newClip)
                } else {
                    track
                }
            }

            state.copy(tracks = updatedTracks)
        }
        recalculateDuration()
    }

    fun removeClip(clipId: String) {
        _projectState.update { state ->
            val updatedTracks = state.tracks.map { track ->
                track.copy(clips = track.clips.filter { it.id != clipId })
            }
            state.copy(tracks = updatedTracks)
        }
        recalculateDuration()
    }

    fun moveClip(trackType: MediaType, fromIndex: Int, toIndex: Int) {
         _projectState.update { state ->
            val updatedTracks = state.tracks.map { track ->
                if (track.trackType == trackType) {
                    val clips = track.clips.toMutableList()
                    if (fromIndex in clips.indices && toIndex in clips.indices) {
                        val clip = clips.removeAt(fromIndex)
                        clips.add(toIndex, clip)
                        track.copy(clips = clips)
                    } else {
                        track
                    }
                } else {
                    track
                }
            }
            state.copy(tracks = updatedTracks)
        }
    }

    fun updateClipTrim(clipId: String, startOffset: Long, endOffset: Long) {
        _projectState.update { state ->
            val updatedTracks = state.tracks.map { track ->
                track.copy(clips = track.clips.map { clip ->
                    if (clip.id == clipId) {
                         val newDuration = endOffset - startOffset
                         clip.copy(startOffsetMs = startOffset, durationMs = newDuration)
                    } else {
                        clip
                    }
                })
            }
            state.copy(tracks = updatedTracks)
        }
        recalculateDuration()
    }

    private fun recalculateDuration() {
        // Duration is determined by the longest track, usually Video
        val videoTrack = _projectState.value.tracks.find { it.trackType == MediaType.VIDEO }
        val duration = videoTrack?.clips?.sumOf { it.durationMs } ?: 0L
        _projectState.update { it.copy(totalDurationMs = duration) }
    }

    // Build ExoPlayer MediaSource from current state
    fun buildMediaSource(): MediaSource? {
        val tracks = _projectState.value.tracks
        val videoTrack = tracks.find { it.trackType == MediaType.VIDEO }
        val audioTrack = tracks.find { it.trackType == MediaType.AUDIO }

        val sourcesToMerge = mutableListOf<MediaSource>()

        // 1. Build Video Source (Concatenated)
        if (videoTrack != null && videoTrack.clips.isNotEmpty()) {
            val videoSources = videoTrack.clips.mapNotNull { clip -> createMediaSource(clip) }
            if (videoSources.isNotEmpty()) {
                sourcesToMerge.add(ConcatenatingMediaSource(*videoSources.toTypedArray()))
            }
        }

        // 2. Build Audio Source
        if (audioTrack != null && audioTrack.clips.isNotEmpty()) {
            val audioSources = audioTrack.clips.mapNotNull { clip -> createMediaSource(clip) }
            if (audioSources.isNotEmpty()) {
                sourcesToMerge.add(ConcatenatingMediaSource(*audioSources.toTypedArray()))
            }
        }

        if (sourcesToMerge.isEmpty()) return null

        return if (sourcesToMerge.size == 1) {
            sourcesToMerge[0]
        } else {
            MergingMediaSource(*sourcesToMerge.toTypedArray())
        }
    }

    private fun createMediaSource(clip: Clip): MediaSource? {
        if (clip.uri == Uri.EMPTY) return null

        val mediaItem = MediaItem.fromUri(clip.uri)
        val source = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        return if (clip.startOffsetMs > 0 || clip.durationMs > 0) {
             val startUs = clip.startOffsetMs * 1000
             val endUs = (clip.startOffsetMs + clip.durationMs) * 1000
             if (endUs > startUs) {
                 ClippingMediaSource(source, startUs, endUs)
             } else {
                 source
             }
        } else {
            source
        }
    }
}
