// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.wallpaper

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

internal class CenterCropBitmapDrawable(private val bitmap: Bitmap) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun draw(canvas: Canvas) {
        val area = bounds
        if (area.width() <= 0 || area.height() <= 0 || bitmap.isRecycled) return
        val scale = maxOf(area.width().toFloat() / bitmap.width, area.height().toFloat() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = area.left + (area.width() - width) / 2f
        val top = area.top + (area.height() - height) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
