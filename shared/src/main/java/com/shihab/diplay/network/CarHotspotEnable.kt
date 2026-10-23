package com.shihab.diplay.network

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shihab.diplay.compat.realSdkInt

/**
 * Single SoftAP enable entry for CS75 KitKat head units.
 *
 * All UI / boot / controller paths should call [enable] (or thin wrappers around it) so STA
 * teardown, credential quoting, and readiness waits stay consistent.
 *
 * Higher-level session bring-up with ADB/tethering fallback remains [CarHotspotTethering.enable].
 */
object CarHotspotEnable {

    data class Result(
        val ok: Boolean,
        val diagnostic: String,
    )

    /** Freescale shared-radio settle after STA teardown before SoftAP bring-up. */
    private const val STA_TEARDOWN_SETTLE_MILLIS = 1_500L

    /** Settle after disabling SoftAP before station Wi-Fi restore. */
    private const val AP_DISABLE_SETTLE_MILLIS = 1_200L

    /**
     * Enable the car SoftAP using [ssid]/[passphrase] when provided, else the ROM-saved AP config.
     * Closes STA first when needed (shared radio on Freescale). Optionally waits until AP reports on.
     */
    fun enable(
        context: Context,
        ssid: String? = null,
        passphrase: String? = null,
        waitUntilReadyMs: Long = 800L,
        log: (String) -> Unit = {},
    ): Result {
        val app = context.applicationContext
        val preferred = when {
            ssid.isNullOrBlank() -> null
            else -> CarHotspotStatus.wifiConfiguration(ssid, passphrase.orEmpty())
        }
        if (CarHotspotStatus.isEnabled(app) == true) {
            log("CarHotspotEnable: already on")
            return Result(true, "already on")
        }
        // Freescale shared radio: give STA a moment to drop before SoftAP bring-up.
        val wifi = wifiManager(app)
        @Suppress("DEPRECATION")
        if (wifi != null && runCatching { wifi.isWifiEnabled }.getOrDefault(false)) {
            @Suppress("DEPRECATION")
            runCatching { wifi.isWifiEnabled = false }
            log("CarHotspotEnable: STA disabled; settling")
            sleepInterruptibly(STA_TEARDOWN_SETTLE_MILLIS)
        }
        val requested = CarHotspotStatus.enableIfPossible(app, preferred)
        log("CarHotspotEnable: enableIfPossible=$requested ssid=${ssid?.takeIf { it.isNotBlank() } ?: "(rom)"}")
        if (requested == null && realSdkInt() >= Build.VERSION_CODES.O) {
            return Result(false, "unsupported sdk")
        }
        if (waitUntilReadyMs > 0L) {
            val deadline = System.nanoTime() + waitUntilReadyMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                if (CarHotspotStatus.isEnabled(app) == true) {
                    return Result(true, "ready")
                }
                Thread.sleep(100L)
            }
        }
        val on = CarHotspotStatus.isEnabled(app) == true || requested == true
        return Result(on, if (on) "enabled" else "failed requested=$requested")
    }

    /** Turn SoftAP off and restore station Wi‑Fi when the radio was shared. */
    fun disable(context: Context, restoreStationWifi: Boolean = true): Boolean {
        val app = context.applicationContext
        val wifi = wifiManager(app) ?: return false
        val state = CarHotspotStatus.isEnabled(app)
        if (state == false) {
            if (restoreStationWifi) restoreWifi(wifi)
            return true
        }
        val ok = runCatching {
            WifiManager::class.java
                .getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType,
                )
                .invoke(wifi, null, false) as? Boolean ?: false
        }.getOrDefault(false)
        sleepInterruptibly(AP_DISABLE_SETTLE_MILLIS)
        if (restoreStationWifi) restoreWifi(wifi)
        return ok || CarHotspotStatus.isEnabled(app) != true
    }

    /**
     * Settling sleep that honors interruption: on [InterruptedException] the interrupt flag is
     * restored and we return immediately, matching the rest of the hotspot code's style.
     */
    private fun sleepInterruptibly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun restoreWifi(wifi: WifiManager) {
        @Suppress("DEPRECATION")
        if (!wifi.isWifiEnabled) {
            @Suppress("DEPRECATION")
            runCatching { wifi.isWifiEnabled = true }
        }
    }

    private fun wifiManager(app: Context): WifiManager? =
        if (realSdkInt() >= Build.VERSION_CODES.M) {
            app.getSystemService(WifiManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        }
}
