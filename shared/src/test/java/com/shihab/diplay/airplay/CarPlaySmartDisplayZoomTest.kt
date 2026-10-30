package com.shihab.diplay.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class CarPlaySmartDisplayZoomTest {
    @Test fun mediumAndSmallKeepTheirWidthSoSmartDisplayZoomStaysAvailable() {
        val medium = AirPlayDisplayConfig(
            widthPixels = 1920,
            heightPixels = 720,
            widthPhysicalMm = 300,
            heightPhysicalMm = 113,
        )
        val small = AirPlayDisplayConfig(
            widthPixels = 1920,
            heightPixels = 720,
            widthPhysicalMm = 350,
            heightPhysicalMm = 131,
        )
        assertEquals(medium, CarPlaySmartDisplayZoom.force(medium))
        assertEquals(small, CarPlaySmartDisplayZoom.force(small))
    }

    @Test fun largeControlsAreNotRewrittenIntoTheZoomReport() {
        val large = AirPlayDisplayConfig(
            widthPixels = 1920,
            heightPixels = 720,
            widthPhysicalMm = 250,
            heightPhysicalMm = 94,
        )
        assertEquals(large, CarPlaySmartDisplayZoom.force(large))
    }

    @Test fun aPhoneThatAlreadyClearsTheDensityIsUnchanged() {
        val phone = AirPlayDisplayConfig(
            widthPixels = 2400,
            heightPixels = 1080,
            widthPhysicalMm = 300,
            heightPhysicalMm = 135,
        )
        assertEquals(phone, CarPlaySmartDisplayZoom.force(phone))
    }

    @Test fun aShortWindowIsEnlargedUntilTheReportQualifies() {
        val reported = CarPlaySmartDisplayZoom.force(
            AirPlayDisplayConfig(
                widthPixels = 800,
                heightPixels = 480,
                widthPhysicalMm = 160,
                heightPhysicalMm = 96,
            ),
        )
        assertTrue(reported.widthPixels > 800)
        assertTrue(reported.widthPixels % 2 == 0)
        assertTrue(reported.heightPixels % 2 == 0)
        assertQualifies(reported)
    }

    @Test fun advertisedFrameRateIsAtLeastSixty() {
        assertEquals(60, CarPlaySmartDisplayZoom.advertisedFps(15))
        assertEquals(60, CarPlaySmartDisplayZoom.advertisedFps(30))
        assertEquals(60, CarPlaySmartDisplayZoom.advertisedFps(60))
    }

    private fun assertQualifies(display: AirPlayDisplayConfig) {
        val widthMm = display.widthPhysicalMm!!
        val heightMm = display.heightPhysicalMm!!
        val ppi = display.widthPixels * 25.4 / widthMm
        assertTrue(ppi >= CarPlaySmartDisplayZoom.TARGET_PPI - 1)
        assertTrue(hypot(widthMm.toDouble(), heightMm.toDouble()) >= CarPlaySmartDisplayZoom.MIN_DIAGONAL_MM - 1)
    }
}
