// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.telephony.PhoneStateListener
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.TelephonyManager
import com.shihab.diplay.network.CarHotspotStatus

/** 顶栏三枚状态：移动信号、Wi-Fi、蓝牙。热点只在车机 SoftAP 打开时出现。 */
enum class ShellBluetooth { OFF, ON, CONNECTED }

data class ShellStatusSnapshot(
    val cellVisible: Boolean,
    val cellInService: Boolean,
    val cellBars: Int,
    val wifiOn: Boolean,
    val wifiConnected: Boolean,
    val wifiBars: Int,
    val hotspotOn: Boolean,
    val bluetooth: ShellBluetooth,
) {
    companion object {
        val EMPTY = ShellStatusSnapshot(
            cellVisible = false,
            cellInService = false,
            cellBars = 0,
            wifiOn = false,
            wifiConnected = false,
            wifiBars = 0,
            hotspotOn = false,
            bluetooth = ShellBluetooth.OFF,
        )
    }
}

/**
 * 信号格数换算。LTE 优先，其次 GSM ASU，再其次 CDMA dBm。
 * Wi-Fi 沿用 KitKat `WifiManager.calculateSignalLevel` 的 -100..-55 dBm 区间，收成 0..3 格。
 */
object ShellStatusLevels {
    fun cellBars(gsm: Boolean, gsmAsu: Int, cdmaDbm: Int, lteRsrp: Int, lteAsu: Int): Int {
        if (lteRsrp in -140..-44) return lteRsrpBars(lteRsrp)
        if (lteAsu in 1..63) return lteAsuBars(lteAsu)
        return if (gsm) gsmBars(gsmAsu) else cdmaBars(cdmaDbm)
    }

    fun gsmBars(asu: Int): Int = when {
        asu == 99 || asu <= 1 -> 0
        asu >= 12 -> 4
        asu >= 8 -> 3
        asu >= 5 -> 2
        else -> 1
    }

    fun lteRsrpBars(rsrp: Int): Int = when {
        rsrp >= -85 -> 4
        rsrp >= -95 -> 3
        rsrp >= -105 -> 2
        rsrp >= -115 -> 1
        else -> 0
    }

    fun lteAsuBars(asu: Int): Int = when {
        asu >= 16 -> 4
        asu >= 12 -> 3
        asu >= 8 -> 2
        asu >= 1 -> 1
        else -> 0
    }

    fun cdmaBars(dbm: Int): Int = when {
        dbm >= 0 || dbm < -120 -> 0
        dbm >= -75 -> 4
        dbm >= -85 -> 3
        dbm >= -95 -> 2
        dbm >= -100 -> 1
        else -> 0
    }

    /** @return 0..3 */
    fun wifiBars(rssi: Int): Int {
        if (rssi <= -100) return 0
        if (rssi >= -55) return 3
        return ((rssi + 100) * 3 / 45).coerceIn(0, 3)
    }

    fun bluetooth(enabled: Boolean, headsetConnected: Boolean, a2dpConnected: Boolean): ShellBluetooth = when {
        !enabled -> ShellBluetooth.OFF
        headsetConnected || a2dpConnected -> ShellBluetooth.CONNECTED
        else -> ShellBluetooth.ON
    }
}

/**
 * 读车机无线电。移动信号在没有电话模块或没有 [android.Manifest.permission.READ_PHONE_STATE] 时隐藏。
 * 监听在主线程 start/stop，和壳层 1 秒刷新对齐。
 */
class ShellStatusProbe(context: Context) {
    private val app = context.applicationContext
    private val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var listener: PhoneStateListener? = null
    private var cellDenied = false

    @Volatile private var cellInService = true
    @Volatile private var cellBars = 0

