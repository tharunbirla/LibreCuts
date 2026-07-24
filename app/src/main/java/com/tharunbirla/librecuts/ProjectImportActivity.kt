package com.tharunbirla.librecuts

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.LruCache
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.EditRecipe
import com.tharunbirla.librecuts.utils.ProjectSerializer
import com.tharunbirla.librecuts.utils.setBounceClickListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectImportActivity : AppCompatActivity() {

    private val TAG = "ProjectImportActivity"

    // Top action bar
    private lateinit var btnClose: ImageView
    private lateinit var btnNext: com.google.android.material.button.MaterialButton

    // Hero Header
    private lateinit var tvProjectTitle: TextView
    private lateinit var tvProjectDuration: TextView
    private lateinit var tvProjectClipsCount: TextView
    private lateinit var tvProjectStatus: TextView

    // Main section
    private lateinit var tvOverallProgress: TextView

    // Row category headers & card containers
    private lateinit var cardPrimaryVideo: View
    private lateinit var layoutPrimaryHeader: View
    private lateinit var tvPrimaryCount: TextView
    private lateinit var cardMergedVideos: View
    private lateinit var layoutMergedHeader: View
    private lateinit var tvMergedCount: TextView
    private lateinit var cardAudioTracks: View
    private lateinit var layoutAudioHeader: View
    private lateinit var tvAudioCount: TextView
    private lateinit var cardOverlays: View
    private lateinit var layoutOverlaysHeader: View
    private lateinit var tvOverlaysCount: TextView

    // RecyclerViews
    private lateinit var rvPrimaryVideo: RecyclerView
    private lateinit var rvMergedVideos: RecyclerView
    private lateinit var rvAudioTracks: RecyclerView
    private lateinit var rvOverlays: RecyclerView

    private var projectUri: Uri? = null
    private var editRecipe: EditRecipe? = null

    private var dependencies = mutableListOf<MediaDependency>()
    private var currentReplacingDependencyId: String? = null
    private val thumbnailCache = LruCache<String, Bitmap>(30)

    enum class DependencyType { PRIMARY, MERGE, AUDIO, OVERLAY }

    data class MediaDependency(
        val id: String,
        val type: DependencyType,
        val originalUri: Uri,
        val name: String,
        var currentUri: Uri?,
        var isFound: Boolean = false,
        val operationId: String? = null,
        val mergeIndex: Int = -1,
        val requiredDurationMs: Long = 0L
    )

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to take persistable permission", e)
                }
                
                val depId = currentReplacingDependencyId
                if (depId != null) {
                    val dep = dependencies.find { it.id == depId }
                    if (dep != null) {
                        val uploadedDurationMs = getMediaDurationMs(uri)
                        val requiredDurationMs = dep.requiredDurationMs

                        if (requiredDurationMs > 0L && uploadedDurationMs > 0L && (uploadedDurationMs + 300L) < requiredDurationMs) {
                            val reqStr = formatDuration(requiredDurationMs)
                            val upStr = formatDuration(uploadedDurationMs)
                            Toast.makeText(
                                this,
                                "Clip duration ($upStr) is shorter than required ($reqStr). Please choose a clip at least $reqStr long.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            dep.currentUri = uri
                            dep.isFound = true
                            updateUi()
                        }
                    }
                }
            }
            currentReplacingDependencyId = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_import)

        // Top bar
        btnClose = findViewById(R.id.btnClose)
        btnNext = findViewById(R.id.btnNext)

        // Hero
        tvProjectTitle = findViewById(R.id.tvProjectTitle)
        tvProjectDuration = findViewById(R.id.tvProjectDuration)
        tvProjectClipsCount = findViewById(R.id.tvProjectClipsCount)
        tvProjectStatus = findViewById(R.id.tvProjectStatus)

        // Main section
        tvOverallProgress = findViewById(R.id.tvOverallProgress)

        // Headers & Cards
        cardPrimaryVideo = findViewById(R.id.cardPrimaryVideo)
        layoutPrimaryHeader = findViewById(R.id.layoutPrimaryHeader)
        tvPrimaryCount = findViewById(R.id.tvPrimaryCount)
        cardMergedVideos = findViewById(R.id.cardMergedVideos)
        layoutMergedHeader = findViewById(R.id.layoutMergedHeader)
        tvMergedCount = findViewById(R.id.tvMergedCount)
        cardAudioTracks = findViewById(R.id.cardAudioTracks)
        layoutAudioHeader = findViewById(R.id.layoutAudioHeader)
        tvAudioCount = findViewById(R.id.tvAudioCount)
        cardOverlays = findViewById(R.id.cardOverlays)
        layoutOverlaysHeader = findViewById(R.id.layoutOverlaysHeader)
        tvOverlaysCount = findViewById(R.id.tvOverlaysCount)

        // Recyclers
        rvPrimaryVideo = findViewById(R.id.rvPrimaryVideo)
        rvMergedVideos = findViewById(R.id.rvMergedVideos)
        rvAudioTracks = findViewById(R.id.rvAudioTracks)
        rvOverlays = findViewById(R.id.rvOverlays)

        btnClose.setBounceClickListener { finish() }
        btnNext.setBounceClickListener {
            if (btnNext.isEnabled) {
                loadProject()
            }
        }

        projectUri = intent.getParcelableExtra("PROJECT_URI")
        if (projectUri == null) {
            Toast.makeText(this, "No project URI provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        loadRecipe()
    }

    private fun loadRecipe() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openInputStream(projectUri!!)?.use { inputStream ->
                    val json = inputStream.bufferedReader().use { it.readText() }
                    editRecipe = ProjectSerializer.deserialize(json)
                }

                if (editRecipe != null) {
                    extractDependencies()
                    checkDependenciesExistence()
                    withContext(Dispatchers.Main) {
                        updateHeroMetadata()
                        setupRecyclerViews()
                        updateUi()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@ProjectImportActivity, "Failed to parse project", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading project", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ProjectImportActivity, "Error loading project", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun updateHeroMetadata() {
        val recipe = editRecipe ?: return
        val rawName = recipe.sourceName.ifBlank { "Import Project" }
        tvProjectTitle.text = rawName

        val primaryDur = dependencies.firstOrNull { it.type == DependencyType.PRIMARY }?.requiredDurationMs ?: 0L
        val mergeDur = dependencies.filter { it.type == DependencyType.MERGE }.sumOf { it.requiredDurationMs }
        val totalMs = primaryDur + mergeDur

        val formattedDur = formatDuration(totalMs).ifEmpty { "0.0s" }
        tvProjectDuration.text = "$formattedDur Total"
        tvProjectClipsCount.text = "${dependencies.size} clips"
    }

    private fun getMediaDurationMs(uri: Uri?): Long {
        if (uri == null) return 0L
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(pfd.fileDescriptor)
                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                retriever.release()
                durationStr?.toLong() ?: 0L
            } ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return ""
        return if (ms < 60000L) {
            val secsInt = (ms / 1000)
            val msRem = (ms % 1000) / 100
            if (msRem > 0) String.format("%.1fs", ms / 1000.0f) else "${secsInt}s"
        } else {
            val mins = (ms / 60000)
            val secs = ((ms % 60000) / 1000)
            String.format("%d:%02d", mins, secs)
        }
    }

    private fun extractDependencies() {
        dependencies.clear()
        val recipe = editRecipe ?: return

        // Primary Video
        val primaryTrim = recipe.operations.filterIsInstance<EditOperation.Trim>().firstOrNull()
        val primaryReqDuration = if (primaryTrim != null) {
            primaryTrim.endMs - primaryTrim.startMs
        } else {
            getMediaDurationMs(recipe.sourceUri)
        }

        dependencies.add(
            MediaDependency(
                id = "primary",
                type = DependencyType.PRIMARY,
                originalUri = recipe.sourceUri,
                name = recipe.sourceName,
                currentUri = recipe.sourceUri,
                requiredDurationMs = primaryReqDuration
            )
        )

        // Operations
        recipe.operations.forEach { op ->
            when (op) {
                is EditOperation.Merge -> {
                    op.items.forEachIndexed { index, item ->
                        val reqDur = if (item.trimmedDurationMs > 0L) item.trimmedDurationMs else item.trimEndMs - item.trimStartMs
                        dependencies.add(
                            MediaDependency(
                                id = "merge_${op.id}_$index",
                                type = DependencyType.MERGE,
                                originalUri = item.uri,
                                name = item.uri.lastPathSegment ?: "merge_video.mp4",
                                currentUri = item.uri,
                                operationId = op.id,
                                mergeIndex = index,
                                requiredDurationMs = if (reqDur > 0L) reqDur else getMediaDurationMs(item.uri)
                            )
                        )
                    }
                }
                is EditOperation.AddBackgroundAudio -> {
                    val reqDur = if (op.internalEndMs > op.internalStartMs) {
                        op.internalEndMs - op.internalStartMs
                    } else {
                        (op.endTimeMs ?: 0L) - (op.startTimeMs ?: 0L)
                    }
                    dependencies.add(
                        MediaDependency(
                            id = "audio_${op.id}",
                            type = DependencyType.AUDIO,
                            originalUri = op.audioUri,
                            name = op.audioUri.lastPathSegment ?: "audio.m4a",
                            currentUri = op.audioUri,
                            operationId = op.id,
                            requiredDurationMs = if (reqDur > 0L) reqDur else getMediaDurationMs(op.audioUri)
                        )
                    )
                }
                is EditOperation.AddImageOverlay -> {
                    val isVideo = op.imageUri.toString().endsWith(".mp4", true) || contentResolver.getType(op.imageUri)?.startsWith("video/") == true
                    val reqDur = if (isVideo) {
                        val durationFromTimes = (op.endTimeMs ?: 0L) - (op.startTimeMs ?: 0L)
                        if (durationFromTimes > 0L) durationFromTimes else getMediaDurationMs(op.imageUri)
                    } else {
                        0L // Image overlays have no minimum duration requirement
                    }
                    dependencies.add(
                        MediaDependency(
                            id = "overlay_${op.id}",
                            type = DependencyType.OVERLAY,
                            originalUri = op.imageUri,
                            name = op.imageUri.lastPathSegment ?: "overlay.png",
                            currentUri = op.imageUri,
                            operationId = op.id,
                            requiredDurationMs = reqDur
                        )
                    )
                }
                else -> {}
            }
        }
    }

    private fun checkDependenciesExistence() {
        dependencies.forEach { dep ->
            dep.isFound = checkUriExists(dep.currentUri)
        }
    }

    private fun checkUriExists(uri: Uri?): Boolean {
        if (uri == null) return false
        return try {
            if (uri.scheme == "file") {
                File(uri.path ?: "").exists()
            } else {
                contentResolver.openInputStream(uri)?.use { true } ?: false
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun setupRecyclerViews() {
        rvPrimaryVideo.adapter = DependencyAdapter(dependencies.filter { it.type == DependencyType.PRIMARY }) { dep ->
            replaceMedia(dep, "video/*")
        }
        
        val mergeDeps = dependencies.filter { it.type == DependencyType.MERGE }
        if (mergeDeps.isNotEmpty()) {
            layoutMergedHeader.visibility = View.VISIBLE
            rvMergedVideos.visibility = View.VISIBLE
            rvMergedVideos.adapter = DependencyAdapter(mergeDeps) { dep -> replaceMedia(dep, "video/*") }
        }

        val audioDeps = dependencies.filter { it.type == DependencyType.AUDIO }
        if (audioDeps.isNotEmpty()) {
            layoutAudioHeader.visibility = View.VISIBLE
            rvAudioTracks.visibility = View.VISIBLE
            rvAudioTracks.adapter = DependencyAdapter(audioDeps) { dep -> replaceMedia(dep, "audio/*") }
        }

        val overlayDeps = dependencies.filter { it.type == DependencyType.OVERLAY }
        if (overlayDeps.isNotEmpty()) {
            layoutOverlaysHeader.visibility = View.VISIBLE
            rvOverlays.visibility = View.VISIBLE
            rvOverlays.adapter = DependencyAdapter(overlayDeps) { dep -> replaceMedia(dep, "image/*", "video/*") }
        }
    }

    private fun replaceMedia(dep: MediaDependency, vararg mimeTypes: String) {
        currentReplacingDependencyId = dep.id
        pickMediaLauncher.launch(arrayOf(*mimeTypes))
    }

    private fun updateUi() {
        val totalCount = dependencies.size
        val foundTotal = dependencies.count { it.isFound }
        val missingCount = totalCount - foundTotal

        tvOverallProgress.text = if (missingCount == 0) "All $totalCount Ready" else "$foundTotal/$totalCount Selected"

        if (missingCount == 0) {
            tvOverallProgress.setTextColor(ContextCompat.getColor(this, R.color.colorSecondary))
            tvProjectStatus.text = "All files found. Ready to edit."
            tvProjectStatus.setTextColor(ContextCompat.getColor(this, R.color.colorSecondary))
            
            btnNext.isEnabled = true
            btnNext.alpha = 1.0f
            btnNext.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorPrimary))
        } else {
            tvOverallProgress.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            tvProjectStatus.text = "$missingCount file(s) missing. Tap clips below to replace."
            tvProjectStatus.setTextColor(ContextCompat.getColor(this, R.color.colorPrimary))
            
            btnNext.isEnabled = false
            btnNext.alpha = 0.4f
            btnNext.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.surfaceContainerHigh))
        }

        val primaryDeps = dependencies.filter { it.type == DependencyType.PRIMARY }
        tvPrimaryCount.text = "${primaryDeps.count { it.isFound }}/${primaryDeps.size}"
        cardPrimaryVideo.visibility = if (primaryDeps.isNotEmpty()) View.VISIBLE else View.GONE

        val mergedDeps = dependencies.filter { it.type == DependencyType.MERGE }
        tvMergedCount.text = "${mergedDeps.count { it.isFound }}/${mergedDeps.size}"
        cardMergedVideos.visibility = if (mergedDeps.isNotEmpty()) View.VISIBLE else View.GONE

        val audioDeps = dependencies.filter { it.type == DependencyType.AUDIO }
        tvAudioCount.text = "${audioDeps.count { it.isFound }}/${audioDeps.size}"
        cardAudioTracks.visibility = if (audioDeps.isNotEmpty()) View.VISIBLE else View.GONE

        val overlayDeps = dependencies.filter { it.type == DependencyType.OVERLAY }
        tvOverlaysCount.text = "${overlayDeps.count { it.isFound }}/${overlayDeps.size}"
        cardOverlays.visibility = if (overlayDeps.isNotEmpty()) View.VISIBLE else View.GONE

        rvPrimaryVideo.adapter?.notifyDataSetChanged()
        rvMergedVideos.adapter?.notifyDataSetChanged()
        rvAudioTracks.adapter?.notifyDataSetChanged()
        rvOverlays.adapter?.notifyDataSetChanged()
    }

    private fun loadProject() {
        lifecycleScope.launch(Dispatchers.IO) {
            val recipe = editRecipe ?: return@launch
            
            val primaryDep = dependencies.find { it.type == DependencyType.PRIMARY }
            val newSourceUri = primaryDep?.currentUri ?: recipe.sourceUri
            val newSourceName = primaryDep?.currentUri?.lastPathSegment ?: recipe.sourceName

            val newOps = recipe.operations.map { op ->
                when (op) {
                    is EditOperation.Merge -> {
                        val newItems = op.items.mapIndexed { index, item ->
                            val dep = dependencies.find { it.operationId == op.id && it.mergeIndex == index }
                            if (dep != null && dep.currentUri != null) item.copy(uri = dep.currentUri!!) else item
                        }
                        op.copy(items = newItems)
                    }
                    is EditOperation.AddBackgroundAudio -> {
                        val dep = dependencies.find { it.operationId == op.id }
                        if (dep != null && dep.currentUri != null) op.copy(audioUri = dep.currentUri!!) else op
                    }
                    is EditOperation.AddImageOverlay -> {
                        val dep = dependencies.find { it.operationId == op.id }
                        if (dep != null && dep.currentUri != null) op.copy(imageUri = dep.currentUri!!) else op
                    }
                    else -> op
                }
            }

            val updatedRecipe = recipe.copy(
                sourceUri = newSourceUri,
                sourceName = newSourceName,
                operations = newOps,
                lastModifiedAt = System.currentTimeMillis()
            )

            try {
                val json = ProjectSerializer.serialize(updatedRecipe)
                contentResolver.openOutputStream(projectUri!!, "wt")?.use { out ->
                    out.write(json.toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save updated project", e)
            }

            withContext(Dispatchers.Main) {
                val intent = Intent(this@ProjectImportActivity, VideoEditingActivity::class.java).apply {
                    putExtra("PROJECT_URI", projectUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
                finish()
            }
        }
    }

    private fun loadThumbnail(uri: Uri, type: DependencyType): Bitmap? {
        if (type == DependencyType.AUDIO) return null

        // 1. Try MediaMetadataRetriever via FileDescriptor (works for all video sources and video overlays)
        try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(pfd.fileDescriptor)
                val bmp = retriever.getFrameAtTime(1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime()
                retriever.release()
                if (bmp != null) return bmp
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaMetadataRetriever thumbnail extraction failed for $uri: ${e.message}")
        }

        // 2. Try Image Decoding (works for all image types: JPG, PNG, WEBP, GIF)
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 2
                }
                val bmp = BitmapFactory.decodeStream(input, null, options)
                if (bmp != null) return bmp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Image decode failed for $uri: ${e.message}")
        }

        return null
    }

    private fun loadThumbnailAsync(holder: DependencyAdapter.ViewHolder, item: MediaDependency) {
        val uri = item.currentUri ?: return
        val cacheKey = uri.toString()

        holder.ivIcon.tag = cacheKey
        val cachedBitmap = thumbnailCache.get(cacheKey)
        if (cachedBitmap != null) {
            holder.ivIcon.setImageBitmap(cachedBitmap)
            holder.ivIcon.imageTintList = null
            holder.ivIcon.colorFilter = null
            holder.ivIcon.scaleType = ImageView.ScaleType.CENTER_CROP
            return
        }

        setDefaultIcon(holder, item.type)

        lifecycleScope.launch(Dispatchers.IO) {
            val bitmap = loadThumbnail(uri, item.type)
            withContext(Dispatchers.Main) {
                if (holder.ivIcon.tag == cacheKey) {
                    if (bitmap != null) {
                        thumbnailCache.put(cacheKey, bitmap)
                        holder.ivIcon.setImageBitmap(bitmap)
                        holder.ivIcon.imageTintList = null
                        holder.ivIcon.colorFilter = null
                        holder.ivIcon.scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        setDefaultIcon(holder, item.type)
                    }
                }
            }
        }
    }

    private fun setDefaultIcon(holder: DependencyAdapter.ViewHolder, type: DependencyType) {
        val iconRes = when (type) {
            DependencyType.PRIMARY, DependencyType.MERGE -> R.drawable.ic_play_24
            DependencyType.AUDIO -> R.drawable.ic_audio_24
            DependencyType.OVERLAY -> R.drawable.ic_image_24
        }
        holder.ivIcon.setImageResource(iconRes)
        holder.ivIcon.scaleType = ImageView.ScaleType.CENTER
        holder.ivIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.iconSecondary)
    }

    // --- Adapter ---
    inner class DependencyAdapter(
        private val items: List<MediaDependency>,
        private val onClick: (MediaDependency) -> Unit
    ) : RecyclerView.Adapter<DependencyAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvFileName: TextView = view.findViewById(R.id.tvFileName)
            val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            val ivStatusBadge: ImageView = view.findViewById(R.id.ivStatusBadge)
            val tvDuration: TextView = view.findViewById(R.id.tvDuration)
            val rootLayout: View = view
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_dependency, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvFileName.text = item.name

            val formattedDur = formatDuration(item.requiredDurationMs)
            if (formattedDur.isNotEmpty()) {
                holder.tvDuration.text = formattedDur
                holder.tvDuration.visibility = View.VISIBLE
            } else {
                holder.tvDuration.visibility = View.GONE
            }

            if (item.isFound && item.currentUri != null) {
                holder.ivStatusBadge.setImageResource(R.drawable.ic_check_24)
                holder.ivStatusBadge.setColorFilter(ContextCompat.getColor(this@ProjectImportActivity, R.color.onPrimaryContainer))
                holder.ivStatusBadge.background = ContextCompat.getDrawable(this@ProjectImportActivity, R.drawable.circle_primary_container)

                loadThumbnailAsync(holder, item)
            } else {
                holder.ivStatusBadge.setImageResource(R.drawable.ic_close_24)
                holder.ivStatusBadge.setColorFilter(ContextCompat.getColor(this@ProjectImportActivity, R.color.colorOnPrimary))
                
                val bg = ContextCompat.getDrawable(this@ProjectImportActivity, R.drawable.circle_primary_container)?.mutate()
                bg?.setTint(ContextCompat.getColor(this@ProjectImportActivity, R.color.colorPrimary))
                holder.ivStatusBadge.background = bg

                setDefaultIcon(holder, item.type)
            }

            holder.rootLayout.setBounceClickListener {
                onClick(item)
            }
        }

        override fun getItemCount() = items.size
    }
}
