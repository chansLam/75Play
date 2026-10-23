package com.shihab.diplay.network

import android.content.Context
import android.content.IntentFilter
import android.content.Intent
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shihab.diplay.compat.realSdkInt

/**
 * Reads whether the head unit's own Wi-Fi hotspot is on, for the "Car hotspot" link.
 *
 * The user can turn it on in car settings or opt into [CarHotspotTethering] after granting permission.
 * On KitKat SoftAP can also be toggled via the hidden [enableIfPossible] path.
 */
object CarHotspotStatus {
    private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
    private const val EXTRA_WIFI_AP_STATE = "wifi_state"
    private const val EXTRA_WIFI_AP_MODE = "wifi_ap_mode"
    private const val WIFI_AP_STATE_ENABLED = 13

    /**
     * True/false from the Wi-Fi AP state, or null when the firmware hides it (then callers must
     * not block the connection). Interface flags are not used: BYD keeps wlan1 up with an address
     * while tethering is off.
     */
    fun isEnabled(context: Context): Boolean? {
        val app = context.applicationContext
        val wifi = wifiManager(app)
        return read(
            state = { wifi?.let { WifiManager::class.java.getMethod("getWifiApState").invoke(it) as? Int } },
            enabled = { wifi?.let { WifiManager::class.java.getMethod("isWifiApEnabled").invoke(it) as? Boolean } },
            sticky = { app.registerReceiver(null, IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)) },
        )
    }

    /**
     * KitKat SoftAP enable for Changan OS_S311-WT style ROMs. Closes STA first when needed.
     * Prefers [preferredConfig] (DiPlay SSID/passphrase), then the ROM-saved AP config, then null.
     * If SoftAP enable fails after turning STA off, STA is restored when it was previously on.
     */
    fun enableIfPossible(context: Context, preferredConfig: WifiConfiguration? = null): Boolean? {
        // Spoofed ROMs may report SDK ≥ 26 while RELEASE is still 4.4.x.
        if (realSdkInt() >= Build.VERSION_CODES.O) return null
        val wifi = wifiManager(context.applicationContext) ?: return null
        return runCatching {
            @Suppress("DEPRECATION")
            val wifiWasEnabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
            if (wifiWasEnabled) {
                @Suppress("DEPRECATION")
                runCatching { wifi.isWifiEnabled = false }
            }
            val method = WifiManager::class.java.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            )
            val savedConfig = readSoftApConfiguration(wifi)
            val candidates = listOfNotNull(preferredConfig, savedConfig)
            var enabled = false
            for (config in candidates) {
                enabled = runCatching { method.invoke(wifi, config, true) as Boolean }.getOrDefault(false)
                if (enabled) break
            }
            if (!enabled) {
                enabled = runCatching { method.invoke(wifi, null, true) as Boolean }.getOrDefault(false)
            }
            if (!enabled && wifiWasEnabled) {
                @Suppress("DEPRECATION")
                runCatching { wifi.isWifiEnabled = true }
            }
            enabled
        }.getOrNull()
    }

    /**
     * Build a KitKat SoftAP [WifiConfiguration] for CS75 ROMs.
     * Empty [passphrase] → open AP; otherwise raw (unquoted) WPA2 PSK — S401 does not strip quotes.
     */
    fun wifiConfiguration(ssid: String, passphrase: String): WifiConfiguration =
        WifiConfiguration().apply {
            SSID = ssid
            status = WifiConfiguration.Status.ENABLED
            if (passphrase.isEmpty()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = passphrase
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            }
        }

    private fun wifiManager(app: Context): WifiManager? =
        if (realSdkInt() >= Build.VERSION_CODES.M) {
            app.getSystemService(WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }

    internal fun stateEnabled(state: Int?): Boolean? = state?.takeIf { it in 10..14 }?.let { it == WIFI_AP_STATE_ENABLED }

    internal fun read(state: () -> Int?, enabled: () -> Boolean?, sticky: () -> Intent?): Boolean? {
        stateEnabled(runCatching(state).getOrNull())?.let { return it }
        runCatching(enabled).getOrNull()?.let { return it }
        val intent = runCatching(sticky).getOrNull() ?: return null
        // Local-only AP state says nothing about the car's tethered hotspot, including on multi-AP firmware.
        if (intent.hasExtra(EXTRA_WIFI_AP_MODE) && intent.getIntExtra(EXTRA_WIFI_AP_MODE, -1) != 1) return null
        return stateEnabled(intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1))
    }
}
