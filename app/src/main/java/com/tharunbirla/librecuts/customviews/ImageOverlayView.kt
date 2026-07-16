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
    private val movieCache = mutableMapOf<String, android.graphics.Movie>()
    private val retrieverCache = mutableMapOf<String, android.media.MediaMetadataRetriever>()
    private val lastFrameCache = mutableMapOf<String, Pair<Long, Bitmap>>()
    
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
        
        // Cache static images
        for (op in operations) {
            val cacheKey = op.imageUri.toString()
            val path = op.imageUri.path ?: continue
            val isGif = path.endsWith(".gif", ignoreCase = true)
            val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                          path.endsWith(".mkv", ignoreCase = true) ||
                          path.endsWith(".mov", ignoreCase = true) ||
                          path.endsWith(".3gp", ignoreCase = true)

            if (!isGif && !isVideo && !bitmapCache.containsKey(cacheKey)) {
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            bitmapCache[cacheKey] = bitmap
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        for (retriever in retrieverCache.values) {
            try {
                retriever.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        retrieverCache.clear()
        lastFrameCache.clear()
        movieCache.clear()
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

            val path = op.imageUri.path ?: continue
            val isGif = path.endsWith(".gif", ignoreCase = true)
            val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                          path.endsWith(".mkv", ignoreCase = true) ||
                          path.endsWith(".mov", ignoreCase = true) ||
                          path.endsWith(".3gp", ignoreCase = true)

            if (isGif) {
                var movie = movieCache[op.imageUri.toString()]
                if (movie == null) {
                    try {
                        movie = android.graphics.Movie.decodeFile(path)
                        if (movie != null) {
                            movieCache[op.imageUri.toString()] = movie
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (movie != null && movie.duration() > 0) {
                    val relativeTimeMs = (currentPositionMs - start).toInt()
                    val gifTime = if (op.isLooping) {
                        relativeTimeMs % movie.duration()
                    } else {
                        Math.min(relativeTimeMs, movie.duration() - 1)
                    }
                    movie.setTime(gifTime)

                    val imgW = op.relativeWidth * videoRect.width()
                    val imgH = op.relativeHeight * videoRect.height()
                    val centerX = videoRect.left + (op.relativeX * videoRect.width())
                    val centerY = videoRect.top + (op.relativeY * videoRect.height())

                    canvas.save()
                    canvas.rotate(op.rotationAngle, centerX, centerY)
                    val scaleX = imgW / movie.width()
                    val scaleY = imgH / movie.height()
                    canvas.translate(centerX - imgW / 2f, centerY - imgH / 2f)
                    canvas.scale(scaleX, scaleY)
                    movie.draw(canvas, 0f, 0f)
                    canvas.restore()
                }
            } else if (isVideo) {
                var retriever = retrieverCache[op.imageUri.toString()]
                if (retriever == null) {
                    try {
                        retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(path)
                        retrieverCache[op.imageUri.toString()] = retriever
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                if (retriever != null) {
                    val relativeTimeMs = currentPositionMs - start
                    val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 1L
                    val effectiveRelativeTimeMs = if (op.isLooping && durationMs > 0) relativeTimeMs % durationMs else relativeTimeMs
                    val frameTimeUs = effectiveRelativeTimeMs * 1000
                    
                    val cacheKey = op.imageUri.toString()
                    val cached = lastFrameCache[cacheKey]
                    val bitmap = if (cached != null && Math.abs(cached.first - effectiveRelativeTimeMs) < 33) {
                        cached.second
                    } else {
                        try {
                            val newBitmap = retriever.getFrameAtTime(frameTimeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (newBitmap != null) {
                                lastFrameCache[cacheKey] = Pair(effectiveRelativeTimeMs, newBitmap)
                            }
                            newBitmap
                        } catch (e: Exception) {
                            cached?.second
                        }
                    }

                    if (bitmap != null) {
                        val imgW = op.relativeWidth * videoRect.width()
                        val imgH = op.relativeHeight * videoRect.height()
                        val centerX = videoRect.left + (op.relativeX * videoRect.width())
                        val centerY = videoRect.top + (op.relativeY * videoRect.height())
                        val dstRect = RectF(
                            centerX - imgW / 2f,
                            centerY - imgH / 2f,
                            centerX + imgW / 2f,
                            centerY + imgH / 2f
                        )
                        canvas.save()
                        canvas.rotate(op.rotationAngle, centerX, centerY)
                        canvas.drawBitmap(bitmap, null, dstRect, paint)
                        canvas.restore()
                    }
                }
            } else {
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
}
