package com.grupotgt.launcherkioscotgt

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.provider.Settings
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs

/** Antena puramente visual para el analizador retro de red. */
class RetroNetworkAntennaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val phosphorGreen = Color.parseColor("#9CFF9F")
    private val fixedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphorGreen
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }
    private val wavePaint = Paint(fixedPaint).apply { strokeWidth = 1.8f }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = phosphorGreen
        style = Paint.Style.FILL
    }
    private val wavePaths = arrayOf(
        Path().apply {
            moveTo(29f, 21f)
            cubicTo(25f, 25f, 25f, 31f, 29f, 35f)
            moveTo(43f, 21f)
            cubicTo(47f, 25f, 47f, 31f, 43f, 35f)
        },
        Path().apply {
            moveTo(24f, 16f)
            cubicTo(17f, 22f, 17f, 34f, 24f, 40f)
            moveTo(48f, 16f)
            cubicTo(55f, 22f, 55f, 34f, 48f, 40f)
        },
        Path().apply {
            moveTo(19f, 11f)
            cubicTo(9f, 20f, 9f, 36f, 19f, 45f)
            moveTo(53f, 11f)
            cubicTo(63f, 20f, 63f, 36f, 53f, 45f)
        }
    )
    private val tipPath = Path().apply {
        moveTo(36f, 13f)
        lineTo(39f, 16f)
        lineTo(36f, 19f)
        lineTo(33f, 16f)
        close()
    }

    private var signalActive = false
    private var phase = 0f
    private var animator: ValueAnimator? = null

    fun setSignalActive(active: Boolean) {
        if (signalActive == active) return
        signalActive = active
        if (active) startAnimationIfPossible() else stopAnimation(release = false)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / 72f, height / 52f)
        val offsetX = (width - 72f * scale) / 2f
        val offsetY = (height - 52f * scale) / 2f

        canvas.save()
        canvas.translate(offsetX, offsetY)
        canvas.scale(scale, scale)

        drawWave(canvas, 0)
        drawWave(canvas, 1)
        drawWave(canvas, 2)

        fixedPaint.alpha = 255
        canvas.drawLine(36f, 19f, 36f, 45f, fixedPaint)
        canvas.drawLine(32f, 45f, 40f, 45f, fixedPaint)
        canvas.drawLine(30f, 48f, 42f, 48f, fixedPaint)
        canvas.drawLine(33f, 24f, 36f, 20f, fixedPaint)
        canvas.drawLine(36f, 20f, 39f, 24f, fixedPaint)

        canvas.drawPath(tipPath, tipPaint)
        canvas.restore()
    }

    private fun drawWave(canvas: Canvas, index: Int) {
        wavePaint.alpha = waveAlpha(index)
        canvas.drawPath(wavePaths[index], wavePaint)
    }

    private fun waveAlpha(index: Int): Int {
        if (!signalActive) return 92
        if (!systemAnimationsEnabled()) return 210
        val center = 0.16f + index * 0.29f
        val distance = abs(phase - center)
        val intensity = (1f - distance / 0.24f).coerceIn(0f, 1f)
        return (82 + 173 * intensity).toInt()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startAnimationIfPossible()
    }

    override fun onDetachedFromWindow() {
        stopAnimation(release = true)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startAnimationIfPossible() else stopAnimation(release = false)
    }

    private fun startAnimationIfPossible() {
        if (!signalActive || !isShown || !systemAnimationsEnabled()) {
            stopAnimation(release = false)
            return
        }
        val current = animator
        if (current?.isStarted == true) return

        val next = current ?: ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
        }.also { animator = it }
        next.start()
    }

    private fun stopAnimation(release: Boolean) {
        animator?.cancel()
        phase = 0f
        if (release) {
            animator?.removeAllUpdateListeners()
            animator = null
        }
    }

    private fun systemAnimationsEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) != 0f
        }
}
