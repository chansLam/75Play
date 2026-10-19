package com.shihab.diplay.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout

/**
 * 玻璃风格开关：圆角矩形底（开=主题强调蓝 / 关=灰）+ 圆形滑块。
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
        track.style = Paint.Style.FILL
        track.color = if (progress > 0.5f) GlassColors.ACCENT else grayColor()
        canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, track)
        val cy = h / 2f
        val cx = knobD / 2f + (w - knobD) * progress
        knob.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, knobD / 2f, knob)
    }

    private fun grayColor(): Int = if (GlassColors.isDark) 0xFF4A4F5A.toInt() else 0xFF94A3B8.toInt()

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
