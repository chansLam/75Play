package com.shihab.diplay.orchestration

import android.content.Context
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.network.HotspotAddressPolicy
import com.shihab.diplay.network.WirelessHotspotBackend
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessInterfaceDiagnostics
import com.shihab.diplay.network.WirelessReceiveDiagnostics
import com.shihab.diplay.network.WirelessStartupDiagnostics
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/**
 * Post–SoftAP wireless stack: start hotspot ([WirelessHotspotBringUp]), pause STA scans, pick a
 * host address, and publish [CarPlayStatus.HotspotReady]. Extracted from [CarPlayController] so
 * [CarPlayController.runWireless] stays a stage orchestrator.
 */
internal object WirelessHotspotStack {
    private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"

    /** Cross-stage state produced while bringing up the hotspot and resolving its address. */
    class BringUp(
        val mfi: MfiAuthenticator,
        val hotspotInfo: WirelessHotspotInfo,
        val startedHotspot: WirelessHotspotManager?,
        val hostAddress: InetAddress,
        val hostAddressText: String,
        val deviceIdentifier: String,
        val diagnostics: WirelessStartupDiagnostics,
    )

    fun bringUp(
        appContext: Context,
        config: CarPlayRuntimeConfig,
        airPlayConfig: AirPlayConfig,
        generation: Int,
        isStaleWirelessRun: (Int) -> Boolean,
        debugLog: (String) -> Unit,
        onStatus: (CarPlayStatus) -> Unit,
        wirelessResourceLock: Any,
        getHotspot: () -> WirelessHotspotManager?,
        setHotspot: (WirelessHotspotManager?) -> Unit,
        onNetworkChanged: () -> Unit,
        closeBestEffort: (String, () -> Unit) -> Unit,
        mfiClient: MfiAuthenticator?,
        wirelessPrefsName: String,
        hotspotFamilyPreferenceKey: String,
        beginConnectionProof: (Int, () -> Unit) -> Unit,
        setPublishedAddress: (InetAddress) -> Unit,
        setAlternateAddress: (InetAddress?) -> Unit,
        setDiagnostics: (WirelessStartupDiagnostics) -> Unit,
        bonjourSnapshot: () -> String,
        pauseWifiScansLocked: (WirelessHotspotBackend) -> Unit,
    ): BringUp? {
        val mfi = mfiClient
            ?: throw IOException("MFi coprocessor client is unavailable")
        val hotspotInfo = WirelessHotspotBringUp.start(
            appContext = appContext,
            config = config,
            generation = generation,
            isStaleWirelessRun = isStaleWirelessRun,
            debugLog = debugLog,
            wirelessResourceLock = wirelessResourceLock,
            getHotspot = getHotspot,
            setHotspot = setHotspot,
            onNetworkChanged = onNetworkChanged,
            closeBestEffort = closeBestEffort,
        )
        if (isStaleWirelessRun(generation)) {
            return null
        }
        pauseWifiScansLocked(hotspotInfo.backend)
        val startedHotspot = getHotspot()
        beginConnectionProof(generation) {
            if (!isStaleWirelessRun(generation)) startedHotspot?.onCarPlayConfirmed()
        }
        val storedFamily = appContext.getSharedPreferences(wirelessPrefsName, Context.MODE_PRIVATE)
            .getString(hotspotFamilyPreferenceKey, null)
        val preferredFamily = if (storedFamily == "AUTO") null else HotspotAddressPolicy.parseFamily(storedFamily)
        val candidates = hotspotCandidates(hotspotInfo, debugLog)
        val hostAddress = HotspotAddressPolicy.select(candidates, preferredFamily)
            ?: hotspotInfo.hostAddress
            ?: throw IOException(
                "Wireless hotspot did not provide a usable host address",
            )
        val alternate = HotspotAddressPolicy.alternateFamilyCandidate(candidates, hostAddress)
        setPublishedAddress(hostAddress)
        setAlternateAddress(alternate)
        if (
            hostAddress is Inet6Address &&
            (!hostAddress.isLinkLocalAddress || hostAddress.scopeId == 0)
        ) {
            throw IOException(
                "Wireless hotspot link-local IPv6 address is not scoped",
            )
        }
        val hostAddressText = hostAddressText(hostAddress)
        val deviceIdentifier = hotspotInfo.bssid
            ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
            ?: airPlayConfig.deviceId
        debugLog(
            "wireless hotspot backend=${hotspotInfo.backend.label} " +
                "iface=${hotspotInfo.interfaceName ?: "unknown"} " +
                "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                "preference=${preferredFamily ?: "default(IPv4)"} " +
                "alternate=${alternate != null} " +
                "identitySource=${if (deviceIdentifier == hotspotInfo.bssid) "interface" else "saved"} " +
                "host=$hostAddressText " +
                "band=${hotspotInfo.bandLabel} channel=${hotspotInfo.channel} " +
                "frequency=${hotspotInfo.frequencyMHz?.toString() ?: "unknown"}MHz",
        )
        val receiveDiagnostics = WirelessReceiveDiagnostics(hotspotInfo.interfaceName)
        val diagnostics = WirelessStartupDiagnostics(
            sample = {
                "${WirelessInterfaceDiagnostics.snapshot(hotspotInfo.interfaceName)} " +
                    "${startedHotspot?.connectionDiagnosticSnapshot() ?: "association=unknown"} " +
                    bonjourSnapshot() + "\n" +
                    receiveDiagnostics.snapshot()
            },
            log = { message -> if (!isStaleWirelessRun(generation)) debugLog(message) },
        )
        setDiagnostics(diagnostics)
        onStatus(
            CarPlayStatus.HotspotReady(
                ssid = hotspotInfo.ssid,
                band = hotspotInfo.bandLabel,
                channel = hotspotInfo.channel,
                bssid = deviceIdentifier,
                address = hostAddressText,
                backend = hotspotInfo.backend.label,
            ),
        )
        onStatus(CarPlayStatus.WaitingForPairedIphone)
        return BringUp(
            mfi = mfi,
            hotspotInfo = hotspotInfo,
            startedHotspot = startedHotspot,
            hostAddress = hostAddress,
            hostAddressText = hostAddressText,
            deviceIdentifier = deviceIdentifier,
            diagnostics = diagnostics,
        )
    }

    fun hotspotCandidates(
        info: WirelessHotspotInfo,
        debugLog: (String) -> Unit,
    ): List<InetAddress> {
        val name = info.interfaceName ?: return listOfNotNull(info.hostAddress)
        val scanned = try {
            NetworkInterface.getByName(name)?.let { Collections.list(it.inetAddresses) }.orEmpty()
        } catch (error: Exception) {
            debugLog("wireless interface scan failed iface=$name error=${error.javaClass.simpleName}")
            emptyList()
        }
        return if (scanned.isEmpty()) listOfNotNull(info.hostAddress) else scanned
    }

    fun hostAddressText(address: InetAddress): String {
        val text = address.hostAddress?.substringBefore('%')
        if (text.isNullOrBlank()) {
            throw IOException("Wireless hotspot host address is unavailable")
        }
        return text
    }

}
