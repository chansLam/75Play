package com.shihab.diplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpeningPictureHoldTest {
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1)
    private val predicted = byteArrayOf(0, 0, 0, 1, 0x41, 2)

    @Test fun nothingArrivedYetSoTheOpeningKeyframeMayStillBeInFlight() {
        val hold = OpeningPictureHold(maxFrames = 4)
        val replay = hold.snapshot()
        assertTrue(replay.frames.isEmpty())
        assertFalse(replay.askKeyframe)
    }

    @Test fun aCompleteChainIsReplayedAndDoesNotAskForAnotherKeyframe() {
        val hold = OpeningPictureHold(maxFrames = 4)
        hold.accept(isKeyframe = true, copy = idr, presentNs = 10)
        hold.accept(isKeyframe = false, copy = predicted, presentNs = 20)
        val replay = hold.snapshot()
        assertEquals(listOf(10L, 20L), replay.frames.map { it.presentNs })
        assertFalse(replay.askKeyframe)
        assertTrue(hold.snapshot().frames.isEmpty())
    }

    @Test fun predictedFramesBeforeAKeyframeAskImmediately() {
        val hold = OpeningPictureHold(maxFrames = 4)
        hold.accept(isKeyframe = false, copy = predicted, presentNs = 10)
        val replay = hold.snapshot()
        assertTrue(replay.frames.isEmpty())
        assertTrue(replay.askKeyframe)
    }

    @Test fun aChainLongerThanTheCapKeepsTheKeyframeAndAsksAgain() {
        val hold = OpeningPictureHold(maxFrames = 2)
        hold.accept(isKeyframe = true, copy = idr, presentNs = 1)
        hold.accept(isKeyframe = false, copy = predicted, presentNs = 2)
        hold.accept(isKeyframe = false, copy = byteArrayOf(0, 0, 0, 1, 0x41, 3), presentNs = 3)
        val replay = hold.snapshot()
        assertEquals(1, replay.frames.size)
        assertEquals(1L, replay.frames[0].presentNs)
        assertTrue(replay.askKeyframe)
    }

    @Test fun aLaterKeyframeReplacesTheChainThatWasHeld() {
        val hold = OpeningPictureHold(maxFrames = 4)
        hold.accept(isKeyframe = true, copy = idr, presentNs = 1)
        hold.accept(isKeyframe = false, copy = predicted, presentNs = 2)
        val next = byteArrayOf(0, 0, 0, 1, 0x65, 9)
        hold.accept(isKeyframe = true, copy = next, presentNs = 3)
        val replay = hold.snapshot()
        assertEquals(1, replay.frames.size)
        assertEquals(9.toByte(), replay.frames[0].bytes[5])
        assertFalse(replay.askKeyframe)
    }

    @Test fun framesAreNotThrownAwayBeforeTheFirstPicture() {
        assertFalse(discardUnrenderedBacklog(renderedOnce = false, renderingNow = false, ageNs = 900_000_000L, maxAgeNs = 800_000_000L))
    }

    @Test fun aStallAfterThePictureWasShownStillDropsAStaleBacklog() {
        assertTrue(discardUnrenderedBacklog(renderedOnce = true, renderingNow = false, ageNs = 900_000_000L, maxAgeNs = 800_000_000L))
        assertFalse(discardUnrenderedBacklog(renderedOnce = true, renderingNow = true, ageNs = 900_000_000L, maxAgeNs = 800_000_000L))
    }
}
