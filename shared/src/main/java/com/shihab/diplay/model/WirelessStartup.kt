package com.shihab.diplay.model

import java.io.IOException

/**
 * Outcome of a wireless-stack bring-up attempt. Shared between the network bring-up layer
 * (which produces the failure) and the transport / orchestration layers (which interpret it),
 * so those packages can depend on this neutral [model] package instead of importing each other.
 */
enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION, BLUETOOTH_UNUSABLE,
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
