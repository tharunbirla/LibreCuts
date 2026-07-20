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

class TrackTrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var videoDurationMs: Long = 0L
    var maxDurationMs: Long = 0L
    var maxSelectionDurationMs: Long? = null
    var startTimeMs: Long = 0L
    var endTimeMs: Long = 0L
    var activeStartMs: Long = 0L
    var activeEndMs: Long = 0L

    enum class DragTarget { NONE, LEFT, RIGHT, CENTER }

    var onTrimChanged: ((Long, Long, DragTarget) -> Unit)? = null
    var onTrimAdjusting: ((Long, Long) -> Unit)? = null
    var onTrimAdjustingWithDelta: ((Long, Long, Long, Long) -> Unit)? = null
    var onDragStateChanged: ((Boolean) -> Unit)? = null
    var customMsPerPixel: Float? = null

    var trackColor: Int = Color.parseColor("#4285F4") // Default blue
    var trackLabel: String? = null
    var isSelectedTrack: Boolean = false
    var isMainVideoTrack: Boolean = false
    var isTrimEnabled: Boolean = true
    var trackIcon: android.graphics.drawable.Drawable? = null
    var trackThumbnail: android.graphics.Bitmap? = null
    var isAudioTrack: Boolean = false
    var onTrackClicked: (() -> Unit)? = null
    
    var beats: List<Long> = emptyList()
    var internalStartMs: Long = 0L

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#252429") // solid color to mask video frames and create a shrinking effect
        style = Paint.Style.FILL
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40FFFFFF") // Semi-transparent white
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }
    private val beatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private val rectF = RectF()
    private val thumbnailRectF = RectF()
    private val handleWidth = 24f

    private var dragTarget = DragTarget.NONE
    private var lastTouchX = 0f
    private var downTouchX = 0f
    
    private var handleScaleAnimator: android.animation.ValueAnimator? = null
    private var currentHandleScale = 1.0f

    private var isDraggingHandle = false
        set(value) {
            if (field != value) {
                field = value
                val targetScale = if (value) 1.6f else 1.0f
                handleScaleAnimator?.cancel()
                handleScaleAnimator = android.animation.ValueAnimator.ofFloat(currentHandleScale, targetScale).apply {
                    duration = 150
                    addUpdateListener {
                        currentHandleScale = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
                
                if (value) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                } else {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }

    fun setRange(videoDurationMs: Long, startTimeMs: Long, endTimeMs: Long) {
        this.videoDurationMs = videoDurationMs
        this.startTimeMs = startTimeMs.coerceIn(0L, videoDurationMs)
        this.endTimeMs = endTimeMs.coerceIn(this.startTimeMs, videoDurationMs)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (videoDurationMs <= 0 || width <= 0) return

        val msPerPixel = customMsPerPixel ?: (videoDurationMs.toFloat() / width)
        val startX = startTimeMs / msPerPixel
        val endX = endTimeMs / msPerPixel

        rectF.set(startX, 0f, endX, height.toFloat())
        
        // Draw track fill
        if (isMainVideoTrack) {
            if (isSelectedTrack) {
                if (isTrimEnabled) {
                    val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#4DFFFFFF") // 30% white
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                        pathEffect = android.graphics.DashPathEffect(floatArrayOf(10f, 10f), 0f)
                    }
                    val ghostFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#1AFFFFFF") // 10% white
                        style = Paint.Style.FILL
                    }
                    val fullRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
                    canvas.drawRect(fullRect, ghostFill)
                    canvas.drawRect(fullRect, ghostPaint)
                    
                    // Only dim the unused bounds when selected, so it doesn't leak over adjacent clips
                    if (startX > 0f) {
                        canvas.drawRect(0f, 0f, startX, height.toFloat(), dimPaint)
                    }
                    if (endX < width) {
                        canvas.drawRect(endX, 0f, width.toFloat(), height.toFloat(), dimPaint)
                    }
                    
                    // Draw a thick border enclosing the active range when selected
                    borderPaint.color = Color.parseColor("#FF4081")
                    borderPaint.strokeWidth = 8f
                    canvas.drawRect(rectF, borderPaint)
                } else {
                    // Main track selection highlight without trimmer handles/ghosts
                    borderPaint.color = Color.parseColor("#FF2A6D") // Electric Pink selection
                    borderPaint.strokeWidth = 10f
                    // Inset the rect so the stroke doesn't get clipped by the parent FrameLayout
                    val inset = borderPaint.strokeWidth / 2f
                    val selRect = RectF(rectF.left + inset, rectF.top + inset, rectF.right - inset, rectF.bottom - inset)
                    canvas.drawRect(selRect, borderPaint)
                    
                    val overlay = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.parseColor("#33FF2A6D")
                        style = Paint.Style.FILL
                    }
                    canvas.drawRect(selRect, overlay)
                }
            }
        } else {
            trackPaint.color = trackColor
            trackPaint.alpha = 200
            canvas.drawRoundRect(rectF, 12f, 12f, trackPaint)
        }

        // Draw Audio Wave Background
        if (isAudioTrack) {
            canvas.save()
            canvas.clipRect(rectF)
            val centerY = height / 2f
            val maxAmplitude = height * 0.35f
            var x = startX + handleWidth + 4f
            val waveSpacing = 12f
            var timeOffset = 0f
            while (x < endX - handleWidth) {
                // Procedural wave using sine and pseudo-random
                val amplitude = maxAmplitude * (0.3f + 0.7f * Math.abs(Math.sin((x + timeOffset) * 0.05).toFloat()))
                canvas.drawLine(x, centerY - amplitude, x, centerY + amplitude, wavePaint)
                x += waveSpacing
                timeOffset += 1f
            }
            canvas.restore()
        }

        // Draw Beat Markers
        if (beats.isNotEmpty()) {
            canvas.save()
            canvas.clipRect(rectF)
            val beatRadius = 4f
            val yPos = height - 12f
            for (beatTimeMs in beats) {
                val relativeToInternalStart = beatTimeMs - internalStartMs
                if (relativeToInternalStart >= 0) {
                    val beatX = startX + (relativeToInternalStart / msPerPixel)
                    if (beatX <= endX) {
                        canvas.drawCircle(beatX, yPos, beatRadius, beatPaint)
                    }
                }
            }
            canvas.restore()
        }
        
        // Draw track border
        if (!isMainVideoTrack) {
            if (isSelectedTrack) {
                borderPaint.color = Color.parseColor("#FF4081") // Vibrant pink selection accent
                borderPaint.strokeWidth = 6f
                canvas.drawRoundRect(rectF, 12f, 12f, borderPaint)
                
                // Draw a subtle 10% opacity pink overlay inside the track
                val selectionOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1AFF4081")
                    style = Paint.Style.FILL
                }
                canvas.drawRoundRect(rectF, 12f, 12f, selectionOverlayPaint)
            } else {
                borderPaint.color = Color.parseColor("#33FFFFFF") // Subtle 20% opacity white outline
                borderPaint.strokeWidth = 2f
                canvas.drawRoundRect(rectF, 12f, 12f, borderPaint)
            }
        }

        // Draw Icon, Thumbnail, and Label if available
        var textStartX = startX + handleWidth + 16f
        val iconSize = 36
        val iconTop = (height - iconSize) / 2

        if (trackIcon != null) {
            trackIcon?.setBounds(textStartX.toInt(), iconTop, (textStartX + iconSize).toInt(), iconTop + iconSize)
            trackIcon?.setTint(Color.WHITE)
            trackIcon?.draw(canvas)
            textStartX += iconSize + 12f
        }

        if (trackThumbnail != null) {
            val thumbWidth = (iconSize * 1.5f)
            thumbnailRectF.set(textStartX, iconTop.toFloat(), textStartX + thumbWidth, iconTop.toFloat() + iconSize)
            canvas.drawBitmap(trackThumbnail!!, null, thumbnailRectF, trackPaint)
            textStartX += thumbWidth + 12f
        }

        if (trackLabel != null) {
            val padding = 16f
            val maxTextWidth = (endX - startX) - (textStartX - startX) - handleWidth - padding
            if (maxTextWidth > 20f) {
                val textPaintObj = android.text.TextPaint(textPaint)
                val textToDraw = android.text.TextUtils.ellipsize(
                    trackLabel,
                    textPaintObj,
                    maxTextWidth,
                    android.text.TextUtils.TruncateAt.END
                ).toString()
                
                val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(textToDraw, textStartX, textY, textPaint)
            }
        }

        if (isTrimEnabled) {
            val currentHandleWidth = handleWidth * currentHandleScale
            if (isMainVideoTrack) {
                handlePaint.color = Color.parseColor("#FF4081")
            } else {
                handlePaint.color = Color.WHITE
            }
            // Draw left handle
            canvas.drawRect(startX, 0f, startX + currentHandleWidth, height.toFloat(), handlePaint)
            // Draw right handle
            canvas.drawRect(endX - currentHandleWidth, 0f, endX, height.toFloat(), handlePaint)

            // Draw vertical grip lines on the handles
            val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#4D000000") // Subtle dark overlay for grips
                style = Paint.Style.STROKE
                strokeWidth = 3f
            }
            // Left handle grip lines
            val leftMidX = startX + currentHandleWidth / 2f
            canvas.drawLine(leftMidX - 4f, height * 0.3f, leftMidX - 4f, height * 0.7f, gripPaint)
            canvas.drawLine(leftMidX + 4f, height * 0.3f, leftMidX + 4f, height * 0.7f, gripPaint)
            
            // Right handle grip lines
            val rightMidX = endX - currentHandleWidth / 2f
            canvas.drawLine(rightMidX - 4f, height * 0.3f, rightMidX - 4f, height * 0.7f, gripPaint)
            canvas.drawLine(rightMidX + 4f, height * 0.3f, rightMidX + 4f, height * 0.7f, gripPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (videoDurationMs <= 0) return false

        val msPerPixel = customMsPerPixel ?: (videoDurationMs.toFloat() / width)
        val startX = startTimeMs / msPerPixel
        val endX = endTimeMs / msPerPixel

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                downTouchX = event.x
                dragTarget = if (isTrimEnabled) {
                    when {
                        event.x >= startX - 30f && event.x <= startX + handleWidth + 30f -> DragTarget.LEFT
                        event.x >= endX - handleWidth - 30f && event.x <= endX + 30f -> DragTarget.RIGHT
                        event.x > startX + handleWidth && event.x < endX - handleWidth -> DragTarget.CENTER
                        else -> DragTarget.NONE
                    }
                } else {
                    if (event.x >= startX && event.x <= endX) DragTarget.CENTER else DragTarget.NONE
                }
                isDraggingHandle = isTrimEnabled && (dragTarget == DragTarget.LEFT || dragTarget == DragTarget.RIGHT)
                if (dragTarget != DragTarget.NONE) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    onDragStateChanged?.invoke(true)
                }
                return dragTarget != DragTarget.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isTrimEnabled) return true
                val dx = event.x - lastTouchX
                val dtMs = (dx * msPerPixel).toLong()

                when (dragTarget) {
                    DragTarget.LEFT -> {
                        val minStart = 0L
                        var maxStart = endTimeMs - 100L
                        if (maxSelectionDurationMs != null && endTimeMs - (startTimeMs + dtMs) > maxSelectionDurationMs!!) {
                            // If user drags left making the window larger than max, limit it
                            startTimeMs = endTimeMs - maxSelectionDurationMs!!
                        } else {
                            startTimeMs = (startTimeMs + dtMs).coerceIn(minStart, maxStart.coerceAtLeast(minStart))
                        }
                    }
                    DragTarget.RIGHT -> {
                        val minEnd = startTimeMs + 100L
                        val limit = if (maxDurationMs > 0L) maxDurationMs else videoDurationMs
                        if (maxSelectionDurationMs != null && (endTimeMs + dtMs) - startTimeMs > maxSelectionDurationMs!!) {
                            // If user drags right making the window larger than max, limit it
                            endTimeMs = startTimeMs + maxSelectionDurationMs!!
                        } else {
                            endTimeMs = (endTimeMs + dtMs).coerceIn(minEnd, limit.coerceAtLeast(minEnd))
                        }
                    }
                    DragTarget.CENTER -> {
                        val duration = endTimeMs - startTimeMs
                        val minCenterStart = 0L
                        val limit = if (maxDurationMs > 0L) maxDurationMs else videoDurationMs
                        val maxCenterStart = limit - duration
                        startTimeMs = (startTimeMs + dtMs).coerceIn(minCenterStart, maxCenterStart.coerceAtLeast(minCenterStart))
                        endTimeMs = startTimeMs + duration
                    }
                    DragTarget.NONE -> {}
                }
                
                lastTouchX = event.x
                invalidate()
                onTrimAdjusting?.invoke(startTimeMs, endTimeMs)
                onTrimAdjustingWithDelta?.invoke(startTimeMs, endTimeMs, if (dragTarget == DragTarget.LEFT) dtMs else 0L, if (dragTarget == DragTarget.RIGHT) dtMs else 0L)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragTarget != DragTarget.NONE) {
                    val wasDrag = Math.abs(event.x - downTouchX) > 10f
                    if (!isTrimEnabled) {
                        if (!wasDrag && event.action == MotionEvent.ACTION_UP) {
                            onTrackClicked?.invoke()
                        }
                    } else {
                        if (!wasDrag && dragTarget == DragTarget.CENTER && event.action == MotionEvent.ACTION_UP) {
                            onTrackClicked?.invoke()
                        } else if (wasDrag) {
                            onTrimChanged?.invoke(startTimeMs, endTimeMs, dragTarget)
                        }
                    }
                    dragTarget = DragTarget.NONE
                    isDraggingHandle = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragStateChanged?.invoke(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
