// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * 与系统设置里的 Wi-Fi 列表同一节奏（AOSP WifiTracker）：先显示缓存，
 * 收到 SCAN_RESULTS_AVAILABLE_ACTION 立刻重画，列表可见期间每 10 秒再请求一次扫描。
 */
internal class WifiListWatcher(
    private val app: Context,
    private val host: DiPlayHost,
    private val onResults: (List<WifiEntry>) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var started = false
    private val rescan = object : Runnable {
        override fun run() {
            if (!started) return
            host.requestWifiScan()
            handler.postDelayed(this, RESCAN_INTERVAL_MS)
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!started) return
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                onResults(host.peekWifiNetworks())
            }
        }
    }

    /** Attach path: idempotent — already-started calls no-op, so hide/show reattach is safe. */
    fun start() {
        if (started) return
        started = true
        app.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val cached = host.peekWifiNetworks()
        if (cached.isNotEmpty()) onResults(cached)
        handler.post(rescan)
    }

    /** Detach path: idempotent — safe to call on Fragment hide even if the view never attached. */
    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(rescan)
        runCatching { app.unregisterReceiver(receiver) }
    }

    private companion object {
        /** AOSP Settings WifiTracker.WIFI_RESCAN_INTERVAL_MS. */
        const val RESCAN_INTERVAL_MS = 10_000L
    }
}
