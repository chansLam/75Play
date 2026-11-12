package com.shihab.diplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanoutGateTest {
    @Test fun steady60FpsShowsEveryFrame() {
        val gate = ScanoutGate()
        var now = 1_000_000_000L
        repeat(8) {
            assertTrue(showOnScanout(gate, now, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
            now += 16_666_666L
        }
    }

    @Test fun aSecondReleaseInsideOneRefreshIsNotShown() {
        val gate = ScanoutGate()
        assertTrue(showOnScanout(gate, 0, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
        assertFalse(showOnScanout(gate, 2_000_000L, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
        assertTrue(showOnScanout(gate, 16_000_000L, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
    }

    @Test fun burstShowsTheNewestFrameAndDropsTheOneAlreadyWaiting() {
        val gate = ScanoutGate()
        assertTrue(showOnScanout(gate, 0, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
        assertFalse(
            "the older frame of a pair must not reach the scanout",
            showOnScanout(gate, 20_000_000L, toScreen = true, catchUp = false, newerReady = true, collapseBursts = true),
        )
        assertTrue(
            "the newer frame is the one that should be shown",
            showOnScanout(gate, 20_000_000L, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true),
        )
    }

    @Test fun droppingAnOlderFrameDoesNotConsumeTheRefresh() {
        val gate = ScanoutGate()
        assertFalse(showOnScanout(gate, 0, toScreen = true, catchUp = false, newerReady = true, collapseBursts = true))
        assertTrue(showOnScanout(gate, 1_000_000L, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
    }

    @Test fun catchUpAndOffScreenFramesAreNotShown() {
        val gate = ScanoutGate()
        assertFalse(showOnScanout(gate, 0, toScreen = true, catchUp = true, newerReady = false, collapseBursts = true))
        assertFalse(showOnScanout(gate, 0, toScreen = false, catchUp = false, newerReady = false, collapseBursts = true))
        assertTrue(showOnScanout(gate, 0, toScreen = true, catchUp = false, newerReady = false, collapseBursts = true))
    }

    @Test fun devicesThatDoNotCollapseBurstsShowEveryFrame() {
        val gate = ScanoutGate()
        assertTrue(showOnScanout(gate, 0, toScreen = true, catchUp = false, newerReady = false, collapseBursts = false))
        assertTrue(showOnScanout(gate, 1_000_000L, toScreen = true, catchUp = false, newerReady = false, collapseBursts = false))
    }
}
