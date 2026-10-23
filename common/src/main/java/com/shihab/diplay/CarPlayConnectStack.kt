package com.shihab.diplay

import android.content.Context
import com.shihab.diplay.airplay.AirPlaySessionListener
import com.shihab.diplay.session.CarPlaySessionDisplay
import com.shihab.diplay.host.R
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.network.WirelessStartupFailure
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.session.CarPlaySessionBuilt
import com.shihab.diplay.session.CarPlaySessionFactory

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
            else -> if (status.message.contains("RFCOMM", ignoreCase = true)) {
                context.getString(R.string.your_iphone_isn_t_available_unlock_it_and_check_bluetooth)
            } else {
                context.getString(R.string.status_failed, status.message)
            }
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
) {
    constructor(built: CarPlaySessionBuilt) : this(built.controller, built.sink, built.display)
}

/**
 * Builds the full CarPlay controller stack without any View/Activity, so [CarPlayConnectService] can
 * drive the wireless handshake in the background. Delegates to [CarPlaySessionFactory.buildHeadless].
 */
internal object CarPlayConnectStack {

    fun build(
        ctx: Context,
        listener: AirPlaySessionListener,
        onStage: (String) -> Unit,
        onFailed: (String) -> Unit,
    ): CarPlayStackBuilt = CarPlayStackBuilt(
        CarPlaySessionFactory.buildHeadless(ctx, listener, onStage, onFailed),
    )
}
