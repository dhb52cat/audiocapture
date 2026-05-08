package com.audiocapture

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * 录音时显示的动态波形动画
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private var phase = 0f
    private var amplitude = 0f
    private var targetAmplitude = 0f
    private var isAnimating = false

    private val runnable = object : Runnable {
        override fun run() {
            phase += 0.08f
            // 平滑振幅过渡
            amplitude += (targetAmplitude - amplitude) * 0.1f
            // 随机抖动模拟真实音频
            targetAmplitude = if (isAnimating) {
                0.5f + Random.nextFloat() * 0.5f
            } else {
                0f
            }
            invalidate()
            if (isAnimating || amplitude > 0.01f) {
                postDelayed(this, 16)
            }
        }
    }

    fun startAnimation() {
        isAnimating = true
        targetAmplitude = 0.8f
        removeCallbacks(runnable)
        post(runnable)
    }

    fun stopAnimation() {
        isAnimating = false
        targetAmplitude = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitude < 0.01f) return

        val w = width.toFloat()
        val h = height.toFloat()
        val centerY = h / 2f

        // 绘制 3 条不同频率的波形叠加
        val waves = listOf(
            Triple(1f, 1.0f, Color.parseColor("#FF4444")),
            Triple(2f, 0.5f, Color.parseColor("#FF8888")),
            Triple(0.5f, 0.7f, Color.parseColor("#FFAAAA"))
        )

        waves.forEach { (freq, ampFactor, color) ->
            paint.color = color
            paint.alpha = (180 * amplitude).toInt().coerceIn(0, 255)

            val path = Path()
            val steps = 120
            for (i in 0..steps) {
                val x = w * i / steps
                val normalX = i.toFloat() / steps
                val y = centerY + centerY * 0.6f * amplitude * ampFactor *
                        sin((normalX * Math.PI * 4 * freq + phase).toFloat()).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
}
