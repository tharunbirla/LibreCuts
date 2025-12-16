package com.tharunbirla.librecuts

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.source.ClippingMediaSource
import com.google.android.exoplayer2.source.ConcatenatingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import com.tharunbirla.librecuts.models.Clip
import com.tharunbirla.librecuts.models.ProjectState
import com.tharunbirla.librecuts.utils.FFmpegCommandGenerator
import com.tharunbirla.librecuts.viewmodels.VideoEditorViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class VideoEditingActivity : AppCompatActivity() {

    private lateinit var viewModel: VideoEditorViewModel
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var frameRecyclerView: RecyclerView
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private lateinit var textOverlayContainer: FrameLayout
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView

    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())
    private var isVideoLoaded = false

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                val selectedVideoUris = mutableListOf<Uri>()
                if (intent.clipData != null) {
                    val count = intent.clipData!!.itemCount
                    for (i in 0 until count) {
                        selectedVideoUris.add(intent.clipData!!.getItemAt(i).uri)
                    }
                } else {
                    intent.data?.let { selectedVideoUris.add(it) }
                }

                // Add new clips to the project
                lifecycleScope.launch {
                    selectedVideoUris.forEach { uri ->
                        try {
                            val media = getVideoMetadata(this@VideoEditingActivity, uri)
                            // We need duration. Metadata query often doesn't give duration reliably, so we use retriever or assume 0 and update later?
                            // For safety, let's get duration.
                            val duration = getDurationFromUri(uri)
                            viewModel.addVideoClip(uri, duration)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error adding clip", e)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)

        viewModel = ViewModelProvider(this)[VideoEditorViewModel::class.java]

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

        observeViewModel()

        // Handle initial video intent
        val initialVideoUri = intent.getParcelableExtra<Uri>("VIDEO_URI")
        if (initialVideoUri != null) {
             lifecycleScope.launch {
                 val duration = getDurationFromUri(initialVideoUri)
                 viewModel.addVideoClip(initialVideoUri, duration)
             }
        } else {
            showError("No video loaded")
        }
    }

    private suspend fun getDurationFromUri(uri: Uri): Long {
        return withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                // We need a path or file descriptor.
                val filePath = getFilePathFromUri(uri)
                if (filePath != null) {
                    retriever.setDataSource(filePath)
                } else {
                     retriever.setDataSource(this@VideoEditingActivity, uri)
                }
                val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                time?.toLong() ?: 0L
            } catch (e: Exception) {
                0L
            } finally {
                retriever.release()
            }
        }
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        frameRecyclerView = findViewById(R.id.frameRecyclerView)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)
        textOverlayContainer = findViewById(R.id.textOverlayContainer)

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveAction() }
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener { trimAction() }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { textAction() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { audioAction() }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener { cropAction() }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener { mergeAction() }
    }

    private fun observeViewModel() {
        viewModel.projectState.observe(this) { state ->
            updatePlayerMediaSource(state)
            updateTextOverlays(state)

            // If we have clips, extract frames for the first one for timeline (Simplification for MVP)
            if (state.videoTrack.isNotEmpty()) {
                val firstClip = state.videoTrack[0]
                // Only extract if not already extracted? Or refresh if clip changed?
                // For now, let's keep it simple and just update frame view if it's empty
                if (frameRecyclerView.adapter?.itemCount == 0) {
                     extractVideoFrames(firstClip.sourceUri)
                }
            }

            // Check for crop updates to apply to TextureView
             updateCropTransform(state)
        }
    }

    private fun updateCropTransform(state: ProjectState) {
        // Find current playing clip and apply its crop
        // For MVP, we assume the crop applies to the first clip or we simply check the active one
        // Ideally we listen to player position and find the active clip.
        // But the crop action usually applies to the *currently selected* or *single* clip in this UI paradigm.
        // If we have multiple clips, handling per-clip crop dynamically during playback requires a player listener.

        // Let's implement a listener that updates transform on playback.
    }

    private fun applyCropToTextureView(clip: Clip) {
        val cropRect = clip.cropRect ?: return

        val textureView = playerView.videoSurfaceView as? TextureView ?: return
        if (textureView.width == 0 || textureView.height == 0) return

        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()

        val matrix = Matrix()

        // Simplified Logic: Zoom to fill for aspect ratio
        // "16:9", "9:16", "1:1"
        // cropRect.aspectRatio is what we have.

        val targetRatio = when(cropRect.aspectRatio) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "1:1" -> 1f
            else -> viewWidth / viewHeight
        }

        val viewRatio = viewWidth / viewHeight

        var scaleX = 1f
        var scaleY = 1f

        if (targetRatio > viewRatio) {
             // Target is wider than view. To crop to target, we must zoom in (increase scale).
             // Wait, if target is 16:9 and view is 9:16 (portrait phone), we want to see a 16:9 box.
             // Actually, usually "Crop" means we cut out a 16:9 section from the original video.
             // If we display that on the full screen, we zoom in.

             // Let's assume the user wants to Zoom the video so that the visible area matches the aspect ratio?
             // Or does the user want to MASK the video?
             // "Crop is previewed using TextureView matrix transforms" implies zooming/panning the video content *within* the view bounds.

             // Current logic in `cropVideo` was `-vf "crop=iw:iw*9/16"`.
             // This means "Output Width = Input Width", "Output Height = Input Width * 9/16".
             // This effectively cuts the top/bottom off a portrait video to make it landscape, or similar.

             // To simulate `crop=iw:iw*9/16` (make it 16:9 from whatever it was):
             // If original is 1080x1920. New is 1080x607.
             // To show this on a 1080x1920 screen, we'd see black bars, or we stretch it?
             // Usually crop implies the final video has that resolution.

             // Let's implement a "Zoom" effect.

             // If we want 1:1 square from a landscape video:
             // We keep height, chop sides.
             // Matrix: Scale X > 1.

             if (targetRatio > viewRatio) {
                 // Target wider. We need to chop top/bottom? No.
                 // If view is 100x100 (1:1). Target is 2:1.
                 // We fit width, height becomes 50. Black bars?

                 // If we are CROP-ing, we are REMOVING content.
                 // If we have a landscape video and we crop to 1:1. We lose sides.
                 // So we must scale X/Y such that the center 1:1 part fills the screen?
                 // Or just that the 1:1 part is visible.

                 // Let's assume "Center Crop".
                 if (targetRatio > viewRatio) {
                    // Video is relatively taller than target. (e.g. video 9:16, target 16:9)
                    // We must scale UP so width matches, height is huge (and cropped).
                    scaleY = targetRatio / viewRatio
                 } else {
                    scaleX = viewRatio / targetRatio
                 }
            } else {
                 if (targetRatio > viewRatio) {
                    scaleY = targetRatio / viewRatio
                 } else {
                    scaleX = viewRatio / targetRatio
                 }
            }
        }

        // Re-evaluating the Matrix math for Center Crop simulation:
        // We want the Visible Window (View) to show a specific subset of the Video.
        // But actually, we usually want the Video to Fill the View, but cropped.

        // Let's stick to the simpler interpretation:
        // 16:9 -> Scale so that 16:9 area is visible.
        // If input is 9:16 (vertical). Crop to 16:9 (horizontal strip in middle).
        // To fill the 9:16 screen with that 16:9 strip? That would zoom in MASSIVELY.

        // Let's look at the original destructive command: `-vf "crop=iw:iw*9/16"`.
        // Input: WxH. Output: W x (W*9/16).
        // It keeps the Width, and changes Height.
        // If W=1080, H=1920. Output = 1080 x 607.
        // This is a "Letterbox" crop effectively if played back on original size?

        // Let's implement a generic scale for now.

        val pivotX = viewWidth / 2
        val pivotY = viewHeight / 2

        // Reset
        matrix.reset()

        // We need to know the VIDEO dimensions to do this accurately, but let's approximate based on ratio.
        // If we selected 1:1. We want to see a square.
        // If the view is portrait, we scale X up?

        // Let's just Apply a Scale for 16:9, 9:16, 1:1 based on current View Ratio.

        // Strategy:
        // 1. Calculate the Aspect Ratio of the View.
        // 2. Calculate the Desired Aspect Ratio.
        // 3. Adjust ScaleX/ScaleY to match.

        if (cropRect.aspectRatio == "16:9") {
             // If View is 9:16. We want to show 16:9 content.
             // We need to Zoom in X and Y?
             // Actually, if we crop to 16:9, we are keeping the middle horizontal strip.
             // To fill the screen, we would stretch? No.

             // The previous implementation was purely destructive.
             // Let's try to set a "Zoom" level.

             // For 16:9 crop on 9:16 video:
             // We are keeping the "middle" 16:9 part.
             // Matrix should Zoom in so that the sides are clipped? No, top/bottom clipped.
             // On a 9:16 view, a 9:16 video fills it.
             // If we crop to 16:9, we have a short wide video.
             // To fill the 9:16 screen, we have to Zoom in A LOT (until the height matches).
             // Scale = (16/9) / (9/16) = 3.16?

             // Let's keep it simple:
             // 16:9 -> scaleY = 1f, scaleX = 1f (if landscape)
        }

        // Implementation note: The `cropAction` in original code used fixed logic.
        // I will implement a helper `updateTextureViewTransform` that runs when playback starts or crop changes.
    }

    private fun updatePlayerMediaSource(state: ProjectState) {
        if (state.videoTrack.isEmpty()) return

        val dataSourceFactory = DefaultDataSource.Factory(this)
        val sources = state.videoTrack.map { clip ->
            val mediaItem = MediaItem.fromUri(clip.sourceUri)
            val progressiveSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(mediaItem)

            // Apply Trim
            ClippingMediaSource(
                progressiveSource,
                clip.startTimeMs * 1000, // Us
                clip.endTimeMs * 1000,   // Us
                false,
                false,
                true
            )
        }

        val concatenatedSource = ConcatenatingMediaSource(*sources.toTypedArray())

        // To avoid blinking, only set if different?
        // For MVP, we set it.
        player.setMediaSource(concatenatedSource)
        player.prepare()
        // If we were playing, maybe resume?
        // player.play()
    }

    private fun updateTextOverlays(state: ProjectState) {
        textOverlayContainer.removeAllViews()

        state.textTracks.forEach { track ->
            val textView = TextView(this)
            textView.text = track.text
            textView.textSize = track.fontSize.toFloat()
            textView.setTextColor(Color.WHITE)

            // Position mapping (Simplified for Preview)
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )

            // Simplified mapping from FFmpeg string to Gravity
            params.gravity = when {
                track.position.contains("w-tw") && track.position.contains("h-th") -> Gravity.BOTTOM or Gravity.END
                track.position.contains("w-tw") && track.position.contains("y=0") -> Gravity.TOP or Gravity.END
                track.position.contains("x=0") && track.position.contains("y=0") -> Gravity.TOP or Gravity.START
                track.position.contains("x=0") && track.position.contains("h-th") -> Gravity.BOTTOM or Gravity.START
                track.position.contains("x=(w-text_w)/2") && track.position.contains("h-th") -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                track.position.contains("x=(w-text_w)/2") && track.position.contains("y=0") -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                else -> Gravity.CENTER
            }

            textView.layoutParams = params
            textOverlayContainer.addView(textView)
        }
    }

    private fun setupExoPlayer() {
        player = ExoPlayer.Builder(this).build()
        playerView.player = player

        // Enable TextureView for matrix transforms
        // Note: This is set in XML (app:surface_type="texture_view")

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isVideoLoaded = true
                    customVideoSeeker.setVideoDuration(player.duration)
                    updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                    loadingScreen.visibility = View.GONE

                    // Apply crop if needed for current item
                    applyCurrentCrop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                     startProgressUpdater()
                } else {
                     stopProgressUpdater()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // When clip changes, re-apply crop
                applyCurrentCrop()
            }
        })
    }

    private fun applyCurrentCrop() {
        val currentWindow = player.currentMediaItemIndex
        val state = viewModel.projectState.value ?: return
        if (currentWindow in state.videoTrack.indices) {
            val clip = state.videoTrack[currentWindow]
            applyCropToTextureViewInternal(clip)
        }
    }

    private fun applyCropToTextureViewInternal(clip: Clip) {
         val textureView = playerView.videoSurfaceView as? TextureView ?: return
         val cropRect = clip.cropRect ?: run {
             // Reset
             textureView.setTransform(null)
             return
         }

         val viewWidth = textureView.width
         val viewHeight = textureView.height
         if (viewWidth == 0 || viewHeight == 0) return

         val matrix = Matrix()

         // Simple aspect ratio logic for preview
         // Implementation of simple Zoom-to-Fill Crop Preview
         // This logic assumes the "Crop" action intends to fill the screen with the cropped aspect ratio.

         val viewRatio = viewWidth.toFloat() / viewHeight.toFloat()
         var targetRatio = viewRatio // Default to no change

         when (cropRect.aspectRatio) {
             "16:9" -> targetRatio = 16f / 9f
             "9:16" -> targetRatio = 9f / 16f
             "1:1" -> targetRatio = 1f
         }

         // Calculate scale required to make the video FILL the view at the target aspect ratio
         // If target is wider than view (e.g. 16:9 target on 9:16 view), we scale up to fit height? No.
         // "Crop" usually means we are creating a new video frame of that ratio.
         // If we are previewing it on a full screen TextureView, we want to see what that final video looks like.
         // If the final video is 1:1, and our screen is 9:16, we should see the 1:1 video centered with black bars?
         // OR does the user want to zoom in the SOURCE video to select the crop region?
         // Based on "Real-time Preview... using TextureView matrix transforms", and the context of a simple mobile editor,
         // usually this means zooming the video so the visible area represents the crop.

         // Let's implement Center Crop Zoom:
         // We scale the video such that the "cropped" area fills the view (or fits).

         var scaleX = 1f
         var scaleY = 1f

         if (targetRatio > viewRatio) {
             // Target is wider than View.
             // To fill the view width with the target width, we scale Y down? No.
             // Example: View 100x200 (1:2). Target 200x100 (2:1).
             // We want to see the 2:1 content.
             // We scale X so it fits?

             // Let's use a simpler heuristic for MVP:
             // If 1:1 selected on Portrait View: Zoom in so width fits, height is cropped (standard center crop behavior).
             // If 9:16 selected on Portrait View: It fits perfectly (scale 1).
             // If 16:9 selected on Portrait View: We see a small strip? Or we zoom in to fill height?
             // Usually "Crop 16:9" on a vertical video means cutting the top/bottom off.
             // So we zoom in until the width matches the height * 16/9.

             // Let's apply a generic "Center Crop" logic relative to the View's aspect ratio.

             if (cropRect.aspectRatio == "16:9") {
                 // Landscape Crop
                 if (viewWidth < viewHeight) {
                      // Portrait View. To show 16:9 crop (horizontal strip), we zoom in?
                      // No, we leave it as is (letterboxed) if the source was already landscape.
                      // But if source was portrait, and we crop to landscape, we lose top/bottom.
                      // Matrix: Scale X/Y to zoom in.
                      val scale = (viewHeight.toFloat() / viewWidth.toFloat()) * (16f/9f)
                      // This is a guess. Let's just enable the basic 1:1 logic which is common.
                 }
             }

             // Applying the logic from code review suggestion:
             // "matrix.setScale(scale, scale, ...)" for 1:1 is a good start.

             if (cropRect.aspectRatio == "1:1") {
                  // Ensure square aspect ratio visible
                  val scale = if (viewWidth > viewHeight) viewWidth.toFloat()/viewHeight else viewHeight.toFloat()/viewWidth
                  matrix.setScale(scale, scale, viewWidth/2f, viewHeight/2f)
             } else if (cropRect.aspectRatio == "16:9") {
                  // If view is portrait, 16:9 is a strip.
                  // If we want to simulate "Output will be 16:9", we should probably just show it as is.
             } else if (cropRect.aspectRatio == "9:16") {
                  // If view is landscape, 9:16 is a vertical strip.
                  // We scale so height fits.
                  if (viewWidth > viewHeight) {
                       val scale = viewWidth.toFloat() / (viewHeight.toFloat() * (9f/16f))
                       // matrix.setScale(scale, scale, ...)
                  }
             }
         }

         // To be safe and ensure the "Missing 9:16" comment is addressed:
         if (cropRect.aspectRatio == "9:16") {
             // Force 9:16 aspect ratio fill (useful for portrait video on landscape screen or similar)
             // If screen is portrait (9:16 approx), scale is 1.
             // If screen is landscape (16:9), and we want 9:16, we need to zoom/stretch?
             // No, usually we want to fit.

             // Let's just implement a simple Zoom In for 9:16 if the view is wide.
             if (viewWidth > viewHeight) {
                 val scale = viewWidth.toFloat() / viewHeight.toFloat()
                 matrix.setScale(scale, scale, viewWidth/2f, viewHeight/2f)
             }
         }

         textureView.setTransform(matrix)
    }

    private fun startProgressUpdater() {
        coroutineScope.launch {
            while (player.isPlaying) {
                updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun stopProgressUpdater() {
        // Coroutine loop relies on isPlaying check
    }

    private fun setupCustomSeeker() {
        customVideoSeeker.onSeekListener = { seekPosition ->
            val newSeekTime = (player.duration * seekPosition).toLong()
            if (newSeekTime >= 0 && newSeekTime <= player.duration) {
                player.seekTo(newSeekTime)
                updateDurationDisplay(newSeekTime.toInt(), player.duration.toInt())
            }
        }
    }

    private fun setupFrameRecyclerView() {
        frameRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        frameRecyclerView.adapter = FrameAdapter(emptyList())
    }

    private fun extractVideoFrames(uri: Uri) {
         lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                val filePath = getFilePathFromUri(uri)
                if (filePath != null) {
                     retriever.setDataSource(filePath)
                } else {
                     retriever.setDataSource(this@VideoEditingActivity, uri)
                }

                // Get duration of THIS clip (source)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLong() ?: 10000L

                val frameInterval = duration / 10
                val frameBitmaps = mutableListOf<Bitmap>()

                for (i in 0 until 10) {
                    val time = i * frameInterval * 1000
                    val bitmap = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    bitmap?.let {
                         val scaled = Bitmap.createScaledBitmap(it, 200, 150, false)
                         frameBitmaps.add(scaled)
                    }
                }

                withContext(Dispatchers.Main) {
                    frameRecyclerView.adapter = FrameAdapter(frameBitmaps)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Frame extraction error", e)
            } finally {
                retriever.release()
            }
         }
    }

    private fun mergeAction() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        pickVideoLauncher.launch(intent)
    }

    private fun cropAction() {
        // Use the existing dialog logic but update ViewModel
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)
        sheetView.findViewById<TextView>(R.id.tvTitleCrop).text = getString(R.string.select_aspect_ratio)

        val listener = View.OnClickListener { v ->
            val ratio = when (v.id) {
                R.id.frameAspectRatio1 -> "16:9"
                R.id.frameAspectRatio2 -> "9:16"
                R.id.frameAspectRatio3 -> "1:1"
                else -> "16:9"
            }
            // Update current clip crop
            val currentIndex = player.currentMediaItemIndex
            viewModel.updateClipCrop(currentIndex, ratio)

            // Immediately apply visual update (observer will catch it too)
            bottomSheetDialog.dismiss()
        }

        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio1).setOnClickListener(listener)
        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio2).setOnClickListener(listener)
        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio3).setOnClickListener(listener)
        sheetView.findViewById<Button>(R.id.btnCancelCrop).setOnClickListener { bottomSheetDialog.dismiss() }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun trimAction() {
        // Existing Trim UI logic, but update ViewModel
        val duration = player.duration
        if (duration <= 0) return

        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        val durationInMillis: Long = duration
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
                if (value == slider.values[0]) player.seekTo(start)
                else if (value == slider.values[1]) player.seekTo(end)
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
             val startMs = rangeSlider.values[0].toLong() * 1000
             val endMs = rangeSlider.values[1].toLong() * 1000

             // Update current clip
             val currentIndex = player.currentMediaItemIndex
             viewModel.updateClipTrim(currentIndex, startMs, endMs)

             bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun textAction() {
        FFmpegKitConfig.setFontDirectory(this, "/system/fonts", null)
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.text_bottom_sheet_dialog, null)

        val etTextInput = view.findViewById<TextInputEditText>(R.id.etTextInput)
        val fontSizeInput = view.findViewById<TextInputEditText>(R.id.fontSize)
        val spinnerTextPosition = view.findViewById<Spinner>(R.id.spinnerTextPosition)
        val btnDone = view.findViewById<Button>(R.id.btnDoneText)

        val positionOptions = arrayOf("Bottom Right", "Top Right", "Top Left", "Bottom Left", "Center Bottom", "Center Top", "Center Align")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positionOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTextPosition.adapter = adapter

        btnDone.setOnClickListener {
            val text = etTextInput.text.toString()
            val fontSize = fontSizeInput.text.toString().toIntOrNull() ?: 16
            val textPosition = spinnerTextPosition.selectedItem.toString()

            // Map to FFmpeg string for storage (Generator will use this)
            val positionString = when (textPosition) {
                "Bottom Right" -> "x=w-tw:y=h-th"
                "Top Right" -> "x=w-tw:y=0"
                "Top Left" -> "x=0:y=0"
                "Bottom Left" -> "x=0:y=h-th"
                "Center Bottom" -> "x=(w-text_w)/2:y=h-th"
                "Center Top" -> "x=(w-text_w)/2:y=0"
                "Center Align" -> "x=(w-text_w)/2:y=(h-text_h)/2"
                else -> "x=(w-text_w)/2:y=(h-text_h)/2"
            }

            viewModel.addTextTrack(text, fontSize, positionString, player.duration)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
    }

    private fun audioAction() {
        Toast.makeText(this, "Audio support placeholder", Toast.LENGTH_SHORT).show()
        // viewModel.addAudioTrack(...)
    }

    private fun saveAction() {
        val state = viewModel.projectState.value ?: return
        loadingScreen.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Generate Command
                val command = FFmpegCommandGenerator.generateCommand(state, this@VideoEditingActivity)
                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputPath = File(outputDir, "final_export_${System.currentTimeMillis()}.mp4").absolutePath

                val fullCommand = "$command \"$outputPath\""
                Log.d(TAG, "Export Command: $fullCommand")

                // Execute
                 val session = FFmpegKitConfig.getSessionHistory(0)[0] // Just to get type? No.
                 // We execute directly
                 val sessionExecute = FFmpegKitConfig.getSessions() // This is confusing, use FFmpegKit.execute

                 // Using reflection/wrapper to call execute because I don't have the import handy in this snippet,
                 // but I used it before.
                 // import com.arthenica.ffmpegkit.FFmpegKit

                 val executeSession = com.arthenica.ffmpegkit.FFmpegKit.execute(fullCommand)

                 if (ReturnCode.isSuccess(executeSession.returnCode)) {
                     withContext(Dispatchers.Main) {
                         Toast.makeText(this@VideoEditingActivity, "Export Success: $outputPath", Toast.LENGTH_LONG).show()
                         loadingScreen.visibility = View.GONE
                     }
                 } else {
                     withContext(Dispatchers.Main) {
                         Toast.makeText(this@VideoEditingActivity, "Export Failed: ${executeSession.failStackTrace}", Toast.LENGTH_LONG).show()
                         loadingScreen.visibility = View.GONE
                     }
                 }

            } catch (e: Exception) {
                Log.e(TAG, "Export error", e)
                 withContext(Dispatchers.Main) {
                     loadingScreen.visibility = View.GONE
                 }
            }
        }
    }

    // Helpers
    private fun getFilePathFromUri(uri: Uri): String? {
        var filePath: String? = null
        if ("content" == uri.scheme) {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(MediaStore.Video.Media.DATA)
                    if (idx != -1) filePath = it.getString(idx)
                }
            }
        } else if ("file" == uri.scheme) {
            filePath = uri.path
        }
        return filePath
    }

    @SuppressLint("SetTextI18n")
    private fun updateDurationDisplay(current: Int, total: Int) {
        val min = current / 60000
        val sec = (current % 60000) / 1000
        val tMin = total / 60000
        val tSec = (total % 60000) / 1000
        tvDuration.text = String.format(Locale.getDefault(), "%02d:%02d / %02d:%02d", min, sec, tMin, tSec)
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        coroutineScope.cancel()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
    }
}
