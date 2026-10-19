// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

/** Sticky immersive fullscreen: hide status + navigation bars (KitKat-friendly). */
object ImmersiveBars {

    private val autoHideInstalled = WeakHashMap<Activity, Boolean>()

    @Suppress("DEPRECATION")
    fun visibilityFlags(): Int {
        var flags = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        if (Build.VERSION.SDK_INT >= 19) {
            flags = flags or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        return flags
    }

    fun apply(activity: Activity) {
        applyToWindow(activity.window, force = false)
    }

    /** Apply immersive flags to any window (Activity or Dialog). */
    fun applyToWindow(window: Window?, force: Boolean = false) {
        window ?: return
        val flags = visibilityFlags()
        @Suppress("DEPRECATION")
        if (!force &&
            window.decorView.systemUiVisibility == flags &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
        ) {
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
        val lp = window.attributes
        @Suppress("DEPRECATION")
        lp.systemUiVisibility = flags
        window.attributes = lp
    }

    /**
     * 监听系统栏被边滑/焦点切换/ROM 拉出后自动再藏。
     * 覆盖「非弹窗」交互：点 Tab、开关、启动应用返回前的残留、IMMERSIVE_STICKY 临时露出等。
     * 勿装到 CarPlayHostActivity（它有自己的会话期守卫逻辑）。
     */
    fun installAutoHide(activity: Activity) {
        if (autoHideInstalled[activity] == true) return
        autoHideInstalled[activity] = true
        val decor = activity.window.decorView
        @Suppress("DEPRECATION")
        decor.setOnSystemUiVisibilityChangeListener { visibility ->
            if (activity.isFinishing) return@setOnSystemUiVisibilityChangeListener
            @Suppress("DEPRECATION")
            val navShowing = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            @Suppress("DEPRECATION")
            val statusShowing = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
            if (!navShowing && !statusShowing) return@setOnSystemUiVisibilityChangeListener
            // 稍延迟：给 IMMERSIVE_STICKY 的短暂预览一点时间，再强制收回。
            decor.postDelayed({
                if (!activity.isFinishing) applyToWindow(activity.window, force = true)
            }, 200L)
        }
        applyToWindow(activity.window, force = true)
    }

    /** 触摸按下时若导航栏已露出则立刻收回（配合 [installAutoHide]）。 */
    fun rehideIfBarsVisible(activity: Activity?) {
        val act = activity ?: return
        @Suppress("DEPRECATION")
        val visibility = act.window.decorView.systemUiVisibility
        @Suppress("DEPRECATION")
        val navShowing = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
        @Suppress("DEPRECATION")
        val statusShowing = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
        if (navShowing || statusShowing) applyToWindow(act.window, force = true)
    }

    /**
     * Dialog / PopupWindow 打开期间反复压制系统栏。
     * 车机 ROM 常在独立窗口获得焦点时重新露出侧边导航栏，单次设置不够。
     */
    fun keepHiddenWhile(activity: Activity?, isActive: () -> Boolean) {
        val act = activity ?: return
        applyToWindow(act.window, force = true)
        val decor = act.window.decorView
        for (delay in longArrayOf(0L, 16L, 48L, 100L, 200L, 400L, 800L, 1600L)) {
            decor.postDelayed({
                if (isActive()) applyToWindow(act.window, force = true)
            }, delay)
        }
    }

    /**
     * 安全显示 Dialog：先 NOT_FOCUSABLE 避免获焦闪出系统导航栏；
     * [allowFocus]=true 仅用于需要软键盘的输入框弹窗。
     */
    fun showDialogGuarded(dialog: android.app.Dialog, activity: Activity?, allowFocus: Boolean = false) {
        val window = dialog.window ?: return
        applyToWindow(activity?.window, force = true)
        applyToWindow(window, force = true)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        dialog.show()
        applyToWindow(window, force = true)
        applyToWindow(activity?.window, force = true)
        if (allowFocus) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            applyToWindow(window, force = true)
            applyToWindow(activity?.window, force = true)
        }
        @Suppress("DEPRECATION")
        window.decorView.setOnSystemUiVisibilityChangeListener {
            if (dialog.isShowing) {
                applyToWindow(window, force = true)
                applyToWindow(activity?.window, force = true)
            }
        }
        keepHiddenWhile(activity) { dialog.isShowing }
        // 不在这里 setOnDismissListener：调用方（如 showManageApps）可能已挂自己的 dismiss 回调。
    }

    /** Toast / 短暂 UI 后补压一次沉浸式（Toast 窗口偶发露出侧栏）。 */
    fun pulse(activity: Activity?) {
        val act = activity ?: return
        applyToWindow(act.window, force = true)
        val decor = act.window.decorView
        decor.post { applyToWindow(act.window, force = true) }
        decor.postDelayed({ applyToWindow(act.window, force = true) }, 120L)
        decor.postDelayed({ applyToWindow(act.window, force = true) }, 400L)
    }
}
