package com.shihab.diplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.widget.SeekBar
import com.shihab.diplay.compat.realSdkInt

/**
 * 画面调节用的玻璃滑条。轨道是细槽，已滑过的一段是系统蓝，圆钮是白底蓝环。
 * 继承 [SeekBar]，进度和监听不用改。
 */
class GlassSlider(context: Context) : SeekBar(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val trackH = GlassColors.dp(context, 6).toFloat().coerceAtLeast(4f)
    private val thumbR = GlassColors.dp(context, 11).toFloat().coerceAtLeast(8f)
    private val ring = GlassColors.dp(context, 2).toFloat().coerceAtLeast(2f)

    init {
        // 系统滑条的细青线和默认圆点不再画。触摸仍走 SeekBar。
        thumb = ColorDrawable(Color.TRANSPARENT)
        progressDrawable = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
        // Head units may spoof SDK_INT above KitKat while RELEASE stays 4.4.x — use realSdkInt.
        if (realSdkInt() >= Build.VERSION_CODES.LOLLIPOP) {
            splitTrack = false
            progressTintList = null
            progressBackgroundTintList = null
            thumbTintList = null
        }
        val pad = (thumbR + ring).toInt()
        setPadding(pad, 0, pad, 0)
        thumbOffset = 0
        minimumHeight = (thumbR * 2f + GlassColors.dp(context, 8)).toInt()
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
        trackPaint.color = if (GlassColors.isDark) 0x40FFFFFF else 0x401C1C1E
        trackRect.set(left, cy - r, right, cy + r)
        canvas.drawRoundRect(trackRect, r, r, trackPaint)
        if (x > left + 1f) {
            trackPaint.shader = LinearGradient(
                left, cy, x, cy,
                GlassColors.ACCENT_LIFT, GlassColors.ACCENT,
                Shader.TileMode.CLAMP,
            )
            trackRect.set(left, cy - r, x, cy + r)
            canvas.drawRoundRect(trackRect, r, r, trackPaint)
            trackPaint.shader = null
        }
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = 0x40000000
        canvas.drawCircle(x, cy + 1.5f, thumbR, thumbPaint)
        thumbPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, cy, thumbR, thumbPaint)
        thumbPaint.style = Paint.Style.STROKE
        thumbPaint.strokeWidth = ring
        thumbPaint.color = GlassColors.ACCENT
        canvas.drawCircle(x, cy, thumbR - ring / 2f, thumbPaint)
        thumbPaint.style = Paint.Style.FILL
        thumbPaint.color = 0x99FFFFFF.toInt()
        canvas.drawCircle(x - thumbR * 0.22f, cy - thumbR * 0.28f, thumbR * 0.28f, thumbPaint)
    }
}
