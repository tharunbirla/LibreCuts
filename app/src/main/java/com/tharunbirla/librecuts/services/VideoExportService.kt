package com.tharunbirla.librecuts.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.tharunbirla.librecuts.models.VideoProject

/**
 * VideoExportService
 * Lightweight export service that runs export work on a background thread and
 * broadcasts progress/success/failure via LocalBroadcastManager.
 *
 * NOTE: This is intentionally engine-agnostic — integrate FFmpeg/FFmpegRenderEngine
 * or your preferred render backend inside the runExport(...) stub.
 */
class VideoExportService : Service() {

    companion object {
        const val ACTION_EXPORT_PROGRESS = "com.tharunbirla.librecuts.ACTION_EXPORT_PROGRESS"
        const val ACTION_EXPORT_SUCCESS = "com.tharunbirla.librecuts.ACTION_EXPORT_SUCCESS"
        const val ACTION_EXPORT_FAILURE = "com.tharunbirla.librecuts.ACTION_EXPORT_FAILURE"

        const val EXTRA_PROGRESS = "EXTRA_PROGRESS"
        const val EXTRA_SAVED_PATH = "EXTRA_SAVED_PATH"
        const val EXTRA_ERROR = "EXTRA_ERROR"

        const val EXTRA_PROJECT = "EXTRA_PROJECT"
        const val EXTRA_OUTPUT_PATH = "EXTRA_OUTPUT_PATH"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val project = intent?.getSerializableExtra(EXTRA_PROJECT) as? VideoProject
        val outputPath = intent?.getStringExtra(EXTRA_OUTPUT_PATH)

        if (project == null || outputPath.isNullOrBlank()) {
            sendFailure("Missing project or output path")
            stopSelf()
            return START_NOT_STICKY
        }

        Thread {
            try {
                sendProgress(0)

                // TODO: Replace the following stub with integration to FFmpegRenderEngine
                // or other render backend. For now we simulate progress updates.
                runExportStub(applicationContext, project, outputPath) { progress ->
                    sendProgress(progress)
                }

                sendSuccess(outputPath)
            } catch (e: Exception) {
                Log.e("VideoExportService", "Export failed", e)
                sendFailure(e.message ?: "Unknown export error")
            } finally {
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun runExportStub(context: Context, project: VideoProject, outputPath: String, onProgress: (Int) -> Unit) {
        // This is a simple simulation. Replace with actual render pipeline.
        try {
            for (p in 1..10) {
                Thread.sleep(300)
                onProgress(p * 10)
            }
            // simulate writing output
            val outFile = java.io.File(outputPath)
            outFile.parentFile?.mkdirs()
            outFile.writeText("Rendered by LibreCuts (stub) for project ${project.sourceName}")
        } catch (e: InterruptedException) {
            throw e
        }
    }

    private fun sendProgress(progress: Int) {
        val intent = Intent(ACTION_EXPORT_PROGRESS).apply {
            putExtra(EXTRA_PROGRESS, progress)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendSuccess(savedPath: String) {
        val intent = Intent(ACTION_EXPORT_SUCCESS).apply {
            putExtra(EXTRA_SAVED_PATH, savedPath)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendFailure(error: String) {
        val intent = Intent(ACTION_EXPORT_FAILURE).apply {
            putExtra(EXTRA_ERROR, error)
        }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}
