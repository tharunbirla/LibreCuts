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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ImageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
    }
    private var imageOperations: List<EditOperation.AddImageOverlay> = emptyList()
    
    // Caches
    private val bitmapCache = mutableMapOf<String, Bitmap>()
    private val movieCache = mutableMapOf<String, android.graphics.Movie>()
    private val retrieverCache = mutableMapOf<String, android.media.MediaMetadataRetriever>()
    private val lastFrameCache = mutableMapOf<String, Pair<Long, Bitmap>>()
    
    // Memory pools to prevent GC churn during playback
    private val reusableBitmaps = mutableMapOf<String, Bitmap>()
    private val reusableIntArrays = mutableMapOf<String, IntArray>()
    
    private val pendingFetches = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
        
        // Cache static images with their chroma key properties considered
        scope.launch(Dispatchers.Default) {
            for (op in operations) {
                val path = op.imageUri.path ?: continue
                val isGif = path.endsWith(".gif", ignoreCase = true)
                val isVideo = path.endsWith(".mp4", ignoreCase = true) ||
                              path.endsWith(".mkv", ignoreCase = true) ||
                              path.endsWith(".mov", ignoreCase = true) ||
                              path.endsWith(".3gp", ignoreCase = true)

                if (!isGif && !isVideo) {
                    val cacheKey = "${op.imageUri}_${op.chromaKeyColor}_${op.chromaKeySimilarity}"
                    if (!bitmapCache.containsKey(cacheKey)) {
                        try {
                            val file = File(path)
                            if (file.exists()) {
                                var bitmap = BitmapFactory.decodeFile(file.absolutePath)
                                if (bitmap != null) {
                                    try {
                                        val exif = android.media.ExifInterface(file.absolutePath)
                                        val orientation = exif.getAttributeInt(
                                            android.media.ExifInterface.TAG_ORIENTATION,
                                            android.media.ExifInterface.ORIENTATION_NORMAL
                                        )
                                        val matrix = android.graphics.Matrix()
                                        var needsRotation = false
                                        when (orientation) {
                                            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> { matrix.postRotate(90f); needsRotation = true }
                                            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> { matrix.postRotate(180f); needsRotation = true }
                                            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> { matrix.postRotate(270f); needsRotation = true }
                                        }
                                        if (needsRotation) {
                                            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                if (bitmap != null && op.chromaKeyColor != null) {
                                    bitmap = applyChromaKey(bitmap, op.chromaKeyColor!!, op.chromaKeySimilarity, cacheKey)
                                }
                                if (bitmap != null) {
                                    withContext(Dispatchers.Main) {
                                        bitmapCache[cacheKey] = bitmap
                                        invalidate()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
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
        bitmapCache.clear()
        reusableBitmaps.values.forEach { it.recycle() }
        reusableBitmaps.clear()
        reusableIntArrays.clear()
        pendingFetches.clear()
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
    
    private fun applyChromaKey(bitmap: Bitmap, colorHex: String, similarity: Float, cacheKey: String): Bitmap {
        try {
            val color = android.graphics.Color.parseColor(colorHex)
            val targetR = android.graphics.Color.red(color)
            val targetG = android.graphics.Color.green(color)
            val targetB = android.graphics.Color.blue(color)
            
            val width = bitmap.width
            val height = bitmap.height
            val pixelCount = width * height
            
            var pixels = reusableIntArrays[cacheKey]
            if (pixels == null || pixels.size != pixelCount) {
                pixels = IntArray(pixelCount)
                reusableIntArrays[cacheKey] = pixels
            }
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val maxDistSq = 255f * 255f * 3f
            val simSq = similarity * similarity * maxDistSq
            val blendSq = 0.1f * 0.1f * maxDistSq
            
            for (i in 0 until pixelCount) {
                val p = pixels[i]
                val a = (p shr 24) and 0xFF
                if (a == 0) continue
                
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                
                val diffR = r - targetR
                val diffG = g - targetG
                val diffB = b - targetB
                val distSq = (diffR * diffR + diffG * diffG + diffB * diffB).toFloat()
                
                if (distSq < simSq) {
                    if (blendSq > 0 && distSq > simSq - blendSq) {
                        val alphaMult = (distSq - (simSq - blendSq)) / blendSq
                        val newA = (a * alphaMult).toInt().coerceIn(0, 255)
                        pixels[i] = (newA shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        pixels[i] = 0
                    }
                }
            }
            var keyed = reusableBitmaps["${cacheKey}_keyed"]
            if (keyed == null || keyed.width != width || keyed.height != height) {
                keyed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                reusableBitmaps["${cacheKey}_keyed"] = keyed
            }
            keyed.setPixels(pixels, 0, width, 0, 0, width, height)
            return keyed
        } catch (e: Exception) {
            return bitmap
        }
    }

    private fun interpolateKeyframes(
        keyframes: List<EditOperation.KeyframePoint>,
        timeMs: Long,
        defaultValue: Float
    ): Float {
        if (keyframes.isEmpty()) return defaultValue
        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) {
            return sorted.first().valueX
        }
        if (timeMs >= sorted.last().timeMs) {
            return sorted.last().valueX
        }
        for (i in 0 until sorted.size - 1) {
            val k1 = sorted[i]
            val k2 = sorted[i + 1]
            if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                return k1.valueX + progress * (k2.valueX - k1.valueX)
            }
        }
        return defaultValue
    }

    private fun interpolateKeyframePosition(
        keyframes: List<EditOperation.KeyframePoint>,
        timeMs: Long,
        defaultX: Float,
        defaultY: Float
    ): Pair<Float, Float> {
        if (keyframes.isEmpty()) return Pair(defaultX, defaultY)
        val sorted = keyframes.sortedBy { it.timeMs }
        if (timeMs <= sorted.first().timeMs) {
            return Pair(sorted.first().valueX, sorted.first().valueY)
        }
        if (timeMs >= sorted.last().timeMs) {
            return Pair(sorted.last().valueX, sorted.last().valueY)
        }
        for (i in 0 until sorted.size - 1) {
            val k1 = sorted[i]
            val k2 = sorted[i + 1]
            if (timeMs >= k1.timeMs && timeMs <= k2.timeMs) {
                val progress = (timeMs - k1.timeMs).toFloat() / (k2.timeMs - k1.timeMs)
                val x = k1.valueX + progress * (k2.valueX - k1.valueX)
                val y = k1.valueY + progress * (k2.valueY - k1.valueY)
                return Pair(x, y)
            }
        }
        return Pair(defaultX, defaultY)
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

            val relativeTimeMs = currentPositionMs - start
            val interpolatedPos = if (op.positionKeyframes.isNotEmpty()) {
                interpolateKeyframePosition(op.positionKeyframes, relativeTimeMs, op.relativeX, op.relativeY)
            } else {
                Pair(op.relativeX, op.relativeY)
            }
            val interpolatedOpacity = if (op.opacityKeyframes.isNotEmpty()) {
                interpolateKeyframes(op.opacityKeyframes, relativeTimeMs, op.opacity)
            } else {
                op.opacity
            }
            val interpolatedOp = op.copy(
                relativeX = interpolatedPos.first,
                relativeY = interpolatedPos.second,
                opacity = interpolatedOpacity
            )

            if (isGif || isVideo) {
                val speed = if (op.speedKeyframes.isNotEmpty()) {
                    interpolateKeyframes(op.speedKeyframes, relativeTimeMs, 1.0f)
                } else {
                    1.0f
                }
                var effectiveTimeMs = (relativeTimeMs * speed).toLong()
                
                if (isGif) {
                    var movie = movieCache[op.imageUri.toString()]
                    if (movie == null) {
                        try {
                            movie = android.graphics.Movie.decodeFile(path)
                            if (movie != null) movieCache[op.imageUri.toString()] = movie
                        } catch (e: Exception) { }
                    }
                    if (movie != null && movie.duration() > 0) {
                        effectiveTimeMs = if (op.isLooping) {
                            effectiveTimeMs % movie.duration()
                        } else {
                            Math.min(effectiveTimeMs, (movie.duration() - 1).toLong())
                        }
                    }
                } else {
                    var retriever = retrieverCache[op.imageUri.toString()]
                    if (retriever == null) {
                        try {
                            retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(path)
                            retrieverCache[op.imageUri.toString()] = retriever
                        } catch (e: Exception) { }
                    }
                    if (retriever != null) {
                        val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                        val durationMs = durationStr?.toLongOrNull() ?: 1L
                        effectiveTimeMs = if (op.isLooping && durationMs > 0) effectiveTimeMs % durationMs else effectiveTimeMs
                    }
                }
                
                val cacheKey = "${op.id}"
                val cached = lastFrameCache[cacheKey]
                
                // We use a relatively high threshold (100ms) to avoid over-fetching
                // As long as the fetched frame is close to what we need, we show it
                if (cached != null && Math.abs(cached.first - effectiveTimeMs) < 100) {
                    drawBitmapOp(canvas, cached.second, interpolatedOp, videoRect)
                } else {
                    // Need a new frame
                    if (!pendingFetches.contains(op.id)) {
                        pendingFetches.add(op.id)
                        scope.launch(Dispatchers.Default) {
                            var bitmap: Bitmap? = null
                            if (isGif) {
                                val movie = movieCache[op.imageUri.toString()]
                                if (movie != null && movie.width() > 0 && movie.height() > 0) {
                                    try {
                                        var tempBitmap = reusableBitmaps["${op.id}_gif"]
                                        if (tempBitmap == null || tempBitmap.width != movie.width() || tempBitmap.height != movie.height()) {
                                            tempBitmap = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
                                            reusableBitmaps["${op.id}_gif"] = tempBitmap
                                        }
                                        tempBitmap.eraseColor(android.graphics.Color.TRANSPARENT)
                                        val tempCanvas = Canvas(tempBitmap)
                                        movie.setTime(effectiveTimeMs.toInt())
                                        movie.draw(tempCanvas, 0f, 0f)
                                        bitmap = tempBitmap
                                    } catch (e: Exception) { }
                                }
                            } else {
                                val retriever = retrieverCache[op.imageUri.toString()]
                                if (retriever != null) {
                                    try {
                                        // Use OPTION_CLOSEST instead of OPTION_CLOSEST_SYNC to get accurate frames rather than choppy I-frames
                                        bitmap = retriever.getFrameAtTime(effectiveTimeMs * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST)
                                    } catch (e: Exception) { }
                                }
                            }
                            
                            if (bitmap != null && op.chromaKeyColor != null) {
                                bitmap = applyChromaKey(bitmap, op.chromaKeyColor!!, op.chromaKeySimilarity, cacheKey)
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (bitmap != null) {
                                    lastFrameCache[cacheKey] = Pair(effectiveTimeMs, bitmap)
                                }
                                pendingFetches.remove(op.id)
                                invalidate() // redraw with new frame
                            }
                        }
                    }
                    // Draw the old frame while fetching the new one to prevent flickering
                    cached?.second?.let { drawBitmapOp(canvas, it, interpolatedOp, videoRect) }
                }
            } else {
                val staticCacheKey = "${op.imageUri}_${op.chromaKeyColor}_${op.chromaKeySimilarity}"
                val bitmap = bitmapCache[staticCacheKey]
                if (bitmap != null) {
                    drawBitmapOp(canvas, bitmap, interpolatedOp, videoRect)
                }
            }
        }
    }
    
    private fun drawBitmapOp(canvas: Canvas, bitmap: Bitmap, op: EditOperation.AddImageOverlay, videoRect: RectF) {
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
        
        val oldAlpha = paint.alpha
        paint.alpha = (op.opacity * 255).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.rotate(op.rotationAngle, centerX, centerY)
        
        if (op.maskConfig.shape != EditOperation.MaskShape.NONE) {
            val path = android.graphics.Path()
            val cx = dstRect.left + dstRect.width() * op.maskConfig.relativeX
            val cy = dstRect.top + dstRect.height() * op.maskConfig.relativeY
            val mw = dstRect.width() * op.maskConfig.relativeWidth
            val mh = dstRect.height() * op.maskConfig.relativeHeight
            
            when (op.maskConfig.shape) {
                EditOperation.MaskShape.RECTANGLE -> path.addRect(cx - mw/2, cy - mh/2, cx + mw/2, cy + mh/2, android.graphics.Path.Direction.CW)
                EditOperation.MaskShape.ELLIPSE -> path.addOval(cx - mw/2, cy - mh/2, cx + mw/2, cy + mh/2, android.graphics.Path.Direction.CW)
                EditOperation.MaskShape.SPLIT -> path.addRect(dstRect.left - dstRect.width(), cy, dstRect.right + dstRect.width(), dstRect.bottom + dstRect.height(), android.graphics.Path.Direction.CW)
                EditOperation.MaskShape.SHUTTER -> path.addRect(dstRect.left - dstRect.width(), cy - mh/2, dstRect.right + dstRect.width(), cy + mh/2, android.graphics.Path.Direction.CW)
                else -> {}
            }
            
            if (op.maskConfig.isInverted) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    canvas.clipOutPath(path)
                } else {
                    canvas.clipPath(path, android.graphics.Region.Op.DIFFERENCE)
                }
            } else {
                canvas.clipPath(path)
            }
        }
        
        if (op.isMirrored) {
            canvas.scale(-1f, 1f, centerX, centerY)
        }
        canvas.drawBitmap(bitmap, null, dstRect, paint)
        canvas.restore()
        paint.alpha = oldAlpha
    }
}
