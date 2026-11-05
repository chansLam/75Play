package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmWiredPolicyTest {
    @Test
    fun parsesNegotiatedNtbLimitsAndRejectsImplausibleSize() {
        val buffer = ByteArray(28)
        putU32(buffer, 16, 4096)
        putU16(buffer, 24, 4)
        putU16(buffer, 26, 16)

        val parsed = NcmNtbLimits.parse(buffer, buffer.size)

        assertEquals(NcmNtbLimits(4096, 4, 16), parsed)
        assertNull(NcmNtbLimits.parse(buffer, 10))
        putU32(buffer, 16, 0)
        assertNull(NcmNtbLimits.parse(buffer, buffer.size))
    }

    @Test
    fun batchStopsBeforeConfiguredNtbCapacity() {
        val limits = NcmNtbLimits(maxBlockBytes = 64, alignment = 4, maxDatagrams = 8)
        val frames = listOf(ByteArray(10), ByteArray(10), ByteArray(40))

        val taken = NcmTxBatch.take(frames, limits)

        assertTrue(taken.size < frames.size)
        assertTrue(Ntb16Codec.encodedLength(taken, limits.alignment) <= limits.maxBlockBytes)
        assertTrue(NcmTxBatch.take(listOf(ByteArray(80)), limits).isEmpty())
    }

    @Test
    fun fullJavaQueueIsBackpressureRatherThanACompletedUrb() {
        val queued = ArrayDeque<ByteArray>()
        val capacity = 2
        val accepted = BooleanArray(3) { index ->
            if (queued.size >= capacity) false else queued.add(byteArrayOf(index.toByte()))
        }

        assertEquals(listOf(true, true, false), accepted.toList())
        assertEquals(2, queued.size)
    }

    @Test
    fun noCompletionDiscardsAndUnreapedCancelRequestsReplug() {
        val live = NcmOutSnapshot(
            queued = 0, submitted = 2, completed = 1, cancelling = 0, reclaimFailed = 0,
            stall = 0, nak = 1, noCompletion = 0, fatalSubmit = 0, latencyMs = 4,
            replug = false, successBytes = 100,
        )
        val silent = live.copy(noCompletion = 1)
        val partial = live.copy(fatalSubmit = 1)
        val wedged = silent.copy(cancelling = 1, reclaimFailed = 1, replug = true)

        assertEquals(NcmOutAction.CONTINUE, NcmSessionHealth.decide(live))
        assertEquals(NcmOutAction.DISCARD, NcmSessionHealth.decide(silent))
        assertEquals(NcmOutAction.DISCARD, NcmSessionHealth.decide(partial))
        assertEquals(NcmOutAction.REPLUG, NcmSessionHealth.decide(wedged))
        assertTrue(NcmSessionHealth.immediateRejection(0))
        assertFalse(NcmSessionHealth.immediateRejection(51))
        assertTrue(NcmSessionHealth.immediateReplug(elapsedMs = 0, outHasSucceeded = true))
        assertFalse(NcmSessionHealth.immediateReplug(elapsedMs = 0, outHasSucceeded = false))
        assertFalse(NcmSessionHealth.immediateReplug(elapsedMs = 51, outHasSucceeded = true))
        assertFalse(NcmSessionHealth.immediateReplug(elapsedMs = 800, outHasSucceeded = true))
        assertEquals(silent, NcmOutSnapshot.parse(
            "queued=0 submitted=2 completed=1 cancelling=0 reclaimFailed=0 stall=0 nak=1 " +
                "noCompletion=1 fatalSubmit=0 latencyMs=4 replug=0 successBytes=100",
        ))
    }

    @Test
    fun partialNativeSubmitIsFatalRatherThanRetryableBackpressure() {
        assertTrue(nativeSubmitResult(-1, 0) === NcmSubmit.FatalPartialSubmit)
        assertTrue(nativeSubmitResult(0, 0) === NcmSubmit.Backpressured)
        assertTrue(nativeSubmitResult(32768, 32768) is NcmSubmit.Accepted)
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        putU16(target, offset, value)
        putU16(target, offset + 2, value ushr 16)
    }
}
