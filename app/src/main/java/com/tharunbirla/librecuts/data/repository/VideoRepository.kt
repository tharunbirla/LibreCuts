package com.tharunbirla.librecuts.data.repository

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoRepository(private val context: Context) {

    suspend fun getRealFilePath(uri: Uri): String? = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext uri.path

        val projection = arrayOf(MediaStore.Video.Media.DATA)
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                    return@use cursor.getString(columnIndex)
                }
            }
        } catch (e: Exception) {
            Log.e("VideoRepository", "Error getting file path", e)
        }
        return@withContext null
    }

    suspend fun executeFFmpeg(command: String): Boolean = withContext(Dispatchers.IO) {
        val session = FFmpegKit.execute(command)
        if (ReturnCode.isSuccess(session.returnCode)) {
            Log.d("FFmpeg", "Success: ${session.output}")
            true
        } else {
            Log.e("FFmpeg", "Failed: ${session.failStackTrace}")
            false
        }
    }

    suspend fun getVideoDuration(uri: Uri): Long = withContext(Dispatchers.IO) {
         try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val time = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            time?.toLong() ?: 0L
        } catch (e: Exception) {
            Log.e("VideoRepository", "Error getting duration", e)
            0L
        }
    }
}
