// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay
import com.shihab.diplay.host.R
import com.shihab.diplay.ui.GlassColors

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.shihab.diplay.host.ImmersiveBars

import jackpal.androidterm.ShellTermSession
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.util.TermSettings
import com.shihab.diplay.util.AppLog

/**
 * Hidden developer terminal (root) rendered as a glass overlay dialog directly on the current
 * activity — it no longer switches to a separate page. Opened from the About page by tapping the
 * version text five times quickly. Runs an interactive shell over a real PTY using the jackpal
 * Android-Terminal-Emulator stack (emulatorview + ShellTermSession). Styled to match the 75Play
 * glass UI (translucent fill + stroke + rounded corners, GlassColors theme-aware). The jackpal
 * EmulatorView is the only non-theme surface (terminal is inherently black).
 */
object TerminalOverlay {

    private var overlay: View? = null
    private var session: ShellTermSession? = null
    private var emulator: EmulatorView? = null
    private var command: EditText? = null
    private var handler: Handler? = null

    /** Show the terminal as an overlay on `activity`; tapping the dark backdrop closes it. */
    fun show(activity: Activity) {
        if (overlay != null) return
        handler = Handler(Looper.getMainLooper())
        val ctx = activity
        GlassColors.syncWithNightMode(ctx)
        val density = ctx.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // Left-side system nav bar on this head unit: inset the overlay so it is never covered.
        val navWidth: Int = run {
            val sysRes = android.content.res.Resources.getSystem()
            val id = sysRes.getIdentifier("navigation_bar_width", "dimen", "android")
            val px = if (id > 0) sysRes.getDimensionPixelSize(id) else 0
            if (px > 0) px else (ctx.resources.displayMetrics.widthPixels / 9)
        }

        // ---- 全屏遮罩 + 居中玻璃卡 ----
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(GlassColors.OVERLAY)
            setPadding(navWidth + dp(18), dp(20), dp(18), dp(20))
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, event ->
                // Tap outside the card closes the terminal; inside is handled by the card.
                if (event.actionMasked == MotionEvent.ACTION_UP) dismiss(activity)
                true
            }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dialogBg())
            setPadding(dp(16), dp(14), dp(16), dp(14))
            // 拦截点击，避免冒泡到遮罩触发关闭。
            isClickable = true
            isFocusable = true
        }

        // 标题行：标题 + 关闭按钮
        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.terminal_title)
            setTextColor(GlassColors.ACCENT)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Button(ctx).apply {
            text = ctx.getString(R.string.back)
            setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light)
            setTextColor(GlassColors.BTN_SECONDARY_TEXT)
            textSize = 14f
            setAllCaps(false)
            setOnClickListener { dismiss(activity) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(titleRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(10) })

        // 终端输出区：玻璃凹槽容器内嵌黑色 PTY 渲染
        val metrics = DisplayMetrics()
        ctx.windowManager.defaultDisplay.getMetrics(metrics)
        val termContainer = FrameLayout(ctx).apply {
            setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        try {
            val settings = TermSettings(ctx.resources, ctx.getSharedPreferences("diplay_term", android.content.Context.MODE_PRIVATE))
            val s = ShellTermSession(settings, null)
            s.initializeEmulator(80, 24)
            emulator = EmulatorView(ctx, s, metrics).apply {
                setDensity(metrics)
                setTextSize(18)
                setBackgroundColor(Color.BLACK)
            }
            session = s
            termContainer.addView(emulator, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Exception) {
            AppLog.e("DevTerm", "shell start failed", e)
            val where = e.stackTrace.firstOrNull()
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                ?: ctx.getString(R.string.terminal_unknown)
            termContainer.addView(TextView(ctx).apply {
                text = ctx.getString(
                    R.string.terminal_start_failed,
                    e.javaClass.simpleName,
                    e.message ?: ctx.getString(R.string.terminal_no_details),
                    where,
                )
                setTextColor(GlassColors.WARNING)
                textSize = 14f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        card.addView(termContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { bottomMargin = dp(10) })

        // 命令输入行
        val inputRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        command = EditText(ctx).apply {
            hint = ctx.getString(R.string.terminal_input_hint)
            setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextColor(GlassColors.INK)
            setHintTextColor(GlassColors.MUTED)
            textSize = 15f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setOnEditorActionListener { _, _, _ -> exec(); true }
        }
        inputRow.addView(command, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(Button(ctx).apply {
            text = ctx.getString(R.string.terminal_execute)
            setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_primary else R.drawable.btn_primary_light)
            setTextColor(GlassColors.NAV_SELECTED_TEXT)
            textSize = 15f
            setAllCaps(false)
            setOnClickListener { exec() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) })
        card.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(card, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        // 加到当前窗口，盖在 Fragment 之上（最后 add，z 最高）。
        activity.addContentView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = root
        ImmersiveBars.applyToWindow(activity.window, force = true)
        ImmersiveBars.keepHiddenWhile(activity) { overlay != null }
    }

    /** Remove the overlay and tear down the PTY session. */
    fun dismiss(activity: Activity) {
        overlay?.let { (it.parent as? ViewGroup)?.removeView(it) }
        overlay = null
        session?.finish()
        session = null
        emulator = null
        command = null
        handler?.removeCallbacksAndMessages(null)
        handler = null
        ImmersiveBars.applyToWindow(activity.window, force = true)
    }

    private fun exec() {
        val s = session ?: return
        val cmd = command?.text?.toString() ?: return
        if (cmd.isEmpty()) return
        s.write(cmd)
        s.write('\r'.toInt())
        command?.text?.clear()
    }
}
