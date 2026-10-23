package com.shihab.diplay
import com.shihab.diplay.ui.DiPlayDialog
import com.shihab.diplay.host.CarPlayBackgroundSession
import com.shihab.diplay.host.CarPlayHostWirelessSettings
import com.shihab.diplay.session.CarPlaySessionDisplay
import com.shihab.diplay.host.CarPlaySurfaceBounds
import com.shihab.diplay.host.CarPlayVideoSurfaceMode
import com.shihab.diplay.host.CarPlayVideoSurfaceOwner
import com.shihab.diplay.host.FloatingBall
import com.shihab.diplay.host.HostVideoSurfaces
import com.shihab.diplay.host.HostLogView
import com.shihab.diplay.host.HomeScreenMonitor
import android.graphics.PixelFormat
import com.shihab.diplay.host.ImmersiveBars
import com.shihab.diplay.host.WirelessStartupRecovery
import com.shihab.diplay.host.HostChrome
import com.shihab.diplay.host.HostSessionLifecycle
import com.shihab.diplay.host.backgroundSessionMatchesView
import com.shihab.diplay.host.carPlayVideoSurfaceMode
import com.shihab.diplay.session.smoothVideoDelayMillis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.shihab.diplay.compat.realSdkInt
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.ViewTreeObserver
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import com.shihab.diplay.airplay.CarPlayMediaButton
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplaySettings
import com.shihab.diplay.airplay.AirPlayPhysicalSizeBasis
import com.shihab.diplay.airplay.AirPlayPhysicalSizeMm
import com.shihab.diplay.airplay.CarPlayDisplayScale
import com.shihab.diplay.airplay.CarPlayUiScale
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayIcon
import com.shihab.diplay.airplay.AirPlaySafeArea
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.CarPlayMediaEngine
import com.shihab.diplay.host.R
import com.shihab.diplay.connect.ConnectBroadcasts
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.media.CarPlayTouchMapper
import com.shihab.diplay.media.CarPlayVideoLayout
import com.shihab.diplay.network.CarPlayWirelessService
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupPolicy
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.ManualHotspotBand
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.MfiTarget
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.session.CarPlayRuntimeConfigFactory
import com.shihab.diplay.session.CarPlayRuntimeConfigInputs
import com.shihab.diplay.session.CarPlaySessionFactory
import com.shihab.diplay.util.safeStackTrace
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import com.shihab.diplay.util.AppLog

/**
 * Full-screen CarPlay host. It renders decoded video through a [TextureView], forwards touch to
 * the active AirPlay session, and drives the complete wired or wireless bring-up through
 * [CarPlayController].
 *
 * Apple devices are discovered by vendor ID; CH341 uses the configured VID/PID below.
 */
class CarPlayHostActivity : ComponentActivity() {
    internal var reconnectAttempts = 0
    private val siriKey = WheelSiriKey()
    private val siriKeyPresses = WheelKeyPresses()
    private val legacySiriPresses = mutableSetOf<Triple<Int, Int, Int>>()
    internal val wirelessStartup = WirelessStartupRecovery()
    private val sessionLifecycle = HostSessionLifecycle(this)
    private val hostChrome = HostChrome(this)
    internal var startupFailureGeneration = -1
    internal val startupFailurePresented = java.util.concurrent.atomic.AtomicBoolean(false)
    internal lateinit var airPlayIdentity: AirPlayIdentity
    private var languagePreferenceAtCreate = AppLocale.SYSTEM
    private val wirelessSettings by lazy { CarPlayHostWirelessSettings(wirelessHost) }
    private val wirelessHost = object : CarPlayHostWirelessSettings.Host {
        override fun getString(resId: Int) = this@CarPlayHostActivity.getString(resId)
        override fun getString(resId: Int, vararg formatArgs: Any) =
            this@CarPlayHostActivity.getString(resId, *formatArgs)
        override var wirelessHotspotMode
            get() = this@CarPlayHostActivity.wirelessHotspotMode
            set(value) { this@CarPlayHostActivity.wirelessHotspotMode = value }
        override var wirelessEnabled
            get() = this@CarPlayHostActivity.wirelessEnabled
            set(value) { this@CarPlayHostActivity.wirelessEnabled = value }
        override var hotspotStatus
            get() = this@CarPlayHostActivity.hotspotStatus
            set(value) { this@CarPlayHostActivity.hotspotStatus = value }
        override var manualHotspotSsid
            get() = this@CarPlayHostActivity.manualHotspotSsid
            set(value) { this@CarPlayHostActivity.manualHotspotSsid = value }
        override var manualHotspotPassphrase
            get() = this@CarPlayHostActivity.manualHotspotPassphrase
            set(value) { this@CarPlayHostActivity.manualHotspotPassphrase = value }
        override var manualHotspotBand
            get() = this@CarPlayHostActivity.manualHotspotBand
            set(value) { this@CarPlayHostActivity.manualHotspotBand = value }
        override var manualHotspotChannel
            get() = this@CarPlayHostActivity.manualHotspotChannel
            set(value) { this@CarPlayHostActivity.manualHotspotChannel = value }
        override var manualHotspotSecurity
            get() = this@CarPlayHostActivity.manualHotspotSecurity
            set(value) { this@CarPlayHostActivity.manualHotspotSecurity = value }
        override var existingWifiSsid
            get() = this@CarPlayHostActivity.existingWifiSsid
            set(value) { this@CarPlayHostActivity.existingWifiSsid = value }
        override var existingWifiPassphrase
            get() = this@CarPlayHostActivity.existingWifiPassphrase
            set(value) { this@CarPlayHostActivity.existingWifiPassphrase = value }
    }


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private fun createRuntimeConfig(): CarPlayRuntimeConfig = sessionCreateRuntimeConfig()

    internal fun sessionCreateRuntimeConfig(): CarPlayRuntimeConfig =
        CarPlayRuntimeConfigFactory.build(
            CarPlayRuntimeConfigInputs(
                identity = airPlayIdentity,
                mfiTarget = mfiTarget,
                mfiI2cPath = mfiI2cPath,
                remoteMfiServer = remoteMfiServer,
                remoteMfiToken = remoteMfiToken,
                manufacturer = manufacturer,
                model = model,
                wirelessHotspotMode = wirelessHotspotMode,
                wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(this),
                manualHotspotSsid = manualHotspotSsid,
                manualHotspotPassphrase = manualHotspotPassphrase,
                manualHotspotBand = manualHotspotBand,
                manualHotspotChannel = manualHotspotChannel,
                manualHotspotSecurity = manualHotspotSecurity,
                existingWifiSsid = existingWifiSsid,
                existingWifiPassphrase = existingWifiPassphrase,
                locationReportingEnabled = locationReportingEnabled,
                wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(this),
            ),
        )

