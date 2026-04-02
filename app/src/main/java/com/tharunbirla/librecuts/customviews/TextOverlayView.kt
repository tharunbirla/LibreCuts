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
    }

    private var textOperations: List<EditOperation.AddText> = emptyList()

    fun setTextOperations(operations: List<EditOperation.AddText>) {
        this.textOperations = operations
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (textOp in textOperations) {
            paint.textSize = textOp.fontSize.toFloat()

            // Calculate position based on TextPosition enum
            val x: Float
            val y: Float

            val textWidth = paint.measureText(textOp.text)
            val textHeight = paint.descent() - paint.ascent()

            when (textOp.position) {
                TextPosition.TOP_LEFT -> {
                    x = 16f
                    y = 32f
                }
                TextPosition.TOP_CENTER -> {
                    x = (width - textWidth) / 2
                    y = 32f
                }
                TextPosition.TOP_RIGHT -> {
                    x = width - textWidth - 16f
                    y = 32f
                }
                TextPosition.CENTER_LEFT -> {
                    x = 16f
                    y = height / 2 + textHeight / 2
                }
                TextPosition.CENTER -> {
                    x = (width - textWidth) / 2
                    y = height / 2 + textHeight / 2
                }
                TextPosition.CENTER_RIGHT -> {
                    x = width - textWidth - 16f
                    y = height / 2 + textHeight / 2
                }
                TextPosition.BOTTOM_LEFT -> {
                    x = 16f
                    y = height - 16f
                }
                TextPosition.BOTTOM_CENTER -> {
                    x = (width - textWidth) / 2
                    y = height - 16f
                }
                TextPosition.BOTTOM_RIGHT -> {
                    x = width - textWidth - 16f
                    y = height - 16f
                }
                TextPosition.CENTER_BOTTOM -> {
                    x = (width - textWidth) / 2
                    y = height - 16f
                }
                TextPosition.CENTER_TOP -> {
                    x = (width - textWidth) / 2
                    y = 32f
                }
            }

            canvas.drawText(textOp.text, x, y, paint)
        }
    }
}
