package com.shihab.diplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.view.WindowManager
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import com.shihab.diplay.airplay.AirPlayConfig
import com.shihab.diplay.airplay.AirPlayDisplayConfig
import com.shihab.diplay.airplay.AirPlayIcon
import com.shihab.diplay.airplay.AirPlayIdentity
import com.shihab.diplay.airplay.CarPlayDisplayScale
import com.shihab.diplay.airplay.CarPlayMediaEngine
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.host.CarPlaySessionDisplay
import com.shihab.diplay.host.R
import com.shihab.diplay.host.smoothVideoDelayMillis
import com.shihab.diplay.location.AndroidCarPlayLocationProvider
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.media.OpusEncoderSupport
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayRuntimeConfig
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.CarPlayTransport
import com.shihab.diplay.orchestration.ManualHotspotProvisioning
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.MfiTarget
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.transport.EvChargingConnectors
import com.shihab.diplay.transport.Iap2IdentificationConfig

/**
 * Stage text mapping shared between [CarPlayHostActivity] and [CarPlayConnectService].
 *
 * The progress/failure broadcasts the service sends to DiPlay's connecting dialog must be byte-for-byte
 * identical to the strings the host used to show during the old connect-only flow, so this is the
 * single source of truth. The host's private `describe()` / `friendlyStage()` delegate here.
 */
internal object CarPlayConnectText {
    fun describe(
        context: Context,
        status: CarPlayStatus,
        wirelessHotspotMode: WirelessHotspotMode,
        wirelessEnabled: Boolean,
    ): String = when (status) {
        CarPlayStatus.DiscoveringMfi -> context.getString(R.string.preparing_mfi_authentication)
        CarPlayStatus.WaitingForMfi -> context.getString(R.string.waiting_for_mfi_coprocessor)
        CarPlayStatus.RequestingMfiPermission -> context.getString(R.string.requesting_mfi_usb_permission)
        CarPlayStatus.MfiReady -> context.getString(R.string.mfi_authentication_ready)
        CarPlayStatus.StartingHotspot -> context.getString(if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI)
            R.string.existing_wifi_attaching else R.string.starting_wireless_hotspot)
        is CarPlayStatus.HotspotReady ->
            if (wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
                context.getString(R.string.existing_wifi_ready, status.ssid, status.band, status.channel, status.address)
            } else context.getString(R.string.status_hotspot_ready, status.backend, status.ssid, status.band,
                if (status.channel == 0) context.getString(R.string.auto_value) else status.channel.toString())
        CarPlayStatus.WaitingForPairedIphone -> context.getString(R.string.waiting_for_paired_iphone)
        CarPlayStatus.ConnectingBluetooth -> context.getString(R.string.connecting_bluetooth)
        CarPlayStatus.RunningWireless -> context.getString(R.string.wireless_carplay_control_running)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> context.getString(R.string.wireless_carplay_active)
        CarPlayStatus.DiscoveringIphone -> context.getString(R.string.discovering_iphone)
        CarPlayStatus.WaitingForIphone -> context.getString(R.string.waiting_for_iphone_over_usb)
        CarPlayStatus.RequestingIphonePermission -> context.getString(R.string.requesting_iphone_usb_permission)
        CarPlayStatus.WaitingForReenumeration -> context.getString(R.string.status_waiting_reenumeration)
        CarPlayStatus.SelectingConfiguration -> context.getString(R.string.selecting_carplay_configuration)
        CarPlayStatus.OpeningDataPaths -> context.getString(R.string.opening_usb_data_paths)
        CarPlayStatus.Pairing -> context.getString(R.string.pairing_with_iphone)
        CarPlayStatus.ConnectingControl -> context.getString(R.string.connecting_iap2_control)
        CarPlayStatus.AttachingNetwork ->
            if (wirelessEnabled) context.getString(R.string.starting_airplay_service) else context.getString(R.string.status_attaching_ncm)
        CarPlayStatus.RunningControl -> context.getString(R.string.carplay_control_running)
        CarPlayStatus.ControlEnded -> context.getString(R.string.carplay_control_window_ended)
        is CarPlayStatus.Failed -> when (status.startupFailure) {
            WirelessStartupFailure.HOTSPOT_NOT_READY -> context.getString(R.string.hotspot_network_not_ready)
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> context.getString(R.string.first_tcp_timeout)
            else -> context.getString(R.string.status_failed, status.message)
        }
    }

    fun friendlyStage(context: Context, message: String): String = when {
        message == context.getString(R.string.waiting_for_mfi_coprocessor) ||
            message == context.getString(R.string.requesting_mfi_usb_permission) -> message
        message.contains("Turn on Wi-Fi", true) -> context.getString(R.string.turn_on_wi_fi_in_the_head_unit_s_settings_to_connect)
        message.contains("Allow precise Location", true) -> context.getString(R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p)
        message.contains("Allow Nearby devices", true) -> context.getString(R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per)
        message.contains("createGroup failed", true) -> context.getString(R.string.the_head_unit_couldn_t_start_carplay_wi_fi_check_wi_fi_and)
        message.contains("needs a reset", true) -> context.getString(R.string.a_previous_wi_fi_direct_connection_is_still_running_reset)
        message.contains("socket", true) || message.contains("RFCOMM", true) ->
            context.getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
        message.contains("Bluetooth adapter is unavailable", true) -> context.getString(R.string.bluetooth_adapter_unavailable)
        message.contains("Bluetooth is turned off", true) -> context.getString(R.string.enable_the_car_s_bluetooth_and_pair_your_iphone_first)
        message.contains("busy", true) || message.contains("EBUSY", true) -> context.getString(R.string.usb_iphone_busy_or_occupied)
        message.contains("permission was not granted", true) -> context.getString(R.string.usb_iphone_permission_not_granted)
        message.contains("unsupported", true) || message.contains("not supported", true) -> context.getString(R.string.this_head_unit_may_not_support_wireless_carplay_try_a_usb)
        message.contains("denied", true) || message.contains("permission", true) -> context.getString(R.string.allow_the_connection_permission_to_continue)
        message.contains("Failed", true) ||
            message.contains("error", true) ||
            message.contains("timed out", true) ||
            message.contains("closed", true) ||
            message.contains("authentication", true) ||
            message.contains("Could not", true) ->
            context.getString(R.string.connection_interrupted_retrying)
        message.contains("Waiting for iPhone", true) || message.contains("Discovering iPhone", true) -> context.getString(R.string.connect_your_iphone_with_a_usb_cable)
        message.contains("paired", true) -> context.getString(R.string.looking_for_your_paired_iphone)
        message.contains("Bluetooth", true) -> context.getString(R.string.connecting_to_your_iphone)
        message.contains("reconnect", true) || message.contains("ended", true) -> context.getString(R.string.reconnecting_to_your_iphone)
        message.contains("active", true) || message.contains("running", true) -> context.getString(R.string.opening_carplay)
        else -> context.getString(R.string.getting_carplay_ready)
    }
}

