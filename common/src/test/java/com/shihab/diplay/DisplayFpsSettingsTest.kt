package com.shihab.diplay

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DisplayFpsSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun selectableFpsStopsAtThirty() {
        AirPlayPersistence.saveFps(context, 60)
        assertEquals(30, AirPlayPersistence.loadFps(context))
        AirPlayPersistence.saveFps(context, 45)
        assertEquals(30, AirPlayPersistence.loadFps(context))
        AirPlayPersistence.saveFps(context, 15)
        assertEquals(15, AirPlayPersistence.loadFps(context))
    }

    @Test fun legacySavedHighFpsLoadsAtThirty() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .edit()
            .putInt("display_fps", 60)
            .commit()
        assertEquals(30, AirPlayPersistence.loadFps(context))
    }
}
