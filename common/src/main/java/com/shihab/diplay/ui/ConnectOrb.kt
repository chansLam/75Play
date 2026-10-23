// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import com.shihab.diplay.host.R

/**
 * 圆形连接按钮。空闲时外圈轻轻呼吸；连接中外圈按阶段推进，并有一道彗星光；连上后换成成功环。
 * 空闲和连接中圆里是同一个 CarPlay 画面标志；已连接换成播放，点一下回到投屏。
 * 阶段句子和五步流程画在这个 View 外面，并且只在连接中出现。
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
            invalidate()
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
    private var pulse = 0.5f
    private var animator: ObjectAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val iconPath = Path()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val oval = RectF()

    fun setSpinning(on: Boolean) {
        if (on == spinning) return
        spinning = on
        if (!on) {
            animator?.cancel()
            animator = null
            spinDegrees = 0f
            invalidate()
            return
        }
        animator?.cancel()
        animator = ObjectAnimator.ofFloat(this, "spinDegrees", 0f, 360f).apply {
            duration = 1600
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** ObjectAnimator 目标。不要从布局代码里调用。 */
    fun setSpinDegrees(value: Float) {
        spinDegrees = value
        invalidate()
    }

    /** 方向盘/旋钮焦点：在环外画一圈青环；旋转连接中不画，避免和进度弧抢视觉。 */
    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        focusRing = gainFocus
        invalidate()
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
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

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val reach = minOf(width, height) / 2f
        if (reach <= 0f) return
        val disc = reach * 0.64f
        val stroke = (reach * 0.035f).coerceAtLeast(GlassColors.dp(context, 2).toFloat())
        drawHalo(canvas, cx, cy, disc, reach)
        drawDisc(canvas, cx, cy, disc)
        drawRing(canvas, cx, cy, disc + stroke * 1.4f, stroke)
        if (focusRing && !spinning) {
            trackPaint.color = GlassColors.ACCENT
            trackPaint.strokeWidth = stroke
            canvas.drawCircle(cx, cy, reach - stroke, trackPaint)
        }
        if (connected) drawPlay(canvas, cx, cy, disc) else drawGlyph(canvas, cx, cy, disc)
    }

    /** 已连接：播放三角，点一下回到正在投屏的画面。 */
    private fun drawPlay(canvas: Canvas, cx: Float, cy: Float, inner: Float) {
        iconPaint.color = GlassColors.NAV_SELECTED_TEXT
        iconPaint.style = Paint.Style.FILL
        val height = inner * 0.46f
        val width = height * 0.9f
        val left = cx - width * 0.36f
        iconPath.reset()
        iconPath.moveTo(left, cy - height / 2f)
        iconPath.lineTo(left + width, cy)
        iconPath.lineTo(left, cy + height / 2f)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
    }

    /** 空闲和连接中：同一个 CarPlay 画面标志，圆角屏幕和下面一条短底座。 */
    private fun drawGlyph(canvas: Canvas, cx: Float, cy: Float, inner: Float) {
        iconPaint.color = GlassColors.NAV_SELECTED_TEXT
        iconPaint.style = Paint.Style.FILL
        val w = inner * 0.72f
        val h = inner * 0.46f
        val top = cy - h * 0.64f
        val radius = h * 0.26f
        oval.set(cx - w / 2f, top, cx + w / 2f, top + h)
        val bezel = h * 0.16f
        iconPath.reset()
        iconPath.fillType = Path.FillType.EVEN_ODD
        iconPath.addRoundRect(oval, radius, radius, Path.Direction.CW)
        iconPath.addRoundRect(
            RectF(oval.left + bezel, oval.top + bezel * 0.85f, oval.right - bezel, oval.bottom - bezel),
            radius * 0.45f,
            radius * 0.45f,
            Path.Direction.CW,
        )
        canvas.drawPath(iconPath, iconPaint)
        val chin = w * 0.20f
        val chinTop = oval.bottom + h * 0.10f
        val chinH = (h * 0.11f).coerceAtLeast(GlassColors.dp(context, 2).toFloat())
        iconPath.reset()
        iconPath.addRoundRect(
            RectF(cx - chin, chinTop, cx + chin, chinTop + chinH),
            chinH / 2f,
            chinH / 2f,
            Path.Direction.CW,
        )
        canvas.drawPath(iconPath, iconPaint)
    }

    /** 球体外的一圈光。实心圆盖住中心，光从圆边晕到控件边缘。 */
    private fun drawHalo(canvas: Canvas, cx: Float, cy: Float, disc: Float, reach: Float) {
        val edge = (disc / reach).coerceIn(0.35f, 0.85f)
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            reach,
            intArrayOf(
                withAlpha(GlassColors.ORB_GLOW, 0xCC),
                withAlpha(GlassColors.ORB_GLOW, (0x88 + 0x33 * pulse).toInt()),
                0x00000000,
            ),
            floatArrayOf(0f, edge, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, reach, fillPaint)
        fillPaint.shader = null
    }

    private fun drawDisc(canvas: Canvas, cx: Float, cy: Float, inner: Float) {
        fillPaint.shader = LinearGradient(
            cx,
            cy - inner,
            cx,
            cy + inner,
            intArrayOf(GlassColors.ORB_LENS_TOP, GlassColors.ORB_LENS_MID, GlassColors.ORB_LENS_BOTTOM),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, inner, fillPaint)
        val highlight = withAlpha(GlassColors.ORB_HIGHLIGHT, (0x66 + 0x44 * pulse).toInt())
        fillPaint.shader = RadialGradient(
            cx,
            cy - inner * 0.42f,
            inner * 0.85f,
            highlight,
            GlassColors.ORB_HIGHLIGHT_FADE,
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, inner, fillPaint)
        fillPaint.shader = null
        rimPaint.color = GlassColors.ORB_RIM
        rimPaint.strokeWidth = GlassColors.dp(context, 1).toFloat().coerceAtLeast(1f)
        canvas.drawCircle(cx, cy, inner - rimPaint.strokeWidth, rimPaint)
    }

    private fun drawRing(canvas: Canvas, cx: Float, cy: Float, outer: Float, stroke: Float) {
        if (connected && !spinning) {
            trackPaint.strokeWidth = stroke * 0.7f
            trackPaint.color = withAlpha(GlassColors.SUCCESS, 0x55)
            canvas.drawCircle(cx, cy, outer + stroke * 0.35f, trackPaint)
            trackPaint.color = GlassColors.SUCCESS
            trackPaint.strokeWidth = stroke
            canvas.drawCircle(cx, cy, outer, trackPaint)
            return
        }
        if (!spinning) {
            trackPaint.strokeWidth = stroke * 0.4f
            trackPaint.color = withAlpha(GlassColors.ACCENT, (0x22 + 0x28 * pulse).toInt())
            canvas.drawCircle(cx, cy, outer, trackPaint)
            return
        }
        trackPaint.strokeWidth = stroke * 0.45f
        trackPaint.color = withAlpha(GlassColors.ACCENT, 0x33)
        canvas.drawCircle(cx, cy, outer, trackPaint)
        arcPaint.strokeWidth = stroke * 0.45f
        arcPaint.color = withAlpha(GlassColors.ACCENT, 0x99)
        oval.set(cx - outer, cy - outer, cx + outer, cy + outer)
        canvas.drawArc(oval, -90f, 360f * progress, false, arcPaint)
        drawComet(canvas, outer, stroke)
    }

    /** 沿进度环跑的一段渐亮彗尾。 */
    private fun drawComet(canvas: Canvas, outer: Float, stroke: Float) {
        val head = spinDegrees - 90f
        val tail = 120f
        val segments = 6
        arcPaint.strokeWidth = stroke
        val seg = tail / segments
        for (i in 0 until segments) {
            val frac = (i + 1f) / segments
            arcPaint.color = withAlpha(GlassColors.ACCENT, (0x24 + 0xDB * frac).toInt())
            canvas.drawArc(oval, head - tail + i * seg, seg + 1.5f, false, arcPaint)
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
}
