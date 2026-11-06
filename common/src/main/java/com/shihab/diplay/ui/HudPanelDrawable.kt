package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.shihab.diplay.ui.theme.ShellShape

/** Filled tonal panel shared by all shell columns. */
class HudPanelDrawable(context: Context) : Drawable() {
    private val radius = GlassColors.dp(context, ShellShape.LARGE).toFloat()
    private val rim = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        rect.set(b)
        GlassPlate.draw(canvas, rect, radius.coerceAtMost(rect.height() / 2f), fill, rim)
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
