package com.shihab.diplay.host

import android.os.Looper
import com.shihab.diplay.media.AndroidMediaSink
import com.shihab.diplay.orchestration.CarPlayController
import com.shihab.diplay.session.CarPlaySessionDisplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class CarPlayParkedSessionReleaseTest {
    @After
    fun reset() {
        CarPlayBackgroundSession.onParkedSessionLost = null
        CarPlayBackgroundSession.forceReset()
    }

    @Test
    fun parkedSessionEndReleasesOnceAndNotifiesTheShell() {
        val owner = Any()
        val controller = mock(CarPlayController::class.java)
        val sink = mock(AndroidMediaSink::class.java)
        val display = mock(CarPlaySessionDisplay::class.java)
        CarPlayBackgroundSession.store(controller, sink, 1920, 720, owner, display) { done ->
            CarPlayBackgroundSession.clear(controller)
            done()
        }
        CarPlayBackgroundSession.park(owner)
        val done = CountDownLatch(1)
        var hits = 0
        CarPlayBackgroundSession.onParkedSessionLost = {
            hits++
            done.countDown()
        }

        CarPlayBackgroundSession.releaseParked("session ended")
        CarPlayBackgroundSession.releaseParked("session ended")

        assertTrue(done.await(5, TimeUnit.SECONDS))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, hits)
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test
    fun sessionLossDeliveredBeforeParkingIsConsumedAfterParking() {
        val owner = Any()
        val controller = mock(CarPlayController::class.java)
        CarPlayBackgroundSession.store(
            controller,
            mock(AndroidMediaSink::class.java),
            1920,
            720,
            owner,
            mock(CarPlaySessionDisplay::class.java),
        ) { done ->
            CarPlayBackgroundSession.clear(controller)
            done()
        }
        val done = CountDownLatch(1)
        CarPlayBackgroundSession.onParkedSessionLost = { done.countDown() }

        CarPlayBackgroundSession.park(owner, "session ended")

        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(CarPlayBackgroundSession.hasSession())
    }

    @Test
    fun releaseDoesNotStopASessionTheHostAlreadyAdopted() {
        val owner = Any()
        CarPlayBackgroundSession.store(
            mock(CarPlayController::class.java),
            mock(AndroidMediaSink::class.java),
            1920,
            720,
            owner,
            mock(CarPlaySessionDisplay::class.java),
        ) { it() }
        var hits = 0
        CarPlayBackgroundSession.onParkedSessionLost = { hits++ }

        CarPlayBackgroundSession.releaseParked("session ended")

        assertEquals(0, hits)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }

    @Test
    fun storeRejectsAnInFlightParkedRelease() {
        val owner = Any()
        val controller = mock(CarPlayController::class.java)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        doAnswer {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            null
        }.`when`(controller).close()
        CarPlayBackgroundSession.store(
            controller,
            mock(AndroidMediaSink::class.java),
            800,
            480,
            owner,
            mock(CarPlaySessionDisplay::class.java),
        ) { it() }
        CarPlayBackgroundSession.park(owner)
        val releaser = Thread { CarPlayBackgroundSession.releaseParked("session ended") }
        releaser.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        val stored = CarPlayBackgroundSession.store(
            mock(CarPlayController::class.java),
            mock(AndroidMediaSink::class.java),
            800,
            480,
            Any(),
            mock(CarPlaySessionDisplay::class.java),
        ) { it() }

        assertFalse(stored)
        assertTrue(CarPlayBackgroundSession.isReleasing())
        release.countDown()
        releaser.join(5_000)
        val idleDeadline = System.nanoTime() + 2_000_000_000L
        while (CarPlayBackgroundSession.isReleasing() && System.nanoTime() < idleDeadline) {
            Thread.sleep(20)
        }
        assertFalse(CarPlayBackgroundSession.isReleasing())
    }

    @Test
    fun anOlderShellCannotClearTheCurrentReconnectCallback() {
        val older = Any()
        val current = Any()
        CarPlayBackgroundSession.setParkedSessionLost(older) {}
        CarPlayBackgroundSession.setParkedSessionLost(current) {}
        CarPlayBackgroundSession.clearParkedSessionLost(older)
        assertTrue(CarPlayBackgroundSession.onParkedSessionLost != null)
        CarPlayBackgroundSession.clearParkedSessionLost(current)
        assertNull(CarPlayBackgroundSession.onParkedSessionLost)
    }
}
