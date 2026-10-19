// SPDX-License-Identifier: AGPL-3.0-only
// UI copy and visual language adapted from DiAuto. See docs/THIRD_PARTY_NOTICES.md.
package com.shihab.diplay
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.FloatingBall
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.settings.SettingsCategory

import android.annotation.SuppressLint
import android.Manifest
import com.shihab.diplay.ui.DiPlayDialog
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import com.shihab.diplay.compat.BluetoothCompat
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 启动期后台解码的全屏背景图缓存，供主页 Fragment 复用，避免主线程重复解码 1920×1080 大图。 */
object StartupCache {
    @Volatile
    var darkBg: android.graphics.Bitmap? = null
}

/** DiAuto's visual language, with a connection flow for an independent CarPlay receiver. */
class DiPlayActivity : FragmentActivity(), DiPlayHost {
    private val handler = Handler(Looper.getMainLooper())
    private val automaticUpdateCheck = Runnable {
        if (!isFinishing && !isDestroyed) updater.checkAutomatically()
    }
    private val updaterDelegate = lazy { DiPlayUpdater(this) }
    private val updater by updaterDelegate
    private fun lazyUpdaterInitialized() = updaterDelegate.isInitialized()
    private var windowLearning: WindowKeyLearning? = null
    private val windowLearningPresses = WheelKeyPresses()
    private val endWindowLearning = Runnable { cancelKeyLearning() }
    private var page = "home"
    private var settingsCategory = SettingsCategory.OVERVIEW
    private var connectionSettingsReturnCategory: SettingsCategory? = null
    private var pendingCarHotspotSetup = false
    private var hotspotJoinControls: HotspotJoinControls? = null
    private var setupError: String? = null
    private var status: TextView? = null
    private var connectButton: Button? = null
    private var disconnectButton: Button? = null
    private var lastRunning: Boolean? = null
    private var lastActive: Boolean? = null
    private var pendingWireless = false
    private var initialLaunch = true
    private var exportInProgress = false
    private var usbPermissionOperation: HeadUnitPermissionSetup.Operation? = null
    internal var usbPermissionOperationFactory: (Context) -> HeadUnitPermissionSetup.Operation = {
        HeadUnitPermissionSetup.Operation(it.applicationContext)
    }
    private var exportButton: Button? = null
    private var fragmentContainer: FrameLayout? = null
    private val navEntries = mutableMapOf<String, NavEntry>()
    private data class NavEntry(val row: LinearLayout, val label: TextView, val icon: ImageView)
    private var reconnectBar: View? = null
    private var adbSwitchChangePending = false
    private var pausedForAdbSwitchChange = false
    private var hotspotStartupResult: CarHotspotTethering.Result? = null
    @Volatile private var startupHotspotCancelled = false
    private val tick = object : Runnable {
        override fun run() { refreshStatus(); handler.postDelayed(this, 1000) }
    }

    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) exportDiagnostics(uri)
    }

    private var languagePreferenceAtCreate = AppLocale.SYSTEM
    private var userNavigatedFromCarPlay = false

    private var interfaceOverride: Configuration? = null
    private var interfaceSystemDensityDpi = 0
    private var interfaceRecreateRequested = false

    private fun enforceInterfaceSize(): Boolean {
        val language = AppLocale.enforce(this)
        if (language) android.util.Log.i("DiPlayUi", "app language re-applied")
        val override = interfaceOverride ?: return language
        val found = resources.displayMetrics.densityDpi
        if (!InterfaceSize.enforce(resources, override)) return language
        android.util.Log.i("DiPlayUi", "interface size re-applied: $found -> ${override.densityDpi} dpi")
        return true
    }

    override fun attachBaseContext(newBase: Context) {
        val base = AppLocale.wrap(newBase)
        super.attachBaseContext(base)
        interfaceSystemDensityDpi = base.resources.configuration.densityDpi
        interfaceOverride = InterfaceSize.attach(this, base)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Back on the home page finishes this activity while the session runs on, so the icon lands here.
        if (savedInstanceState == null && isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            openProjection(); finish(); return
        }
        if (intent.hasExtra("page")) {
            userNavigatedFromCarPlay = true
        }
        enforceInterfaceSize()
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
        settingsCategory = savedInstanceState?.getString("settings_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
            ?: SettingsCategory.OVERVIEW
        connectionSettingsReturnCategory = savedInstanceState?.getString("connection_settings_return_category")
            ?.let { runCatching { SettingsCategory.valueOf(it) }.getOrNull() }
        page = savedInstanceState?.getString("page") ?: intent.getStringExtra("page") ?: "home"
        // 品牌启动页：暗色全屏背景图 + logo + “应用启动中，请稍后”，立即首帧显示；
        // 真实 UI 延迟到下一帧构建，避免 multidex/慢 CPU 启动期纯黑屏。
        showBrandSplash()
        handler.post {
            if (isFinishing || isDestroyed) return@post
            // 主题与方控恢复延迟到首帧之后，避免阻塞品牌页第一帧。
            initNightModeController()
            WheelKeyService.restoreIfNeeded(this)
            runCatching {
                if (WheelRootInjector.isEnabled(this) && WheelRootInjector.learned(this) != null) {
                    WheelRootInjector.start(this)
                }
            }
            applyShell()
            ImmersiveBars.installAutoHide(this)
            showPage(page)
            // Diagnostic probe walks Bluetooth/USB; keep it off the first-paint critical path.
            if (savedInstanceState == null) {
                handler.post { LegacyConnectionProbe.log(applicationContext) }
            }
            handler.postDelayed(automaticUpdateCheck, 4000L)
            handleWirelessRecovery()
            // 车机蓝牙若被关闭，启动后自动打开，保证 CarPlay 无线连接可用。
            ensureBluetoothEnabledAsync()
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (page != "home") navigateBack()
                    else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            })
            // Show the floating ball as soon as DiPlay launches (when enabled), rather than waiting
            // until a CarPlay session is in front. Tap stays on DiPlay; long-press hops back to
            // CarPlay when a session is running.
            if (AirPlayPersistence.loadFloatingBallEnabled(this) && FloatingBall.permitted(this)) {
                FloatingBall.show(
                    applicationContext,
                    { FloatingBall.backToDiPlay(applicationContext) },
                    { if (hasCarPlaySession()) FloatingBall.backToCarPlay(applicationContext) else FloatingBall.backToDiPlay(applicationContext) },
                )
            }
        }
    }

    /** 启动品牌页：先画纯深色 + logo + 提示文字（首帧最快，不解码大图）；全屏背景图在后台
     * 解码后异步浮现，避免慢 CPU 上 1920×1080 JPEG 解码阻塞第一帧。 */
    private fun showBrandSplash() {
        val root = android.widget.FrameLayout(this)
        root.setBackgroundColor(GlassColors.BACKGROUND)
        val hint = TextView(this).apply {
            text = getString(R.string.diplay_app_launching)
            setTextColor(0xFFD7E6F7.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            )
        }
        root.addView(hint)
        setContentView(root)
        // 后台预解码暗色全屏背景图，就绪后替换纯色；主线程不参与大图解码。
        Thread({
            try {
                val bmp = android.graphics.BitmapFactory.decodeResource(
                    resources, R.drawable.bg_home_photo)
                if (bmp != null) {
                    StartupCache.darkBg = bmp
                    handler.post {
                        if (!isFinishing && !isDestroyed) {
                            root.setBackground(android.graphics.drawable.BitmapDrawable(resources, bmp))
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }, "splash-bg").start()
    }

    // ---- Glass-morphism shell: fixed left nav (240dp) + Fragment container on the right ----

    private fun applyShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 与主页卡片同一底图，侧栏玻璃卡才能透出一致氛围。
            val cached = StartupCache.darkBg
            if (cached != null && GlassColors.isDark) {
                setBackground(android.graphics.drawable.BitmapDrawable(resources, cached))
            } else {
                setBackgroundResource(GlassColors.bgHome())
            }
        }
        // 1920×720 短屏缩窄侧栏；外边距与内容卡片对齐，做成独立玻璃卡。
        val navW = LayoutMetrics.navWidth(this)
        val pagePad = LayoutMetrics.pagePad(this)
        root.addView(
            buildLeftNav(),
            LinearLayout.LayoutParams(navW, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                // 左/上/下与内容卡对齐；右侧间距交给 Fragment 的 pagePad。
                setMargins(pagePad, pagePad, 0, pagePad)
            },
        )
        val container = FrameLayout(this)
        // 固定容器 id：Activity 因 OOM 等原因重建后，FragmentManager 恢复 Fragment 时
        // 依赖稳定 id 找到容器；用 View.generateViewId() 每次生成新 id 会导致
        // “No view found for id” 崩溃（FragmentSetting NoView）。
        container.id = R.id.fragment_container
        fragmentContainer = container
        root.addView(container, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
        showPage(page)
    }

    private fun buildLeftNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // 与连接中心 / 设置卡同一套 card_bg 玻璃材质。
            setBackgroundResource(GlassColors.cardBg())
            val ph = LayoutMetrics.navPadH(this@DiPlayActivity)
            val pv = LayoutMetrics.navPadV(this@DiPlayActivity)
            setPadding(ph, pv, ph, pv)
        }
        // 品牌区：Logo 在上、名称在下居中，避免窄栏横排挤成一团。
        val brand = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(6), 0, dp(12))
        }
        val logoSize = LayoutMetrics.navLogoSize(this)
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_carplay)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(logoSize, logoSize))
        brand.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(GlassColors.TEXT_PRIMARY)
            textSize = LayoutMetrics.navBrandSize(this@DiPlayActivity)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nav.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nav.addView(View(this).apply {
            setBackgroundColor(GlassColors.DIVIDER)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                bottomMargin = dp(12)
            }
        })
        // 短标签：主页 / 显示 / 车辆；完整名放 contentDescription。
        navEntry(
            nav, "home",
            getString(R.string.diplay_nav_home),
            getString(R.string.diplay_nav_home),
            R.drawable.ic_dp_overview,
        )
        navEntry(
            nav, "settings",
            getString(R.string.settings),
            getString(R.string.diplay_nav_display_audio),
            R.drawable.ic_dp_settings,
        )
        navEntry(
            nav, "about",
            getString(R.string.settings_vehicle),
            getString(R.string.diplay_nav_vehicle_system),
            R.drawable.ic_dp_vehicle,
        )
        nav.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 0, 1f) })
        nav.addView(TextView(this).apply {
            text = version()
            setTextColor(GlassColors.TEXT_MUTED)
            textSize = LayoutMetrics.navVersionSize(this@DiPlayActivity)
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return nav
    }

    /**
     * 侧栏项：图标在上、短标签在下（车机常见竖排）。
     * [shortLabel] 用于展示；[fullLabel] 写入 contentDescription（无障碍 / 长按提示语义）。
     */
    private fun navEntry(nav: LinearLayout, target: String, shortLabel: String, fullLabel: String, icon: Int) {
        val iconSize = LayoutMetrics.navIconSize(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setMinimumHeight(LayoutMetrics.navEntryMinH(this@DiPlayActivity))
            setPadding(dp(6), dp(12), dp(6), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = fullLabel
            setOnClickListener { if (page != target) showPage(target) }
        }
        val itemIcon = ImageView(this).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setColorFilter(GlassColors.NAV_UNSELECTED)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        row.addView(itemIcon, LinearLayout.LayoutParams(iconSize, iconSize))
        val label = TextView(this).apply {
            text = shortLabel
            textSize = LayoutMetrics.navLabelSize(this@DiPlayActivity)
            setTextColor(GlassColors.NAV_UNSELECTED)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        nav.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            },
        )
        navEntries[target] = NavEntry(row, label, itemIcon)
    }

    private fun updateNavHighlight() {
        navEntries.forEach { (target, entry) ->
            val selected = target == page
            entry.row.setBackgroundResource(if (selected) GlassColors.navSelected() else 0)
            entry.label.setTextColor(if (selected) GlassColors.NAV_SELECTED_TEXT else GlassColors.NAV_UNSELECTED)
            entry.label.setTypeface(entry.label.typeface,
                if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            // 图标与文字同色：选中=深色(黑，配合青蓝渐变底)，未选中=NAV_UNSELECTED（浅色加深可读）。
            entry.icon.setColorFilter(if (selected) GlassColors.NAV_SELECTED_TEXT else GlassColors.NAV_UNSELECTED)
        }
    }

    private fun showPage(target: String) {
        page = target
        val fragment = when (target) {
            "home" -> FragmentHome()
            "about" -> FragmentAbout()
            else -> FragmentSetting()
        }
        val container = fragmentContainer ?: return
        supportFragmentManager.beginTransaction()
            .replace(container.id, fragment, target)
            .commit()
        updateNavHighlight()
    }

    // ---- DiPlayHost implementation (glass Fragments reach business actions here) ----

    override fun getStr(resId: Int): String = getString(resId)
    override fun disconnectSession() {
        if (isCarPlayConnecting()) {
            cancelConnectAttempt()
            return
        }
        CarPlayBackgroundSession.stop { runOnUiThread { refreshStatus(); refreshHomeFragment() } }
    }
    override fun hasCarPlaySession(): Boolean = CarPlayBackgroundSession.hasSession()
    override fun isCarPlayActive(): Boolean = CarPlayBackgroundSession.active
    override fun isCarPlayConnecting(): Boolean =
        // Only the explicit connect flow flags — do NOT use hasSession()&&!active here:
        // after Cancel, stop()/teardown is async and hasSession stays true briefly, which
        // used to leave the home pill on「连接中」forever from the user's view.
        connectOnly || CarPlayConnectService.connecting
    override fun cancelConnectAttempt() = cancelConnect()
    override fun updateCheckNow() { updater.check(manual = true) }
    override fun exportDiagnosticsNow() { exportDiagnostics() }
    override fun setRootHideNavbar(enabled: Boolean) {
        AirPlayPersistence.saveRootHideNavbar(this, enabled)
        if (enabled) {
            Thread({
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: hide requested")
                val rootOk = RootNavbar.available()
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: root available=$rootOk")
                val ok = RootNavbar.enable()
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: enable result=$ok")
                if (!ok) {
                    AirPlayPersistence.saveRootHideNavbar(this, false)
                    AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: FAILED - fallback disabled")
                    runOnUiThread {
                        toast(getString(R.string.diplay_root_failed_fallback))
                        showPage(page)
                    }
                }
            }, "root-navbar-apply").start()
        } else {
            Thread({
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: restore requested")
                val ok = RootNavbar.applyHidden(false)
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "RootNavbar: restore result=$ok")
            }, "root-navbar-apply").start()
        }
    }

    override fun openWheelKeysSettings() {
        val act = this
        val role = WheelZoomSettings.Role.SIRI
        val content = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(act.dp(18), act.dp(12), act.dp(18), act.dp(12))
        }
        val siriRow = android.widget.LinearLayout(act).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        siriRow.addView(android.widget.TextView(act).apply {
            text = act.getString(R.string.wheel_siri_key)
            setTextColor(0xFFD0D2D9.toInt())
            textSize = 15f
            setPadding(0, 0, act.dp(12), 0)
        })
        val siriSwitch = com.shihab.diplay.ui.GlassSwitch(act).apply {
            isChecked = WheelZoomSettings.siriKey(act)
            onCheckedChangeListener = { _, checked -> WheelZoomSettings.setSiriKey(act, checked) }
        }
        siriRow.addView(siriSwitch)
        content.addView(siriRow)
        val assign = android.widget.Button(act)
        fun refreshAssign() {
            val key = WheelZoomSettings.key(act, role)
            assign.text = act.getString(R.string.wheel_key_assign, act.getString(R.string.wheel_key_role_siri), key?.toString() ?: act.getString(R.string.wheel_key_none))
        }
        refreshAssign()
        assign.setOnClickListener {
            val started = learnInWindow(role,
                cancelled = { act.runOnUiThread { refreshAssign() } },
                refused = { taken -> act.runOnUiThread { refreshAssign(); toast(act.getString(R.string.wheel_key_in_use, act.getString(wheelKeyRoleName(taken)))) } },
                done = { act.runOnUiThread { refreshAssign() } })
            if (started) assign.text = act.getString(R.string.wheel_key_press, act.getString(R.string.wheel_key_role_siri))
            else toast(act.getString(R.string.wheel_keys_service_off))
        }
        content.addView(assign)
        DiPlayDialog.showCustom(act, act.getString(R.string.settings_wheel_keys), content, act.getString(android.R.string.ok))
    }
    override fun autoUpdateEnabled(): Boolean = updater.autoEnabled()
    override fun setAutoUpdateEnabled(enabled: Boolean) { updater.setAutoEnabled(enabled) }
    override fun openDevTerminal() {
        // FragmentAbout already counts the 5 taps; open the terminal as a glass overlay dialog on
        // the current screen (same look as the connecting popup) instead of switching to a page.
        runCatching {
            TerminalOverlay.show(this)
        }
    }
    override fun showGlassToast(message: String, type: DiPlayToast.Type) {
        DiPlayToast.show(this, message, type)
    }
    override fun versionString(): String = version()
    override fun rebuildTheme() {
        configureNightController()
        applyShell()
    }

    private fun systemNightNow(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun initNightModeController() {
        nightModeController = CarPlayNightModeController(
            light = AmbientLightSource(this),
            scheduler = object : CarPlayNightModeController.Scheduler {
                override fun postDelayed(task: Runnable, delayMillis: Long) {
                    nightModeHandler.postDelayed(task, delayMillis)
                }
                override fun remove(task: Runnable) {
                    nightModeHandler.removeCallbacks(task)
                }
            },
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
    override fun showHome() = showPage("home")

    override fun setFloatingBallEnabled(enabled: Boolean) {
        AirPlayPersistence.saveFloatingBallEnabled(this, enabled)
        if (enabled) {
            FloatingBall.show(
                applicationContext,
                { FloatingBall.backToDiPlay(applicationContext) },
                {
                    if (hasCarPlaySession()) FloatingBall.backToCarPlay(applicationContext)
                    else FloatingBall.backToDiPlay(applicationContext)
                },
            )
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
        // WiFi and the car hotspot are mutually exclusive on API19 head units. If the hotspot
        // is still on, shut it down first so the radio is free for station WiFi.
        if (isCarHotspotEnabled()) {
            runCatching {
                wifi.javaClass.getMethod(
                    "setWifiApEnabled",
                    WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(wifi, null, false)
            }
            log.append("hotspotWasOn closed; ")
            Thread.sleep(1200L)
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

    override fun isCarHotspotEnabled(): Boolean = runCatching {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean ?: false
    }.getOrDefault(false)

    override fun openCarHotspot(): Boolean = runCatching {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        val log = StringBuilder("CarHotspot: open ")
        // API19 head units treat WiFi and hotspot as mutually exclusive; most ROMs refuse to
        // start an AP while station WiFi is on. Disable WiFi first, then bring up the AP.
        if (wifi.isWifiEnabled) {
            runCatching { wifi.setWifiEnabled(false) }
            log.append("wifiWasOn=true disabled; ")
            Thread.sleep(1500L)
        } else {
            log.append("wifiWasOn=false; ")
        }
        // Many head-unit ROMs ignore a null WifiConfiguration and the hotspot never turns on;
        // build a real WPA2 config from the saved (or default) SSID/passphrase.
        val ssid = carHotspotSsid().ifBlank { DEFAULT_HOTSPOT_SSID }
        val pass = carHotspotPassphrase().ifBlank { DEFAULT_HOTSPOT_PASS }
        // Persist the hotspot credentials actually used so the home hotspot tab shows the real
        // SSID/passphrase (and a refresh can display "recording/usage info") instead of "未设置".
        AirPlayPersistence.saveManualHotspotSsid(this, ssid)
        AirPlayPersistence.saveManualHotspotPassphrase(this, pass)
        val config = WifiConfiguration().apply {
            SSID = ssid
            // CS75 S401 ROM's setWifiApEnabled treats the config verbatim and does NOT strip the
            // standard WifiConfiguration quote wrapper, so `"$pass"` broadcasts a literal password
            // with quotes and the iPhone (notified with the raw pass) can never connect. Write the
            // raw passphrase so the AP password matches what the app shows / notifies.
            preSharedKey = pass
            status = WifiConfiguration.Status.ENABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN)
            allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
            allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
        }
        val ok = runCatching {
            wifi.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType,
            ).invoke(wifi, config, true) as? Boolean ?: false
        }.onFailure { log.append("setApException=${it.javaClass.simpleName}:${it.message}; ") }
            .getOrDefault(false)
        log.append("setApResult=$ok; ")
        Thread.sleep(800L)
        log.append("apNowEnabled=${isCarHotspotEnabled()}; ")
        val finalOk = ok || isCarHotspotEnabled()
        AsyncDiagnosticLog.append(ActiveSessionLog.current, log.append("final=$finalOk").toString())
        finalOk
    }.getOrDefault(false)

    override fun carHotspotSsid(): String = AirPlayPersistence.loadManualHotspotSsid(this)

    override fun carHotspotPassphrase(): String = AirPlayPersistence.loadManualHotspotPassphrase(this)

    override fun closeCarHotspot(): Boolean = runCatching {
        val wifi = getSystemService(WIFI_SERVICE) as WifiManager
        if (!runCatching { wifi.javaClass.getMethod("isWifiApEnabled").invoke(wifi) as? Boolean ?: false }
                .getOrDefault(false)) {
            // Already off; make sure station WiFi is back on so direct/LAN can use the radio.
            if (!wifi.isWifiEnabled) wifi.setWifiEnabled(true)
            return@runCatching true
        }
        val ok = runCatching {
            wifi.javaClass.getMethod("setWifiApEnabled", WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
                .invoke(wifi, null, false) as? Boolean ?: false
        }.getOrDefault(false)
        Thread.sleep(1200L)
        if (!wifi.isWifiEnabled) wifi.setWifiEnabled(true)
        AsyncDiagnosticLog.append(
            ActiveSessionLog.current,
            "CarHotspot: close apResult=$ok now=${isCarHotspotEnabled()}",
        )
        ok || !isCarHotspotEnabled()
    }.getOrDefault(false)

    override fun scanWifiNetworks(callback: (List<WifiEntry>) -> Unit) {
        Thread {
            val entries = runCatching {
                val wifi = getSystemService(WIFI_SERVICE) as? WifiManager ?: return@runCatching emptyList<WifiEntry>()
                runCatching { wifi.startScan() }
                // startScan 异步；稍等再读，避免仍是上一轮空/旧结果。
                Thread.sleep(900L)
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
            }.getOrDefault(emptyList<WifiEntry>())
            runOnUiThread { callback(entries) }
        }.start()
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

    override fun connectedBluetoothDeviceName(): String {
        var name = currentlyConnectedDevice()?.name?.takeIf { it.isNotBlank() } ?: ""
        if (name.isEmpty()) {
            // WiFi-direct / bootstrap mode may not keep a persistent A2DP "connected" state
            // (isConnected is false even though the iPhone is reachable), so fall back to a
            // paired iPhone, then any paired device, so the startup card shows a name instead
            // of the picker hint.
            val paired = runCatching {
                com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(this).getPairedDevices()
            }.getOrNull().orEmpty()
            name = paired.firstOrNull { it.name.contains("iPhone", ignoreCase = true) }?.name.orEmpty()
            if (name.isEmpty()) name = paired.firstOrNull()?.name.orEmpty()
        }
        if (name.isEmpty()) name = DiPlayPreferences.phoneName(this)?.takeIf { it.isNotBlank() }.orEmpty()
        runCatching {
            AsyncDiagnosticLog.append(
                ActiveSessionLog.current,
                "BluetoothConnected: name=$name",
            )
        }
        return name
    }

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
                if (err != null) android.util.Log.e("DiPlaySetup", "CarPlay authentication could not be loaded", err)
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        connectionSettingsReturnCategory = null
        // CarPlay runs in its own task, so the launcher icon resumes this one. Settings opened from
        // CarPlay carry a "page" extra, which isLauncherIntent rejects.
        if (isLauncherIntent(intent) && CarPlayBackgroundSession.hasSession()) {
            userNavigatedFromCarPlay = false
            page = "home"; showPage(page); openProjection(); return
        }
        val explicitPage = intent.getStringExtra("page")
        if (explicitPage != null) {
            userNavigatedFromCarPlay = true
            page = explicitPage
            showPage(page)
        } else if (CarPlayBackgroundSession.hasSession() && !userNavigatedFromCarPlay) {
            page = "home"; showPage(page); openProjection(); return
        } else {
            page = "home"; showPage(page)
        }
        handleWirelessRecovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("page", page)
        outState.putString("settings_category", settingsCategory.name)
        connectionSettingsReturnCategory?.let { outState.putString("connection_settings_return_category", it.name) }
        outState.putBoolean("pending_car_hotspot", pendingCarHotspotSetup)
        super.onSaveInstanceState(outState)
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (updateInterfaceSize(newConfig)) return
        showPage(page)
    }

    /** True while a density change is recreating this activity. */
    private fun updateInterfaceSize(configuration: Configuration): Boolean {
        if (interfaceRecreateRequested) return true
        // The activity handles real system density changes too. Its callback contains our installed
        // density, while application resources retain the current unscaled system density.
        applicationContext.resources.configuration.densityDpi.takeIf { it > 0 }?.let {
            interfaceSystemDensityDpi = it
        }
        val next = InterfaceSize.configurationChange(configuration, interfaceSystemDensityDpi,
            InterfaceSize.preference(this))
        if (InterfaceSize.needsRecreate(interfaceOverride, next)) {
            interfaceRecreateRequested = true
            recreate()
            return true
        }
        interfaceOverride = next
        return false
    }
    override fun onStart() {
        super.onStart()
        // Results from the image picker and crop screens arrive before onResume.
        enforceInterfaceSize()
    }

    override fun onStop() {
        cancelHeadUnitPermissionSetup()
        startupHotspotCancelled = true
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // 主题切换/环境光/定时变化时重建 shell（Fragment 重新渲染读新色板）。
        if (applyNightMode()) {
            applyShell()
            ImmersiveBars.applyToWindow(window, force = true)
            ImmersiveBars.installAutoHide(this)
            return
        }
        ImmersiveBars.applyToWindow(window, force = true)
        ImmersiveBars.installAutoHide(this)
        // Returning from another activity can bring the head unit's own density back.
        if (enforceInterfaceSize()) showPage(page)
        if (Build.VERSION.SDK_INT < 33 && AppLocale.preference(this) != languagePreferenceAtCreate) {
            recreate()
            return
        }
        handler.removeCallbacks(tick); handler.post(tick)
        updater.onResume()
        // Back from the car settings: refresh the car hotspot reminder on the home page.
        if (!initialLaunch && !adbSwitchChangePending && !pausedForAdbSwitchChange &&
            (page == "home" || page == "settings" || page == "connection")) showPage(page)
        pausedForAdbSwitchChange = false
        // Came back from the CarPlay screen via three-finger swipe / floating ball: stay on the
        // DiPlay home even though the CarPlay session is still alive, instead of flashing back.
        // Use a time-window too, because onPause clears userNavigatedFromCarPlay and the delayed
        // CarPlayHost finish can trigger a second onResume after the intent flag was consumed.
        val justReturnedFromCarPlay =
            intent?.getBooleanExtra("returnFromCarPlay", false) == true ||
                (System.currentTimeMillis() - returnedFromCarPlayAt) < 3000
        if (justReturnedFromCarPlay) {
            userNavigatedFromCarPlay = true
            returnedFromCarPlayAt = System.currentTimeMillis()
            intent?.removeExtra("returnFromCarPlay")
        }
        if (!initialLaunch && CarPlayBackgroundSession.hasSession() && page == "home" &&
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
        userNavigatedFromCarPlay = false
        cancelKeyLearning()
        pausedForAdbSwitchChange = adbSwitchChangePending
        handler.removeCallbacks(tick)
        nightModeController?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(automaticUpdateCheck)
        if (lazyUpdaterInitialized()) updater.close()
        hotspotJoinControls?.close()
        cancelHeadUnitPermissionSetup()
        cancelKeyLearning()
        unregisterConnectStateReceiver()
        dismissConnectDialog()
        connectOnly = false
        super.onDestroy()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        if (updateInterfaceSize(newConfig)) return
        showPage(page)
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            ImmersiveBars.applyToWindow(window, force = true)
            ImmersiveBars.installAutoHide(this)
        }
        // Some head units put their own density back without any other callback.
        if (hasFocus && enforceInterfaceSize()) showPage(page)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        // 任意点击：若边滑/ROM 已把系统栏拉出来，立刻收回（不限于弹窗）。
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
            ImmersiveBars.rehideIfBarsVisible(this)
        }
        return super.dispatchTouchEvent(ev)
    }

    private val isExpandedSettingsLayout: Boolean
        get() = resources.configuration.let {
            SettingsLayoutPolicy.isExpanded(it.screenWidthDp, it.screenHeightDp, it.fontScale)
        }

    private fun navigateBack() {
        val returnCategory = connectionSettingsReturnCategory
        when {
            page == "connection" && returnCategory != null -> {
                page = "settings"
                settingsCategory = returnCategory
                connectionSettingsReturnCategory = null
            }
            page == "settings" && !isExpandedSettingsLayout && settingsCategory != SettingsCategory.OVERVIEW -> {
                settingsCategory = SettingsCategory.OVERVIEW
            }
            else -> {
                page = "home"
                connectionSettingsReturnCategory = null
            }
        }
        showPage(page)
    }

    private fun startCarHotspotOnLaunch() {
        if (!startupHotspotEligible()) return
        val app = applicationContext
        Thread({
            val result = CarHotspotTethering.enable(app,
                isCancelled = { startupHotspotCancelled || !startupHotspotEligible() },
                log = { Log.i("DiPlay-Hotspot", it) },
            )
            runOnUiThread {
                if (isFinishing || isDestroyed || result == CarHotspotTethering.Result.CANCELLED) return@runOnUiThread
                hotspotStartupResult = result
                if (page == "home") showPage(page)
            }
        }, "diplay-hotspot-startup").start()
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

    private fun wheelKeyRoleName(role: WheelZoomSettings.Role): Int = when (role) {
        WheelZoomSettings.Role.MODE -> R.string.wheel_key_role_mode
        WheelZoomSettings.Role.ZOOM_IN -> R.string.wheel_key_role_zoom_in
        WheelZoomSettings.Role.ZOOM_OUT -> R.string.wheel_key_role_zoom_out
        WheelZoomSettings.Role.JOYSTICK -> R.string.wheel_key_role_joystick
        WheelZoomSettings.Role.PREVIOUS -> R.string.wheel_key_role_previous
        WheelZoomSettings.Role.NEXT -> R.string.wheel_key_role_next
        WheelZoomSettings.Role.SELECT -> R.string.wheel_key_role_select
        WheelZoomSettings.Role.SIRI -> R.string.wheel_key_role_siri
    }

    private class WindowKeyLearning(
        val role: WheelZoomSettings.Role,
        val cancelled: () -> Unit,
        val refused: (WheelZoomSettings.Role) -> Unit,
        val done: (WheelKey) -> Unit,
    )

    private fun learnInWindow(role: WheelZoomSettings.Role, cancelled: () -> Unit,
        refused: (WheelZoomSettings.Role) -> Unit, done: (WheelKey) -> Unit): Boolean {
        cancelKeyLearning()
        windowLearning = WindowKeyLearning(role, cancelled, refused, done)
        AsyncDiagnosticLog.append(ActiveSessionLog.current, "WheelLearning: window open role=$role (learn via dispatchKeyEvent)")
        handler.postDelayed(endWindowLearning, WheelKeyService.LEARNING_TIMEOUT_MILLIS)
        return true
    }

    private fun cancelKeyLearning() {
        WheelKeyService.cancelLearning()
        handler.removeCallbacks(endWindowLearning)
        windowLearning?.cancelled?.invoke()
        windowLearning = null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return super.dispatchKeyEvent(event)
        val learningNow = windowLearning
        if (learningNow != null) {
            AsyncDiagnosticLog.append(ActiveSessionLog.current,
                "WheelLearning: KEYEVENT device=${event.deviceId} code=${event.keyCode} scan=${event.scanCode} action=${event.action} repeat=${event.repeatCount} role=${learningNow.role} audioMode=${audioModeName()}")
        }
        val action = windowLearningPresses.filter(
            Triple(event.deviceId, event.keyCode, event.scanCode),
            event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            val learning = windowLearning
            if (learning == null || event.keyCode == KeyEvent.KEYCODE_BACK) return@filter WheelZoomKeys.Action.PASS
            // task #53: 学习判定不再用 mode!=NORMAL 的过宽通话判定——CarPlay 媒体/通信会话会把
            // AudioManager.mode 置为非 NORMAL，导致所有方向盘学习被误判为“通话中”而拒绝。
            // 只拒绝明确的来电(MODE_RINGTONE)与通话(MODE_IN_CALL)模式。
            val audioMode = (getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.mode
            val realCall = audioMode == android.media.AudioManager.MODE_IN_CALL || audioMode == android.media.AudioManager.MODE_RINGTONE
            if (realCall || !WheelZoomSettings.siriKey(this)) {
                val why = if (realCall) "callMode=$audioMode" else "siriKeySwitch=false"
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "WheelLearning: REJECTED role=${learning.role} ($why)")
                cancelKeyLearning()
                return@filter WheelZoomKeys.Action.PASS
            }
            handler.removeCallbacks(endWindowLearning)
            windowLearning = null
            val key = WheelKey.of(event)
            val taken = WheelZoomSettings.conflict(this, learning.role, key)
            if (taken != null) {
                Log.i(WheelKeyService.TAG, "${learning.role} key $key refused: it is the $taken key (learnt without the service)")
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "WheelLearning: key=$key refused for ${learning.role}: taken by $taken")
                learning.refused(taken)
            } else {
                WheelZoomSettings.assign(this, learning.role, key)
                Log.i(WheelKeyService.TAG, "${learning.role} key is now $key (learnt without the service)")
                AsyncDiagnosticLog.append(ActiveSessionLog.current, "WheelLearning: key=$key assigned to ${learning.role}")
                learning.done(key)
            }
            WheelZoomKeys.Action.CONSUME
        }
        return action != WheelZoomKeys.Action.PASS || super.dispatchKeyEvent(event)
    }

    private fun audioModeName(): String = when (
        (getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager)?.mode
    ) {
        android.media.AudioManager.MODE_NORMAL -> "normal"
        android.media.AudioManager.MODE_RINGTONE -> "ringtone"
        android.media.AudioManager.MODE_IN_CALL -> "in_call"
        android.media.AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        else -> "other"
    }

    override fun reconnectIfRunning() {
        if (CarPlayBackgroundSession.hasSession()) connect(AirPlayPersistence.loadWirelessEnabled(this))
    }

    // A reconnect-required setting was changed: confirm before applying (OK = save + reconnect).
    override fun confirmReconnectChange(message: String, onApply: () -> Unit) {
        DiPlayDialog.showConfirm(
            this,
            getString(R.string.reconnect_required_title),
            message,
            getString(R.string.save_and_reconnect),
            getString(R.string.cancel),
            onConfirm = { onApply() },
            onCancel = null,
        )
    }

    // Saved without dropping CarPlay; the reconnect bar lets the driver choose when.
    override fun markReconnectNeeded() {
        CarPlayBackgroundSession.snapshot()?.controller?.let(PendingReconnect::mark)
        refreshReconnectBar()
    }

    private fun refreshReconnectBar() {
        reconnectBar?.visibility =
            if (PendingReconnect.isPending(CarPlayBackgroundSession.snapshot()?.controller)) View.VISIBLE else View.GONE
    }

    override fun connect(wireless: Boolean) {
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
                if (cur.isNotBlank() && AirPlayPersistence.loadExistingWifiPassphrase(this).isBlank()) {
                    promptExistingWifiPassword(cur) { connect(wireless) }
                    return
                }
            }
            if (mode != WirelessHotspotMode.MANUAL && !isWifiEnabled()) enableWifi()
            // On startup prefer the currently connected Bluetooth device: if one is already
            // active, adopt it automatically instead of forcing the phone picker.
            val connected = currentlyConnectedDevice()
            if (connected != null) {
                DiPlayPreferences.savePhone(this, connected.address, connected.name)
            } else if (DiPlayPreferences.phoneAddress(this) == null) {
                // 无上次连接设备：若车机已配对唯一 iPhone，直接用该设备连接；否则弹窗让用户选择。
                pendingWireless = true
                bluetoothExecutor.execute {
                    val onlyDevice = uniquePairedDevice()
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
        val preferences = getSharedPreferences("diplay", MODE_PRIVATE)
        val open = {
            AirPlayPersistence.saveWirelessEnabled(this, wireless)
            startConnectFlow()
        }
        if (CarPlayBackgroundSession.hasSession()) CarPlayBackgroundSession.stop { runOnUiThread { open() } }
        else open()
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

    // ---- 连接中：留在 DiPlay 玻璃弹窗（可取消），连接成功后由 CarPlayHostActivity 全屏接管 ----

    private var connectDialog: Dialog? = null
    private var connectProgressHandle: ProgressDialogHandle? = null
    private var connectOnly = false
    private var connectStateReceiver: BroadcastReceiver? = null
    // 五档主题：跟随系统/环境光/浅色/暗黑/定时（环境光与定时由 Controller 驱动）
    private var nightModeController: CarPlayNightModeController? = null
    private val nightModeHandler = Handler(Looper.getMainLooper())

    private fun startConnectFlow() {
        if (connectOnly) return
        connectOnly = true
        showConnectDialog()
        registerConnectStateReceiver()
        refreshHomeFragment()
        // Connection is driven by the background service (no connect-only host Activity, which
        // flashed a black window on API 19). Progress/success/failure come back as broadcasts.
        val service = Intent(this, CarPlayConnectService::class.java)
            .setAction(CarPlayConnectService.ACTION_START)
        runCatching { startService(service) }.onFailure {
            connectOnly = false
            dismissConnectDialog()
            refreshHomeFragment()
            toast(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
    }

    private fun showConnectDialog() {
        dismissConnectDialog()
        val fingers = AirPlayPersistence.loadSettingsGestureFingers(this)
        val handle = DiPlayDialog.showProgressLive(
            this,
            getString(R.string.connecting_to_your_iphone),
            getString(R.string.connecting_carplay_please_wait),
            footer = getString(R.string.connecting_swipe_hint, fingers),
            cancellable = true,
            onCancel = { cancelConnect() },
        )
        connectDialog = handle.dialogRef
        connectProgressHandle = handle
    }

    private fun dismissConnectDialog() {
        connectDialog?.let { if (it.isShowing) it.dismiss() }
        connectDialog = null
        connectProgressHandle = null
    }

    private fun cancelConnect() {
        connectOnly = false
        CarPlayConnectService.markIdle()
        CarPlayBackgroundSession.forceReset()
        dismissConnectDialog()
        unregisterConnectStateReceiver()
        // Prefer an explicit service cancel (runs teardown) over broadcast+stopService alone:
        // some head units drop the broadcast, and onDestroy used to skip teardown.
        runCatching {
            startService(
                Intent(this, CarPlayConnectService::class.java)
                    .setAction(CarPlayConnectService.ACTION_CANCEL),
            )
        }
        runCatching { sendBroadcast(Intent(ACTION_CONNECT_CANCEL)) }
        runCatching { stopService(Intent(this, CarPlayConnectService::class.java)) }
        refreshStatus()
        refreshHomeFragment()
        // Second pass after service teardown races on slow head units.
        handler.postDelayed({ refreshStatus(); refreshHomeFragment() }, 250L)
    }

    /** Push connection / app-list state into the visible home fragment immediately. */
    private fun refreshHomeFragment() {
        (supportFragmentManager.findFragmentByTag("home") as? FragmentHome)?.refreshFromHost()
    }

    private fun registerConnectStateReceiver() {
        unregisterConnectStateReceiver()
        val filter = IntentFilter().apply {
            addAction(ACTION_CONNECTED)
            addAction(ACTION_CONNECT_FAILED)
            addAction(ACTION_CONNECT_PROGRESS)
        }
        connectStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_CONNECT_PROGRESS -> {
                        val stage = intent.getStringExtra(EXTRA_CONNECT_STAGE)
                        if (stage != null) connectProgressHandle?.updateDescription(stage)
                    }
                    ACTION_CONNECTED -> {
                        connectOnly = false
                        dismissConnectDialog()
                        unregisterConnectStateReceiver()
                        refreshHomeFragment()
                        // 连接成功：带回 CarPlayHostActivity 前台展示 CarPlay 画面
                        openProjection()
                    }
                    ACTION_CONNECT_FAILED -> {
                        val msg = intent.getStringExtra(EXTRA_FAILURE_MESSAGE) ?: getString(R.string.connection_failed)
                        connectOnly = false
                        CarPlayConnectService.markIdle()
                        // 与 cancelConnect 一致：失败后清掉未交接的后台会话，避免 hasSession 短暂残留。
                        CarPlayBackgroundSession.forceReset()
                        dismissConnectDialog()
                        unregisterConnectStateReceiver()
                        refreshStatus()
                        refreshHomeFragment()
                        // 服务 teardown 可能晚于本广播：再刷一次，避免主页卡在「连接中」。
                        handler.postDelayed({ refreshStatus(); refreshHomeFragment() }, 250L)
                        DiPlayDialog.showInfo(this@DiPlayActivity, getString(R.string.connection_failed), msg, ok = getString(R.string.back))
                    }
                }
            }
        }
        runCatching { registerReceiver(connectStateReceiver, filter) }
    }

    private fun unregisterConnectStateReceiver() {
        connectStateReceiver?.let { runCatching { unregisterReceiver(it) } }
        connectStateReceiver = null
    }
    override fun openProjection() {
        // KitKat (API 19) ActivityStack mishandles REORDER_TO_FRONT with singleTask and can
        // resume the home launcher instead of CarPlayHostActivity (CS75 / OS_S311-WT).
        startActivity(Intent(this, CarPlayHostActivity::class.java))
    }
    data class DiscoveredBluetoothDevice(
        val address: String,
        val name: String,
        val isConnected: Boolean,
    )

    private val bluetoothExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** 车机蓝牙若被关闭，后台自动打开，保证 CarPlay 无线连接可用。 */
    private fun ensureBluetoothEnabledAsync() {
        bluetoothExecutor.execute {
            runCatching {
                val adapter = BluetoothCompat.getAdapter(this)
                if (adapter != null && !adapter.isEnabled) adapter.enable()
            }
        }
    }

    /** The Bluetooth device that is currently connected (from the hybrid HSAE stack CarPlay uses). */
    private fun currentlyConnectedDevice(): DiscoveredBluetoothDevice? = runCatching {
        // Multiple devices can be connected at once (A2DP/HFP split). Prefer an already-connected
        // iPhone; fall back to the first connected device, then the adapter's own pick.
        val hybrid = com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(this)
        val connected = hybrid.getPairedDevices().filter { it.isConnected }
        val iphone = connected.firstOrNull { it.name.contains("iPhone", ignoreCase = true) }
        val pick = iphone ?: connected.firstOrNull() ?: hybrid.getConnectedDevice()
        pick?.let { DiscoveredBluetoothDevice(it.address.trim().uppercase(java.util.Locale.US), it.name, true) }
    }.getOrNull()

    /**
     * 无上次连接设备时：若车机已配对的设备恰好只有一个，则返回它用于自动连接；
     * 配对为空或存在多个配对设备都返回 null（由调用方弹窗让用户选择）。
     * 不按设备名判断是否为 iPhone（用户可能改名，按名字判断会失效）。
     */
    private fun uniquePairedDevice(): DiscoveredBluetoothDevice? {
        val adapter = BluetoothCompat.getAdapter(this)
        val devices = fetchBluetoothDevicesForPhonePicker(adapter)
        return if (devices.size == 1) devices.single() else null
    }

    /** "Choose iPhone · <name>" once an iPhone is chosen. Before that there is no name to show, only "Choose iPhone". */
    override fun chooseIphoneTitle(): String =
        if (DiPlayPreferences.phoneAddress(this) == null) getString(R.string.choose_iphone)
        else "${getString(R.string.choose_iphone_prefix)}${DiPlayPreferences.phoneName(this)}"

    @android.annotation.SuppressLint("MissingPermission")
    override fun choosePhone() {
        val adapter = BluetoothCompat.getAdapter(this)
        if (adapter != null && !adapter.isEnabled) {
            runCatching { adapter.enable() }
        }

        bluetoothExecutor.execute {
            val devices = fetchBluetoothDevicesForPhonePicker(adapter)
            runOnUiThread {
                if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) return@runOnUiThread
                if (devices.isNotEmpty()) {
                    showChoosePhoneDialog(devices)
                } else {
                    promptManualBluetoothInput()
                }
            }
        }
    }

    /**
     * Phone picker sources (merged):
     * 1. Hybrid / HSAE vendor paired list (CS75 often keeps the iPhone here only)
     * 2. Android bonded + GATT + A2DP/HEADSET connected devices
     */
    @android.annotation.SuppressLint("MissingPermission")
    private fun fetchBluetoothDevicesForPhonePicker(
        adapter: BluetoothAdapter?,
    ): List<DiscoveredBluetoothDevice> {
        val result = LinkedHashMap<String, DiscoveredBluetoothDevice>()

        fun put(addr: String?, name: String?, connected: Boolean) {
            val key = addr?.trim()?.uppercase(Locale.US) ?: return
            if (key.isEmpty() || !BluetoothAdapter.checkBluetoothAddress(key)) return
            val existing = result[key]
            val resolvedName = name?.takeIf { it.isNotBlank() }
                ?: existing?.name
                ?: getString(R.string.paired_device)
            result[key] = DiscoveredBluetoothDevice(
                address = key,
                name = resolvedName,
                isConnected = connected || existing?.isConnected == true,
            )
        }

        // 1. Hybrid adapter (HSAE + Android) — same stack CarPlay connection uses
        runCatching {
            val hybrid = com.shihab.diplay.bluetooth.CarPlayBluetoothManager.get(this)
            for (device in hybrid.getPairedDevices()) {
                put(device.address, device.name, device.isConnected)
            }
            hybrid.getConnectedDevice()?.let { put(it.address, it.name, true) }
        }.onFailure { Log.w("DiPlay", "hybrid Bluetooth phone list failed", it) }

        // 2. Direct Android adapter enumeration (bonded / GATT / profiles)
        if (adapter != null) {
            val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrDefault(emptySet())
            for (device in bonded) {
                put(device.address, device.name, isBluetoothDeviceConnected(device))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                for (device in BluetoothCompat.getConnectedGattDevices(this)) {
                    put(device.address, device.name, true)
                }
            }
            runCatching {
                for (device in queryConnectedProfileDevices(adapter)) {
                    put(device.address, device.name, true)
                }
            }
        }

        return result.values.sortedWith(
            compareByDescending<DiscoveredBluetoothDevice> { it.isConnected }
                .thenByDescending { it.name.contains("iPhone", ignoreCase = true) }
                .thenBy { it.name },
        )
    }

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: Throwable) {
        false
    }

    private fun queryConnectedProfileDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> {
        val connected = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val latch = java.util.concurrent.CountDownLatch(2)

        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                try {
                    proxy.connectedDevices?.let { connected.addAll(it) }
                } catch (_: Throwable) {
                } finally {
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                latch.countDown()
            }
        }

        val hasHeadset = runCatching { adapter.getProfileProxy(this, listener, BluetoothProfile.HEADSET) }.getOrDefault(false)
        if (!hasHeadset) latch.countDown()

        val hasA2dp = runCatching { adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP) }.getOrDefault(false)
        if (!hasA2dp) latch.countDown()

        runCatching { latch.await(600, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return synchronized(connected) { connected.toSet() }
    }

    private fun showChoosePhoneDialog(devices: List<DiscoveredBluetoothDevice>) {
        val manualLabel = getString(R.string.manual_input_mac)
        val options = devices.map { device ->
            val status = if (device.isConnected) " · ${getString(R.string.bluetooth_device_connected)}" else ""
            val duplicate = devices.count { it.name == device.name } > 1
            val disambiguation = if (duplicate) " (${device.address.takeLast(5)})" else ""
            "${device.name}$disambiguation$status"
        } + manualLabel
        // Default selection: the currently connected device first, then the saved phone,
        // otherwise the first entry.
        val savedAddr = DiPlayPreferences.phoneAddress(this)?.trim()?.uppercase(java.util.Locale.US)
        val connectedIdx = devices.indexOfFirst { it.isConnected }
        val savedIdx = devices.indexOfFirst { it.address == savedAddr }
        val current = when {
            connectedIdx >= 0 -> connectedIdx
            savedIdx >= 0 -> savedIdx
            else -> 0
        }
        DiPlayDialog.showSingleChoice(this, getString(R.string.choose_your_iphone), options, current) { index ->
            if (index == devices.size) {
                promptManualBluetoothInput()
            } else {
                val device = devices[index]
                DiPlayPreferences.savePhone(this, device.address, device.name)
                val start = pendingWireless
                pendingWireless = false
                showPage(page)
                if (start) connect(true)
            }
        }
    }

    private fun promptManualBluetoothInput() {
        DiPlayDialog.showInput(
            this,
            getString(R.string.choose_your_iphone),
            "00:11:22:33:44:55",
            DiPlayPreferences.phoneAddress(this) ?: "",
            assist = getString(R.string.manual_bluetooth_input_mac_label),
            save = getString(R.string.save_details),
            validator = { mac ->
                if (!BluetoothAdapter.checkBluetoothAddress(mac.trim().uppercase(java.util.Locale.US)))
                    getString(R.string.manual_bluetooth_input_invalid_mac) else null
            },
            onSave = { mac ->
                val raw = mac.trim().uppercase(java.util.Locale.US)
                DiPlayPreferences.savePhone(this, raw, "iPhone")
                val start = pendingWireless
                pendingWireless = false
                showPage(page)
                if (start) connect(true)
            },
        )
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
        val channel = manager.initialize(this, mainLooper, null)
        try {
            manager.requestGroupInfo(channel) { group ->
                if (group == null) { closeP2pChannel(channel); connect(true); return@requestGroupInfo }
                manager.removeGroup(channel, object : android.net.wifi.p2p.WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val deadline = android.os.SystemClock.elapsedRealtime() + 4000
                        fun waitUntilRemoved() {
                            manager.requestGroupInfo(channel) { remaining ->
                                when {
                                    remaining == null -> { closeP2pChannel(channel); if (!isFinishing && (Build.VERSION.SDK_INT < 17 || !isDestroyed)) connect(true) }
                                    android.os.SystemClock.elapsedRealtime() >= deadline -> {
                                        closeP2pChannel(channel); toast(getString(R.string.wi_fi_direct_is_still_busy_close_the_other_projection_app))
                                    }
                                    else -> handler.postDelayed({ waitUntilRemoved() }, 200)
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
    }

    private fun refreshStatus() {
        val active = CarPlayBackgroundSession.active
        val running = CarPlayBackgroundSession.hasSession()
        status?.text = when {
            setupError != null -> getString(R.string.setup_needs_attention)
            active -> getString(R.string.carplay_connected)
            running -> getString(R.string.connecting_to_your_iphone)
            DiPlayPreferences.phoneAddress(this) != null -> "${getString(R.string.status_ready_for_prefix)}${DiPlayPreferences.phoneName(this)}"
            else -> getString(R.string.ready_when_you_are)
        }
        // Only show "Open CarPlay" once a session is truly active; while (re)connecting after a
        // connection-mode switch the button shows "Connecting..." so the user does not tap into a
        // not-yet-ready host and see a black screen.
        if (lastRunning != running || lastActive != active) {
            connectButton?.text = when {
                active -> getString(R.string.open_carplay)
                running -> getString(R.string.connecting_to_your_iphone)
                else -> getString(R.string.connect_phone)
            }
            disconnectButton?.visibility = if (active) View.VISIBLE else View.GONE
            disconnectButton?.isEnabled = true
            lastRunning = running
            lastActive = active
        }
        connectButton?.isEnabled = setupError == null
        refreshReconnectBar()
    }
    private fun reportFileName() = "75Play-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}.txt"

    private var preferredReportRoot: File? = null

    override fun chooseReportDestination() {
        // Some head units omit or disable DocumentsUI. Launch itself can throw, before
        // the result callback and the background writer's exception handler ever run.
        if (exportInProgress) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            // Use our own USB volume picker: KitKat DocumentsUI often hides vendor mounts.
            val choices = DiagnosticExportStore.availableUsbVolumes(applicationContext)
            if (choices.isEmpty()) {
                DiPlayDialog.showConfirm(
                    this, getString(R.string.save_diagnostic_report),
                    getString(R.string.report_no_usb_drive),
                    getString(R.string.save_diagnostic_report), getString(R.string.done),
                    onConfirm = { exportDiagnostics() },
                )
            } else {
                val labels = arrayOf(getString(R.string.report_auto_save_locations)) +
                    choices.map { getString(R.string.report_usb_location, it.absolutePath) }
                DiPlayDialog.showSingleChoice(
                    this, getString(R.string.choose_save_location), labels.toList(), 0,
                ) { selected ->
                    preferredReportRoot = choices.getOrNull(selected - 1)
                    exportDiagnostics()
                }
            }
            return
        }
        runCatching { export.launch(reportFileName()) }.onFailure { exportDiagnostics() }
    }

    private fun exportDiagnostics(uri: Uri? = null) {
        if (exportInProgress) return
        exportInProgress = true
        exportButton?.apply { isEnabled = false; text = getString(R.string.saving_report) }
        val appContext = applicationContext
        val fileName = reportFileName()
        val requestedRoot = preferredReportRoot
        preferredReportRoot = null
        Thread({
            val result = runCatching {
                ActiveSessionLog.flush()
                val report = buildString {
                    appendLine("DiPlay ${version()} · private beta diagnostic report")
                    appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
                    appendLine("Head unit: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("--- Last crash ---")
                    appendLine(CrashRecord.report(appContext))
                    appendLine("--- Legacy USB / wireless / TLS provider diagnostics ---")
                    append(LegacyConnectionProbe.report(appContext))
                    appendLine("Connection: ${if (AirPlayPersistence.loadWirelessEnabled(appContext)) "wireless" else "USB"}")
                    appendLine("Authentication: local experimental beta identity; no remote fallback")
                    appendLine("CarPlay setup: ${if (setupError == null) "ready" else "authentication unavailable"}")
                    appendLine("Saved video preference (may differ from active session): ${if (AirPlayPersistence.loadHevcEnabled(appContext)) "HEVC" else "H.264"}; ${AirPlayPersistence.loadFps(appContext)} fps")
                    appendLine("CarPlay size: ${com.shihab.diplay.airplay.CarPlaySize.fromWidthMillimeters(AirPlayPersistence.loadWidthPhysicalMm(appContext)).label}")
                    appendLine("Saved resolution preference (may differ from active session): ${AirPlayPersistence.loadDisplayScalePercent(appContext)}%")
                    appendLine("Session: ${if (CarPlayBackgroundSession.active) "active" else if (CarPlayBackgroundSession.hasSession()) "connecting" else "stopped"}")
                    appendLine("Head-unit board: ${Build.BOARD}; hardware: ${Build.HARDWARE}; build: ${Build.DISPLAY}")
                    appendLine()
                    appendLine("--- Last display negotiation (timestamps distinguish it from current settings) ---")
                    appendLine(DisplayDiagnosticSnapshot.report(appContext))
                    appendLine()
                    appendLine("--- Last received boot and app-launch result ---")
                    appendLine(StartupDiagnosticSnapshot.report(appContext))
                    appendLine("Startup settings: openAfterBoot=${AirPlayPersistence.loadAutoStartOnBoot(appContext)} " +
                        "connectWhenOpened=${DiPlayPreferences.autoConnect(appContext)}")
                    appendLine()
                    appendLine("--- Recent own-app process exits (Android 11+) ---")
                    appendLine(ProcessExitDiagnostics.report(appContext))
                    appendLine()
                    for (name in SessionLogFile.REPORT_NAMES) {
                        val file = File(appContext.filesDir, "logs/$name")
                        if (file.isFile) {
                            appendLine("--- $name ---")
                            file.useLines { lines -> lines.forEach { line -> DiagnosticRedactor.redact(line)?.let { appendLine(it) } } }
                        }
                    }
                }
                val savedReport = if (uri != null) {
                    DiagnosticExportStore.write(appContext.contentResolver, uri, report)
                    DiagnosticExportStore.SavedReport(uri)
                } else DiagnosticExportStore.saveWithoutPicker(appContext, fileName, report, requestedRoot)
                savedReport to report
            }
            runOnUiThread {
                exportInProgress = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                exportButton?.apply { isEnabled = true; text = getString(R.string.save_diagnostic_report) }
                if (result.isSuccess) {
                    val (savedReport, report) = result.getOrThrow()
                    val msg = when {
                        savedReport.savedInApp -> getString(R.string.diagnostic_report_saved_in_app)
                        savedReport.savedPath != null -> getString(R.string.diagnostic_report_saved_to_path, savedReport.savedPath) +
                            if (savedReport.copyPaths.isEmpty()) "\n" + getString(R.string.report_usb_copy_unavailable) else "\n" + getString(R.string.report_usb_copy_paths, savedReport.copyPaths.joinToString("\n"))
                        uri == null -> "Downloads/DiPlay/$fileName"
                        else -> getString(R.string.your_report_was_saved_to_the_selected_location)
                    }
                    DiPlayDialog.showChoices(
                        this, getString(R.string.diagnostic_report_saved), msg, listOf(
                            getString(R.string.view_diagnostic_report) to { showDiagnosticReport(report) },
                            getString(R.string.share) to {
                                runCatching {
                                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_STREAM, savedReport.uri)
                                        clipData = android.content.ClipData.newRawUri(getString(R.string.report_clip_label), savedReport.uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, getString(R.string.share_diagnostic_report)))
                                }.onFailure { showDiagnosticReport(report) }
                            },
                            getString(R.string.done) to {},
                        ),
                    )
                } else {
                    DiPlayDialog.showConfirm(
                        this, getString(R.string.could_not_save_the_report),
                        getString(R.string.check_that_storage_is_available_or_choose_another_save_loc),
                        getString(R.string.choose_location), getString(R.string.close),
                        onConfirm = { chooseReportDestination() },
                    )
                }
            }
        }, "diplay-export").start()
    }

    private fun showDiagnosticReport(report: String) {
        // Some head units (e.g. Allwinner T3, real API 19) choke laying out a multi-hundred-KB
        // TextView: the tap then appears dead. Show only the tail in-app; the full report is
        // already saved to storage by the export step.
        val preview = if (report.length > REPORT_PREVIEW_CHARS) {
            getString(R.string.diplay_report_preview_tail, REPORT_PREVIEW_CHARS / 1024) +
                report.takeLast(REPORT_PREVIEW_CHARS)
        } else {
            report
        }
        val body = column().apply { setPadding(dp(24), dp(12), dp(24), dp(12)) }
        body.addView(label(getString(R.string.diagnostic_report_copy_hint), 14, GlassColors.MUTED))
        try {
            body.addView(label(preview, 13, GlassColors.INK).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            })
        } catch (error: Throwable) {
            // Even the preview can blow the layout budget on very weak devices; fall back to
            // a short notice instead of a dead tap.
            Log.w("DiPlay", "report preview failed", error)
            body.addView(label(getString(R.string.diagnostic_report_preview_unavailable), 14, GlassColors.MUTED))
        }
        runCatching {
            DiPlayDialog.showCustom(
                this, getString(R.string.view_diagnostic_report),
                ScrollView(this).apply { addView(body) }, getString(R.string.close),
            )
        }.onFailure { error ->
            Log.w("DiPlay", "report dialog failed", error)
            toast(getString(R.string.diagnostic_report_preview_unavailable))
        }
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
    private fun openSystem(intent: Intent): Boolean {
        if (runCatching { startActivity(intent) }.isSuccess) return true
        // Stripped head-unit ROMs often drop the standard Settings action even though the Settings app
        // exists; fall back to the explicit component (and a couple of common OEM names).
        val fallbacks = listOf(
            ComponentName("com.android.settings", "com.android.settings.Settings"),
            ComponentName("com.android.settings", "com.android.settings.SettingsActivity"),
            ComponentName("com.flyaudio.settings", "com.flyaudio.settings.activity.MainActivity"),
            ComponentName("com.ww.settings", "com.ww.settings.MainActivity"),
        )
        for (component in fallbacks) {
            if (runCatching { startActivity(Intent().setComponent(component)) }.isSuccess) return true
        }
        toast(getString(R.string.open_this_setting_from_your_car_s_settings_app))
        return false
    }
    private fun toast(message: String) { DiPlayDialog.toast(this, message, true) }

    private fun version() = packageManager.getPackageInfo(packageName, 0).versionName ?: "0.1.0-beta.1"

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun label(value: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    // For an action whose label happens to contain the separator, such as "Turn on … · ADB".
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)); tag = SPACER }

    /**
     * One vertical rhythm for every card, whatever margins each control builder chose:
     * buttons are one height with one gap, a note under a button closes its group with a larger gap,
     * and the last item adds nothing to the card's own padding.
     */
    // Rounded, not truncated: below 160 dpi dp(1) became 0 and every border vanished.
    override fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    /** 环境光传感器（TYPE_LIGHT）LightSource：车机无传感器时 available=false，Controller 回退跟随系统。 */
    private class AmbientLightSource(private val context: Context) : CarPlayNightModeController.LightSource {
        private var manager: SensorManager? = null
        private var sensor: Sensor? = null
        private var listener: SensorEventListener? = null
        override val available: Boolean
            get() {
                if (manager == null) manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
                if (sensor == null) sensor = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)
                return sensor != null
            }
        override fun start(onLux: (Float) -> Unit): Boolean {
            if (sensor == null) return false
            listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    event?.values?.getOrNull(0)?.let { onLux(it) }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            return try {
                manager?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) ?: false
            } catch (t: Throwable) {
                listener = null
                false
            }
        }
        override fun stop() {
            try { manager?.unregisterListener(listener) } catch (t: Throwable) {}
            listener = null
        }
    }

    companion object {
        const val EXTRA_CONNECT_ONLY = "connect_only"
        /** Timestamp of the last deliberate return from the CarPlay screen (three-finger swipe /
         *  floating ball). DiPlayActivity uses it to avoid auto-re-entering the still-alive CarPlay
         *  session for a short window even if onPause cleared userNavigatedFromCarPlay. */
        @JvmStatic var returnedFromCarPlayAt = 0L
        const val ACTION_CONNECTED = "com.shihab.diplay.CONNECTED"
        const val ACTION_CONNECT_CANCEL = "com.shihab.diplay.CONNECT_CANCEL"
        const val ACTION_CONNECT_FAILED = "com.shihab.diplay.CONNECT_FAILED"
        const val ACTION_CONNECT_PROGRESS = "com.shihab.diplay.CONNECT_PROGRESS"
        const val EXTRA_FAILURE_MESSAGE = "failure_message"
        const val EXTRA_CONNECT_STAGE = "connect_stage"
        internal fun isLauncherIntent(intent: Intent): Boolean =
            intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
                !intent.hasExtra("page")
        private const val VEHICLE_VALIDATION_RETRY_MILLIS = 500L
        private const val ADB_KEY_SAVE_WAIT_MILLIS = 500L
        private const val DEFAULT_HOTSPOT_SSID = "75Play"
        private const val DEFAULT_HOTSPOT_PASS = "12345678"
        /** In-app report preview cap: weak head units cannot lay out a huge TextView. */
        private const val REPORT_PREVIEW_CHARS = 100 * 1024
        private const val VALUE_SEPARATOR = " · "
        private const val SEARCH_HIGHLIGHT_MILLIS = 900L
        private const val SPACER = "spacer"
        private const val CONTROL_HEIGHT_DP = 60
        private const val SIDEBAR_DP = 88
        private const val CONTROL_GAP_DP = 12
        private const val GROUP_GAP_DP = 20
        private const val NOTE_LINE_GAP_DP = 4
        private const val NOTE_ATTACH_GAP_DP = 6
        private const val SETTINGS_BLOCK_GAP_DP = 18
    }
}
