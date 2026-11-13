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
    fun immediateHostRejectionIsDistinctFromANakThatWaited() {
        assertTrue(NcmSessionHealth.immediateRejection(0))
        assertFalse(NcmSessionHealth.immediateRejection(51))
    }

    @Test
    fun inboundSilenceBeforeThePhoneMarksTheLinkUpIsNotAFailure() {
        val stall = NcmReadStall(stallNanos = 8_000)
        assertEquals(0L, stall.fatalQuietNanos(nowNs = 9_000, networkConnected = false, inboundSeen = true, lastFrameNs = 100))
    }

    @Test
    fun linkUpStartsTheSilenceClockInsteadOfUsingTheOldDiscoveryFrame() {
        val stall = NcmReadStall(stallNanos = 8_000)
        assertEquals(0L, stall.fatalQuietNanos(nowNs = 20_000, networkConnected = true, inboundSeen = true, lastFrameNs = 100))
        assertEquals(0L, stall.fatalQuietNanos(nowNs = 27_000, networkConnected = true, inboundSeen = true, lastFrameNs = 100))
        assertEquals(8_000L, stall.fatalQuietNanos(nowNs = 28_000, networkConnected = true, inboundSeen = true, lastFrameNs = 100))
    }

    @Test
    fun silenceAfterLiveTrafficFailsOnceTheLinkIsUp() {
        val stall = NcmReadStall(stallNanos = 8_000)
        assertEquals(0L, stall.fatalQuietNanos(nowNs = 1_000, networkConnected = true, inboundSeen = true, lastFrameNs = 900))
        assertEquals(0L, stall.fatalQuietNanos(nowNs = 8_800, networkConnected = true, inboundSeen = true, lastFrameNs = 1_000))
        assertEquals(8_000L, stall.fatalQuietNanos(nowNs = 9_000, networkConnected = true, inboundSeen = true, lastFrameNs = 1_000))
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
