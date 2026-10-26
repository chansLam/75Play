package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 一块圆角玻璃。暗色和浅色同一浓度，底图从填充里透出来，不再描边。 */
class HudPanelDrawable(context: Context) : Drawable() {
    private val radius = GlassColors.dp(context, 22).toFloat()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        GlassPlate.draw(canvas, rect, radius.coerceAtMost(rect.height() / 2f), fill)
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
