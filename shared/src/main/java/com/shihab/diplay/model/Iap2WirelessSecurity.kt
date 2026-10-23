package com.shihab.diplay.model

/**
 * Wireless 0x5703 security values. Used by both the transport control client that builds the
 * wire frame and the network layer that configures the SoftAP, so it lives in the neutral
 * [model] package rather than being imported across the transport/network package boundary.
 */
enum class Iap2WirelessSecurity(val wireValue: Int) {
    NONE(0),
    WEP(1),
    WPA_WPA2(2),
    WPA3_TRANSITION(3),
    WPA3_ONLY(4),
}
