// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import com.shihab.diplay.host.R

/**
 * 玻璃连接钮。
 * 空闲：日景是磨砂圆片，夜景用系统蓝圆钮，免得融进炭色面板。边缘有一截慢慢走动的高光。
 * 连接中：圆外扩出波纹，进度环按阶段长出来，环头继续转。
 * 连上：圆片换成成功色玻璃，中间是播放，点一下回到投屏。
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
            startSweep(fast = spinning)
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
        startSweep(fast = on)
    }

    /** ObjectAnimator 目标。不要从布局代码里调用。 */
    fun setSpinDegrees(value: Float) {
        spinDegrees = value
        invalidate()
    }

    /** 方向盘/旋钮焦点：在玻璃外画一圈；连接中不画。 */
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
        startSweep(fast = spinning)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    private fun startSweep(fast: Boolean) {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        if (connected && !fast) {
            spinDegrees = 0f
            pulse = 0f
            invalidate()
            return
        }
        animator = ObjectAnimator.ofFloat(this, "spinDegrees", 0f, 360f).apply {
            duration = if (fast) 1200 else 7200
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
        if (!isAttachedToWindow) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (fast) 1500 else 2600
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
        val tone = if (connected && !spinning) GlassColors.SUCCESS else GlassColors.ACCENT
        val pressed = isPressed
        if (pressed) {
            canvas.save()
            canvas.scale(0.96f, 0.96f, cx, cy)
        }
        if (spinning) drawRipples(canvas, cx, cy, disc, reach * 0.94f, tone)
        drawDisc(canvas, cx, cy, disc, tone)
        drawRing(canvas, cx, cy, disc * 1.22f, tone)
        if (focusRing && !spinning) {
            strokePaint.strokeWidth = (reach * 0.018f).coerceAtLeast(2f)
            strokePaint.color = tone
            canvas.drawCircle(cx, cy, reach * 0.9f, strokePaint)
        }
        if (connected && !spinning) drawPlay(canvas, cx, cy, disc) else drawLink(canvas, cx, cy, disc, tone)
        if (pressed) canvas.restore()
    }

    /** 连接中从圆片向外淡出的三道玻璃波。 */
    private fun drawRipples(canvas: Canvas, cx: Float, cy: Float, inner: Float, outer: Float, tone: Int) {
        strokePaint.strokeWidth = (inner * 0.035f).coerceAtLeast(2f)
        for (i in 0 until 3) {
            val t = (pulse + i / 3f) % 1f
            val radius = inner + (outer - inner) * t
            strokePaint.color = withAlpha(tone, ((1f - t) * 150f).toInt())
            canvas.drawCircle(cx, cy, radius, strokePaint)
        }
    }

    /** 磨砂圆片。连上后掺进成功色，圆心仍然透出底图。 */
    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, disc: Float, tone: Int) {
        val settled = connected && !spinning
        val top = when {
            settled -> withAlpha(tone, if (GlassColors.isDark) 0xCC else 0x66)
            GlassColors.isDark -> GlassColors.ACCENT_LIFT
            else -> GlassColors.plateTop()
        }
        val bottom = when {
            settled -> withAlpha(tone, if (GlassColors.isDark) 0x88 else 0x33)
            GlassColors.isDark -> GlassColors.ACCENT_DEEP
            else -> GlassColors.plateBottom()
        }
        fillPaint.shader = LinearGradient(cx, cy - disc, cx, cy + disc, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, disc, fillPaint)
        fillPaint.shader = null
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
            strokePaint.color = 0xF2FFFFFF.toInt()
            canvas.drawArc(oval, spinDegrees - 90f + sweep - 14f, 16f, false, strokePaint)
        } else {
            strokePaint.color = withAlpha(0xFFFFFFFF.toInt(), 0xCC)
            canvas.drawArc(oval, spinDegrees - 90f, 36f, false, strokePaint)
        }
    }

    /** 两枚交叠的胶囊，表示还没连上。 */
    private fun drawLink(canvas: Canvas, cx: Float, cy: Float, disc: Float, tone: Int) {
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = (disc * 0.07f).coerceAtLeast(2f)
        iconPaint.color = when {
            spinning -> tone
            GlassColors.isDark -> GlassColors.NAV_SELECTED_TEXT
            else -> GlassColors.INK
        }
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
        iconPaint.color = GlassColors.INK
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

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
