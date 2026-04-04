package com.tharunbirla.librecuts

import android.annotation.SuppressLint
import android.app.Activity
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
import android.widget.Spinner
import android.widget.TextView
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
import com.google.android.material.slider.RangeSlider
import com.google.android.material.textfield.TextInputEditText
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
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
                if (player.volume > 0f) {
                    player.volume = 0f
                    btnMute.setImageResource(R.drawable.ic_volume_off_24)
                } else {
                    player.volume = 1f
                    btnMute.setImageResource(R.drawable.ic_volume_up_24)
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
        if (videoDuration <= 0) {
            Toast.makeText(this, "Video duration is invalid.", Toast.LENGTH_SHORT).show()
            return
        }

        val bottomSheetDialog = BottomSheetDialog(this@VideoEditingActivity)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)
        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        val totalSeconds = (videoDuration / 1000).toFloat()
        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = totalSeconds
        rangeSlider.values = listOf(0f, totalSeconds)

        rangeSlider.addOnChangeListener { slider, _, fromUser ->
            if (fromUser) {
                player.seekTo(slider.values[0].toLong() * 1000)
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val startMs = rangeSlider.values[0].toLong() * 1000
            val endMs = rangeSlider.values[1].toLong() * 1000
            viewModel.addTrimOperation(startMs, endMs)
            updatePreviewWithClipping(startMs, endMs)
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Trim operation added (preview active)", Toast.LENGTH_SHORT).show()
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

        sheetView.findViewById<TextView>(R.id.tvTitleCrop).text = getString(R.string.select_aspect_ratio)

        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio1).setOnClickListener {
            viewModel.addCropOperation("16:9")
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Crop 16:9 added (pending)", Toast.LENGTH_SHORT).show()
        }
        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio2).setOnClickListener {
            viewModel.addCropOperation("9:16")
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Crop 9:16 added (pending)", Toast.LENGTH_SHORT).show()
        }
        sheetView.findViewById<FrameLayout>(R.id.frameAspectRatio3).setOnClickListener {
            viewModel.addCropOperation("1:1")
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Crop 1:1 added (pending)", Toast.LENGTH_SHORT).show()
        }
        sheetView.findViewById<Button>(R.id.btnCancelCrop).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    @SuppressLint("InflateParams")
    private fun textAction() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.text_bottom_sheet_dialog, null)

        val etTextInput = view.findViewById<TextInputEditText>(R.id.etTextInput)
        val fontSizeInput = view.findViewById<TextInputEditText>(R.id.fontSize)
        val spinnerTextPosition = view.findViewById<Spinner>(R.id.spinnerTextPosition)
        val btnDone = view.findViewById<Button>(R.id.btnDoneText)

        val positionOptions = TextPosition.labels()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, positionOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTextPosition.adapter = adapter

        btnDone.setOnClickListener {
            val text = etTextInput.text.toString()
            val fontSize = fontSizeInput.text.toString().toIntOrNull() ?: 16
            val textPosition = spinnerTextPosition.selectedItem.toString()

            if (text.isNotEmpty()) {
                viewModel.addTextOperation(text, fontSize, textPosition)
                bottomSheetDialog.dismiss()
                Toast.makeText(this, "Text overlay added (pending)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter text", Toast.LENGTH_SHORT).show()
            }
        }

        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
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
        }
    }

    private fun audioAction() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(android.R.layout.simple_list_item_1, null)
        val tvOptions = view.findViewById<TextView>(android.R.id.text1)
        tvOptions.text = "Audio options:\n\n1. Mute original audio\n2. Add background audio\n\nNote: Audio features require proper setup. Coming soon!"
        bottomSheetDialog.setContentView(view)
        bottomSheetDialog.show()
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
                    val concatListStart = ffmpegCommand.indexOf("(CONCAT_LIST:") + "(CONCAT_LIST:".length
                    val concatListEnd = ffmpegCommand.lastIndexOf(")")
                    if (concatListStart > 13 && concatListEnd > concatListStart) {
                        val concatList = ffmpegCommand.substring(concatListStart, concatListEnd)
                        val processedConcatList = processConcatList(concatList)

                        concatFile = File(cacheDir, "concat_${System.currentTimeMillis()}.txt")
                        concatFile.writeText(processedConcatList)
                        Log.d(TAG, "Concat file: ${concatFile.absolutePath}")
                        Log.d(TAG, "Concat content:\n$processedConcatList")

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
                        Log.e(TAG, "Export failed: ${result.error}")
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
                        loadingScreen.visibility = View.GONE

                        if (player.isPlaying) {
                            startProgressUpdater()
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

    private fun extractVideoFrames() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(tempInputFile.absolutePath)

                val duration = withContext(Dispatchers.Main) { player.duration }
                val frameInterval = duration / 10

                extractedFrames.clear()
                for (i in 0 until 10) {
                    val frameTime = i * frameInterval
                    val bitmap = retriever.getFrameAtTime(
                        frameTime * 1000,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    )
                    bitmap?.let {
                        extractedFrames.add(Bitmap.createScaledBitmap(it, 200, 150, false))
                    }
                }
                retriever.release()

                withContext(Dispatchers.Main) {
                    frameRecyclerView.adapter = FrameAdapter(extractedFrames)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error extracting frames: ${e.message}", e)
            }
        }
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

    private fun copyContentUriToTempFile(contentUri: Uri): File? {
        return try {
            val tempFile = File(cacheDir, "merge_video_${System.currentTimeMillis()}.mp4")
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

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        ffmpegEngine.cleanup()
    }

    companion object {
        private const val TAG = "VideoEditingActivity"
        private const val PICK_VIDEO_REQUEST = 1
    }
}