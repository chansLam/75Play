// SPDX-License-Identifier: AGPL-3.0-only
package com.shihab.diplay
import com.shihab.diplay.connect.ShellConnectCoordinator
import com.shihab.diplay.connect.ShellDiagnostics
import com.shihab.diplay.connect.ShellNetworkActions
import com.shihab.diplay.network.CarHotspotEnable
import com.shihab.diplay.network.CarHotspotStatus
import com.shihab.diplay.connect.ShellPhonePicker
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.FloatingBall
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.ui.shell.ShellChrome
import com.shihab.diplay.ui.shell.ShellNavigator
import com.shihab.diplay.ui.shell.ShellStatusProbe

import android.annotation.SuppressLint
import android.Manifest
import com.shihab.diplay.ui.DiPlayDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.shihab.diplay.compat.BluetoothCompat
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import com.shihab.diplay.compat.realSdkInt
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.doOnLayout
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.FragmentActivity
import com.shihab.diplay.ui.*
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shihab.diplay.adb.LocalAdb
import com.shihab.diplay.bluetooth.BluetoothAddressResolver
import com.shihab.diplay.airplay.CarPlayDisplayScale
import com.shihab.diplay.host.R
import com.shihab.diplay.network.CarHotspotSettings
import com.shihab.diplay.network.CarHotspotTethering
import com.shihab.diplay.network.WifiP2pChannels
import com.shihab.diplay.orchestration.WirelessHotspotMode
import java.io.File
import kotlin.math.roundToInt
import com.shihab.diplay.util.AppLog

/** Shell activity: connection, settings, and diagnostics. */
class DiPlayActivity : FragmentActivity(), DiPlayHost {
    private val handler = Handler(Looper.getMainLooper())
    private val automaticUpdateCheck = Runnable {
        if (!isFinishing && !isDestroyed) updater.checkAutomatically()
    }
    private val updaterDelegate = lazy { DiPlayUpdater(this) }
    private val updater by updaterDelegate
    private fun lazyUpdaterInitialized() = updaterDelegate.isInitialized()
    private var page = ShellNavigator.HOME
    /** Shell page switcher; fragment swap stays in [applyShellPage]. */
    private val shellNavigator by lazy {
        ShellNavigator(initialPage = page, applyPage = ::applyShellPage)
    }
    private val shellChrome by lazy {
        ShellChrome(this, versionLabel = { version() }, dp = { dp(it) })
    }
    private val radioProbe by lazy { ShellStatusProbe(this) }
    private val phonePicker by lazy { ShellPhonePicker(this) }
    private val diagnostics by lazy {
        ShellDiagnostics(
            activity = this,
            versionLabel = { version() },
            setupError = { setupError },
            dp = { dp(it) },
            toast = { toast(it) },
            rechoose = { chooseReportDestination() },
        )
    }
    private var pendingCarHotspotSetup = false
    private var setupError: String? = null
    private var initialLaunch = true
    private var usbPermissionOperation: HeadUnitPermissionSetup.Operation? = null
    internal var usbPermissionOperationFactory: (Context) -> HeadUnitPermissionSetup.Operation = {
        HeadUnitPermissionSetup.Operation(it.applicationContext)
    }
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    private var wifiEnableAttempts = 0
    private var wifiEnableGeneration = 0
    private var p2pGroupPoll: Runnable? = null
    /** Bumped when a connect is superseded or cancelled, so a late restart does not start. */
    private val connectEpoch = AtomicInteger(0)
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM
    private var userNavigatedFromCarPlay = false

