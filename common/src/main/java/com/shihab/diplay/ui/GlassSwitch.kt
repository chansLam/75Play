package com.shihab.diplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.widget.LinearLayout

/**
 * 圆角开关：开=青蓝轨道，关=灰轨道。
 * API 19 用 RectF 版 drawRoundRect；不依赖系统 Switch。
 * [compact] 保留兼容；车机短屏尺寸改由 [LayoutMetrics] 统一放大。
 */
class GlassSwitch @JvmOverloads constructor(
    context: Context,
    compact: Boolean = false,
) : View(context) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
    // switchW/H/Knob 为 dp；短屏走车机放大尺寸。
    private val w = GlassColors.dp(context, LayoutMetrics.switchW(context).let { if (!LayoutMetrics.isShort(context) && compact) 40 else it })
    private val h = GlassColors.dp(context, LayoutMetrics.switchH(context).let { if (!LayoutMetrics.isShort(context) && compact) 22 else it })
    private val knobD = GlassColors.dp(context, LayoutMetrics.switchKnob(context).let { if (!LayoutMetrics.isShort(context) && compact) 16 else it })
    private var progress = 0f // 0=关, 1=开
    private var animator: ValueAnimator? = null
    private var checked = false

    var isChecked: Boolean
        get() = checked
        set(value) {
            setChecked(value, animate = true)
        }

    var onCheckedChangeListener: ((GlassSwitch, Boolean) -> Unit)? = null

    init {
        layoutParams = LinearLayout.LayoutParams(w, h)
        isClickable = true
        isFocusable = true
        setOnClickListener { setChecked(!checked, animate = true) }
    }

    /** [animate]=false 用于首次绑定，避免开关闪一下关再开。 */
    fun setChecked(value: Boolean, animate: Boolean) {
        if (checked == value) {
            if (!animate) {
                animator?.cancel()
                progress = if (value) 1f else 0f
                invalidate()
            }
            return
        }
        checked = value
        if (animate) animateTo(if (value) 1f else 0f)
        else {
            animator?.cancel()
            progress = if (value) 1f else 0f
            invalidate()
        }
        onCheckedChangeListener?.invoke(this, value)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val r = h / 2f
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        track.style = Paint.Style.FILL
        track.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            blend(grayColor(), GlassColors.ACCENT_LIFT, progress),
            blend(grayColor(), GlassColors.ACCENT_DEEP, progress),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, r, r, track)
        track.shader = null
        track.style = Paint.Style.STROKE
        track.strokeWidth = 1f
        track.color = if (GlassColors.isDark) 0x33FFFFFF else 0x66FFFFFF
        canvas.drawRoundRect(rect, r, r, track)
        val cy = h / 2f
        val cx = knobD / 2f + (w - knobD) * progress
        val kr = knobD / 2f
        knob.style = Paint.Style.FILL
        knob.color = 0x33000000
        canvas.drawCircle(cx, cy + 1.5f, kr, knob)
        knob.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, kr, knob)
        knob.color = 0x66FFFFFF
        canvas.drawCircle(cx - kr * 0.22f, cy - kr * 0.28f, kr * 0.36f, knob)
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        val u = t.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val a = (from ushr shift) and 0xFF
            val b = (to ushr shift) and 0xFF
            return a + ((b - a) * u).toInt()
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun grayColor(): Int = if (GlassColors.isDark) 0xFF3A4654.toInt() else 0xFFB7C6D4.toInt()

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = 160
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
