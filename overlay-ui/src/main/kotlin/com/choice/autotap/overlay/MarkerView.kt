package com.choice.autotap.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** A numbered crosshair. The exact tap point is the center of the view. */
class MarkerView(context: Context, label: String, markerColor: Int) : View(context) {

    var label: String = label
        set(value) {
            field = value
            invalidate()
        }

    private val sizePx = context.dp(SIZE_DP)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.argb(110, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
        style = Paint.Style.FILL
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = markerColor
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f).toFloat()
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        strokeWidth = context.dp(1f).toFloat()
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = context.dp(12f).toFloat()
        isFakeBoldText = true
        setShadowLayer(3f, 0f, 0f, Color.BLACK)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(sizePx, sizePx)
    }

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        val r = c - stroke.strokeWidth
        canvas.drawCircle(c, c, r, fill)
        canvas.drawCircle(c, c, r, stroke)
        canvas.drawLine(c, 0f, c, width.toFloat(), cross)
        canvas.drawLine(0f, c, width.toFloat(), c, cross)
        canvas.drawText(label, c + r / 2.2f, c - r / 3f, text)
    }

    companion object {
        const val SIZE_DP = 52f
    }
}
