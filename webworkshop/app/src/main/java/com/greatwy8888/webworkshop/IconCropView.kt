package com.greatwy8888.webworkshop

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Manual square crop surface. It never applies filters, corner rounding, background,
 * shadows, or color changes; it only exports the user-positioned square selection.
 */
class IconCropView(context: Context, private val source: Bitmap) : View(context) {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x88000000.toInt()
        style = Paint.Style.FILL
    }
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var initialized = false
    private var lastX = 0f
    private var lastY = 0f
    private var activePointer = -1
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val old = scale
            scale = (scale * detector.scaleFactor).coerceIn(minimumScale(), maximumScale())
            val factor = scale / old
            offsetX = detector.focusX - (detector.focusX - offsetX) * factor
            offsetY = detector.focusY - (detector.focusY - offsetY) * factor
            clampOffsets()
            invalidate()
            return true
        }
    })

    private fun cropRect(): RectF {
        val side = min(width.toFloat(), height.toFloat())
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        return RectF(left, top, left + side, top + side)
    }

    private fun minimumScale(): Float {
        val rect = cropRect()
        return max(rect.width() / source.width, rect.height() / source.height)
    }

    private fun maximumScale(): Float = minimumScale() * 8f

    private fun initialize() {
        if (initialized || width == 0 || height == 0) return
        scale = minimumScale()
        val rect = cropRect()
        offsetX = rect.centerX() - source.width * scale / 2f
        offsetY = rect.centerY() - source.height * scale / 2f
        initialized = true
        clampOffsets()
    }

    private fun clampOffsets() {
        val rect = cropRect()
        val imageWidth = source.width * scale
        val imageHeight = source.height * scale
        offsetX = offsetX.coerceIn(rect.right - imageWidth, rect.left)
        offsetY = offsetY.coerceIn(rect.bottom - imageHeight, rect.top)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff202830.toInt())
        initialize()
        val crop = cropRect()
        canvas.save()
        canvas.clipRect(crop)
        canvas.drawBitmap(source, null,
            RectF(offsetX, offsetY, offsetX + source.width * scale, offsetY + source.height * scale),
            imagePaint)
        canvas.restore()
        // Dim everything outside the square crop area.
        canvas.drawRect(0f, 0f, width.toFloat(), crop.top, shadePaint)
        canvas.drawRect(0f, crop.bottom, width.toFloat(), height.toFloat(), shadePaint)
        canvas.drawRect(0f, crop.top, crop.left, crop.bottom, shadePaint)
        canvas.drawRect(crop.right, crop.top, width.toFloat(), crop.bottom, shadePaint)
        canvas.drawRect(crop, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activePointer = event.getPointerId(0)
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> return true
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    offsetX += dx
                    offsetY += dy
                    clampOffsets()
                    invalidate()
                }
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val next = if (index == 0) 1 else 0
                if (next < event.pointerCount) {
                    activePointer = event.getPointerId(next)
                    lastX = event.getX(next)
                    lastY = event.getY(next)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointer = -1
                performClick()
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun exportBitmap(outputSize: Int): Bitmap {
        initialize()
        val crop = cropRect()
        val sourceLeft = ((crop.left - offsetX) / scale).coerceIn(0f, source.width.toFloat())
        val sourceTop = ((crop.top - offsetY) / scale).coerceIn(0f, source.height.toFloat())
        val sourceSide = crop.width() / scale
        val safeSide = min(sourceSide, min(source.width - sourceLeft, source.height - sourceTop))
        require(safeSide > 0f) { "裁切区域无效" }
        val result = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val sourceRect = Rect(
            sourceLeft.toInt().coerceIn(0, source.width - 1),
            sourceTop.toInt().coerceIn(0, source.height - 1),
            (sourceLeft + safeSide).toInt().coerceIn(1, source.width),
            (sourceTop + safeSide).toInt().coerceIn(1, source.height)
        )
        canvas.drawBitmap(
            source,
            sourceRect,
            Rect(0, 0, outputSize, outputSize),
            imagePaint
        )
        return result
    }
}
