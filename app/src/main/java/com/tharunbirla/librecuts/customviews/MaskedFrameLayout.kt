package com.tharunbirla.librecuts.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Region
import android.os.Build
import android.util.AttributeSet
import android.widget.FrameLayout
import com.tharunbirla.librecuts.models.EditOperation

class MaskedFrameLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var maskConfig: EditOperation.MaskConfig = EditOperation.MaskConfig()
        set(value) {
            field = value
            invalidate()
        }

    override fun dispatchDraw(canvas: Canvas) {
        if (maskConfig.shape != EditOperation.MaskShape.NONE) {
            canvas.save()
            val path = Path()
            val cx = width * maskConfig.relativeX
            val cy = height * maskConfig.relativeY
            val mw = width * maskConfig.relativeWidth
            val mh = height * maskConfig.relativeHeight

            // Rotate canvas for mask if needed (usually just rotating the whole thing)
            canvas.rotate(maskConfig.rotationAngle, cx, cy)

            when (maskConfig.shape) {
                EditOperation.MaskShape.RECTANGLE -> path.addRect(cx - mw/2, cy - mh/2, cx + mw/2, cy + mh/2, Path.Direction.CW)
                EditOperation.MaskShape.ELLIPSE -> path.addOval(cx - mw/2, cy - mh/2, cx + mw/2, cy + mh/2, Path.Direction.CW)
                EditOperation.MaskShape.SPLIT -> path.addRect(-width.toFloat(), cy, width * 2f, height * 2f, Path.Direction.CW)
                EditOperation.MaskShape.SHUTTER -> path.addRect(-width.toFloat(), cy - mh/2, width * 2f, cy + mh/2, Path.Direction.CW)
                else -> {}
            }

            if (maskConfig.isInverted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    canvas.clipOutPath(path)
                } else {
                    canvas.clipPath(path, Region.Op.DIFFERENCE)
                }
            } else {
                canvas.clipPath(path)
            }
            super.dispatchDraw(canvas)
            canvas.restore()
        } else {
            super.dispatchDraw(canvas)
        }
    }
}
