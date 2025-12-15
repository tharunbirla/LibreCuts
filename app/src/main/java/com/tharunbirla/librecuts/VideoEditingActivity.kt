package com.tharunbirla.librecuts

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.airbnb.lottie.LottieAnimationView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.tharunbirla.librecuts.ui.timeline.TimelineView
import com.tharunbirla.librecuts.ui.viewmodel.VideoEditorViewModel
import java.util.Locale

class VideoEditingActivity : AppCompatActivity() {

    private val viewModel: VideoEditorViewModel by viewModels()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var timelineView: TimelineView
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView

    private var isUserSeeking = false
    private val updateRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying && !isUserSeeking) {
                val currentPos = player.currentPosition
                viewModel.updateCurrentTime(currentPos)
                timelineView.scrollToTime(currentPos)
            }
            playerView.postDelayed(this, 30)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)

        // Window flags
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        initializeViews()
        setupExoPlayer()
        setupTimeline()
        observeViewModel()

        // Load initial video
        val videoUri = intent.getParcelableExtra<Uri>("VIDEO_URI")
        if (videoUri != null) {
            viewModel.addVideo(videoUri)
        }
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        timelineView = findViewById(R.id.timelineView)
        loadingScreen = findViewById(R.id.loadingScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        // Other buttons can be hooked up to ViewModel commands
    }

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player
        player.playWhenReady = false

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
               if (state == Player.STATE_ENDED) {
                   viewModel.setPlaying(false)
               }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                viewModel.setPlaying(isPlaying)
            }
        })

        // Start sync loop
        playerView.post(updateRunnable)
    }

    private fun setupTimeline() {
        timelineView.onTimeChangedListener = { timeMs ->
            isUserSeeking = true
            viewModel.updateCurrentTime(timeMs)
            player.seekTo(timeMs)
            isUserSeeking = false // Reset after seek
        }
    }

    private fun observeViewModel() {
        viewModel.clips.observe(this) { clips ->
            if (clips.isNotEmpty()) {
                // For now, just play the first clip or handle multiple clips logic later
                val firstClip = clips[0]
                if (player.mediaItemCount == 0) {
                     val mediaItem = MediaItem.fromUri(firstClip.uri)
                     player.setMediaItem(mediaItem)
                     player.prepare()
                }
                timelineView.setClips(clips)
            }
        }

        viewModel.currentTimeMs.observe(this) { timeMs ->
             val total = viewModel.totalDurationMs.value ?: 0L
             tvDuration.text = formatDuration(timeMs, total)
        }
    }

    private fun formatDuration(current: Long, total: Long): String {
        val currentStr = String.format(Locale.getDefault(), "%02d:%02d", (current / 60000), (current % 60000) / 1000)
        val totalStr = String.format(Locale.getDefault(), "%02d:%02d", (total / 60000), (total % 60000) / 1000)
        return "$currentStr / $totalStr"
    }

    override fun onDestroy() {
        super.onDestroy()
        playerView.removeCallbacks(updateRunnable)
        player.release()
    }
}
