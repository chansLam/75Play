package com.shihab.diplay.orchestration

import android.content.Context
import com.shihab.diplay.network.CarHotspotEnable
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.network.CarHotspotTethering
import com.shihab.diplay.network.LegacyWifiP2pGroupManager
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.model.WirelessStartupException
import com.shihab.diplay.model.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupPolicy
import java.io.IOException

/**
 * SoftAP / wireless hotspot bring-up extracted from [CarPlayController].
 *
 * Lock ownership stays with the controller: [wirelessResourceLock] and hotspot field access are
 * injected so call order and cancellation semantics are unchanged.
 */
internal object WirelessHotspotBringUp {
    private const val HOTSPOT_START_TIMEOUT_MILLIS = 60_000L
    private const val WIFI_P2P_START_TIMEOUT_MILLIS = 20_000L
    private const val LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS = 25_000L

    fun start(
        appContext: Context,
        config: CarPlayRuntimeConfig,
        generation: Int,
        isStaleWirelessRun: (Int) -> Boolean,
        debugLog: (String) -> Unit,
        wirelessResourceLock: Any,
        getHotspot: () -> WirelessHotspotManager?,
        setHotspot: (WirelessHotspotManager?) -> Unit,
        onNetworkChanged: () -> Unit,
        closeBestEffort: (String, () -> Unit) -> Unit,
    ): WirelessHotspotInfo {
        if (config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P) {
            return startLegacyWifiDirectWithFallback(
                appContext = appContext,
                generation = generation,
                isStaleWirelessRun = isStaleWirelessRun,
                debugLog = debugLog,
                getHotspot = getHotspot,
                setHotspot = setHotspot,
                closeBestEffort = closeBestEffort,
            )
        }
        val hotspotMode = config.wirelessHotspotMode
        if (CarHotspotSettings.shouldEnable(
                appContext, config.transport == CarPlayTransport.WIRELESS, hotspotMode,
            )
        ) {
            // SoftAP enable budget is separate from interface readiness (see below).
            val manualCredentials = hotspotMode == WirelessHotspotMode.MANUAL
            val result = CarHotspotTethering.enable(
                appContext,
                isCancelled = { isStaleWirelessRun(generation) ||
                    !CarHotspotSettings.enabled(appContext) },
                timeoutMillis = WirelessStartupPolicy.SOFTAP_ENABLE_MILLIS,
                log = { debugLog("generation=$generation atNs=${System.nanoTime()} $it; awaiting SoftAP enable") },
                ssid = config.manualHotspotSsid?.takeIf { manualCredentials },
                passphrase = config.manualHotspotPassphrase?.takeIf { manualCredentials },
            )
            val manualFallback = result == CarHotspotTethering.Result.UNSUPPORTED &&
                CarHotspotStatus.isEnabled(appContext) == null
            if (result != CarHotspotTethering.Result.READY && !manualFallback) {
                throw WirelessStartupException(
                    if (result == CarHotspotTethering.Result.TIMED_OUT ||
                        result == CarHotspotTethering.Result.FAILED)
                        WirelessStartupFailure.HOTSPOT_NOT_READY else WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "${result.diagnostic}. Open the car hotspot settings and connect again.")
            }
        }
        if (hotspotMode == WirelessHotspotMode.MANUAL) {
            val state = CarHotspotStatus.isEnabled(appContext)
            if (CarHotspotSettings.reportedOff(appContext, state)) {
                // MANUAL wireless on KitKat SoftAP: always try hidden setWifiApEnabled when the
                // car hotspot is off. Independent of the ADB-gated auto-enable setting.
                val ssid = config.manualHotspotSsid?.takeIf { it.isNotBlank() }
                val pass = config.manualHotspotPassphrase.orEmpty()
                if (ssid != null && pass.isNotEmpty() && pass.length !in 8..63) {
                    throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "The car hotspot is off. Turn it on in the car settings and connect again.")
                }
                val enableResult = CarHotspotEnable.enable(
                    context = appContext,
                    ssid = ssid,
                    passphrase = pass.takeIf { ssid != null },
                    waitUntilReadyMs = 0L,
                    log = debugLog,
                )
                if (!enableResult.ok) {
                    throw WirelessStartupException(WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                        "The car hotspot is off. Turn it on in the car settings and connect again.")
                }
                // Launch warmup or a racing enable may already have flipped SoftAP on.
                if (CarHotspotStatus.isEnabled(appContext) == true) {
                    debugLog("car hotspot enable requested; SoftAP already ENABLED")
                } else {
                    debugLog("car hotspot was off; enable requested; waiting for SoftAP ENABLED")
                    CarHotspotEnableGuard.awaitSoftApEnabled(appContext) { isStaleWirelessRun(generation) }
                }
            } else if (state == false) {
                debugLog("car hotspot: Android reports it off; connecting anyway, as the driver chose")
            }
        }
        val manager: WirelessHotspotManager = HotspotPolicyRouter.create(
            mode = hotspotMode,
            context = appContext,
            config = config,
            onLog = debugLog,
            onManualDiagnostic = { debugLog("generation=$generation $it") },
            isCancelled = { isStaleWirelessRun(generation) },
            onNetworkChanged = onNetworkChanged,
        )
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) {
                manager.close()
                throw java.io.InterruptedIOException("Hotspot startup cancelled")
            }
            setHotspot(manager)
        }
        // Fresh interface-ready budget after SoftAP is on (not shared with enable wait).
        val timeoutMillis = if (hotspotMode == WirelessHotspotMode.WIFI_P2P) {
            WIFI_P2P_START_TIMEOUT_MILLIS
        } else if (hotspotMode == WirelessHotspotMode.MANUAL) {
            WirelessStartupPolicy.INTERFACE_READY_MILLIS
        } else {
            HOTSPOT_START_TIMEOUT_MILLIS
        }
        return try {
            manager.start(timeoutMillis)
        } catch (failure: Exception) {
            if (getHotspot() === manager) setHotspot(null)
            closeBestEffort(hotspotMode.name) { manager.close() }
            if (isStaleWirelessRun(generation)) throw failure
            throw IOException(
                "Could not establish ${hotspotMode.name} hotspot: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }

    private fun startLegacyWifiDirectWithFallback(
        appContext: Context,
        generation: Int,
        isStaleWirelessRun: (Int) -> Boolean,
        debugLog: (String) -> Unit,
        getHotspot: () -> WirelessHotspotManager?,
        setHotspot: (WirelessHotspotManager?) -> Unit,
        closeBestEffort: (String, () -> Unit) -> Unit,
    ): WirelessHotspotInfo {
        var primary: WirelessHotspotManager? = null
        try {
            primary = LegacyWifiP2pGroupManager(appContext, debugLog)
            setHotspot(primary)
            debugLog("wireless legacy Wi-Fi Direct starting")
            return primary.start(LEGACY_WIFI_P2P_START_TIMEOUT_MILLIS)
        } catch (primaryFailure: Exception) {
            if (getHotspot() === primary) setHotspot(null)
            closeBestEffort("legacy-wifi-p2p") { primary?.close() }
            if (isStaleWirelessRun(generation)) throw primaryFailure
            throw IOException(
                "Wi-Fi Direct could not start on this head unit; use the car hotspot: ${primaryFailure.message}",
                primaryFailure,
            )
        }
    }
}
