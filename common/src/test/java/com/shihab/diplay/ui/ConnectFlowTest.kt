package com.shihab.diplay.ui

import com.shihab.diplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectFlowTest {
    private val strings = mapOf(
        R.string.preparing_mfi_authentication to "Preparing MFi authentication",
        R.string.getting_carplay_ready to "Getting CarPlay ready",
        R.string.starting_wireless_hotspot to "Starting wireless hotspot",
        R.string.existing_wifi_attaching to "Attaching to existing Wi-Fi",
        R.string.status_hotspot_ready to "Hotspot ready: %1\$s, %2\$s, %3\$s, channel %4\$s",
        R.string.existing_wifi_ready to "Existing Wi-Fi ready: %1\$s",
        R.string.waiting_for_paired_iphone to "Waiting for iPhone",
        R.string.connecting_bluetooth to "Connecting Bluetooth",
        R.string.connecting_to_your_iphone to "Connecting to your iPhone",
        R.string.starting_airplay_service to "Starting AirPlay service",
        R.string.pairing_with_iphone to "Pairing with iPhone",
        R.string.connecting_iap2_control to "Connecting iAP2 control",
        R.string.wireless_carplay_control_running to "Wireless CarPlay control running",
        R.string.wireless_carplay_active to "Wireless CarPlay active",
        R.string.opening_carplay to "Opening CarPlay",
    )

    private fun text(id: Int): String = strings[id] ?: "unused-$id"

    @Test fun stagesFollowTheWirelessOrder() {
        assertEquals(ConnectFlow.PREPARE, ConnectFlow.indexOf("Preparing MFi authentication", ::text))
        assertEquals(ConnectFlow.PREPARE, ConnectFlow.indexOf("Getting CarPlay ready", ::text))
        assertEquals(ConnectFlow.PREPARE, ConnectFlow.indexOf("Connecting to your iPhone", ::text))
        assertEquals(ConnectFlow.NETWORK, ConnectFlow.indexOf("Starting wireless hotspot", ::text))
        assertEquals(ConnectFlow.NETWORK, ConnectFlow.indexOf("Attaching to existing Wi-Fi", ::text))
        assertEquals(
            ConnectFlow.NETWORK,
            ConnectFlow.indexOf("Hotspot ready: car, 5 GHz, aa, channel 6", ::text),
        )
        assertEquals(ConnectFlow.NETWORK, ConnectFlow.indexOf("Existing Wi-Fi ready: cabin", ::text))
        assertEquals(ConnectFlow.PHONE, ConnectFlow.indexOf("Waiting for iPhone", ::text))
        assertEquals(ConnectFlow.PHONE, ConnectFlow.indexOf("Connecting Bluetooth", ::text))
        assertEquals(ConnectFlow.PHONE, ConnectFlow.indexOf("Starting AirPlay service", ::text))
        assertEquals(ConnectFlow.LINK, ConnectFlow.indexOf("Pairing with iPhone", ::text))
        assertEquals(ConnectFlow.LINK, ConnectFlow.indexOf("Connecting iAP2 control", ::text))
        assertEquals(ConnectFlow.LINK, ConnectFlow.indexOf("Wireless CarPlay control running", ::text))
        assertEquals(ConnectFlow.SCREEN, ConnectFlow.indexOf("Wireless CarPlay active", ::text))
        assertEquals(ConnectFlow.SCREEN, ConnectFlow.indexOf("Opening CarPlay", ::text))
    }

    @Test fun unknownOrBlankStageDoesNotReset() {
        assertEquals(-1, ConnectFlow.indexOf("", ::text))
        assertEquals(-1, ConnectFlow.indexOf("something else", ::text))
    }
}
