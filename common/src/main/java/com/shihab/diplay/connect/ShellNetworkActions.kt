// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.connect

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.shihab.diplay.ActiveSessionLog
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.OemSettingsIntents
import com.shihab.diplay.network.CarHotspotEnable
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.orchestration.WirelessHotspotMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * SoftAP open/close and OEM Settings launch helpers for the DiPlay shell.
 * Thin wrappers so [com.shihab.diplay.DiPlayActivity] stays one-liners.
 */
object ShellNetworkActions {
    const val DEFAULT_HOTSPOT_SSID = "75Play"
    const val DEFAULT_HOTSPOT_PASS = "12345678"
    private val radioGeneration = AtomicInteger()

    fun isCarHotspotEnabled(context: Context): Boolean =
        runCatching { CarHotspotStatus.isEnabled(context) == true }.getOrDefault(false)

    /**
     * Persist credentials (when blank, defaults) and enable SoftAP via [CarHotspotEnable].
     * @return true when the AP reports ready / enabled.
     */
    fun openCarHotspot(
        context: Context,
        ssid: String = AirPlayPersistence.loadManualHotspotSsid(context),
        passphrase: String = AirPlayPersistence.loadManualHotspotPassphrase(context),
    ): Boolean = runCatching {
        val apSsid = ssid.ifBlank { DEFAULT_HOTSPOT_SSID }
        val apPass = passphrase.ifBlank { DEFAULT_HOTSPOT_PASS }
        AirPlayPersistence.saveManualHotspotSsid(context, apSsid)
        AirPlayPersistence.saveManualHotspotPassphrase(context, apPass)
        val result = CarHotspotEnable.enable(
            context = context,
            ssid = apSsid,
            passphrase = apPass,
            waitUntilReadyMs = 800L,
        ) { msg -> AsyncDiagnosticLog.append(ActiveSessionLog.current, msg) }
        AsyncDiagnosticLog.append(
            ActiveSessionLog.current,
            "CarHotspot: open ${result.diagnostic} final=${result.ok}",
        )
        result.ok
    }.getOrDefault(false)

    /**
     * Persist the chosen connection mode so it survives a restart. Each tab must set BOTH
     * the hotspot mode AND wireless-enabled, otherwise a stale mode (e.g. MANUAL left over
     * from the hotspot tab) makes "connect" run the wrong transport.
     */
    fun saveHomeConnectionTab(context: Context, tab: String) {
        when (tab) {
            "hotspot" -> {
                AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.MANUAL)
                AirPlayPersistence.saveWirelessEnabled(context, true)
            }
            "lan" -> {
                AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.EXISTING_WIFI)
                AirPlayPersistence.saveWirelessEnabled(context, true)
            }
            else -> {
                AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
                AirPlayPersistence.saveWirelessEnabled(context, true)
            }
        }
    }

    fun prepareHomeConnectionRadio(
        context: Context,
        tab: String,
        enableWifi: () -> Boolean,
        onReady: () -> Unit,
    ) {
        val generation = radioGeneration.incrementAndGet()
        Thread({
            if (radioGeneration.get() != generation) return@Thread
            if (tab == "hotspot") {
                openCarHotspot(context)
            } else {
                // WiFi-direct / LAN share the radio with the car hotspot: close the AP first,
                // otherwise the hotspot keeps the radio busy and direct reports "open hotspot".
                if (radioGeneration.get() != generation) return@Thread
                if (isCarHotspotEnabled(context)) closeCarHotspot(context)
                if (radioGeneration.get() != generation) return@Thread
                enableWifi()
            }
            if (radioGeneration.get() != generation) return@Thread
            onReady()
        }, "diplay-home-radio").start()
    }

    fun closeCarHotspot(context: Context): Boolean = runCatching {
        val ok = CarHotspotEnable.disable(context, restoreStationWifi = true)
        AsyncDiagnosticLog.append(
            ActiveSessionLog.current,
            "CarHotspot: close apResult=$ok now=${isCarHotspotEnabled(context)}",
        )
        ok
    }.getOrDefault(false)

    /**
     * Try [preferred] first, then [OemSettingsIntents] components (stripped Freescale ROMs).
     * Invokes [onAllFailed] when nothing launches (e.g. toast).
     */
    fun openSystemSettings(
        activity: Activity,
        preferred: Intent? = null,
        onAllFailed: () -> Unit = {},
    ): Boolean {
        if (preferred != null && runCatching { activity.startActivity(preferred) }.isSuccess) {
            return true
        }
        for (component in OemSettingsIntents.candidates) {
            if (runCatching { activity.startActivity(Intent().setComponent(component)) }.isSuccess) {
                return true
            }
        }
        onAllFailed()
        return false
    }
}
