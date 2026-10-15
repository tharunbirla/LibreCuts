package com.tharunbirla.librecuts

import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.ReturnCode
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.VideoProject
import com.tharunbirla.librecuts.services.FFmpegRenderEngine
import com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class ClipPromotionExportTest {
    @Test
    fun deletingFirstClipResolvesPromotedContentUriAndExports() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("clip-promotion-", ".mp4", context.cacheDir)
        val output = File.createTempFile("clip-export-", ".mp4", context.cacheDir)
        val generated = FFmpegKit.execute("-y -f lavfi -i testsrc2=size=128x96:rate=12:duration=1 -c:v libx264 -preset ultrafast \"${source.absolutePath}\"")
        assertTrue(generated.allLogsAsString, ReturnCode.isSuccess(generated.returnCode))
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        })!!
        var promotedFile: File? = null
        var activity: VideoEditingActivity? = null
        try {
            context.contentResolver.openOutputStream(uri)!!.use { sink -> source.inputStream().use { it.copyTo(sink) } }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            val inputField = VideoEditingActivity::class.java.getDeclaredField("tempInputFile").apply { isAccessible = true }
            val selectedField = VideoEditingActivity::class.java.getDeclaredField("selectedVideoIndex").apply { isAccessible = true }
            val deleteMethod = VideoEditingActivity::class.java.getDeclaredMethod("deleteSelectedVideo").apply { isAccessible = true }
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation
                .executeShellCommand("am start -W -n ${context.packageName}/.MainActivity")).use { it.readBytes() }
            context.startActivity(Intent(context, VideoEditingActivity::class.java)
                .putExtra("VIDEO_URI", Uri.fromFile(source)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeout(15_000) {
                while (activity == null) {
                    instrumentation.runOnMainSync {
                        activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                            .filterIsInstance<VideoEditingActivity>().firstOrNull()
                    }
                    delay(50)
                }
            }
            withTimeout(15_000) {
                var initialized = false
                while (!initialized) {
                    instrumentation.runOnMainSync { initialized = inputField.get(activity) != null }
                    delay(50)
                }
            }
            lateinit var model: VideoEditingViewModel
            instrumentation.runOnMainSync {
                model = ViewModelProvider(activity!!)[VideoEditingViewModel::class.java]
                model.loadProject(VideoProject(Uri.fromFile(source), source.name, operations = listOf(
                    EditOperation.Merge(listOf(EditOperation.MergeItem(uri, 1000, trimStartMs = 200, trimEndMs = 800)))
                )))
                selectedField.set(activity, 0)
                deleteMethod.invoke(activity)
            }
            withTimeout(15_000) {
                while (promotedFile == null) {
                    instrumentation.runOnMainSync {
                        val file = inputField.get(activity) as? File
                        if (file != source && file?.isFile == true) promotedFile = file
                    }
                    delay(50)
                }
            }
            assertEquals(uri, model.project.value!!.sourceUri)
            assertArrayEquals(source.readBytes(), promotedFile!!.readBytes())
            model.setExportSettings(360, 12, false)
            val command = model.buildConsolidatedFFmpegCommand(promotedFile!!.absolutePath, output.absolutePath, context = context)!!
            assertTrue(command, command.contains("-i \"${promotedFile!!.absolutePath}\""))
            val engine = FFmpegRenderEngine(context)
            try {
                val result = engine.exportFinal(command, 0.6)
                assertTrue(result.toString(), result is FFmpegRenderEngine.RenderResult.Success)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(output.absolutePath)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Duration $duration", duration in 500..750)
                } finally { retriever.release() }
            } finally { engine.cleanup() }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
            context.contentResolver.delete(uri, null, null)
            promotedFile?.delete()
            source.delete()
            output.delete()
        }
    }
}
