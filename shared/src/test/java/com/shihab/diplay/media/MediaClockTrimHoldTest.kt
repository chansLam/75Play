package com.shihab.diplay.media

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClockTrimHoldTest {
    @Test
    fun bufferedMusicSteersToTheLevelItHolds() {
        val rate = 48_000
        val start = 48_000L
        val held = start * 2
        val trim = MediaClockTrim(rate, start, measuresTarget = true)
        trim.sample(held, 0L, headMoving = true)
        trim.sample(held, 3_100_000_000L, headMoving = true)
        assertNull(trim.rateDue(3_100_000_000L))

        trim.sample(start, 4_200_000_000L, headMoving = true)
        val slowed = trim.rateDue(4_200_000_000L)
        assertTrue(slowed != null && slowed < rate)
    }

    @Test
    fun aStillPlayHeadDoesNotLockTheHeldLevel() {
        val rate = 48_000
        val trim = MediaClockTrim(rate, 48_000L, measuresTarget = true)
        trim.sample(96_000L, 0L, headMoving = false)
        trim.sample(96_000L, 3_100_000_000L, headMoving = false)
        assertNull(trim.rateDue(3_100_000_000L))
    }
}
