package com.tharunbirla.librecuts.customviews

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.tharunbirla.librecuts.models.EditOperation
import java.io.File

class ImageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var imageOperations: List<EditOperation.AddImageOverlay> = emptyList()
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    
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

    fun setImageOperations(operations: List<EditOperation.AddImageOverlay>) {
        this.imageOperations = operations
        
        // Cache bitmaps
        for (op in operations) {
            val cacheKey = op.imageUri.toString()
            if (!bitmapCache.containsKey(cacheKey)) {
                try {
                    val path = op.imageUri.path
                    if (path != null) {
                        val file = File(path)
                        if (file.exists()) {
                            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                            if (bitmap != null) {
                                bitmapCache[cacheKey] = bitmap
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        invalidate()
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        invalidate()
    }

    private fun getVideoRect(): RectF {
        val rect = RectF()
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

        for (op in imageOperations) {
            if (op.id == hiddenOperationId) continue
            val start = op.startTimeMs ?: 0L
            val end = op.endTimeMs ?: Long.MAX_VALUE
            if (currentPositionMs < start || currentPositionMs > end) continue

            val bitmap = bitmapCache[op.imageUri.toString()] ?: continue
            
            // Width and height of image in screen coordinates
            val imgW = op.relativeWidth * videoRect.width()
            val imgH = op.relativeHeight * videoRect.height()
            
            // Center position on screen
            val centerX = videoRect.left + (op.relativeX * videoRect.width())
            val centerY = videoRect.top + (op.relativeY * videoRect.height())
            
            // Rect where image will be drawn
            val dstRect = RectF(
                centerX - imgW / 2f,
                centerY - imgH / 2f,
                centerX + imgW / 2f,
                centerY + imgH / 2f
            )
            
            // Draw with rotation
            canvas.save()
            canvas.rotate(op.rotationAngle, centerX, centerY)
            canvas.drawBitmap(bitmap, null, dstRect, paint)
            canvas.restore()
        }
    }
}
