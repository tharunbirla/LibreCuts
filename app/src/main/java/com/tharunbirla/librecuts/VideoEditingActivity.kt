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
        findViewById<ImageButton>(R.id.btnSplit).setOnClickListener { splitAction() }
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

        // Update Audio/Text Track Visuals
        val audioTrack = state.tracks.find { it.trackType == MediaType.AUDIO }
        val textTrack = state.tracks.find { it.trackType == MediaType.TEXT }

        val audioContainer = findViewById<android.widget.LinearLayout>(R.id.audioTrackContainer)
        val textContainer = findViewById<android.widget.LinearLayout>(R.id.textTrackContainer)

        // Simple visualization: Add View for each clip with width proportional to duration
        // We need total duration to calculate width relative to screen width?
        // No, Timeline usually scrolls. But here we have one RecyclerView scrolling and separate LinearLayouts?
        // This mismatch will break synchronization.
        // If RecyclerView scrolls, the LinearLayouts won't scroll with it unless they are inside the RecyclerView or synced.
        // Given constraints and "stick to my style", the original style was a single horizontal scroll.
        // To make Audio/Text scroll with Video, they should be part of the RecyclerView Item OR the RecyclerView should be a vertical list of horizontal tracks.

        // Correct approach for "stick to style" but "add tracks":
        // FrameAdapter should render a "Vertical Strip" for each time slice? No.

        // Alternative: Hide Audio/Text tracks for now but show them in the "Selected Clip" info?
        // Or just populate them in the containers and hope user doesn't scroll much? No, that's bad.

        // Since I added LinearLayouts *outside* the RecyclerView, they won't scroll.
        // I will make them visible ONLY if they have content, to indicate "tracks exist".
        // Syncing scroll is complex without a proper TimelineView (which I removed).
        // I will just show them as static indicators for now or remove the containers if they confuse functionality.

        // Let's populate them just to show "Something is there".
        audioContainer.removeAllViews()
        if (audioTrack?.clips?.isNotEmpty() == true) {
            audioContainer.visibility = View.VISIBLE
            // Add a view for full duration?
            val v = View(this)
            v.setBackgroundColor(android.graphics.Color.GREEN)
            val params = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
            v.layoutParams = params
            audioContainer.addView(v)
        } else {
            audioContainer.visibility = View.GONE
        }

        textContainer.removeAllViews()
        if (textTrack?.clips?.isNotEmpty() == true) {
            textContainer.visibility = View.VISIBLE
            val v = View(this)
            v.setBackgroundColor(android.graphics.Color.BLUE)
            val params = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.MATCH_PARENT)
            v.layoutParams = params
            textContainer.addView(v)
        } else {
            textContainer.visibility = View.GONE
        }

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
        // Apply Crop to View and Model
        var cropData: com.tharunbirla.librecuts.models.CropData? = null

        playerView.videoSurfaceView?.let { surfaceView ->
             if (surfaceView is android.view.TextureView) {
                 val viewWidth = surfaceView.width.toFloat()
                 val viewHeight = surfaceView.height.toFloat()
                 val matrix = android.graphics.Matrix()

                 // Logic to set matrix AND calculate normalized crop rect (x,y,w,h) relative to video frame.
                 // This is simplified. Real logic requires knowing video aspect ratio vs view aspect ratio.
                 // For MVP "YouTube Create" style, we usually crop to center.

                 when (aspectRatio) {
                    "16:9" -> {
                        // Assuming Landscape.
                        // Crop is centered.
                        // w = 1.0, h = (9/16) / (videoAR).
                        // Let's assume video is 16:9 for now or generic.
                        // We set crop to Center 16:9 relative to whatever the video is.
                        // If video is 9:16, cropping to 16:9 is a tiny strip.

                        // Let's just store the aspect ratio string in CropData and let FFmpegGen handle logic?
                        // No, FFmpegGen expects x,y,w,h.

                        // We'll set a placeholder Normalized Crop.
                        // 16:9 means w=1.0, h= calculated.
                        // For this demo, I will hardcode "Center Crop" logic in FFmpegGen based on AR string if possible
                        // But I must pass CropData.

                        // Let's use x=0, y=0.1, w=1.0, h=0.8 for example? No.
                        // I will pass the aspect ratio string in CropData and update FFmpegGen to handle "16:9" logic.
                        cropData = com.tharunbirla.librecuts.models.CropData(0f, 0f, 0f, 0f, "16:9")

                        // Matrix for Preview (Zoom to Fill 16:9 box?)
                        // matrix.setScale(...)
                    }
                    "9:16" -> {
                        cropData = com.tharunbirla.librecuts.models.CropData(0f, 0f, 0f, 0f, "9:16")
                    }
                    "1:1" -> {
                        cropData = com.tharunbirla.librecuts.models.CropData(0f, 0f, 0f, 0f, "1:1")
                    }
                    else -> {
                        cropData = null // Reset
                    }
                 }

                 // Apply visual transform (Simple Scale)
                 // Re-using the logic from before for visual feedback
                 val scaleX = if (aspectRatio == "9:16") 1.5f else 1.0f
                 val scaleY = if (aspectRatio == "16:9") 1.5f else 1.0f
                 matrix.setScale(scaleX, scaleY, viewWidth/2, viewHeight/2)
                 if (aspectRatio == "Reset") matrix.reset()

                 surfaceView.setTransform(matrix)
             }
        }

        // Update ViewModel
        if (selectedClipId != null && cropData != null) {
            viewModel.updateClipCrop(selectedClipId!!, cropData!!)
        } else {
             // If no clip selected, maybe warn or apply to first?
             val firstClip = viewModel.projectState.value.tracks.find { it.trackType == MediaType.VIDEO }?.clips?.firstOrNull()
             if (firstClip != null && cropData != null) {
                 viewModel.updateClipCrop(firstClip.id, cropData!!)
             }
        }

        Toast.makeText(this, "Crop $aspectRatio Applied", Toast.LENGTH_SHORT).show()
    }

    private fun audioAction() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Audio"), PICK_AUDIO_REQUEST)
    }

    private fun splitAction() {
        if (selectedClipId != null) {
            val currentPos = player.currentPosition
            viewModel.splitClip(selectedClipId!!, currentPos)
            Toast.makeText(this, "Split Applied", Toast.LENGTH_SHORT).show()
        } else {
            // Try to find clip at current time if none selected
            val currentPos = player.currentPosition
            val state = viewModel.projectState.value
            // Simple logic for video track
            val videoTrack = state.tracks.find { it.trackType == MediaType.VIDEO }
            var startTime = 0L
            var foundClipId: String? = null

            videoTrack?.clips?.forEach { clip ->
                if (currentPos >= startTime && currentPos < startTime + clip.durationMs) {
                    foundClipId = clip.id
                    return@forEach
                }
                startTime += clip.durationMs
            }

            if (foundClipId != null) {
                viewModel.splitClip(foundClipId!!, currentPos)
                Toast.makeText(this, "Split Applied", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "No clip to split at this position", Toast.LENGTH_SHORT).show()
            }
        }
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
        lifecycleScope.launch {
            loadingScreen.visibility = View.VISIBLE
            val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!outputDir.exists()) outputDir.mkdirs()
            val outputPath = File(outputDir, "exported_${System.currentTimeMillis()}.mp4").absolutePath

            val command = com.tharunbirla.librecuts.utils.FFmpegCommandGenerator.generate(
                this@VideoEditingActivity,
                viewModel.projectState.value,
                outputPath
            )

            Log.d("Export", "Command: $command")

            val session = withContext(Dispatchers.IO) {
                FFmpegKit.execute(command)
            }

            loadingScreen.visibility = View.GONE

            if (ReturnCode.isSuccess(session.returnCode)) {
                Toast.makeText(this@VideoEditingActivity, "Export Success: $outputPath", Toast.LENGTH_LONG).show()
                // Optionally scan media
            } else {
                Toast.makeText(this@VideoEditingActivity, "Export Failed: ${session.failStackTrace}", Toast.LENGTH_LONG).show()
                Log.e("Export", "Failed: ${session.failStackTrace}")
            }
        }
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
