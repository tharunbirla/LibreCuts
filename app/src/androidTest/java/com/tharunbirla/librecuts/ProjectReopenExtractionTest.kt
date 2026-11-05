package com.tharunbirla.librecuts

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.VideoProject
import com.tharunbirla.librecuts.viewmodels.VideoEditingViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProjectReopenExtractionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var image: File
    private lateinit var scenario: ActivityScenario<VideoEditingActivity>

    @Before
    fun openEditor() = runBlocking {
        image = File.createTempFile("reopen", ".png", context.cacheDir)
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        scenario = ActivityScenario.launch(
            Intent(context, VideoEditingActivity::class.java).putExtra("VIDEO_URI", Uri.fromFile(image))
        )
        awaitActivity { !field<Boolean>(it, "isImportLoading") && field<Int>(it, "activeExtractionCount") == 0 }
    }

    @After
    fun closeEditor() {
        if (::scenario.isInitialized) scenario.close()
        if (::image.isInitialized) image.delete()
    }

    @Test
    fun cancellingBeforeDispatchDoesNotRegisterExtraction(): Unit = runBlocking {
        val uri = Uri.fromFile(File(context.cacheDir, "cancel-before-dispatch.mp4"))
        lateinit var job: Job
        scenario.onActivity {
            val semaphore = field<Semaphore>(it, "frameExtractionSemaphore")
            assertTrue(semaphore.tryAcquire())
            assertTrue(semaphore.tryAcquire())
            try {
                job = extract(it, uri)!!
                job.cancel()
            } finally {
                semaphore.release()
                semaphore.release()
            }
        }
        withTimeout(5000) { job.join() }
        scenario.onActivity {
            assertFalse(field<Set<Uri>>(it, "activeExtractionUris").contains(uri))
            assertEquals(0, field<Int>(it, "activeExtractionCount"))
            assertEquals(2, field<Semaphore>(it, "frameExtractionSemaphore").availablePermits)
        }
    }

    @Test
    fun renderCancellationCleansBlockedOwnerWithoutLettingWaitersRemoveIt(): Unit = runBlocking {
        val uri = Uri.fromFile(File(context.cacheDir, "blocked-extraction.mp4"))
        lateinit var semaphore: Semaphore
        lateinit var owner: Job
        lateinit var cancelledWaiter: Job
        lateinit var waiter: Job
        scenario.onActivity {
            semaphore = field(it, "frameExtractionSemaphore")
            assertTrue(semaphore.tryAcquire())
            assertTrue(semaphore.tryAcquire())
            owner = extract(it, uri)!!
        }
        try {
            awaitActivity { field<Set<Uri>>(it, "activeExtractionUris").contains(uri) }
            scenario.onActivity {
                cancelledWaiter = extract(it, uri)!!
                waiter = extract(it, uri)!!
            }
            // Let both same-URI jobs enter their non-owning wait loops.
            scenario.onActivity { }
            cancelledWaiter.cancel()
            withTimeout(5000) { cancelledWaiter.join() }
            scenario.onActivity {
                assertTrue(field<Set<Uri>>(it, "activeExtractionUris").contains(uri))
                assertEquals(1, field<Int>(it, "activeExtractionCount"))
                field<MutableList<Job>>(it, "activeRenderJobs").add(owner)
                render(it, field<VideoEditingViewModel>(it, "viewModel").project.value!!)
            }
            withTimeout(5000) {
                owner.join()
                waiter.join()
            }
            scenario.onActivity {
                assertFalse(field<Set<Uri>>(it, "activeExtractionUris").contains(uri))
                assertEquals(0, field<Int>(it, "activeExtractionCount"))
                assertEquals(0, semaphore.availablePermits)
            }
        } finally {
            owner.cancel()
            semaphore.release()
            semaphore.release()
        }

        // Retrying the same unreadable URI must finish and return its acquired permit.
        lateinit var retry: Job
        scenario.onActivity { retry = extract(it, uri)!! }
        withTimeout(5000) { retry.join() }
        scenario.onActivity {
            assertFalse(field<Set<Uri>>(it, "activeExtractionUris").contains(uri))
            assertEquals(0, field<Int>(it, "activeExtractionCount"))
            assertEquals(2, semaphore.availablePermits)
        }
    }

    @Test
    fun cacheHitsAndRepeatedRendersPreserveClipsAndTrims(): Unit = runBlocking {
        scenario.onActivity { activity ->
            val uri = Uri.fromFile(image)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            field<LruCache<Uri, List<Bitmap>>>(activity, "frameCache").put(uri, listOf(bitmap))
            val semaphore = field<Semaphore>(activity, "frameExtractionSemaphore")
            assertTrue(semaphore.tryAcquire())
            assertTrue(semaphore.tryAcquire())
            try {
                val adapter = FrameAdapter(emptyList(), 20)
                assertNull(extract(activity, uri, adapter))
                val frames = FrameAdapter::class.java.getDeclaredField("frameBitmaps").apply { isAccessible = true }
                assertEquals(listOf(bitmap), frames.get(adapter))
                assertEquals(0, field<Int>(activity, "activeExtractionCount"))
                assertTrue(field<Set<Uri>>(activity, "activeExtractionUris").isEmpty())
                assertEquals(0, semaphore.availablePermits)
            } finally {
                semaphore.release()
                semaphore.release()
            }

            val viewModel = field<VideoEditingViewModel>(activity, "viewModel")
            val project = viewModel.project.value!!.copy(operations = listOf(
                EditOperation.Trim(500L, 4000L),
                EditOperation.Merge(List(3) {
                    EditOperation.MergeItem(uri, 5000L, trimStartMs = 1000L, trimEndMs = 3000L, isImage = true)
                })
            ))
            viewModel.loadProject(project)
            VideoEditingActivity::class.java.getDeclaredField("isImportLoading")
                .apply { isAccessible = true }.setBoolean(activity, true)
            field<View>(activity, "loadingScreen").visibility = View.VISIBLE
            repeat(3) { render(activity, project) }
            assertEquals(project, viewModel.project.value)
            assertEquals(4, field<List<View>>(activity, "activeSegmentViews").size)
        }
        awaitActivity { !field<Boolean>(it, "isImportLoading") }
        scenario.onActivity {
            assertEquals(View.GONE, field<View>(it, "loadingScreen").visibility)
            assertEquals(0, field<Int>(it, "activeExtractionCount"))
        }
    }

    private fun extract(activity: VideoEditingActivity, uri: Uri, adapter: FrameAdapter = FrameAdapter(emptyList(), 20)): Job? {
        val method = VideoEditingActivity::class.java.getDeclaredMethod(
            "extractFramesForSegment", Uri::class.java, Long::class.javaPrimitiveType, FrameAdapter::class.java
        ).apply { isAccessible = true }
        return method.invoke(activity, uri, 1000L, adapter) as Job?
    }

    private fun render(activity: VideoEditingActivity, project: VideoProject) {
        VideoEditingActivity::class.java.getDeclaredMethod("performRenderTracks", VideoProject::class.java)
            .apply { isAccessible = true }.invoke(activity, project)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: VideoEditingActivity, name: String): T =
        VideoEditingActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private suspend fun awaitActivity(predicate: (VideoEditingActivity) -> Boolean) {
        withTimeout(5000) {
            var ready = false
            while (!ready) {
                scenario.onActivity { ready = predicate(it) }
                if (!ready) delay(20)
            }
        }
    }
}
