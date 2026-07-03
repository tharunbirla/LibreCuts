package com.tharunbirla.librecuts.customviews

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout

/**
 * DraggableTextOverlayView provides an inline, draggable text editing experience
 * directly on top of the video viewport.
 *
 * When activated, it spawns a transparent EditText at the center of the viewport.
 * The user can:
 *   - Type text and see it live on the video canvas
 *   - Drag the text to any position on the viewport
 *   - Adjust font size via external controls (the text editing toolbar)
 *
 * On commit, it reports the text, font size, and fractional position (0.0–1.0)
 * relative to the viewport dimensions — these map directly to FFmpeg drawtext
 * coordinates: x='w*relX':y='h*relY'
 */
class DraggableTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // ── Public callbacks ──────────────────────────────────────────────────────
    /** Called when the user commits the text (taps Done). */
    var onTextCommitted: ((text: String, fontSize: Int, relativeX: Float, relativeY: Float, color: String) -> Unit)? = null

    /** Called when the text content changes (for live preview feedback). */
    var onTextChanged: ((text: String) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────
    private var isEditingActive = false
    private var currentFontSize = 36
    private var relativeX = 0.5f  // center default
    private var relativeY = 0.5f
    private var videoWidth = 0
    private var videoHeight = 0
    private var currentColorString = "#FFFFFF"

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val scaleFactor = detector.scaleFactor
            val newSize = (currentFontSize * scaleFactor).toInt().coerceIn(12, 500)
            if (newSize != currentFontSize) {
                setFontSize(newSize)
            }
            return true
        }
    })

    fun setTextColor(colorString: String) {
        currentColorString = colorString
        try {
            editText.setTextColor(Color.parseColor(colorString))
        } catch (e: Exception) {
            editText.setTextColor(Color.WHITE)
        }
        invalidate()
    }

    fun getTextColor(): String = currentColorString

    // ── Snap & Drag tracking ──────────────────────────────────────────────────
    var isSnappingEnabled = true
    private var showVerticalGuideline = false
    private var showHorizontalGuideline = false
    private var isDragging = false
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    private val guidelinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A6D")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
    }

    // ── UI components ─────────────────────────────────────────────────────────
    private val editText: EditText = EditText(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setTextColor(Color.WHITE)
        // Text size set in pixels later
        setPadding(16, 8, 16, 8)
        hint = "Type here"
        setHintTextColor(Color.argb(100, 255, 255, 255))
        gravity = Gravity.CENTER
        isSingleLine = false
        maxLines = 5
        isCursorVisible = true

        // Shadow for readability on any video background
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // ── Selection border paint ────────────────────────────────────────────────
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        setShadowLayer(4f, 0f, 0f, Color.parseColor("#80000000"))
    }

    private val selectionRect = RectF()

    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A6D")
        style = Paint.Style.FILL
    }
    
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    fun setVideoSize(width: Int, height: Int) {
        this.videoWidth = width
        this.videoHeight = height
        post {
            updateFontSizeOnScreen()
            positionEditTextFromRelative()
            invalidate()
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

    private fun updateFontSizeOnScreen() {
        val videoRect = getVideoRect()
        val scale = if (videoHeight > 0) videoRect.height() / videoHeight.toFloat() else 1f
        val fontSizeOnScreen = currentFontSize * scale
        editText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, fontSizeOnScreen)
    }

    init {
        // Initially hidden until activated
        visibility = GONE

        // Add the EditText as a child
        val editTextParams = LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(editText, editTextParams)

        // Track text changes
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onTextChanged?.invoke(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Allow this view to draw over its children (for the selection border)
        setWillNotDraw(false)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Activate the text editing mode with an optional pre-filled text. */
    fun activate(initialText: String = "", fontSize: Int = 36) {
        isEditingActive = true
        currentFontSize = fontSize
        relativeX = 0.5f
        relativeY = 0.5f
        currentColorString = "#FFFFFF"

        editText.setText(initialText)
        editText.setTextColor(Color.WHITE)
        updateFontSizeOnScreen()
        visibility = VISIBLE

        // Position at center
        post {
            positionEditTextFromRelative()
            editText.requestFocus()
            showKeyboard()
        }
    }

    /** Activate for re-editing an existing text operation. */
    fun activateForEdit(op: com.tharunbirla.librecuts.models.EditOperation.AddText) {
        isEditingActive = true
        currentFontSize = op.fontSize
        relativeX = op.relativeX ?: 0.5f
        relativeY = op.relativeY ?: 0.5f
        currentColorString = op.color

        editText.setText(op.text)
        try {
            editText.setTextColor(Color.parseColor(op.color))
        } catch (e: Exception) {
            editText.setTextColor(Color.WHITE)
        }
        updateFontSizeOnScreen()
        visibility = VISIBLE

        post {
            positionEditTextFromRelative()
            editText.requestFocus()
        }
    }

    /** Deactivate and hide the text editor. */
    fun deactivate() {
        isEditingActive = false
        visibility = GONE
        hideKeyboard()
        editText.setText("")
    }

    /** Expose a way to focus the input field and pop the keyboard. */
    fun requestEditingFocus() {
        editText.requestFocus()
        showKeyboard()
    }

    /** Commit the current text and position, invoke the callback. */
    fun commitText() {
        val text = editText.text.toString().trim()
        if (text.isNotEmpty()) {
            // Compute final relative position from EditText's current layout position
            updateRelativePosition()
            onTextCommitted?.invoke(text, currentFontSize, relativeX, relativeY, currentColorString)
        }
        deactivate()
    }

    /** Get the current text. */
    fun getText(): String = editText.text.toString()

    /** Update the font size from external toolbar. */
    fun setFontSize(size: Int) {
        currentFontSize = size.coerceIn(8, 500)
        updateFontSizeOnScreen()
        invalidate()
    }

    /** Increase font size by a step. */
    fun increaseFontSize(step: Int = 2): Int {
        setFontSize(currentFontSize + step)
        return currentFontSize
    }

    /** Decrease font size by a step. */
    fun decreaseFontSize(step: Int = 2): Int {
        setFontSize(currentFontSize - step)
        return currentFontSize
    }

    fun getCurrentFontSize(): Int = currentFontSize

    // ── Touch handling for drag ───────────────────────────────────────────────

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
                val editLeft = editText.left.toFloat()
                val editTop = editText.top.toFloat()
                val editRight = editText.right.toFloat()
                val editBottom = editText.bottom.toFloat()

                if (touchX in editLeft..editRight && touchY in editTop..editBottom) {
                    return false
                }

                isDragging = true
                dragOffsetX = touchX - editText.x
                dragOffsetY = touchY - editText.y
                return true
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
                dragOffsetX = event.x - editText.x
                dragOffsetY = event.y - editText.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    val videoRect = getVideoRect()
                    val centerX = event.x - dragOffsetX + editText.width / 2f
                    val centerY = event.y - dragOffsetY + editText.height / 2f

                    val constrainedCenterX = centerX.coerceIn(videoRect.left, videoRect.right)
                    val constrainedCenterY = centerY.coerceIn(videoRect.top, videoRect.bottom)

                    val videoCenterX = videoRect.centerX()
                    val videoCenterY = videoRect.centerY()
                    val threshold = 16f

                    var snappedX = constrainedCenterX
                    var snappedY = constrainedCenterY

                    if (isSnappingEnabled) {
                        if (Math.abs(constrainedCenterX - videoCenterX) < threshold) {
                            snappedX = videoCenterX
                            showVerticalGuideline = true
                        } else {
                            showVerticalGuideline = false
                        }
                        if (Math.abs(constrainedCenterY - videoCenterY) < threshold) {
                            snappedY = videoCenterY
                            showHorizontalGuideline = true
                        } else {
                            showHorizontalGuideline = false
                        }
                    } else {
                        showVerticalGuideline = false
                        showHorizontalGuideline = false
                    }

                    editText.x = snappedX - editText.width / 2f
                    editText.y = snappedY - editText.height / 2f
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    isDragging = false
                    showVerticalGuideline = false
                    showHorizontalGuideline = false
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

        if (isEditingActive && editText.text.isNotEmpty()) {
            val videoRect = getVideoRect()
            if (showVerticalGuideline) {
                canvas.drawLine(videoRect.centerX(), videoRect.top, videoRect.centerX(), videoRect.bottom, guidelinePaint)
            }
            if (showHorizontalGuideline) {
                canvas.drawLine(videoRect.left, videoRect.centerY(), videoRect.right, videoRect.centerY(), guidelinePaint)
            }

            // Draw selection border around the EditText
            selectionRect.set(
                editText.x - 4f,
                editText.y - 4f,
                editText.x + editText.width + 4f,
                editText.y + editText.height + 4f
            )
            canvas.drawRect(selectionRect, selectionPaint)

            // Draw 4 corner resize handles
            val handleRadius = 14f
            
            canvas.drawCircle(selectionRect.left, selectionRect.top, handleRadius, handleFillPaint)
            canvas.drawCircle(selectionRect.left, selectionRect.top, handleRadius, handleStrokePaint)
            
            canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, handleFillPaint)
            canvas.drawCircle(selectionRect.right, selectionRect.top, handleRadius, handleStrokePaint)
            
            canvas.drawCircle(selectionRect.left, selectionRect.bottom, handleRadius, handleFillPaint)
            canvas.drawCircle(selectionRect.left, selectionRect.bottom, handleRadius, handleStrokePaint)
            
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handleFillPaint)
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handleStrokePaint)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun positionEditTextFromRelative() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val targetCenterX = videoRect.left + (relativeX * videoRect.width())
        val targetCenterY = videoRect.top + (relativeY * videoRect.height())

        editText.x = targetCenterX - editText.width / 2f
        editText.y = targetCenterY - editText.height / 2f
    }

    private fun updateRelativePosition() {
        if (width <= 0 || height <= 0) return
        val videoRect = getVideoRect()

        val centerX = editText.x + editText.width / 2f
        val centerY = editText.y + editText.height / 2f

        relativeX = if (videoRect.width() > 0) ((centerX - videoRect.left) / videoRect.width()).coerceIn(0f, 1f) else 0.5f
        relativeY = if (videoRect.height() > 0) ((centerY - videoRect.top) / videoRect.height()).coerceIn(0f, 1f) else 0.5f
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }
}
