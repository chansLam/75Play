// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.ui.GlassColors
import com.shihab.diplay.ui.shell.ShellStatusIconDrawable
import com.shihab.diplay.ui.theme.ShellShape
import com.shihab.diplay.ui.theme.ShellSpacing
import com.shihab.diplay.ui.theme.ShellStroke
import com.shihab.diplay.ui.theme.ShellType
import com.shihab.diplay.util.AppLog
import java.util.Locale

/**
 * Global top temperature bar. A lightweight system overlay (`TYPE_PHONE`, non-touchable) that sits
 * at the top-right of the screen and shows three live AC temperatures read from the Coagent
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
    private var shellCovering = false
    private val main = Handler(Looper.getMainLooper())

    private fun diag(message: String) {
        AsyncDiagnosticLog.append(ActiveSessionLog.current, message)
        AppLog.w(TAG, message)
    }

    /**
     * The shell draws the same temperatures in its own top bar. Hide this overlay while that
     * bar is on screen so the two do not stack, and bring it back when CarPlay covers the shell.
     */
    fun setShellCovering(covering: Boolean) {
        shellCovering = covering
        root?.visibility = if (covering) View.GONE else View.VISIBLE
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

        GlassColors.syncWithNightMode(context)
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (ShellShape.EXTRA_SMALL * density)
                setColor(GlassColors.GLASS_CHIP)
            }
            val padH = (ShellSpacing.SMALL * density).toInt()
            val padV = (ShellSpacing.EXTRA_SMALL * density).toInt()
            setPadding(padH, padV, padH, padV)
            contentDescription = context.getString(com.shihab.diplay.host.R.string.cd_temp_bar)
            visibility = if (shellCovering) View.GONE else View.VISIBLE
        }
        val icon = (ShellSpacing.LARGE * density).toInt().coerceAtLeast(1)
        bar.addView(ImageView(context).apply {
            setImageDrawable(ShellStatusIconDrawable(ShellStatusIconDrawable.Kind.SNOW).apply {
                show(ShellStatusIconDrawable.Phase.ON)
            })
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(icon, icon).apply {
            marginEnd = (ShellSpacing.EXTRA_SMALL * density).toInt()
        })

        fun reading(): TextView = TextView(context).apply {
            text = "--"
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = ShellType.HEADING
            includeFontPadding = false
        }
        fun divider(): View = View(context).apply { setBackgroundColor(GlassColors.DIM) }
        val dividerLp = LinearLayout.LayoutParams(
            maxOf(1, (ShellStroke.HAIRLINE * density).toInt()),
            (ShellSpacing.MEDIUM * density).toInt(),
        ).apply {
            marginStart = (ShellSpacing.SMALL * density).toInt()
            marginEnd = marginStart
            gravity = Gravity.CENTER_VERTICAL
        }

        outsideText = reading()
        insideText = reading()
        setText = reading()
        bar.addView(outsideText)
        bar.addView(divider(), dividerLp)
        bar.addView(insideText)
        bar.addView(divider(), LinearLayout.LayoutParams(dividerLp))
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
            gravity = Gravity.TOP or Gravity.END
            x = (ShellSpacing.LARGE * density).toInt()
            y = (ShellSpacing.SMALL * density).toInt()
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
        out.text = statusTemperatureText(CoagentAcBridge.outsideTemperature)
        inn.text = statusTemperatureText(CoagentAcBridge.insideTemperature)
        val setValue = when {
            !CoagentAcBridge.leftTemperature.isNaN() -> CoagentAcBridge.leftTemperature
            !CoagentAcBridge.rightTemperature.isNaN() -> CoagentAcBridge.rightTemperature
            else -> Float.NaN
        }
        set.text = statusTemperatureText(setValue)
    }
}

/** Compact temperature for the top-right status row. LO/HI match the head-unit AC service. */
internal fun statusTemperatureText(value: Float): String = when {
    value.isNaN() -> "--"
    value == -1f -> "LO"
    value == -2f -> "HI"
    else -> String.format(Locale.US, "%.1f°", value)
}
