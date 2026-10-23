package com.shihab.diplay.host

import com.shihab.diplay.messageResource
import com.shihab.diplay.orchestration.CarPlayStatus
import com.shihab.diplay.orchestration.ManualHotspotBand
import com.shihab.diplay.orchestration.ManualHotspotSecurity
import com.shihab.diplay.orchestration.WirelessHotspotMode
import com.shihab.diplay.orchestration.isManualHotspotChannelCompatible

/**
 * Hotspot status formatting and manual-hotspot validation for [com.shihab.diplay.CarPlayHostActivity].
 * Settings UI lives in the shell ([com.shihab.diplay.ui.FragmentSetting] / connection tab).
 */
internal class CarPlayHostWirelessSettings(private val host: Host) {

    data class HotspotStatus(
        val state: String,
        val ssid: String? = null,
        val band: String? = null,
        val channel: Int? = null,
        val backend: String? = null,
    )

    interface Host {
        fun getString(resId: Int): String
        fun getString(resId: Int, vararg formatArgs: Any): String

        var wirelessHotspotMode: WirelessHotspotMode
        var wirelessEnabled: Boolean
        var hotspotStatus: HotspotStatus
        var manualHotspotSsid: String
        var manualHotspotPassphrase: String
        var manualHotspotBand: ManualHotspotBand
        var manualHotspotChannel: Int
        var manualHotspotSecurity: ManualHotspotSecurity
        var existingWifiSsid: String
        var existingWifiPassphrase: String
    }

    fun manualHotspotSettingsError(): String? {
        if (host.wirelessHotspotMode == WirelessHotspotMode.EXISTING_WIFI) {
            val error = com.shihab.diplay.orchestration.ManualHotspotValidation.error(
                host.existingWifiSsid,
                host.existingWifiPassphrase,
            )
            return error?.let { host.getString(it.messageResource()) }
        }
        if (host.wirelessHotspotMode != WirelessHotspotMode.MANUAL) return null
        return when {
            host.manualHotspotSsid.isBlank() -> host.getString(R.string.hotspot_ssid_is_required)
            host.manualHotspotSsid.encodeToByteArray().size > 32 ->
                host.getString(R.string.hotspot_ssid_must_be_at_most_32_utf_8_bytes)
            '\u0000' in host.manualHotspotSsid -> host.getString(R.string.hotspot_ssid_contains_u_0000)
            host.manualHotspotChannel !in 0..196 -> host.getString(R.string.channel_must_be_0_or_1_196)
            host.manualHotspotChannel != 0 &&
                !isManualHotspotChannelCompatible(host.manualHotspotBand, host.manualHotspotChannel) ->
                host.getString(R.string.channel_is_not_valid_for_the_selected_band)
            '\u0000' in host.manualHotspotPassphrase -> host.getString(R.string.hotspot_password_contains_u_0000)
            host.manualHotspotSecurity == ManualHotspotSecurity.OPEN &&
                host.manualHotspotPassphrase.isNotEmpty() ->
                host.getString(R.string.password_must_be_empty_when_security_is_open)
            host.manualHotspotSecurity != ManualHotspotSecurity.OPEN &&
                host.manualHotspotPassphrase.length !in 8..63 ->
                host.getString(R.string.wpa2_wpa3_password_must_be_8_63_characters)
            else -> null
        }
    }

    fun hotspotModeLabel(mode: WirelessHotspotMode): String = when (mode) {
        WirelessHotspotMode.WIFI_P2P -> host.getString(R.string.wi_fi_p2p_5_ghz)
        WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> host.getString(R.string.localonlyhotspot)
        WirelessHotspotMode.MANUAL -> host.getString(R.string.manual_hotspot)
        WirelessHotspotMode.EXISTING_WIFI -> host.getString(R.string.existing_wifi_title)
    }

    fun updateHotspotStatus(status: CarPlayStatus) {
        if (!host.wirelessEnabled) return
        host.hotspotStatus = when (status) {
            CarPlayStatus.StartingHotspot -> HotspotStatus(state = host.getString(R.string.starting))
            is CarPlayStatus.HotspotReady -> HotspotStatus(
                state = host.getString(R.string.ready),
                ssid = status.ssid,
                band = status.band,
                channel = status.channel,
                backend = status.backend,
            )
            CarPlayStatus.WaitingForPairedIphone ->
                host.hotspotStatus.copy(state = host.getString(R.string.waiting_for_paired_iphone))
            CarPlayStatus.ConnectingBluetooth ->
                host.hotspotStatus.copy(state = host.getString(R.string.connecting_bluetooth))
            CarPlayStatus.RunningWireless ->
                host.hotspotStatus.copy(state = host.getString(R.string.running))
            CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback ->
                host.hotspotStatus.copy(state = host.getString(R.string.active))
            CarPlayStatus.AttachingNetwork ->
                host.hotspotStatus.copy(state = host.getString(R.string.starting_airplay_service))
            is CarPlayStatus.Failed -> host.hotspotStatus.copy(state = host.getString(R.string.error))
            else -> return
        }
        updateHotspotStatusBlock()
    }

    /** Status view was removed with Host settings UI; keep updating [Host.hotspotStatus] only. */
    fun updateHotspotStatusBlock() = Unit
}
