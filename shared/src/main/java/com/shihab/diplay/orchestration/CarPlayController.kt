package com.shihab.diplay.orchestration

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import com.shihab.diplay.compat.BluetoothCompat
import com.shihab.diplay.media.musicVolumeWarning
import com.shihab.diplay.bluetooth.BluetoothAddressResolver
import com.shihab.diplay.bluetooth.CarPlayBluetoothManager
import com.shihab.diplay.bluetooth.HybridBluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.shihab.diplay.airplay.AirPlayListenerIdentity
import com.shihab.diplay.airplay.AirPlayTcpAccepted
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayContact
import com.shihab.diplay.airplay.AirPlayKnobState
import com.shihab.diplay.airplay.AirPlayDeviceInfo
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlayMediaHandler
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.PairingStore
import com.shihab.diplay.airplay.VideoInCar
import com.shihab.diplay.airplay.VideoPlaybackDelivery
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.mfi.RemoteMfiAuthenticationClient
import com.shihab.diplay.mfi.LocalMfiAuthenticationClient
import com.shihab.diplay.network.CarPlayBonjour
import com.shihab.diplay.network.diagnosticSummary
import com.shihab.diplay.network.CarPlayWirelessService
import com.shihab.diplay.network.HotspotAddressPolicy
import com.shihab.diplay.network.ManualHotspotManager
import com.shihab.diplay.network.WifiScanPause
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.network.WirelessHotspotInfo
import com.shihab.diplay.network.WirelessHotspotBackend
import com.shihab.diplay.network.WirelessHotspotManager
import com.shihab.diplay.network.WirelessInterfaceDiagnostics
import com.shihab.diplay.network.WirelessReceiveDiagnostics
import com.shihab.diplay.network.WirelessStartupPolicy
import com.shihab.diplay.model.WirelessStartupException
import com.shihab.diplay.model.WirelessStartupFailure
import com.shihab.diplay.network.WirelessStartupDiagnostics
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LocationProvider
import com.shihab.diplay.util.safeStackTrace
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessIdentification
import com.shihab.diplay.transport.Iap2WirelessLinkRole
import com.shihab.diplay.transport.forWirelessLink
import com.shihab.diplay.transport.LinuxI2cTransport
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.shihab.diplay.util.AppLog

sealed class CarPlayStatus {
    data object DiscoveringMfi : CarPlayStatus()
    data object WaitingForMfi : CarPlayStatus()
    data object RequestingMfiPermission : CarPlayStatus()
    data object MfiReady : CarPlayStatus()
    data object StartingHotspot : CarPlayStatus()
    data class HotspotReady(
        val ssid: String,
        val band: String,
        val channel: Int,
        val bssid: String,
        val address: String,
        val backend: String,
    ) : CarPlayStatus()
    data object WaitingForPairedIphone : CarPlayStatus()
    data object ConnectingBluetooth : CarPlayStatus()
    data object RunningWireless : CarPlayStatus()
    data object WirelessActive : CarPlayStatus()
    data object WirelessActiveFallback : CarPlayStatus()
    data object DiscoveringIphone : CarPlayStatus()
    data object WaitingForIphone : CarPlayStatus()
    data object RequestingIphonePermission : CarPlayStatus()
    data object WaitingForReenumeration : CarPlayStatus()
    data object SelectingConfiguration : CarPlayStatus()
    data object OpeningDataPaths : CarPlayStatus()
    data object Pairing : CarPlayStatus()
    data object ConnectingControl : CarPlayStatus()
    data object AttachingNetwork : CarPlayStatus()
    data object RunningControl : CarPlayStatus()
    data object ControlEnded : CarPlayStatus()
    data class Failed(val message: String, val wifiResetRequired: Boolean = false,
        val startupFailure: WirelessStartupFailure? = null) : CarPlayStatus()
}

internal fun isWirelessHandoffInProgress(
    handoffRequested: Boolean,
    tunnelActive: Boolean,
    sessionActive: Boolean,
    startSessionSent: Boolean = false,
): Boolean = handoffRequested || tunnelActive || sessionActive || startSessionSent

/**
 * Wires the complete wired or wireless CarPlay path: MFi coprocessor discovery, iPhone bring-up,
 * iAP2 control, transport setup, and the AirPlay media/input sessions.
 *
 * Reusable lifecycle entry points: [start], [close] (stop), [reconnectMfi], [reconnectIphone].
 * Hosts keep a direct reference to this class.
 *
 * All blocking USB/I2C work runs on one worker executor. Status callbacks are delivered on the
 * main thread. This class is the integration seam only and is not evidence of hardware operation.
 */
