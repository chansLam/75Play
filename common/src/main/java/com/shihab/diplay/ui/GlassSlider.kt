package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.widget.SeekBar
import com.shihab.diplay.ui.theme.ShellSpacing

/**
 * Tonal picture-adjustment slider with a primary progress segment and outlined handle.
 * 继承 [SeekBar]，进度和监听不用改。
 */
class GlassSlider(context: Context) : SeekBar(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val trackH = GlassColors.dp(context, ShellSpacing.EXTRA_SMALL).toFloat()
    private val thumbR = GlassColors.dp(context, ShellSpacing.MEDIUM).toFloat()
    private val ring = GlassColors.dp(context, ShellSpacing.EXTRA_SMALL).toFloat()

    init {
        // 系统滑条的细青线和默认圆点不再画。触摸仍走 SeekBar。
        thumb = ColorDrawable(GlassColors.TRANSPARENT)
        progressDrawable = GradientDrawable().apply { setColor(GlassColors.TRANSPARENT) }
        val pad = (thumbR + ring).toInt()
        setPadding(pad, 0, pad, 0)
        thumbOffset = 0
        minimumHeight = GlassColors.dp(context, ShellSpacing.MIN_TOUCH_TARGET)
    }

    override fun onDraw(canvas: Canvas) {
        val left = paddingLeft.toFloat()
        val right = (width - paddingRight).toFloat()
        if (right - left < 2f || height < 2) return
        val cy = height / 2f
        val span = right - left
        val fraction = if (max <= 0) 0f else progress.toFloat() / max.toFloat()
        val x = left + span * fraction.coerceIn(0f, 1f)
        val r = trackH / 2f
        trackPaint.style = Paint.Style.FILL
        trackPaint.shader = null
        trackPaint.color = GlassColors.CARD
        trackRect.set(left, cy - r, right, cy + r)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)
        if (x > left + 1f) {
            trackPaint.shader = null
            trackPaint.color = GlassColors.ACCENT
            trackRect.set(left, cy - r, x, cy + r)
            canvas.drawRoundRect(trackRect, r, r, trackPaint)
            trackPaint.shader = null
        }
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = GlassColors.SURFACE
        canvas.drawCircle(x, cy, thumbR, thumbPaint)
        thumbPaint.style = Paint.Style.STROKE
        thumbPaint.strokeWidth = ring
        thumbPaint.color = GlassColors.ACCENT
        canvas.drawCircle(x, cy, thumbR - ring / 2f, thumbPaint)
        thumbPaint.style = Paint.Style.FILL
    }
}
