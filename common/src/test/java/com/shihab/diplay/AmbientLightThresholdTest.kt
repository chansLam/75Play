package com.shihab.diplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientLightThresholdTest {
    @Test fun acceptsThresholdsWithinTheSupportedRange() {
        for (lux in listOf(0, 80, 255)) assertTrue(AmbientLightThreshold.isValid(lux))
    }

    @Test fun rejectsOutOfRangeThresholdsAndFallsBackToDefaultBrightness() {
        for (lux in listOf(-1, 256, 200_001, Int.MAX_VALUE)) {
            assertFalse(AmbientLightThreshold.isValid(lux))
            assertEquals(AmbientLightThreshold(), AmbientLightThreshold.fromStored(lux))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdCannotReachTheControllerOrPersistence() {
        AmbientLightThreshold(256)
    }
}
