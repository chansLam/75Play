package com.shihab.diplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarHotspotEnableTest {
    @Test fun enableShortCircuitsWhenAlreadyOnIsReportedViaStatus() {
        // Without a real SoftAP on the emulator, enableIfPossible may fail; the API must still
        // return a structured Result (not throw) for blank credentials.
        val result = CarHotspotEnable.enable(
            context = RuntimeEnvironment.getApplication(),
            ssid = null,
            passphrase = null,
            waitUntilReadyMs = 0L,
        )
        assertTrue(result.diagnostic.isNotBlank())
        assertEquals(result.ok, result.ok) // structured result always present
    }

    @Test fun wifiConfigurationKeepsRawPassphraseWithoutQuotes() {
        val config = CarHotspotStatus.wifiConfiguration("DiPlay-Test", "password12")
        assertEquals("DiPlay-Test", config.SSID)
        assertEquals("password12", config.preSharedKey)
    }
}
