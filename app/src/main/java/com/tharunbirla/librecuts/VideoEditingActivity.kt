package com.tharunbirla.librecuts

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import com.tharunbirla.librecuts.models.MediaType
import com.tharunbirla.librecuts.ui.timeline.TimelineScreen
import com.tharunbirla.librecuts.viewmodels.VideoEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale


@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    private val viewModel: VideoEditorViewModel by viewModels()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    // private lateinit var frameRecyclerView: RecyclerView // Removed
    // private lateinit var customVideoSeeker: CustomVideoSeeker // Kept in layout but might need adjustment
    private var videoUri: Uri? = null
    // private var videoFileName: String = "" // Not used in new logic yet
    // private lateinit var tempInputFile: File // Not used in new logic yet
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView
    private lateinit var timelineComposeView: ComposeView

    private var activeFFmpegSessions = mutableListOf<FFmpegSession>()
    private var isVideoLoaded = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)

        // Set fullscreen flags
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Set loading and animation view
        loadingScreen = findViewById(R.id.loadingScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)
        try {
            lottieAnimationView.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Error loading Lottie animation: ${e.message}")
        }

        // Initialize UI components and setup the player
        initializeViews()
        setupExoPlayer()
        // setupCustomSeeker() // TODO: Re-integrate seeker
        setupTimelineUI()

        // Initial Video Load
        videoUri = intent.getParcelableExtra("VIDEO_URI")
        if (videoUri != null) {
            // We need to get duration before adding to VM
             lifecycleScope.launch {
                 val duration = getVideoDuration(videoUri!!)
                 viewModel.addClip(videoUri!!, MediaType.VIDEO, duration)
             }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.projectState.collectLatest { state ->
                        refreshPlayerSource()
                    }
                }
                launch {
                    viewModel.textTracks.collectLatest { textTracks ->
                        updateTextOverlays(textTracks)
                    }
                }
            }
        }
    }

    private fun updateTextOverlays(textTracks: List<com.tharunbirla.librecuts.models.Track>) {
        // Find the textConfigs layout
        val textContainer = findViewById<android.widget.LinearLayout>(R.id.textConfigs)
        textContainer.removeAllViews()

        // Iterate through all text tracks and their clips
        textTracks.forEach { track ->
            track.clips.forEach { clip ->
                if (clip.text != null) {
                    val textView = TextView(this)
                    textView.text = clip.text
                    textView.textSize = 24f // Default size, should come from clip
                    textView.setTextColor(android.graphics.Color.WHITE)

                    // Simple logic to show all texts. In reality, we need to check current player time.
                    // Since this function is called on state change, not every frame,
                    // we need a separate update loop for visibility based on time.
                    // For now, let's just add them but set visibility to GONE,
                    // and use the player listener to toggle them.

                    textView.tag = clip // Store clip in tag
                    textView.visibility = View.INVISIBLE
                    textContainer.addView(textView)
                }
            }
        }
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        timelineComposeView = findViewById(R.id.timelineComposeView)
        // customVideoSeeker = findViewById(R.id.customVideoSeeker)

        // Set up button click listeners
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed()}
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveAction() }
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener { trimAction() }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { textAction() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { audioAction() }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener { cropAction() }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener { mergeAction() }
    }

    private fun setupTimelineUI() {
        timelineComposeView.setContent {
            TimelineScreen(viewModel = viewModel)
        }
    }


    private fun refreshPlayerSource() {
        val mediaSource = viewModel.buildMediaSource()
        if (mediaSource != null) {
            val currentPos = player.currentPosition
            player.setMediaSource(mediaSource)
            player.prepare()
            if (currentPos > 0) {
                 player.seekTo(currentPos)
            }
        }
    }

    private fun mergeAction() {
        openFilePickerMerge()
    }

    private fun openFilePickerMerge() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Video"), PICK_VIDEO_REQUEST)
    }

    @Deprecated("This method has been deprecated in favor of using the Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (resultCode == Activity.RESULT_OK && data != null) {
            when (requestCode) {
                PICK_VIDEO_REQUEST -> {
                    val selectedVideoUris = mutableListOf<Uri>()
                    if (data.clipData != null) {
                        val itemCount = data.clipData!!.itemCount
                        for (i in 0 until itemCount) {
                            selectedVideoUris.add(data.clipData!!.getItemAt(i).uri)
                        }
                    } else {
                        data.data?.let { selectedUri -> selectedVideoUris.add(selectedUri) }
                    }
                    lifecycleScope.launch {
                        selectedVideoUris.forEach { uri ->
                            val duration = getVideoDuration(uri)
                            viewModel.addClip(uri, MediaType.VIDEO, duration)
                        }
                    }
                }
                PICK_AUDIO_REQUEST -> {
                    data.data?.let { uri ->
                         lifecycleScope.launch {
                             val duration = getVideoDuration(uri) // Works for audio too
                             viewModel.addClip(uri, MediaType.AUDIO, duration)
                         }
                    }
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun cropAction() {
        // Create BottomSheetDialog
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)

        // Set title
        sheetView.findViewById<TextView>(R.id.tvTitleCrop).text =
            getString(R.string.select_aspect_ratio)

        // Set button click listeners for aspect ratios
        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio1).setOnClickListener {
            cropVideo("16:9")
            bottomSheetDialog.dismiss()
        }

        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio2).setOnClickListener {
            cropVideo("9:16")
            bottomSheetDialog.dismiss()
        }

        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio3).setOnClickListener {
            cropVideo("1:1")
            bottomSheetDialog.dismiss()
        }

        // Set cancel button listener
        sheetView.findViewById<Button>(R.id.btnCancelCrop).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun cropVideo(aspectRatio: String) {
        // Apply crop to the PlayerView (Preview only)
        // In a real export, this would be an FFmpeg filter.

        // Exoplayer's TextureView can be scaled.
        // Or we can use a Transformation.
        // Simple approach: Adjust the AspectRatioFrameLayout's resize mode.

        when (aspectRatio) {
            "16:9" -> {
                 // Force 16:9
                 // playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                 // This is just fitting content.
                 // Real cropping requires Zooming into the video.

                 // We can emulate crop by scaling the video view.
                 playerView.videoSurfaceView?.let { surfaceView ->
                     if (surfaceView is android.view.TextureView) {
                         val viewWidth = surfaceView.width.toFloat()
                         val viewHeight = surfaceView.height.toFloat()
                         val matrix = android.graphics.Matrix()

                         // Scale to simulate 16:9 crop (zoom in)
                         // This is a naive implementation, assuming centered crop.
                         // Scale X/Y to fill the 16:9 box
                         matrix.setScale(1.5f, 1.5f, viewWidth / 2, viewHeight / 2)

                         surfaceView.setTransform(matrix)
                     }
                 }
                 Toast.makeText(this, "Preview: Crop $aspectRatio applied", Toast.LENGTH_SHORT).show()
            }
            "9:16" -> {
                 playerView.videoSurfaceView?.let { surfaceView ->
                     if (surfaceView is android.view.TextureView) {
                         val viewWidth = surfaceView.width.toFloat()
                         val viewHeight = surfaceView.height.toFloat()
                         val matrix = android.graphics.Matrix()
                         matrix.setScale(1.5f, 1.5f, viewWidth / 2, viewHeight / 2)
                         surfaceView.setTransform(matrix)
                     }
                 }
                 Toast.makeText(this, "Preview: Crop $aspectRatio applied", Toast.LENGTH_SHORT).show()
            }
            "1:1" -> {
                 playerView.videoSurfaceView?.let { surfaceView ->
                     if (surfaceView is android.view.TextureView) {
                         val viewWidth = surfaceView.width.toFloat()
                         val viewHeight = surfaceView.height.toFloat()
                         val matrix = android.graphics.Matrix()
                         matrix.setScale(1.2f, 1.2f, viewWidth / 2, viewHeight / 2)
                         surfaceView.setTransform(matrix)
                     }
                 }
                 Toast.makeText(this, "Preview: Crop $aspectRatio applied", Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Reset
                playerView.videoSurfaceView?.let { surfaceView ->
                     if (surfaceView is android.view.TextureView) {
                         val matrix = android.graphics.Matrix()
                         surfaceView.setTransform(matrix)
                     }
                 }
                 Toast.makeText(this, "Crop Reset", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun audioAction() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Audio"), PICK_AUDIO_REQUEST)
    }

    private fun textAction() {
        // Create the BottomSheetDialog
        val bottomSheetDialog = BottomSheetDialog(this)

        // Inflate the layout for the bottom sheet
        val view = layoutInflater.inflate(R.layout.text_bottom_sheet_dialog, null)

        val etTextInput = view.findViewById<TextInputEditText>(R.id.etTextInput)
        val fontSizeInput = view.findViewById<TextInputEditText>(R.id.fontSize)
        val spinnerTextPosition = view.findViewById<Spinner>(R.id.spinnerTextPosition)
        val btnDone = view.findViewById<Button>(R.id.btnDoneText)

        val positionOptions = arrayOf(
            "Bottom Right",
            "Top Right",
            "Top Left",
            "Bottom Left",
            "Center Bottom",
            "Center Top",
            "Center Align"
        )

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positionOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTextPosition.adapter = adapter

        btnDone.setOnClickListener {
            val text = etTextInput.text.toString()
            // val fontSize = fontSizeInput.text.toString().toIntOrNull() ?: 16
            // val textPosition = spinnerTextPosition.selectedItem.toString()

            viewModel.addTextClip(text)

            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        val videoDuration = player.duration

        if (videoDuration <= 0) {
            Toast.makeText(this, "Video duration is invalid.", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(this@VideoEditingActivity)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)

        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        val durationInMillis: Long = videoDuration
        val totalMinutes = (durationInMillis / 60000).toInt()
        val totalSeconds = ((durationInMillis % 60000) / 1000).toInt()

        val formattedValueTo = (totalMinutes * 60 + totalSeconds).toFloat()

        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = formattedValueTo
        rangeSlider.values = listOf(0f, formattedValueTo)

        rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val start = slider.values[0].toLong() * 1000
            val end = slider.values[1].toLong() * 1000

            if (fromUser) {
                if (value == slider.values[0]) {
                    player.seekTo(start)
                }
                else if (value == slider.values[1]) {
                    player.seekTo(end)
                }
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val start = rangeSlider.values[0].toLong() * 1000
            val end = rangeSlider.values[1].toLong() * 1000

            // Logic to trim the currently selected clip or the main video if only one.
            // For simplicity in this demo, we assume we are trimming the last added video clip
            // or we need a 'selectedClip' state in VM.
            // Let's assume the VM has a concept of selected clip, or we find the first video clip.

            val state = viewModel.getProjectState()
            val videoTrack = state.tracks.find { it.trackType == MediaType.VIDEO }
            if (videoTrack != null && videoTrack.clips.isNotEmpty()) {
                // Determine which clip is being trimmed.
                // Ideally, user selects a clip in Timeline, then clicks Trim.
                // Here, we'll just trim the first clip as a fallback or the one matching current time.

                val clipToTrim = videoTrack.clips.first() // Simplified

                viewModel.updateClipTrim(clipToTrim.id, start, end)
                Toast.makeText(this, "Clip trimmed!", Toast.LENGTH_SHORT).show()
            } else {
                 Toast.makeText(this, "No video clip to trim", Toast.LENGTH_SHORT).show()
            }

            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun showError(error: String) {
        Log.e("VideoEditingError", error)
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }

    private fun refreshUI() {
        // No-op or refactor
    }

    private fun saveAction() {
        // Iterate through ViewModel state and build complex FFmpeg command
        Toast.makeText(this, "Exporting...", Toast.LENGTH_SHORT).show()
        // TODO: Implement export logic based on ViewModel state
    }

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // Initial setup done in onCreate via VM

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isVideoLoaded = true
                    updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && isVideoLoaded) {
                     startTextOverlayUpdateLoop()
                }
            }
        })
    }

    private fun startTextOverlayUpdateLoop() {
        lifecycleScope.launch {
            while (player.isPlaying) {
                updateTextVisibility()
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun updateTextVisibility() {
        val currentPosition = player.currentPosition
        val textContainer = findViewById<android.widget.LinearLayout>(R.id.textConfigs)

        // We need the project state to calculate start times
        val state = viewModel.getProjectState()
        val textTrack = state.tracks.find { it.trackType == MediaType.TEXT } ?: return

        // Calculate start times for magnetic text track
        var clipStartTime = 0L
        val clipStartTimes = mutableMapOf<String, Long>()
        for (clip in textTrack.clips) {
            clipStartTimes[clip.id] = clipStartTime
            clipStartTime += clip.durationMs
        }

        for (i in 0 until textContainer.childCount) {
            val view = textContainer.getChildAt(i)
            val clip = view.tag as? com.tharunbirla.librecuts.models.Clip

            if (clip != null) {
                val startTime = clipStartTimes[clip.id] ?: 0L
                val endTime = startTime + clip.durationMs

                if (currentPosition in startTime until endTime) {
                    view.visibility = View.VISIBLE
                } else {
                    view.visibility = View.INVISIBLE
                }
            }
        }
    }

    private suspend fun getVideoDuration(uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                // If it's a content URI, we need a way to read it.
                // Retriever supports context/uri in newer APIs or path.
                // Since we have context, let's use it.
                retriever.setDataSource(this@VideoEditingActivity, uri)
                val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                time?.toLong() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error getting duration", e)
                0L
            } finally {
                retriever.release()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDurationDisplay(current: Int, total: Int) {
        if (!isVideoLoaded || total <= 0) return

        val currentFormatted = formatDuration(current)
        val totalFormatted = formatDuration(total)

        tvDuration.text = "$currentFormatted / $totalFormatted"
    }

    private fun formatDuration(milliseconds: Int): String {
        val minutes = milliseconds / 60000
        val seconds = (milliseconds % 60000) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        activeFFmpegSessions.forEach { session ->
            FFmpegKit.cancel(session.sessionId)
        }
        activeFFmpegSessions.clear()
        player.release()
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
        private const val PICK_AUDIO_REQUEST = 2
    }
}
