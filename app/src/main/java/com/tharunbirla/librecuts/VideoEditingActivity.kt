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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
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
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.MediaType
import com.tharunbirla.librecuts.viewmodels.VideoEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.Locale


@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    private val viewModel: VideoEditorViewModel by viewModels()

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var frameRecyclerView: RecyclerView
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private var videoUri: Uri? = null

    // Legacy vars
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView

    private var activeFFmpegSessions = mutableListOf<FFmpegSession>()
    private var isVideoLoaded = false
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private lateinit var frameAdapter: FrameAdapter
    private var selectedClipId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)

        // Set fullscreen flags
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        loadingScreen = findViewById(R.id.loadingScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)
        try {
            lottieAnimationView.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Error loading Lottie animation: ${e.message}")
        }

        initializeViews()
        setupExoPlayer()
        setupCustomSeeker()
        setupFrameRecyclerView()

        // Initial Video Load
        videoUri = intent.getParcelableExtra("VIDEO_URI")
        if (videoUri != null) {
             lifecycleScope.launch {
                 val duration = getVideoDuration(videoUri!!)
                 viewModel.addClip(videoUri!!, MediaType.VIDEO, duration)
             }
        }

        observeViewModel()
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        frameRecyclerView = findViewById(R.id.frameRecyclerView)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed()}
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveAction() }
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener { trimAction() }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { textAction() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { audioAction() }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener { cropAction() }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener { mergeAction() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.projectState.collectLatest { state ->
                    refreshPlayerSource()
                    updateTimeline(state)
                }
            }
        }
    }

    private fun refreshPlayerSource() {
        val mediaSource = viewModel.buildMediaSource()
        if (mediaSource != null) {
            val currentPos = player.currentPosition
            val wasPlaying = player.isPlaying
            player.setMediaSource(mediaSource)
            player.prepare()
            if (currentPos > 0) {
                 player.seekTo(currentPos)
            }
            player.playWhenReady = wasPlaying
        }
    }

    private fun updateTimeline(state: com.tharunbirla.librecuts.models.ProjectState) {
        val videoTrack = state.tracks.find { it.trackType == MediaType.VIDEO }
        val clips = videoTrack?.clips ?: emptyList()

        frameAdapter.updateClips(clips)

        // Generate thumbnails for new clips
        // We should cache them to avoid regenerating.
        // For MVP, just generate for all if not cached.

        lifecycleScope.launch(Dispatchers.IO) {
            val newThumbnails = mutableMapOf<String, Bitmap>()
            val retriever = MediaMetadataRetriever()

            clips.forEach { clip ->
                if (clip.mediaType == MediaType.VIDEO && clip.uri != Uri.EMPTY) {
                     try {
                         retriever.setDataSource(this@VideoEditingActivity, clip.uri)
                         // Extract frame at middle of clip or start
                         // clip.startOffsetMs is start in source.
                         val frameTime = (clip.startOffsetMs + clip.durationMs / 2) * 1000
                         val bitmap = retriever.getFrameAtTime(frameTime, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                         if (bitmap != null) {
                             val scaled = Bitmap.createScaledBitmap(bitmap, 200, 150, false)
                             newThumbnails[clip.id] = scaled
                         }
                     } catch (e: Exception) {
                         Log.e(TAG, "Error generating thumbnail for ${clip.id}", e)
                     }
                }
            }
            retriever.release()

            withContext(Dispatchers.Main) {
                frameAdapter.updateThumbnails(newThumbnails)
            }
        }

        customVideoSeeker.setVideoDuration(state.totalDurationMs)
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

    @Deprecated("Deprecated in Java")
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
                             val duration = getVideoDuration(uri)
                             viewModel.addClip(uri, MediaType.AUDIO, duration)
                             Toast.makeText(this@VideoEditingActivity, "Audio added!", Toast.LENGTH_SHORT).show()
                         }
                    }
                }
            }
        }
    }


    @SuppressLint("InflateParams")
    private fun cropAction() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)

        sheetView.findViewById<TextView>(R.id.tvTitleCrop).text = getString(R.string.select_aspect_ratio)

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

        sheetView.findViewById<Button>(R.id.btnCancelCrop).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun cropVideo(aspectRatio: String) {
        // Preview Only Crop
        playerView.videoSurfaceView?.let { surfaceView ->
             if (surfaceView is android.view.TextureView) {
                 val viewWidth = surfaceView.width.toFloat()
                 val viewHeight = surfaceView.height.toFloat()
                 val matrix = android.graphics.Matrix()

                 when (aspectRatio) {
                    "16:9" -> matrix.setScale(1.0f, 9f/16f * (viewWidth/viewHeight), viewWidth/2, viewHeight/2) // Very rough approx
                    "9:16" -> matrix.setScale(9f/16f * (viewHeight/viewWidth), 1.0f, viewWidth/2, viewHeight/2)
                    "1:1" -> matrix.setScale(1.0f, 1.0f, viewWidth/2, viewHeight/2) // Reset/Normal
                    else -> matrix.reset()
                 }
                 // Improved Matrix Logic required for real fit/crop, but this enables the hook.
                 // For now, let's just use Scale to fill
                 surfaceView.setTransform(matrix)
             }
        }
        Toast.makeText(this, "Preview Crop: $aspectRatio", Toast.LENGTH_SHORT).show()
    }

    private fun audioAction() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Audio"), PICK_AUDIO_REQUEST)
    }

    private fun textAction() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.text_bottom_sheet_dialog, null)
        val etTextInput = view.findViewById<TextInputEditText>(R.id.etTextInput)
        val btnDone = view.findViewById<Button>(R.id.btnDoneText)

        // Spinners etc ignored for MVP functionality speed

        btnDone.setOnClickListener {
            val text = etTextInput.text.toString()
            viewModel.addClip(Uri.EMPTY, MediaType.TEXT, 5000L, text)
            Toast.makeText(this, "Text added", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        val videoDuration = player.duration
        if (videoDuration <= 0) return

        val bottomSheetDialog = BottomSheetDialog(this@VideoEditingActivity)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        val durationInMillis: Long = videoDuration
        val formattedValueTo = (durationInMillis / 1000).toFloat()

        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = formattedValueTo.coerceAtLeast(1f)
        rangeSlider.values = listOf(0f, formattedValueTo.coerceAtLeast(1f))

        rangeSlider.addOnChangeListener { slider, value, fromUser ->
            if (fromUser) {
                 val pos = (value * 1000).toLong()
                 player.seekTo(pos)
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val start = (rangeSlider.values[0] * 1000).toLong()
            val end = (rangeSlider.values[1] * 1000).toLong()

            // Trim the selected clip
            if (selectedClipId != null) {
                viewModel.updateClipTrim(selectedClipId!!, start, end)
                Toast.makeText(this, "Trim Applied", Toast.LENGTH_SHORT).show()
            } else {
                 // Fallback to first video clip
                 val state = viewModel.projectState.value
                 val clip = state.tracks.find { it.trackType == MediaType.VIDEO }?.clips?.firstOrNull()
                 if (clip != null) {
                     viewModel.updateClipTrim(clip.id, start, end)
                     Toast.makeText(this, "Trim Applied to first clip", Toast.LENGTH_SHORT).show()
                 } else {
                     Toast.makeText(this, "Select a clip to trim", Toast.LENGTH_SHORT).show()
                 }
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

    private fun saveAction() {
        Toast.makeText(this, "Exporting to file (TODO)", Toast.LENGTH_SHORT).show()
    }

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isVideoLoaded = true
                    customVideoSeeker.setVideoDuration(player.duration)
                    updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                 if (isPlaying) {
                     startOverlayUpdateLoop()
                 }
            }
        })
    }

    private fun startOverlayUpdateLoop() {
        lifecycleScope.launch {
            while (player.isPlaying) {
                updateTextOverlays()
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun updateTextOverlays() {
        // Simple logic: If text clips exist, show them.
        // Real logic: Calculate current playback time against clip start/end times.
        // Since our magnetic timeline logic for non-video tracks is not fully visualized,
        // we will assume Text Track clips are also sequential starting at 0.

        val state = viewModel.projectState.value
        val textTrack = state.tracks.find { it.trackType == MediaType.TEXT } ?: return
        val currentPos = player.currentPosition

        // Calculate start/end for each clip
        var startTime = 0L
        val visibleText = StringBuilder()

        textTrack.clips.forEach { clip ->
             val endTime = startTime + clip.durationMs
             if (currentPos in startTime until endTime) {
                 visibleText.append(clip.text ?: "").append("\n")
             }
             startTime = endTime
        }

        // We need a TextView to show this.
        // `textConfigs` Layout contains views? No, it's used for config.
        // We should add an overlay TextView on top of playerView.
        // For now, I'll use a specific TextView if I can find one or add it dynamically.
        // The layout doesn't have an overlay TextView.
        // I'll dynamically add one to `playerView` (which is a FrameLayout/Relative) or the parent.

        // Better: Use `textConfigs` container to hold the active TextView.
        val container = findViewById<android.widget.LinearLayout>(R.id.textConfigs)
        if (container.childCount == 0) {
            val tv = TextView(this)
            tv.setTextColor(android.graphics.Color.WHITE)
            tv.textSize = 24f
            tv.tag = "overlay"
            container.addView(tv)
        }
        val tv = container.findViewWithTag<TextView>("overlay")
        tv?.text = visibleText.toString()
    }

    private fun setupCustomSeeker() {
        customVideoSeeker.onSeekListener = { seekPosition ->
            // seekPosition is normalized 0..1 or absolute time if float > 1?
            // Checking CustomVideoSeeker impl: it invokes with normalized (0-1) AND time.
            // But Kotlin lambda only takes one arg if defined as { }.
            // Wait, CustomVideoSeeker.kt: var onSeekListener: ((Float) -> Unit)? = null
            // It calls it twice? once with pos, once with time. That's weird.
            // Let's check CustomVideoSeeker.kt again.
            // onSeekListener?.invoke(seekPosition)
            // onSeekListener?.invoke(seekTime.toFloat())
            // This is buggy in original code if the listener expects normalized but gets time second.
            // I'll fix CustomVideoSeeker to be clearer or handle it here.
            // If I assume it passes normalized:
            if (seekPosition <= 1.0f) {
                 val newSeekTime = (player.duration * seekPosition).toLong()
                 player.seekTo(newSeekTime)
            }
        }
    }

    private fun setupFrameRecyclerView() {
        frameAdapter = FrameAdapter(emptyList()) { clip ->
             // On clip click
             selectedClipId = clip.id
             Toast.makeText(this, "Selected: ${clip.id.take(8)}...", Toast.LENGTH_SHORT).show()
        }

        frameRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        frameRecyclerView.adapter = frameAdapter

        // Add Drag and Drop
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPos = viewHolder.adapterPosition
                val toPos = target.adapterPosition
                viewModel.moveClip(MediaType.VIDEO, fromPos, toPos)
                frameAdapter.notifyItemMoved(fromPos, toPos)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Remove clip?
                val pos = viewHolder.adapterPosition
                val clip = viewModel.projectState.value.tracks.find { it.trackType == MediaType.VIDEO }?.clips?.get(pos)
                if (clip != null) {
                    viewModel.removeClip(clip.id)
                    frameAdapter.notifyItemRemoved(pos)
                }
            }
        })
        itemTouchHelper.attachToRecyclerView(frameRecyclerView)
    }

    private suspend fun getVideoDuration(uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this@VideoEditingActivity, uri)
                val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                time?.toLong() ?: 0L
            } catch (e: Exception) {
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
        player.release()
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
        private const val PICK_AUDIO_REQUEST = 2
    }
}
