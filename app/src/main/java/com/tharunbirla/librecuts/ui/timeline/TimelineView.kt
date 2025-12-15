package com.tharunbirla.librecuts.ui.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.core.view.ViewCompat
import com.tharunbirla.librecuts.data.model.Clip
import kotlin.math.abs

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.LTGRAY
        textSize = 30f
        textAlign = Paint.Align.CENTER
    }

    // Config
    private var pixelsPerSecond = 100f // Zoom level
    private val rulerHeight = 60f
    private val tickHeight = 20f

    // State
    private var scrollXOffset = 0f
    private var maxScrollX = 0f
    private var clips: List<Clip> = emptyList()

    // Scrolling
    private val scroller = OverScroller(context)
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            scrollXOffset += distanceX
            clampScroll()
            invalidate()
            notifyTimeChanged()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            scroller.fling(
                scrollXOffset.toInt(), 0,
                (-velocityX).toInt(), 0,
                0, maxScrollX.toInt(),
                0, 0
            )
            ViewCompat.postInvalidateOnAnimation(this@TimelineView)
            return true
        }
    })

    var onTimeChangedListener: ((Long) -> Unit)? = null
    var isUserInteracting = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            isUserInteracting = true
            scroller.forceFinished(true)
        } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            isUserInteracting = false
        }
        return gestureDetector.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollXOffset = scroller.currX.toFloat()
            clampScroll()
            invalidate()
            notifyTimeChanged()
            ViewCompat.postInvalidateOnAnimation(this)
        }
    }

    private fun clampScroll() {
        if (scrollXOffset < 0) scrollXOffset = 0f
        if (scrollXOffset > maxScrollX) scrollXOffset = maxScrollX
    }

    private fun notifyTimeChanged() {
        val timeMs = (scrollXOffset / pixelsPerSecond * 1000).toLong()
        onTimeChangedListener?.invoke(timeMs)
    }

    fun setClips(newClips: List<Clip>) {
        clips = newClips
        val totalDurationMs = clips.sumOf { it.playbackDuration }
        maxScrollX = (totalDurationMs / 1000f) * pixelsPerSecond
        invalidate()
    }

    fun scrollToTime(timeMs: Long) {
        if (!isUserInteracting) {
            scrollXOffset = (timeMs / 1000f) * pixelsPerSecond
            clampScroll()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Background
        canvas.drawColor(Color.parseColor("#1E1E1E"))

        val visibleStartTime = (scrollXOffset / pixelsPerSecond).toLong()
        val visibleEndTime = ((scrollXOffset + width) / pixelsPerSecond).toLong()

        // Draw Ruler
        drawRuler(canvas)

        // Draw Clips (simplified representation for now)
        drawClips(canvas)

        // Draw Center Playhead
        paint.color = Color.WHITE
        paint.strokeWidth = 5f
        val centerX = width / 2f
        canvas.drawLine(centerX, 0f, centerX, height.toFloat(), paint)
    }

    private fun drawRuler(canvas: Canvas) {
        paint.color = Color.GRAY
        paint.strokeWidth = 2f

        // Offset everything by width/2 so 0:00 starts at center
        val startOffset = width / 2f

        // Determine visible range in seconds
        val startSec = ((scrollXOffset - startOffset) / pixelsPerSecond).toInt()
        val endSec = ((scrollXOffset + width - startOffset) / pixelsPerSecond).toInt()

        for (sec in (startSec - 1)..(endSec + 1)) {
            if (sec < 0) continue

            val x = (sec * pixelsPerSecond) - scrollXOffset + startOffset

            // Major tick (Second)
            canvas.drawLine(x, 0f, x, rulerHeight, paint)

            val timeString = String.format("%02d:%02d", sec / 60, sec % 60)
            canvas.drawText(timeString, x, rulerHeight + 30f, textPaint)

            // Minor ticks
            for (i in 1..4) {
                val minorX = x + (pixelsPerSecond * i / 5)
                canvas.drawLine(minorX, 0f, minorX, tickHeight, paint)
            }
        }
    }

    private fun drawClips(canvas: Canvas) {
        // Draw clips as colored blocks for now
        // This visualizes the "Magnetic" timeline
        val startOffset = width / 2f

        clips.forEachIndexed { index, clip ->
            val startX = (clip.startTimeMs / 1000f * pixelsPerSecond) - scrollXOffset + startOffset
            val endX = ((clip.startTimeMs + clip.playbackDuration) / 1000f * pixelsPerSecond) - scrollXOffset + startOffset

            if (endX > 0 && startX < width) {
                paint.color = if (index % 2 == 0) Color.DKGRAY else Color.parseColor("#333333")
                canvas.drawRect(startX, rulerHeight + 50f, endX, height.toFloat(), paint)

                // Draw clip name/info
                paint.color = Color.WHITE
                paint.textSize = 24f
                val clipName = "Clip ${index + 1}"
                canvas.drawText(clipName, startX + 10f, rulerHeight + 90f, paint)
            }
        }
    }
}