@android.annotation.SuppressLint("MissingPermission")
class CarPlayController(
    context: Context,
    private val config: CarPlayRuntimeConfig,
    private val airPlayConfig: AirPlayConfig,
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
    listener: AirPlaySessionListener,
    private val media: AirPlayMediaHandler,
    reportStatus: (CarPlayStatus) -> Unit,
    private val locationProvider: Iap2LocationProvider? = null,
    private val vehicleStatusProvider: com.shihab.diplay.transport.VehicleStatusProvider? = null,
) : Closeable {
    init {
        require(!config.locationReportingEnabled || locationProvider != null) {
            "A location provider is required when location reporting is enabled"
        }
        WifiScanPause.restoreIfNeeded(context.applicationContext)
    }

    private enum class Phase { IDLE, MFI, WIRELESS, WIRED }

    private val appContext = context.applicationContext
    private val diagnosticAttempt = diagnosticAttempts.incrementAndGet()
    private val diagnosticRun = AtomicInteger()
    private val bluetoothAdapter = BluetoothCompat.getAdapter(appContext)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { Thread(it, "diplay-carplay-main").apply { isDaemon = true } },
    )
    private val touchExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { Thread(it, "diplay-carplay-touch").apply { isDaemon = true } },
    )
    private val tunnelExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        ThreadFactory { Thread(it, "diplay-carplay-tunnel").apply { isDaemon = true } },
    )
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    @Volatile private var uiListener: AirPlaySessionListener? = listener
    /**
     * The decoder reports "first frame rendered" once per output surface. After the first surface of
     * a session, [wirelessSessionListener] would otherwise drop that event. Set while the host is
     * still covered and waiting for a picture.
     */
    @Volatile var reportEveryVideoFrame: Boolean = false
    @Volatile private var uiStatusReporter: ((CarPlayStatus) -> Unit)? = reportStatus
    private val availabilityPollGeneration = AtomicInteger(0)
    @Volatile private var lastReportedStatus: CarPlayStatus? = null

    @Volatile private var closed = false
    @Volatile private var phase = Phase.IDLE
    @Volatile private var mfiSession: MfiSession? = null
    @Volatile private var csm: Iap2Session? = null
    @Volatile private var activeSession: AirPlaySession? = null
    // Wired (USB) CarPlay state, wired alongside the wireless path below. All fields are owned
    // by the single-threaded executor; they are only torn down by close().
    @Volatile private var wiredIphoneHost: com.shilapi.xcertplay.transport.IphoneUsbHost? = null
    private val wiredAborted = AtomicBoolean(false)
    private val wiredGeneration = AtomicInteger(0)
    private val wiredTcpArrived = AtomicBoolean(false)
    private val wiredPermissionPollGeneration = AtomicInteger(0)
    @Volatile private var wiredUsbSession: com.shilapi.xcertplay.transport.Iap2UsbSession? = null
    @Volatile private var wiredNcm: com.shilapi.xcertplay.transport.NcmUsbBridge? = null
    @Volatile private var wiredLwip: com.shilapi.xcertplay.network.LwipNcmBridge? = null
    @Volatile private var wiredAirPlayServer: java.net.ServerSocket? = null
    @Volatile private var wiredAirPlaySession: AirPlaySession? = null
    private val wiredHostId = java.util.UUID.randomUUID().toString().uppercase(java.util.Locale.US)
    private val wiredSystemBuid = java.util.UUID.randomUUID().toString().uppercase(java.util.Locale.US)
    @Volatile private var requestedDashboardUrl: String? = airPlayConfig.cluster?.initialUrl
    private val clusterUiLock = Any()
    @Volatile private var clusterUiStream: Pair<AirPlaySession, Int>? = null
    @Volatile private var clusterUiShown = true
    // Immutable snapshots keep accessibility key filtering away from the network-writing UI lock.
    @Volatile private var clusterUiVisibility: Pair<Pair<AirPlaySession, Int>, Boolean>? = null
    @Volatile private var dashboardMapOutputVisible = false
    private val dashboardMapEpoch = AtomicInteger()
    private val playbackStatus = com.shihab.diplay.media.CarPlayPlaybackStatus()

    /** Told when the iPhone starts or stops playing media; may run on any thread. */
    @Volatile var playbackListener: ((Boolean) -> Unit)? = null

    /** Told when retained iPhone now-playing metadata changes; may run on any thread. */
    @Volatile var nowPlayingListener: ((com.shihab.diplay.media.CarPlayNowPlaying) -> Unit)? = null

    /** Told when an iAP2 Now Playing artwork transfer completes; may run on the link worker. */
    @Volatile var artworkListener: ((Int, ByteArray) -> Unit)? = null

    /** Video in car; set before [start] to offer it to the iPhone (with AirPlayConfig.videoInCar). */
    @Volatile var videoListener: CarPlayVideoListener? = null
    @Volatile private var videoGate: VideoInCarGate? = null

    /** Answers the iPhone on a video in car remote control session; a network write, any thread. */
    fun sendVideoMessage(streamId: Long, message: Map<String, Any?>): Boolean =
        activeSession?.sendRemoteControlMessage(streamId, message) ?: false

    @Volatile private var hotspot: WirelessHotspotManager? = null
    @Volatile private var wifiScanPause: WifiScanPause? = null
    @Volatile private var bonjour: CarPlayBonjour? = null
    private val wirelessResourceLock = Any()
    private val wirelessFailureReported = AtomicBoolean(false)
    @Volatile private var firstTcpWatchdog: FirstTcpWatchdog? = null
    private val startupTimer = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "diplay-first-tcp-timeout").apply { isDaemon = true }
    }
    @Volatile private var wirelessDiagnostics: WirelessStartupDiagnostics? = null
    @Volatile private var bluetoothSocket: BluetoothSocket? = null
    @Volatile private var bluetoothStream: BlockingDuplexByteStream? = null
    @Volatile private var wirelessPublishedAddress: InetAddress? = null
    @Volatile private var wirelessAlternateAddress: InetAddress? = null
    @Volatile private var wirelessTunnelChannel: Iap2Session? = null
    @Volatile private var wirelessRuntimeIdentification: Iap2IdentificationConfig? = null
    @Volatile private var wirelessAirPlayEndpoint: Iap2WirelessCarPlayEndpoint? = null
    @Volatile private var wirelessListener: CarPlayWirelessService? = null
    @Volatile private var wirelessListenerBound = false
    private val wirelessHandoffRequested = AtomicBoolean(false)
    private val wirelessTunnelReady = AtomicBoolean(false)
    private val wirelessActiveReported = AtomicBoolean(false)
    /** Set when the handoff fell back and kept Bluetooth as the only iAP2 channel. */
    private val wirelessHandoffFellBack = AtomicBoolean(false)
    private val wirelessGeneration = AtomicInteger(0)
    private val wirelessConnectionProof = WirelessConnectionProof<AirPlaySession>()

    private var wirelessListenerLatch = CountDownLatch(1)
    private val teardownComplete = CountDownLatch(1)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            wirelessListener = (binder as CarPlayWirelessService.LocalBinder).service
            wirelessListenerLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            wirelessListener = null
            fail(IOException("CarPlay wireless listener disconnected"))
        }
    }

    private val sessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            val replacement = activeSession !== session
            if (replacement) {
                com.shihab.diplay.glance.CarPlayGlance.setConnected(true)
                // The gear may have changed since /info.
                if (videoListener != null) {
                    val delivery = session.setVideoPlaybackAllowed(VideoInCar.allowed)
                    debugLog("video in car session allowed=${VideoInCar.allowed} delivery=$delivery")
                }
            }
            activeSession = session
            if (replacement) restoreDashboardContent(session)
            debugLog(
                "AirPlay session active controller=${session.controllerId ?: "unknown"} " +
                    "peer=${session.host}",
            )
            if (config.transport == CarPlayTransport.WIRELESS) {
                val output = runCatching {
                    @Suppress("DEPRECATION")
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    if (audio == null) "audioManager=unavailable" else {
                        @Suppress("DEPRECATION")
                        "a2dpOn=${audio.isBluetoothA2dpOn} musicActive=${audio.isMusicActive} " +
                            "musicVolume=${audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)}/" +
                            "${audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)}"
                    }
                }.getOrElse { "audioDiagnostics=${it.javaClass.simpleName}" }
                debugLog("wireless audio route at AirPlay session start: $output; " +
                    "OEM A2DP/MCU source selection may differ from Android routing APIs")
                // Issue #88: near-zero STREAM_MUSIC volume is the most common "packets flow
                // but no sound" cause on head units; surface it loudly at session start.
                runCatching {
                    val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                    musicVolumeWarning(audio)?.let { debugLog(it) }
                }
            }
            uiListener?.onSessionActive(session)
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeSession === session) {
                activeSession = null
                com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
                videoListener?.onVideoSessionEnded()
                synchronized(playbackStatus) {
                    val wasPlaying = playbackStatus.playing
                    playbackStatus.clearAll()?.let { it to wasPlaying }
                }?.let { (cleared, wasPlaying) ->
                    nowPlayingListener?.invoke(cleared)
                    if (wasPlaying) playbackListener?.invoke(false)
                }
            }
            debugLog("AirPlay session ended peer=${session.host}")
            uiListener?.onSessionEnded(session)
        }

        override fun onTransportError(message: String) {
            debugLog("AirPlay transport error: $message")
            uiListener?.onTransportError(message)
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            debugLog(
                "AirPlay device info name=${info.name} deviceId=${info.deviceId} " +
                    "wifiMac=${info.wifiMac} model=${info.model}",
            )
            uiListener?.onDeviceInfo(session, info)
        }

        // The user tapped the car icon in CarPlay: host opens an in-session system-app panel
        // (overlay) so CarPlay does not leave the foreground Activity.
        override fun onHostUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car UI; notifying host for system app panel")
            uiListener?.onHostUiRequested(session)
        }

        override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) {
            if (activeSession === session) videoListener?.onVideoMessage(streamId, message)
        }

        override fun onVideoPlaybackUiRequested(session: AirPlaySession) {
            debugLog("CarPlay requested the car's video player")
            if (activeSession === session) videoListener?.onVideoUiRequested()
        }

        override fun onCommand(session: AirPlaySession, type: String, params: Map<String, Any?>) {
            val deviceId = params["deviceID"] as? String ?: params["deviceIdentifier"] as? String
            debugLog(
                "AirPlay command type=$type params=${params.keys.sorted().joinToString(",")} deviceId=${if (deviceId != null) "[address]" else "none"}",
            )
            if (
                config.transport == CarPlayTransport.WIRELESS &&
                phase == Phase.WIRELESS &&
                !closed &&
                activeSession === session &&
                isBluetoothHandoffCommand(type) &&
                wirelessHandoffRequested.compareAndSet(false, true)
            ) {
                debugLog(
                    "wireless CarPlay Bluetooth handoff requested; " +
                        "waiting for tunnel iAP2 readiness",
                )
                // Issue #128: on head units with native Bluetooth music, the iPhone is often
                // also paired with that A2DP sink. iOS then resolves audio away from CarPlay
                // (kept on the phone) and never opens the AirPlay audio stream. This hint makes
                // the conflict visible in the report; the fix is user-side (forget the native
                // BT pairing), the app cannot re-route iOS audio.
                debugLog(
                    "wireless audio route hint: if this iPhone is also paired with the head " +
                        "unit's native Bluetooth music, iOS may keep audio on the phone and " +
                        "never request the CarPlay audio stream; disconnect/forget that " +
                        "pairing so CarPlay is the only audio route",
                )
                armWirelessHandoffWatchdog(wirelessGeneration.get())
                maybeCompleteWirelessHandoff()
            }
            uiListener?.onCommand(session, type, params)
        }

        override fun onDebugLog(message: String) {
            debugLog(message)
        }
    }

    fun attachUi(
        listener: AirPlaySessionListener,
        reportStatus: (CarPlayStatus) -> Unit,
    ) {
        uiListener = listener
        uiStatusReporter = reportStatus
        mainHandler.post {
            if (uiListener === listener) lastReportedStatus?.let(reportStatus)
        }
    }

    fun isClosed(): Boolean = closed

    /** Nonblocking identity for an accepted, still-current primary screen session. */
    fun activeAirPlaySessionToken(): Any? {
        if (closed) return null
        val session = activeSession ?: return null
        val token = session.mainScreenSessionToken() ?: return null
        return token.takeIf { !closed && activeSession === session && session.mainScreenSessionToken() === token }
    }

    fun hasActiveAirPlayAttachment(): Boolean = synchronized(lifecycleLock) {
        !closed && wirelessListener?.isAttached() == true
    }

    fun start() {
        synchronized(this) {
            if (closed) return
        }
        connectionDiagnostic("start transport=${config.transport}")
        videoListener?.let { listener ->
            videoGate = VideoInCarGate(
                readParked = listener::readParked,
                onChanged = { allowed ->
                    val delivery = activeSession?.setVideoPlaybackAllowed(allowed)
                        ?: VideoPlaybackDelivery.QUEUED
                    debugLog("video in car allowed=$allowed delivery=$delivery")
                    listener.onVideoAllowedChanged(allowed)
                },
                onObserved = { parked ->
                    debugLog("video in car gear=${when (parked) { true -> "P"; false -> "not-P"; null -> "unknown" }}")
                },
            ).also { it.start() }
        }
        startMfi()
    }

    /** Reopens the MFi path without restarting the app. */
    fun reconnectMfi() = synchronized(lifecycleLock) {
        if (closed) return
        closeMfiSession()
        startMfi()
    }

    /** Re-runs iPhone discovery/bring-up using the already-open MFi session. */
    fun reconnectIphone() = synchronized(lifecycleLock) {
        if (closed) return
        if (mfiSession == null) {
            startMfi()
        } else {
            restartWireless()
        }
    }

    fun sendTouch(contacts: List<AirPlayContact>): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTouch(contacts) }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Pinch at the centre of the CarPlay frame. Maps zoom; other screens ignore the gesture. */
    fun zoomMap(zoomIn: Boolean) {
        if (closed) return
        val session = activeSession ?: return
        touchExecutor.execute {
            if (closed || activeSession !== session) return@execute
            val steps = 5
            for (step in 0..steps) {
                val spread = if (zoomIn) 0.06 + step * 0.035 else 0.28 - step * 0.035
                session.sendTouch(
                    listOf(
                        AirPlayContact(0, 0.5 - spread, 0.5, true),
                        AirPlayContact(1, 0.5 + spread, 0.5, true),
                    ),
                )
                if (!sleepTouch(20)) return@execute
            }
            session.sendTouch(emptyList())
        }
    }

    /**
     * Tap the upper-right control where CarPlay maps keep the 2D / 3D / heading button.
     * The spot is fixed in the video frame, so a map that draws that button elsewhere will miss.
     */
    fun cycleMapView() {
        if (closed) return
        val session = activeSession ?: return
        touchExecutor.execute {
            if (closed || activeSession !== session) return@execute
            session.sendTouch(listOf(AirPlayContact(0, 0.90, 0.16, true)))
            if (!sleepTouch(50)) return@execute
            session.sendTouch(emptyList())
        }
    }

    private fun sleepTouch(millis: Long): Boolean = try {
        Thread.sleep(millis)
        !closed
    } catch (_: InterruptedException) {
        false
    }

    /** Moves CarPlay on the main screen to its declared view area [index], for example another dock edge. */
    fun showViewArea(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    debugLog("View area $index sent=${session.showViewArea(index)}")
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends a CarPlay knob/touchpad movement or button state through the AirPlay HID channel. */
    fun sendKnob(state: AirPlayKnobState, momentary: Boolean = true): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        val token = session.mainScreenSessionToken() ?: return false
        if (closed || activeSession !== session) return false
        return try {
            touchExecutor.execute {
                if (!closed && activeSession === session && session.mainScreenSessionToken() === token) {
                    session.sendKnob(state, momentary)
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Sends one CarPlay media-button press (an [com.shihab.diplay.airplay.AirPlayHid] media index). */
    /** The host reports its physical cluster surface independently of the centre-map pause policy. */
    fun setDashboardMapOutputVisible(visible: Boolean) {
        val next = visible && !closed
        if (dashboardMapOutputVisible == next) return
        dashboardMapOutputVisible = next
        dashboardMapEpoch.incrementAndGet()
    }

    /** Immutable stream geometry retained when a new host adopts this background controller. */
    fun configuredClusterSize(): Pair<Int, Int>? = airPlayConfig.cluster?.let { it.widthPixels to it.heightPixels }

    /** Opens Siri on the iPhone, as the car's voice button does in CarPlay. */
    fun requestSiri(): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.invokeSiri() }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Answers the ringing call on the iPhone (telephony Hook Switch), as the wheel's call button does. */
    fun answerCall(): Boolean = sendTelephony(TELEPHONY_HOOK_SWITCH)

    /** Ends the current call or declines the ringing one (telephony Drop). */
    fun endCall(): Boolean = sendTelephony(TELEPHONY_DROP)

    private fun sendTelephony(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendTelephony(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun sendMediaButton(index: Int): Boolean {
        if (closed) return false
        val session = activeSession ?: return false
        return try {
            touchExecutor.execute { session.sendMedia(index) }
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            dashboardMapOutputVisible = false
        }
        // Drop any pending main-thread callbacks/posts before tearing down executors,
        // otherwise posted continuations may fire against a half-closed controller.
        mainHandler.removeCallbacksAndMessages(null)
        firstTcpWatchdog?.terminate()
        startupTimer.shutdownNow()
        (hotspot as? ManualHotspotManager)?.close()
        val teardownStarted = System.nanoTime()
        connectionDiagnostic("teardown begin transport=${config.transport}")
        videoGate?.close()
        com.shihab.diplay.glance.CarPlayGlance.setConnected(false)
        closeReceivers()
        availabilityPollGeneration.incrementAndGet()
        wirelessGeneration.incrementAndGet()
        wiredAborted.set(true)
        wiredGeneration.incrementAndGet()
        wiredPermissionPollGeneration.incrementAndGet()
        touchExecutor.shutdownNow()
        tunnelExecutor.shutdownNow()
        val service = wirelessListener
        unbindWirelessListener()
        Thread(
            {
                try {
                    closeBestEffort("wireless stack") { closeWirelessStack(service) }
                    closeBestEffort("Wi-Fi scan pause") { wifiScanPause?.close() }
                    wifiScanPause = null
                    closeBestEffort("MFi") { mfiSession?.close() }
                    mfiSession = null
                    wirelessRuntimeIdentification = null
                    wirelessAirPlayEndpoint = null
                    closeWiredResources()
                    closeBestEffort("location provider") { locationProvider?.close() }
                } finally {
                    executor.shutdownNow()
                    var executorTerminated = false
                    try {
                        executorTerminated = executor.awaitTermination(EXECUTOR_CLOSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    connectionDiagnostic(
                        "teardown end elapsedMs=${elapsedMillis(teardownStarted)} " +
                            "executorTerminated=$executorTerminated",
                    )
                    teardownComplete.countDown()
                }
            },
            "xcertplay-controller-teardown",
        ).apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches what the dashboard shows to another of the iPhone's cluster contents without reconnecting;
     * a paused map stays paused and comes back with the new content. Completion reports delivery
     * or a retained paused selection; the settings UI can reconnect when delivery fails.
     */
    fun showDashboardContent(url: String, onComplete: (Boolean) -> Unit) {
        val session = activeSession
        val stream = session?.clusterStream ?: 0
        if (closed || session == null || stream <= 0) {
            onComplete(false)
            return
        }
        try {
            touchExecutor.execute {
                val sent = synchronized(clusterUiLock) {
                    if (closed || activeSession !== session || session.clusterStream != stream) {
                        return@synchronized false
                    }
                    // A cluster stream DiPlay has not paused yet starts with the map drawn.
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val delivered = session.setClusterUrl(url, send = shown)
                    val sent = delivered && !closed && activeSession === session && session.clusterStream == stream
                    // The dashboard shows the new content now, so the cluster state starts over.
                    if (sent) {
                        requestedDashboardUrl = url
                        dashboardMapEpoch.incrementAndGet()
                    }
                    debugLog("Dashboard content: $url sent=$sent")
                    sent
                }
                onComplete(sent)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            onComplete(false)
        }
    }

    /** A new phone inherits the last accepted selection; all command work stays off its listener. */
    private fun restoreDashboardContent(session: AirPlaySession) {
        try {
            touchExecutor.execute {
                synchronized(clusterUiLock) {
                    if (closed || activeSession !== session) return@synchronized
                    val url = requestedDashboardUrl ?: return@synchronized
                    val stream = session.clusterStream
                    val shown = clusterUiShown || clusterUiStream != session to stream
                    val restored = session.restoreClusterUrl(url, send = shown)
                    if (restored && !closed && activeSession === session && session.clusterStream == stream && stream > 0) {
                        dashboardMapEpoch.incrementAndGet()
                    }
                }
            }
        } catch (t: java.util.concurrent.RejectedExecutionException) {
            // Closing the controller also discards the pending restore.
            AppLog.w(TAG, "dashboard restore rejected while closing", t)
        }
    }

    // Each new cluster stream starts with the map drawn (its initialURL); send only real changes.

    /** Waits for USB, iAP2, MFi and wireless-listener teardown; intended for a non-main lifecycle thread. */
    fun awaitClosed(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            teardownComplete.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun onRouteFrame(frame: com.shihab.diplay.iap2.wire.Iap2Frame) {
        com.shihab.diplay.glance.CarPlayGlance.onFrame(frame)
        synchronized(playbackStatus) {
            val previousPlaying = playbackStatus.playing
            playbackStatus.acceptUpdate(frame)?.let { it to (it.playing != previousPlaying) }
        }?.let { (update, playingChanged) ->
            nowPlayingListener?.invoke(update)
            if (playingChanged) playbackListener?.invoke(update.playing)
        }
    }

    private fun onArtworkTransfer(transfer: com.shihab.diplay.transport.Iap2ArtworkTransfer) {
        debugLog("iap2 artwork transfer id=0x${transfer.id.toString(16)} bytes=${transfer.bytes.size}")
        artworkListener?.invoke(transfer.id, transfer.bytes)
    }

    private fun startMfi() {
        availabilityPollGeneration.incrementAndGet()
        phase = Phase.MFI
        onStatus(CarPlayStatus.DiscoveringMfi)
        val privateRoot = appContext.filesDir
        val offlineDirectory = java.io.File(privateRoot, LocalMfiAuthenticationClient.DIRECTORY)
        when (config.mfiTarget) {
            MfiTarget.LOCAL -> openLocalMfi(offlineDirectory)
            MfiTarget.I2C -> {
                debugLog("mfi discovery backend=Linux I2C path=${config.linuxI2cPath}")
                openLinuxMfi()
            }
            MfiTarget.REMOTE -> {
                debugLog("mfi discovery backend=Remote server=${config.remoteMfiServer.orEmpty()}")
                openRemoteMfi()
            }
        }
    }

    private fun openLocalMfi(directory: java.io.File) {
        debugLog("mfi discovery backend=LocalOffline remoteFallback=disabled")
        executor.execute {
            try {
                val signatures = AtomicInteger(0)
                val client = LocalMfiAuthenticationClient.load(directory) { size ->
                    debugLog("mfi local signature count=${signatures.incrementAndGet()} digestBytes=$size")
                }
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog("mfi local offline ready protocolMajor=${client.protocolMajor()} certificateBytes=${client.readCertificate().size}")
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                // A broken local identity must fail closed rather than silently use the helper.
                fail(error)
            }
        }
    }

    private fun openRemoteMfi() {
        executor.execute {
            try {
                val client = RemoteMfiAuthenticationClient(
                    serverAddress = checkNotNull(config.remoteMfiServer),
                    token = config.remoteMfiToken,
                )
                client.reset()
                val protocolMajor = client.protocolMajor()
                if (closed || phase != Phase.MFI) return@execute
                mfiSession = MfiSession(client, null)
                debugLog(
                    "mfi remote service ready server=${config.remoteMfiServer} " +
                        "protocolMajor=$protocolMajor",
                )
                onStatus(CarPlayStatus.MfiReady)
                startPhone()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun openLinuxMfi() {
        executor.execute {
            try {
                val i2cPath = config.linuxI2cPath
                    ?: throw IllegalStateException("I2C backend selected but linuxI2cPath not configured")
                val transport = LinuxI2cTransport.open(i2cPath)
                try {
                    mfiSession = MfiSession(MfiRuntime.scan(transport), transport)
                    debugLog("mfi Linux I2C coprocessor ready path=${config.linuxI2cPath}")
                    onStatus(CarPlayStatus.MfiReady)
                    startPhone()
                } catch (error: Throwable) {
                    transport.close()
                    throw error
                }
            } catch (error: MfiCoprocessorNotFoundException) {
                debugLog("mfi Linux discovery failed: ${error.message}")
                waitForMfi()
            } catch (error: Throwable) {
                fail(error)
            }
        }
    }

    private fun waitForMfi() {
        if (closed || phase != Phase.MFI) return
        onStatus(CarPlayStatus.WaitingForMfi)
        scheduleAvailabilityPoll(Phase.MFI, attempt = 0) {
            when (config.mfiTarget) {
                MfiTarget.I2C -> openLinuxMfi()
                MfiTarget.REMOTE, MfiTarget.LOCAL -> Unit
            }
        }
    }

    private fun startPhone() {
        if (config.locationReportingEnabled) {
            val started = try {
                locationProvider?.start() == true
            } catch (error: Throwable) {
                AppLog.w(
                    TAG,
                    "Could not prewarm the Android location provider",
                    error,
                )
                false
            }
            debugLog("location provider prewarmed=$started")
        }
        if (config.transport == CarPlayTransport.WIRED) {
            startWired()
        } else if (detectIphoneOnUsb()) {
            // Auto-detect: an iPhone attached on USB means the user wants the wired path even
            // when the configured transport is WIRELESS. USB enumeration needs no permission.
            debugLog("startPhone auto-detect: iPhone present on USB host; using wired path")
            startWired()
        } else {
            startWireless()
        }
    }

    /**
     * True when a CarPlay iPhone is currently enumerated on the USB host. Purely enumerates the
     * device list (no permission, no open), so it is safe to call synchronously from [startPhone].
     */
    private fun detectIphoneOnUsb(): Boolean {
        val usbManager = appContext.getSystemService(android.content.Context.USB_SERVICE)
            as? android.hardware.usb.UsbManager
            ?: return false
        return runCatching {
            com.shilapi.xcertplay.transport.IphoneUsbHost(
                appContext,
                usbManager,
                com.shilapi.xcertplay.transport.IphoneUsbMatcher.appleVendor(),
                onDiagnostic = ::connectionDiagnostic,
            ).discover().isNotEmpty()
        }.getOrDefault(false)
    }

    /**
     * Wired (USB) CarPlay bring-up. Detection-first: poll for the iPhone USB device, request
     * framework permission, open the iAP2 USBMUX session and NCM, then run USBMUX/Lockdown,
     * iAP2 control and the loopback AirPlay bridge. Every step runs on [executor] and reports
     * through [onStatus] so the same UI surfaces used by the wireless path apply.
     */
    private fun startWired() {
        if (closed) return
        phase = Phase.WIRED
        onStatus(CarPlayStatus.DiscoveringIphone)
        connectionDiagnostic("wired start transport=USB")
        val usbManager = appContext.getSystemService(android.content.Context.USB_SERVICE)
            as? android.hardware.usb.UsbManager
            ?: run { fail(IllegalStateException("USB host service is unavailable")); return }
        val host = com.shilapi.xcertplay.transport.IphoneUsbHost(
            appContext,
            usbManager,
            com.shilapi.xcertplay.transport.IphoneUsbMatcher.appleVendor(),
            onDiagnostic = ::connectionDiagnostic,
        )
        wiredIphoneHost = host
        wiredAborted.set(false)
        wiredGeneration.incrementAndGet()
        checkWiredIphoneAvailability()
    }

    private fun checkWiredIphoneAvailability() {
        if (closed || phase != Phase.WIRED || wiredAborted.get()) return
        val host = wiredIphoneHost ?: return
        val device = runCatching { host.discover().firstOrNull() }.getOrElse {
            debugLog("wired discover failed: ${it.message}")
            null
        }
        if (device == null) {
            onStatus(CarPlayStatus.WaitingForIphone)
            scheduleAvailabilityPoll(phase, check = ::checkWiredIphoneAvailability)
        } else {
            debugLog(
                "wired iPhone discovered vid=0x${device.vendorId.toString(16)} " +
                    "pid=0x${device.productId.toString(16)}",
            )
            connectionDiagnostic("wired iPhone attached vid=0x${device.vendorId.toString(16)}")
            requestWiredIphonePermission(device)
        }
    }

    private fun requestWiredIphonePermission(device: android.hardware.usb.UsbDevice) {
        mainHandler.post { doRequestWiredIphonePermission(device) }
    }

    private fun doRequestWiredIphonePermission(device: android.hardware.usb.UsbDevice) {
        if (closed || phase != Phase.WIRED || wiredAborted.get()) return
        val host = wiredIphoneHost ?: return
        try {
            when (val request = host.requestPermission(device)) {
                is com.shilapi.xcertplay.transport.IphoneUsbHost.PermissionRequest.AlreadyGranted -> {
                    debugLog("wired iPhone USB permission already granted")
                    onWiredIphonePermission(device)
                }
                is com.shilapi.xcertplay.transport.IphoneUsbHost.PermissionRequest.Requested -> {
                    debugLog("wired iPhone USB permission requested")
                    onStatus(CarPlayStatus.RequestingIphonePermission)
                    pollWiredIphonePermission(device)
                }
            }
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private fun pollWiredIphonePermission(device: android.hardware.usb.UsbDevice) {
        val generation = wiredPermissionPollGeneration.incrementAndGet()
        val deadlineNanos = System.nanoTime() + WIRED_PERMISSION_POLL_TIMEOUT_MILLIS * 1_000_000L
        val check = object : Runnable {
            override fun run() {
                try {
                    if (closed || generation != wiredPermissionPollGeneration.get()) return
                    val hasPerm = runCatching {
                        (appContext.getSystemService(android.content.Context.USB_SERVICE)
                            as? android.hardware.usb.UsbManager)?.hasPermission(device) == true
                    }.getOrDefault(false)
                    if (hasPerm) {
                        onWiredIphonePermission(device)
                        return
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        onStatus(
                            CarPlayStatus.Failed(
                                "iPhone USB permission was not granted; replug and retry",
                            ),
                        )
                        return
                    }
                    mainHandler.postDelayed(this, WIRED_PERMISSION_POLL_INTERVAL_MILLIS)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
        mainHandler.postDelayed(check, WIRED_PERMISSION_POLL_INTERVAL_MILLIS)
    }

    private fun onWiredIphonePermission(device: android.hardware.usb.UsbDevice) {
        if (closed || phase != Phase.WIRED || wiredAborted.get()) return
        wiredPermissionPollGeneration.incrementAndGet()
        // A freshly attached iPhone first enumerates as an ordinary USB device; only after the
        // vendor 0x51 transition request does it re-enumerate into the CarPlay (USBMUX+NCM)
        // configuration. Opening data paths before that fails with "exposes no USBMUX CarPlay
        // configuration", so confirm the configuration is ready first and re-enumerate if not.
        if (wiredCarPlayConfigurationReady(device)) {
            ensureNcmModeThenOpenDataPaths(device)
        } else {
            debugLog("wired iPhone has no CarPlay configuration yet; requesting re-enumeration")
            beginWiredReenumeration(device, attempts = 0)
        }
    }

    private fun wiredCarPlayConfigurationReady(device: android.hardware.usb.UsbDevice): Boolean =
        runCatching { com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration.find(device) != null }
            .getOrDefault(false)

    /**
     * Requests the Apple vendor 0x51 transition so the iPhone re-enumerates into the CarPlay
     * configuration, then waits for it. Mirrors the upstream re-enumeration state machine
     * (beginReenumeration -> requestCarPlayReenumerationAsync -> pollReenumeration).
     */
    private fun beginWiredReenumeration(
        device: android.hardware.usb.UsbDevice,
        attempts: Int,
    ) {
        if (closed || phase != Phase.WIRED || wiredAborted.get()) return
        onStatus(CarPlayStatus.SelectingConfiguration)
        val host = wiredIphoneHost ?: return
        host.requestCarPlayReenumerationAsync(device, executor) { transition ->
            when (transition) {
                is com.shilapi.xcertplay.transport.IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                    if (closed || phase != Phase.WIRED || wiredAborted.get()) return@requestCarPlayReenumerationAsync
                    onStatus(CarPlayStatus.WaitingForReenumeration)
                    wiredPollReenumeration(device, attempts)
                }
                is com.shilapi.xcertplay.transport.IphoneUsbHost.TransitionResult.Failed -> {
                    // The 0x51 request may STALL when the device is already transitioning or already
                    // in CarPlay mode; fall back to a configuration inspection before counting a failure.
                    if (wiredCarPlayConfigurationReady(device)) {
                        onWiredIphonePermission(device)
                    } else if (attempts < MAX_WIRED_REENUMERATION_ATTEMPTS) {
                        mainHandler.postDelayed(
                            { beginWiredReenumeration(device, attempts + 1) },
                            WIRED_REENUMERATION_RETRY_MILLIS,
                        )
                    } else {
                        fail(transition.error)
                    }
                }
            }
        }
    }

    /** Polls for the re-enumerated iPhone that now exposes the CarPlay configuration. */
    private fun wiredPollReenumeration(
        previous: android.hardware.usb.UsbDevice,
        attempts: Int,
    ) {
        val started = System.nanoTime()
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (closed || phase != Phase.WIRED || wiredAborted.get()) return
                    val elapsed = (System.nanoTime() - started) / 1_000_000
                    val host = wiredIphoneHost ?: return
                    val found = runCatching { host.discover().firstOrNull() }.getOrNull()
                    val ready = found != null && wiredCarPlayConfigurationReady(found)
                    val isNewDevice = found != null &&
                        (found.deviceId != previous.deviceId || found.deviceName != previous.deviceName)
                    if (found != null && (ready || isNewDevice || elapsed >= WIRED_REENUMERATION_READY_EARLY_MILLIS)) {
                        debugLog(
                            "wired re-enumeration settled after ${elapsed}ms " +
                                "ready=$ready newDevice=$isNewDevice",
                        )
                        // The device object changed during re-enumeration; re-acquire permission
                        // (it may need a fresh grant on Android 4.x) then re-check the configuration.
                        requestWiredIphonePermission(found)
                        return
                    }
                    if (elapsed >= WIRED_REENUMERATION_TIMEOUT_MILLIS) {
                        if (attempts < MAX_WIRED_REENUMERATION_ATTEMPTS) {
                            beginWiredReenumeration(previous, attempts + 1)
                        } else {
                            fail(
                                com.shilapi.xcertplay.transport.IphoneUsbException.DeviceUnavailable(
                                    "iPhone did not expose the CarPlay USB configuration after " +
                                        "re-enumeration; replug and retry",
                                ),
                            )
                        }
                        return
                    }
                    mainHandler.postDelayed(this, WIRED_REENUMERATION_POLL_MILLIS)
                } catch (error: Throwable) {
                    fail(error)
                }
            }
        }
        mainHandler.postDelayed(poll, WIRED_REENUMERATION_POLL_MILLIS)
    }

    /**
     * Opens the data paths once the NCM CarPlay configuration is ready (the caller already verified
     * [wiredCarPlayConfigurationReady]).
     *
     * Do NOT gate this on the GET_MODE first byte: per usbmuxd that reply is "3:3:3:0 in the
     * initial mode, 5:3:3:0 otherwise" — the first byte is an initial/non-initial flag, not the
     * Apple mode number, and the real mode is derived from bNumConfigurations (guess_mode). A device
     * with 6 configurations is already in mode 4 (USB Ethernet + CDC-NCM), the correct working
     * mode. Comparing that byte against mode 3 never matches and made the old code issue SET_MODE
     * in an infinite loop (195 times in the 2026-11-08 report) without ever opening the data
     * plane. So when the NCM configuration is present, open the data paths directly.
     */
    private fun ensureNcmModeThenOpenDataPaths(device: android.hardware.usb.UsbDevice) {
        val host = wiredIphoneHost ?: return
        executor.execute {
            if (closed || phase != Phase.WIRED || wiredAborted.get()) return@execute
            if (wiredCarPlayConfigurationReady(device)) {
                debugLog("wired NCM configuration ready; opening data paths directly (no Apple SET_MODE)")
                openWiredDataPaths(device)
                return@execute
            }
            // Fallback only: the device is still in the initial mode with no NCM configuration.
            // Ask for mode 4 (the mode LIVI uses); a STALL is treated as "already usable" and the
            // re-enumeration poll / configuration check bounds the work, so this can never loop.
            debugLog("wired NCM configuration absent; requesting Apple mode 4")
            host.selectAppleModeAsync(
                device,
                com.shilapi.xcertplay.transport.IphoneUsbHost.APPLE_MODE_USB_ETHERNET_CDC_NCM,
                executor,
            ) { transition ->
                when (transition) {
                    is com.shilapi.xcertplay.transport.IphoneUsbHost.TransitionResult.ReenumerationRequested -> {
                        if (closed || phase != Phase.WIRED || wiredAborted.get()) return@selectAppleModeAsync
                        onStatus(CarPlayStatus.WaitingForReenumeration)
                        wiredPollReenumeration(device, attempts = 0)
                    }
                    is com.shilapi.xcertplay.transport.IphoneUsbHost.TransitionResult.Failed -> {
                        // A STALL here can mean the device is already usable; fall back rather than
                        // failing outright when the CarPlay configuration is still present.
                        if (wiredCarPlayConfigurationReady(device)) {
                            debugLog("wired SET_MODE failed but configuration is ready; opening data paths")
                            openWiredDataPaths(device)
                        } else {
                            fail(transition.error)
                        }
                    }
                }
            }
        }
    }

    private fun openWiredDataPaths(device: android.hardware.usb.UsbDevice) {
        val host = wiredIphoneHost ?: return
        wiredAborted.set(false)
        val generation = wiredGeneration.incrementAndGet()
        debugLog("wired opening iPhone USB data paths")
        onStatus(CarPlayStatus.SelectingConfiguration)
        onStatus(CarPlayStatus.OpeningDataPaths)
        host.openIap2UsbSessionAsync(device, executor, null) { result ->
            when (result) {
                is com.shilapi.xcertplay.transport.IphoneUsbHost.Iap2SessionResult.Connected -> {
                    val session = result.session
                    wiredUsbSession = session
                    if (closed || wiredAborted.get() || generation != wiredGeneration.get()) {
                        session.markAborted()
                        session.close()
                        return@openIap2UsbSessionAsync
                    }
                    wiredChain(3, "iAP2 USB session opened (USBMUX fd ready)")
                    try {
                        val ncm = openWiredNcm(device.vendorId, session)
                        wiredNcm = ncm
                        if (closed || wiredAborted.get() || generation != wiredGeneration.get()) {
                            ncm.markAborted()
                            ncm.close()
                            session.markAborted()
                            session.close()
                            return@openIap2UsbSessionAsync
                        }
                        runWiredStack(session, ncm)
                    } catch (error: Throwable) {
                        session.close()
                        if (!closed && !wiredAborted.get() && generation == wiredGeneration.get()) fail(error)
                    }
                }
                is com.shilapi.xcertplay.transport.IphoneUsbHost.Iap2SessionResult.Failed -> {
                    if (!closed && !wiredAborted.get() && generation == wiredGeneration.get()) fail(result.error)
                }
            }
        }
    }

    private fun openWiredNcm(
        vendorId: Int,
        usbSession: com.shilapi.xcertplay.transport.Iap2UsbSession,
    ): com.shilapi.xcertplay.transport.NcmUsbBridge {
        val configuration = usbSession.carPlayConfiguration
            ?: throw com.shilapi.xcertplay.transport.IphoneUsbException.Protocol(
                "iPhone exposes no CarPlay configuration for NCM",
            )
        val function = com.shilapi.xcertplay.transport.NcmFunctionDiscovery.find(configuration)
            ?: throw com.shilapi.xcertplay.transport.IphoneUsbException.Protocol(
                "iPhone configuration does not expose an NCM function",
            )
        wiredChain(4, "NCM config=${configuration.id} function found")
        debugLog(
            "ncm config=${configuration.id} control=${function.control.id}/" +
                "${com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration.alternateSetting(function.control)}" +
                " data=${function.data.id}/${com.shilapi.xcertplay.transport.IphoneCarPlayConfiguration.alternateSetting(function.data)}" +
                " in=0x${function.bulkIn.address.toString(16)} out=0x${function.bulkOut.address.toString(16)}" +
                " status=${function.statusIn?.let { "0x${it.address.toString(16)}" } ?: "none"}" +
                " unions=${configuration.unions}",
        )
        // The phone may legitimately STALL SET_NTB_INPUT_SIZE / SET_MAX_DATAGRAM_SIZE when the
        // matching bmNetworkCapabilities bit is clear, so log what it actually declares — and the
        // raw configuration descriptor, because two NCM functions with no Union between them must
        // be told apart by endpoint ownership rather than assumed order.
        debugLog(
            "ncm descriptors caps=${configuration.ncmCapabilities} unions=${configuration.unions} " +
                "raw=${(configuration.platformConfiguration as? ByteArray)?.let { bytes ->
                    bytes.take(400).joinToString(" ") { "%02x".format(it) }
                } ?: "unavailable"}",
        )
        // Re-entry guard: a previous NCM bridge whose kernel endpoints died after a UI/surface
        // destroy must never be reused (previous.log: surface recreate -> instant raw(1) EPERM on
        // the old bridge while counters still read completed=591). Close any stale bridge before
        // opening a fresh one; normal first entry sees wiredNcm == null and skips this.
        wiredNcm?.let { stale ->
            debugLog(
                "wired re-entry: closing stale NCM bridge " +
                    "identity=${System.identityHashCode(stale)} before fresh open",
            )
            closeBestEffort("stale wired NCM (re-entry)") {
                stale.markAborted()
                stale.close()
            }
        }
        wiredNcm = null
        val ncm = com.shilapi.xcertplay.transport.NcmUsbBridge.open(
            sharedConnection = usbSession.retainConnectionForNcm(),
            function = function,
            expectedConfiguration = configuration.id,
            vendorId = vendorId,
            useAsyncRead = com.shilapi.xcertplay.compat.realSdkInt() >= 21,
            onDiagnostic = ::connectionDiagnostic,
        )
        debugLog("wired NCM bridge FRESH open identity=${System.identityHashCode(ncm)}")
        wiredChain(5, "NCM bridge opened (bulk endpoints claimed)")
        return ncm
    }

    /** Tears down every wired (USB) resource acquired during this session. Called from [close]. */
    private fun closeWiredResources() {
        // Mark first so blocking bulk/control calls unwind instead of wedging the teardown thread.
        wiredAborted.set(true)
        closeBestEffort("wired AirPlay session") {
            wiredAirPlaySession?.close()
        }
        wiredAirPlaySession = null
        wiredAirPlayServer?.close()
        wiredAirPlayServer = null
        closeBestEffort("wired lwIP") {
            wiredLwip?.close()
        }
        wiredLwip = null
        closeBestEffort("wired NCM") {
            wiredNcm?.markAborted()
            wiredNcm?.close()
        }
        wiredNcm = null
        // Best-effort iAP2 CarPlay session end on the CSM channel while the USBMUX pipe is still
        // open. Gated by default because the 0xE001 EndSession opcode/body is not catalogued here;
        // send failure or a dead channel is swallowed and never blocks teardown.
        if (com.shilapi.xcertplay.transport.WiredNcmTuning.endSessionOnClose) {
            closeBestEffort("wired iAP2 EndSession 0xE001") {
                val session = csm?.takeIf { !it.isClosed } ?: return@closeBestEffort
                debugLog("wired sending iAP2 EndSession 0xE001 before USBMUX close")
                session.sendRaw(0xE001, 2_000L) {}
                debugLog("wired iAP2 EndSession 0xE001 sent")
            }
        }
        closeBestEffort("wired USBMUX session") {
            wiredUsbSession?.markAborted()
            wiredUsbSession?.close()
        }
        wiredUsbSession = null
        wiredIphoneHost = null
        csm?.close()
        csm = null
    }

    private fun wiredChain(step: Int, message: String) {
        debugLog("WIRED-CHAIN [$step/16] $message")
    }

    /**
     * Runs the wired control stack after NCM is open: USBMUX, Lockdown pairing, the CarKit
     * (com.apple.carkit.service) channel, the iAP2 CSM control loop, and the loopback AirPlay
     * bridge that relays iPhone TCP into this process's Conscrypt AirPlay socket. This mirrors
     * the upstream userspaceNetworkEnabled path but keeps the AirPlay listener in-process
     * (no VPN/TUN), which is exactly what this head unit's kernel requires.
     */
    private fun runWiredStack(
        usbSession: com.shilapi.xcertplay.transport.Iap2UsbSession,
        ncm: com.shilapi.xcertplay.transport.NcmUsbBridge,
    ) {
        if (closed || wiredAborted.get()) return
        try {
            val mux = com.shilapi.xcertplay.transport.Iap2UsbMuxHost.open(usbSession, onDiagnostic = ::connectionDiagnostic)
            wiredChain(6, "USBMUX host opened")
            debugLog("wired USBMUX host opened")
            onStatus(CarPlayStatus.Pairing)
            val pairingClient = com.shilapi.xcertplay.transport.LockdownPairingClient(mux)
            val carKitClient = com.shilapi.xcertplay.transport.LockdownCarKitClient(mux)
            val saved = com.shilapi.xcertplay.transport.WiredLockdownStore.load(appContext)
            val carkitStream = if (saved != null) {
                try {
                    debugLog("wired reusing saved lockdown record")
                    carKitClient.open(saved, config.label)
                } catch (error: Exception) {
                    if (closed || error is com.shilapi.xcertplay.transport.LockdownPairingException.Cancelled) {
                        throw error
                    }
                    debugLog("wired saved lockdown record rejected (${error.message}); pairing again")
                    com.shilapi.xcertplay.transport.WiredLockdownStore.clear(appContext)
                    val paired = pairingClient.pair(
                        label = config.label,
                        hostId = wiredHostId,
                        systemBuid = wiredSystemBuid,
                        totalTimeoutMillis = WIRED_PAIR_TIMEOUT_MILLIS,
                        isCancelled = { closed },
                    )
                    com.shilapi.xcertplay.transport.WiredLockdownStore.save(appContext, paired.pairRecord)
                    carKitClient.open(paired, config.label)
                }
            } else {
                val paired = pairingClient.pair(
                    label = config.label,
                    hostId = wiredHostId,
                    systemBuid = wiredSystemBuid,
                    totalTimeoutMillis = WIRED_PAIR_TIMEOUT_MILLIS,
                    isCancelled = { closed },
                )
                com.shilapi.xcertplay.transport.WiredLockdownStore.save(appContext, paired.pairRecord)
                debugLog("wired lockdown pair record created")
                carKitClient.open(paired, config.label)
            }
            wiredChain(7, "Lockdown pairing ok (saved=${saved != null})")
            onStatus(CarPlayStatus.ConnectingControl)
            wiredChain(8, "CarKit service opened")
            debugLog("wired com.apple.carkit.service stream opened")
            if (closed || wiredAborted.get()) return
            // Adapt the upstream (com.shilapi) Lockdown byte stream to this project's iAP2
            // byte stream interface. Both interfaces are structurally identical.
            val adaptedCarkit = object : com.shihab.diplay.transport.BlockingDuplexByteStream {
                override fun send(data: ByteArray) = carkitStream.send(data)
                override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                    carkitStream.recv(maxBytes, timeoutMillis)
                override fun close() = carkitStream.close()
            }
            val csm = com.shihab.diplay.iap2.session.Iap2Session.open(
                adaptedCarkit,
                traceContext = "wired",
                onTrace = ::debugLog,
                onArtwork = ::onArtworkTransfer,
            )
            this.csm = csm
            wiredChain(9, "iAP2 CSM channel opened")
            debugLog("wired iAP2 CSM channel opened")

            val ncmHostMac = ncm.hostMac ?: config.hostMac
            if (closed || wiredAborted.get()) return
            debugLog("ncm using hostMac=${ncmHostMac.hexString()}")
            val airPlayPort = startWiredAirPlayServer(ncm, ncmHostMac)
            wiredChain(10, "loopback AirPlay listener attached")
            wiredChain(11, "NCM/lwIP transport attached (userspace)")
            val mfi = mfiSession?.client
                ?: throw com.shilapi.xcertplay.transport.IphoneUsbException.DeviceUnavailable(
                    "MFi coprocessor client is unavailable",
                )
            wiredChain(12, "MFi coprocessor client ready")
            val endpoint = com.shihab.diplay.transport.Iap2WiredCarPlayEndpoint(
                ipv6Addresses = listOf(config.linkLocal),
                airPlayPort = airPlayPort,
                publicKey = identity.publicKeyHex,
                sourceVersion = airPlayConfig.sourceVersion,
                deviceIdentifier = ncmHostMac.hexString(),
            )
            onStatus(CarPlayStatus.RunningControl)
            debugLog(
                "wired iAP2 runtime control starting " +
                    "location=${config.identification.locationInformationEnabled} " +
                    "vehicleStatus=${config.identification.vehicleStatusEnabled}",
            )
            wiredTcpArrived.set(false)
            val result = com.shihab.diplay.transport.Iap2WiredControlClient(
                csm,
                com.shihab.diplay.mfi.Iap2MfiAuthenticationClient(mfi),
            ).run(
                identification = config.identification,
                endpoint = endpoint,
                availableCurrentMilliAmps = config.availableCurrentMilliAmps,
                // Keep the iAP2 control loop alive for the whole session: a fixed 60s deadline used
                // to return TIMED_OUT right after a successful connect, stopping iAP2 receive while
                // media kept streaming; the iPhone then saw an unmaintained link and sent EOF ~20s
                // later (the "dies after ~1 minute" fault). NO_TIMEOUT keeps the bounded 60s handshake
                // but runs live control until closed/aborted.
                timeoutMillis = com.shihab.diplay.transport.Iap2WiredControlClient.NO_TIMEOUT_MILLIS,
                locationProvider = locationProvider,
                vehicleStatusProvider = vehicleStatusProvider,
                onIncoming = ::onRouteFrame,
                onProgress = { message ->
                    debugLog("wired $message")
                    if (message == "iap2 tx=0x4301 carplay-start-session") {
                        wiredChain(14, "iAP2 StartSession (0x4301) sent; waiting for iPhone mDNS/TCP")
                        if (wiredAirPlaySession == null) wiredTcpArrived.set(false)
                    }
                },
                airPlayTcpArrived = { wiredTcpArrived.get() },
                airPlayDataRecent = { true },
            )
            if (closed || wiredAborted.get() || phase == Phase.WIRED) {
                debugLog("wired control loop finished after abort/detach")
                return
            }
            onStatus(
                when (result.terminal) {
                    com.shihab.diplay.transport.Iap2WiredControlTerminal.TIMED_OUT ->
                        CarPlayStatus.Failed("Wired CarPlay control timed out at ${result.stage}; StartSession sent=${result.carPlayStartSessionsSent}")
                    com.shihab.diplay.transport.Iap2WiredControlTerminal.CHANNEL_CLOSED ->
                        CarPlayStatus.Failed("CarPlay control channel closed")
                    com.shihab.diplay.transport.Iap2WiredControlTerminal.PEER_SILENT ->
                        CarPlayStatus.Failed("Wired CarPlay peer silent; resetting link")
                    com.shihab.diplay.transport.Iap2WiredControlTerminal.NO_AIRPLAY_TCP ->
                        CarPlayStatus.Failed("No AirPlay TCP after CarPlay StartSession; resetting link")
                },
            )
        } catch (error: Throwable) {
            if (!closed && !wiredAborted.get()) fail(error)
        }
    }

    /**
     * Starts the userspace lwIP NCM bridge and an in-process loopback AirPlay listener. iPhone
     * TCP to port 7000 is injected by the lwIP relay into 127.0.0.1, where the existing Conscrypt
     * [AirPlaySession] accepts it. Returns the bound port.
     */
    private fun startWiredAirPlayServer(
        ncm: com.shilapi.xcertplay.transport.NcmUsbBridge,
        hostMac: ByteArray,
    ): Int {
        val lwip = com.shilapi.xcertplay.network.LwipNcmBridge(
            ncm = ncm,
            hostMac = hostMac,
            hostLinkLocal = config.linkLocal,
            onDebugLog = ::debugLog,
            onError = { fail(it) },
        )
        lwip.start()
        wiredLwip = lwip
        val server = java.net.ServerSocket()
        server.reuseAddress = true
        server.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), airPlayConfig.port))
        val port = server.localPort
        lwip.registerPort(port, true)
        wiredAirPlayServer = server
        Thread({ wiredAirPlayAcceptLoop(server) }, "xcertplay-wired-airplay-accept").apply {
            isDaemon = true
            start()
        }
        debugLog("wired loopback AirPlay listener ready 127.0.0.1:$port")
        return port
    }

    private fun wiredAirPlayAcceptLoop(server: java.net.ServerSocket) {
        try {
            while (!closed && !wiredAborted.get() && wiredAirPlayServer === server) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.setSoLinger(true, 0)
                debugLog("wired AirPlay connection accepted from ${socket.remoteSocketAddress}")
                val mfi = mfiSession?.client
                val session = AirPlaySession(
                    socket = socket,
                    config = airPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = sessionListener,
                    media = media,
                    registerPort = { port, isTcp -> wiredLwip?.registerPort(port, isTcp) },
                )
                wiredAirPlaySession = session
                wiredTcpArrived.set(true)
                session.start()
            }
        } catch (error: Exception) {
            if (!closed && !wiredAborted.get()) {
                debugLog("wired AirPlay accept ended: ${error.javaClass.simpleName}: ${error.message}")
            }
        }
    }

    private fun ByteArray.hexString(): String = joinToString("") { "%02x".format(it) }

    private fun startWireless(expectedGeneration: Int? = null) {
        val generation = synchronized(wirelessResourceLock) {
            if (closed || expectedGeneration != null && expectedGeneration != wirelessGeneration.get()) return
            wirelessFailureReported.set(false)
            diagnosticRun.incrementAndGet()
            availabilityPollGeneration.incrementAndGet()
            phase = Phase.WIRELESS
            wirelessHandoffRequested.set(false)
            wirelessTunnelReady.set(false)
            wirelessActiveReported.set(false)
            wirelessHandoffFellBack.set(false)
            val next = wirelessGeneration.incrementAndGet()
            onStatus(CarPlayStatus.StartingHotspot, next)
            next
        }
        executor.execute { runWireless(generation) }
    }

    private fun restartWireless() {
        val generation = synchronized(wirelessResourceLock) {
            firstTcpWatchdog?.terminate()
            wirelessGeneration.incrementAndGet()
        }
        Thread(
            {
                closeWirelessStack(generation = generation)
                startWireless(generation)
            },
            "xcertplay-wireless-restart",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun runWireless(generation: Int) {
        var stage = "hotspot startup"
        try {
            debugLog("wireless bring-up generation=$generation starting")
            // Stage 1: precondition checks (Bluetooth adapter present/enabled).
            checkWirelessBluetoothPreconditions()
            closeWirelessStack(generation = generation)
            if (
                closed ||
                phase != Phase.WIRELESS ||
                generation != wirelessGeneration.get()
            ) {
                return
            }

            // Stage 2: install the first-TCP watchdog used for the whole bring-up.
            val watchdog = installFirstTcpWatchdog(generation)

            // Stage 3: bring up the SoftAP/P2P hotspot and resolve the host address.
            var startedBonjour: CarPlayBonjour? = null
            val hotspotStack = WirelessHotspotStack.bringUp(
                appContext = appContext,
                config = config,
                airPlayConfig = airPlayConfig,
                generation = generation,
                isStaleWirelessRun = ::isStaleWirelessRun,
                debugLog = ::debugLog,
                onStatus = { onStatus(it) },
                wirelessResourceLock = wirelessResourceLock,
                getHotspot = { hotspot },
                setHotspot = { hotspot = it },
                onNetworkChanged = { if (!isStaleWirelessRun(generation)) restartWireless() },
                closeBestEffort = ::closeBestEffort,
                mfiClient = mfiSession?.client,
                wirelessPrefsName = WIRELESS_PREFS,
                hotspotFamilyPreferenceKey = HOTSPOT_FAMILY_PREFERENCE_KEY,
                beginConnectionProof = { gen, onConfirm ->
                    wirelessConnectionProof.begin(gen, onConfirm)
                },
                setPublishedAddress = { wirelessPublishedAddress = it },
                setAlternateAddress = { wirelessAlternateAddress = it },
                setDiagnostics = { wirelessDiagnostics = it },
                bonjourSnapshot = { startedBonjour?.diagnosticSnapshot() ?: "bonjour=not_started" },
                pauseWifiScansLocked = ::pauseWifiScans,
            ) ?: return
            stage = "Bluetooth device selection"

            // Stage 4: pick the iPhone Bluetooth target and build the per-link AirPlay config.
            val bt = selectWirelessBluetoothTarget(hotspotStack.deviceIdentifier)

            onStatus(CarPlayStatus.AttachingNetwork)
            stage = "AirPlay listener startup"
            // Stage 5: attach the AirPlay listener to the chosen bind address.
            val attached = attachWirelessAirPlayListener(
                generation = generation,
                hostAddress = hotspotStack.hostAddress,
                wirelessAirPlayConfig = bt.wirelessAirPlayConfig,
                watchdog = watchdog,
                startedHotspot = hotspotStack.startedHotspot,
                mfi = hotspotStack.mfi,
            )
            if (isStaleWirelessRun(generation)) {
                return
            }

            // Stage 6: build the iAP2 wireless endpoint / identification handed to the iPhone.
            val endpoint = buildWirelessEndpoint(
                hotspotInfo = hotspotStack.hotspotInfo,
                hostAddress = hotspotStack.hostAddress,
                hostAddressText = hotspotStack.hostAddressText,
                listenerPort = attached.listenerPort,
                hostBluetoothMac = bt.hostBluetoothMac,
            )
            stage = "Bonjour discovery startup"
            // Stage 7: start Bonjour discovery on the same address family as the AirPlay listener.
            startBonjourDiscovery(
                generation = generation,
                advertisedAirPlayConfig = attached.advertisedAirPlayConfig,
                hostAddress = hotspotStack.hostAddress,
                startedHotspot = hotspotStack.startedHotspot,
                diagnostics = hotspotStack.diagnostics,
            )
            startedBonjour = bonjour
            debugLog("wireless Bonjour services started mode=interface iface=${hotspotStack.hotspotInfo.interfaceName ?: "unknown"}")
            if (isStaleWirelessRun(generation)) {
                return
            }

            // Stage 8: establish the iAP2 link (Bluetooth RFCOMM bootstrap, or Wi-Fi discovery fallback).
            runWirelessLinkEstablish(
                generation = generation,
                stageSetter = { stage = it },
                device = bt.device,
                hybridAdapter = CarPlayBluetoothManager.get(appContext),
                hotspot = hotspotStack,
                endpoint = endpoint.endpoint,
                bootstrapIdentification = endpoint.bootstrapIdentification,
                watchdog = watchdog,
            )
        } catch (error: Throwable) {
            if (isStaleWirelessRun(generation)) {
                return
            }
            if (wirelessActiveReported.get() && error !is Error) {
                debugLog("wireless RFCOMM control ended after tunnel handoff: ${error.message}")
            } else {
                debugLog("wireless bring-up failed", error)
                if (error is Error) throw error
                flipWirelessAddressFamily()
                fail(error, generation)
                closeWirelessStack(generation = generation)
            }
        }
    }

    /** Stage 1 of [runWireless]: verify the head unit has a usable Bluetooth adapter before waiting on Wi-Fi. */
    private fun checkWirelessBluetoothPreconditions() {
        // Issue #79: without a Bluetooth adapter, wireless CarPlay is impossible —
        // iPhone needs Bluetooth for discovery/pairing. Fail fast instead of waiting
        // 60s for a Wi-Fi connection that will never come.
        // Issue #138: gate on the hybrid adapter (vendor-private Bluetooth service +
        // standard Android), not the raw Android adapter alone — on some head units
        // (e.g. ECARX E01) Bluetooth only works through the vendor service while
        // getDefaultAdapter() reports null or disabled.
        val hybridBt = CarPlayBluetoothManager.get(appContext)
        if (!hybridBt.isSupported()) {
            throw IOException(
                "No Bluetooth adapter on this head unit; wireless CarPlay requires Bluetooth " +
                    "for iPhone discovery."
            )
        }
        // Issue #117: adapter present but switched off — the iPhone can never discover
        // the head unit, so fail fast with an actionable message instead of the 60s
        // Wi-Fi wait ending in "Timed out waiting for iPhone to connect over Wi-Fi hotspot".
        // Issue #165: try enabling Bluetooth first — some head units start with BT off and
        // would otherwise never get past this check.
        if (!hybridBt.isEnabled()) {
            runCatching { hybridBt.enable() }
            val enableDeadline = System.currentTimeMillis() + 3000L
            while (!hybridBt.isEnabled() && System.currentTimeMillis() < enableDeadline) {
                try {
                    Thread.sleep(200L)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        if (!hybridBt.isEnabled()) {
            throw IOException(
                "Bluetooth is turned off on this head unit; wireless CarPlay requires Bluetooth " +
                    "for iPhone discovery. Turn on Bluetooth in the head unit system settings " +
                    "and retry."
            )
        }
    }

    /** Stage 2 of [runWireless]: arm the watchdog that fails the bring-up when no AirPlay TCP arrives. */
    private fun installFirstTcpWatchdog(generation: Int): FirstTcpWatchdog {
        val listenerIdentity = AirPlayListenerIdentity(generation)
        val watchdog = FirstTcpWatchdog(
            listener = listenerIdentity,
            schedule = { delay, action ->
                val future = startupTimer.schedule({ action() }, delay, TimeUnit.MILLISECONDS)
                val cancel: () -> Unit = { future.cancel(false); Unit }
                cancel
            },
            onTimeout = {
                if (!closed && generation == wirelessGeneration.get()) {
                    fail(
                        WirelessStartupException(
                            WirelessStartupFailure.FIRST_TCP_TIMEOUT,
                            "No AirPlay TCP after CarPlay StartSession",
                        ),
                        generation,
                    )
                    Thread({ closeWirelessStack(generation = generation) }, "diplay-startup-cleanup")
                        .apply { isDaemon = true; start() }
                }
            },
            log = { debugLog("wireless startup generation=$generation listener=${listenerIdentity.id} $it") },
        )
        firstTcpWatchdog = watchdog
        return watchdog
    }

    /** Cross-stage state produced while selecting the Bluetooth target. */
    private class BluetoothTarget(
        val device: BluetoothDevice?,
        val hostBluetoothMac: String,
        val wirelessAirPlayConfig: AirPlayConfig,
    )

    /** Stage 4 of [runWireless]: select the iPhone Bluetooth device and build the per-link AirPlay config. */
    private fun selectWirelessBluetoothTarget(deviceIdentifier: String): BluetoothTarget {
        val adapter = bluetoothAdapter
        val hybridAdapter = CarPlayBluetoothManager.get(appContext)
        // BT off already failed fast at bring-up start; do not burn seconds on enable().
        val device = try {
            if (adapter != null && (adapter.isEnabled || hybridAdapter.isEnabled())) {
                selectWirelessBluetoothDevice(adapter)
            } else {
                null
            }
        } catch (error: Throwable) {
            debugLog("wireless Bluetooth device selection not available: ${error.message}; proceeding with Wi-Fi discovery")
            null
        }
        val hostBluetoothMac = accessoryBluetoothMac(adapter)
        debugLog(
            "wireless selected Bluetooth target targetAddress=${device?.address ?: "none"} localBt=$hostBluetoothMac",
        )
        val wirelessAirPlayConfig = airPlayConfig.copy(
            deviceId = deviceIdentifier,
            btMac = hostBluetoothMac,
        )
        return BluetoothTarget(device, hostBluetoothMac, wirelessAirPlayConfig)
    }

    /** Cross-stage state produced once the AirPlay listener is attached. */
    private class AttachedAirPlay(
        val listenerPort: Int,
        val advertisedAirPlayConfig: AirPlayConfig,
    )

    /** Stage 5 of [runWireless]: bind the CarPlay AirPlay service to the chosen host address. */
    private fun attachWirelessAirPlayListener(
        generation: Int,
        hostAddress: InetAddress,
        wirelessAirPlayConfig: AirPlayConfig,
        watchdog: FirstTcpWatchdog,
        startedHotspot: WirelessHotspotManager?,
        mfi: MfiAuthenticator,
    ): AttachedAirPlay {
        val service = awaitWirelessListener()
            ?: throw IOException("Could not bind the CarPlay AirPlay service")
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) return AttachedAirPlay(-1, wirelessAirPlayConfig)
            startedHotspot?.validateReady()
            when (
                val result = service.attachWireless(
                    bindAddress = hostAddress,
                    config = wirelessAirPlayConfig,
                    identity = identity,
                    pairings = pairings,
                    mfi = mfi,
                    listener = wirelessSessionListener(generation, watchdog),
                    listenerIdentity = watchdog.listener,
                    media = media,
                    // KitKat Bonjour is single-family; extra accept threads cost RAM/CPU with no discovery gain.
                    additionalBindAddresses = emptyList(),
                )
            ) {
                is CarPlayWirelessService.AttachResult.Started -> Unit
                CarPlayWirelessService.AttachResult.AlreadyStarted ->
                    throw IOException("Wireless AirPlay transport is already attached")
                is CarPlayWirelessService.AttachResult.Failed ->
                    throw IOException(result.message)
            }
        }
        val listenerPort = service.boundPort() ?: wirelessAirPlayConfig.port
        val advertisedAirPlayConfig = wirelessAirPlayConfig.copy(port = listenerPort)
        debugLog(
            "wireless AirPlay listener attached bind=${WirelessHotspotStack.hostAddressText(hostAddress)} " +
                "port=$listenerPort" +
                (if (listenerPort != airPlayConfig.port) " (preferred ${airPlayConfig.port} in use)" else ""),
        )
        return AttachedAirPlay(listenerPort, advertisedAirPlayConfig)
    }

    /** Cross-stage state produced while building the iAP2 wireless endpoint. */
    private class WirelessEndpoint(
        val bootstrapIdentification: Iap2IdentificationConfig,
        val endpoint: Iap2WirelessCarPlayEndpoint,
    )

    /** Stage 6 of [runWireless]: build the iAP2 identification and CarPlay endpoint advertised to the iPhone. */
    private fun buildWirelessEndpoint(
        hotspotInfo: WirelessHotspotInfo,
        hostAddress: InetAddress,
        hostAddressText: String,
        listenerPort: Int,
        hostBluetoothMac: String,
    ): WirelessEndpoint {
        val wirelessIdentification = Iap2WirelessIdentification(hostBluetoothMac, hotspotInfo.ssid)
        val bootstrapIdentification = config.identification.forWirelessLink(
            Iap2WirelessLinkRole.BLUETOOTH_BOOTSTRAP,
            wirelessIdentification,
        )
        val runtimeIdentification = config.identification.forWirelessLink(
            Iap2WirelessLinkRole.RUNTIME_TUNNEL,
            wirelessIdentification,
        )
        val endpoint = Iap2WirelessCarPlayEndpoint(
            ssid = hotspotInfo.ssid,
            passphrase = hotspotInfo.passphrase,
            channel = hotspotInfo.channel,
            security = hotspotInfo.security,
            ipAddresses = listOf(hostAddressText),
            airPlayPort = listenerPort,
            deviceIdentifier = deviceIdentifierForEndpoint(hotspotInfo),
            publicKey = identity.publicKeyHex,
            sourceVersion = airPlayConfig.sourceVersion,
            accessPointBssid = hotspotInfo.accessPointBssid,
        )
        wirelessRuntimeIdentification = runtimeIdentification
        wirelessAirPlayEndpoint = endpoint
        debugLog(
            "wireless endpoint addressCount=${endpoint.ipAddresses.size} " +
                "family=${if (hostAddress is Inet6Address) "IPv6" else "IPv4"} " +
                "port=${endpoint.airPlayPort} channel=${endpoint.channel} security=${endpoint.security}",
        )
        media.setIapTunnelHandler(::startWirelessTunnelControl)
        return WirelessEndpoint(bootstrapIdentification, endpoint)
    }

    private fun deviceIdentifierForEndpoint(hotspotInfo: WirelessHotspotInfo): String =
        hotspotInfo.bssid
            ?.takeUnless { it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true) }
            ?: airPlayConfig.deviceId

    /** Stage 7 of [runWireless]: start Bonjour discovery on the same address family as the AirPlay listener. */
    private fun startBonjourDiscovery(
        generation: Int,
        advertisedAirPlayConfig: AirPlayConfig,
        hostAddress: InetAddress,
        startedHotspot: WirelessHotspotManager?,
        diagnostics: WirelessStartupDiagnostics,
    ) {
        val bonjourClient = CarPlayBonjour(
            context = appContext,
            config = advertisedAirPlayConfig,
            identity = identity,
            advertisedHost = hostAddress.hostAddress,
            // Bind discovery and its connect probe to the same AP/address family as AirPlay.
            // The car hotspot previously used system NSD, which could resolve another interface
            // or IPv6 while the listener/probe was bound to the AP's IPv4 address.
            // Some KitKat head units have no working servicediscovery system service.
            // Use bundled JmDNS instead of crashing in NsdManager.getMessenger().
            useInterfaceMdns = com.shihab.diplay.network.useBundledCarPlayMdns(
                com.shihab.diplay.compat.realSdkInt(),
            ),
            onEvent = { event -> debugLog("wireless bonjour: ${event.diagnosticSummary()}") },
            additionalAddresses = emptyList(),
        )
        synchronized(wirelessResourceLock) {
            if (isStaleWirelessRun(generation)) return
            bonjour = bonjourClient
            startedHotspot?.validateReady()
            bonjourClient.start()
        }
        diagnostics.start()
    }

    /** Stage 8 of [runWireless]: establish the iAP2 link over Bluetooth RFCOMM, or fall back to Wi-Fi discovery. */
    private fun runWirelessLinkEstablish(
        generation: Int,
        stageSetter: (String) -> Unit,
        device: BluetoothDevice?,
        hybridAdapter: HybridBluetoothAdapter,
        hotspot: WirelessHotspotStack.BringUp,
        endpoint: Iap2WirelessCarPlayEndpoint,
        bootstrapIdentification: Iap2IdentificationConfig,
        watchdog: FirstTcpWatchdog,
    ) {
        if (device != null) {
            if (
                WirelessIap2LinkRunner.runBluetoothRfcommBootstrap(
                    generation = generation,
                    device = device,
                    iap2IphoneUuid = IAP2_IPHONE_UUID,
                    wirelessResourceLock = wirelessResourceLock,
                    stage = stageSetter,
                    isStaleWirelessRun = ::isStaleWirelessRun,
                    debugLog = ::debugLog,
                    onStatus = { onStatus(it) },
                    logBluetoothConnectionSnapshot = ::logBluetoothConnectionSnapshot,
                    openRfcommStream = hybridAdapter::openRfcommStream,
                    setBluetoothStream = { bluetoothStream = it },
                    setCsm = { csm = it },
                    onArtworkTransfer = ::onArtworkTransfer,
                    validateHotspotReady = { hotspot.startedHotspot?.validateReady() },
                    mfi = hotspot.mfi,
                    identification = bootstrapIdentification,
                    endpoint = endpoint,
                    keepBluetoothControlAlive = ::keepBluetoothControlAlive,
                    startSessionSent = { watchdog.startSessionSent(it) },
                    onRouteFrame = ::onRouteFrame,
                    controlProgress = hotspot.diagnostics::controlProgress,
                    closeWirelessStack = { closeWirelessStack(generation = it) },
                    wirelessActiveReported = wirelessActiveReported,
                    wirelessHandoffRequested = wirelessHandoffRequested,
                    wirelessTunnelReady = wirelessTunnelReady,
                    readActiveSession = { activeSession },
                    tunnelChannel = { wirelessTunnelChannel },
                    armWirelessHandoffWatchdog = ::armWirelessHandoffWatchdog,
                    waitForWifiDiscovery = ::waitForWifiDiscovery,
                )
            ) {
                return
            }
        } else {
            debugLog("wireless Bluetooth bootstrap unavailable; waiting for AirPlay connection over Wi-Fi")
            waitForWifiDiscovery(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack(generation = generation)
                return
            }
            if (activeSession != null || wirelessActiveReported.get()) {
                debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                onStatus(CarPlayStatus.WirelessActive)
                return
            }
            throw IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot")
        }
    }

    private fun startWirelessTunnelControl(stream: BlockingDuplexByteStream): Boolean =
        WirelessIap2LinkRunner.startWirelessTunnelControl(
            stream = stream,
            closed = { closed },
            isWirelessTransport = { config.transport == CarPlayTransport.WIRELESS },
            readIdentification = { wirelessRuntimeIdentification },
            readEndpoint = { wirelessAirPlayEndpoint },
            readMfi = { mfiSession?.client },
            debugLog = ::debugLog,
            debugLogFailure = { message, error -> debugLog(message, error) },
            onArtworkTransfer = ::onArtworkTransfer,
            getTunnelChannel = { wirelessTunnelChannel },
            setTunnelChannel = { wirelessTunnelChannel = it },
            wirelessGeneration = wirelessGeneration,
            tunnelExecutor = tunnelExecutor,
            locationProvider = locationProvider,
            vehicleStatusProvider = vehicleStatusProvider,
            onWirelessTunnelReady = ::onWirelessTunnelReady,
            onRouteFrame = ::onRouteFrame,
            onStatus = { onStatus(it) },
            closeBestEffort = ::closeBestEffort,
        )

    private fun wirelessSessionListener(generation: Int, watchdog: FirstTcpWatchdog): AirPlaySessionListener =
        object : AirPlaySessionListener by sessionListener {
            @Volatile private var reportedFrameSession: AirPlaySession? = null

            override fun onTcpAccepted(event: AirPlayTcpAccepted) {
                if (isStaleWirelessRun(generation)) return
                if (watchdog.accepted(event)) wirelessDiagnostics?.connectionAccepted()
            }

            override fun onTransportError(message: String) {
                if (isStaleWirelessRun(generation)) return
                watchdog.terminate()
                sessionListener.onTransportError(message)
            }

            override fun onSessionActive(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || !watchdog.sessionEstablished()) return
                wirelessDiagnostics?.let {
                    it.sessionActive()
                    it.close()
                }
                wirelessConnectionProof.activate(generation, session)
                // Keep wirelessActiveReported false until maybeCompleteWirelessHandoff() closes
                // the Bluetooth bootstrap after the tunneled iAP2 channel is ready.
                sessionListener.onSessionActive(session)
            }

            override fun onSessionEnded(session: AirPlaySession) {
                if (isStaleWirelessRun(generation)) return
                wirelessConnectionProof.end(generation, session)
                sessionListener.onSessionEnded(session)
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                if (isStaleWirelessRun(generation) || activeSession !== session) return
                if (!watchdog.sessionEstablished()) return
                wirelessConnectionProof.rendered(generation, session)
                val firstFrame = synchronized(this) {
                    if (reportedFrameSession === session) false else {
                        reportedFrameSession = session
                        true
                    }
                }
                if (firstFrame || reportEveryVideoFrame) uiListener?.onVideoFrameRendered(session)
            }

            override fun onDebugLog(message: String) {
                if (isStaleWirelessRun(generation)) return
                sessionListener.onDebugLog(message)
            }

            // Passed on explicitly: without these the car's video player never opened over Wi-Fi.
            override fun onRemoteControlMessage(session: AirPlaySession, streamId: Long, message: Map<String, Any?>) =
                sessionListener.onRemoteControlMessage(session, streamId, message)

            override fun onVideoPlaybackUiRequested(session: AirPlaySession) =
                sessionListener.onVideoPlaybackUiRequested(session)
        }

    private fun onWirelessTunnelReady(generation: Int) =
        WirelessHandoffCoordinator.onTunnelReady(
            generation = generation,
            wirelessResourceLock = wirelessResourceLock,
            closed = { closed },
            isWirelessPhase = { phase == Phase.WIRELESS },
            wirelessGeneration = wirelessGeneration,
            wirelessFailureReported = wirelessFailureReported,
            wirelessTunnelReady = wirelessTunnelReady,
            wirelessHandoffRequested = wirelessHandoffRequested,
            markAuthenticated = { wirelessConnectionProof.authenticated(it) },
            debugLog = ::debugLog,
            maybeComplete = ::maybeCompleteWirelessHandoff,
        )

    /** One owner releases Bluetooth, including when a rendered-video fallback gains a tunnel later. */
    private fun maybeCompleteWirelessHandoff() =
        WirelessHandoffCoordinator.maybeComplete(
            wirelessResourceLock = wirelessResourceLock,
            closed = { closed },
            isWirelessPhase = { phase == Phase.WIRELESS },
            wirelessFailureReported = wirelessFailureReported,
            wirelessHandoffRequested = wirelessHandoffRequested,
            wirelessTunnelReady = wirelessTunnelReady,
            wirelessActiveReported = wirelessActiveReported,
            wirelessHandoffFellBack = wirelessHandoffFellBack,
            wirelessGeneration = wirelessGeneration,
            debugLog = ::debugLog,
            closeBluetoothBootstrapTransport = ::closeBluetoothBootstrapTransport,
            onWirelessActive = { onStatus(CarPlayStatus.WirelessActive, it) },
        )

    private fun keepBluetoothControlAlive(generation: Int): Boolean =
        WirelessHandoffCoordinator.keepBluetoothControlAlive(
            generation = generation,
            wirelessResourceLock = wirelessResourceLock,
            closed = { closed },
            isWirelessPhase = { phase == Phase.WIRELESS },
            wirelessGeneration = wirelessGeneration,
            wirelessFailureReported = wirelessFailureReported,
            hasActiveSession = { activeSession != null },
            hasRenderedFrame = { wirelessConnectionProof.hasRenderedFrame(it) },
        )

    private fun armWirelessHandoffWatchdog(generation: Int) =
        WirelessHandoffCoordinator.armWatchdog(
            generation = generation,
            postDelayed = { runnable, delay -> mainHandler.postDelayed(runnable, delay) },
            closed = { closed },
            isWirelessPhase = { phase == Phase.WIRELESS },
            wirelessGeneration = wirelessGeneration,
            wirelessHandoffRequested = wirelessHandoffRequested,
            wirelessActiveReported = wirelessActiveReported,
            debugLog = ::debugLog,
            onTimeout = ::handleWirelessHandoffTimeout,
        )

    private fun handleWirelessHandoffTimeout(generation: Int) =
        WirelessHandoffCoordinator.handleTimeout(
            generation = generation,
            wirelessResourceLock = wirelessResourceLock,
            closed = { closed },
            isWirelessPhase = { phase == Phase.WIRELESS },
            wirelessGeneration = wirelessGeneration,
            wirelessFailureReported = wirelessFailureReported,
            wirelessHandoffRequested = wirelessHandoffRequested,
            wirelessActiveReported = wirelessActiveReported,
            wirelessTunnelReady = wirelessTunnelReady,
            wirelessHandoffFellBack = wirelessHandoffFellBack,
            hasActiveSession = { activeSession != null },
            hasRenderedFrame = { wirelessConnectionProof.hasRenderedFrame(it) },
            debugLog = ::debugLog,
            onWirelessActiveFallback = { onStatus(CarPlayStatus.WirelessActiveFallback, it) },
            onHardFail = { gen ->
                flipWirelessAddressFamily()
                closeWirelessStack(generation = gen)
                fail(IOException("Wireless CarPlay handoff timed out waiting for tunnel iAP2"), gen)
            },
        )

    private fun flipWirelessAddressFamily() {
        if (config.wirelessHotspotMode == WirelessHotspotMode.WIFI_P2P) {
            debugLog("wireless address family flip skipped: Wi-Fi Direct is IPv4 only")
            return
        }
        val stored = appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            .getString(HOTSPOT_FAMILY_PREFERENCE_KEY, null)
        if (stored != null && stored != "AUTO") {
            debugLog("wireless address family flip ignored: user explicitly configured $stored")
            return
        }
        val published = wirelessPublishedAddress
        val alternate = wirelessAlternateAddress
        val from = published?.let {
            if (it is Inet6Address) HotspotAddressPolicy.FAMILY_IPV6 else HotspotAddressPolicy.FAMILY_IPV4
        }
        if (published == null || alternate == null || from == null) {
            debugLog(
                "wireless address family flip skipped published=${from ?: "none"} " +
                    "alternate=${alternate != null}",
            )
            return
        }
        val next = HotspotAddressPolicy.flipFamily(from)
        appContext.getSharedPreferences(WIRELESS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(HOTSPOT_FAMILY_PREFERENCE_KEY, next)
            .apply()
        debugLog("wireless address family flip published=$from next=$next")
    }

    private fun closeBluetoothBootstrapTransport() {
        val activeCsm = csm
        csm = null
        if (activeCsm != null) closeBestEffort("wireless CSM") { activeCsm.close() }

        val activeStream = bluetoothStream
        bluetoothStream = null
        if (activeStream != null) closeBestEffort("wireless RFCOMM stream") { activeStream.close() }

        val activeSocket = bluetoothSocket
        bluetoothSocket = null
        if (activeSocket != null) closeBestEffort("wireless Bluetooth socket") { activeSocket.close() }
    }

    private fun scheduleAvailabilityPoll(phase: Phase, attempt: Int = 0, check: () -> Unit) {
        val generation = availabilityPollGeneration.get()
        // First misses use a short interval so a freshly attached iPhone is seen quickly;
        // later polls back off to the steady 2s cadence to avoid USB binder spam.
        val delayMillis = when {
            attempt < 5 -> DEVICE_AVAILABILITY_FAST_POLL_MILLIS
            attempt < 10 -> DEVICE_AVAILABILITY_MID_POLL_MILLIS
            else -> DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS
        }
        mainHandler.postDelayed(
            {
                if (
                    !closed &&
                    this.phase == phase &&
                    generation == availabilityPollGeneration.get()
                ) {
                    check()
                }
            },
            delayMillis,
        )
    }



    private fun isBluetoothHandoffCommand(type: String): Boolean =
        type.equals("disableBluetooth", ignoreCase = true) ||
            type.equals("disable-bluetooth", ignoreCase = true)

    /**
     * Wait for an AirPlay session when Bluetooth bootstrap is unavailable.
     * Periodically re-checks SoftAP so a dropped hotspot fails fast instead of sitting out the full budget.
     */
    private fun waitForWifiDiscovery(generation: Int) {
        val deadline = System.currentTimeMillis() + WirelessStartupPolicy.WIFI_DISCOVERY_WAIT_MILLIS
        var nextValidateAt = 0L
        while (
            activeSession == null &&
            !wirelessActiveReported.get() &&
            System.currentTimeMillis() < deadline &&
            !isStaleWirelessRun(generation)
        ) {
            val now = System.currentTimeMillis()
            if (now >= nextValidateAt) {
                nextValidateAt = now + 5_000L
                runCatching { hotspot?.validateReady() }.onFailure { error ->
                    debugLog("Wi-Fi discovery aborted; hotspot no longer ready: ${error.message}")
                    throw error
                }
            }
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun isStaleWirelessRun(generation: Int): Boolean =
        closed || phase != Phase.WIRELESS || generation != wirelessGeneration.get() || wirelessFailureReported.get()

    // Kept across reconnects within this controller: resuming between attempts would start a scan.
    private fun pauseWifiScans(backend: WirelessHotspotBackend) = synchronized(this) {
        if (closed || !WifiScanPause.eligible(backend)) return@synchronized
        (wifiScanPause ?: WifiScanPause(appContext, ::debugLog).also { wifiScanPause = it }).pause()
    }

    private fun selectWirelessBluetoothDevice(adapter: BluetoothAdapter): BluetoothDevice {
        val bonded = adapter.bondedDevices.orEmpty()
        config.wirelessBluetoothDeviceAddress?.let { selected ->
            bonded.firstOrNull { it.address.equals(selected, ignoreCase = true) }?.let { return it }
            if (BluetoothAdapter.checkBluetoothAddress(selected)) {
                runCatching { adapter.getRemoteDevice(selected) }.getOrNull()?.let { return it }
            }
            debugLog("saved wireless device is no longer paired; falling back to current paired devices")
        }
        // 不按设备名判断是否为 iPhone（用户改名会失效）：只要唯一配对设备就直接连接。
        // 优先使用已连接的设备；否则唯一配对设备；多个/无则抛异常，由上层弹窗让用户选择。
        val hybridBt = CarPlayBluetoothManager.get(appContext)
        val connectedDevice = hybridBt.getConnectedDevice()
        if (connectedDevice != null && BluetoothAdapter.checkBluetoothAddress(connectedDevice.address)) {
            AppLog.i(
                TAG,
                "Using connected device from ${hybridBt.getAdapterName()}: " +
                    "${connectedDevice.name} (${connectedDevice.address})",
            )
            return adapter.getRemoteDevice(connectedDevice.address)
        }
        val pairedDevices = hybridBt.getPairedDevices()
        if (pairedDevices.size == 1 && BluetoothAdapter.checkBluetoothAddress(pairedDevices.single().address)) {
            AppLog.i(
                TAG,
                "Using single paired device from ${hybridBt.getAdapterName()}: " +
                    "${pairedDevices.single().name} (${pairedDevices.single().address})",
            )
            return adapter.getRemoteDevice(pairedDevices.single().address)
        }
        if (bonded.size == 1) return bonded.single()
        throw IOException(
            "No unambiguous paired device found; pair one device and retry",
        )
    }


    /** Reads cached service metadata only; it does not start/cancel discovery or require SCAN. */
    private fun logBluetoothConnectionSnapshot(device: BluetoothDevice, point: String) {
        try {
            val bondState = device.bondState
            val bondName = when (bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(IAP2_IPHONE_UUID)
            connectionDiagnostic(
                "Bluetooth snapshot point=$point enabled=${bluetoothAdapter?.isEnabled} " +
                    "bondState=$bondState bondName=$bondName cachedServicesReadable=${cachedServices.isSuccess} " +
                    "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                    "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}",
            )
        } catch (error: RuntimeException) {
            connectionDiagnostic("Bluetooth snapshot point=$point unavailable failureClass=${diagnosticFailureClass(error)}")
        }
    }

    private fun closeWirelessStack(service: CarPlayWirelessService? = wirelessListener, generation: Int? = null) =
        WirelessStackCloser.close(
            service = service,
            generation = generation,
            wirelessResourceLock = wirelessResourceLock,
            wirelessGeneration = wirelessGeneration,
            watchdog = { firstTcpWatchdog },
            takeDiagnostics = { wirelessDiagnostics.also { wirelessDiagnostics = null } },
            clearConnectionProof = { wirelessConnectionProof.clear() },
            clearTunnelHandler = { media.setIapTunnelHandler(null) },
            takeTunnel = { wirelessTunnelChannel.also { wirelessTunnelChannel = null } },
            closeBluetoothBootstrap = ::closeBluetoothBootstrapTransport,
            takeBonjour = { bonjour.also { bonjour = null } },
            takeHotspot = { hotspot.also { hotspot = null } },
            clearIdentification = {
                wirelessRuntimeIdentification = null
                wirelessAirPlayEndpoint = null
            },
            wirelessHandoffRequested = wirelessHandoffRequested,
            wirelessTunnelReady = wirelessTunnelReady,
            wirelessActiveReported = wirelessActiveReported,
            wirelessHandoffFellBack = wirelessHandoffFellBack,
            closeBestEffort = ::closeBestEffort,
        )

    private fun isBluetoothDeviceConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (error: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        AppLog.w(TAG, "Could not read Bluetooth connection state", error)
        false
    }

    private fun connectedBluetoothDevices(adapter: BluetoothAdapter): Set<BluetoothDevice> =
        buildSet {
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
            addAll(connectedBluetoothDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        }

    private fun <T : BluetoothProfile> connectedBluetoothDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    AppLog.w(TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(appContext, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            AppLog.w(TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }

    @Suppress("DEPRECATION")
    private fun accessoryBluetoothMac(adapter: BluetoothAdapter?): String {
        val managerAddress = try {
            CarPlayBluetoothManager.get(appContext).localAddress(appContext)
        } catch (_: Throwable) {
            null
        }
        val address = managerAddress ?: BluetoothAddressResolver.resolve(appContext)
        return address ?: airPlayConfig.btMac
    }

    private fun closeBestEffort(name: String, close: () -> Unit) {
        val started = System.nanoTime()
        var completed = false
        try {
            close()
            completed = true
        } catch (error: Throwable) {
            debugLog("$name teardown failed", error)
        } finally {
            connectionDiagnostic("teardown resource=$name completed=$completed elapsedMs=${elapsedMillis(started)}")
        }
    }


    private fun awaitWirelessListener(): CarPlayWirelessService? {
        wirelessListener?.let { return it }
        bindWirelessListener()
        return try {
            if (wirelessListenerLatch.await(WIRELESS_LISTENER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) wirelessListener else null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun bindWirelessListener() {
        if (wirelessListenerBound) return
        // A failed earlier bind may have counted down the previous latch. Reset it
        // before each new bind or a reconnect can observe a stale completed latch.
        wirelessListenerLatch = CountDownLatch(1)
        wirelessListenerBound = true
        try {
            val intent = Intent(appContext, CarPlayWirelessService::class.java)
            if (!appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)) {
                wirelessListenerBound = false
                wirelessListenerLatch.countDown()
            }
        } catch (_: Throwable) {
            wirelessListenerBound = false
            wirelessListenerLatch.countDown()
        }
    }

    private fun unbindWirelessListener() {
        if (!wirelessListenerBound) return
        wirelessListenerBound = false
        try {
            appContext.unbindService(serviceConnection)
        } catch (t: Exception) {
            // The service may have already been unbound.
            AppLog.w(TAG, "wireless listener unbindService failed", t)
        }
        wirelessListener = null
    }

    private fun closeReceivers() {
        // USB permission/attach receivers were removed along with the wired transport.
    }

    private fun closeMfiSession() {
        val session = mfiSession
        mfiSession = null
        if (session != null) {
            executor.execute {
                try {
                    session.close()
                } catch (t: Exception) {
                    // Best effort.
                    AppLog.w(TAG, "session close failed on executor", t)
                }
            }
        }
    }

    private fun fail(error: Throwable, generation: Int? = null) {
        synchronized(wirelessResourceLock) {
            if (closed || generation != null && generation != wirelessGeneration.get()) return
            val wireless = config.transport == CarPlayTransport.WIRELESS
            if (wireless) {
                if (!wirelessFailureReported.compareAndSet(false, true)) return
                firstTcpWatchdog?.terminate()
            }
            val seenCauses = HashSet<Throwable>()
            val causes = generateSequence(error) { it.cause }
                .takeWhile { seenCauses.add(it) }
                .toList()
            val wifiResetRequired = causes.any { it is com.shihab.diplay.network.P2pResetRequiredException } ||
                causes.any { it.message?.contains("needs a reset", ignoreCase = true) == true }
            onStatus(CarPlayStatus.Failed(error.message ?: error.javaClass.simpleName,
                wifiResetRequired = wifiResetRequired,
                causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.reason),
                if (wireless) wirelessGeneration.get() else null)
        }
    }

    private fun debugLog(message: String) {
        AppLog.i(TAG, message)
        try {
            uiListener?.onDebugLog(message)
        } catch (error: Exception) {
            AppLog.w(TAG, "debug log callback failed", error)
        }
    }

    private fun connectionDiagnostic(message: String) {
        try {
            // The redactor reserves "PHONE " for private phone-side log captures.
            val diagnosticPhase = phase.name
            debugLog("$CONNECTION_DIAGNOSTIC_PREFIX attempt=$diagnosticAttempt run=${diagnosticRun.get()} phase=$diagnosticPhase $message")
        } catch (t: Exception) {
            // Optional diagnostics must not change transport or shutdown behavior.
            AppLog.w(TAG, "connection diagnostic log failed", t)
        }
    }

    private fun diagnosticFailureClass(error: Throwable): String =
        error.javaClass.simpleName.take(80).replace(Regex("[^A-Za-z0-9_$]"), "?")

    private fun elapsedMillis(startedNanos: Long): Long =
        ((System.nanoTime() - startedNanos) / 1_000_000L).coerceAtLeast(0)

    private fun debugLog(message: String, error: Throwable) {
        val safeTrace = runCatching { error.safeStackTrace(maxCauses = 4, maxFramesPerCause = 30) }.getOrNull()
        if (safeTrace != null) {
            AppLog.w(TAG, "$message: ${describeThrowable(error)}\n$safeTrace")
        } else {
            AppLog.w(TAG, "$message: ${describeThrowable(error)}")
        }
        try {
            uiListener?.onDebugLog("$message: ${describeThrowable(error)}")
        } catch (callbackError: Exception) {
            AppLog.w(TAG, "debug log callback failed", callbackError)
        }
    }

    /**
     * Renders a throwable and its cause chain with the top stack frame of each, so failures that
     * are wrapped (e.g. "USBMUX read failed" hiding the real cause) are diagnosable from the session
     * log alone, without logcat. Bounded in depth; the redactor still runs on the result.
     */
    private fun describeThrowable(error: Throwable): String {
        val builder = StringBuilder()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 5) {
            if (depth > 0) builder.append(" <- ")
            builder.append(current.javaClass.simpleName)
            current.message?.let { builder.append(": ").append(it) }
            current.stackTrace.firstOrNull()?.let { frame ->
                builder.append(" @ ").append(frame.className.substringAfterLast('.'))
                    .append('.').append(frame.methodName)
                    .append('(').append(frame.fileName).append(':').append(frame.lineNumber).append(')')
            }
            current = current.cause
            depth++
        }
        return builder.toString()
    }

    private fun onStatus(status: CarPlayStatus, generation: Int? = null) {
        if (closed) return
        mainHandler.post {
            if (!closed && (generation == null || generation == wirelessGeneration.get()) && status != lastReportedStatus) {
                lastReportedStatus = status
                connectionDiagnostic("stage=${status.javaClass.simpleName}")
                uiListener?.onDebugLog(status.debugLogMessage())
                uiStatusReporter?.invoke(status)
            }
        }
    }

    private fun CarPlayStatus.debugLogMessage(): String = when (this) {
        CarPlayStatus.DiscoveringMfi ->
            "STEP mfi/start: preparing the configured MFi authentication provider"
        CarPlayStatus.WaitingForMfi ->
            "STEP mfi/wait: MFi coprocessor not present; polling"
        CarPlayStatus.RequestingMfiPermission ->
            "STEP mfi/permission: requesting CH341 USB access"
        CarPlayStatus.MfiReady ->
            "STEP mfi/ready: MFi authentication provider is ready"
        CarPlayStatus.StartingHotspot ->
            "STEP wifi/ap: starting the wireless CarPlay access point"
        is CarPlayStatus.HotspotReady ->
            "STEP wifi/ap-ready: backend=$backend ssid=$ssid band=$band " +
                "channel=$channel bssid=$bssid address=$address"
        CarPlayStatus.WaitingForPairedIphone ->
            "STEP bt/select: waiting for a paired or connected iPhone"
        CarPlayStatus.ConnectingBluetooth ->
            "STEP bt/rfcomm: connecting to the iPhone iAP2 RFCOMM service"
        CarPlayStatus.RunningWireless ->
            "STEP iap2/wireless: Bluetooth control loop running"
        CarPlayStatus.WirelessActive ->
            "STEP handoff/complete: tunnel iAP2 ready; Bluetooth bootstrap released"
        CarPlayStatus.WirelessActiveFallback ->
            "STEP handoff/fallback: rendered video preserved; tunnel iAP2 unavailable; Bluetooth bootstrap preserved"
        CarPlayStatus.DiscoveringIphone ->
            "STEP usb/discover: searching for an iPhone USB device"
        CarPlayStatus.WaitingForIphone ->
            "STEP usb/wait: iPhone USB device not present; polling"
        CarPlayStatus.RequestingIphonePermission ->
            "STEP usb/permission: requesting USB access to the iPhone"
        CarPlayStatus.WaitingForReenumeration ->
            "STEP usb/reenum: waiting for the CarPlay USB configuration"
        CarPlayStatus.SelectingConfiguration ->
            "STEP usb/config: selecting the iPhone CarPlay configuration"
        CarPlayStatus.OpeningDataPaths ->
            "STEP usb/data: opening iAP2 and NCM USB data paths"
        CarPlayStatus.Pairing ->
            "STEP lockdown/pair: loading or creating the pairing record"
        CarPlayStatus.ConnectingControl ->
            "STEP lockdown/carkit: opening com.apple.carkit.service"
        CarPlayStatus.AttachingNetwork ->
            "STEP network/attach: attaching the AirPlay network transport"
        CarPlayStatus.RunningControl ->
            "STEP iap2/wired: wired iAP2 control loop running"
        CarPlayStatus.ControlEnded ->
            "STEP control/end: the control window ended"
        is CarPlayStatus.Failed ->
            "ERROR $message"
    }

    companion object {
        private const val TAG = "xcertplay"
        const val CONNECTION_DIAGNOSTIC_PREFIX = "CONNECTION_DIAGNOSTIC"
        // Indices in the telephony HID report (AirPlayHid.telephonyDescriptor).
        private const val TELEPHONY_HOOK_SWITCH = 1
        private const val TELEPHONY_DROP = 3
        private val diagnosticAttempts = AtomicInteger()
        private const val IAP2_IPHONE_UUID = "00000000-deca-fade-deca-deafdecacafe"
        private const val WIRELESS_PREFS = "diplay_wireless"
        private const val HOTSPOT_FAMILY_PREFERENCE_KEY = "hotspot_address_family"
        private const val WIRELESS_LISTENER_TIMEOUT_MILLIS = 10_000L
        private const val DEVICE_AVAILABILITY_FAST_POLL_MILLIS = 400L
        private const val DEVICE_AVAILABILITY_MID_POLL_MILLIS = 800L
        private const val DEVICE_AVAILABILITY_POLL_INTERVAL_MILLIS = 2_000L
        /** Matches [AndroidBluetoothAdapter] KitKat RFCOMM budget so the waiter does not cut short. */
        private const val RFCOMM_CONNECT_TIMEOUT_LEGACY_MILLIS = 18_000L
        private const val EXECUTOR_CLOSE_TIMEOUT_MILLIS = 2_000L
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
        private const val WIRED_PERMISSION_POLL_TIMEOUT_MILLIS = 10_000L
        private const val WIRED_PERMISSION_POLL_INTERVAL_MILLIS = 400L
        // 60s: the iPhone trust dialog appears only when unlocked/awake; a locked or slow-unlock
        // phone misses the old 15s budget. Per-step wait lives in LockdownPairingClient (10s).
        private const val WIRED_PAIR_TIMEOUT_MILLIS = 60_000L
        // Retained for reference; the live iAP2 loop now uses NO_TIMEOUT_MILLIS so a fixed 60s
        // deadline cannot stop the control channel after a successful connect.
        private const val WIRED_CONTROL_TIMEOUT_MILLIS = 60_000L
        private const val MAX_WIRED_REENUMERATION_ATTEMPTS = 3
        private const val WIRED_REENUMERATION_RETRY_MILLIS = 250L
        private const val WIRED_REENUMERATION_POLL_MILLIS = 250L
        /** Poll once considered settled: a fresh device id or a ready config (or after 800ms). */
        private const val WIRED_REENUMERATION_READY_EARLY_MILLIS = 800L
        private const val WIRED_REENUMERATION_TIMEOUT_MILLIS = 15_000L
    }
}
