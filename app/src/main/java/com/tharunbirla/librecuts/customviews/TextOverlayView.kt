package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.ScaleGestureDetector
import android.view.View
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.TextPosition
import java.io.File

/**
 * TextOverlayView renders text overlays on top of the video player.
 * This allows users to see text operations in real-time without FFmpeg processing.
 */
class TextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.WHITE
        textSize = 48f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private var isDraggingSubtitle = false
    private var dragStartX = 0f
    private var dragStartY = 0f
    private var initialRelativeX = 0.5f
    private var initialRelativeY = 0.8f

    private val subtitleBounds = android.graphics.RectF()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#007AFF") // Active accent color
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    var isSubtitlesEditingActive: Boolean = false
        set(value) {
            field = value
            invalidate()
        }
    var subtitleOperation: EditOperation.AddSubtitles? = null
        set(value) {
            field = value
            this.subtitleCues = value?.cues ?: emptyList()
            invalidate()
        }
    var onSubtitlePositionChanged: ((relativeX: Float, relativeY: Float) -> Unit)? = null

    private var overlayOperations: List<EditOperation> = emptyList()
    private val bitmapCache = mutableMapOf<String, Bitmap>()

    private fun loadBitmapFromUri(uri: android.net.Uri): Bitmap? {
        val cacheKey = uri.toString()
        if (bitmapCache.containsKey(cacheKey)) {
            return bitmapCache[cacheKey]
        }
        try {
            val bitmap = if (uri.scheme == "content") {
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }
            } else {
                val path = uri.path
                if (path != null && File(path).exists()) {
                    BitmapFactory.decodeFile(path)
                } else {
                    null
                }
            }
            if (bitmap != null) {
                bitmapCache[cacheKey] = bitmap
                return bitmap
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private var videoWidth = 0
    private var videoHeight = 0
    var currentPositionMs: Long = 0L
        set(value) {
            field = value
            invalidate()
        }
    var hiddenOperationId: String? = null
        set(value) {
            field = value
            invalidate()
        }

    private var subtitleCues: List<com.tharunbirla.librecuts.models.SubtitleCue> = emptyList()

    fun setTextOperations(operations: List<EditOperation.AddText>) {
        this.overlayOperations = operations
        invalidate()
    }

    fun setOverlayOperations(operations: List<EditOperation>) {
        this.overlayOperations = operations
        invalidate()
    }

    fun setSubtitleCues(cues: List<com.tharunbirla.librecuts.models.SubtitleCue>) {
        this.subtitleCues = cues
        invalidate()
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        invalidate()
    }

    private fun getVideoRect(): android.graphics.RectF {
        val rect = android.graphics.RectF()
        if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            rect.set(0f, 0f, width.toFloat(), height.toFloat())
            return rect
        }

        val containerRatio = width.toFloat() / height
        val videoRatio = videoWidth.toFloat() / videoHeight

        if (videoRatio > containerRatio) {
            val h = width / videoRatio
            val top = (height - h) / 2f
            rect.set(0f, top, width.toFloat(), top + h)
        } else {
            val w = height * videoRatio
            val left = (width - w) / 2f
            rect.set(left, 0f, left + w, height.toFloat())
        }
        return rect
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val videoRect = getVideoRect()
        val scale = if (videoHeight > 0) videoRect.height() / videoHeight.toFloat() else 1f

        for (op in overlayOperations) {
            if (op is EditOperation.AddText) {
                if (op.id == hiddenOperationId) continue
                val start = op.startTimeMs ?: 0L
                val end = op.endTimeMs ?: Long.MAX_VALUE
                if (currentPositionMs < start || currentPositionMs > end) continue

                paint.textSize = op.fontSize * scale
                try {
                    paint.color = Color.parseColor(op.color)
                } catch (e: Exception) {
                    paint.color = Color.WHITE
                }

                val x: Float
                val y: Float

                val textWidth = paint.measureText(op.text)
                val textHeight = paint.descent() - paint.ascent()

                if (op.hasCustomPosition()) {
                    // WYSIWYG drag-and-drop coordinates (fractional 0.0–1.0) relative to text center
                    val centerX = videoRect.left + (op.relativeX!! * videoRect.width())
                    val centerY = videoRect.top + (op.relativeY!! * videoRect.height())
                    x = centerX - (textWidth / 2f)
                    y = centerY - ((paint.ascent() + paint.descent()) / 2f)
                } else {
                    val rectW = videoRect.width()
                    val rectH = videoRect.height()
                    val rectL = videoRect.left
                    val rectT = videoRect.top

                    when (op.position) {
                        TextPosition.TOP_LEFT -> {
                            x = rectL + 16f
                            y = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_CENTER -> {
                            x = rectL + (rectW - textWidth) / 2
                            y = rectT + 32f - paint.ascent()
                        }
                        TextPosition.TOP_RIGHT -> {
                            x = rectL + rectW - textWidth - 16f
                            y = rectT + 32f - paint.ascent()
                        }
                        TextPosition.CENTER_LEFT -> {
                            x = rectL + 16f
                            y = rectT + rectH / 2 - textHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER -> {
                            x = rectL + (rectW - textWidth) / 2
                            y = rectT + rectH / 2 - textHeight / 2 - paint.ascent()
                        }
                        TextPosition.CENTER_RIGHT -> {
                            x = rectL + rectW - textWidth - 16f
                            y = rectT + rectH / 2 - textHeight / 2 - paint.ascent()
                        }
                        TextPosition.BOTTOM_LEFT -> {
                            x = rectL + 16f
                            y = rectT + rectH - 16f - paint.descent()
                        }
                        TextPosition.BOTTOM_CENTER -> {
                            x = rectL + (rectW - textWidth) / 2
                            y = rectT + rectH - 16f - paint.descent()
                        }
                        TextPosition.BOTTOM_RIGHT -> {
                            x = rectL + rectW - textWidth - 16f
                            y = rectT + rectH - 16f - paint.descent()
                        }
                        TextPosition.CENTER_BOTTOM -> {
                            x = rectL + (rectW - textWidth) / 2
                            y = rectT + rectH - 16f - paint.descent()
                        }
                        TextPosition.CENTER_TOP -> {
                            x = rectL + (rectW - textWidth) / 2
                            y = rectT + 32f - paint.ascent()
                        }
                    }
                }

                canvas.drawText(op.text, x, y, paint)
            }
        }

        // Render subtitles centered at bottom or custom positioned
        val activeCue = subtitleCues.firstOrNull { currentPositionMs in it.startTimeMs..it.endTimeMs }
            ?: if (isSubtitlesEditingActive && subtitleOperation != null) {
                com.tharunbirla.librecuts.models.SubtitleCue(0, 0, "[Subtitle Preview]")
            } else {
                null
            }

        if (activeCue != null) {
            val subOp = subtitleOperation
            val fontSizeVal = subOp?.fontSize ?: 22
            paint.textSize = fontSizeVal.toFloat() * context.resources.displayMetrics.density * scale
            paint.color = Color.WHITE

            val bgPaint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
                color = Color.parseColor("#80000000")
            }
            val showBg = true

            val lines = activeCue.text.split("\n")
            val textHeight = paint.descent() - paint.ascent()
            
            val rectW = videoRect.width()
            val rectH = videoRect.height()
            val rectL = videoRect.left
            val rectT = videoRect.top
            
            val totalBlockHeight = lines.size * textHeight
            val maxLineWidth = lines.maxOfOrNull { paint.measureText(it) } ?: 0f

            val refX: Float
            val refY: Float

            if (subOp != null && subOp.hasCustomPosition()) {
                refX = rectL + (subOp.relativeX!! * rectW)
                refY = rectT + (subOp.relativeY!! * rectH)
                
                // Draw centering each line horizontally around refX and the whole block vertically around refY
                var currentY = refY - (totalBlockHeight / 2f) - ((paint.ascent() + paint.descent()) / 2f)
                
                // Update bounds for border
                val paddingVal = 12f * scale
                subtitleBounds.set(
                    refX - (maxLineWidth / 2f) - paddingVal,
                    refY - (totalBlockHeight / 2f) - paddingVal,
                    refX + (maxLineWidth / 2f) + paddingVal,
                    refY + (totalBlockHeight / 2f) + paddingVal
                )

                for (line in lines) {
                    val lineTextWidth = paint.measureText(line)
                    val lineX = refX - (lineTextWidth / 2f)
                    
                    if (showBg) {
                        val paddingX = 8f * scale
                        val paddingY = 4f * scale
                        canvas.drawRoundRect(
                            lineX - paddingX,
                            currentY + paint.ascent() - paddingY,
                            lineX + lineTextWidth + paddingX,
                            currentY + paint.descent() + paddingY,
                            8f * scale, 8f * scale, bgPaint
                        )
                    }
                    canvas.drawText(line, lineX, currentY, paint)
                    currentY += textHeight
                }
            } else {
                // Predefined position alignment
                val pos = subOp?.position ?: TextPosition.BOTTOM_CENTER
                
                when (pos) {
                    TextPosition.TOP_LEFT -> {
                        refX = rectL + 16f * scale + (maxLineWidth / 2f)
                        refY = rectT + 32f * scale + (totalBlockHeight / 2f)
                    }
                    TextPosition.TOP_CENTER, TextPosition.CENTER_TOP -> {
                        refX = rectL + rectW / 2
                        refY = rectT + 32f * scale + (totalBlockHeight / 2f)
                    }
                    TextPosition.TOP_RIGHT -> {
                        refX = rectL + rectW - 16f * scale - (maxLineWidth / 2f)
                        refY = rectT + 32f * scale + (totalBlockHeight / 2f)
                    }
                    TextPosition.CENTER_LEFT -> {
                        refX = rectL + 16f * scale + (maxLineWidth / 2f)
                        refY = rectT + rectH / 2
                    }
                    TextPosition.CENTER -> {
                        refX = rectL + rectW / 2
                        refY = rectT + rectH / 2
                    }
                    TextPosition.CENTER_RIGHT -> {
                        refX = rectL + rectW - 16f * scale - (maxLineWidth / 2f)
                        refY = rectT + rectH / 2
                    }
                    TextPosition.BOTTOM_LEFT -> {
                        refX = rectL + 16f * scale + (maxLineWidth / 2f)
                        refY = rectT + rectH - 16f * scale - (totalBlockHeight / 2f)
                    }
                    TextPosition.BOTTOM_CENTER, TextPosition.CENTER_BOTTOM -> {
                        refX = rectL + rectW / 2
                        refY = rectT + rectH - 24f * scale - (totalBlockHeight / 2f)
                    }
                    TextPosition.BOTTOM_RIGHT -> {
                        refX = rectL + rectW - 16f * scale - (maxLineWidth / 2f)
                        refY = rectT + rectH - 16f * scale - (totalBlockHeight / 2f)
                    }
                }
                
                // Update bounds for border
                val paddingVal = 12f * scale
                subtitleBounds.set(
                    refX - (maxLineWidth / 2f) - paddingVal,
                    refY - (totalBlockHeight / 2f) - paddingVal,
                    refX + (maxLineWidth / 2f) + paddingVal,
                    refY + (totalBlockHeight / 2f) + paddingVal
                )

                var currentY = refY - (totalBlockHeight / 2f) - ((paint.ascent() + paint.descent()) / 2f)
                for (line in lines) {
                    val lineTextWidth = paint.measureText(line)
                    val lineX = when (pos) {
                        TextPosition.TOP_LEFT, TextPosition.CENTER_LEFT, TextPosition.BOTTOM_LEFT -> {
                            refX - (maxLineWidth / 2f)
                        }
                        TextPosition.TOP_RIGHT, TextPosition.CENTER_RIGHT, TextPosition.BOTTOM_RIGHT -> {
                            refX + (maxLineWidth / 2f) - lineTextWidth
                        }
                        else -> {
                            refX - (lineTextWidth / 2f)
                        }
                    }
                    
                    if (showBg) {
                        val paddingX = 8f * scale
                        val paddingY = 4f * scale
                        canvas.drawRoundRect(
                            lineX - paddingX,
                            currentY + paint.ascent() - paddingY,
                            lineX + lineTextWidth + paddingX,
                            currentY + paint.descent() + paddingY,
                            8f * scale, 8f * scale, bgPaint
                        )
                    }
                    canvas.drawText(line, lineX, currentY, paint)
                    currentY += textHeight
                }
            }

            // Draw dotted selection border if subtitles editing is active
            if (isSubtitlesEditingActive) {
                canvas.drawRect(subtitleBounds, selectionPaint)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        if (!isSubtitlesEditingActive || subtitleOperation == null) {
            return super.onTouchEvent(event)
        }


        val videoRect = getVideoRect()

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val touchX = event.x
                val touchY = event.y
                if (subtitleBounds.contains(touchX, touchY)) {
                    isDraggingSubtitle = true
                    dragStartX = touchX
                    dragStartY = touchY
                    initialRelativeX = subtitleOperation?.relativeX ?: 0.5f
                    initialRelativeY = subtitleOperation?.relativeY ?: 0.8f
                    return true
                }
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                if (isDraggingSubtitle) {
                    val deltaX = event.x - dragStartX
                    val deltaY = event.y - dragStartY
                    val newRelX = (initialRelativeX + deltaX / videoRect.width()).coerceIn(0f, 1f)
                    val newRelY = (initialRelativeY + deltaY / videoRect.height()).coerceIn(0f, 1f)
                    onSubtitlePositionChanged?.invoke(newRelX, newRelY)
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                if (isDraggingSubtitle) {
                    isDraggingSubtitle = false
                    return true
                }
            }
        }
        return true
    }
}
