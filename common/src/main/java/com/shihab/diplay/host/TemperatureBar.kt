// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.util.AppLog

/**
 * Global top temperature bar. A lightweight system overlay (`TYPE_PHONE`, non-touchable) that sits
 * at the top-center of the screen and shows three live AC temperatures read from the Coagent
 * air-condition service via [CoagentAcBridge]:
 *   · 室外 (outside)   · 车内 (inside)   · 设定 (set, left/right average)
 *
 * It is a pure display overlay: `FLAG_NOT_TOUCHABLE` so it never intercepts taps, and
 * `FLAG_NOT_FOCUSABLE` so it never steals steering-wheel focus. Values update on the firmware
 * callback (no polling).
 *
 * Show once from the app entry point; it then stays visible across CarPlay host and home screens.
 */
object TemperatureBar {
    private const val TAG = "DiPlay-TempBar"

    private var root: View? = null
    private var outsideText: TextView? = null
    private var insideText: TextView? = null
    private var setText: TextView? = null
    private val main = Handler(Looper.getMainLooper())

    private fun diag(message: String) {
        AsyncDiagnosticLog.append(ActiveSessionLog.current, message)
        AppLog.w(TAG, message)
    }

    /** Shows the temperature bar (idempotent). Returns true when added. */
    fun show(context: Context): Boolean {
        diag("TemperatureBar.show invoked rootExisting=${root != null} thread=${Thread.currentThread().name}")
        if (root != null) {
            // Already displayed: still re-probe the (possibly late-ready) AC service.
            CoagentAcBridge.nudge()
            return true
        }
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: run {
            diag("TemperatureBar.show FAILED: WindowManager unavailable")
            return false
        }
        val density = context.resources.displayMetrics.density

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Glass pill: semi-transparent dark background + subtle accent border, matching theme.
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (10 * density).toFloat()
                setColor(0xCC000000.toInt()) // dark translucent pill reads on both light/dark screens
            }
            background = bg
            setPadding((14 * density).toInt(), (5 * density).toInt(), (14 * density).toInt(), (5 * density).toInt())
            contentDescription = context.getString(com.shihab.diplay.host.R.string.cd_temp_bar)
        }

        fun label(text: String, value: Float, unitSuffix: String, accent: Boolean): TextView {
            return TextView(context).apply {
                val v = if (value.isNaN()) "--" else String.format("%.1f°C", value)
                setText("$text $v")
                setTextColor(if (accent) 0xFFFFFFFF.toInt() else 0xFFD8D8DC.toInt())
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            }
        }

        outsideText = label("室外", Float.NaN, "°C", true)
        insideText = label("车内", Float.NaN, "°C", false)
        setText = label("设定", Float.NaN, "°C", false)

        // Two independent spacer instances: a View can only ever have one parent, so reusing a
        // single `sep` across two addView() calls throws IllegalStateException on the second add.
        fun spacer(): TextView = TextView(context).apply {
            text = "  "
            textSize = 12f
        }

        bar.addView(outsideText)
        bar.addView(spacer())
        bar.addView(insideText)
        bar.addView(spacer())
        bar.addView(setText)

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (8 * density).toInt()
            title = "DiPlay temperature bar"
        }

        runCatching { windowManager.addView(bar, params) }.onFailure { e ->
            // API 19: TYPE_PHONE requires SYSTEM_ALERT_WINDOW (declared in the common manifest);
            // a SecurityException here means the permission was not granted, any other exception
            // (BadTokenException / IllegalStateException) means a window-manager conflict.
            diag("TemperatureBar addView FAILED type=${e.javaClass.simpleName} msg=${e.message}")
            // Still probe coagent.ac so binding/parse failures are diagnosable even when the
            // overlay itself cannot be shown.
            CoagentAcBridge.onTemperatureChanged = { main.post { render() } }
            CoagentAcBridge.startRetries()
            return false
        }
        root = bar
        diag("TemperatureBar addView OK TYPE_PHONE")

        // Register for firmware temperature updates (idempotent) and draw current snapshot.
        // startRetries() polls register(tx19) + refreshSnapshot(tx27) because coagent.ac can be
        // late-ready after head-unit boot; refreshSnapshot() pulls the full AirConditionInfo once:
        // the firmware only pushes onACTempChanged when a value *changes*, so without it the set
        // (left/right) temperature would stay "--" until the user actually adjusts the HVAC.
        CoagentAcBridge.onTemperatureChanged = { main.post { render() } }
        CoagentAcBridge.startRetries()
        render()
        return true
    }

    /** Hides the temperature bar (idempotent). */
    fun hide(context: Context) {
        val bar = root ?: return
        root = null
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        runCatching { windowManager?.removeViewImmediate(bar) }
            .onFailure { e -> diag("TemperatureBar hide removeView FAILED type=${e.javaClass.simpleName} msg=${e.message}") }
        diag("TemperatureBar hidden by settings switch")
    }

    private fun render() {
        val out = outsideText ?: return
        val inn = insideText ?: return
        val set = setText ?: return
        out.text = fmt("室外", CoagentAcBridge.outsideTemperature)
        inn.text = fmt("车内", CoagentAcBridge.insideTemperature)
        // Set temperature: prefer left; fall back to right when left invalid.
        val setValue = when {
            !CoagentAcBridge.leftTemperature.isNaN() -> CoagentAcBridge.leftTemperature
            !CoagentAcBridge.rightTemperature.isNaN() -> CoagentAcBridge.rightTemperature
            else -> Float.NaN
        }
        set.text = fmt("设定", setValue)
    }

    private fun fmt(label: String, value: Float): String {
        return when {
            value.isNaN() -> "$label --"
            value == -1f -> "$label LO"
            value == -2f -> "$label HI"
            else -> "$label ${String.format("%.1f°C", value)}"
        }
    }
}
