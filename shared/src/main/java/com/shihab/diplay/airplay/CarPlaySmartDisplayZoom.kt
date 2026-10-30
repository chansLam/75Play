package com.shihab.diplay.airplay

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * iOS 26 shows Smart Display Zoom (智能缩放显示) for a large panel in the 2x density
 * bucket: about 200 ppi and a 10-inch diagonal. It also hides parts of that display
 * page when maxFPS is below 60. There is no accessory flag that forces the row on, so
 * [force] and [advertisedFps] are the report that satisfies both checks. Video pixels
 * grow only when the window is too small to reach that size at 200 ppi.
 */
object CarPlaySmartDisplayZoom {
    const val TARGET_PPI = 200.0
    /** About 10 inches. Smaller reported panels are not offered the setting. */
    const val MIN_DIAGONAL_MM = 250
    private const val MAX_EDGE = 3840
    private const val MAX_SHORT_EDGE = 2160

    fun advertisedFps(fps: Int): Int =
        max(AirPlayDisplaySettings.sanitizeFps(fps), AirPlayDisplaySettings.MAX_FPS)

    fun force(display: AirPlayDisplayConfig): AirPlayDisplayConfig {
        if (display.widthPixels <= 0 || display.heightPixels <= 0) return display
        if (qualifies(display)) return display
        val widthMm = display.widthPhysicalMm
        val heightMm = display.heightPhysicalMm
        // Large controls report a narrow panel. iOS does not offer 智能缩放显示 for that size,
        // and shrinking a wider (medium/small) report down into it hides the row for every size.
        if (widthMm != null && heightMm != null &&
            widthMm <= CarPlaySize.LARGE.widthMillimeters &&
            hypot(widthMm.toDouble(), heightMm.toDouble()) >= MIN_DIAGONAL_MM
        ) {
            return display
        }
        val adjusted = applyDensity(liftPixels(display))
        val adjustedWidth = adjusted.widthPhysicalMm
        if (widthMm != null && widthMm > CarPlaySize.LARGE.widthMillimeters &&
            adjustedWidth != null && adjustedWidth < widthMm
        ) {
            return display
        }
        return adjusted
    }

    private fun qualifies(display: AirPlayDisplayConfig): Boolean {
        val widthMm = display.widthPhysicalMm ?: return false
        val heightMm = display.heightPhysicalMm ?: return false
        if (widthMm <= 0 || heightMm <= 0) return false
        val diagonal = hypot(widthMm.toDouble(), heightMm.toDouble())
        val ppi = display.widthPixels * 25.4 / widthMm
        return diagonal >= MIN_DIAGONAL_MM && ppi >= TARGET_PPI
    }

    private fun liftPixels(display: AirPlayDisplayConfig): AirPlayDisplayConfig {
        val scale = requiredScale(display.widthPixels, display.heightPixels)
        if (scale <= 1.0) return display
        fun px(value: Int): Int {
            val scaled = (value * scale).roundToInt().coerceAtLeast(2)
            return if (scaled % 2 == 0) scaled else scaled + 1
        }
        var width = px(display.widthPixels)
        var height = px(display.heightPixels)
        val longEdge = max(width, height)
        val shortEdge = minOf(width, height)
        if (longEdge > MAX_EDGE || shortEdge > MAX_SHORT_EDGE) {
            val capped = minOf(MAX_EDGE.toDouble() / longEdge, MAX_SHORT_EDGE.toDouble() / shortEdge)
            fun cappedPx(value: Int): Int {
                val scaled = (value * capped).toInt().coerceAtLeast(2)
                return if (scaled % 2 == 0) scaled else scaled - 1
            }
            width = cappedPx(width)
            height = cappedPx(height)
        }
        return display.copy(widthPixels = width, heightPixels = height)
    }

    /** Scale that lets [applyDensity] clear both the diagonal and the 2x bucket. */
    private fun requiredScale(width: Int, height: Int): Double {
        val aspect = height.toDouble() / width
        val widthMm = MIN_DIAGONAL_MM / hypot(1.0, aspect)
        val minPixels = TARGET_PPI * widthMm / 25.4
        val scale = minPixels / width
        return if (scale <= 1.0) scale else scale * 1.02
    }

    private fun applyDensity(display: AirPlayDisplayConfig): AirPlayDisplayConfig {
        val widthMm = (display.widthPixels * 25.4 / TARGET_PPI).roundToInt()
            .coerceIn(AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM, AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM)
        val heightMm = (widthMm.toLong() * display.heightPixels / display.widthPixels)
            .toInt()
            .coerceIn(AirPlayDisplaySettings.MIN_REPORTED_PHYSICAL_MM, AirPlayDisplaySettings.MAX_REPORTED_PHYSICAL_MM)
        return display.copy(widthPhysicalMm = widthMm, heightPhysicalMm = heightMm)
    }
}
