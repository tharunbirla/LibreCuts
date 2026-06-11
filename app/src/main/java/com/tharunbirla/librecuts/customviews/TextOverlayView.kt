package com.tharunbirla.librecuts.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.tharunbirla.librecuts.models.EditOperation
import com.tharunbirla.librecuts.models.TextPosition

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

    private var textOperations: List<EditOperation.AddText> = emptyList()
    private var videoWidth = 0
    private var videoHeight = 0

    fun setTextOperations(operations: List<EditOperation.AddText>) {
        this.textOperations = operations
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

        for (textOp in textOperations) {
            paint.textSize = textOp.fontSize * scale
            try {
                paint.color = Color.parseColor(textOp.color)
            } catch (e: Exception) {
                paint.color = Color.WHITE
            }

            val x: Float
            val y: Float

            val textWidth = paint.measureText(textOp.text)
            val textHeight = paint.descent() - paint.ascent()

            if (textOp.hasCustomPosition()) {
                // WYSIWYG drag-and-drop coordinates (fractional 0.0–1.0) relative to text center
                val centerX = videoRect.left + (textOp.relativeX!! * videoRect.width())
                val centerY = videoRect.top + (textOp.relativeY!! * videoRect.height())
                x = centerX - (textWidth / 2f)
                y = centerY - ((paint.ascent() + paint.descent()) / 2f)
            } else {
                val rectW = videoRect.width()
                val rectH = videoRect.height()
                val rectL = videoRect.left
                val rectT = videoRect.top

                when (textOp.position) {
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

            canvas.drawText(textOp.text, x, y, paint)
        }
    }
}
