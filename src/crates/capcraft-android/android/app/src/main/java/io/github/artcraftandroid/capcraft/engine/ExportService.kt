package io.github.artcraftandroid.capcraft.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app alive while a video exports with the app in the background, as a foreground
 * service whose notification shows how far the export has got. It does no work of its own: the
 * engine exports on its own thread, and this is started with the export and stopped as it ends.
 */
class ExportService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = running(this, 0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, notification)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 41
        private const val DONE_ID = 42
        private const val CHANNEL = "export"

        /** The last share shown, so the notification is redrawn every few percent rather than every frame. */
        @Volatile private var shown = -1

        private fun channel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** Opens the app where it was, from a notification. */
        private fun opening(context: Context): PendingIntent? {
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun running(context: Context, fraction: Float) = NotificationCompat.Builder(context, CHANNEL.also { channel(context) })
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Exporting video")
            .setContentText("${(fraction * 100).toInt()}%")
            .setProgress(100, (fraction * 100).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(opening(context))
            .build()

        /** Starts keeping the app alive for an export. */
        fun start(context: Context) {
            shown = -1
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java)) }
        }

        /** Shows how far the export has got, every few percent. */
        fun progress(context: Context, fraction: Float) {
            val percent = (fraction * 100).toInt()
            if (percent < shown + 2 && percent < 100) return
            shown = percent
            runCatching { NotificationManagerCompat.from(context).notify(ID, running(context, fraction)) }
        }

        /** Stops keeping the app alive; with [message], leaves a notification saying how it ended. */
        fun finish(context: Context, message: String?) {
            runCatching { context.stopService(Intent(context, ExportService::class.java)) }
            if (message == null) return
            runCatching {
                channel(context)
                val done = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                    .setContentTitle(message)
                    .setAutoCancel(true)
                    .setContentIntent(opening(context))
                    .build()
                NotificationManagerCompat.from(context).notify(DONE_ID, done)
            }
        }
    }
}
