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
import android.os.Build
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
    private lateinit var btnMoveLayerUp: ImageButton
    private lateinit var btnMoveLayerDown: ImageButton

    private lateinit var timelineHorizontalScroll: android.widget.HorizontalScrollView
    private lateinit var timelineVerticalScroll: android.widget.ScrollView
    private lateinit var btnTimelineAdd: View
    private lateinit var timelineContainer: FrameLayout
    private var isUserScrollingTimeline = false
    private var isTrackDragging = false
    private var isProgrammaticScroll = false
    private var pixelsPerMs: Float = 0.3f
    private var lastSnappedTargetMs: Long = -1L
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
    private lateinit var emptyProjectState: View

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
    private var exportJob: Job? = null
    private val activeRenderJobs = mutableListOf<Job>()
    private var pendingRenderRunnable: Runnable? = null
    private var isVideoLoaded = false
    private var isImportLoading = true
    private var activeExtractionCount = 0
    private var chunkDurationsMs = listOf<Long>()
    private var originalMainVideoDurationMs: Long = 0L
    private val frameCache = mutableMapOf<Uri, List<Bitmap>>()
    private var activeDirectoryTitleView: TextView? = null
    private var activeDirectoryPathView: TextView? = null

    // Drag-to-rearrange clips state
    private var isDraggingSegment = false
    private var draggedIndex = -1
    private var draggedView: View? = null
    private var initialTouchX = 0f
    private var initialScrollX = 0
    private var segmentViews = mutableListOf<View>()
    private var originalLefts = mutableListOf<Int>()
    private var currentDragOrder = listOf<Int>()
    private var activeSegmentViews = mutableListOf<View>()


    // ── FONT: cached absolute path to Roboto-Regular.ttf in cacheDir ──────────
    // Populated in onCreate via ffmpegEngine.copyFontToCache().
    // Passed to buildConsolidatedFFmpegCommand() so drawtext can find the font.
    private var fontFilePath: String? = null

    private fun populateFontSpinner(formatContainer: View?) {
        val spinnerFonts = formatContainer?.findViewById<Spinner>(R.id.spinnerFonts)
        if (spinnerFonts == null) return

        val fontNames = mutableListOf<String>()
        val fontPaths = mutableListOf<String?>()

        fun addFontEntry(name: String, path: String?) {
            if (path.isNullOrBlank()) return
            if (fontPaths.contains(path)) return
            fontNames.add(name)
            fontPaths.add(path)
        }

        addFontEntry("Roboto Regular", fontFilePath)

        val fontDirectories = listOf(
            File("/system/fonts"),
            File("/data/fonts"),
            File("/product/fonts"),
            File(cacheDir, "fonts")
        )

        for (dir in fontDirectories) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.listFiles()
                ?.filter { file -> file.isFile && file.extension.lowercase(Locale.US) in setOf("ttf", "otf") }
                ?.sortedBy { it.nameWithoutExtension.lowercase(Locale.US) }
                ?.forEach { file ->
                    addFontEntry(file.nameWithoutExtension, file.absolutePath)
                }
        }

        if (fontNames.isEmpty()) {
            fontNames.add("Roboto Regular")
            fontPaths.add(fontFilePath)
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, fontNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerFonts.adapter = adapter

        val defaultIndex = fontPaths.indexOf(fontFilePath).takeIf { it >= 0 } ?: 0
        spinnerFonts.setSelection(defaultIndex)
        spinnerFonts.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedFontPath = fontPaths.getOrNull(position)
                draggableTextOverlay?.setFontPath(selectedFontPath)

                if (formatContainer?.visibility == View.VISIBLE) {
                    formatContainer.visibility = View.GONE
                    val btnFormat = (formatContainer.parent as? View)?.findViewById<ImageButton>(R.id.btnTextFormatTab)
                    btnFormat?.setColorFilter(getColor(R.color.toolTextInactive))
                }
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
    }

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
    private var isMagnetEnabled = true

    // Video layer selection state
    private var selectedVideoIndex: Int? = null
    private var videoEditingToolbar: View? = null
    private var audioEditingToolbar: View? = null
    private var speedEditingToolbar: View? = null
    private var cropEditingToolbar: View? = null
    private var cropOverlayView: com.tharunbirla.librecuts.customviews.CropOverlayView? = null
    private var initialCropOperation: com.tharunbirla.librecuts.models.EditOperation.Crop? = null
    private var subtitlesEditingToolbar: View? = null
    private var isSubtitlesEditingActive = false

    // Segmented preview state
    private var previewJob: Job? = null
    private var isShowingPreview = false
    private var isRenderingPreview = false
    private var previewFile: File? = null
    
    private var audioPreviewPlayer: android.media.MediaPlayer? = null
    
    private var isInitialFitDone = false

    private var mediaRecorder: android.media.MediaRecorder? = null
    private var isRecordingVoiceOver = false
    private var voiceOverFile: File? = null
    private var voiceOverStartTimeMs = 0L

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showVoiceOverDialog()
        } else {
            Toast.makeText(this, R.string.toast_microphone_permission_required, Toast.LENGTH_SHORT).show()
        }
    }
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
                    val totalDuration = getTotalSequenceDuration()
                    val currentGlobalPos = getGlobalPosition()
                    
                    // If we reached the end of the timeline, restart from beginning
                    if (currentGlobalPos >= totalDuration - 100L || player.playbackState == Player.STATE_ENDED) {
                        seekToGlobalPosition(0L)
                        timelineHorizontalScroll.scrollTo(0, 0)
                        updateDurationDisplay(0, totalDuration.toInt())
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

        val btnCaptureFrame = findViewById<ImageButton>(R.id.btnCaptureFrame)
        btnCaptureFrame.setBounceClickListener {
            captureCurrentFrame()
        }

        val btnMagnet = findViewById<ImageButton>(R.id.btnMagnet)
        btnMagnet.setImageResource(if (isMagnetEnabled) R.drawable.ic_magnet_24 else R.drawable.ic_magnet_off_24)
        btnMagnet.setBounceClickListener {
            isMagnetEnabled = !isMagnetEnabled
            if (isMagnetEnabled) {
                btnMagnet.setImageResource(R.drawable.ic_magnet_24)
                Toast.makeText(this, R.string.toast_snapping_enabled, Toast.LENGTH_SHORT).show()
            } else {
                btnMagnet.setImageResource(R.drawable.ic_magnet_off_24)
                Toast.makeText(this, R.string.toast_snapping_disabled, Toast.LENGTH_SHORT).show()
            }
            draggableTextOverlay?.isSnappingEnabled = isMagnetEnabled
            draggableImageOverlay?.isSnappingEnabled = isMagnetEnabled
        }

        btnMoveLayerUp = findViewById(R.id.btnMoveLayerUp)
        btnMoveLayerDown = findViewById(R.id.btnMoveLayerDown)
        btnMoveLayerUp.setBounceClickListener {
            viewModel.selectedOperationId.value?.let { id ->
                viewModel.moveOverlayOperation(id, moveUp = true)
            }
        }
        btnMoveLayerDown.setBounceClickListener {
            viewModel.selectedOperationId.value?.let { id ->
                viewModel.moveOverlayOperation(id, moveUp = false)
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

                val currentPos = getGlobalPosition()
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
                Toast.makeText(this, R.string.toast_log_copied_to_clipboard, Toast.LENGTH_SHORT).show()
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
        showLoading(getString(R.string.loading), getString(R.string.loading_tag))
        isImportLoading = true
        exportScreen = findViewById(R.id.exportScreen)
        exportScreen.findViewById<View>(R.id.btnCancelExport)?.setBounceClickListener {
            cancelExport()
        }
        previewLoadingOverlay = findViewById(R.id.previewLoadingOverlay)
        lottieAnimationView = findViewById(R.id.lottieAnimation)
        textTrackContainer = findViewById(R.id.textTrackContainer)
        imageTrackContainer = findViewById(R.id.imageTrackContainer)
        audioTrackContainer = findViewById(R.id.audioTrackContainer)

        btnPlayPause = findViewById(R.id.btnPlayPause)
        timelineHorizontalScroll = findViewById(R.id.timelineHorizontalScroll)
        timelineContainer = findViewById(R.id.timelineContainer)
        timelineVerticalScroll = findViewById(R.id.timelineVerticalScroll)
        btnTimelineAdd = findViewById(R.id.btnTimelineAdd)
        
        emptyProjectState = findViewById(R.id.emptyProjectState)
        emptyProjectState.setBounceClickListener {
            openFilePickerMain()
        }

        btnTimelineAdd.setBounceClickListener {
            openFilePickerMerge()
        }

        timelineContainer.post {
            val halfWidth = timelineContainer.width / 2
            timelineHorizontalScroll.setPadding(halfWidth, 0, halfWidth, 0)
            updateTimelineAddButtonPosition()
        }

        timelineVerticalScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            updateTimelineAddButtonPosition()
        }

        timelineHorizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            if (!isProgrammaticScroll) {
                var targetMs = (scrollX / pixelsPerMs).toLong()

                if (isMagnetEnabled) {
                    val snapTargets = getSnapTargetsMs()
                    val thresholdPx = 10f
                    val thresholdMs = (thresholdPx / pixelsPerMs).toLong()

                    var snapped = false
                    for (target in snapTargets) {
                        if (Math.abs(targetMs - target) <= thresholdMs) {
                            targetMs = target
                            snapped = true
                            if (lastSnappedTargetMs != target) {
                                timelineHorizontalScroll.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                                lastSnappedTargetMs = target
                            }
                            if (!isUserScrollingTimeline) {
                                val snappedScrollX = (targetMs * pixelsPerMs).toInt()
                                if (scrollX != snappedScrollX) {
                                    isProgrammaticScroll = true
                                    timelineHorizontalScroll.scrollTo(snappedScrollX, 0)
                                    isProgrammaticScroll = false
                                }
                            }
                            break
                        }
                    }
                    if (!snapped) {
                        lastSnappedTargetMs = -1L
                    }
                } else {
                    lastSnappedTargetMs = -1L
                }

                seekToGlobalPosition(targetMs)
            }
            updateTimelineAddButtonPosition()
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
                        if (isMagnetEnabled) {
                            val scrollX = timelineHorizontalScroll.scrollX
                            val targetMs = (scrollX / pixelsPerMs).toLong()
                            val snapTargets = getSnapTargetsMs()
                            val thresholdPx = 10f
                            val thresholdMs = (thresholdPx / pixelsPerMs).toLong()
                            for (target in snapTargets) {
                                if (Math.abs(targetMs - target) <= thresholdMs) {
                                    val snappedScrollX = (target * pixelsPerMs).toInt()
                                    if (scrollX != snappedScrollX) {
                                        isProgrammaticScroll = true
                                        timelineHorizontalScroll.scrollTo(snappedScrollX, 0)
                                        isProgrammaticScroll = false
                                        seekToGlobalPosition(target)
                                    }
                                    break
                                }
                            }
                        }
                    }
                }
                false
            }
        }

        textOverlayView = try {
            findViewById<com.tharunbirla.librecuts.customviews.TextOverlayView>(R.id.textOverlayView)?.also { overlay ->
                overlay.onSubtitlePositionChanged = { relX, relY ->
                    val project = viewModel.project.value
                    val subOp = project?.operations?.find { it is EditOperation.AddSubtitles } as? EditOperation.AddSubtitles
                    if (subOp != null) {
                        updateSubtitleOp(subOp.copy(relativeX = relX, relativeY = relY))
                    }
                }
            }
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

        draggableTextOverlay = try {
            findViewById<DraggableTextOverlayView>(R.id.draggableTextOverlay)?.also { overlay ->
                overlay.isSnappingEnabled = isMagnetEnabled
                overlay.onTextCommitted = { text, fontSize, relX, relY, color, fontPath, opacity, borderThickness, borderColor, textAlign, letterSpacing, lineSpacing ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddText)?.id == selectedId } as? EditOperation.AddText
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                text = text, fontSize = fontSize, relativeX = relX, relativeY = relY, color = color,
                                fontPath = fontPath, opacity = opacity, borderThickness = borderThickness, borderColor = borderColor,
                                textAlign = textAlign, letterSpacing = letterSpacing, lineSpacing = lineSpacing
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
                            endTimeMs = end,
                            fontPath = fontPath,
                            opacity = opacity,
                            borderThickness = borderThickness,
                            borderColor = borderColor,
                            textAlign = textAlign,
                            letterSpacing = letterSpacing,
                            lineSpacing = lineSpacing
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
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Text")
                        .setMessage("Are you sure you want to delete this text overlay?")
                        .setPositiveButton("Delete") { _, _ ->
                            draggableTextOverlay?.deactivate()
                            viewModel.selectedOperationId.value?.let { id ->
                                viewModel.removeOperation(id)
                            }
                            exitTextEditingMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<View>(R.id.btnTextDuplicate)?.setBounceClickListener {
                    duplicateSelectedOverlay()
                }


                val btnKeyboard = toolbar.findViewById<ImageButton>(R.id.btnTextKeyboardTab)
                val btnPalette = toolbar.findViewById<ImageButton>(R.id.btnTextPaletteTab)
                val btnFormat = toolbar.findViewById<ImageButton>(R.id.btnTextFormatTab)
                val colorContainer = toolbar.findViewById<View>(R.id.colorPickerContainer)
                val formatContainer = toolbar.findViewById<View>(R.id.formatSettingsContainer)

                fun resetTabs() {
                    colorContainer?.visibility = View.GONE
                    formatContainer?.visibility = View.GONE
                    btnKeyboard?.setColorFilter(getColor(R.color.toolTextInactive))
                    btnPalette?.setColorFilter(getColor(R.color.toolTextInactive))
                    btnFormat?.setColorFilter(getColor(R.color.toolTextInactive))
                }

                draggableTextOverlay?.onEditingFocused = {
                    resetTabs()
                    btnKeyboard?.setColorFilter(getColor(R.color.colorPrimary))
                }

                btnKeyboard?.setBounceClickListener {
                    resetTabs()
                    btnKeyboard.setColorFilter(getColor(R.color.colorPrimary))
                    draggableTextOverlay?.requestEditingFocus()
                }

                btnPalette?.setBounceClickListener {
                    draggableTextOverlay?.hideKeyboard()

                    resetTabs()
                    colorContainer?.visibility = View.VISIBLE
                    btnPalette.setColorFilter(getColor(R.color.colorPrimary))
                    setupColorPicker(toolbar)
                }

                btnFormat?.setBounceClickListener {
                    draggableTextOverlay?.hideKeyboard()
                    
                    resetTabs()
                    formatContainer?.visibility = View.VISIBLE
                    btnFormat.setColorFilter(getColor(R.color.colorPrimary))
                }
                
                // Format UI listeners
                populateFontSpinner(formatContainer)

                val seekOpacity = formatContainer?.findViewById<Slider>(R.id.seekOpacity)
                seekOpacity?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setOpacity(value / 100f)
                }
                
                val seekBorderWidth = formatContainer?.findViewById<Slider>(R.id.seekBorderWidth)
                seekBorderWidth?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setBorderThickness(value.toInt())
                }
                
                val seekLetterSpacing = formatContainer?.findViewById<Slider>(R.id.seekLetterSpacing)
                seekLetterSpacing?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setLetterSpacing((value - 50f) / 100f)
                }

                val seekLineSpacing = formatContainer?.findViewById<Slider>(R.id.seekLineSpacing)
                seekLineSpacing?.addOnChangeListener { _, value, _ ->
                    draggableTextOverlay?.setLineSpacing((value - 50f) * 2f)
                }
                
                val btnAlignL = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignLeft)
                val btnAlignC = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignCenter)
                val btnAlignR = formatContainer?.findViewById<android.widget.Button>(R.id.btnAlignRight)
                
                btnAlignL?.setOnClickListener { 
                    draggableTextOverlay?.setTextAlign("left")
                    btnAlignL.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                    btnAlignC?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignR?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                }
                btnAlignC?.setOnClickListener { 
                    draggableTextOverlay?.setTextAlign("center") 
                    btnAlignL?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignC.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                    btnAlignR?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                }
                btnAlignR?.setOnClickListener { 
                    draggableTextOverlay?.setTextAlign("right") 
                    btnAlignL?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignC?.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.inactiveToolBackground))
                    btnAlignR.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(R.color.colorPrimary))
                }

                resetTabs()
                btnKeyboard?.setColorFilter(getColor(R.color.colorPrimary))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text editing toolbar not found: ${e.message}")
            null
        }

        draggableImageOverlay = try {
            findViewById<DraggableImageOverlayView>(R.id.draggableImageOverlay)?.also { overlay ->
                overlay.isSnappingEnabled = isMagnetEnabled
                overlay.onImageCommitted = { uri, relX, relY, relW, relH, rotationAngle, opacity, isMirrored ->
                    val selectedId = viewModel.selectedOperationId.value
                    if (selectedId != null) {
                        val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                        if (op != null) {
                            viewModel.updateOperation(op.copy(
                                imageUri = uri, relativeX = relX, relativeY = relY, relativeWidth = relW, relativeHeight = relH, rotationAngle = rotationAngle, opacity = opacity, isMirrored = isMirrored
                            ))
                        }
                    } else {
                        val start = getGlobalPosition()
                        val fileDuration = getOverlayFileDurationMs(uri)
                        val duration = fileDuration ?: 3000L
                        val end = minOf(start + duration, getTotalSequenceDuration())
                        val chromaColor = draggableImageOverlay?.currentChromaColor
                        val chromaSim = draggableImageOverlay?.currentChromaSimilarity ?: 0.1f
                        viewModel.addImageOverlayOperation(
                            imageUri = uri,
                            relativeX = relX,
                            relativeY = relY,
                            relativeWidth = relW,
                            relativeHeight = relH,
                            rotationAngle = rotationAngle,
                            startTimeMs = start,
                            endTimeMs = end,
                            fileDurationMs = fileDuration,
                            chromaKeyColor = chromaColor,
                            chromaKeySimilarity = chromaSim,
                            opacity = opacity,
                            isMirrored = isMirrored
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

        cropOverlayView = try {
            findViewById<com.tharunbirla.librecuts.customviews.CropOverlayView>(R.id.cropOverlayView)?.also { overlay ->
                overlay.onCropBoundsChanged = { x, y, w, h ->
                    val existing = viewModel.project.value?.operations
                        ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    if (existing != null && existing.aspectRatio == "Custom") {
                        viewModel.updateOperation(existing.copy(
                            xFraction = x,
                            yFraction = y,
                            wFraction = w,
                            hFraction = h
                        ))
                    } else {
                        viewModel.addCropOperation("Custom", x, y, w, h)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "CropOverlayView not found: ${e.message}")
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
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Image")
                        .setMessage("Are you sure you want to delete this image overlay?")
                        .setPositiveButton("Delete") { _, _ ->
                            draggableImageOverlay?.deactivate()
                            viewModel.selectedOperationId.value?.let { id ->
                                viewModel.removeOperation(id)
                            }
                            exitImageEditingMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<View>(R.id.btnImageDuplicate)?.setBounceClickListener {
                    duplicateSelectedOverlay()
                }
                toolbar.findViewById<View>(R.id.btnImageMirror)?.setBounceClickListener {
                    draggableImageOverlay?.toggleMirror()
                }
                toolbar.findViewById<View>(R.id.btnImageChromaKey)?.setBounceClickListener {
                    showChromaKeyDialog()
                }
                toolbar.findViewById<View>(R.id.btnImageLoop)?.setBounceClickListener {
                    val selectedId = viewModel.selectedOperationId.value
                    val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
                    if (op != null) {
                        val newIsLooping = !op.isLooping
                        val actualDuration = op.fileDurationMs ?: 3000L
                        val newEndTimeMs = if (!newIsLooping) {
                            Math.min(op.endTimeMs ?: Long.MAX_VALUE, (op.startTimeMs ?: 0L) + actualDuration)
                        } else {
                            op.endTimeMs
                        }
                        viewModel.updateOperation(op.copy(isLooping = newIsLooping, endTimeMs = newEndTimeMs))
                        
                        val color = if (newIsLooping) androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary) else androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive)
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageLoop)?.setColorFilter(color)
                        toolbar.findViewById<android.widget.TextView>(R.id.tvImageLoop)?.setTextColor(color)
                    }
                }

                val slider = toolbar.findViewById<Slider>(R.id.imageRotationSlider)
                val tvValue = toolbar.findViewById<TextView>(R.id.tvImageRotationValue)
                slider?.addOnChangeListener { _, value, _ ->
                    tvValue?.text = "${value.toInt()}°"
                    draggableImageOverlay?.setRotationAngle(value)
                }

                toolbar.findViewById<View>(R.id.btnImageOpacity)?.setBounceClickListener {
                    val opacityRow = toolbar.findViewById<View>(R.id.imageOpacitySliderRow)
                    val rotationRow = toolbar.findViewById<View>(R.id.imageRotationSliderRow)
                    if (opacityRow?.visibility == View.VISIBLE) {
                        opacityRow.visibility = View.GONE
                        rotationRow?.visibility = View.VISIBLE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.toolTextInactive))
                    } else {
                        opacityRow?.visibility = View.VISIBLE
                        rotationRow?.visibility = View.GONE
                        toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this@VideoEditingActivity, R.color.colorPrimary))
                    }
                }

                val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
                val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
                opacitySlider?.addOnChangeListener { _, value, _ ->
                    tvOpacityValue?.text = "${value.toInt()}%"
                    draggableImageOverlay?.setOpacity(value / 100f)
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
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Clip")
                        .setMessage("Are you sure you want to delete this clip from the project?")
                        .setPositiveButton("Delete") { _, _ ->
                            deleteSelectedVideo()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoExtractAudio)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            extractAudioFromSegment(index, items[index])
                        }
                    }
                }

                toolbar.findViewById<ImageButton>(R.id.btnVideoFreezeFrame)?.setBounceClickListener {
                    freezeFrameAtCurrentPosition()
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoSpeed)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            showSpeedEditingToolbar(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoReverse)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        val items = getSequenceItems()
                        if (index >= 0 && index < items.size) {
                            reverseVideoSegment(index, items[index])
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoMirror)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        if (index == 0 && viewModel.project.value?.operations?.any { it is EditOperation.Merge } != true) {
                            val isCurrentlyMirrored = viewModel.project.value?.operations?.any { it is EditOperation.MirrorMain && it.isMirrored } ?: false
                            viewModel.updateMainVideoMirror(!isCurrentlyMirrored)
                        } else {
                            viewModel.toggleMergeItemMirror(index)
                        }
                        viewModel.project.value?.let { renderTracks(it) }
                        syncUiWithPlayer()
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoMute)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        viewModel.toggleMuteClip(index)
                        viewModel.project.value?.let { renderTracks(it) }
                        updateVideoMuteButtonState(toolbar, index)
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoFilter)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        showColorFilterSelectionDialog(index)
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnVideoAdjust)?.setBounceClickListener {
                    selectedVideoIndex?.let { index ->
                        showAdjustSelectionDialog(index)
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

                val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCustomSpeed)
                val tvCustomSpeed = toolbar.findViewById<TextView>(R.id.tvCustomSpeedValue)
                
                slider?.addOnChangeListener { _, value, _ ->
                    val roundedValue = String.format(java.util.Locale.US, "%.1f", value).toFloat()
                    tvCustomSpeed?.text = "${roundedValue}x"
                }

                slider?.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                    override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
                    override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                        val roundedValue = String.format(java.util.Locale.US, "%.1f", slider.value).toFloat()
                        applySpeedToSegment(roundedValue)
                    }
                })
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
                toolbar.findViewById<ImageButton>(R.id.btnAudioCancel)?.setBounceClickListener {
                    exitAudioEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioDelete)?.setBounceClickListener {
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Audio")
                        .setMessage("Are you sure you want to delete this audio track?")
                        .setPositiveButton("Delete") { _, _ ->
                            viewModel.selectedOperationId.value?.let { id ->
                                viewModel.deleteOperation(id)
                            }
                            exitAudioEditingMode()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener
                    
                    if (op.beats.isNotEmpty()) {
                        // Toggle off if already detected
                        viewModel.updateOperation(op.copy(beats = emptyList()))
                        android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_beats_cleared, android.widget.Toast.LENGTH_SHORT).show()
                        toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setColorFilter(getColor(R.color.toolTextInactive))
                        return@setBounceClickListener
                    }
                    
                    android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_analyzing_beats, android.widget.Toast.LENGTH_SHORT).show()
                    com.tharunbirla.librecuts.utils.AudioAnalyzer.detectBeats(this@VideoEditingActivity, op.audioUri) { beats ->
                        runOnUiThread {
                            if (beats.isNotEmpty()) {
                                viewModel.updateOperation(op.copy(beats = beats))
                                android.widget.Toast.makeText(this@VideoEditingActivity, "Found ${beats.size} beats!", android.widget.Toast.LENGTH_SHORT).show()
                                toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)?.setColorFilter(getColor(R.color.colorPrimary))
                            } else {
                                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_no_clear_beats_found, android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener

                    val newDucking = !op.ducking
                    viewModel.updateOperation(op.copy(ducking = newDucking))
                    
                    val color = if (newDucking) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive)
                    toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)?.setColorFilter(color)
                    val msg = if (newDucking) "Audio ducking enabled" else "Audio ducking disabled"
                    android.widget.Toast.makeText(this@VideoEditingActivity, msg, android.widget.Toast.LENGTH_SHORT).show()
                }
                toolbar.findViewById<ImageButton>(R.id.btnAudioFade)?.setBounceClickListener {
                    val id = viewModel.selectedOperationId.value ?: return@setBounceClickListener
                    val project = viewModel.project.value ?: return@setBounceClickListener
                    val op = project.operations.find { it is com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio && it.id == id } as? com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio ?: return@setBounceClickListener
                    showFadeDialog(op)
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
                    val currentCrop = viewModel.project.value?.operations
                        ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    if (currentCrop != null) {
                        val init = initialCropOperation
                        if (init == null) {
                            viewModel.removeOperation(currentCrop.id)
                        } else {
                            viewModel.addCropOperation(
                                aspectRatio = init.aspectRatio,
                                xFraction = init.xFraction,
                                yFraction = init.yFraction,
                                wFraction = init.wFraction,
                                hFraction = init.hFraction
                            )
                        }
                    }
                    exitCropEditingMode()
                }
                toolbar.findViewById<ImageButton>(R.id.btnCropDone)?.setBounceClickListener {
                    exitCropEditingMode()
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio1)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    viewModel.addCropOperation("16:9")
                    updateCropUi("16:9")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio2)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    viewModel.addCropOperation("9:16")
                    updateCropUi("9:16")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatio3)?.setBounceClickListener {
                    cropOverlayView?.visibility = View.GONE
                    viewModel.addCropOperation("1:1")
                    updateCropUi("1:1")
                }
                toolbar.findViewById<LinearLayout>(R.id.frameAspectRatioCustom)?.setBounceClickListener {
                    val existing = viewModel.project.value?.operations
                        ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
                        ?.lastOrNull()
                    
                    val x: Float
                    val y: Float
                    val w: Float
                    val h: Float
                    if (existing != null && existing.aspectRatio == "Custom") {
                        x = existing.xFraction
                        y = existing.yFraction
                        w = existing.wFraction
                        h = existing.hFraction
                    } else if (existing != null) {
                        val bounds = getPresetCropBounds(existing.aspectRatio)
                        x = bounds.left
                        y = bounds.top
                        w = bounds.width()
                        h = bounds.height()
                    } else {
                        x = 0.1f
                        y = 0.1f
                        w = 0.8f
                        h = 0.8f
                    }
                    
                    cropOverlayView?.setCropBounds(x, y, w, h)
                    cropOverlayView?.visibility = View.VISIBLE
                    viewModel.addCropOperation("Custom", x, y, w, h)
                    updateCropUi("Custom")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Crop editing toolbar not found: ${e.message}")
            null
        }

        subtitlesEditingToolbar = try {
            findViewById<View>(R.id.subtitlesEditingToolbar)?.also { toolbar ->
                toolbar.findViewById<ImageButton>(R.id.btnCloseSubtitlesSheet)?.setBounceClickListener {
                    exitSubtitlesEditingMode()
                }
                toolbar.findViewById<Button>(R.id.btnUploadSrt)?.setBounceClickListener {
                    pickSrtFile()
                }
                toolbar.findViewById<View>(R.id.btnChangeSrt)?.setBounceClickListener {
                    pickSrtFile()
                }
                toolbar.findViewById<Button>(R.id.btnDeleteSrt)?.setBounceClickListener {
                    MaterialAlertDialogBuilder(this@VideoEditingActivity)
                        .setTitle("Delete Subtitles")
                        .setMessage("Are you sure you want to remove the subtitles track?")
                        .setPositiveButton("Delete") { _, _ ->
                            removeSubtitles()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                toolbar.findViewById<Button>(R.id.btnSaveSubtitles)?.setBounceClickListener {
                    exitSubtitlesEditingMode()
                }
                toolbar.findViewById<Slider>(R.id.subtitleFontSizeSlider)?.addOnChangeListener { _, value, fromUser ->
                    if (fromUser) {
                        val project = viewModel.project.value
                        val subOp = project?.operations?.find { it is EditOperation.AddSubtitles } as? EditOperation.AddSubtitles
                        if (subOp != null) {
                            val newSize = value.toInt()
                            updateSubtitleOp(subOp.copy(fontSize = newSize))
                            toolbar.findViewById<TextView>(R.id.tvSubtitleFontSizeValue)?.text = "$newSize"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Subtitles editing toolbar not found: ${e.message}")
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
        findViewById<ImageButton>(R.id.btnMediaOverlay)?.setBounceClickListener {
            setActiveToolButton(R.id.btnMediaOverlay)
            mediaOverlayAction()
        }

        findViewById<ImageButton>(R.id.btnAudio).setBounceClickListener {
            setActiveToolButton(R.id.btnAudio)
            audioAction()
        }
        findViewById<ImageButton>(R.id.btnCrop).setBounceClickListener {
            setActiveToolButton(R.id.btnCrop)
            cropAction()
        }

        findViewById<ImageButton>(R.id.btnSubtitles).setBounceClickListener {
            setActiveToolButton(R.id.btnSubtitles)
            subtitlesAction()
        }

        findViewById<ImageButton>(R.id.btnVoiceOver)?.setBounceClickListener {
            setActiveToolButton(R.id.btnVoiceOver)
            voiceOverAction()
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
        val toolIds = listOf(R.id.btnText, R.id.btnMediaOverlay, R.id.btnAudio, R.id.btnCrop, R.id.btnSubtitles, R.id.btnVoiceOver)
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
                    val emptyStateVisible = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
                    if (isImportLoading || (!isVideoLoaded && !emptyStateVisible)) {
                        showLoading(getString(R.string.loading), getString(R.string.loading_tag))
                    } else {
                        hideLoading()
                    }
                }

                exportScreen.visibility = if (uiState.isExporting) View.VISIBLE else View.GONE

                if (::btnUndo.isInitialized && ::btnRedo.isInitialized) {
                    val isProjectEmpty = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
                    btnUndo.isEnabled = uiState.canUndo && !isProjectEmpty
                    btnUndo.alpha = if (uiState.canUndo && !isProjectEmpty) 1.0f else 0.5f
                    btnRedo.isEnabled = uiState.canRedo && !isProjectEmpty
                    btnRedo.alpha = if (uiState.canRedo && !isProjectEmpty) 1.0f else 0.5f
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
                updateLayerReorderButtons()
            }
        }

        lifecycleScope.launch {
            viewModel.project.collect { project ->
                if (project != null) {
                    Log.d(TAG, "Project updated with ${project.getOperationCount()} operations")

                    if (project.sourceUri.scheme == "file") {
                        val projectSourcePath = project.sourceUri.path
                        if (projectSourcePath != null && (!::tempInputFile.isInitialized || tempInputFile.absolutePath != projectSourcePath)) {
                            tempInputFile = File(projectSourcePath)
                            videoFileName = tempInputFile.name
                            try {
                                val r = android.media.MediaMetadataRetriever()
                                r.setDataSource(tempInputFile.absolutePath)
                                originalMainVideoDurationMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                                r.release()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error getting original duration: ${e.message}")
                            }
                        }
                    }

                    updateUIInteractionState()

                    textOverlayView?.let { overlay ->
                        val overlayOps = project.operations.filter { it is EditOperation.AddText }
                        overlay.setOverlayOperations(overlayOps)
                    }

                    imageOverlayView?.let { overlay ->
                        val overlayOps = project.operations.filterIsInstance<EditOperation.AddImageOverlay>()
                        overlay.setImageOperations(overlayOps)
                    }

                    textOverlayView?.let { overlay ->
                        val subOp = project.operations.filterIsInstance<EditOperation.AddSubtitles>().firstOrNull()
                        overlay.subtitleOperation = subOp
                        overlay.setSubtitleCues(subOp?.cues ?: emptyList())
                    }

                    if (isSubtitlesEditingActive) {
                        updateSubtitlesUi()
                    }

                    val cropOps = project.operations.filterIsInstance<EditOperation.Crop>()
                    if (cropOps.isNotEmpty()) {
                        applyCropPreview(cropOps.last().aspectRatio)
                    } else {
                        resetCropPreview()
                    }
                    
                    renderTracks(project)
                    updateLayerReorderButtons()
                }
            }
        }
    }

    private fun updateLayerReorderButtons() {
        if (!::btnMoveLayerUp.isInitialized || !::btnMoveLayerDown.isInitialized) return
        
        val selectedId = viewModel.selectedOperationId.value
        val project = viewModel.project.value
        
        if (selectedId == null || project == null) {
            btnMoveLayerUp.visibility = View.GONE
            btnMoveLayerDown.visibility = View.GONE
            return
        }
        
        val overlayOps = project.operations.filter {
            it is EditOperation.AddText || it is EditOperation.AddImageOverlay
        }
        
        val selectedIndex = overlayOps.indexOfFirst { op ->
            when (op) {
                is EditOperation.AddText -> op.id == selectedId
                is EditOperation.AddImageOverlay -> op.id == selectedId
                else -> false
            }
        }
        
        if (selectedIndex == -1) {
            btnMoveLayerUp.visibility = View.GONE
            btnMoveLayerDown.visibility = View.GONE
            return
        }
        
        val canMoveUp = selectedIndex < overlayOps.size - 1
        val canMoveDown = selectedIndex > 0
        
        btnMoveLayerUp.visibility = if (canMoveUp) View.VISIBLE else View.GONE
        btnMoveLayerDown.visibility = if (canMoveDown) View.VISIBLE else View.GONE
    }

    private fun applyCropPreview(aspectRatio: String) {
        if (aspectRatio == "Custom" && cropEditingToolbar?.visibility == View.VISIBLE) {
            resetCropPreview()
            return
        }

        val format = if (::player.isInitialized) player.videoFormat else null
        val rotation = format?.rotationDegrees ?: 0
        val videoWidth = if (rotation == 90 || rotation == 270) format?.height ?: 1 else format?.width ?: 1
        val videoHeight = if (rotation == 90 || rotation == 270) format?.width ?: 1 else format?.height ?: 1

        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
            ?.lastOrNull()

        // Get coordinates depending on aspect ratio
        val xFrac: Float
        val yFrac: Float
        val wFrac: Float
        val hFrac: Float
        
        when (aspectRatio) {
            "16:9" -> {
                val bounds = getPresetCropBounds("16:9")
                xFrac = bounds.left
                yFrac = bounds.top
                wFrac = bounds.width()
                hFrac = bounds.height()
            }
            "9:16" -> {
                val bounds = getPresetCropBounds("9:16")
                xFrac = bounds.left
                yFrac = bounds.top
                wFrac = bounds.width()
                hFrac = bounds.height()
            }
            "1:1" -> {
                val bounds = getPresetCropBounds("1:1")
                xFrac = bounds.left
                yFrac = bounds.top
                wFrac = bounds.width()
                hFrac = bounds.height()
            }
            "Custom" -> {
                if (cropOp != null) {
                    xFrac = cropOp.xFraction
                    yFrac = cropOp.yFraction
                    wFrac = cropOp.wFraction
                    hFrac = cropOp.hFraction
                } else {
                    xFrac = 0f
                    yFrac = 0f
                    wFrac = 1f
                    hFrac = 1f
                }
            }
            else -> {
                resetCropPreview()
                return
            }
        }

        playerView.post {
            val containerWidth = playerContainer.width.toFloat()
            val containerHeight = playerContainer.height.toFloat()
            if (containerWidth <= 0 || containerHeight <= 0) return@post

            val containerRatio = containerWidth / containerHeight
            val targetRatio = (videoWidth.toFloat() * wFrac) / (videoHeight.toFloat() * hFrac)

            // 1. Calculate fitted crop window dimensions inside container
            val fitWidth: Float
            val fitHeight: Float
            if (targetRatio > containerRatio) {
                fitWidth = containerWidth
                fitHeight = containerWidth / targetRatio
            } else {
                fitWidth = containerHeight * targetRatio
                fitHeight = containerHeight
            }

            // 2. Scale up full playerView size so that cropped fraction matches fitted dimensions
            val fullWidth = if (wFrac > 0f) fitWidth / wFrac else fitWidth
            val fullHeight = if (hFrac > 0f) fitHeight / hFrac else fitHeight

            val params = playerView.layoutParams as FrameLayout.LayoutParams
            params.width = fullWidth.toInt()
            params.height = fullHeight.toInt()
            params.gravity = Gravity.CENTER
            playerView.layoutParams = params

            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            // 3. Translate playerView so the crop window is centered inside the container
            val transX = -fullWidth * (xFrac + wFrac / 2f - 0.5f)
            val transY = -fullHeight * (yFrac + hFrac / 2f - 0.5f)
            playerView.translationX = transX
            playerView.translationY = transY

            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay, cropOverlayView)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = fullWidth.toInt()
                    overlayParams.height = fullHeight.toInt()
                    overlayParams.gravity = Gravity.CENTER
                    it.layoutParams = overlayParams
                    it.translationX = transX
                    it.translationY = transY
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

            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView.translationX = 0f
            playerView.translationY = 0f

            val overlays = listOf(textOverlayView, draggableTextOverlay, imageOverlayView, draggableImageOverlay, cropOverlayView)
            for (overlay in overlays) {
                overlay?.let {
                    val overlayParams = it.layoutParams as FrameLayout.LayoutParams
                    overlayParams.width = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.height = FrameLayout.LayoutParams.MATCH_PARENT
                    overlayParams.gravity = Gravity.NO_GRAVITY
                    it.layoutParams = overlayParams
                    it.translationX = 0f
                    it.translationY = 0f
                }
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun trimAction() {
        Toast.makeText(this, R.string.toast_drag_the_handles_on_the_timeli, Toast.LENGTH_LONG).show()
    }

    private fun captureCurrentFrame() {
        closeActiveEditingModes()
        val container = findViewById<FrameLayout>(R.id.playerContainer)
        val tvDuration = findViewById<TextView>(R.id.tvDuration)
        val tvPreviewBadge = findViewById<TextView>(R.id.tvPreviewBadge)
        
        val durationVis = tvDuration?.visibility ?: View.GONE
        val badgeVis = tvPreviewBadge?.visibility ?: View.GONE
        
        tvDuration?.visibility = View.INVISIBLE
        tvPreviewBadge?.visibility = View.INVISIBLE
        
        container.post {
            if (container.width <= 0 || container.height <= 0) {
                tvDuration?.visibility = durationVis
                tvPreviewBadge?.visibility = badgeVis
                return@post
            }
            val bitmap = android.graphics.Bitmap.createBitmap(container.width, container.height, android.graphics.Bitmap.Config.ARGB_8888)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val location = IntArray(2)
                container.getLocationInWindow(location)
                val rect = android.graphics.Rect(location[0], location[1], location[0] + container.width, location[1] + container.height)
                
                try {
                    android.view.PixelCopy.request(window, rect, bitmap, { copyResult ->
                        tvDuration?.visibility = durationVis
                        tvPreviewBadge?.visibility = badgeVis
                        if (copyResult == android.view.PixelCopy.SUCCESS) {
                            saveBitmapToGallery(bitmap)
                        } else {
                            Toast.makeText(this, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                        }
                    }, android.os.Handler(android.os.Looper.getMainLooper()))
                } catch (e: Exception) {
                    e.printStackTrace()
                    tvDuration?.visibility = durationVis
                    tvPreviewBadge?.visibility = badgeVis
                    Toast.makeText(this, "Failed to capture frame", Toast.LENGTH_SHORT).show()
                }
            } else {
                val canvas = android.graphics.Canvas(bitmap)
                val bg = container.background
                if (bg != null) bg.draw(canvas) else canvas.drawColor(android.graphics.Color.BLACK)
                
                val textureView = findTextureView(playerView)
                if (textureView != null) {
                    val videoBitmap = textureView.bitmap
                    if (videoBitmap != null) {
                        canvas.save()
                        canvas.translate(playerView.x + textureView.x, playerView.y + textureView.y)
                        canvas.scale(playerView.scaleX, playerView.scaleY)
                        canvas.drawBitmap(videoBitmap, 0f, 0f, null)
                        canvas.restore()
                    }
                }
                
                val overlays = listOf(R.id.textOverlayView, R.id.imageOverlayView, R.id.cropOverlayView)
                for (id in overlays) {
                    val view = container.findViewById<View>(id)
                    if (view != null && view.visibility == View.VISIBLE) {
                        canvas.save()
                        canvas.translate(view.x, view.y)
                        view.draw(canvas)
                        canvas.restore()
                    }
                }
                
                tvDuration?.visibility = durationVis
                tvPreviewBadge?.visibility = badgeVis
                saveBitmapToGallery(bitmap)
            }
        }
    }

    private fun findTextureView(viewGroup: android.view.ViewGroup): android.view.TextureView? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is android.view.TextureView) return child
            if (child is android.view.ViewGroup) {
                val tv = findTextureView(child)
                if (tv != null) return tv
            }
        }
        return null
    }

    private fun saveBitmapToGallery(bitmap: android.graphics.Bitmap) {
        val filename = "LibreCuts_Frame_${System.currentTimeMillis()}.png"
        var fos: java.io.OutputStream? = null
        var imageUri: Uri? = null
        try {
            val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
            val customUriString = sharedPreferences.getString("export_snapshot_directory_uri", null)
            
            if (customUriString != null) {
                try {
                    val treeUri = Uri.parse(customUriString)
                    val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    imageUri = android.provider.DocumentsContract.createDocument(
                        contentResolver,
                        parentUri,
                        "image/png",
                        filename
                    )
                    fos = imageUri?.let { contentResolver.openOutputStream(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving snapshot to custom directory: ${e.message}, falling back to default", e)
                }
            }
            
            if (fos == null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = contentResolver
                    val contentValues = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/LibreCuts")
                    }
                    imageUri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { resolver.openOutputStream(it) }
                } else {
                    val imagesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES)
                    val directory = java.io.File(imagesDir, "LibreCuts")
                    if (!directory.exists()) directory.mkdirs()
                    val image = java.io.File(directory, filename)
                    fos = java.io.FileOutputStream(image)
                    imageUri = Uri.fromFile(image)
                }
            }
            
            fos?.use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                Toast.makeText(this, "Frame saved to gallery", Toast.LENGTH_SHORT).show()
            }
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && imageUri != null) {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, imageUri))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to save frame", Toast.LENGTH_SHORT).show()
        }
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
        if (isSubtitlesEditingActive) {
            isSubtitlesEditingActive = false
        }
        textEditingToolbar?.visibility = View.GONE
        imageEditingToolbar?.visibility = View.GONE
        videoEditingToolbar?.visibility = View.GONE
        audioEditingToolbar?.visibility = View.GONE
        cropEditingToolbar?.visibility = View.GONE
        subtitlesEditingToolbar?.visibility = View.GONE
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
        setActiveToolButton(0)
    }

    private fun duplicateSelectedOverlay() {
        val selectedId = viewModel.selectedOperationId.value ?: return
        val currentProject = viewModel.project.value ?: return
        val op = currentProject.operations.find {
            when (it) {
                is EditOperation.AddText -> it.id == selectedId
                is EditOperation.AddImageOverlay -> it.id == selectedId
                else -> false
            }
        } ?: return

        val newOp = when (op) {
            is EditOperation.AddText -> {
                val newX = ((op.relativeX ?: 0.5f) + 0.05f).coerceIn(0f, 1f)
                val newY = ((op.relativeY ?: 0.5f) + 0.05f).coerceIn(0f, 1f)
                op.copy(
                    id = System.nanoTime().toString(),
                    relativeX = newX,
                    relativeY = newY
                )
            }
            is EditOperation.AddImageOverlay -> {
                val newX = (op.relativeX + 0.05f).coerceIn(0f, 1f)
                val newY = (op.relativeY + 0.05f).coerceIn(0f, 1f)
                op.copy(
                    id = System.nanoTime().toString(),
                    relativeX = newX,
                    relativeY = newY
                )
            }
            else -> null
        }

        val newOpId = when (newOp) {
            is EditOperation.AddText -> newOp.id
            is EditOperation.AddImageOverlay -> newOp.id
            else -> null
        }

        if (newOp != null && newOpId != null) {
            viewModel.addOperation(newOp)
            viewModel.selectOperation(newOpId)
        }
    }

    private fun voiceOverAction() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            showVoiceOverDialog()
        } else {
            requestRecordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showVoiceOverDialog() {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_voice_over, null)
        bottomSheet.setContentView(view)

        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseVoiceOver)
        val btnToggle = view.findViewById<ImageButton>(R.id.btnToggleRecording)
        val tvStatus = view.findViewById<TextView>(R.id.tvRecordingStatus)
        val ripple1 = view.findViewById<View>(R.id.ripple1)
        val ripple2 = view.findViewById<View>(R.id.ripple2)
        
        isRecordingVoiceOver = false

        val scaleX1 = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleX", 1f, 1.8f)
        val scaleY1 = android.animation.ObjectAnimator.ofFloat(ripple1, "scaleY", 1f, 1.8f)
        val alpha1 = android.animation.ObjectAnimator.ofFloat(ripple1, "alpha", 0.6f, 0f)
        
        val scaleX2 = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleX", 1f, 1.8f)
        val scaleY2 = android.animation.ObjectAnimator.ofFloat(ripple2, "scaleY", 1f, 1.8f)
        val alpha2 = android.animation.ObjectAnimator.ofFloat(ripple2, "alpha", 0.6f, 0f)

        scaleX1.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY1.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha1.repeatCount = android.animation.ValueAnimator.INFINITE
        
        scaleX2.repeatCount = android.animation.ValueAnimator.INFINITE
        scaleY2.repeatCount = android.animation.ValueAnimator.INFINITE
        alpha2.repeatCount = android.animation.ValueAnimator.INFINITE
        
        val rippleAnimator = android.animation.AnimatorSet().apply {
            playTogether(scaleX1, scaleY1, alpha1)
            duration = 1500
        }
        val rippleAnimator2 = android.animation.AnimatorSet().apply {
            playTogether(scaleX2, scaleY2, alpha2)
            duration = 1500
            startDelay = 750
        }

        btnToggle.setBounceClickListener {
            if (!isRecordingVoiceOver) {
                // Start Recording
                voiceOverFile = File(cacheDir, "voice_over_${System.currentTimeMillis()}.m4a")
                try {
                    mediaRecorder = android.media.MediaRecorder().apply {
                        setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                        setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                        setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                        setOutputFile(voiceOverFile?.absolutePath)
                        prepare()
                        start()
                    }
                    
                    isRecordingVoiceOver = true
                    tvStatus.text = "Recording... Tap to stop"
                    btnToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FF4081"))
                    
                    rippleAnimator.start()
                    rippleAnimator2.start()
                    
                    // Play video while recording
                    voiceOverStartTimeMs = getGlobalPosition()
                    if (::player.isInitialized) {
                        player.play()
                        btnPlayPause.setImageResource(R.drawable.ic_pause_24)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_start_recording, Toast.LENGTH_SHORT).show()
                }
            } else {
                // Stop Recording
                try {
                    mediaRecorder?.stop()
                    mediaRecorder?.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                mediaRecorder = null
                isRecordingVoiceOver = false
                
                rippleAnimator.cancel()
                rippleAnimator2.cancel()
                ripple1.alpha = 0f
                ripple2.alpha = 0f
                ripple1.scaleX = 1f
                ripple1.scaleY = 1f
                ripple2.scaleX = 1f
                ripple2.scaleY = 1f
                
                // Pause video
                if (::player.isInitialized) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                }
                
                tvStatus.text = "Tap to start recording"
                btnToggle.backgroundTintList = null // Reset tint
                
                // Add to audio layer
                voiceOverFile?.let { file ->
                    if (file.exists()) {
                        var durationMs = 3000L
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(file.absolutePath)
                            val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            if (timeStr != null) {
                                durationMs = timeStr.toLong()
                            }
                            retriever.release()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        
                        viewModel.addBackgroundAudioOperation(
                            audioUri = Uri.fromFile(file),
                            startTimeMs = voiceOverStartTimeMs,
                            endTimeMs = voiceOverStartTimeMs + durationMs,
                            internalStartMs = 0L,
                            internalEndMs = durationMs
                        )
                    }
                }
                bottomSheet.dismiss()
            }
        }

        btnClose.setBounceClickListener {
            bottomSheet.dismiss()
        }
        
        bottomSheet.setOnDismissListener {
            rippleAnimator.cancel()
            rippleAnimator2.cancel()
            if (isRecordingVoiceOver) {
                try { mediaRecorder?.stop(); mediaRecorder?.release() } catch(e: Exception) {}
                mediaRecorder = null
                isRecordingVoiceOver = false
                if (::player.isInitialized) {
                    player.pause()
                    btnPlayPause.setImageResource(R.drawable.ic_play_24)
                }
            }
            setActiveToolButton(-1)
        }

        bottomSheet.show()
    }

    private fun subtitlesAction() {
        enterSubtitlesEditingMode()
    }

    private fun enterSubtitlesEditingMode() {
        closeActiveEditingModes()
        selectedVideoIndex = null
        isSubtitlesEditingActive = true
        textOverlayView?.isSubtitlesEditingActive = true
        val project = viewModel.project.value
        val subOp = project?.operations?.find { it is EditOperation.AddSubtitles } as? EditOperation.AddSubtitles
        textOverlayView?.subtitleOperation = subOp
        subtitlesEditingToolbar?.visibility = View.VISIBLE
        updateSubtitlesUi()
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.GONE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.GONE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.GONE
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitSubtitlesEditingMode() {
        isSubtitlesEditingActive = false
        textOverlayView?.isSubtitlesEditingActive = false
        textOverlayView?.subtitleOperation = null
        subtitlesEditingToolbar?.visibility = View.GONE
        findViewById<LinearLayout>(R.id.editingControlsWrapper)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineContainer)?.visibility = View.VISIBLE
        findViewById<View>(R.id.timelineDivider)?.visibility = View.VISIBLE
        setActiveToolButton(0)
    }

    private fun updateSubtitlesUi() {
        val toolbar = subtitlesEditingToolbar ?: return
        val project = viewModel.project.value
        val subOp = project?.operations?.find { it is EditOperation.AddSubtitles } as? EditOperation.AddSubtitles
        val layoutNo = toolbar.findViewById<View>(R.id.layoutNoSubtitles)
        val layoutHas = toolbar.findViewById<View>(R.id.layoutHasSubtitles)
        val tvFileName = toolbar.findViewById<TextView>(R.id.tvSubtitleFileName)

        if (subOp != null) {
            layoutNo?.visibility = View.GONE
            layoutHas?.visibility = View.VISIBLE
            tvFileName?.text = subOp.fileName

            val slider = toolbar.findViewById<Slider>(R.id.subtitleFontSizeSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvSubtitleFontSizeValue)
            slider?.value = subOp.fontSize.toFloat().coerceIn(10f, 80f)
            tvValue?.text = "${subOp.fontSize}"
        } else {
            layoutNo?.visibility = View.VISIBLE
            layoutHas?.visibility = View.GONE
        }
    }

    private fun updateSubtitleOp(newOp: EditOperation.AddSubtitles) {
        viewModel.updateOperation(newOp)
        textOverlayView?.subtitleOperation = newOp
    }

    private fun pickSrtFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select SRT Subtitle File"), PICK_SRT_REQUEST)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(intent, PICK_SRT_REQUEST)
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle this action", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun removeSubtitles() {
        val project = viewModel.project.value ?: return
        val subOp = project.operations.filterIsInstance<EditOperation.AddSubtitles>().firstOrNull() ?: return
        viewModel.deleteOperation(subOp.id)
        textOverlayView?.subtitleOperation = null
        textOverlayView?.setSubtitleCues(emptyList())
        textOverlayView?.isSubtitlesEditingActive = false
        
        // Immediately update toolbar UI to reflect empty state
        val toolbar = subtitlesEditingToolbar
        if (toolbar != null) {
            val layoutNo = toolbar.findViewById<View>(R.id.layoutNoSubtitles)
            val layoutHas = toolbar.findViewById<View>(R.id.layoutHasSubtitles)
            layoutNo?.visibility = View.VISIBLE
            layoutHas?.visibility = View.GONE
        }
        
        Toast.makeText(this, R.string.toast_subtitles_removed, Toast.LENGTH_SHORT).show()
    }

    private fun mediaOverlayAction() {
        if (isImageEditingActive) {
            draggableImageOverlay?.commitImage()
            return
        }
        openImagePicker()
    }


    private fun openImagePicker() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Image, GIF, or Video Overlay"), PICK_IMAGE_REQUEST)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(intent, PICK_IMAGE_REQUEST)
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle this action", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showImageOverlayConfig(imageUri: Uri) {
        lifecycleScope.launch {
            val extension = getExtensionFromUri(imageUri) ?: ".png"
            val tempImageFile = withContext(Dispatchers.IO) {
                copyContentUriToTempFile(imageUri, "overlay_image", extension)
            }
            if (tempImageFile == null) {
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_load_overlay_file, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val localUri = Uri.fromFile(tempImageFile)
            
            val isVideo = extension.equals(".mp4", ignoreCase = true) ||
                          extension.equals(".mkv", ignoreCase = true) ||
                          extension.equals(".mov", ignoreCase = true) ||
                          extension.equals(".3gp", ignoreCase = true)
            
            val aspect = if (isVideo) {
                withContext(Dispatchers.IO) {
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(tempImageFile.absolutePath)
                        val wStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val hStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        val rStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                        val w = wStr?.toIntOrNull() ?: 1
                        val h = hStr?.toIntOrNull() ?: 1
                        val r = rStr?.toIntOrNull() ?: 0
                        retriever.release()
                        val isSwapped = r == 90 || r == 270
                        if (isSwapped) h.toFloat() / w else w.toFloat() / h
                    } catch (e: Exception) {
                        Log.e(TAG, "Error getting video aspect: ${e.message}", e)
                        1.0f
                    }
                }
            } else {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(tempImageFile.absolutePath, options)
                if (options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 1.0f
            }
            
            enterImageEditingMode(localUri, aspect)
        }
    }

    private fun getExtensionFromUri(uri: Uri): String? {
        val mimeType = contentResolver.getType(uri)
        if (mimeType != null) {
            val mime = android.webkit.MimeTypeMap.getSingleton()
            val ext = mime.getExtensionFromMimeType(mimeType)
            if (ext != null) {
                return ".$ext"
            }
        }
        val path = uri.path ?: return null
        val lastDot = path.lastIndexOf('.')
        if (lastDot != -1) {
            return path.substring(lastDot)
        }
        return null
    }

    private fun getOverlayFileDurationMs(uri: Uri): Long? {
        val path = uri.path ?: return null
        val isGif = path.endsWith(".gif", ignoreCase = true)
        val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                      path.endsWith(".mkv", ignoreCase = true) ||
                      path.endsWith(".mov", ignoreCase = true) ||
                      path.endsWith(".3gp", ignoreCase = true)
        if (isGif) {
            try {
                val movie = android.graphics.Movie.decodeFile(path)
                if (movie != null && movie.duration() > 0) {
                    return movie.duration().toLong()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading GIF duration: ${e.message}", e)
            }
        } else if (isVideo) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(path)
                val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durationMs = durationStr?.toLongOrNull()
                retriever.release()
                if (durationMs != null && durationMs > 0) {
                    return durationMs
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading video duration: ${e.message}", e)
            }
        }
        return null
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

            val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
            val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
            opacitySlider?.value = 100f
            tvOpacityValue?.text = "100%"

            toolbar.findViewById<View>(R.id.imageOpacitySliderRow)?.visibility = View.GONE
            toolbar.findViewById<View>(R.id.imageRotationSliderRow)?.visibility = View.VISIBLE
            toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
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
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.imageRotationSlider)
            val tvValue = toolbar.findViewById<android.widget.TextView>(R.id.tvImageRotationValue)
            slider?.value = rotation
            tvValue?.text = "${rotation.toInt()}°"
            
            val loopContainer = toolbar.findViewById<View>(R.id.btnImageLoopContainer)
            val btnLoop = toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageLoop)
            val tvLoop = toolbar.findViewById<android.widget.TextView>(R.id.tvImageLoop)
            
            val selectedId = viewModel.selectedOperationId.value
            val op = viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
            
            val isGif = op?.imageUri?.path?.endsWith(".gif", ignoreCase = true) == true
            val isVideo = op?.imageUri?.path?.endsWith(".mp4", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".mkv", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".mov", ignoreCase = true) == true ||
                          op?.imageUri?.path?.endsWith(".3gp", ignoreCase = true) == true
            
            if (op != null && (isGif || isVideo)) {
                loopContainer?.visibility = View.VISIBLE
                val isLooping = op.isLooping
                val color = if (isLooping) androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary) else androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive)
                btnLoop?.setColorFilter(color)
                tvLoop?.setTextColor(color)
            } else {
                loopContainer?.visibility = View.GONE
            }

            val opacitySlider = toolbar.findViewById<Slider>(R.id.imageOpacitySlider)
            val tvOpacityValue = toolbar.findViewById<TextView>(R.id.tvImageOpacityValue)
            val currentOpacity = ((op?.opacity ?: 1.0f) * 100f).coerceIn(0f, 100f)
            opacitySlider?.value = currentOpacity
            tvOpacityValue?.text = "${currentOpacity.toInt()}%"

            toolbar.findViewById<View>(R.id.imageOpacitySliderRow)?.visibility = View.GONE
            toolbar.findViewById<View>(R.id.imageRotationSliderRow)?.visibility = View.VISIBLE
            toolbar.findViewById<android.widget.ImageButton>(R.id.btnImageOpacity)?.setColorFilter(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
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
        setActiveToolButton(0)
    }

    private fun enterVideoEditingMode() {
        closeActiveEditingModes()
        viewModel.selectOperation(null)
        videoEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        videoEditingToolbar?.findViewById<View>(R.id.btnVideoDeleteContainer)?.visibility = View.VISIBLE
        selectedVideoIndex?.let { index ->
            videoEditingToolbar?.let { toolbar ->
                updateVideoMuteButtonState(toolbar, index)
            }
        }
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun freezeFrameAtCurrentPosition() {
        val index = selectedVideoIndex ?: return
        val sequenceItems = getSequenceItems()
        if (index < 0 || index >= sequenceItems.size) return
        val item = sequenceItems[index]

        showLoading("Creating freeze frame...")
        isImportLoading = true

        val globalPos = getGlobalPosition()

        lifecycleScope.launch {
            val resultUri = withContext(Dispatchers.IO) {
                var bitmap: android.graphics.Bitmap? = null
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this@VideoEditingActivity, item.uri)
                    
                    var globalStartMs = 0L
                    for (i in 0 until index) {
                        globalStartMs += sequenceItems[i].trimmedDurationMs
                    }
                    val relativePosMs = globalPos - globalStartMs
                    val sourceTimeMs = item.trimStartMs + (relativePosMs * item.speed).toLong()
                    val coercedSourceTimeMs = sourceTimeMs.coerceIn(item.trimStartMs, item.trimEndMs)

                    bitmap = retriever.getFrameAtTime(coercedSourceTimeMs * 1000L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to retrieve frame: ${e.message}", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (e: Exception) {}
                }

                if (bitmap == null) return@withContext null

                val pngFile = File(cacheDir, "freeze_${System.nanoTime()}.png")
                try {
                    java.io.FileOutputStream(pngFile).use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save temp PNG: ${e.message}", e)
                    return@withContext null
                }

                val mp4File = File(cacheDir, "freeze_vid_${System.nanoTime()}.mp4")
                val cmd = "-y -loop 1 -i \"${pngFile.absolutePath}\" -c:v h264_mediacodec -t 3 -pix_fmt yuv420p -vf \"scale=trunc(iw/2)*2:trunc(ih/2)*2\" \"${mp4File.absolutePath}\""
                val renderResult = ffmpegEngine.executeCommand(cmd)

                try {
                    pngFile.delete()
                } catch (e: Exception) {}

                if (renderResult is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                    Uri.fromFile(mp4File)
                } else {
                    Log.e(TAG, "FFmpeg failed to create freeze frame video")
                    null
                }
            }

            isImportLoading = false
            loadingScreen.visibility = View.GONE

            if (resultUri != null) {
                val freezeFrameItem = com.tharunbirla.librecuts.models.EditOperation.MergeItem(resultUri, 3000L)
                val newItems = mutableListOf<com.tharunbirla.librecuts.models.EditOperation.MergeItem>()
                
                var globalStartMs = 0L
                for (i in 0 until index) {
                    globalStartMs += sequenceItems[i].trimmedDurationMs
                }
                val relativePosMs = globalPos - globalStartMs
                
                for (i in 0 until sequenceItems.size) {
                    if (i == index) {
                        val splitSourceMs = item.trimStartMs + (relativePosMs * item.speed).toLong()
                        if (splitSourceMs > item.trimStartMs && splitSourceMs < item.trimEndMs) {
                            val itemA = item.copy(trimEndMs = splitSourceMs)
                            val itemB = item.copy(trimStartMs = splitSourceMs)
                            newItems.add(itemA)
                            newItems.add(freezeFrameItem)
                            newItems.add(itemB)
                        } else if (splitSourceMs <= item.trimStartMs) {
                            newItems.add(freezeFrameItem)
                            newItems.add(item)
                        } else {
                            newItems.add(item)
                            newItems.add(freezeFrameItem)
                        }
                    } else {
                        newItems.add(sequenceItems[i])
                    }
                }
                
                selectedVideoIndex = null
                exitVideoEditingMode()
                viewModel.updateSequenceOrder(newItems)
                Toast.makeText(this@VideoEditingActivity, R.string.toast_freeze_frame_added_to_sequence, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_create_freeze_frame, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exitVideoEditingMode() {
        videoEditingToolbar?.visibility = View.GONE
        editingControlsWrapper.visibility = View.VISIBLE
        setActiveToolButton(0)
    }


    private fun enterAudioEditingMode(op: com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio) {
        closeActiveEditingModes()
        selectedVideoIndex = null
        audioEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        audioEditingToolbar?.let { toolbar ->
            val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.audioVolumeSlider)
            val tvValue = toolbar.findViewById<TextView>(R.id.tvAudioVolumeValue)
            slider?.value = op.volume.coerceIn(0f, 2f)
            tvValue?.text = "${(op.volume * 100).toInt()}%"
            
            val btnBeats = toolbar.findViewById<ImageButton>(R.id.btnAudioBeats)
            btnBeats?.setColorFilter(if (op.beats.isNotEmpty()) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))

            val btnDucking = toolbar.findViewById<ImageButton>(R.id.btnAudioDucking)
            btnDucking?.setColorFilter(if (op.ducking) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))

            val btnFade = toolbar.findViewById<ImageButton>(R.id.btnAudioFade)
            val isFadeActive = op.fadeInDurationMs > 0 || op.fadeOutDurationMs > 0
            btnFade?.setColorFilter(if (isFadeActive) getColor(R.color.colorPrimary) else getColor(R.color.toolTextInactive))


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
                
                val trackWidth = resources.displayMetrics.widthPixels - 64.dpToPx()
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
        setActiveToolButton(0)
    }

    private fun showFadeDialog(op: com.tharunbirla.librecuts.models.EditOperation.AddBackgroundAudio) {
        val bottomSheet = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_audio_fade, null)
        bottomSheet.setContentView(view)

        val fadeInSlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.fadeInSlider)
        val fadeOutSlider = view.findViewById<com.google.android.material.slider.Slider>(R.id.fadeOutSlider)
        val tvFadeInValue = view.findViewById<TextView>(R.id.tvFadeInValue)
        val tvFadeOutValue = view.findViewById<TextView>(R.id.tvFadeOutValue)
        val btnFadeDone = view.findViewById<android.view.View>(R.id.btnFadeDone)

        val clipDurMs = if (op.endTimeMs != null && op.startTimeMs != null) {
            op.endTimeMs - op.startTimeMs
        } else if (op.internalEndMs > 0L) {
            op.internalEndMs - op.internalStartMs
        } else {
            op.originalDurationMs - op.internalStartMs
        }
        val maxFadeMs = if (clipDurMs > 0) clipDurMs.coerceAtMost(5000L).toFloat() / 1000f else 5.0f

        fadeInSlider.valueTo = if (maxFadeMs >= 0.1f) maxFadeMs else 0.1f
        fadeOutSlider.valueTo = if (maxFadeMs >= 0.1f) maxFadeMs else 0.1f

        val initFadeInSec = (op.fadeInDurationMs / 1000f).coerceIn(0f, fadeInSlider.valueTo)
        val initFadeOutSec = (op.fadeOutDurationMs / 1000f).coerceIn(0f, fadeOutSlider.valueTo)

        fadeInSlider.value = initFadeInSec
        fadeOutSlider.value = initFadeOutSec
        tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", initFadeInSec)
        tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", initFadeOutSec)

        fadeInSlider.addOnChangeListener { _, value, _ ->
            tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", value)
            if (clipDurMs > 0 && value + fadeOutSlider.value > clipDurMs / 1000f) {
                val newOut = (clipDurMs / 1000f - value).coerceAtLeast(0f).coerceAtMost(fadeOutSlider.valueTo)
                fadeOutSlider.value = newOut
                tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", newOut)
            }
        }

        fadeOutSlider.addOnChangeListener { _, value, _ ->
            tvFadeOutValue.text = String.format(java.util.Locale.US, "%.1fs", value)
            if (clipDurMs > 0 && value + fadeInSlider.value > clipDurMs / 1000f) {
                val newIn = (clipDurMs / 1000f - value).coerceAtLeast(0f).coerceAtMost(fadeInSlider.valueTo)
                fadeInSlider.value = newIn
                tvFadeInValue.text = String.format(java.util.Locale.US, "%.1fs", newIn)
            }
        }

        btnFadeDone.setBounceClickListener {
            val newFadeInMs = (fadeInSlider.value * 1000).toLong()
            val newFadeOutMs = (fadeOutSlider.value * 1000).toLong()
            viewModel.updateOperation(op.copy(fadeInDurationMs = newFadeInMs, fadeOutDurationMs = newFadeOutMs))
            bottomSheet.dismiss()
        }

        bottomSheet.show()
    }

    private fun getPresetCropBounds(aspectRatio: String): android.graphics.RectF {
        val rect = android.graphics.RectF(0f, 0f, 1f, 1f)
        val format = if (::player.isInitialized) player.videoFormat else null
        if (format == null || format.width <= 0 || format.height <= 0) return rect
        val rotation = format.rotationDegrees
        val videoWidth = if (rotation == 90 || rotation == 270) format.height else format.width
        val videoHeight = if (rotation == 90 || rotation == 270) format.width else format.height
        val videoRatio = videoWidth.toFloat() / videoHeight
        val targetRatio = when (aspectRatio) {
            "16:9" -> 16f / 9f
            "9:16" -> 9f / 16f
            "1:1" -> 1f
            else -> return rect
        }
        if (videoRatio > targetRatio) {
            val w = targetRatio / videoRatio
            val x = (1f - w) / 2f
            rect.set(x, 0f, x + w, 1f)
        } else {
            val h = videoRatio / targetRatio
            val y = (1f - h) / 2f
            rect.set(0f, y, 1f, y + h)
        }
        return rect
    }

    private fun enterCropEditingMode() {
        closeActiveEditingModes()
        cropEditingToolbar?.visibility = View.VISIBLE
        editingControlsWrapper.visibility = View.GONE
        
        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
            ?.lastOrNull()

        initialCropOperation = cropOp
        val currentRatio = cropOp?.aspectRatio ?: "Original"
            
        updateCropUi(currentRatio)
        resetCropPreview()

        if (currentRatio == "Custom" && cropOp != null) {
            cropOverlayView?.setCropBounds(
                cropOp.xFraction,
                cropOp.yFraction,
                cropOp.wFraction,
                cropOp.hFraction
            )
            cropOverlayView?.visibility = View.VISIBLE
        } else {
            val bounds = getPresetCropBounds(currentRatio)
            cropOverlayView?.setCropBounds(bounds.left, bounds.top, bounds.width(), bounds.height())
            cropOverlayView?.visibility = View.GONE
        }
        
        if (::player.isInitialized && player.isPlaying) {
            player.pause()
        }
    }

    private fun exitCropEditingMode() {
        cropEditingToolbar?.visibility = View.GONE
        cropOverlayView?.visibility = View.GONE
        editingControlsWrapper.visibility = View.VISIBLE
        setActiveToolButton(0)

        val cropOp = viewModel.project.value?.operations
            ?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Crop>()
            ?.lastOrNull()
        if (cropOp != null) {
            applyCropPreview(cropOp.aspectRatio)
        } else {
            resetCropPreview()
        }
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

    private fun showLoading(message: String, subtitle: String? = null) {
        val tvLoadingTitle = loadingScreen.findViewById<TextView>(R.id.tvLoadingTitle)
        val tvLoadingSubtitle = loadingScreen.findViewById<TextView>(R.id.tvLoadingSubtitle)
        tvLoadingTitle?.text = message
        if (subtitle != null) {
            tvLoadingSubtitle?.text = subtitle
            tvLoadingSubtitle?.visibility = View.VISIBLE
        } else {
            tvLoadingSubtitle?.visibility = View.GONE
        }
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
            if (kotlin.math.abs(speed - s) < 0.01f) {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorOnPrimary))
            } else {
                bg?.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                txt?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.toolTextInactive))
            }
        }
        
        val slider = toolbar.findViewById<com.google.android.material.slider.Slider>(R.id.sliderCustomSpeed)
        val tvCustomSpeed = toolbar.findViewById<TextView>(R.id.tvCustomSpeedValue)
        
        slider?.let {
            val safeSpeed = speed.coerceIn(it.valueFrom, it.valueTo)
            if (kotlin.math.abs(it.value - safeSpeed) > 0.01f) {
                it.value = safeSpeed
            }
            val roundedValue = String.format(java.util.Locale.US, "%.1f", safeSpeed).toFloat()
            tvCustomSpeed?.text = "${roundedValue}x"
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
            // Reset to normal speed, but preserve reverse if it is reversed!
            if (item.isReversed) {
                lifecycleScope.launch {
                    showLoading("Resetting speed...")
                    val outputFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
                    val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath
                    val result = ffmpegEngine.reverseVideo(
                        sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                        startMs = item.trimStartMs,
                        endMs = item.trimEndMs,
                        outputFilePath = outputFilePath
                    )
                    hideLoading()
                    if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                        val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                        if (index == 0) {
                            viewModel.updateMainVideoSpeed(1.0f, null)
                            viewModel.updateMainVideoReverse(isReversed = true, proxyUri = proxyUri)
                        } else {
                            updateVideoSegment(index, item.copy(speed = 1.0f, proxyUri = proxyUri, isReversed = true))
                        }
                        hideSpeedEditingToolbar()
                    } else {
                        android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed_reset, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                if (index == 0) {
                    viewModel.updateMainVideoSpeed(1.0f, null)
                } else {
                    updateVideoSegment(index, item.copy(speed = 1.0f, proxyUri = null))
                }
                hideSpeedEditingToolbar()
            }
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

            if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                if (item.isReversed) {
                    showLoading("Reversing speed-adjusted video...")
                    val reverseFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
                    val reverseFilePath = java.io.File(cacheDir, reverseFileName).absolutePath
                    val speedProxyPath = result.outputPath

                    val durationMs = ((item.trimEndMs - item.trimStartMs) / speed).toLong()
                    val reverseResult = ffmpegEngine.reverseVideo(
                        sourceFilePath = speedProxyPath,
                        startMs = 0L,
                        endMs = durationMs,
                        outputFilePath = reverseFilePath
                    )

                    hideLoading()

                    if (reverseResult is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                        val finalProxyUri = android.net.Uri.fromFile(java.io.File(reverseResult.outputPath))
                        if (index == 0) {
                            viewModel.updateMainVideoSpeed(speed, null)
                            viewModel.updateMainVideoReverse(isReversed = true, proxyUri = finalProxyUri)
                        } else {
                            updateVideoSegment(index, item.copy(speed = speed, proxyUri = finalProxyUri, isReversed = true))
                        }
                        hideSpeedEditingToolbar()
                    } else {
                        android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_reverse_speed_proxy, android.widget.Toast.LENGTH_SHORT).show()
                    }
                } else {
                    hideLoading()
                    val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                    if (index == 0) {
                        viewModel.updateMainVideoSpeed(speed, proxyUri)
                    } else {
                        updateVideoSegment(index, item.copy(speed = speed, proxyUri = proxyUri))
                    }
                    hideSpeedEditingToolbar()
                }
            } else {
                hideLoading()
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_apply_speed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun reverseVideoSegment(index: Int, item: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        if (item.isReversed) {
            // Un-reverse the clip
            if (index == 0) {
                if (item.speed == 1.0f) {
                    viewModel.updateMainVideoReverse(isReversed = false, proxyUri = null)
                } else {
                    regenerateSpeedProxyForUnreverse(index, item)
                }
            } else {
                if (item.speed == 1.0f) {
                    updateVideoSegment(index, item.copy(isReversed = false, proxyUri = null))
                } else {
                    regenerateSpeedProxyForUnreverse(index, item)
                }
            }
            return
        }

        lifecycleScope.launch {
            showLoading("Reversing video...")
            val outputFileName = "proxy_reverse_${System.currentTimeMillis()}.mp4"
            val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath

            val sourceFilePath: String
            val startMs: Long
            val endMs: Long

            if (item.proxyUri != null) {
                sourceFilePath = getFilePathFromUri(item.proxyUri) ?: ""
                startMs = 0L
                endMs = item.trimmedDurationMs
            } else {
                sourceFilePath = getFilePathFromUri(item.uri) ?: ""
                startMs = item.trimStartMs
                endMs = item.trimEndMs
            }

            val result = ffmpegEngine.reverseVideo(
                sourceFilePath = sourceFilePath,
                startMs = startMs,
                endMs = endMs,
                outputFilePath = outputFilePath
            )

            hideLoading()

            if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                val reversedProxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                if (index == 0) {
                    viewModel.updateMainVideoReverse(isReversed = true, proxyUri = reversedProxyUri)
                } else {
                    updateVideoSegment(index, item.copy(isReversed = true, proxyUri = reversedProxyUri))
                }
            } else {
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_reverse_video, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun regenerateSpeedProxyForUnreverse(index: Int, item: com.tharunbirla.librecuts.models.EditOperation.MergeItem) {
        lifecycleScope.launch {
            showLoading("Restoring forward video...")
            val outputFileName = "proxy_speed_${System.currentTimeMillis()}.mp4"
            val outputFilePath = java.io.File(cacheDir, outputFileName).absolutePath

            val result = ffmpegEngine.generateSpeedProxy(
                sourceFilePath = getFilePathFromUri(item.uri) ?: "",
                startMs = item.trimStartMs,
                endMs = item.trimEndMs,
                speed = item.speed,
                outputFilePath = outputFilePath
            )

            hideLoading()

            if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Success) {
                val proxyUri = android.net.Uri.fromFile(java.io.File(result.outputPath))
                if (index == 0) {
                    viewModel.updateMainVideoSpeed(item.speed, proxyUri)
                    viewModel.updateMainVideoReverse(isReversed = false, proxyUri = null)
                } else {
                    updateVideoSegment(index, item.copy(isReversed = false, speed = item.speed, proxyUri = proxyUri))
                }
            } else {
                android.widget.Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_restore_video_speed, android.widget.Toast.LENGTH_SHORT).show()
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
            "1:1" to Triple(R.id.bg1_1, R.id.ic1_1, R.id.txt1_1),
            "Custom" to Triple(R.id.bgCustom, R.id.icCustom, R.id.txtCustom)
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
                setTextColor(resources.getColor(if (isActive) R.color.colorOnPrimary else R.color.toolTextInactive, null))
                paint.isFakeBoldText = isActive
            }
        }

        cropOverlayView?.visibility = if (ratio == "Custom") View.VISIBLE else View.GONE
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
            Toast.makeText(this, R.string.toast_seeker_is_not_over_the_selecte, Toast.LENGTH_SHORT).show()
            return
        }
        
        // Calculate the local split time inside the clip's own timeline
        val localOffset = globalPos - clipStartGlobal
        val localSplitTimeMs = item.trimStartMs + localOffset
        
        viewModel.splitVideoSegment(index, localSplitTimeMs, item.uri, item.durationMs)
        selectedVideoIndex = null
        exitVideoEditingMode()
    }
    
    private fun clearProjectAndShowImportScreen() {
        if (::player.isInitialized) {
            if (player.isPlaying) player.pause()
            player.clearMediaItems()
        }
        isVideoLoaded = false
        videoUri = null
        viewModel.clearAllOperations() // wait, no initialize is better
        
        // Keep timeline container visible, but hide its normal contents
        timelineContainer.visibility = View.VISIBLE
        playerContainer.visibility = View.INVISIBLE
        findViewById<View>(R.id.seekerContainer).visibility = View.VISIBLE
        findViewById<View>(R.id.editingControlsScroll).visibility = View.INVISIBLE
        
        // Hide normal timeline elements inside the container
        findViewById<View>(R.id.timelineHorizontalScroll)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.btnTimelineAdd)?.visibility = View.INVISIBLE
        findViewById<View>(R.id.customVideoSeeker)?.visibility = View.INVISIBLE
        
        // Hide toolbars
        exitVideoEditingMode()
        exitAudioEditingMode()
        exitImageEditingMode()
        exitTextEditingMode()
        
        // Show empty state
        findViewById<View>(R.id.emptyProjectState)?.visibility = View.VISIBLE
        setEditingButtonsEnabled(false)
        
        // Ensure loading screen is hidden and reset
        isImportLoading = false
        loadingScreen.visibility = View.GONE
    }

    private fun setEditingButtonsEnabled(enabled: Boolean) {
        val alpha = if (enabled) 1.0f else 0.5f
        if (::btnPlayPause.isInitialized) {
            btnPlayPause.isEnabled = enabled
            btnPlayPause.alpha = alpha
        }
        findViewById<View>(R.id.btnMagnet)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.btnMute)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.btnCaptureFrame)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        findViewById<View>(R.id.layoutSaveSplit)?.apply {
            isEnabled = enabled
            this.alpha = alpha
        }
        if (!enabled) {
            if (::btnUndo.isInitialized) {
                btnUndo.isEnabled = false
                btnUndo.alpha = 0.5f
            }
            if (::btnRedo.isInitialized) {
                btnRedo.isEnabled = false
                btnRedo.alpha = 0.5f
            }
        }
    }

    private fun deleteSelectedVideo() {
        val index = selectedVideoIndex ?: return
        val items = getSequenceItems()
        
        if (items.size <= 1) {
            clearProjectAndShowImportScreen()
            return
        }
        
        if (index > 0) {
            viewModel.removeMergeVideo(index - 1)
        } else {
            val nextItem = items[1]
            viewModel.removeMergeVideo(0)
            
            val newSourceFile = File(nextItem.uri.path ?: nextItem.uri.toString())
            tempInputFile = newSourceFile
            originalMainVideoDurationMs = nextItem.durationMs
            videoFileName = tempInputFile.name
            videoUri = nextItem.uri
            
            // Reinitialize project base with new operations
            val mainOps = viewModel.project.value?.operations?.filter { 
                it is EditOperation.Trim || it is EditOperation.SpeedMain || it is EditOperation.ReverseMain 
            }
            if (mainOps != null) {
                mainOps.forEach { op ->
                    val opId = when(op) {
                        is EditOperation.Trim -> op.id
                        is EditOperation.SpeedMain -> op.id
                        is EditOperation.ReverseMain -> op.id
                        else -> null
                    }
                    if (opId != null) viewModel.removeOperation(opId) 
                }
            }
            
            if (nextItem.trimStartMs > 0 || nextItem.trimEndMs < nextItem.durationMs) {
                viewModel.updateMainVideoTrim(nextItem.trimStartMs, nextItem.trimEndMs)
            }
            if (nextItem.speed != 1.0f) {
                viewModel.updateMainVideoSpeed(nextItem.speed, nextItem.proxyUri)
            }
            if (nextItem.isReversed) {
                viewModel.updateMainVideoReverse(true, nextItem.proxyUri)
            }
            
            // Need to update the sourceUri and sourceName in the project
            viewModel.project.value?.let { proj ->
                viewModel.updateSequenceOrder(getSequenceItems().toMutableList().apply { 
                    this[0] = this[0].copy(uri = nextItem.uri, durationMs = nextItem.durationMs) 
                })
            }
        }
        selectedVideoIndex = null
        exitVideoEditingMode()
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
                Toast.makeText(this@VideoEditingActivity, R.string.toast_audio_extracted_to_new_layer, Toast.LENGTH_SHORT).show()
            } else if (result is com.tharunbirla.librecuts.services.FFmpegRenderEngine.RenderResult.Failure) {
                Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_extract_audio, Toast.LENGTH_SHORT).show()
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
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Video"), PICK_VIDEO_REQUEST)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(intent, PICK_VIDEO_REQUEST)
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle this action", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openFilePickerMain() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        try {
            mainFilePickerLauncher.launch(Intent.createChooser(intent, "Select Video"))
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                mainFilePickerLauncher.launch(intent)
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle this action", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val mainFilePickerLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                emptyProjectState.visibility = View.GONE
                setEditingButtonsEnabled(true)
                playerContainer.visibility = View.VISIBLE
                timelineContainer.visibility = View.VISIBLE
                findViewById<View>(R.id.seekerContainer).visibility = View.VISIBLE
                findViewById<View>(R.id.editingControlsScroll).visibility = View.VISIBLE
                
                // Restore timeline children visibility
                findViewById<View>(R.id.timelineHorizontalScroll)?.visibility = View.VISIBLE
                findViewById<View>(R.id.btnTimelineAdd)?.visibility = View.VISIBLE
                findViewById<View>(R.id.customVideoSeeker)?.visibility = View.VISIBLE
                
                showLoading(getString(R.string.loading), getString(R.string.loading_tag))
                isImportLoading = true
                isVideoLoaded = false
                videoUri = uri
                
                lifecycleScope.launch {
                    val projectSourcePath = getFilePathFromUri(uri)
                    if (projectSourcePath != null) {
                        tempInputFile = File(projectSourcePath)
                        videoFileName = tempInputFile.name
                    }
                    
                    viewModel.initializeProject(uri, videoFileName)
                    initializeVideoData()
                }
            }
        }
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
                            Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_load_selected_video, Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(this@VideoEditingActivity, R.string.toast_audio_track_added, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { imageUri ->
                showImageOverlayConfig(imageUri)
            }
        } else if (requestCode == PICK_SRT_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { srtUri ->
                lifecycleScope.launch {
                    val tempFile = withContext(Dispatchers.IO) { copyContentUriToTempFile(srtUri, "subtitles", ".srt") }
                    if (tempFile != null) {
                        val tempUri = Uri.fromFile(tempFile)
                        val content = withContext(Dispatchers.IO) {
                            try {
                                val bytes = tempFile.readBytes()
                                if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
                                    String(bytes, 2, bytes.size - 2, kotlin.text.Charsets.UTF_16LE)
                                } else if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
                                    String(bytes, 2, bytes.size - 2, kotlin.text.Charsets.UTF_16BE)
                                } else if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                                    String(bytes, 3, bytes.size - 3, kotlin.text.Charsets.UTF_8)
                                } else {
                                    String(bytes, kotlin.text.Charsets.UTF_8)
                                }
                            } catch (e: Exception) {
                                ""
                            }
                        }
                        val cues = withContext(Dispatchers.IO) {
                            try {
                                com.tharunbirla.librecuts.utils.SubtitleParser.parse(content)
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                        if (cues.isNotEmpty()) {
                            // First remove any existing subtitles operations so we only have one subtitles track
                            val project = viewModel.project.value
                            project?.operations?.filterIsInstance<EditOperation.AddSubtitles>()?.forEach { op ->
                                viewModel.deleteOperation(op.id)
                            }
                            
                            val name = try {
                                var displayName = "subtitles.srt"
                                contentResolver.query(srtUri, null, null, null, null)?.use { cursor ->
                                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                    if (nameIndex != -1 && cursor.moveToFirst()) {
                                        displayName = cursor.getString(nameIndex)
                                    }
                                }
                                displayName
                            } catch (e: Exception) {
                                "subtitles.srt"
                            }

                            viewModel.addOperation(
                                EditOperation.AddSubtitles(
                                    subtitlesUri = tempUri,
                                    srtContent = content,
                                    fileName = name,
                                    cues = cues
                                )
                            )
                            Toast.makeText(this@VideoEditingActivity, "Subtitles added: ${cues.size} captions", Toast.LENGTH_SHORT).show()
                            updateSubtitlesUi()
                            textOverlayView?.setSubtitleCues(cues)
                        } else {
                            Toast.makeText(this@VideoEditingActivity, R.string.toast_failed_to_parse_subtitle_file, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        } else if (requestCode == PICK_DIRECTORY_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.data?.let { treeUri ->
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(treeUri, takeFlags)

                    val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
                    val isAudioOnly = viewModel.exportAudioOnly.value
                    val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
                    sharedPreferences.edit().putString(prefKey, treeUri.toString()).apply()

                    val activeTitle = activeDirectoryTitleView
                    val activePath = activeDirectoryPathView
                    if (activeTitle != null && activePath != null) {
                        updateExportDirectoryUi(activeTitle, activePath)
                    }
                    Toast.makeText(this, R.string.toast_export_location_updated_succes, Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving export directory: ${e.message}", e)
                    Toast.makeText(this, R.string.toast_failed_to_select_folder, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun audioAction() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "audio/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Audio"), PICK_AUDIO_REQUEST)
        } catch (e: android.content.ActivityNotFoundException) {
            try {
                startActivityForResult(intent, PICK_AUDIO_REQUEST)
            } catch (e2: android.content.ActivityNotFoundException) {
                Toast.makeText(this, "No app found to handle this action", Toast.LENGTH_SHORT).show()
            }
        }
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

        exportJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.Job()).launch {
            var tempOutputFile: File? = null
            var concatFile: File? = null
            try {
                viewModel.startExport()

                val sourceFilePath = tempInputFile.absolutePath
                val isAudioOnly = viewModel.exportAudioOnly.value
                val ext = if (isAudioOnly) ".mp3" else ".mp4"
                tempOutputFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}$ext")
                val tempOutputPath = tempOutputFile.absolutePath

                // ── THE FIX: pass fontFilePath as the third argument ──────────────
                var ffmpegCommand = viewModel.buildConsolidatedFFmpegCommand(
                    sourceFilePath = sourceFilePath,
                    outputFilePath = tempOutputPath,
                    fontFilePath = fontFilePath,
                    context = this@VideoEditingActivity  // needed to cache content:// URIs for audio/image
                )

                if (ffmpegCommand == null) {
                    viewModel.exportError("Failed to build FFmpeg command")
                    return@launch
                }

                Log.d(TAG, "Raw FFmpeg command: $ffmpegCommand")

                // Handle merge operations
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
                                R.string.toast_video_exported_to_gallery_succ,
                                Toast.LENGTH_LONG
                            ).show()
                            Log.d(TAG, "Export successful: $savedUri")
                            tempOutputFile.delete()
                            concatFile?.delete()
                        } else {
                            viewModel.exportError("Failed to save video to Gallery")
                            Toast.makeText(
                                this@VideoEditingActivity,
                                R.string.toast_failed_to_save_video_to_galler,
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
                        Toast.makeText(this@VideoEditingActivity, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                tempOutputFile?.let { if (it.exists()) it.delete() }
                concatFile?.let { if (it.exists()) it.delete() }
                viewModel.exportError("Export cancelled")
                Toast.makeText(this@VideoEditingActivity, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                tempOutputFile?.let { if (it.exists()) it.delete() }
                concatFile?.let { if (it.exists()) it.delete() }
                viewModel.exportError(e.message ?: "Unknown error")
                Log.e(TAG, "Export exception: ${e.message}", e)
            }
        }
    }

    private fun showQualitySettingsDialog() {
        if (isShowingPreview) dismissPreview()
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.export_quality_bottom_sheet_dialog, null)

        val cgResolution = sheetView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgResolution)
        val cgFps = sheetView.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgFps)
        val switchAudioOnly = sheetView.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchAudioOnly)
        val btnClose = sheetView.findViewById<ImageButton>(R.id.btnCloseSheet)

        // Initialize state
        val currentRes = viewModel.exportResolution.value
        val currentFps = viewModel.exportFps.value
        val isAudioOnly = viewModel.exportAudioOnly.value

        when (currentRes) {
            360 -> cgResolution.check(R.id.chipRes360)
            480 -> cgResolution.check(R.id.chipRes480)
            720 -> cgResolution.check(R.id.chipRes720)
            1080 -> cgResolution.check(R.id.chipRes1080)
            1440 -> cgResolution.check(R.id.chipRes1440)
            2160 -> cgResolution.check(R.id.chipRes2160)
            else -> cgResolution.check(R.id.chipRes1080)
        }

        when (currentFps) {
            24 -> cgFps.check(R.id.chipFps24)
            25 -> cgFps.check(R.id.chipFps25)
            30 -> cgFps.check(R.id.chipFps30)
            50 -> cgFps.check(R.id.chipFps50)
            60 -> cgFps.check(R.id.chipFps60)
            else -> cgFps.check(R.id.chipFps30)
        }

        switchAudioOnly.isChecked = isAudioOnly

        val layoutExportDirectory = sheetView.findViewById<LinearLayout>(R.id.layoutExportDirectory)
        val tvExportDirectoryTitle = sheetView.findViewById<TextView>(R.id.tvExportDirectoryTitle)
        val tvExportDirectoryPath = sheetView.findViewById<TextView>(R.id.tvExportDirectoryPath)

        activeDirectoryTitleView = tvExportDirectoryTitle
        activeDirectoryPathView = tvExportDirectoryPath
        updateExportDirectoryUi(tvExportDirectoryTitle, tvExportDirectoryPath)

        fun saveSettings() {
            val res = when (cgResolution.checkedChipId) {
                R.id.chipRes360 -> 360
                R.id.chipRes480 -> 480
                R.id.chipRes720 -> 720
                R.id.chipRes1080 -> 1080
                R.id.chipRes1440 -> 1440
                R.id.chipRes2160 -> 2160
                else -> 1080
            }
            val fps = when (cgFps.checkedChipId) {
                R.id.chipFps24 -> 24
                R.id.chipFps25 -> 25
                R.id.chipFps30 -> 30
                R.id.chipFps50 -> 50
                R.id.chipFps60 -> 60
                else -> 30
            }
            viewModel.setExportSettings(res, fps, switchAudioOnly.isChecked)
            updateExportDirectoryUi(tvExportDirectoryTitle, tvExportDirectoryPath)
        }

        cgResolution.setOnCheckedStateChangeListener { _, _ -> saveSettings() }
        cgFps.setOnCheckedStateChangeListener { _, _ -> saveSettings() }
        switchAudioOnly.setOnCheckedChangeListener { _, _ -> saveSettings() }

        btnClose.setBounceClickListener {
            bottomSheetDialog.dismiss()
        }

        layoutExportDirectory.setBounceClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            try {
                startActivityForResult(intent, PICK_DIRECTORY_REQUEST)
            } catch (e: android.content.ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_failed_to_select_folder, Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheetDialog.setOnDismissListener {
            activeDirectoryTitleView = null
            activeDirectoryPathView = null
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun exportVideoFile(uri: Uri) {
        exportJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.Job()).launch {
            var outputFile: File? = null
            var customFileUri: Uri? = null
            try {
                viewModel.startExport()
                
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
                    val isAudioOnly = viewModel.exportAudioOnly.value
                    val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
                    val customUriString = sharedPreferences.getString(prefKey, null)
                    val sourceFile = File(tempInputFile.absolutePath)
                    val totalSize = sourceFile.length()
                    
                    val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
                    val ext = if (isAudioOnly) ".mp3" else ".mp4"
                    val prefix = if (isAudioOnly) "LibreCuts_Audio_" else "LibreCuts_"

                    if (customUriString != null) {
                        try {
                            val treeUri = Uri.parse(customUriString)
                            val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                                treeUri,
                                android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                            )
                            customFileUri = android.provider.DocumentsContract.createDocument(
                                contentResolver,
                                parentUri,
                                mimeType,
                                "${prefix}${System.currentTimeMillis()}$ext"
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to create SAF file: ${e.message}, falling back to default", e)
                        }
                    }
                    
                    val outputStream = if (customFileUri != null) {
                        contentResolver.openOutputStream(customFileUri!!)
                    } else {
                        // Default fallback
                        val defaultDir = if (isAudioOnly) Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC) else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                        val outputDir = File(defaultDir, "LibreCuts")
                        if (!outputDir.exists()) outputDir.mkdirs()
                        val file = File(outputDir, "${prefix}${System.currentTimeMillis()}$ext")
                        outputFile = file
                        java.io.FileOutputStream(file)
                    }
                    
                    val input = java.io.FileInputStream(sourceFile)
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    
                    var lastProgressUpdate = System.currentTimeMillis()
                    
                    input.use { i ->
                        outputStream?.use { o ->
                            while (i.read(buffer).also { bytesRead = it } >= 0) {
                                if (!isActive) {
                                    throw kotlinx.coroutines.CancellationException("Export cancelled")
                                }
                                o.write(buffer, 0, bytesRead)
                                totalBytesRead += bytesRead
                                
                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 100) { // Update UI at most every 100ms
                                    val progress = ((totalBytesRead.toFloat() / totalSize) * 100).toInt()
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        viewModel.updateExportProgress(progress)
                                    }
                                    lastProgressUpdate = now
                                }
                            }
                        }
                    }
                    
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        viewModel.updateExportProgress(100)
                        viewModel.finishExport()
                        val displayPath = if (customFileUri != null) {
                            "Custom Folder"
                        } else {
                            outputFile?.absolutePath ?: "Downloads/LibreCuts"
                        }
                        Toast.makeText(
                            this@VideoEditingActivity,
                            "Video exported: $displayPath",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                outputFile?.let { if (it.exists()) it.delete() }
                customFileUri?.let {
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, it)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to delete cancelled custom file: ${ex.message}")
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    viewModel.exportError("Export cancelled")
                    Toast.makeText(this@VideoEditingActivity, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                outputFile?.let { if (it.exists()) it.delete() }
                customFileUri?.let {
                    try {
                        android.provider.DocumentsContract.deleteDocument(contentResolver, it)
                    } catch (ex: Exception) {
                        Log.w(TAG, "Failed to delete failed custom file: ${ex.message}")
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    viewModel.exportError(e.message ?: "Export failed")
                }
            }
        }
    }

    private fun cancelExport() {
        lifecycleScope.launch {
            try {
                ffmpegEngine.cancelAllSessions()
            } catch (e: Exception) {
                Log.e(TAG, "Error cancelling FFmpeg sessions: ${e.message}")
            }
        }
        exportJob?.cancel()
        viewModel.exportError("Export cancelled")
        Toast.makeText(this, R.string.toast_export_cancelled, Toast.LENGTH_SHORT).show()
    }

    private fun updateExportDirectoryUi(titleView: TextView, pathView: TextView) {
        val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
        val isAudioOnly = viewModel.exportAudioOnly.value
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)
        
        if (customUriString != null) {
            val customUri = Uri.parse(customUriString)
            val displayName = getDocumentFolderName(customUri) ?: "Custom Folder"
            titleView.text = displayName
            pathView.text = customUri.path ?: customUriString
        } else {
            titleView.text = "LibreCuts (Default)"
            pathView.text = if (isAudioOnly) "Music/LibreCuts" else "Movies/LibreCuts"
        }
    }

    private fun getDocumentFolderName(uri: Uri): String? {
        return try {
            val documentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                uri,
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            )
            contentResolver.query(documentUri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
        } catch (e: Exception) {
            null
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
                        val totalDuration = getTotalSequenceDuration()
                        customVideoSeeker.setVideoDuration(totalDuration)
                        timeRulerView.setVideoDuration(totalDuration)
                        updateDurationDisplay(getGlobalPosition().toInt(), totalDuration.toInt())
                        
                        if (!isInitialFitDone && totalDuration > 0L) {
                            timelineHorizontalScroll.post {
                                val scrollWidth = timelineHorizontalScroll.width.toFloat()
                                if (scrollWidth > 0) {
                                    val safeDuration = totalDuration
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
                            cropOverlayView?.setVideoSize(displayWidth, displayHeight)
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
                        cropOverlayView?.setVideoSize(displayWidth, displayHeight)
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
        val reverseOp = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.ReverseMain>()?.lastOrNull()
        val isReversed = reverseOp?.isReversed ?: false
        val proxyUri = reverseOp?.proxyUri ?: speedOp?.proxyUri
        items.add(com.tharunbirla.librecuts.models.EditOperation.MergeItem(sourceUri, sourceDuration, sTrimStart, sTrimEnd, speed, proxyUri, isReversed))
        
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

    private var lastAppliedFilterName: String? = null
    private var lastAppliedAdjust: com.tharunbirla.librecuts.models.EditOperation.Adjust? = null

    private fun applyColorFilterToPlayer(filterName: String) {
        val idx = selectedVideoIndex ?: 0
        val activeAdjust = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Adjust>()
            ?.find { it.index == idx }
        applyColorFilterAndAdjustToPlayer(filterName, activeAdjust)
    }

    private fun applyColorFilterAndAdjustToPlayer(
        filterName: String,
        adjust: com.tharunbirla.librecuts.models.EditOperation.Adjust?
    ) {
        if (lastAppliedFilterName == filterName && lastAppliedAdjust == adjust) return
        lastAppliedFilterName = filterName
        lastAppliedAdjust = adjust

        val filterMatrix = when (filterName.lowercase()) {
            "vintage" -> floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f,     0f,     0f,     1f, 0f
            )
            "warm" -> floatArrayOf(
                1.1f, 0f, 0f, 0f, 10f,
                0f, 1.0f, 0f, 0f, 5f,
                0f, 0f, 0.9f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            )
            "cool" -> floatArrayOf(
                0.9f, 0f, 0f, 0f, -10f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 15f,
                0f, 0f, 0f, 1f, 0f
            )
            "contrast" -> floatArrayOf(
                1.4f, 0f, 0f, 0f, -50f,
                0f, 1.4f, 0f, 0f, -50f,
                0f, 0f, 1.4f, 0f, -50f,
                0f, 0f, 0f, 1f, 0f
            )
            "monochrome" -> floatArrayOf(
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0.33f, 0.59f, 0.11f, 0f, 0f,
                0f,    0f,    0f,    1f, 0f
            )
            "vignette" -> floatArrayOf(
                0.8f, 0f, 0f, 0f, 0f,
                0f, 0.8f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
            "negative" -> floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            )
            "crossprocess" -> floatArrayOf(
                1.2f, 0f, 0f, 0f, 0f,
                0f, 1.0f, 0f, 0f, 10f,
                0f, 0f, 1.4f, 0f, -20f,
                0f, 0f, 0f, 1f, 0f
            )
            else -> null
        }

        val baseMatrix = if (filterMatrix != null) {
            android.graphics.ColorMatrix(filterMatrix)
        } else {
            android.graphics.ColorMatrix()
        }

        if (adjust != null && !adjust.isDefault()) {
            if (adjust.saturation != 0) {
                val satFactor = 1.0f + (adjust.saturation / 100f)
                val satMatrix = android.graphics.ColorMatrix()
                satMatrix.setSaturation(satFactor)
                baseMatrix.postConcat(satMatrix)
            }
            if (adjust.contrast != 0) {
                val scale = 1.0f + (adjust.contrast / 100f)
                val translate = 127.5f * (1.0f - scale)
                val contrastMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, translate,
                    0f, scale, 0f, 0f, translate,
                    0f, 0f, scale, 0f, translate,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(contrastMatrix)
            }
            if (adjust.brightness != 0) {
                val bOffset = (adjust.brightness / 100f) * 255f
                val brightMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, bOffset,
                    0f, 1f, 0f, 0f, bOffset,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(brightMatrix)
            }
            if (adjust.exposure != 0) {
                val ev = (adjust.exposure / 100f) * 3.0f
                val scale = Math.pow(2.0, ev.toDouble()).toFloat()
                val expMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    scale, 0f, 0f, 0f, 0f,
                    0f, scale, 0f, 0f, 0f,
                    0f, 0f, scale, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(expMatrix)
            }
            if (adjust.warmth != 0) {
                val rOffset = (adjust.warmth / 100f) * 30f
                val bOffset = -(adjust.warmth / 100f) * 30f
                val warmthMatrix = android.graphics.ColorMatrix(floatArrayOf(
                    1f, 0f, 0f, 0f, rOffset,
                    0f, 1f, 0f, 0f, rOffset / 2f,
                    0f, 0f, 1f, 0f, bOffset,
                    0f, 0f, 0f, 1f, 0f
                ))
                baseMatrix.postConcat(warmthMatrix)
            }
        }

        val hasAdjustments = (adjust != null && !adjust.isDefault())
        if (filterMatrix != null || hasAdjustments) {
            val paint = android.graphics.Paint()
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(baseMatrix)
            playerView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
        } else {
            playerView.setLayerType(View.LAYER_TYPE_NONE, null)
        }
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

            // Apply color filter corresponding to the active clip
            val sequenceItems = getSequenceItems()
            var accumulatedStartMs = 0L
            var activeClipIndex = 0
            for ((index, item) in sequenceItems.withIndex()) {
                val start = accumulatedStartMs
                val end = accumulatedStartMs + item.trimmedDurationMs
                if (currentGlobalPos >= start && currentGlobalPos <= end) {
                    activeClipIndex = index
                    break
                }
                accumulatedStartMs += item.trimmedDurationMs
            }
            val activeFilterName = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.ColorFilter>()
                ?.find { it.index == activeClipIndex }?.filterName ?: "none"
            val activeAdjust = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Adjust>()
                ?.find { it.index == activeClipIndex }
            applyColorFilterAndAdjustToPlayer(activeFilterName, activeAdjust)

            val isMirrored = if (activeClipIndex == 0) {
                viewModel.project.value?.operations?.any { it is com.tharunbirla.librecuts.models.EditOperation.MirrorMain && it.isMirrored } == true
            } else {
                viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Merge>()
                    ?.firstOrNull()?.items?.getOrNull(activeClipIndex - 1)?.isMirrored == true
            }
            
            val videoSurface = playerView.videoSurfaceView
            if (videoSurface != null) {
                videoSurface.scaleX = if (isMirrored) -1f else 1f
            } else {
                findTextureView(playerView)?.scaleX = if (isMirrored) -1f else 1f
            }
        }
    }

    private var lastSoughtGlobalPos = -1L
    private var pendingSeekRunnable: Runnable? = null

    private fun seekToGlobalPosition(globalPos: Long, force: Boolean = false) {
        if (!::player.isInitialized) return
        
        val timeDiff = Math.abs(globalPos - lastSoughtGlobalPos)
        if (!force && timeDiff < 40 && lastSoughtGlobalPos != -1L) {
            pendingSeekRunnable?.let { customVideoSeeker.removeCallbacks(it) }
            val runnable = Runnable {
                seekToGlobalPosition(globalPos, force = true)
            }
            pendingSeekRunnable = runnable
            customVideoSeeker.postDelayed(runnable, 50)
            return
        }
        
        pendingSeekRunnable?.let { customVideoSeeker.removeCallbacks(it) }
        lastSoughtGlobalPos = globalPos

        var remainingPos = globalPos
        var index = 0
        while (index < chunkDurationsMs.size && remainingPos > chunkDurationsMs[index]) {
            remainingPos -= chunkDurationsMs[index]
            index++
        }
        if (index < chunkDurationsMs.size) {
            player.seekTo(index, remainingPos)
        } else if (chunkDurationsMs.isNotEmpty()) {
            val lastIndex = chunkDurationsMs.size - 1
            player.seekTo(lastIndex, chunkDurationsMs[lastIndex])
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
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Error initializing video: ${e.message}")
                isImportLoading = false
                isVideoLoaded = true
                loadingScreen.visibility = View.GONE
                finish()
            }
        }
    }

    private val uriToFilePathCache = mutableMapOf<Uri, String>()

    private suspend fun getFilePathFromUri(uri: Uri): String? {
        if (uriToFilePathCache.containsKey(uri)) {
            val cached = uriToFilePathCache[uri]
            if (cached != null && File(cached).exists()) {
                return cached
            }
        }
        var filePath: String? = null
        when (uri.scheme) {
            "content" -> {
                // For content URIs, always copy to cache to avoid Scoped Storage issues
                filePath = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val tempFile = File(cacheDir, "imported_video_${System.currentTimeMillis()}.mp4")
                        val inputStream = contentResolver.openInputStream(uri)
                        if (inputStream == null) {
                            Log.e("PathError", "openInputStream returned null for URI: $uri")
                            return@withContext null
                        }
                        inputStream.use { input ->
                            tempFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (tempFile.exists() && tempFile.length() > 0) {
                            tempFile.absolutePath
                        } else {
                            Log.e("PathError", "File copy failed or file is empty")
                            null
                        }
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
        if (filePath != null) {
            uriToFilePathCache[uri] = filePath
        }
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

    private fun startDragSession(view: View, index: Int, rawX: Float) {
        if (isDraggingSegment) return
        isDraggingSegment = true
        draggedIndex = index
        draggedView = view
        initialTouchX = rawX
        initialScrollX = timelineHorizontalScroll.scrollX

        timelineHorizontalScroll.requestDisallowInterceptTouchEvent(true)
        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        view.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .alpha(0.8f)
            .setDuration(100)
            .start()
        view.elevation = 20f * resources.displayMetrics.density

        segmentViews = activeSegmentViews.toMutableList()
        originalLefts.clear()
        for (i in 0 until segmentViews.size) {
            val lp = segmentViews[i].layoutParams as FrameLayout.LayoutParams
            originalLefts.add(lp.leftMargin)
        }
        currentDragOrder = (0 until segmentViews.size).toList()
    }

    private fun updateDragPosition(rawX: Float) {
        val draggedView = draggedView ?: return
        
        // Auto-scroll logic when dragging near screen edges
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeThreshold = 100f * resources.displayMetrics.density
        if (rawX < edgeThreshold) {
            timelineHorizontalScroll.scrollBy(-15, 0)
        } else if (rawX > screenWidth - edgeThreshold) {
            timelineHorizontalScroll.scrollBy(15, 0)
        }
        
        val scrollDelta = timelineHorizontalScroll.scrollX - initialScrollX
        val dx = rawX - initialTouchX + scrollDelta
        draggedView.translationX = dx

        val draggedCenter = originalLefts[draggedIndex] + draggedView.width / 2f + dx
        val centers = List(segmentViews.size) { i ->
            if (i == draggedIndex) {
                draggedCenter
            } else {
                originalLefts[i] + segmentViews[i].width / 2f
            }
        }

        val newOrder = (0 until segmentViews.size).sortedBy { centers[it] }
        if (newOrder != currentDragOrder) {
            currentDragOrder = newOrder
            var currentLeft = 0f
            for (index in newOrder) {
                val view = segmentViews[index]
                if (index == draggedIndex) {
                    currentLeft += view.width
                } else {
                    val targetTranslationX = currentLeft - originalLefts[index]
                    view.animate()
                        .translationX(targetTranslationX)
                        .setDuration(150)
                        .start()
                    currentLeft += view.width
                }
            }
        }
    }

    private fun endDragSession() {
        if (!isDraggingSegment) return
        isDraggingSegment = false
        val draggedView = draggedView ?: return

        timelineHorizontalScroll.requestDisallowInterceptTouchEvent(false)

        var targetLeft = 0f
        for (index in currentDragOrder) {
            if (index == draggedIndex) {
                break
            }
            targetLeft += segmentViews[index].width
        }
        val targetTranslationX = targetLeft - originalLefts[draggedIndex]

        draggedView.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .translationX(targetTranslationX)
            .setDuration(150)
            .withEndAction {
                draggedView.elevation = 0f
                val sequenceItems = getSequenceItems()
                val finalItems = currentDragOrder.map { sequenceItems[it] }

                for (view in segmentViews) {
                    view.translationX = 0f
                }

                val originalOrder = (0 until segmentViews.size).toList()
                if (currentDragOrder != originalOrder) {
                    viewModel.updateSequenceOrder(finalItems)
                } else {
                    viewModel.project.value?.let { renderTracks(it) }
                }

                this.draggedView = null
                draggedIndex = -1
            }
            .start()
    }

    private fun performRenderTracks(project: VideoProject) {
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        activeSegmentViews.clear()

        val sequenceItems = getSequenceItems()

        val totalSequenceDuration = getTotalSequenceDuration()
        if (totalSequenceDuration <= 0L) {
            updateTimelineAddButtonPosition()
            return
        }

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
            for ((index, item) in sequenceItems.withIndex()) {
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
                    
                    val clippedSource = com.google.android.exoplayer2.source.ClippingMediaSource(
                        baseVideoSource, clipStartUs, clipEndUs, true, false, true
                    )
                    
                    val isClipMuted = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.MuteClip>()
                        ?.find { it.index == index }?.isMuted ?: false

                    videoSourceForChunk = if (isClipMuted) {
                        com.google.android.exoplayer2.source.FilteringMediaSource(
                            clippedSource,
                            com.google.android.exoplayer2.C.TRACK_TYPE_VIDEO
                        )
                    } else {
                        clippedSource
                    }
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
        
        seekToGlobalPosition(globalPos, force = true)
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
            activeSegmentViews.add(segmentView)

            val stretchedDurationMs = (item.durationMs / item.speed).toLong()
            val stretchedTrimStartMs = (item.trimStartMs / item.speed).toLong()
            val stretchedTrimEndMs = (item.trimEndMs / item.speed).toLong()

            val rv = segmentView.findViewById<RecyclerView>(R.id.segmentFrameRecyclerView)
            val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
            rv.layoutManager = lm
            val itemWidth = maxOf(1, ((stretchedDurationMs * pixelsPerMs) / 15).toInt())
            val adapter = FrameAdapter(emptyList(), itemWidth)
            rv.adapter = adapter
            
            // Align frames precisely with the trim bounds by offsetting the RecyclerView scroll
            rv.post {
                lm.scrollToPositionWithOffset(0, -(stretchedTrimStartMs * pixelsPerMs).toInt())
            }

            val job = extractFramesForSegment(item.uri, item.durationMs, adapter)
            if (job != null) {
                activeRenderJobs.add(job)
                rv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {}
                    override fun onViewDetachedFromWindow(v: View) {
                        job.cancel()
                        activeRenderJobs.remove(job)
                    }
                })
            }

            val trackTrimView = segmentView.findViewById<com.tharunbirla.librecuts.customviews.TrackTrimView>(R.id.segmentTrimTrack)
            trackTrimView.isMainVideoTrack = true
            trackTrimView.trackColor = android.graphics.Color.TRANSPARENT
            trackTrimView.maxDurationMs = stretchedDurationMs
            trackTrimView.customMsPerPixel = 1.0f / pixelsPerMs
            trackTrimView.isSelectedTrack = (selectedVideoIndex == index)
            trackTrimView.isTrimEnabled = false
            
            // Set the full untrimmed width on TrackTrimView and offset it
            val trackWidth = (stretchedDurationMs * pixelsPerMs).toInt()
            trackTrimView.layoutParams = FrameLayout.LayoutParams(trackWidth, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                leftMargin = -(stretchedTrimStartMs * pixelsPerMs).toInt()
            }
            
            trackTrimView.activeStartMs = stretchedTrimStartMs
            trackTrimView.activeEndMs = stretchedTrimEndMs
            trackTrimView.setRange(stretchedDurationMs, stretchedTrimStartMs, stretchedTrimEndMs)
            
            // Selection highlight is drawn by TrackTrimView in the foreground
            segmentView.background = null
            
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

            var lastTouchRawX = 0f
            val gestureDetector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onLongPress(e: android.view.MotionEvent) {
                    startDragSession(segmentView, index, lastTouchRawX)
                }
            })

            val touchListener = View.OnTouchListener { v, event ->
                lastTouchRawX = event.rawX
                gestureDetector.onTouchEvent(event)
                if (isDraggingSegment && draggedView != null) {
                    if (event.action == android.view.MotionEvent.ACTION_MOVE) {
                        updateDragPosition(event.rawX)
                    } else if (event.action == android.view.MotionEvent.ACTION_UP || event.action == android.view.MotionEvent.ACTION_CANCEL) {
                        endDragSession()
                    }
                    true
                } else {
                    false
                }
            }
            
            segmentView.setOnTouchListener(touchListener)
            trackTrimView.setOnTouchListener(touchListener)
            


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
                val transParams = FrameLayout.LayoutParams(transitionWidth, transitionWidth).apply {
                    leftMargin = segmentLeft - transitionWidth / 2
                    gravity = android.view.Gravity.CENTER_VERTICAL
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

        // Combined Text and Image tracks (Overlays) in Z-order stack
        textTrackContainer.removeAllViews()
        imageTrackContainer.removeAllViews()
        imageTrackContainer.visibility = View.GONE

        val overlayOps = project.operations.filter { it is EditOperation.AddText || it is EditOperation.AddImageOverlay }
        if (overlayOps.isNotEmpty()) {
            textTrackContainer.visibility = View.VISIBLE
            // Reverse so that the top-most overlay is visually at the top of the timeline track stack
            for (op in overlayOps.reversed()) {
                val opId = when (op) {
                    is EditOperation.AddText -> op.id
                    is EditOperation.AddImageOverlay -> op.id
                    else -> ""
                }
                val trackView = com.tharunbirla.librecuts.customviews.TrackTrimView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 48.dpToPx()).apply {
                        topMargin = 4.dpToPx()
                    }
                    isSelectedTrack = (opId == viewModel.selectedOperationId.value)
                    maxDurationMs = totalSequenceDuration
                    customMsPerPixel = 1.0f / pixelsPerMs
                    
                    onTrackClicked = {
                        if (viewModel.selectedOperationId.value == opId) {
                            viewModel.selectOperation(null)
                        } else {
                            viewModel.selectOperation(opId)
                        }
                    }
                    
                    onDragStateChanged = { isDragging ->
                        isTrackDragging = isDragging
                    }
                }

                if (op is EditOperation.AddText) {
                    trackView.apply {
                        trackColor = android.graphics.Color.parseColor("#E91E63") // Pink for text
                        trackLabel = op.text
                        trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_text_24)
                        activeStartMs = op.startTimeMs ?: 0L
                        activeEndMs = op.endTimeMs ?: totalSequenceDuration
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
                    }
                } else if (op is EditOperation.AddImageOverlay) {
                    trackView.apply {
                        trackColor = android.graphics.Color.parseColor("#FF9800") // Orange for image
                        trackLabel = op.imageUri.lastPathSegment ?: "Image Overlay"
                        trackIcon = androidx.core.content.ContextCompat.getDrawable(this@VideoEditingActivity, R.drawable.ic_image_24)
                        activeStartMs = op.startTimeMs ?: 0L
                        activeEndMs = op.endTimeMs ?: totalSequenceDuration
                        val actualDuration = op.fileDurationMs ?: 3000L
                        maxSelectionDurationMs = if (op.isLooping) null else actualDuration
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
                }
                textTrackContainer.addView(trackView)
            }
        } else {
            textTrackContainer.visibility = View.GONE
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
                    beats = op.beats
                    internalStartMs = op.internalStartMs
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
        updateTimelineAddButtonPosition()
    }

    private fun updateTimelineAddButtonPosition() {
        if (!::btnTimelineAdd.isInitialized) return
        val emptyStateVisible = findViewById<View>(R.id.emptyProjectState)?.visibility == View.VISIBLE
        if (emptyStateVisible) {
            btnTimelineAdd.visibility = View.GONE
            return
        }
        val rulerView = findViewById<View>(R.id.timeRulerView) ?: return
        val seqTrack = findViewById<View>(R.id.sequenceTrackContainer) ?: return
        val vScroll = findViewById<android.widget.ScrollView>(R.id.timelineVerticalScroll) ?: return

        val timelineWidth = timelineHorizontalScroll.width
        if (timelineWidth <= 0) return

        val halfWidth = timelineWidth / 2
        val totalDurationMs = getSequenceItems().sumOf { it.trimmedDurationMs }
        val totalTrackWidthPx = halfWidth + (totalDurationMs * pixelsPerMs).toInt()

        val scrollX = timelineHorizontalScroll.scrollX

        // Position the button 8dp to the right of the end of the last clip
        val snapX = totalTrackWidthPx - scrollX + 8.dpToPx()

        val btnWidth = btnTimelineAdd.width.takeIf { it > 0 } ?: 36.dpToPx()
        val rightMargin = 16.dpToPx()
        val maxBtnX = timelineWidth - btnWidth - rightMargin

        // The button floats at the right end of the screen, unless the end of the last clip scrolls into view,
        // in which case it snaps to the end of the clip (snapX).
        val btnX = Math.min(snapX.toFloat(), maxBtnX.toFloat())

        // If the button is scrolled off-screen to the left, hide it
        if (btnX + btnWidth < 0) {
            btnTimelineAdd.visibility = View.GONE
        } else {
            btnTimelineAdd.visibility = View.VISIBLE
            btnTimelineAdd.translationX = btnX
        }

        // Vertically center on the main video track (sequenceTrackContainer)
        // Taking into account the timeRulerView and vertical scroll of the tracks
        val rulerHeight = rulerView.height.takeIf { it > 0 } ?: 28.dpToPx()
        val seqTrackTop = seqTrack.top
        val seqTrackHeight = seqTrack.height.takeIf { it > 0 } ?: 64.dpToPx()
        val btnHeight = btnTimelineAdd.height.takeIf { it > 0 } ?: 36.dpToPx()

        val btnY = rulerHeight + seqTrackTop - vScroll.scrollY + (seqTrackHeight - btnHeight) / 2

        // Check if the button is within the visible bounds of the timeline container
        val visibleTop = rulerHeight
        val visibleBottom = timelineContainer.height
        val btnCenterY = btnY + btnHeight / 2
        if (btnCenterY < visibleTop || btnCenterY > visibleBottom) {
            btnTimelineAdd.visibility = View.GONE
        } else {
            btnTimelineAdd.translationY = btnY.toFloat()
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
        val dialogRecyclerViewWidth = resources.displayMetrics.widthPixels - 80.dpToPx()
        val dialogItemWidth = maxOf(1, dialogRecyclerViewWidth / 15)
        val adapter = FrameAdapter(emptyList(), dialogItemWidth)
        recyclerView.adapter = adapter

        // Extract frames for the entire video clip
        val job = extractFramesForSegment(item.uri, item.durationMs, adapter)
        if (job != null) {
            activeRenderJobs.add(job)
            recyclerView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) {
                    job.cancel()
                    activeRenderJobs.remove(job)
                }
            })
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

    private fun updateVideoMuteButtonState(toolbar: View, index: Int) {
        val isMuted = viewModel.project.value?.operations?.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.MuteClip>()
            ?.find { it.index == index }?.isMuted ?: false
        
        val btnMute = toolbar.findViewById<ImageButton>(R.id.btnVideoMute)
        val tvMuteLabel = toolbar.findViewById<TextView>(R.id.tvVideoMuteLabel)
        
        if (isMuted) {
            btnMute?.setImageResource(R.drawable.ic_volume_off_24)
            tvMuteLabel?.text = "Unmute"
        } else {
            btnMute?.setImageResource(R.drawable.ic_volume_up_24)
            tvMuteLabel?.text = "Mute"
        }
    }

    private fun showColorFilterSelectionDialog(clipIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_color_filters, null)
        bottomSheet.setContentView(view)

        val filtersList = view.findViewById<LinearLayout>(R.id.colorFiltersList)
        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.ColorFilter>().find { it.index == clipIndex }
        var activeFilterName = existingOp?.filterName ?: "none"

        val filters = listOf(
            Pair("none", "None"),
            Pair("vintage", "Vintage"),
            Pair("warm", "Warm"),
            Pair("cool", "Cool"),
            Pair("contrast", "Contrast"),
            Pair("monochrome", "B&W"),
            Pair("vignette", "Vignette"),
            Pair("negative", "Negative"),
            Pair("crossprocess", "Cross P")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()

        fun refreshSelectionStates() {
            for ((id, bgFrame) in itemBgs) {
                val tvShort = itemTvShorts[id]
                val isSelected = (id == activeFilterName)
                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvShort?.setTextColor(android.graphics.Color.WHITE)
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvShort?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        for ((filterId, displayName) in filters) {
            val itemView = layoutInflater.inflate(R.layout.item_color_filter_option, filtersList, false)
            val tvName = itemView.findViewById<TextView>(R.id.filterName)
            val bg = itemView.findViewById<FrameLayout>(R.id.filterIconBg)
            val previewImage = itemView.findViewById<ImageView>(R.id.filterPreviewImage)
            val tvShort = itemView.findViewById<TextView>(R.id.filterShortName)

            itemBgs[filterId] = bg
            itemTvShorts[filterId] = tvShort

            tvName.text = displayName
            bg.clipToOutline = true

            if (filterId == "none") {
                previewImage.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = "✖"
            } else {
                previewImage.visibility = View.VISIBLE
                tvShort.visibility = View.GONE
                val resName = "filter_preview_${filterId.lowercase()}"
                val resId = resources.getIdentifier(resName, "drawable", packageName)
                if (resId != 0) {
                    previewImage.setImageResource(resId)
                }
            }

            itemView.setOnClickListener {
                activeFilterName = filterId
                refreshSelectionStates()
                viewModel.setColorFilter(clipIndex, filterId)
                applyColorFilterToPlayer(filterId)
            }
            
            filtersList.addView(itemView)
        }

        refreshSelectionStates()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val chunkCount = chunkDurationsMs.size
            for (i in 0 until chunkCount) {
                viewModel.setColorFilter(i, activeFilterName)
            }
            applyColorFilterToPlayer(activeFilterName)
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
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
        var activeTransitionType = existingOp?.type ?: "none"
        
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
            Pair("vuslice", "V-Slice")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()

        fun refreshSelectionStates() {
            for ((typeKey, bgFrame) in itemBgs) {
                val tvShort = itemTvShorts[typeKey]
                val isSelected = (typeKey == activeTransitionType)
                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvShort?.setTextColor(android.graphics.Color.WHITE)
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvShort?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        fun applyTransitionToIndex(idx: Int, transType: String) {
            val proj = viewModel.project.value ?: return
            val existing = proj.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Transition>().find { it.index == idx }
            if (transType == "none") {
                if (existing != null) {
                    viewModel.removeOperation(existing.id)
                }
            } else {
                if (existing != null) {
                    viewModel.updateOperation(existing.copy(type = transType))
                } else {
                    viewModel.addTransitionOperation(idx, transType)
                }
            }
        }

        for ((type, name) in transitions) {
            val itemView = layoutInflater.inflate(R.layout.item_transition_option, transitionsList, false)
            val tvName = itemView.findViewById<TextView>(R.id.transitionName)
            val tvShort = itemView.findViewById<TextView>(R.id.transitionShortName)
            val bg = itemView.findViewById<FrameLayout>(R.id.transitionIconBg)

            itemBgs[type] = bg
            itemTvShorts[type] = tvShort

            tvName.text = name
            bg.clipToOutline = true

            val ivPreview = itemView.findViewById<ImageView>(R.id.transitionPreviewImage)
            val startDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.trans_frame_start)
            val endDrawable = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.trans_frame_end)

            if (startDrawable != null && endDrawable != null) {
                if (type == "none") {
                    ivPreview.visibility = View.GONE
                    tvShort.visibility = View.VISIBLE
                    tvShort.text = "✖"
                } else {
                    val animation = android.graphics.drawable.AnimationDrawable().apply {
                        isOneShot = false
                    }
                    val frame1Res = resources.getIdentifier("trans_preview_${type}_1", "drawable", packageName)
                    val frame2Res = resources.getIdentifier("trans_preview_${type}_2", "drawable", packageName)
                    val frame3Res = resources.getIdentifier("trans_preview_${type}_3", "drawable", packageName)

                    if (frame1Res != 0 && frame2Res != 0 && frame3Res != 0) {
                        val f1 = androidx.core.content.ContextCompat.getDrawable(this, frame1Res)
                        val f2 = androidx.core.content.ContextCompat.getDrawable(this, frame2Res)
                        val f3 = androidx.core.content.ContextCompat.getDrawable(this, frame3Res)

                        if (f1 != null && f2 != null && f3 != null) {
                            animation.addFrame(startDrawable, 600)
                            animation.addFrame(f1, 100)
                            animation.addFrame(f2, 100)
                            animation.addFrame(f3, 100)
                            animation.addFrame(endDrawable, 600)

                            ivPreview.setImageDrawable(animation)
                            ivPreview.visibility = View.VISIBLE
                            tvShort.visibility = View.GONE
                            ivPreview.post { animation.start() }
                        } else {
                            ivPreview.visibility = View.GONE
                            tvShort.visibility = View.VISIBLE
                            tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
                        }
                    } else {
                        ivPreview.visibility = View.GONE
                        tvShort.visibility = View.VISIBLE
                        tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
                    }
                }
            } else {
                ivPreview.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = name.substring(0, minOf(2, name.length)).uppercase()
            }

            itemView.setOnClickListener {
                activeTransitionType = type
                refreshSelectionStates()
                applyTransitionToIndex(transitionIndex, type)
            }

            transitionsList.addView(itemView)
        }

        refreshSelectionStates()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val transitionsCount = chunkDurationsMs.size - 1
            for (i in 0 until transitionsCount) {
                applyTransitionToIndex(i, activeTransitionType)
            }
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
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
            showLoading(getString(R.string.loading), getString(R.string.loading_tag))
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
        if (total <= 0) return
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
        val isAudioOnly = videoFile.name.endsWith(".mp3")
        val mimeType = if (isAudioOnly) "audio/mpeg" else "video/mp4"
        val ext = if (isAudioOnly) ".mp3" else ".mp4"
        val prefix = if (isAudioOnly) "LibreCuts_Audio_" else "LibreCuts_"
        
        val sharedPreferences = getSharedPreferences("librecuts_prefs", Context.MODE_PRIVATE)
        val prefKey = if (isAudioOnly) "export_audio_directory_uri" else "export_directory_uri"
        val customUriString = sharedPreferences.getString(prefKey, null)
        if (customUriString != null) {
            try {
                val treeUri = Uri.parse(customUriString)
                val parentUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                )
                val newFileUri = android.provider.DocumentsContract.createDocument(
                    contentResolver,
                    parentUri,
                    mimeType,
                    "${prefix}${System.currentTimeMillis()}$ext"
                )
                if (newFileUri != null) {
                    contentResolver.openOutputStream(newFileUri)?.use { output ->
                        videoFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    return newFileUri
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving to custom directory: ${e.message}, falling back to default", e)
            }
        }

        // Fallback or Default to Movies/LibreCuts
        return try {
            val contentValues = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "${prefix}${System.currentTimeMillis()}$ext")
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (isAudioOnly) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/LibreCuts")
                } else {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/LibreCuts")
                }
            }
            val collectionUri = if (isAudioOnly) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val uri = contentResolver.insert(collectionUri, contentValues)
            uri?.let {
                contentResolver.openOutputStream(it)?.use { output ->
                    videoFile.inputStream().use { input -> input.copyTo(output) }
                }
                it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to default gallery: ${e.message}", e)
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
                fontFilePath = fontFilePath,
                density = resources.displayMetrics.density
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

    override fun onPause() {
        super.onPause()
        if (::player.isInitialized) {
            player.pause()
        }
        activeRenderJobs.forEach { it.cancel() }
        activeRenderJobs.clear()
        frameExtractionJob?.cancel()
        previewJob?.cancel()
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

    private fun showAdjustSelectionDialog(clipIndex: Int) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_adjustments, null)
        bottomSheet.setContentView(view)

        val optionsList = view.findViewById<LinearLayout>(R.id.adjustOptionsList)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.adjustSlider)
        val valueLabel = view.findViewById<TextView>(R.id.adjustValueLabel)

        val project = viewModel.project.value ?: return
        val existingOp = project.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.Adjust>().find { it.index == clipIndex }
        
        var localAdjust = existingOp ?: com.tharunbirla.librecuts.models.EditOperation.Adjust(index = clipIndex)
        var selectedOptionId = "brightness" // Default selection

        val options = listOf(
            Pair("reset", "Reset All"),
            Pair("brightness", "Brightness"),
            Pair("contrast", "Contrast"),
            Pair("warmth", "Warmth"),
            Pair("shadow", "Shadow"),
            Pair("highlights", "Highlights"),
            Pair("saturation", "Saturation"),
            Pair("exposure", "Exposure"),
            Pair("sharpen", "Sharpen"),
            Pair("vignette", "Vignette")
        )

        val itemBgs = mutableMapOf<String, FrameLayout>()
        val itemTvShorts = mutableMapOf<String, TextView>()
        val itemTvNames = mutableMapOf<String, TextView>()

        fun getValForOption(id: String): Int {
            return when (id) {
                "brightness" -> localAdjust.brightness
                "contrast" -> localAdjust.contrast
                "warmth" -> localAdjust.warmth
                "shadow" -> localAdjust.shadow
                "highlights" -> localAdjust.highlights
                "saturation" -> localAdjust.saturation
                "exposure" -> localAdjust.exposure
                "sharpen" -> localAdjust.sharpen
                "vignette" -> localAdjust.vignette
                else -> 0
            }
        }

        fun refreshSelectionStates() {
            for ((id, bgFrame) in itemBgs) {
                val tvName = itemTvNames[id]
                val isSelected = (id == selectedOptionId)
                val isModified = (id != "reset" && getValForOption(id) != 0)

                if (isSelected) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_selected)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorPrimary))
                } else if (isModified) {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = androidx.core.content.ContextCompat.getDrawable(this, R.drawable.bg_transition_border_selected)
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                } else {
                    bgFrame.setBackgroundResource(R.drawable.bg_aspect_ratio_item)
                    bgFrame.foreground = null
                    tvName?.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.textColor))
                }
            }
        }

        fun updateSliderForSelection() {
            if (selectedOptionId == "reset") {
                slider.visibility = View.INVISIBLE
                valueLabel.text = "Reset All"
            } else {
                slider.visibility = View.VISIBLE
                val currentVal = getValForOption(selectedOptionId)
                slider.value = currentVal.toFloat()
                valueLabel.text = "${selectedOptionId.replaceFirstChar { it.uppercase() }}: $currentVal"
            }
        }

        val activeFilterName = project.operations.filterIsInstance<com.tharunbirla.librecuts.models.EditOperation.ColorFilter>()
            .find { it.index == clipIndex }?.filterName ?: "none"

        for ((id, displayName) in options) {
            val itemView = layoutInflater.inflate(R.layout.item_adjust_option, optionsList, false)
            val tvName = itemView.findViewById<TextView>(R.id.adjustName)
            val bg = itemView.findViewById<FrameLayout>(R.id.adjustIconBg)
            val iconImage = itemView.findViewById<ImageView>(R.id.adjustIconImage)
            val tvShort = itemView.findViewById<TextView>(R.id.adjustShortName)

            itemBgs[id] = bg
            itemTvShorts[id] = tvShort
            itemTvNames[id] = tvName

            tvName.text = displayName
            bg.clipToOutline = true

            val resName = "ic_${id.lowercase()}_24"
            val resId = resources.getIdentifier(resName, "drawable", packageName)
            if (resId != 0) {
                iconImage.setImageResource(resId)
                iconImage.visibility = View.VISIBLE
                tvShort.visibility = View.GONE
            } else {
                iconImage.visibility = View.GONE
                tvShort.visibility = View.VISIBLE
                tvShort.text = displayName.substring(0, minOf(2, displayName.length)).uppercase()
            }

            itemView.setOnClickListener {
                if (id == "reset") {
                    localAdjust = com.tharunbirla.librecuts.models.EditOperation.Adjust(index = clipIndex)
                    viewModel.setAdjust(
                        index = clipIndex,
                        brightness = 0,
                        contrast = 0,
                        warmth = 0,
                        shadow = 0,
                        highlights = 0,
                        saturation = 0,
                        exposure = 0,
                        sharpen = 0,
                        vignette = 0
                    )
                    applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
                    refreshSelectionStates()
                    updateSliderForSelection()
                } else {
                    selectedOptionId = id
                    refreshSelectionStates()
                    updateSliderForSelection()
                }
            }

            optionsList.addView(itemView)
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser && selectedOptionId != "reset_all") {
                val intVal = value.toInt()
                localAdjust = when (selectedOptionId) {
                    "brightness" -> localAdjust.copy(brightness = intVal)
                    "contrast" -> localAdjust.copy(contrast = intVal)
                    "warmth" -> localAdjust.copy(warmth = intVal)
                    "shadow" -> localAdjust.copy(shadow = intVal)
                    "highlights" -> localAdjust.copy(highlights = intVal)
                    "saturation" -> localAdjust.copy(saturation = intVal)
                    "exposure" -> localAdjust.copy(exposure = intVal)
                    "sharpen" -> localAdjust.copy(sharpen = intVal)
                    "vignette" -> localAdjust.copy(vignette = intVal)
                    else -> localAdjust
                }

                valueLabel.text = "${selectedOptionId.replaceFirstChar { it.uppercase() }}: $intVal"
                
                viewModel.setAdjust(
                    index = clipIndex,
                    brightness = localAdjust.brightness,
                    contrast = localAdjust.contrast,
                    warmth = localAdjust.warmth,
                    shadow = localAdjust.shadow,
                    highlights = localAdjust.highlights,
                    saturation = localAdjust.saturation,
                    exposure = localAdjust.exposure,
                    sharpen = localAdjust.sharpen,
                    vignette = localAdjust.vignette
                )
                applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
                refreshSelectionStates()
            }
        }

        refreshSelectionStates()
        updateSliderForSelection()

        view.findViewById<View>(R.id.btnApplyToAll)?.setOnClickListener {
            val chunkCount = chunkDurationsMs.size
            for (i in 0 until chunkCount) {
                viewModel.setAdjust(
                    index = i,
                    brightness = localAdjust.brightness,
                    contrast = localAdjust.contrast,
                    warmth = localAdjust.warmth,
                    shadow = localAdjust.shadow,
                    highlights = localAdjust.highlights,
                    saturation = localAdjust.saturation,
                    exposure = localAdjust.exposure,
                    sharpen = localAdjust.sharpen,
                    vignette = localAdjust.vignette
                )
            }
            applyColorFilterAndAdjustToPlayer(activeFilterName, localAdjust)
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        view.findViewById<View>(R.id.btnDone)?.setOnClickListener {
            viewModel.project.value?.let { renderTracks(it) }
            bottomSheet.dismiss()
        }

        bottomSheet.show()
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

    private fun getSnapTargetsMs(): List<Long> {
        val targets = mutableSetOf<Long>()
        targets.add(0L)

        // 1. Clip segment boundaries
        var cumulative = 0L
        for (duration in chunkDurationsMs) {
            cumulative += duration
            targets.add(cumulative)
        }

        // 2. Operations boundaries
        viewModel.project.value?.operations?.forEach { op ->
            when (op) {
                is EditOperation.AddBackgroundAudio -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                    val start = op.startTimeMs ?: 0L
                    val end = op.endTimeMs ?: getTotalSequenceDuration()
                    for (beat in op.beats) {
                        val relative = beat - op.internalStartMs
                        if (relative >= 0) {
                            val timelineMs = start + relative
                            if (timelineMs <= end) {
                                targets.add(timelineMs)
                            }
                        }
                    }
                }
                is EditOperation.AddText -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                is EditOperation.AddImageOverlay -> {
                    op.startTimeMs?.let { targets.add(it) }
                    op.endTimeMs?.let { targets.add(it) }
                }
                else -> {}
            }
        }

        return targets.toList().sorted()
    }

    private fun showChromaKeyDialog() {
        val selectedId = viewModel.selectedOperationId.value
        val op = if (selectedId != null) {
            viewModel.project.value?.operations?.find { (it as? EditOperation.AddImageOverlay)?.id == selectedId } as? EditOperation.AddImageOverlay
        } else null

        if (op == null && draggableImageOverlay?.visibility != View.VISIBLE) return

        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.chroma_key_bottom_sheet_dialog, null)
        bottomSheet.setContentView(view)

        val colorPreview = view.findViewById<View>(R.id.chromaColorPreview)
        val hsvPicker = view.findViewById<com.tharunbirla.librecuts.customviews.HSVColorPickerView>(R.id.hsvChromaColorPicker)
        val slider = view.findViewById<com.google.android.material.slider.Slider>(R.id.chromaIntensitySlider)
        val btnClear = view.findViewById<Button>(R.id.btnChromaClear)
        val btnApply = view.findViewById<Button>(R.id.btnChromaApply)
        val btnEyedropper = view.findViewById<ImageView>(R.id.btnChromaEyedropper)

        var selectedColor = op?.chromaKeyColor ?: draggableImageOverlay?.currentChromaColor ?: "#00FF00"
        slider.value = (op?.chromaKeySimilarity ?: draggableImageOverlay?.currentChromaSimilarity ?: 0.1f).coerceIn(0.01f, 0.5f)

        val originalColor = op?.chromaKeyColor ?: draggableImageOverlay?.currentChromaColor
        val originalSimilarity = op?.chromaKeySimilarity ?: draggableImageOverlay?.currentChromaSimilarity ?: 0.1f

        fun updatePreview() {
            try {
                colorPreview.setBackgroundColor(android.graphics.Color.parseColor(selectedColor))
                draggableImageOverlay?.setChromaKey(selectedColor, slider.value)
            } catch (e: Exception) {
                // Ignore parsing errors for safety
            }
        }
        
        try {
            colorPreview.setBackgroundColor(android.graphics.Color.parseColor(selectedColor))
            hsvPicker.setColor(android.graphics.Color.parseColor(selectedColor))
        } catch (e: Exception) {
            hsvPicker.setColor(android.graphics.Color.GREEN)
        }
        
        hsvPicker.onColorChanged = { newColor ->
            selectedColor = String.format("#%06X", (0xFFFFFF and newColor))
            updatePreview()
        }

        slider.addOnChangeListener { _, _, _ -> updatePreview() }

        btnEyedropper.setBounceClickListener {
            bottomSheet.hide()
            Toast.makeText(this@VideoEditingActivity, "Tap a color on the image to pick it", Toast.LENGTH_SHORT).show()
            draggableImageOverlay?.isColorPickingMode = true
            draggableImageOverlay?.onColorPicked = { hex ->
                selectedColor = hex
                try {
                    hsvPicker.setColor(android.graphics.Color.parseColor(selectedColor))
                } catch(e: Exception){}
                updatePreview()
                draggableImageOverlay?.isColorPickingMode = false
                bottomSheet.show()
            }
        }
        
        var applied = false

        btnClear.setOnClickListener {
            applied = true
            if (op != null) {
                viewModel.updateOperation(op.copy(chromaKeyColor = null))
            }
            draggableImageOverlay?.setChromaKey(null, 0.1f)
            bottomSheet.dismiss()
        }

        btnApply.setOnClickListener {
            applied = true
            if (op != null && selectedColor != null) {
                viewModel.updateOperation(op.copy(
                    chromaKeyColor = selectedColor,
                    chromaKeySimilarity = slider.value
                ))
            }
            bottomSheet.dismiss()
        }

        bottomSheet.setOnDismissListener {
            if (!applied) {
                draggableImageOverlay?.setChromaKey(originalColor, originalSimilarity)
            }
            draggableImageOverlay?.isColorPickingMode = false
        }
        bottomSheet.show()
    }

    private fun showCustomColorPicker(initialHex: String, onColorPicked: (String) -> Unit) {
        val bottomSheet = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_custom_color_picker, null)
        bottomSheet.setContentView(view)

        val preview = view.findViewById<View>(R.id.colorPickerPreview)
        val hsvPicker = view.findViewById<com.tharunbirla.librecuts.customviews.HSVColorPickerView>(R.id.hsvColorPicker)
        val btnCancel = view.findViewById<Button>(R.id.btnCancelCustomColor)
        val btnApply = view.findViewById<Button>(R.id.btnApplyCustomColor)

        var currentColor = android.graphics.Color.parseColor(initialHex)
        preview.setBackgroundColor(currentColor)

        hsvPicker.setColor(currentColor)
        hsvPicker.onColorChanged = { newColor ->
            currentColor = newColor
            preview.setBackgroundColor(currentColor)
        }

        btnCancel.setOnClickListener { bottomSheet.dismiss() }
        btnApply.setOnClickListener {
            val hex = String.format("#%06X", 0xFFFFFF and currentColor)
            onColorPicked(hex)
            bottomSheet.dismiss()
        }
        bottomSheet.show()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
        private const val PICK_AUDIO_REQUEST = 2
        private const val PICK_IMAGE_REQUEST = 3
        private const val PICK_SRT_REQUEST = 4
        private const val PICK_DIRECTORY_REQUEST = 5
    }
}



