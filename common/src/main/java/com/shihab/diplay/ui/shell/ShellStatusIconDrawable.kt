// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.shihab.diplay.ui.GlassColors

/**
 * 顶栏小图标。颜色在绘制时读 [GlassColors]，昼夜切换后下一帧就跟上。
 * 信号 4 格，Wi-Fi 3 段弧，蓝牙开/关/已连接，热点是中心点加两圈。
 */
class ShellStatusIconDrawable(private val kind: Kind) : Drawable() {
    enum class Kind { SIGNAL, WIFI, HOTSPOT, BLUETOOTH }
    enum class Phase { OFF, IDLE, ON, LINKED }

    var phase = Phase.OFF
        private set
    var bars = 0
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private val bluetooth = Path()
    private var bluetoothLeft = Float.NaN
    private var bluetoothTop = Float.NaN
    private var bluetoothW = -1f
    private var bluetoothH = -1f

    fun show(phase: Phase, bars: Int = 0) {
        val next = bars.coerceAtLeast(0)
        if (this.phase == phase && this.bars == next) return
        this.phase = phase
        this.bars = next
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        when (kind) {
            Kind.SIGNAL -> drawSignal(canvas, left, top, w, h)
            Kind.WIFI -> drawWifi(canvas, left, top, w, h)
            Kind.HOTSPOT -> drawHotspot(canvas, left, top, w, h)
            Kind.BLUETOOTH -> drawBluetooth(canvas, left, top, w, h)
        }
    }

    private fun drawSignal(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val count = 4
        val gap = w * 0.08f
        val barW = (w - gap * (count - 1)) / count
        val radius = barW * 0.18f
        val inService = phase == Phase.ON || phase == Phase.LINKED
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val barH = h * (0.38f + 0.62f * i / (count - 1))
            val x = left + i * (barW + gap)
            paint.color = if (inService && i < bars) onColor() else idleColor()
            arc.set(x, top + h - barH, x + barW, top + h)
            canvas.drawRoundRect(arc, radius, radius, paint)
        }
    }

    private fun drawWifi(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w * 0.5f
        val cy = top + h * 0.78f
        val stroke = w * 0.065f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = stroke
        val radii = floatArrayOf(w * 0.18f, w * 0.32f, w * 0.46f)
        for (i in radii.indices) {
            val r = radii[i]
            arc.set(cx - r, cy - r, cx + r, cy + r)
            paint.color = if (phase == Phase.LINKED && i < bars) onColor() else idleColor()
            canvas.drawArc(arc, 205f, 130f, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (phase == Phase.OFF) idleColor() else onColor()
        canvas.drawCircle(cx, cy, w * 0.05f, paint)
        if (phase == Phase.OFF) {
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = stroke
            paint.color = GlassColors.WARNING
            canvas.drawLine(left + w * 0.22f, top + h * 0.78f, left + w * 0.78f, top + h * 0.22f, paint)
        }
    }

    private fun drawHotspot(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        val cx = left + w * 0.5f
        val cy = top + h * 0.5f
        val min = minOf(w, h)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = min * 0.06f
        paint.color = onColor()
        canvas.drawCircle(cx, cy, min * 0.28f, paint)
        canvas.drawCircle(cx, cy, min * 0.44f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx, cy, min * 0.09f, paint)
    }

    private fun drawBluetooth(canvas: Canvas, left: Float, top: Float, w: Float, h: Float) {
        if (bluetoothLeft != left || bluetoothTop != top || bluetoothW != w || bluetoothH != h) {
            bluetooth.rewind()
            bluetooth.fillType = Path.FillType.EVEN_ODD
            fun x(v: Float) = left + v / 24f * w
            fun y(v: Float) = top + v / 24f * h
            bluetooth.moveTo(x(17.71f), y(7.71f))
            bluetooth.lineTo(x(12f), y(2f))
            bluetooth.lineTo(x(11f), y(2f))
            bluetooth.lineTo(x(11f), y(9.59f))
            bluetooth.lineTo(x(6.41f), y(5f))
            bluetooth.lineTo(x(5f), y(6.41f))
            bluetooth.lineTo(x(10.59f), y(12f))
            bluetooth.lineTo(x(5f), y(17.59f))
            bluetooth.lineTo(x(6.41f), y(19f))
            bluetooth.lineTo(x(11f), y(14.41f))
            bluetooth.lineTo(x(11f), y(22f))
            bluetooth.lineTo(x(12f), y(22f))
            bluetooth.lineTo(x(17.71f), y(16.29f))
            bluetooth.lineTo(x(13.41f), y(12f))
            bluetooth.close()
            bluetooth.moveTo(x(13f), y(5.83f))
            bluetooth.lineTo(x(15.17f), y(8f))
            bluetooth.lineTo(x(13f), y(10.17f))
            bluetooth.close()
            bluetooth.moveTo(x(13f), y(13.83f))
            bluetooth.lineTo(x(15.17f), y(16f))
            bluetooth.lineTo(x(13f), y(18.17f))
            bluetooth.close()
            bluetoothLeft = left
            bluetoothTop = top
            bluetoothW = w
            bluetoothH = h
        }
        paint.style = Paint.Style.FILL
        paint.color = when (phase) {
            Phase.LINKED -> GlassColors.ACCENT
            Phase.ON, Phase.IDLE -> onColor()
            Phase.OFF -> idleColor()
        }
        canvas.drawPath(bluetooth, paint)
    }

    private fun onColor(): Int = GlassColors.TEXT_PRIMARY

    private fun idleColor(): Int = GlassColors.DIM

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
