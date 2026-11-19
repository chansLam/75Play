package com.shihab.diplay.host

internal object CarPlayRecoveryGate {
    @Volatile private var userDisconnected = false
    @Volatile private var iphoneDetached = false

    @Synchronized
    fun userRequestedDisconnect() {
        userDisconnected = true
    }

    @Synchronized
    fun userRequestedConnect() {
        userDisconnected = false
        iphoneDetached = false
    }

    @Synchronized
    fun iphoneDetached() {
        iphoneDetached = true
    }

    @Synchronized
    fun iphoneAttached() {
        userDisconnected = false
        iphoneDetached = false
    }

    fun isSuppressed(): Boolean = userDisconnected || iphoneDetached

    fun isIphoneDetachSuppressed(): Boolean = iphoneDetached

    internal fun resetForTests() {
        userDisconnected = false
        iphoneDetached = false
    }
}
