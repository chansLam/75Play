package com.shihab.diplay.host

import com.shihab.diplay.CarPlayHostActivity

import android.content.Intent
import android.view.View
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AsyncDiagnosticLog
import com.shihab.diplay.CarPlayConnectService
import com.shihab.diplay.CarPlayMediaKeys
import com.shihab.diplay.CarPlayVideo
import com.shihab.diplay.CarPlayViewAreas
import com.shihab.diplay.DiagnosticRedactor
import com.shihab.diplay.DiPlaySessionService
import com.shihab.diplay.DisplayDiagnosticSnapshot
import com.shihab.diplay.ThemeModeDiagnostics
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.CarPlayMediaEngine
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.network.WirelessStartupPolicy
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.session.CarPlaySessionFactory
import com.shihab.diplay.session.CarPlaySessionDisplay
import com.shihab.diplay.util.AppLog


/**
 * Adopt, start, and reconnect for the CarPlay host. The Activity keeps the same private
 * method names so lifecycle tests can still call them.
 */
internal class HostSessionLifecycle(private val host: CarPlayHostActivity) {
    private var reconnectRunnable: Runnable? = null

    private companion object {
        const val TAG = "xcertplay-usb"
        const val RECONNECT_DELAY_MILLIS = 2_000L
        const val IAP_TUNNEL_RECONNECT_DELAY_MILLIS = 15_000L
        const val CONTROLLER_CLOSE_TIMEOUT_MILLIS = 4_000L
        const val PROTOCOL_TRACE_PREFIX = "TRACE "
    }

    private fun createMediaSink(videoWidth: Int, videoHeight: Int, controllerGeneration: Int): com.shihab.diplay.media.AndroidMediaSink {
        with(host) {

        // Capture this session's log: late decoder shutdown must not write into a new session.
        val diagnosticLog = sessionLog
        return AndroidMediaSink(
            surface = null,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            preferSoftwareHevcDecoder = hevcSoftwareDecoderEnabled,
            advancedAudioChannelMapping = advancedAudioChannelMapping,
            audioFocusEnabled = AirPlayPersistence.loadAudioFocusEnabled(this),
            audioFocusAutoYield = AirPlayPersistence.loadAudioFocusAutoYield(this),
            mediaChannel = AirPlayPersistence.loadMediaAudioChannel(this),
            navigationChannel = AirPlayPersistence.loadNavigationAudioChannel(this),
            callChannel = AirPlayPersistence.loadCallAudioChannel(this),
            context = this,
            navigationStreamType = navigationStreamType,
            onScreenStreamActiveChanged = { type, active ->
                onScreenStreamStateChanged(controllerGeneration, type, active)
            },
            mediaBufferMillis = AirPlayPersistence.loadMediaBufferMillis(this),
            onAudioDiagnostic = { message ->
                if (message.startsWith("Microphone: ")) {
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                } else {
                    diagnosticLog?.append(formattedLogLine(message, System.currentTimeMillis()))
                }
            },
            onMediaAudioChanged = CarPlayMediaKeys::onMediaAudioChanged,
            callEchoCancellation = AirPlayPersistence.loadCallEchoCancellation(this),
            callVoiceFilter = AirPlayPersistence.loadCallVoiceFilter(this),
            callSpeakerphone = { AirPlayPersistence.loadCallSpeakerphone(this) },
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = CarPlaySessionFactory.videoPacingDelayMillis(smoothVideo, fps),
        )
            }
    }