    @SuppressLint("MissingPermission")
    fun start() {
        if (listener != null || cellDenied) return
        val radio = telephony ?: return
        val type = runCatching { radio.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE)
        if (type == TelephonyManager.PHONE_TYPE_NONE) return
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            cellDenied = true
            return
        }
        val created = object : PhoneStateListener() {
            override fun onServiceStateChanged(state: ServiceState) {
                cellInService = state.state == ServiceState.STATE_IN_SERVICE
            }

            override fun onSignalStrengthsChanged(signal: SignalStrength) {
                cellBars = barsFrom(signal)
            }
        }
        val listened = runCatching {
            @Suppress("DEPRECATION")
            radio.listen(created, PhoneStateListener.LISTEN_SERVICE_STATE or PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        }.isSuccess
        if (listened) listener = created else cellDenied = true
    }

    fun stop() {
        val radio = telephony
        val current = listener
        listener = null
        if (radio != null && current != null) {
            runCatching {
                @Suppress("DEPRECATION")
                radio.listen(current, PhoneStateListener.LISTEN_NONE)
            }
        }
    }

    fun snapshot(): ShellStatusSnapshot {
        val wifi = readWifi()
        return ShellStatusSnapshot(
            cellVisible = cellAvailable(),
            cellInService = cellInService,
            cellBars = if (cellInService) cellBars else 0,
            wifiOn = wifi.on,
            wifiConnected = wifi.connected,
            wifiBars = wifi.bars,
            hotspotOn = readHotspot(),
            bluetooth = readBluetooth(),
        )
    }

    private fun cellAvailable(): Boolean {
        if (cellDenied) return false
        val radio = telephony ?: return false
        val type = runCatching { radio.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE)
        return type != TelephonyManager.PHONE_TYPE_NONE
    }

    @Suppress("DEPRECATION")
    private fun barsFrom(signal: SignalStrength): Int {
        val gsm = hiddenBoolean(signal, "isGsm") ?: true
        return ShellStatusLevels.cellBars(
            gsm = gsm,
            gsmAsu = signal.gsmSignalStrength,
            cdmaDbm = signal.cdmaDbm,
            lteRsrp = hiddenInt(signal, "getLteRsrp"),
            lteAsu = hiddenInt(signal, "getLteSignalStrength"),
        )
    }

    private data class WifiRead(val on: Boolean, val connected: Boolean, val bars: Int)

    @SuppressLint("MissingPermission")
    private fun readWifi(): WifiRead {
        if (!granted(Manifest.permission.ACCESS_WIFI_STATE)) return WifiRead(false, false, 0)
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return WifiRead(false, false, 0)
        val on = runCatching { @Suppress("DEPRECATION") wifi.isWifiEnabled }.getOrDefault(false)
        if (!on) return WifiRead(false, false, 0)
        val info = runCatching { wifi.connectionInfo }.getOrNull()
        val rssi = info?.rssi ?: -127
        val connected = info != null &&
            info.supplicantState == SupplicantState.COMPLETED &&
            rssi < 0 && rssi > -127
        return WifiRead(true, connected, if (connected) ShellStatusLevels.wifiBars(rssi) else 0)
    }

    private fun readHotspot(): Boolean =
        runCatching { CarHotspotStatus.isEnabled(app) == true }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun readBluetooth(): ShellBluetooth {
        if (!granted(Manifest.permission.BLUETOOTH)) return ShellBluetooth.OFF
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return ShellBluetooth.OFF
        val enabled = runCatching { adapter.isEnabled }.getOrDefault(false)
        if (!enabled) return ShellBluetooth.OFF
        val headset = profileConnected(adapter, BluetoothProfile.HEADSET)
        val a2dp = profileConnected(adapter, BluetoothProfile.A2DP)
        return ShellStatusLevels.bluetooth(true, headset, a2dp)
    }

    @SuppressLint("MissingPermission")
    private fun profileConnected(adapter: BluetoothAdapter, profile: Int): Boolean =
        runCatching { adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)

    private fun granted(permission: String): Boolean =
        app.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hiddenInt(target: Any, name: String): Int =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Int }.getOrNull() ?: Int.MIN_VALUE

    private fun hiddenBoolean(target: Any, name: String): Boolean? =
        runCatching { target.javaClass.getMethod(name).invoke(target) as? Boolean }.getOrNull()
}
