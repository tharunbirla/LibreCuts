package com.tharunbirla.librecuts

import android.widget.ImageView
import kotlinx.coroutines.isActive
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import com.tharunbirla.librecuts.customviews.DraggableTextOverlayView
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.TextPosition
import kotlinx.coroutines.Job
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import com.tharunbirla.librecuts.utils.ErrorCode
import com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    // UI Components
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var frameRecyclerView: RecyclerView
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private lateinit var loadingScreen: View
    private lateinit var exportScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private var textOverlayView: com.tharunbirla.librecuts.customviews.TextOverlayView? = null

    // ViewModel and Services
    private lateinit var viewModel: VideoEditingViewModel
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    // State
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private lateinit var tempInputFile: File
    private var isVideoLoaded = false

    // ── FONT: cached absolute path to Roboto-Regular.ttf in cacheDir ──────────
    // Populated in onCreate via ffmpegEngine.copyFontToCache().
    // Passed to buildConsolidatedFFmpegCommand() so drawtext can find the font.
    private var fontFilePath: String? = null

    // Cache for frame extraction
    private val extractedFrames = mutableListOf<Bitmap>()

    // Inline text editing state
    private var draggableTextOverlay: DraggableTextOverlayView? = null
    private var textEditingToolbar: View? = null
    private var isTextEditingActive = false

    // Segmented preview state
    private var previewJob: Job? = null
    private var isShowingPreview = false
    private var previewFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)

        // Set fullscreen flags
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Initialize ViewModel and engine
        viewModel = ViewModelProvider(this).get(VideoEditingViewModel::class.java)
        ffmpegEngine = FFmpegRenderEngine(this)

        // ── Copy the bundled font from assets → cacheDir so FFmpeg can read it ──
        // The font must exist at:  app/src/main/assets/fonts/Roboto-Regular.ttf
        fontFilePath = ffmpegEngine.copyFontToCache("fonts/Roboto-Regular.ttf")
        Log.d(TAG, "Font path: $fontFilePath")
        Log.d(TAG, "Font file exists: ${fontFilePath?.let { File(it).exists() }}")
        if (fontFilePath == null) {
            Log.e(TAG, "Font copy failed — text overlays will not render. " +
                    "Make sure assets/fonts/Roboto-Regular.ttf exists in the project.")
        }

        // Initialize UI components
        initializeViews()
        setupExoPlayer()
        setupCustomSeeker()
        setupFrameRecyclerView()
        observeViewModelState()

        // Play/Pause button logic
        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
        btnPlayPause.setOnClickListener {
            if (::player.isInitialized && isVideoLoaded) {
                if (player.isPlaying) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                } else {
                    // CHECK: If the player is at the end, seek to the start first
                    if (player.currentPosition >= player.duration) {
                        player.seekTo(0)
                        // Also update your custom UI immediately
                        customVideoSeeker.setSeekPosition(0f)
                        updateDurationDisplay(0, player.duration.toInt())
                    }
                    player.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                }
            }
        }

        // Mute/Unmute button logic (icon only, no function yet)
        val btnMute = findViewById<ImageButton>(R.id.btnMute)
        btnMute.setOnClickListener {
            if (::player.isInitialized) {
                val isCurrentlyMuted = player.volume == 0f
                if (isCurrentlyMuted) {
                    player.volume = 1f
                    btnMute.setImageResource(R.drawable.ic_volume_up_24)
                    viewModel.project.value?.let { proj ->
                        val muteOp = proj.operations.find { it is EditOperation.MuteAudio } as? EditOperation.MuteAudio
                        if (muteOp != null) {
                            viewModel.removeOperation(muteOp.id)
                        }
                    }
                } else {
                    player.volume = 0f
                    btnMute.setImageResource(R.drawable.ic_volume_off_24)
                    viewModel.addMuteAudioOperation()
                }
            }
        }

        // Update play/pause button icon on playback state change
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                } else {
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                }
            }
        })
    }

    private fun showProErrorDialog(errorCode: ErrorCode, technicalLog: String) {
        val message = "${errorCode.description}\n\nError Code: ${errorCode.code}"

        MaterialAlertDialogBuilder(this)
            .setTitle("Something Went Wrong")
            .setMessage(message)
            .setNeutralButton("Copy Log") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Error Log", technicalLog)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            }
            .setPositiveButton("Report on GitHub") { _, _ ->
                val githubUrl = "https://github.com/tharunbirla/librecuts/issues/new?title=" +
                        "Error ${errorCode.code}&body=${technicalLog.take(1000)}"
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
            }
            .setNegativeButton("Close") { dialog, _ -> dialog.dismiss() }
            .setCancelable(false)
            .show()
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        frameRecyclerView = findViewById(R.id.frameRecyclerView)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)
        loadingScreen = findViewById(R.id.loadingScreen)
        exportScreen = findViewById(R.id.exportScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)

        textOverlayView = try {
            findViewById(R.id.textOverlayView)
        } catch (e: Exception) {
            Log.w(TAG, "TextOverlayView not found in layout: ${e.message}")
            null
        }

        // Inline text editing components
        draggableTextOverlay = try {
            findViewById<DraggableTextOverlayView>(R.id.draggableTextOverlay)?.also { overlay ->
                overlay.onTextCommitted = { text, fontSize, relX, relY, color ->
                    viewModel.addTextOperation(
                        text = text,
                        fontSize = fontSize,
                        position = "Center Align",
                        relativeX = relX,
                        relativeY = relY,
                        color = color
                    )
                    exitTextEditingMode()
                    renderSegmentedPreview()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DraggableTextOverlayView not found: ${e.message}")
            null
        }

        textEditingToolbar = try {
            findViewById<View>(R.id.textEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnTextCancel)?.setOnClickListener {
                    draggableTextOverlay?.deactivate()
                    exitTextEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnTextDone)?.setOnClickListener {
                    draggableTextOverlay?.commitText()
                }

                val btnKeyboard = toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)
                val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
                val colorContainer = toolbar.findViewById<View>(R.id.colorPickerContainer)

                btnKeyboard?.setOnClickListener {
                    colorContainer?.visibility = View.GONE
                    btnKeyboard.setColorFilter(getColor(R.color.colorPrimary))
                    btnPalette?.setColorFilter(getColor(R.color.toolTextInactive))
                    draggableTextOverlay?.requestEditingFocus()
                }

                btnPalette?.setOnClickListener {
                    val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(toolbar.windowToken, 0)

                    colorContainer?.visibility = View.VISIBLE
                    btnPalette.setColorFilter(getColor(R.color.colorPrimary))
                    btnKeyboard?.setColorFilter(getColor(R.color.toolTextInactive))
                    setupColorPicker(toolbar)
                }

                btnKeyboard?.setColorFilter(getColor(R.color.colorPrimary))
                btnPalette?.setColorFilter(getColor(R.color.toolTextInactive))
                colorContainer?.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text editing toolbar not found: ${e.message}")
            null
        }

        findViewById<ImageButton>(R.id.btnHome).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        findViewById<com.google.android.material.button.MaterialButton>(R.id.btnSave).setOnClickListener {
            saveAction()
        }

        // Tool Buttons with proper scoping
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener {
            setActiveToolButton(R.id.btnTrim)
            trimAction()
        }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener {
            setActiveToolButton(R.id.btnText)
            textAction()
        }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener {
            setActiveToolButton(R.id.btnAudio)
            audioAction()
        }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener {
            setActiveToolButton(R.id.btnCrop)
            cropAction()
        }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener {
            setActiveToolButton(R.id.btnMerge)
            mergeAction()
        }

        try {
            btnUndo = findViewById(R.id.btnUndo)
            btnRedo = findViewById(R.id.btnRedo)
            btnUndo.setOnClickListener { viewModel.undo() }
            btnRedo.setOnClickListener { viewModel.redo() }
        } catch (e: Exception) {
            Log.d(TAG, "Undo/Redo buttons not found in layout (optional)")
        }

        try {
            lottieAnimationView.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Error loading Lottie animation: ${e.message}")
        }
    }

    private fun setActiveToolButton(activeId: Int) {
        val toolIds = listOf(R.id.btnTrim, R.id.btnText, R.id.btnAudio, R.id.btnCrop, R.id.btnMerge)
        for (id in toolIds) {
            val btn = findViewById<ImageButton>(id)
            btn.setBackgroundResource(if (id == activeId) R.drawable.tool_button_active else R.drawable.tool_button_inactive)
            btn.setColorFilter(
                if (id == activeId) resources.getColor(R.color.toolTextActive, null)
                else resources.getColor(R.color.toolTextInactive, null)
            )
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                if (!uiState.isExporting) {
                    if (uiState.isExporting) {
                        loadingScreen.visibility = View.VISIBLE
                        lottieAnimationView.playAnimation()
                    } else if (!isVideoLoaded) {
                        loadingScreen.visibility = View.VISIBLE
                    } else {
                        loadingScreen.visibility = View.GONE
                    }
                }

                exportScreen.visibility = if (uiState.isExporting) View.VISIBLE else View.GONE

                if (::btnUndo.isInitialized && ::btnRedo.isInitialized) {
                    btnUndo.isEnabled = uiState.canUndo
                    btnUndo.alpha = if (uiState.canUndo) 1.0f else 0.5f
                    btnRedo.isEnabled = uiState.canRedo
                    btnRedo.alpha = if (uiState.canRedo) 1.0f else 0.5f
                }

                uiState.errorMessage?.let { error ->
                    showError(error)
                    viewModel.clearError()
                }

                if (uiState.pendingOperationCount > 0) {
                    Log.d(TAG, "Pending operations: ${uiState.pendingOperationCount}")
                }
            }
        }

        lifecycleScope.launch {
            viewModel.project.collect { project ->
                if (project != null) {
                    Log.d(TAG, "Project updated with ${project.getOperationCount()} operations")

                    textOverlayView?.let { overlay ->
                        val textOps = project.operations.filterIsInstance<EditOperation.AddText>()
                        overlay.setTextOperations(textOps)
                    }

                    val cropOps = project.operations.filterIsInstance<EditOperation.Crop>()
                    if (cropOps.isNotEmpty()) {
                        applyCropPreview(cropOps.last().aspectRatio)
                    } else {
                        resetCropPreview()
                    }
                }
            }
        }
    }

    private fun applyCropPreview(aspectRatio: String) {
        Toast.makeText(this, "Crop $aspectRatio will be applied on export", Toast.LENGTH_SHORT).show()
    }

    private fun resetCropPreview() {
        // No-op
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        val videoDuration = player.duration
        if (videoDuration <= 0) return

        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        // Set UI colors from your theme
        rangeSlider.trackActiveTintList = getColorStateList(R.color.colorPrimary)
        rangeSlider.thumbTintList = getColorStateList(R.color.colorPrimary)

        val totalSeconds = (videoDuration / 1000).toFloat()
        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = maxOf(100f, totalSeconds)
        rangeSlider.values = listOf(0f, totalSeconds)
        rangeSlider.valueTo = totalSeconds

        rangeSlider.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                // PRO FEATURE: Live seeking while trimming
                // If user moves start handle, seek to start. If end handle, seek to end.
                val thumbIndex = slider.activeThumbIndex
                if (thumbIndex in 0 until slider.values.size) {
                    val seekTargetMs = slider.values[thumbIndex].toLong() * 1000
                    player.seekTo(seekTargetMs)
                }
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val startMs = rangeSlider.values[0].toLong() * 1000
            val endMs = rangeSlider.values[1].toLong() * 1000
            viewModel.addTrimOperation(startMs, endMs)
            updatePreviewWithClipping(startMs, endMs)
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun updatePreviewWithClipping(startMs: Long, endMs: Long) {
        videoUri?.let {
            try {
                val mediaItem = MediaItem.Builder()
                    .setUri(it)
                    .setClipStartPositionMs(startMs)
                    .setClipEndPositionMs(endMs)
                    .build()
                player.setMediaItem(mediaItem)
                player.prepare()
            } catch (e: Exception) {
                Log.w(TAG, "Clipping not supported, falling back to seek: ${e.message}")
                player.seekTo(startMs)
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun cropAction() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)

        // 1. Determine current active ratio from ViewModel
        val currentRatio = viewModel.project.value?.operations
            ?.filterIsInstance<EditOperation.Crop>()
            ?.lastOrNull()?.aspectRatio ?: "Original"

        // 2. Helper function to update UI state
        fun updateActiveUi(ratio: String) {
            val ratios = mapOf(
                "16:9" to Triple(R.id.bg16_9, R.id.ic16_9, R.id.txt16_9),
                "9:16" to Triple(R.id.bg9_16, R.id.ic9_16, R.id.txt9_16),
                "1:1" to Triple(R.id.bg1_1, R.id.ic1_1, R.id.txt1_1)
            )

            ratios.forEach { (key, views) ->
                val isActive = key == ratio
                sheetView.findViewById<View>(views.first).setBackgroundResource(
                    if (isActive) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
                )
                sheetView.findViewById<ImageView>(views.second).setColorFilter(
                    resources.getColor(if (isActive) R.color.onPrimaryContainer else R.color.iconSecondary, null)
                )
                sheetView.findViewById<TextView>(views.third).apply {
                    setTextColor(resources.getColor(if (isActive) R.color.activeTool else R.color.toolTextInactive, null))
                    paint.isFakeBoldText = isActive
                }
            }
        }

        // Initialize UI with current state
        updateActiveUi(currentRatio)

        // 3. Click Listeners
        sheetView.findViewById<LinearLayout>(R.id.frameAspectRatio1).setOnClickListener {
            viewModel.addCropOperation("16:9")
            updateActiveUi("16:9")
            bottomSheetDialog.dismiss()
            renderSegmentedPreview()
        }

        sheetView.findViewById<LinearLayout>(R.id.frameAspectRatio2).setOnClickListener {
            viewModel.addCropOperation("9:16")
            updateActiveUi("9:16")
            bottomSheetDialog.dismiss()
            renderSegmentedPreview()
        }

        sheetView.findViewById<LinearLayout>(R.id.frameAspectRatio3).setOnClickListener {
            viewModel.addCropOperation("1:1")
            updateActiveUi("1:1")
            bottomSheetDialog.dismiss()
            renderSegmentedPreview()
        }

        sheetView.findViewById<ImageButton>(R.id.btnCloseSheet).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    @SuppressLint("InflateParams")
    private fun textAction() {
        if (isTextEditingActive) {
            // Already editing — commit current text
            draggableTextOverlay?.commitText()
            return
        }
        enterTextEditingMode()
    }

    private fun enterTextEditingMode() {
        isTextEditingActive = true
        selectedTextColor = "#FFFFFF"
        draggableTextOverlay?.activate("", 36)
        textEditingToolbar?.visibility = View.VISIBLE
        textEditingToolbar?.let { toolbar ->
            toolbar.findViewById<View>(R.id.colorPickerContainer)?.visibility = View.GONE
            toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)?.setColorFilter(getColor(R.color.colorPrimary))
            toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)?.setColorFilter(getColor(R.color.toolTextInactive))
            setupColorPicker(toolbar)
        }
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitTextEditingMode() {
        isTextEditingActive = false
        textEditingToolbar?.visibility = View.GONE
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.VISIBLE
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
        if (requestCode == PICK_VIDEO_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.let {
                val selectedVideoUris = mutableListOf<Uri>()
                if (it.clipData != null) {
                    val itemCount = it.clipData!!.itemCount
                    for (i in 0 until itemCount) {
                        selectedVideoUris.add(it.clipData!!.getItemAt(i).uri)
                    }
                } else {
                    it.data?.let { uri -> selectedVideoUris.add(uri) }
                }
                if (selectedVideoUris.isNotEmpty()) {
                    viewModel.addMergeOperation(selectedVideoUris)
                    Toast.makeText(this, "Merge operation added (pending)", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (requestCode == PICK_AUDIO_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { audioUri ->
                showAudioConfigDialog(audioUri)
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

    private fun showAudioConfigDialog(audioUri: Uri) {
        lifecycleScope.launch {
            val tempAudioFile = withContext(Dispatchers.IO) {
                copyContentUriToTempFile(audioUri, "audio")
            }

            if (tempAudioFile == null) {
                Toast.makeText(this@VideoEditingActivity, "Failed to load audio file", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val tempAudioUri = Uri.fromFile(tempAudioFile)

            // Get audio file duration
            val audioDuration = try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(tempAudioFile.absolutePath)
                val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durStr?.toLong() ?: 0L
            } catch (e: Exception) {
                Log.e(TAG, "Error reading audio duration: ${e.message}")
                0L
            }

            if (audioDuration <= 0L) {
                Toast.makeText(this@VideoEditingActivity, "Invalid audio file", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Inflate sheet layout
            val bottomSheet = BottomSheetDialog(this@VideoEditingActivity)
            val view = layoutInflater.inflate(R.layout.audio_trim_bottom_sheet_dialog, null)
            bottomSheet.setContentView(view)

            val tvProgress = view.findViewById<TextView>(R.id.tvAudioProgress)
            val rangeSlider = view.findViewById<com.google.android.material.slider.RangeSlider>(R.id.audioRangeSlider)
            val switchReplace = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchReplaceAudio)
            val volumeSlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
            val tvVolumePercent = view.findViewById<TextView>(R.id.tvAudioVolumePercent)
            val btnPlayPause = view.findViewById<ImageButton>(R.id.btnAudioPlayPause)
            val btnDone = view.findViewById<Button>(R.id.btnDoneAudio)
            val btnClose = view.findViewById<ImageButton>(R.id.btnCloseAudioSheet)

            val videoDurationMs = if (::player.isInitialized) player.duration else 0L

            // Configure rangeSlider
            val initialEnd = minOf(audioDuration, videoDurationMs).toFloat()
            rangeSlider.valueFrom = 0f
            rangeSlider.valueTo = maxOf(100f, audioDuration.toFloat())
            rangeSlider.values = listOf(0f, initialEnd)
            rangeSlider.valueTo = audioDuration.toFloat()

            var startMs = 0L
            var endMs = initialEnd.toLong()

            var lastStartMs = startMs
            var lastEndMs = endMs

            fun updateTimeDisplay() {
                val duration = endMs - startMs
                tvProgress.text = "${formatDuration(startMs.toInt())} - ${formatDuration(endMs.toInt())} (${formatDuration(duration.toInt())})"
            }
            updateTimeDisplay()

            // Media Player Setup for Preview
            var mediaPlayer: android.media.MediaPlayer? = null
            var isPlaying = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())

            val checkPlayback = object : Runnable {
                override fun run() {
                    mediaPlayer?.let { mp ->
                        if (mp.isPlaying) {
                            val currentPos = mp.currentPosition.toLong()
                            if (currentPos >= endMs || currentPos < startMs) {
                                mp.seekTo(startMs.toInt())
                            }
                            handler.postDelayed(this, 100)
                        }
                    }
                }
            }

            fun stopPlayback() {
                isPlaying = false
                btnPlayPause.setImageResource(R.drawable.ic_play_24)
                handler.removeCallbacks(checkPlayback)
                try {
                    mediaPlayer?.pause()
                } catch (_: Exception) {}
            }

            fun startPlayback() {
                if (mediaPlayer == null) {
                    try {
                        mediaPlayer = android.media.MediaPlayer().apply {
                            setDataSource(tempAudioFile.absolutePath)
                            prepare()
                            isLooping = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error preparing MediaPlayer: ${e.message}")
                        return
                    }
                }
                isPlaying = true
                btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                val vol = volumeSlider.value
                mediaPlayer?.setVolume(vol, vol)
                mediaPlayer?.seekTo(startMs.toInt())
                mediaPlayer?.start()
                handler.post(checkPlayback)
            }

            btnPlayPause.setOnClickListener {
                if (isPlaying) {
                    stopPlayback()
                } else {
                    startPlayback()
                }
            }

            rangeSlider.addOnChangeListener { slider, _, fromUser ->
                if (fromUser) {
                    val values = slider.values
                    var start = values[0]
                    var end = values[1]

                    if (end - start > videoDurationMs) {
                        // Enforce select segment length does not exceed videoDurationMs
                        if (start != lastStartMs.toFloat()) {
                            // Left thumb (start) was dragged: adjust end
                            end = (start + videoDurationMs).coerceAtMost(audioDuration.toFloat())
                            start = (end - videoDurationMs).coerceAtLeast(0f)
                        } else {
                            // Right thumb (end) was dragged: adjust start
                            start = (end - videoDurationMs).coerceAtLeast(0f)
                        }
                        slider.values = listOf(start, end)
                    }

                    startMs = start.toLong()
                    endMs = end.toLong()
                    lastStartMs = startMs
                    lastEndMs = endMs

                    updateTimeDisplay()

                    if (isPlaying) {
                        mediaPlayer?.seekTo(startMs.toInt())
                    }
                }
            }

            volumeSlider.addOnChangeListener { _, value, _ ->
                val percent = (value * 100).toInt()
                tvVolumePercent.text = "$percent%"
                mediaPlayer?.setVolume(value, value)
            }

            btnClose.setOnClickListener {
                bottomSheet.dismiss()
            }

            btnDone.setOnClickListener {
                stopPlayback()
                val replaceOriginal = switchReplace?.isChecked ?: false
                viewModel.addBackgroundAudioOperation(
                    audioUri = tempAudioUri,
                    removeOriginalAudio = replaceOriginal,
                    delayMs = 0L,
                    volume = volumeSlider.value,
                    startMs = startMs,
                    endMs = endMs
                )
                Toast.makeText(this@VideoEditingActivity, "Background audio added", Toast.LENGTH_SHORT).show()
                bottomSheet.dismiss()
            }

            bottomSheet.setOnDismissListener {
                stopPlayback()
                try {
                    mediaPlayer?.release()
                } catch (_: Exception) {}
                mediaPlayer = null
            }

            bottomSheet.show()
        }
    }

    /**
     * SAVE/EXPORT: Consolidates all pending operations into a single FFmpeg command and runs it.
     *
     * KEY FIX: fontFilePath (populated in onCreate from assets/fonts/Roboto-Regular.ttf)
     * is now passed to buildConsolidatedFFmpegCommand so drawtext gets a valid fontfile= path.
     */
    private fun saveAction() {
        val project = viewModel.project.value
        if (project == null) {
            showError("No project loaded")
            return
        }

        if (!project.hasOperations()) {
            exportVideoFile(videoUri!!)
            return
        }

        // Warn but don't block — export will just skip text rendering if font is missing
        if (fontFilePath == null) {
            Log.w(TAG, "fontFilePath is null — text overlays will be skipped. " +
                    "Check that assets/fonts/Roboto-Regular.ttf exists.")
        }

        lifecycleScope.launch {
            try {
                viewModel.startExport()

                val sourceFilePath = tempInputFile.absolutePath
                val tempOutputFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
                val tempOutputPath = tempOutputFile.absolutePath

                // ── THE FIX: pass fontFilePath as the third argument ──────────────
                var ffmpegCommand = viewModel.buildConsolidatedFFmpegCommand(
                    sourceFilePath = sourceFilePath,
                    outputFilePath = tempOutputPath,
                    fontFilePath = fontFilePath          // was missing before — caused "No font filename provided"
                )

                if (ffmpegCommand == null) {
                    viewModel.exportError("Failed to build FFmpeg command")
                    return@launch
                }

                Log.d(TAG, "Raw FFmpeg command: $ffmpegCommand")

                // Handle merge operations
                var concatFile: File? = null
                val currentProject = viewModel.project.value
                if (currentProject != null && ffmpegCommand.contains("(CONCAT_LIST:")) {
                    val cmdSnapshot = ffmpegCommand  // capture for closure — var can't be smart-cast
                    concatFile = withContext(Dispatchers.IO) {
                        val concatListStart = cmdSnapshot.indexOf("(CONCAT_LIST:") + "(CONCAT_LIST:".length
                        val concatListEnd = cmdSnapshot.lastIndexOf(")")
                        if (concatListStart > 13 && concatListEnd > concatListStart) {
                            val concatList = cmdSnapshot.substring(concatListStart, concatListEnd)
                            val processedConcatList = processConcatList(concatList)

                            val file = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
                            file.writeText(processedConcatList)
                            Log.d(TAG, "Concat file: ${file.absolutePath}")
                            Log.d(TAG, "Concat content:\n$processedConcatList")
                            file
                        } else null
                    }

                    if (concatFile != null) {
                        ffmpegCommand = ffmpegCommand
                            .replace("{CONCAT_FILE_PATH}", concatFile.absolutePath)
                            .substring(0, ffmpegCommand.indexOf("(CONCAT_LIST:"))
                            .trim()
                    }
                }

                Log.d(TAG, "Final FFmpeg command: $ffmpegCommand")

                val result = ffmpegEngine.exportFinal(ffmpegCommand)

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        val savedUri = saveVideoToGallery(tempOutputFile)
                        if (savedUri != null) {
                            viewModel.finishExport()
                            Toast.makeText(
                                this@VideoEditingActivity,
                                "Video exported to Gallery successfully!",
                                Toast.LENGTH_LONG
                            ).show()
                            Log.d(TAG, "Export successful: $savedUri")
                            tempOutputFile.delete()
                            concatFile?.delete()
                        } else {
                            viewModel.exportError("Failed to save video to Gallery")
                            Toast.makeText(
                                this@VideoEditingActivity,
                                "Failed to save video to Gallery",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        viewModel.exportError(result.error)
                        showProErrorDialog(ErrorCode.FFMPEG_EXECUTION_FAILED, result.error)
                        Toast.makeText(
                            this@VideoEditingActivity,
                            "Export failed: ${result.error.take(200)}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    FFmpegRenderEngine.RenderResult.Cancelled -> {
                        viewModel.exportError("Export cancelled")
                        Toast.makeText(this@VideoEditingActivity, "Export cancelled", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                viewModel.exportError(e.message ?: "Unknown error")
                Log.e(TAG, "Export exception: ${e.message}", e)
            }
        }
    }

    private fun exportVideoFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                viewModel.startExport()

                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!outputDir.exists()) outputDir.mkdirs()

                val outputFile = File(outputDir, "video_${System.currentTimeMillis()}.mp4")
                File(tempInputFile.absolutePath).copyTo(outputFile, overwrite = true)

                viewModel.finishExport()
                Toast.makeText(
                    this@VideoEditingActivity,
                    "Video exported: ${outputFile.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                viewModel.exportError(e.message ?: "Export failed")
            }
        }
    }

    private fun setupExoPlayer() {
        videoUri = intent.getParcelableExtra("VIDEO_URI")
        if (videoUri != null) {
            player = ExoPlayer.Builder(this).build()
            playerView.player = player

            val mediaItem = MediaItem.fromUri(videoUri!!)
            player.setMediaItem(mediaItem)
            loadingScreen.visibility = View.VISIBLE
            player.prepare()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // Reset UI components when video reaches the end
                        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                        customVideoSeeker.setSeekPosition(1.0f) // Keep it at the end visually

                        // OPTIONAL: If you want the seeker to jump back to 0 immediately
                        // once it finishes, uncomment the next lines:
                        // player.seekTo(0)
                        // customVideoSeeker.setSeekPosition(0f)

                        stopProgressUpdater()
                    }
                    if (state == Player.STATE_READY) {
                        isVideoLoaded = true
                        customVideoSeeker.setVideoDuration(player.duration)
                        updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())

                        val format = player.videoFormat
                        if (format != null && format.width > 0 && format.height > 0) {
                            textOverlayView?.setVideoSize(format.width, format.height)
                            draggableTextOverlay?.setVideoSize(format.width, format.height)
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        startProgressUpdater()
                    } else {
                        stopProgressUpdater()
                    }
                }

                override fun onVideoSizeChanged(videoSize: com.google.android.exoplayer2.video.VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        textOverlayView?.setVideoSize(videoSize.width, videoSize.height)
                        draggableTextOverlay?.setVideoSize(videoSize.width, videoSize.height)
                    }
                }

                override fun onPositionDiscontinuity(reason: Int) {
                    // Update UI immediately on manual seek
                    if (isVideoLoaded) {
                        syncUiWithPlayer()
                    }
                }
            })

            initializeVideoData()
            val displayName = videoUri!!.lastPathSegment ?: "video"
            viewModel.initializeProject(videoUri!!, displayName)
        }
    }

    private fun startProgressUpdater() {
        customVideoSeeker.removeCallbacks(updateSeekerRunnable)
        customVideoSeeker.post(updateSeekerRunnable)
    }

    private fun stopProgressUpdater() {
        customVideoSeeker.removeCallbacks(updateSeekerRunnable)
    }

    private fun syncUiWithPlayer() {
        val currentPos = player.currentPosition
        val duration = player.duration
        if (duration > 0) {
            val progress = currentPos.toFloat() / duration
            customVideoSeeker.setSeekPosition(progress)
            updateDurationDisplay(currentPos.toInt(), duration.toInt())
        }
    }

    // Update your existing Runnable to include the text display update
    private val updateSeekerRunnable = object : Runnable {
        override fun run() {
            if (::player.isInitialized && player.isPlaying) {
                syncUiWithPlayer()
                // 50ms (20fps) is a good balance for smooth seeker movement
                // without slamming the main thread
                customVideoSeeker.postDelayed(this, 50)
            }
        }
    }

    private fun initializeVideoData() {
        lifecycleScope.launch {
            try {
                val videoFilePath = getFilePathFromUri(videoUri!!)
                if (videoFilePath != null) {
                    tempInputFile = File(videoFilePath)
                    videoFileName = tempInputFile.name
                    extractVideoFrames()
                }
            } catch (e: Exception) {
                showError("Error initializing video: ${e.message}")
            }
        }
    }

    private fun getFilePathFromUri(uri: Uri): String? {
        var filePath: String? = null
        when (uri.scheme) {
            "content" -> {
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val dataIndex = it.getColumnIndex(MediaStore.Video.Media.DATA)
                        if (dataIndex != -1) filePath = it.getString(dataIndex)
                        else Log.e("PathError", "Column '_data' not found in cursor")
                    } else {
                        Log.e("PathError", "Cursor is empty for URI: $uri")
                    }
                } ?: Log.e("PathError", "Cursor is null for URI: $uri")
            }
            "file" -> filePath = uri.path
            else -> Log.e("PathError", "Unsupported URI scheme: ${uri.scheme}")
        }
        Log.d("PathInfo", "File path: $filePath")
        return filePath
    }

    private fun setupCustomSeeker() {
        customVideoSeeker.onSeekListener = { seekPosition ->
            // Dismiss preview when user manually seeks
            if (isShowingPreview) dismissPreview()

            val newSeekTime = (player.duration * seekPosition).toLong()
            if (newSeekTime >= 0 && newSeekTime <= player.duration) {
                player.seekTo(newSeekTime)
                updateDurationDisplay(newSeekTime.toInt(), player.duration.toInt())
            } else {
                Log.d("SeekError", "Seek position out of bounds.")
            }
        }
    }

    private fun setupFrameRecyclerView() {
        frameRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        frameRecyclerView.adapter = FrameAdapter(emptyList())
    }

    // Inside VideoEditingActivity
    private var frameExtractionJob: kotlinx.coroutines.Job? = null

    private fun extractVideoFrames() {
        frameExtractionJob?.cancel()

        // Ensure loading screen is visible at the start
        loadingScreen.visibility = View.VISIBLE

        frameExtractionJob = lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val tempFrames = mutableListOf<Bitmap>()
            try {
                retriever.setDataSource(tempInputFile.absolutePath)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val videoDurationMs = durationStr?.toLong() ?: 0L

                if (videoDurationMs <= 0) return@launch

                val frameCount = 10
                val intervalUs = (videoDurationMs * 1000) / frameCount

                // Process all frames in the background WITHOUT updating the UI partially
                for (i in 0 until frameCount) {
                    if (!coroutineContext.isActive) break

                    val timeUs = i * intervalUs

                    // Hardware-accelerated scaling
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST, 200, 150)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    }

                    bitmap?.let {
                        val finalBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) it else processBitmap(it)
                        tempFrames.add(finalBitmap)
                    }
                }

                // ONLY update the UI once the loop is completely finished
                withContext(Dispatchers.Main) {
                    // FIX: Recycle OLD bitmaps before replacing to prevent memory leak
                    extractedFrames.forEach { if (!it.isRecycled) it.recycle() }
                    extractedFrames.clear()
                    extractedFrames.addAll(tempFrames)
                    frameRecyclerView.adapter = FrameAdapter(extractedFrames)

                    // FINALLY: Hide the loading screen now that everything is visible
                    loadingScreen.visibility = View.GONE
                }

            } catch (e: Exception) {
                Log.e(TAG, "Extraction error: ${e.message}")
                // FIX: Recycle tempFrames on error — they won't reach the UI
                tempFrames.forEach { if (!it.isRecycled) it.recycle() }
                withContext(Dispatchers.Main) { loadingScreen.visibility = View.GONE }
            } finally {
                retriever.release()
                // FIX: If cancelled mid-extraction, recycle orphaned bitmaps
                if (!coroutineContext.isActive) {
                    tempFrames.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    // Helper to keep scaling logic clean and maintain aspect ratio
    private fun processBitmap(source: Bitmap): Bitmap {
        val aspectRatio = source.width.toFloat() / source.height.toFloat()
        val targetHeight = 150
        val targetWidth = (targetHeight * aspectRatio).toInt()
        return Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true)
    }

    @SuppressLint("SetTextI18n")
    private fun updateDurationDisplay(current: Int, total: Int) {
        if (!isVideoLoaded || total <= 0) return
        tvDuration.text = "${formatDuration(current)} / ${formatDuration(total)}"
    }

    private fun formatDuration(milliseconds: Int): String {
        val minutes = milliseconds / 60000
        val seconds = (milliseconds % 60000) / 1000
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun showError(error: String) {
        Log.e(TAG, error)
        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
    }

    private fun saveVideoToGallery(videoFile: File): Uri? {
        return try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "LibreCuts_${System.currentTimeMillis()}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES)
            }
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { output ->
                    videoFile.inputStream().use { input -> input.copyTo(output) }
                }
                it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to gallery: ${e.message}", e)
            null
        }
    }

    private fun processConcatList(concatList: String): String {
        val lines = concatList.trim().split("\n")
        val processedLines = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("file")) {
                val pathStart = line.indexOf("'") + 1
                val pathEnd = line.lastIndexOf("'")
                if (pathStart > 0 && pathEnd > pathStart) {
                    val filePath = line.substring(pathStart, pathEnd)
                    val processedPath = if (filePath.startsWith("content://")) {
                        copyContentUriToTempFile(Uri.parse(filePath))?.absolutePath ?: filePath
                    } else {
                        filePath
                    }
                    processedLines.add("file '$processedPath'")
                }
            } else if (line.isNotEmpty()) {
                processedLines.add(line)
            }
        }

        return processedLines.joinToString("\n").trim() + "\n"
    }

    private fun getAudioExtension(uri: Uri): String {
        val mimeType = contentResolver.getType(uri)
        if (mimeType != null) {
            val mime = android.webkit.MimeTypeMap.getSingleton()
            val extension = mime.getExtensionFromMimeType(mimeType)
            if (extension != null) {
                return ".$extension"
            }
        }
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        val name = cursor.getString(nameIndex)
                        val lastDot = name.lastIndexOf('.')
                        if (lastDot != -1) {
                            return name.substring(lastDot)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return ".mp3"
    }

    private fun copyContentUriToTempFile(contentUri: Uri): File? {
        return copyContentUriToTempFile(contentUri, "merge_video", ".mp4")
    }

    private fun copyContentUriToTempFile(contentUri: Uri, prefix: String, extension: String = ".mp4"): File? {
        return try {
            val ext = if (prefix == "audio") {
                getAudioExtension(contentUri)
            } else {
                extension
            }
            val tempFile = File(cacheDir, "${prefix}_${System.currentTimeMillis()}$ext")
            contentResolver.openInputStream(contentUri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.d(TAG, "Copied content URI to temp file: ${tempFile.absolutePath}")
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying content URI to temp file: ${e.message}", e)
            null
        }
    }

    // ── Segmented Preview Rendering ────────────────────────────────────────────

    /**
     * Render a fast 3-second preview segment around the current playhead.
     * Swaps ExoPlayer's source to the preview clip for immediate visual feedback.
     */
    private fun renderSegmentedPreview() {
        if (!::tempInputFile.isInitialized || !isVideoLoaded) return

        previewJob?.cancel()
        previewJob = lifecycleScope.launch {
            val seekPos = if (::player.isInitialized) player.currentPosition else 0L
            val previewOutput = File(cacheDir, "preview_segment_${System.currentTimeMillis()}.mp4")

            val cmd = viewModel.buildPreviewCommand(
                sourceFilePath = tempInputFile.absolutePath,
                previewOutputPath = previewOutput.absolutePath,
                seekPositionMs = seekPos,
                fontFilePath = fontFilePath
            ) ?: return@launch

            Log.d(TAG, "Rendering segmented preview...")

            val result = withContext(Dispatchers.IO) {
                ffmpegEngine.executeCommand(cmd)
            }

            if (result is FFmpegRenderEngine.RenderResult.Success && previewOutput.exists()) {
                isShowingPreview = true
                previewFile?.delete() // Clean up previous preview
                previewFile = previewOutput

                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(previewOutput)))
                player.prepare()
                player.play()

                // Show preview badge
                try {
                    findViewById<TextView>(R.id.tvPreviewBadge)?.visibility = View.VISIBLE
                } catch (_: Exception) {}
            } else {
                Log.w(TAG, "Preview render failed or was cancelled")
                previewOutput.delete()
            }
        }
    }

    /**
     * Dismiss the preview and restore the original video source.
     */
    private fun dismissPreview() {
        if (!isShowingPreview) return
        isShowingPreview = false
        previewJob?.cancel()

        videoUri?.let {
            player.setMediaItem(MediaItem.fromUri(it))
            player.prepare()
        }

        try {
            findViewById<TextView>(R.id.tvPreviewBadge)?.visibility = View.GONE
        } catch (_: Exception) {}

        previewFile?.delete()
        previewFile = null
    }

    override fun onDestroy() {
        frameExtractionJob?.cancel()
        previewJob?.cancel()
        extractedFrames.forEach { if (!it.isRecycled) it.recycle() }
        draggableTextOverlay?.deactivate()
        previewFile?.delete()
        super.onDestroy()
        player.release()
        ffmpegEngine.cleanup()
    }

    private val colorsList = listOf(
        "#FFFFFF", "#000000", "#FF3B30", "#FF9500", "#FFCC00",
        "#34C759", "#30B0C7", "#007AFF", "#5856D6", "#AF52DE", "#FF2D55"
    )
    private var selectedTextColor = "#FFFFFF"

    private fun setupColorPicker(toolbar: View) {
        val colorPickerList = toolbar.findViewById<LinearLayout>(R.id.colorPickerList) ?: return
        colorPickerList.removeAllViews()

        val density = resources.displayMetrics.density
        val sizePx = (36 * density).toInt()
        val marginPx = (8 * density).toInt()

        for (colorHex in colorsList) {
            val colorView = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    setMargins(marginPx, 0, marginPx, 0)
                }

                val shape = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(android.graphics.Color.parseColor(colorHex))
                    if (colorHex == selectedTextColor) {
                        setStroke((3 * density).toInt(), android.graphics.Color.parseColor("#007AFF"))
                    } else {
                        if (colorHex == "#FFFFFF" || colorHex == "#000000") {
                            setStroke(1, android.graphics.Color.GRAY)
                        }
                    }
                }
                background = shape

                setOnClickListener {
                    selectedTextColor = colorHex
                    draggableTextOverlay?.setTextColor(colorHex)
                    setupColorPicker(toolbar)
                }
            }
            colorPickerList.addView(colorView)
        }
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
        private const val PICK_AUDIO_REQUEST = 2
    }
}