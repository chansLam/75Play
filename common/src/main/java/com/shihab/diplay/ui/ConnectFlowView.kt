package com.shihab.diplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import com.shihab.diplay.host.R

/**
 * 连接按钮下方的五步。只在连接过程中显示：点亮当前步。未连接和已连接都不画。
 */
class ConnectFlowView(context: Context) : View(context) {
    /** -1 未开始，0..4 当前步，[ConnectFlow.COUNT] 全部完成。 */
    var activeIndex: Int = -1
        private set
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    fun showIdle() = setIndex(-1)

    fun showConnected() = setIndex(ConnectFlow.COUNT)

    /** 认不出的阶段保持原位。 */
    fun showStage(index: Int) {
        if (index < 0) return
        setIndex(index.coerceAtMost(ConnectFlow.COUNT - 1))
    }

    private fun setIndex(index: Int) {
        if (activeIndex == index) return
        activeIndex = index
        contentDescription = describe()
        syncPulse()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (contentDescription.isNullOrEmpty()) contentDescription = describe()
        syncPulse()
    }

    override fun onDetachedFromWindow() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private fun syncPulse() {
        val live = activeIndex in 0 until ConnectFlow.COUNT
        if (!live || !isAttachedToWindow) {
            pulseAnimator?.cancel()
            pulseAnimator = null
            pulse = 0f
            return
        }
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun describe(): String {
        val names = (0 until ConnectFlow.COUNT).joinToString(" · ") {
            context.getString(ConnectFlow.titleRes(it))
        }
        return if (activeIndex in 0 until ConnectFlow.COUNT) {
            context.getString(R.string.connect_flow_current, context.getString(ConnectFlow.titleRes(activeIndex))) +
                " · " + names
        } else {
            names
        }
    }

    override fun onDraw(canvas: Canvas) {
        val count = ConnectFlow.COUNT
        val slot = width / count.toFloat()
        if (slot <= 0f) return
        val radius = GlassColors.dp(context, 5).toFloat().coerceAtMost(slot * 0.16f).coerceAtLeast(3f)
        val cy = radius + GlassColors.dp(context, 6)
        linePaint.strokeWidth = GlassColors.dp(context, 2).toFloat().coerceAtLeast(2f)
        val current = activeIndex
        for (i in 0 until count - 1) {
            val done = current > i
            linePaint.color = if (done) GlassColors.ACCENT else (GlassColors.DIM and 0x00FFFFFF) or 0x66000000
            val y = cy
            canvas.drawLine(slot * i + slot / 2f + radius, y, slot * (i + 1) + slot / 2f - radius, y, linePaint)
        }
        textPaint.textSize = sp(11f)
        for (i in 0 until count) {
            val cx = slot * i + slot / 2f
            val done = current > i
            val now = current == i
            val ring = if (done || now) GlassColors.ACCENT else GlassColors.DIM
            fillPaint.color = ring
            if (now) {
                fillPaint.alpha = (0x55 + 0xAA * pulse).toInt()
                canvas.drawCircle(cx, cy, radius + GlassColors.dp(context, 3) * pulse, fillPaint)
            }
            fillPaint.alpha = 255
            if (done) {
                fillPaint.color = GlassColors.ACCENT
                canvas.drawCircle(cx, cy, radius, fillPaint)
            } else if (now) {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = GlassColors.dp(context, 2).toFloat().coerceAtLeast(2f)
                fillPaint.color = GlassColors.ACCENT
                canvas.drawCircle(cx, cy, radius, fillPaint)
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = GlassColors.ACCENT
                canvas.drawCircle(cx, cy, radius * (0.45f + 0.25f * pulse), fillPaint)
            } else {
                fillPaint.style = Paint.Style.STROKE
                fillPaint.strokeWidth = GlassColors.dp(context, 1).toFloat().coerceAtLeast(1f)
                fillPaint.color = GlassColors.DIM
                canvas.drawCircle(cx, cy, radius, fillPaint)
                fillPaint.style = Paint.Style.FILL
            }
            val title = context.getString(ConnectFlow.titleRes(i))
            var size = sp(11f)
            textPaint.textSize = size
            val limit = slot - GlassColors.dp(context, 2)
            while (textPaint.measureText(title) > limit && size > sp(8f)) {
                size -= 1f
                textPaint.textSize = size
            }
            textPaint.color = when {
                now -> GlassColors.ACCENT
                done -> GlassColors.TEXT_BODY
                else -> GlassColors.MUTED
            }
            val baseline = cy + radius + GlassColors.dp(context, 4) - textPaint.ascent()
            canvas.drawText(title, cx, baseline, textPaint)
        }
    }

    private fun sp(value: Float): Float = value * resources.displayMetrics.scaledDensity
}
