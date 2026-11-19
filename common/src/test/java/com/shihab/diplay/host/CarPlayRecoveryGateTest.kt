package com.shihab.diplay.host

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayRecoveryGateTest {
    @After
    fun reset() {
        CarPlayRecoveryGate.resetForTests()
    }

    @Test
    fun userDisconnectBlocksRecoveryUntilExplicitConnect() {
        CarPlayRecoveryGate.userRequestedDisconnect()

        assertTrue(CarPlayRecoveryGate.isSuppressed())
        assertFalse(CarPlayRecoveryGate.isIphoneDetachSuppressed())

        CarPlayRecoveryGate.userRequestedConnect()

        assertFalse(CarPlayRecoveryGate.isSuppressed())
    }

    @Test
    fun usbDetachBlocksRecoveryUntilThePhoneIsAttachedAgain() {
        CarPlayRecoveryGate.iphoneDetached()

        assertTrue(CarPlayRecoveryGate.isSuppressed())
        assertTrue(CarPlayRecoveryGate.isIphoneDetachSuppressed())

        CarPlayRecoveryGate.iphoneAttached()

        assertFalse(CarPlayRecoveryGate.isSuppressed())
    }

    @Test
    fun attachingAfterManualDisconnectAllowsAFreshSession() {
        CarPlayRecoveryGate.userRequestedDisconnect()
        CarPlayRecoveryGate.iphoneDetached()
        CarPlayRecoveryGate.iphoneAttached()

        assertFalse(CarPlayRecoveryGate.isSuppressed())
    }
}
