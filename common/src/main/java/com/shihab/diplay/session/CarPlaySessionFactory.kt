package com.shihab.diplay.session

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.content.ContextCompat
import com.shihab.diplay.AirPlayPersistence
import com.shihab.diplay.AndroidTvInputMode
import com.shihab.diplay.CarPlayConnectText
import com.shihab.diplay.CarPlayMediaKeys
import com.shihab.diplay.DiPlayBluetooth
import com.shihab.diplay.DiPlayBootstrap
import com.shihab.diplay.DiPlayPreferences
import com.shihab.diplay.HevcSupport
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplaySettings
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIcon
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.airplay.CarPlayDisplayScale
import com.shihab.diplay.airplay.CarPlayMediaEngine
import com.shihab.diplay.host.R
import com.shihab.diplay.location.AndroidCarPlayLocationProvider
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.media.OpusEncoderSupport
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.CarPlayTransport
import com.shihab.diplay.orchestration.ManualHotspotBand
import com.shihab.diplay.orchestration.ManualHotspotProvisioning
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.MfiTarget
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.transport.EvChargingConnectors
import com.shihab.diplay.transport.Iap2IdentificationConfig
import com.shihab.diplay.transport.Iap2LocationProvider

/**
 * Snapshot of persistence / Host in-memory fields needed to build [CarPlayRuntimeConfig].
 * Host passes its live fields; the headless connect path loads them via [fromPersistence].
 */
data class CarPlayRuntimeConfigInputs(
    val identity: AirPlayIdentity,
    val mfiTarget: MfiTarget,
    val mfiI2cPath: String,
    val remoteMfiServer: String,
    val remoteMfiToken: String,
    val manufacturer: String,
    val model: String,
    val wirelessHotspotMode: WirelessHotspotMode,
    val wifiP2pPreferredChannel: Int,
    val manualHotspotSsid: String,
    val manualHotspotPassphrase: String,
    val manualHotspotBand: ManualHotspotBand,
    val manualHotspotChannel: Int,
    val manualHotspotSecurity: ManualHotspotSecurity,
    val existingWifiSsid: String,
    val existingWifiPassphrase: String,
    val locationReportingEnabled: Boolean,
    val wirelessBluetoothDeviceAddress: String?,
)

/** Shared [CarPlayRuntimeConfig] construction for Host and the headless connect stack. */
object CarPlayRuntimeConfigFactory {

    private val manualHotspotLock = Any()

    /**
     * One writer for seamless manual-hotspot credentials. Host and the connect stack both call
     * this, so two concurrent starts cannot persist two different SSIDs.
     */
    fun provisionManualHotspot(ctx: Context) {
        synchronized(manualHotspotLock) {
            if (AirPlayPersistence.loadWirelessHotspotMode(ctx) != WirelessHotspotMode.MANUAL) return
            if (AirPlayPersistence.loadManualHotspotSsid(ctx).isNotBlank()) return
            val generated = ManualHotspotProvisioning.generate()
            AirPlayPersistence.saveManualHotspotSsid(ctx, generated.ssid)
            AirPlayPersistence.saveManualHotspotPassphrase(ctx, generated.passphrase)
            AirPlayPersistence.saveManualHotspotSecurity(ctx, ManualHotspotSecurity.WPA2)
        }
    }

    /**
     * Loads connection inputs from persistence. When manual hotspot mode has no SSID yet,
     * [provisionManualHotspot] invents and persists seamless credentials.
     */
    fun fromPersistence(ctx: Context): CarPlayRuntimeConfigInputs {
        provisionManualHotspot(ctx)
        val manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(ctx)
        val manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(ctx)
        val manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(ctx)
        val wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(ctx)
        return CarPlayRuntimeConfigInputs(
            identity = AirPlayPersistence.loadIdentity(ctx),
            mfiTarget = AirPlayPersistence.loadMfiTarget(ctx),
            mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(ctx),
            remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(ctx),
            remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(ctx),
            manufacturer = AirPlayPersistence.loadManufacturer(ctx),
            model = AirPlayPersistence.loadModel(ctx),
            wirelessHotspotMode = wirelessHotspotMode,
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx),
            manualHotspotSsid = manualHotspotSsid,
            manualHotspotPassphrase = manualHotspotPassphrase,
            manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(ctx),
            manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(ctx),
            manualHotspotSecurity = manualHotspotSecurity,
            existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(ctx),
            existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(ctx),
            locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(ctx),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(ctx),
        )
    }

    fun build(inputs: CarPlayRuntimeConfigInputs): CarPlayRuntimeConfig {
        val deviceId = DiPlayBootstrap.deviceId(inputs.identity)
        val manufacturer = inputs.manufacturer.trim()
            .ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
        val model = inputs.model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }
        return CarPlayRuntimeConfig(
            mfiTarget = inputs.mfiTarget,
            linuxI2cPath = if (inputs.mfiTarget == MfiTarget.I2C) inputs.mfiI2cPath.trim() else null,
            remoteMfiServer = inputs.remoteMfiServer.trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = inputs.remoteMfiToken.takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = "75Play",
                modelIdentifier = model,
                manufacturer = manufacturer,
                serialNumber = "75PLAY-" + deviceId.replace(":", ""),
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = inputs.locationReportingEnabled,
                vehicleStatusEnabled = false,
                chargingConnectors = EvChargingConnectors.CCS2_TYPE2,
                vehicleSpeedEnabled = false,
            ),
            label = "75Play",
            hostName = "75play-" + deviceId.replace(":", "").lowercase(),
            hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = inputs.wirelessBluetoothDeviceAddress,
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = inputs.wirelessHotspotMode,
            wifiP2pPreferredChannel = inputs.wifiP2pPreferredChannel,
            manualHotspotSsid = inputs.manualHotspotSsid.takeIf { it.isNotBlank() } ?: "75Play",
            manualHotspotPassphrase = inputs.manualHotspotPassphrase,
            manualHotspotBand = inputs.manualHotspotBand,
            manualHotspotChannel = inputs.manualHotspotChannel,
            manualHotspotSecurity = inputs.manualHotspotSecurity,
            existingWifiSsid = inputs.existingWifiSsid,
            existingWifiPassphrase = inputs.existingWifiPassphrase,
            locationReportingEnabled = inputs.locationReportingEnabled,
        )
    }

    fun fromPersistenceConfig(ctx: Context): CarPlayRuntimeConfig = build(fromPersistence(ctx))
}

