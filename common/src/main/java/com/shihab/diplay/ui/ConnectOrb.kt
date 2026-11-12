// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.theme.ShellMotion

/**
 * Primary connection control. Idle and connected states are static; progress motion is only active
 * while a connection attempt is running.
 */
class ConnectOrb(context: Context) : View(context) {
    init {
        contentDescription = context.getString(R.string.home_connect_carplay)
    }

    /** 已连接的静止成功环。转圈时不画。 */
    var connected: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            syncMotion()
        }

    /** 0..1，连接中外圈已经走过的比例。 */
    var progress: Float = 0f
        set(value) {
            val next = value.coerceIn(0f, 1f)
            if (field == next) return
            field = next
            invalidate()
        }

    private var spinning = false
    private var focusRing = false
    private var spinDegrees = 0f
    private var pulse = 0f
    private var animator: ObjectAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val oval = RectF()

    fun setSpinning(on: Boolean) {
        if (on == spinning) return
        spinning = on
        syncMotion()
    }

    /** ObjectAnimator 目标。不要从布局代码里调用。 */
    fun setSpinDegrees(value: Float) {
        spinDegrees = value
        invalidate()
    }

    /** Steering-wheel focus ring; hidden during connection progress. */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        focusRing = gainFocus
        invalidate()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun setPressed(pressed: Boolean) {
        if (pressed != isPressed) invalidate()
        super.setPressed(pressed)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncMotion()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private fun syncMotion() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (!spinning || !isAttachedToWindow) {
            spinDegrees = 0f
            pulse = 0f
            invalidate()
            return
        }
        animator = ObjectAnimator.ofFloat(this, "spinDegrees", 0f, 360f).apply {
            duration = ShellMotion.MODAL * 2
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ShellMotion.LAYOUT * 4
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulse = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val reach = minOf(width, height) / 2f
        if (reach <= 1f) return
        val disc = reach * 0.58f
        val settled = connected && !spinning
        val tone = if (settled) GlassColors.SUCCESS else GlassColors.ACCENT
        if (spinning) drawRipples(canvas, cx, cy, disc, reach * 0.94f, tone)
        drawDisc(canvas, cx, cy, disc, settled)
        drawRing(canvas, cx, cy, disc * 1.22f, tone)
        if (focusRing && !spinning) {
            strokePaint.strokeWidth = (reach * 0.018f).coerceAtLeast(2f)
            strokePaint.color = tone
            canvas.drawCircle(cx, cy, reach * 0.9f, strokePaint)
        }
        if (settled) drawPlay(canvas, cx, cy, disc) else drawLink(canvas, cx, cy, disc)
    }

    /** Three progress waves shown only during a connection attempt. */
    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float, tone: Int) {
        strokePaint.strokeWidth = (inner * 0.035f).coerceAtLeast(2f)
        for (i in 0 until 3) {
            val t = (pulse + i / 3f) % 1f
            val radius = inner + (outer - inner) * t
            strokePaint.color = GlassColors.withAlpha(tone, ((1f - t) * 150f).toInt())
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, disc: Float, settled: Boolean) {
        fillPaint.shader = null
        fillPaint.color = when {
            settled -> GlassColors.BTN_SUCCESS
            isPressed -> GlassColors.ACCENT_DEEP
            else -> GlassColors.ACCENT
        }
        canvas.drawCircle(cx, cy, disc, fillPaint)
    }

    /**
     * 空闲：一截高光在圆外慢慢走，不画闭合的圈。
     * 连接中：进度弧按阶段变长，亮头跟着转。
     */
    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, radius: Float, tone: Int) {
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        strokePaint.strokeWidth = (radius * 0.055f).coerceAtLeast(3f)
        if (connected && !spinning) return
        if (spinning) {
            val sweep = (360f * progress).coerceIn(28f, 360f)
            strokePaint.color = tone
            canvas.drawArc(oval, spinDegrees - 90f, sweep, false, strokePaint)
            strokePaint.color = GlassColors.NAV_SELECTED_TEXT
            canvas.drawArc(oval, spinDegrees - 90f + sweep - 14f, 16f, false, strokePaint)
        }
    }

    /** 两枚交叠的胶囊，表示还没连上。 */
    private fun drawLink(canvas: Canvas, cx: Float, cy: Float, disc: Float) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = (disc * 0.07f).coerceAtLeast(2f)
        iconPaint.color = if (isPressed) GlassColors.PRIMARY_CONTAINER_TEXT else GlassColors.NAV_SELECTED_TEXT
        val w = disc * 0.62f
        val h = disc * 0.28f
        val r = h / 2f
        canvas.save()
        canvas.rotate(-42f, cx, cy)
        oval.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(oval, r, r, iconPaint)
        canvas.restore()
        canvas.save()
        canvas.rotate(42f, cx, cy)
        oval.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
        canvas.drawRoundRect(oval, r, r, iconPaint)
        canvas.restore()
    }

    /** 已连接：播放三角。 */
    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, disc: Float) {
        iconPaint.style = Paint.Style.FILL
        iconPaint.color = GlassColors.SUCCESS_TEXT
        val height = disc * 0.36f
        val width = height * 0.86f
        val left = cx - width * 0.28f
        val path = android.graphics.Path()
        path.moveTo(left, cy - height / 2f)
        path.lineTo(left + width, cy)
        path.lineTo(left, cy + height / 2f)
        path.close()
        canvas.drawPath(path, iconPaint)
    }
}
