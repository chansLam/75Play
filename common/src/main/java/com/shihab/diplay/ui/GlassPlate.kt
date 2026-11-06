package com.shihab.diplay.ui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

/** API 19 compatible glass plate: translucent fill with a restrained rim, without blur APIs. */
internal object GlassPlate {
    fun draw(canvas: Canvas, rect: RectF, radius: Float, paint: Paint, rimPx: Float = 0f) {
        if (rect.width() <= 1f || rect.height() <= 1f) return
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = GlassColors.plateFill()
        canvas.drawRoundRect(rect, radius, radius, paint)
        if (rimPx <= 0f) return
        val inset = rimPx / 2f
        val inner = RectF(rect.left + inset, rect.top + inset, rect.right - inset, rect.bottom - inset)
        if (inner.width() <= 1f || inner.height() <= 1f) return
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = rimPx
        paint.color = GlassColors.plateRim()
        val innerRadius = (radius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(inner, innerRadius, innerRadius, paint)
        paint.style = Paint.Style.FILL
    }
}
