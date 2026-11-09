package com.shihab.diplay.host

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAbsencePolicyTest {
    @Test
    fun wirelessPreferenceDoesNotHideAnUnpluggedWiredLink() {
        assertTrue(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = true,
                usbPresent = false,
                wirelessPreferred = true,
                sessionOrConnectActive = true,
                wiredLinkActive = true,
            ),
        )
    }

    @Test
    fun unpluggingACableLeavesAWirelessSessionAlone() {
        assertFalse(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = true,
                usbPresent = false,
                wirelessPreferred = true,
                sessionOrConnectActive = true,
                wiredLinkActive = false,
            ),
        )
    }

    @Test
    fun usbOnlyModeDropsAnyLiveAttemptWhenTheCableGoes() {
        assertTrue(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = true,
                usbPresent = false,
                wirelessPreferred = false,
                sessionOrConnectActive = true,
                wiredLinkActive = false,
            ),
        )
    }

    @Test
    fun aSteadyOrIdleUsbStateDoesNotDropTheLink() {
        assertFalse(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = false,
                usbPresent = false,
                wirelessPreferred = true,
                sessionOrConnectActive = true,
                wiredLinkActive = true,
            ),
        )
        assertFalse(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = true,
                usbPresent = true,
                wirelessPreferred = false,
                sessionOrConnectActive = true,
                wiredLinkActive = true,
            ),
        )
        assertFalse(
            shouldDropLinkAfterUsbLoss(
                usbWasPresent = true,
                usbPresent = false,
                wirelessPreferred = false,
                sessionOrConnectActive = false,
                wiredLinkActive = false,
            ),
        )
    }

    @Test
    fun recoveryStartsWithoutACableOnlyWhenWirelessIsAllowed() {
        assertTrue(shouldStartRecovery(wirelessPreferred = true, usbPresent = false))
        assertTrue(shouldStartRecovery(wirelessPreferred = false, usbPresent = true))
        assertFalse(shouldStartRecovery(wirelessPreferred = false, usbPresent = false))
    }
}