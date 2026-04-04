package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * CustomVideoSeeker — redesigned to match the CapCut-style reference UI.
 *
 * Visual changes (no functional changes):
 *  • Playhead line color → #FF4081 (accent)
 *  • Playhead has a teardrop / rounded-top handle at the top (like the reference)
 *  • Line is slightly thinner for a refined look
 *
 * All seek logic (onSeekListener, setVideoDuration, seekPosition) is untouched.
 */
class CustomVideoSeeker @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Paints ────────────────────────────────────────────────────────────────

    /** The main accent line */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        strokeWidth = 6f // Slightly thicker for a premium feel
        style = Paint.Style.FILL_AND_STROKE
        strokeCap = Paint.Cap.ROUND // Smooth ends
    }

    /** The "Shield" handle at the top */
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
    }

    /** Subtle drop shadow so the playhead doesn't get lost in light scenes */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 50 // Very subtle
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }

    /** Subtle white glow behind the handle for depth */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        style = Paint.Style.FILL
    }

    // ── State (identical to original) ─────────────────────────────────────────
    private var seekPosition = 0f   // 0..1
    private var videoDuration = 0L  // ms
    var onSeekListener: ((Float) -> Unit)? = null

    // ── Handle geometry ───────────────────────────────────────────────────────
    private val handleRadius = 10f  // dp-ish; fine at mdpi; scale if needed
    private val handleRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val seekX = width * seekPosition
        val handleWidth = 24f  // The width of the top pin
        val handleHeight = 40f // The height of the top pin
        val cornerRadius = 6f

        // 1. Draw the subtle drop shadow for the vertical line
        // We offset it slightly to the right to create depth
        canvas.drawLine(seekX + 2f, handleHeight, seekX + 2f, height.toFloat(), shadowPaint)

        // 2. Draw the main vertical accent line (The Stem)
        canvas.drawLine(seekX, handleHeight, seekX, height.toFloat(), linePaint)

        // 3. Draw the handle "Head" (A rounded rectangle/shield)
        // This replaces the simple circle and looks much more like a timeline needle
        handleRect.set(
            seekX - (handleWidth / 2),
            0f,
            seekX + (handleWidth / 2),
            handleHeight
        )

        // Draw a slight glow/shadow under the head
        canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, glowPaint)

        // Draw the actual head
        canvas.drawRoundRect(handleRect, cornerRadius, cornerRadius, handlePaint)

        // 4. Draw a tiny white indicator dot in the center of the head
        // This gives it that "precision instrument" look
//        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
//            color = Color.WHITE
//            style = Paint.Style.FILL
//            alpha = 200
//        }
//        canvas.drawCircle(seekX, handleHeight / 2, 4f, dotPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                seekPosition = (event.x / width).coerceIn(0f, 1f)
                // ONLY invoke with the 0f..1f ratio
                onSeekListener?.invoke(seekPosition)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun setVideoDuration(duration: Long) {
        videoDuration = duration
    }

    fun setSeekPosition(position: Float) {
        seekPosition = position.coerceIn(0f, 1f)
        invalidate()
    }

}