package org.traccar.manager

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class CompassView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var targetBearing = 0f
    private var currentBearing = 0f
    private var distance = 0
    private var direction = 0
    private var showTrueNorth = false
    private var zoom = 1.0f

    /** Azimut (Kompassrichtung) in Grad, vom Magnetometer */
    private var azimuth = 0f

    private var needleBitmap: Bitmap? = null
    private var needleBitmapScaled: Bitmap? = null

    private val rosePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val roseFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(15, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 40f
        textAlign = Paint.Align.CENTER
    }
    private val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.DKGRAY
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        try {
            val resId = context.resources.getIdentifier("needle_v3", "drawable", context.packageName)
            if (resId != 0) {
                needleBitmap = BitmapFactory.decodeResource(context.resources, resId)
            }
        } catch (_: Exception) {}
        if (needleBitmap == null) {
            try {
                val stream = context.assets.open("needle_v3.png")
                needleBitmap = BitmapFactory.decodeStream(stream)
                stream.close()
            } catch (_: Exception) {}
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Stell sicher dass die View korrekt gemessen wird
        val w = View.getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val h = View.getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(
            w.coerceAtLeast(220),
            h.coerceAtLeast(220)
        )
    }

    fun updatePosition(bearing: Float, distance: Int, direction: Int) {
        this.targetBearing = bearing
        this.distance = distance
        this.direction = direction
        animateBearing()
        invalidate()
    }

    fun setAzimuth(azimuthDeg: Float) {
        azimuth = azimuthDeg
        invalidate()
    }

    fun setZoom(zoom: Float) { this.zoom = zoom.coerceIn(0.5f, 3.0f); invalidate() }
    fun toggleNorth() { showTrueNorth = !showTrueNorth; invalidate() }

    private fun animateBearing() {
        ValueAnimator.ofFloat(currentBearing, targetBearing).apply {
            interpolator = DecelerateInterpolator()
            duration = 300
            addUpdateListener { currentBearing = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(centerX, centerY) * 0.7f * zoom
        val rotation = -azimuth

        canvas.save()
        canvas.translate(centerX, centerY)

        // Compass ring
        canvas.drawCircle(0f, 0f, radius, rosePaint)
        canvas.drawCircle(0f, 0f, radius, roseFillPaint)

        // Degree ticks
        for (deg in 0 until 360 step 5) {
            val angle = Math.toRadians((deg + rotation).toDouble())
            val isMajor = deg % 45 == 0
            val outerR = if (isMajor) radius - 8f else radius - 4f
            val innerR = radius - (if (isMajor) 20f else 12f)
            canvas.drawLine(
                (outerR * cos(angle)).toFloat(),
                (outerR * sin(angle)).toFloat(),
                (innerR * cos(angle)).toFloat(),
                (innerR * sin(angle)).toFloat(),
                if (isMajor) cardinalPaint.apply { textSize = 20f } else tickPaint
            )
        }

        // Cardinal directions
        val cardinals = mapOf(0f to "N", 90f to "O", 180f to "S", 270f to "W")
        for ((angle, label) in cardinals) {
            val rad = Math.toRadians((angle + rotation).toDouble())
            val x = ((radius - 32f) * cos(rad)).toFloat()
            val y = ((radius - 32f) * sin(rad)).toFloat()
            canvas.drawText(label, x, y + 10f, cardinalPaint.apply {
                color = if (label == "N") Color.RED else Color.DKGRAY
            })
        }

        // Compass rose inner circle
        val roseRadius = radius * 0.2f
        for (i in 0..7) {
            val angle = Math.toRadians((i * 45.0 + rotation))
            val x = roseRadius * cos(angle)
            val y = roseRadius * sin(angle)
            canvas.drawCircle(x.toFloat(), y.toFloat(), 4f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.FILL })
        }

        // Draw needle — needle_v3.png zeigt auf targetBearing
        needleBitmap?.let { bmp ->
            val needleLen = radius * 1.6f
            val scale = needleLen / maxOf(bmp.width, bmp.height).toFloat()
            val scaledW = (bmp.width * scale).toInt()
            val scaledH = (bmp.height * scale).toInt()
            if (scaledW <= 0 || scaledH <= 0) return@let

            if (needleBitmapScaled == null ||
                needleBitmapScaled?.width != scaledW ||
                needleBitmapScaled?.height != scaledH) {
                needleBitmapScaled = Bitmap.createScaledBitmap(bmp, scaledW, scaledH, true)
            }

            canvas.save()
            // Nadel zeigt zum Ziel, korrigiert um Handy-Drehung
            val needleAngle = currentBearing - azimuth
            canvas.rotate(needleAngle)
            canvas.drawBitmap(
                needleBitmapScaled!!,
                -(scaledW / 2f),
                -(scaledH / 2f),
                null
            )
            canvas.restore()
        }

        canvas.restore()

        // Distance and direction text below compass
        val distText = if (distance >= 1000) "%.1f km".format(distance / 1000f) else "$distance m"
        canvas.drawText(distText, centerX, centerY + radius + 40f, distancePaint)

        val dirText = when {
            direction in 338..360 || direction in 0..22 -> "Norden"
            direction in 23..67 -> "Nordosten"
            direction in 68..112 -> "Osten"
            direction in 113..157 -> "Sudosten"
            direction in 158..202 -> "Suden"
            direction in 203..247 -> "Sudwesten"
            direction in 248..292 -> "Westen"
            direction in 293..337 -> "Nordwesten"
            else -> ""
        }
        canvas.drawText(dirText, centerX, centerY + radius + 70f, textPaint)
    }
}
