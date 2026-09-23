package io.github.rvbcrs.wallmode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

internal enum class PulseVisualMode { LISTENING, THINKING, RESPONDING, FAILED }

internal class PulseListeningVisualView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var mode = PulseVisualMode.LISTENING
    private val startedAt = System.currentTimeMillis()

    fun setMode(value: PulseVisualMode) {
        mode = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val seconds = (System.currentTimeMillis() - startedAt) / 1000f
        val cx = width / 2f
        val cy = height * 0.43f
        val base = minOf(width, height) * 0.115f
        val accent = when (mode) {
            PulseVisualMode.LISTENING -> Color.rgb(50, 222, 235)
            PulseVisualMode.THINKING -> Color.rgb(116, 132, 255)
            PulseVisualMode.RESPONDING -> Color.rgb(70, 234, 172)
            PulseVisualMode.FAILED -> Color.rgb(255, 105, 116)
        }

        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, base * 2.8f,
            intArrayOf(Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent)), Color.TRANSPARENT),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, base * 2.8f, paint)
        paint.shader = null

        repeat(3) { index ->
            val phase = ((seconds * 0.55f + index / 3f) % 1f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = Color.argb(((1f - phase) * 115).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent))
            canvas.drawCircle(cx, cy, base * (1.05f + phase * 1.55f), paint)
        }

        paint.style = Paint.Style.FILL
        paint.color = accent
        val barWidth = base * 0.11f
        val gap = base * 0.10f
        repeat(9) { index ->
            val x = cx + (index - 4) * (barWidth + gap)
            val motion = abs(sin(seconds * (if (mode == PulseVisualMode.THINKING) 4.2 else 6.8) + index * PI / 4)).toFloat()
            val barHeight = base * (0.22f + motion * if (mode == PulseVisualMode.RESPONDING) 0.65f else 1.05f)
            canvas.drawRoundRect(x - barWidth / 2, cy - barHeight / 2, x + barWidth / 2, cy + barHeight / 2, barWidth, barWidth, paint)
        }
        postInvalidateDelayed(32L)
    }
}
