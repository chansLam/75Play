package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.shihab.diplay.ui.theme.ShellShape

/** Tonal navigation state. Active and focused rows share a fixed radius. */
class HudNavDrawable(context: Context, private val active: Boolean) : Drawable() {
    private val rim = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val radius = GlassColors.dp(context, ShellShape.MEDIUM).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = rim
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (rect.width() <= 1f || rect.height() <= 1f) return
        fill.style = Paint.Style.FILL
        fill.color = if (active) GlassColors.NAV_SELECTED else GlassColors.CARD
        canvas.drawRoundRect(rect, radius, radius, fill)
        if (!active) {
            fill.style = Paint.Style.STROKE
            fill.strokeWidth = rim * 2f
            fill.color = GlassColors.ACCENT
            canvas.drawRoundRect(rect, radius, radius, fill)
            fill.style = Paint.Style.FILL
        }
    }

    override fun setAlpha(alpha: Int) {
        fill.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fill.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