/** Result of building the headless connection stack inside [com.shihab.diplay.CarPlayConnectService]. */
internal class CarPlaySessionBuilt(
    val controller: CarPlayController,
    val sink: AndroidMediaSink,
    val display: CarPlaySessionDisplay,
)

/**
 * Single place to assemble CarPlay controller stacks shared by the headless connect service and
 * [com.shihab.diplay.CarPlayHostActivity]. Host still owns adopt/background session behaviour and
 * its richer [AirPlayConfig] / sink callbacks; those stay on the Activity.
 */
internal object CarPlaySessionFactory {

    /**
     * Builds the full CarPlay controller stack without any View/Activity, so
     * [com.shihab.diplay.CarPlayConnectService] can drive the wireless handshake in the background.
     * The sink is created with a null Surface; the host attaches its surface later via `setSurface`
     * when it adopts the session.
     *
     * The canvas uses the screen's current size and rotation. The host treats a mismatch as a
     * layout change and reconnects, which leaves CarPlay black for the whole second handshake.
     */
    fun buildHeadless(
        ctx: Context,
        listener: AirPlaySessionListener,
        onStage: (String) -> Unit,
        onFailed: (String) -> Unit,
    ): CarPlaySessionBuilt {
        val inputs = CarPlayRuntimeConfigFactory.fromPersistence(ctx)
        val wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(ctx)
        val config = CarPlayRuntimeConfigFactory.build(inputs)
        val identity = inputs.identity
        val deviceId = DiPlayBootstrap.deviceId(identity)
        val manufacturer = inputs.manufacturer.trim()
            .ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
        val model = inputs.model.trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }

        // Prefer the persisted maximum only when it is the same orientation as the screen now.
        // A landscape maximum on a portrait screen (or the reverse) would not match the host
        // window, and the host would reconnect.
        val (maxW, maxH) = AirPlayPersistence.loadMaximumDetectedDisplay(ctx)
        val real = DisplayMetrics()
        var rotation = 0
        runCatching {
            val display = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
            display.getRealMetrics(real)
            rotation = display.rotation
        }
        val savedMatchesOrientation = maxW > 0 && maxH > 0 &&
            (maxW >= maxH) == (real.widthPixels >= real.heightPixels)
        val windowWidth = if (savedMatchesOrientation) maxW else real.widthPixels
        val windowHeight = if (savedMatchesOrientation) maxH else real.heightPixels
        val safeWidth = (windowWidth / 2 * 2).coerceAtLeast(2)
        val safeHeight = (windowHeight / 2 * 2).coerceAtLeast(2)

