// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.R

/**
 * 盖住 CarPlay 出画前的黑屏。第一帧到了就藏起来，三指下滑仍由它上面的手势层接收。
 */
internal class CarPlayLoadingLayer(context: Context) : FrameLayout(context) {
    val status: TextView

    init {
        setBackgroundColor(0xF0071018.toInt())
        val spinner = Spinner(context)
        status = TextView(context).apply {
            setTextColor(0xFFE7F4FF.toInt())
            textSize = 20f
            gravity = Gravity.CENTER
            text = context.getString(R.string.opening_carplay)
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val size = dp(72)
            addView(spinner, LinearLayout.LayoutParams(size, size))
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(18) })
        }
        addView(column, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        val pad = dp(32)
        setPadding(pad, pad, pad, pad)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private class Spinner(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = 0xFF2EC6FF.toInt()
        }
        private val oval = RectF()
        private var spin: ObjectAnimator? = null

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (spin != null) return
            spin = ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                start()
            }
        }

        override fun onDetachedFromWindow() {
            spin?.cancel()
            spin = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val stroke = (width * 0.08f).coerceAtLeast(dp(2).toFloat())
            paint.strokeWidth = stroke
            val inset = stroke / 2f + dp(2)
            oval.set(inset, inset, width - inset, height - inset)
            canvas.drawArc(oval, -90f, 270f, false, paint)
        }

        private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    }
}
