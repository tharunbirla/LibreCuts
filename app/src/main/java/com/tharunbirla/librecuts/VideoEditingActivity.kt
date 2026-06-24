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
import com.tharunbirla.librecuts.utils.setBounceClickListener
import com.tharunbirla.librecuts.utils.performHapticLight
import com.tharunbirla.librecuts.utils.performHapticClick
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
import com.tharunbirla.librecuts.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import android.graphics.BitmapFactory
import com.google.android.material.slider.Slider
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import com.tharunbirla.librecuts.customviews.DraggableTextOverlayView
import com.tharunbirla.librecuts.customviews.DraggableImageOverlayView
import com.tharunbirla.librecuts.customviews.ImageOverlayView
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.VideoProject
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
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout


@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    // UI Components
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var sequenceTrackContainer: FrameLayout
    private lateinit var textTrackContainer: LinearLayout
    private lateinit var imageTrackContainer: LinearLayout
    private lateinit var playerContainer: FrameLayout
    private lateinit var audioTrackContainer: LinearLayout
    private lateinit var btnPlayPause: ImageButton

    private lateinit var timelineHorizontalScroll: android.widget.HorizontalScrollView
    private lateinit var timelineContainer: FrameLayout
    private var isUserScrollingTimeline = false
    private var isTrackDragging = false
    private var isProgrammaticScroll = false
    private var pixelsPerMs: Float = 0.3f
    private enum class ZoomMode { FIT, MEDIUM, PRECISION }
    private var currentZoomMode = ZoomMode.MEDIUM
    private lateinit var scaleDetector: android.view.ScaleGestureDetector

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private lateinit var timeRulerView: com.tharunbirla.librecuts.customviews.TimeRulerView
    private lateinit var loadingScreen: View
    private lateinit var exportScreen: View
    private lateinit var previewLoadingOverlay: View
    private lateinit var lottieAnimationView: LottieAnimationView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton
    private lateinit var btnPreview: ImageButton
    private lateinit var layoutSaveSplit: View
    private lateinit var btnSaveText: View
    private lateinit var btnSaveDropdown: View
    private lateinit var editingControlsWrapper: LinearLayout

    private var tvPreviewBadge: TextView? = null
    private var textOverlayView: com.tharunbirla.librecuts.customviews.TextOverlayView? = null

    // ViewModel and Services
    private lateinit var viewModel: VideoEditingViewModel
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    // State
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private lateinit var tempInputFile: File
    private var frameExtractionJob: Job? = null
    private val activeRenderJobs = mutableListOf<Job>()
    private var pendingRenderRunnable: Runnable? = null
    private var isVideoLoaded = false
    private var isImportLoading = true
    private var activeExtractionCount = 0
    private var chunkDurationsMs = listOf<Long>()
    private var originalMainVideoDurationMs: Long = 0L
    private val frameCache = mutableMapOf<Uri, List<Bitmap>>()

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

    // Inline image overlay editing state
    private var draggableImageOverlay: DraggableImageOverlayView? = null
    private var imageOverlayView: ImageOverlayView? = null
    private var imageEditingToolbar: View? = null
    private var isImageEditingActive = false

    // Video layer selection state
    private var selectedVideoIndex: Int? = null
    private var videoEditingToolbar: View? = null
    private var audioEditingToolbar: View? = null
    private var speedEditingToolbar: View? = null
    private var cropEditingToolbar: View? = null

    // Segmented preview state
    private var previewJob: Job? = null
    private var isShowingPreview = false
    private var isRenderingPreview = false
    private var previewFile: File? = null
    
    private var audioPreviewPlayer: android.media.MediaPlayer? = null
    
    private var isInitialFitDone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)
        pixelsPerMs = 0.15f * resources.displayMetrics.density

        // Set fullscreen flags
        window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )

        // Initialize ViewModel and engine
        viewModel = ViewModelProvider(this).get(VideoEditingViewModel::class.java)
        ffmpegEngine = FFmpegRenderEngine(this)

        // Register back-press callback to prompt for quit confirmation
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showQuitConfirmationDialog()
            }
        })

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
        observeViewModelState()

        // Play/Pause button logic
        btnPlayPause.setBounceClickListener {
            if (::player.isInitialized && isVideoLoaded) {
                if (player.isPlaying) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                } else {
                    // CHECK: If the player is outside active trim bounds, seek to the active start first
                    val trimOp = viewModel.project.value?.operations?.filterIsInstance<EditOperation.Trim>()?.lastOrNull()
                    val startMs = trimOp?.startMs ?: 0L
                    val endMs = trimOp?.endMs ?: player.duration
                    if (player.currentPosition < startMs || player.currentPosition >= endMs) {
                        player.seekTo(startMs)
                        timelineHorizontalScroll.scrollTo((startMs * pixelsPerMs).toInt(), 0)
                        updateDurationDisplay(startMs.toInt(), player.duration.toInt())
                    }
                    player.play()
                    btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                }
            }
        }

        // Mute/Unmute button logic (icon only, no function yet)
        val btnMute = findViewById<ImageButton>(R.id.btnMute)
        btnMute.setBounceClickListener {
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
        scaleDetector = android.view.ScaleGestureDetector(this, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val density = resources.displayMetrics.density
                val totalDuration = getTotalSequenceDuration()
                if (totalDuration <= 0L) return true

                val minPixelsPerMs = maxOf(0.001f * density, (timelineHorizontalScroll.width.toFloat() - 32.dpToPx()) / totalDuration)
                val maxPixelsPerMs = 1.5f * density

                val newPixelsPerMs = pixelsPerMs * scaleFactor
                pixelsPerMs = newPixelsPerMs.coerceIn(minPixelsPerMs, maxPixelsPerMs)

                viewModel.project.value?.let { renderTracks(it) }

                val currentPos = if (::player.isInitialized) player.currentPosition else 0L
                isProgrammaticScroll = true
                timelineHorizontalScroll.scrollTo((currentPos * pixelsPerMs).toInt(), 0)
                isProgrammaticScroll = false
                return true
            }
        })

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
        playerContainer = findViewById(R.id.playerContainer)
        tvDuration = findViewById(R.id.tvDuration)
        sequenceTrackContainer = findViewById(R.id.sequenceTrackContainer)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)
        timeRulerView = findViewById(R.id.timeRulerView)
        loadingScreen = findViewById(R.id.loadingScreen)
        loadingScreen.visibility = View.VISIBLE
        isImportLoading = true
        exportScreen = findViewById(R.id.exportScreen)
        previewLoadingOverlay = findViewById(R.id.previewLoadingOverlay)
        lottieAnimationView = findViewById(R.id.lottieAnimation)
        textTrackContainer = findViewById(R.id.textTrackContainer)
        imageTrackContainer = findViewById(R.id.imageTrackContainer)
        audioTrackContainer = findViewById(R.id.audioTrackContainer)

        btnPlayPause = findViewById(R.id.btnPlayPause)
        timelineHorizontalScroll = findViewById(R.id.timelineHorizontalScroll)
        timelineContainer = findViewById(R.id.timelineContainer)

        timelineContainer.post {
            val halfWidth = timelineContainer.width / 2
            timelineHorizontalScroll.setPadding(halfWidth, 0, halfWidth, 0)
        }

        timelineHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (!isProgrammaticScroll) {
                val targetMs = (scrollX / pixelsPerMs).toLong()
                seekToGlobalPosition(targetMs)
            }
        }

        timelineHorizontalScroll.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (scaleDetector.isInProgress) {
                true
            } else {
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        isUserScrollingTimeline = true
                        if (::player.isInitialized && player.isPlaying) {
                            player.pause()
                            btnPlayPause.setImageResource(R.drawable.ic_play_24)
                        }
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        isUserScrollingTimeline = true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        isUserScrollingTimeline = false
                    }
                }
                false
            }
        }

        textOverlayView = try {
            findViewById(R.id.textOverlayView)
        } catch (e: Exception) {
            Log.w(TAG, "TextOverlayView not found in layout: ${e.message}")
            null
        }

        imageOverlayView = try {
            findViewById(R.id.imageOverlayView)
        } catch (e: Exception) {
            Log.w(TAG, "ImageOverlayView not found in layout: ${e.message}")
            null
        }

        // Inline text editing components
        draggableTextOverlay = try {
            findViewById<DraggableTextOverlayView>(R.id.draggableTextOverlay)?.also { overlay ->
                overlay.onTextCommitted = { text, fontSize, relX, relY, color ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                text = text, fontSize = fontSize, relativeX = relX, relativeY = relY, color = color
                            ))
                        }
                    } else {
                        val start = getGlobalPosition()
                        val end = minOf(start + 3000L, getTotalSequenceDuration())
                        viewModel.addTextOperation(
                            text = text,
                            fontSize = fontSize,
                            position = "Center Align",
                            relativeX = relX,
                            relativeY = relY,
                            color = color,
                            startTimeMs = start,
                            endTimeMs = end
                        )
                    }
                    viewModel.selectOperation(null)
                    exitTextEditingMode()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DraggableTextOverlayView not found: ${e.message}")
            null
        }

        textEditingToolbar = try {
            findViewById<View>(R.id.textEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnTextCancel)?.setBounceClickListener {
                    draggableTextOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitTextEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnTextDone)?.setBounceClickListener {
                    draggableTextOverlay?.commitText()
                }
                toolbar.findViewById<View>(R.id.btnTextDelete)?.setBounceClickListener {
                    draggableTextOverlay?.deactivate()
                    viewModel.selectedOperationId.value?.let { id ->
                        viewModel.removeOperation(id)
                    }
                    exitTextEditingMode()
                }

                val btnKeyboard = toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)
                val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
                val colorContainer = toolbar.findViewById<View>(R.id.colorPickerContainer)

                btnKeyboard?.setBounceClickListener {
                    colorContainer?.visibility = View.GONE
                    btnKeyboard.setColorFilter(getColor(R.color.colorPrimary))
                    btnPalette?.setColorFilter(getColor(R.color.toolTextInactive))
                    draggableTextOverlay?.requestEditingFocus()
                }

                btnPalette?.setBounceClickListener {
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

        draggableImageOverlay = try {
            findViewById<DraggableImageOverlayView>(R.id.draggableImageOverlay)?.also { overlay ->
                overlay.onImageCommitted = { uri, relX, relY, relW, relH, rotationAngle ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                imageUri = uri, relativeX = relX, relativeY = relY, relativeWidth = relW, relativeHeight = relH, rotationAngle = rotationAngle
                            ))
                        }
                    } else {
                        val start = getGlobalPosition()
                        val end = minOf(start + 3000L, getTotalSequenceDuration())
                        viewModel.addImageOverlayOperation(
                            imageUri = uri,
                            relativeX = relX,
                            relativeY = relY,
                            relativeWidth = relW,
                            relativeHeight = relH,
                            rotationAngle = rotationAngle,
                            startTimeMs = start,
                            endTimeMs = end
                        )
                    }
                    viewModel.selectOperation(null)
                    exitImageEditingMode()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "DraggableImageOverlayView not found: ${e.message}")
            null
        }

        imageEditingToolbar = try {
            findViewById<View>(R.id.imageEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnImageCancel)?.setBounceClickListener {
                    draggableImageOverlay?.deactivate()
                    viewModel.selectOperation(null)
                    exitImageEditingMode()
                }
                toolbar.findViewById<View>(R.id.btnImageDone)?.setBounceClickListener {
                    draggableImageOverlay?.commitImage()
                }
                toolbar.findViewById<View>(R.id.btnImageDelete)?.setBounceClickListener {
                    draggableImageOverlay?.deactivate()
                    viewModel.selectedOperationId.value?.let { id ->
                        viewModel.removeOperation(id)
                    }
                    exitImageEditingMode()
                }
                val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
                val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
                slider?.addOnChangeListener { _, value, _ ->
                    tvValue?.text = "${value.toInt()}°"
                    draggableImageOverlay?.setRotationAngle(value)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Image editing toolbar not found: ${e.message}")
            null
        }

        videoEditingToolbar = try {
            findViewById<View>(R.id.videoEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnVideoCancel)?.setBounceClickListener {
                    selectedVideoIndex = null
                    viewModel.project.value?.let { renderTracks(it) }
                    exitVideoEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoSplit)?.setBounceClickListener {
                    splitSelectedVideo()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoTrim)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showVideoSegmentTrimDialog(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoDelete)?.setBounceClickListener {
                    deleteSelectedVideo()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoExtractAudio)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            extractAudioFromSegment(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoSpeed)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showSpeedEditingToolbar(index, items[index])
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Video editing toolbar not found: ${e.message}")
            null
        }

        speedEditingToolbar = try {
            findViewById<View>(R.id.speedEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnCloseSpeedSheet)?.setBounceClickListener {
                    hideSpeedEditingToolbar()
                }
                toolbar.findViewById<View>(R.id.speedBtn05)?.setBounceClickListener { applySpeedToSegment(0.5f) }
                toolbar.findViewById<View>(R.id.speedBtn10)?.setBounceClickListener { applySpeedToSegment(1.0f) }
                toolbar.findViewById<View>(R.id.speedBtn15)?.setBounceClickListener { applySpeedToSegment(1.5f) }
                toolbar.findViewById<View>(R.id.speedBtn20)?.setBounceClickListener { applySpeedToSegment(2.0f) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Speed editing toolbar not found: ${e.message}")
            null
        }

        audioEditingToolbar = try {
            findViewById<View>(R.id.audioEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnAudioDone)?.setBounceClickListener {
                    exitAudioEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioDelete)?.setBounceClickListener {
                    viewModel.selectedOperationId.value?.let { id ->
                        viewModel.deleteOperation(id)
                    }
                    exitAudioEditingMode()
                }
                val volumeSlider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
                val tvVolumeValue = toolbar.findViewById<TextView>(R.id.tvAudioVolumeValue)
                volumeSlider?.addOnChangeListener { _, value, _ ->
                    tvVolumeValue?.text = "${(value * 100).toInt()}%"
                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@addOnChangeListener
                        val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            viewModel.updateOperation(op.copy(volume = value))
                        }
                    }
                }

                val trimTrack = toolbar.findViewById<com.tharunbirla.librecuts.customviews.TrackTrimView>(R.id.audioTrimTrack)
                val tvTrimValue = toolbar.findViewById<TextView>(R.id.tvAudioTrimValues)
                
                trimTrack?.onTrimChanged = { startMs, endMs, _ ->
                    tvTrimValue?.text = "${formatDuration(startMs.toInt())} - ${formatDuration(endMs.toInt())}"
                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@let
                        val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                internalStartMs = startMs,
                                internalEndMs = endMs
                            ))
                        }
                    }
                }

                trimTrack?.onTrimAdjustingWithDelta = { startMs, endMs, _, _ ->
                    tvTrimValue?.text = "${formatDuration(startMs.toInt())} - ${formatDuration(endMs.toInt())}"
                }
                
                val btnAudioPreviewPlay = toolbar.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)
                btnAudioPreviewPlay?.setBounceClickListener {
                    if (audioPreviewPlayer?.isPlaying == true) {
                        audioPreviewPlayer?.stop()
                        audioPreviewPlayer?.release()
                        audioPreviewPlayer = null
                        btnAudioPreviewPlay.setImageResource(R.drawable.ic_play_24)
                        return@setBounceClickListener
                    }

                    viewModel.selectedOperationId.value?.let { id ->
                        val project = viewModel.project.value ?: return@setBounceClickListener
                        val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio
                        if (op != null) {
                            try {
                                audioPreviewPlayer = android.media.MediaPlayer().apply {
                                    setDataSource(this@VideoEditingActivity, op.audioUri)
                                    prepare()
                                    seekTo(op.internalStartMs.toInt())
                                    start()
                                    btnAudioPreviewPlay.setImageResource(R.drawable.ic_pause_24)
                                    
                                    val durationToPlay = if (op.internalEndMs > 0 && op.internalEndMs > op.internalStartMs) {
                                        op.internalEndMs - op.internalStartMs
                                    } else {
                                        op.originalDurationMs - op.internalStartMs
                                    }
                                    
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        try {
                                            if (audioPreviewPlayer === this && isPlaying) {
                                                stop()
                                                release()
                                                if (audioPreviewPlayer === this) {
                                                    audioPreviewPlayer = null
                                                }
                                                btnAudioPreviewPlay.setImageResource(R.drawable.ic_play_24)
                                            }
                                        } catch (e: Exception) {
                                            // Player was already released or invalid state
                                        }
                                    }, durationToPlay)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to preview audio segment", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio editing toolbar not found: ${e.message}")
            null
        }

        cropEditingToolbar = try {
            findViewById<View>(R.id.cropEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnCloseSheet)?.setBounceClickListener {
                    exitCropEditingMode()
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio1)?.setBounceClickListener {
                    viewModel.addCropOperation("16:9")
                    updateCropUi("16:9")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio2)?.setBounceClickListener {
                    viewModel.addCropOperation("9:16")
                    updateCropUi("9:16")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio3)?.setBounceClickListener {
                    viewModel.addCropOperation("1:1")
                    updateCropUi("1:1")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crop editing toolbar not found: ${e.message}")
            null
        }

        findViewById<ImageButton>(R.id.btnHome).setBounceClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        layoutSaveSplit = findViewById(R.id.layoutSaveSplit)
        btnSaveText = findViewById(R.id.btnSaveText)
        btnSaveDropdown = findViewById(R.id.btnSaveDropdown)
        editingControlsWrapper = findViewById(R.id.editingControlsWrapper)

        tvPreviewBadge = findViewById(R.id.tvPreviewBadge)

        btnSaveText.setBounceClickListener {
            saveAction()
        }

        btnSaveDropdown.setBounceClickListener {
            showQualitySettingsDialog()
        }

        // Tool Buttons with proper scoping
        findViewById<ImageButton>(R.id.btnText).setBounceClickListener {
            setActiveToolButton(R.id.btnText)
            textAction()
        }
        findViewById<ImageButton>(R.id.btnImageOverlay).setBounceClickListener {
            setActiveToolButton(R.id.btnImageOverlay)
            imageOverlayAction()
        }
        findViewById<ImageButton>(R.id.btnAudio).setBounceClickListener {
            setActiveToolButton(R.id.btnAudio)
            audioAction()
        }
        findViewById<ImageButton>(R.id.btnCrop).setBounceClickListener {
            setActiveToolButton(R.id.btnCrop)
            cropAction()
        }
        findViewById<ImageButton>(R.id.btnMerge).setBounceClickListener {
            setActiveToolButton(R.id.btnMerge)
            mergeAction()
        }

        try {
            btnUndo = findViewById(R.id.btnUndo)
            btnRedo = findViewById(R.id.btnRedo)
            btnPreview = findViewById(R.id.btnPreview)
            btnUndo.setBounceClickListener {
                if (isShowingPreview) dismissPreview()
                viewModel.undo()
            }
            btnRedo.setBounceClickListener {
                if (isShowingPreview) dismissPreview()
                viewModel.redo()
            }
            btnPreview.setBounceClickListener {
                if (isShowingPreview) {
                    dismissPreview()
                } else {
                    renderSegmentedPreview()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Undo/Redo/Preview buttons not found in layout (optional)")
        }

        try {
            lottieAnimationView.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Error loading Lottie animation: ${e.message}")
        }
    }

    private fun updateUIInteractionState() {
        val uiState = viewModel.uiState.value
        val isBusy = uiState.isExporting || isRenderingPreview
        
        if (::previewLoadingOverlay.isInitialized) {
            previewLoadingOverlay.visibility = if (isRenderingPreview) View.VISIBLE else View.GONE
        }
        
        val alpha = if (isBusy) 0.4f else 1.0f
        
        if (::editingControlsWrapper.isInitialized) {
            editingControlsWrapper.alpha = alpha
            for (i in 0 until editingControlsWrapper.childCount) {
                editingControlsWrapper.getChildAt(i)?.isEnabled = !isBusy
            }
        }
        
        val hasOps = viewModel.project.value?.hasOperations() == true
        if (::layoutSaveSplit.isInitialized && ::btnSaveText.isInitialized && ::btnSaveDropdown.isInitialized) {
            layoutSaveSplit.alpha = if (isBusy || !hasOps) 0.4f else 1.0f
            btnSaveText.isEnabled = !isBusy && hasOps
            btnSaveDropdown.isEnabled = !isBusy && hasOps
        }
        
        if (::btnPreview.isInitialized) {
            val hasPreviewableOps = viewModel.project.value?.operations?.any { 
                it is EditOperation.Crop || it is EditOperation.AddText
            } == true
            btnPreview.isEnabled = !isBusy && hasPreviewableOps
            btnPreview.alpha = if (isBusy) 0.4f else if (hasPreviewableOps) 1.0f else 0.5f
            if (isShowingPreview) {
                btnPreview.setColorFilter(getColor(R.color.colorPrimary))
            } else {
                btnPreview.setColorFilter(getColor(R.color.colorOnPrimary))
            }
        }
        
        customVideoSeeker.isEnabled = !isBusy
    }

    private fun setActiveToolButton(activeId: Int) {
        if (isShowingPreview) dismissPreview()
        val toolIds = listOf(R.id.btnText, R.id.btnImageOverlay, R.id.btnAudio, R.id.btnCrop, R.id.btnMerge)
        for (id in toolIds) {
            val btn = findViewById<ImageButton>(id) ?: continue
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
                if (uiState.isExporting) {
                    loadingScreen.visibility = View.GONE
                    val progress = uiState.exportProgress
                    exportScreen.findViewById<TextView>(R.id.tvExportPercentage)?.text = "$progress%"
                    exportScreen.findViewById<android.widget.ProgressBar>(R.id.exportProgressBar)?.progress = progress
                } else {
                    if (isImportLoading || !isVideoLoaded) {
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
                updateUIInteractionState()
            }
        }

        lifecycleScope.launch {
            viewModel.selectedOperationId.collect { selectedId ->
                if (selectedId != null && isShowingPreview) dismissPreview()
                textOverlayView?.hiddenOperationId = selectedId
                imageOverlayView?.hiddenOperationId = selectedId
                
                if (selectedId != null) {

                    // Reset editing modes to prevent multiple active editing toolbars/states
                    draggableTextOverlay?.deactivate()
                    draggableImageOverlay?.deactivate()
                    exitTextEditingMode()
                    exitImageEditingMode()

                    val op = viewModel.project.value?.operations?.find {
                        when (it) {
                            is EditOperation.AddText -> it.id == selectedId
                            is EditOperation.AddImageOverlay -> it.id == selectedId
                            is EditOperation.AddBackgroundAudio -> it.id == selectedId
                            else -> false
                        }
                    }
                    when (op) {
                        is EditOperation.AddText -> {
                            draggableTextOverlay?.activateForEdit(op)
                            enterTextEditingMode(isReEditing = true)
                        }
                        is EditOperation.AddImageOverlay -> {
                            draggableImageOverlay?.activateForEdit(op)
                            enterImageEditingModeForEdit(op.rotationAngle)
                        }
                        is EditOperation.AddBackgroundAudio -> {
                            enterAudioEditingMode(op)
                        }
                        else -> {}
                    }
                } else {
                    draggableTextOverlay?.deactivate()
                    draggableImageOverlay?.deactivate()
                    exitTextEditingMode()
                    exitImageEditingMode()
                    exitAudioEditingMode()
                }
                
                // Re-render tracks so the selection highlight updates
                viewModel.project.value?.let { renderTracks(it) }
            }
        }

        lifecycleScope.launch {
            viewModel.project.collect { project ->
                if (project != null) {
                    Log.d(TAG, "Project updated with ${project.getOperationCount()} operations")

                    updateUIInteractionState()

                    textOverlayView?.let { overlay ->
                        val textOps = project.operations.filterIsInstance<EditOperation.AddText>()
                        overlay.setTextOperations(textOps)
                    }

                    imageOverlayView?.let { overlay ->
                        val imageOps = project.operations.filterIsInstance<EditOperation.AddImageOverlay>()
                        overlay.setImageOperations(imageOps)
                    }

                    val cropOps = project.operations.filterIsInstance<EditOperation.Crop>()
                    if (cropOps.isNotEmpty()) {
                        applyCropPreview(cropOps.last().aspectRatio)
                    } else {
                        resetCropPreview()
                    }
                    
                    renderTracks(project)
                }
            }
        }
    }

    private fun applyCropPreview(aspectRatio: String) {
        val targetRatio = when (aspectRatio) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "1:1" -> 1f
            "4:5" -> 4f / 5f
            else -> return
        }

        playerView.post {
            val containerWidth = playerContainer.width.toFloat()
            val containerHeight = playerContainer.height.toFloat()
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val containerRatio = containerWidth / containerHeight
            var targetWidth = containerWidth
            var targetHeight = containerHeight

            if (targetRatio > containerRatio) {
                // Wider than container — fit width, shrink height
                targetHeight = containerWidth / targetRatio
            } else {
                // Taller than container — fit height, shrink width
                targetWidth = containerHeight * targetRatio
            }

            val params = playerView.layoutParams as FrameLayout.LayoutParams
            params.width = targetWidth.toInt()
            params.height = targetHeight.toInt()
            params.gravity = Gravity.CENTER
            playerView.layoutParams = params

            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM // RESIZE_MODE_ZOOM

            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = targetWidth.toInt()
                    overlayParams.height = targetHeight.toInt()
                    overlayParams.gravity = Gravity.CENTER
                    it.layoutParams = overlayParams
                }
            }
        }
    }

    private fun resetCropPreview() {
        playerView.post {
            val params = playerView.layoutParams as FrameLayout.LayoutParams
            params.width = FrameLayout.LayoutParams.MATCH_PARENT
            params.height = FrameLayout.LayoutParams.MATCH_PARENT
            params.gravity = Gravity.NO_GRAVITY
            playerView.layoutParams = params

            playerView.resizeMode = 0 // RESIZE_MODE_FIT

            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.gravity = Gravity.NO_GRAVITY
                    it.layoutParams = overlayParams
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        Toast.makeText(this, "Drag the handles on the timeline's main video track to trim.", Toast.LENGTH_LONG).show()
    }

    @SuppressLint("InflateParams")
    private fun cropAction() {
        enterCropEditingMode()
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

    private fun closeActiveEditingModes() {
        if (isTextEditingActive) {
            draggableTextOverlay?.commitText()
            isTextEditingActive = false
        }
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
            isImageEditingActive = false
        }
        textEditingToolbar?.visibility = View.GONE
        imageEditingToolbar?.visibility = View.GONE
        videoEditingToolbar?.visibility = View.GONE
        audioEditingToolbar?.visibility = View.GONE
        cropEditingToolbar?.visibility = View.GONE
    }

    private fun enterTextEditingMode(isReEditing: Boolean = false) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isTextEditingActive = true
        selectedTextColor = "#FFFFFF"
        if (!isReEditing) {
            draggableTextOverlay?.activate("", 36)
        }
        textEditingToolbar?.visibility = View.VISIBLE
        textEditingToolbar?.let { toolbar ->
            toolbar.findViewById<View>(R.id.colorPickerContainer)?.visibility = View.GONE
            toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)?.setColorFilter(getColor(R.color.colorPrimary))
            toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)?.setColorFilter(getColor(R.color.toolTextInactive))
            setupColorPicker(toolbar)
        }
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.GONE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.GONE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitTextEditingMode() {
        isTextEditingActive = false
        textEditingToolbar?.visibility = View.GONE
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.VISIBLE
    }

    private fun imageOverlayAction() {
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
            return
        }
        openImagePicker()
    }

    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(Intent.createChooser(intent, "Select Image"), PICK_IMAGE_REQUEST)
    }

    private fun showImageOverlayConfig(imageUri: Uri) {
        lifecycleScope.launch {
            val tempImageFile = withContext(Dispatchers.IO) {
                copyContentUriToTempFile(imageUri, "overlay_image", ".png")
            }
            if (tempImageFile == null) {
                Toast.makeText(this@VideoEditingActivity, "Failed to load image", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val localUri = Uri.fromFile(tempImageFile)
            
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(tempImageFile.absolutePath, options)
            val aspect = if (options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 1.0f
            
            enterImageEditingMode(localUri, aspect)
        }
    }

    private fun enterImageEditingMode(uri: Uri, aspect: Float) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isImageEditingActive = true
        draggableImageOverlay?.activate(uri, aspect)
        imageEditingToolbar?.visibility = View.VISIBLE
        imageEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
            slider?.value = 0f
            tvValue?.text = "0°"
        }
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun enterImageEditingModeForEdit(rotation: Float) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isImageEditingActive = true
        imageEditingToolbar?.visibility = View.VISIBLE
        imageEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
            slider?.value = rotation
            tvValue?.text = "${rotation.toInt()}°"
        }
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitImageEditingMode() {
        isImageEditingActive = false
        imageEditingToolbar?.visibility = View.GONE
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.VISIBLE
    }

    private fun enterVideoEditingMode() {
        closeActiveEditingModes()
        viewModel.selectOperation(null)
        videoEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        videoEditingToolbar?.findViewById<View>(R.id.btnVideoDeleteContainer)?.visibility = if (selectedVideoIndex != null && selectedVideoIndex!! > 0) View.VISIBLE else View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitVideoEditingMode() {
        videoEditingToolbar?.visibility = View.GONE
        editingControlsWrapper.visibility = View.VISIBLE
    }


    private fun enterAudioEditingMode(op: com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        audioEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        audioEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvAudioVolumeValue)
            slider?.value = op.volume.coerceIn(0f, 1f)
            tvValue?.text = "${(op.volume * 100).toInt()}%"

            val trimTrack = toolbar.findViewById<com.tharunbirla.librecuts.customviews.TrackTrimView>(R.id.audioTrimTrack)
            val tvTrimValue = toolbar.findViewById<TextView>(R.id.tvAudioTrimValues)
            
            val maxMs = getTotalSequenceDuration().coerceAtLeast(op.originalDurationMs)
            if (op.originalDurationMs > 0) {
                trimTrack?.isMainVideoTrack = false
                trimTrack?.isAudioTrack = true
                trimTrack?.trackColor = android.graphics.Color.TRANSPARENT
                trimTrack?.maxDurationMs = op.originalDurationMs
                val seqDuration = getTotalSequenceDuration()
                trimTrack?.maxSelectionDurationMs = if (seqDuration > 0) seqDuration else null
                
                val trackWidth = resources.displayMetrics.widthPixels - 32.dpToPx()
                trimTrack?.customMsPerPixel = op.originalDurationMs.toFloat() / trackWidth
                
                val endMs = if (op.internalEndMs > 0) op.internalEndMs else op.originalDurationMs
                val initialEndMs = endMs.coerceAtMost(op.internalStartMs + seqDuration)
                trimTrack?.setRange(op.originalDurationMs, op.internalStartMs, initialEndMs)
                
                tvTrimValue?.text = "${formatDuration(op.internalStartMs.toInt())} - ${formatDuration(endMs.toInt())}"
            } else {
                trimTrack?.setRange(maxMs, 0, maxMs)
                tvTrimValue?.text = "Unknown duration"
            }
        }
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitAudioEditingMode() {
        audioEditingToolbar?.visibility = View.GONE
        editingControlsWrapper.visibility = View.VISIBLE
        viewModel.selectOperation(null)
        
        if (audioPreviewPlayer?.isPlaying == true) {
            audioPreviewPlayer?.stop()
        }
        audioPreviewPlayer?.release()
        audioPreviewPlayer = null
        
        audioEditingToolbar?.findViewById<ImageButton>(R.id.btnAudioPreviewPlay)?.setImageResource(R.drawable.ic_play_24)
    }

    private fun enterCropEditingMode() {
        closeActiveEditingModes()
        cropEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        
        val currentRatio = viewModel.project.value?.operations
            ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
            ?.lastOrNull()?.aspectRatio ?: "Original"
            
        updateCropUi(currentRatio)
        
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitCropEditingMode() {
        cropEditingToolbar?.visibility = View.GONE
        editingControlsWrapper.visibility = View.VISIBLE
    }

    private fun showSpeedEditingToolbar(index: Int, item: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        closeActiveEditingModes()
        speedEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        
        updateSpeedUi(item.speed)
        
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun hideSpeedEditingToolbar() {
        speedEditingToolbar?.visibility = View.GONE
        if (selectedVideoIndex != null) {
            videoEditingToolbar?.visibility = View.VISIBLE
        } else {
            editingControlsWrapper.visibility = View.VISIBLE
        }
    }

    private fun showLoading(message: String) {
        val tvLoadingTitle = loadingScreen.findViewById<TextView>(R.id.tvLoadingTitle)
        tvLoadingTitle?.text = message
        loadingScreen.visibility = View.VISIBLE
    }

    private fun hideLoading() {
        loadingScreen.visibility = View.GONE
    }

    private fun updateSpeedUi(speed: Float) {
        val toolbar = speedEditingToolbar ?: return
        val speeds = mapOf(
            0.5f to Pair(R.id.bgSpeed05, R.id.txtSpeed05),
            1.0f to Pair(R.id.bgSpeed10, R.id.txtSpeed10),
            1.5f to Pair(R.id.bgSpeed15, R.id.txtSpeed15),
            2.0f to Pair(R.id.bgSpeed20, R.id.txtSpeed20)
        )
        
        speeds.forEach { (s, views) ->
            val bg = toolbar.findViewById<View>(views.first)
            val txt = toolbar.findViewById<TextView>(views.second)
            if (speed == s) {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.activeTool))
            } else {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
            }
        }
    }

    private fun applySpeedToSegment(speed: Float) {
        val index = selectedVideoIndex ?: return
        val items = getSequenceItems()
        if (index < 0 || index >= items.size) return
        
        val item = items[index]
        if (item.speed == speed) {
            hideSpeedEditingToolbar()
            return
        }
        
        updateSpeedUi(speed)
        
        if (speed == 1.0f) {
            // Reset to normal speed, no proxy needed
            if (index == 0) {
                viewModel.updateMainVideoSpeed(1.0f, null)
            } else {
                updateVideoSegment(index, item.copy(speed = 1.0f, proxyUri = null))
            }
            hideSpeedEditingToolbar()
            return
        }
        
        lifecycleScope.launch {
            showLoading("Applying speed changes...")
            val outputFileName = "proxy_speed_${System.currentTimeMillis()}.mp4"
            val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath
            
            val result = ffmpegEngine.generateSpeedProxy(
                sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                startMs = item.trimStartMs,
                endMs = item.trimEndMs,
                speed = speed,
                outputFilePath = outputFilePath
            )
            
            hideLoading()
            
            if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                if (index == 0) {
                    viewModel.updateMainVideoSpeed(speed, proxyUri)
                } else {
                    updateVideoSegment(index, item.copy(speed = speed, proxyUri = proxyUri))
                }
                hideSpeedEditingToolbar()
            } else {
                android.widget.Toast.makeText(this@VideoEditingActivity, "Failed to apply speed", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun updateVideoSegment(index: Int, newItem: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        val project = viewModel.project.value ?: return
        val currentMergeOp = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.Merge } as? com.tharunbirla.librecuts.models.EditOperation.Merge ?: return
        val updatedItems = currentMergeOp.items.toMutableList()
        updatedItems[index - 1] = newItem
        val newMergeOp = currentMergeOp.copy(items = updatedItems)
        viewModel.updateOperation(newMergeOp)
        viewModel.project.value?.let { renderTracks(it) }
    }
    
    private fun updateCropUi(ratio: String) {
        val toolbar = cropEditingToolbar ?: return
        val ratios = mapOf(
            "16:9" to Triple(R.id.bg16_9, R.id.ic16_9, R.id.txt16_9),
            "9:16" to Triple(R.id.bg9_16, R.id.ic9_16, R.id.txt9_16),
            "1:1" to Triple(R.id.bg1_1, R.id.ic1_1, R.id.txt1_1)
        )

        ratios.forEach { (key, views) ->
            val isActive = key == ratio
            toolbar.findViewById<View>(views.first)?.setBackgroundResource(
                if (isActive) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
            )
            toolbar.findViewById<ImageView>(views.second)?.setColorFilter(
                resources.getColor(if (isActive) R.color.onPrimaryContainer else R.color.iconSecondary, null)
            )
            toolbar.findViewById<TextView>(views.third)?.apply {
                setTextColor(resources.getColor(if (isActive) R.color.activeTool else R.color.toolTextInactive, null))
                paint.isFakeBoldText = isActive
            }
        }
    }

    private fun splitSelectedVideo() {
        val index = selectedVideoIndex ?: return
        val globalPos = getGlobalPosition()
        
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        
        val item = sequenceItems[index]
        
        // Calculate the clip's starting global position
        var clipStartGlobal = 0L
        for (i in 0 until index) {
            clipStartGlobal += sequenceItems[i].trimmedDurationMs
        }
        val clipEndGlobal = clipStartGlobal + item.trimmedDurationMs
        
        // Check if the seeker is inside this clip
        if (globalPos <= clipStartGlobal || globalPos >= clipEndGlobal) {
            Toast.makeText(this, "Seeker is not over the selected clip.", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Calculate the local split time inside the clip's own timeline
        val localOffset = globalPos - clipStartGlobal
        val localSplitTimeMs = item.trimStartMs + localOffset
        
        viewModel.splitVideoSegment(index, localSplitTimeMs, item.uri, item.durationMs)
        selectedVideoIndex = null
        exitVideoEditingMode()
    }
    
    private fun deleteSelectedVideo() {
        val index = selectedVideoIndex ?: return
        if (index > 0) {
            viewModel.removeMergeVideo(index - 1)
            selectedVideoIndex = null
            exitVideoEditingMode()
        }
    }

    private fun extractAudioFromSegment(index: Int, item: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        lifecycleScope.launch {
            showLoading("Extracting audio...")
            val ffmpegEngine = com.tharunbirla.librecuts.services.FFmpegRenderEngine(this@VideoEditingActivity)
            
            // Generate a unique output file path
            val outputFileName = "extracted_audio_${System.currentTimeMillis()}.m4a"
            val outputFilePath = File(cacheDir, outputFileName).absolutePath
            val sourceFilePath = item.uri.path ?: item.uri.toString()

            val result = withContext(Dispatchers.IO) {
                ffmpegEngine.extractAudio(sourceFilePath, outputFilePath)
            }

            hideLoading()

            if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                val sequenceItems = getSequenceItems()
                var clipStartGlobal = 0L
                for (i in 0 until index) {
                    clipStartGlobal += sequenceItems[i].trimmedDurationMs
                }
                val clipEndGlobal = clipStartGlobal + item.trimmedDurationMs

                val tempUri = Uri.fromFile(File(outputFilePath))
                viewModel.addOperation(
                    com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio(
                        audioUri = tempUri,
                        internalStartMs = item.trimStartMs,
                        internalEndMs = item.trimEndMs,
                        startTimeMs = clipStartGlobal,
                        endTimeMs = clipEndGlobal,
                        originalDurationMs = item.durationMs,
                        extractedFromSegmentIndex = index
                    )
                )
                viewModel.addMuteSegmentOperation(index)
                Toast.makeText(this@VideoEditingActivity, "Audio extracted to new layer", Toast.LENGTH_SHORT).show()
            } else if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Failure) {
                Toast.makeText(this@VideoEditingActivity, "Failed to extract audio", Toast.LENGTH_SHORT).show()
                Log.e(TAG, "Audio extraction failed: ${result.error}")
            }
            
            selectedVideoIndex = null
            exitVideoEditingMode()
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
                    // Copy content URIs to temp files so FFmpeg can access them, and fetch duration
                    lifecycleScope.launch {
                        val mergeItems = withContext(Dispatchers.IO) {
                            selectedVideoUris.mapNotNull { uri ->
                                val tempFile = copyContentUriToTempFile(uri, "merge_video", ".mp4")
                                if (tempFile != null) {
                                    val tempUri = Uri.fromFile(tempFile)
                                    val duration = try {
                                        val retriever = MediaMetadataRetriever()
                                        try {
                                            retriever.setDataSource(tempFile.absolutePath)
                                            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            durStr?.toLong() ?: 0L
                                        } finally {
                                            retriever.release()
                                        }
                                    } catch (e: Exception) {
                                        0L
                                    }
                                    com.tharunbirla.librecuts.models.EditOperation.MergeItem(tempUri, duration)
                                } else null
                            }
                        }
                        if (mergeItems.isNotEmpty()) {
                            isImportLoading = true
                            showLoading("Importing video...")
                            viewModel.addMergeOperation(mergeItems)
                            Toast.makeText(this@VideoEditingActivity, "${mergeItems.size} video(s) added to sequence", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this@VideoEditingActivity, "Failed to load selected video(s)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else if (requestCode == PICK_AUDIO_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { audioUri ->
                lifecycleScope.launch {
                    val tempFile = withContext(Dispatchers.IO) { copyContentUriToTempFile(audioUri, "audio", ".m4a") }
                    if (tempFile != null) {
                        val tempUri = Uri.fromFile(tempFile)
                        val realDurationMs = withContext(Dispatchers.IO) {
                            try {
                                val r = android.media.MediaMetadataRetriever()
                                r.setDataSource(this@VideoEditingActivity, tempUri)
                                val d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                                r.release()
                                d
                            } catch(e: Exception) { 0L }
                        }
                        val totalSeqDur = getTotalSequenceDuration()
                        val endGlobalMs = if (realDurationMs > 0) minOf(totalSeqDur, realDurationMs) else totalSeqDur
                        viewModel.addOperation(
                            com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio(
                                audioUri = tempUri,
                                internalStartMs = 0L,
                                internalEndMs = realDurationMs,
                                startTimeMs = 0L,
                                endTimeMs = endGlobalMs,
                                originalDurationMs = realDurationMs
                            )
                        )
                        Toast.makeText(this@VideoEditingActivity, "Audio track added", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { imageUri ->
                showImageOverlayConfig(imageUri)
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

    /**
     * SAVE/EXPORT: Consolidates all pending operations into a single FFmpeg command and runs it.
     *
     * KEY FIX: fontFilePath (populated in onCreate from assets/fonts/Roboto-Regular.ttf)
     * is now passed to buildConsolidatedFFmpegCommand so drawtext gets a valid fontfile= path.
     */
    private fun saveAction() {
        if (isShowingPreview) dismissPreview()
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

                val totalDurationSecs = getTotalSequenceDuration() / 1000.0
                val result = ffmpegEngine.exportFinal(
                    ffmpegCommand = ffmpegCommand,
                    totalDurationSecs = totalDurationSecs,
                    onProgress = { progress ->
                        lifecycleScope.launch(Dispatchers.Main) {
                            viewModel.updateExportProgress(progress)
                        }
                    }
                )

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

    private fun showQualitySettingsDialog() {
        if (isShowingPreview) dismissPreview()
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.export_quality_bottom_sheet_dialog, null)

        val layoutHigh = sheetView.findViewById<LinearLayout>(R.id.layoutQualityHigh)
        val layoutMedium = sheetView.findViewById<LinearLayout>(R.id.layoutQualityMedium)
        val layoutLow = sheetView.findViewById<LinearLayout>(R.id.layoutQualityLow)

        val ivCheckHigh = sheetView.findViewById<ImageView>(R.id.ivCheckHigh)
        val ivCheckMedium = sheetView.findViewById<ImageView>(R.id.ivCheckMedium)
        val ivCheckLow = sheetView.findViewById<ImageView>(R.id.ivCheckLow)

        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseSheet)

        fun updateSelection(selected: com.tharunbirla.librecuts.viewmodels.ExportQuality) {
            ivCheckHigh.visibility = if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.HIGH) View.VISIBLE else View.GONE
            ivCheckMedium.visibility = if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.MEDIUM) View.VISIBLE else View.GONE
            ivCheckLow.visibility = if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.LOW) View.VISIBLE else View.GONE

            layoutHigh.setBackgroundResource(
                if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.HIGH) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
            )
            layoutMedium.setBackgroundResource(
                if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.MEDIUM) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
            )
            layoutLow.setBackgroundResource(
                if (selected == com.tharunbirla.librecuts.viewmodels.ExportQuality.LOW) R.drawable.bg_aspect_ratio_selected else R.drawable.bg_aspect_ratio_item
            )
        }

        // Initialize state
        val currentQuality = viewModel.exportQuality.value
        updateSelection(currentQuality)

        layoutHigh.setBounceClickListener {
            viewModel.setExportQuality(com.tharunbirla.librecuts.viewmodels.ExportQuality.HIGH)
            updateSelection(com.tharunbirla.librecuts.viewmodels.ExportQuality.HIGH)
            Toast.makeText(this, "Export quality set to High", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.dismiss()
        }

        layoutMedium.setBounceClickListener {
            viewModel.setExportQuality(com.tharunbirla.librecuts.viewmodels.ExportQuality.MEDIUM)
            updateSelection(com.tharunbirla.librecuts.viewmodels.ExportQuality.MEDIUM)
            Toast.makeText(this, "Export quality set to Medium", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.dismiss()
        }

        layoutLow.setBounceClickListener {
            viewModel.setExportQuality(com.tharunbirla.librecuts.viewmodels.ExportQuality.LOW)
            updateSelection(com.tharunbirla.librecuts.viewmodels.ExportQuality.LOW)
            Toast.makeText(this, "Export quality set to Low", Toast.LENGTH_SHORT).show()
            bottomSheetDialog.dismiss()
        }

        btnClose.setBounceClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
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
            showLoading("Loading...")

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        // Reset UI components when video reaches the end
                        val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
                        btnPlayPause.setImageResource(R.drawable.ic_play_24)
                        isProgrammaticScroll = true
                        timelineHorizontalScroll.scrollTo((getTotalSequenceDuration() * pixelsPerMs).toInt(), 0)
                        isProgrammaticScroll = false

                        // OPTIONAL: If you want the seeker to jump back to 0 immediately
                        // once it finishes, uncomment the next lines:
                        // player.seekTo(0)
                        // customVideoSeeker.setSeekPosition(0f)

                        stopProgressUpdater()
                        if (isShowingPreview) dismissPreview()
                    }
                    if (state == Player.STATE_READY) {
                        isVideoLoaded = true
                        customVideoSeeker.setVideoDuration(player.duration)
                        timeRulerView.setVideoDuration(player.duration)
                        updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                        
                        if (!isInitialFitDone && player.duration > 0L) {
                            timelineHorizontalScroll.post {
                                val scrollWidth = timelineHorizontalScroll.width.toFloat()
                                if (scrollWidth > 0) {
                                    val safeDuration = getTotalSequenceDuration().coerceAtLeast(player.duration)
                                    val newPixelsPerMs = (scrollWidth - 32.dpToPx()) / safeDuration
                                    val density = resources.displayMetrics.density
                                    pixelsPerMs = newPixelsPerMs.coerceIn(0.001f * density, 2.0f * density)
                                    viewModel.project.value?.let { renderTracks(it) }
                                    isInitialFitDone = true
                                }
                            }
                        }

                        val format = player.videoFormat
                        if (format != null && format.width > 0 && format.height > 0) {
                            val rotation = format.rotationDegrees
                            val displayWidth = if (rotation == 90 || rotation == 270) format.height else format.width
                            val displayHeight = if (rotation == 90 || rotation == 270) format.width else format.height
                            textOverlayView?.setVideoSize(displayWidth, displayHeight)
                            draggableTextOverlay?.setVideoSize(displayWidth, displayHeight)
                            imageOverlayView?.setVideoSize(displayWidth, displayHeight)
                            draggableImageOverlay?.setVideoSize(displayWidth, displayHeight)
                        }
                        updateUIInteractionState()
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
                        val rotation = videoSize.unappliedRotationDegrees
                        val displayWidth = if (rotation == 90 || rotation == 270) videoSize.height else videoSize.width
                        val displayHeight = if (rotation == 90 || rotation == 270) videoSize.width else videoSize.height
                        textOverlayView?.setVideoSize(displayWidth, displayHeight)
                        draggableTextOverlay?.setVideoSize(displayWidth, displayHeight)
                        imageOverlayView?.setVideoSize(displayWidth, displayHeight)
                        draggableImageOverlay?.setVideoSize(displayWidth, displayHeight)
                    }
                }

                override fun onPositionDiscontinuity(reason: Int) {
                    // Update UI immediately on manual seek
                    if (isVideoLoaded) {
                        syncUiWithPlayer()
                    }
                }

                override fun onPlayerError(error: com.google.android.exoplayer2.PlaybackException) {
                    super.onPlayerError(error)
                    Log.e(TAG, "ExoPlayer Error: ${error.message}", error)
                    showError("Unsupported video format or corrupted file.")
                    isImportLoading = false
                    isVideoLoaded = true // Prevent UI loop from showing loading screen again
                    loadingScreen.visibility = View.GONE
                    
                    // Exit the editing activity since the main video cannot be played
                    finish()
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

    private fun getSequenceItems(): List<com.tharunbirla.librecuts.models.EditOperation.MergeItem> {
        val items = mutableListOf<com.tharunbirla.librecuts.models.EditOperation.MergeItem>()
        if (!::tempInputFile.isInitialized) return items
        
        val sourceUri = android.net.Uri.fromFile(tempInputFile)
        val sourceDuration = if (originalMainVideoDurationMs > 0L) {
            originalMainVideoDurationMs
        } else {
            try {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(tempInputFile.absolutePath)
                    val duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                    originalMainVideoDurationMs = duration
                    duration
                } finally {
                    r.release()
                }
            } catch (e: Exception) { 0L }
        }

        val trimOp = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Trim>()?.lastOrNull()
        val sTrimStart = trimOp?.startMs ?: 0L
        val sTrimEnd = trimOp?.endMs ?: sourceDuration
        val speedOp = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.SpeedMain>()?.lastOrNull()
        val speed = speedOp?.speed ?: 1.0f
        val proxyUri = speedOp?.proxyUri
        items.add(com.tharunbirla.librecuts.models.EditOperation.MergeItem(sourceUri, sourceDuration, sTrimStart, sTrimEnd, speed, proxyUri))
        
        val mergeOp = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Merge>()?.firstOrNull()
        if (mergeOp != null) {
            items.addAll(mergeOp.items)
        }
        return items
    }

    private fun getGlobalPosition(): Long {
        if (!::player.isInitialized) return 0L
        val index = player.currentMediaItemIndex
        var pos = 0L
        for (i in 0 until minOf(index, chunkDurationsMs.size)) {
            pos += chunkDurationsMs[i]
        }
        return pos + player.currentPosition
    }

    private fun getTotalSequenceDuration(): Long {
        return getSequenceItems().sumOf { it.trimmedDurationMs }
    }

    private fun syncUiWithPlayer() {
        val currentGlobalPos = getGlobalPosition()
        val totalDuration = getTotalSequenceDuration()

        if (totalDuration > 0) {
            updateDurationDisplay(currentGlobalPos.toInt(), totalDuration.toInt())
            
            if (!isUserScrollingTimeline && !isTrackDragging) {
                val targetScrollX = (currentGlobalPos * pixelsPerMs).toInt()
                isProgrammaticScroll = true
                timelineHorizontalScroll.scrollTo(targetScrollX, 0)
                isProgrammaticScroll = false
            }

            textOverlayView?.currentPositionMs = currentGlobalPos
            imageOverlayView?.currentPositionMs = currentGlobalPos
        }
    }

    private fun seekToGlobalPosition(globalPos: Long) {
        if (!::player.isInitialized) return
        var remainingPos = globalPos
        var index = 0
        while (index < chunkDurationsMs.size && remainingPos > chunkDurationsMs[index]) {
            remainingPos -= chunkDurationsMs[index]
            index++
        }
        if (index < chunkDurationsMs.size) {
            player.seekTo(index, remainingPos)
        }
        val totalDuration = getTotalSequenceDuration()
        updateDurationDisplay(globalPos.toInt(), totalDuration.toInt())
        textOverlayView?.currentPositionMs = globalPos
        imageOverlayView?.currentPositionMs = globalPos
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
                        
                        try {
                            val r = android.media.MediaMetadataRetriever()
                            r.setDataSource(tempInputFile.absolutePath)
                            originalMainVideoDurationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                            r.release()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error getting original duration: ${e.message}")
                        }

                        // Now load it into ExoPlayer
                        val mediaItem = com.google.android.exoplayer2.MediaItem.fromUri(Uri.fromFile(tempInputFile))
                        player.setMediaItem(mediaItem)
                        player.prepare()
                        
                        // isVideoLoaded and renderTracks will be handled by STATE_READY in ExoPlayer listener
                } else {
                    showError("Could not process the selected video file.")
                    isImportLoading = false
                    isVideoLoaded = true
                    loadingScreen.visibility = View.GONE
                    finish()
                }
            } catch (e: Exception) {
                showError("Error initializing video: ${e.message}")
                isImportLoading = false
                isVideoLoaded = true
                loadingScreen.visibility = View.GONE
                finish()
            }
        }
    }

    private suspend fun getFilePathFromUri(uri: Uri): String? {
        var filePath: String? = null
        when (uri.scheme) {
            "content" -> {
                // For content URIs, always copy to cache to avoid Scoped Storage issues
                filePath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val tempFile = File(cacheDir, "imported_video_${System.currentTimeMillis()}.mp4")
                        contentResolver.openInputStream(uri)?.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempFile.absolutePath
                    } catch (e: Exception) {
                        Log.e("PathError", "Failed to copy file from URI: ${e.message}")
                        null
                    }
                }
            }
            "file" -> filePath = uri.path
            else -> Log.e("PathError", "Unsupported URI scheme: ${uri.scheme}")
        }
        Log.d("PathInfo", "File path: $filePath")
        return filePath
    }

    private fun renderTracks(project: VideoProject) {
        if (!::sequenceTrackContainer.isInitialized) return
        pendingRenderRunnable?.let { sequenceTrackContainer.removeCallbacks(it) }
        val runnable = Runnable {
            performRenderTracks(project)
        }
        pendingRenderRunnable = runnable
        sequenceTrackContainer.post(runnable)
    }

    private fun performRenderTracks(project: VideoProject) {
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()

        val sequenceItems = getSequenceItems()

        val totalSequenceDuration = getTotalSequenceDuration()
        if (totalSequenceDuration <= 0L) return

        val mediaSourceFactory = com.google.android.exoplayer2.source.DefaultMediaSourceFactory(this)
        
        val exoAudioOps = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio>() ?: emptyList()
        
        val boundaries = mutableSetOf<Long>()
        boundaries.add(0L)
        boundaries.add(totalSequenceDuration)
        
        var currentGlobalPosMs = 0L
        sequenceItems.forEach { item ->
            boundaries.add(currentGlobalPosMs)
            currentGlobalPosMs += item.trimmedDurationMs
            boundaries.add(currentGlobalPosMs)
        }
        
        exoAudioOps.forEach { op ->
            boundaries.add(op.startTimeMs ?: 0L)
            boundaries.add(op.endTimeMs ?: totalSequenceDuration)
        }
        
        val sortedBoundaries = boundaries.filter { it in 0..totalSequenceDuration }.sorted()
        val chunkedSources = mutableListOf<com.google.android.exoplayer2.source.MediaSource>()
        val newChunkDurations = mutableListOf<Long>()
        
        for (i in 0 until sortedBoundaries.size - 1) {
            val chunkStartMs = sortedBoundaries[i]
            val chunkEndMs = sortedBoundaries[i + 1]
            val chunkDurationMs = chunkEndMs - chunkStartMs
            if (chunkDurationMs <= 0L) continue
            
            var videoSourceForChunk: com.google.android.exoplayer2.source.MediaSource? = null
            var vGlobalMs = 0L
            for (item in sequenceItems) {
                val vStart = vGlobalMs
                val vEnd = vGlobalMs + item.trimmedDurationMs
                if (chunkStartMs >= vStart && chunkEndMs <= vEnd) {
                    val offsetInVideoMs = chunkStartMs - vStart
                    
                    val clipStartUs: Long
                    val videoUri: Uri
                    if (item.proxyUri != null) {
                        clipStartUs = offsetInVideoMs * 1000L
                        videoUri = item.proxyUri
                    } else {
                        clipStartUs = (item.trimStartMs + offsetInVideoMs) * 1000L
                        videoUri = item.uri
                    }
                    val clipEndUs = clipStartUs + (chunkDurationMs * 1000L)
                    
                    val videoMediaItem = com.google.android.exoplayer2.MediaItem.Builder()
                        .setUri(videoUri)
                        .build()
                    val baseVideoSource = mediaSourceFactory.createMediaSource(videoMediaItem)
                    
                    videoSourceForChunk = com.google.android.exoplayer2.source.ClippingMediaSource(
                        baseVideoSource, clipStartUs, clipEndUs, true, false, true
                    )
                    break
                }
                vGlobalMs += item.trimmedDurationMs
            }
            
            if (videoSourceForChunk == null) {
                videoSourceForChunk = com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L)
            }
            
            val chunkSourcesToMerge = mutableListOf<com.google.android.exoplayer2.source.MediaSource>(videoSourceForChunk)
            
            exoAudioOps.forEach { op ->
                val aStart = op.startTimeMs ?: 0L
                val aEnd = op.endTimeMs ?: totalSequenceDuration
                
                if (chunkStartMs >= aStart && chunkEndMs <= aEnd) {
                    val offsetInAudioMs = chunkStartMs - aStart
                    val clipStartUs = (op.internalStartMs + offsetInAudioMs) * 1000L
                    val clipEndUs = clipStartUs + (chunkDurationMs * 1000L)
                    
                    val actualAudioDurationUs = if (op.internalEndMs > 0) op.internalEndMs * 1000L else Long.MAX_VALUE
                    if (clipStartUs >= actualAudioDurationUs && actualAudioDurationUs != Long.MAX_VALUE) {
                        chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                    } else {
                        var safeClipEndUs = clipEndUs
                        if (actualAudioDurationUs != Long.MAX_VALUE && safeClipEndUs > actualAudioDurationUs) {
                            safeClipEndUs = actualAudioDurationUs
                        }
                        
                        val audioMediaItem = com.google.android.exoplayer2.MediaItem.Builder()
                            .setUri(op.audioUri)
                            .build()
                        val baseAudioSource = mediaSourceFactory.createMediaSource(audioMediaItem)
                        
                        try {
                            val audioSlice = com.google.android.exoplayer2.source.ClippingMediaSource(
                                baseAudioSource, clipStartUs, safeClipEndUs, true, false, true
                            )
                            chunkSourcesToMerge.add(audioSlice)
                        } catch (e: Exception) {
                            chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                        }
                    }
                } else {
                    chunkSourcesToMerge.add(com.google.android.exoplayer2.source.SilenceMediaSource(chunkDurationMs * 1000L))
                }
            }
            
            val mergedChunk = com.google.android.exoplayer2.source.MergingMediaSource(true, true, *chunkSourcesToMerge.toTypedArray())
            chunkedSources.add(mergedChunk)
            newChunkDurations.add(chunkDurationMs)
        }
        
        chunkDurationsMs = newChunkDurations
        val finalSource = com.google.android.exoplayer2.source.ConcatenatingMediaSource(*chunkedSources.toTypedArray())

        val globalPos = getGlobalPosition()
        val wasPlaying = player.isPlaying
        
        player.setMediaSource(finalSource)
        player.prepare()
        
        seekToGlobalPosition(globalPos)
        if (wasPlaying) player.play()


        val timelineWidth = (totalSequenceDuration * pixelsPerMs).toInt()

        // Set explicit widths for ruler and track containers
        timeRulerView.layoutParams = timeRulerView.layoutParams.apply {
            width = timelineWidth
        }
        timeRulerView.setVideoDuration(totalSequenceDuration)

        customVideoSeeker.setVideoDuration(totalSequenceDuration)

        sequenceTrackContainer.layoutParams = sequenceTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        textTrackContainer.layoutParams = textTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        imageTrackContainer.layoutParams = imageTrackContainer.layoutParams.apply {
            width = timelineWidth
        }
        audioTrackContainer.layoutParams = audioTrackContainer.layoutParams.apply {
            width = timelineWidth
        }

        // Render Sequence Track
        sequenceTrackContainer.removeAllViews()
        var accumulatedStartMs = 0L
        val transitionViewsToLayout = mutableListOf<Pair<View, FrameLayout.LayoutParams>>()

        sequenceItems.forEachIndexed { index, item ->
            val segmentView = layoutInflater.inflate(R.layout.item_sequence_segment, sequenceTrackContainer, false) as FrameLayout
            val segmentWidth = (item.trimmedDurationMs * pixelsPerMs).toInt()
            val segmentLeft = (accumulatedStartMs * pixelsPerMs).toInt()
            val params = FrameLayout.LayoutParams(segmentWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = segmentLeft
            }
            segmentView.layoutParams = params

            val rv = segmentView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
            val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.layoutManager = lm
            val itemWidth = maxOf(1, ((item.durationMs * pixelsPerMs) / 15).toInt())
            val adapter = FrameAdapter(emptyList(), itemWidth)
            rv.adapter = adapter
            
            // Align frames precisely with the trim bounds by offsetting the RecyclerView scroll
            rv.post {
                lm.scrollToPositionWithOffset(0, -(item.trimStartMs * pixelsPerMs).toInt())
            }

            // Extract or load cached frames
            val job = extractFramesForSegment(item.uri, item.durationMs, adapter)
            if (job != null) {
                activeRenderJobs.add(job)
            }

            val trackTrimView = segmentView.findViewById<com.tharunbirla.librecuts.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            trackTrimView.isMainVideoTrack = true
            trackTrimView.trackColor = android.graphics.Color.TRANSPARENT
            trackTrimView.maxDurationMs = item.durationMs
            trackTrimView.customMsPerPixel = 1.0f / pixelsPerMs
            trackTrimView.isTrimEnabled = false
            
            // Set the full untrimmed width on TrackTrimView and offset it
            val trackWidth = (item.durationMs * pixelsPerMs).toInt()
            trackTrimView.layoutParams = FrameLayout.LayoutParams(trackWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = -(item.trimStartMs * pixelsPerMs).toInt()
            }
            
            trackTrimView.activeStartMs = item.trimStartMs
            trackTrimView.activeEndMs = item.trimEndMs
            trackTrimView.setRange(item.durationMs, item.trimStartMs, item.trimEndMs)
            
            // Apply selection border if selected
            if (selectedVideoIndex == index) {
                segmentView.setBackgroundResource(R.drawable.bg_track_selected)
            } else {
                segmentView.background = null
            }
            
            trackTrimView.onTrackClicked = {
                if (selectedVideoIndex == index) {
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                } else {
                    selectedVideoIndex = index
                    enterVideoEditingMode()
                }
                viewModel.project.value?.let { renderTracks(it) }
            }
            
            segmentView.setBounceClickListener {
                if (selectedVideoIndex == index) {
                    selectedVideoIndex = null
                    exitVideoEditingMode()
                } else {
                    selectedVideoIndex = index
                    enterVideoEditingMode()
                }
                viewModel.project.value?.let { renderTracks(it) }
            }
            segmentView.setOnLongClickListener {
                showVideoSegmentTrimDialog(index, item)
                true
            }
            
            val btnLeft = segmentView.findViewById<ImageButton>(R.id.btnMoveLeft)
            val btnRight = segmentView.findViewById<ImageButton>(R.id.btnMoveRight)
            
            if (index > 0) { // Merged items
                btnLeft.visibility = View.VISIBLE
                btnRight.visibility = if (index < sequenceItems.size - 1) View.VISIBLE else View.GONE
                
                btnLeft.setBounceClickListener { viewModel.reorderMergeVideo(index - 1, moveForward = true) }
                btnRight.setBounceClickListener { viewModel.reorderMergeVideo(index - 1, moveForward = false) }
            } else {
                // Source video cannot be reordered from here
                btnLeft.visibility = View.GONE
                btnRight.visibility = View.GONE
            }

            if (index > 0) {
                val transitionView = layoutInflater.inflate(R.layout.item_transition_button, sequenceTrackContainer, false)
                val btnTransition = transitionView.findViewById<ImageView>(R.id.btnTransition)
                
                // Highlight if a transition is selected
                val transitionOp = project.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Transition>().find { it.index == index - 1 }
                if (transitionOp != null && transitionOp.type != "none") {
                    btnTransition.setImageResource(R.drawable.ic_check_24) // Or some infinite icon
                    btnTransition.setColorFilter(android.graphics.Color.WHITE)
                    transitionView.background = null
                } else {
                    btnTransition.setImageResource(R.drawable.ic_add_24)
                }
                
                val transitionWidth = 24.dpToPx()
                val transParams = FrameLayout.LayoutParams(transitionWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    leftMargin = segmentLeft - transitionWidth / 2
                }
                transitionViewsToLayout.add(Pair(transitionView, transParams))
                transitionView.setOnClickListener {
                    showTransitionDialog(index - 1)
                }
            }

            sequenceTrackContainer.addView(segmentView)
            accumulatedStartMs += item.trimmedDurationMs
        }

        // Add transition buttons on top of segment views
        for ((view, params) in transitionViewsToLayout) {
            sequenceTrackContainer.addView(view, params)
        }
        
        if (activeRenderJobs.isNotEmpty()) {
            // Keep track if needed but don't block
        }

        // Text tracks
        textTrackContainer.removeAllViews()
        val textOps = project.operations.filterIsInstance<EditOperation.AddText>()
        if (textOps.isNotEmpty()) {
            textTrackContainer.visibility = View.VISIBLE
            for (op in textOps) {
                val trackView = com.tharunbirla.librecuts.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                        topMargin = 4.dpToPx()
                    }
                    trackColor = android.graphics.Color.parseColor("#E91E63") // Pink for text
                    trackLabel = op.text
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_text_24)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    maxDurationMs = totalSequenceDuration
                    activeStartMs = op.startTimeMs ?: 0L
                    activeEndMs = op.endTimeMs ?: totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    setRange(totalSequenceDuration, op.startTimeMs ?: 0L, op.endTimeMs ?: totalSequenceDuration)
                    onTrimChanged = { start, end, _ ->
                        viewModel.updateOperation(op.copy(startTimeMs = start, endTimeMs = end))
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end)
                        } else {
                            seekToGlobalPosition(start)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                    }
                }
                textTrackContainer.addView(trackView)
            }
        } else {
            textTrackContainer.visibility = View.GONE
        }

        // Image tracks
        imageTrackContainer.removeAllViews()
        val imageOps = project.operations.filterIsInstance<EditOperation.AddImageOverlay>()
        if (imageOps.isNotEmpty()) {
            imageTrackContainer.visibility = View.VISIBLE
            for (op in imageOps) {
                val trackView = com.tharunbirla.librecuts.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                        topMargin = 4.dpToPx()
                    }
                    trackColor = android.graphics.Color.parseColor("#FF9800") // Orange for image
                    trackLabel = op.imageUri.lastPathSegment ?: "Image Overlay"
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_image_24)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    maxDurationMs = totalSequenceDuration
                    activeStartMs = op.startTimeMs ?: 0L
                    activeEndMs = op.endTimeMs ?: totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    setRange(totalSequenceDuration, op.startTimeMs ?: 0L, op.endTimeMs ?: totalSequenceDuration)
                    onTrimChanged = { start, end, _ ->
                        viewModel.updateOperation(op.copy(startTimeMs = start, endTimeMs = end))
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end)
                        } else {
                            seekToGlobalPosition(start)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                    }
                    
                    val viewRef = this
                    val imageJob = lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val path = getFilePathFromUri(op.imageUri)
                        if (path != null) {
                            try {
                                val options = android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 }
                                val bitmap = android.graphics.BitmapFactory.decodeFile(path, options)
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    viewRef.trackThumbnail = bitmap
                                    viewRef.invalidate()
                                }
                            } catch (e: Exception) {}
                        }
                    }
                    activeRenderJobs.add(imageJob)
                }
                imageTrackContainer.addView(trackView)
            }
        } else {
            imageTrackContainer.visibility = View.GONE
        }

        // Audio tracks
        audioTrackContainer.removeAllViews()
        val audioOps = project.operations.filterIsInstance<EditOperation.AddBackgroundAudio>()
        if (audioOps.isNotEmpty()) {
            audioTrackContainer.visibility = View.VISIBLE
            for (op in audioOps) {
                val trackView = com.tharunbirla.librecuts.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                        topMargin = 4.dpToPx()
                    }
                    trackColor = android.graphics.Color.parseColor("#4CAF50") // Green for audio
                    trackLabel = op.audioUri.lastPathSegment ?: "Audio Track"
                    isAudioTrack = true
                    isSelectedTrack = (op.id == viewModel.selectedOperationId.value)
                    trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_audio_24)
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == op.id) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(op.id)
                        }
                    }
                    maxDurationMs = totalSequenceDuration
                    activeStartMs = op.startTimeMs ?: 0L
                    activeEndMs = op.endTimeMs ?: totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    setRange(totalSequenceDuration, op.startTimeMs ?: 0L, op.endTimeMs ?: totalSequenceDuration)
                    onTrimChanged = { start, end, target ->
                        val oldOp = viewModel.project.value?.operations?.find { 
                            (it as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio)?.id == op.id 
                        } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio
                        if (oldOp != null) {
                            var newInternalStart = oldOp.internalStartMs
                            if (target == com.tharunbirla.librecuts.customviews.TrackTrimView.DragTarget.LEFT) {
                                val delta = start - (oldOp.startTimeMs ?: 0L)
                                newInternalStart = (newInternalStart + delta).coerceAtLeast(0L)
                            }
                            viewModel.updateOperation(oldOp.copy(startTimeMs = start, endTimeMs = end, internalStartMs = newInternalStart))
                        }
                    }
                    onTrimAdjustingWithDelta = { start, end, deltaStart, deltaEnd ->
                        if (deltaStart != 0L) {
                            seekToGlobalPosition(start)
                        } else if (deltaEnd != 0L) {
                            seekToGlobalPosition(end)
                        } else {
                            seekToGlobalPosition(start)
                        }
                    }
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                    }
                }
                audioTrackContainer.addView(trackView)
            }
        } else {
            audioTrackContainer.visibility = View.GONE
        }

        if (activeExtractionCount == 0 && isImportLoading) {
            isImportLoading = false
            loadingScreen.visibility = View.GONE
        }
    }

    private fun showVideoSegmentTrimDialog(index: Int, item: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        if (isShowingPreview) dismissPreview()

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        bottomSheet.setContentView(view)

        val tvDurationDisplay = view.findViewById<TextView>(R.id.tvTrimDuration)
        val trimTrackView = view.findViewById<com.tharunbirla.librecuts.customviews.TrackTrimView>(R.id.trimTrackView)
        val recyclerView = view.findViewById<RecyclerView>(R.id.trimRecyclerView)
        val btnDone = view.findViewById<Button>(R.id.btnDoneTrim)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseTrimSheet)

        // Configure RecyclerView for 15 dynamic tiles
        recyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        val dialogRecyclerViewWidth = resources.displayMetrics.widthPixels - 40.dpToPx()
        val dialogItemWidth = maxOf(1, dialogRecyclerViewWidth / 15)
        val adapter = FrameAdapter(emptyList(), dialogItemWidth)
        recyclerView.adapter = adapter

        // Extract frames for the entire video clip
        val job = extractFramesForSegment(item.uri, item.durationMs, adapter)
        if (job != null) {
            activeRenderJobs.add(job)
        }

        // Configure custom TrackTrimView as the premium Range Slider
        trimTrackView.isMainVideoTrack = true
        trimTrackView.isTrimEnabled = true
        trimTrackView.trackColor = android.graphics.Color.TRANSPARENT
        trimTrackView.maxDurationMs = item.durationMs
        trimTrackView.customMsPerPixel = item.durationMs.toFloat() / dialogRecyclerViewWidth

        trimTrackView.activeStartMs = item.trimStartMs
        trimTrackView.activeEndMs = item.trimEndMs
        trimTrackView.setRange(item.durationMs, item.trimStartMs, item.trimEndMs)

        var currentStart = item.trimStartMs
        var currentEnd = item.trimEndMs

        fun updateTimeText(start: Long, end: Long) {
            val duration = end - start
            tvDurationDisplay.text = "${formatDuration(start.toInt())} - ${formatDuration(end.toInt())} (${formatDuration(duration.toInt())})"
        }
        updateTimeText(item.trimStartMs, item.trimEndMs)

        trimTrackView.onTrimAdjustingWithDelta = { start, end, deltaL, deltaR ->
            currentStart = start
            currentEnd = end
            updateTimeText(start, end)

            val sequenceItems = getSequenceItems()
            val clipStartGlobal = sequenceItems.take(index).sumOf { it.trimmedDurationMs }
            if (deltaL != 0L) {
                seekToGlobalPosition(clipStartGlobal + start)
            } else if (deltaR != 0L) {
                seekToGlobalPosition(clipStartGlobal + end)
            }
        }

        trimTrackView.onTrimChanged = { start, end, _ ->
            currentStart = start
            currentEnd = end
            updateTimeText(start, end)
        }

        btnDone.setBounceClickListener {
            if (index == 0) {
                viewModel.updateMainVideoTrim(currentStart, currentEnd)
            } else {
                viewModel.updateMergeItemTrim(index - 1, currentStart, currentEnd)
            }
            bottomSheet.dismiss()
        }

        btnClose.setBounceClickListener {
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun showTransitionDialog(transitionIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_transitions, null)
        bottomSheet.setContentView(view)

        val transitionsList = view.findViewById<LinearLayout>(R.id.transitionsList)
        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Transition>().find { it.index == transitionIndex }
        
        val transitions = listOf(
            Pair("none", "None"),
            Pair("fade", "Fade"),
            Pair("dissolve", "Dissolve"),
            Pair("wipeleft", "Wipe L"),
            Pair("wiperight", "Wipe R"),
            Pair("slideleft", "Slide L"),
            Pair("slideright", "Slide R"),
            Pair("smoothleft", "Smth L"),
            Pair("smoothright", "Smth R"),
            Pair("circlecrop", "Circle"),
            Pair("distance", "Distance"),
            Pair("pixelize", "Pixelize"),
            Pair("hlslice", "H-Slice"),
            Pair("vlslice", "V-Slice")
        )

        for ((type, name) in transitions) {
            val itemView = layoutInflater.inflate(R.layout.item_transition_option, transitionsList, false)
            val tvName = itemView.findViewById<TextView>(R.id.transitionName)
            val tvShort = itemView.findViewById<TextView>(R.id.transitionShortName)
            val bg = itemView.findViewById<FrameLayout>(R.id.transitionIconBg)

            tvName.text = name
            tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()

            val isSelected = (existingOp?.type == type) || (existingOp == null && type == "none")
            if (isSelected) {
                bg.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                tvShort.setTextColor(android.graphics.Color.WHITE)
            } else {
                bg.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                tvShort.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
            }

            itemView.setOnClickListener {
                if (type == "none") {
                    if (existingOp != null) {
                        viewModel.removeOperation(existingOp.id)
                    }
                } else {
                    if (existingOp != null) {
                        viewModel.updateOperation(existingOp.copy(type = type))
                    } else {
                        viewModel.addTransitionOperation(transitionIndex, type)
                    }
                }
                bottomSheet.dismiss()
                viewModel.project.value?.let { renderTracks(it) }
            }

            transitionsList.addView(itemView)
        }

        bottomSheet.show()
    }

    private fun setupCustomSeeker() {
        customVideoSeeker.onSeekListener = { seekPosition ->
            // Dismiss preview when user manually seeks
            if (isShowingPreview) dismissPreview()

            val totalDuration = getTotalSequenceDuration()
            val newSeekTime = (totalDuration * seekPosition).toLong()
            if (newSeekTime >= 0 && newSeekTime <= totalDuration) {
                seekToGlobalPosition(newSeekTime)
                updateDurationDisplay(newSeekTime.toInt(), totalDuration.toInt())
                
                textOverlayView?.currentPositionMs = newSeekTime
                imageOverlayView?.currentPositionMs = newSeekTime
            } else {
                Log.d("SeekError", "Seek position out of bounds.")
            }
        }
    }

    private fun setupTrackInitializers() {
        // Nothing here anymore since sequence track is dynamic
    }

    // Inside VideoEditingActivity

    private fun extractFramesForSegment(uri: Uri, durationMs: Long, adapter: FrameAdapter): kotlinx.coroutines.Job? {
        if (durationMs <= 0) return null
        
        // Return cached if available
        if (frameCache.containsKey(uri)) {
            adapter.updateFrames(frameCache[uri]!!)
            return null
        }

        activeExtractionCount++
        if (isImportLoading) {
            loadingScreen.visibility = View.VISIBLE
        }

        return lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            val tempFrames = mutableListOf<Bitmap>()
            try {
                val path = getFilePathFromUri(uri)
                if (path != null) {
                    retriever.setDataSource(path)
                } else {
                    retriever.setDataSource(this@VideoEditingActivity, uri)
                }

                val frameCount = 15
                val intervalUs = (durationMs * 1000) / frameCount

                for (i in 0 until frameCount) {
                    if (!coroutineContext.isActive) break
                    val timeUs = i * intervalUs
                    val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 200, 150)
                    } else {
                        retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }

                    bitmap?.let {
                        val finalBitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) it else processBitmap(it)
                        tempFrames.add(finalBitmap)
                        withContext(Dispatchers.Main) {
                            adapter.addFrame(finalBitmap)
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    frameCache[uri] = tempFrames
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Extraction error for segment: ${e.message}")
                }
            } finally {
                retriever.release()
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.Main) {
                        activeExtractionCount--
                        if (activeExtractionCount <= 0) {
                            activeExtractionCount = 0
                            if (isImportLoading) {
                                isImportLoading = false
                                loadingScreen.visibility = View.GONE
                            }
                        }
                    }
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
        isRenderingPreview = true
        updateUIInteractionState()

        previewJob = lifecycleScope.launch {
            val seekPos = if (::player.isInitialized) player.currentPosition else 0L
            val previewOutput = File(cacheDir, "preview_segment_${System.currentTimeMillis()}.mp4")

            val cmd = viewModel.buildPreviewCommand(
                sourceFilePath = tempInputFile.absolutePath,
                previewOutputPath = previewOutput.absolutePath,
                seekPositionMs = seekPos,
                fontFilePath = fontFilePath
            ) ?: run {
                isRenderingPreview = false
                updateUIInteractionState()
                return@launch
            }

            Log.d(TAG, "Rendering segmented preview...")

            val result = withContext(Dispatchers.IO) {
                ffmpegEngine.executeCommand(cmd)
            }

            isRenderingPreview = false
            if (result is FFmpegRenderEngine.RenderResult.Success && previewOutput.exists()) {
                isShowingPreview = true
                previewFile?.delete() // Clean up previous preview
                previewFile = previewOutput

                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(previewOutput)))
                player.prepare()
                player.play()

                // Show preview badge
                try {
                    tvPreviewBadge?.visibility = View.VISIBLE
                } catch (_: Exception) {}
                updateUIInteractionState()
            } else {
                Log.w(TAG, "Preview render failed or was cancelled")
                previewOutput.delete()
                updateUIInteractionState()
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
            tvPreviewBadge?.visibility = View.GONE
        } catch (_: Exception) {}

        previewFile?.delete()
        previewFile = null
        updateUIInteractionState()
    }

    override fun onDestroy() {
        if (::sequenceTrackContainer.isInitialized) {
            pendingRenderRunnable?.let { sequenceTrackContainer.removeCallbacks(it) }
        }
        frameExtractionJob?.cancel()
        previewJob?.cancel()
        extractedFrames.forEach { if (!it.isRecycled) it.recycle() }
        draggableTextOverlay?.deactivate()
        draggableImageOverlay?.deactivate()
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

                setBounceClickListener {
                    selectedTextColor = colorHex
                    draggableTextOverlay?.setTextColor(colorHex)
                    setupColorPicker(toolbar)
                }
            }
            colorPickerList.addView(colorView)
        }
    }

    private fun updateActiveBoundaries(activeStart: Long, activeEnd: Long) {
        for (i in 0 until textTrackContainer.childCount) {
            val track = textTrackContainer.getChildAt(i) as? com.tharunbirla.librecuts.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }

        for (i in 0 until imageTrackContainer.childCount) {
            val track = imageTrackContainer.getChildAt(i) as? com.tharunbirla.librecuts.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }

        for (i in 0 until audioTrackContainer.childCount) {
            val track = audioTrackContainer.getChildAt(i) as? com.tharunbirla.librecuts.customviews.TrackTrimView
            track?.let {
                it.activeStartMs = activeStart
                it.activeEndMs = activeEnd
                it.invalidate()
            }
        }
    }

    private fun showQuitConfirmationDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Quit Editing?")
            .setMessage("Are you sure you want to quit? Any unsaved edits will be lost.")
            .setPositiveButton("Quit") { _, _ ->
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
        private const val PICK_AUDIO_REQUEST = 2
        private const val PICK_IMAGE_REQUEST = 3
    }
}