    private val wirelessPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            awaitingWirelessPermissions = false
            wirelessPermissionsReady = hasRequiredWirelessPermissions()
            appendLog(
                if (wirelessPermissionsReady) {
                    "Wireless startup permissions granted"
                } else {
                    "Wireless startup permissions denied"
                },
            )
            updateHotspotStatusBlock()
            maybeStartCarPlay()
        }
    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            microphoneAvailable = granted
            microphonePermissionResolved = true
            appendLog(if (granted) "Microphone permission granted" else "Microphone permission denied")
            requestStartupPrerequisites()
        }
    private val locationPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            awaitingLocationPermission = false
            locationPermissionAvailable = hasFineLocationPermission()
            if (locationPermissionAvailable) {
                appendLog("Location permission granted")
            } else if (locationReportingEnabled) {
                // This session only. The shell owns the saved preference.
                locationReportingEnabled = false
                val approximateOnly =
                    grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
                appendLog(
                    if (approximateOnly) {
                        "Precise location permission denied; location reporting disabled"
                    } else {
                        "Location permission denied; location reporting disabled"
                    },
                )
            }
            requestStartupPrerequisites()
        }

    // The full-window viewport stays independent of the letterboxed SurfaceView dimensions.
    internal var videoView: View? = null
    internal var fallbackVideoView: SurfaceView? = null
    private var fallbackVideoBounds: CarPlaySurfaceBounds? = null
    // Smooth video (a setting): SurfaceView output with frames released at the iPhone's frame time.
    internal var smoothVideo = false
    // Direct SurfaceView output: hardware overlay without TextureView composition overhead.
    internal var directSurfaceView = false
    // Sinks whose sessions are being torn down; their decoders may still render to the current surface
    // until they have released their codecs, so a destroyed surface is detached from them too. A restart
    // and a shutdown can overlap, so this is a set.
    internal val retiringSinks = java.util.concurrent.CopyOnWriteArraySet<AndroidMediaSink>()
    internal var sinkReleaseWaitMillis = SINK_RELEASE_WAIT_MILLIS
    private var videoSurfaceProbe: ViewTreeObserver.OnPreDrawListener? = null
    private var pictureBinding: CarPlayPicture.Binding? = null
    internal var picturePanel: View? = null
    internal var picturePanelGeneration = 0
    internal var gestureOverlay: View? = null
    internal var loadingLayer: View? = null
    /** In-host app list opened by the CarPlay OEM button. */
    internal var systemAppsMenu: View? = null
    internal var systemAppsOpen = false
    /** Skip centre-map overlay when Host stops because the driver launched a head-unit app. */
    internal var appsMenuLoadGeneration = 0
    internal var statusView: TextView? = null
    internal var statusScrollView: ScrollView? = null
    internal var stageStatusView: TextView? = null
    internal var stageDiagnosticView: TextView? = null
    internal var stageStartedAtMillis = 0L
    internal var stageWatchdogGeneration = 0
    internal var sink: AndroidMediaSink? = null
    internal var controller: CarPlayController? = null
    private val videoSurfaceOwner = CarPlayVideoSurfaceOwner<Surface>(
        detach = { surface ->
            sink?.clearSurface(SCREEN_TYPE_MAIN, surface)
        },
        release = { it.release() },
    )
    private val hostVideoSurfaces = HostVideoSurfaces(
        owner = videoSurfaceOwner,
        bridge = object : HostVideoSurfaces.Bridge {
            override fun log(message: String) = appendLog(message)
            override fun attach(surface: Surface) = attachSurface(surface)
            override fun layout(width: Int, height: Int) = updateVideoLayout(width, height)
            override fun schedule(width: Int, height: Int) = scheduleDisplaySize(width, height)
            override fun destroyed(): Boolean = isDestroyed
            override fun fallbackHolder(): SurfaceHolder? = fallbackVideoView?.holder
            override fun videoSize(): Pair<Int, Int>? {
                val view = videoView ?: return null
                return view.width to view.height
            }
            override fun smoothVideo(): Boolean = this@CarPlayHostActivity.smoothVideo
            override fun refreshMainPicture() {
                sink?.refreshPicture(SCREEN_TYPE_MAIN)
            }
            override fun framePresented() {
                hideVideoLoading()
            }
            override fun liveSink(): AndroidMediaSink? = sink
            override fun retiringSinks(): Collection<AndroidMediaSink> = retiringSinks
        },
    )
    internal val currentSurface: Surface? get() = hostVideoSurfaces.current
    // Home-screen monitor (optional Usage Access).
    private var homeMonitor: HomeScreenMonitor? = null
    private var homeScreenVisible: Boolean? = null
    private var isActivityStarted = false
    internal var activeDisplaySize: DisplaySize? = null
    internal var pendingDisplaySize: DisplaySize? = null
    internal var sessionDisplay: CarPlaySessionDisplay? = null
    private var touchOutsideContent = false
    internal var displayScalePercent = 100
    private var displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
    private var uiScalePercent = CarPlayUiScale.DEFAULT
    internal var displayDiagnosticAttempt: String? = null
    private var hevcEnabled = true
    internal var hevcSoftwareDecoderEnabled = false
    private val hevcDecoders by lazy { HevcSupport.decoders() }
    /** The codec a session asks for: the HEVC setting falls back to H.264 without a decoder for it (#727). */
    private val sessionHevc: Boolean
        get() = HevcSupport.usable(hevcEnabled, hevcSoftwareDecoderEnabled, hevcDecoders)
    internal var advancedAudioChannelMappingSupported = false
    internal var advancedAudioChannelMapping = false
    internal var navigationStreamType = android.media.AudioManager.STREAM_MUSIC
    private var debugLogsEnabled = false
    private var autoStartOnBoot = false
    private var manufacturer = AirPlayPersistence.DEFAULT_MANUFACTURER
    private var model = AirPlayPersistence.DEFAULT_MODEL
    private var oemLabel = AirPlayPersistence.DEFAULT_OEM_LABEL
    internal var fps = AirPlayDisplaySettings.DEFAULT_FPS
    private var widthPhysicalMm = AirPlayDisplaySettings.DEFAULT_WIDTH_PHYSICAL_MM
    private var physicalSizeBasis = AirPlayDisplaySettings.DEFAULT_PHYSICAL_SIZE_BASIS
    internal var maximumDetectedWidthPixels = 0
    internal var maximumDetectedHeightPixels = 0
    private var rightHandDrive = false
    private var carPlayDock = CarPlayDock.AUTOMATIC
    /** The view areas the next session declares (see createAirPlayConfig). */
    internal var pendingViewAreas: CarPlayViewAreas? = null
    /** The dock the running session declared; null before the first connection. */
    private var sessionDock: CarPlayDock? = null
    internal var hideTopBar = true
    internal var hideBottomBar = true
    private var harmanFullscreenEnabled = false
    private var harmanFullscreenApplied = false
    private var safeAreaDrawOutside = true
    internal var locationReportingEnabled = false
    internal var locationPermissionAvailable = false
    internal var microphoneAvailable = false
    internal var microphonePermissionResolved = false
    internal var wirelessEnabled = false
    private var mfiTarget = MfiTarget.LOCAL
    private var mfiI2cPath = AirPlayPersistence.DEFAULT_MFI_I2C_PATH
    private var remoteMfiServer = ""
    private var remoteMfiToken = ""
    internal var wirelessPermissionsReady = false
    private var wirelessHotspotMode = WirelessHotspotMode.WIFI_P2P
    private var manualHotspotSsid = ""
    private var existingWifiSsid = ""
    private var existingWifiPassphrase = ""
    private var manualHotspotPassphrase = ""
    private var manualHotspotBand = ManualHotspotBand.AUTO
    private var manualHotspotChannel = 0
    private var manualHotspotSecurity = ManualHotspotSecurity.OPEN
    private var awaitingWirelessPermissions = false
    private var awaitingLocationPermission = false
    internal var vpnReady = false
    private var hotspotStatus = CarPlayHostWirelessSettings.HotspotStatus(state = "off")
    internal var latestStage = "Preparing CarPlay"
    private var darkMode = false
    private var carPlayNightMode = CarPlayNightMode.SYSTEM
    private var nightSchedule = CarPlayNightSchedule()
    private var ambientLightThreshold = AmbientLightThreshold()
    private var ambientDelaySeconds = 2
    private var nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
    private val nightModeController by lazy {
        bindCarPlayNightMode(
            context = this,
            initialNight = darkMode,
            onNightChanged = { night ->
                darkMode = night
                        appendLog("CarPlay switched to ${if (night) "night" else "day"} mode")
                logThemeState(nightModeDiagnosticSource, resources.configuration)
                syncAirPlayDarkMode(nightModeDiagnosticSource)
            },
        )
    }
    private val themeDiagnostics = ThemeModeDiagnostics()
    private var lastConfiguration: Configuration? = null
    internal var activeAirPlaySession: AirPlaySession? = null
    internal val activeScreenStreamTypes = mutableSetOf<Int>()
    internal var handshakeResetInProgress = false
    private var startAfterHandshakeReset = false
    internal var reconnectScheduled = false
    internal var sessionLog: SessionLogFile? = null
    private var gestureFingerCount = 3
    private var temporarySystemBarsEnabled = false
    private var temporarySystemBarsVisible = false
    private val hideTemporarySystemBars = Runnable {
        temporarySystemBarsVisible = false
        applyFullscreenMode()
    }
    private var settingsGestureHint: TextView? = null
    private var gestureSequenceActive = false
    private var gestureTracking = false
    private var gestureStartX = 0f
    private var gestureStartY = 0f
    internal val shuttingDown = AtomicBoolean(false)
    /** Set only after [sessionShutdown] wins the shutdown race and will close the executors. */
    internal val sessionTeardownStarted = AtomicBoolean(false)
    // Steering-wheel codes we have not mapped; log each once so a diagnostic report shows what
    // the CS75 wheel actually sends (it emits no AVRCP MEDIA_BUTTON broadcast on this ROM).
    private val loggedUnknownKeys = mutableSetOf<Int>()
    internal val mainHandler = Handler(Looper.getMainLooper())
    private var returnToShellFinish: Runnable? = null
    internal var sidePanel: LinearLayout? = null
    internal var sidePanelBattery: TextView? = null
    internal var sidePanelShown = false
    internal val sidePanelTick = object : Runnable {
        override fun run() {
            refreshSidePanel()
            if (sidePanelShown) mainHandler.postDelayed(this, SIDE_PANEL_REFRESH_MILLIS)
        }
    }
    // P1: single-thread executors must run on daemon threads so they cannot block process exit.
    private val teardownDaemonFactory = ThreadFactory { Thread(it, "carplay-teardown").apply { isDaemon = true } }
    private val airPlayCommandDaemonFactory = ThreadFactory { Thread(it, "carplay-airplay-cmd").apply { isDaemon = true } }
    private val appsCatalogDaemonFactory = ThreadFactory { Thread(it, "carplay-apps-catalog").apply { isDaemon = true } }
    internal val teardownExecutor: ExecutorService = Executors.newSingleThreadExecutor(teardownDaemonFactory)
    private val airPlayCommandExecutor: ExecutorService = Executors.newSingleThreadExecutor(airPlayCommandDaemonFactory)
    internal val appsCatalogExecutor: ExecutorService = Executors.newSingleThreadExecutor(appsCatalogDaemonFactory)
    internal val hostLogView = HostLogView(this, mainHandler)
    // Some head units (e.g. BYD DiLink) update resources.configuration for day/night
    // without delivering onConfigurationChanged, so poll while the activity is visible.
    private val pollConfiguration = object : Runnable {
        override fun run() {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.POLL)
            mainHandler.postDelayed(this, CONFIGURATION_POLL_INTERVAL_MILLIS)
        }
    }
    private val applyDisplaySize = Runnable {
        val size = pendingDisplaySize ?: return@Runnable
        pendingDisplaySize = null
        applyDisplaySize(size)
    }

    private val textureListener = hostVideoSurfaces.textureListener
    private val fallbackSurfaceCallback = hostVideoSurfaces.fallbackCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NavigationWidgetUpdater.attach(applicationContext)
        languagePreferenceAtCreate = AppLocale.preference(this)
        if (runCatching { DiPlayBootstrap.ensure(this, AirPlayPersistence.loadMfiTarget(this)) }.isFailure) {
            startActivity(Intent(this, DiPlayActivity::class.java))
            finish(); return
        }
        WheelKeyService.restoreIfNeeded(this)
        lastConfiguration = Configuration(resources.configuration)
        darkMode = savedInstanceState?.getBoolean("carplay_night_active")
            ?: nightModeOrNull(resources.configuration.uiMode) ?: false
        advancedAudioChannelMappingSupported =
            resources.getBoolean(R.bool.config_advanced_audio_channel_mapping)
        airPlayIdentity = AirPlayPersistence.loadIdentity(this)
        loadPersistedSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        val content = buildContentView()
        setContentView(content)
        AppLocale.applyRootLayoutDirection(this)
        // Open the session log after first layout so KitKat storage I/O is not on the paint path.
        initializeSessionLog()
        installCrashDiagnostics()
        logThemeState(ThemeModeDiagnostics.Source.CREATE, resources.configuration)
        applyFullscreenMode()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (DiPlayDialog.consumeBack()) return
                    if (picturePanel != null) {
                        closePicturePanel()
                    } else if (systemAppsOpen) {
                        closeSystemAppsMenu()
                    } else {
                        showDiPlayHome()
                    }
                }
            },
        )

        appendLog(
            "Host started; MFI target=${mfiTargetLabel(mfiTarget)}; " +
                "transport=${if (wirelessEnabled) "wireless" else "wired"}",
        )
        // If the background CarPlayConnectService is mid-handshake, do NOT adopt-or-build our own
        // controller: that would race it (and double-build). Keep the waiting UI and adopt once its
        // success/failure broadcast lands.
        val connectingViaService = CarPlayConnectService.connecting
        val reusedBackgroundSession = if (connectingViaService) {
            installDeferredAdoptWaiter()
            false
        } else {
            adoptBackgroundSession()
        }
        microphoneAvailable =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        microphonePermissionResolved = microphoneAvailable
        if (reusedBackgroundSession) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // --- Deferred adopt while CarPlayConnectService is mid-handshake -------------------------
    // We were brought to the foreground before the background connection finished. Build no
    // controller; wait for its CONNECTED/FAILED broadcast and adopt the live controller then.
    private var deferredAdoptReceiver: BroadcastReceiver? = null

    private fun installDeferredAdoptWaiter() {
        deferredAdoptReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ConnectBroadcasts.ACTION_CONNECTED -> finishDeferredAdopt()
                    ConnectBroadcasts.ACTION_CONNECT_FAILED -> finish()
                }
            }
        }
        runCatching {
            registerReceiver(
                deferredAdoptReceiver,
                IntentFilter().apply {
                    addAction(ConnectBroadcasts.ACTION_CONNECTED)
                    addAction(ConnectBroadcasts.ACTION_CONNECT_FAILED)
                },
            )
        }
        // The success broadcast may already have been delivered before we registered: adopt now.
        mainHandler.post {
            if (!isDestroyed && !CarPlayConnectService.connecting) finishDeferredAdopt()
        }
    }

    private fun finishDeferredAdopt() {
        unregisterDeferredAdoptReceiver()
        if (isDestroyed) return
        if (adoptBackgroundSession()) {
            updateDebugOverlays()
        } else if (microphonePermissionResolved) {
            requestStartupPrerequisites()
        }
    }

    private fun unregisterDeferredAdoptReceiver() {
        deferredAdoptReceiver?.let { receiver -> try { unregisterReceiver(receiver) } catch (t: Exception) { AppLog.w(TAG, "unregister deferred adopt receiver failed", t) } }
        deferredAdoptReceiver = null
    }

    private fun loadPersistedSettings() {
        carPlayDock = CarPlayDock.load(this)
        carPlayNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        ambientLightThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        ambientDelaySeconds = AirPlayPersistence.loadAmbientDelaySeconds(this)
        nightSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        nightModeController.configure(
            carPlayNightMode,
            nightModeOrNull(resources.configuration.uiMode) ?: false,
            ambientLightThreshold,
            ambientDelaySeconds,
            nightSchedule,
        )
        darkMode = nightModeController.night
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        temporarySystemBarsEnabled = AirPlayPersistence.loadTemporarySystemBars(this)
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        // Size is now chosen only through CarPlaySize; ignore the canvas scale older builds stored.
        uiScalePercent = CarPlayUiScale.DEFAULT
        hevcEnabled = AirPlayPersistence.loadHevcEnabled(this)
        hevcSoftwareDecoderEnabled = false
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        navigationStreamType = AirPlayPersistence.loadNavigationStreamType(this)
        debugLogsEnabled = AirPlayPersistence.loadDebugLogsEnabled(this)
        autoStartOnBoot = AirPlayPersistence.loadAutoStartOnBoot(this)
        manufacturer = AirPlayPersistence.loadManufacturer(this)
        model = AirPlayPersistence.loadModel(this)
        oemLabel = AirPlayPersistence.defaultOemLabel(this)
        fps = AirPlayPersistence.loadFps(this)
        widthPhysicalMm = AirPlayPersistence.loadWidthPhysicalMm(this)
        physicalSizeBasis = AirPlayPersistence.loadPhysicalSizeBasis(this)
        AirPlayPersistence.loadMaximumDetectedDisplay(this).let { (width, height) ->
            maximumDetectedWidthPixels = width
            maximumDetectedHeightPixels = height
        }
        rightHandDrive = AirPlayPersistence.loadRightHandDrive(this)
        hideTopBar = AirPlayPersistence.loadHideTopBar(this)
        hideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        harmanFullscreenEnabled = AirPlayPersistence.loadHarmanFullscreen(this)
        safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this)
        locationPermissionAvailable = hasFineLocationPermission()
        loadConnectionSettings()
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
    }

    /**
     * Connection settings owned by the settings screen ([DiPlayActivity]). It saves them in this
     * process while this screen keeps running, so they are re-read on every resume instead of
     * relying on the snapshot [onCreate] took: otherwise the next handshake would keep using the
     * mode, hotspot and MFI settings that were current when this screen was first opened.
     */
    private fun loadConnectionSettings() {
        wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(this)
        mfiTarget = AirPlayPersistence.loadMfiTarget(this)
        mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(this)
        remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(this)
        remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(this)
        locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(this)
        wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(this)
        existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(this)
        existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(this)
        com.shihab.diplay.session.CarPlayRuntimeConfigFactory.provisionManualHotspot(this)
        manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(this)
        manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(this)
        manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(this)
        manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(this)
        manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(this)
    }

    private fun requestStartupPrerequisites() {
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
            return
        }
        if (wirelessEnabled) {
            requestWirelessPermissions()
        } else {
            requestVpnConsent()
        }
    }

    private fun requestLocationPermission() {
        if (locationPermissionAvailable || awaitingLocationPermission) return
        awaitingLocationPermission = true
        locationPermission.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
        )
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestVpnConsent() {
        // This head unit has no /dev/tun and no VPN consent dialog, and the wireless AirPlay
        // listener binds directly on the SoftAP address — no VPN/tun is ever established, so
        // no consent is needed. Keep the readiness flag so startup proceeds immediately.
        vpnReady = true
        maybeStartCarPlay()
    }

    private fun requestWirelessPermissions() {
        val permissions = requiredWirelessPermissions()
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            wirelessPermissionsReady = true
            updateHotspotStatusBlock()
            maybeStartCarPlay()
            return
        }
        wirelessPermissionsReady = false
        updateHotspotStatusBlock()
        awaitingWirelessPermissions = true
        wirelessPermissions.launch(permissions.toTypedArray())
    }

    private fun hasRequiredWirelessPermissions(): Boolean =
        requiredWirelessPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /** KitKat grants Bluetooth / Wi‑Fi / location at install time; no runtime permission list. */
    private fun requiredWirelessPermissions(): List<String> = emptyList()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        cancelReturnToShellFinish()
        // Save-and-reconnect closes the parked controller, then brings this singleTask host
        // forward. A return-to-shell that has not torn the session down must be able to adopt it.
        if (shuttingDown.get() && !sessionTeardownStarted.get()) {
            shuttingDown.set(false)
        }
        if (shuttingDown.get() && controller?.isClosed() == true) {
            shuttingDown.set(false)
            controller = null
        }
    }

    private fun cancelReturnToShellFinish() {
        returnToShellFinish?.let { mainHandler.removeCallbacks(it) }
        returnToShellFinish = null
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        logThemeState(ThemeModeDiagnostics.Source.START, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        mainHandler.post(pollConfiguration)
        homeMonitor?.stop()
        homeScreenVisible = null
    }

    override fun onResume() {
        super.onResume()
        // CarPlay is in front: hide the floating ball (return to DiPlay via three-finger swipe).
        FloatingBall.hide()
        val savedNightMode = AirPlayPersistence.loadCarPlayNightMode(this)
        val savedThreshold = AirPlayPersistence.loadAmbientLightThreshold(this)
        val savedDelay = AirPlayPersistence.loadAmbientDelaySeconds(this)
        val savedSchedule = AirPlayPersistence.loadCarPlayNightSchedule(this)
        val systemNight = nightModeOrNull(resources.configuration.uiMode) ?: false
        if (savedNightMode != carPlayNightMode || savedThreshold != ambientLightThreshold ||
            savedDelay != ambientDelaySeconds || savedSchedule != nightSchedule) {
            carPlayNightMode = savedNightMode
            ambientLightThreshold = savedThreshold
            ambientDelaySeconds = savedDelay
            nightSchedule = savedSchedule
            nightModeController.configure(carPlayNightMode, systemNight, ambientLightThreshold, ambientDelaySeconds,
                nightSchedule)
        }
        nightModeController.resume(systemNight)
        darkMode = nightModeController.night
        if (activeAirPlaySession != null) {
            syncAirPlayDarkMode(ThemeModeDiagnostics.Source.RESUME)
        }
        displayScalePercent = AirPlayPersistence.loadDisplayScalePercent(this)
        displayScaleTenths = CarPlayDisplayScale.sanitize((displayScalePercent + 5) / 10)
        val languagePreference = AppLocale.preference(this)
        if (realSdkInt() < 33 && languagePreference != languagePreferenceAtCreate) {
            languagePreferenceAtCreate = languagePreference
            recreate()
            return
        }
        // The settings screen returns here with FLAG_ACTIVITY_REORDER_TO_FRONT, so this screen is
        // resumed, not recreated: refresh what that screen can change before it is used again.
        loadConnectionSettings()
        locationPermissionAvailable = hasFineLocationPermission()
        if (locationReportingEnabled && !locationPermissionAvailable) {
            requestLocationPermission()
        }
        wirelessPermissionsReady = !wirelessEnabled || hasRequiredWirelessPermissions()
        advancedAudioChannelMapping =
            advancedAudioChannelMappingSupported &&
                AirPlayPersistence.loadAdvancedAudioChannelMapping(this)
        gestureFingerCount = AirPlayPersistence.loadSettingsGestureFingers(this)
        settingsGestureHint?.text = getString(R.string.open_diplay_settings_hint, gestureFingerCount)
        var systemBarsChanged = false
        val savedHideTopBar = AirPlayPersistence.loadHideTopBar(this)
        val savedHideBottomBar = AirPlayPersistence.loadHideBottomBar(this)
        val savedHarmanFullscreen = AirPlayPersistence.loadHarmanFullscreen(this)
        systemBarsChanged = hideTopBar != savedHideTopBar ||
            hideBottomBar != savedHideBottomBar ||
            harmanFullscreenEnabled != savedHarmanFullscreen
        hideTopBar = savedHideTopBar
        hideBottomBar = savedHideBottomBar
        harmanFullscreenEnabled = savedHarmanFullscreen
        applyFullscreenMode()
        if (systemBarsChanged) refreshDisplaySizeAfterLayout()
        if (!pictureVisible) showVideoLoading() else controller?.reportEveryVideoFrame = false
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                scheduleDisplaySize(view.width, view.height)
            }
            // Refresh the picture when re-entering CarPlay so it does not sit on a black frame.
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
        }
    }
    // Whether the running session's cluster stream was sized for a physical cluster display.

    // DiLink 3 creates its cluster display only after DiPlay first projects to the cluster, which can
    // finish after the session has already asked for the virtual fallback stream.
    // Hardware navigation belongs to the iPhone-rendered CarPlay UI, not Android View focus.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val physicalKey = Triple(event.deviceId, event.keyCode, event.scanCode)
        val downOrUp = event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
        if (downOrUp && physicalKey in legacySiriPresses) {
            if (event.action == KeyEvent.ACTION_UP) {
                legacySiriPresses.remove(physicalKey)
                requestLegacySiri(event.keyCode)
            }
            return true
        }
        // A release/repeat belongs to its original press even if a call, session or setting changed.
        if (downOrUp && siriKeyPresses.hasConsumedPress(physicalKey)) {
            siriKeyPresses.filter(physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0) {
                WheelZoomKeys.Action.PASS
            }
            return true
        }
        if (AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this) &&
            CarPlayRemoteKeys.dispatch(event, controller)) {
            if (event.repeatCount == 0) {
                AppLog.d(
                    TAG,
                    "remote key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action}",
                )
            }
            return true
        }

        // During a CarPlay call the wheel's call key answers on the iPhone instead of opening BYD's phone app.

        // The wheel key service, when it runs, takes an assigned Siri key before this window sees it.
        val assignedKey = WheelZoomSettings.isSiriKey(this, WheelKey.of(event))
        val assignedAction = if (downOrUp) siriKeyPresses.filter(
            physicalKey, event.action == KeyEvent.ACTION_DOWN, event.repeatCount == 0,
        ) {
            if (activeAirPlaySession == null || !assignedKey || inCall(this)) {
                return@filter WheelZoomKeys.Action.PASS
            }
            if (siriKey.opens(event.eventTime)) {
                val message = "Siri: assigned key ${event.keyCode} sent=${controller?.requestSiri() == true}"
                AppLog.i(WheelKeyService.TAG, message)
                appendLog(message)
            }
            WheelZoomKeys.Action.CONSUME
        } else WheelZoomKeys.Action.PASS
        if (assignedAction != WheelZoomKeys.Action.PASS) return true
        if (downOrUp && assignedKey) return super.dispatchKeyEvent(event)

        // Steering-wheel media keys reach the focused window on KitKat ROMs that send no AVRCP
        // MEDIA_BUTTON broadcast: forward them to the iPhone as CarPlay HID presses.
        CarPlayMediaButton.forKeyCode(event.keyCode)?.let { index ->
            if (event.action == KeyEvent.ACTION_UP && event.repeatCount == 0) {
                appendLog("Wheel key ${KeyEvent.keyCodeToString(event.keyCode)} -> HID $index")
                CarPlayMediaKeys.onHardwareMediaButton(index, KeyEvent.keyCodeToString(event.keyCode))
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            synchronized(loggedUnknownKeys) {
                if (loggedUnknownKeys.add(event.keyCode)) {
                    appendLog("Wheel key ${KeyEvent.keyCodeToString(event.keyCode)} not mapped")
                }
            }
        }

        // Keep DiPlay's existing steering-wheel/voice-key Siri handling intact.
        if (!CarPlayMediaButton.opensSiri(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) legacySiriPresses.add(physicalKey)
        if (event.action == KeyEvent.ACTION_UP) {
            requestLegacySiri(event.keyCode)
        }
        return true
    }

    private fun requestLegacySiri(keyCode: Int) {
        val sent = controller?.requestSiri() == true
        appendLog("Siri: voice key $keyCode sent=$sent")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            refreshConfiguration(source = ThemeModeDiagnostics.Source.WINDOW_FOCUS)
            applyFullscreenMode()
            // Returning to CarPlay can leave a stale black surface; ask the sink for a fresh
            // frame so it appears without needing a touch to wake it up.
            videoView?.invalidate()
            sink?.refreshPicture(SCREEN_TYPE_MAIN)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("carplay_night_active", darkMode)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        nightModeController.pause()
        super.onPause()
    }

    override fun onStop() {
        notePicture(false)
        closePicturePanel()
        // The controller, USB/iAP2 link, and VPN attachment intentionally outlive the UI.
        isActivityStarted = false
        logThemeState(ThemeModeDiagnostics.Source.STOP, resources.configuration)
        mainHandler.removeCallbacks(pollConfiguration)
        super.onStop()
        // Leaving the CarPlay screen: show the floating ball (tap=Back, long-press=return to
        // CarPlay), only while its switch is on.
        if (!isFinishing && !isChangingConfigurations &&
            AirPlayPersistence.loadFloatingBallEnabled(this)
        ) {
            FloatingBall.show(
                applicationContext,
                { FloatingBall.back() },
                { FloatingBall.backToCarPlay(applicationContext) },
            )
        }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshConfiguration(newConfig, ThemeModeDiagnostics.Source.CALLBACK)
        applyFullscreenMode()
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        hostLogView.scrollLogsToBottom()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        applyFullscreenMode()
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    override fun onDestroy() {
        notePicture(false)
        // Returning to the shell keeps the session. A destroy that is not sessionShutdown must
        // drop this Activity as owner so it cannot reconnect on a dead context.
        if (!sessionTeardownStarted.get()) {
            sessionLifecycle.parkForShell()
            airPlayCommandExecutor.shutdown()
            appsCatalogExecutor.shutdown()
            teardownExecutor.shutdown()
        }
        unregisterDeferredAdoptReceiver()
        resetSidePanel()
        nightModeController.pause()
        pictureBinding?.close()
        pictureBinding = null
        removeVideoSurfaceProbe()
        fallbackVideoView?.holder?.removeCallback(fallbackSurfaceCallback)
        homeMonitor?.stop()
        mainHandler.removeCallbacks(applyDisplaySize)
        mainHandler.removeCallbacks(hostLogView.expireOldLogLines)
        mainHandler.removeCallbacks(pollConfiguration)
        cancelReturnToShellFinish()
        videoSurfaceOwner.clear()
        hostVideoSurfaces.forgetTexture()
        fallbackVideoView = null
        fallbackVideoBounds = null
        sessionLog?.append("Activity destroyed")
        sessionLog?.close()
        if (crashDiagnosticLog === sessionLog) crashDiagnosticLog = null
        if (ActiveSessionLog.current === sessionLog) ActiveSessionLog.current = null
        sessionLog = null
        // P2: drop any remaining posted callbacks/messages on the main handler as a final safety net.
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun buildContentView(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val video = TextureView(this).apply {
            isOpaque = false
            surfaceTextureListener = textureListener
        }
        pictureBinding = CarPlayPicture.Binding(video)
        val gestureLayer = View(this).apply {
            isClickable = true
            setOnTouchListener { view, event -> onHostTouch(view, event) }
        }
        val loading = com.shihab.diplay.host.CarPlayLoadingLayer(this)
        stageStatusView = loading.status
        stageStatusView?.text = hostChrome.friendlyStage(latestStage)
        stageStatusView?.maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
        root.addView(video, FrameLayout.LayoutParams(-1, -1))
        root.addView(loading, FrameLayout.LayoutParams(-1, -1))
        loading.visibility = View.GONE
        root.addView(gestureLayer, FrameLayout.LayoutParams(-1, -1))
        // Above the gesture layer: OEM button opens a centered app popup (apps load on open).
        systemAppsMenu = buildSystemAppsMenu(emptyList()).apply { visibility = View.GONE }
        root.addView(systemAppsMenu, FrameLayout.LayoutParams(-1, -1))
        videoView = video
        smoothVideo = AirPlayPersistence.loadSmoothVideo(this)
        directSurfaceView = AirPlayPersistence.loadDirectSurfaceView(this)
        observeVideoWindow(video)
        gestureOverlay = gestureLayer
        loadingLayer = loading
        updateDebugOverlays()
        return root
    }

    internal fun wantsVideoFrameNotifications(): Boolean = !pictureVisible

    // DiPlay's side panel: placed over the part of the stream CarPlay leaves black (placeSidePanel), so it
    // follows the video when it is letterboxed; touches elsewhere still reach the gesture layer and CarPlay.
    private fun buildSidePanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            visibility = View.GONE
            setBackgroundColor(Color.rgb(16, 16, 18))
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        panel.addView(android.widget.TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            textSize = 72f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        sidePanelBattery = TextView(this).apply {
            textSize = 30f
            setTextColor(Color.rgb(200, 200, 205))
            gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(32))
        }
        panel.addView(sidePanelBattery)
        panel.addView(Button(this).apply {
            text = getString(R.string.side_panel_full_screen)
            textSize = 22f
            isAllCaps = false
            setOnClickListener { showSidePanel(false) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))
        return panel
    }

    // The panel's strip of the stream, mapped through the video's layout in the window.
    private fun placeSidePanel(viewWidth: Int, viewHeight: Int) {
        val panel = sidePanel ?: return
        val display = sessionDisplay ?: return
        val strip = display.viewAreas?.let { it.panelRect(it.current) } ?: return
        val content = contentRect(viewWidth, viewHeight)
        val scaleX = content.width / display.width
        val scaleY = content.height / display.height
        val left = Math.round(content.left + strip.originX * scaleX)
        val top = Math.round(content.top + strip.originY * scaleY)
        panel.layoutParams = FrameLayout.LayoutParams(
            Math.round(content.left + (strip.originX + strip.width) * scaleX) - left,
            Math.round(content.top + (strip.originY + strip.height) * scaleY) - top,
            // Stream coordinates are physical pixels, independent of the UI's reading direction.
            Gravity.TOP or Gravity.LEFT,
        ).apply { leftMargin = left; topMargin = top }
    }

    private fun showSidePanel(show: Boolean) {
        val areas = sessionDisplay?.viewAreas ?: return
        val view = videoView ?: return
        val split = isMultiWindowActive()
        val portrait = if (split) screenPortrait() else view.height > view.width
        val target = if (show) areas.sidePanel(portrait) else areas.indexFor(view.width, view.height, split, portrait)
        val sent = target != null && controller?.showViewArea(target) == true
        appendLog("Side panel ${if (show) "shown" else "hidden"}: view area $target sent=$sent")
        if (!sent || target == null) return
        areas.use(target)
        sidePanelShown = show
        sidePanel?.visibility = if (show) View.VISIBLE else View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
        if (show) sidePanelTick.run()
        updateVideoLayout(view.width, view.height)
    }

    internal fun resetSidePanel() {
        sidePanelShown = false
        sidePanel?.visibility = View.GONE
        mainHandler.removeCallbacks(sidePanelTick)
    }

    // The battery shows only where DiPlay already reads it for the iPhone.
    private fun refreshSidePanel() {
        sidePanelBattery?.text = ""
    }

    internal fun mfiTargetLabel(target: MfiTarget): String = when (target) {
        MfiTarget.LOCAL -> getString(R.string.local_offline)
        MfiTarget.I2C -> getString(R.string.i2c)
        MfiTarget.REMOTE -> getString(R.string.remote)
    }

    internal fun manualHotspotSettingsError(): String? = wirelessSettings.manualHotspotSettingsError()
    internal fun updateHotspotStatus(status: CarPlayStatus) = wirelessSettings.updateHotspotStatus(status)
    private fun updateHotspotStatusBlock() = wirelessSettings.updateHotspotStatusBlock()

    private data class CanvasSupport(val supported: Boolean, val reason: String, val details: String)

    private fun largerCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport =
        if (maxOf(display.widthPixels, display.heightPixels) > 3840 || minOf(display.widthPixels, display.heightPixels) > 2160) {
            CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
        } else decoderCanvasSupport(display)

    private fun decoderCanvasSupport(display: AirPlayDisplayConfig): CanvasSupport = try {
        if (realSdkInt() < 21) {
            CanvasSupport(false, "legacy_decoder_capabilities", "Keep the native screen canvas on Android 4.4")
        } else {
        val mime = if (sessionHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        // Match MediaCodec.createDecoderByType's first suitable decoder; do not silently force
        // an enlarged stream through a software decoder on a slower head unit.
        val decoder = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull {
            !it.isEncoder && it.supportedTypes.any { type -> type.equals(mime, ignoreCase = true) }
        }
        if (decoder == null) {
            CanvasSupport(false, "no_decoder", "Decoder capability mime=$mime result=no_decoder")
        } else {
            val hardware = if (realSdkInt() >= 29) decoder.isHardwareAccelerated
                else !decoder.name.startsWith("OMX.google.") && !decoder.name.startsWith("c2.android.")
            val video = decoder.getCapabilitiesForType(mime).videoCapabilities
            val sizeSupported = video?.isSizeSupported(display.widthPixels, display.heightPixels) == true
            val rateSupported = sizeSupported && video?.areSizeAndRateSupported(
                display.widthPixels, display.heightPixels, display.fps.toDouble()) == true
            val reason = when {
                !hardware -> "software_decoder"
                sessionHevc && hevcSoftwareDecoderEnabled -> "software_hevc_selected"
                video == null -> "no_video_capabilities"
                !sizeSupported -> "canvas_dimensions_unsupported"
                !rateSupported -> "frame_rate_unsupported"
                else -> "supported"
            }
            CanvasSupport(reason == "supported", reason,
                "Decoder capability codec=${decoder.name} mime=$mime hardware=$hardware " +
                    "sizeSupported=$sizeSupported rateSupported=$rateSupported " +
                    "widths=${video?.supportedWidths} heights=${video?.supportedHeights} " +
                    "alignment=${video?.widthAlignment}x${video?.heightAlignment} " +
                    "fpsRange=${video?.supportedFrameRates} result=$reason")
        }
        }
    } catch (error: Exception) {
        CanvasSupport(false, "capability_query_${error.javaClass.simpleName}",
            "Decoder capability query failed error=${error.javaClass.simpleName}")
    }

    private fun createAirPlayConfig(size: DisplaySize): AirPlayConfig = sessionCreateAirPlayConfig(size)

    internal fun sessionCreateAirPlayConfig(size: DisplaySize): AirPlayConfig {
        // The home settings page can change the name while this host stays alive.
        oemLabel = AirPlayPersistence.defaultOemLabel(this)
        val safeWidth = (size.width / 2 * 2).coerceAtLeast(2)
        val safeHeight = (size.height / 2 * 2).coerceAtLeast(2)
        val alignedSize = DisplaySize(safeWidth, safeHeight)
        val physical = resolvePhysicalSize(alignedSize)
        val knobPrimary = AndroidTvInputMode.shouldUseKnobAsPrimaryInput(this)
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = alignedSize.width,
            heightPixels = alignedSize.height,
            widthPhysicalMm = physical.widthMm,
            heightPhysicalMm = physical.heightMm,
            fps = fps,
            // Tell CarPlay to use its native knob/focus model on Android TV and other non-touch
            // hosts. Touch-capable head units remain touchscreen-primary.
            primaryInputDevice = if (knobPrimary) 3 else 1,
        )
        appendLog(
            "CarPlay primary input=${if (knobPrimary) "knob" else "touch"} " +
                "tv=${AndroidTvInputMode.isTelevision(this)} " +
                "touchscreen=${resources.configuration.touchscreen}",
        )
        val requestedResolutionPercent = displayScalePercent
        val requestedResolutionDisplay = CarPlayDisplayScale.applyPercent(baseDisplay, requestedResolutionPercent)
        var resolutionDisplay = requestedResolutionDisplay
        val requestedPercent = uiScalePercent
        var scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
        val candidate = scaledDisplay
        var support = when {
            uiScalePercent < CarPlayUiScale.DEFAULT && scaledDisplay === resolutionDisplay ->
                CanvasSupport(false, "canvas_4k_limit", "Decoder capability check skipped: canvas exceeds enlargement limit")
            uiScalePercent < CarPlayUiScale.DEFAULT || scaledDisplay.widthPixels > baseDisplay.widthPixels ||
                scaledDisplay.heightPixels > baseDisplay.heightPixels -> largerCanvasSupport(scaledDisplay)
            else -> CanvasSupport(true, "not_enlarging", "Decoder capability enlargement check not required")
        }
        var smallerUiFallback = false
        var resolutionFallback = false
        if (!support.supported && uiScalePercent < CarPlayUiScale.DEFAULT) {
            val failedCanvas = support
            smallerUiFallback = true
            uiScalePercent = CarPlayUiScale.DEFAULT
            AirPlayPersistence.saveUiScalePercent(this, uiScalePercent)
            scaledDisplay = resolutionDisplay
            appendLog("Larger CarPlay canvas unavailable reason=${failedCanvas.reason}; using Default icon and text size")
            // Removing the smaller-controls enlargement may leave a separately enlarged resolution.
            if (displayScalePercent > 100) {
                val resolutionSupport = largerCanvasSupport(scaledDisplay)
                support = resolutionSupport.copy(
                    reason = if (resolutionSupport.supported) failedCanvas.reason else resolutionSupport.reason,
                    details = "${failedCanvas.details}\n${resolutionSupport.details}",
                )
            }
        }
        if (!support.supported && displayScalePercent > 100) {
            resolutionFallback = true
            displayScalePercent = 100
            displayScaleTenths = CarPlayDisplayScale.DEFAULT_TENTHS
            AirPlayPersistence.saveDisplayScalePercent(this, displayScalePercent)
            AirPlayPersistence.saveDisplayScaleTenths(this, displayScaleTenths)
            resolutionDisplay = baseDisplay
            scaledDisplay = CarPlayUiScale.apply(resolutionDisplay, uiScalePercent)
            appendLog("Enlarged CarPlay resolution unavailable reason=${support.reason}; using 100%")
        }
        if (smallerUiFallback || resolutionFallback) {
            runOnUiThread {
                DiPlayDialog.toast(this,
                    getString(if (resolutionFallback) R.string.resolution_canvas_unsupported
                        else R.string.this_head_unit_cannot_use_the_smaller_size_at_this_resolut), true)
            }
        }
        appendLog("CarPlay size=${CarPlayUiScale.label(uiScalePercent)} canvas=${scaledDisplay.widthPixels}x${scaledDisplay.heightPixels}")
        val display = scaledDisplay.copy(
            safeArea = AirPlaySafeArea.toInsets(
                mapping = AirPlayPersistence.resolveSafeAreaRect(this, size.width, size.height),
                activityWidthPixels = size.width,
                activityHeightPixels = size.height,
                displayWidthPixels = scaledDisplay.widthPixels,
                displayHeightPixels = scaledDisplay.heightPixels,
            ),
            safeAreaDrawOutside = AirPlayPersistence.loadSafeAreaDrawOutside(this),
        )
        // A fixed dock, split-screen support or a turning screen declares several areas the car switches
        // between live. A session that starts in split screen sizes its canvas to that window, so it keeps
        // the plain canvas and one area for it.
        val inSplitScreen = isMultiWindowActive()
        val dock = CarPlayDock.load(this).also { sessionDock = it }
        // The whole screen and CarPlay's full window in each orientation: the system bars take their
        // place in both, so a remembered split window is sized against the window of its orientation.
        val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
        val screenLong = maxOf(screen.widthPixels, screen.heightPixels)
        val screenShort = minOf(screen.widthPixels, screen.heightPixels)
        val (landscapeWindow, portraitWindow) = CarPlayRotation.turnedWindows(size.width, size.height,
            screen.widthPixels, screen.heightPixels)
        // Before DiPlay has seen a split window, expect one from the screen, its bars and Android's divider.
        val statusBar = systemDimension("status_bar_height")
        val navigationBar = systemDimension("navigation_bar_height")
        val divider = (systemDimension("docked_stack_divider_thickness") -
            2 * systemDimension("docked_stack_divider_insets")).coerceAtLeast(0)
        val splitWindow: (Boolean) -> Pair<Float, Float>? = { portrait ->
            if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
                val window = if (portrait) portraitWindow else landscapeWindow
                val expected = SplitScreenSettings.expectedWindow(portrait, screenLong, screenShort,
                    statusBar, navigationBar, divider)
                SplitScreenSettings.ofWindow(SplitScreenSettings.window(this, portrait, expected),
                    if (portrait) screenShort else screenLong, if (portrait) screenLong else screenShort,
                    window.first, window.second)
            } else null
        }
        val longPixels = maxOf(display.widthPixels, display.heightPixels)
        val square = if (CarPlayRotation.enabled(this) && !inSplitScreen) {
            CarPlayRotation.squareSide(longPixels, CarPlayRotation.picture(this), sessionHevc, hevcSoftwareDecoderEnabled)
        } else null
        val canvas = if (square == null) display else {
            // The square keeps the plain canvas's pixel density, so CarPlay's scale (and "CarPlay size")
            // stays the same in both orientations; it has no room for the custom safe area.
            val longMm = if (display.widthPixels >= display.heightPixels) display.widthPhysicalMm else display.heightPhysicalMm
            val squareMm = longMm?.let { (it.toLong() * square / longPixels).toInt() }
            display.copy(widthPixels = square, heightPixels = square, widthPhysicalMm = squareMm,
                heightPhysicalMm = squareMm, viewArea = null, safeArea = null)
        }
        val viewAreas = if (square == null) {
            CarPlayViewAreas.build(display.widthPixels, display.heightPixels, listOf(
                CarPlayViewAreas.Screen(display.widthPixels, display.heightPixels, portrait = display.heightPixels > display.widthPixels),
            ), dock, splitWindow, startPortrait = display.heightPixels > display.widthPixels,
                sidePanel = SidePanelSettings.enabled(this), rightHandDrive = rightHandDrive)
        } else {
            val (landscape, portrait) = CarPlayRotation.turningAreas(square, size.width, size.height,
                screen.widthPixels, screen.heightPixels)
            CarPlayViewAreas.build(square, square, listOf(
                CarPlayViewAreas.Screen(landscape.first, landscape.second, portrait = false),
                CarPlayViewAreas.Screen(portrait.first, portrait.second, portrait = true),
            ), dock, splitWindow, startPortrait = size.height > size.width, sidePanel = SidePanelSettings.enabled(this),
                rightHandDrive = rightHandDrive)
        }
        pendingViewAreas = viewAreas
        val declared = if (viewAreas == null) canvas else canvas.copy(viewAreas = viewAreas.areas, initialViewArea = viewAreas.current)
        if (square != null) appendLog("Turning screen: square canvas ${square}x$square, areas ${viewAreas?.areas}")
        if (SplitScreenSettings.enabled(this) && !inSplitScreen) {
            appendLog("Split screen expected from status bar=$statusBar navigation bar=$navigationBar divider=$divider px")
        }
        val requestSummary = "Display request selected=${CarPlayUiScale.label(requestedPercent)} percent=$requestedPercent " +
            "surface=${size.width}x${size.height} resolution=${requestedResolutionPercent}% " +
            "base=${requestedResolutionDisplay.widthPixels}x${requestedResolutionDisplay.heightPixels} " +
            "candidate=${candidate.widthPixels}x${candidate.heightPixels} fps=$fps " +
            "codec=${if (sessionHevc) "HEVC" else "H.264"} softwareHevc=$hevcSoftwareDecoderEnabled"
        val effectiveSummary = "Display effective percent=$uiScalePercent resolution=${displayScalePercent}% " +
            "canvas=${display.widthPixels}x${display.heightPixels} decision=${support.reason} " +
            "physical=${physical.widthMm}x${physical.heightMm}mm safeArea=${display.safeArea} " +
            "drawOutside=${display.safeAreaDrawOutside}"
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.begin(this, requestSummary, support.details, effectiveSummary)
        appendLog(requestSummary)
        appendLog(support.details)
        appendLog(effectiveSummary)
        return AirPlayConfig(
            deviceName = "75Play",
            deviceId = DiPlayBootstrap.deviceId(airPlayIdentity),
            btMac = DiPlayBluetooth.localAddress(this) ?: DiPlayBootstrap.deviceId(airPlayIdentity),
            sourceVersion = "950.7.1",
            main = declared,
            cluster = null,
            rightHandDrive = rightHandDrive,
            hevc = sessionHevc,
            microphone = microphoneAvailable,
            microphoneOpus = com.shihab.diplay.media.OpusEncoderSupport.isAvailable(),
            // iOS Wireless CarPlay mandates Opus in the offered audio formats; without it the
            // iPhone skips audio stream negotiation entirely (video-only SETUP) and keeps music
            // on the phone. API19 has no system Opus decoder, but the iPhone actually sends
            // AAC (which we decode) when Opus is declared — so advertise Opus regardless.
            opusAudioOutput = true,
            manufacturer = normalizedManufacturer(),
            model = normalizedModel(),
            oemLabel = AirPlayPersistence.defaultOemLabel(this),
            icons = listOf(loadAirPlayIcon()),
            videoInCar = false,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(this),
        )
    }

    private fun loadAirPlayIcon(): AirPlayIcon =
        // CarPlay OEM entry: opens the head-unit app list. Packaged app-grid icon +
        // oemIconLabel ("车机应用"); iOS always shows this slot.
        CarPlaySessionFactory.loadAirPlayIcon(this)

    private fun currentActivitySize(): DisplaySize? {
        val view = videoView
        if (view != null && view.width > 0 && view.height > 0) {
            return DisplaySize(view.width, view.height)
        }
        return activeDisplaySize
    }

    private fun resolvePhysicalSize(size: DisplaySize): AirPlayPhysicalSizeMm =
        AirPlayDisplaySettings.resolvePhysicalSizeMm(
            currentWidthPixels = size.width,
            currentHeightPixels = size.height,
            maximumWidthPixels = maxOf(maximumDetectedWidthPixels, size.width),
            maximumHeightPixels = maxOf(maximumDetectedHeightPixels, size.height),
            referenceMillimeters = widthPhysicalMm,
            basis = physicalSizeBasis,
        )

    private fun refreshDisplaySizeAfterLayout() {
        videoView?.post {
            val view = videoView ?: return@post
            scheduleDisplaySize(view.width, view.height)
        }
    }

    private fun normalizedManufacturer(): String =
        manufacturer.trim().ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }

    private fun normalizedModel(): String =
        model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

    private fun adoptBackgroundSession(): Boolean = sessionLifecycle.adopt()


    private fun startCarPlay(size: DisplaySize) = sessionLifecycle.start(size)

    private fun createSessionListener(controllerGeneration: Int) =
        sessionLifecycle.createSessionListener(controllerGeneration)

    private fun createStatusReporter(controllerGeneration: Int) =
        sessionLifecycle.createStatusReporter(controllerGeneration)


    private fun refreshConfiguration(
        newConfig: Configuration = resources.configuration,
        source: ThemeModeDiagnostics.Source,
    ) {
        if (lastConfiguration == newConfig) {
            logThemeState(source, newConfig)
            return
        }
        // resources.configuration is mutated in place, so keep a copy to compare against.
        lastConfiguration = Configuration(newConfig)
        nightModeDiagnosticSource = source
        try {
            nightModeOrNull(newConfig.uiMode)?.let { nightModeController.systemChanged(it) }
        } finally {
            nightModeDiagnosticSource = ThemeModeDiagnostics.Source.CARPLAY_MODE
        }
        logThemeState(source, newConfig)
    }

    internal fun logThemeState(source: ThemeModeDiagnostics.Source, configuration: Configuration) {
        themeDiagnostics.observe(
            source, configuration.uiMode, darkMode, activeAirPlaySession != null, SystemClock.elapsedRealtime(),
        )?.let(::appendLog)
    }

    internal fun syncAirPlayDarkMode(source: ThemeModeDiagnostics.Source) {
        val session = activeAirPlaySession
        val night = nightModeController.night
        darkMode = night
        if (session == null) {
            appendLog("THEME_DIAGNOSTIC request source=${source.label} applied=${if (night) "dark" else "light"} sessionActive=false")
            return
        }
        airPlayCommandExecutor.execute {
            try {
                val sent = session.setNightMode(night)
                // A successful write does not prove the iPhone changed its appearance.
                appendLog(
                    "THEME_DIAGNOSTIC send source=${source.label} applied=${if (night) "dark" else "light"} commandWritten=$sent",
                )
                AppLog.i(
                    TAG,
                    "AirPlay dark mode=${if (night) "dark" else "light"} eventChannelReady=$sent",
                )
            } catch (error: Throwable) {
                val failureClass = error.javaClass.simpleName.take(64)
                    .filter { it.isLetterOrDigit() || it == '_' || it == '$' }
                    .ifEmpty { "unknown" }
                appendLog("THEME_DIAGNOSTIC send source=${source.label} failureClass=$failureClass")
                AppLog.w(TAG, "Could not send AirPlay dark mode update", error)
            }
        }
    }

    internal fun audioCaptureDirectory(): File? {
        if (!File(filesDir, AUDIO_CAPTURE_MARKER).isFile) return null
        return File(filesDir, AUDIO_CAPTURE_DIRECTORY)
    }

    private fun scheduleDisplaySize(width: Int, height: Int) = sessionScheduleDisplaySize(width, height)

    internal fun sessionScheduleDisplaySize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || shuttingDown.get()) return
        val size = DisplaySize(width, height)
        if (size == pendingDisplaySize) return
        mainHandler.removeCallbacks(applyDisplaySize)
        if (size == activeDisplaySize && !displayLayoutChanged()) {
            pendingDisplaySize = null
            // A rotation back can cancel the last pending resize after teardown has finished.
            maybeStartCarPlay()
            return
        }
        pendingDisplaySize = size
        // With a turning screen's square canvas nothing reconnects, so the turn need not wait for the size to settle.
        mainHandler.postDelayed(applyDisplaySize,
            if (sessionDisplay?.viewAreas?.turnsWithScreen == true) 0L else DISPLAY_CHANGE_DEBOUNCE_MILLIS)
    }

    internal fun isMultiWindowActive(): Boolean {
        return false
    }


    private fun applyDisplaySize(size: DisplaySize) {
        val display = sessionDisplay
        if (display != null && applyViewArea(size, display)) return
        val layoutChanged = displayLayoutChanged(size)
        if (shuttingDown.get()) return
        if (size == activeDisplaySize && !layoutChanged) {
            // Teardown may have removed the session that made this same-size rotation pending.
            maybeStartCarPlay()
            return
        }
        val previous = activeDisplaySize
        activeDisplaySize = size
        recordDetectedMaximum(size)
        if (previous == null) {
            appendLog("Display detected: ${size.width}x${size.height}")
            maybeStartCarPlay()
        } else if (handshakeResetInProgress) {
            appendLog(
                "Display updated while handshake is reset: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        } else if (controller == null && display == null) {
            appendLog(
                "Display updated before CarPlay startup: " +
                    "${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
            maybeStartCarPlay()
        } else if (display != null && !layoutChanged &&
            size.width <= display.windowWidth && size.height <= display.windowHeight) {
            // Keep camera shrink/restore cycles within the original window connected. If the
            // session started in a camera window, growth beyond it needs a full-size canvas.
            val message = "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}; " +
                "keeping CarPlay session canvas=${display.width}x${display.height}"
            appendLog(message)
            AppLog.i(TAG, message)
            videoView?.let { updateVideoLayout(it.width, it.height) }
        } else {
            restartCarPlay(
                "Display changed ${previous.width}x${previous.height} -> ${size.width}x${size.height}",
            )
        }
    }

    /**
     * Entering or leaving the head unit's split screen, or turning the screen with a square canvas, moves
     * CarPlay to the matching view area instead of reconnecting. The split window is remembered per screen
     * orientation for the next session's area. False when the change is something else (a camera window,
     * a turn without a square canvas), which the usual path handles.
     */
    private fun applyViewArea(size: DisplaySize, display: CarPlaySessionDisplay): Boolean {
        val areas = display.viewAreas ?: return false
        if (controller == null || handshakeResetInProgress || shuttingDown.get()) return false
        val split = isMultiWindowActive()
        // A full window's own shape tells the screen's orientation; during a turn the display metrics and
        // rotation can lag behind the window. A split-screen window can have either shape, so it asks the
        // display, which is settled then.
        val portrait = if (split) screenPortrait() else size.height > size.width
        val previous = activeDisplaySize
        if (areas.turnsWithScreen && display.rotation != displayRotation() && size == previous) {
            // The turn arrived before the window's new size: note it and wait for the size, which picks
            // the area, instead of letting the old rotation look like a changed layout.
            sessionDisplay = display.copy(rotation = displayRotation())
            AppLog.i(TAG, "Screen turned before the window resized (${size.width}x${size.height}); waiting for its size")
            return true
        }
        val turned = previous != null && !split && (previous.height > previous.width) != portrait
        if (turned && !areas.turnsWithScreen) return false
        if (split) {
            // Against the whole screen, which the system bars (shown in BYD's split screen) do not change.
            val screen = android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }
            val screenLong = maxOf(screen.widthPixels, screen.heightPixels).toFloat()
            val screenShort = minOf(screen.widthPixels, screen.heightPixels).toFloat()
            if (screenLong > 0 && screenShort > 0) {
                if (portrait) SplitScreenSettings.saveWindow(this, true, size.width / screenShort, size.height / screenLong)
                else SplitScreenSettings.saveWindow(this, false, size.width / screenLong, size.height / screenShort)
            }
        }
        val target = areas.indexFor(size.width, size.height, split, portrait) ?: return false
        if (sidePanelShown) resetSidePanel() // a turn or the split screen ends the side panel
        // The same whole-screen area without a turn (a camera window, say) keeps the usual handling.
        if (!turned && target == areas.current && areas.kindOf(target) == CarPlayViewAreas.Kind.FULL_SCREEN) return false
        activeDisplaySize = size
        sessionDisplay = display.copy(rotation = displayRotation())
        val sent = if (target == areas.current) true else controller?.showViewArea(target) == true
        if (sent) areas.use(target)
        videoView?.let { updateVideoLayout(it.width, it.height) }
        val message = "Window ${previous?.width}x${previous?.height} -> ${size.width}x${size.height} " +
            "split=$split portrait=$portrait turned=$turned: view area $target sent=$sent"
        appendLog(message)
        AppLog.i(TAG, message)
        return true
    }

    /** Whether the screen itself is portrait now (DiPlay's own window may be a split-screen part of it). */
    @Suppress("DEPRECATION")
    private fun screenPortrait(): Boolean {
        val metrics = android.util.DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.heightPixels > metrics.widthPixels
    }

    @Suppress("DEPRECATION")
    internal fun displayRotation(): Int = videoView?.display?.rotation ?: windowManager.defaultDisplay.rotation

    private fun displayLayoutChanged(newSize: DisplaySize? = activeDisplaySize): Boolean {
        val display = sessionDisplay ?: return false
        if (display.rotation != displayRotation()) return true
        if (display.hideTopBar != hideTopBar || display.hideBottomBar != hideBottomBar) return true
        if (newSize != null && newSize.width > 0 && newSize.height > 0) {
            val baseAspect = display.width.toDouble() / display.height
            val currentAspect = newSize.width.toDouble() / newSize.height
            val aspectDiff = kotlin.math.abs(currentAspect / baseAspect - 1.0)
            if (aspectDiff > 0.08 && AirPlayPersistence.loadAdaptPipResolution(this)) {
                return true
            }
        }
        return false
    }



    private fun contentRect(viewWidth: Int, viewHeight: Int): CarPlayVideoLayout {
        val display = sessionDisplay ?: return CarPlayVideoLayout(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        // In the head unit's split screen, or on a turning screen's square canvas, CarPlay draws in part of
        // the canvas; that area fills the window and the rest falls outside it. Video and touches both
        // follow this. An area of the whole canvas keeps the usual fit.
        display.viewAreas?.let { areas ->
            val area = areas.layoutArea(areas.current)
            if (area.width != display.width || area.height != display.height) {
                val scaleX = viewWidth.toFloat() / area.width
                val scaleY = viewHeight.toFloat() / area.height
                return CarPlayVideoLayout(-area.originX * scaleX, -area.originY * scaleY,
                    display.width * scaleX, display.height * scaleY)
            }
        }
        return CarPlayVideoLayout.fit(display.width, display.height, viewWidth, viewHeight)
    }

    private fun updateVideoLayout(viewWidth: Int, viewHeight: Int) = sessionUpdateVideoLayout(viewWidth, viewHeight)

    internal fun sessionUpdateVideoLayout(viewWidth: Int, viewHeight: Int) {
        val view = videoView ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return
        val content = contentRect(viewWidth, viewHeight)
        val fallback = fallbackVideoView
        if (fallback != null) {
            val bounds = CarPlaySurfaceBounds.from(content)
            if (fallbackVideoBounds != bounds) {
                fallbackVideoBounds = bounds
                fallback.layoutParams = FrameLayout.LayoutParams(bounds.width, bounds.height,
                    Gravity.TOP or Gravity.LEFT).apply {
                    leftMargin = bounds.left
                    topMargin = bounds.top
                }
            }
        } else {
            (view as? TextureView)?.setTransform(Matrix().apply {
                setScale(content.width / viewWidth, content.height / viewHeight)
                postTranslate(content.left, content.top)
            })
        }
        if (sidePanelShown) placeSidePanel(viewWidth, viewHeight)
    }

    private fun recordDetectedMaximum(size: DisplaySize) {
        val width = maxOf(maximumDetectedWidthPixels, size.width)
        val height = maxOf(maximumDetectedHeightPixels, size.height)
        if (width == maximumDetectedWidthPixels && height == maximumDetectedHeightPixels) return
        maximumDetectedWidthPixels = width
        maximumDetectedHeightPixels = height
        AirPlayPersistence.saveMaximumDetectedDisplay(this, width, height)
    }

    private fun maybeStartCarPlay() = sessionLifecycle.maybeStart()


    private fun reconnectAfterLoss(reason: String, startupFailure: WirelessStartupFailure? = null) =
        sessionLifecycle.reconnectAfterLoss(reason, startupFailure)


    /** Full-stack fallback when an AirPlay-only reconnect is unavailable. */
    private fun restartCarPlay(reason: String) = sessionLifecycle.restart(reason)


    private fun showDiPlayHome(page: String = "home") {
        controller?.sendTouch(emptyList())
        // No FLAG_ACTIVITY_REORDER_TO_FRONT on KitKat i.MX6: it conflicts with the home-screen
        // relaunch and bounces DiPlay back to the launcher when opening CarPlay.
        startActivity(Intent(this, DiPlayActivity::class.java).putExtra("page", page))
    }

    /** Keep a timed-out sink visible to surface teardown until its codecs have actually been released. */
    internal fun closeRetiringSink(owner: AndroidMediaSink) {
        owner.close()
        val owners = retiringSinks
        owner.whenVideoReleased { owners.remove(owner) }
        // Preserve bounded teardown sequencing without blocking the UI or forgetting a live worker.
        owner.awaitVideoReleased(sinkReleaseWaitMillis)
    }

    internal fun launchHeadUnitApp(app: CarAppCatalog.App) {
        // Opening another app backgrounds Host; do not cover it with the centre-map card.
        try {
            app.launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(app.launch)
        } catch (e: Exception) {
            appendLog("Could not launch ${app.label}: ${e.javaClass.simpleName}")
        }
    }

    private fun buildSystemAppsMenu(apps: List<CarAppCatalog.App>): View =
        hostChrome.buildSystemAppsMenu(apps)

    private fun replaceSystemAppsMenu(apps: List<CarAppCatalog.App>) =
        hostChrome.replaceSystemAppsMenu(apps)

    internal fun openSystemAppsMenu() = hostChrome.openSystemAppsMenu()

    private fun closeSystemAppsMenu() = hostChrome.closeSystemAppsMenu()

    private fun closePicturePanel() = hostChrome.closePicturePanel()

    /** Leave CarPlay and show the DiPlay shell. Settings are edited there, never in Host. */
    private fun returnToDiPlay() {
        sessionLifecycle.parkForShell()
        cancelTemporarySystemBars()
        closeSystemAppsMenu()
        controller?.sendTouch(emptyList())
        // Bring DiPlay forward first, then finish this host shortly after so the same task
        // does not flash a black frame (DiPlay is already in the back stack).
        FloatingBall.backToDiPlay(this)
        cancelReturnToShellFinish()
        val finishHost = Runnable {
            returnToShellFinish = null
            if (!isFinishing && !isDestroyed) finish()
        }
        returnToShellFinish = finishHost
        mainHandler.postDelayed(finishHost, 150L)
    }

    /**
     * Startup cannot recover on this host. The failure dialog lives on the shell.
     */
    internal fun returnAfterStartupFailure(message: String) {
        if (!startupFailurePresented.compareAndSet(false, true)) return
        // Leave the controller callback before closing it.
        mainHandler.post {
            if (isDestroyed) return@post
            appendLog("startup failed; returning to DiPlay: $message")
            val openShell: () -> Unit = {
                runCatching {
                    startActivity(
                        Intent(this, DiPlayActivity::class.java)
                            .putExtra("page", "home")
                            .putExtra(com.shihab.diplay.connect.ConnectBroadcasts.EXTRA_FAILURE_MESSAGE, message)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                }
                runCatching { if (!isFinishing) finish() }
                Unit
            }
            if (sessionTeardownStarted.get()) {
                openShell()
                return@post
            }
            runCatching { sessionShutdown(false, "startup retries exhausted", openShell) }
                .onFailure { openShell() }
        }
    }

    private fun shutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) =
        sessionShutdown(terminateProcess, reason, completion)

    internal fun sessionShutdown(terminateProcess: Boolean, reason: String, completion: () -> Unit = {}) {
        if (!shuttingDown.compareAndSet(false, true)) { completion(); return }
        sessionTeardownStarted.set(true)
        resetSidePanel()
        wirelessStartup.disconnected()
        wirelessStartup.bumpGeneration()
        mainHandler.removeCallbacks(applyDisplaySize)
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        AppLog.i(TAG, "shutdown reason=$reason terminateProcess=$terminateProcess")
        teardownExecutor.execute {
            oldController?.close()
            val clean = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            oldSink?.let(::closeRetiringSink)
            airPlayCommandExecutor.shutdown()
            appsCatalogExecutor.shutdown()
            if (terminateProcess) {
                applicationContext.stopService(Intent(applicationContext, CarPlayWirelessService::class.java))
            }
            AppLog.i(TAG, "shutdown complete clean=$clean")
            applicationContext.stopService(Intent(applicationContext, DiPlaySessionService::class.java))
            teardownExecutor.shutdown()
            mainHandler.post { completion() }
            if (terminateProcess) Process.killProcess(Process.myPid())
        }
    }

    private fun observeVideoWindow(texture: TextureView) {
        val probe = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!texture.isAttachedToWindow) return true
                removeVideoSurfaceProbe()
                if (isDestroyed || videoView !== texture) return true
                val mode = carPlayVideoSurfaceMode(texture.isHardwareAccelerated, smoothVideo, directSurfaceView)
                appendLog("Video output mode=$mode windowHardwareAccelerated=${texture.isHardwareAccelerated} " +
                    "smoothVideo=$smoothVideo directSurfaceView=$directSurfaceView")
                if (mode == CarPlayVideoSurfaceMode.TEXTURE) return true
                useFallbackVideoSurface(texture)
                return false // Measure the replacement before drawing the software window.
            }
        }
        videoSurfaceProbe = probe
        texture.viewTreeObserver.addOnPreDrawListener(probe)
    }

    private fun removeVideoSurfaceProbe() {
        val probe = videoSurfaceProbe ?: return
        videoView?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(probe)
        videoSurfaceProbe = null
    }

    private fun useFallbackVideoSurface(texture: TextureView) {
        val root = texture.parent as? FrameLayout ?: return
        val index = root.indexOfChild(texture)
        val viewport = FrameLayout(this).apply { clipChildren = true }
        val surfaceView = SurfaceView(this)
        pictureBinding?.close()
        pictureBinding = null
        // Detach our wrapper before removing its TextureView; a late destruction callback
        // must not clear the framework-owned replacement surface.
        videoSurfaceOwner.clear()
        hostVideoSurfaces.forgetTexture()
        texture.surfaceTextureListener = null
        root.removeView(texture)
        videoView = viewport
        fallbackVideoView = surfaceView
        surfaceView.holder.addCallback(fallbackSurfaceCallback)
        viewport.addView(surfaceView, FrameLayout.LayoutParams(-1, -1))
        viewport.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val width = right - left
            val height = bottom - top
            updateVideoLayout(width, height)
            if (width != oldRight - oldLeft || height != oldBottom - oldTop) {
                scheduleDisplaySize(width, height)
            }
        }
        root.addView(viewport, index, texture.layoutParams)
        showVideoLoading()
        appendLog(when {
            smoothVideo ->
                "Using SurfaceView video output: smooth video, frames shown at the iPhone's frame time + a delay " +
                    "starting at ${smoothVideoDelayMillis(fps)} ms; picture adjustments unavailable"
            directSurfaceView ->
                "Using SurfaceView video output: direct hardware overlay (low CPU/GPU overhead); picture adjustments unavailable"
            else ->
                "Using SurfaceView video output: window has no hardware acceleration; picture adjustments unavailable"
        })
    }

    /** A dimension of the platform's own resources in pixels, or 0 when this build has none by [name]. */
    private fun systemDimension(name: String): Int {
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    internal fun attachSurface(surface: Surface) {
        sink?.setSurface(SCREEN_TYPE_MAIN, surface)
        // SoftAP CS75 Plus: main surface only; no cluster/alt stream.
    }

    /** Temporary reveal without persisting bar visibility or changing AirPlay's negotiated size. */
    private fun showTemporarySystemBars(): Boolean {
        if (!temporarySystemBarsEnabled || gestureFingerCount == 0 ||
            harmanFullscreenEnabled || isMultiWindowActive() || (!hideTopBar && !hideBottomBar)) {
            return false
        }
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        temporarySystemBarsVisible = true
        applyFullscreenMode()
        mainHandler.postDelayed(hideTemporarySystemBars, 5_000L)
        return true
    }

    private fun cancelTemporarySystemBars() {
        mainHandler.removeCallbacks(hideTemporarySystemBars)
        if (temporarySystemBarsVisible) {
            temporarySystemBarsVisible = false
            applyFullscreenMode()
        }
    }

    private fun onHostTouch(view: View, event: MotionEvent): Boolean {
        if (systemAppsOpen) return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureSequenceActive = false
                gestureTracking = false
                // Some head-unit panels report every finger on the first DOWN, with no POINTER_DOWN.
                if (beginReturnSwipe(event)) return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (beginReturnSwipe(event)) return true
            }
        }

        if (gestureSequenceActive) {
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP || event.pointerCount != gestureFingerCount) {
                gestureTracking = false
            }
            if (!gestureTracking || event.pointerCount != gestureFingerCount) {
                if (event.actionMasked == MotionEvent.ACTION_UP ||
                    event.actionMasked == MotionEvent.ACTION_CANCEL
                ) {
                    gestureSequenceActive = false
                    gestureTracking = false
                } else if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
                    gestureTracking = false
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                val deltaX = Math.abs(pointerCentroid(event, horizontal = true) - gestureStartX)
                val deltaY = pointerCentroid(event, horizontal = false) - gestureStartY
                val swipeDistance = dp(SETTINGS_SWIPE_DISTANCE_DP)
                if (deltaY >= swipeDistance && deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO) {
                    // Swipe down with N fingers: return to the DiPlay shell (not Host settings).
                    gestureSequenceActive = false
                    gestureTracking = false
                    returnToDiPlay()
                    return true
                }
                if (deltaY <= -swipeDistance && -deltaY >= deltaX * SETTINGS_SWIPE_DIRECTION_RATIO) {
                    // Swipe up with N fingers: briefly reveal hidden system bars, else back to DiPlay.
                    gestureSequenceActive = false
                    gestureTracking = false
                    if (!showTemporarySystemBars()) FloatingBall.backToDiPlay(this)
                    return true
                }
            }
            return true
        }

        val content = contentRect(view.width, view.height)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchOutsideContent = !content.contains(event.x, event.y)
        }
        if (touchOutsideContent) {
            // Ignore the entire touch sequence when it starts in a letterbox bar.
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touchOutsideContent = false
            }
            return true
        }
        val contacts = CarPlayTouchMapper.contacts(event, content)
        val queued = controller?.sendTouch(contacts) ?: false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> AppLog.i(
                TAG,
                "touch action=${MotionEvent.actionToString(event.actionMasked)} " +
                    "pointers=${event.pointerCount} queued=$queued",
            )
        }
        return true
    }

    /** Start a return swipe once [gestureFingerCount] fingers are down. Already tracking: no-op. */
    private fun beginReturnSwipe(event: MotionEvent): Boolean {
        if (gestureSequenceActive || event.pointerCount != gestureFingerCount) return false
        gestureSequenceActive = true
        gestureTracking = true
        gestureStartX = pointerCentroid(event, horizontal = true)
        gestureStartY = pointerCentroid(event, horizontal = false)
        controller?.sendTouch(emptyList())
        appendLog("DiPlay return swipe tracking started; fingers=$gestureFingerCount")
        return true
    }

    private fun pointerCentroid(event: MotionEvent, horizontal: Boolean): Float {
        var total = 0f
        for (index in 0 until event.pointerCount) {
            total += if (horizontal) event.getX(index) else event.getY(index)
        }
        return total / event.pointerCount
    }

    internal fun onScreenStreamStateChanged(generation: Int, type: Int, active: Boolean) {
        runOnUiThread {
            if (shuttingDown.get() || generation != wirelessStartup.generation) return@runOnUiThread
            if (active) {
                activeScreenStreamTypes.add(type)
            } else {
                activeScreenStreamTypes.remove(type)
            }
            appendLog("Screen stream state: type=$type active=$active generation=$generation")
            if (type == SCREEN_TYPE_ALT) {
                // Cluster/alt stream removed for SoftAP CS75 Plus.
            }
            updateDebugOverlays()
        }
    }


    internal fun setConnectionStage(message: String, exact: Boolean = false) =
        hostChrome.setConnectionStage(message, exact)

    internal fun setFailedConnectionStage(message: String) =
        hostChrome.setFailedConnectionStage(message)

    internal fun updateDebugOverlays() = hostChrome.updateDebugOverlays()

    /** Ask for another frame. The picture stays on the host surface; no spinner covers it. */
    internal fun showVideoLoading() {
        notePicture(false)
        controller?.reportEveryVideoFrame = true
        loadingLayer?.visibility = View.GONE
    }

    internal fun hideVideoLoading() {
        notePicture(true)
        controller?.reportEveryVideoFrame = false
        loadingLayer?.visibility = View.GONE
    }

    private fun notePicture(visible: Boolean) {
        val me = System.identityHashCode(this)
        if (visible) {
            pictureOwner = me
            pictureVisible = true
        } else if (pictureOwner == me || pictureOwner == 0) {
            pictureVisible = false
            if (pictureOwner == me) pictureOwner = 0
        }
    }

    internal fun appendLog(message: String) {
        val safe = DiagnosticRedactor.redact(message) ?: return
        sessionLog?.append(formattedLogLine(safe, System.currentTimeMillis()))
    }

    internal fun appendFileLog(message: String) {
        sessionLog?.append(formattedLogLine(message, System.currentTimeMillis()))
    }

    internal fun formattedLogLine(message: String, nowMillis: Long): String =
        "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(nowMillis))}  $message"

    private fun initializeSessionLog() {
        val logFile = File(File(filesDir, "logs"), "diplay.log")
        val activeLog = SessionLogFile(logFile)
        runCatching {
            activeLog.reset(
                "DiPlay log started " +
                    "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())} " +
                    "pid=${Process.myPid()} path=${logFile.absolutePath}",
            )
        }
        sessionLog = activeLog
        crashDiagnosticLog = activeLog
        ActiveSessionLog.current = activeLog
    }

    /**
     * Records uncaught exceptions into the diagnostic log before the process dies, so
     * crash-class issues can be triaged from the exported report without adb. The previous
     * default handler still runs, preserving the normal crash flow. See issue #106.
     */
    private fun installCrashDiagnostics() {
        if (Thread.getDefaultUncaughtExceptionHandler() is DiPlayCrashHandler) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(DiPlayCrashHandler(previous))
    }

    private class DiPlayCrashHandler(
        private val next: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        private val reported = AtomicBoolean(false)

        override fun uncaughtException(thread: Thread, error: Throwable) {
            if (reported.compareAndSet(false, true)) {
                runCatching {
                    val trace = error.safeStackTrace().lineSequence().take(40).joinToString("\n")
                    crashDiagnosticLog?.append(
                        "FATAL thread=${thread.name} " +
                            "${error.javaClass.name}: ${error.message}\n$trace",
                    )
                }
            }
            runCatching { next?.uncaughtException(thread, error) }
        }
    }

    private var systemUiVisibilityListenerInstalled = false

    // CarPlay 会话期间周期重隐系统栏：部分车机 ROM 在下拉状态栏后不自动恢复隐藏，
    // 由心跳每 1.5s 强制压一次（用 FLAG_FULLSCREEN 级全屏，保证导航栏不露出）。
    private val carPlayBarGuard = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            if (activeAirPlaySession == null || temporarySystemBarsVisible || isMultiWindowActive()) return
            ImmersiveBars.applyToWindow(window, force = true)
            mainHandler.postDelayed(this, 1500)
        }
    }

    internal fun syncFullscreenChrome() = applyFullscreenMode()

    private fun applyFullscreenMode() {
        val multiWindow = isMultiWindowActive()
        if (harmanFullscreenEnabled) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            // Apply the OEM-specific bit last; the standard insets controller does not preserve it.
            HarmanFullscreenCompat.apply(window)
            harmanFullscreenApplied = true
            return
        }
        if (harmanFullscreenApplied) {
            HarmanFullscreenCompat.clear(window)
            harmanFullscreenApplied = false
        }
        // While a CarPlay session is live, force fullscreen regardless of the toggles so the
        // CarPlay picture always hides the status + navigation bars, like the system car UI.
        val carPlayActive = activeAirPlaySession != null
        // A three-finger upward swipe can briefly reveal hidden bars even during a live session.
        val peekBars = temporarySystemBarsVisible
        val hideTop = ((hideTopBar || carPlayActive) && !peekBars) && !multiWindow
        val hideBottom = ((hideBottomBar || carPlayActive) && !peekBars) && !multiWindow
        val immersive = hideTop || hideBottom
        if (hideTop && hideBottom) {
            // Full sticky immersive: share the same flags/window path as the shell.
            ImmersiveBars.applyToWindow(window, force = true)
        } else {
            // KitKat / Changan OS_S311-WT: one bar hidden still needs sticky immersive layout.
            ImmersiveBars.applyPartial(window, hideTop, hideBottom)
        }

        if (!systemUiVisibilityListenerInstalled) {
            systemUiVisibilityListenerInstalled = true
            @Suppress("DEPRECATION")
            window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
                if (isFinishing || isDestroyed) return@setOnSystemUiVisibilityChangeListener
                if (isMultiWindowActive()) return@setOnSystemUiVisibilityChangeListener
                // CarPlay 会话激活时强制全屏（见 applyFullscreenMode）：无论设置开关如何，
                // 下拉状态栏/导航栏意外显示都必须立即重新隐藏，不能遮挡 CarPlay 画面。
                val carPlayActiveNow = activeAirPlaySession != null
                val wantHide = hideTopBar || hideBottomBar || carPlayActiveNow
                // 三指上滑临时显示系统栏期间（5 秒）不强制隐藏，超时后由 hideTemporarySystemBars 恢复。
                if (!wantHide || temporarySystemBarsVisible) return@setOnSystemUiVisibilityChangeListener
                @Suppress("DEPRECATION")
                val navShowing = visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
                @Suppress("DEPRECATION")
                val statusShowing = visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0
                if ((hideBottomBar || carPlayActiveNow) && navShowing) {
                    window.decorView.postDelayed({ applyFullscreenMode() }, 200)
                } else if ((hideTopBar || carPlayActiveNow) && statusShowing) {
                    window.decorView.postDelayed({ applyFullscreenMode() }, 200)
                }
            }
        }
        // 心跳重隐：CarPlay 会话期间周期压导航栏，对抗下拉状态栏后 ROM 不自动恢复隐藏。
        mainHandler.removeCallbacks(carPlayBarGuard)
        if (carPlayActive && immersive) mainHandler.postDelayed(carPlayBarGuard, 1500)
    }

    internal fun dp(value: Int): Int = com.shihab.diplay.ui.LayoutMetrics.dp(this, value)

    internal fun CarPlayStatus.describe(): String =
        CarPlayConnectText.describe(this@CarPlayHostActivity, this, wirelessHotspotMode, wirelessEnabled)

    internal companion object {
        /** A frame is on the host surface right now. Cleared when that host's surface goes away. */
        @JvmStatic var pictureVisible = false
        private var pictureOwner = 0
        const val SIDE_PANEL_REFRESH_MILLIS = 5_000L
        const val TAG = "xcertplay-usb"
        const val SCREEN_TYPE_MAIN = 110
        const val SCREEN_TYPE_ALT = 111
        // On the teardown thread: bounded sequencing wait. Release notification retains ownership beyond
        // this budget when a codec is still busy.
        const val SINK_RELEASE_WAIT_MILLIS = 2_000L
        private const val CENTER_MAP_IDLE_MILLIS = 3_000L // a reconnect is quicker; a session end is not
        const val DISPLAY_CHANGE_DEBOUNCE_MILLIS = 500L
        const val CONFIGURATION_POLL_INTERVAL_MILLIS = 2_000L
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val AUDIO_CAPTURE_MARKER = "audio-capture.enabled"
        const val AUDIO_CAPTURE_DIRECTORY = "audio-captures"
        /**
         * Process-global target for [DiPlayCrashHandler]. Kept off the activity instance so the
         * default uncaught-exception handler never retains a destroyed activity. See issue #106.
         */
        @Volatile
        private var crashDiagnosticLog: SessionLogFile? = null
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
        const val SETTINGS_SWIPE_DISTANCE_DP = 40
        const val SETTINGS_SWIPE_DIRECTION_RATIO = 1.05f
        val MENU_BACKGROUND = Color.rgb(12, 16, 19)
        val MENU_SECONDARY = Color.rgb(170, 180, 190)
        val MENU_ACCENT = Color.rgb(127, 205, 154)
        val MENU_ACCENT_TRACK = Color.rgb(78, 143, 102)
        val MENU_TRACK_OFF = Color.rgb(64, 74, 80)
        val MENU_BUTTON_TEXT = Color.rgb(8, 17, 11)
        val MENU_DANGER = Color.rgb(190, 45, 45)
        val NO_VIDEO_BACKGROUND = Color.rgb(0x16, 0x16, 0x18)
    }

    internal data class DisplaySize(val width: Int, val height: Int)
}