        val fps = AirPlayPersistence.loadFps(ctx)
        // The sink fixes its pacing at construction time; the host adopts the session only when this
        // matches the Smooth Video setting the host built its view with. Read the same persisted flag
        // and compute the delay exactly like CarPlayHostActivity.createMediaSink.
        val smoothVideo = AirPlayPersistence.loadSmoothVideo(ctx)
        val sessionHevc = HevcSupport.usable(
            AirPlayPersistence.loadHevcEnabled(ctx), false, HevcSupport.decoders(),
        )
        val baseDisplay = AirPlayDisplayConfig(
            widthPixels = safeWidth,
            heightPixels = safeHeight,
            fps = fps,
            primaryInputDevice = if (AndroidTvInputMode.shouldUseKnobAsPrimaryInput(ctx)) 3 else 1,
        )
        val scaled = CarPlayDisplayScale.applyPercent(baseDisplay, AirPlayPersistence.loadDisplayScalePercent(ctx))
        val microphoneGranted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val airPlayConfig = AirPlayConfig(
            deviceName = "75Play",
            deviceId = deviceId,
            btMac = DiPlayBluetooth.localAddress(ctx) ?: deviceId,
            sourceVersion = "950.7.1",
            main = scaled,
            rightHandDrive = AirPlayPersistence.loadRightHandDrive(ctx),
            hevc = sessionHevc,
            microphone = microphoneGranted,
            microphoneOpus = OpusEncoderSupport.isAvailable(),
            opusAudioOutput = true,
            manufacturer = manufacturer,
            model = model,
            oemLabel = AirPlayPersistence.defaultOemLabel(ctx),
            icons = listOf(loadAirPlayIcon(ctx)),
            videoInCar = false,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(ctx),
        )

        val locationProvider = locationProvider(ctx, config.locationReportingEnabled)
        val sink = createHeadlessMediaSink(
            ctx = ctx,
            videoWidth = scaled.widthPixels,
            videoHeight = scaled.heightPixels,
            fps = fps,
            smoothVideo = smoothVideo,
        )
        val media = CarPlayMediaEngine(
            sink = sink,
            microphoneEnabled = microphoneGranted,
            audioCaptureDirectory = null,
            callSpeakerphone = AirPlayPersistence.loadCallSpeakerphone(ctx),
        )
        val pairings = AirPlayPersistence.loadPairings(ctx) { id, key ->
            AirPlayPersistence.savePairing(ctx, id, key)
        }

        val wirelessHotspotMode = inputs.wirelessHotspotMode
        val reporter: (CarPlayStatus) -> Unit = { status ->
            val text = CarPlayConnectText.describe(ctx, status, wirelessHotspotMode, wirelessEnabled)
            if (status is CarPlayStatus.Failed) onFailed(text) else onStage(text)
        }

        val controller = CarPlayController(
            context = ctx,
            config = config,
            airPlayConfig = airPlayConfig,
            identity = identity,
            pairings = pairings,
            listener = listener,
            media = media,
            reportStatus = reporter,
            locationProvider = locationProvider,
            vehicleStatusProvider = null,
        )
        // Media keys / now-playing are process-global on the controller; wire them here so they keep
        // working after the host adopts the session (the host does not re-attach on adopt).
        attachMediaKeys(ctx, controller)

        val display = CarPlaySessionDisplay(
            scaled.widthPixels, scaled.heightPixels,
            rotation = rotation,
            hideTopBar = AirPlayPersistence.loadHideTopBar(ctx),
            hideBottomBar = AirPlayPersistence.loadHideBottomBar(ctx),
            windowWidth, windowHeight,
            viewAreas = null,
        )
        return CarPlaySessionBuilt(controller, sink, display)
    }

    fun attachMediaKeys(ctx: Context, controller: CarPlayController) {
        CarPlayMediaKeys.attach(ctx, controller)
    }

    fun locationProvider(ctx: Context, enabled: Boolean): Iap2LocationProvider? =
        if (enabled) AndroidCarPlayLocationProvider(ctx) else null

    /** Smooth-video pacing delay used by both headless and Host sinks. */
    fun videoPacingDelayMillis(smoothVideo: Boolean, fps: Int): Int =
        if (smoothVideo) smoothVideoDelayMillis(fps) else 0

    fun loadAirPlayIcon(ctx: Context): AirPlayIcon {
        val encoded = ctx.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth != bounds.outHeight) {
            throw IllegalStateException("Packaged AirPlay icon is invalid")
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }

    private fun createHeadlessMediaSink(
        ctx: Context,
        videoWidth: Int,
        videoHeight: Int,
        fps: Int,
        smoothVideo: Boolean,
    ): AndroidMediaSink = AndroidMediaSink(
        surface = null,
        videoWidth = videoWidth,
        videoHeight = videoHeight,
        preferSoftwareHevcDecoder = false,
        context = ctx,
        onScreenStreamActiveChanged = { _, _ -> },
        // Only a SurfaceView honours release timestamps; smooth video always selects one.
        videoPacingDelayMillis = videoPacingDelayMillis(smoothVideo, fps),
    )
}


/**
 * Smooth video's starting delay over the link's base delay; it then adjusts to the frames the decoder
 * releases. On my Tang the Qualcomm decoder released a frame only after about two more had been queued
 * (p90 69-77 ms at about 56 fps on faster SoCs), so it starts at three frame
 * intervals plus 40 ms: 90 ms at 60 fps.
 *
 * Moved here from the host package so [CarPlaySessionFactory] can call it without a session -> host
 * source dependency; host UI still reads it for diagnostics.
 */
internal fun smoothVideoDelayMillis(fps: Int): Int =
    40 + 3_000 / fps.coerceIn(AirPlayDisplaySettings.MIN_FPS, AirPlayDisplaySettings.MAX_FPS)
