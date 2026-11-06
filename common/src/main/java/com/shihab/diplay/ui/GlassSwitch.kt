package com.shihab.diplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout
import com.shihab.diplay.ui.theme.ShellMotion
import com.shihab.diplay.ui.theme.ShellSpacing

/**
 * Tonal switch: the active track uses primary; the inactive track uses a surface container.
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
    private val trackH = GlassColors.dp(context, LayoutMetrics.switchH(context))
    private val knobD = GlassColors.dp(context, LayoutMetrics.switchKnob(context))
    private val touchH = GlassColors.dp(context, ShellSpacing.MIN_TOUCH_TARGET)
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
        layoutParams = LinearLayout.LayoutParams(w, touchH)
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
        setMeasuredDimension(w, touchH)
    }

    override fun onDraw(canvas: Canvas) {
        val top = (touchH - trackH) / 2f
        val bottom = top + trackH
        val r = trackH / 2f
        val rect = RectF(0f, top, w.toFloat(), bottom)
        track.style = Paint.Style.FILL
        track.shader = null
        track.color = blend(GlassColors.CARD, GlassColors.ACCENT, progress)
        canvas.drawRoundRect(rect, r, r, track)
        val cy = touchH / 2f
        val cx = knobD / 2f + (w - knobD) * progress
        val kr = knobD / 2f
        knob.style = Paint.Style.FILL
        knob.color = blend(GlassColors.MUTED, GlassColors.CONTROL_HANDLE, progress)
        canvas.drawCircle(cx, cy, kr, knob)
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

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(progress, target).apply {
            duration = ShellMotion.STATE
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }
}
