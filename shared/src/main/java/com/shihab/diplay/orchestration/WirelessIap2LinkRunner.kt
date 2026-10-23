package com.shihab.diplay.orchestration

import android.bluetooth.BluetoothDevice
import com.shihab.diplay.airplay.AirPlaySession
import com.shihab.diplay.iap2.session.Iap2Session
import com.shihab.diplay.iap2.wire.Iap2Frame
import com.shihab.diplay.mfi.Iap2MfiAuthenticationClient
import com.shihab.diplay.mfi.MfiAuthenticator
import com.shihab.diplay.transport.BlockingDuplexByteStream
import com.shihab.diplay.transport.Iap2ArtworkTransfer
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LocationProvider
import com.shihab.diplay.transport.Iap2WirelessCarPlayEndpoint
import com.shihab.diplay.transport.Iap2WirelessControlClient
import com.shihab.diplay.transport.Iap2WirelessControlResult
import com.shihab.diplay.transport.Iap2WirelessControlTerminal
import com.shihab.diplay.transport.VehicleStatusProvider
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wireless RFCOMM bootstrap and type-130 tunnel iAP2 control extracted from [CarPlayController].
 *
 * Lock ownership stays with the controller: [wirelessResourceLock] and field access are injected so
 * call order, diagnostic strings, and failure paths are unchanged.
 */
internal object WirelessIap2LinkRunner {
    private const val WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS = 90_000L

