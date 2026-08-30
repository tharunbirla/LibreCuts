package com.tharunbirla.librecuts.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tharunbirla.librecuts.R
import com.tharunbirla.librecuts.utils.AppEventManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

class ProxyGenerationService : Service() {

    private val TAG = "ProxyGenerationService"
    private val CHANNEL_ID = "ProxyChannel"
    private val NOTIFICATION_ID = 1002

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var ffmpegEngine: FFmpegRenderEngine

    companion object {
        const val ACTION_PROXY_GENERATED = "com.tharunbirla.librecuts.ACTION_PROXY_GENERATED"
        const val EXTRA_SOURCE_URI = "extra_source_uri"
        const val EXTRA_PROXY_URI = "extra_proxy_uri"
        const val EXTRA_DEPENDENCY_ID = "extra_dependency_id"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ffmpegEngine = FFmpegRenderEngine(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sourceUriStr = intent.getStringExtra(EXTRA_SOURCE_URI) ?: return START_NOT_STICKY
        val sourceUri = Uri.parse(sourceUriStr)
        val dependencyId = intent.getStringExtra(EXTRA_DEPENDENCY_ID) ?: return START_NOT_STICKY
        
        // Ensure cache directory exists for proxy
        val cacheDir = File(cacheDir, "proxies")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        
        val proxyFileName = "proxy_${System.currentTimeMillis()}.mp4"
        val proxyFile = File(cacheDir, proxyFileName)

        startForeground(NOTIFICATION_ID, buildNotification("Generating optimized playback proxy..."))

        serviceScope.launch {
            try {
                // Need to convert sourceUri to a real path for FFmpegKit if it's a content URI.
                // For simplicity we will assume FFmpegKit handles SAF via its SAF protocol if it is content://
                // or we use FFmpegKitConfig.getSafParameterForRead
                val sourcePath = getSafPath(sourceUri)

                val result = ffmpegEngine.generateScrubProxy(
                    sourceFilePath = sourcePath,
                    outputFilePath = proxyFile.absolutePath
                )

                when (result) {
                    is FFmpegRenderEngine.RenderResult.Success -> {
                        val proxyUri = Uri.fromFile(proxyFile)
                        Log.d(TAG, "Proxy generated successfully: $proxyUri")
                        broadcastSuccess(sourceUriStr, proxyUri.toString(), dependencyId)
                    }
                    is FFmpegRenderEngine.RenderResult.Failure -> {
                        Log.e(TAG, "Proxy generation failed: ${result.error}")
                        if (proxyFile.exists()) proxyFile.delete()
                    }
                    is FFmpegRenderEngine.RenderResult.Cancelled -> {
                        Log.w(TAG, "Proxy generation cancelled")
                        if (proxyFile.exists()) proxyFile.delete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy generation exception", e)
                if (proxyFile.exists()) proxyFile.delete()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }
    
    private fun getSafPath(uri: Uri): String {
        return if (uri.scheme == "content") {
            try {
                com.antonkarpenko.ffmpegkit.FFmpegKitConfig.getSafParameterForRead(this, uri)
            } catch (e: Exception) {
                // Fallback to caching if saf parameter fails
                uri.toString()
            }
        } else {
            uri.path ?: uri.toString()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Generation",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LibreCuts")
            .setContentText(title)
            .setSmallIcon(R.drawable.ic_save_24) // Reusing save icon
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
    }

    private fun broadcastSuccess(sourceUriStr: String, proxyUriStr: String, dependencyId: String) {
        serviceScope.launch {
            AppEventManager.sendEvent(AppEventManager.AppEvents.ProxyGenerated(sourceUriStr,proxyUriStr,dependencyId))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}