/** Result of building the headless connection stack inside [CarPlayConnectService]. */
internal class CarPlayStackBuilt(
    val controller: CarPlayController,
    val sink: AndroidMediaSink,
    val display: CarPlaySessionDisplay,
)

/**
 * Builds the full CarPlay controller stack without any View/Activity, so [CarPlayConnectService] can
 * drive the wireless handshake in the background. The sink is created with a null Surface; the host
 * attaches its surface later via `setSurface` when it adopts the session.
 *
 * Deliberately a headless simplification of CarPlayHostActivity.startCarPlay: it negotiates a sane
 * canvas from the persisted display size and re-negotiates through the existing restart path once the
 * host adopts and reports its real surface size.
 */
internal object CarPlayConnectStack {

    fun build(
        ctx: Context,
        listener: AirPlaySessionListener,
        onStage: (String) -> Unit,
        onFailed: (String) -> Unit,
    ): CarPlayStackBuilt {
        val identity: AirPlayIdentity = AirPlayPersistence.loadIdentity(ctx)
        val wirelessEnabled = AirPlayPersistence.loadWirelessEnabled(ctx)
        val mfiTarget = AirPlayPersistence.loadMfiTarget(ctx)
        val mfiI2cPath = AirPlayPersistence.loadMfiI2cPath(ctx)
        val remoteMfiServer = AirPlayPersistence.loadRemoteMfiServer(ctx)
        val remoteMfiToken = AirPlayPersistence.loadRemoteMfiToken(ctx)
        val locationReportingEnabled = AirPlayPersistence.loadLocationReportingEnabled(ctx)
        val wirelessHotspotMode = AirPlayPersistence.loadWirelessHotspotMode(ctx)
        val existingWifiSsid = AirPlayPersistence.loadExistingWifiSsid(ctx)
        val existingWifiPassphrase = AirPlayPersistence.loadExistingWifiPassphrase(ctx)
        var manualHotspotSsid = AirPlayPersistence.loadManualHotspotSsid(ctx)
        var manualHotspotPassphrase = AirPlayPersistence.loadManualHotspotPassphrase(ctx)
        var manualHotspotSecurity = AirPlayPersistence.loadManualHotspotSecurity(ctx)
        // Seamless manual hotspot credentials are invented once and persisted, mirroring the host.
        if (wirelessHotspotMode == WirelessHotspotMode.MANUAL && manualHotspotSsid.isBlank()) {
            val generated = ManualHotspotProvisioning.generate()
            manualHotspotSsid = generated.ssid
            manualHotspotPassphrase = generated.passphrase
            manualHotspotSecurity = ManualHotspotSecurity.WPA2
            AirPlayPersistence.saveManualHotspotSsid(ctx, generated.ssid)
            AirPlayPersistence.saveManualHotspotPassphrase(ctx, generated.passphrase)
            AirPlayPersistence.saveManualHotspotSecurity(ctx, ManualHotspotSecurity.WPA2)
        }
        val manualHotspotBand = AirPlayPersistence.loadManualHotspotBand(ctx)
        val manualHotspotChannel = AirPlayPersistence.loadManualHotspotChannel(ctx)

        val deviceId = DiPlayBootstrap.deviceId(identity)
        val manufacturer = AirPlayPersistence.loadManufacturer(ctx).trim()
            .ifBlank { AirPlayPersistence.DEFAULT_MANUFACTURER }
        val model = AirPlayPersistence.loadModel(ctx).trim().ifBlank { AirPlayPersistence.DEFAULT_MODEL }
        val config = CarPlayRuntimeConfig(
            mfiTarget = mfiTarget,
            linuxI2cPath = if (mfiTarget == MfiTarget.I2C) mfiI2cPath.trim() else null,
            remoteMfiServer = remoteMfiServer.trim().takeIf { it.isNotEmpty() },
            remoteMfiToken = remoteMfiToken.takeIf { it.isNotEmpty() },
            identification = Iap2IdentificationConfig(
                name = "75Play",
                modelIdentifier = model,
                manufacturer = manufacturer,
                serialNumber = "75PLAY-" + deviceId.replace(":", ""),
                firmwareVersion = "0.1.0",
                hardwareVersion = "1.0",
                carPlayUsbInterfaceNumber = 3,
                locationInformationEnabled = locationReportingEnabled,
                vehicleStatusEnabled = false,
                chargingConnectors = EvChargingConnectors.CCS2_TYPE2,
                vehicleSpeedEnabled = false,
            ),
            label = "75Play",
            hostName = "75play-" + deviceId.replace(":", "").lowercase(),
            hostMac = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray(),
            wirelessBluetoothDeviceAddress = DiPlayPreferences.phoneAddress(ctx),
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = wirelessHotspotMode,
            wifiP2pPreferredChannel = AirPlayPersistence.loadWifiP2pPreferredChannel(ctx),
            manualHotspotSsid = manualHotspotSsid.takeIf { it.isNotBlank() } ?: "75Play",
            manualHotspotPassphrase = manualHotspotPassphrase,
            manualHotspotBand = manualHotspotBand,
            manualHotspotChannel = manualHotspotChannel,
            manualHotspotSecurity = manualHotspotSecurity,
            existingWifiSsid = existingWifiSsid,
            existingWifiPassphrase = existingWifiPassphrase,
            locationReportingEnabled = locationReportingEnabled,
        )

        // Headless size: prefer the persisted maximum-detected display, fall back to the real display.
        val (maxW, maxH) = AirPlayPersistence.loadMaximumDetectedDisplay(ctx)
        val real = DisplayMetrics()
        runCatching {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .defaultDisplay.getRealMetrics(real)
        }
        val windowWidth = if (maxW > 0) maxW else real.widthPixels
        val windowHeight = if (maxH > 0) maxH else real.heightPixels
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
            icons = listOf(loadIcon(ctx)),
            videoInCar = false,
            mainBufferedAudio = AirPlayPersistence.loadMainBufferedAudio(ctx),
        )

        val locationProvider = if (config.locationReportingEnabled) AndroidCarPlayLocationProvider(ctx) else null

        val sink = AndroidMediaSink(
            surface = null,
            videoWidth = scaled.widthPixels,
            videoHeight = scaled.heightPixels,
            preferSoftwareHevcDecoder = false,
            context = ctx,
            onScreenStreamActiveChanged = { _, _ -> },
            // Only a SurfaceView honours release timestamps; smooth video always selects one.
            videoPacingDelayMillis = if (smoothVideo) smoothVideoDelayMillis(fps) else 0,
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
        CarPlayMediaKeys.attach(ctx, controller)

        val display = CarPlaySessionDisplay(
            scaled.widthPixels, scaled.heightPixels,
            rotation = 0, hideTopBar = true, hideBottomBar = true,
            windowWidth, windowHeight,
            viewAreas = null,
        )
        return CarPlayStackBuilt(controller, sink, display)
    }

    private fun loadIcon(ctx: Context): AirPlayIcon {
        val encoded = ctx.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth != bounds.outHeight) {
            throw IllegalStateException("Packaged AirPlay icon is invalid")
        }
        return AirPlayIcon(bounds.outWidth, bounds.outHeight, encoded)
    }
}
