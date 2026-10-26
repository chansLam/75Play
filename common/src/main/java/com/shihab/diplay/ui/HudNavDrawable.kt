package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** 导航项：选中是透出底图的蓝色玻璃胶囊，按下或焦点是一块浅玻璃。 */
class HudNavDrawable(context: Context, private val active: Boolean) : Drawable() {
    private val rim = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val inset = rim
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        if (rect.width() <= 1f || rect.height() <= 1f) return
        val radius = minOf(rect.width(), rect.height()) / 2f
        if (active) {
            fill.style = Paint.Style.FILL
            fill.color = GlassColors.NAV_SELECTED
            canvas.drawRoundRect(rect, radius, radius, fill)
        } else {
            GlassPlate.draw(canvas, rect, radius, fill, rim)
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
