// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.host

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.shihab.diplay.CarAppCatalog
import com.shihab.diplay.CarPlayAppPanels
import com.shihab.diplay.CarPlayConnectText
import com.shihab.diplay.CarPlayHostActivity
import com.shihab.diplay.CarPlayPicture
import com.shihab.diplay.DiagnosticRedactor
import com.shihab.diplay.host.R

/**
 * Host chrome that is not part of the session state machine:
 * system-app menu, picture panel, and connection-stage copy.
 */
internal class HostChrome(private val activity: CarPlayHostActivity) {

    fun buildSystemAppsMenu(apps: List<CarAppCatalog.App>): View =
        CarPlayAppPanels.buildAppsPopup(
            activity,
            CarPlayAppPanels.shownApps(activity, apps),
            onLaunch = { app ->
                closeSystemAppsMenu()
                activity.launchHeadUnitApp(app)
            },
            onClose = ::closeSystemAppsMenu,
        )

    fun replaceSystemAppsMenu(apps: List<CarAppCatalog.App>) {
        val previous = activity.systemAppsMenu ?: return
        val parent = previous.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(previous)
        parent.removeView(previous)
        activity.systemAppsMenu = buildSystemAppsMenu(apps)
        parent.addView(activity.systemAppsMenu, index, FrameLayout.LayoutParams(-1, -1))
    }

    internal fun openSystemAppsMenu() {
        if (activity.systemAppsOpen) return
        activity.controller?.sendTouch(emptyList())
        activity.systemAppsOpen = true
        activity.gestureOverlay?.visibility = View.GONE
        val cached = CarAppCatalog.peek()
        if (cached != null) replaceSystemAppsMenu(cached)
        activity.systemAppsMenu?.visibility = View.VISIBLE
        activity.syncFullscreenChrome()
        updateDebugOverlays()
        val loadId = ++activity.appsMenuLoadGeneration
        if (cached != null && !CarAppCatalog.isStale()) return
        activity.appsCatalogExecutor.execute {
            val apps = runCatching { CarAppCatalog.enumerate(activity.applicationContext, forceRefresh = true) }
                .getOrElse { emptyList() }
            activity.runOnUiThread {
                if (loadId != activity.appsMenuLoadGeneration || !activity.systemAppsOpen || activity.shuttingDown.get()) return@runOnUiThread
                replaceSystemAppsMenu(apps)
                activity.systemAppsMenu?.visibility = View.VISIBLE
                activity.syncFullscreenChrome()
                updateDebugOverlays()
            }
        }
    }

    fun closeSystemAppsMenu() {
        if (!activity.systemAppsOpen) return
        activity.systemAppsOpen = false
        activity.appsMenuLoadGeneration++
        activity.systemAppsMenu?.visibility = View.GONE
        activity.gestureOverlay?.visibility = View.VISIBLE
        activity.syncFullscreenChrome()
        updateDebugOverlays()
    }

    fun closePicturePanel() {
        ++activity.picturePanelGeneration
        activity.picturePanel?.let { (it.parent as? ViewGroup)?.removeView(it) }
        activity.picturePanel = null
        CarPlayPicture.showOriginal(false)
    }

    fun setConnectionStage(message: String, exact: Boolean = false) {
        activity.latestStage = message
        activity.stageStatusView?.text = if (exact) message else friendlyStage(message)
        activity.stageStartedAtMillis = System.currentTimeMillis()
        val generation = ++activity.stageWatchdogGeneration
        activity.stageDiagnosticView?.text = activity.getString(R.string.dialog_stage_elapsed, 0)
        fun tick() {
            if (generation != activity.stageWatchdogGeneration || activity.shuttingDown.get()) return
            val elapsed = ((System.currentTimeMillis() - activity.stageStartedAtMillis) / 1000L).coerceAtLeast(0L)
            activity.stageDiagnosticView?.text = if (elapsed >= 20L) {
                activity.getString(R.string.host_stage_waiting_stuck, elapsed)
            } else {
                activity.getString(R.string.dialog_stage_elapsed, elapsed)
            }
            activity.mainHandler.postDelayed({ tick() }, 1000L)
        }
        activity.mainHandler.postDelayed({ tick() }, 1000L)
        updateDebugOverlays()
    }

    fun setFailedConnectionStage(message: String) {
        activity.latestStage = message
        activity.stageWatchdogGeneration++
        activity.stageDiagnosticView?.text = DiagnosticRedactor.redact(message)?.take(240)
        val friendly = friendlyStage(message)
        val base = if (friendly == activity.getString(R.string.getting_carplay_ready)) {
            activity.getString(R.string.connection_interrupted_retrying)
        } else {
            friendly
        }
        activity.stageStatusView?.text = withRetryDetail(base, message)
        updateDebugOverlays()
    }

    fun updateDebugOverlays() {
        activity.statusScrollView?.visibility = View.GONE
        // 出画前由 CarPlayLoadingLayer 盖住黑屏，第一帧到达后再揭开。
    }

    fun friendlyStage(message: String): String =
        CarPlayConnectText.friendlyStage(activity, message)

    fun withRetryDetail(base: String, message: String): String {
        val detail = DiagnosticRedactor.redact(message)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(base, ignoreCase = true) }
            ?: return base
        return "$base\n$detail"
    }

}
