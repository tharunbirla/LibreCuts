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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class VideoEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _projectState = MutableStateFlow(ProjectState())
    val projectState: StateFlow<ProjectState> = _projectState.asStateFlow()

    // Flow to expose active text overlays based on current playback time
    // Note: In a real app, we'd pass the player's current position to this flow,
    // but here we might need the View to pull it or push time updates to VM.
    // For simplicity, we'll let the View observe the full list and decide visibility.
    val textTracks = _projectState.map { state ->
        state.tracks.filter { it.trackType == MediaType.TEXT }
    }

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

    fun addClip(uri: Uri, type: MediaType, durationMs: Long) {
        _projectState.update { state ->
            val newClip = Clip(
                uri = uri,
                mediaType = type,
                durationMs = durationMs
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

    fun addTextClip(text: String, durationMs: Long = 5000) {
        _projectState.update { state ->
            val newClip = Clip(
                uri = Uri.EMPTY, // No file for text
                mediaType = MediaType.TEXT,
                durationMs = durationMs,
                text = text
            )

            val updatedTracks = state.tracks.map { track ->
                if (track.trackType == MediaType.TEXT) {
                    track.copy(clips = track.clips + newClip)
                } else {
                    track
                }
            }
            state.copy(tracks = updatedTracks)
        }
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

    fun removeClip(clipId: String) {
        _projectState.update { state ->
            val updatedTracks = state.tracks.map { track ->
                track.copy(clips = track.clips.filter { it.id != clipId })
            }
            state.copy(tracks = updatedTracks)
        }
        recalculateDuration()
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
        val mainTrack = _projectState.value.tracks.find { it.trackType == MediaType.VIDEO }
        val duration = mainTrack?.clips?.sumOf { it.durationMs } ?: 0L
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

        // 2. Build Audio Source (Concatenated or Merged?)
        // The requirement is "Audio tracks above/below main video track".
        // This usually implies a separate timeline.
        // For simplicity, we will assume audio clips are sequential on their own track, starting at t=0
        // To support precise timing (gaps), we'd need SilenceMediaSource, but let's stick to basic "magnetic" (no gaps) for now.
        if (audioTrack != null && audioTrack.clips.isNotEmpty()) {
            val audioSources = audioTrack.clips.mapNotNull { clip -> createMediaSource(clip) }
            if (audioSources.isNotEmpty()) {
                sourcesToMerge.add(ConcatenatingMediaSource(*audioSources.toTypedArray()))
            }
        }

        if (sourcesToMerge.isEmpty()) return null

        // If we have both video and audio tracks, merge them to play simultaneously
        return if (sourcesToMerge.size == 1) {
            sourcesToMerge[0]
        } else {
            MergingMediaSource(*sourcesToMerge.toTypedArray())
        }
    }

    private fun createMediaSource(clip: Clip): MediaSource? {
        if (clip.uri == Uri.EMPTY) return null // Skip text clips or invalid URIs in this player

        val mediaItem = MediaItem.fromUri(clip.uri)
        val source = ProgressiveMediaSource.Factory(dataSourceFactory)
            .createMediaSource(mediaItem)

        return if (clip.startOffsetMs > 0 || clip.durationMs > 0) {
             val startUs = clip.startOffsetMs * 1000
             val endUs = (clip.startOffsetMs + clip.durationMs) * 1000
             // Verify endUs is valid (greater than startUs)
             if (endUs > startUs) {
                 ClippingMediaSource(source, startUs, endUs)
             } else {
                 source
             }
        } else {
            source
        }
    }

    fun getProjectState() = _projectState.value
}
