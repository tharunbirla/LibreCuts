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

    /** Thin accent line — the vertical playhead stem */
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    /** Filled circle / handle at the top of the playhead */
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.FILL
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

        // 1. Vertical stem
        canvas.drawLine(seekX, handleRadius * 2f, seekX, height.toFloat(), linePaint)

        // 2. Glow halo behind handle
        canvas.drawCircle(seekX, handleRadius, handleRadius + 4f, glowPaint)

        // 3. Filled handle circle
        canvas.drawCircle(seekX, handleRadius, handleRadius, handlePaint)
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