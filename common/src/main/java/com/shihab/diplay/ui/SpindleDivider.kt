package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Single-pixel tonal divider for navigation, chrome, and section boundaries.
 */
class SpindleDivider(context: Context) : View(context) {
    /** true：竖线，分隔左右两栏。false：横线。 */
    var vertical: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hair = resources.displayMetrics.density.coerceAtLeast(1f)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return
        paint.shader = null
        paint.color = GlassColors.GLASS_EDGE
        if (vertical) {
            val x = w / 2f
            canvas.drawRect(x - hair / 2f, 0f, x + hair / 2f, h, paint)
        } else {
            val y = h / 2f
            canvas.drawRect(0f, y - hair / 2f, w, y + hair / 2f, paint)
        }
    }
}
