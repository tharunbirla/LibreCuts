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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.airbnb.lottie.LottieAnimationView
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.slider.RangeSlider
import com.tharunbirla.librecuts.customviews.CustomVideoSeeker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale


@Suppress("DEPRECATION")
class VideoEditingActivity : AppCompatActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var playerView: StyledPlayerView
    private lateinit var tvDuration: TextView
    private lateinit var frameRecyclerView: RecyclerView
    private lateinit var customVideoSeeker: CustomVideoSeeker
    private var videoUri: Uri? = null
    private var videoFileName: String = ""
    private lateinit var tempInputFile: File
    private lateinit var loadingScreen: View
    private lateinit var lottieAnimationView: LottieAnimationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_editing)
        loadingScreen = findViewById(R.id.loadingScreen)
        lottieAnimationView = findViewById(R.id.lottieAnimation)
        try {
            lottieAnimationView.playAnimation()
        } catch (e: Exception) {
            Log.e("LottieError", "Error loading Lottie animation: ${e.message}")
            // Handle the error gracefully
        }

        // Initialize UI components and setup the player
        initializeViews()
        setupExoPlayer()
        setupCustomSeeker()
        setupFrameRecyclerView()
    }

    private fun initializeViews() {
        playerView = findViewById(R.id.playerView)
        tvDuration = findViewById(R.id.tvDuration)
        frameRecyclerView = findViewById(R.id.frameRecyclerView)
        customVideoSeeker = findViewById(R.id.customVideoSeeker)

        // Set up button click listeners
        findViewById<ImageButton>(R.id.btnHome).setOnClickListener { onBackPressedDispatcher.onBackPressed()}
        findViewById<ImageButton>(R.id.btnSave).setOnClickListener { saveAction() }
        findViewById<ImageButton>(R.id.btnDel).setOnClickListener { deleteAction() }
        findViewById<ImageButton>(R.id.btnTrim).setOnClickListener { trimAction() }
        findViewById<ImageButton>(R.id.btnOverlay).setOnClickListener { overlayAction() }
        findViewById<ImageButton>(R.id.btnText).setOnClickListener { textAction() }
        findViewById<ImageButton>(R.id.btnAudio).setOnClickListener { audioAction() }
        findViewById<ImageButton>(R.id.btnCrop).setOnClickListener { cropAction() }
        findViewById<ImageButton>(R.id.btnMerge).setOnClickListener { mergeAction() }
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == PICK_VIDEO_REQUEST && resultCode == Activity.RESULT_OK) {
            data?.let {
                val currentVideoUri = videoUri // The current video URI
                val selectedVideoUris = mutableListOf<Uri>()

                if (it.clipData != null) {
                    val itemCount = it.clipData!!.itemCount
                    for (i in 0 until itemCount) {
                        selectedVideoUris.add(it.clipData!!.getItemAt(i).uri)
                    }
                } else {
                    it.data?.let { selectedUri -> selectedVideoUris.add(selectedUri) }
                }

                if (currentVideoUri != null) {
                    mergeVideos(currentVideoUri, selectedVideoUris)
                } // Pass the URIs to mergeVideos
            }
        }
    }

    private fun mergeVideos(currentVideoUri: Uri, selectedVideoUris: List<Uri>) {
        lifecycleScope.launch {
            try {
                // Fetch metadata for the current video
                val currentMedia = getVideoMetadata(this@VideoEditingActivity, currentVideoUri)
                val currentInputPath = currentMedia.uri.toString()

                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!outputDir.exists()) {
                    outputDir.mkdirs()
                }

                val outputPath = File(outputDir, "merged_video_${System.currentTimeMillis()}.mp4").absolutePath
                Log.d("MergeAction", "Output file path: $outputPath")

                // Create a temporary file to store the list of input files
                val listFile = File(cacheDir, "videolist.txt")
                val builder = StringBuilder().append("file '$currentInputPath'\n")
                for (uri in selectedVideoUris) {
                    val selectedMedia = getVideoMetadata(this@VideoEditingActivity, uri)
                    val selectedInputPath = selectedMedia.uri.toString()
                    builder.append("file '$selectedInputPath'\n")
                }
                listFile.writeText(builder.toString())

                // Build the FFmpeg command for merging videos
                val command = "-f concat -safe 0 -i ${listFile.absolutePath} -c:v copy -c:a copy $outputPath"
                Log.d("MergeCommand", "FFmpeg command: $command")

                lifecycleScope.launch {
                    try {
                        // Execute the command in a background thread
                        val session = withContext(Dispatchers.IO) {
                            FFmpegKit.execute(command)
                        }

                        val state = session.state
                        val returnCode = session.returnCode

                        Log.d("FFmpegSession", "FFmpeg process exited with state $state and rc $returnCode.")

                        if (ReturnCode.isSuccess(returnCode)) {
                            Toast.makeText(this@VideoEditingActivity, "Videos merged successfully!", Toast.LENGTH_SHORT).show()

                            // Update video URI to the merged video
                            videoUri = Uri.parse(outputPath)
                            refreshPlayer() // Refresh player with new video
                            refreshUI()     // Refresh UI
                        } else {
                            Log.e("FFmpegError", "Error merging videos: ${session.failStackTrace}")
                            Toast.makeText(this@VideoEditingActivity, "Error merging videos.", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        Log.e("FFmpegError", "Exception during FFmpeg execution: ${e.message}")
                        Toast.makeText(this@VideoEditingActivity, "Error merging videos: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }



            } catch (e: Exception) {
                Log.e("MetadataError", "Error fetching video metadata: ${e.message}")
                Toast.makeText(this@VideoEditingActivity, "Error fetching video metadata: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun cropAction() {
        // Create BottomSheetDialog
        val bottomSheetDialog = BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.crop_bottom_sheet_dialog, null)

        // Set title
        sheetView.findViewById<TextView>(R.id.tvTitleCrop).text = "Select Aspect Ratio"

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
        // Retrieve the video URI from the intent
        val videoUri = intent.getParcelableExtra<Uri>("VIDEO_URI")
        if (videoUri == null) {
            Toast.makeText(this, "Error retrieving video URI", Toast.LENGTH_SHORT).show()
            return
        }

        // Fetch video metadata asynchronously to get the file path
        lifecycleScope.launch {
            try {
                val media = getVideoMetadata(this@VideoEditingActivity, videoUri)
                val inputPath = media.uri.toString() // Get the actual file path
                val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

                if (!outputDir.exists()) {
                    outputDir.mkdirs() // Create output directory if it doesn't exist
                }

                val outputPath = File(outputDir, "cropped_${System.currentTimeMillis()}.mp4").absolutePath
                Log.d("CropAction", "Output file path: $outputPath")

                // Define crop parameters based on aspect ratio
                    val cropCommand = when (aspectRatio) {
                        "16:9" -> "-vf \"crop=iw:iw*9/16\""
                        "9:16" -> "-vf \"crop=ih*9/16:ih\""
                        "1:1" -> "-vf \"crop=ih:ih\""
                        else -> return@launch
                    }

                // Build the FFmpeg command correctly
                val command = "-i \"$inputPath\" $cropCommand -c:a copy \"$outputPath\""
                Log.d("FFmpegCommand", "FFmpeg command: $command")

                executeFFmpegCommand(command, outputPath)

            } catch (e: Exception) {
                Log.e("MetadataError", "Error fetching video metadata: ${e.message}")
                Toast.makeText(this@VideoEditingActivity, "Error fetching video metadata: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun audioAction() {
        TODO("Not yet implemented")
    }

    private fun textAction() {
        TODO("Not yet implemented")
    }

    private fun overlayAction() {
        TODO("Not yet implemented")
    }

    private fun deleteAction() {
        // Placeholder for future implementation of delete/undo action
    }

    private fun trimAction() {
        val videoDuration = player.duration

        // Validate the video duration
        if (videoDuration <= 0) {
            Toast.makeText(this, "Video duration is invalid.", Toast.LENGTH_SHORT).show()
            return
        }

        // Create BottomSheetDialog
        val bottomSheetDialog = BottomSheetDialog(this@VideoEditingActivity)
        val sheetView = layoutInflater.inflate(R.layout.trim_bottom_sheet_dialog, null)

        val rangeSlider: RangeSlider = sheetView.findViewById(R.id.rangeSlider)

        // Convert duration to minutes and seconds
        val durationInMillis: Long = videoDuration
        val totalMinutes = (durationInMillis / 60000).toInt()
        val totalSeconds = ((durationInMillis % 60000) / 1000).toInt()

        // Format as float for the RangeSlider (00.00)
        val formattedValueTo = (totalMinutes * 60 + totalSeconds).toFloat() // Total seconds as float

        rangeSlider.valueFrom = 0f
        rangeSlider.valueTo = formattedValueTo
        rangeSlider.values = listOf(0f, formattedValueTo) // Set initial range

        // Log the values for debugging
        Log.d("RangeSlider", "Value from: ${rangeSlider.valueFrom}, Value to: ${rangeSlider.valueTo}")

        rangeSlider.addOnChangeListener { slider, value, fromUser ->
            val start = slider.values[0].toLong() * 1000 // Convert to milliseconds
            val end = slider.values[1].toLong() * 1000 // Convert to milliseconds

            // Update the player’s playback position based on the start value
            if (fromUser) {
                if (value == slider.values[0]) {
                    player.seekTo(start)
                }
                else if (value == slider.values[1]) {
                    player.seekTo(end)
                }
            }
        }

        // Set up button listeners
        sheetView.findViewById<Button>(R.id.btnDoneTrim).setOnClickListener {
            trimVideo(rangeSlider.values[0].toLong(), rangeSlider.values[1].toLong())
        }

        bottomSheetDialog.setContentView(sheetView)
        bottomSheetDialog.show()
    }

    private fun trimVideo(trimBeginingTime: Long, trimEndTime: Long) {
        lifecycleScope.launch {
            val media = videoUri?.let { getVideoMetadata(this@VideoEditingActivity, it) }
            val realFilePath = media?.uri.toString()

            val outputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!outputDir.exists()) {
                outputDir.mkdirs() // Create output directory if it doesn't exist
            }
            val outputPath = File(outputDir, "trimmed_video_${System.currentTimeMillis()}.mp4").absolutePath
            Log.d("TrimAction", "Output file path: $outputPath")
            val command = "-ss $trimBeginingTime -i \"$realFilePath\" -to $trimEndTime -c copy \"$outputPath\""
            Log.d("FFmpegCommand", "FFmpeg command: $command")
            executeFFmpegCommand(command, outputPath)
        }
    }

    private fun executeFFmpegCommand(command: String, outputPath: String) {
        lifecycleScope.launch {
            try {
                FFmpegKit.executeAsync(command) { session ->
                    runOnUiThread {
                        if (ReturnCode.isSuccess(session.returnCode)) {
                            Log.d("EditSuccess", "Video edited successfully!")
                            Toast.makeText(this@VideoEditingActivity, "Video edited successfully!", Toast.LENGTH_SHORT).show()
                            videoUri = Uri.parse(outputPath) // Update video URI to the trimmed video
                            refreshPlayer() // Refresh player with new video
                            refreshUI() // Update UI components
                        } else {
                            Log.e("TrimError", "Error trimming video: ${session.returnCode}")
                            Toast.makeText(this@VideoEditingActivity, "Error trimming video: ${session.returnCode}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("excuteFFmpegCommandError", "Exception during FFmpeg execution: ${e.message}")
            }
        }
    }

    private fun refreshUI() {
        // Update UI elements based on the player's current state
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    customVideoSeeker.setVideoDuration(player.duration)
                    updateDurationDisplay(player.currentPosition.toInt(), player.duration.toInt())
                    extractVideoFrames() // Refresh frame list for the trimmed video
                }
            }
        })

        // Call to extract frames for display
        extractVideoFrames()
    }

    private fun refreshPlayer() {
        player.release() // Release the current player instance

        player = ExoPlayer.Builder(this).build().apply {
            playerView.player = this // Bind player to the player view
            setMediaItem(MediaItem.fromUri(videoUri!!)) // Set the new media item
            prepare() // Prepare the player
            playWhenReady = false // Start playback automatically
            seekTo(0) // Seek to the start of the video
        }

        // Update the custom seeker to reflect the new video's duration
        customVideoSeeker.setVideoDuration(player.duration)
        updateDurationDisplay(0, player.duration.toInt()) // Reset duration display
    }


    private fun saveAction() {
        // Placeholder for future implementation of save functionality
    }

    private fun setupExoPlayer() {
        videoUri = intent.getParcelableExtra("VIDEO_URI") // Retrieve the video URI from intent
        if (videoUri != null) {
            player = ExoPlayer.Builder(this).build()
            playerView.player = player // Bind player to the view

            val mediaItem = MediaItem.fromUri(videoUri!!)
            player.setMediaItem(mediaItem) // Set media item for the player

            // Show loading screen while preparing the video
            loadingScreen.visibility = View.VISIBLE

            player.prepare() // Prepare the player for playback

            // Get the actual file path from the URI
            val videoFilePath = getFilePathFromUri(videoUri!!)
            Log.d("Path","File path: $videoFilePath")
            if (videoFilePath != null) {
                tempInputFile = File(videoFilePath) // Set temporary input file to the real file path
                videoFileName = File(videoFilePath).name // Store the file name

                // Fetch video metadata asynchronously
                lifecycleScope.launch {
                    try {
                        val media = getVideoMetadata(this@VideoEditingActivity, videoUri!!)
                        Log.d("MetadataSuccess", "Media: $media")

                        // Call to extract frames for display after loading
                        extractVideoFrames() // Extract frames after metadata is fetched

                    } catch (e: Exception) {
                        Log.e("MetadataError", "Error fetching video metadata: ${e.message}")
                        Toast.makeText(this@VideoEditingActivity, "Error fetching video metadata: ${e.message}", Toast.LENGTH_SHORT).show()
                    } finally {
//                        // Hide loading screen after 5 seconds
//                        Handler(Looper.getMainLooper()).postDelayed({
//                            loadingScreen.visibility = View.GONE
//                        }, 500) // 5000 milliseconds = 5 seconds
                    }
                }
            } else {
                Log.e("VideoLoadError", "Error loading video")
                Toast.makeText(this, "Error loading video", Toast.LENGTH_SHORT).show()
                return
            }

            // Add listener for player events
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        customVideoSeeker.setVideoDuration(player.duration) // Set duration on seeker
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        val currentPosition = player.currentPosition.toInt()
                        updateDurationDisplay(currentPosition, player.duration.toInt()) // Update displayed duration
                    }
                }
            })
        } else {
            Log.e("VideoLoadError", "Error loading video")
            Toast.makeText(this, "Error loading video", Toast.LENGTH_SHORT).show()
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
                            filePath = it.getString(dataIndex) // Fetch file path
                        } else {
                            Log.e("PathError", "Column '_data' not found in cursor")
                        }
                    } else {
                        Log.e("PathError", "Cursor is empty for URI: $uri")
                    }
                } ?: Log.e("PathError", "Cursor is null for URI: $uri")
            }
            "file" -> {
                filePath = uri.path // Directly get path from URI
            }
            else -> {
                Log.e("PathError", "Unsupported URI scheme: ${uri.scheme}")
            }
        }

        Log.d("PathInfo", "File path: $filePath")
        return filePath
    }


    private fun setupCustomSeeker() {
        // Configure the custom video seeker for seeking playback
        customVideoSeeker.onSeekListener = { seekPosition ->
            val newSeekTime = (player.duration * seekPosition).toLong()

            // Ensure new seek time is within valid bounds
            if (newSeekTime >= 0 && newSeekTime <= player.duration) {
                player.seekTo(newSeekTime) // Seek to new position
                updateDurationDisplay(newSeekTime.toInt(), player.duration.toInt()) // Update duration display
            } else {
                Log.d("SeekError", "Seek position out of bounds.")
            }
        }
    }

    private fun setupFrameRecyclerView() {
        frameRecyclerView.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        frameRecyclerView.adapter = FrameAdapter(emptyList()) // Initialize frame adapter with video name
    }

    private fun extractVideoFrames() {
        lifecycleScope.launch(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(tempInputFile.absolutePath)

            val duration = withContext(Dispatchers.Main) { player.duration }
            val frameInterval = duration / 10 // Extract fewer frames

            val frameBitmaps = mutableListOf<Bitmap>()
            val frameCount = 10 // Adjust to change how many frames you extract

            try {
                for (i in 0 until frameCount) {
                    val frameTime = (i * frameInterval) // Time in microseconds
                    val bitmap = retriever.getFrameAtTime(frameTime * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    bitmap?.let {
                        val processedBitmap = Bitmap.createScaledBitmap(it, 200, 150, false)
                        frameBitmaps.add(processedBitmap)
                    }
                }
            } finally {
                retriever.release()
            }

            withContext(Dispatchers.Main) {
                frameRecyclerView.adapter = FrameAdapter(frameBitmaps)
                // Update Loading screen after completion
                loadingScreen.visibility = View.GONE
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDurationDisplay(current: Int, total: Int) {
        Log.d("VideoEditingActivity", "Current: $current, Total: $total")

        // Format and display the current and total duration
        val currentFormatted = String.format(Locale.getDefault(),"%02d:%02d", current / 60000, (current / 1000) % 60)
        val totalFormatted = String.format(Locale.getDefault(),"%02d:%02d", total / 60000, (total / 1000) % 60)

        Log.d("DurationDisplay", "Current: $currentFormatted / Total: $totalFormatted")

        tvDuration.text = "$currentFormatted / $totalFormatted" // Update duration text view
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release() // Release player resources on destruction
    }

    /**
     * Fetch video metadata based on URI.
     *
     * @param context The context to access content resolver
     * @param uri The URI of the video
     * @return Media object containing metadata
     */
    private suspend fun getVideoMetadata(context: Context, uri: Uri): Media {
        return withContext(Dispatchers.IO) {
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }

            // Define projection for querying video metadata
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DATA // Fetch the real file path
            )

            val selection = "${MediaStore.Video.Media._ID} = ?"
            val selectionArgs = arrayOf(uri.lastPathSegment)

            context.contentResolver.query(
                contentUri,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val displayNameColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeTypeColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dataColumnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

                if (cursor.moveToFirst()) {
                    // Retrieve metadata from cursor
                    val fileName = cursor.getString(displayNameColumnIndex)
                    val size = cursor.getLong(sizeColumnIndex)
                    val mimeType = cursor.getString(mimeTypeColumnIndex)
                    val realFilePath = cursor.getString(dataColumnIndex)

                    // Log metadata for debugging
                    Log.d(TAG, "File Name: $fileName")
                    Log.d(TAG, "File Size: $size bytes")
                    Log.d(TAG, "MIME Type: $mimeType")
                    Log.d("MetadataInfo", "File Name: $fileName, Size: $size bytes, MIME Type: $mimeType, Real File Path: $realFilePath")


                    // Return Media object containing the metadata
                    return@use Media(Uri.parse(realFilePath), fileName, size, mimeType)
                } else {
                    Log.e("MetadataError", "cursor.moveToFirst() returned false")
                    throw Error("cursor.moveToFirst() method returned false")
                }
            } ?: run {
                Log.e("MetadataError", "Unexpected null from contentResolver query")
                throw Error("Unexpected null returned by contentResolver query")
            }
        }
    }

    data class Media(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String
    )

    companion object {
        private const val TAG = "VideoMetadata"
        private const val PICK_VIDEO_REQUEST = 1
    }
}