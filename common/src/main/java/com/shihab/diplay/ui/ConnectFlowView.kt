package com.shihab.diplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.theme.ShellMotion
import com.shihab.diplay.ui.theme.ShellSpacing
import com.shihab.diplay.ui.theme.ShellType

/**
 * Connection progress with five explicit stages: preparation, network, phone, handshake, and video.
 * 只在连接过程中显示。
 */
class ConnectFlowView(context: Context) : View(context) {
    /** -1 未开始，0..4 当前步，[ConnectFlow.COUNT] 全部完成。 */
    var activeIndex: Int = -1
        private set
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val track = RectF()
    /** 液体已填充段，复用避免每帧分配。drawRoundRect(RectF,…) 是 API 1 重载；float 重载要到 API 21 才有。 */
    private val liquid = RectF()

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
            duration = ShellMotion.MODAL * 2
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        textPaint.textSize = sp(ShellType.CAPTION)
        val nodeR = GlassColors.dp(context, ShellSpacing.SMALL).toFloat()
        val textH = textPaint.descent() - textPaint.ascent()
        val wanted = (nodeR + GlassColors.dp(context, ShellSpacing.EXTRA_SMALL) + nodeR * 1.25f +
            GlassColors.dp(context, ShellSpacing.SMALL) + textH +
            GlassColors.dp(context, ShellSpacing.EXTRA_SMALL)).toInt()
        setMeasuredDimension(width, wanted.coerceAtLeast(LayoutMetrics.connectFlowH(context)))
    }

    override fun onDraw(canvas: Canvas) {
        val count = ConnectFlow.COUNT
        val inset = GlassColors.dp(context, ShellSpacing.MEDIUM).toFloat()
        val span = width - inset * 2
        if (span <= 0f) return
        val seg = span / count
        textPaint.textSize = sp(ShellType.CAPTION)
        val nodeR = GlassColors.dp(context, ShellSpacing.SMALL).toFloat()
        val cy = nodeR + GlassColors.dp(context, ShellSpacing.EXTRA_SMALL).toFloat()
        val current = activeIndex
        val fraction = when {
            current < 0 -> 0f
            current >= count -> 1f
            else -> ((current + pulse) / count).coerceIn(0f, 1f)
        }
        val trackH = GlassColors.dp(context, ShellSpacing.EXTRA_SMALL).toFloat()
        track.set(inset, cy - trackH / 2f, inset + span, cy + trackH / 2f)
        fillPaint.color = GlassColors.plateBottom()
        canvas.drawRoundRect(track, trackH / 2f, trackH / 2f, fillPaint)
        if (fraction > 0f) {
            val liquidRight = track.left + track.width() * fraction
            liquid.set(track.left, track.top, liquidRight, track.bottom)
            fillPaint.color = GlassColors.ACCENT
            canvas.drawRoundRect(liquid, trackH / 2f, trackH / 2f, fillPaint)
            if (current in 0 until count) {
                fillPaint.color = GlassColors.ACCENT_LIFT
                canvas.drawCircle(liquidRight, cy, trackH * 0.85f, fillPaint)
            }
        }
        for (i in 0 until count) {
            val x = inset + seg * (i + 0.5f)
            val done = current > i || current >= count
            val now = current == i
            val r = if (now) nodeR * 1.25f else nodeR
            if (done) {
                fillPaint.color = GlassColors.ACCENT
                canvas.drawCircle(x, cy, r, fillPaint)
            } else {
                fillPaint.color = if (now) GlassColors.withAlpha(GlassColors.ACCENT, 0x55) else GlassColors.plateTop()
                canvas.drawCircle(x, cy, r, fillPaint)
            }
            val title = context.getString(ConnectFlow.titleRes(i))
            textPaint.color = when {
                now -> GlassColors.ACCENT
                done -> GlassColors.TEXT_BODY
                else -> GlassColors.MUTED
            }
            val baseline = cy + r + GlassColors.dp(context, ShellSpacing.EXTRA_SMALL) - textPaint.ascent()
            canvas.drawText(title, x, baseline, textPaint)
        }
    }

    private fun longestTitle(): String {
        var best = ""
        var width = 0f
        for (i in 0 until ConnectFlow.COUNT) {
            val title = context.getString(ConnectFlow.titleRes(i))
            val measured = textPaint.measureText(title)
            if (measured > width) {
                width = measured
                best = title
            }
        }
        return best
    }

    private fun sp(value: Float): Float = LayoutMetrics.textPx(context, value)
}
