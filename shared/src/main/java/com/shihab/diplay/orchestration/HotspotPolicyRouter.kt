package com.shihab.diplay.orchestration

import android.content.Context
import com.shihab.diplay.network.ExistingWifiManager
import com.shihab.diplay.network.LegacyWifiP2pGroupManager
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.model.WirelessStartupException
import com.shihab.diplay.model.WirelessStartupFailure

/**
 * Single policy routing point over the API 19 hotspot backends: Wi-Fi P2P, Manual/Car-hotspot,
 * and Existing Wi-Fi.
 */
internal object HotspotPolicyRouter {
    fun create(
        mode: WirelessHotspotMode,
        context: Context,
        config: CarPlayRuntimeConfig,
        /** Plain diagnostic sink for P2P / Existing Wi-Fi builds. */
        onLog: (String) -> Unit,
        /** Manual-hotspot diagnostic sink (caller decorates it with the run generation). */
        onManualDiagnostic: (String) -> Unit,
        isCancelled: () -> Boolean,
        onNetworkChanged: () -> Unit,
    ): WirelessHotspotManager = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> LegacyWifiP2pGroupManager(context, onLog)
        WirelessHotspotMode.EXISTING_WIFI -> ExistingWifiManager(
            context, config.existingWifiSsid, config.existingWifiPassphrase, onLog,
            onNetworkChanged = { onNetworkChanged() },
        )
        WirelessHotspotMode.MANUAL -> ManualHotspotManager(
            context = context,
            ssid = config.manualHotspotSsid
                ?: throw WirelessStartupException(
                    WirelessStartupFailure.HOTSPOT_CONFIGURATION,
                    "Manual hotspot SSID is not configured",
                ),
            passphrase = config.manualHotspotPassphrase.orEmpty(),
            band = config.manualHotspotBand,
            channel = config.manualHotspotChannel,
            security = config.manualHotspotSecurity,
            onDiagnostic = onManualDiagnostic,
            isCancelled = isCancelled,
        )
    }
}