    /**
     * Bluetooth RFCOMM connect, wireless iAP2 open, and bootstrap terminal handling.
     *
     * @return true when the caller should return from its wireless run (the original non-local returns).
     */
    fun runBluetoothRfcommBootstrap(
        generation: Int,
        device: BluetoothDevice,
        iap2IphoneUuid: String,
        wirelessResourceLock: Any,
        stage: (String) -> Unit,
        isStaleWirelessRun: (Int) -> Boolean,
        debugLog: (String) -> Unit,
        onStatus: (CarPlayStatus) -> Unit,
        logBluetoothConnectionSnapshot: (BluetoothDevice, String) -> Unit,
        openRfcommStream: (String, String) -> BlockingDuplexByteStream,
        setBluetoothStream: (BlockingDuplexByteStream?) -> Unit,
        setCsm: (Iap2Session) -> Unit,
        onArtworkTransfer: (Iap2ArtworkTransfer) -> Unit,
        validateHotspotReady: () -> Unit,
        mfi: MfiAuthenticator,
        identification: Iap2IdentificationConfig,
        endpoint: Iap2WirelessCarPlayEndpoint,
        keepBluetoothControlAlive: (Int) -> Boolean,
        startSessionSent: (Long) -> Unit,
        onRouteFrame: (Iap2Frame) -> Unit,
        controlProgress: (String) -> Unit,
        closeWirelessStack: (Int) -> Unit,
        wirelessActiveReported: AtomicBoolean,
        wirelessHandoffRequested: AtomicBoolean,
        wirelessTunnelReady: AtomicBoolean,
        readActiveSession: () -> AirPlaySession?,
        tunnelChannel: () -> Iap2Session?,
        armWirelessHandoffWatchdog: (Int) -> Unit,
        waitForWifiDiscovery: (Int) -> Unit,
    ): Boolean {
        onStatus(CarPlayStatus.ConnectingBluetooth)
        stage("Bluetooth RFCOMM connection")
        debugLog(
            "wireless RFCOMM connecting address=${device.address} " +
                "uuid=$iap2IphoneUuid",
        )
        logBluetoothConnectionSnapshot(device, "before-connect")
        @Suppress("UNUSED_VARIABLE")
        val bluetoothStarted = System.nanoTime()

        var stream: BlockingDuplexByteStream? = null
        var channel: Iap2Session? = null
        var controlResult: Iap2WirelessControlResult? = null
        var lastBootstrapError: Throwable? = null
        var attempt = 0
        val maxAttempts = 2

        while (attempt < maxAttempts && !isStaleWirelessRun(generation)) {
            attempt++
            if (readActiveSession() != null || wirelessActiveReported.get()) {
                debugLog("AirPlay session active over Wi-Fi; continuing without Bluetooth bootstrap")
                break
            }
            try {
                if (device.bondState != BluetoothDevice.BOND_BONDED) {
                    onStatus(CarPlayStatus.Pairing)
                } else {
                    onStatus(CarPlayStatus.ConnectingBluetooth)
                }
                stage("Bluetooth RFCOMM connection")
                debugLog("wireless RFCOMM connecting (attempt $attempt/$maxAttempts) address=${device.address} uuid=$iap2IphoneUuid")
                logBluetoothConnectionSnapshot(device, "before-connect-$attempt")
                val currentStream = openRfcommStream(device.address, iap2IphoneUuid)
                setBluetoothStream(currentStream)
                stream = currentStream
                debugLog("wireless RFCOMM connected address=${device.address}")

                val currentChannel = Iap2Session.openWireless(
                    currentStream,
                    traceContext = "wireless-rfcomm",
                    onTrace = debugLog,
                    onArtwork = onArtworkTransfer,
                )
                synchronized(wirelessResourceLock) {
                    if (isStaleWirelessRun(generation)) {
                        currentChannel.close()
                        return true
                    }
                    setCsm(currentChannel)
                    channel = currentChannel
                }
                debugLog("wireless iAP2 CSM channel opened over RFCOMM")
                if (isStaleWirelessRun(generation)) return true

                onStatus(CarPlayStatus.RunningWireless)
                debugLog(
                    "wireless Bluetooth iAP2 bootstrap starting " +
                        "location=false vehicleStatus=false",
                )
                validateHotspotReady()
                val result = Iap2WirelessControlClient(
                    session = currentChannel,
                    mfi = Iap2MfiAuthenticationClient(mfi),
                ).run(
                    identification = identification,
                    endpoint = endpoint,
                    timeoutMillis = WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS,
                    keepAliveAfterDeadline = { keepBluetoothControlAlive(generation) },
                    beforeStartSession = { validateHotspotReady() },
                    onStartSessionSent = { startSessionSent(it.sentAtNanos) },
                    onIncoming = onRouteFrame,
                    onProgress = { message ->
                        controlProgress(message)
                        debugLog(message)
                    },
                )
                controlResult = result
                break
            } catch (e: Throwable) {
                lastBootstrapError = e
                debugLog("wireless Bluetooth bootstrap attempt $attempt failed: ${e.message}")
                runCatching { channel?.close() }
                channel = null
                runCatching { stream?.close() }
                stream = null
                setBluetoothStream(null)
                if (readActiveSession() != null || wirelessActiveReported.get() || isStaleWirelessRun(generation)) {
                    break
                }
                // The same SDP lookup already used its budget. A second pass waits the same way.
                if (e.message?.contains("Timed out connecting AOSP RFCOMM") == true) break
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(1000L)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }
        }

        if (isStaleWirelessRun(generation)) {
            closeWirelessStack(generation)
            return true
        }

        if (controlResult != null) {
            val result = controlResult
            when (result.terminal) {
                Iap2WirelessControlTerminal.CHANNEL_CLOSED -> {
                    debugLog(
                        "wireless RFCOMM EOF: iap2State=${result.stage} " +
                            "wirelessCarPlayAvailable=${result.wirelessCarPlayAvailableSeen} " +
                            "transportIdentifier=${result.transportNotificationSeen} " +
                            "carPlayStartSessions=${result.carPlayStartSessionsSent} " +
                            "postTransportConfigs=${result.postTransportWiFiConfigurationsSent} " +
                            "handoffRequested=${wirelessHandoffRequested.get()} " +
                            "tunnelReady=${wirelessTunnelReady.get()} " +
                            "wirelessActive=${wirelessActiveReported.get()}",
                    )
                    if (!wirelessActiveReported.get()) {
                        val handoffInProgress = isWirelessHandoffInProgress(
                            handoffRequested = wirelessHandoffRequested.get(),
                            tunnelActive = tunnelChannel() != null,
                            sessionActive = readActiveSession() != null,
                            startSessionSent = result.carPlayStartSessionsSent > 0,
                        )
                        if (!handoffInProgress) {
                            throw IOException(
                                "Wireless CarPlay control channel closed before tunnel iAP2 ready",
                            )
                        }
                        if (result.carPlayStartSessionsSent > 0 && !wirelessHandoffRequested.get()) {
                            armWirelessHandoffWatchdog(generation)
                        }
                        debugLog(
                            "wireless Bluetooth bootstrap closed during handoff; " +
                                "keeping the Wi-Fi AirPlay tunnel alive",
                        )
                    }
                }
                Iap2WirelessControlTerminal.TIMED_OUT ->
                    if (!wirelessActiveReported.get()) {
                        throw IOException("Wireless iAP2 bootstrap timeout after ${WIRELESS_BOOTSTRAP_TIMEOUT_MILLIS}ms " +
                            "stage=${result.stage} startSessions=${result.carPlayStartSessionsSent} " +
                            "wifiConfigs=${result.wifiConfigurationsSent} " +
                            "transportNotified=${result.transportNotificationSeen}; " +
                            "check iPhone Bluetooth pairing, Wi-Fi and diagnostic log")
                    }
            }
        } else {
            debugLog("wireless Bluetooth bootstrap failed or unavailable (${lastBootstrapError?.message}); falling back to Wi-Fi discovery")
            waitForWifiDiscovery(generation)
            if (isStaleWirelessRun(generation)) {
                closeWirelessStack(generation)
                return true
            }
            if (readActiveSession() != null || wirelessActiveReported.get()) {
                debugLog("AirPlay session active over Wi-Fi without Bluetooth bootstrap")
                onStatus(CarPlayStatus.WirelessActive)
                return true
            }
            throw (lastBootstrapError ?: IOException("Timed out waiting for iPhone to connect over Wi-Fi hotspot"))
        }
        return false
    }

