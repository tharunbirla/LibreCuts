package com.tharunbirla.librecuts.customviews

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class HSVColorPickerView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val hsv = floatArrayOf(0f, 1f, 1f) // Hue (0-360), Saturation (0-1), Value (0-1)
    
    private val svRect = RectF()
    private val hueRect = RectF()
    
    private val svPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.WHITE
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private var hueShader: Shader? = null
    
    var onColorChanged: ((Int) -> Unit)? = null
    
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }
    
    fun getColor(): Int {
        return Color.HSVToColor(hsv)
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        val hueHeight = 24f * density
        val margin = 16f * density
        
        svRect.set(0f, 0f, w.toFloat(), h - hueHeight - margin)
        hueRect.set(0f, h - hueHeight, w.toFloat(), h.toFloat())
        
        val hueColors = intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        )
        hueShader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f, hueColors, null, Shader.TileMode.CLAMP)
        huePaint.shader = hueShader
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Draw SV Square
        val pureColor = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        val valShader = LinearGradient(0f, svRect.top, 0f, svRect.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
        val satShader = LinearGradient(svRect.left, 0f, svRect.right, 0f, Color.WHITE, pureColor, Shader.TileMode.CLAMP)
        
        val composeShader = ComposeShader(valShader, satShader, PorterDuff.Mode.MULTIPLY)
        svPaint.shader = composeShader
        canvas.drawRoundRect(svRect, 12f, 12f, svPaint)
        
        // Draw SV pointer
        val px = svRect.left + hsv[1] * svRect.width()
        val py = svRect.top + (1f - hsv[2]) * svRect.height()
        canvas.drawCircle(px, py, 16f, pointerPaint)
        
        // Draw Hue Bar
        canvas.drawRoundRect(hueRect, 12f, 12f, huePaint)
        
        // Draw Hue pointer
        val hx = hueRect.left + (hsv[0] / 360f) * hueRect.width()
        canvas.drawLine(hx, hueRect.top - 4f, hx, hueRect.bottom + 4f, pointerPaint)
    }
    
    private var trackingMode = 0 // 0 = none, 1 = SV, 2 = Hue
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (svRect.contains(x, y)) {
                    trackingMode = 1
                } else if (hueRect.contains(x, y) || (y > svRect.bottom && y < hueRect.top)) {
                    trackingMode = 2
                } else {
                    return false
                }
                updateColorFromTouch(x, y)
                return true
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                updateColorFromTouch(x, y)
                if (event.action == MotionEvent.ACTION_UP) {
                    trackingMode = 0
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
    
    private fun updateColorFromTouch(x: Float, y: Float) {
        if (trackingMode == 1) {
            val sat = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
            val v = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
            hsv[1] = sat
            hsv[2] = v
            onColorChanged?.invoke(getColor())
            invalidate()
        } else if (trackingMode == 2) {
            val h = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f
            hsv[0] = h
            onColorChanged?.invoke(getColor())
            invalidate()
        }
    }
}
