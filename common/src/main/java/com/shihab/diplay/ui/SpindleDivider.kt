package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * 两头淡出的发丝分隔。横着时左右淡出，竖着时上下淡出。
 * 用来分开导航、顶栏和栏目，不再画尖角色条。
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
        val edge = GlassColors.GLASS_EDGE
        if (vertical) {
            val x = w / 2f
            paint.shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(0x00000000, edge, edge, 0x00000000),
                floatArrayOf(0f, 0.18f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(x - hair / 2f, 0f, x + hair / 2f, h, paint)
        } else {
            val y = h / 2f
            paint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(0x00000000, edge, edge, 0x00000000),
                floatArrayOf(0f, 0.12f, 0.88f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, y - hair / 2f, w, y + hair / 2f, paint)
        }
        paint.shader = null
    }
}
