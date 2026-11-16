// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay.ui.shell

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
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
 * 热点、蓝牙和 Wi-Fi 在 start 时读一次，之后只跟广播更新；1 秒刷新只读缓存。
 * 监听在主线程 start/stop。
 */
class ShellStatusProbe(context: Context) {
    private val app = context.applicationContext
    private val telephony = app.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    private var listener: PhoneStateListener? = null
    private var radioReceiver: BroadcastReceiver? = null
    private var cellDenied = false

    @Volatile private var cellRadioPresent = false
    @Volatile private var cellInService = true
    @Volatile private var cellBars = 0
    @Volatile private var wifiOn = false
    @Volatile private var wifiConnected = false
    @Volatile private var wifiBars = 0
    @Volatile private var hotspotOn = false
    @Volatile private var adapterEnabled = false
    @Volatile private var headsetConnected = false
    @Volatile private var a2dpConnected = false
    @Volatile private var bluetooth = ShellBluetooth.OFF

    fun start() {
        startRadios()
        startCell()
    }

    fun stop() {
        stopRadios()
        stopCell()
    }

    @SuppressLint("MissingPermission")
    private fun startCell() {
        if (listener != null || cellDenied) return
        val radio = telephony ?: return
        val type = runCatching { radio.phoneType }.getOrDefault(TelephonyManager.PHONE_TYPE_NONE)
        if (type == TelephonyManager.PHONE_TYPE_NONE) {
            cellRadioPresent = false
            return
        }
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            cellDenied = true
            cellRadioPresent = false
            return
        }
        cellRadioPresent = true
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
        if (listened) listener = created else {
            cellDenied = true
            cellRadioPresent = false
        }
    }

    private fun stopCell() {
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

    @SuppressLint("MissingPermission")
    private fun startRadios() {
        if (radioReceiver != null) return
        seedRadios()
        val created = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onRadioBroadcast(intent)
        }
        val filter = IntentFilter().apply {
            addAction(CarHotspotStatus.WIFI_AP_STATE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        if (runCatching { app.registerReceiver(created, filter) }.isSuccess) radioReceiver = created
    }

    private fun stopRadios() {
        val current = radioReceiver ?: return
        radioReceiver = null
        runCatching { app.unregisterReceiver(current) }
    }

    internal fun onRadioBroadcast(intent: Intent) {
        when (intent.action) {
            CarHotspotStatus.WIFI_AP_STATE_CHANGED_ACTION ->
                CarHotspotStatus.fromBroadcast(intent)?.let { hotspotOn = it }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> onWifiState(intent)
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> onWifiNetwork(intent)
            WifiManager.RSSI_CHANGED_ACTION -> onWifiRssi(intent)
            BluetoothAdapter.ACTION_STATE_CHANGED -> onAdapterState(intent)
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> onProfile(intent, headset = true)
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> onProfile(intent, headset = false)
        }
    }

    private fun onAdapterState(intent: Intent) {
        when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
            BluetoothAdapter.STATE_ON -> adapterEnabled = true
            BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                adapterEnabled = false
                headsetConnected = false
                a2dpConnected = false
            }
        }
        publishBluetooth()
    }

    private fun onProfile(intent: Intent, headset: Boolean) {
        val connected = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED) ==
            BluetoothProfile.STATE_CONNECTED
        if (headset) headsetConnected = connected else a2dpConnected = connected
        if (connected) adapterEnabled = true
        publishBluetooth()
    }

    private fun publishBluetooth() {
        bluetooth = ShellStatusLevels.bluetooth(adapterEnabled, headsetConnected, a2dpConnected)
    }

    private fun onWifiState(intent: Intent) {
        when (intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)) {
            WifiManager.WIFI_STATE_ENABLED -> wifiOn = true
            WifiManager.WIFI_STATE_DISABLED, WifiManager.WIFI_STATE_DISABLING -> {
                wifiOn = false
                wifiConnected = false
                wifiBars = 0
            }
        }
    }

    private fun onWifiNetwork(intent: Intent) {
        @Suppress("DEPRECATION")
        val connected = (intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO) as? NetworkInfo)?.isConnected == true
        if (connected) wifiOn = true
        wifiConnected = connected
        if (!connected) wifiBars = 0
    }

    private fun onWifiRssi(intent: Intent) {
        if (!wifiOn || !wifiConnected) return
        val rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, -127)
        wifiBars = if (rssi < 0 && rssi > -127) ShellStatusLevels.wifiBars(rssi) else 0
    }

    fun snapshot(): ShellStatusSnapshot = ShellStatusSnapshot(
        cellVisible = cellRadioPresent,
        cellInService = cellInService,
        cellBars = if (cellInService) cellBars else 0,
        wifiOn = wifiOn,
        wifiConnected = wifiConnected,
        wifiBars = wifiBars,
        hotspotOn = hotspotOn,
        bluetooth = bluetooth,
    )

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

    private fun seedRadios() {
        val wifi = readWifi()
        wifiOn = wifi.on
        wifiConnected = wifi.connected
        wifiBars = wifi.bars
        hotspotOn = runCatching { CarHotspotStatus.isEnabled(app) == true }.getOrDefault(false)
        val adapter = if (granted(Manifest.permission.BLUETOOTH)) {
            runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        } else {
            null
        }
        adapterEnabled = adapter?.let { runCatching { it.isEnabled }.getOrDefault(false) } == true
        if (adapter != null && adapterEnabled) {
            headsetConnected = profileConnected(adapter, BluetoothProfile.HEADSET)
            a2dpConnected = profileConnected(adapter, BluetoothProfile.A2DP)
        } else {
            headsetConnected = false
            a2dpConnected = false
        }
        publishBluetooth()
    }

    @SuppressLint("MissingPermission")
    private fun profileConnected(adapter: BluetoothAdapter, profile: Int): Boolean =
        runCatching { adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED }.getOrDefault(false)

    private fun granted(permission: String): Boolean =
        app.checkCallingOrSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private var signalMethodsReady = false
    private var lteRsrpMethod: java.lang.reflect.Method? = null
    private var lteAsuMethod: java.lang.reflect.Method? = null
    private var gsmMethod: java.lang.reflect.Method? = null

    @Suppress("DEPRECATION")
    private fun barsFrom(signal: SignalStrength): Int {
        if (!signalMethodsReady) {
            signalMethodsReady = true
            val type = signal.javaClass
            lteRsrpMethod = runCatching { type.getMethod("getLteRsrp") }.getOrNull()
            lteAsuMethod = runCatching { type.getMethod("getLteSignalStrength") }.getOrNull()
            gsmMethod = runCatching { type.getMethod("isGsm") }.getOrNull()
        }
        val gsm = runCatching { gsmMethod?.invoke(signal) as? Boolean }.getOrNull() ?: true
        return ShellStatusLevels.cellBars(
            gsm = gsm,
            gsmAsu = signal.gsmSignalStrength,
            cdmaDbm = signal.cdmaDbm,
            lteRsrp = invokeInt(lteRsrpMethod, signal),
            lteAsu = invokeInt(lteAsuMethod, signal),
        )
    }

    private fun invokeInt(method: java.lang.reflect.Method?, target: Any): Int =
        runCatching { method?.invoke(target) as? Int }.getOrNull() ?: Int.MIN_VALUE
}
