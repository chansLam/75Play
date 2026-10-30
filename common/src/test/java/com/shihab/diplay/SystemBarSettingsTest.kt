package com.shihab.diplay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SystemBarSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun harmanFullscreenPersistsWithoutSettingsUi() {
        AirPlayPersistence.saveHarmanFullscreen(context, false)
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))

        AirPlayPersistence.saveHarmanFullscreen(context, true)
        assertTrue(AirPlayPersistence.loadHarmanFullscreen(context))

        AirPlayPersistence.saveHarmanFullscreen(context, false)
        assertFalse(AirPlayPersistence.loadHarmanFullscreen(context))
    }
}
