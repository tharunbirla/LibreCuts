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
import com.tharunbirla.librecuts.models.TextPosition
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * VideoEditingActivity is the main screen for video editing in LibreCuts.
 * 
 * ARCHITECTURE CHANGES:
 * - This activity now uses a state-driven architecture with ViewModel
 * - All edits (trim, crop, text, etc.) are stored as EditOperation objects
 * - Edits are NOT rendered immediately; they're queued in VideoProject
 * - Only when the user clicks "Save/Export" does the entire operation list
 *   get consolidated into a single FFmpeg command and executed
 * - ExoPlayer's native clipping is used for virtual trim previews
 * - No more "render on every change" - just deferred rendering on export
 */
@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    // UI Components
    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var frameRecyclerView: RecyclerView
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView
    private lateinit var btnUndo: ImageButton
    private lateinit var btnRedo: ImageButton

    // ViewModel and Services
    private lateinit var viewModel: VideoEditingViewModel
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    // State
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private lateinit var tempInputFile: File
    private var isVideoLoaded = false
    
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

        // Initialize ViewModel
        viewModel = ViewModelProvider(this).get(VideoEditingViewModel::class.java)
        ffmpegEngine = FFmpegRenderEngine(this)

        // Initialize UI components
        initializeViews()
        setupExoPlayer()
        setupCustomSeeker()
        setupFrameRecyclerView()
        observeViewModelState()
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        frameRecyclerView = findViewById(R.id.frameRecyclerView)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)
        loadingScreen = findViewById(R.id.loadingScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)

        // Set up button click listeners
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveAction() }
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener { trimAction() }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { textAction() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { audioAction() }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener { cropAction() }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener { mergeAction() }

        // Optional: Add undo/redo buttons if they exist in layout
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

    /**
     * Observe ViewModel state changes and update UI accordingly.
     */
    private fun observeViewModelState() {
        lifecycleScope.launch {
            viewModel.uiState.collect { uiState ->
                // Update loading screen visibility
                if (uiState.isExporting) {
                    loadingScreen.visibility = View.VISIBLE
                    lottieAnimationView.playAnimation()
                } else if (!isVideoLoaded) {
                    loadingScreen.visibility = View.VISIBLE
                } else {
                    loadingScreen.visibility = View.GONE
                }

                // Update undo/redo button states
                if (::btnUndo.isInitialized && ::btnRedo.isInitialized) {
                    btnUndo.isEnabled = uiState.canUndo
                    btnUndo.alpha = if (uiState.canUndo) 1.0f else 0.5f
                    btnRedo.isEnabled = uiState.canRedo
                    btnRedo.alpha = if (uiState.canRedo) 1.0f else 0.5f
                }

                // Handle errors
                uiState.errorMessage?.let { error ->
                    showError(error)
                    viewModel.clearError()
                }
            }
        }

        // Observe project changes for logging
        lifecycleScope.launch {
            viewModel.project.collect { project ->
                if (project != null) {
                    Log.d(TAG, "Project updated with ${project.getOperationCount()} operations")
                }
            }
        }
    }

    /**
     * TRIM ACTION: Add a trim operation to the project.
     * No rendering happens here; the operation is just stored in the ViewModel.
     */
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
                val start = slider.values[0].toLong() * 1000
                player.seekTo(start)
            }
        }

        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            val startMs = rangeSlider.values[0].toLong() * 1000
            val endMs = rangeSlider.values[1].toLong() * 1000
            
            // Add operation to ViewModel (no rendering yet)
            viewModel.addTrimOperation(startMs, endMs)
            
            // Show virtual preview using ExoPlayer clipping
            updatePreviewWithClipping(startMs, endMs)
            
            bottomSheetDialog.dismiss()
            Toast.makeText(this, "Trim operation added (preview active)", Toast.LENGTH_SHORT).show()
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    /**
     * Update the preview with ExoPlayer's native clipping.
     * This provides instant feedback without re-encoding.
     */
    private fun updatePreviewWithClipping(startMs: Long, endMs: Long) {
        if (videoUri != null) {
            val mediaItem = MediaItem.Builder()
                .setUri(videoUri!!)
                .setClippingProperties(
                    MediaItem.ClippingProperties.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(endMs)
                        .build()
                )
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            Log.d(TAG, "ExoPlayer clipping applied: $startMs - $endMs ms")
        }
    }

    /**
     * CROP ACTION: Add a crop operation to the project.
     */
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

    /**
     * TEXT ACTION: Add a text overlay operation to the project.
     */
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

    /**
     * MERGE ACTION: Merge additional videos with the current one.
     */
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
                    it.data?.let { selectedUri -> selectedVideoUris.add(selectedUri) }
                }

                if (selectedVideoUris.isNotEmpty()) {
                    viewModel.addMergeOperation(selectedVideoUris)
                    Toast.makeText(this, "Merge operation added (pending)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * AUDIO ACTION: Placeholder for audio operations (mute/add background audio).
     */
    private fun audioAction() {
        Toast.makeText(this, "Audio operations coming soon", Toast.LENGTH_SHORT).show()
    }

    /**
     * SAVE/EXPORT ACTION: Render all pending operations and save the final video.
     * This is where all the queued operations are finally rendered into a single FFmpeg command.
     */
    private fun saveAction() {
        val project = viewModel.project.value
        if (project == null) {
            showError("No project loaded")
            return
        }

        // If there are no operations, just copy the source
        if (!project.hasOperations()) {
            exportVideoFile(videoUri!!)
            return
        }

        lifecycleScope.launch {
            try {
                viewModel.startExport()

                val sourceFilePath = tempInputFile.absolutePath
                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }

                val outputFile = File(outputDir, "exported_video_${System.currentTimeMillis()}.mp4")
                val outputPath = outputFile.absolutePath

                // Build consolidated FFmpeg command
                val ffmpegCommand = viewModel.buildConsolidatedFFmpegCommand(sourceFilePath, outputPath)
                if (ffmpegCommand == null) {
                    viewModel.exportError("Failed to build FFmpeg command")
                    return@launch
                }

                Log.d(TAG, "Final FFmpeg command: $ffmpegCommand")

                // Execute the consolidated FFmpeg command
                val result = ffmpegEngine.exportFinal(ffmpegCommand)

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        viewModel.finishExport()
                        Toast.makeText(
                            this@VideoEditingActivity,
                            "Video exported successfully: ${result.outputPath}",
                            Toast.LENGTH_LONG
                        ).show()
                        Log.d(TAG, "Export successful: ${result.outputPath}")
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        viewModel.exportError(result.error)
                        Toast.makeText(
                            this@VideoEditingActivity,
                            "Export failed: ${result.error}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    FFmpegRenderEngine.RenderResult.Cancelled -> {
                        viewModel.exportError("Export cancelled")
                        Toast.makeText(
                            this@VideoEditingActivity,
                            "Export cancelled",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                viewModel.exportError(e.message ?: "Unknown error")
                Log.e(TAG, "Export error: ${e.message}", e)
            }
        }
    }

    /**
     * Export a video file to the Downloads folder (no operations applied).
     */
    private fun exportVideoFile(uri: Uri) {
        lifecycleScope.launch {
            try {
                viewModel.startExport()
                
                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }

                val outputFile = File(outputDir, "video_${System.currentTimeMillis()}.mp4")
                val sourceFile = File(tempInputFile.absolutePath)

                sourceFile.copyTo(outputFile, overwrite = true)

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
                    if (state == Player.STATE_READY) {
                        isVideoLoaded = true
                        customVideoSeeker.setVideoDuration(player.duration)
                        updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                        loadingScreen.visibility = View.GONE
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && isVideoLoaded) {
                        updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                    }
                }
            })

            initializeVideoData()
            
            // Initialize ViewModel with the video
            val displayName = videoUri!!.lastPathSegment ?: "video"
            viewModel.initializeProject(videoUri!!, displayName)
        } else {
            showError("Error loading video")
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
                        if (dataIndex != -1) {
                            filePath = it.getString(dataIndex)
                        } else {
                            Log.e("PathError", "Column '_data' not found in cursor")
                        }
                    } else {
                        Log.e("PathError", "Cursor is empty for URI: $uri")
                    }
                } ?: Log.e("PathError", "Cursor is null for URI: $uri")
            }
            "file" -> {
                filePath = uri.path
            }
            else -> {
                Log.e("PathError", "Unsupported URI scheme: ${uri.scheme}")
            }
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
                val frameCount = 10

                for (i in 0 until frameCount) {
                    val frameTime = (i * frameInterval)
                    val bitmap = retriever.getFrameAtTime(frameTime * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    bitmap?.let {
                        val processedBitmap = Bitmap.createScaledBitmap(it, 200, 150, false)
                        extractedFrames.add(processedBitmap)
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

        val currentFormatted = formatDuration(current)
        val totalFormatted = formatDuration(total)

        tvDuration.text = "$currentFormatted / $totalFormatted"
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