    private fun createMediaEngine(sink: com.shihab.diplay.media.AndroidMediaSink): com.shihab.diplay.airplay.CarPlayMediaEngine = with(host) {

        CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneAvailable,
            audioCaptureDirectory = audioCaptureDirectory(),
            callSpeakerphone = AirPlayPersistence.loadCallSpeakerphone(this),
        )
    }

    internal fun createSessionListener(controllerGeneration: Int): com.shihab.diplay.airplay.AirPlaySessionListener = with(host) {
        object : AirPlaySessionListener {
            private val diagnosticLog = sessionLog

            override fun onSessionActive(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != wirelessStartup.generation) {
                        return@runOnUiThread
                    }
                    activeAirPlaySession = session
                    CarPlayBackgroundSession.active = true
                    reconnectAttempts = 0
                    logThemeState(ThemeModeDiagnostics.Source.SESSION_ACTIVE, resources.configuration)
                    syncAirPlayDarkMode(ThemeModeDiagnostics.Source.SESSION_ACTIVE)
                    appendLog("AirPlay session active")
                }
            }

            override fun onHostUiRequested(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != wirelessStartup.generation || shuttingDown.get()) return@runOnUiThread
                    appendLog("CarPlay OEM button: opening system apps panel")
                    openSystemAppsMenu()
                }
            }

            override fun onVideoFrameRendered(session: AirPlaySession) {
                runOnUiThread {
                    if (controllerGeneration != wirelessStartup.generation || activeAirPlaySession !== session || shuttingDown.get()) return@runOnUiThread
                    hideVideoLoading()
                    if (!wirelessStartup.firstFrame(session, android.os.SystemClock.elapsedRealtime())) return@runOnUiThread
                    mainHandler.postDelayed({
                        if (controllerGeneration == wirelessStartup.generation && activeAirPlaySession === session &&
                            !shuttingDown.get() && CarPlayBackgroundSession.isOwner(host) &&
                            wirelessStartup.resetIfStable(session, android.os.SystemClock.elapsedRealtime())) {
                            appendLog("wireless startup retry budget reset after stable video session")
                        }
                    }, WirelessStartupPolicy.STABLE_SESSION_MILLIS)
                }
            }

            override fun onSessionEnded(session: AirPlaySession) {
                runOnUiThread {
                    val active = activeAirPlaySession
                    if (active != null && active !== session) {
                        // 探针或非活动握手结束时，保留当前播放会话。
                        appendLog("Non-active AirPlay connection ended; keeping the live session")
                        return@runOnUiThread
                    }
                    if (wirelessStartup.isStale(controllerGeneration)) return@runOnUiThread
                    wirelessStartup.disconnected()
                    activeAirPlaySession = null
                    CarPlayBackgroundSession.active = false
                    activeScreenStreamTypes.clear()
                    showVideoLoading()
                    setConnectionStage(getString(R.string.carplay_session_ended_reconnecting))
                    appendLog("AirPlay session ended; reconnecting from scratch")
                    reconnectAfterLoss("AirPlay session ended")
                }
            }

            override fun onTransportError(message: String) {
                runOnUiThread {
                    if (wirelessStartup.isStale(controllerGeneration)) return@runOnUiThread
                    wirelessStartup.disconnected()
                    activeScreenStreamTypes.clear()
                    showVideoLoading()
                    setConnectionStage(getString(R.string.transport_error_reconnecting))
                    appendLog("CarPlay transport error: $message; reconnecting from scratch")
                    reconnectAfterLoss("CarPlay transport error: $message")
                }
            }

            override fun onDebugLog(message: String) {
                if (DiagnosticRedactor.redact(message) == null) return
                if (message.startsWith(CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX + " ")) {
                    // Retain old-controller teardown evidence without accepting its UI/session state.
                    AsyncDiagnosticLog.append(diagnosticLog, message)
                    return
                }
                runOnUiThread {
                    if (controllerGeneration != wirelessStartup.generation) {
                        return@runOnUiThread
                    }
                    DisplayDiagnosticSnapshot.record(host, displayDiagnosticAttempt, message)
                    if (message.startsWith(PROTOCOL_TRACE_PREFIX)) {
                        appendFileLog(message)
                    } else {
                        appendLog(message)
                    }
                }
            }
        }
    }

    internal fun createStatusReporter(controllerGeneration: Int): (com.shihab.diplay.orchestration.CarPlayStatus) -> Unit = with(host) {
        report@{ status ->
        if (controllerGeneration != wirelessStartup.generation) return@report
        updateHotspotStatus(status)
        val description = status.describe()
        // Once the retries stopped, a later failure of the same attempt, such as its cleanup, keeps the line saying so.
        if (status is CarPlayStatus.Failed) { if (!wirelessStartup.stopped) setFailedConnectionStage(status.message) }
        else setConnectionStage(description, exact = true)
        when (status) {
            is CarPlayStatus.Failed -> {
                if (status.wifiResetRequired) {
                    returnAfterStartupFailure(description)
                } else {
                    if (status.startupFailure != null) {
                        if (startupFailureGeneration == controllerGeneration) return@report
                        startupFailureGeneration = controllerGeneration
                    }
                    reconnectAfterLoss(description, status.startupFailure)
                }
            }
            else -> Unit
        }
            }
    }

    fun adopt(): Boolean {
        with(host) {

        val snapshot = CarPlayBackgroundSession.snapshot() ?: return false
        if (snapshot.controller.isClosed()) {
            CarPlayBackgroundSession.clear(snapshot.controller)
            return false
        }
        // The sink's pacing is fixed when it is built; a session from before a Smooth video change (for
        // example one whose reconnect stopped at a prerequisite) is stopped instead of adopted. Callers
        // retry, and the next start matches this view.
        if (!backgroundSessionMatchesView(snapshot.sink.videoPacingEnabled, smoothVideo)) {
            appendLog("Background session smooth video=${snapshot.sink.videoPacingEnabled} differs from this view; " +
                "stopping it instead of adopting it")
            CarPlayBackgroundSession.stop { mainHandler.post { if (!isDestroyed) maybeStart() } }
            return false
        }
        displayDiagnosticAttempt = DisplayDiagnosticSnapshot.currentAttempt(this)
        controller = snapshot.controller
        sink = snapshot.sink
        sessionDisplay = snapshot.display
        resetSidePanel()
        if (snapshot.display.viewAreas?.let { it.kindOf(it.current) == CarPlayViewAreas.Kind.SIDE_PANEL } == true) {
            // The live stream can outlast its Activity. Restore the Android view covering its
            // unused strip instead of leaving a blank third when a new host adopts the session.
            sidePanelShown = true
            sidePanel?.visibility = View.VISIBLE
            sidePanelTick.run()
        }
        CarPlayBackgroundSession.store(snapshot.controller, snapshot.sink, snapshot.width, snapshot.height,
            this, snapshot.display) { completion ->
            runOnUiThread {
                sessionShutdown(false, "DiPlay disconnect", completion)
                finish()
            }
        }
        if (snapshot.width > 0 && snapshot.height > 0) {
            activeDisplaySize = CarPlayHostActivity.DisplaySize(snapshot.width, snapshot.height)
        }
        videoView?.let { sessionUpdateVideoLayout(it.width, it.height) }
        videoView?.post {
            val view = videoView ?: return@post
            if (view.width > 0 && view.height > 0) {
                sessionScheduleDisplaySize(view.width, view.height)
            }
        }

        val generation = wirelessStartup.generation
        snapshot.controller.attachUi(
            createSessionListener(generation),
            createStatusReporter(generation),
        )
        if (snapshot.controller.activeAirPlaySessionToken() != null) {
            CarPlayBackgroundSession.active = true
        }
        snapshot.sink.setScreenStreamActiveChangedListener { type, active ->
            onScreenStreamStateChanged(wirelessStartup.generation, type, active)
        }
        snapshot.controller.reportEveryVideoFrame = host.wantsVideoFrameNotifications()
        currentSurface?.let(::attachSurface)
        val serviceReused = snapshot.controller.hasActiveAirPlayAttachment()
        appendLog(
            if (serviceReused) {
                "Reusing existing background CarPlay service"
            } else {
                "Reusing existing background CarPlay session"
            },
        )
        setConnectionStage(
            if (serviceReused) {
                getString(R.string.carplay_service_already_running)
            } else {
                getString(R.string.carplay_session_already_running)
            },
        )
        updateDebugOverlays()
        // The background connect service handed us a live controller; it no longer owns the
        // foreground notification and can exit (owner already swapped to `this` above).
        runCatching { stopService(Intent(this, CarPlayConnectService::class.java)) }
        return true
            }
    }

    fun start(size: CarPlayHostActivity.DisplaySize) {
        with(host) {

        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress || controller != null) return
        if (wirelessEnabled) {
            val hotspotError = manualHotspotSettingsError()
            if (hotspotError != null) {
                appendLog("Wireless CarPlay configuration incomplete: $hotspotError")
                setConnectionStage(hotspotError)
                return
            }
        }
        val controllerGeneration = wirelessStartup.generation
        val config = sessionCreateRuntimeConfig()
        val effectiveSize = if (isMultiWindowActive() && !AirPlayPersistence.loadAdaptPipResolution(this) &&
            maximumDetectedWidthPixels >= size.width && maximumDetectedHeightPixels >= size.height &&
            (maximumDetectedWidthPixels > size.width || maximumDetectedHeightPixels > size.height)) {
            CarPlayHostActivity.DisplaySize(maximumDetectedWidthPixels, maximumDetectedHeightPixels)
        } else {
            size
        }
        val airPlayConfig = sessionCreateAirPlayConfig(effectiveSize)
        val locationProvider = CarPlaySessionFactory.locationProvider(this, config.locationReportingEnabled)
        appendLog(
            "Starting CarPlay controller at ${size.width}x${size.height} -> " +
                "${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "(${displayScalePercent}%) " +
                "physical=${airPlayConfig.main.widthPhysicalMm}x" +
                "${airPlayConfig.main.heightPhysicalMm}mm " +
                "video=${if (airPlayConfig.hevc) "HEVC" else "H.264"} " +
                "decoder=${if (airPlayConfig.hevc && hevcSoftwareDecoderEnabled) "software" else "hardware"} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${if (config.locationReportingEnabled) "enabled" else "disabled"}" +
                "${if (config.identification.vehicleSpeedEnabled) "+wheel-speed" else ""} " +
                "mfi=${mfiTargetLabel(config.mfiTarget)}",
        )
        AppLog.i(
            TAG,
            "starting controller display=${size.width}x${size.height} " +
                "negotiated=${airPlayConfig.main.widthPixels}x${airPlayConfig.main.heightPixels} " +
                "scale=${displayScalePercent}% " +
                "hevc=${airPlayConfig.hevc} " +
                "softwareHevc=${airPlayConfig.hevc && hevcSoftwareDecoderEnabled} " +
                "microphone=${airPlayConfig.microphone} " +
                "location=${config.locationReportingEnabled} " +
                "mfi=${config.mfiTarget}",
        )
        val renderer = createMediaSink(
            videoWidth = airPlayConfig.main.widthPixels,
            videoHeight = airPlayConfig.main.heightPixels,
            controllerGeneration = controllerGeneration,
        )
        sink = renderer
        currentSurface?.let(::attachSurface)
        val media = createMediaEngine(renderer)
        val pairings = AirPlayPersistence.loadPairings(this) { id, key ->
            AirPlayPersistence.savePairing(this, id, key)
        }
        val next = CarPlayController(
            context = this,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = airPlayIdentity,
            pairings = pairings,
            listener = createSessionListener(controllerGeneration),
            media = media,
            reportStatus = createStatusReporter(controllerGeneration),
            locationProvider = locationProvider,
            vehicleStatusProvider = null,
        )
        controller = next
        next.reportEveryVideoFrame = wantsVideoFrameNotifications()
        resetSidePanel() // a new session starts without the side panel
        CarPlaySessionFactory.attachMediaKeys(this, next)
        if (airPlayConfig.videoInCar) CarPlayVideo.attach(this, next)
        val display = CarPlaySessionDisplay(
            airPlayConfig.main.widthPixels, airPlayConfig.main.heightPixels,
            displayRotation(), hideTopBar, hideBottomBar, effectiveSize.width, effectiveSize.height,
            viewAreas = pendingViewAreas,
        )
        sessionDisplay = display
        videoView?.let { sessionUpdateVideoLayout(it.width, it.height) }
        CarPlayBackgroundSession.store(next, renderer, size.width, size.height, this, display) { completion ->
            runOnUiThread {
                sessionShutdown(terminateProcess = false, reason = "DiPlay disconnect", completion = completion)
                finish()
            }
        }
        try {
            val service = Intent(this, DiPlaySessionService::class.java)
            startService(service)
            next.start()
        } catch (error: RuntimeException) {
            appendLog("Connection could not start: ${error.javaClass.simpleName}")
            sessionShutdown(false, "foreground service could not start")
            setConnectionStage(getString(R.string.could_not_start_carplay_return_to_diplay_and_check_app_per))
        }
            }
    }

    fun maybeStart() {
        with(host) {

        if (shuttingDown.get()) return
        if (CarPlayBackgroundSession.hasSession() && !CarPlayBackgroundSession.isOwner(this)) {
            if (!adopt()) mainHandler.postDelayed({ maybeStart() }, 500)
            return
        }
        if (controller == null && adopt()) return
        // The video view is chosen once per activity; a changed video surface setting needs a new one.
        if (controller == null && (AirPlayPersistence.loadSmoothVideo(this) != smoothVideo ||
            AirPlayPersistence.loadDirectSurfaceView(this) != directSurfaceView)) {
            appendLog("Video surface setting changed; rebuilding the video view")
            // No session runs here, but a restart keeps this host as the session owner; the new instance
            // must be able to start its own.
            if (CarPlayBackgroundSession.isOwner(this)) CarPlayBackgroundSession.clear()
            recreate()
            return
        }
        val size = activeDisplaySize ?: return
        val transportReady = if (wirelessEnabled) wirelessPermissionsReady else vpnReady
        val locationReady = !locationReportingEnabled || locationPermissionAvailable
        if (
            !transportReady ||
            !locationReady ||
            !microphonePermissionResolved ||
            shuttingDown.get() ||
            handshakeResetInProgress ||
            pendingDisplaySize != null ||
            controller != null
        ) {
            return
        }
        start(size)
            }
    }

    /** Drop this Activity as session owner without closing the controller. */
    fun parkForShell() {
        with(host) {
            if (sessionTeardownStarted.get()) return
            shuttingDown.set(true)
            reconnectRunnable?.let { mainHandler.removeCallbacks(it) }
            reconnectRunnable = null
            reconnectScheduled = false
            if (!CarPlayBackgroundSession.isOwner(this)) return
            controller?.attachUi(object : AirPlaySessionListener {}, { _ -> })
            CarPlayBackgroundSession.park(this)
        }
    }

    fun reconnectAfterLoss(reason: String, startupFailure: com.shihab.diplay.network.WirelessStartupFailure? = null) {
        with(host) {

        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || isFinishing || isDestroyed || handshakeResetInProgress || wirelessStartup.stopped) return
        if (reconnectScheduled) return
        val startupDelay = if (startupFailure != null && startupFailure != WirelessStartupFailure.HOTSPOT_CONFIGURATION)
            wirelessStartup.nextDelayMillis() else null
        if (startupFailure != null && startupDelay == null) {
            wirelessStartup.stop()
            val message = if (startupFailure == WirelessStartupFailure.HOTSPOT_CONFIGURATION)
                "${getString(R.string.hotspot_network_not_ready)}\n${getString(R.string.wireless_startup_retries_exhausted)}"
                else "$reason\n${getString(R.string.wireless_startup_retries_exhausted)}"
            appendLog("wireless startup recovery stopped generation=${wirelessStartup.generation} reason=$startupFailure retries=${wirelessStartup.budget.retries}")
            returnAfterStartupFailure(message)
            return
        }
        reconnectScheduled = true
        val generation = wirelessStartup.generation
        val delayMillis = if (startupDelay != null) {
            startupDelay
        } else if (reason.contains("AirPlay iAP tunnel", ignoreCase = true)) {
            IAP_TUNNEL_RECONNECT_DELAY_MILLIS
        } else {
            (RECONNECT_DELAY_MILLIS * (1L shl reconnectAttempts.coerceAtMost(4))).coerceAtMost(30_000L)
        }
        reconnectAttempts += 1
        appendLog("$reason; retrying in ${delayMillis}ms generation=$generation startupFailure=$startupFailure startupRetries=${wirelessStartup.budget.retries}")
        val task = Runnable {
            reconnectRunnable = null
            reconnectScheduled = false
            if (
                shuttingDown.get() ||
                isFinishing ||
                isDestroyed ||
                handshakeResetInProgress ||
                wirelessStartup.isStale(generation)
            ) {
                return@Runnable
            }
            restart("Reconnecting after $reason")
        }
        reconnectRunnable = task
        mainHandler.postDelayed(task, delayMillis)
            }
    }

    fun restart(reason: String) {
        with(host) {

        if (!CarPlayBackgroundSession.isOwner(this)) return
        if (shuttingDown.get() || handshakeResetInProgress) return
        val size = activeDisplaySize ?: return
        wirelessStartup.disconnected()
        appendLog(reason)
        activeScreenStreamTypes.clear()
        setConnectionStage(reason)
        AppLog.i(TAG, "$reason; rebuilding stack at ${size.width}x${size.height}")
        val generation = wirelessStartup.bumpGeneration()
        handshakeResetInProgress = true
        val oldController = controller
        val oldSink = sink
        CarPlayMediaKeys.detach(oldController)
        CarPlayBackgroundSession.clear(oldController, keepOwner = true)
        controller = null
        oldSink?.let(retiringSinks::add)
        sink = null
        sessionDisplay = null
        val diagnosticLog = sessionLog
        teardownExecutor.execute {
            val started = System.nanoTime()
            oldController?.close()
            val completed = oldController?.awaitClosed(CONTROLLER_CLOSE_TIMEOUT_MILLIS) ?: true
            AsyncDiagnosticLog.append(
                diagnosticLog,
                "${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} generation=$generation " +
                    "restart teardownWaitCompleted=$completed " +
                    "elapsedMs=${((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0)}",
            )
            oldSink?.let(::closeRetiringSink)
            runOnUiThread {
                if (!shuttingDown.get() && generation == wirelessStartup.generation) {
                    handshakeResetInProgress = false
                    // The display can rotate again while the old stack is closing. Use the
                    // latest accepted size, and wait for any pending resize to settle first.
                    maybeStart()
                }
            }
        }
            }
    }
}
