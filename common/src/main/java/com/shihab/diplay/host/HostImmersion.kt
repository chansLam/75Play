// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.view.View
import androidx.core.view.WindowCompat
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.HarmanFullscreenCompat

/**
 * Fullscreen / immersive chrome for CarPlay Host: sticky system-bar hide, temporary peek,
 * and the KitKat bar-guard heartbeat against ROMs that restore the nav bar after a swipe-down.
 */
internal class HostImmersion(private val activity: CarPlayHostActivity) {

    private var systemUiVisibilityListenerInstalled = false

    // CarPlay 会话期间周期重隐系统栏：部分车机 ROM 在下拉状态栏后不自动恢复隐藏，
    // 由心跳每 1.5s 强制压一次（用 FLAG_FULLSCREEN 级全屏，保证导航栏不露出）。
    private val carPlayBarGuard = object : Runnable {
        override fun run() {
            if (activity.isFinishing || activity.isDestroyed) return
            if (activity.activeAirPlaySession == null ||
                activity.temporarySystemBarsVisible ||
                activity.isMultiWindowActive()
            ) {
                return
            }
            ImmersiveBars.applyToWindow(activity.window, force = true)
            activity.mainHandler.postDelayed(this, 1500)
        }
    }

    val hideTemporarySystemBars = Runnable {
        activity.temporarySystemBarsVisible = false
        applyFullscreenMode()
    }

    /** Temporary reveal without persisting bar visibility or changing AirPlay's negotiated size. */
    fun showTemporarySystemBars(): Boolean {
        if (!activity.temporarySystemBarsEnabled || activity.gestureFingerCount == 0 ||
            activity.harmanFullscreenEnabled || activity.isMultiWindowActive() ||
            (!activity.hideTopBar && !activity.hideBottomBar)
        ) {
            return false
        }
        activity.mainHandler.removeCallbacks(hideTemporarySystemBars)
        activity.temporarySystemBarsVisible = true
        applyFullscreenMode()
        activity.mainHandler.postDelayed(hideTemporarySystemBars, 5_000L)
        return true
    }

    fun cancelTemporarySystemBars() {
        activity.mainHandler.removeCallbacks(hideTemporarySystemBars)
        if (activity.temporarySystemBarsVisible) {
            activity.temporarySystemBarsVisible = false
            applyFullscreenMode()
        }
    }

    fun applyFullscreenMode() {
        val multiWindow = activity.isMultiWindowActive()
        if (activity.harmanFullscreenEnabled) {
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            // Apply the OEM-specific bit last; the standard insets controller does not preserve it.
            HarmanFullscreenCompat.apply(activity.window)
            activity.harmanFullscreenApplied = true
            return
        }
        if (activity.harmanFullscreenApplied) {
            HarmanFullscreenCompat.clear(activity.window)
            activity.harmanFullscreenApplied = false
        }
        // While a CarPlay session is live, force fullscreen regardless of the toggles so the
        // CarPlay picture always hides the status + navigation bars, like the system car UI.
        val carPlayActive = activity.activeAirPlaySession != null
        // A three-finger upward swipe can briefly reveal hidden bars even during a live session.
        val peekBars = activity.temporarySystemBarsVisible
        val hideTop = ((activity.hideTopBar || carPlayActive) && !peekBars) && !multiWindow
        val hideBottom = ((activity.hideBottomBar || carPlayActive) && !peekBars) && !multiWindow
        val immersive = hideTop || hideBottom
        if (hideTop && hideBottom) {
            // Full sticky immersive: share the same flags/window path as the shell.
            ImmersiveBars.applyToWindow(activity.window, force = true)
        } else {
            // KitKat / Changan OS_S311-WT: one bar hidden still needs sticky immersive layout.
            ImmersiveBars.applyPartial(activity.window, hideTop, hideBottom)
        }

        if (!systemUiVisibilityListenerInstalled) {
            systemUiVisibilityListenerInstalled = true
            @Suppress("DEPRECATION")
            activity.window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (activity.isFinishing || activity.isDestroyed) return@setOnSystemUiVisibilityChangeListener
                if (activity.isMultiWindowActive()) return@setOnSystemUiVisibilityChangeListener
                // CarPlay 会话激活时强制全屏（见 applyFullscreenMode）：无论设置开关如何，
                // 下拉状态栏/导航栏意外显示都必须立即重新隐藏，不能遮挡 CarPlay 画面。
                val carPlayActiveNow = activity.activeAirPlaySession != null
                val wantHide = activity.hideTopBar || activity.hideBottomBar || carPlayActiveNow
                // 三指上滑临时显示系统栏期间（5 秒）不强制隐藏，超时后由 hideTemporarySystemBars 恢复。
                if (!wantHide || activity.temporarySystemBarsVisible) {
                    return@setOnSystemUiVisibilityChangeListener
                }
                @Suppress("DEPRECATION")
                val navShowing = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
                @Suppress("DEPRECATION")
                val statusShowing = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
                if ((activity.hideBottomBar || carPlayActiveNow) && navShowing) {
                    activity.window.decorView.postDelayed({ applyFullscreenMode() }, 200)
                } else if ((activity.hideTopBar || carPlayActiveNow) && statusShowing) {
                    activity.window.decorView.postDelayed({ applyFullscreenMode() }, 200)
                }
            }
        }
        // 心跳重隐：CarPlay 会话期间周期压导航栏，对抗下拉状态栏后 ROM 不自动恢复隐藏。
        activity.mainHandler.removeCallbacks(carPlayBarGuard)
        if (carPlayActive && immersive) activity.mainHandler.postDelayed(carPlayBarGuard, 1500)
    }

    fun clearBarGuard() {
        activity.mainHandler.removeCallbacks(carPlayBarGuard)
        activity.mainHandler.removeCallbacks(hideTemporarySystemBars)
    }
}
