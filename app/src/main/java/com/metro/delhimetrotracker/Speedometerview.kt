package com.metro.delhimetrotracker.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentSpeed = 0f
    private var maxSpeed = 120f // Maximum speed on the gauge

    // Paint objects
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 20f
        strokeCap = Paint.Cap.ROUND
    }

    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        strokeWidth = 8f
    }

    private val centerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = Color.parseColor("#8A94B8")
        textSize = 24f
    }

    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3A4565")
        strokeWidth = 3f
    }

    private val rectF = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0f, maxSpeed)
        invalidate()
    }

    fun setMaxSpeed(max: Float) {
        maxSpeed = max
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = min(w, h) / 2f - 40f

        val left = centerX - radius
        val top = centerY - radius
        val right = centerX + radius
        val bottom = centerY + radius
        rectF.set(left, top, right, bottom)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw background arc (track)
        arcPaint.color = Color.parseColor("#1A1F3A")
        canvas.drawArc(rectF, 135f, 270f, false, arcPaint)

        // Draw colored arc based on speed
        val sweepAngle = (currentSpeed / maxSpeed) * 270f
        arcPaint.shader = LinearGradient(
            centerX - radius, centerY,
            centerX + radius, centerY,
            getSpeedColor(currentSpeed),
            getSpeedColor(currentSpeed),
            Shader.TileMode.CLAMP
        )
        canvas.drawArc(rectF, 135f, sweepAngle, false, arcPaint)
        arcPaint.shader = null

        // Draw tick marks
        drawTickMarks(canvas)

        // Draw speed value
        textPaint.textSize = 72f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(
            String.format("%.0f", currentSpeed),
            centerX,
            centerY + 20f,
            textPaint
        )

        // Draw km/h label
        labelPaint.textSize = 24f
        canvas.drawText("km/h", centerX, centerY + 55f, labelPaint)

        // Draw needle
        drawNeedle(canvas)

        // Draw center circle
        canvas.drawCircle(centerX, centerY, 15f, centerCirclePaint)
    }

    private fun drawTickMarks(canvas: Canvas) {
        val startAngle = 135f
        val sweepAngle = 270f
        val tickInterval = 20 // Every 20 km/h

        for (i in 0..(maxSpeed.toInt() / tickInterval)) {
            val speed = i * tickInterval
            val angle = startAngle + (speed / maxSpeed) * sweepAngle
            val angleRad = Math.toRadians(angle.toDouble())

            val startX = centerX + (radius - 25f) * cos(angleRad).toFloat()
            val startY = centerY + (radius - 25f) * sin(angleRad).toFloat()
            val endX = centerX + (radius - 15f) * cos(angleRad).toFloat()
            val endY = centerY + (radius - 15f) * sin(angleRad).toFloat()

            canvas.drawLine(startX, startY, endX, endY, tickPaint)

            // Draw speed labels
            if (i % 2 == 0) {
                val labelX = centerX + (radius - 50f) * cos(angleRad).toFloat()
                val labelY = centerY + (radius - 50f) * sin(angleRad).toFloat()
                labelPaint.textSize = 18f
                canvas.drawText(speed.toString(), labelX, labelY + 5f, labelPaint)
            }
        }
    }

    private fun drawNeedle(canvas: Canvas) {
        val angle = 135f + (currentSpeed / maxSpeed) * 270f
        val angleRad = Math.toRadians(angle.toDouble())

        val needleLength = radius - 40f
        val endX = centerX + needleLength * cos(angleRad).toFloat()
        val endY = centerY + needleLength * sin(angleRad).toFloat()

        needlePaint.strokeWidth = 6f
        needlePaint.strokeCap = Paint.Cap.ROUND
        needlePaint.style = Paint.Style.STROKE
        canvas.drawLine(centerX, centerY, endX, endY, needlePaint)

        // Draw needle tip circle
        needlePaint.style = Paint.Style.FILL
        canvas.drawCircle(endX, endY, 8f, needlePaint)
    }

    private fun getSpeedColor(speed: Float): Int {
        return when {
            speed < 30f -> Color.parseColor("#4CAF50") // Green - slow
            speed < 60f -> Color.parseColor("#64B5F6") // Blue - moderate
            speed < 90f -> Color.parseColor("#FFA726") // Orange - fast
            else -> Color.parseColor("#EF5350") // Red - very fast
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
        setMeasuredDimension(size, size)
    }
}