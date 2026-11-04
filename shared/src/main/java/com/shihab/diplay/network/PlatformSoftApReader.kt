package com.shihab.diplay.network

import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import com.shihab.diplay.model.Iap2WirelessSecurity

internal data class SoftApSnapshot(
    val ssid: String,
    val band: Int?,
    val channel: Int,
    val security: Iap2WirelessSecurity,
)

/** Loaded by name only when the real SDK is at least 30. */
internal object PlatformSoftApReader {
    @JvmStatic
    fun read(wifiManager: WifiManager): SoftApSnapshot? {
        val method = WifiManager::class.java.getMethod("getSoftApConfiguration")
        val configuration = method.invoke(wifiManager) as? SoftApConfiguration ?: return null
        val ssid = configuration.ssid ?: return null
        val band = SoftApConfiguration::class.java.getMethod("getBand").invoke(configuration) as? Int
        val channel = (SoftApConfiguration::class.java.getMethod("getChannel").invoke(configuration) as? Number)
            ?.toInt() ?: 0
        return SoftApSnapshot(ssid, band, channel, security(configuration.securityType))
    }

    private fun security(securityType: Int): Iap2WirelessSecurity = when (securityType) {
        SoftApConfiguration.SECURITY_TYPE_OPEN -> Iap2WirelessSecurity.NONE
        SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Iap2WirelessSecurity.WPA_WPA2
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> Iap2WirelessSecurity.WPA3_TRANSITION
        SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Iap2WirelessSecurity.WPA3_ONLY
        else -> Iap2WirelessSecurity.WPA_WPA2
    }
}