    private fun enforceAppLocale(): Boolean {
        val applied = AppLocale.enforce(this)
        if (applied) AppLog.i("DiPlayUi", "app language re-applied")
        return applied
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the home page finishes this activity while the session runs on, so the icon lands here.
        if (savedInstanceState == null && isLauncherIntent(intent) && isCarPlayActive()) {
            startCarPlayHost(); finish(); return
        }
        if (intent.hasExtra("page")) {
            userNavigatedFromCarPlay = true
        }
        enforceAppLocale()
        languagePreferenceAtCreate = AppLocale.preference(this)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        runCatching {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = false
                hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        ensureAuthenticationAsync()
        pendingCarHotspotSetup = savedInstanceState?.getBoolean("pending_car_hotspot") ?: false
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // 品牌启动页：暗色全屏背景图 + logo + “应用启动中，请稍后”，立即首帧显示；
        // 真实 UI 延迟到下一帧构建，避免 multidex/慢 CPU 启动期纯黑屏。
        showBrandSplash()
        handler.post {
            if (isFinishing || isDestroyed) return@post
            // 主题恢复延迟到首帧之后，避免阻塞品牌页第一帧。
            initNightModeController()
            applyShell()
            ImmersiveBars.installAutoHide(this)
            showPage(page)
            consumeConnectFailure()
            // Diagnostic probe walks Bluetooth/USB; keep it off the first-paint critical path.
            if (savedInstanceState == null) {
                handler.post { LegacyConnectionProbe.log(applicationContext) }
            }
            handler.postDelayed(automaticUpdateCheck, 4000L)
            handleWirelessRecovery()
            // 车机蓝牙若被关闭，启动后自动打开，保证 CarPlay 无线连接可用。
            phonePicker.ensureEnabledAsync()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (DiPlayDialog.consumeBack()) return
                    if (com.shihab.diplay.ui.GlassDropdown.dismiss()) return
                    if (com.shihab.diplay.ui.HomeAppMenu.dismiss()) return
                    if (page != ShellNavigator.HOME) navigateBack()
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            })
            // Show the floating ball as soon as DiPlay launches (when enabled), rather than waiting
            // until a CarPlay session is in front. Tap performs a system Back via the assist-back
            // accessibility service; long-press hops back to the DiPlay shell.
            if (AirPlayPersistence.loadFloatingBallEnabled(this) && FloatingBall.permitted(this)) {
                FloatingBall.show(
                    applicationContext,
                    { FloatingBall.back() },
                    { FloatingBall.backToDiPlay(applicationContext) },
                )
            }
        }
    }

    /** 启动页：实色底 + 一句提示。不解码全屏照片。 */
    private fun showBrandSplash() {
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(GlassColors.BACKGROUND)
        val hint = TextView(this).apply {
            text = getString(R.string.diplay_app_launching)
            setTextColor(GlassColors.INK)
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(hint)
        setContentView(root)
        AppLocale.applyRootLayoutDirection(this)
    }

    // ---- 车机壳：宽屏左侧窄导航 + 顶栏；竖屏目的地在底栏 ----

    private fun applyShell() {
        window.setBackgroundDrawableResource(
            if (com.shihab.diplay.ui.GlassColors.isDark) com.shihab.diplay.host.R.drawable.window_background
            else com.shihab.diplay.host.R.drawable.window_background_light,
        )
        shellChrome.install(currentPage = { page }, requestPage = { showPage(it) })
        showPage(page)
        refreshStatus()
    }

    private fun showPage(target: String) = shellNavigator.showPage(target)

    private fun applyShellPage(target: String) {
        page = target
        shellChrome.applyPage(target)
    }


    // ---- DiPlayHost implementation (glass Fragments reach business actions here) ----

    override fun getStr(resId: Int): String = getString(resId)
    override fun disconnectSession() {
        if (isCarPlayConnecting()) {
            cancelConnectAttempt()
            return
        }
        CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus(); refreshHomeFragment() } }
        // stop() sets isStopping synchronously — refresh now so the play icon / disconnect
        // row do not linger as "still connected" while teardown finishes.
        refreshStatus()
        refreshHomeFragment()
    }
    override fun hasCarPlaySession(): Boolean = CarPlayBackgroundSession.hasSession()
    override fun isCarPlayActive(): Boolean {
        // A parked session is still connected. The active flag is set when the phone accepts
        // the session; hasSession covers the return from CarPlay if that callback already happened.
        // While stop() is in flight, hasSession stays true for waiters — do not show play/disconnect.
        if (isCarPlayConnecting() || CarPlayBackgroundSession.isStopping()) return false
        return CarPlayBackgroundSession.active || CarPlayBackgroundSession.hasSession()
    }
    override fun isCarPlayConnecting(): Boolean =
        // Only the explicit connect flow flags — do NOT use hasSession()&&!active here:
        // after Cancel, stop()/teardown is async and hasSession stays true briefly, which
        // used to leave the home pill on「连接中」forever from the user's view.
        shellConnect.isConnecting()
    override fun connectStageLabel(): String = shellConnect.stageLabel.orEmpty()
    override fun cancelConnectAttempt() {
        connectEpoch.incrementAndGet()
        shellConnect.cancel()
    }
    override fun updateCheckNow() { updater.check(manual = true) }
    override fun exportDiagnosticsNow() { exportDiagnostics() }

    override fun autoUpdateEnabled(): Boolean = updater.autoEnabled()
    override fun setAutoUpdateEnabled(enabled: Boolean) { updater.setAutoEnabled(enabled) }
    override fun openDevTerminal() {
        // FragmentAbout already counts the 5 taps. Confirm before opening a root PTY shell so a
        // stray fifth tap does not drop the driver into a terminal by accident. Release builds
        // keep the terminal (product capability); this gate only avoids mis-taps.
        DiPlayDialog.showConfirm(
            this,
            getString(R.string.about_dev_terminal_title),
            getString(R.string.about_dev_terminal_confirm),
            confirm = getString(android.R.string.ok),
            cancel = getString(android.R.string.cancel),
            onConfirm = { runCatching { TerminalOverlay.show(this) } },
        )
    }
    override fun showGlassToast(message: String, type: DiPlayToast.Type) {
        DiPlayToast.show(this, message, type)
    }
    override fun versionString(): String = version()
    override fun rebuildTheme() {
        configureNightController()
        applyShell()
    }

    private val headlightThemeWatch = HeadlightThemeWatch(this) {
        followSystemNight()?.let { nightModeController?.systemChanged(it) }
    }

    private fun systemNightNow(): Boolean = followSystemNight() ?: false

    private fun initNightModeController() {
        nightModeController = bindCarPlayNightMode(
            context = this,
            initialNight = GlassColors.isDark,
            onNightChanged = { night ->
                nightModeHandler.post {
                    if (night != GlassColors.isDark) {
                        GlassColors.isDark = night
                        if (!isFinishing && !isDestroyed) applyShell()
                    }
                }
            },
        )
        configureNightController()
    }

    private fun configureNightController() {
        val c = nightModeController ?: return
        c.configure(
            mode = AirPlayPersistence.loadCarPlayNightMode(this),
            systemNight = systemNightNow(),
            threshold = AirPlayPersistence.loadAmbientLightThreshold(this),
            delaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this),
            schedule = AirPlayPersistence.loadCarPlayNightSchedule(this),
        )
    }

    /** 主题五档即时生效：resume Controller；主题发生变化返回 true 由调用方重建 shell。 */
    private fun applyNightMode(): Boolean {
        val c = nightModeController ?: return false
        val before = GlassColors.isDark
        c.resume(systemNightNow())
        return c.night != before
    }
    override fun showHome() = shellNavigator.showHome()

    override fun setFloatingBallEnabled(enabled: Boolean) {
        AirPlayPersistence.saveFloatingBallEnabled(this, enabled)
        if (enabled) {
            FloatingBall.show(
                applicationContext,
                { FloatingBall.back() },
                { FloatingBall.backToDiPlay(applicationContext) },
            )
            if (!com.shihab.diplay.host.AssistBackService.isReady) {
                DiPlayDialog.showConfirm(
                    this, getString(R.string.floating_ball_assist_title),
                    getString(R.string.floating_ball_assist_message),
                    getString(R.string.floating_ball_assist_open), getString(R.string.cancel),
                    onConfirm = {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                )
            }
        } else {
            FloatingBall.hide()
        }
    }

    // ---- Home status-card connection tabs ----
    override fun isWifiEnabled(): Boolean =
        (getSystemService(WIFI_SERVICE) as? WifiManager)?.isWifiEnabled == true

    override fun enableWifi(): Boolean {
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return false
        val log = StringBuilder("EnableWifi: ")
        // WiFi and the car hotspot are mutually exclusive on API19 head units. Unknown AP
        // state still turns SoftAP off: treating it as off leaves the radio on the hotspot.
        if (CarHotspotStatus.isEnabled(this) != false) {
            CarHotspotEnable.disable(this, restoreStationWifi = false)
            log.append("hotspotClosed; ")
        }
        val set = runCatching { wifi.setWifiEnabled(true) }
            .onFailure { log.append("exception=${it.javaClass.simpleName}; ") }
            .getOrDefault(false)
        log.append("setWifiEnabled=$set; ")
        // setWifiEnabled only kicks off an async bring-up; poll briefly until the radio reports on.
        var enabled = isWifiEnabled()
        var waited = 0
        while (!enabled && waited < 2000) {
            Thread.sleep(200)
            waited += 200
            enabled = isWifiEnabled()
        }
        log.append("nowEnabled=$enabled after=${waited}ms ")
        AsyncDiagnosticLog.append(ActiveSessionLog.current, log.toString())
        return enabled
    }

    override fun isCarHotspotEnabled(): Boolean = ShellNetworkActions.isCarHotspotEnabled(this)

    override fun openCarHotspot(): Boolean = ShellNetworkActions.openCarHotspot(
        this,
        ssid = carHotspotSsid(),
        passphrase = carHotspotPassphrase(),
    )

    override fun carHotspotSsid(): String = AirPlayPersistence.loadManualHotspotSsid(this)

    override fun carHotspotPassphrase(): String = AirPlayPersistence.loadManualHotspotPassphrase(this)

    override fun closeCarHotspot(): Boolean = ShellNetworkActions.closeCarHotspot(this)

    override fun peekWifiNetworks(): List<WifiEntry> = readWifiEntries()

    override fun requestWifiScan() {
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        runCatching { wifi.startScan() }
    }

    override fun scanWifiNetworks(callback: (List<WifiEntry>) -> Unit) {
        requestWifiScan()
        runOnUiThread { callback(readWifiEntries()) }
    }

    private fun readWifiEntries(): List<WifiEntry> {
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return emptyList()
        return runCatching {
            wifi.scanResults
                .map { r ->
                    val caps = r.capabilities.orEmpty()
                    WifiEntry(
                        ssid = r.SSID ?: "",
                        secured = "WPA" in caps || "WEP" in caps || "PSK" in caps ||
                            "SAE" in caps || "EAP" in caps,
                        signalLevel = r.level,
                    )
                }
                .filter { it.ssid.isNotBlank() }
                .distinctBy { it.ssid }
                .sortedByDescending { it.signalLevel }
        }.getOrDefault(emptyList())
    }

    override fun connectToWifi(ssid: String, passphrase: String): Boolean {
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return false
        val log = StringBuilder("WifiConnect: ssid=$ssid wifiEnabled=${runCatching { wifi.isWifiEnabled }.getOrDefault(false)} ")
        // The Wi-Fi radio must be on before adding/enabling a network: with the radio off,
        // addNetwork/enableNetwork fail and the connect is reported as failed straight away
        // (LAN shows "0x" / no IP). Turn it on first (this also closes the car hotspot, which
        // shares the radio with LAN).
        if (!runCatching { wifi.isWifiEnabled }.getOrDefault(false)) {
            if (!enableWifi()) {
                log.append("enableWifiRadio=failed ")
                AsyncDiagnosticLog.append(ActiveSessionLog.current, log.append("final=false").toString())
                return false
            }
            log.append("radioWasOff=turnedOn ")
        }
        // A network already saved (e.g. this hotspot was connected before) makes addNetwork
        // return -1 and the connect silently fails. Drop any stale config for the same SSID
        // first so the fresh one is accepted and enabled.
        runCatching {
            wifi.configuredNetworks
                ?.filter { it.SSID?.trim('"').equals(ssid, ignoreCase = true) }
                ?.also { log.append("stale=${it.size} ") }
                ?.forEach { wifi.removeNetwork(it.networkId) }
        }
        runCatching { wifi.saveConfiguration() }
        // saveConfiguration writes the supplicant config asynchronously; give the radio a brief
        // settle before adding, otherwise the stale network may still be seen and addNetwork -1s.
        runCatching { Thread.sleep(300) }
        // Escape backslash / quote in the passphrase so WPA parsing cannot choke (quirk #58).
        val escPass = passphrase.replace("\\", "\\\\").replace("\"", "\\\"")
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (escPass.isBlank()) {
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            } else {
                preSharedKey = "\"$escPass\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            // 部分车机 ROM 缺默认 cipher 时 enableNetwork 成功但握手失败。
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedProtocols.set(WifiConfiguration.Protocol.WPA)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP104)
            status = WifiConfiguration.Status.ENABLED
        }
        var netId = runCatching { wifi.addNetwork(config) }
            .onFailure { log.append("addNetException=${it.javaClass.simpleName}; ") }
            .getOrDefault(-2)
        log.append("addNetId=$netId ")
        // addNetwork can still return -1 (stale removal not committed yet / ROM quirk). Fall back
        // to updating the existing network in place instead of failing straight away.
        if (netId < 0) {
            val existing = runCatching {
                wifi.configuredNetworks?.firstOrNull {
                    it.SSID?.trim('"').equals(ssid, ignoreCase = true)
                }
            }.getOrNull()
            if (existing != null) {
                config.networkId = existing.networkId
                netId = runCatching { wifi.updateNetwork(config) }
                    .onFailure { log.append("updateException=${it.javaClass.simpleName}; ") }
                    .getOrDefault(-1)
                log.append("updateNetId=$netId ")
            }
        }
        if (netId < 0) {
            AsyncDiagnosticLog.append(ActiveSessionLog.current, log.append("final=false").toString())
            return false
        }
        // 已连其它热点时，仅 enableNetwork 往往切不过去（华为等 ROM）；先 disconnect 再 reconnect。
        val curSsid = runCatching { wifi.connectionInfo?.ssid?.trim('"') }.getOrNull().orEmpty()
        if (curSsid.isNotBlank() && curSsid != "<unknown ssid>" &&
            !curSsid.equals(ssid, ignoreCase = true)
        ) {
            runCatching { wifi.disconnect() }
            log.append("disconnectFrom=$curSsid ")
            Thread.sleep(400L)
        }
        val ok = runCatching { wifi.enableNetwork(netId, true) }
            .onFailure { log.append("enableException=${it.javaClass.simpleName}; ") }
            .getOrDefault(false)
        runCatching { wifi.reconnect() }
        runCatching { wifi.saveConfiguration() }
        log.append("enable=$ok ")
        // enableNetwork returning true only means the request was accepted; the actual WPA
        // handshake may fail (wrong password / ROM quirk). Poll until the supplicant reaches
        // COMPLETED on the target SSID so a bad connect is reported as failed instead of "connecting".
        var authenticated = false
        repeat(24) {
            Thread.sleep(750L)
            val info = runCatching { wifi.connectionInfo }.getOrNull()
            val onTarget = info?.ssid?.trim('"').equals(ssid, ignoreCase = true)
            val state = info?.supplicantState
            if (onTarget && state == android.net.wifi.SupplicantState.COMPLETED) {
                authenticated = true
                return@repeat
            }
            // 仍粘在旧 SSID 上时再踢一脚，避免轮询空转。
            if (it == 8 || it == 16) {
                runCatching { wifi.enableNetwork(netId, true) }
                runCatching { wifi.reconnect() }
                log.append("nudge; ")
            }
            log.append("st=$state; ")
        }
        runCatching { wifi.saveConfiguration() }
        log.append("authenticated=$authenticated ")
        AsyncDiagnosticLog.append(ActiveSessionLog.current, log.append("final=$authenticated").toString())
        return authenticated
    }

    override fun currentWifiSsid(): String = runCatching {
        val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return@runCatching ""
        wifi.connectionInfo?.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
            .orEmpty()
    }.getOrDefault("")

    override fun connectedBluetoothDeviceName(): String = phonePicker.connectedDisplayName()

    override fun isBluetoothEnabled(): Boolean =
        runCatching { BluetoothCompat.getAdapter(this)?.isEnabled == true }
            .getOrDefault(false)

    /** MFI authentication warm-up off the main thread (ECDSA can stall Freescale/KitKat head units). */
    private fun ensureAuthenticationAsync() {
        Thread {
            val err = runCatching {
                DiPlayBootstrap.ensure(this@DiPlayActivity, AirPlayPersistence.loadMfiTarget(this@DiPlayActivity))
            }.exceptionOrNull()
            runOnUiThread {
                setupError = err?.let { getString(R.string.setup_error_auth) }
                if (err != null) AppLog.e("DiPlaySetup", "CarPlay authentication could not be loaded", err)
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        // CarPlay runs in its own task, so the launcher icon resumes this one. Settings opened from
        // CarPlay carry a "page" extra, which isLauncherIntent rejects.
        if (isLauncherIntent(intent) && isCarPlayActive()) {
            userNavigatedFromCarPlay = false
            page = "home"; showPage(page); openProjection(); return
        }
        // Three-finger swipe / floating ball: singleTop delivers this before onResume. Without the
        // flag, the branch below sees a live session and opens CarPlay again, so the swipe looks dead.
        if (isReturnFromCarPlay(intent)) {
            noteReturnFromCarPlay()
            intent.removeExtra("returnFromCarPlay")
            showPage(intent.getStringExtra("page") ?: page)
            handleWirelessRecovery()
            consumeConnectFailure()
            return
        }
        val explicitPage = intent.getStringExtra("page")
        if (explicitPage != null) {
            userNavigatedFromCarPlay = true
            page = explicitPage
            showPage(page)
        } else if (isCarPlayActive() && !userNavigatedFromCarPlay) {
            page = "home"; showPage(page); openProjection(); return
        } else {
            page = "home"; showPage(page)
        }
        handleWirelessRecovery()
        consumeConnectFailure()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enforceAppLocale()
        shellChrome.discardPages()
        showPage(page)
    }
    override fun onStart() {
        super.onStart()
        wifiEnableAttempts = 0
        // Results from the image picker and crop screens arrive before onResume.
        enforceAppLocale()
    }

    override fun onStop() {
        cancelHeadUnitPermissionSetup()
        startupHotspotCancelled = true
        wifiEnableGeneration++
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (AirPlayPersistence.loadFloatingBallEnabled(this)) {
            FloatingBall.show(
                applicationContext,
                { FloatingBall.back() },
                { FloatingBall.backToDiPlay(applicationContext) },
            )
        }
        headlightThemeWatch.start()
        CarPlayVolumeTarget.attach(this)
        radioProbe.start()
        // 主题切换/环境光/定时变化时重建 shell（Fragment 重新渲染读新色板）。
        if (applyNightMode()) {
            applyShell()
            ImmersiveBars.applyToWindow(window, force = true)
            ImmersiveBars.installAutoHide(this)
            handler.removeCallbacks(tick)
            handler.post(tick)
            return
        }
        ImmersiveBars.applyToWindow(window, force = true)
        ImmersiveBars.installAutoHide(this)
        // Returning from another activity can bring the head unit's own density back.
        if (enforceAppLocale()) {
            shellChrome.discardPages()
            showPage(page)
        }
        if (realSdkInt() < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        updater.onResume()
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "about")) showPage(page)
        pausedForAdbSwitchChange = false
        // Came back from the CarPlay screen via three-finger swipe / floating ball: stay on the
        // DiPlay home even though the CarPlay session is still alive, instead of flashing back.
        // Use a time-window too, because onPause clears userNavigatedFromCarPlay and the delayed
        // CarPlayHost finish can trigger a second onResume after the intent flag was consumed.
        val justReturnedFromCarPlay = intent?.let { isReturnFromCarPlay(it) } == true
        if (justReturnedFromCarPlay) {
            noteReturnFromCarPlay()
            intent?.removeExtra("returnFromCarPlay")
        }
        if (!initialLaunch && isCarPlayActive() && page == "home" &&
            !userNavigatedFromCarPlay && !justReturnedFromCarPlay) {
            openProjection()
            return
        }
        if (initialLaunch) {
            initialLaunch = false
            startCarHotspotOnLaunch()
            if (setupError == null && !CarPlayBackgroundSession.hasSession() &&
                DiPlayPreferences.autoConnect(this) && intent.getStringExtra("page") == null) {
                handler.post { connect(DiPlayPreferences.autoConnectWireless(this)) }
            }
        }
    }
    override fun onPause() {
        headlightThemeWatch.stop()
        CarPlayVolumeTarget.detach(this)
        userNavigatedFromCarPlay = false
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        radioProbe.stop()
        nightModeController?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        p2pGroupPoll?.let { handler.removeCallbacks(it) }
        p2pGroupPoll = null
        handler.removeCallbacks(recheckStatusRunnable)
        wifiEnableGeneration++
        handler.removeCallbacks(automaticUpdateCheck)
        if (lazyUpdaterInitialized()) updater.close()
        cancelHeadUnitPermissionSetup()
        shellConnect.release()
        radioProbe.stop()
        // P1 leak兜底：开发者终端是单例 object，持有 PTY 子进程/overlay View，Activity 销毁时
        // 若用户没手动关闭，子进程与窗口引用会泄漏。dismiss 幂等，重复调用安全。
        runCatching { TerminalOverlay.dismiss(this) }
        // P1 leak兜底：选机用的单线程 ExecutorService 是非 daemon，不 shutdown 会随 Activity 泄漏。
        runCatching { phonePicker.shutdown() }
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        shellChrome.discardPages()
        showPage(page)
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveBars.applyToWindow(window, force = true)
            ImmersiveBars.installAutoHide(this)
        }
        // Some head units reset the app language without any other callback.
        if (hasFocus && enforceAppLocale()) {
            shellChrome.discardPages()
            showPage(page)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 任意点击：若边滑/ROM 已把系统栏拉出来，立刻收回（不限于弹窗）。
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            ImmersiveBars.rehideIfBarsVisible(this)
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun navigateBack() = shellNavigator.navigateBack()

    /** Deliberate leave from the CarPlay screen (three-finger swipe down, or the floating ball). */
    private fun isReturnFromCarPlay(intent: Intent): Boolean =
        intent.getBooleanExtra("returnFromCarPlay", false) ||
            (System.currentTimeMillis() - returnedFromCarPlayAt) < 3_000L

    private fun noteReturnFromCarPlay() {
        userNavigatedFromCarPlay = true
        returnedFromCarPlayAt = System.currentTimeMillis()
    }

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val ssid = AirPlayPersistence.loadManualHotspotSsid(app).ifBlank { null }
            val passphrase = AirPlayPersistence.loadManualHotspotPassphrase(app).ifBlank { null }
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { AppLog.i("DiPlay-Hotspot", it) },
                ssid = ssid,
                passphrase = passphrase,
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") showPage(page)
            }
        }, "diplay-hotspot-startup").apply { isDaemon = true }.start()
    }

    private fun startupHotspotEligible(): Boolean =
        CarHotspotSetup.shouldStartOnLaunch(applicationContext, CarPlayBackgroundSession.hasSession())

    private fun storedSsid() = AirPlayPersistence.loadManualHotspotSsid(this)
    private fun storedPassword() = AirPlayPersistence.loadManualHotspotPassphrase(this)
    private fun hotspotError(ssid: String, password: String) =
        com.shihab.diplay.orchestration.ManualHotspotValidation.error(ssid, password)?.let { getString(it.messageResource()) }

    /** True until a usable car hotspot name and password are saved, as on a first run. */
    private fun cancelHeadUnitPermissionSetup() {
        val operation = usbPermissionOperation
        usbPermissionOperation = null
        operation?.cancel()
    }

    override fun reconnectIfRunning() {
        if (!CarPlayBackgroundSession.hasSession()) return
        showPage("home")
        runCatching { supportFragmentManager.executePendingTransactions() }
        shellConnect.prepare()
        connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // A reconnect-required setting was changed. With no session there is nothing to drop, so save
    // immediately. A live session still asks before save + reconnect.
    override fun confirmReconnectChange(message: String, onCancel: (() -> Unit)?, onApply: () -> Unit) {
        if (!hasCarPlaySession() && !isCarPlayActive()) {
            onApply()
            return
        }
        DiPlayDialog.showConfirm(
            this,
            getString(R.string.reconnect_required_title),
            message,
            getString(R.string.save_and_reconnect),
            getString(R.string.cancel),
            onConfirm = { onApply() },
            onCancel = onCancel,
        )
    }

    override fun connect(wireless: Boolean) {
        if (!suppressWifiResetFlagClear) autoWifiResetUsed = false
        startupHotspotCancelled = true
        if (wireless && pendingCarHotspotSetup) { toast(getString(R.string.save_your_hotspot_details_in_connection_setup_first)); return }
        if (setupError != null) {
            // 长说明用正式弹窗，避免居中 Toast 贴边、难读
            DiPlayDialog.showInfo(
                this,
                getString(R.string.setup_needs_attention),
                setupError!!,
                ok = getString(R.string.dialog_ok),
            )
            return
        }
        if (wireless && AirPlayPersistence.loadWirelessHotspotMode(this) == WirelessHotspotMode.MANUAL &&
            hotspotError(storedSsid(), storedPassword()) != null) {
            // Don't bounce the user to the replaced old connection-setup page. Auto-fill the
            // default hotspot credentials and let the connection proceed.
            if (storedSsid().isBlank()) AirPlayPersistence.saveManualHotspotSsid(this, DEFAULT_HOTSPOT_SSID)
            if (storedPassword().isBlank()) AirPlayPersistence.saveManualHotspotPassphrase(this, DEFAULT_HOTSPOT_PASS)
            pendingCarHotspotSetup = false
            toast(getString(R.string.using_default_hotspot))
        }
        // OEM Android 8 head units can report hotspot state=false even while SoftAP is active.
        // Always let the runtime probe the real hotspot interface instead of blocking here.
        if (wireless) {
            // 按连接方式自动准备网络：wifi 直连(WIFI_P2P)/局域网(EXISTING_WIFI) 需要车机 WiFi 开启，
            // 若未开则自动打开；热点(MANUAL) 由连接栈自动开热点，不能抢开 wifi（API19 二者互斥）。
            val mode = AirPlayPersistence.loadWirelessHotspotMode(this)
            // 局域网模式：连接 CarPlay 前确保已连 WiFi 且已保存其密码。若车机已连 WiFi 但应用尚无
            // 该 WiFi 密码（用户可能是在车机系统里手动连的），先弹窗输入，保存后继续连接。
            if (mode == WirelessHotspotMode.EXISTING_WIFI) {
                val cur = currentWifiSsid()
                if (cur.isBlank() && AirPlayPersistence.loadExistingWifiSsid(this).isBlank()) {
                    toast(getString(R.string.home_car_not_connected_wifi))
                    showPage("home")
                    return
                }
                if (cur.isNotBlank() && AirPlayPersistence.loadExistingWifiPassphrase(this).isBlank()) {
                    promptExistingWifiPassword(cur) { connect(wireless) }
                    return
                }
            }
            // enableWifi sleeps while the radio comes up — never block the UI thread.
            if (mode != WirelessHotspotMode.MANUAL && !isWifiEnabled()) {
                if (wifiEnableAttempts >= 2) {
                    toast(getString(R.string.wifi_enable_failed))
                    return
                }
                wifiEnableAttempts++
                val generation = ++wifiEnableGeneration
                Thread({
                    val enabled = enableWifi()
                    runOnUiThread {
                        if (generation != wifiEnableGeneration) return@runOnUiThread
                        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                        if (!enabled) {
                            toast(getString(R.string.wifi_enable_failed))
                            return@runOnUiThread
                        }
                        wifiEnableAttempts = 0
                        connect(wireless)
                    }
                }, "enable-wifi-before-connect").apply { isDaemon = true }.start()
                return
            }
            wifiEnableAttempts = 0
            // On startup prefer the currently connected Bluetooth device: if one is already
            // active, adopt it automatically instead of forcing the phone picker.
            val connected = phonePicker.currentlyConnected()
            if (connected != null) {
                DiPlayPreferences.savePhone(this, connected.address, connected.name)
            } else if (DiPlayPreferences.phoneAddress(this) == null) {
                // 无上次连接设备：若车机已配对唯一 iPhone，直接用该设备连接；否则弹窗让用户选择。
                phonePicker.pendingWireless = true
                phonePicker.runOffMain {
                    val onlyDevice = phonePicker.uniquePaired()
                    runOnUiThread {
                        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                        if (onlyDevice != null) {
                            DiPlayPreferences.savePhone(this, onlyDevice.address, onlyDevice.name)
                            startConnectFlow()
                        } else {
                            choosePhone()
                        }
                    }
                }
                return
            }
        }
        val epoch = connectEpoch.incrementAndGet()
        val open = open@{
            if (epoch != connectEpoch.get()) return@open
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            startConnectFlow()
        }
        if (CarPlayBackgroundSession.hasSession()) {
            // close() returns before Wi-Fi Direct and Bluetooth are released. Starting the next
            // attempt immediately fails with the radio still held.
            val closing = CarPlayBackgroundSession.snapshot()?.controller
            CarPlayBackgroundSession.stop {
                Thread({
                    closing?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS)
                    runOnUiThread {
                        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                        open()
                    }
                }, "diplay-connect-after-close").apply { isDaemon = true }.start()
            }
        } else {
            open()
        }
    }

    /** 局域网模式：车机已连 WiFi 但应用未保存其密码时，弹窗输入；保存后回调继续连接。 */
    private fun promptExistingWifiPassword(ssid: String, onSave: (String) -> Unit) {
        com.shihab.diplay.ui.DiPlayDialog.showInput(
            this,
            getString(R.string.home_connect_wifi),
            hint = getString(R.string.home_wifi_password_hint, ssid),
            initial = "",
            assist = ssid,
            save = getString(R.string.home_save_and_connect_carplay),
            // 空密码=开放网络；勿用 ifBlank 把已输入密码当成错误文案显示成红字
            validator = { null },
            onSave = { pass ->
                AirPlayPersistence.saveExistingWifiCredentials(this, ssid, pass)
                onSave(pass)
            },
        )
    }

    // ---- 连接中：主页圆形按钮外圈转圈，不再弹进度窗；失败仍用说明弹窗。成功后由 CarPlayHostActivity 全屏接管 ----

    private val shellConnect by lazy {
        ShellConnectCoordinator(
            context = this,
            onConnected = { openProjection() },
            onFailed = { msg ->
                refreshStatus()
                handler.postDelayed(recheckStatusRunnable, 250L)
                presentConnectFailure(msg)
            },
            onRefreshHome = {
                refreshStatus()
                refreshHomeFragment()
                handler.postDelayed(recheckStatusRunnable, 250L)
            },
        )
    }
    // 五档主题：跟随系统/环境光/浅色/暗黑/定时（环境光与定时由 Controller 驱动）
    private var nightModeController: CarPlayNightModeController? = null
    private val nightModeHandler = Handler(Looper.getMainLooper())
    // P2: 匿名 lambda postDelayed 无法 removeCallbacks，抽成具名 Runnable 在 onDestroy 清理
    private val recheckStatusRunnable = Runnable { refreshStatus(); refreshHomeFragment() }

    private fun startConnectFlow() {
        shellConnect.start()
    }

    /** Push connection / app-list state into the visible home fragment immediately. */
    private fun refreshHomeFragment() {
        (supportFragmentManager.findFragmentByTag("home") as? FragmentHome)?.refreshFromHost()
    }

    override fun openProjection() {
        // No live parked session (idle / connecting / stopping): stay on the shell.
        // Opening Host anyway is what produced the black screen after a mode switch.
        if (!isCarPlayActive()) return
        // The host owns the decoder surface and shows it immediately. Holding the surface
        // off-screen left CarPlay black until an 8s timeout.
        startCarPlayHost()
    }

    /**
     * KitKat (API 19) ActivityStack mishandles REORDER_TO_FRONT with singleTask and can
     * resume the home launcher instead of CarPlayHostActivity (CS75 / OS_S311-WT).
     */
    private fun startCarPlayHost() {
        startActivity(Intent(this, CarPlayHostActivity::class.java))
    }
    override fun chooseIphoneTitle(): String =
        if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.choose_iphone)
        else "${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}"

    override fun choosePhone() {
        phonePicker.choose { start ->
            showPage(page)
            if (start) connect(true)
        }
    }

    private fun handleWirelessRecovery() {
        if (page != "wireless-recovery") return
        page = "home"; showPage(page)
        confirmWirelessReset()
    }

    private fun confirmWirelessReset() {
        DiPlayDialog.showConfirm(
            this, getString(R.string.reset_carplay_wi_fi_2),
            getString(R.string.this_ends_the_existing_wi_fi_direct_connection_including_o),
            getString(R.string.reset_and_connect), getString(R.string.cancel), danger = true,
            onConfirm = { CarPlayBackgroundSession.stop { runOnUiThread { resetWirelessGroup() } } },
        )
    }

    @SuppressLint("MissingPermission")
    private fun resetWirelessGroup() {
        val manager = getSystemService(Context.WIFI_P2P_SERVICE) as? android.net.wifi.p2p.WifiP2pManager
        if (manager == null) { toast(getString(R.string.this_head_unit_does_not_support_wi_fi_direct)); return }
        val channel = manager.initialize(applicationContext, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) {
                    closeP2pChannel(channel)
                    connectAfterWifiReset()
                    return@requestGroupInfo
                }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> {
                                        closeP2pChannel(channel)
                                        if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) {
                                            connectAfterWifiReset()
                                        }
                                    }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> {
                                        val poll = Runnable { waitUntilRemoved() }
                                        p2pGroupPoll = poll
                                        handler.postDelayed(poll, 200)
                                    }
                                }
                            }
                        }
                        waitUntilRemoved()
                    }
                    override fun onFailure(reason: Int) { closeP2pChannel(channel); toast(getString(R.string.could_not_reset_wi_fi_direct_close_the_other_projection_ap)) }
                })
            }
        } catch (_: SecurityException) {
            closeP2pChannel(channel); permissionHelp(getString(R.string.wireless_permissions), getString(R.string.allow_nearby_devices_and_on_older_android_versions_locatio))
        }
    }

    private fun closeP2pChannel(channel: android.net.wifi.p2p.WifiP2pManager.Channel) {
        p2pGroupPoll?.let { handler.removeCallbacks(it) }
        p2pGroupPoll = null
        runCatching { channel.javaClass.getMethod("close").invoke(channel) }
    }

    private var autoWifiResetUsed = false
    private var suppressWifiResetFlagClear = false

    private fun connectAfterWifiReset() {
        suppressWifiResetFlagClear = true
        try {
            connect(true)
        } finally {
            suppressWifiResetFlagClear = false
        }
    }

    /** A stuck Wi-Fi Direct group is reset and the connect is retried once, without a confirm dialog. */
    private fun presentConnectFailure(message: String) {
        val friendly = getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        val needsReset = message == friendly || message.contains(friendly) ||
            message.contains("needs a reset", ignoreCase = true)
        if (needsReset && !autoWifiResetUsed) {
            autoWifiResetUsed = true
            resetWirelessGroup()
            return
        }
        DiPlayDialog.showInfo(
            this,
            getString(R.string.connection_failed),
            message,
            ok = getString(R.string.back),
        )
    }

    private fun consumeConnectFailure() {
        val message = intent.getStringExtra(EXTRA_FAILURE_MESSAGE)?.takeIf { it.isNotBlank() } ?: return
        intent.removeExtra(EXTRA_FAILURE_MESSAGE)
        CarPlayConnectService.markIdle()
        CarPlayBackgroundSession.forceReset()
        presentConnectFailure(message)
    }

    private fun refreshStatus() {
        shellChrome.updateClock()
        shellChrome.updateRadios(radioProbe.snapshot())
        val active = isCarPlayActive()
        // Do not use hasSession here: stop() keeps hasSession true while tearing down,
        // which used to flash「连接中」after Disconnect / mode switch.
        val running = !active && isCarPlayConnecting()
        val label: String
        val color: Int
        val fill: Int
        val stroke: Int
        when {
            setupError != null -> {
                label = getString(R.string.setup_needs_attention)
                color = GlassColors.ALERT
                fill = 0x33FFD60A
                stroke = 0x66FFD60A
            }
            active -> {
                label = getString(R.string.carplay_connected)
                color = GlassColors.SUCCESS
                fill = 0x3330D158
                stroke = 0x6630D158
            }
            running -> {
                label = getString(R.string.home_connecting)
                color = GlassColors.WARNING
                fill = 0x33FF9F0A
                stroke = 0x66FF9F0A
            }
            else -> {
                label = getString(R.string.home_not_connected)
                color = GlassColors.TEXT_MUTED
                fill = GlassColors.GLASS_CHIP
                stroke = GlassColors.GLASS_RIM
            }
        }
        shellChrome.applyStatus(label, color, fill, stroke)
    }
    private fun exportDiagnostics(uri: Uri? = null) = diagnostics.export(uri)

    override fun chooseReportDestination() {
        diagnostics.chooseDestination { export.launch(it) }
    }

    private fun permissionHelp(title: String, body: String) {
        DiPlayDialog.showConfirm(
            this, title, body,
            getString(R.string.app_settings), getString(R.string.later),
            onConfirm = {
                openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            },
        )
    }
    private fun openSystem(intent: Intent): Boolean = ShellNetworkActions.openSystemSettings(
        this,
        preferred = intent,
        onAllFailed = {
            toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
        },
    )
    private fun toast(message: String) { DiPlayDialog.toast(this, message, true) }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    override fun dp(value: Int) = com.shihab.diplay.ui.LayoutMetrics.dp(this, value)
    companion object {
        /** Timestamp of the last deliberate return from the CarPlay screen (three-finger swipe /
         *  floating ball). DiPlayActivity uses it to avoid auto-re-entering the still-alive CarPlay
         *  session for a short window even if onPause cleared userNavigatedFromCarPlay. */
        @JvmStatic var returnedFromCarPlayAt = 0L
        const val ACTION_CONNECTED = com.shihab.diplay.connect.ConnectBroadcasts.ACTION_CONNECTED
        const val ACTION_CONNECT_CANCEL = com.shihab.diplay.connect.ConnectBroadcasts.ACTION_CONNECT_CANCEL
        const val ACTION_CONNECT_FAILED = com.shihab.diplay.connect.ConnectBroadcasts.ACTION_CONNECT_FAILED
        const val ACTION_CONNECT_PROGRESS = com.shihab.diplay.connect.ConnectBroadcasts.ACTION_CONNECT_PROGRESS
        const val EXTRA_FAILURE_MESSAGE = com.shihab.diplay.connect.ConnectBroadcasts.EXTRA_FAILURE_MESSAGE
        const val EXTRA_CONNECT_STAGE = com.shihab.diplay.connect.ConnectBroadcasts.EXTRA_CONNECT_STAGE
        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")
        /** Same budget the host uses before it builds a new stack on a closing controller. */
        private const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        private const val DEFAULT_HOTSPOT_SSID = ShellNetworkActions.DEFAULT_HOTSPOT_SSID
        private const val DEFAULT_HOTSPOT_PASS = ShellNetworkActions.DEFAULT_HOTSPOT_PASS
    }
}
