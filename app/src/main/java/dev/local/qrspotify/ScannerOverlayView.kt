package dev.local.qrspotify

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Darkens the camera preview outside a central square and draws corner
 * brackets around it, like a typical QR-scanner viewfinder mask.
 */
class ScannerOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        // PorterDuff.CLEAR inside saveLayer needs an unaccelerated canvas.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val maskPaint = Paint().apply {
        color = Color.parseColor("#B3000000")
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#1DB954")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }

    private val boxRect = RectF()
    private val cornerLength = 70f
    private val cornerRadius = 32f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxSize = w * 0.72f
        val left = (w - boxSize) / 2f
        val top = (h - boxSize) / 2f - h * 0.06f
        boxRect.set(left, top, left + boxSize, top + boxSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val layerId = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, clearPaint)
        canvas.restoreToCount(layerId)

        drawCorner(canvas, boxRect.left, boxRect.top, 1, 1)
        drawCorner(canvas, boxRect.right, boxRect.top, -1, 1)
        drawCorner(canvas, boxRect.left, boxRect.bottom, 1, -1)
        drawCorner(canvas, boxRect.right, boxRect.bottom, -1, -1)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, dirX: Int, dirY: Int) {
        canvas.drawLine(x, y, x + cornerLength * dirX, y, cornerPaint)
        canvas.drawLine(x, y, x, y + cornerLength * dirY, cornerPaint)
    }
}