    fun startWirelessTunnelControl(
        stream: BlockingDuplexByteStream,
        closed: () -> Boolean,
        isWirelessTransport: () -> Boolean,
        readIdentification: () -> Iap2IdentificationConfig?,
        readEndpoint: () -> Iap2WirelessCarPlayEndpoint?,
        readMfi: () -> MfiAuthenticator?,
        debugLog: (String) -> Unit,
        debugLogFailure: (String, Throwable) -> Unit,
        onArtworkTransfer: (Iap2ArtworkTransfer) -> Unit,
        getTunnelChannel: () -> Iap2Session?,
        setTunnelChannel: (Iap2Session?) -> Unit,
        wirelessGeneration: AtomicInteger,
        tunnelExecutor: ExecutorService,
        locationProvider: Iap2LocationProvider?,
        vehicleStatusProvider: VehicleStatusProvider?,
        onWirelessTunnelReady: (Int) -> Unit,
        onRouteFrame: (Iap2Frame) -> Unit,
        onStatus: (CarPlayStatus) -> Unit,
        closeBestEffort: (String, () -> Unit) -> Unit,
    ): Boolean {
        if (closed() || !isWirelessTransport()) return false
        val identification = readIdentification() ?: return false
        val endpoint = readEndpoint() ?: return false
        val mfi = readMfi() ?: return false
        debugLog("wireless type-130 tunnel data stream accepted")
        val channel = try {
            Iap2Session.openTunnel(
                stream,
                traceContext = "wireless-tunnel",
                onTrace = debugLog,
                onArtwork = onArtworkTransfer,
            )
        } catch (error: Throwable) {
            debugLogFailure("Could not open the tunneled iAP2 link", error)
            return false
        }
        setTunnelChannel(channel)
        val generation = wirelessGeneration.get()
        debugLog(
            "wireless iAP2 runtime tunnel control starting " +
                "location=${identification.locationInformationEnabled} " +
                "vehicleStatus=${identification.vehicleStatusEnabled}",
        )
        return try {
            tunnelExecutor.execute {
                try {
                    val result = Iap2WirelessControlClient(
                        session = channel,
                        mfi = Iap2MfiAuthenticationClient(mfi),
                    ).run(
                        identification = identification,
                        endpoint = endpoint,
                        timeoutMillis = Iap2WirelessControlClient.NO_TIMEOUT_MILLIS,
                        locationProvider = locationProvider,
                        vehicleStatusProvider = vehicleStatusProvider,
                        onReady = {
                            onWirelessTunnelReady(generation)
                        },
                        onIncoming = onRouteFrame,
                        onProgress = { message -> debugLog("iAP tunnel $message") },
                    )
                    if (closed() || generation != wirelessGeneration.get()) return@execute
                    when (result.terminal) {
                        Iap2WirelessControlTerminal.TIMED_OUT ->
                            onStatus(CarPlayStatus.ControlEnded)
                        Iap2WirelessControlTerminal.CHANNEL_CLOSED ->
                            onStatus(CarPlayStatus.Failed("Wireless iAP2 tunnel closed"))
                    }
                } catch (error: Throwable) {
                    if (!closed() && generation == wirelessGeneration.get()) {
                        debugLogFailure("tunneled iAP2 control failed", error)
                        onStatus(
                            CarPlayStatus.Failed(
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    }
                } finally {
                    if (getTunnelChannel() === channel) setTunnelChannel(null)
                }
            }
            true
        } catch (error: Throwable) {
            if (getTunnelChannel() === channel) setTunnelChannel(null)
            closeBestEffort("tunneled iAP2 link") { channel.close() }
            debugLogFailure("iAP2 tunnel executor rejected the link", error)
            false
        }
    }
}
