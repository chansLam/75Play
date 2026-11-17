package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class WiredNcmTuningTest {
    @Test fun disconnectedOrUnsetConnectionDoesNotApplySettleWindow() {
        assertEquals(0L, networkConnectionSettleRemainingMillis(false, 1_000L, 1_000L, 1_000L))
        assertEquals(0L, networkConnectionSettleRemainingMillis(true, 0L, 1_000L, 1_000L))
        assertEquals(0L, networkConnectionSettleRemainingMillis(true, 1_000L, 1_000L, 0L))
    }

    @Test fun settleWindowBlocksUntilTheFullDelayHasElapsed() {
        val connectedAt = 10_000_000L
        assertEquals(1_000L, networkConnectionSettleRemainingMillis(true, connectedAt, connectedAt, 1_000L))
        assertEquals(1L, networkConnectionSettleRemainingMillis(
            true, connectedAt, connectedAt + 999_000_000L, 1_000L,
        ))
        assertEquals(0L, networkConnectionSettleRemainingMillis(
            true, connectedAt, connectedAt + 1_000_000_000L, 1_000L,
        ))
        assertEquals(0L, networkConnectionSettleRemainingMillis(
            true, connectedAt, connectedAt + 2_000_000_000L, 1_000L,
        ))
    }

    @Test fun monotonicTimestampRegressionFailsClosed() {
        assertEquals(1_000L, networkConnectionSettleRemainingMillis(
            true, connectedAtNanos = 2_000_000L, nowNanos = 1_000_000L, settleMillis = 1_000L,
        ))
    }
}
