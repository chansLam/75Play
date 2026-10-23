package com.shihab.diplay.ui

/**
 * Contract the host Activity (DiPlayActivity) implements so the glass-morphism
 * Fragments can reach business actions without touching the view layer.
 */
interface DiPlayHost {
    fun dp(value: Int): Int
    fun getStr(resId: Int): String
    fun connect(wireless: Boolean = false)
    fun choosePhone()
    fun chooseIphoneTitle(): String
    fun disconnectSession()
    fun openProjection()
    fun hasCarPlaySession(): Boolean
    /** True only after AirPlay handshake completed (safe to open projection). */
    fun isCarPlayActive(): Boolean
    /** True while a connect attempt is in flight (session may exist but not yet active). */
    fun isCarPlayConnecting(): Boolean
    /** Latest connection-stage line while connecting; blank otherwise. */
    fun connectStageLabel(): String
    /** Cancel an in-flight connect (dialog Cancel / disconnect-while-connecting). */
    fun cancelConnectAttempt()
    fun reconnectIfRunning()
    /** Reconnect-required setting. No session: run [onApply] immediately. A live session asks
     * first: OK = [onApply] (save + reconnect), Cancel = [onCancel] (value is not saved).
     * [onApply] is last so trailing lambdas still bind to it. */
    fun confirmReconnectChange(message: String, onCancel: (() -> Unit)? = null, onApply: () -> Unit)
    fun updateCheckNow()
    fun exportDiagnosticsNow()
    fun setRootHideNavbar(enabled: Boolean)
    fun openWheelKeysSettings()
    fun chooseReportDestination()
    fun autoUpdateEnabled(): Boolean
    fun setAutoUpdateEnabled(enabled: Boolean)
    fun openDevTerminal()
    fun showGlassToast(message: String, type: DiPlayToast.Type = DiPlayToast.Type.INFO)
    fun versionString(): String
    /** 夜间模式等主题偏好变化时重建 shell（Fragment 重渲染读新色板）。 */
    fun rebuildTheme()
    /** 切到主页（车机软件管理在主页）。 */
    fun showHome()
    /** 悬浮小白点开关：立即 show/hide，并写入偏好。 */
    fun setFloatingBallEnabled(enabled: Boolean)

    // ---- home status-card connection tabs ----
    fun isWifiEnabled(): Boolean
    fun enableWifi(): Boolean
    fun isCarHotspotEnabled(): Boolean
    fun openCarHotspot(): Boolean
    /** Turn off the head-unit soft AP (needed before switching to WiFi-direct / LAN, which share the radio). */
    fun closeCarHotspot(): Boolean
    fun carHotspotSsid(): String
    fun carHotspotPassphrase(): String
    /** 最近一次扫描缓存，不等待新扫描。 */
    fun peekWifiNetworks(): List<WifiEntry>
    /** 发起一次扫描。结果由系统 SCAN_RESULTS 广播送达，不在这里阻塞。 */
    fun requestWifiScan()
    fun scanWifiNetworks(callback: (List<WifiEntry>) -> Unit)
    fun connectToWifi(ssid: String, passphrase: String): Boolean
    /** SSID the head unit is currently connected to as station (blank if none). */
    fun currentWifiSsid(): String
    /** Currently connected Bluetooth device name (empty if none), read live at startup. */
    fun connectedBluetoothDeviceName(): String
    /** Whether the head-unit Bluetooth radio is currently enabled. */
    fun isBluetoothEnabled(): Boolean
}

/** A nearby WiFi network shown in the LAN tab. */
data class WifiEntry(val ssid: String, val secured: Boolean, val signalLevel: Int)
