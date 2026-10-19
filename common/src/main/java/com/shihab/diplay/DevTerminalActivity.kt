// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.ui.GlassColors

import android.app.Activity
import android.content.res.Resources
import com.shihab.diplay.host.R
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

import jackpal.androidterm.ShellTermSession
import jackpal.androidterm.emulatorview.EmulatorView
import jackpal.androidterm.util.TermSettings

/**
 * Hidden developer terminal (root), styled as a glass dialog matching the 75Play UI.
 * Opened from the About page by tapping the version text five times quickly. Runs an
 * interactive shell over a real PTY using the jackpal Android-Terminal-Emulator stack
 * (emulatorview + ShellTermSession), instead of Runtime.exec which produced no output
 * on the head unit. Intended for a rooted head unit (Changan CS75 Plus, Freescale
 * Android 4.4, API 19). Glass look is simulated on API 19 (translucent fill + stroke +
 * rounded corners), following GlassColors theme and the dark/light dialog tokens.
 */
class DevTerminalActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var session: ShellTermSession? = null
    private var emulatorView: EmulatorView? = null
    private var command: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.terminal_title)
        GlassColors.syncWithNightMode(this)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // System nav bar on this head unit sits on the LEFT edge and can't be hidden (the OEM
        // S311WT SystemUI ignores app-level immersive). Measure its width and inset the whole
        // page so the terminal output is never covered.
        val navWidth: Int = run {
            val sysRes = Resources.getSystem()
            val id = sysRes.getIdentifier("navigation_bar_width", "dimen", "android")
            val px = if (id > 0) sysRes.getDimensionPixelSize(id) else 0
            // Head-unit left-side nav bar is wider than the system default; fall back to a
            // generous inset (~1/9 of screen width) so the output is never covered.
            if (px > 0) px else (resources.displayMetrics.widthPixels / 9)
        }

        // ---- 遮罩根：半透明背景 + 居中 ----
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(GlassColors.OVERLAY)
            setPadding(navWidth + dp(18), dp(20), dp(18), dp(20))
        }

        // ---- 玻璃弹窗卡：圆角深蓝近实 + 细亮描边 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(GlassColors.dialogBg())
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        root.addView(card, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 标题（主题强调色）
        card.addView(
            TextView(this).apply {
                text = getString(R.string.terminal_title)
                setTextColor(GlassColors.ACCENT)
                textSize = 19f
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(12) },
        )

        // ---- 终端区域：玻璃凹槽容器内嵌黑色 PTY 渲染 ----
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getMetrics(metrics)
        val termContainer = FrameLayout(this).apply {
            setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light)
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        try {
            val settings = TermSettings(resources, getSharedPreferences("diplay_term", MODE_PRIVATE))
            val s = ShellTermSession(settings, null)
            s.initializeEmulator(80, 24)
            emulatorView = EmulatorView(this, s, metrics).apply {
                setDensity(metrics)
                setTextSize(18)
                setBackgroundColor(Color.BLACK)
            }
            session = s
            termContainer.addView(emulatorView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        } catch (e: Exception) {
            android.util.Log.e("DevTerm", "shell start failed", e)
            val where = e.stackTrace.firstOrNull()
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                ?: getString(R.string.terminal_unknown)
            termContainer.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.terminal_start_failed,
                        e.javaClass.simpleName,
                        e.message ?: getString(R.string.terminal_no_details),
                        where,
                    )
                    setTextColor(GlassColors.WARNING)
                    textSize = 14f
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
        card.addView(termContainer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            .apply { bottomMargin = dp(12) })

        // ---- 命令输入行 ----
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        command = EditText(this).apply {
            hint = getString(R.string.terminal_input_hint)
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
        inputRow.addView(
            Button(this).apply {
                text = getString(R.string.terminal_execute)
                setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_primary else R.drawable.btn_primary_light)
                setTextColor(GlassColors.NAV_SELECTED_TEXT)
                textSize = 15f
                setAllCaps(false)
                setOnClickListener { exec() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) },
        )
        card.addView(inputRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(12) })

        // ---- 返回按钮（玻璃凹槽次级按钮）----
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        buttonRow.addView(
            Button(this).apply {
                text = getString(R.string.back)
                setBackgroundResource(if (GlassColors.isDark) R.drawable.btn_secondary else R.drawable.btn_secondary_light)
                setTextColor(GlassColors.BTN_SECONDARY_TEXT)
                textSize = 15f
                setAllCaps(false)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        card.addView(buttonRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        applyImmersive()
    }

    private fun exec() {
        val s = session ?: return
        val cmd = command?.text?.toString() ?: return
        if (cmd.isEmpty()) return
        s.write(cmd)
        s.write('\r'.toInt())
        command?.text?.clear()
    }

    private fun applyImmersive() {
        ImmersiveBars.apply(this)
        ImmersiveBars.installAutoHide(this)
        main.postDelayed({ ImmersiveBars.apply(this) }, 250L)
        main.postDelayed({ ImmersiveBars.apply(this) }, 800L)
    }

    override fun onDestroy() {
        session?.finish()
        session = null
        super.onDestroy()
    }
}
