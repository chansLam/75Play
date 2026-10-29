package com.shihab.diplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderStallRebuildTrackerTest {
    @Test
    fun firstTwoStallsDoNotForceThirdDoes() {
        val tracker = DecoderStallRebuildTracker()
        assertFalse(tracker.onStall())
        assertFalse(tracker.onStall())
        assertTrue(tracker.onStall())
    }

    @Test
    fun forcingResetsSoNextCycleNeedsThreeMore() {
        val tracker = DecoderStallRebuildTracker()
        tracker.onStall()
        tracker.onStall()
        assertTrue(tracker.onStall())
        assertFalse(tracker.onStall())
        assertFalse(tracker.onStall())
        assertTrue(tracker.onStall())
    }

    @Test
    fun renderedFrameBreaksTheSequence() {
        val tracker = DecoderStallRebuildTracker()
        tracker.onStall()
        tracker.onStall()
        tracker.onOutput()
        assertFalse(tracker.onStall())
        assertFalse(tracker.onStall())
        assertTrue(tracker.onStall())
    }

    @Test
    fun resetClearsPartialCount() {
        val tracker = DecoderStallRebuildTracker()
        tracker.onStall()
        tracker.onStall()
        tracker.reset()
        assertFalse(tracker.onStall())
        assertFalse(tracker.onStall())
        assertTrue(tracker.onStall())
    }

    @Test
    fun thresholdIsThree() {
        assertEquals(3, DecoderStallRebuildTracker.REBUILD_AFTER_STALLS)
    }
}
