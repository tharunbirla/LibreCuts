package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView

class DraggableImageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    var onImageCommitted: ((uri: Uri, relativeX: Float, relativeY: Float, relativeWidth: Float, relativeHeight: Float, rotationAngle: Float) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isEditingActive = false
    private var relativeX = 0.5f  // center default
    private var relativeY = 0.5f
    private var relativeWidth = 0.3f
    private var relativeHeight = 0.3f
    private var rotationAngle = 0f
    
    private var videoWidth = 0
    private var videoHeight = 0
    private var imageAspectRatio = 1.0f
    private var imageUri: Uri? = null

    // ── Drag tracking ─────────────────────────────────────────────────────────
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    // ── UI components ─────────────────────────────────────────────────────────
    private val imageView: ImageView = AppCompatImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    // ── Selection border paint ────────────────────────────────────────────────
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF4081")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 6f), 0f)
    }

    private val selectionRect = RectF()

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            relativeWidth = (relativeWidth * scaleFactor).coerceIn(0.1f, 0.9f)
            relativeHeight = relativeWidth / imageAspectRatio
            updateImageViewSizeAndPosition()
            return true
        }
    })

    init {
        visibility = GONE
        val imageViewParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(imageView, imageViewParams)
        setWillNotDraw(false)
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        post {
            updateImageViewSizeAndPosition()
        }
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

    private fun updateImageViewSizeAndPosition() {
        if (width <= 0 || height <= 0 || imageUri == null) return
        val videoRect = getVideoRect()

        // Calculate size on screen based on relative width/height
        val widthOnScreen = relativeWidth * videoRect.width()
        val heightOnScreen = relativeHeight * videoRect.height()

        imageView.layoutParams = LayoutParams(widthOnScreen.toInt(), heightOnScreen.toInt())

        // Calculate position on screen
        val targetCenterX = videoRect.left + (relativeX * videoRect.width())
        val targetCenterY = videoRect.top + (relativeY * videoRect.height())

        imageView.x = targetCenterX - widthOnScreen / 2f
        imageView.y = targetCenterY - heightOnScreen / 2f
        
        imageView.rotation = rotationAngle
        invalidate()
    }

    private fun updateRelativePosition() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val centerX = imageView.x + imageView.width / 2f
        val centerY = imageView.y + imageView.height / 2f

        relativeX = if (videoRect.width() > 0) ((centerX - videoRect.left) / videoRect.width()).coerceIn(0f, 1f) else 0.5f
        relativeY = if (videoRect.height() > 0) ((centerY - videoRect.top) / videoRect.height()).coerceIn(0f, 1f) else 0.5f
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun activate(uri: Uri, aspect: Float = 1.0f) {
        isEditingActive = true
        imageUri = uri
        imageAspectRatio = aspect
        relativeX = 0.5f
        relativeY = 0.5f
        relativeWidth = 0.3f
        relativeHeight = 0.3f / aspect
        rotationAngle = 0f

        imageView.setImageURI(uri)
        visibility = VISIBLE
        post {
            updateImageViewSizeAndPosition()
        }
    }

    fun activateForEdit(op: com.tharunbirla.librecuts.models.EditOperation.AddImageOverlay) {
        isEditingActive = true
        imageUri = op.imageUri
        imageAspectRatio = if (op.relativeHeight > 0) op.relativeWidth / op.relativeHeight else 1.0f
        relativeX = op.relativeX
        relativeY = op.relativeY
        relativeWidth = op.relativeWidth
        relativeHeight = op.relativeHeight
        rotationAngle = op.rotationAngle

        imageView.setImageURI(op.imageUri)
        imageView.rotation = rotationAngle
        visibility = VISIBLE
        post {
            updateImageViewSizeAndPosition()
        }
    }

    fun deactivate() {
        isEditingActive = false
        visibility = GONE
        imageUri = null
    }

    fun setRotationAngle(angle: Float) {
        rotationAngle = angle
        imageView.rotation = rotationAngle
        invalidate()
    }

    fun commitImage() {
        val uri = imageUri
        if (uri != null) {
            updateRelativePosition()
            onImageCommitted?.invoke(uri, relativeX, relativeY, relativeWidth, relativeHeight, rotationAngle)
        }
        deactivate()
    }

    // ── Touch handling for drag and scale ───────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!isEditingActive) return false

        scaleDetector.onTouchEvent(ev)

        if (scaleDetector.isInProgress || ev.pointerCount > 1) {
            return true
        }

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val touchX = ev.x
                val touchY = ev.y
                val imgLeft = imageView.left.toFloat()
                val imgTop = imageView.top.toFloat()
                val imgRight = imageView.right.toFloat()
                val imgBottom = imageView.bottom.toFloat()

                // Check if touch is inside the image bounds
                if (touchX in imgLeft..imgRight && touchY in imgTop..imgBottom) {
                    isDragging = true
                    dragOffsetX = touchX - imageView.x
                    dragOffsetY = touchY - imageView.y
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEditingActive) return false

        scaleDetector.onTouchEvent(event)

        if (scaleDetector.isInProgress || event.pointerCount > 1) {
            isDragging = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = true
                dragOffsetX = event.x - imageView.x
                dragOffsetY = event.y - imageView.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val videoRect = getVideoRect()
                    val centerX = event.x - dragOffsetX + imageView.width / 2f
                    val centerY = event.y - dragOffsetY + imageView.height / 2f

                    val constrainedCenterX = centerX.coerceIn(videoRect.left, videoRect.right)
                    val constrainedCenterY = centerY.coerceIn(videoRect.top, videoRect.bottom)

                    imageView.x = constrainedCenterX - imageView.width / 2f
                    imageView.y = constrainedCenterY - imageView.height / 2f
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    updateRelativePosition()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)

        if (isEditingActive && imageUri != null) {
            // Draw selection bounds correctly around rotated image
            canvas.save()
            canvas.rotate(rotationAngle, imageView.x + imageView.width / 2f, imageView.y + imageView.height / 2f)
            selectionRect.set(
                imageView.x - 4f,
                imageView.y - 4f,
                imageView.x + imageView.width + 4f,
                imageView.y + imageView.height + 4f
            )
            canvas.drawRoundRect(selectionRect, 8f, 8f, selectionPaint)

            // Draw 4 corner handles
            val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#007AFF")
                style = Paint.Style.FILL
            }
            val handleRadius = 12f
            canvas.drawCircle(selectionRect.left, selectionRect.top, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.left, selectionRect.bottom, handleRadius, handlePaint)
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handlePaint)

            canvas.restore()
        }
    }
}
