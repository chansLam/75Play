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

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        putU16(target, offset, value)
        putU16(target, offset + 2, value ushr 16)
    }
}
